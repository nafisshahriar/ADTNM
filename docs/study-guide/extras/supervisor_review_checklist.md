# Supervisor & Reviewer Audit Checklist (`supervisor_review_checklist.md`)

Use this verification checklist when auditing the thesis manuscript, architecture documentation, or subsequent `v1.1` experimental extensions.

- [ ] **Architecture-First Framing:** ADTNM is presented consistently as a cross-router management architecture (`Managed*Router` substrates governed by `DtnmaAgent`), rather than a standalone router.
- [ ] **Bounded RFC Positioning:** **RFC 9675** (Nov 2024) and **RFC 7575** (June 2015) are cited accurately as conceptual inspiration, with zero claims of binary wire-format `CBOR`/`ADM` conformance or external institutional affiliation ([Appendix A](../appendices/appendix_a_rfc_conformance.md)).
- [ ] **Disjoint Seed & Campaign Governance:** Confirmatory claims rest solely on frozen seeds `7103–7207` (`715` runs, Holm–Bonferroni corrected), while exploratory controls (`v1.0-exp-controls`) use fresh seeds `7211–7307` (`176` runs, unadjusted `p_unadj`).
- [ ] **Transparent Disclosure of Negative Results:** Both the failed survival non-inferiority test (`-5.48 ± 1.96 pp`, `0/11` wins) and the optional `M3` RL planner's shortfall behind declarative `SRL` (`M2`) appear clearly in Chapters 1, 6, and 8.
- [ ] **Fair Causal Attribution:** Stress survival comparisons explicitly present `M0e-25` (`83.8`), `M2` (`83.8`), `M2-LocalOnly` (`80.0`), and `M1-F-Group` (`99.5` at `201.6 J`, `482.9 s` median adoption) side-by-side.
- [ ] **Scenario & Provenance Completeness:** Every quantitative metric traces to [`provenance_ledger.json`](../data/provenance_ledger.json) and [`appendix_d_provenance.md`](../appendices/appendix_d_provenance.md), backed by explicit scenario parameters in [`Chapter 6`](../chapters/06_evaluation_and_results.md).
