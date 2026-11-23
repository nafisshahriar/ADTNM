# ADTNM Preregistered Claims Ledger

Written prior to freezing the final camera-ready tag and executing the
11 held-out fresh prime seeds (`7103..7207`).

## 1. Primary Confirmatory Hypotheses (Comparison B, Holm-Bonferroni Family m = 4)

All tests use paired seed-level observations (`n = 11`, `df = 10`) across exact
matched seeds on `compB_multitimescale` (Stress: `initialEnergy = 2650, 3650`)
and `compB_benign` (Benign: `initialEnergy = 5500, 7500`).

| ID | Comparison | Scenario & Window | Endpoint | Test & Direction |
|---|---|---|---|---|
| **H1** | `M2` (`DtnmaM2SrlRouter`) vs `M1-U` (`DtnmaM1RemoteRouter`) | Stress, Event A (`3600–3900 s`) | `eventA_nodesec_q90` (lower is better) | Two-sided paired t-test (Holm step) |
| **H2** | `M2` (`DtnmaM2SrlRouter`) vs `M1-U` (`DtnmaM1RemoteRouter`) | Stress, Event C (`21600–28800 s`) | `alive_nodes_end / N` | Non-inferiority test, pre-specified margin `delta = 0.05` (expect near parity at 2h+ timescale) |
| **H3** | `M2` (`DtnmaM2SrlRouter`) vs `M0b` (`DtnmaM0bStaticRouter`) | Stress (full 12h) | `alive_nodes_end / N` | Two-sided paired t-test (Holm step) |
| **H4** | `M2` (`DtnmaM2SrlRouter`) vs `M0c` (`DtnmaM0cV1GuardRouter`) | Benign (full 12h) | `bulk_delivery_prob` | Two-sided paired t-test (Holm step) |

## 2. Architecture, Composition & Verification Claims (Comparison A & Equivalence)

| Claim | Experiment | Criterion |
|---|---|---|
| **A1 (Router-agnostic critical protection)** | Comparison A across 4 regimes (Benign, Overload `5M`, Energy Blackout, Sparse NTN) x 4 routers (`SnW, PRoPHETv2, MaxProp, Epidemic`) | Composed `+DTNMA` eliminates un-ACKed critical buffer loss (`unique_critical_lost = 0`, `max_buffer_occupancy <= 1.0`) across all 4 routing substrates using declarative rules only. |
| **A2 (Intervention Tier decomposition)** | `compA_tiers` (`TIER1..3` x 4 routers) | Tier 1 (protection + ACK only) drives `unique_critical_lost = 0`; Tier 2 adds energy/ingress shedding without touching routing logic; Tier 3 adds adaptive replica caps. |
| **A3 (Policy-equivalent hand-coded baseline vs rules LOC)** | `EnergyAwareEpidemicRouter` vs `ManagedEpidemic (M2, TIER2)` | Matches Tier 2 policy outcomes closely on matched seeds while quantifying declarative rules lines (`rules_vdtn.txt` / `rules_ntn.txt`) vs per-router Java LOC. |
| **E1 (Determinism & bit-for-bit Tier 1 parity)** | `compA_equivalence` (`equivalenceConnOuterOrder = true`) rerun hash + `PrioritySprayAndWaitRouter` | Identical SHA-256 report hashes on rerun; exact parity on `MessageStatsReport` and `DtnmaRlMetricsReport`. |
| **E2 (Default global HoL ordering gap)** | `compA_equivalence_default` (`equivalenceConnOuterOrder = false`) | Two one-sided tests (TOST) within `+/- 1 pp` on bulk delivery probability across 11 seeds. |
| **R1 (Auditability & Replay, RQ4)** | `DtnmaAuditReport` + `verify_audit_replay()` | `100.0%` causal ARI traceability and deterministic replay (`SafeMask`, action, static SRL ARI). |

## 3. Exploratory Outcomes (Not Adjusted for Multiple Testing)

- Event B (`7200–9000 s`) per-class delivery, latency (`med`/`p95`), buffer evictions, and TTL expiries.
- `M1-F` (`DtnmaM1FloodRouter`) vs `M1-U` convergence speed (`downlink_t90_sec`) vs radio energy overhead (`total_ctrl_tx_J`, `event{e}_ctrl_tx_J`).
- `M3` (`DtnmaM3RlRouter` + Gossip) vs `M2` learning efficiency, `M0d` conservative benign cost bracket, control-timing `throttle_coverage`, `stale_throttle_nodesec`, `event{e}_react_med_s`, and `event{e}_react_censored_frac`.
