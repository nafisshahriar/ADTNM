# Week 1 Guided Onboarding Plan (`onboarding_week1_plan.md`)

> **Goal:** Take any newcomer with zero DTN, RFC, or ONE simulator background from first clone to running tests, inspecting logs, writing custom SRL rules, and understanding the `v1.0` results inside `5` focused half-day sessions.

| Day | Learning Focus | Readings (`docs/study-guide/`) | Hands-On Exercise & Checkoff |
|---|---|---|---|
| **Day 1** | DTN fundamentals & architecture framing | `01_executive_summary.md`, `02_primer_for_newcomers.md`, `03_architecture_overview.md` | Compile codebase (`Tutorial 1`), run `DtnmaUnitTests` (`27/27 pass`), explain "managed substrate vs. smart router" in 2 sentences |
| **Day 2** | Running & reading logs (`ARI`, tiers, cohorts) | `04a_mechanisms_telemetry_and_profiles.md`, `07_howto_and_tutorials.md` (`Tutorial 2`), `Worked Example 6` | Run 1h `scenario_dtnma.txt` smoke test (`262 created / 184 delivered`); reconcile `53` ACK-pruned vs `0` un-ACKed loss (`DISC-01`) |
| **Day 3** | Rules, safety shield & replay invariance | `04b_mechanisms_srl_and_shield.md`, `Worked Examples 1–3`, `07_howto_and_tutorials.md` (`Tutorial 3`) | Author custom `RULE/IF/THEN` file with `PRIORITY: 10` catch-all; verify `100.00%` replay via `verify_audit_replay` |
| **Day 4** | Adapters, remote overlays & codebase tour | `04c_mechanisms_adapters_and_remote.md`, `05_codebase_tour.md`, `Worked Examples 4–5` | Inspect `ManagedEpidemicRouter.java` (`236` lines) vs `EnergyAwareEpidemicRouter.java` (`444` lines); run `parallel_batch_runner.py --dry-run` |
| **Day 5** | Empirical rigor & critical limitations | `06_evaluation_and_results.md`, `08_history_and_decisions.md`, `appendix_a`, `appendix_discrepancies.md` | Review confirmatory `H1–H4` vs exploratory `M0e-25` / `M1-F-Group`; articulate `M3` loss and `H2_orig` survival failure cleanly |
