# Worked Examples: Six End-to-End Walkthroughs From Real Logs

> **Provenance Guarantee:** Every timestamp, node ID, numerical state, and audit row below comes verbatim from `data/provenance_ledger.json` and the raw simulator logs in `smoke_verify`, `final_11seed/` (seed `7103`), and `exploratory_v1_0_controls/` (seed `7211`).

---

## Example 1: One Closed-Loop Epoch on `M2` (`VDTN_SURGE_THROTTLE`, Seed `7103`)

**Raw Audit Line (`compB_multitimescale/...M2SrlRouter_7103_DtnmaAuditReport.txt`):**
```text
3360.5  3360.5  53  M2_AUTONOMIC_SRL  0.919217000  0.005594100  0.008392627  15.000000000  7.178909959  0.922762185  1  0  0.200000000  0.350000000  SAFE_THROTTLE_STRICT  SAFE_THROTTLE_STRICT  SAFE_THROTTLE_STRICT  PASS  11111111  ARI://DTNMA/SRL/VDTN_SURGE_THROTTLE  [EDD_QUEUE_RATIO(0.92) >= 0.88 -> TRUE] AND [EDD_QUEUE_DRIFT(0.01) >= 0.01 -> TRUE]
```

**Step-by-Step Arithmetic & Trace:**
1. **Atomic Snapshot (`t = 3360.5 s`, node `53`):** `EddTelemetrySampler` locks `Q = 0.919217`, `CritQ = 0.005594`, `dQ = 0.008393`, `Dens = 15.0`, `Speed = 7.179 m/s`, `SoC = 0.9228`.
2. **Rule Priority Scan (`rules_vdtn.txt`):**
   - `PRIO 100` (`VDTN_BUFFER_CRITICAL`): `SoC(0.923) < 0.20` (`FALSE`) || `CritQ(0.006) >= 0.50` (`FALSE`) -> skipped.
   - `PRIO 80` (`VDTN_SURGE_THROTTLE`): `Q(0.9192) >= 0.88` (`TRUE`) && `dQ(0.008393) >= 0.008` (`TRUE`) -> **MATCHES!**
3. **Shield Check (`DtnmaSafetyShield`):** `CritQ < 0.35` and `SoC > 0.20` -> `mask = 11111111`; proposed `P1` (`SAFE_THROTTLE_STRICT`: `L = 2, ing = 1, bp = 0.08`) is **`PASS`**.
4. **Resulting Actuation:** Transit `BULK_` relays throttled immediately during the burst without locking out local critical creation.

---

## Example 2: `DtnmaSafetyShield` Override Inside Experimental `M3` (`Seed 7103`)

**Raw Audit Line (`compB_multitimescale/...M3RlRouter_7103_DtnmaAuditReport.txt`):**
```text
3300.5  3300.5  53  SHIELDED_CMDP_RL  0.929816400  0.011173100  -0.000280700  12.000000000  7.181158489  0.921812089  1  0  0.200000000  0.350000000  HIGH_SPRAY_BACKPRESSURE  BALANCED_AGILE_FOCUS  BALANCED_AGILE_FOCUS  OVERRIDE  11111100  ARI://DTNMA/SHIELD/DENSE_STORM_GUARD  SHIELD_OVERRIDE(HIGH_SPRAY_BACKPRESSURE->BALANCED_AGILE_FOCUS): Q=0.93,Dens=12.0
```

**Why It Happened:**
1. At `t = 3300.5 s`, node `53` runs `M3` (`SHIELDED_CMDP_RL`) in a congested intersection cluster: `Q = 0.9298 >= 0.78`, `Dens = 12.0 >= 5.0`, `CritQ = 0.011`.
2. Linear Q-critic proposes **`HIGH_SPRAY_BACKPRESSURE` (`P6`, `L = 10`)**.
3. Shield rule `S3` (`DENSE_STORM_GUARD`) triggers (`Q >= 0.78 && Dens >= 5.0`), masking off `{P6, P7}` -> **`11111100`**, clamping executed profile down to **`P3` (`BALANCED_AGILE_FOCUS`, `L = 6`)** with status **`OVERRIDE`**.

---

## Example 3: Battery Emergency Guard Firing at `SoC = 0.1919` (`Seed 7103`, Node `94`)

