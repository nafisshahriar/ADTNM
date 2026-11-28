# Appendix C: Exhaustive Reference Tables

> Complete lookup reference for all `26` `DtnmaConfig` parameters, `8` `DtnmaPolicyProfile` gears (`P0–P7`), `15` EDD indicators, `4` `rules_vdtn.txt` rules, `16` scenario/rule configs, `21` `DtnmaAuditReport` columns, and primary reporter keys.

---

## 1. Active `DtnmaConfig` Settings (`src/routing/DtnmaConfig.java`)

Override any key in `scenarios/dtnma_params_default.txt`, via `DtnmaRouter.<key> = <val>`, or per host group via `Group.<key> = <val>`:

| Key (`DtnmaConfig`) | Type | Default | Valid Clamp / Notes |
|---|---|---|---|
| `managementMode` | Enum | `M2_MOBILITY_AWARE` | `M0_STATIC`, `M1_REMOTE_ONLY`, `M2_AUTONOMIC_SRL`, `M2_MOBILITY_AWARE` (`AUTO` aliases `M2_MOBILITY_AWARE`) |
| `interventionTier` | Enum | `TIER3_FULL_WITH_REPLICA_CAPS` | `TIER1_PROTECTION_ONLY`, `TIER2_PROTECTION_INGRESS_ENERGY`, `TIER3_FULL_WITH_REPLICA_CAPS` |
| `staticQuota` / `staticIngressMode` / `staticBackpressure` | `int` / `int` / `bool` | `6` / `0` / `true` | `quota` `[1..32]`, `ingress` `[0..2]` |
| `enablePriorityProtection` | `boolean` | `true` | Enables critical buffer reserve & admission protection |
| `densityAdaptiveBeacons` | `boolean` | `true` | Adapts `16 B` peer contact beacon refresh interval with node density |
| `ackFeedbackEnabled` | `boolean` | `true` | Enables `4 B` delayed ACK pruning exchange |
| `beaconRefreshInterval` / `ackCacheSize` / `ackMaxPerContact` | mixed | `5.0` / `512` / `32` | `>= 0.5 s`, `[32..10000]`, `[1..512]` |
| `shieldCriticalQueue` / `shieldCongestionQueue` | `double` | `0.94` / `0.90` | Critical `>= 0.50`, Congestion `[0.40..critQ]` |
| `shieldCriticalCritQueue` / `shieldWarnCritQueue` | `double` | `0.50` / `0.35` | Un-ACKed critical pressure thresholds |
| `shieldSurgeQueue` / `shieldSurgeDrift` | `double` | `0.82` / `0.015` | Surge queue `>= 0.30`, drift `[0.001..0.50]` |
| `shieldStormQueue` / `shieldStormDensity` | `double` | `0.78` / `5.0` | Blocks `{P6, P7}` under dense congestion |
| `shieldEnergyMin` | `double` | `0.20` | `[0.0..0.90]` (set to `0.25` in `M0e-25` and remote `V1` wave) |
| `mobHorizonSec` | `double` | `0.0` (`600.0` in `params`) | Energy time-to-depletion horizon (`>= 30.0 s` when > 0) |
| `mobTtdWarnRatio` / `mobTtdCritRatio` | `double` | `1.10` / `0.60` | Energy-horizon cap/throttle/hold ratios |
| `mobShortContactSec` / `mobLongIctSec` | `double` | `12.0` / `180.0` | Short-contact replica cap and long-ICT buffer scaling anchors |
| `mobAblationMode` | String | `JOINT` | `JOINT`, `ENERGY_ONLY`, `BUFFER_ONLY`, `REPLICA_ONLY` |

---

## 2. All `8` Discrete Control Profiles (`src/routing/DtnmaPolicyProfile.java:23-32`)

| Index | Name & `ARI://DTNMA/ACT/...` Tag | Quota `L` | Backpressure `bp` | Focus Margin / Min | Ingress Mode | BP Enabled |
|---:|---|---:|---:|---:|---:|:---:|
| **`P0`** | `SAFE_EMERGENCY_HOLD` | `1` | `0.05` | `0.35 / 0.60` | `2` (`CRIT_ONLY`) | `true` |
| **`P1`** | `SAFE_THROTTLE_STRICT` | `2` | `0.08` | `0.30 / 0.55` | `1` (`THROTTLE_BULK`) | `true` |
| **`P2`** | `BALANCED_CONSERVATIVE` | `4` | `0.10` | `0.28 / 0.50` | `0` (`ACCEPT_ALL`) | `true` |
| **`P3`** | `BALANCED_AGILE_FOCUS` | `6` | `0.12` | `0.25 / 0.45` | `0` (`ACCEPT_ALL`) | `true` |
| **`P4`** | `STANDARD_SPRAY_FOCUS` | `6` | `0.15` | `0.25 / 0.45` | `0` (`ACCEPT_ALL`) | `false` |
| **`P5`** | `EXPLORE_AGILE_SPRAY` | `8` | `0.15` | `0.20 / 0.40` | `0` (`ACCEPT_ALL`) | `true` |
| **`P6`** | `HIGH_SPRAY_BACKPRESSURE` | `10` | `0.15` | `0.25 / 0.45` | `0` (`ACCEPT_ALL`) | `true` |
| **`P7`** | `HIGH_SPRAY_OPEN` | `12` | `0.30` | `0.20 / 0.40` | `0` (`ACCEPT_ALL`) | `false` |

