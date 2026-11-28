# ADTNM — Autonomic Delay/Disruption-Tolerant Network Management

[![Status: Work in Progress](https://img.shields.io/badge/status-work%20in%20progress-orange)](#project-status--license)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Simulator](https://img.shields.io/badge/simulator-The%20ONE%20(Java%2017%2B)-green)](#prerequisites)
[![Standards](https://img.shields.io/badge/design-RFC%209675%20%26%207575--inspired-informational)](#documentation--study-guide)

> [!NOTE] Work in Progress
> **ADTNM** is an active **work-in-progress** research framework released under the **[MIT License](LICENSE)** by **S. M. Nafis Shahriar, Pulok Akibuzzaman, Nusrat Rahman Aurna, and Md Safin Rahman**.

**ADTNM** (*Autonomic Delay/Disruption-Tolerant Network Management*) is an architectural management layer built atop **The ONE** (*Opportunistic Network Environment*) simulator. Inspired by **IETF RFC 9675** (*DTN Management Architecture*, Nov 2024) and **IRTF RFC 7575** (*Autonomic Networking*, June 2015), it cleanly decouples *network management* from *bundle forwarding*:

- Existing DTN protocols (**Spray-and-Wait**, **PRoPHETv2**, **MaxProp**, and **Epidemic**) operate unchanged as **managed substrates** via thin adapters implementing `DtnmaManagedRouter`.
- A shared local engine (`DtnmaAgent`) executes an onboard closed loop combining **15 Externally Defined Data (`EDD`)** telemetry indicators (queue occupancy/drift, battery state of charge and time-to-depletion, node density/speed, neighbor queue gradients, contact duration, inter-contact time, and encounter rate), a closed-form **Mobility-Aware Distributed Resource Coordinator (`M2_MOBILITY_AWARE`)**, declarative **State-Based Rules (`SRL`)**, discrete **`P0–P7` Control Profiles**, a deterministic **Safety Shield (`S1–S4`)**, compact `16 B` peer contact beacons, `4 B` delayed delivery `ACK` copy pruning, and deterministic **Application Resource Identifier (`ARI`)** audit traces.

![ADTNM Layered Autonomic Management Architecture](docs/study-guide/figures/svg/fig01_architecture_overview.svg)

---

## Prerequisites

- **Java JDK `17+`** (`javac`, `java`)
- **Python `3.10+`** (with `numpy`, `scipy`, `matplotlib` for analysis/plotting; add `markdown`, `pygments`, `reportlab`, `PyMuPDF`, `Pillow` only if rebuilding the Study Guide HTML/PDFs)
- **Git** and a sibling copy of **The ONE Simulator** (`https://github.com/akeranen/the-one.git`)

---

## Installation & Setup

ADTNM lives beside a standard `the-one` directory and deploys its sources, scenarios, and analysis tools cleanly via `install.py` (or `install.bat` on Windows / `install.sh` on Linux & macOS):

```powershell
# 1. Clone ADTNM and The ONE as sibling directories
git clone https://github.com/nafisshahriar/ADTNM.git
git clone https://github.com/akeranen/the-one.git the-one

# 2. Deploy ADTNM into The ONE and compile all Java sources + adapters
cd ADTNM
$env:PYTHONDONTWRITEBYTECODE = '1'   # Optional on Windows: avoid __pycache__ folders
python install.py --one-dir ..\the-one
```

Useful installer options:
- `python install.py --one-dir ..\the-one --dry-run` — preview what would be copied without modifying files.
- `python install.py --one-dir ..\the-one --status` — check file synchronization status between `ADTNM` and `the-one`.
- `python install.py --one-dir ..\the-one --no-compile` — copy files without invoking `javac`.

*(On Windows you may also run `install.bat --one-dir ..\the-one`; on Linux/macOS run `bash install.sh --one-dir ../the-one`.)*

---

## Usage & Verification Workflow

### 1. Run the Java Unit Test Suite

```powershell
javac -cp "..\the-one\target;..\the-one\lib\*" -d ..\the-one\target tests\DtnmaUnitTests.java
java -cp "..\the-one\target;..\the-one\lib\*" DtnmaUnitTests
```

Expected summary: `Results: 21 passed, 0 failed.` (covering Safety Shield `S1–S4` invariants, `16 B` beacons, binary token conservation, zero buffer overshoot, `Managed*` protection, first-class mobility EDDs, `M2_MOBILITY_AWARE` & ablations, and `DtnmaMetricsReport`).

### 2. Run the 1-Hour Benchmark Scenario (`scenario_dtnma.txt`)

```powershell
Push-Location ..\the-one
java -Xmx512m -cp "target;lib\*" core.DTNSim -b 1 scenarios\scenario_dtnma.txt
Pop-Location
```

*(To launch The ONE Swing GUI instead of headless batch mode, omit `-b 1`.)*

Inspect generated outputs under `..\the-one\reports\`:
- `Dtnma_Benchmark_MessageStatsReport.txt` — standard bundle delivery, latency, and overhead summary.
- `Dtnma_Benchmark_DtnmaMetricsReport.txt` — critical vs. bulk delivery, survival, battery energy, mobility contact stats, control overhead (`beacon_overhead_bytes`, `ack_overhead_bytes`), CPU (`decision_cpu_us_mean`), and profile shares (`profile_0_frac`..`profile_7_frac`).
- `Dtnma_Benchmark_DtnmaAuditReport.txt` — per-decision 21-column `ARI` provenance trace ending with `traceable_ari_ratio: 1.0000`.
- `Dtnma_Benchmark_DtnmaRecoveryTimelineReport.txt` — unsmoothed `300 s`-binned fleet survival, battery SoC, and rolling delivery trajectories.

### 3. Validate & Run Multi-Mobility Campaigns

```powershell
# Pre-run structural check + 10s smoke validation across all mob_*.txt scenarios
python tools\validate_mobility_scenarios.py --one-dir ..\the-one --smoke

# Run the confirmatory multi-mobility suite (use --dry-run first to inspect commands)
python tools\run_mobility_campaign.py --one-dir ..\the-one --dry-run
python tools\run_mobility_campaign.py --one-dir ..\the-one

# Run the strictly isolated exploratory sensitivity sweeps (range 20/55m, energy 0.75x/1.35x)
python tools\run_sensitivity_exploratory.py --one-dir ..\the-one

# Aggregate results with Holm-Bonferroni statistics and generate figures/tables
python tools\analyze_mobility_campaign.py --reports-dir ..\the-one\reports\mobility_suite --out-dir .\analysis_out
python tools\plot_mobility_figures.py --summary-json .\analysis_out\summary_stats.json --out-dir .\repro_figures
```

---

## Declarative Policy Configuration (`SRL` & Parameters)

In `M2_AUTONOMIC_SRL` mode (`scenarios/rules_vdtn.txt`), operators customize congestion and battery responses without recompiling Java:

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

Global controller defaults, Shield limits, and mobility horizons (`mobHorizonSec`, `mobTtdWarnRatio`, `mobTtdCritRatio`, `mobShortContactSec`, `mobLongIctSec`) are configured in [`scenarios/dtnma_params_default.txt`](scenarios/dtnma_params_default.txt) and [`DtnmaConfig.java`](src/routing/DtnmaConfig.java).

---

## Repository Structure

| Path | Contents |
|---|---|
| [`src/routing/`](src/routing/) | Core engine (`DtnmaAgent`, `DtnmaConfig`, `EddTelemetrySampler`, `DtnmaSafetyShield`, `DtnmaRuleParser`, `PredicateExpression`, `ControlAction`, `StateBasedRule`, `DtnmaContactBeacon`, `DtnmaPolicyProfile`), controllers (`DtnmaM2MobRouter` + `EnergyOnly`/`BufferOnly`/`ReplicaOnly` ablations, `DtnmaM2SrlRouter`, `DtnmaM1RemoteRouter`, `DtnmaM0eEnergy25Router`, `DtnmaM0bStaticRouter`), cross-substrate adapters (`DtnmaManagedRouter`, `Managed{SprayAndWait,ProphetV2,MaxProp,Epidemic}Router`), and `CongestionAwareSnWRouter` baseline |
| [`src/report/`](src/report/) | `DtnmaMetricsReport`, `DtnmaAuditReport`, `DtnmaRecoveryTimelineReport`, `DtnmaDecisionReport` |
| [`scenarios/`](scenarios/) | Smoke (`scenario_dtnma.txt`), defaults (`dtnma_params_default.txt`), rules (`rules_vdtn.txt`), confirmatory multi-mobility (`mob_*.txt`), and sensitivity (`sens_*.txt`) scenarios |
| [`tests/`](tests/) | Self-contained Java verification suite ([`DtnmaUnitTests.java`](tests/DtnmaUnitTests.java)) |
| [`tools/`](tools/) | Pre-run validation, campaign runners, Holm-Bonferroni analyzers, and figure/table builders ([`tools/README.md`](tools/README.md)) |
| [`figures/`](figures/) | Rendered empirical comparison, ablation, cross-substrate, scalability, and sensitivity figures |
| [`docs/study-guide/`](docs/study-guide/) | Complete newcomer-to-researcher study guide (Markdown chapters, diagrams, offline HTML, and PDFs) |

---

## Documentation & Study Guide

- **[Comprehensive Study Guide (Index)](docs/study-guide/index.md)** — Zero-background DTN primer, full mechanism encyclopedia (`04a`–`04d`), codebase tour & validation details, complete empirical findings (`06_evaluation_and_results.md`), tutorials, worked examples, RFC alignment, glossary, and reference tables.
- **Prebuilt Offline Readers:** [`ADTNM_Study_Guide.html`](docs/study-guide/build/ADTNM_Study_Guide.html) | [`ADTNM_Study_Guide.pdf`](docs/study-guide/build/ADTNM_Study_Guide.pdf) | [`ADTNM_Executive_Summary.pdf`](docs/study-guide/build/ADTNM_Executive_Summary.pdf) | [`ADTNM_One_Page_Cheatsheet.pdf`](docs/study-guide/build/ADTNM_One_Page_Cheatsheet.pdf).
- **[Claims Ledger](CLAIMS_LEDGER.md)** & **[Seed Governance](SEED_ROLES.md)** — Predeclared Holm-Bonferroni hypothesis families and seed isolation protocol.

---

## Project Status & License

This codebase is an active **work in progress**. Released under the **[MIT License](LICENSE)** — Copyright (c) 2026 **S. M. Nafis Shahriar, Pulok Akibuzzaman, Nusrat Rahman Aurna, Md Safin Rahman**.
