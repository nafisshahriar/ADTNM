# ADTNM — Autonomic Delay/Disruption-Tolerant Network Management

[![Status](https://img.shields.io/badge/status-work%20in%20progress-amber)](#project-status--license)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Simulator](https://img.shields.io/badge/simulator-The%20ONE%20(Java%2017%2B)-green)](#quick-start)
[![Standards](https://img.shields.io/badge/design-RFC%209675%20%26%207575--inspired-informational)](#documentation--study-guide)

> [!NOTE] Active Research — Work in Progress
> **ADTNM** is an active research framework released under the **[MIT License](LICENSE)**. APIs, scenario configurations, and optional learning modules are still evolving as evaluation continues.

**ADTNM** is an architectural management layer for Delay/Disruption-Tolerant Networks (DTNs) built on top of **The ONE** (*Opportunistic Network Environment*) simulator. Inspired by **IETF RFC 9675** (*DTN Management Architecture*, Nov 2024) and **IRTF RFC 7575** (*Autonomic Networking*, June 2015), it cleanly separates *network management* from *bundle forwarding*:

- Existing DTN protocols (**Spray-and-Wait**, **PRoPHETv2**, **MaxProp**, and **Epidemic**) operate unchanged as **managed substrates** via lightweight adapters implementing `DtnmaManagedRouter`.
- A shared local engine (`DtnmaAgent`) executes an onboard closed loop combining **11 Externally Defined Data (`EDD`)** telemetry indicators, declarative **State-Based Rules (`SRL`)**, discrete **`P0–P7` Control Profiles**, a deterministic **Safety Shield (`S1–S4`)**, delayed delivery `ACK` copy pruning, and **Application Resource Identifier (`ARI`)** audit traces.
- An optional Constrained MDP (`CMDP`) reinforcement learning module (`ShieldedCmdpRlAgent`) is included as an **experimental baseline**; in current evaluations, compact declarative rules remain the stronger and recommended controller.

![ADTNM Four-Plane Architecture](docs/study-guide/figures/svg/fig01_architecture_overview.svg)

---

## Why Separate Management from Routing?

In sparse vehicular and emergency mobile DTNs, centralized Manager commands arrive orders of magnitude too slowly (`minutes to hours`) to contain sudden `5`-minute traffic bursts, while unconstrained flooding exhausts node batteries. Rather than inventing yet another routing heuristic, ADTNM wraps diverse routers with uniform, auditable resource governance:

- **Tier 1 (Critical Protection & ACK Pruning):** Five-tier $O(N)$ priority queue protection and compact `4 B` delivery acknowledgments preserve critical bundles without changing underlying forwarding paths.
- **Tier 2 (Ingress & Energy Autonomy):** Node-local `SRL` rules and hard safety limits (`S1–S4`) shed non-critical load during congestion surges and energy depletion before cascading blackouts occur.
- **Full Auditability:** Every local decision captures an atomic telemetry snapshot and emits a deterministic `ARI` audit record suitable for post-hoc verification.

---

## Quick Start

Requires **Java JDK `17+`**, **Python `3.10+`**, and a sibling copy of **The ONE** (`../the-one`).

```powershell
# 1. Sync ADTNM sources/scenarios into The ONE and compile (adds ADTNM classes to ../the-one/target)
python install.py --one-dir ..\the-one

# 2. Run the Java unit test suite (from The ONE or with target on classpath)
java -cp "..\the-one\target;..\the-one\lib\*" DtnmaUnitTests

# 3. Run the 1-hour smoke scenario headless
Push-Location ..\the-one
java -Xmx512m -cp "target;lib\*" core.DTNSim -b 1 scenarios\scenario_dtnma.txt
Pop-Location
```

*(Set `$env:PYTHONDONTWRITEBYTECODE = '1'` on Windows PowerShell if you want to prevent Python `__pycache__` folders.)*

---

## Declarative Policy Example (`SRL`)

Operators customize congestion and energy responses in plain `.txt` rule files (`scenarios/rules_vdtn.txt`) without recompiling Java:

```text
RULE: ARI://DTNMA/SRL/VDTN_BUFFER_CRITICAL | PRIORITY: 100 | DESC: Emergency hold on low battery or critical pressure
IF: (EDD_ENERGY_SOC < 0.20) || (EDD_CRIT_QUEUE_RATIO >= 0.50)
THEN: CTRL_SET_SPRAY_QUOTA(1); CTRL_SET_INGRESS_MODE(2); CTRL_SET_TELEMETRY_INTERVAL(600); CTRL_SET_BACKPRESSURE(true)

RULE: ARI://DTNMA/SRL/VDTN_SURGE_THROTTLE | PRIORITY: 80 | DESC: Throttle bulk spray during active surges
IF: (EDD_QUEUE_RATIO >= 0.88) && (EDD_QUEUE_DRIFT >= 0.008)
THEN: CTRL_SET_SPRAY_QUOTA(2); CTRL_SET_INGRESS_MODE(1); CTRL_SET_TELEMETRY_INTERVAL(300); CTRL_SET_BACKPRESSURE(true)

RULE: ARI://DTNMA/SRL/VDTN_URBAN_NOMINAL | PRIORITY: 10 | DESC: Release throttling promptly once calm
IF: (EDD_QUEUE_RATIO >= 0.0)
THEN: CTRL_SET_SPRAY_QUOTA(6); CTRL_SET_INGRESS_MODE(0); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(true)
```

---

## Research Takeaways at a Glance

- **Cross-Router Critical Preservation:** Across held-out evaluations spanning urban overload, energy blackout, and benign vehicular operation, all four managed substrates (`SprayAndWait`, `PRoPHETv2`, `MaxProp`, and `Epidemic`) achieved zero lost unique critical bundles.
- **Policy Over Routing Churn:** Tier decomposition shows that survival improvements stem chiefly from ingress and energy governance (`Tier 2`), leaving native forwarding logic untouched.
- **Local Reactivity vs. Remote Delay:** Sub-minute local rules eliminate post-burst stale throttling caused by delayed Manager downlinks during short surges while sustaining benign throughput; however, trade-offs remain between aggressive remote throttling and unconstrained stress survival, and the experimental RL planner lags behind deterministic rules.
- **Deterministic Traceability:** Decision-time atomic snapshots deliver `100%` deterministic offline audit replay across full multi-seed campaigns.

---

## Documentation & Study Guide

For a gentle zero-background introduction, full mechanism specifications, scenario setup details, evaluation methodology, and reproducible tutorials, start with the Study Guide:

| Resource | Audience | Location |
|---|---|---|
| **Interactive HTML Study Guide** | Everyone (Offline Reader + Search) | [`docs/study-guide/build/ADTNM_Study_Guide.html`](docs/study-guide/build/ADTNM_Study_Guide.html) |
| **Markdown Study Guide Hub** | Readers & Developers | [`docs/study-guide/index.md`](docs/study-guide/index.md) |
| **Complete Handbook (PDF)** | Deep Technical Reference | [`docs/study-guide/build/ADTNM_Study_Guide.pdf`](docs/study-guide/build/ADTNM_Study_Guide.pdf) |
| **Executive Summary (2-Page PDF)** | Supervisors & Reviewers | [`docs/study-guide/build/ADTNM_Executive_Summary.pdf`](docs/study-guide/build/ADTNM_Executive_Summary.pdf) |
| **One-Page Cheat Sheet (PDF)** | Day-to-Day Development | [`docs/study-guide/build/ADTNM_One_Page_Cheatsheet.pdf`](docs/study-guide/build/ADTNM_One_Page_Cheatsheet.pdf) |

### Repository Layout

- `src/routing/` — Core management engine (`DtnmaAgent`, `EddTelemetrySampler`, `DtnmaSafetyShield`, `DtnmaRuleParser`, `ShieldedCmdpRlAgent`) and thin `Managed*Router` substrate adapters
- `src/report/` — Metrics, audit logging (`ARI`), health timelines, and decision summary reporters
- `scenarios/` — Smoke, multi-timescale stress/benign, cross-router Comparison A, and declarative rule files
- `tools/` — Parallel batch runner, audit replay verifier, and statistical analysis scripts
- `tests/` — Standalone Java unit test suite (`DtnmaUnitTests.java`)
- `docs/study-guide/` — Academic Study Guide, figures, tutorials, worked examples, HTML/PDF builds

---

## Project Status & License

This codebase is **work in progress** provided under the **[MIT License](LICENSE)** (`Copyright (c) 2026 Nafis Shahriar`). Upstream simulator components in `the-one` remain subject to their original GPLv3 license.

```bibtex
@misc{shahriar2026adtnm,
  author = {Nafis Shahriar},
  title  = {ADTNM: Autonomic Management Architecture for Delay/Disruption-Tolerant Networks},
  year   = {2026},
  note   = {Work in progress; MIT License; RFC 9675 and RFC 7575-inspired framework in The ONE}
}
```
