#!/usr/bin/env python3
"""Automated 9 Quality Gates Verification Suite for ADTNM Study Guide & README.

All 9 gates must pass with exit code 0 before final commit/handoff.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List, Tuple

import fitz

SCRIPT_DIR = Path(__file__).resolve().parent
GUIDE_DIR = SCRIPT_DIR.parent
ADTNM_ROOT = GUIDE_DIR.parent.parent
THE_ONE_DIR = ADTNM_ROOT.parent / "the-one"
LEDGER_PATH = GUIDE_DIR / "data" / "provenance_ledger.json"

BANNED_RE = re.compile(r"\b(certified|compliant|airtight|proven|rfc-compliant)\b", re.I)


def collect_authored_md() -> List[Path]:
    files = sorted(GUIDE_DIR.rglob("*.md"))
    sup = ADTNM_ROOT / "docs" / "archive" / "README_SUPERSEDED.md"
    if sup.exists():
        files.append(sup)
    rd = ADTNM_ROOT / "README.md"
    if rd.exists():
        files.append(rd)
    return files


def gate1_links() -> Tuple[bool, str]:
    missing = []
    # Match [text](target) and ![alt](target)
    link_re = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
    for md in collect_authored_md():
        txt = md.read_text(encoding="utf-8")
        # Strip fenced code blocks before link check
        clean_lines = []
        in_code = False
        for l in txt.splitlines():
            if l.strip().startswith("```"):
                in_code = not in_code
                continue
            if not in_code:
                clean_lines.append(l)
        clean_txt = "\n".join(clean_lines)
        for m in link_re.finditer(clean_txt):
            tgt = m.group(1).strip()
            if tgt.startswith(("http://", "https://", "mailto:")):
                continue
            if tgt.startswith("#"):
                continue
            if tgt.startswith("file:///"):
                p = Path(tgt[8:])
                if not p.exists():
                    missing.append(f"{md.name}: missing {tgt}")
                continue
            rel = tgt.split("#", 1)[0]
            if not rel:
                continue
            p = (md.parent / rel).resolve()
            if not p.exists():
                missing.append(f"{md.relative_to(ADTNM_ROOT)} -> {tgt}")
    return len(missing) == 0, f"checked {len(collect_authored_md())} md files, broken={len(missing)} {missing[:5]}"


def gate2_provenance() -> Tuple[bool, str]:
    ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
    exec_txt = (GUIDE_DIR / "chapters/01_executive_summary.md").read_text(encoding="utf-8")
    eval_txt = (GUIDE_DIR / "chapters/06_evaluation_and_results.md").read_text(encoding="utf-8")
    rd_txt = (ADTNM_ROOT / "README.md").read_text(encoding="utf-8")
    combined = exec_txt + "\n" + eval_txt + "\n" + rd_txt
    required_vals = [
        ledger["smoke_1h"]["message_stats"]["created"],
        ledger["smoke_1h"]["message_stats"]["delivered"],
        "-389.06",
        "+19.21",
        "+23.59",
        "+28.13",
        "-5.48",
        "83.8",
        "80.0",
        "99.5",
        "201.6",
        "100.00%",
    ]
    absent = [v for v in required_vals if str(v) not in combined]
    return len(absent) == 0, f"missing provenance tokens: {absent}"


def gate3_terminology_and_status() -> Tuple[bool, str]:
    idx_txt = (GUIDE_DIR / "index.md").read_text(encoding="utf-8")
    plan_txt = (GUIDE_DIR / "PLAN.md").read_text(encoding="utf-8")
    all_badges = ["Implemented+tested", "Implemented, experimental", "Deprecated or removed", "Not implemented (planned)"]
    missing_badges = [b for b in all_badges if b not in idx_txt or b not in plan_txt]
    rd_txt = (ADTNM_ROOT / "README.md").read_text(encoding="utf-8")
    if "managed substrates" not in rd_txt.lower() or "implemented, experimental" not in rd_txt.lower():
        missing_badges.append("README framing check")
    return len(missing_badges) == 0, f"badge/framing issues: {missing_badges}"


def gate4_mechanisms_11_parts() -> Tuple[bool, str]:
    files = [
        GUIDE_DIR / "chapters/04a_mechanisms_telemetry_and_profiles.md",
        GUIDE_DIR / "chapters/04b_mechanisms_srl_and_shield.md",
        GUIDE_DIR / "chapters/04c_mechanisms_adapters_and_remote.md",
        GUIDE_DIR / "chapters/04d_mechanisms_rl_and_reporting.md",
    ]
    total_mechs = 0
    total_subs = 0
    callout_fails = []
    for f in files:
        t = f.read_text(encoding="utf-8")
        mechs = re.findall(r"^## Mechanism \d+:", t, re.M)
        subs = re.findall(r"^### (?:1[01]|[1-9])\.", t, re.M)
        total_mechs += len(mechs)
        total_subs += len(subs)
        for tag in ["[!TIP] INTUITION", "[!IMPORTANT] FORMAL / CODE", "[!WARNING] GOTCHA / PITFALL", "[!NOTE] SUPERVISOR NOTE"]:
            if tag not in t:
                callout_fails.append(f"{f.name}:{tag}")
    ok = (total_mechs == 23 and total_subs == 253 and not callout_fails)
    return ok, f"mechs={total_mechs}/23, subsections={total_subs}/253, missing_callouts={callout_fails}"


def gate5_isolated_smoke() -> Tuple[bool, str]:
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    with tempfile.TemporaryDirectory() as tmp:
        tp = Path(tmp)
        iso_target = tp / "target"
        shutil.copytree(THE_ONE_DIR / "target", iso_target)
        (tp / "rep").mkdir()
        cp_sep = ";" if os.name == "nt" else ":"
        cp = f"{iso_target}{cp_sep}{THE_ONE_DIR / 'lib' / '*'}"
        javac_cmd = [
            "javac", "-cp", cp, "-d", str(iso_target),
            *[str(p) for p in sorted((ADTNM_ROOT / "src/routing").glob("*.java"))],
            *[str(p) for p in sorted((ADTNM_ROOT / "src/report").glob("*.java"))],
            str(ADTNM_ROOT / "tests/DtnmaUnitTests.java"),
        ]
        subprocess.run(javac_cmd, check=True, capture_output=True)
        ut = subprocess.run(["java", "-cp", cp, "DtnmaUnitTests"], capture_output=True, text=True, check=True)
        if "27 passed, 0 failed" not in ut.stdout:
            return False, f"unexpected DtnmaUnitTests output: {ut.stdout[-200:]}"

        cfg = (ADTNM_ROOT / "scenarios/scenario_dtnma.txt").read_text(encoding="utf-8")
        cfg += f"\nReport.reportDir = {(tp / 'rep').as_posix()}/\n"
        (tp / "smoke.txt").write_text(cfg, encoding="utf-8")
        subprocess.run(
            ["java", "-Xmx512m", "-cp", cp, "core.DTNSim", "-b", "1", str(tp / "smoke.txt")],
            cwd=str(THE_ONE_DIR), check=True, capture_output=True,
        )
        msg_rep = (tp / "rep" / "Dtnma_Benchmark_MessageStatsReport.txt").read_text(encoding="utf-8")
        aud_rep = (tp / "rep" / "Dtnma_Benchmark_DtnmaAuditReport.txt").read_text(encoding="utf-8")
        ok = ("created: 262" in msg_rep and "delivered: 184" in msg_rep and "traceable_ari_ratio: 1.0000" in aud_rep)
        return ok, "isolated compile + 27/27 tests + smoke 262/184 & 1.0000 ARI verified"


def gate6_visuals_and_pdfs() -> Tuple[bool, str]:
    svgs = sorted((GUIDE_DIR / "figures/svg").glob("*.svg"))
    pngs = sorted((GUIDE_DIR / "figures/png").glob("*.png"))
    mmds = sorted((GUIDE_DIR / "figures/mmd").glob("*.mmd"))
    ann_shots = sorted((GUIDE_DIR / "screenshots/annotated").glob("*.png"))
    raw_shots = sorted((GUIDE_DIR / "screenshots/raw").glob("*.png"))
    md_corpus = "\n".join(p.read_text(encoding="utf-8") for p in collect_authored_md())
    unreferenced = [p.name for p in svgs + ann_shots if p.name not in md_corpus]

    full_pdf = GUIDE_DIR / "build/ADTNM_Study_Guide.pdf"
    exec_pdf = GUIDE_DIR / "build/ADTNM_Executive_Summary.pdf"
    cs_pdf = GUIDE_DIR / "build/ADTNM_One_Page_Cheatsheet.pdf"
    html_out = GUIDE_DIR / "build/ADTNM_Study_Guide.html"
    if not all(x.exists() for x in (full_pdf, exec_pdf, cs_pdf, html_out)):
        return False, "missing one or more build/ deliverables"
    with fitz.open(full_pdf) as d1, fitz.open(exec_pdf) as d2, fitz.open(cs_pdf) as d3:
        p1, t1 = d1.page_count, len(d1.get_toc())
        p2 = d2.page_count
        p3 = d3.page_count
    ok = (
        len(svgs) == 21 and len(pngs) == 21 and len(mmds) == 14
        and len(raw_shots) >= 4 and len(ann_shots) >= 4
        and not unreferenced and p1 >= 20 and t1 >= 15 and 1 <= p2 <= 2 and p3 == 1
    )
    return ok, f"figs={len(svgs)}/21, mmds={len(mmds)}/14, shots={len(ann_shots)}, unref={unreferenced}, pdf_pages=({p1}[toc={t1}], {p2}, {p3})"


def gate7_readme_bounds() -> Tuple[bool, str]:
    rd = ADTNM_ROOT / "README.md"
    txt = rd.read_text(encoding="utf-8")
    n = len(txt.splitlines())
    has_conf = "Confirmatory Results" in txt and "v1.0-tnsm-freeze" in txt
    has_exp = "Post-Freeze Exploratory" in txt and "v1.0-exp-controls" in txt
    return (250 <= n <= 400 and has_conf and has_exp), f"README lines={n} (target 250..400), conf={has_conf}, exp={has_exp}"


def gate8_banned_words() -> Tuple[bool, str]:
    hits = []
    for p in collect_authored_md():
        for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            m = BANNED_RE.search(line)
            if m:
                hits.append(f"{p.relative_to(ADTNM_ROOT)}:{i}:{m.group(0)}")
    return len(hits) == 0, f"banned hits={hits}"


def gate9_repo_integrity() -> Tuple[bool, str]:
    diff_out = subprocess.check_output(["git", "-C", str(ADTNM_ROOT), "diff", "--name-only", "d4a159f"], text=True).splitlines()
    bad_paths = [p for p in diff_out if not (p.startswith("docs/") or p == "README.md")]
    tags_out = subprocess.check_output(["git", "-C", str(ADTNM_ROOT), "show-ref", "--tags", "-d"], text=True)
    tag_ok = ("c820752c27683f070ec72664c68bb0112a91d1b8 refs/tags/v1.0-tnsm-freeze" in tags_out and
              "ca5d9acccf6b4f606df5a586ecb80d4842179e4d refs/tags/v1.0-tnsm-freeze^{}" in tags_out)
    ok = len(bad_paths) == 0 and tag_ok
    return ok, f"changed_files={len(diff_out)}, non_doc={bad_paths}, freeze_tag_intact={tag_ok}"


def main() -> None:
    gates = [
        ("Gate 1: Structure & Link Integrity", gate1_links),
        ("Gate 2: Provenance & Numbers", gate2_provenance),
        ("Gate 3: Terminology & Status Framing", gate3_terminology_and_status),
        ("Gate 4: 23 Mechanisms x 11 Subsections", gate4_mechanisms_11_parts),
        ("Gate 5: Live Isolated Execution", gate5_isolated_smoke),
        ("Gate 6: 21 Figures, Shots & PDFs", gate6_visuals_and_pdfs),
        ("Gate 7: README Bounds & Separation", gate7_readme_bounds),
        ("Gate 8: Banned Vocabulary Check", gate8_banned_words),
        ("Gate 9: Repo Docs-Only & Tag Check", gate9_repo_integrity),
    ]
    all_ok = True
    for name, fn in gates:
        ok, detail = fn()
        tag = "PASS" if ok else "FAIL"
        print(f"[{tag}] {name} -> {detail}")
        if not ok:
            all_ok = False
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
