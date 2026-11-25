# ADTNM Comprehensive Study Guide

> **Status:** Active Research Framework (Work in Progress) · **License:** MIT (`LICENSE`) · **Standards Alignment:** IETF RFC 9675 & IRTF RFC 7575-Inspired · **Simulator:** The ONE (`Java`, JDK `17+`)

**ADTNM** (*Autonomic Delay/Disruption-Tolerant Network Management*) is an **architectural framework for autonomic management of delay/disruption-tolerant networks (DTNs)**. Inspired by **IETF RFC 9675** (*Delay-Tolerant Networking Management Architecture*, Birrane et al., November 2024) and **IRTF RFC 7575** (*Autonomic Networking*, Behringer et al., June 2015), ADTNM decouples network management from packet forwarding. Established DTN protocols (`SprayAndWait`, `ProphetV2`, `MaxProp`, `Epidemic`, and `DtnmaRouter`) operate unchanged as **managed substrates**, while lightweight adapters connect them to a unified local `DtnmaAgent` controller running standardized Externally Defined Data (`EDD`) telemetry, declarative state-based rules (`SRL`), and deterministic safety shields (`S1–S4`). An optional constrained Markov decision process (`CMDP`) reinforcement learning (`RL`) module (`M3`, `ShieldedCmdpRlAgent`) is marked **`Implemented, experimental`** and did not outperform hand-written declarative rules (`M2`) in `v1.0` evaluations.

---

## Choose Your Reading Path

| Audience | Objective | Recommended Sequence |
|---|---|---|
| **(a) Newcomer** (no DTN, RFC, or simulator background) | Grasp DTN store-carry-forward concepts, compile & run a benchmark in minutes, and inspect live logs | [`01 Executive Summary`](chapters/01_executive_summary.md) → [`02 Primer for Newcomers`](chapters/02_primer_for_newcomers.md) → [`03 Architecture Overview`](chapters/03_architecture_overview.md) → [`07 Scenario Guide & Tutorials`](chapters/07_howto_and_tutorials.md) → [`Worked Examples`](worked_examples/worked_examples.md) → [`Week 1 Plan`](extras/onboarding_week1_plan.md) |
| **(b) Developer** (extending adapters, sensors, or policies) | Understand class contracts, queue invariants, rule syntax, and clean extension hooks | [`03 Architecture Overview`](chapters/03_architecture_overview.md) → [`04a`–`04d` Mechanism Reference](chapters/04a_mechanisms_telemetry_and_profiles.md) → [`05 Codebase Tour`](chapters/05_codebase_tour.md) → [`07 Scenario Guide & Tutorials`](chapters/07_howto_and_tutorials.md) → [`Reference Tables`](appendices/appendix_c_reference_tables.md) → [`One-Page Cheat Sheet`](extras/one_page_cheatsheet.md) |
| **(c) Supervisor & Reviewer** (evaluating rigor & evidence) | Audit RFC conceptualization, scenario realism, confirmatory vs. exploratory hypotheses, and open research questions | [`01 Executive Summary`](chapters/01_executive_summary.md) → [`06 Scenarios, Evaluation & Results`](chapters/06_evaluation_and_results.md) → [`08 Evolution & Design Lessons`](chapters/08_history_and_decisions.md) → [`Appendix A: RFC Alignment`](appendices/appendix_a_rfc_conformance.md) → [`Appendix D: Provenance`](appendices/appendix_d_provenance.md) → [`Review Checklist`](extras/supervisor_review_checklist.md) |

---

## Status Taxonomy Used Throughout This Guide

- `Implemented+tested` — Implemented in the main repository, covered by `DtnmaUnitTests.java` (`27` tests / `30` `check()` assertions), deterministic offline replay checks, and held-out simulator campaigns.
- `Implemented, experimental` — Compiled and runnable, yet optional or unvalidated as a primary controller (e.g., `M3` linear Q-learning planner, `rules_uav.txt`, `EDD_ENERGY_DRAIN`).
- `Deprecated or removed` — Retired prior to the `v1.0` freeze (e.g., dynamic Lagrangian `lambda` adaptation and legacy plotting scripts).
- `Not implemented (planned)` — Future research direction (e.g., closed-loop remote Manager policy synthesis driven dynamically by delayed uplink telemetry).

