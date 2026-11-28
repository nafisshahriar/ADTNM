# Chapter 8: Design Evolution, Engineering Post-Mortems & Architectural Decisions

> **Scope:** Traces ADTNM's empirical engineering progression toward **Mobility-Aware Distributed Resource Coordination (`M2_MOBILITY_AWARE`)** over managed DTN substrates, distilling five key engineering post-mortems and four Architectural Decision Records (`ADRs`).

---

## 1. Design Progression & Lessons Learned

| Stage | Core Transition | Engineering Lesson Learned |
|---|---|---|
| **Stage 1: Priority Reserve & ACK Pruning** | Added `4`-tier forwarding prioritization, byte overshoot reservation, and `4 B` delayed `ACK` pruning inside `DtnmaRouter` | Separating critical protection from duplicate replica retention eliminates `unique_critical_lost` under queue saturation (`0` live loss). |
| **Stage 2: Composable Substrate Pivot** | Extracted `DtnmaAgent` and `DtnmaManagedRouter` (`Managed*Router` adapters) separating management from forwarding | Cut per-protocol integration to thin `201–305` LOC delegation hooks while wrapping `SprayAndWait`, `PRoPHETv2`, `MaxProp`, and `Epidemic` uniformly. |
| **Stage 3: Telemetry & Audit Hardening** | Added pre-decision telemetry locking, `CritQ` shield gates, `21`-column `DtnmaAuditReport`, and `DtnmaMetricsReport` cohorts | Eliminated unintended idle over-throttling and tick-timing drift while achieving `100.00%` traceable `ARI` provenance. |
| **Stage 4: Mobility-Aware Joint Coordination (Current)** | Introduced `M2_MOBILITY_AWARE`, `4` first-class mobility EDDs (`15` total), ablations (`EnergyOnly`/`BufferOnly`/`ReplicaOnly`), and multi-mobility suites (`mob_*.txt`, `sens_*.txt`) | Closed-form coordination of energy time-to-depletion, ICT-scaled surge thresholds, and contact-duration replica caps lifts stress survival (`+18.42` alive over `M2-SRL`) and bulk delivery (`+3.68 pp`) while preserving benign throughput and cross-substrate generality. |

---

## 2. Key Engineering Lessons & Post-Mortems

### PM-1: Unintended Over-Throttling & `CritQ` Separation
Early checks locked healthy nodes into `SAFE_EMERGENCY_HOLD` (`P0`) too aggressively when checking aggregate queue occupancy `Q` without checking critical message saturation (`CritQ`). Separating `CritQ >= 0.35 / 0.50` thresholds from `BULK_` occupancy in `DtnmaSafetyShield` fixed idle over-throttling.

### PM-2: Pre-Decision Telemetry Snapshots for `100%` `ARI` Traceability
Locking the pre-decision telemetry snapshot before coordinator/shield evaluation keeps `DtnmaAuditReport` `100.00%` deterministic (`traceable_ari_ratio: 1.0000`) and eliminates within-tick arrival race artifacts.

### PM-3: Cohort Horizon Tracking (`DtnmaMetricsReport`)
Over `12 h` runs, short `5`-minute bursts (`Event A`) were diluted by full-mission averages. Fixed-horizon creation-cohort windows (`Events A, B, C` evaluated over `3,600 s` horizons within a `43,200 s` run) isolate acute surge dynamics cleanly without right-censoring.

### PM-4: Separating `ACK`-Pruned Duplicates from Live Critical Loss
In `1 h` smoke runs, `DtnmaDecisionReport` counts harmless `ACK`-pruned duplicate evictions (`53`) inside generic critical drops whereas `DtnmaMetricsReport` records `unique_critical_lost: 0` (`critical_buffer_dropped_acked: 53`). Documenting both side by side prevents misinterpretation.

### PM-5: Sub-Additive Module Complementarity in Ablations
Controlled single-factor ablations (`EnergyOnly`, `BufferOnly`, `ReplicaOnly`) established that combined `M2-Mob` yields `+18.42` surviving nodes vs. a linear sum of isolated gains `+41.84` ($p = 1.73\times 10^{-14}$), confirming overlapping bottleneck mitigation rather than artificial tuning.

---

## 3. Architectural Decision Records (`ADRs`)

| ADR | Architectural Decision | Rejected Alternative | Primary Rationale |
|---|---|---|---|
| **ADR-01** | **Substrate Composition (`DtnmaManagedRouter`)** | Rewriting per-protocol subclasses from scratch | Cuts per-protocol integration to thin `Managed*Router` adapters + shared `DtnmaAgent` (`956` LOC) |
| **ADR-02** | **Finite `8`-Profile Action Catalog (`P0..P7`)** | Continuous unquantized tuning of `L` and `bpRatio` | Guarantees `O(8)` bitmask safety decidability (`11111100`) & human-readable `ARI` logs |
| **ADR-03** | **Closed-Form `M2_MOBILITY_AWARE` & Three-Factor Ablations** | Black-box adaptive parameter search | Eliminates cold-start instability and keeps every decision human-auditable via `ARI` traces |
| **ADR-04** | **Isolated Confirmatory vs. Sensitivity Pools** | Pooling sensitivity runs with confirmatory runs | Protects eight Holm-Bonferroni confirmatory families (`mob_*.txt`, `n=19`) from exploratory tuning bias (`sens_*.txt`, `n=11`) |