**Raw Audit Line (`compB_multitimescale/...M2SrlRouter_7103_DtnmaAuditReport.txt`):**
```text
29460.5  29460.5  94  M2_AUTONOMIC_SRL  0.937859000  0.034220600  -0.000000000  6.000000000  8.093327344  0.191856707  1  6  0.200000000  0.350000000  SAFE_EMERGENCY_HOLD  SAFE_EMERGENCY_HOLD  SAFE_EMERGENCY_HOLD  PASS  11111111  ARI://DTNMA/SRL/VDTN_BUFFER_CRITICAL  [EDD_ENERGY_SOC(0.19) < 0.20 -> TRUE] OR [EDD_CRIT_QUEUE_RATIO(0.03) >= 0.50 -> FALSE]
```

Notice `VDTN_BUFFER_CRITICAL` evaluates **disjunction** (`||`): `CritQ = 0.034` is harmless, yet `SoC = 0.1919 < 0.20` (`TRUE`) switches node `94` cleanly into `P0` (`SAFE_EMERGENCY_HOLD`, `ing = 2`, `L = 1`), preserving emergency critical reachability while stopping battery drain from bulk transit.

---

## Example 4: Comparison A Tier 1 vs. Tier 2 on `Epidemic` Blackout (`Seed 7103`, `t = 42900.5 s`)

**Final Timeline Rows (`CompA_*_Epidemic*_7103_DtnmaRecoveryTimelineReport.txt`):**

| Configuration | Sim Time | Alive Nodes | Avg SoC | Crit Deliv | Bulk Deliv | Total Energy (J) | Unique Crit Lost |
|---|---:|---:|---:|---:|---:|---:|---:|
| Stock `EpidemicRouter` | `42900.5` | `16` | `0.0527` | `0.0000` | `0.0000` | `18,231.83 J` | `> 0` (stock cascade) |
| `ManagedEpidemic` **Tier 1** (Protection Only) | `42900.5` | `16` | `0.0527` | **`0.5625`** | `0.0000` | `15,803.83 J` | **`0`** |
| `ManagedEpidemic` **Tier 2** (+ Ingress & Energy) | `42900.5` | **`124`** | **`0.2050`** | **`0.6694`** | **`0.0795`** | **`6,834.63 J`** | **`0`** |

Tier 1 keeps zero critical loss (`0`) via priority queuing, yet `110` of `126` nodes die identically to stock `Epidemic`. Adding Tier 2 ingress/energy policy slashes fleet energy from `15.8 kJ -> 6.83 kJ`, saving **`108` extra nodes** without altering epidemic routing logic (`Tier 3`).

---

## Example 5: Remote Wave Adoption CDF (`M1-U` vs. `M1-F-Group`, Seed `7211`)

**Raw `# WAVE_ADOPTION_CDF` Lines for Wave 1 (`CTRL_*_THROTTLE_W1`, `t_start = 3601.0 s`, `120` Targets):**
- **`M1-U` (`controls_stress`, unicast):** `q10 = 387.0 s`, `q25 = 639.0 s`, **`q50 = 1131.0 s`**, `q75 = 1754.0 s`, **`q90 = 2722.0 s`**, `q95 = 4301.0 s`.
- **`M1-F-Group` (`groupflood_stress`, single shared group bundle):** `q10 = 252.5 s`, `q25 = 300.0 s`, **`q50 = 441.5 s`**, `q75 = 624.5 s`, **`q90 = 823.0 s`**, `q95 = 1033.0 s`.

Because Event A's traffic spike runs only from **`3600–3900 s` (`300 s` duration)**, `75%` of `M1-F-Group` nodes (and `~90%` of `M1-U` nodes) adopt the throttle command *after* `3900 s` — turning even best-case remote flooding into stale post-event throttling (`117.5` stale node-s/N mean vs `0.0` for `M2`).

---

## Example 6: Explaining Smoke Test `53` Critical Drops vs. `0` `unique_critical_lost` (`DISC-01`)

**From 1-Hour Smoke Run (`scenario_dtnma.txt` at `d4a159f`):**
- `MessageStatsReport`: `created = 262`, `delivered = 184`, `delivery_prob = 0.7023`, `overhead_ratio = 6.7663`
- `DtnmaDecisionReport`: `Critical Fault Messages Dropped: 53`
- `DtnmaRlMetricsReport`: `unique_critical_lost = 0`, `critical_buffer_dropped = 0`, `critical_buffer_dropped_acked = 53`

**Reconciliation:** Once `CRIT_` bundles reach their destination, peers exchange `4 B` ACK IDs and move redundant replicas from protected Tier 4 down into Tier 1 (`deliveredAckSet`). When bulk traffic later needs RAM, Tier 1 safely evicts those **`53` already-delivered duplicate copies**, freeing buffer space with **`0` live information loss** — while `DtnmaDecisionReport.messageDeleted` mistakenly counts Tier 1 evictions inside `criticalMessagesDropped` (`DISC-01`).
