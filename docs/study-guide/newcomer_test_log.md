# Newcomer Verbatim Walkthrough Test Log (`newcomer_test_log.md`)

> **Date:** `2026-11-25` | **Environment:** Windows 11, PowerShell 5.1, JDK `24.0.2` | **Sandbox:** Isolated scratch dir `scratch/newcomer_walkthrough/` (zero edits to `the-one/target` or `ADTNM` non-doc source)

## 1. Execution Transcript Summary (`Total Wall-Clock Time: 5.37 s`)

1. **Tutorial 1 (Isolated Compile & `DtnmaUnitTests`):**
   - Command: `javac -cp "$ISO\target;$THE_ONE\lib\*" -d "$ISO\target" "$ADTNM\src\routing\*.java" "$ADTNM\src\report\*.java" "$ADTNM\tests\DtnmaUnitTests.java"` -> Exited `0`.
   - Command: `java -cp "$ISO\target;$THE_ONE\lib\*" DtnmaUnitTests`
   - Observed: `[PASS] Test1a` .. `[PASS] Test22` -> **`Results: 27 passed, 0 failed.` (`30` `check()` assertions)**
2. **Tutorial 2 (1-Hour Smoke Scenario):**
   - Command: `java -Xmx512m -cp "$ISO\target;$THE_ONE\lib\*" core.DTNSim -b 1 $smokeCfg` -> **`Simulation done in 1.20s`**.
   - Observed: `created: 262`, `delivered: 184`, `delivery_prob: 0.7023`, `# AUDIT_SUMMARY total_audited_decisions: 2400`, **`# AUDIT_SUMMARY traceable_ari_ratio: 1.0000`**.
3. **Tutorial 3 (Custom `RULE:/IF:/THEN:` Policy Execution):**
   - Created `my_custom_rules.txt` (`CUSTOM_BATT_GUARD`, `CUSTOM_SURGE`, `CUSTOM_NOMINAL` with `Group.rulesFile` & `DtnmaRouter.rulesFile` and `DtnmaRouter.intelligenceMode = STATIC_RULES`).
   - Command: `java -Xmx512m -cp "$ISO\target;$THE_ONE\lib\*" core.DTNSim -b 1 $customCfg` -> **`Simulation done in 1.26s`**.
   - Observed custom audit trace:
     `0.5  0.5  0  M2_AUTONOMIC_SRL  0.000 ... BALANCED_AGILE_FOCUS  PASS  11111111  ARI://DTNMA/SRL/CUSTOM_NOMINAL  [EDD_QUEUE_RATIO(0.00) >= 0.00 -> TRUE]`
4. **Friction Points Caught & Fixed During Preparation:**
   - Fixed `DtnmaUnitTests` class invocation (default package `DtnmaUnitTests`, not `routing.DtnmaUnitTests`) across Tutorial 1 and Cheatsheet.
   - Fixed `DtnmaRuleParser` exact header syntax (`RULE:/IF:/THEN: CTRL_SET_*`) & `Group.rulesFile` / `DtnmaRouter.rulesFile` keys in `04b` and Tutorial 3.
   - Fixed `parallel_batch_runner.py` CLI flag (`--scenario`, not `--suite`) in Tutorial 5, Cheatsheet, and `capture_screenshots.py`, plus logged `DISC-10`.
