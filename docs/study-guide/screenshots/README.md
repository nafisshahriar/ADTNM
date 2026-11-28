# ADTNM Verification Screenshots (`screenshots/`)

| File (`raw/` & `annotated/`) | Capture Method | Underlying Evidence |
|---|---|---|
| `01_the_one_gui.png` | Swing GUI map capture on Helsinki urban topology | Live mobility, connectivity links, and bundle relay trace |
| `02_compile_tests_smoke.png` | Live subprocess stdout capture | `DtnmaUnitTests` (`21 passed, 0 failed`) + 1 h `scenario_dtnma.txt` smoke run |
| `03_audit_mob_excerpts.png` | Live `DtnmaAuditReport` & `DtnmaMetricsReport` slices | `21`-col `ARI` trace, `unique_critical_lost: 0`, and `F4` cross-substrate gains |
| `04_validate_and_campaign.png` | Live `validate_mobility_scenarios.py --smoke` & `run_mobility_campaign.py --dry-run` | `9/9` `[PASS]` scenario smoke check & confirmatory batch schedule |
