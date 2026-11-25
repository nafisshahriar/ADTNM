#!/usr/bin/env python3
"""Automated 9 Quality Gates Verification Suite for Clean ADTNM Repo & Study Guide."""

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
    rd = ADTNM_ROOT / "README.md"
    if rd.exists():
        files.append(rd)
    return files


def gate1_links() -> Tuple[bool, str]:
    missing = []
    link_re = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
    for md in collect_authored_md():
        txt = md.read_text(encoding="utf-8")
        clean_lines = []
        in_code = False
        for l in txt.splitlines():
            if l.strip().startswith("```"):
                in_code = not in_code
                continue
            if not in_code:
                clean_lines.append(l)
        for m in link_re.finditer("\n".join(clean_lines)):
            tgt = m.group(1).strip()
            if tgt.startswith(("http://", "https://", "mailto:", "#")):
                continue
            rel = tgt[8:] if tgt.startswith("file:///") else tgt.split("#", 1)[0]
            if not rel:
                continue
            p = Path(rel) if tgt.startswith("file:///") else (md.parent / rel).resolve()
            if not p.exists():
                missing.append(f"{md.relative_to(ADTNM_ROOT)} -> {tgt}")
    return len(missing) == 0, f"checked {len(collect_authored_md())} md files, broken={len(missing)} {missing[:5]}"


def gate2_provenance() -> Tuple[bool, str]:
    ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
    exec_txt = (GUIDE_DIR / "chapters/01_executive_summary.md").read_text(encoding="utf-8")
    eval_txt = (GUIDE_DIR / "chapters/06_evaluation_and_results.md").read_text(encoding="utf-8")
    combined = exec_txt + "\n" + eval_txt
    required_vals = [
        "-389.06", "+19.21", "+23.59", "+28.13", "-5.48",
        "83.8", "80.0", "99.5", "201.6", "100.00%",
        "4,500–6,000 J", "2,650–3,650 J", "5,500–7,500 J",
    ]
    absent = [v for v in required_vals if str(v) not in combined]
    return len(absent) == 0, f"missing tokens: {absent}"


def gate3_public_readme_visitor_concise() -> Tuple[bool, str]:
    rd = ADTNM_ROOT / "README.md"
    txt = rd.read_text(encoding="utf-8")
    n = len(txt.splitlines())
    insider_re = re.compile(r"\b(DISC-\d+|SDR-\d+|d4a159f|ca5d9ac|30849)\b")
    insider_hits = insider_re.findall(txt)
    has_mit = "MIT License" in txt and "LICENSE" in txt
    has_wip = "work in progress" in txt.lower()
    ok = (95 <= n <= 175) and not insider_hits and has_mit and has_wip
    return ok, f"README lines={n} (target 95..175), mit={has_mit}, wip={has_wip}, insider={insider_hits}"


def gate4_mechanisms_and_scenarios() -> Tuple[bool, str]:
    files = [
        GUIDE_DIR / "chapters/04a_mechanisms_telemetry_and_profiles.md",
        GUIDE_DIR / "chapters/04b_mechanisms_srl_and_shield.md",
        GUIDE_DIR / "chapters/04c_mechanisms_adapters_and_remote.md",
        GUIDE_DIR / "chapters/04d_mechanisms_rl_and_reporting.md",
    ]
    total_mechs, total_subs = 0, 0
    for f in files:
        t = f.read_text(encoding="utf-8")
        total_mechs += len(re.findall(r"^## Mechanism \d+:", t, re.M))
        total_subs += len(re.findall(r"^### (?:1[01]|[1-9])\.", t, re.M))
    eval_txt = (GUIDE_DIR / "chapters/06_evaluation_and_results.md").read_text(encoding="utf-8")
    scen_checks = ["4,500 × 3,400", "9,000 × 7,000", "Event A", "Event B", "Event C", "fig22_evaluation_methodology_timeline.svg"]
    missing_scen = [s for s in scen_checks if s not in eval_txt]
    ok = total_mechs == 23 and total_subs == 253 and not missing_scen
    return ok, f"mechs={total_mechs}/23, subs={total_subs}/253, missing_scen={missing_scen}"


