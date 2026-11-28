# ADTNM One-Page Developer & Researcher Cheat Sheet

> **Mobility-Aware Autonomic DTN Management (`M2_MOBILITY_AWARE`) over `Managed*Router` Substrates (`RFC 9675 / 7575`-inspired)**

## 1. Install, Test & Smoke Commands (PowerShell)
```powershell
$env:PYTHONDONTWRITEBYTECODE='1'; $THE_ONE="..\the-one"
python install.py --one-dir "$THE_ONE"
javac -cp "$THE_ONE\target;$THE_ONE\lib\*" -d "$THE_ONE\target" tests\DtnmaUnitTests.java
java -cp "$THE_ONE\target;$THE_ONE\lib\*" DtnmaUnitTests          # Expect: 21 passed, 0 failed.
python tools\validate_mobility_scenarios.py --one-dir "$THE_ONE" --smoke
```

## 2. Core Pipeline (`15 EDDs -> M2-Mob / SRL -> Shield -> P0..P7`)
`15 EDDs (EddTelemetrySampler)` -> `M2_MOBILITY_AWARE / SRL` -> `DtnmaSafetyShield (S1..S4, mask[0]=true)` -> `P0..P7` -> `DtnmaManagedRouter` -> `DtnmaAuditReport (21-col ARI)`
- **S1 Critical/Surge:** `CritQ >= 0.50 || (Q >= 0.94 && dQ >= 0.015)` -> `{P0}` | `CritQ >= 0.35 || (Q >= 0.82 && dQ > 0.015)` -> `{P0, P1, P2}`
- **S2 Battery:** `SoC < shieldEnergyMin (0.20/0.25)` -> `{P0, P1}` | **S3 Storm:** `Q >= 0.78 && Dens >= 5.0` -> block `{P6, P7}` | **S4 Remote Clamp**
- **M2-Mob Anchors:** `mobHorizonSec=600`, `mobTtdCritRatio=0.60`, `mobTtdWarnRatio=1.10`, `mobShortContactSec=12`, `mobLongIctSec=180` ($\theta_Q \in [0.78, 0.88]$)

## 3. Control Profiles (`P0..P7`) & Queue / ACK Rules
`P0 HOLD (L=1, ing=2, bp=.05)` | `P1 THROTTLE (L=2, ing=1, bp=.08)` | `P2 CONSERVATIVE (L=4, ing=0, bp=.10)` | `P3 AGILE (L=6, bp=.12)` | `P4 STD (L=6, bpOff)` | `P5 SPRAY (L=8)` | `P6..P7 (L=10..12)`
- **Forward:** 4-tier $O(N)$ `CRIT_(L>1) -> CRIT_(L<=1) -> BULK_(L>1) -> BULK_(L<=1)` | **Evict:** `deliveredAckSet CRIT_` (`4 B` ACKs, `<=32/contact`) -> worst `BULK_` -> Protected live `CRIT_`

## 4. Rule Syntax (`rules_vdtn.txt`) & Key Metrics (`DtnmaMetricsReport`)
```text
RULE: ARI://DTNMA/SRL/VDTN_SURGE_THROTTLE | PRIORITY: 80 | DESC: Throttle bulk spray during active surges
IF: (EDD_QUEUE_RATIO >= 0.88) && (EDD_QUEUE_DRIFT >= 0.008)
THEN: CTRL_SET_SPRAY_QUOTA(2); CTRL_SET_INGRESS_MODE(1); CTRL_SET_TELEMETRY_INTERVAL(300); CTRL_SET_BACKPRESSURE(true)
```
Reports: `DtnmaMetricsReport` (`unique_critical_lost`, `critical_delivery_prob`, `bulk_delivery_prob`, `eventA/B/C_*`, `alive_nodes_end`, `shield_decisions_total`, `control_bytes_total`), `DtnmaAuditReport` (`traceable_ari_ratio: 1.0000`), `DtnmaRecoveryTimelineReport`.
