# Appendix D: Number Provenance Ledger (`appendix_d_provenance.md`)

> **Documented Commit:** `d4a159f` | **Frozen Tag:** `v1.0-freeze` (`c820752c` -> `ca5d9ac`) | **Exploratory Campaign:** `v1.0-exp-controls` | **Generated:** `2026-11-25` by `docs/study-guide/tools/generate_provenance.py`

Every quantitative claim in the Study Guide and root `README.md` traces programmatically to [provenance_ledger.json](file:///n:/R&D/cog/ADTNM/docs/study-guide/data/provenance_ledger.json) and the raw simulator outputs listed below. Confirmatory `v1.0` (`7103..7207`, Holm-adjusted) and Post-Freeze Exploratory (`7211..7307`, unadjusted) remain strictly separated.

| Claim / Metric Group | Verified Value | Raw Source Files | Computing Script |
|---|---|---|---|
| Smoke 1h: Created / Delivered / Crit Dropped (Decision vs RlMetrics) | `262 / 184 / Decision=53 (unique_lost=0)` | `scratch/smoke_verify/reports/Dtnma_Benchmark_*` | `generate_provenance.py` |
| Unit tests (d4a159f) | `27 passed, 0 failed (30 check() assertions)` | `tests/DtnmaUnitTests.java` | `DtnmaUnitTests.java` |
| Confirmatory H1 (M2 vs M1-U EvA Stale) | `-389.06 +/- 111.49 node-s/N (p_holm=1.510e-05, 11/11)` | `final_11seed/compB_multitimescale/*_DtnmaRlMetricsReport.txt` | `analyze_final_11seed.py` |
| Confirmatory H2 (M2 vs M1-U EvC Bulk) | `+19.21 +/- 3.97 pp (p_holm=1.575e-06, 11/11)` | `final_11seed/compB_multitimescale/*_DtnmaRlMetricsReport.txt` | `analyze_final_11seed.py` |
| Confirmatory H3 (M2 vs M0b Stress Survival) | `+23.59 +/- 2.35 pp (p_holm=2.145e-09, 11/11)` | `final_11seed/compB_multitimescale/*_DtnmaRlMetricsReport.txt` | `analyze_final_11seed.py` |
| Confirmatory H4 (M2 vs M0c Benign Bulk) | `+28.13 +/- 0.75 pp (p_holm=6.116e-15, 11/11)` | `final_11seed/compB_benign/*_DtnmaRlMetricsReport.txt` | `analyze_final_11seed.py` |
| Original H2 Failed Survival NI (M2 vs M1-U) | `-5.48 +/- 1.96 pp (p_unadj=9.646e-05, 0/11)` | `final_11seed/compB_multitimescale/*_DtnmaRlMetricsReport.txt` | `analyze_final_11seed.py` |
| CompA 132 managed runs unique_critical_lost | `0` | `final_11seed/compA_*/*_DtnmaRlMetricsReport.txt` | `generate_provenance.py` |
| A3 EnergyAwareEpidemic vs ManagedEpidemic (TIER2 & TIER3) BkDel | `+3.39 pp (TIER2) / +3.14 pp (TIER3)` | `final_11seed/compA_{energy_blackout,tiers}/*` | `generate_provenance.py` |
| LOC Accounting (Core 7 files / Mean Adapter / Hand-coded / Rules vDTN) | `2095 / 253.5 / 394 / 12` | `src/routing/*.java, scenarios/rules_vdtn.txt` | `extract_code_inventory.py` |
| E1 Strict Parity & E2 TOST Default Ordering | `E1: 11/11 MsgStats (11/11 SHA), 10/11 RlCore; E2 diff=-0.3100 pp, p_TOST=2.288e-06` | `final_11seed/compA_equivalence*/*` | `analyze_final_11seed.py / generate_provenance.py` |
| Confirmatory R1 Deterministic Audit Replay (CompB Stress & Benign) | `Stress: 100.00% traceable (13,128,736 rows, 100.00% static, 100.00% RL); Benign: 100.00% (13,970,880 rows)` | `final_11seed/compB_*/*_DtnmaAuditReport.txt` | `analyze_final_11seed.py` |
| Exploratory v1.0-exp-controls (176 runs, seeds 7211..7307) | `M2-LocalOnly=80.0/126; M1-F-Group=99.5/126 (CtrlTx=201.6 J); 100.00% ARI replay` | `exploratory_v1_0_controls/*/*` | `analyze_exploratory_controls.py` |
| Post-freeze Regression Check (7103, 7109) | `6/6 MsgStats SHA-256, 6/6 Timeline SHA-256, 6/6 non-CPU RlMetrics` | `final_11seed/compB_multitimescale` | `parallel_batch_runner.py + SHA-256 check` |
