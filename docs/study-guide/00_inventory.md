# Phase 0 Repository Inventory (`00_inventory.md`)

> **Documented Commit:** `d4a159f` (`d4a159f3556e32582773bb5fc883917662411dd2`) on branch `docs/study-guide` | **Frozen Tag:** `v1.0-tnsm-freeze` (`c820752c` -> `ca5d9ac`) | **Exploratory Campaign:** `v1.0-exp-controls` | **Generated:** `2026-11-25` by `docs/study-guide/tools/extract_code_inventory.py`

Total non-study-guide repository files scanned at `d4a159f`: **`109`**.

## 1. File Inventory (Path, Total Lines, Non-Comment LOC, Size)

| Path | Ext | Total Lines | Non-Comment LOC | Bytes | Types / Functions |
|---|---|---:|---:|---:|---|
| `.github/ISSUE_TEMPLATE/user-story.md` | `.md` | 19 | 14 | 422 |  |
| `.github/workflows/ci.yml` | `.yml` | 62 | 50 | 1884 |  |
| `.gitignore` | `none` | 36 | 25 | 396 |  |
| `CLAIMS_LEDGER.md` | `.md` | 49 | 33 | 9470 |  |
| `CONTRIBUTING.md` | `.md` | 149 | 102 | 7315 |  |
| `docs/DTNMA_Autonomic_Framework_Mathematical_Derivations_and_Formulations.md` | `.md` | 1034 | 766 | 72807 |  |
| `docs/DTNMA_Framework_Implementation_and_Router_Architecture.md` | `.md` | 148 | 111 | 10751 |  |
| `docs/dtnma_mathematical_formulation.pdf` | `.pdf` | 0 | 0 | 377285 |  |
| `docs/dtnma_mathematical_formulation.tex` | `.tex` | 489 | 426 | 28925 |  |
| `docs/verification_proof.png` | `.png` | 0 | 0 | 495514 |  |
| `figures/exploratory_v1_0_controls_summary.json` | `.json` | 11234 | 11234 | 329453 |  |
| `figures/exploratory_v1_0_controls_summary.md` | `.md` | 103 | 77 | 15698 |  |
| `figures/fig_final_11seed_compA_energy_depletion.png` | `.png` | 0 | 0 | 440989 |  |
| `figures/fig_final_11seed_compB_adoption_cdf.png` | `.png` | 0 | 0 | 340568 |  |
| `figures/fig_final_11seed_compB_pareto.png` | `.png` | 0 | 0 | 190818 |  |
| `figures/fig_pilot_compA_energy_depletion.png` | `.png` | 0 | 0 | 331569 |  |
| `figures/fig_pilot_compB_adoption_cdf.png` | `.png` | 0 | 0 | 357698 |  |
| `figures/fig_pilot_compB_pareto.png` | `.png` | 0 | 0 | 184461 |  |
| `figures/final_11seed_summary.json` | `.json` | 9495 | 9495 | 288236 |  |
| `figures/final_11seed_tables.md` | `.md` | 151 | 122 | 14111 |  |
| `install.bat` | `.bat` | 10 | 10 | 346 |  |
| `install.py` | `.py` | 500 | 425 | 20156 | 16 py defs |
| `install.sh` | `.sh` | 6 | 2 | 324 |  |
| `LICENSE` | `none` | 21 | 17 | 1092 |  |
| `patches/ActiveRouter.java` | `.java` | 660 | 364 | 20382 | class `ActiveRouter` (31-660) |
| `patches/ProphetRouter.java` | `.java` | 324 | 189 | 9248 | class `ProphetRouter` (30-324) |
| `patches/the-one-dtnma.patch` | `.patch` | 35 | 33 | 1422 |  |
| `README.md` | `.md` | 352 | 254 | 18727 |  |
| `scenarios/compA_benign.txt` | `.txt` | 150 | 123 | 4454 | 88 runs |
| `scenarios/compA_energy_blackout.txt` | `.txt` | 172 | 130 | 5376 | 99 runs |
| `scenarios/compA_equivalence.txt` | `.txt` | 161 | 132 | 4686 | 22 runs |
| `scenarios/compA_equivalence_default.txt` | `.txt` | 161 | 132 | 4708 | 22 runs |
| `scenarios/compA_ntn_space.txt` | `.txt` | 108 | 82 | 3400 | 88 runs |
| `scenarios/compA_tiers.txt` | `.txt` | 162 | 131 | 4966 | 132 runs |
| `scenarios/compA_vdtn_overload.txt` | `.txt` | 147 | 121 | 4306 | 88 runs |
| `scenarios/compB_benign.txt` | `.txt` | 239 | 193 | 7116 | 88 runs |
| `scenarios/compB_exp_controls_benign.txt` | `.txt` | 238 | 193 | 6764 | 55 runs |
| `scenarios/compB_exp_controls_stress.txt` | `.txt` | 238 | 193 | 6793 | 55 runs |
| `scenarios/compB_exp_groupflood_benign.txt` | `.txt` | 237 | 193 | 6654 | 22 runs |
| `scenarios/compB_exp_groupflood_stress.txt` | `.txt` | 238 | 193 | 6741 | 22 runs |
| `scenarios/compB_exp_m2_localonly_benign.txt` | `.txt` | 238 | 193 | 6705 | 11 runs |
| `scenarios/compB_exp_m2_localonly_stress.txt` | `.txt` | 238 | 193 | 6729 | 11 runs |
| `scenarios/compB_multitimescale.txt` | `.txt` | 242 | 193 | 7397 | 88 runs |
| `scenarios/dtnma_params_default.txt` | `.txt` | 60 | 36 | 1859 |  |
| `scenarios/rules_fixed_L6.txt` | `.txt` | 8 | 3 | 521 | 1 rules |
| `scenarios/rules_ntn.txt` | `.txt` | 25 | 12 | 1750 | 4 rules |
| `scenarios/rules_uav.txt` | `.txt` | 28 | 12 | 1832 | 4 rules |
| `scenarios/rules_vdtn.txt` | `.txt` | 26 | 12 | 2013 | 4 rules |
| `scenarios/rules_vdtn_L10.txt` | `.txt` | 29 | 12 | 2163 | 4 rules |
| `scenarios/rules_vdtn_L12.txt` | `.txt` | 20 | 12 | 1499 | 4 rules |
| `scenarios/rules_vdtn_L2.txt` | `.txt` | 20 | 12 | 1502 | 4 rules |
| `scenarios/rules_vdtn_L4.txt` | `.txt` | 20 | 12 | 1502 | 4 rules |
| `scenarios/rules_vdtn_L8.txt` | `.txt` | 20 | 12 | 1495 | 4 rules |
| `scenarios/rules_vdtn_minus20.txt` | `.txt` | 20 | 12 | 1443 | 4 rules |
| `scenarios/rules_vdtn_plus20.txt` | `.txt` | 20 | 12 | 1444 | 4 rules |
| `scenarios/scenario_dtnma.txt` | `.txt` | 66 | 42 | 1709 | 1 runs |
| `SEED_ROLES.md` | `.md` | 31 | 18 | 3361 |  |
| `src/report/DtnmaAuditReport.java` | `.java` | 137 | 113 | 6387 | class `DtnmaAuditReport` (26-137) |
| `src/report/DtnmaDecisionReport.java` | `.java` | 190 | 153 | 7849 | class `DtnmaDecisionReport` (33-190) |
| `src/report/DtnmaRecoveryTimelineReport.java` | `.java` | 260 | 229 | 10176 | class `DtnmaRecoveryTimelineReport` (28-260), class `MsgEvent` (34-44), class `Snapshot` (46-62) |
| `src/report/DtnmaRlMetricsReport.java` | `.java` | 1271 | 1144 | 64295 | class `DtnmaRlMetricsReport` (41-1271), class `MsgLifecycleRecord` (47-60), class `EventWindowState` (62-99) |
| `src/routing/ControlAction.java` | `.java` | 112 | 90 | 3330 | class `ControlAction` (7-112), enum `ControlType` (9-14) |
| `src/routing/DtnmaAgent.java` | `.java` | 1395 | 1202 | 68323 | class `DtnmaAgent` (27-1395), class `ForwardedCreditRecord` (31-41) |
| `src/routing/DtnmaConfig.java` | `.java` | 453 | 386 | 23337 | class `DtnmaConfig` (23-453), enum `ManagementMode` (27-33), enum `InterventionTier` (35-39) |
| `src/routing/DtnmaContactBeacon.java` | `.java` | 101 | 61 | 3792 | class `DtnmaContactBeacon` (8-101) |
| `src/routing/DtnmaFixedAckRouter.java` | `.java` | 35 | 23 | 1047 | class `DtnmaFixedAckRouter` (10-35) |
| `src/routing/DtnmaGossipAckRLRouter.java` | `.java` | 29 | 20 | 802 | class `DtnmaGossipAckRLRouter` (9-29) |
| `src/routing/DtnmaM0bStaticRouter.java` | `.java` | 31 | 20 | 900 | class `DtnmaM0bStaticRouter` (11-31) |
| `src/routing/DtnmaM0cV1GuardRouter.java` | `.java` | 47 | 34 | 1884 | class `DtnmaM0cV1GuardRouter` (11-47) |
| `src/routing/DtnmaM0dConservativeRouter.java` | `.java` | 34 | 23 | 1116 | class `DtnmaM0dConservativeRouter` (11-34) |
| `src/routing/DtnmaM0eEnergy20Router.java` | `.java` | 48 | 35 | 1887 | class `DtnmaM0eEnergy20Router` (11-48) |
| `src/routing/DtnmaM0eEnergy25Router.java` | `.java` | 48 | 35 | 1887 | class `DtnmaM0eEnergy25Router` (11-48) |
| `src/routing/DtnmaM1FloodGroupRouter.java` | `.java` | 31 | 20 | 975 | class `DtnmaM1FloodGroupRouter` (11-31) |
| `src/routing/DtnmaM1FloodRouter.java` | `.java` | 30 | 20 | 825 | class `DtnmaM1FloodRouter` (10-30) |
| `src/routing/DtnmaM1RemoteRouter.java` | `.java` | 30 | 19 | 853 | class `DtnmaM1RemoteRouter` (11-30) |
| `src/routing/DtnmaM2LocalOnlyRouter.java` | `.java` | 31 | 18 | 805 | class `DtnmaM2LocalOnlyRouter` (12-31) |
| `src/routing/DtnmaM2SrlRouter.java` | `.java` | 29 | 19 | 782 | class `DtnmaM2SrlRouter` (10-29) |
| `src/routing/DtnmaM3RlRouter.java` | `.java` | 30 | 20 | 828 | class `DtnmaM3RlRouter` (10-30) |
| `src/routing/DtnmaManagedRouter.java` | `.java` | 46 | 31 | 1506 | interface `DtnmaManagedRouter` (15-46) |
| `src/routing/DtnmaPolicyProfile.java` | `.java` | 96 | 71 | 3490 | class `DtnmaPolicyProfile` (9-96) |
| `src/routing/DtnmaRouter.java` | `.java` | 727 | 605 | 31005 | class `DtnmaRouter` (23-727), enum `IntelligenceMode` (25-30) |
| `src/routing/DtnmaRuleParser.java` | `.java` | 166 | 125 | 6460 | class `DtnmaRuleParser` (19-166) |
| `src/routing/DtnmaSafetyShield.java` | `.java` | 317 | 255 | 13667 | class `DtnmaSafetyShield` (14-317), class `ShieldDecision` (16-40) |
| `src/routing/DtnmaShieldedAckRLRouter.java` | `.java` | 30 | 20 | 880 | class `DtnmaShieldedAckRLRouter` (10-30) |
| `src/routing/DtnmaStaticAckL12Router.java` | `.java` | 27 | 16 | 699 | class `DtnmaStaticAckL12Router` (10-27) |
| `src/routing/DtnmaStaticAckL2Router.java` | `.java` | 27 | 16 | 688 | class `DtnmaStaticAckL2Router` (10-27) |
| `src/routing/DtnmaStaticAckL4Router.java` | `.java` | 27 | 16 | 688 | class `DtnmaStaticAckL4Router` (10-27) |
| `src/routing/DtnmaStaticAckL8Router.java` | `.java` | 27 | 16 | 688 | class `DtnmaStaticAckL8Router` (10-27) |
| `src/routing/DtnmaStaticAckRouter.java` | `.java` | 30 | 20 | 867 | class `DtnmaStaticAckRouter` (10-30) |
| `src/routing/DtnmaStaticAckTunedRouter.java` | `.java` | 28 | 16 | 821 | class `DtnmaStaticAckTunedRouter` (11-28) |
| `src/routing/EddTelemetrySampler.java` | `.java` | 340 | 241 | 12847 | class `EddTelemetrySampler` (23-340) |
| `src/routing/EnergyAwareEpidemicRouter.java` | `.java` | 444 | 394 | 17314 | class `EnergyAwareEpidemicRouter` (24-444), class `PeerSnapshot` (41-51) |
| `src/routing/ManagedEpidemicRouter.java` | `.java` | 236 | 202 | 9068 | class `ManagedEpidemicRouter` (21-236) |
| `src/routing/ManagedMaxPropRouter.java` | `.java` | 355 | 305 | 15038 | class `ManagedMaxPropRouter` (24-355) |
| `src/routing/ManagedProphetV2Router.java` | `.java` | 291 | 250 | 11808 | class `ManagedProphetV2Router` (23-291) |
| `src/routing/ManagedSprayAndWaitRouter.java` | `.java` | 291 | 257 | 11927 | class `ManagedSprayAndWaitRouter` (20-291) |
| `src/routing/PredicateExpression.java` | `.java` | 145 | 121 | 5260 | class `PredicateExpression` (12-145), enum `Operator` (14-16), class `Clause` (18-67) |
| `src/routing/PriorityMaxPropRouter.java` | `.java` | 144 | 116 | 5126 | class `PriorityMaxPropRouter` (22-144) |
| `src/routing/PrioritySprayAndWaitRouter.java` | `.java` | 102 | 82 | 3528 | class `PrioritySprayAndWaitRouter` (16-102) |
| `src/routing/ShieldedCmdpRlAgent.java` | `.java` | 394 | 287 | 15659 | class `ShieldedCmdpRlAgent` (13-394) |
| `src/routing/StateBasedRule.java` | `.java` | 81 | 61 | 2252 | class `StateBasedRule` (12-81) |
| `tests/DtnmaUnitTests.java` | `.java` | 1591 | 1387 | 87499 | class `DtnmaUnitTests` (74-1591), class `StubSampler` (89-109) |
| `tools/analyze_exploratory_controls.py` | `.py` | 565 | 496 | 26045 | 10 py defs |
| `tools/analyze_final_11seed.py` | `.py` | 618 | 551 | 28985 | 8 py defs |
| `tools/evaluate_autonomic_framework.py` | `.py` | 1203 | 1081 | 56416 | 19 py defs |
| `tools/parallel_batch_runner.py` | `.py` | 397 | 342 | 14417 | 10 py defs |
| `tools/plot_compA_energy_depletion.py` | `.py` | 123 | 107 | 5276 | 2 py defs |
| `tools/plot_compB_adoption_cdf.py` | `.py` | 195 | 166 | 8852 | 2 py defs |
| `tools/plot_compB_pareto.py` | `.py` | 158 | 137 | 7217 | 3 py defs |

