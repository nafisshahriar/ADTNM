# Appendix D: Provenance & Verification Integrity Ledger (`appendix_d_provenance.md`)

> **Active Architecture:** Mobility-Aware Autonomic Resource Coordination (`M2_MOBILITY_AWARE`) over `Managed*Router` Substrates.

Quantitative results are extracted and verified programmatically from raw simulator reports (`*_DtnmaMetricsReport.txt`, `*_MessageStatsReport.txt`, `*_DtnmaRecoveryTimelineReport.txt`, and `*_DtnmaAuditReport.txt`; archived `*_DtnmaRlMetricsReport.txt` reports remain backward-compatible in `analyze_mobility_campaign.py` and `build_publication_package.py`).

| Verification / Campaign Artifact | Verified Scope | Tool / Source |
|---|---|---|
| **Java Unit Tests** | `17` test methods, `21` assertions (`Results: 21 passed, 0 failed.`) | `tests/DtnmaUnitTests.java` |
| **Scenario Config & Smoke Validator** | `9/9` `mob_*.txt` `[PASS]` + `4/4` `sens_*.txt` `ok=True` (`10 s` dry runs + coprimality check) | `tools/validate_mobility_scenarios.py --smoke` |
| **1-Hour Smoke Benchmark & Audit Trace** | `scenarios/scenario_dtnma.txt` -> `DtnmaMetricsReport` (`shield_decisions_total:`) + `DtnmaAuditReport` (`2,400` decisions, `traceable_ari_ratio: 1.0000`) | `scenarios/scenario_dtnma.txt` |
| **Confirmatory Multi-Mobility Integrity (`F1–F5`)** | `1,216/1,216` runs across `19` primes (`8101..8269`), `4,864/4,864` report files, `0` zero-size, `0` duplicates, `2,432/2,432` SHA-256 matches | `tools/analyze_mobility_campaign.py` / `tools/build_publication_package.py` |
| **Isolated Exploratory Sensitivity (`sens_*.txt`)** | `176/176` runs across `11` matched primes (`8101..8209`), `704/704` files, `0` zero-size, strictly excluded from confirmatory families | `tools/run_sensitivity_exploratory.py` / `tools/build_publication_package.py` |
