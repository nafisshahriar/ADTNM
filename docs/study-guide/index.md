# ADTNM Comprehensive Study Guide

> **Documented Commit:** `d4a159f` | **Frozen Evaluation Tag:** `v1.0-tnsm-freeze` (`c820752c` -> `ca5d9ac`) |
> **Exploratory Controls Campaign:** `v1.0-exp-controls` | **Simulator:** The ONE (`Java`, JDK 24)

**ADTNM** (*Autonomic Delay-Tolerant Network Management*) is an **architectural framework for autonomic management of delay/disruption-tolerant networks (DTNs)**. Inspired by **IETF RFC 9675** (*DTN Management Architecture*, Birrane et al., Nov 2024) and **IRTF RFC 7575** (*Autonomic Networking*, Behringer et al., June 2015), ADTNM decouples network management from packet forwarding: underlying DTN protocols (`SprayAndWait`, `ProphetV2`, `MaxProp`, `Epidemic`, and `DtnmaRouter`) remain **managed substrates**, while thin adapters wire them to a unified local `DtnmaAgent` autonomic engine executing Externally Defined Data (`EDD`) telemetry, state-based rules (`SRL`), and hard safety shields (`S1–S4`). An optional constrained Markov decision process (`CMDP`) reinforcement learning (`RL`) module (`M3`, `ShieldedCmdpRlAgent`) is `Implemented, experimental` and did not outperform deterministic state-based rules (`M2`) in the `v1.0` evaluation.

---

## Choose Your Reading Path

| Audience | What You Need | Start Here -> Follow This Path |
|---|---|---|
| **(a) Newcomer** (no DTN / RFC / ONE background) | Grasp DTNs, build & run a scenario in `< 10` minutes, trace live logs | [`01 Executive Summary`](chapters/01_executive_summary.md) -> [`02 Primer for Newcomers`](chapters/02_primer_for_newcomers.md) -> [`03 Architecture Overview`](chapters/03_architecture_overview.md) -> [`07 How-To & Tutorials`](chapters/07_howto_and_tutorials.md) -> [`Worked Examples`](worked_examples/worked_examples.md) -> [`Week 1 Plan`](extras/onboarding_week1_plan.md) |
| **(b) Developer** (extending adapters, rules, or core) | Class contracts, equations, exact invariants, safe extension hooks | [`03 Architecture Overview`](chapters/03_architecture_overview.md) -> [`04a`–`04d` Mechanisms](chapters/04a_mechanisms_telemetry_and_profiles.md) -> [`05 Codebase Tour`](chapters/05_codebase_tour.md) -> [`07 How-To & Tutorials`](chapters/07_howto_and_tutorials.md) -> [`Ref Tables`](appendices/appendix_c_reference_tables.md) & [`Discrepancies`](appendix_discrepancies.md) |
| **(c) Supervisor & Reviewer** (auditing architecture & evidence) | RFC grounding, confirmatory vs. exploratory evidence, negatives, open decisions | [`01 Executive Summary`](chapters/01_executive_summary.md) -> [`06 Evaluation & Results`](chapters/06_evaluation_and_results.md) -> [`08 History & Decisions`](chapters/08_history_and_decisions.md) -> [`Appendix A: RFCs`](appendices/appendix_a_rfc_conformance.md) & [`Appendix D: Provenance`](appendices/appendix_d_provenance.md) -> [`Review Checklist`](extras/supervisor_review_checklist.md) |

---

## Status Taxonomy Used Throughout This Guide

- `Implemented+tested` — Present at `d4a159f`, covered by `DtnmaUnitTests.java` (`27` tests / `30` `check()` assertions), deterministic replay checks, or held-out `v1.0` simulator campaigns.
- `Implemented, experimental` — Present at `d4a159f`, wired and runnable, but optional/exploratory or unvalidated as a primary controller (e.g., `M3` RL planner, `rules_uav.txt`, `EDD_ENERGY_DRAIN`).
- `Deprecated or removed` — Retired prior to `d4a159f` (e.g., dynamic Lagrangian `lambda` updates, unshielded RL variants, `verify_and_plot.py`, legacy scripts).
- `Not implemented (planned)` — Architectural target or open decision for `v1.1+` (e.g., automated closed-loop remote Manager synthesis from uplink telemetry, binary CBOR ARI encoding).

