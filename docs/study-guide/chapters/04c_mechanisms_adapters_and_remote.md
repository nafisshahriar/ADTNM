# Chapter 4c: Mechanisms 12–17 — Router Adapters, Remote Overlay & Neighbor Coordination

> **Series:** Part 3 of 4 (`04a`–`04d`). Covers the Substrate Adaptation Plane, remote Manager control modes (`M1-U`, `M1-F`, `M1-F-Group`), `M2` vs `M2-LocalOnly`, and `16 B` peer coordination (`d4a159f`).

> [!TIP] INTUITION
> Instead of rewriting every routing protocol into a brand-new class, `DtnmaManagedRouter` slips a uniform remote-control receiver and governor (`DtnmaAgent`) onto stock routers (`SnW`, `PRoPHETv2`, `MaxProp`, `Epidemic`) like a standardized electronic speed limiter on different engine designs.

> [!IMPORTANT] FORMAL / CODE
> Control byte accounting spans three orders of magnitude across dissemination channels: `118.7 kJ` (`M1-F` per-node flood) -> `2.51 kJ` (`M1-U` unicast) -> `201.6 J` (`M1-F-Group` single-copy group flood).

> [!WARNING] GOTCHA / PITFALL
> When a mode such as `M2` receives zero group-flood packets in `groupflood_stress`, `DtnmaRlMetricsReport` writes `downlink_t90_sec = 30849.0` (end-of-horizon sentinel) alongside `downlink_90pct_reached = 0.0`. Always filter by `downlink_90pct_reached` before interpreting adoption latencies.

> [!NOTE] SUPERVISOR NOTE
> Exploratory isolation (`M2-LocalOnly = 80.0`, `M0e-25 = 83.8`, `M2 = 83.8`) clarifies how much of `M2`'s end-of-mission survival stemmed from its `0.25` remote guard wave.

---

## Mechanism 12: `DtnmaManagedRouter` Thin-Adapter Composition Contract

### 1. Purpose & Problem Solved
Establishes a single router-agnostic Java interface decoupling forwarding substrates from `DtnmaAgent`, avoiding `O(R)` duplication of `2,095` lines of management logic across every routing protocol.

### 2. Design Alternatives Considered
- **Subclass inheritance (`EnergyAwareEpidemicRouter`, `394` LOC):** Re-implements EDD tracking, ACKs, and thresholds inside every router subclass; drifts easily when policies change.
- **Selected composition interface (`DtnmaManagedRouter` + `DtnmaAgent`):** Any ONE `ActiveRouter` delegates lifecycle hooks (`update`, `createNewMessage`, `checkReceiving`, `makeRoomForMessage`, `changedConnection`) via ~`253.5` mean non-comment LOC.

### 3. Architecture & Data Flow
`Router Adapter (extends StockRouter implements DtnmaManagedRouter)` owns one `private final DtnmaAgent agent` -> Exposes `15` buffer/connection/energy getters + `4` actuator setters (`setDynamicSprayQuota`, `setIngressMode`, `setRoutineTelemetryInterval`, `setBackpressureEnabled`) -> Delegates forwarding filters cleanly to `agent`.

### 4. Inputs & Configuration
Configured automatically inside router copy constructor; `supportsDynamicControlReconfig()` returns `true`; copies clone cleanly in ONE's `replicate()` prototype pattern.

### 5. Algorithm & Exact Math / Equations
Interface contract guarantees invariance:
- `getBufferOccupancyRatio()` $= (B_{\text{total}} - B_{\text{free}}) / B_{\text{total}}$
- `getCriticalOccupancyRatio()` $= \sum_{m \in \text{CRIT}} |m| / B_{\text{total}}$
- `getEnergySoc()` $= \text{clamp}(E_{\text{rem}} / E_{\text{init}}, 0, 1)$
- `createNewMessage(m)` wraps `agent.evaluateCreateNewMessage(m)` cleanly before calling `super.createNewMessage(m)`.

