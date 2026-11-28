#!/usr/bin/env python3
"""Automated 8 Quality Gates Verification Suite for Clean ADTNM Repo & Study Guide."""

from __future__ import annotations

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

BANNED_RE = re.compile(r"\b(certified|compliant|airtight|proven|rfc-compliant|tnsm|tmc|tccn)\b", re.I)


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
    exec_txt = (GUIDE_DIR / "chapters/01_executive_summary.md").read_text(encoding="utf-8")
    eval_txt = (GUIDE_DIR / "chapters/06_evaluation_and_results.md").read_text(encoding="utf-8")
    combined = exec_txt + "\n" + eval_txt
    required_vals = [
        "1,216", "176", "8101..8269", "8101..8209",
        "+18.42", "102.42", "84.00", "+3.68", "98.48%",
        "+24.94", "+63.56", "172.8", "53.8", "0.75x",
    ]
    absent = [v for v in required_vals if v not in combined]
    return len(absent) == 0, f"checked mobility metrics tokens, missing={absent}"


def gate3_public_readme_visitor_concise() -> Tuple[bool, str]:
    rd = ADTNM_ROOT / "README.md"
    txt = rd.read_text(encoding="utf-8")
    n = len(txt.splitlines())
    insider_re = re.compile(r"\b(DISC-\d+|SDR-\d+|d4a159f|ca5d9ac|30849)\b")
    insider_hits = insider_re.findall(txt)
    has_mit = "MIT License" in txt and "LICENSE" in txt
    has_wip = "work in progress" in txt.lower()
    has_install = "install.py" in txt and "## Installation" in txt
    authors = ["S. M. Nafis Shahriar", "Pulok Akibuzzaman", "Nusrat Rahman Aurna", "Md Safin Rahman"]
    missing_authors = [a for a in authors if a not in txt]
    ok = (95 <= n <= 260) and not insider_hits and has_mit and has_wip and has_install and not missing_authors
    return ok, f"README lines={n}, mit={has_mit}, wip={has_wip}, install={has_install}, missing_authors={missing_authors}, insider={insider_hits}"


def gate4_mechanisms_and_scenarios() -> Tuple[bool, str]:
    files = [
        GUIDE_DIR / "chapters/04a_mechanisms_telemetry_and_profiles.md",
        GUIDE_DIR / "chapters/04b_mechanisms_srl_and_shield.md",
        GUIDE_DIR / "chapters/04c_mechanisms_adapters_and_remote.md",
        GUIDE_DIR / "chapters/04d_mechanisms_mobility_and_reporting.md",
    ]
    total_mechs = sum(len(re.findall(r"^## Mechanism \d+:", f.read_text(encoding="utf-8"), re.M)) for f in files)
    eval_txt = (GUIDE_DIR / "chapters/06_evaluation_and_results.md").read_text(encoding="utf-8")
    scen_checks = ["4,500 × 3,400", "10,000 × 8,000", "1,000 × 1,000", "mob_cross_substrate", "mob_scale_{60,126,250,500}", "sens_energy_{075x,135x}"]
    missing_scen = [s for s in scen_checks if s not in eval_txt]
    ok = total_mechs == 22 and not missing_scen
    return ok, f"mechs={total_mechs}/22, missing_scen={missing_scen}"


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
        if "21 passed, 0 failed" not in ut.stdout:
            return False, f"unexpected DtnmaUnitTests output: {ut.stdout[-200:]}"

        cfg = (ADTNM_ROOT / "scenarios/scenario_dtnma.txt").read_text(encoding="utf-8")
        cfg += f"\nReport.reportDir = {(tp / 'rep').as_posix()}/\n"
        (tp / "smoke.txt").write_text(cfg, encoding="utf-8")
        subprocess.run(
            ["java", "-Xmx512m", "-cp", cp, "core.DTNSim", "-b", "1", str(tp / "smoke.txt")],
            cwd=str(THE_ONE_DIR), check=True, capture_output=True,
        )
        met_rep = (tp / "rep" / "Dtnma_Benchmark_DtnmaMetricsReport.txt").read_text(encoding="utf-8")
        aud_rep = (tp / "rep" / "Dtnma_Benchmark_DtnmaAuditReport.txt").read_text(encoding="utf-8")
        ok = ("shield_decisions_total:" in met_rep and "traceable_ari_ratio: 1.0000" in aud_rep)
        return ok, "isolated compile + 21/21 tests + smoke DtnmaMetricsReport & 1.0000 ARI verified"


def gate6_visuals_and_pdfs() -> Tuple[bool, str]:
    svgs = sorted((GUIDE_DIR / "figures/svg").glob("*.svg"))
    pngs = sorted((GUIDE_DIR / "figures/png").glob("*.png"))
    mmds = sorted((GUIDE_DIR / "figures/mmd").glob("*.mmd"))
    ann_shots = sorted((GUIDE_DIR / "screenshots/annotated").glob("*.png"))
    md_corpus = "\n".join(p.read_text(encoding="utf-8") for p in collect_authored_md())
    emp_pngs = [p for p in pngs if int(p.name[3:5]) >= 15]
    unreferenced = [p.name for p in svgs + emp_pngs + ann_shots if p.name not in md_corpus]

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
        len(svgs) == 13 and len(mmds) == 13 and len(pngs) == 21
        and len(ann_shots) >= 4 and not unreferenced
        and p1 >= 20 and t1 >= 15 and 1 <= p2 <= 2 and p3 == 1
    )
    return ok, f"svgs={len(svgs)}/13, mmds={len(mmds)}/13, pngs={len(pngs)}/21, unref={unreferenced}, pdf=({p1}[toc={t1}], {p2}, {p3})"


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
    lic_txt = (ADTNM_ROOT / "LICENSE").read_text(encoding="utf-8")
    lic_ok = "Copyright (c) 2026 S. M. Nafis Shahriar, Pulok Akibuzzaman, Nusrat Rahman Aurna, Md Safin Rahman" in lic_txt
    ok = not pycaches and lic_ok
    return ok, f"pycaches={len(pycaches)}, mit_copyright_exact={lic_ok}"


def main() -> None:
    gates = [
        ("Gate 1: Link Integrity", gate1_links),
        ("Gate 2: Provenance & Mobility Findings Match", gate2_provenance),
        ("Gate 3: Visitor MIT/WIP/Authors README Cleanliness", gate3_public_readme_visitor_concise),
        ("Gate 4: 22 Mechanisms & Scenario Specs", gate4_mechanisms_and_scenarios),
        ("Gate 5: Live Isolated Execution", gate5_isolated_smoke),
        ("Gate 6: Diagrams, Empirical Plots, HTML & Verified PDFs", gate6_visuals_and_pdfs),
        ("Gate 7: Prohibited Vocabulary Check", gate7_banned_words),
        ("Gate 8: Workspace Tidy & MIT Copyright Check", gate8_tidiness_and_safety),
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
