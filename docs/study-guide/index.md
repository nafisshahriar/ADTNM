# ADTNM Comprehensive Study Guide

> **Status:** Active Research Framework (Work in Progress) · **License:** MIT ([`LICENSE`](../../LICENSE)) · **Authors:** S. M. Nafis Shahriar, Pulok Akibuzzaman, Nusrat Rahman Aurna, Md Safin Rahman · **Standards Alignment:** IETF RFC 9675 & IRTF RFC 7575-Inspired · **Simulator:** The ONE (`Java`, JDK `17+`)

**ADTNM** (*Autonomic Delay/Disruption-Tolerant Network Management*) is an **architectural framework for mobility-aware autonomic resource coordination in delay/disruption-tolerant networks (DTNs)**. Inspired by **IETF RFC 9675** (*Delay-Tolerant Networking Management Architecture*, Birrane et al., November 2024) and **IRTF RFC 7575** (*Autonomic Networking*, Behringer et al., June 2015), ADTNM decouples network management from packet forwarding. Established DTN protocols (`SprayAndWait`, `ProphetV2`, `MaxProp`, `Epidemic`, and `DtnmaRouter`) operate unchanged as **managed substrates**, while thin adapters connect them to a unified local `DtnmaAgent` controller executing standardized **15 Externally Defined Data (`EDD`)** telemetry indicators, a closed-form **Mobility-Aware Distributed Resource Coordinator (`M2_MOBILITY_AWARE`)**, declarative **State-Based Rules (`SRL`)**, discrete **`P0–P7` Control Profiles**, a deterministic **Safety Shield (`S1–S4`)**, compact `16 B` peer contact beacons, `4 B` delayed delivery `ACK` pruning, and `100.00%` traceable **Application Resource Identifier (`ARI`)** audit logs.

---

## Choose Your Reading Path

| Audience | Objective | Recommended Sequence |
|---|---|---|
| **(a) Newcomer** (no DTN, RFC, or simulator background) | Grasp store-carry-forward concepts, install ADTNM alongside The ONE, run unit tests & smoke benchmarks, and read logs | [`01 Executive Summary`](chapters/01_executive_summary.md) → [`02 Primer for Newcomers`](chapters/02_primer_for_newcomers.md) → [`03 Architecture Overview`](chapters/03_architecture_overview.md) → [`07 Scenario Guide & Tutorials`](chapters/07_howto_and_tutorials.md) → [`Worked Examples`](worked_examples/worked_examples.md) → [`Week 1 Plan`](extras/onboarding_week1_plan.md) |
| **(b) Developer** (extending adapters, telemetry EDDs, or rules) | Understand class contracts, queue/ACK invariants, closed-form mobility laws, rule syntax, and validation gates | [`03 Architecture Overview`](chapters/03_architecture_overview.md) → [`04a`–`04d` Mechanism Reference](chapters/04a_mechanisms_telemetry_and_profiles.md) → [`05 Codebase Tour & Validation`](chapters/05_codebase_tour.md) → [`07 Scenario Guide & Tutorials`](chapters/07_howto_and_tutorials.md) → [`Reference Tables`](appendices/appendix_c_reference_tables.md) → [`One-Page Cheat Sheet`](extras/one_page_cheatsheet.md) |
| **(c) Researcher & Reviewer** (evaluating rigor & findings) | Audit RFC positioning, simulation settings, reporters, 1,216-run confirmatory & 176-run exploratory findings, trade-offs, and validity bounds | [`01 Executive Summary`](chapters/01_executive_summary.md) → [`06 Simulation Configurations & Empirical Findings`](chapters/06_evaluation_and_results.md) → [`08 Evolution & Design Decisions`](chapters/08_history_and_decisions.md) → [`Appendix A: RFC Alignment`](appendices/appendix_a_rfc_conformance.md) → [`Appendix D: Provenance`](appendices/appendix_d_provenance.md) → [`Review Checklist`](extras/supervisor_review_checklist.md) |

---

## Prebuilt Offline Deliverables (`docs/study-guide/build/`)

- [`ADTNM_Study_Guide.html`](build/ADTNM_Study_Guide.html) — Self-contained searchable single-file HTML reader with chapter sidebar and embedded figures.
- [`ADTNM_Study_Guide.pdf`](build/ADTNM_Study_Guide.pdf) — Complete bookmarked PDF monograph with clickable table-of-contents outlines and 300-DPI figures.
- [`ADTNM_Executive_Summary.pdf`](build/ADTNM_Executive_Summary.pdf) — Standalone `<= 2`-page architectural & empirical briefing.
- [`ADTNM_One_Page_Cheatsheet.pdf`](build/ADTNM_One_Page_Cheatsheet.pdf) — Standalone `1`-page desktop developer reference card.