### 6. Code Anchors
- `src/routing/DtnmaManagedRouter.java:1-46`
- `src/routing/ManagedEpidemicRouter.java:21-98`

### 7. Outputs, Telemetry & Logs
`Agent` attaches identically to all adapters, enabling unified `DtnmaAuditReport` and `DtnmaRlMetricsReport` emission across heterogeneous protocols.

### 8. Edge Cases, Failure Modes & Safeguards
`E1` equivalence proof confirms Tier 1 `ManagedSprayAndWaitRouter` diverges by **`0.0000 pp`** from hard-coded `PrioritySprayAndWaitRouter` across `11/11` seeds (`MessageStats` SHA-256 identical).

### 9. Worked Mini-Example
Adding management to a new stock router requires zero edits to `DtnmaAgent`: extend the stock class, implement `DtnmaManagedRouter`, forward the `5` ONE lifecycle hooks (`update`, `checkReceiving`, `createNewMessage`, `changedConnection`, `makeRoomForMessage`), and plug in `rules_vdtn.txt`.

### 10. Status Label & Evolution
`Implemented+tested` (`v1.0-tnsm-freeze`). Replaces earlier monolithic routing hierarchy cleanly.

### 11. Supervisor Rationale
Core architectural contribution of ADTNM (`2,095` shared core LOC vs `253.5` mean adapter LOC).

---

## Mechanism 13: Substrate-Specific Relay Adaptations (`SnW`, `PRoPHETv2`, `MaxProp`, `Epidemic`)

### 1. Purpose & Problem Solved
Applies priority ordering (`CRIT_/CTRL_` before `BULK_`), backpressure blocking (`allowNonDirectBulkForward`), and Tier 3 copy quotas without corrupting each router's native path-selection estimator.

### 2. Design Alternatives Considered
- **Replacing native comparators globally:** Destroys PRoPHET delivery predictability (`P(a,b)`) and MaxProp Dijkstra cost ordering.
- **Selected Tiered Native Wrapping:** Sorts candidate tuples into `CRIT_/CTRL_` first, then applies native router comparator inside each priority band + optional Tier 3 replica cap.

### 3. Architecture & Data Flow
In each `Managed*Router`:
- **`ManagedSprayAndWaitRouter`:** Clamps binary token splitting (`MSG_COUNT_PROPERTY`) dynamically under Tier 3 when active profile quota `L_i < defaultQuota`.
- **`ManagedProphetV2Router`:** Updates native RFC 6693 aging/transitivity untouched; filters `BULK_` relays via `allowNonDirectBulkForward` and priority-orders `tryAllMessages`.
- **`ManagedMaxPropRouter`:** Keeps Dijkstra encounter probabilities and cost tables untouched; guards congestion & ACKed duplicate eviction cleanly.
- **`ManagedEpidemicRouter`:** Retains unrestricted flooding under nominal `P4`, throttling bulk flooding smoothly under `S1–S4` and ingress guards (`14.5 -> 125.9` survival jump in CompA Blackout).

### 4. Inputs & Configuration
`dtnmaInterventionTier`: `TIER1_PROTECTION_ONLY`, `TIER2_PROTECTION_INGRESS_ENERGY`, `TIER3_FULL_WITH_REPLICA_CAPS`; stock router settings (`SprayAndWaitRouter.nrofCopies = 6`, `ProphetV2Router.secondsInTimeUnit = 30`).

### 5. Algorithm & Exact Math / Equations
Tier gating inside `DtnmaAgent`:
- If `Tier == 1`: skip ingress throttling, backpressure block, and dynamic spray cap — preserve `Priority*` behavior cleanly.
- If `Tier == 2`: enable `ingressMode` + `S1–S4` + `SRL` rules (`L` cap bypassed).
- If `Tier == 3`: enforce full profile `(L_i, bp, ing)`.

