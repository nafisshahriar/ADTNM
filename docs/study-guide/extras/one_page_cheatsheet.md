# ADTNM One-Page Developer & Researcher Cheat Sheet

> **Commit `d4a159f` | Freeze `v1.0-tnsm-freeze` (`ca5d9ac`) | Framing: Management layer over managed DTN substrates (`RFC 9675 / 7575`-inspired)**

## 1. Quick Commands (PowerShell)
```powershell
$env:PYTHONDONTWRITEBYTECODE='1'; $THE_ONE="n:\R&D\cog\the-one"; $ADTNM="n:\R&D\cog\ADTNM"
javac -cp "$THE_ONE\target;$THE_ONE\lib\*" -d "$THE_ONE\target" "$ADTNM\src\routing\*.java" "$ADTNM\src\report\*.java" "$ADTNM\tests\DtnmaUnitTests.java"
java -cp "$THE_ONE\target;$THE_ONE\lib\*" routing.DtnmaUnitTests   # 27 tests, 30 check() assertions
python "$ADTNM\tools\parallel_batch_runner.py" --suite compB_exp_controls --dry-run
```

## 2. Core Data Flow & Hard Shield (`S1–S4`)
`11 EDDs (EddTelemetrySampler)` -> `SRL Rules / [Opt. M3 RL]` -> `DtnmaSafetyShield` (`mask[0]=true`) -> `P0..P7` -> `DtnmaManagedRouter` -> `DtnmaAuditReport (ARI://...)`
- **S1 Critical Buffer:** `CritQ >= 0.50 || (Q >= 0.94 && dQ >= 0.015)` -> `{P0}` | `CritQ >= 0.35 || (Q >= 0.82 && dQ > 0.015)` -> `{P0, P1, P2}`
- **S2 Battery:** `SoC < shieldEnergyMin (0.20 / 0.25)` -> `{P0, P1}` | **S3 Dense Storm:** `Q >= 0.78 && Dens >= 5.0` -> block `{P6, P7}` | **S4 Remote Clamp**

## 3. Control Profiles (`DtnmaPolicyProfile`) & Queue Eviction Order (`O(N)`)
`P0 SAFE_EMERGENCY_HOLD (L=1, ing=2, bp=.05)` | `P1 SAFE_THROTTLE_STRICT (L=2, ing=1, bp=.08)` | `P2..P3 BALANCED (L=4..6, ing=0, bp=.10..12)` | `P4 STANDARD (L=6, bpOff)` | `P5..P7 SPRAY (L=8..12)`
- **Evict First -> Last:** `Tier 0 BULK_` -> `Tier 1 ACKed CRIT_` -> `Tier 2 Stale` -> `Tier 3 Active CTRL_` -> `Tier 4 Live CRIT_`

## 4. Rule Syntax (`rules_vdtn.txt`, `rules_ntn.txt`) & Key Config Keys
```text
RULE: ARI://DTNMA/SRL/ID | PRIORITY: 80 | DESC: ...
IF: (EDD_QUEUE_RATIO >= 0.88) && (EDD_QUEUE_DRIFT >= 0.008)
THEN: CTRL_SET_SPRAY_QUOTA(2); CTRL_SET_INGRESS_MODE(1); CTRL_SET_TELEMETRY_INTERVAL(300); CTRL_SET_BACKPRESSURE(true)
```
Keys: `Group.rulesFile`, `DtnmaRouter.configFile`, `interventionTier` (`TIER1..TIER3`), `intelligenceMode` (`STATIC_RULES` vs `SHIELDED_CMDP_RL`), `shieldEnergyMin`.

## 5. Headline Numbers (`provenance_ledger.json`) & Cautions
- **Confirmatory `v1.0` (`715` runs, `7103..7207`):** `0` crit lost across `176` CompA runs; `E1` `11/11` `MessageStats` exact; `H1 -389.06` (`p=1.51e-5`), `H2 +19.21 pp` (`p=1.58e-6`), `H3 +23.59 pp`, `H4 +28.13 pp`; `100.00%` replay (`27.1M` rows).
- **Negatives & Exploratory (`7211..7307`, `p_unadj`):** `H2_orig` survival NI **failed** (`-5.48 pp`, `0/11`); `M3` RL lost to `M2`; `M0e-25` tied `M2` stress survival (`83.8 vs 83.8`, `M2-LocalOnly = 80.0`); `M1-F-Group` (`201.6 J`, `99.5` alive, `T50=483 s`) misses `Event A` (`300 s`).
