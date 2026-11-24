# Chapter 7: Hands-On Tutorials (Verified End-to-End)

> **Time to Complete All 5 Tutorials:** `~10` minutes total.  
> **Tested Environment:** Windows 11 + PowerShell 5.1 + JDK 24 + Python 3.12 (also portable to Linux/macOS bash).  
> Remember: On Windows PowerShell, prevent `__pycache__` pollution in `ADTNM` using `$env:PYTHONDONTWRITEBYTECODE = '1'` and verify `n:\R&D\cog\the-one\target` contains base ONE compiled classes first.

---

## Tutorial 1: Compile & Run `DtnmaUnitTests` (`< 2 min`)

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
$THE_ONE = "n:\R&D\cog\the-one"
$ADTNM   = "n:\R&D\cog\ADTNM"
New-Item -ItemType Directory -Force "$THE_ONE\target" | Out-Null

# 1. Compile ADTNM sources + tests onto existing the-one classpath
javac -cp "$THE_ONE\target;$THE_ONE\lib\*" -d "$THE_ONE\target" `
  "$ADTNM\src\routing\*.java" "$ADTNM\src\report\*.java" "$ADTNM\tests\DtnmaUnitTests.java"

# 2. Run unit suite (27 tests, 30 check() assertions)
java -cp "$THE_ONE\target;$THE_ONE\lib\*" routing.DtnmaUnitTests
```

**Expected Output Tail:**
`ALL TESTS PASSED! (27/27 tests passed, 0 failed)` (spanning `Test1a..Test22` with `30` internal `check()` assertions).

---

## Tutorial 2: Run 1-Hour Smoke Scenario & Inspect All 3 Reports (`~15 s`)

> [!IMPORTANT] FORMAL / CODE
> Running `DTNSim` directly from `the-one` with `-b 1 scenarios/scenario_dtnma.txt` writes into the report dir configured in `scenario_dtnma.txt`. To keep your git working tree completely clean during experiments, redirect output to a temporary scratch settings overlay (shown below)!

