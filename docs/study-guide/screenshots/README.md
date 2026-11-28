# ADTNM Real Screenshot Captures (`screenshots/`)

| File (`raw/` & `annotated/`) | Capture Method | Underlying Evidence |
|---|---|---|
| `01_the_one_gui.png` | `SWING_MAINWINDOW` via temporary `CaptureOneGuiScreenshot.java` compiled to temp dir | Helsinki urban DTN map (`DtnmaM2SrlRouter`) |
| `02_compile_tests_smoke.png` | Subprocess stdout/stderr capture rendered on dark terminal canvas | `DtnmaUnitTests` + 1h `scenario_dtnma.txt` smoke outputs |
| `03_audit_rl_excerpts.png` | Verbatim raw log excerpts | Autonomic `SRL` match, Shield `OVERRIDE` `11111100`, `Epidemic` Tier 1 vs Tier 2 |
| `04_batch_dryrun_and_tags.png` | Live execution of `parallel_batch_runner.py --dry-run` | Safe batch dry-run & scenario sweep verification |
