#!/usr/bin/env python3
"""Programmatic read-only inventory extractor for the ADTNM repository.

Scans every file in ADTNM (at commit d4a159f) to record:
- exact file sizes, total lines, non-comment LOC
- Java packages, imports, classes/interfaces/enums, methods + exact line ranges
- DtnmaConfig parameters, defaults, clamp ranges
- EddTelemetrySampler EDD constants, ControlAction types, DtnmaPolicyProfile presets
- Declarative rule files (rules_*.txt) and ONE scenarios (*.txt)
- Python scripts & functions, DtnmaUnitTests cases, patches, and CI files

Writes:
- docs/study-guide/data/code_inventory.json
- docs/study-guide/00_inventory.md
"""

from __future__ import annotations

import ast
import json
import re
import subprocess
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
GUIDE_DIR = SCRIPT_DIR.parent
ADTNM_ROOT = GUIDE_DIR.parent.parent
DATA_DIR = GUIDE_DIR / "data"

DOC_DATE = "2026-11-25"
FREEZE_TAG = "v1.0-tnsm-freeze"
FREEZE_TAG_OBJ = "c820752c27683f070ec72664c68bb0112a91d1b8"
FREEZE_COMMIT = "ca5d9acccf6b4f606df5a586ecb80d4842179e4d"
EXP_CAMPAIGN = "v1.0-exp-controls"


def run_git(args: List[str]) -> str:
    res = subprocess.run(
        ["git", *args], cwd=str(ADTNM_ROOT), capture_output=True, text=True, check=False
    )
    return res.stdout.strip()


def count_noncomment_loc(text: str, ext: str) -> int:
    count = 0
    in_block = False
    for raw in text.splitlines():
        s = raw.strip()
        if ext == ".java":
            if in_block:
                if "*/" in s:
                    in_block = False
                continue
            if not s or s.startswith("//"):
                continue
            if s.startswith("/*"):
                if "*/" not in s:
                    in_block = True
                continue
            count += 1
        elif ext == ".py":
            if not s or s.startswith("#"):
                continue
            count += 1
        else:
            if not s or s.startswith("#") or s.startswith("//"):
                continue
            count += 1
    return count


def parse_java_structure(text: str) -> Dict[str, Any]:
    lines = text.splitlines()
    imports: List[str] = []
    types: List[Dict[str, Any]] = []
    methods: List[Dict[str, Any]] = []

    type_re = re.compile(
        r"^\s*(?:public\s+|protected\s+|private\s+)?(?:abstract\s+|static\s+|final\s+)*"
        r"(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)"
        r"(?:\s+extends\s+([A-Za-z0-9_<>.,\s]+?))?"
        r"(?:\s+implements\s+([A-Za-z0-9_<>.,\s]+?))?\s*\{"
    )
    meth_re = re.compile(
        r"^\s*(?:public|protected|private)\s+(?:static\s+|final\s+|synchronized\s+|abstract\s+)*"
        r"(?:<[^>]+>\s+)?"
        r"(?:([A-Za-z0-9_<>\[\].,?]+)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)"
    )

    brace_depth = 0
    block_comment = False
    stack: List[Tuple[str, Dict[str, Any], int]] = []

    for idx, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("import "):
            imports.append(stripped.rstrip(";").replace("import ", "").strip())

        if block_comment:
            if "*/" in line:
                block_comment = False
            continue
        if stripped.startswith("/*"):
            if "*/" not in line:
                block_comment = True
            continue
        if stripped.startswith("//"):
            continue

        # strip string literals and inline comments before brace count
        clean = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
        clean = re.sub(r"//.*$", "", clean)

        if brace_depth <= 1:
            tm = type_re.match(clean)
            if tm:
                entry = {
                    "kind": tm.group(1),
                    "name": tm.group(2),
                    "extends": (tm.group(3) or "").strip(),
                    "implements": (tm.group(4) or "").strip(),
                    "start_line": idx,
                    "end_line": idx,
                }
                types.append(entry)
                stack.append(("type", entry, brace_depth + clean.count("{") - clean.count("}")))

        if brace_depth in (1, 2):
            # multi-line signature check: concatenate next line if '(' without ')'
            sig = clean
            if "(" in sig and ")" not in sig and idx < len(lines):
                sig = sig + " " + re.sub(r"//.*$", "", lines[idx].strip())
            mm = meth_re.match(sig)
            if mm and not stripped.startswith(("if ", "for ", "while ", "switch ", "catch ", "return ", "throw ", "new ")):
                ret = (mm.group(1) or "<ctor>").strip()
                mname = mm.group(2).strip()
                params = re.sub(r"\s+", " ", mm.group(3).strip())
                if ";" in sig and "{" not in sig and "interface" not in (types[0]["kind"] if types else ""):
                    pass
                else:
                    entry = {
                        "name": mname,
                        "return_type": ret,
                        "params": params,
                        "start_line": idx,
                        "end_line": idx,
                    }
                    methods.append(entry)
                    if "{" in sig:
                        delta = clean.count("{") - clean.count("}")
                        stack.append(("method", entry, brace_depth + delta))

        brace_depth += clean.count("{") - clean.count("}")
        while stack and brace_depth < stack[-1][2]:
            _, item, _ = stack.pop()
            item["end_line"] = idx

    for _, item, _ in stack:
        item["end_line"] = len(lines)

    # Check single-line interface methods or semicolon abstract methods
    for m in methods:
        if m["end_line"] < m["start_line"]:
            m["end_line"] = m["start_line"]

    return {"imports": imports, "types": types, "methods": methods}


