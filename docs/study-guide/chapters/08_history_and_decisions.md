# Chapter 8: Design Evolution, Engineering Post-Mortems & Architectural Decisions

> **Scope:** Traces ADTNM's empirical development from early single-router RL experiments through the architecture-first refactoring (`v1.0-freeze` and exploratory follow-ups), distilling seven engineering post-mortems and core Architectural Decision Records (`ADRs`).

---

## 1. Major Design Phases

| Phase | Core Transition | Scientific Lesson Learned |
|---|---|---|
| **Phase 1: Single-Router CMDP RL** | Initial shielded Expected SARSA prototype with continuous beacons and parameterized configuration (`DtnmaConfig`) | Dynamic Lagrangian `lambda` multipliers oscillated during multi-timescale traffic bursts; safety had to be hard-enforced deterministically rather than learned asymptotically. |
| **Phase 2: Composable Substrate Pivot** | Extracted `DtnmaAgent` and `DtnmaManagedRouter` interface (`Managed*Router` adapters) separating management from routing | Reduced per-protocol integration cost from `~400` lines of subclass logic to `~253.5` lines of thin delegation hooks while enabling portable `.txt` policy files. |
| **Pilot Diagnostics & Pre-Freeze Protocol** | Introduced atomic decision-time snapshots, separated `CritQ` shield gates, added Tier `1–3` ablation, and split `ACK`-pruned evictions | Eliminated `41%` idle over-throttling and `0.1%` tick-timing replay drift; demonstrated `100.00%` deterministic offline audit replay. |
| **Confirmatory `v1.0` & Post-Freeze Controls** | Executed `627` held-out confirmatory urban VDTN runs (`7103–7207`), followed by `176` exploratory control runs (`7211–7307`) on `M0e-20/25`, `M2-LocalOnly`, and `M1-F-Group` | Exposed the trade-off between stress survival and benign throughput, isolated the `0.25` energy-guard contribution, and confirmed `M3` RL trails `M2` `SRL`. |

---

## 2. Seven Empirical Engineering Post-Mortems

### PM-1: Unintended Over-Throttling of Idle Agents (`41% -> 1.8%` `P0` Occupancy)
Early pilot runs locked healthy nodes into `SAFE_EMERGENCY_HOLD` (`P0`) for `41%` of decision epochs, severely depressing normal bulk throughput. Investigation revealed two interacting flaws: the shield's `S1` rule inspected only aggregate queue fill `Q` without distinguishing critical message saturation (`CritQ`), and early RL hold penalties distorted action preferences. Separating `CritQ >= 0.35 / 0.50` thresholds from `BULK_` occupancy in `DtnmaSafetyShield` and rebalancing epoch rewards dropped idle `P0` hold time to `1.8%` cleanly.

### PM-2: Resolving a `0.1%` Replay Drift via Atomic Pre-Decision Snapshots
Offline verification initially flagged minor rule-evaluation discrepancies (`~0.1%` of epochs) where a queue hovered right across a rule threshold. Logging was sampling host buffer occupancy *after* transfers within the tick rather than capturing the exact vector seen by the rule engine. Locking an immutable pre-decision snapshot (`eddSnapshot[11]`) atomically ahead of evaluation brought offline replay fidelity to **`100.00%`** across **`27.1 million`** audited rows.

### PM-3: Why Whole-Mission Averages Mask Short Surges (`Event A` Cohort Tracking)
Over `12`-hour runs, short `5`-minute acute bursts (`Event A`, `3600–3900 s`) were numerically washed out by long periods of moderate load. Introducing fixed-horizon creation-cohort windows (`Events A, B, C` with a `3,600 s` observation horizon before `endTime = 43,200 s`) prevented right-censoring and quantified remote stale throttling (`389.1` node-s/N in `M1-U` vs `0.0` in `M2`).

### PM-4: Avoiding Broadcast Strawmen with Single-Copy Group Flooding (`M1-F-Group`)
The initial flooded remote baseline (`M1-F`) transmitted `120` individual per-destination bundles across the network (`118.7 kJ`), burning out batteries cleanly (`13.5/126` alive). Post-freeze exploratory evaluation added `M1-F-Group` (one shared group-addressed wave bundle), slashing flooding energy by `589×` to **`201.6 J`** and rescuing `99.5/126` stress survivors—demonstrating fairly that even near-optimal remote flooding (`482.9 s` median adoption) remains slower than local sub-minute autonomy (`300 s` surges).

### PM-5: Honest Reporting of Failed Survival Non-Inferiority (`H2_orig`)
Pre-registered survival non-inferiority (`M2` within `-5.0 pp` of `M1-U` at `12 h` stress) failed at **`-5.48 ± 1.96 pp`** (`83.8` vs `90.7/126`, `0/11` wins). Rather than hiding the shortfall after shifting `H2` to `Event C` bulk, both outcomes are reported side-by-side in Chapter 6 alongside exploratory `M0e-25` and `M2-LocalOnly` causal decomposition.

### PM-6: Why Primal-Dual Lagrangian (`lambda`) Was Retired
Dynamic dual `lambda` updates could not track non-stationary multi-timescale DTN congestion fast enough, causing overshoot during sudden bursts. Hard constraints were consolidated into deterministic bitmask gating (`DtnmaSafetyShield`), leaving zeroed `lambda = 0.0` fields purely as backward-compatible configuration shims.

### PM-7: Separating `ACK`-Pruned Duplicates from Live Critical Loss
In the `1 h` smoke scenario, `DtnmaDecisionReport` reports `53` critical drops whereas `DtnmaRlMetricsReport` records `unique_critical_lost: 0` (`critical_buffer_dropped_acked: 53`). The `53` evictions were redundant replicas already acknowledged via `4 B` contact ACKs (Tier 1 reclamation) rather than live message losses. Filtering ACKed duplicates cleanly inside `DtnmaDecisionReport` remains queued for a future code release.

---

## 3. Architectural Decision Records (`ADRs`)

| ADR | Architectural Decision | Rejected Alternative | Primary Rationale |
|---|---|---|---|
| **ADR-01** | **Substrate Composition (`DtnmaManagedRouter`)** | Rewriting routing subclasses (`EnergyAwareEpidemic`) | Cuts per-protocol code to `~253.5` LOC adapters + portable `12`-line rule files |
| **ADR-02** | **Finite `8`-Profile Action Catalog (`P0..P7`)** | Continuous tuning of `L` and `bpRatio` | Guarantees `O(8)` bitmask safety decidability (`11111100`) & clean `ARI` logs |
| **ADR-03** | **Three-Tier Experimental Ablation (`T1/T2/T3`)** | Single monolithic enable toggle | Isolates critical protection (`T1`) & ingress/energy policy (`T2`) from routing changes (`T3`) |
| **ADR-04** | **Transparent Disclosure of Weak RL (`M3`)** | Parameter tuning to fit a single scenario | Maintains integrity: deterministic `SRL` beats online linear Q-learning in `v1.0` |
| **ADR-05** | **Strict Seed & Freeze Separation** | Mixing post-freeze tuning with `7103..7207` seeds | Keeps `627` frozen confirmatory VDTN runs byte-reproducible (`6/6` regression verified) |
