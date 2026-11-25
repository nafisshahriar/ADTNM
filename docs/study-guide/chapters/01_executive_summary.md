# Chapter 1: Executive Summary

> **Scope:** Active Research Framework (MIT License) · **Frozen Confirmatory Evaluation:** `v1.0-tnsm-freeze` (`n = 11` seeds, `715` runs) · **Exploratory Follow-Up:** `v1.0-exp-controls` (`176` runs) · **Target:** Standalone `<= 2` PDF pages

---

## 1. Architectural Identity & Core Thesis

**ADTNM** (*Autonomic Delay/Disruption-Tolerant Network Management*) is an **architectural management framework** for challenged wireless and space networks implemented in Java atop **The ONE** simulator. Drawing inspiration from **IETF RFC 9675** (*DTN Management Architecture*, Nov 2024) and **IRTF RFC 7575** (*Autonomic Networking*, June 2015), ADTNM decouples *network management semantics* from *bundle forwarding heuristics*:

- Existing DTN protocols (`EpidemicRouter`, `ProphetV2Router`, `MaxPropRouter`, `SprayAndWaitRouter`, `DtnmaRouter`) remain **managed substrates** whose underlying path estimators stay untouched.
- A uniform `DtnmaAgent` engine wraps any substrate through a thin adapter (`Managed*Router`, averaging `253.5` non-comment lines of Java) to execute **11 Externally Defined Data (`EDD`)** telemetry sensors, declarative **State-Based Rules (`SRL`)**, discrete **`P0–P7` Control Profiles**, a deterministic **Safety Shield (`S1–S4`)**, and atomic **Application Resource Identifier (`ARI`)** audit logging (`100.00%` replayable offline).
- An optional Constrained MDP (`CMDP`) reinforcement learning (`RL`) planner (`M3`, `ShieldedCmdpRlAgent`) is included strictly as an **`Implemented, experimental`** module. In `v1.0` evaluations it trailed hand-written `SRL` rules (`M2`).

![ADTNM Architecture Overview](../figures/svg/fig01_architecture_overview.svg)

---

## 2. Headline Empirical Findings (Confirmatory vs. Exploratory Separated)

All metrics trace programmatically to [provenance_ledger.json](../data/provenance_ledger.json). Confirmatory runs (`7103–7207`) and post-freeze exploratory controls (`7211–7307`) never share statistical pools.

### A. Frozen Confirmatory `v1.0-tnsm-freeze` Summary (`715` Runs, `n = 11` Seeds, Holm-Adjusted)

| Research Claim / Test | Comparison & Scope | Measured Result (`95%` CI, Wins) | Architectural Interpretation |
|---|---|---|---|
| **Router-Agnostic Safety** | Critical lost across all `176` managed runs (`4` routers × `4` regimes) | **`0`** unique critical bundles lost (stock loses up to `100%`) | Priority reserve + `4 B` ACK pruning protects criticals across diverse substrates |
| **Tier Ablation (`Epidemic`)** | Tier 1 (protection) vs Tier 2 (+ingress/energy) vs Tier 3 (+quota caps) | Alive nodes: **`15.2`** (T1) → **`123.1`** (T2) → **`123.5/126`** (T3) | Survival derives from ingress & energy rules (`Tier 2`), leaving routing logic untouched |
| **E1 & E2 Equivalence** | `ManagedSprayAndWait` (Tier 1) vs hard-coded `PrioritySprayAndWait` | **E1:** `11/11` `MessageStats` exact; **E2 TOST:** `-0.31 pp`, `p = 2.29e-6` | Composition introduces zero unintended forwarding penalty |
| **A3 Policy Reuse** | Bespoke `EnergyAwareEpidemic` (`394` LOC) vs uniform `rules_vdtn.txt` (`12` LOC) | Bulk diff **`+3.39 pp`** vs Tier 2 (`0` crit lost both) | Recovers `~97%` of hand-coded performance at `3%` rule LOC across `4` routers |
| **H1 (Surge Agility)** | `M2` (Local `SRL`) vs `M1-U` (Unicast Remote), `Event A` (`3600–3900 s`) | **`-389.06 ± 111.49` node-s/N** (`0` vs `389.1`), `p_holm = 1.51e-5` (`11/11`) | Remote commands arrive late (`T90 = 3,336 s`) and throttle after the burst ends |
| **H2 (Event C Cohort)** | `M2` vs `M1-U`, `Event C` (`21600–28800 s`, `1 h` horizon) | **`+19.21 ± 3.97 pp`** bulk delivery, `p_holm = 1.58e-6` (`11/11`) | Local rules sustain heavy late-shift traffic without stale remote throttling |
| **H3 (Stress Survival)** | `M2` vs `M0b` (static protection, no energy guard), `12 h` Stress | **`+23.59 ± 2.35 pp`** survival (`83.8` vs `54.1/126`), `p_holm = 2.15e-9` | Energy-aware local rules prevent cascading fleet battery collapse |
| **H4 (Benign Efficiency)** | `M2` vs `M0c` (static `0.25` energy guard), `12 h` Benign | **`+28.13 ± 0.75 pp`** bulk (`82.0%` vs `53.9%`), `p_holm = 6.12e-15` | Conditional autonomy avoids throttling healthy idle networks |
| **R1 Audit Replay** | `DtnmaAuditReport` offline verification across `176` CompB runs | **`100.00%`** (`27,099,616` rows exact `ARI` & shield mask) | Atomic pre-decision snapshots guarantee deterministic auditability |