def gate5_isolated_smoke() -> Tuple[bool, str]:
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
    md_corpus = "\n".join(p.read_text(encoding="utf-8") for p in collect_authored_md())
    unreferenced = [p.name for p in svgs + ann_shots if p.name not in md_corpus]

    full_pdf = GUIDE_DIR / "build/ADTNM_Study_Guide.pdf"
    exec_pdf = GUIDE_DIR / "build/ADTNM_Executive_Summary.pdf"
    cs_pdf = GUIDE_DIR / "build/ADTNM_One_Page_Cheatsheet.pdf"
    html_out = GUIDE_DIR / "build/ADTNM_Study_Guide.html"
    if not all(x.exists() for x in (full_pdf, exec_pdf, cs_pdf, html_out)):
        return False, "missing build deliverables"
    with fitz.open(full_pdf) as d1, fitz.open(exec_pdf) as d2, fitz.open(cs_pdf) as d3:
        p1, t1 = d1.page_count, len(d1.get_toc())
        p2, p3 = d2.page_count, d3.page_count
    ok = (
        len(svgs) == 22 and len(pngs) == 22 and len(mmds) == 15
        and len(ann_shots) >= 4 and not unreferenced
        and p1 >= 20 and t1 >= 15 and 1 <= p2 <= 2 and p3 == 1
    )
    return ok, f"figs={len(svgs)}/22, mmds={len(mmds)}/15, unref={unreferenced}, pdf=({p1}[toc={t1}], {p2}, {p3})"


def gate7_banned_words() -> Tuple[bool, str]:
    hits = []
    for p in collect_authored_md():
        for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            m = BANNED_RE.search(line)
            if m:
                hits.append(f"{p.relative_to(ADTNM_ROOT)}:{i}:{m.group(0)}")
    return len(hits) == 0, f"banned hits={hits}"


def gate8_tidiness_and_safety() -> Tuple[bool, str]:
    pycaches = list(ADTNM_ROOT.rglob("__pycache__"))
    tags_out = subprocess.check_output(["git", "-C", str(ADTNM_ROOT), "show-ref", "--tags", "-d"], text=True)
    tag_ok = ("c820752c27683f070ec72664c68bb0112a91d1b8 refs/tags/v1.0-tnsm-freeze" in tags_out and
              "ca5d9acccf6b4f606df5a586ecb80d4842179e4d refs/tags/v1.0-tnsm-freeze^{}" in tags_out)
    rep_ok = (THE_ONE_DIR / "reports/final_11seed").is_dir() and (THE_ONE_DIR / "reports/exploratory_v1_0_controls").is_dir()
    ok = not pycaches and tag_ok and rep_ok
    return ok, f"pycaches={len(pycaches)}, freeze_tag_intact={tag_ok}, preserved_reports={rep_ok}"


def main() -> None:
    gates = [
        ("Gate 1: Link Integrity", gate1_links),
        ("Gate 2: Provenance & Scenarios Match", gate2_provenance),
        ("Gate 3: Visitor MIT/WIP README Cleanliness", gate3_public_readme_visitor_concise),
        ("Gate 4: 23 Mechanisms & Scenario Specs", gate4_mechanisms_and_scenarios),
        ("Gate 5: Live Isolated Execution", gate5_isolated_smoke),
        ("Gate 6: 22 Figures, HTML & Verified PDFs", gate6_visuals_and_pdfs),
        ("Gate 7: Prohibited Vocabulary Check", gate7_banned_words),
        ("Gate 8: Workspace Tidy & Safety Check", gate8_tidiness_and_safety),
    ]
    all_ok = True
    for name, fn in gates:
        ok, detail = fn()
        print(f"[{'PASS' if ok else 'FAIL'}] {name} -> {detail}")
        if not ok:
            all_ok = False
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
