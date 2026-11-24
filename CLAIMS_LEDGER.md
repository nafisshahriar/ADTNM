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

## 4. Post-Freeze Deviations, Honest Limitations & Revised Framing (Post-v1.0 Audit)

This section documents methodological deviations and analytical clarifications added **after** freezing `v1.0-tnsm-freeze` (`ca5d9ac`) and completing all 715 held-out runs (`7103..7207`), without altering `v1.0` data or moving `v1.0-tnsm-freeze`.

1. **H2 Deviation & Failed Survival Non-Inferiority:** `H2` was changed on dev seed `4001` before `v1.0-tnsm-freeze` from full-12h survival non-inferiority (`M2` vs `M1-U`, margin `delta = 5 pp`) to Stress Event C `eventC_bulk_deliv` (`+19.21 pp`, `p_holm = 1.575e-06`). Under the original survival non-inferiority endpoint across `7103..7207`, `M2` loses to `M1-U` on `11/11` seeds (`-5.483 ± 1.958 pp`, 95% CI `[-7.442, -3.525]`), **failing the `5 pp` non-inferiority margin**.
2. **Pareto Position Without Dominance & Missing Energy-Only / Hybrid-Isolation Controls:** `M2` sits on the Pareto frontier rather than dominating `M1-U` or conservative statics: `M1-U` leads on Stress survival (`90.7` vs `83.8` alive) and Stress bulk delivery (`73.15%` vs `66.43%`), while `M0c`/`M0d` preserve `126.0/126` nodes at severe benign cost (`-28.13`/`-23.04 pp`), and `M2` still loses `~42.2/126` nodes under sustained Stress starvation. Because `M0c` bundled `Q >= 0.88` with `SoC < 0.25`, two post-freeze exploratory **energy-only static controls** (`M0e-20`: `SoC < 0.20`, `M0e-25`: `SoC < 0.25`) plus **`M2-LocalOnly`** (`M2` blocking remote `CTRL_UPDATE_*` mutations) are added on fresh primes `7211..7307` to separate local adaptation from remote strategy overlays.
3. **Oracle Remote Trigger, Unicast Control-Flood Artifact (`M1-F` vs `M1-F-Group`), & Post-Freeze Regression:** `M1-U` and `M1-F` receive oracle manager commands at exact event onset (`3600 s`, `7200 s`, `21600 s`) with zero uplink detection delay, making remote dissemination lag conservative. In addition, `v1.0` `M1-F` (`118.7 kJ`, `13.5` alive) reflects flooding `720` unicast per-agent `CTRL_UPDATE_*` bundles (`120/wave`), rather than flooding **one shared group-addressed policy bundle per wave** (`CTRL_GROUP_*`, `6` IDs total); `M1-F-Group` (`DtnmaM1FloodGroupRouter`) is added on `7211..7307` to isolate true flooding efficiency. Confirmatory `v1.0` numbers remain reproducible on the post-freeze commit (`6/6` regression check on `M0b`, `M1-U`, `M2` for seeds `7103` and `7109`: exact SHA-256 on `MessageStatsReport` & `DtnmaRecoveryTimelineReport`, exact non-CPU metrics on `DtnmaRlMetricsReport`).
4. **Event A Interpretation & Structural Zero Stale Throttle:** In Event A (`3600–3900 s`), `M2` and `M1-U` tie on both `eventA_nodesec_q90` (`7.941` vs `7.943 s`, `p = 0.981`) and cohort bulk delivery (`87.46%` vs `87.38%`, `p = 0.786`). Event A isolates avoidable post-event stale throttling by remote commands (`389.06` vs `0.00` node-s/N), where `M2`'s exact `0.0000` is structural by construction: local rule triggers (`SoC < 0.20`, `Q >= 0.88 && dQ >= 0.008`) are strict subsets of `atRisk` (`Q >= 0.82 || dQ > 0.015 || SoC < 0.25`), guaranteeing zero stale actuation whenever `!atRisk`.
5. **Reaction Latency Reconciliation:** Distinguish **agent evaluation tick latency** (`<= 1.0 s` once a node satisfies its rule predicate) from **at-risk-to-throttle actuation delay** (`487.7 s` Event A, `174.4 s` Event B, `186.4 s` Event C median for `M2`).
6. **Modularity Overhead & Amortized Code Footprint (`A3`):** `EnergyAwareEpidemicRouter` outperforms `ManagedEpidemicRouter` by `+3.14 ± 0.76 pp` bulk / `+1.91 ± 1.02` alive (`+3.39 pp` / `+2.27` vs explicit `TIER2`) because it inspects unquantized in-memory peer state and prunes ACKed bundles immediately on encounter, whereas DTNMA exchanges 16-byte quantized beacons and prunes lazily on eviction. This difference is modularity overhead rather than parity. Code size comparison is reported explicitly on an amortized basis: `2,093` non-comment Java lines for the shared management core (`DtnmaAgent` `1,200` + rule engine `397` + `EddTelemetrySampler` `241` + `DtnmaSafetyShield` `255`) plus `202–305` (mean `254`) adapter lines per router vs `394` lines for one hard-coded single-policy router. Because `2,093 + 254n + 12` vs `394n` breaks even in total Java lines only at **`~15` routers**, the practical architectural benefit is **marginal cross-router policy edit cost (`12` declarative lines vs modifying 4 routers) and uniform deterministic auditability**, not total LOC.
7. **Negative Result (`M3`) & `MaxProp` Benign Cost:** Online CMDP RL + gossip (`M3`) is reported as a negative result vs concise declarative SRLs (`M2`) over 12 h (`-3.03 pp` Stress bulk, `-7.8` Stress alive, `-2.06 pp` Benign bulk), and `MaxProp + DTNMA` incurs a `-9.42 pp` Benign bulk cost (`0.9026 -> 0.8084`) to raise node survival from `63.3` to `126.0`.