### 6. Code Anchors
- `src/routing/ManagedSprayAndWaitRouter.java:1-291`
- `src/routing/ManagedProphetV2Router.java:1-291`
- `src/routing/ManagedMaxPropRouter.java:1-350`
- `src/routing/ManagedEpidemicRouter.java:1-236`

### 7. Outputs, Telemetry & Logs
Tracked identically across all `4` routers in `compA_vdtn_overload`, `compA_energy_blackout`, `compA_ntn_space`, `compA_benign`, and `compA_tiers`.

### 8. Edge Cases, Failure Modes & Safeguards
Tier 1 leaves `Epidemic` unprotected against battery drain (`14.5/126` alive in Blackout), isolating why **Tier 2 (`125.9/126` alive)** drives survival while Tier 3 adds minimal marginal gain (`0` extra alive nodes on Epidemic/SnW).

### 9. Worked Mini-Example
In `compA_tiers` (`ManagedEpidemicRouter`, seed `7103`), Tier 1 finishes with `16` alive nodes (`avg SoC = 0.0527`, bulk `0.0%`), whereas Tier 2 finishes with **`124` alive nodes** (`avg SoC = 0.2050`, critical `66.94%`, zero critical loss).

### 10. Status Label & Evolution
`Implemented+tested` (`v1.0-tnsm-freeze`).

### 11. Supervisor Rationale
**Cleanest router-agnostic finding:** Levels 1–2 (`Tier 2`) achieve survival & critical protection across diverse routing families without rewriting core routing heuristics.

---

## Mechanism 14: Remote Manager Overlay (`CTRL_UPDATE_` / `CTRL_GROUP_`) & Rule Upsert

### 1. Purpose & Problem Solved
Allows a central DTN Manager to issue time-bounded configuration waves (such as tightening the battery survival threshold to `shieldEnergyMin = 0.25`) over opportunistic DTN links without rebooting agents.

### 2. Design Alternatives Considered
- **Permanent global overwrite:** Locks agents permanently into defensive throttling even long after a scenario window closes.
- **Selected priority-100 overlay slot (`upsertDownlinkOverlayRule`):** Temporary highest-priority rule overriding `PRIO <= 99` local rules until expiration or explicit `NOMINAL` release.

### 3. Architecture & Data Flow
`Manager host` generates `CTRL_` bundle carrying command property -> Routed across DTN relays to target nodes (`unicast` or `group-flood`) -> On reception at `applyDownlinkControlBundle` (`DtnmaAgent.java:1200-1323`), node records adoption latency (`downlink_t90_sec`), installs overlay rule at `PRIO = 100`, and updates `shieldEnergyMin` / throttle bounds when instructed.

### 4. Inputs & Configuration
`dtnmaControlPriority = 100`, `dtnmaControlTimeoutSec`, message prefix `CTRL_UPDATE_` (targeted) or `CTRL_GROUP_` (shared wave); payload specifies profile clamp, energy floor, or nominal release.

### 5. Algorithm & Exact Math / Equations
Wave Adoption CDF fraction at offset $\tau \ge 0$ after wave issue $t_0$:
$$F_{\text{wave}}(\tau) = \frac{|\{i \in \text{Targets} : t_{\text{rx}, i} - t_0 \le \tau\}|}{|\text{Targets}|}$$
Sampled across `[0, 60]` one-minute bins (`q10, q25, q50, q75, q90, q95`) into `# WAVE_ADOPTION_CDF`.

### 6. Code Anchors
- `src/routing/DtnmaAgent.java:1200-1323` (`applyDownlinkControlBundle`, `upsertDownlinkOverlayRule`)
- `src/report/DtnmaRlMetricsReport.java:1229-1266` (`# WAVE_ADOPTION_CDF` computation)

### 7. Outputs, Telemetry & Logs
Logs wave percentiles, `downlink_t90_sec`, and `total_ctrl_tx_J`. **Reporting Note:** When `0` waves are received (e.g., `M2` in `groupflood`), `downlink_t90_sec` records an end-of-horizon sentinel (`30849.0`) while `downlink_90pct_reached = 0.0`.

