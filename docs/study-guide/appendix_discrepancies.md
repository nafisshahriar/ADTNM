# Appendix: Code, Test, Report & Legacy Documentation Discrepancies (`appendix_discrepancies.md`)

> **Policy:** Per strict `DOCS-ONLY` rules anchored at commit `d4a159f`, **NO non-documentation source files (`src/`, `scenarios/`, `tools/`, `tests/`, `.github/`, root markdown ledgers) were modified.** Every discrepancy discovered during verification is logged verbatim here with exact `file:line` pointers so the engineering team can resolve them cleanly in a dedicated follow-up code PR.

---

## Summary Index

| ID | Category | Severity | Location (`d4a159f`) | Summary |
|---|---|---|---|---|
| **DISC-01** | Reporting / Metrics | High | `src/report/DtnmaDecisionReport.java:103-107` | Counts ACK-pruned critical copy evictions inside `Critical Fault Messages Dropped` |
| **DISC-02** | CI Workflow | High | `.github/workflows/ci.yml:52-62` | Calls deleted `verify_and_plot.py` and expects removed `verification_proof.png` |
| **DISC-03** | Contributor Guide | High | `CONTRIBUTING.md:85-137` | Cites non-existent `sampleTelemetry()`, stale actuator hook, outdated eviction snippet, deleted script |
| **DISC-04** | Legacy README | Medium | `README.md` (pre-refresh at `d4a159f`) | Wrong RFC 7575/6693 titles, deleted `phase1_*`/`phase2_*` scripts, states `15 assertions` vs `30` |
| **DISC-05** | Dead / Unused Telemetry | Low | `src/routing/EddTelemetrySampler.java:33,204` | Computes & exposes `EDD_ENERGY_DRAIN` (`energyDrainRate`), unused in `v1.0` rulesets |
| **DISC-06** | Unvalidated Ruleset | Medium | `scenarios/rules_uav.txt:1-28` | Uses bare `<` without hysteresis release rule; never evaluated in `v1.0` |
| **DISC-07** | Exploratory Report Sentinel | Medium | `src/report/DtnmaRlMetricsReport.java` (`downlink_t90_sec`) | Emits `30849.0` end-of-horizon sentinel when `downlink_90pct_reached == 0.0` (e.g., `M2`) |
| **DISC-08** | Retired Config Shims | Low | `src/routing/DtnmaConfig.java:63-66`, `scenarios/dtnma_params_default.txt:29-32` | Retains zeroed primal-dual keys (`rlLambdaInit=0.0`, `rlEtaLambda=0.0`) & `getLagrangianValue()` name |
| **DISC-09** | Test Label Count Mismatch | Low | `tests/DtnmaUnitTests.java` vs summary footer | Contains `27` test methods (`Test1a..Test22`), `30` `check()` assertions (`Test7`+`Test8` inside `test7...`) |

---

## Detailed Findings

### DISC-01 — `DtnmaDecisionReport` Counts ACK-Pruned Evictions as Critical Drops
- **Where:** `src/report/DtnmaDecisionReport.java:103-107` vs `src/routing/DtnmaAgent.java:1004-1020` and `src/report/DtnmaRlMetricsReport.java`.
- **Observed behavior:** `DtnmaDecisionReport.messageDeleted(...)` increments `criticalMessagesDropped` whenever `DtnmaRouter.isCriticalMessage(m)` is true and `dropped == true`, regardless of whether the bundle's ID is already in `DtnmaAgent`'s `deliveredAckSet`. In the 1-hour `scenario_dtnma.txt` smoke run, `DtnmaDecisionReport` reported **`Critical Fault Messages Dropped: 53`**, while `DtnmaRlMetricsReport` reported **`unique_critical_lost: 0`** and **`effective_critical_loss: 0`**.
- **Root cause:** `DtnmaAgent.selectNextMessageToRemove(...)` intentionally prioritizes evicting already-ACKed critical copies *before* touching any un-ACKed payload (`DtnmaAgent.java:1004-1020`) to free buffer space safely. `DtnmaRlMetricsReport` distinguishes `critical_buffer_dropped_acked` from un-ACKed `critical_buffer_dropped` / `unique_critical_lost`; `DtnmaDecisionReport` does not.
- **Recommended fix:** Filter out `!agent.isCriticalDeliveredAcked(m.getId())` in `DtnmaDecisionReport.messageDeleted` or split the counter into `Critical Dropped (Un-ACKed)` vs `Critical Pruned (ACKed)`.

### DISC-02 — Broken CI Pipeline Step Calling Deleted `verify_and_plot.py`
- **Where:** `.github/workflows/ci.yml:52-62`.
- **Observed behavior:** After compiling `the-one` + `ADTNM` and running `DtnmaUnitTests` (`lines 25-49`), `.github/workflows/ci.yml` executes `python3 ADTNM/tools/verify_and_plot.py --assert-zero-drops` and checks `docs/verification_proof.png`. `verify_and_plot.py` no longer exists in `tools/` (`tools/` now houses `parallel_batch_runner.py`, `evaluate_autonomic_framework.py`, `analyze_final_11seed.py`, `analyze_exploratory_controls.py`), and `docs/verification_proof.png` is archived to `docs/archive/` in Phase 4.
- **Recommended fix:** Replace lines 52–62 in `.github/workflows/ci.yml` with a 1-hour smoke run on `scenarios/scenario_dtnma.txt` plus `verify_audit_replay` and assertion of `unique_critical_lost == 0`.

