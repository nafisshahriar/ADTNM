# ADTNM Real Screenshot Captures (`screenshots/`)

| File (`raw/` & `annotated/`) | Capture Method | Underlying Evidence |
|---|---|---|
| `01_the_one_gui.png` | `SWING_MAINWINDOW` via temporary `CaptureOneGuiScreenshot.java` compiled to temp dir | Helsinki `compB` stress map @ `t=360s`, seed `7103`, `DtnmaM2SrlRouter` |
| `02_compile_tests_smoke.png` | Subprocess stdout/stderr capture rendered on dark terminal canvas | `DtnmaUnitTests` (`27/27 pass`) + 1h `scenario_dtnma.txt` smoke outputs |
| `03_audit_rl_excerpts.png` | Verbatim line slices from frozen `final_11seed/` seed `7103` raw logs | `M2` SRL match, `M3` shield override `11111100`, `Epidemic` Tier 1 vs Tier 2 |
| `04_batch_dryrun_and_tags.png` | Live execution of `parallel_batch_runner.py --dry-run` + `git show-ref --tags -d` | Safe batch dry-run & `v1.0-tnsm-freeze` tag lock |