## 2. Java Source Line Totals (`src/routing/` + `src/report/`)

| Category | Files | Total Lines | Non-Comment LOC |
|---|---:|---:|---:|
| `src/routing/*.java` | 40 | 6885 | 5568 |
| `src/report/*.java` | 4 | 1858 | 1639 |

## 3. `DtnmaConfig.java` Parameter Inventory (48 Tunable Fields)

| Key | Type | Default | Clamp Rule | Line |
|---|---|---|---|---:|
| `intelligenceMode` | `DtnmaRouter.IntelligenceMode` | `DtnmaRouter.IntelligenceMode.SHIELDED_CMDP_RL` | `none` | `src/routing/DtnmaConfig.java:42` |
| `managementMode` | `ManagementMode` | `ManagementMode.AUTO` | `none` | `src/routing/DtnmaConfig.java:43` |
| `interventionTier` | `InterventionTier` | `InterventionTier.TIER3_FULL_WITH_REPLICA_CAPS` | `none` | `src/routing/DtnmaConfig.java:44` |
| `staticQuota` | `int` | `6` | `Math.max(1, Math.min(32, staticQuota))` | `src/routing/DtnmaConfig.java:45` |
| `staticIngressMode` | `int` | `0` | `Math.max(0, Math.min(2, staticIngressMode))` | `src/routing/DtnmaConfig.java:46` |
| `staticBackpressure` | `boolean` | `true` | `none` | `src/routing/DtnmaConfig.java:47` |
| `enablePriorityProtection` | `boolean` | `true` | `none` | `src/routing/DtnmaConfig.java:48` |
| `epidemicControlFlooding` | `boolean` | `false` | `none` | `src/routing/DtnmaConfig.java:49` |
| `densityAdaptiveGossip` | `boolean` | `true` | `none` | `src/routing/DtnmaConfig.java:50` |
| `openLoopAuditOnly` | `boolean` | `false` | `none` | `src/routing/DtnmaConfig.java:51` |
| `gossipEnabled` | `boolean` | `true` | `none` | `src/routing/DtnmaConfig.java:52` |
| `ackFeedbackEnabled` | `boolean` | `true` | `none` | `src/routing/DtnmaConfig.java:53` |
| `rlEpochInterval` | `double` | `15.0` | `Math.max(1.0, Math.min(600.0, rlEpochInterval))` | `src/routing/DtnmaConfig.java:54` |
| `rlAlpha` | `double` | `0.15` | `Math.max(1e-4, Math.min(1.0, rlAlpha))` | `src/routing/DtnmaConfig.java:57` |
| `rlGamma` | `double` | `0.90` | `Math.max(0.0, Math.min(0.999, rlGamma))` | `src/routing/DtnmaConfig.java:58` |
| `rlWeightDecay` | `double` | `1e-4` | `Math.max(0.0, Math.min(0.1, rlWeightDecay))` | `src/routing/DtnmaConfig.java:59` |
| `rlEpsilonStart` | `double` | `0.15` | `Math.max(0.0, Math.min(1.0, rlEpsilonStart))` | `src/routing/DtnmaConfig.java:60` |
| `rlEpsilonMin` | `double` | `0.01` | `Math.max(0.0, Math.min(rlEpsilonStart, rlEpsilonMin))` | `src/routing/DtnmaConfig.java:61` |
| `rlEpsilonDecay` | `double` | `900.0` | `Math.max(10.0, rlEpsilonDecay)` | `src/routing/DtnmaConfig.java:62` |
| `rlLambdaInit` | `double` | `0.0` | `Math.max(0.0, Math.min(rlLambdaMax, rlLambdaInit))` | `src/routing/DtnmaConfig.java:63` |
| `rlEtaLambda` | `double` | `0.0` | `Math.max(0.0, Math.min(1.0, rlEtaLambda))` | `src/routing/DtnmaConfig.java:64` |
| `rlTargetCost` | `double` | `0.30` | `Math.max(0.0, Math.min(2.0, rlTargetCost))` | `src/routing/DtnmaConfig.java:65` |
| `rlLambdaMax` | `double` | `5.0` | `Math.max(0.1, Math.min(100.0, rlLambdaMax))` | `src/routing/DtnmaConfig.java:66` |
| `rlOptimisticInit` | `double` | `0.0` | `none` | `src/routing/DtnmaConfig.java:67` |
| `rewardDirectDelivery` | `double` | `2.5` | `Math.max(0.0, rewardDirectDelivery)` | `src/routing/DtnmaConfig.java:70` |
| `rewardAckDelivery` | `double` | `1.5` | `Math.max(0.0, rewardAckDelivery)` | `src/routing/DtnmaConfig.java:71` |
| `rewardPredProgress` | `double` | `1.5` | `Math.max(0.0, rewardPredProgress)` | `src/routing/DtnmaConfig.java:72` |
| `rewardCoverageGain` | `double` | `1.0` | `Math.max(0.0, rewardCoverageGain)` | `src/routing/DtnmaConfig.java:73` |
| `penaltyBulkDrop` | `double` | `0.35` | `Math.max(0.0, penaltyBulkDrop)` | `src/routing/DtnmaConfig.java:74` |
| `penaltyRelayCost` | `double` | `0.02` | `Math.max(0.0, penaltyRelayCost)` | `src/routing/DtnmaConfig.java:75` |
| `gossipCooldown` | `double` | `120.0` | `Math.max(1.0, gossipCooldown)` | `src/routing/DtnmaConfig.java:78` |
| `gossipWeightMin` | `double` | `0.05` | `Math.max(0.0, Math.min(0.5, gossipWeightMin))` | `src/routing/DtnmaConfig.java:79` |
| `gossipWeightMax` | `double` | `0.25` | `Math.max(gossipWeightMin, Math.min(0.5, gossipWeightMax))` | `src/routing/DtnmaConfig.java:80` |
| `gossipSameClassOnly` | `boolean` | `true` | `none` | `src/routing/DtnmaConfig.java:81` |
| `gossipMinEpochs` | `int` | `8` | `Math.max(1, Math.min(500, gossipMinEpochs))` | `src/routing/DtnmaConfig.java:82` |
| `beaconRefreshInterval` | `double` | `5.0` | `Math.max(0.5, beaconRefreshInterval)` | `src/routing/DtnmaConfig.java:83` |
| `ackCacheSize` | `int` | `512` | `Math.max(32, Math.min(10000, ackCacheSize))` | `src/routing/DtnmaConfig.java:84` |
| `ackMaxPerContact` | `int` | `32` | `Math.max(1, Math.min(512, ackMaxPerContact))` | `src/routing/DtnmaConfig.java:85` |
| `shieldCriticalQueue` | `double` | `0.94` | `Math.max(0.50, Math.min(1.0, shieldCriticalQueue))` | `src/routing/DtnmaConfig.java:88` |
| `shieldCongestionQueue` | `double` | `0.90` | `Math.max(0.40, Math.min(shieldCriticalQueue, shieldCongestionQueue))` | `src/routing/DtnmaConfig.java:89` |
| `shieldCriticalCritQueue` | `double` | `0.50` | `Math.max(0.10, Math.min(1.0, shieldCriticalCritQueue))` | `src/routing/DtnmaConfig.java:90` |
| `shieldWarnCritQueue` | `double` | `0.35` | `Math.max(0.05, Math.min(shieldCriticalCritQueue, shieldWarnCritQueue))` | `src/routing/DtnmaConfig.java:91` |
| `shieldSurgeQueue` | `double` | `0.82` | `Math.max(0.30, Math.min(shieldCongestionQueue, shieldSurgeQueue))` | `src/routing/DtnmaConfig.java:92` |
| `shieldSurgeDrift` | `double` | `0.015` | `Math.max(0.001, Math.min(0.50, shieldSurgeDrift))` | `src/routing/DtnmaConfig.java:93` |
| `shieldStormQueue` | `double` | `0.78` | `Math.max(0.30, Math.min(shieldCongestionQueue, shieldStormQueue))` | `src/routing/DtnmaConfig.java:94` |
| `shieldStormDensity` | `double` | `5.0` | `Math.max(1.0, shieldStormDensity)` | `src/routing/DtnmaConfig.java:95` |
| `shieldEnergyMin` | `double` | `0.20` | `Math.max(0.0, Math.min(0.90, shieldEnergyMin))` | `src/routing/DtnmaConfig.java:96` |
| `equivalenceConnOuterOrder` | `boolean` | `false` | `none` | `src/routing/DtnmaConfig.java:97` |