### 8. Edge Cases, Failure Modes & Safeguards
Duplicate remote wave copies are deduplicated cleanly by message ID; remote overlays never drop `P0` liveness admissibility (`S1–S2` safety guards stay active).

### 9. Worked Mini-Example
In `compB_exp_controls_stress` (seed `7211`), `M1-U`'s `CTRL_UPDATE_THROTTLE_W1_` wave issued at `3601 s` hits `50%` node adoption at **`1131 s`** (`~18.9 min`) and `90%` (`T90`) at **`2722 s`** (`~45.4 min`) — completely missing the `300 s` Event A surge!

### 10. Status Label & Evolution
`Implemented+tested` (`v1.0-tnsm-freeze` + `v1.0-exp-controls`). Note Manager waves in `v1.0` use oracle schedule times (`compB` scenario scripts).

### 11. Supervisor Rationale
Quantifies the exact physical cost (`J`) and latency (`T50`, `T90`) of remote management in challenged DTNs.

---

## Mechanism 15: Unicast (`M1-U`) vs. Legacy Flood (`M1-F`) vs. Group Flood (`M1-F-Group`) Dissemination

### 1. Purpose & Problem Solved
Isolates *control transport physics* from *decision intelligence*: separates whether delayed remote management fails because of unicast queueing (`M1-U`), excessive per-node flood storm (`M1-F`), or fundamental speed-of-light / contact delay (`M1-F-Group`).

### 2. Design Alternatives Considered
- **`M1-U` (`DtnmaM1RemoteRouter`):** Sends `N = 120` separate unicast `CTRL_UPDATE_` bundles (one per managed node).
- **`M1-F` (`DtnmaM1FloodRouter`, confirmatory `v1.0`):** Floods all `120` individual `CTRL_UPDATE_` bundles epidemics-style (`118.7 kJ` radio cost -> `13.55/126` survivors!).
- **`M1-F-Group` (`DtnmaM1FloodGroupRouter`, exploratory `v1.0-exp-controls`):** Floods **1 shared group-addressed `CTRL_GROUP_` bundle per wave** adopted by every node (`201.6 J` cost, `T90 = 841 s`, `99.5/126` survivors).

### 3. Architecture & Data Flow
`compB_multitimescale` vs `compB_exp_groupflood_*`: In `M1-F-Group`, receiving a single `CTRL_GROUP_` bundle triggers local overlay adoption at that host *and* continues propagating the single bundle to peers until TTL expires.

### 4. Inputs & Configuration
Configured via scenario message generators (`CTRL_UPDATE_*` vs `CTRL_GROUP_*`) and mode class selection (`DtnmaM1RemoteRouter`, `DtnmaM1FloodRouter`, `DtnmaM1FloodGroupRouter`).

### 5. Algorithm & Exact Math / Equations
Control transmission energy breakdown:
$$E_{\text{ctrl, tx}} = \sum_{\text{tx} \in \text{CTRL}} P_{\text{tx}} \cdot \frac{|\text{msg}|}{\text{baud}} \implies \begin{cases} 118,728\ \text{J} & (\text{M1-F: } O(N^2)\ \text{per-node floods}) \\ 2,512\ \text{J} & (\text{M1-U: unicast}) \\ 201.6\ \text{J} & (\text{M1-F-Group: } O(N)\ \text{single-copy group flood}) \end{cases}$$

### 6. Code Anchors
- `src/routing/DtnmaM1RemoteRouter.java`, `DtnmaM1FloodRouter.java`, `DtnmaM1FloodGroupRouter.java`
- `scenarios/compB_exp_controls_stress.txt` & `tools/analyze_exploratory_controls.py:40-52`

### 7. Outputs, Telemetry & Logs
`total_ctrl_tx_J`, `downlink_t90_sec`, `alive_nodes_end`, and per-wave CDFs (`# WAVE_ADOPTION_CDF`).

