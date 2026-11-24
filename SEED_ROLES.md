# ADTNM Seed Governance and Scenario Freeze Record

To eliminate post-hoc tuning on evaluation seeds or scenario-shopping across
management paradigms, random seeds and stressor calibrations follow a strict
train/dev vs. held-out test separation.

## 1. Seed Partitions

| Role | Seeds | Usage Policy |
|---|---|---|
| **Development & Calibration** | `4001, 4003, 4007, 4009, 4021` | Bug-hunting, reward shaping verification, M0a/M0b stressor calibration, and matched single-seed pilot verification (`4001`). Never cited as final paper tables without held-out replication. |
| **Final Paper Campaigns** | `7103, 7109, 7121, 7127, 7129, 7151, 7159, 7177, 7187, 7193, 7207` | 11 fresh prime seeds reserved exclusively for final camera-ready campaigns (`df = 10` paired tests across Comparison A multi-regime, Comparison A Tiers, Equivalence/TOST, Comparison B Stress + Benign). Prime count `11` is coprime to all router sweep counts (`2, 4, 5, 6, 8, 9, 12`) to prevent ONE modular-index collisions. Never executed until one frozen commit tag exists. |
| **Post-Freeze Exploratory Controls (`v1.0-exp-controls`)** | `7211, 7213, 7219, 7229, 7237, 7243, 7247, 7253, 7283, 7297, 7307` | 11 strictly disjoint fresh primes (`> 7207`, `176` runs total) reserved for post-freeze exploratory controls (`M0e-20`, `M0e-25` energy-only static guards, `M2-LocalOnly` no-manager-update isolation, and single-bundle group-flooded `M1-F-Group` vs `M1-U`/`M2`), written separately to `reports/exploratory_v1_0_controls/` without touching `v1.0-tnsm-freeze`. |

## 2. Comparison B Stressor Calibration Record (Seed `4001`, `M0a`/`M0b` Only)

Target band chosen before running `M1`/`M2`/`M3`: `M0b` (`DtnmaM0bStaticRouter`)
must enter **35–60% field blackout** (`alive_nodes_end` in `[54, 84]` out of 126 hosts,
i.e., `42–72` depleted out of 120 field agents) or **>= 20 pp post-fault delivery drop**.

Calibration iterations executed exclusively on `SprayAndWaitRouter` (`M0a`) and
`DtnmaM0bStaticRouter` (`M0b`) with CompA-matched physical radio power
(`scanEnergy = 0.05`, `scanResponseEnergy = 0.02`, `transmitEnergy = 0.90`):

1. **Iter #1 (`initialEnergy = 2900, 4100`)**: `M0b` `alive_nodes_end = 91` (`35` depleted, `29.2%` field blackout), `CrDel = 0.9818`, `BkDel = 0.8005`. Too mild.
2. **Iter #2 (`initialEnergy = 2400, 3300`)**: `M0b` `alive_nodes_end = 24` (`102` depleted, `85.0%` field blackout), `CrDel = 0.9245`, `BkDel = 0.7027`. Too harsh.
3. **Iter #3 (`initialEnergy = 2650, 3650`)**: `M0b` `alive_nodes_end = 55` (`71` depleted, **`59.2%` field blackout**), `CrDel = 0.9691`, `CrLst = 0`, `BkDel = 0.7706`, `PostFault = 0.7592`; `M0a` `alive_nodes_end = 56` (`70` depleted), `CrDel = 0.8626`, `CrLst = 117`, `BkDel = 0.7357`. **Within target band -> `compB_multitimescale.txt` FROZEN prior to running `M1`/`M2`/`M3`.**

## 3. Control-Transport Equalization Note

The first Step 4a Comparison B pilot revealed that setting `epidemicControlFlooding = true` only on `M1`/`M2`/`M3` flooded `6 × 120 = 720` distinct unicast `CTRL_UPDATE_*` IDs across every encounter, draining `~650–800 J` extra battery (`12–17` alive vs `55–56` on `M0a`/`M0b`). Equalizing `DtnmaRouter.epidemicControlFlooding = false` across all four managed modes (`M0b`/`M1`/`M2`/`M3`, retaining `M0a`/`M0b` frozen calibration values unchanged) removes that medium/energy confound.
