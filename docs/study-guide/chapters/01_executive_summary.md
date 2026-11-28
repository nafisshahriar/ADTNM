# Chapter 1: Executive Summary

> **Status:** Active Research Framework (Work in Progress) · **License:** MIT · **Authors:** S. M. Nafis Shahriar, Pulok Akibuzzaman, Nusrat Rahman Aurna, Md Safin Rahman

---

## 1. Architectural Identity & Core Thesis

**ADTNM** (*Autonomic Delay/Disruption-Tolerant Network Management*) is an architectural management framework for challenged mobile networks built in Java atop **The ONE** simulator. Inspired by **IETF RFC 9675** (*DTN Management Architecture*, Nov 2024) and **IRTF RFC 7575** (*Autonomic Networking*, June 2015), ADTNM decouples *network management semantics* from *bundle forwarding heuristics*:

- Classical DTN routers (`SprayAndWaitRouter`, `ProphetV2Router`, `MaxPropRouter`, `EpidemicRouter`) operate unchanged as **managed substrates** via lightweight adapters implementing `DtnmaManagedRouter`.
- A shared local engine (`DtnmaAgent`) samples **15 Externally Defined Data (`EDD`)** indicators, executes either closed-form **Mobility-Aware Distributed Resource Coordination (`M2_MOBILITY_AWARE`)** or declarative **State-Based Rules (`SRL`)**, enforces a deterministic **Safety Shield (`S1–S4`)** over discrete **`P0–P7` profiles**, exchanges `16 B` contact beacons and `4 B` delivery `ACK` IDs, and logs `100.00%` traceable **Application Resource Identifier (`ARI`)** audit records.

![ADTNM Architecture Overview](../figures/svg/fig01_architecture_overview.svg)

---

## 2. Headline Empirical Findings (`1,216` Confirmatory + `176` Exploratory Runs)

Evaluated across `19` matched prime mobility seeds (`8101..8269`) and `8` predeclared Holm-Bonferroni families (`112` confirmatory tests) plus `11` isolated sensitivity seeds (`8101..8209`):

- **Env 1 Urban VDTN Stress (`126` nodes, `12 h`, `n = 19`):** `M2-Mob` preserves **`102.42 +- 0.52`** surviving nodes (`+18.42` over `M2-SRL` at `84.00`, `+47.37` over stock `SprayAndWait`), **`98.48%`** critical delivery (`0.0` unique critical lost), and **`70.18%`** bulk delivery (`+3.68 pp` over `M2-SRL` at `66.50%`), while maintaining benign energy non-interference (`82.41%` bulk, `126/126` alive) and low overhead (`15.5 us` mean CPU, `0.79%` control-to-payload).
- **Cross-Substrate Generality (`F4`, `4` Stock vs. Managed Substrates):** Wrapping stock routers with `M2-Mob` eliminates `unique_critical_lost` (`70.8–124.0 -> 0.0` across all 19 seeds), lifts critical delivery by **`+24.94 pp`** (`SnW`), **`+63.56 pp`** (`PRoPHETv2`), **`+33.18 pp`** (`MaxProp`), and **`+53.71 pp`** (`Epidemic`), and cuts `Epidemic` replication overhead from **`172.8x` to `53.8x`**.
- **Scalability (`N = 60..500`, `6 h`):** Bulk delivery gain over `M2-SRL` grows monotonically (`+0.37 pp ns` at `N=60` to `+1.22 pp ***` at `N=500`) at **`16.3–20.7 us`** CPU per decision and **`17.7 -> 54.3 KB/node`** linear state footprint.
- **Explicit Trade-Offs & Scope Boundaries:** During sustained `Event C` overload (`Hours 6–8`), `M2-Mob` matches `M2-SRL` (`67.22%` vs `67.33%`, `ns`); under severe `0.75x` exploratory battery scarcity, `M2-Mob` saves `+6.18` alive nodes and `+6.85 pp` critical delivery by shedding `-2.82 pp` bulk traffic; and `Env 2` / `Env 3` confirm regime boundaries (`~6.3%` sparse bulk floor; `125.79/126` 8h energy ceiling) rather than unconstrained dominance.
