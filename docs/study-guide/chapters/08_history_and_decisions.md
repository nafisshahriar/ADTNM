# Chapter 8: Design Evolution, Engineering Post-Mortems & Architectural Decisions

> **Scope:** Traces ADTNM's empirical development from early single-router experiments through substrate composition to the active **Mobility-Aware Distributed Resource Coordination (`M2_MOBILITY_AWARE`)** framework, distilling five engineering post-mortems and four Architectural Decision Records (`ADRs`).

---

## 1. Major Design Phases

| Phase | Core Transition | Scientific Lesson Learned |
|---|---|---|
| **Phase 1: Single-Router & Policy Exploration** | Initial `DtnmaRouter`, declarative `SRL`, Safety Shield, and experimental RL/gossip prototypes | Online CMDP RL (`lambda` multipliers and weight gossip) oscillated under multi-timescale traffic bursts and trailed deterministic rules; safety had to be hard-enforced deterministically rather than learned asymptotically. |
| **Phase 2: Composable Substrate Pivot** | Extracted `DtnmaAgent` and `DtnmaManagedRouter` (`Managed*Router` adapters) separating management from forwarding | Reduced per-protocol integration to thin `201–305` LOC delegation hooks while eliminating `unique_critical_lost` across four distinct forwarders (`SprayAndWait`, `PRoPHETv2`, `MaxProp`, `Epidemic`). |
| **Phase 3: Telemetry & Audit Hardening** | Added pre-decision telemetry locking, `CritQ` shield gates, Tier `1–3` decomposition, and `ACK`-pruned duplicate accounting | Eliminated unintended idle over-throttling and tick-timing drift while achieving `100.00%` traceable `ARI` provenance (`DtnmaAuditReport`). |
| **Phase 4: Mobility-Aware Consolidation (Current)** | Retired experimental RL/gossip and legacy single-regime presets; introduced `M2_MOBILITY_AWARE`, 4 first-class mobility EDDs (`15` total), ablations (`EnergyOnly`/`BufferOnly`/`ReplicaOnly`), multi-mobility campaigns (`mob_*.txt`, `sens_*.txt`), and `DtnmaMetricsReport` | Closed-form joint coordination of energy time-to-depletion, ICT-scaled surge thresholds, and contact-duration replica caps lifts stress survival (`+18.42` alive over `M2-SRL`) and bulk delivery (`+3.68 pp`) while preserving benign throughput and cross-substrate generality. |

---

## 2. Key Engineering Lessons & Post-Mortems

### PM-1: Unintended Over-Throttling & `CritQ` Separation
Early pilot runs locked healthy nodes into `SAFE_EMERGENCY_HOLD` (`P0`) too frequently because the shield inspected aggregate queue fill `Q` without distinguishing critical message saturation (`CritQ`). Separating `CritQ >= 0.35 / 0.50` thresholds from `BULK_` occupancy in `DtnmaSafetyShield` fixed idle over-throttling.

### PM-2: Pre-Decision Telemetry Snapshots for `100%` `ARI` Traceability
Locking the pre-decision telemetry snapshot right before coordinator/shield evaluation keeps `DtnmaAuditReport` `100.00%` deterministic (`traceable_ari_ratio: 1.0000`) and eliminates within-tick arrival race artifacts.

### PM-3: Cohort Horizon Tracking (`DtnmaMetricsReport`)
Over multi-hour runs, short `5`-minute bursts (`Event A`) were washed out by whole-mission averages. Fixed-horizon creation-cohort windows (`Events A, B, C` over `3,600 s` horizons with `43,200 s` `endTime`) isolate acute surge dynamics without right-censoring.

### PM-4: Retirement of Online CMDP RL, Gossip & Single-Regime Legacy Presets
Online CMDP RL (`ShieldedCmdpRlAgent`, `DtnmaM3RlRouter`, peer weight gossip) and superseded single-regime presets were removed from the active codebase after empirical evaluations showed closed-form mobility coordination (`M2_MOBILITY_AWARE`) and declarative rules (`M2_AUTONOMIC_SRL`) deliver higher survival, better benign preservation, lower overhead, and full human auditability.

### PM-5: Separating `ACK`-Pruned Duplicates from Live Critical Loss
In `1 h` smoke runs, `DtnmaDecisionReport` counts harmless `ACK`-pruned duplicate evictions (`53`) inside generic critical drops whereas `DtnmaMetricsReport` records `unique_critical_lost: 0` (`critical_buffer_dropped_acked: 53`). Both counters remain clearly explained for newcomers.

---

## 3. Architectural Decision Records (`ADRs`)

| ADR | Architectural Decision | Rejected Alternative | Primary Rationale |
|---|---|---|---|
| **ADR-01** | **Substrate Composition (`DtnmaManagedRouter`)** | Rewriting per-protocol subclasses from scratch | Cuts per-protocol integration to thin `Managed*Router` adapters + shared `DtnmaAgent` (`956` LOC) |
| **ADR-02** | **Finite `8`-Profile Action Catalog (`P0..P7`)** | Continuous unquantized tuning of `L` and `bpRatio` | Guarantees `O(8)` bitmask safety decidability (`11111100`) & human-readable `ARI` logs |
| **ADR-03** | **Closed-Form `M2_MOBILITY_AWARE` & Three-Factor Ablations** | Online Q-learning (`M3` + `648 B` weight gossip) | Eliminates RL cold-start/instability; cleanly isolates Energy, Buffer, and Replica mechanisms |
| **ADR-04** | **Isolated Confirmatory vs. Sensitivity Pools** | Pooling sensitivity runs with confirmatory runs | Protects eight Holm-Bonferroni confirmatory families (`mob_*.txt`, `n=19`) from exploratory tuning bias (`sens_*.txt`, `n=11`) |
