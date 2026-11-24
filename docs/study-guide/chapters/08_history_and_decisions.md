# Chapter 8: Engineering History, Post-Mortems & Architectural Decisions

> **Scope:** Traces ADTNM's empirical evolution across git history (`f131849` -> `v1.0-tnsm-freeze` at `ca5d9ac` -> `d4a159f`), documenting real failures uncovered by pilot testing, post-mortems, and Architectural Decision Records (`ADRs`).

---

## 1. Chronological Project Timeline (`git log d4a159f`)

| Milestone / Commit | Date / Phase | Key Changes & Scientific Purpose |
|---|---|---|
| `f131849` -> `f8fcceb` | Phase 1 | Initial CMDP RL prototype, beacon telemetry, config externalization (`DtnmaConfig`), 210-run grid scenarios |
| `f020e96` -> `051af4d` -> `4c5160a` | Phase 1 v2 -> Phase 2 | Fixed Layer-1 ingress bottlenecks, introduced single-critic Q-learning (`648 B` gossip), `PriorityMaxProp`, Holm-Bonferroni stats |
| **`e768ea3`** | **Milestone 2** | **Pivot to Architecture-First:** Built composable `DtnmaAgent` + `Managed*Router` adapters, `M0–M3` presets, unit tests |
| `14bb0ce` -> `3faa6fe` | Milestone 2.5 -> Pilots | Hot-loop `O(N)` buffer scan, **atomic decision snapshots (`100%` replay)**, **CritQ shield & reward fix**, Tiers `1–3` & adoption CDFs |
| `50fecb2` -> `a816143` | Pre-Freeze Protocol | Per-event cohorts (`Event A/B/C`), `M0c`/`M0d`/`M1-F`, `E1` bit-for-bit Tier 1 parity, `EnergyAwareEpidemicRouter`, split ACKed drops, updated `CLAIMS_LEDGER.md` |
| **`ca5d9ac` (`v1.0-tnsm-freeze`)** | **Frozen Confirmatory** | **Locked codebase & executed `715` held-out runs (`seeds 7103..7207`)** (`3394648` statistical tables & audit replay) |
| `96097bc` -> **`d4a159f`** | **Post-Freeze Exploratory** | Added `M0e-20`, `M0e-25`, `M1-F-Group`, `M2-LocalOnly` (`176` runs, `7211..7307`), framing audit, and `6/6` SHA-256 regression check |

---

## 2. Seven Critical Engineering Post-Mortems

### PM-1: Over-Throttling Idle Agents to `P0` (`41% -> 1.8%`)
- **Symptom:** Early pilot RL/shield runs locked nodes into `P0` (`SAFE_EMERGENCY_HOLD`) for `41%` of ticks, starving healthy bulk delivery (`M2/M3` trailed benign throughput badly).
- **Root Cause:** Dual bug — (a) Shield `S1` inspected aggregate `Q` without checking `CritQ >= 0.50` / surge derivative, and (b) RL reward assigned misaligned hold penalties encouraging passive conservation.
- **Fix (`3faa6fe`):** Separated `CritQ` thresholds (`>= 0.35 / 0.50`) in `DtnmaSafetyShield` and fixed RL epoch reward (`computeLocalEpochRewardCost`); idle `P0` dropped to `1.8%` (`<= 3.5 pp` benign spread for `M2` prior to final `82%` match).

### PM-2: `0.1%` Replay Mismatch From Snapshot Ordering
- **Symptom:** Offline `verify_audit_replay` occasionally flagged rules where `Q` logged on an audit line barely crossed a threshold differently than the fired action.
- **Root Cause:** `DtnmaAuditReport` previously sampled host buffer occupancy *after* transfers inside the tick instead of atomically *at decision evaluation time*.
- **Fix (`3faa6fe`):** Captured immutable `double[11] eddSnapshot` immediately before `SRL`/`RL` evaluation; replay rose immediately to **`100.00%`** (`27.1M` CompB rows verified).