---

## 3. All `15` EDD Telemetry Metrics (`src/routing/EddTelemetrySampler.java`)

| Constant Key | Formula / Source | Range | Primary Consumer |
|---|---|---|---|
| `EDD_QUEUE_RATIO` | $(B_{\text{tot}} - B_{\text{free}}) / B_{\text{tot}}$ | `[0.0, 1.0]` | Rules, Shield (`S1, S3`), `M2-Mob` |
| `EDD_CRIT_QUEUE_RATIO` | $\sum_{\text{CRIT}} |m| / B_{\text{tot}}$ | `[0.0, 1.0]` | Rules (`PRIO 100`), Shield (`S1`), `M2-Mob` |
| `EDD_QUEUE_DRIFT` | EWMA ($\alpha=0.30$) of $\Delta Q$ | `[-1.0, 1.0]` | Rules (`PRIO 80`), Shield, `M2-Mob` |
| `EDD_NODE_DENSITY` | Distinct peers in trailing `300.0 s` | `[0, N]` | Rules (`PRIO 60`), Shield (`S3`), `M2-Mob` |
| `EDD_ACTIVE_CONNECTIONS` | Open links on host interfaces | `[0, ...]` | Telemetry & audit trace |
| `EDD_NODE_SPEED` | EWMA ($\alpha=0.25$) displacement rate (`m/s`) | `>= 0.0` | Rules (`VDTN_TRAFFIC_JAM`), Mobility class |
| `EDD_ENERGY_SOC` | `clamp(E_rem / E_init, 0, 1)` | `[0.0, 1.0]` | Rules (`< 0.20`), Shield (`S2`), `M2-Mob` |
| `EDD_ENERGY_DRAIN` | EWMA battery drain rate | `>= 0.0` | Computes `EDD_ENERGY_TTD_RATIO` |
| `EDD_NEIGHBOR_MIN_QUEUE` | Minimum `Q` over fresh beacons | `[0.0, 1.0]` | Backpressure |
| `EDD_NEIGHBOR_QUEUE_GRAD` | $Q_{\text{local}} - Q_{\text{nbr}}^{\min}$ | `[-1.0, 1.0]` | Backpressure |
| `EDD_DEST_PROB` | Native PRoPHET-style encounter predictability | `[0.0, 1.0]` | Focus forwarding |
| `EDD_CONTACT_DURATION_MEAN` | EWMA contact duration (`s`) | `>= 0.0` | `M2-Mob` replica caps (`<12 s`) & boosts (`>=25 s`) |
| `EDD_INTER_CONTACT_TIME` | Effective inter-contact time (`s`) | `>= 0.0` | `M2-Mob` ICT-scaled $\theta_Q$ surge guard |
| `EDD_ENCOUNTER_RATE` | Encounters per minute | `>= 0.0` | `M2-Mob` sparse long-contact boost (`< 1.5/min`) |
| `EDD_ENERGY_TTD_RATIO` | Estimated remaining energy TTD / `mobHorizonSec` | `>= 0.0` | `M2-Mob` energy-horizon gating |

---

## 4. Declarative Rule Set (`scenarios/rules_vdtn.txt`) & `21` Audit Columns (`DtnmaAuditReport`)

| Rule ID (`rules_vdtn.txt`) | Priority | Condition (`IF:`) | Actions (`THEN:`) |
|---|---:|---|---|
| `VDTN_BUFFER_CRITICAL` | `100` | `EDD_ENERGY_SOC < 0.20 \|\| EDD_CRIT_QUEUE_RATIO >= 0.50` | `L=1, ing=2, int=600, bp=true` |
| `VDTN_SURGE_THROTTLE` | `80` | `EDD_QUEUE_RATIO >= 0.88 && EDD_QUEUE_DRIFT >= 0.008` | `L=2, ing=1, int=300, bp=true` |
| `VDTN_TRAFFIC_JAM` | `60` | `EDD_QUEUE_RATIO >= 0.75 && EDD_NODE_DENSITY >= 5.0 && EDD_NODE_SPEED < 2.0` | `L=4, ing=0, int=180, bp=true` |
| `VDTN_URBAN_NOMINAL` | `10` | `EDD_QUEUE_RATIO >= 0.0` (Catch-all recovery) | `L=6, ing=0, int=60, bp=true` |

**`DtnmaAuditReport` Columns (`src/report/DtnmaAuditReport.java:1-135`):** `1`: `SimTime` | `2`: `DecisionTime` | `3`: `NodeID` | `4`: `Mode` | `5–10`: `Q, CritQ, dQ, Density, Speed, SoC` | `11–14`: `ActiveConn, CtrlVer, ShieldEnergyMin, ShieldWarnCritQ` | `15–17`: `ProposedAct, FallbackAct, ExecutedAct` | `18`: `ShieldStatus` (`PASS`/`OVERRIDE`) | `19`: `SafeMask` (`8`-bit) | `20`: `ActiveRuleARI` | `21`: `CausalExplanation`.
