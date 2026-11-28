# Appendix A: Alignment with IETF RFC 9675 & IRTF RFC 7575

> **Scope Statement:** ADTNM is **RFC 9675-inspired** and **RFC 7575-inspired** for architectural separation inside The ONE simulator. It does **not** implement wire-format CBOR encodings, formally authored YANG/ADM schemas, or ION runtime interoperability, and makes zero claim of certification or affiliation with external organizations.

---

## 1. Concept Mapping vs. IETF RFC 9675 (*DTN Management Architecture*, Nov 2024)

| RFC 9675 Section / Concept | RFC Requirement / Architectural Goal | ADTNM Implementation | Explicit Difference / Boundary |
|---|---|---|---|
| **Sec 3.1–3.2: Agent & Manager Roles** | Managers issue declarative guidance asynchronously; Agents monitor locally & act without polling | `DtnmaAgent` runs closed-loop autonomously per node; remote scenario hosts emit delayed `CTRL_UPDATE_*` overlays (`M1-Remote`, `M2`) | Remote Manager schedules in scenarios are pre-scripted waves, not an interactive Manager daemon |
| **Sec 4.1: Application Resource Identifiers (`ARIs`)** | Unambiguous compact naming for managed objects, rules, telemetry, and controls | String URIs (`ARI://DTNMA/SRL/<ID>`, `ARI://DTNMA/MOB/<POLICY>`, `ARI://DTNMA/SHIELD/<GUARD>`) logged on `100%` of decisions | Text URIs serialized in Java/TSV audit files rather than CBOR-encoded binary DTNMA ARIs |
| **Sec 4.2: Externally Defined Data (`EDDs`)** | Standardized node-sampled operational metrics decoupled from internal protocol structures | `15` named `EDD_*` metrics in `EddTelemetrySampler.java` (queue, energy, density, duration, ICT, encounter rate, TTD) sampled uniformly across all routers | Java `double[15]` metrics sampled locally; dynamic YANG/ADM schemas are not loaded |
| **Sec 4.3: Controls (Actuators)** | Parameterized node actions altering behavior without code reloads | `4` `ControlAction` primitives + `8` discrete profiles (`P0..P7`) driving `L, bp, ing` hooks via `DtnmaManagedRouter` | Discrete finite gear box (`P0..P7`) chosen for `O(8)` shield decidability |
| **Sec 4.4: State-Based Autonomy (`SRL`)** | Onboard event-condition-action rules evaluated continuously while disconnected | `DtnmaRuleParser` loads `RULE / IF / THEN` files (`rules_vdtn.txt`) evaluated every `1.0 s` (`M2-SRL`) | Flat conjunction (`&&`) or disjunction (`||`) clauses; rules do not call external scripts |

---

## 2. Concept Mapping vs. IRTF RFC 7575 (*Autonomic Networking*, June 2015)

| RFC 7575 Principle | Autonomic Goal | ADTNM Mechanism (`04a`–`04d`) | Boundary / Empirical Status |
|---|---|---|---|
| **Sec 3.1: Self-Configuring** | Automatically adopt configuration changes upon context shift or intent update | `upsertDownlinkOverlayRule()` integrates remote `CTRL_UPDATE_*` waves (`PRIO 110`) without restart | Remote waves limited to profile/guard clamps in current scenarios |
| **Sec 3.1: Self-Healing** | Recover normal capacity automatically once transient disturbance passes | Catch-all recovery rules (`PRIORITY: 10`, e.g., `VDTN_URBAN_NOMINAL`) + closed-form `M2_MOBILITY_AWARE` release under calm conditions | Relies on accurate contact/energy telemetry sampling |
| **Sec 3.1: Self-Optimizing** | Improve throughput/latency continuously within safe resource budgets | `M2_MOBILITY_AWARE` coordinates energy-horizon throttling, ICT-scaled buffer thresholds, and contact-duration replica caps (`P0..P7`) + local backpressure (`bp`) | Uses closed-form mobility control laws inside hard safety bounds |
| **Sec 3.1: Self-Protecting** | Defend critical availability & energy survival against storms or battery depletion | `DtnmaSafetyShield` (`S1–S4`) + critical buffer reserve + Tier 1 ACK pruning (`0` unique critical loss under contention) | Relies on honest bundle prefixes (`CRIT_`, `CTRL_`); no cryptographic auth |
| **Sec 4: Explicit Non-Goals** | Autonomy operates strictly inside designer bounds; deterministic auditability preserved | Hard `S1–S4` bitmask blocks unsafe actions + atomic `100.00%` `DtnmaAuditReport` trace | Hard shield overrides (`OVERRIDE` column) remain traceable to specific EDD readings |

---

## 3. Related IETF/IRTF Protocols Referenced Accurately

- **RFC 9171** (*Bundle Protocol Version 7*, S. Burleigh, K. Fall, E. Birrane, III, IETF Proposed Standard, January 2022) — Store-carry-forward bundle model emulated in The ONE.
- **RFC 6693** (*Probabilistic Routing Protocol for Intermittently Connected Networks [PRoPHET]*, A. Lindgren, A. Doria, E. Davies, S. Grasic, IRTF Experimental, August 2012) — Managed substrate implemented by `ProphetV2Router` / `ManagedProphetV2Router`.
