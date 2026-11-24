# ADTNM Study Guide & README Master Plan (`PLAN.md`)

> **Documented Commit:** `d4a159f` | **Frozen Evaluation Tag:** `v1.0-tnsm-freeze` (`c820752c` -> `ca5d9ac`) |
> **Branch:** `docs/study-guide` | **Scope:** Documentation-only (`docs/**` and root `README.md`)

This document locks the structure, terminology, audience reading paths, figure inventory, mechanism documentation template, and quality verification gates for the ADTNM Study Guide and refreshed root `README.md`.

---

## 1. Mission & Architectural Framing

**ADTNM** (Autonomic DTN Management Architecture) is an **architectural framework for autonomic management of delay/disruption-tolerant networks (DTNs)**, implemented in Java atop **The ONE** (Opportunistic Network Environment) simulator. Its design combines concepts from two Internet Engineering Task Force / Internet Research Task Force (IETF/IRTF) specifications:

- **IETF RFC 9675** (*Delay-Tolerant Networking Management Architecture*, Birrane et al., November 2024): separation of Agents and Managers, Application Resource Identifiers (ARIs), Externally Defined Data (EDDs), Controls (actuators), and state-based autonomic rules executed locally at nodes without requiring continuous connectivity to a manager.
- **IRTF RFC 7575** (*Autonomic Networking: Definitions and Design Goals*, Behringer et al., June 2015): closed-loop self-management objectives (`self-configuring`, `self-healing`, `self-optimizing`, `self-protecting`) alongside explicit non-goals and bounded-autonomy design constraints.

**Non-negotiable framing rules:**
1. **Routers are managed substrates:** `SprayAndWaitRouter`, `ProphetV2Router`, `MaxPropRouter`, `EpidemicRouter`, and `DtnmaRouter` are managed forwarding substrates. ADTNM is the management layer wrapped around them — never frame ADTNM as "a smarter router."
2. **Modular optionality & honest negatives:** The shielded constrained Markov decision process (CMDP) reinforcement learning (RL) planner (`ShieldedCmdpRlAgent`, mode `M3`) is `Implemented, experimental`. In the held-out `v1.0` evaluation (`715` confirmatory runs, seeds `7103..7207`), `M3` did not outperform hand-written state-based rules (`M2`). Both this negative result and the failed non-inferiority survival check (`H2_orig`) are documented prominently.
3. **Confirmatory vs. Exploratory separation:** Frozen `v1.0-tnsm-freeze` confirmatory results (`7103..7207`, Holm-adjusted where applicable) are kept visually and structurally separate from post-freeze exploratory control runs (`v1.0-exp-controls`, `7211..7307`, unadjusted `p_unadj`) in every chapter, table, figure, and build output.
4. **No absolutist or vendor claims:** Zero fabricated metrics, zero claims of wire-format binary conformance or external certification, and zero claims of institutional affiliation with NASA, APL, SCaN, or ION.

---

## 2. Target Audiences & Reading Paths

The Study Guide serves three distinct audiences simultaneously via explicit navigation paths:

| Audience | Goal | Recommended Reading Path |
|---|---|---|
| **(a) Newcomer** (no DTN, RFC, or simulator background) | Understand what DTNs and autonomic management are, build and run a scenario in `< 10` minutes, and trace one complete decision loop | `01_executive_summary.md` -> `02_primer_for_newcomers.md` -> `03_architecture_overview.md` -> `07_howto_and_tutorials.md` -> `worked_examples/worked_examples.md` -> `extras/onboarding_week1_plan.md` |
| **(b) Developer** (will modify Java/Python code or add adapters/rules) | Navigate classes safely, understand contracts & invariants, add a managed router adapter or rule, and pass all tests | `03_architecture_overview.md` -> `04a..04d` (23 Mechanisms) -> `05_codebase_tour.md` -> `07_howto_and_tutorials.md` -> `appendices/appendix_{c_reference_tables,discrepancies}.md` -> `extras/one_page_cheatsheet.md` |
| **(c) Supervisor & Reviewer** (evaluates rigor, evidence, limitations, open decisions) | Audit architecture vs. RFCs, confirmatory vs. exploratory hypotheses, limitations, reproducibility, and decide v1.1 priorities | `01_executive_summary.md` (with 5 Decision Records) -> `06_evaluation_and_results.md` -> `08_history_and_decisions.md` -> `appendices/appendix_{a_rfc_conformance,d_provenance}.md` -> `extras/supervisor_review_checklist.md` |

---

## 3. Canonical Terminology Table

All chapters, appendices, diagrams, and `README.md` MUST use these exact standardized terms:

| Canonical Term | Meaning in ADTNM | Avoid Ambiguous Synonym |
|---|---|---|
| **Managed Substrate** | Underlying forwarding protocol (`Epidemic`, `PRoPHETv2`, `MaxProp`, `SprayAndWait`, `DtnmaRouter`) governed via `DtnmaAgent` | "Smart router", "base AI router" |
| **Thin Adapter** | Router-specific wrapper (`Managed*Router`) implementing `DtnmaManagedRouter` hooks (~`246`–`265` non-comment LOC each) | "Plugin", "driver" (except by analogy) |
| **Autonomic Engine** | `DtnmaAgent` local closed-loop controller executing EDD sampling, SRL matching, safety shielding, and optional RL | "Cognitive core", "AI brain" |
| **EDD** (*Externally Defined Data*) | One of `11` local/neighbor telemetry indicators sampled by `EddTelemetrySampler` every `dt = 30.0 s` (configurable via `dtnmaTelemetryIntervalSec`) | "Hidden feature", "KPI" |
| **SRL Rule** (*State-Based Rule*) | `IF (<predicate>) THEN <action>` tuple parsed by `DtnmaRuleParser` with priority & latch duration | "Script", "heuristic macro" |
| **Control Profile (`P0..P7`)** | Discrete `8`-tuple bundle of actuators (`L`, `backpressureRatio`, `ingressMode`, `backpressureEnabled`) in `DtnmaPolicyProfile` | "Action ID" alone without profile context |
| **Safety Shield** | Deterministic hard-constraint filter `S1–S4 + Liveness` (`DtnmaSafetyShield`) masking unsafe `P0..P7` profiles before actuation | "Reward shaping", "soft penalty" |
| **Intervention Tier (1 / 2 / 3)** | Ablation decomposition: Tier 1 protection only; Tier 2 protection + ingress/energy; Tier 3 full (+ replica caps) | "Mode" (reserve Mode for `M0a..M3`) |
| **Evaluation Mode (`M0a..M3`)** | Multi-timescale experimental configurations comparing static (`M0a`–`M0e`), remote-only (`M1-U`, `M1-F`, `M1-F-Group`), local-rule (`M2`, `M2-LocalOnly`), and RL (`M3`) | "Agent type" |
| **ARI** (*Application Resource Identifier*) | Human-readable provenance URI string (e.g., `ARI://DTNMA/SRL/VDTN_SURGE_THROTTLE`) logged per decision in `DtnmaAuditReport` | "CBOR wire URI", "packet address" |
| **RL Planner (`M3`)** | `ShieldedCmdpRlAgent` (optional `20`-feature linear Q-critic with shield mask & neighbor gossip); `Implemented, experimental` | "Core controller", "production optimizer" |

---

## 4. Document Structure & Deliverables

### 4.1 Study Guide Tree (`docs/study-guide/`)
- `index.md` — Audience selector, Study Guide map, status taxonomy legend, fast navigation
- `00_inventory.md` + `data/code_inventory.json` — Automated repository inventory (`109` files, `48` `DtnmaConfig` fields, `30` `check()` assertions)
- `data/provenance_ledger.json` — Programmatic quantitative truth store (`smoke_1h`, `confirmatory_v1_0`, `exploratory_v1_0_controls`, regression, tests)
- `PLAN.md`, `OPEN_QUESTIONS.md`, `PROGRESS.md`
- `chapters/`:
  - `01_executive_summary.md` (standalone `<= 2` PDF pages, includes **5 Supervisor Decision Records**)
  - `02_primer_for_newcomers.md` (zero-background explainer covering DTNs, routing tradeoffs, RFC 9675 & 7575, The ONE discrete-event engine, 5 failure modes)
  - `03_architecture_overview.md` (4-plane architecture, 3-timescale closed loop, 3 intervention tiers, router adapter pattern, M0a–M3 matrix, RFC concept alignment vs. non-claims)
  - `04a_mechanisms_telemetry_and_profiles.md` (Mechanisms 1–6: Agent-Manager split, 11 EDDs, 5-tier priority queue & ACK-first eviction, byte-budget guard, 3 ingress regimes, `P0..P7` profiles)
  - `04b_mechanisms_srl_and_shield.md` (Mechanisms 7–11: Rule parser & recursive-descent predicates, priority + latch evaluation, Safety Shield `S1–S4`, critical delivery protection & ACK pruning, atomic snapshot ARI audit & 100% replay)
  - `04c_mechanisms_adapters_and_remote.md` (Mechanisms 12–17: `DtnmaManagedRouter` thin adapter contract, substrate adaptations across 4 routers, remote Manager uplink/downlink overlay, unicast `M1-U` vs legacy flood `M1-F` vs group flood `M1-F-Group`, hybrid `M2` vs `M2-LocalOnly`, 16 B beacon + periodic neighbor state sharing)
  - `04d_mechanisms_rl_and_reporting.md` (Mechanisms 18–23: Shielded linear Q-RL planner & feature map, neighbor weight gossip & `lambda=0` history, static benchmark modes `M0b`–`M0e-25`, 3-tier reporting pipeline & Cohort/Horizon stressor evaluation, per-decision `DtnmaAuditReport` format)
  - `05_codebase_tour.md` (package/file map, boot-to-decision execution sequence, dependency DAG, how-to-extend hooks)
  - `06_evaluation_and_results.md` (seed isolation taxonomy, Comparison A across 4 regimes + 3 tiers + E1/E2 equivalence + A3 parity, Comparison B confirmatory H1–H4 Holm-adjusted + failed survival NI, Post-Freeze Exploratory `v1.0-exp-controls`, honest limitations)
  - `07_howto_and_tutorials.md` (5 copy-paste runnable tutorials verified in isolated test)
  - `08_history_and_decisions.md` (chronological evolution across `git log`, 7 engineering post-mortems, Architectural Decision Records)
