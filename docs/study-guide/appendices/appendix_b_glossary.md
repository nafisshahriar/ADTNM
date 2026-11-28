# Appendix B: Exhaustive Glossary & Acronym Index

Every acronym, protocol abbreviation, KPI, and domain term used across the ADTNM framework, simulator scenarios, reporters, and Study Guide is defined below.

- **`ACK` (Delivery Acknowledgment):** `4 B` bundle ID notification exchanged on peer contact (`<= 32` IDs / contact, `512`-entry LRU `deliveredAckSet`) so delivered `CRIT_` replicas become first-evictable for space reclamation.
- **`ADM` (Application Data Model):** RFC 9675 YANG-like specification template defining standardized `EDD`s and `Control`s; conceptually mirrored by `EddTelemetrySampler` and `ControlAction` in Java.
- **`ADR` (Architectural Decision Record):** Numbered engineering record documenting why ADTNM adopted composition, finite `P0–P7` profiles, closed-form `M2-Mob`, and isolated confirmatory/exploratory seeds (`Ch. 8`).
- **`ADTNM` (Autonomic DTN Management):** Middleware-style management architecture atop **The ONE** decoupling local resource governance (`DtnmaAgent`) from substrate forwarding (`Managed*Router`, `DtnmaRouter`).
- **`AoI` (Age of Information):** Time elapsed (`AoI(t) = t - t_last_rx`) since the latest remote Manager uplink/downlink status update arrived, sampled every `10.0 s`.
- **`ARI` (Application Resource Identifier):** RFC 9675-inspired URI identifier (e.g., `ARI://DTNMA/MOB/ENERGY_HORIZON_THROTTLE`, `ARI://DTNMA/SRL/VDTN_SURGE_THROTTLE`, `ARI://DTNMA/SHIELD/DENSE_STORM_GUARD`) emitted per decision in `DtnmaAuditReport`.
- **`AST` / `PredicateExpression`:** Structured boolean comparison tree (`>`, `<`, `>=`, `<=`, `==`, `!=` linked via `&&` or `||`) compiled from `.txt` rules by `DtnmaRuleParser`.
- **`Backpressure Guard` (`bpRatio` / `bp`):** Relay admission filter (`allowNonDirectBulkForward`) blocking non-direct `BULK_` transfers when `Q_nbr > Q_local - bpRatio`.
- **`BULK_` (`SCIENCE_DATA_`):** Low-priority delay-tolerant bulk payload prefix protected only after `CRIT_` and subject to Tier 2/3 ingress & backpressure gating.
- **`CA-SnW` (`CongestionAwareSnWRouter`):** Non-DTNMA congestion- and energy-aware Spray-and-Wait baseline without `CRIT_`-class priority reserve or ACK pruning.
- **`CI` ($95\%$ Confidence Interval):** Two-sided Student-$t$ interval $\bar{d} \pm t_{0.975,\,n-1} s_d / \sqrt{n}$ over matched-seed paired differences ($n=19$ confirmatory, $n=11$ exploratory).
- **`CRIT_` (`CRITICAL_FAULT_`):** High-priority mission-critical emergency telemetry prefix shielded from un-ACKed buffer eviction under active priority reserve.
- **`CTRL_` (`CTRL_UPDATE_`):** Manager downlink configuration bundle carrying priority-110 time-bounded overlay updates (`shieldEnergyMin`, throttle `L`) tracked via `downlink_delivery_prob` and `# WAVE_ADOPTION_CDF`.
- **`DTN` (Delay/Disruption-Tolerant Network):** Intermittently connected mobile wireless network operating via Store-Carry-Forward bundle transport (**RFC 9171**).
- **`DTNMA` (DTN Management Architecture):** Asynchronous Agent/Manager operational specification defined in **IETF RFC 9675** (Nov 2024).
- **`EDD` (Externally Defined Data):** Standardized `15`-element telemetry vector (`EDD_QUEUE_RATIO` through `EDD_ENERGY_TTD_RATIO`) computed uniformly by `EddTelemetrySampler`.
- **`EWMA` (Exponentially Weighted Moving Average):** Infinite-impulse-response smoothing filter ($y_t = \alpha x_t + (1-\alpha)y_{t-1}$) used for queue drift ($\alpha = 0.30$), speed ($\alpha = 0.25$), and contact duration/ICT estimators.
- **`FWER` (Family-Wise Error Rate):** Probability of making at least one Type I false-discovery error across a family of statistical tests; controlled via **Holm–Bonferroni** step-down adjustment (`p_holm`).
- **`Hedges'` $g_z$:** Small-sample bias-corrected paired standardized effect size: $(\bar{d} / s_d) \cdot (1 - 3/(4(n-1)-1))$.
- **`ICT` (Inter-Contact Time):** Elapsed silence interval between consecutive encounters (`EDD_INTER_CONTACT_TIME`, `avg_ict_s`), used in `M2-Mob` to scale surge queue thresholds ($\theta_Q$).
- **`L` (Spray Replica Quota):** Initial / capped copy token count (`1..12`) per bundle in Spray-and-Wait and `ManagedSprayAndWaitRouter`.
- **`M0b` / `M0e-25` / `M1-Remote` / `M2-SRL` / `M2-Mob`:** ADTNM controller modes comparing protection-only static (`M0b`), static battery cutoff (`M0e-25`), remote unicast waves (`M1-Remote`), declarative local rules (`M2-SRL`), and closed-form mobility coordination (`M2-Mob` + `EnergyOnly`/`BufferOnly`/`ReplicaOnly` ablations).
- **`Managed Substrate`:** Stock ONE routing algorithm (`SprayAndWait`, `PRoPHETv2`, `MaxProp`, `Epidemic`) wrapped cleanly by `DtnmaManagedRouter`.
- **`ONE` (The Opportunistic Network Environment):** Discrete-event Java DTN simulator hosting ADTNM under `0.1 s`–`0.5 s` simulation ticks.
- **`P0..P7` (`DtnmaPolicyProfile`):** Eight immutable actuator tuples (`SAFE_EMERGENCY_HOLD` .. `HIGH_SPRAY_OPEN`) fixing `(L, bpRatio, ingressMode, backpressureEnabled)`.
- **`pp` (Percentage Points):** Arithmetic difference between two percentages (e.g., `70.18% - 66.50% = +3.68 pp`).
- **`PRoPHETv2` (**RFC 6693**):** Probabilistic routing protocol updating encounter delivery predictabilities $P(a, b)$ with transitivity and time aging.
- **`RWP` (Random Waypoint Movement):** Synthetic mobility model moving nodes at uniform speeds between uniformly sampled coordinates (`scenario_dtnma.txt`, `Env 3`).
- **`S1–S4` (`DtnmaSafetyShield`):** Four deterministic invariant gates (`S1` Critical/Surge Buffer, `S2` Battery SoC Minimum, `S3` Dense Storm Block `{P6, P7}`, `S4` Remote Overlay Clamp) + unconditional `P0` liveness.
- **`SoC` (State of Charge):** Remaining battery ratio $E_{\text{rem}} / E_{\text{init}} \in [0, 1]$ (`EDD_ENERGY_SOC`).
- **`SPMBM` (Shortest-Path Map-Based Movement):** Map-constrained street/transit mobility (`helsinki` roads + tram routes in `Env 1`, `Cross-Substrate`, and `Scale` scenarios).
- **`SRL` (State-Based Rule):** Declarative local autonomy clause (`RULE / IF / THEN`) parsed from `.txt` rule sets (`rules_vdtn.txt`).
- **`T50` / `T90`:** Elapsed time (`s`) from Manager wave injection until `50%` / `90%` of target field nodes adopt the downlink bundle (`# WAVE_ADOPTION_CDF`).
- **`TTD` (Time-to-Depletion Ratio):** Remaining battery lifetime normalized by `mobHorizonSec` (`EDD_ENERGY_TTD_RATIO`).
- **`vDTN` (Vehicular DTN):** Urban vehicular/tram challenged network regime (`mob_env1_vdtn_*`).
- **`WDM` (Working-Day Movement):** Multi-district commuter/home/office/bus daily activity mobility model (`mob_env2_workingday.txt`).