def parse_python_structure(text: str) -> Dict[str, Any]:
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return {"functions": [], "classes": []}
    funcs: List[Dict[str, Any]] = []
    classes: List[Dict[str, Any]] = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef):
            funcs.append({
                "name": node.name,
                "start_line": node.lineno,
                "end_line": getattr(node, "end_lineno", node.lineno),
            })
        elif isinstance(node, ast.ClassDef):
            classes.append({
                "name": node.name,
                "start_line": node.lineno,
                "end_line": getattr(node, "end_lineno", node.lineno),
            })
    return {"functions": funcs, "classes": classes}


def parse_scenario(path: Path) -> Dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    settings: Dict[str, Any] = {}
    swept: List[Dict[str, Any]] = []
    for i, raw in enumerate(text.splitlines(), 1):
        s = raw.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = [x.strip() for x in s.split("=", 1)]
        if v.startswith("[") and v.endswith("]"):
            items = [x.strip() for x in v[1:-1].split(";") if x.strip()]
            settings[k] = items
            if len(items) > 1:
                swept.append({"key": k, "count": len(items), "values": items, "line": i})
        else:
            settings[k] = v
    total_runs = 1
    for sw in swept:
        total_runs *= sw["count"]
    return {
        "scenario_name": settings.get("Scenario.name", ""),
        "end_time": settings.get("Scenario.endTime", ""),
        "update_interval": settings.get("Scenario.updateInterval", ""),
        "router": settings.get("Group.router", ""),
        "seeds": settings.get("MovementModel.rngSeed", ""),
        "rules_file": settings.get("DtnmaRouter.rulesFile", settings.get("Group.rulesFile", "")),
        "report_dir": settings.get("Report.reportDir", ""),
        "swept": swept,
        "total_runs": total_runs,
    }


def parse_rules(path: Path) -> List[Dict[str, Any]]:
    text = path.read_text(encoding="utf-8", errors="ignore")
    rules: List[Dict[str, Any]] = []
    cur: Optional[Dict[str, Any]] = None
    for idx, raw in enumerate(text.splitlines(), 1):
        s = raw.strip()
        if not s or s.startswith("#") or s.startswith("//"):
            continue
        if s.startswith("RULE:"):
            if cur:
                rules.append(cur)
            parts = [p.strip() for p in s[5:].split("|")]
            ari = parts[0]
            prio = "10"
            desc = ""
            for p in parts[1:]:
                if p.upper().startswith("PRIORITY:"):
                    prio = p.split(":", 1)[1].strip()
                elif p.upper().startswith("DESC:"):
                    desc = p.split(":", 1)[1].strip()
            cur = {"ari": ari, "priority": int(prio), "desc": desc, "line": idx, "if": "", "then": ""}
        elif s.startswith("IF:") and cur:
            cur["if"] = s[3:].strip()
        elif s.startswith("THEN:") and cur:
            cur["then"] = s[5:].strip()
    if cur:
        rules.append(cur)
    return rules


