# Appendix C: Exhaustive Reference Tables

> Complete verified lookup tables for all `48` `DtnmaConfig` parameters, `8` profiles, `11` EDDs, `16+` audit columns, `8` rules, and `11` evaluation modes (`d4a159f`).

---

## 1. All `48` `DtnmaConfig` Settings (`src/routing/DtnmaConfig.java:42-97`)

Override any key in `scenarios/dtnma_params_default.txt` or scenario settings via `DtnmaRouter.<key> = <val>` or `Group.<key> = <val>`:

| Key (`DtnmaConfig`) | Type | Default | Valid Clamp / Notes |
|---|---|---|---|
| `intelligenceMode` | Enum | `SHIELDED_CMDP_RL` | `STATIC_RULES`, `UNSHIELDED_RL`, `SHIELDED_RL_PASSIVE`, `SHIELDED_CMDP_RL` |
| `managementMode` | Enum | `AUTO` | `AUTO`, `M0B_*`, `M0C_*`, `M1_*`, `M2_AUTONOMIC_SRL`, `M3_*` |
| `interventionTier` | Enum | `TIER3_FULL_WITH_REPLICA_CAPS` | `TIER1_PROTECTION_ONLY`, `TIER2_PROTECTION_INGRESS_ENERGY`, `TIER3_*` |
| `staticQuota` / `staticIngressMode` / `staticBackpressure` | `int` / `int` / `bool` | `6` / `0` / `true` | `quota` `[1..32]`, `ingress` `[0..2]` |
| `enablePriorityProtection` | `boolean` | `true` | Enables 5-tier eviction & byte guard |
| `epidemicControlFlooding` | `boolean` | `false` | `true` in `M1-F`; `false` in `M1-U` / `M2` |
| `densityAdaptiveGossip` | `boolean` | `true` | Adapts M3 RL gossip interval with node density |
| `openLoopAuditOnly` | `boolean` | `false` | Logs counterfactual SRL/RL decisions while executing static knobs |
| `gossipEnabled` / `ackFeedbackEnabled` | `boolean` / `boolean` | `true` / `true` | Contact gossip (`M3`) & `4 B` delayed ACK pruning |
| `rlEpochInterval` | `double` | `15.0` | Clamped `[1.0..600.0]` s |
| `rlAlpha` / `rlGamma` / `rlWeightDecay` | `double` | `0.15` / `0.90` / `1e-4` | `[1e-4..1.0]`, `[0..0.999]`, `[0..0.1]` |
| `rlEpsilonStart` / `rlEpsilonMin` / `rlEpsilonDecay` | `double` | `0.15` / `0.01` / `900.0` | Exploration schedule (`decay >= 10.0`) |
| `rlLambdaInit` / `rlEtaLambda` / `rlTargetCost` / `rlLambdaMax` | `double` | `0.0` / `0.0` / `0.30` / `5.0` | Retired primal-dual shims (active `lambda = 0.0`) |
| `rlOptimisticInit` | `double` | `0.0` | Initial Q-weight bias |
| `rewardDirectDelivery` / `AckDelivery` / `PredProgress` / `CoverageGain` | `double` | `2.5` / `1.5` / `1.5` / `1.0` | All `>= 0.0` |
| `penaltyBulkDrop` / `penaltyRelayCost` | `double` | `0.35` / `0.02` | All `>= 0.0` |
| `gossipCooldown` / `WeightMin` / `WeightMax` / `SameClassOnly` / `MinEpochs` | mixed | `120.0` / `0.05` / `0.25` / `true` / `8` | Cooldown `>= 1.0 s`, weights `[0..0.5]`, `minEpochs [1..500]` |
| `beaconRefreshInterval` / `ackCacheSize` / `ackMaxPerContact` | mixed | `5.0` / `512` / `32` | `>= 0.5 s`, `[32..10000]`, `[1..512]` |
| `shieldCriticalQueue` / `shieldCongestionQueue` | `double` | `0.94` / `0.90` | Critical `>= 0.50`, Congestion `[0.40..critQ]` |
| `shieldCriticalCritQueue` / `shieldWarnCritQueue` | `double` | `0.50` / `0.35` | Un-ACKed critical pressure thresholds |
| `shieldSurgeQueue` / `shieldSurgeDrift` | `double` | `0.82` / `0.015` | Surge queue `>= 0.30`, drift `[0.001..0.50]` |
| `shieldStormQueue` / `shieldStormDensity` | `double` | `0.78` / `5.0` | Blocks `{P6, P7}` under dense congestion |
| `shieldEnergyMin` | `double` | `0.20` | `[0.0..0.90]` (elevated to `0.25` in `M0c`/`M0e-25`/wave) |
| `equivalenceConnOuterOrder` | `boolean` | `false` | `true` aligns loop ordering identically with `PrioritySprayAndWaitRouter` |

---