### DISC-03 — Stale Refactor Pointers in `CONTRIBUTING.md`
- **Where:** `CONTRIBUTING.md:85-96, 117-119, 137`.
- **Observed behavior:**
  1. Line 86 references `sampleTelemetry()` inside `DtnmaAgent.java`; telemetry was refactored into `EddTelemetrySampler.sampleAndCompute(...)` (`src/routing/EddTelemetrySampler.java`).
  2. Line 96 tells contributors to add new actions inside `DtnmaRouter.java` via `ControlAction` enums; actuators now live in `ControlAction.java` (`L21-38`) and `DtnmaPolicyProfile.java`, executed via `DtnmaAgent.java`.
  3. Lines 117–119 show an incomplete critical-eviction loop missing the `deliveredAckSet` priority tier present in `DtnmaAgent.java:1004-1020`.
  4. Line 137 tells developers to run deleted `verify_and_plot.py`.
- **Recommended fix:** Sync `CONTRIBUTING.md` with Ch. `05_codebase_tour.md` and Ch. `07_howto_and_tutorials.md` after this docs PR merges.

### DISC-04 — Inaccurate Claims in Pre-Refresh Root `README.md` (`d4a159f`)
- **Where:** Root `README.md` (fixed in Phase 4 refresh of this PR).
- **Observed issues:** Described ADTNM as a "Cognitive Routing Architecture" ("smarter router" framing); gave incorrect titles/years for RFC 7575 and RFC 6693; cited deleted `phase1_vdtn_*` / `phase2_*` scripts and removed `DtnmaUnshielded*` classes; reported stale `64.12%` 1-hour delivery rate and `15 assertions`.

### DISC-05 — Unused Telemetry Metric `EDD_ENERGY_DRAIN`
- **Where:** `src/routing/EddTelemetrySampler.java:33, 204`.
- **Observed behavior:** `EddTelemetrySampler` computes exponential moving drain rate (`EDD_ENERGY_DRAIN`) and exposes it to rule parsing via `getEddValue`, yet none of the `v1.0` rules (`rules_vdtn.txt`, `rules_ntn.txt`, `rules_uav.txt`) or RL feature extractors currently read `EDD_ENERGY_DRAIN`.
- **Impact:** Harmless in `v1.0`; noted as a ready-to-use `v1.1` predictor for early battery-depletion rules (e.g., to address Decision 2).

### DISC-06 — `scenarios/rules_uav.txt` Unvalidated & Lacks Catch-All Hysteresis
- **Where:** `scenarios/rules_uav.txt:1-28`.
- **Observed behavior:** Defines UAV-specific rules referencing `EDD_NODE_SPEED`, `EDD_ENERGY_SOC`, and `EDD_QUEUE_RATIO` without a default catch-all nominal fallback at priority `10` (unlike `rules_vdtn.txt` and `rules_ntn.txt`) and was never executed in `final_11seed/` or `exploratory_v1_0_controls/`. Labeled `Implemented, experimental / Not evaluated in v1.0`.

### DISC-07 — End-of-Horizon Sentinel Value in `downlink_t90_sec`
- **Where:** `src/report/DtnmaRlMetricsReport.java` (`downlink_t90_sec`).
- **Observed behavior:** When `downlink_90pct_reached == 0.0` (e.g., mode `M2` in `compB_exp_groupflood_stress`, which receives zero remote wave packets), `downlink_t90_sec` outputs `30849.0` (end of evaluation horizon). Downstream parsers and papers must check `downlink_90pct_reached` (or mode applicability) before averaging `downlink_t90_sec` (`analyze_final_11seed.py` filters `< 0`, whereas `30849.0 >= 0`).

### DISC-08 — Retired Primal-Dual CMDP Fields & Method Name Retained for Compatibility
- **Where:** `src/routing/DtnmaConfig.java:63-66`, `src/routing/ShieldedCmdpRlAgent.java:29, 136`, `scenarios/dtnma_params_default.txt:29-32`.
- **Observed behavior:** Dynamic Lagrangian multiplier updates were retired (`lambda = 0.0`, `rlEtaLambda = 0.0`), leaving hard constraints solely to `DtnmaSafetyShield`. However, the getter `ShieldedCmdpRlAgent.getLagrangianValue(int, double[])` (computing reward `wReward[a] * phi`) and config keys `rlLambdaInit`..`rlLambdaMax` remain for backward compatibility with older configs and tests.

### DISC-09 — `DtnmaUnitTests.java` Method vs. Label Count
- **Where:** `tests/DtnmaUnitTests.java` (`27` `test*` methods, `30` `check()` calls).
- **Observed behavior:** `test7AllCriticalFullBufferZeroOvershoot` emits both `Test7` and `Test8` check labels inside a single test method, producing `30` `check()` assertions across `27` test functions.
