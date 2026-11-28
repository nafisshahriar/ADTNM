# ADTNM Seed Governance & Experimental Isolation Record

To prevent post-hoc seed selection or parameter tuning on evaluation trajectories, confirmatory mobility campaigns and exploratory sensitivity sweeps follow a strict seed-partitioning and scenario-freeze discipline.

## 1. Seed Partitions

| Role | Seeds (`MovementModel.rngSeed`) | Runs | Usage Policy |
|---|---|---:|---|
| **Confirmatory Mobility Campaign (`mob_*.txt`)** | `8101, 8111, 8117, 8123, 8147, 8161, 8167, 8171, 8179, 8191, 8209, 8219, 8221, 8231, 8233, 8237, 8243, 8263, 8269` (`19` primes) | `1,216` | Reserved for confirmatory evaluation across the `8` predeclared Holm-Bonferroni families (`F1`, `F1b`, `F1c`, `F2`, `F3a`, `F3b`, `F4`, `F5`; `112` tests total). Prime count `19` is coprime to all router sweep lengths (`4, 8, 10`) to prevent modular-index collisions in The ONE. |
| **Isolated Exploratory Sensitivity Sweeps (`sens_*.txt`)** | `8101, 8111, 8117, 8123, 8147, 8161, 8167, 8171, 8179, 8191, 8209` (first `11` matched primes) | `176` | Strictly isolated from all confirmatory Holm-Bonferroni families and main-paper confirmatory tables (`sens_range_20m.txt`, `sens_range_55m.txt`, `sens_energy_075x.txt`, `sens_energy_135x.txt`). |
| **Retired Single-Regime Legacy Archives** | `4001..4021` (dev), `7103..7207` (`v1.0`), `7211..7307` (`exp-controls`) | Superseded | Belonged to retired CompA/CompB single-regime RL/SRL campaigns; not used in the active mobility-aware evaluation suite. |

## 2. Stochastic Scope Note

Across matched runs within each seed, only `MovementModel.rngSeed` varies across the `19` confirmatory (or `11` exploratory) trajectories. `MessageEventGenerator`, `EnergyModel`, and host update schedules remain deterministic per scenario configuration. Paired differences $d_i = X_{A,i} - X_{B,i}$ therefore quantify controller robustness across independent mobility realizations under identical traffic and radio workloads, verified with frozen SHA-256 manifests (`2,432` checked files, `0` mismatches, `0` zero-size files).