## 2. All `11` EDD Metrics (`src/routing/EddTelemetrySampler.java:26-36`)

| Constant Key | Formula / Source | Range | Used in `v1.0` Rules/Shield/RL? |
|---|---|---|---|
| `EDD_QUEUE_RATIO` | $(B_{\text{tot}} - B_{\text{free}}) / B_{\text{tot}}$ | `[0.0, 1.0]` | Rules, Shield (`S1, S3`), RL |
| `EDD_CRIT_QUEUE_RATIO` | $\sum_{\text{CRIT}} |m| / B_{\text{tot}}$ | `[0.0, 1.0]` | Rules (`PRIO 100`), Shield (`S1`) |
| `EDD_QUEUE_DRIFT` | EWMA ($\alpha=0.30$) of $\Delta Q$ | `[-1.0, 1.0]` | Rules (`VDTN_SURGE_THROTTLE`), Shield |
| `EDD_NODE_DENSITY` | Distinct peers in trailing `300.0 s` | `[0, N]` | Rules (`PRIO 60`), Shield (`S3`) |
| `EDD_ACTIVE_CONNECTIONS` | Open links on host interfaces | `[0, ...]` | RL features |
| `EDD_NODE_SPEED` | EWMA ($\alpha=0.25$) displacement rate (`m/s`) | `>= 0.0` | Rules (`VDTN_TRAFFIC_JAM`), Mobility class |
| `EDD_ENERGY_SOC` | `clamp(E_rem / E_init, 0, 1)` | `[0.0, 1.0]` | Rules (`< 0.20`), Shield (`S2`) |
| `EDD_ENERGY_DRAIN` | EWMA battery drain rate | `>= 0.0` | Computed & exposed; reserved for future `v1.1` slope triggers |
| `EDD_NEIGHBOR_MIN_QUEUE` | Minimum `Q` over fresh beacons | `[0.0, 1.0]` | Backpressure / RL |
| `EDD_NEIGHBOR_QUEUE_GRAD` | $Q_{\text{local}} - Q_{\text{nbr}}^{\min}$ | `[-1.0, 1.0]` | RL / Backpressure |
| `EDD_DEST_PROB` | Native PRoPHET-style encounter predictability | `[0.0, 1.0]` | RL features & reward shaping |

---

## 3. Declarative Rule Files (`scenarios/rules_vdtn.txt` & `rules_ntn.txt`)

| Rule File | Rule ID | Priority | Condition | Actions (`L`, `Ing`, `Telem`, `BP`) |
|---|---|---|---|---|
| `rules_vdtn.txt` | `VDTN_BUFFER_CRITICAL` | `100` | `SoC < 0.20 \|\| CritQ >= 0.50` | `L=1, ing=2, int=600, bp=true` |
| `rules_vdtn.txt` | `VDTN_SURGE_THROTTLE` | `80` | `Q >= 0.88 && dQ >= 0.008` | `L=2, ing=1, int=300, bp=true` |
| `rules_vdtn.txt` | `VDTN_TRAFFIC_JAM` | `60` | `Q >= 0.75 && Dens >= 5.0 && Speed < 2.0` | `L=4, ing=0, int=180, bp=true` |
| `rules_vdtn.txt` | `VDTN_URBAN_NOMINAL` | `10` | `Q >= 0.0` (Catch-all recovery) | `L=6, ing=0, int=60, bp=true` |
| `rules_ntn.txt` | `NTN_CRITICAL_FAILSAFE` | `100` | `SoC < 0.20 \|\| CritQ >= 0.50` | `L=1, ing=2, int=600, bp=true` |
| `rules_ntn.txt` | `NTN_SURGE_THROTTLE` | `80` | `Q >= 0.88 && dQ >= 0.008` | `L=2, ing=1, int=300, bp=true` |
| `rules_ntn.txt` | `NTN_ORBITAL_CLUSTER` | `50` | `Q >= 0.75 && Dens >= 4.0` | `L=4, ing=0, int=180, bp=true` |
| `rules_ntn.txt` | `NTN_DEEP_SPACE_SPARSE` | `10` | `Q >= 0.0` (Catch-all recovery) | `L=6, ing=0, int=60, bp=true` |

---

## 4. `DtnmaAuditReport` TSV Columns (`src/report/DtnmaAuditReport.java:1-137`)

`1`: `simTime` | `2`: `decisionTime` | `3`: `nodeId` | `4`: `modeTag` | `5–10`: `Q, CritQ, dQ, Dens, Speed, SoC` | `11–14`: `ActiveConn, CtrlVer, ShieldEnergyMin, ShieldWarnCritQ` | `15–17`: `proposedProfile, shieldedProfile, activeProfile` | `18`: `PASS / OVERRIDE` | `19`: `8-bit shieldMask` | `20`: `ARI://...` | `21`: `Clause Trace / Override Reason`.
