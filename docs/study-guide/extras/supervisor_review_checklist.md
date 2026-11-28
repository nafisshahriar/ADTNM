# Supervisor & Reviewer Audit Checklist (`supervisor_review_checklist.md`)

Use this verification checklist when auditing the thesis manuscript, architecture documentation, or subsequent `v1.1` experimental extensions.

- [ ] **Architecture-First Framing:** ADTNM is presented consistently as a cross-router management architecture (`Managed*Router` substrates governed by `DtnmaAgent`), rather than a standalone router.
- [ ] **Bounded RFC Positioning:** **RFC 9675** (Nov 2024) and **RFC 7575** (June 2015) are cited accurately as conceptual inspiration, with zero claims of binary wire-format `CBOR`/`ADM` conformance or external institutional affiliation ([Appendix A](../appendices/appendix_a_rfc_conformance.md)).
- [ ] **Disjoint Seed & Campaign Governance:** Confirmatory claims rest solely on held-out seeds `8101–8269` (`mob_*.txt`, 8 Holm–Bonferroni families), while exploratory sensitivity sweeps (`sens_*.txt`) remain strictly isolated.
- [ ] **Causal Ablation Attribution:** Stress survival, critical loss, and bulk throughput comparisons explicitly isolate `M2-Mob` against `M2-Mob (EnergyOnly / BufferOnly / ReplicaOnly)`, `M2-SRL`, `M0e-25`, `M1-Remote`, and non-DTNMA baselines (`SprayAndWait`, `CongestionAwareSnW`).
- [ ] **Audit & Report Verification:** `DtnmaUnitTests`, `validate_mobility_scenarios.py --smoke`, and `scenario_dtnma.txt` (`DtnmaMetricsReport` + `traceable_ari_ratio: 1.0000`) pass cleanly.
