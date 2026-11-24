# ADTNM Study Guide Progress Log (`PROGRESS.md`)

> **Branch:** `docs/study-guide` | **Base Commit:** `d4a159f` | **Frozen Tag Preserved:** `v1.0-tnsm-freeze` (`c820752c` -> `ca5d9ac`)

| Phase / Step | Deliverable | Status | Commit Notes |
|---|---|---|---|
| **Phase 0** | Branch `docs/study-guide`, env tool check, verified RFC dates, `extract_code_inventory.py`, 1h smoke run (`262/184`), raw report inventory (`715` + `176` runs) | Done | Part of Phase 0+1 commit |
| **Phase 1** | `generate_provenance.py`, `data/provenance_ledger.json`, `appendix_d_provenance.md`, `PLAN.md`, `OPEN_QUESTIONS.md`, `PROGRESS.md`, `appendix_discrepancies.md` | Done | Committing Phase 0+1 next |
| **Phase 2A** | `index.md`, `01_executive_summary.md`, `02_primer_for_newcomers.md`, `03_architecture_overview.md`, `04a`–`04d` (23 Mechanisms) | Done | Step 2 commit (`index` + `01`–`04d`) |
| **Phase 2B** | `05_codebase_tour.md`, `06_evaluation_and_results.md`, `07_howto_and_tutorials.md`, `08_history_and_decisions.md`, `worked_examples.md` (6 examples), `appendices/{a,b,c}`, `extras/*` | In Progress | Committing per chapter/set |
| **Phase 3** | `generate_figures.py` (`21` SVGs/PNGs + `14` `.mmd`), `capture_screenshots.py` + `CaptureOneGuiScreenshot.java`, `build_study_guide.py` + HTML + 3 PDFs | Pending | Verify <=2p Exec Summary & 1p Cheatsheet |
| **Phase 4** | Move 5 legacy `docs/*` files to `docs/archive/` (`git mv`) with banners + `README_SUPERSEDED.md`; refresh root `README.md` (`250–400` lines) | Pending | Commit Phase 4 |
| **Phase 5** | Newcomer verbatim walkthrough + `newcomer_test_log.md`; implement & pass all 9 Quality Gates (`run_quality_gates.py`); rebuild outputs | Pending | Final cleanup commit |
| **Phase 6** | Final audit of `git diff --name-only d4a159f`, tag immutability, `DtnmaUnitTests`, and Phase 6 report | Pending | Clean working tree, NO `git push` |