## 4. Scenario Catalog Extracted from `scenarios/*.txt`

| File | EndTime (s) | UpdateInterval (s) | Total Runs | Rules File | Report Dir |
|---|---:|---:|---:|---|---|
| `scenarios/compA_benign.txt` | 43200 | 0.5 | 88 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compA_benign` |
| `scenarios/compA_energy_blackout.txt` | 43200 | 0.5 | 99 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compA_energy_blackout` |
| `scenarios/compA_equivalence.txt` | 43200 | 0.5 | 22 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compA_equivalence` |
| `scenarios/compA_equivalence_default.txt` | 43200 | 0.5 | 22 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compA_equivalence_default` |
| `scenarios/compA_ntn_space.txt` | 43200 | 0.5 | 88 | `scenarios/rules_ntn.txt` | `reports/final_11seed/compA_ntn_space` |
| `scenarios/compA_tiers.txt` | 43200 | 0.5 | 132 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compA_tiers` |
| `scenarios/compA_vdtn_overload.txt` | 21600 | 0.5 | 88 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compA_vdtn_overload` |
| `scenarios/compB_benign.txt` | 43200 | 0.5 | 88 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compB_benign` |
| `scenarios/compB_exp_controls_benign.txt` | 43200 | 0.5 | 55 | `scenarios/rules_vdtn.txt` | `reports/exploratory_v1_0_controls/controls_benign` |
| `scenarios/compB_exp_controls_stress.txt` | 43200 | 0.5 | 55 | `scenarios/rules_vdtn.txt` | `reports/exploratory_v1_0_controls/controls_stress` |
| `scenarios/compB_exp_groupflood_benign.txt` | 43200 | 0.5 | 22 | `scenarios/rules_vdtn.txt` | `reports/exploratory_v1_0_controls/groupflood_benign` |
| `scenarios/compB_exp_groupflood_stress.txt` | 43200 | 0.5 | 22 | `scenarios/rules_vdtn.txt` | `reports/exploratory_v1_0_controls/groupflood_stress` |
| `scenarios/compB_exp_m2_localonly_benign.txt` | 43200 | 0.5 | 11 | `scenarios/rules_vdtn.txt` | `reports/exploratory_v1_0_controls/m2_localonly_benign` |
| `scenarios/compB_exp_m2_localonly_stress.txt` | 43200 | 0.5 | 11 | `scenarios/rules_vdtn.txt` | `reports/exploratory_v1_0_controls/m2_localonly_stress` |
| `scenarios/compB_multitimescale.txt` | 43200 | 0.5 | 88 | `scenarios/rules_vdtn.txt` | `reports/final_11seed/compB_multitimescale` |
| `scenarios/scenario_dtnma.txt` | 3600 | 0.5 | 1 | `scenarios/rules_ntn.txt` | `reports/` |

## 5. `DtnmaUnitTests.java` Check Catalog (30 `check()` Assertions)

| # | Assertion Label | Location |
|---:|---|---|
| 1 | `Test1a: Shield S1 distinguishes CritQ/active-surge {0} from steady full bulk cache (P4 admissible)` | `tests/DtnmaUnitTests.java:140` |
| 2 | `Test1b: Shield S2 energy SoC<0.20 survival failsafe restricts to {0,1}` | `tests/DtnmaUnitTests.java:147` |
| 3 | `Test1c: Shield S3 dense storm guard blocks high-spray {6,7}` | `tests/DtnmaUnitTests.java:154` |
| 4 | `Test1d: Shield S4 priority-100 rule enforces quota<=2 while keeping liveness` | `tests/DtnmaUnitTests.java:161` |
| 5 | `Test2a: Safe-Anchored shaping drives Q(s_cong, unsafe) < Q(s_cong, safe) in single-critic mode` | `tests/DtnmaUnitTests.java:190` |
| 6 | `Test2b: Hazard-localized penalty keeps healthy Q=0.25 at Profile 4 & phi[16]=0 on steady full bulk` | `tests/DtnmaUnitTests.java:196` |
| 7 | `Test3a: gossipMinEpochs guard blocks premature gossip (<15 epochs)` | `tests/DtnmaUnitTests.java:213` |
| 8 | `Test3b: Symmetric atomic gossip is class-gated, lambda=0.0, & strictly order-independent (A<->B == B<->A)` | `tests/DtnmaUnitTests.java:239` |
| 9 | `Test4: Causal Delayed-ACK eligibility credits forwarded (s, a) without leaking to other actions` | `tests/DtnmaUnitTests.java:255` |
| 10 | `Test5: ContactBeacon 16B wire quantization & exact single-critic gossip size (648B)` | `tests/DtnmaUnitTests.java:275` |
| 11 | `Test6: Relay-time adaptive split enforces exact Binary Token Conservation` | `tests/DtnmaUnitTests.java:300` |
| 12 | `Test7: All-Critical Full-Buffer enforces occ <= 1.0 & never evicts un-ACKed critical copies` | `tests/DtnmaUnitTests.java:355` |
| 13 | `Test8: localCreationRejected audit, PrioritySprayAndWaitRouter protection & DtnmaShieldedAckRLRouter flags` | `tests/DtnmaUnitTests.java:392` |
| 14 | `Test9: Protected FIFO evicts oldest bulk before copies=1 while protecting critical & reporting critical occupancy` | `tests/DtnmaUnitTests.java:447` |
| 15 | `Test10: PriorityMaxProp zero-drop & cap enforcement + DtnmaStaticAckTunedRouter L=10 load` | `tests/DtnmaUnitTests.java:520` |
| 16 | `Test11: Step-3 DtnmaFixedAckRouter, StaticAck L2/L4, CPU counter & CTRL/TELEM classification` | `tests/DtnmaUnitTests.java:574` |
| 17 | `Test12: In-band RFC 9675 Downlink CTRL_UPDATE V1/V2 delivery triggers live shield & SRL reconfig` | `tests/DtnmaUnitTests.java:633` |
| 18 | `Test13: Composed Managed{SnW,PRoPHETv2,MaxProp,Epidemic} protect un-ACKed critical & track split rejections` | `tests/DtnmaUnitTests.java:700` |
| 19 | `Test14: ManagedSprayAndWait matches PrioritySnW protection & supports runtime loadRulesFromString` | `tests/DtnmaUnitTests.java:769` |
| 20 | `Test15: M0 vs M1 remote-only vs M2 local SRL split, repeated CTRL_UPDATE, & epidemic ctrl flag` | `tests/DtnmaUnitTests.java:849` |
| 21 | `Test16: Density-adaptive gossip cooldown scales to 360s at D=12 & openLoopAuditOnly leaves L=6 unmutated` | `tests/DtnmaUnitTests.java:901` |
| 22 | `Test17: Static oracle presets DtnmaStaticAckL8Router & DtnmaStaticAckL12Router load L=8,12 cleanly` | `tests/DtnmaUnitTests.java:942` |
| 23 | `Test18: InterventionTier 1/3, M0b..M3 presets, & rules_vdtn catch-all release work correctly` | `tests/DtnmaUnitTests.java:1038` |
| 24 | `Test19: M0c V1-guard/immutable, M0d/M1-F presets, ordering flag, & EnergyAwareEpidemic work cleanly` | `tests/DtnmaUnitTests.java:1107` |
| 25 | `Test20: Nominal backpressure (P3), isHostKnownAcked, & ACKed critical eviction split work cleanly` | `tests/DtnmaUnitTests.java:1262` |
| 26 | `Test20: Nominal backpressure (P3), isHostKnownAcked, & ACKed critical eviction split` | `tests/DtnmaUnitTests.java:1269` |
| 27 | `Test21: M0e-20/M0e-25 energy-only guards & M1-F-Group broadcast adoption work cleanly` | `tests/DtnmaUnitTests.java:1426` |
| 28 | `Test21: M0e-20/M0e-25 energy-only guards & M1-F-Group broadcast adoption` | `tests/DtnmaUnitTests.java:1432` |
| 29 | `Test22: M2-LocalOnly ignores remote CTRL_UPDATE overlays while preserving local rules` | `tests/DtnmaUnitTests.java:1539` |
| 30 | `Test22: M2-LocalOnly ignores remote CTRL_UPDATE overlays` | `tests/DtnmaUnitTests.java:1544` |