def parse_config_keys(config_path: Path) -> List[Dict[str, Any]]:
    text = config_path.read_text(encoding="utf-8", errors="ignore")
    keys: List[Dict[str, Any]] = []
    field_re = re.compile(
        r"^\s*public\s+(?!static|final)([A-Za-z0-9_.<>]+)\s+([A-Za-z0-9_]+)\s*=\s*([^;]+);"
    )
    clamp_re = re.compile(r"^\s*([A-Za-z0-9_]+)\s*=\s*(Math\.[^;]+);")
    clamps: Dict[str, str] = {}
    for idx, raw in enumerate(text.splitlines(), 1):
        cm = clamp_re.match(raw)
        if cm:
            clamps[cm.group(1)] = cm.group(2).strip()
    for idx, raw in enumerate(text.splitlines(), 1):
        fm = field_re.match(raw)
        if fm:
            name = fm.group(2)
            keys.append({
                "key": name,
                "type": fm.group(1),
                "default": fm.group(3).strip(),
                "line": idx,
                "clamp": clamps.get(name, "none"),
            })
    return keys


def parse_unit_tests(tests_path: Path) -> List[Dict[str, Any]]:
    text = tests_path.read_text(encoding="utf-8", errors="ignore")
    checks: List[Dict[str, Any]] = []
    chk_re = re.compile(r'"(Test\d+[a-z]?:\s*[^"]+)"')
    for idx, raw in enumerate(text.splitlines(), 1):
        m = chk_re.search(raw)
        if m:
            checks.append({"label": m.group(1), "line": idx})
    return checks