---

## Complete Study Guide Map

### Foundation & Architecture
1. [`01_executive_summary.md`](chapters/01_executive_summary.md) — 2-page project overview, headline numbers, honest caveats, and **5 Supervisor Decision Records**.
2. [`02_primer_for_newcomers.md`](chapters/02_primer_for_newcomers.md) — Zero-background primer: intermittent links, store-carry-forward, 4 routing families, RFC 9675 & 7575, The ONE loop.
3. [`03_architecture_overview.md`](chapters/03_architecture_overview.md) — Four planes, three timescales, three intervention tiers, `M0a–M3` taxonomy, RFC boundaries.

### Mechanism Encyclopedia (`23` Deep Dives, Standard 11-Part Template)
4a. [`04a_mechanisms_telemetry_and_profiles.md`](chapters/04a_mechanisms_telemetry_and_profiles.md) — Mechanisms `01–06`: Agent-Manager split, `11` EDDs, `5`-tier buffer eviction + ACK-first, byte-budget guard, ingress control, `P0–P7` profiles.
4b. [`04b_mechanisms_srl_and_shield.md`](chapters/04b_mechanisms_srl_and_shield.md) — Mechanisms `07–11`: Rule parser & AST, priority + latch evaluation, Safety Shield (`S1–S4`), critical protection & ACK pruning, atomic snapshot ARI audit & 100% replay.
4c. [`04c_mechanisms_adapters_and_remote.md`](chapters/04c_mechanisms_adapters_and_remote.md) — Mechanisms `12–17`: `DtnmaManagedRouter` contract, 4 substrate adapters, Manager uplink/downlink overlay, unicast vs. flooded vs. group-flooded dissemination, `M2` vs `M2-LocalOnly`, 16 B beacon & neighbor gossip.
4d. [`04d_mechanisms_rl_and_reporting.md`](chapters/04d_mechanisms_rl_and_reporting.md) — Mechanisms `18–23`: Shielded linear Q-agent (`M3`), neighbor weight gossip & `lambda=0` retirement, static controls (`M0b`–`M0e-25`), 3-tier reporting + Cohort/Horizon events, audit schema.

### Code, Evidence, Practice & Evolution
5. [`05_codebase_tour.md`](chapters/05_codebase_tour.md) — Directory inventory (`109` repo files), boot-to-step execution order, dependency DAG, extension recipes.
6. [`06_evaluation_and_results.md`](chapters/06_evaluation_and_results.md) — Seed separation, Comparison A (`4` regimes, `3` tiers, `E1`/`E2` equivalence, `A3` LOC parity), Confirmatory CompB (`H1–H4` Holm-adjusted + failed `H2_orig` survival NI), Post-Freeze Exploratory `v1.0-exp-controls`, honest limitations.
7. [`07_howto_and_tutorials.md`](chapters/07_howto_and_tutorials.md) — 5 hands-on runnable tutorials verified in an isolated clean environment.
8. [`08_history_and_decisions.md`](chapters/08_history_and_decisions.md) — Commit-by-commit evolution, 7 bug post-mortems, and Architectural Decision Records.

### Worked Examples, Appendices, Planning & Extras
- [`Worked Examples (1–6)`](worked_examples/worked_examples.md) — End-to-end numerical walkthroughs over real smoke, `7103`, and `7211` report logs.
- [`Appendix A: RFC Alignment`](appendices/appendix_a_rfc_conformance.md) | [`Appendix B: Glossary`](appendices/appendix_b_glossary.md) | [`Appendix C: Reference Tables`](appendices/appendix_c_reference_tables.md) | [`Appendix D: Provenance`](appendices/appendix_d_provenance.md) | [`Discrepancies Log`](appendix_discrepancies.md)
- [`Week 1 Onboarding`](extras/onboarding_week1_plan.md) | [`One-Page Cheatsheet`](extras/one_page_cheatsheet.md) | [`Supervisor Checklist`](extras/supervisor_review_checklist.md)
- Planning & Inventory: [`00_inventory.md`](00_inventory.md) | [`PLAN.md`](PLAN.md) | [`OPEN_QUESTIONS.md`](OPEN_QUESTIONS.md) | [`PROGRESS.md`](PROGRESS.md) | [`README_BUILD.md`](README_BUILD.md)