```powershell
$SCRATCH = "$env:TEMP\adtnm_smoke"
New-Item -ItemType Directory -Force "$SCRATCH\reports" | Out-Null
$cfg = Get-Content "$ADTNM\scenarios\scenario_dtnma.txt"
($cfg + "`nReport.reportDir = $($SCRATCH.Replace('\','/'))/reports/") | Set-Content "$SCRATCH\smoke.txt"

Push-Location $THE_ONE
java -Xmx512m -cp "$THE_ONE\target;$THE_ONE\lib\*" core.DTNSim -b 1 "$SCRATCH\smoke.txt"
Pop-Location

Get-Content "$SCRATCH\reports\Dtnma_Benchmark_MessageStatsReport.txt" -TotalCount 12
Get-Content "$SCRATCH\reports\Dtnma_Benchmark_DtnmaDecisionReport.txt" | Select-String "^#"
Get-Content "$SCRATCH\reports\Dtnma_Benchmark_DtnmaAuditReport.txt" | Select-String "AUDIT_SUMMARY"
```

**What You Should Observe (`d4a159f`):**
- `MessageStatsReport`: `created: 262`, `delivered: 184`, `delivery_prob: 0.7023`, `overhead_ratio: 6.7663`
- `DtnmaDecisionReport`: `Critical Fault Messages Dropped: 53` — **Wait (`DISC-01` check):** inspect `unique_critical_lost` (`0`) vs `critical_buffer_dropped_acked` (`53`) in `DtnmaRlMetricsReport` to confirm zero live un-ACKed loss!
- `DtnmaAuditReport`: `total_audited_decisions: 2400`, `traceable_ari_ratio: 1.0000`, `log_bytes_per_node_hour: 33468.50`.

---

## Tutorial 3: Author Custom `SRL` Rules & Verify `100%` Replay (`< 3 min`)

Create `$SCRATCH\my_custom_rules.txt` enforcing battery save at `SoC < 0.30` and surge throttle above `Q >= 0.80` with safe catch-all recovery (`PRIORITY: 10`):

```text
RULE: ARI://DTNMA/SRL/CUSTOM_BATT_GUARD | PRIORITY: 100 | DESC: Custom battery survival guard
IF: (EDD_ENERGY_SOC < 0.30) || (EDD_CRIT_QUEUE_RATIO >= 0.50)
THEN: CTRL_SET_SPRAY_QUOTA(1); CTRL_SET_INGRESS_MODE(2); CTRL_SET_TELEMETRY_INTERVAL(600); CTRL_SET_BACKPRESSURE(true)

RULE: ARI://DTNMA/SRL/CUSTOM_SURGE | PRIORITY: 80 | DESC: Custom queue surge throttle
IF: (EDD_QUEUE_RATIO >= 0.80) && (EDD_QUEUE_DRIFT >= 0.005)
THEN: CTRL_SET_SPRAY_QUOTA(2); CTRL_SET_INGRESS_MODE(1); CTRL_SET_TELEMETRY_INTERVAL(300); CTRL_SET_BACKPRESSURE(true)

RULE: ARI://DTNMA/SRL/CUSTOM_NOMINAL | PRIORITY: 10 | DESC: Nominal catch-all recovery
IF: (EDD_QUEUE_RATIO >= 0.0)
THEN: CTRL_SET_SPRAY_QUOTA(6); CTRL_SET_INGRESS_MODE(0); CTRL_SET_TELEMETRY_INTERVAL(60); CTRL_SET_BACKPRESSURE(true)
```

Create an overlay scenario pointing `Group.rulesFile` and `DtnmaRouter.rulesFile` (plus `DtnmaRouter.intelligenceMode = STATIC_RULES`, using `/`-normalized paths) at `$SCRATCH\my_custom_rules.txt`, run `DTNSim -b 1`, and inspect `DtnmaAuditReport.txt`:
- Every decision row cites `ARI://DTNMA/SRL/CUSTOM_*` (or `ARI://DTNMA/SHIELD/*`) with `traceable_ari_ratio: 1.0000`.
- Re-evaluate rows cleanly via Python `verify_audit_replay` (`tools/evaluate_autonomic_framework.py`) to confirm `100.00%` deterministic replay.

---

## Tutorial 4: Manage Any Stock ONE Router via `DtnmaManagedRouter` (`Concept Template`)

1. Subclass your target ONE router (e.g., `FirstContactRouter`), adding `implements DtnmaManagedRouter`.
2. Add `private final DtnmaAgent agent;` initialized in both `Settings` constructor and copy constructor (`super(r); this.agent = new DtnmaAgent(this, r.agent);`).
3. Delegate `update()` (`agent.onBeforeUpdate(SimClock.getTime()); super.update();`), `changedConnection()`, `createNewMessage()`, `checkReceiving()`, and `makeRoomForMessage()`.
4. Keep native forwarding priority ordering cleanly partitioned (`CRIT_/CTRL_` tuples first, `BULK_` filtered through `agent.allowNonDirectBulkForward`).
5. Validate with `DtnmaUnitTests` and compare Tier 1 against an unmanaged priority baseline!

---

## Tutorial 5: Batch Dry-Run & Re-Running `v1.0` Statistical Analyzers (`< 1 min`)

> [!WARNING] GOTCHA / PITFALL
> Never launch all `715` runs blindly on an active workstation without checking `--dry-run` first! Use `--dry-run` to inspect commands, JVM heap flags, and report paths cleanly:

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
python "$ADTNM\tools\parallel_batch_runner.py" --suite compB_exp_controls --dry-run
# Re-run read-only statistics over existing reports (no simulation executed):
python "$ADTNM\tools\analyze_final_11seed.py" --skip-replay --out-json "$env:TEMP\check_f11.json" --out-md "$env:TEMP\check_f11.md"
python "$ADTNM\tools\analyze_exploratory_controls.py" --skip-replay --out-dir "$env:TEMP"
```