### 8. Edge Cases, Failure Modes & Safeguards
Reviewer critique caught `M1-F` (`118.7 kJ`) as a strawman control channel prior to exploratory follow-up; adding `M1-F-Group` (`201.6 J`) fairly evaluates best-case remote flooding!

### 9. Worked Mini-Example
With single-copy `CTRL_GROUP_THROTTLE_W1_` (seed `7211`), `M1-F-Group` hits `T50 = 441.5 s` (`T90 = 823.0 s` across Wave 1) at tiny energy cost, saving `99.5/126` nodes across 12h stress — *still* too slow (`441.5 s > 300 s`) to prevent Event A stale throttling (`252 s` for `10%`, median `~483 s` across 11 seeds).

### 10. Status Label & Evolution
`M1-U` & `M1-F`: `Implemented+tested` (`v1.0-tnsm-freeze`); `M1-F-Group`: `Implemented+tested` (`v1.0-exp-controls`, exploratory).

### 11. Supervisor Rationale
Proves even an unrealistic best-case oracle-triggered group flood still loses the sub-10-minute timescale argument (`483 s` median adoption vs `300 s` surge).

---

## Mechanism 16: Hybrid Local SRL (`M2`) vs. Disconnected `M2-LocalOnly`

### 1. Purpose & Problem Solved
Tests how much of `M2`'s autonomy gain comes from **local `30 s` SRL reactivity** versus **receiving Manager `0.25` remote wave configuration**.

### 2. Design Alternatives Considered
- **`M2` (`DtnmaM2SrlRouter`):** Runs `12` local vDTN SRL rules (`shieldEnergyMin = 0.20` initially) AND accepts `CTRL_` waves upgrading guards to `0.25`.
- **`M2-LocalOnly` (`DtnmaM2LocalOnlyRouter`):** Runs identical `12` local rules with **all remote Manager waves disconnected (`0 J` control waves)**.

### 3. Architecture & Data Flow
`DtnmaM2LocalOnlyRouter` extends `DtnmaAgent` composition identically to `M2`, differing solely in setting `dtnmaRemoteOverlayEnabled = false` / `scenario` omitting remote wave generators.

### 4. Inputs & Configuration
Configured in `compB_exp_controls_stress` (`m2_localonly_stress` / `benign`) over exploratory seeds `7211..7307`.

### 5. Algorithm & Exact Math / Equations
Attribution decomposition (`v1.0-exp-controls`, means across `11` seeds in Stress):
- `M0e-20` (`SoC < 0.20` static cutoff only): `73.7` alive
- `M2-LocalOnly` (local `0.20` + SRL rules, zero remote updates): `80.0` alive (`+6.3` nodes vs `M0e-20`)
- `M2` (local `0.20` + SRL + `0.25` remote wave): `83.8` alive (`+3.8` extra from remote update; matches `M0e-25 = 83.8`!)

### 6. Code Anchors
- `src/routing/DtnmaM2SrlRouter.java:1-29`
- `src/routing/DtnmaM2LocalOnlyRouter.java`
- `tools/analyze_exploratory_controls.py:44-50`

### 7. Outputs, Telemetry & Logs
Recorded in `exploratory_v1_0_controls/{m2_localonly_stress,m2_localonly_benign}`.

### 8. Edge Cases, Failure Modes & Safeguards
Separates confirmatory reporting cleanly: `M2-LocalOnly` never modifies frozen `v1.0-tnsm-freeze` claims and is labeled exploratory (`7211..7307`).

### 9. Worked Mini-Example
In Benign scenarios (`12 h`), `M2-LocalOnly` retains full throughput (`~82%` bulk) with `126/126` survivors and `0 J` remote control traffic; only prolonged battery-stress blackout shows the `3.8`-node benefit of upgrading SoC floor from `0.20 -> 0.25`.