### B. Honest Negatives & Post-Freeze Exploratory Controls (`176` Runs, Unadjusted `p_unadj`)

> [!WARNING] Essential Caveats for Academic Review
> 1. **Failed Survival Non-Inferiority (`H2_orig`):** Pre-registered survival non-inferiority (`M2` within `-5 pp` of `M1-U` in stress) **failed**: `M2` trailed `M1-U` by **`-5.48 ± 1.96 pp`** (`83.8` vs `90.7/126`, `0/11` wins, `p_unadj = 9.65e-5`).
> 2. **Experimental `M3` RL Module Loses to `M2`:** Across stress (`bulk 63% vs 66%`, `alive 76.0 vs 83.8`) and benign (`80% vs 82%`) runs, linear Q-learning underperformed `SRL` (`M2`), underscoring why RL remains optional.
> 3. **Exploratory Causal Attribution (`v1.0-exp-controls`):** Static `M0e-25` (`SoC < 0.25`) matched `M2` end survival (**`83.8` vs `83.8/126`**, `p = 1.0`), whereas **`M2-LocalOnly`** dropped to **`80.0/126`** — isolating how much survival derived from `M2`'s `0.25` guard wave. Meanwhile, single-copy group flooding (**`M1-F-Group`**) slashed flood energy from `118.7 kJ` (`M1-F`) to **`201.6 J`** (`99.5/126` survivors), though median adoption (`482.9 s`) still missed the `300 s` Event A burst and trailed `M2` in Benign bulk (`76.0%` vs `82.0%`).

---

## 3. Five Open Research & Engineering Decisions (`v1.1` Roadmap)

1. **Local Energy-Drain Hysteresis vs. Benign Throughput:** Incorporate continuous `EDD_ENERGY_DRAIN` slope awareness into `rules_vdtn.txt` so local autonomy matches or exceeds `M1-U` stress survival without needing a `0.25` remote guard wave (`>= 90` survivors while holding `~82%` benign bulk).
2. **Scope of the `M3` RL Module:** Retain `M3` purely as an experimental negative control in `v1.0` manuscripts, or explore low-dimensional hazard-feature contextual bandits for `v1.1`.
3. **Closed-Loop Remote Manager Synthesis:** Replace scenario-scheduled Manager waves (`M1-U`, `M1-F-Group`) with adaptive policy synthesis computed dynamically from delayed `AoI`/uplink telemetry.
4. **Report Accounting & CI Hygiene:** Separate `ACK`-pruned duplicate evictions from live drops inside `DtnmaDecisionReport` (`53` benign prunes counted as drops in `1 h` smoke while `unique_critical_lost = 0`) and modernize `.github/workflows/ci.yml` onto headless smoke + replay checks.
5. **Domain Expansion & Schema Cleanup:** Empirically validate `scenarios/rules_uav.txt` with explicit priority-10 recovery fallback and retire unused legacy primal-dual configuration keys (`lambda = 0.0`).