def main() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    head_full = run_git(["rev-parse", "HEAD"])
    head_short = run_git(["rev-parse", "--short", "HEAD"])
    branch = run_git(["branch", "--show-current"])

    files_inv: List[Dict[str, Any]] = []
    all_files = sorted(
        p for p in ADTNM_ROOT.rglob("*")
        if p.is_file()
        and ".git" not in p.parts
        and "__pycache__" not in p.parts
        and "docs/study-guide" not in p.relative_to(ADTNM_ROOT).as_posix()
    )

    for p in all_files:
        rel = p.relative_to(ADTNM_ROOT).as_posix()
        ext = p.suffix.lower()
        size_b = p.stat().st_size
        is_text = ext in {".java", ".py", ".txt", ".md", ".json", ".yml", ".yaml", ".bat", ".sh", ".tex", ".patch", ""} or p.name in {".gitignore", "LICENSE"}
        total_lines = 0
        noncomment = 0
        meta: Dict[str, Any] = {}
        if is_text:
            text = p.read_text(encoding="utf-8", errors="ignore")
            total_lines = len(text.splitlines())
            noncomment = count_noncomment_loc(text, ext)
            if ext == ".java":
                meta = parse_java_structure(text)
            elif ext == ".py":
                meta = parse_python_structure(text)
            elif rel.startswith("scenarios/rules_"):
                meta = {"rules": parse_rules(p)}
            elif rel.startswith("scenarios/") and ext == ".txt" and rel != "scenarios/dtnma_params_default.txt":
                meta = {"scenario": parse_scenario(p)}

        files_inv.append({
            "path": rel,
            "ext": ext,
            "size_bytes": size_b,
            "total_lines": total_lines,
            "noncomment_loc": noncomment,
            "meta": meta,
        })

    cfg_keys = parse_config_keys(ADTNM_ROOT / "src/routing/DtnmaConfig.java")
    unit_checks = parse_unit_tests(ADTNM_ROOT / "tests/DtnmaUnitTests.java")

    inv = {
        "documented_commit": head_full,
        "documented_commit_short": head_short,
        "branch": branch,
        "freeze_tag": FREEZE_TAG,
        "freeze_tag_object": FREEZE_TAG_OBJ,
        "freeze_commit": FREEZE_COMMIT,
        "exploratory_campaign": EXP_CAMPAIGN,
        "doc_date": DOC_DATE,
        "file_count": len(files_inv),
        "files": files_inv,
        "config_keys": cfg_keys,
        "unit_checks": unit_checks,
    }

    json_path = DATA_DIR / "code_inventory.json"
    json_path.write_text(json.dumps(inv, indent=2), encoding="utf-8")

    # Write 00_inventory.md
    md: List[str] = [
        "# Phase 0 Repository Inventory (`00_inventory.md`)",
        "",
        f"> **Documented Commit:** `{head_short}` (`{head_full}`) on branch `{branch}` | "
        f"**Frozen Tag:** `{FREEZE_TAG}` (`{FREEZE_TAG_OBJ[:8]}` -> `{FREEZE_COMMIT[:7]}`) | "
        f"**Exploratory Campaign:** `{EXP_CAMPAIGN}` | **Generated:** `{DOC_DATE}` by `docs/study-guide/tools/extract_code_inventory.py`",
        "",
        f"Total non-study-guide repository files scanned at `{head_short}`: **`{len(files_inv)}`**.",
        "",
        "## 1. File Inventory (Path, Total Lines, Non-Comment LOC, Size)",
        "",
        "| Path | Ext | Total Lines | Non-Comment LOC | Bytes | Types / Functions |",
        "|---|---|---:|---:|---:|---|",
    ]
    for f in files_inv:
        m = f["meta"]
        summary_syms = []
        if "types" in m:
            summary_syms.extend(f"{t['kind']} `{t['name']}` ({t['start_line']}-{t['end_line']})" for t in m["types"])
        if "functions" in m:
            summary_syms.append(f"{len(m['functions'])} py defs")
        if "rules" in m:
            summary_syms.append(f"{len(m['rules'])} rules")
        if "scenario" in m and m["scenario"].get("total_runs"):
            summary_syms.append(f"{m['scenario']['total_runs']} runs")
        md.append(
            f"| `{f['path']}` | `{f['ext'] or 'none'}` | {f['total_lines']} | {f['noncomment_loc']} | {f['size_bytes']} | {', '.join(summary_syms[:4])} |"
        )

    md.extend([
        "",
        "## 2. Java Source Line Totals (`src/routing/` + `src/report/`)",
        "",
        "| Category | Files | Total Lines | Non-Comment LOC |",
        "|---|---:|---:|---:|",
    ])
    routing_files = [f for f in files_inv if f["path"].startswith("src/routing/")]
    report_files = [f for f in files_inv if f["path"].startswith("src/report/")]
    md.append(f"| `src/routing/*.java` | {len(routing_files)} | {sum(x['total_lines'] for x in routing_files)} | {sum(x['noncomment_loc'] for x in routing_files)} |")
    md.append(f"| `src/report/*.java` | {len(report_files)} | {sum(x['total_lines'] for x in report_files)} | {sum(x['noncomment_loc'] for x in report_files)} |")

    md.extend([
        "",
        f"## 3. `DtnmaConfig.java` Parameter Inventory ({len(cfg_keys)} Tunable Fields)",
        "",
        "| Key | Type | Default | Clamp Rule | Line |",
        "|---|---|---|---|---:|",
    ])
    for ck in cfg_keys:
        md.append(f"| `{ck['key']}` | `{ck['type']}` | `{ck['default']}` | `{ck['clamp']}` | `src/routing/DtnmaConfig.java:{ck['line']}` |")

    md.extend([
        "",
        "## 4. Scenario Catalog Extracted from `scenarios/*.txt`",
        "",
        "| File | EndTime (s) | UpdateInterval (s) | Total Runs | Rules File | Report Dir |",
        "|---|---:|---:|---:|---|---|",
    ])
    for f in files_inv:
        sc = f["meta"].get("scenario")
        if sc:
            md.append(
                f"| `{f['path']}` | {sc['end_time']} | {sc['update_interval']} | {sc['total_runs']} | `{sc['rules_file'] or 'default'}` | `{sc['report_dir']}` |"
            )

    md.extend([
        "",
        f"## 5. `DtnmaUnitTests.java` Check Catalog ({len(unit_checks)} `check()` Assertions)",
        "",
        "| # | Assertion Label | Location |",
        "|---:|---|---|",
    ])
    for i, uc in enumerate(unit_checks, 1):
        md.append(f"| {i} | `{uc['label']}` | `tests/DtnmaUnitTests.java:{uc['line']}` |")

    out_md = GUIDE_DIR / "00_inventory.md"
    out_md.write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"Wrote {json_path} and {out_md} ({len(files_inv)} files, {len(cfg_keys)} config keys, {len(unit_checks)} unit checks)")


if __name__ == "__main__":
    main()