### 10. Status Label & Evolution
`M2`: `Implemented+tested` (`v1.0-tnsm-freeze`); `M2-LocalOnly`: `Implemented+tested` (`v1.0-exp-controls`).

### 11. Supervisor Rationale
Directly addresses causal survival attribution: demonstrates `M2`'s stress survival depends partly on remote threshold updates (`0.25`), motivating `v1.1` local energy-drain tuning.

---

## Mechanism 17: Compact `16 B` Contact Beacon & Peer Queue Gradient Exchange

### 1. Purpose & Problem Solved
Supplies neighbors with real-time congestion awareness (`Q_nbr`, `CritQ_nbr`, `SoC_nbr`) upon wireless contact without transmitting heavy XML/JSON routing state dumps.

### 2. Design Alternatives Considered
- **Periodic broadcast flooding of node state tables:** Saturated channel under high density (`>= 12` neighbors in storm hubs).
- **Selected unicast contact `16 B` beacon (`DtnmaContactBeacon`):** Piggybacks O(1) state bytes on peer handshake every `>= 5.0 s`.

### 3. Architecture & Data Flow
`changedConnection` UP event -> Check cooldown (`>= 5.0 s`) -> Peer writes `16 B` beacon (`Q`, `CritQ`, `SoC`, active profile, timestamp) -> `EddTelemetrySampler` updates `EDD_NEIGHBOR_MIN_QUEUE` and `EDD_NEIGHBOR_QUEUE_GRAD = Q_local - Q_nbr_min` -> Backpressure forwarder uses peer `Q_nbr`.

### 4. Inputs & Configuration
`DtnmaContactBeacon.java:18-21`: `BEACON_SIZE_BYTES = 16`, `ACK_ID_BYTES = 4`, `GOSSIP_PAYLOAD_BYTES = 648` (`M3` only).

### 5. Algorithm & Exact Math / Equations
- `EDD_NEIGHBOR_MIN_QUEUE`: $\min_{j \in \text{FreshNbrs}(t)} Q_j$, where freshness respects stale timeout window (`300 s`).
- Backpressure forwarding rule (`allowNonDirectBulkForward`): block transit `BULK_` relay to peer $j$ if $Q_j > Q_{\text{local}} - bp_{\text{ratio}}$ or peer $j$ is battery-depleted (`SoC_j < minSoC`).

### 6. Code Anchors
- `src/routing/DtnmaContactBeacon.java:1-100`
- `src/routing/DtnmaAgent.java:520-550`, `:1119-1159`

### 7. Outputs, Telemetry & Logs
`beacon_overhead_bytes` and `control_bytes_total` in `DtnmaRlMetricsReport`. Even across heavy `12 h` runs, beacon + ACK bytes account for `<< 1%` of total payload traffic (`control_to_payload_ratio`).

### 8. Edge Cases, Failure Modes & Safeguards
`EnergyAwareEpidemicRouter` replicates a `5.0 s` cooldown beacon cache internally so baseline `A3` parity isolates policy from transport overhead.

### 9. Worked Mini-Example
When node `53` hits `Q = 0.92` (`P1`, `bpRatio = 0.08`), if its neighbor reports `Q_nbr = 0.90` via its `16 B` beacon, `0.90 > 0.92 - 0.08 (= 0.84)` -> Backpressure **blocks non-direct `BULK_` upload**, sparing the congested peer while still delivering bundles addressed directly to that peer (`dest == peer`).

### 10. Status Label & Evolution
`Implemented+tested` (`v1.0-tnsm-freeze`).

### 11. Supervisor Rationale
Enables distributed congestion backpressure (`VDTN_NEIGHBOR_RELIEF`) and energy relief at fixed `16 B` wire budget per handshake.

## Visual Diagrams (`Figures 10–11`)

![Composition Adapters vs Hand-Coded Inheritance](../figures/svg/fig10_adapter_composition.svg)

![Remote Dissemination Modes vs Local SRL](../figures/svg/fig11_dissemination_modes.svg)
