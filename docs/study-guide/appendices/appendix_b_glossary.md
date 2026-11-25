# Appendix B: Alphabetical Glossary & Acronym Index

Every acronym and domain term used across the ADTNM Study Guide and root `README.md` is defined concisely here.

- **`ACK` (Delivery Acknowledgment):** `4 B` bundle ID notification exchanged on contact (`<= 32` IDs / contact, LRU `512`) so peers move delivered `CRIT_` replicas into Tier 1 for safe reclamation.
- **`ADTNM` (Autonomic DTN Management):** Architectural management layer implemented atop The ONE decoupling policy (`DtnmaAgent`) from routing substrates (`Managed*Router`).
- **`AoI` (Age of Information):** Time elapsed (`AoI(t) = t - t_last_rx`) since the last remote Manager uplink/downlink status update arrived, sampled every `10 s`.
- **`ARI` (Application Resource Identifier):** RFC 9675-inspired URI tag (e.g., `ARI://DTNMA/SRL/VDTN_SURGE_THROTTLE`, `ARI://DTNMA/SHIELD/DENSE_STORM_GUARD`) emitted per decision in `DtnmaAuditReport`.
- **`AST` / Predicate Expression:** Structured comparison clause list (`>`, `<`, `>=`, `<=`, `==`, `!=` combined via `&&` or `||`) compiled by `DtnmaRuleParser`.
- **`Backpressure Guard` (`bp`):** Hop-by-hop check (`allowNonDirectBulkForward`) blocking non-direct `BULK_` forwarding whenever `Q_nbr > Q_local - bpRatio`.
- **`BULK_` (`SCIENCE_DATA_`):** Low-priority delay-tolerant bundle class (`Tier 0` eviction target).
- **`CMDP` (Constrained Markov Decision Process):** Mathematical framing motivating `M3` (`ShieldedCmdpRlAgent`); dynamic primal-dual `lambda` updates were retired (`lambda = 0.0`), leaving hard safety guarantees strictly to `DtnmaSafetyShield`.
- **`CRIT_` (`CRITICAL_FAULT_`):** High-priority mission-critical fault telemetry (`Tier 4` protection while un-ACKed).
- **`CTRL_` (`CTRL_UPDATE_`, `CTRL_GROUP_`):** Remote Manager control bundles carrying priority-100 temporary overlay configurations.
- **`DTN` (Delay/Disruption-Tolerant Network):** Sparse intermittent network relying on store-carry-forward routing (`RFC 9171`).
- **`DTNMA` (DTN Management Architecture):** Architecture specified in IETF **RFC 9675** (Nov 2024).
- **`EDD` (Externally Defined Data):** Standardized `11`-element telemetry vector sampled by `EddTelemetrySampler`.
- **`E1` / `E2` Equivalence:** `E1` = exact bit-for-bit Tier 1 parity (`ManagedSprayAndWait` vs `PrioritySprayAndWait`); `E2` = statistical equivalence via Two One-Sided Tests (`TOST`, `+/- 1.0 pp`).
- **`EWMA` (Exponentially Weighted Moving Average):** Smoothing filter used for `EDD_QUEUE_DRIFT` ($\alpha = 0.30$) and `EDD_NODE_SPEED` ($\alpha = 0.25$).
- **`Holm-Bonferroni`:** Sequential multiple-comparison correction (`m = 4`) protecting the confirmatory CompB hypothesis family (`H1–H4`).
- **`Managed Substrate`:** Stock forwarding algorithm (`Epidemic`, `PRoPHETv2`, `MaxProp`, `SprayAndWait`, `DtnmaRouter`) governed through `DtnmaManagedRouter`.
- **`NTN` (Non-Terrestrial Network):** High-latency asymmetric space/orbital DTN regime (`rules_ntn.txt`, `compA_ntn_space`).
- **`ONE` (The Opportunistic Network Environment):** Discrete-event Java DTN simulator (`0.1 s`–`0.5 s` tick step) hosting ADTNM.
- **`P0..P7` (`DtnmaPolicyProfile`):** Eight immutable actuator configurations varying replica quota `L` (`1..12`), `bpRatio` (`0.05..0.30`), and `ingressMode` (`0..2`).
- **`R1` Deterministic Replay:** Offline verification confirming `100.00%` of audited rows reproduce identical ARIs, actions, and `8`-bit safety masks.
- **`RL` (Reinforcement Learning):** Experimental `20`-feature linear Q-critic (`M3`, `ShieldedCmdpRlAgent`, `Implemented, experimental`).
- **`SoC` (State of Charge):** Normalized remaining battery ratio $E_{\text{rem}} / E_{\text{init}} \in [0, 1]$ (`EDD_ENERGY_SOC`).
- **`SRL` (State-Based Rule):** Declarative local autonomy rule (`RULE / IF / THEN`) parsed from `.txt` scenario rule sets.
- **`T50` / `T90`:** Median / `90th`-percentile time (`s`) from Manager wave emission until `50%` / `90%` of target agents adopt the downlink bundle (`# WAVE_ADOPTION_CDF`).
- **`vDTN` (Vehicular DTN):** Urban vehicular delay-tolerant traffic & ITS regime (`rules_vdtn.txt`, `compA_vdtn_overload`).
