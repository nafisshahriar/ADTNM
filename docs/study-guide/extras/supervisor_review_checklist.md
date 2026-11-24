# Supervisor & Reviewer Audit Checklist (`supervisor_review_checklist.md`)

Use this targeted verification checklist when reviewing the thesis, journal manuscript (`v1.0-tnsm-freeze`), or `v1.1` roadmap.

- [ ] **1. Architectural Framing Verified:** ADTNM is presented consistently as a cross-router management layer (`Managed*Router` substrates + `DtnmaAgent`), never as "a smarter router" or RL breakthrough.
- [ ] **2. RFC Positioning Bounded:** Both **RFC 9675** (Nov 2024) and **RFC 7575** (June 2015) are cited accurately as conceptual inspiration; no wire-level CBOR/ADM conformance or NASA/APL/ION claims appear anywhere (`Appendix A`).
- [ ] **3. Confirmatory vs. Exploratory Pools Strictly Disjoint:** Confirmatory claims use only frozen `7103..7207` (`v1.0-tnsm-freeze`, `715` runs, Holm-adjusted `m=4`); exploratory controls use fresh `7211..7307` (`v1.0-exp-controls`, `176` runs, `p_unadj`).
- [ ] **4. Negative Results Disclosed Upfront:** Both the failed survival non-inferiority test (`H2_orig: -5.48 +/- 1.96 pp`, `0/11`) and `M3` RL's across-the-board deficit behind `M2` (`bulk 63% vs 66%`, `alive 76.0 vs 83.8`) are stated explicitly in Executive Summary, Chapter 6, and `README.md`.
- [ ] **5. Causal Attribution Transparent:** `M0e-25` (`83.8`) vs `M2` (`83.8`) vs `M2-LocalOnly` (`80.0`) stress survival and `M1-F-Group` (`201.6 J`, `99.5` alive vs `483 s` median wave delay) are presented fairly side by side (`SDR-1`).
- [ ] **6. Code vs. Docs Integrity:** `git diff --name-only d4a159f` touches solely `docs/**` and root `README.md`; `v1.0-tnsm-freeze` points to `ca5d9ac`; non-doc discrepancies stay isolated in `appendix_discrepancies.md`.
- [ ] **7. Supervisor Decision Records (`SDR-1..SDR-5`) Reviewed:** Decisions on local battery rules (`EDD_ENERGY_DRAIN`), `M3` retirement/redesign, Manager uplink loop, and `DISC-01/02` PR prioritized for `v1.1`.
