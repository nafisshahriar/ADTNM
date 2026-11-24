# Open Questions, Decisions & Toolchain Resolutions (`OPEN_QUESTIONS.md`)

> **Documented Commit:** `d4a159f` | **Updated:** `2026-11-25`

This file records technical/editorial questions encountered while auditing the codebase against the documentation brief, along with the resolved approach (strictly without modifying source code) and supervisor open questions requiring decisions for post-`v1.0` work.

---

## 1. Toolchain & Build Resolutions (Environment Facts)

| Item | Question / Constraint | Observation & Resolution |
|---|---|---|
| **HTML -> PDF Renderer** | Neither `pandoc`, `wkhtmltopdf`, `weasyprint`, nor headless Microsoft Edge `--print-to-pdf` succeeded on this Windows host (`EDGE_PDF_FAILED`). | Resolved cleanly via Python `reportlab` + `PIL` (`reportlab` vector typography, tables, bookmarks/outlines, embedded 300-DPI PNG figures & annotated screenshots) verified page-by-page using PyMuPDF (`fitz`). `ADTNM_Study_Guide.html` provides full interactive client-side full-text search, collapsible TOC, and dark/light reader styling from pure Python `markdown` + `pygments`. |
| **Diagram Renderer** | `mermaid-cli` (`mmdc`) is not installed globally and network downloads cannot be assumed offline. | Every structural/sequence diagram (`Figures 01–14`) has its canonical Mermaid `.mmd` source saved under `docs/study-guide/figures/mmd/` AND is rendered directly to clean publication-grade `.svg` and 300-DPI `.png` diagrams (Okabe-Ito colorblind-safe palette) via `docs/study-guide/tools/generate_figures.py`. |
| **The ONE GUI Screenshot** | Does The ONE Swing GUI (`MainWindow` / `PlayField`) launch cleanly on Windows, or fall back to offscreen rendering? | Pending test in Phase 3 via temporary scratch harness `CaptureOneGuiScreenshot.java` (kept outside `src/` and deleted/archived inside `docs/study-guide/tools/` after capture so `src/` stays untouched). Will record exact capture method (desktop Swing window vs offscreen AWT `BufferedImage` paint) in `docs/study-guide/screenshots/README.md`. |

---

## 2. Ground-Truth Resolutions Across Discrepancies (No Code Changed)

1. **`DtnmaUnitTests.java` Assertion Count:** Legacy `README.md` stated `15 assertions`. Direct AST/grep inspection in `extract_code_inventory.py` measured **`30` `check()` assertions across `27` test methods** (`Test1a`–`Test22`, with `test7AllCriticalFullBufferZeroOvershoot` printing both `Test7` and `Test8` PASS labels). All documentation cites `27 test methods / 30 assertions` faithfully.
2. **Smoke Run Drop Count (`53` vs `0`):** In the 1-hour `scenario_dtnma.txt` smoke run, `DtnmaDecisionReport` reports `Critical Fault Messages Dropped: 53` while `DtnmaRlMetricsReport` reports `unique_critical_lost: 0`. Source audit (`DtnmaDecisionReport.java:103-107` vs `DtnmaAgent.java:1004-1020`) explains both: all `53` evictions were **redundant copies whose delivery ACKs had already arrived** (`deliveredAckSet`), evicted deliberately before bulk payloads (`effective_critical_loss = 0`). Both metrics are documented side-by-side in Ch. 04b, Ch. 04d, Worked Example 6, and `appendix_discrepancies.md`.
3. **E1 Strict Equivalence `10/11` vs `11/11`:** Under `compA_equivalence`, `ManagedSprayAndWaitRouter` matches `PrioritySprayAndWaitRouter` byte-for-bit on `MessageStatsReport` across **`11/11` seeds** (`0.0000 pp` difference, `0` critical loss). On `DtnmaRlMetricsReport`, `10/11` seeds match identically across non-control keys, while seed `7103` shows a `0.0001` SoC rounding difference (`0.4055` vs `0.4054`) due to `DtnmaAgent` 16 B beacon radio transmissions that `PrioritySprayAndWaitRouter` does not emit. Documented explicitly in Ch. 06 & Appendix D.
4. **`downlink_t90_sec = 30849.0` in Exploratory `M2`:** In `compB_exp_groupflood_stress`, `M2` reports `downlink_90pct_reached = 0.0` (since group-flood packets are not generated in `M2`) with `downlink_t90_sec = 30849.0` (end-of-horizon sentinel). Reported as `n/a` when adoption target is `0%`, rather than presenting `30849.0 s` as an adoption latency.

---

## 3. Open Architectural Questions for Supervisor Decisions (`01_executive_summary.md`)

1. **Decision 1 — Should remote uplink telemetry drive automated adaptation when `M1-U` / `M1-F-Group` operate?** Currently (`v1.0`), the Manager scenario injects fixed-time scheduled control waves (`oracle` timing in stress tests) while `DtnmaManagerReport` logs passive statistics. Implementing a reactive closed-loop remote Manager over delayed uplinks is open for `v1.1`.
2. **Decision 2 — How should `M2`'s stress survival deficit (`-5.48 pp` vs `M1-U` in `H2_orig`) be resolved without `v1.0` outcome bias?** Exploratory runs showed that `M0e-25` (`SoC < 0.25` energy guard) matches `M2` (`83.8/126`) while `M2-LocalOnly` without remote updates trails at `80.0/126` (`M1-F-Group` reaches `99.5/126` via aggressive early throttling). Should `v1.1` tighten local `SoC` thresholds, keep the benign throughput trade-off, or add energy-derivative triggers (`EDD_ENERGY_DRAIN`)?
3. **Decision 3 — Should `M3` (`ShieldedCmdpRlAgent`) be redesigned or retired cleanly?** In `v1.0`, the `20`-feature linear Q-agent with stationary `alpha=0.05` and local reward `-20` hold penalties underperformed `M2` across every stress and benign endpoint (`81.1%` vs `84.4%` critical delivery in stress). Next step options: tabular/low-dimensional state abstraction over hazard features, offline contextual policy evaluation, or scoping `v1.1` papers strictly to deterministic `SRL + Shield` while archiving `M3` cleanly.
4. **Decision 4 — Should `rules_uav.txt` and `DtnmaDecisionReport` ACK-eviction accounting be patched on `main` post-docs?** Since this documentation task is strictly docs-only, both items are deferred in `appendix_discrepancies.md`.
5. **Decision 5 — Should `DtnmaConfig` retire unused primal-dual keys (`rlLambdaInit`, `rlEtaLambda`, `rlTargetCost`, `rlLambdaMax`)?** Kept as zeroed compatibility shims after primal-dual adaptation was removed prior to `v1.0-tnsm-freeze`.