---

## Complete Study Guide Structure

### Foundations & System Architecture
1. [`01_executive_summary.md`](chapters/01_executive_summary.md) — Concise `2`-page academic briefing covering system architecture, confirmatory vs. exploratory findings, caveats, and five open research decisions.
2. [`02_primer_for_newcomers.md`](chapters/02_primer_for_newcomers.md) — Accessible zero-background introduction to intermittent connectivity, store-carry-forward routing, classical protocols, RFC 9675 & 7575, and The ONE discrete-event engine.
3. [`03_architecture_overview.md`](chapters/03_architecture_overview.md) — Four-plane stack, three timescales, three intervention tiers, operating modes (`M0a–M3`), and RFC concept boundaries.

### Mechanism Encyclopedia (`23` Standardized Specifications)
4a. [`04a_mechanisms_telemetry_and_profiles.md`](chapters/04a_mechanisms_telemetry_and_profiles.md) — Mechanisms `01–06`: Agent–Manager split, `11` EDD telemetry indicators, `5`-tier buffer eviction & ACK-first pruning, byte-budget reservation, ingress locks, `P0–P7` control profiles.
4b. [`04b_mechanisms_srl_and_shield.md`](chapters/04b_mechanisms_srl_and_shield.md) — Mechanisms `07–11`: Declarative rule parser, priority precedence, deterministic Safety Shield (`S1–S4`), delayed `4 B` ACK exchange, atomic snapshot `ARI` replay.
4c. [`04c_mechanisms_adapters_and_remote.md`](chapters/04c_mechanisms_adapters_and_remote.md) — Mechanisms `12–17`: `DtnmaManagedRouter` contract, four substrate adapters, remote downlink overlays, unicast vs. flooded vs. group dissemination, `M2` vs. `M2-LocalOnly`, `16 B` peer beacons.
4d. [`04d_mechanisms_rl_and_reporting.md`](chapters/04d_mechanisms_rl_and_reporting.md) — Mechanisms `18–23`: Experimental `M3` RL planner, weight gossip & `lambda=0` history, static controls, `3`-tier cohort reporting, fleet timelines, audit TSV schema.

### Codebase, Evaluation Methodology, Tutorials & History
5. [`05_codebase_tour.md`](chapters/05_codebase_tour.md) — Package layout, tick-by-tick execution sequence, zero-cycle dependency DAG, and step-by-step extension guides.
6. [`06_evaluation_and_results.md`](chapters/06_evaluation_and_results.md) — Five-stage evaluation methodology, complete `.txt` scenario & energy specifications, Comparison A & B confirmatory findings, exploratory controls, and threats to validity.
7. [`07_howto_and_tutorials.md`](chapters/07_howto_and_tutorials.md) — Scenario file anatomy plus five verified hands-on tutorials (`install.py`, unit tests, smoke benchmark, custom `SRL` rules, router adaptation, batch dry-runs).
8. [`08_history_and_decisions.md`](chapters/08_history_and_decisions.md) — Architectural evolution, seven empirical engineering post-mortems, and Architecture Decision Records (`ADRs`).

### Worked Examples, Appendices & Aids
- [`Worked Examples (1–6)`](worked_examples/worked_examples.md) — Six numerical walkthroughs grounded in real seed `7103`, seed `7211`, and `1 h` smoke traces.
- [`Appendix A: RFC Alignment`](appendices/appendix_a_rfc_conformance.md) | [`Appendix B: Glossary`](appendices/appendix_b_glossary.md) | [`Appendix C: Reference Tables`](appendices/appendix_c_reference_tables.md) | [`Appendix D: Provenance Ledger`](appendices/appendix_d_provenance.md)
- [`Week 1 Onboarding`](extras/onboarding_week1_plan.md) | [`One-Page Cheat Sheet`](extras/one_page_cheatsheet.md) | [`Supervisor Checklist`](extras/supervisor_review_checklist.md) | [`Build Guide`](README_BUILD.md)