- `worked_examples/worked_examples.md` — 6 complete step-by-step walkthroughs built from real seed `7103`/`7211`/smoke logs
- `appendices/`:
  - `appendix_a_rfc_conformance.md` (Concept-by-concept comparison vs. RFC 9675 & RFC 7575)
  - `appendix_b_glossary.md` (Exhaustive alphabetical glossary & acronym index)
  - `appendix_c_reference_tables.md` (All `48` `DtnmaConfig` keys, `8` profiles, `11` EDDs, `16` audit columns, `12`+`10` rules, router matrix)
  - `appendix_d_provenance.md` (Metric-to-raw-file traceability ledger)
  - `appendix_discrepancies.md` (Verified code/doc/report discrepancies logged without modifying non-doc code)
- `extras/`:
  - `onboarding_week1_plan.md` (Day 1–5 guided newcomer ramp-up)
  - `one_page_cheatsheet.md` (`== 1` PDF page dense reference card)
  - `supervisor_review_checklist.md` (Targeted audit checklist for thesis/paper review)
- `figures/` (`mmd/`, `svg/`, `png/` — **21 figures**: 14 architectural/sequence diagrams + 7 quantitative plots from raw data)
- `screenshots/` (`raw/`, `annotated/` — real GUI/PlayField, terminal compile+test+smoke, log excerpts, batch dry-run, git tag proof)
- `tools/` & `build/` — Python/Java generators, quality-gate harness, searchable HTML + bookmarked PDF + 2-page Exec Summary PDF + 1-page Cheatsheet PDF

---

## 5. Mandatory 11-Part Mechanism Template (Chapters `04a`–`04d`)

Every one of the `23` numbered mechanisms MUST include all `11` labeled subsections:
1. **Purpose & Problem Solved**
2. **Design Alternatives Considered**
3. **Architecture & Data Flow**
4. **Inputs & Configuration** (`DtnmaConfig` keys, scenario keys, defaults)
5. **Algorithm & Exact Math / Equations**
6. **Code Anchors** (`path:Lstart-Lend`)
7. **Outputs, Telemetry & Logs**
8. **Edge Cases, Failure Modes & Safeguards**
9. **Worked Mini-Example**
10. **Status Label & Evolution** (`Implemented+tested`, `Implemented, experimental`, `Deprecated or removed`, `Not implemented (planned)`)
11. **Supervisor Rationale** (why built this way & limitations)

Plus required pedagogical callouts across prose: `[!TIP] INTUITION`, `[!IMPORTANT] FORMAL / CODE`, `[!WARNING] GOTCHA / PITFALL`, `[!NOTE] SUPERVISOR NOTE`.

---

## 6. Nine Automated Quality Gates (`tools/run_quality_gates.py`)

1. **Gate 1 (Structure & Link Integrity):** Every markdown file, anchor `#id`, relative link, image `src`, and `file://` path resolves cleanly.
2. **Gate 2 (Provenance & Numerical Consistency):** Key metrics in `01_executive_summary.md`, `06_evaluation_and_results.md`, and `README.md` match `data/provenance_ledger.json`.
3. **Gate 3 (Terminology, Status & First-Use Acronyms):** Status labels present; canonical terminology honored; acronyms expanded on first chapter use.
4. **Gate 4 (Mechanism Template Completeness):** All `23` mechanisms in `04a..04d` contain all `11` mandatory subsections.
5. **Gate 5 (Live Smoke Execution in Isolated Temp Clone):** Copy `ADTNM` + compiled `the-one` classes into scratch temp dir; execute Quick Start & Tutorials cleanly (`exit 0`, deterministic output).
6. **Gate 6 (Visual Asset & PDF Verification):** All `21` figure `.svg`/`.png` files exist with `14` `.mmd` sources; screenshots exist and are referenced; PDFs exist with `ADTNM_Executive_Summary.pdf <= 2` pages and `ADTNM_One_Page_Cheatsheet.pdf == 1` page.
7. **Gate 7 (README Bounds & Separation Check):** Root `README.md` line count within `[250, 400]` and clearly separates confirmatory from exploratory numbers.
8. **Gate 8 (Prohibited Vocabulary Check):** Zero matches for prohibited absolutist verification words across all `docs/**` and root `README.md`.
9. **Gate 9 (Repo Safety & Test Regression):** `DtnmaUnitTests` runs cleanly (`27 passed, 0 failed`), `git diff --name-only d4a159f` contains ONLY `docs/**` and `README.md`, and `v1.0-tnsm-freeze` points identically to `c820752c` -> `ca5d9ac`.
