# ADTNM Predeclared Claims Ledger (Mobility-Aware Coordination)

All statistical hypothesis tests are evaluated under predeclared Holm-Bonferroni family-wise error rate control in [analyze_mobility_campaign.py](tools/analyze_mobility_campaign.py) and [build_publication_package.py](tools/build_publication_package.py).

## 1. Confirmatory Evaluation Families (`mob_*.txt`, 19 Prime Seeds `8101..8269`)

| Family ID | Tests ($m$) | Scenario(s) | Target vs. Comparators | Endpoints Tested |
|---|---:|---|---|---|
| **F1_Env1_Stress_Core** | 18 | `mob_env1_vdtn_stress` | `M2-Mob` (`DtnmaM2MobRouter`) vs `{M2-SRL, M0e-25, M1-Remote, EnergyOnly, BufferOnly, ReplicaOnly}` | `alive_nodes_end`, `critical_delivery_prob`, `bulk_delivery_prob` |
| **F1b_Env1_Stress_EventC** | 6 | `mob_env1_vdtn_stress` | `M2-Mob` vs same 6 comparators | `eventC_bulk_deliv` |
| **F1c_Env1_Stress_NonDtnma** | 8 | `mob_env1_vdtn_stress` | `M2-Mob` vs `{SprayAndWait, CongestionAwareSnW}` | `alive_nodes_end`, `critical_delivery_prob`, `bulk_delivery_prob`, `eventC_bulk_deliv` |
| **F2_Env1_Benign_NonInterference** | 10 | `mob_env1_vdtn_benign` | `M2-Mob` vs `{M2-SRL, M0e-25, M1-Remote, SprayAndWait, CongestionAwareSnW}` | `bulk_delivery_prob`, `eventC_bulk_deliv` |
| **F3a_Env2_WorkingDay** | 9 | `mob_env2_workingday` | `M2-Mob` vs `{M2-SRL, M0e-25, M1-Remote}` | `alive_nodes_end`, `critical_delivery_prob`, `bulk_delivery_prob` |
| **F3b_Env3_DisasterRelief** | 9 | `mob_env3_disaster_relief` | `M2-Mob` vs `{M2-SRL, M0e-25, M1-Remote}` | `alive_nodes_end`, `critical_delivery_prob`, `bulk_delivery_prob` |
| **F4_CrossSubstrate** | 16 | `mob_cross_substrate` | `Managed{SprayAndWait, ProphetV2, MaxProp, Epidemic}` vs stock counterparts | `alive_nodes_end`, `critical_delivery_prob`, `bulk_delivery_prob`, `unique_critical_lost` |
| **F5_Scalability_Joint** | 36 | `mob_scale_{60,126,250,500}` | `M2-Mob` vs `{M2-SRL, M1-Remote, SprayAndWait}` | `critical_delivery_prob`, `bulk_delivery_prob`, `eventC_bulk_deliv` |

## 2. Exploratory Sensitivity Sweeps (`sens_*.txt`, Seeds `8101..8209`)

Strictly isolated from confirmatory Holm families:
- `sens_range_20m.txt` / `sens_range_55m.txt`: radio range sensitivity
- `sens_energy_075x.txt` / `sens_energy_135x.txt`: energy budget sensitivity

## 3. Architectural Design Decisions & Scope

1. **Deterministic Closed-Form Coordination (`M2_MOBILITY_AWARE`):** Uses an auditable closed-form coordinator driven by first-class mobility EDDs (`EDD_CONTACT_DURATION_MEAN`, `EDD_INTER_CONTACT_TIME`, `EDD_ENCOUNTER_RATE`, `EDD_ENERGY_TTD_RATIO`) constrained by the hard `S1–S4` Safety Shield.
2. **Modular Cross-Substrate Adapters (`Managed*Router`):** `DtnmaAgent` is composed into stock `SprayAndWait`, `PRoPHETv2`, `MaxProp`, and `Epidemic` routers via `DtnmaManagedRouter`, preserving native routing tables and path metrics while enforcing critical buffer protection, `4 B` ACK pruning, and optional `16 B` beacon backpressure/quota governance.
3. **Report Output Format:** Active simulation runs emit `*_DtnmaMetricsReport.txt` (`shield_decisions_total`); analysis scripts retain legacy filename read fallback for archived campaign outputs.
