# ADTNM Preregistered Claims Ledger

Written prior to freezing the final camera-ready tag (`v1.0-tnsm-freeze`) and executing the
11 held-out fresh prime seeds (`7103..7207`).

## 1. Primary Confirmatory Hypotheses (Comparison B, Holm-Bonferroni Family m = 4)

All tests use paired seed-level observations (`n = 11`, `df = 10`) across exact
matched seeds on `compB_multitimescale` (Stress: `initialEnergy = 2650, 3650`)
and `compB_benign` (Benign: `initialEnergy = 5500, 7500`). All four primary endpoints
use two-sided paired t-tests with Holm-Bonferroni step-down multiplicity adjustment (`m = 4`).

| ID | Comparison | Scenario & Window | Endpoint | Test & Direction |
|---|---|---|---|---|
| **H1** | `M2` (`DtnmaM2SrlRouter`) vs `M1-U` (`DtnmaM1RemoteRouter`) | Stress, Event A (`3600–3900 s`) | `eventA_stale_throttle_nodesec` (lower is better) | Two-sided paired t-test (Holm step): tests the 5-min timescale RFC 9675 claim directly (late remote commands arrive after Event A ends and cause stale throttling, whereas local SRLs release promptly) |
| **H2** | `M2` (`DtnmaM2SrlRouter`) vs `M1-U` (`DtnmaM1RemoteRouter`) | Stress, Event C (`21600–28800 s`) | `eventC_bulk_deliv` (higher is better) | Two-sided paired t-test (Holm step): tests the 2h sustained shift where remote updates do converge (`T90 < 7200 s`); selective local adaptation preserves bulk throughput compared to coarse fleet-wide remote throttling |
| **H3** | `M2` (`DtnmaM2SrlRouter`) vs `M0b` (`DtnmaM0bStaticRouter`) | Stress (full 12h) | `alive_nodes_end / N` (higher is better) | Two-sided paired t-test (Holm step) |
| **H4** | `M2` (`DtnmaM2SrlRouter`) vs `M0c` (`DtnmaM0cV1GuardRouter`) | Benign (full 12h) | `bulk_delivery_prob` (higher is better) | Two-sided paired t-test (Holm step) |

## 2. Architecture, Composition & Verification Claims (Comparison A & Equivalence)

| Claim | Experiment | Criterion |
|---|---|---|
| **A1 (Router-agnostic critical protection)** | Comparison A across 4 regimes (Benign, Overload `5M`, Energy Blackout, Sparse NTN) x 4 routers (`SnW, PRoPHETv2, MaxProp, Epidemic`) | Composed `+DTNMA` eliminates un-ACKed critical buffer loss (`unique_critical_lost = 0`, `max_buffer_occupancy <= 1.0`) across all 4 routing substrates using declarative rules only. |
| **A2 (Intervention Tier decomposition)** | `compA_tiers` (`TIER1..3` x 4 routers) | Tier 1 (protection + ACK only) drives `unique_critical_lost = 0`; Tier 2 adds energy/ingress shedding without touching routing logic; Tier 3 adds adaptive replica caps. |
| **A3 (Policy-equivalent hand-coded baseline vs rules LOC)** | `EnergyAwareEpidemicRouter` vs `ManagedEpidemic (M2, TIER2)` | Matches Tier 2 policy outcomes closely on matched seeds while quantifying declarative rules lines (`rules_vdtn.txt` / `rules_ntn.txt`) vs per-router Java LOC. |
| **E1 (Determinism & bit-for-bit Tier 1 parity)** | `compA_equivalence` (`equivalenceConnOuterOrder = true`) rerun hash + `PrioritySprayAndWaitRouter` | Identical SHA-256 report hashes on rerun; exact parity on `MessageStatsReport` and `DtnmaRlMetricsReport` (excluding wall-clock `decision_cpu_us_mean`). |
| **E2 (Default global HoL ordering gap)** | `compA_equivalence_default` (`equivalenceConnOuterOrder = false`) | Two one-sided tests (TOST) within `+/- 1 pp` on bulk delivery probability across 11 seeds. |
| **R1 (Auditability & Replay, RQ4)** | `DtnmaAuditReport` + `verify_audit_replay()` | `100.0%` causal ARI traceability and deterministic replay (`SafeMask`, action, static SRL ARI). |

## 3. Exploratory Outcomes (Not Adjusted for Multiple Testing)

- Full-12h `alive_nodes_end / N` and `bulk_delivery_prob` Pareto trade-off across `M0a`, `M0b`, `M0c`, `M0d`, `M1-U`, `M1-F`, `M2`, and `M3` (including paired 95% CI for `M2` vs `M1-U` on `alive_nodes_end / N`, documenting transparently where coarse fleet-wide `M1-U` throttling trades Event C / Benign bulk throughput for higher survival).
- Event A `eventA_nodesec_q90`, `eventA_bulk_deliv`, `eventA_react_med_s`, and `eventA_react_censored_frac`.
- Event B (`7200–9000 s`) and Event C (`21600–28800 s`) per-class delivery, latency (`med`/`p95`), buffer evictions (`crit_evicted` for un-ACKed vs `crit_evicted_acked` for ACK-pruned redundant copies alongside `crit_unique_lost`), and TTL expiries.
- `M1-F` (`DtnmaM1FloodRouter`) vs `M1-U` convergence speed (`downlink_t90_sec`) vs radio energy overhead (`total_ctrl_tx_J`, `event{e}_ctrl_tx_J`).
- `M3` (`DtnmaM3RlRouter` + Gossip) vs `M2` learning efficiency and `M0d` conservative benign cost bracket (`throttle_coverage`, `stale_throttle_nodesec`, `event{e}_react_med_s`, `event{e}_react_censored_frac`).