---

## Complete Study Guide Structure

### Foundations & System Architecture
1. [`01_executive_summary.md`](chapters/01_executive_summary.md) — Concise academic briefing covering architecture, key findings (`1,216` confirmatory + `176` exploratory runs), trade-offs, and scope.
2. [`02_primer_for_newcomers.md`](chapters/02_primer_for_newcomers.md) — Zero-background introduction to DTNs, store-carry-forward routing, classical protocols, RFC 9675 & 7575, and The ONE discrete-event engine.
3. [`03_architecture_overview.md`](chapters/03_architecture_overview.md) — Layered management–substrate architecture, three timescales, three intervention tiers, active modes (`M0_STATIC`, `M1_REMOTE_ONLY`, `M2_AUTONOMIC_SRL`, `M2_MOBILITY_AWARE`), and RFC boundaries.

### Mechanism Encyclopedia
4a. [`04a_mechanisms_telemetry_and_profiles.md`](chapters/04a_mechanisms_telemetry_and_profiles.md) — Agent–Manager split, `15` EDD indicators (including contact duration, ICT, encounter rate, and energy TTD), 4-tier forwarding & ACK-first eviction, byte reservation, ingress control, and `P0–P7` profiles.
4b. [`04b_mechanisms_srl_and_shield.md`](chapters/04b_mechanisms_srl_and_shield.md) — Declarative `SRL` rule parser, priority precedence, deterministic Safety Shield (`S1–S4`), delayed `4 B` ACK exchange, and atomic `ARI` provenance.
4c. [`04c_mechanisms_adapters_and_remote.md`](chapters/04c_mechanisms_adapters_and_remote.md) — `DtnmaManagedRouter` contract, four substrate adapters (`SnW`, `PRoPHETv2`, `MaxProp`, `Epidemic`), remote `CTRL_UPDATE_*` overlays, and `16 B` density-adaptive peer beacons.
4d. [`04d_mechanisms_mobility_and_reporting.md`](chapters/04d_mechanisms_mobility_and_reporting.md) — Closed-form `M2_MOBILITY_AWARE` coordination & ablations, controller presets (`M0b`, `M0e-25`, `M1-Remote`, `M2-SRL`, `M2-Mob`), and all reporters (`DtnmaMetricsReport`, `DtnmaRecoveryTimelineReport`, `DtnmaAuditReport`, `DtnmaDecisionReport`, `MessageStatsReport`).

### Codebase, Simulation Configs, Findings, Tutorials & Decisions
5. [`05_codebase_tour.md`](chapters/05_codebase_tour.md) — Package inventory (`29` Java files, `16` scenario/rule configs, `9` tool files), tick-by-tick lifecycle, dependency DAG, validation suite (`21` unit tests, smoke validator, CI), and extension recipes.
6. [`06_evaluation_and_results.md`](chapters/06_evaluation_and_results.md) — Complete simulation configurations, statistical methodology (8 Holm-Bonferroni families, `112` tests), empirical findings (`Env1–Env3`, Cross-Substrate `F4`, Scalability `N=60..500`, Exploratory Sensitivity), trade-offs, and explicit non-claims.
7. [`07_howto_and_tutorials.md`](chapters/07_howto_and_tutorials.md) — Scenario file anatomy, installation, `21` unit tests, `1 h` smoke benchmark, custom `SRL` rules, router adaptation, and full campaign analysis workflows.
8. [`08_history_and_decisions.md`](chapters/08_history_and_decisions.md) — Design evolution, five engineering post-mortems, and Architectural Decision Records (`ADRs`).

### Worked Examples, Appendices & Aids
- [`Worked Examples (1–6)`](worked_examples/worked_examples.md) — Six end-to-end numerical walkthroughs covering `M2-Mob`, `SRL`, Safety Shield overrides, Tier 1 vs. Tier 2 blackout survival, remote lag, and ACK-pruned vs. live loss reconciliation.
- [`Appendix A: RFC Alignment`](appendices/appendix_a_rfc_conformance.md) | [`Appendix B: Exhaustive Glossary & Acronyms`](appendices/appendix_b_glossary.md) | [`Appendix C: Reference Tables`](appendices/appendix_c_reference_tables.md) | [`Appendix D: Provenance & Integrity`](appendices/appendix_d_provenance.md)
- [`Week 1 Onboarding Plan`](extras/onboarding_week1_plan.md) | [`One-Page Cheat Sheet`](extras/one_page_cheatsheet.md) | [`Supervisor & Reviewer Checklist`](extras/supervisor_review_checklist.md) | [`Build Guide`](README_BUILD.md)