### PM-3: Whole-Mission Means Hiding 5-Minute Surges (`Event A` Stale Throttling)
- **Symptom:** `12 h` bulk averages washed out `300 s` burst collapses (`3600–3900 s`), making remote `M1-U` look nearly harmless despite arriving after bursts ended.
- **Fix (`50fecb2`):** Implemented per-event creation cohorts with bounded `+3600 s` horizons (`DtnmaRlMetricsReport`) and explicit `stale_throttle_nodesec`, cleanly establishing **`H1` (`-389.06 node-s/N`)** and **`H2` (`+19.21 pp` Event C)**.

### PM-4: `M1-F` (`118.7 kJ`) Flood Strawman & Post-Freeze Fair Control (`M1-F-Group`)
- **Symptom:** Confirmatory `M1-F` sent `120` separate unicast `CTRL_UPDATE_` bundles via Epidemic flooding (`118,728 J`), burning batteries prematurely (`13.5` alive). Reviewers noted real broadcast/group management only floods **1 shared wave bundle**.
- **Fix (`96097bc`):** Added `M1-F-Group` post-freeze on fresh seeds `7211..7307`: drops cost `589x` to **`201.6 J`** (`99.5/126` alive), confirming survival advantage for remote group dissemination *while still proving* median wave adoption (`482.9 s`) cannot beat `300 s` local events (`H1`).

### PM-5: Outcome Transparency on Failed `H2_orig` Survival Non-Inferiority
- **Lesson:** Pre-registered survival non-inferiority (`M2` within `-5 pp` of `M1-U`) failed at **`-5.48 +/- 1.96 pp`** (`0/11` wins). Instead of concealing the miss after shifting `H2` to `Event C` bulk, `CLAIMS_LEDGER.md` and Ch. `06` record both prominently alongside `M0e-25` / `M2-LocalOnly` causal decomposition.

### PM-6: Why Primal-Dual Lagrangian (`lambda`) Was Retired
- **Lesson:** Dynamic gradient `lambda` updates lagged fast DTN surges and still violated safety constraints before dual step sizes reacted. Moving hard guarantees strictly into `DtnmaSafetyShield` (`S1–S4`) eliminated safety violations while keeping `M3`'s `lambda = 0.0` legacy hooks documented (`DISC-08`).

### PM-7: `DtnmaDecisionReport` ACK-Pruned Drop Discrepancy (`DISC-01`)
- **Lesson:** In 1h smoke (`scenario_dtnma.txt`), `53` already-ACKed critical copies were legitimately evicted from full buffers (Tier 1) with zero live packet loss — yet `DtnmaDecisionReport` reported them under `"Critical Fault Messages Dropped: 53"` while `DtnmaRlMetricsReport` separated `critical_buffer_dropped_acked = 53` from `unique_critical_lost = 0`. Logged in `appendix_discrepancies.md` for `v1.1` fix.

---

## 3. Architectural Decision Records (`ADRs`)

| ADR | Choice Made | Rejected Alternative | Rationale |
|---|---|---|---|
| **ADR-01** | Composition (`DtnmaManagedRouter` wrapping stock routers) | Subclass re-implementation (`EnergyAwareEpidemicRouter`) | Cuts per-protocol code from `394`+ LOC to `253.5` mean adapter LOC + shared `12`-line rule files |
| **ADR-02** | `8` Discrete Control Profiles (`P0..P7`) | Continuous scalar quotas & backpressure knobs | Permits `O(8)` bitmask shield verification (`11111100`) & deterministic ARI replay |
| **ADR-03** | Tiered Ablation (`Tier 1, 2, 3`) | Monolithic toggle (`on/off`) | Isolates routing-agnostic protection (`Tier 1`) & ingress/energy (`Tier 2`) from routing quota caps (`Tier 3`) |
| **ADR-04** | Honest reporting of weak `M3` RL module | Selective scenario overfitting to hide `M3` losses | Preserves scientific integrity; positions `M3` strictly as `Implemented, experimental` |
| **ADR-05** | Docs-only freeze discipline on `v1.0-tnsm-freeze` | Post-freeze tweaks mixed with `7103..7207` seeds | Ensures `715` frozen results remain reproducible byte-for-byte (`6/6` regression verified) |
