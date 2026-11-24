# Chapter 5: Codebase Tour & Developer Architecture Guide

> **Audience:** Developers extending ADTNM adapters, rules, safety shields, or reports (`d4a159f`).  
> **Repository Totals:** `109` files documented in [`00_inventory.md`](../00_inventory.md) and `data/code_inventory.json`.

---

## 1. Repository Layout & Package Responsibilities

The ADTNM workspace sits beside its host simulator `the-one` (`n:\R&D\cog\the-one\`), sharing `target/` bytecode classes at compile/run time:

| Directory | Files | Non-Comment LOC | Primary Responsibility |
|---|---|---|---|
| **`src/routing/`** | `40` `.java` | `5,568` | Autonomic agent (`DtnmaAgent`), telemetry (`EddTelemetrySampler`), rule AST (`DtnmaRuleParser`, `PredicateExpression`, `StateBasedRule`), safety (`DtnmaSafetyShield`), profiles (`DtnmaPolicyProfile`), adapters (`Managed*Router`), experimental RL (`ShieldedCmdpRlAgent`), baselines (`DtnmaM0*..M3*`, `Priority*`, `EnergyAwareEpidemicRouter`) |
| **`src/report/`** | `4` `.java` | `1,639` | `DtnmaRlMetricsReport`, `DtnmaAuditReport`, `DtnmaRecoveryTimelineReport`, `DtnmaDecisionReport` |
| **`scenarios/`** | `~20` `.txt` | `—` | Base configs (`dtnma_params_default.txt`), smoke (`scenario_dtnma.txt`), rule files (`rules_vdtn.txt`, `rules_ntn.txt`, experimental `rules_uav.txt`), Comparison A/B and exploratory `.txt` settings |
| **`tests/`** | `1` `.java` | `—` | `DtnmaUnitTests.java`: `27` test methods containing `30` `check()` assertions (`Test1a..Test22`) |
| **`tools/`** | Python scripts | `—` | `parallel_batch_runner.py`, `evaluate_autonomic_framework.py` (`verify_audit_replay`), `analyze_final_11seed.py`, `analyze_exploratory_controls.py` |
| **Root ledgers** | `.md`, `.yml`, `install.*` | `—` | `CLAIMS_LEDGER.md`, `SEED_ROLES.md`, `CONTRIBUTING.md`, `install.{py,sh,bat}`, `.github/workflows/ci.yml` |

> [!NOTE] SUPERVISOR NOTE
> **LOC Accounting (`A3` evidence):** The `7` core engine classes (`DtnmaAgent`, `DtnmaSafetyShield`, `EddTelemetrySampler`, `DtnmaRuleParser`, `PredicateExpression`, `ControlAction`, `StateBasedRule`) total **`2,095` non-comment LOC**, each `Managed*Router` thin adapter averages **`253.5` LOC**, and declarative vDTN rules span just **`12` non-comment lines** — replacing `394`-line hand-coded protocol subclasses like `EnergyAwareEpidemicRouter`.

---

## 2. Boot-to-Decision Execution Lifecycle Inside The ONE

Every simulation instantiates one prototype router per host group via `Settings` reflection, cloning per-node state via `replicate()`:

```
DTNSim.main -> SimScenario parses dtnma_params_default.txt + scenario.txt
  -> Host group calls router.replicate() -> constructs Managed*Router(prototype)
     -> initializes DtnmaConfig (48 fields) & compiles dtnmaRuleFile via DtnmaRuleParser
  -> Loop every dt_tick = 0.1 s until endTime:
     1. World moves nodes -> NetworkInterface checks proximity -> changedConnection(con)
        -> DtnmaAgent.onChangedConnection(con)
           -> exchanges 16 B DtnmaContactBeacon (cooldown >= 5.0 s)
           -> exchanges <= 32 ACK IDs (4 B each) -> prunes delivered CRIT_ duplicates
           -> [M3 only] exchanges 648 B ShieldedCmdpRlAgent weights
     2. Router.update() calls DtnmaAgent.onBeforeUpdate(now):
        -> If now - lastEval >= dtnmaTelemetryIntervalSec (30.0 s):
           a. EddTelemetrySampler.sampleAndCompute() captures 11 EDDs atomically
           b. SRL matches prioritized StateBasedRules + hysteresis LATCH / overlay slot
              [or M3 runs ShieldedCmdpRlAgent Q-value + epsilon-greedy]
           c. DtnmaSafetyShield enforces S1..S4 + Liveness mask[0]=true -> ACTIVE profile
           d. Applies L_i, bpRatio, ingressMode, backpressureEnabled to router hooks
           e. Logs atomic 16-col ARI decision row to DtnmaAuditReport listeners
        -> super.update() executes substrate forwarding filtered by:
           - 5-tier tryAllMessages priority queue (CRIT_ -> CTRL_ -> BULK_)
           - allowNonDirectBulkForward(m, peer) backpressure guard
           - Dynamic Tier 3 copy quota clamping (ManagedSprayAndWaitRouter)
     3. Incoming transfer checks: DtnmaAgent.evaluateCheckReceiving -> ByteBudget + Ingress
     4. Buffer full eviction: DtnmaAgent.selectNextMessageToRemove -> 5-tier O(N) scan
  -> End of run: Reports flush MessageStatsReport, DtnmaRlMetricsReport, DtnmaAuditReport
```

---

## 3. Core Dependency DAG & Key Class Contracts

- **Zero Circular Dependencies:**
  `DtnmaConfig` (POD settings) & `ControlAction` & `DtnmaPolicyProfile` -> `PredicateExpression` -> `StateBasedRule` -> `DtnmaRuleParser` -> `DtnmaSafetyShield` & `EddTelemetrySampler` & `ShieldedCmdpRlAgent` -> `DtnmaAgent` <-> `DtnmaManagedRouter` (implemented by `Managed*Router`).
- **Two Separate Composition vs. Standalone Trees:**
  - `DtnmaRouter` (`src/routing/DtnmaRouter.java`, `700` lines): direct Spray-and-Wait-based router exposing `DtnmaM0b..M3` mode presets (`DtnmaM0bStaticRouter`, `DtnmaM0cV1GuardRouter`, `DtnmaM1RemoteRouter`, `DtnmaM2SrlRouter`, `DtnmaM3RlRouter`, etc.).
  - `Managed*Router` (`ManagedSprayAndWaitRouter`, `ManagedProphetV2Router`, `ManagedMaxPropRouter`, `ManagedEpidemicRouter`): wraps stock ONE routers by implementing `DtnmaManagedRouter` (`src/routing/DtnmaManagedRouter.java:1-46`).

---

## 4. Safe Extension Recipes (`How Do I Extend ADTNM?`)

### Recipe A: Add a New `SRL` Policy Without Recompiling Java
1. Author a rule line using valid `EDD_*` names, operators (`>=`, `<=`, `>`, `<`, `==`), `AND`/`OR`/`NOT`, target `P0..P7`, `LATCH <sec>`, `PRIO <int>`, and unique `ID <ARI_TAG>`.
2. Always include a low-priority catch-all recovery rule (`PRIO 10`, e.g., `IF (EDD_QUEUE_RATIO >= 0.0) THEN P4`) so nodes release throttling smoothly when load drops (`DISC-06`).
3. Point `Group.dtnmaRuleFile = scenarios/my_rules.txt` and validate `100%` replay via `verify_audit_replay`.

### Recipe B: Wrap Another Stock ONE Router
1. Subclass `StockOneRouter implements DtnmaManagedRouter`.
2. Instantiate `private final DtnmaAgent agent` in constructor & copy constructor (compute `initialEnergy` safely, defaulting to `1.0` if missing).
3. Implement all getters/setters from `DtnmaManagedRouter.java:1-46`, overriding `update()`, `createNewMessage()`, `checkReceiving()`, `makeRoomForMessage()`, `changedConnection()`, and prioritizing `CRIT_`/`CTRL_` tuples ahead of native `tryAllMessages` ordering.
4. Run `DtnmaUnitTests` and test Tier 1 (`TIER1_PROTECTION_ONLY`) equivalence against a priority-only baseline.

### Recipe C: Wire an Existing Unused Telemetry Signal (`EDD_ENERGY_DRAIN`)
`EddTelemetrySampler` already updates `EDD_ENERGY_DRAIN` (`EddTelemetrySampler.java:33,204`, logged as `DISC-05`), making it immediately readable in `PredicateExpression` once tuned cleanly against false triggers during legitimate multi-contact transfers.

> [!WARNING] GOTCHA / PITFALL
> Never read un-sanitized `CONTRIBUTING.md` line pointers (`DISC-03`) — telemetry now lives in `EddTelemetrySampler`, actuators in `ControlAction` / `DtnmaPolicyProfile`, and `verify_and_plot.py` has been removed (`DISC-02`).

## Dependency DAG (`Figure 13`)

![ADTNM Package and Class Dependency DAG](../figures/svg/fig13_codebase_dag.svg)
