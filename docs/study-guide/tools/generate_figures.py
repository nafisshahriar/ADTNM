#!/usr/bin/env python3
"""Generate all 13 Study Guide Architectural Diagrams (.mmd + .svg + 300-DPI .png).

Diagrams (01-11, 13-14):
  - Canonical Mermaid sources saved to docs/study-guide/figures/mmd/figXX_*.mmd
  - Publication-grade vector SVG + 300-DPI PNG rendered via matplotlib patches (Okabe-Ito palette)
Empirical Plots (15-22):
  - Maintained as 300-DPI PNGs in docs/study-guide/figures/png/ copied from the
    verified mobility-campaign publication package (1,216 confirmatory + 176 exploratory runs).
"""

from __future__ import annotations

from pathlib import Path
from typing import Dict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

SCRIPT_DIR = Path(__file__).resolve().parent
GUIDE_DIR = SCRIPT_DIR.parent
FIG_DIR = GUIDE_DIR / "figures"
MMD_DIR = FIG_DIR / "mmd"
SVG_DIR = FIG_DIR / "svg"
PNG_DIR = FIG_DIR / "png"

# Okabe-Ito colorblind-safe palette
OI = {
    "blue": "#0072B2",
    "orange": "#E69F00",
    "green": "#009E73",
    "vermillion": "#D55E00",
    "purple": "#CC79A7",
    "sky": "#56B4E9",
    "yellow": "#F0E442",
    "black": "#1E293B",
    "slate": "#475569",
    "light": "#F8FAFC",
    "border": "#CBD5E1",
}

plt.rcParams.update({
    "font.sans-serif": ["DejaVu Sans", "Arial"],
    "axes.edgecolor": "#475569",
    "axes.linewidth": 0.9,
    "figure.facecolor": "white",
    "axes.facecolor": "white",
})


def save_fig(fig: plt.Figure, name: str) -> None:
    SVG_DIR.mkdir(parents=True, exist_ok=True)
    PNG_DIR.mkdir(parents=True, exist_ok=True)
    fig.savefig(SVG_DIR / f"{name}.svg", bbox_inches="tight", dpi=150)
    fig.savefig(PNG_DIR / f"{name}.png", bbox_inches="tight", dpi=300)
    plt.close(fig)


def draw_box(ax, x, y, w, h, text, fc="#EFF6FF", ec="#0072B2", tc="#0F172A", fontsize=9.2, bold=False, lw=1.6):
    box = FancyBboxPatch(
        (x, y), w, h,
        boxstyle="round,pad=0.015,rounding_size=0.025",
        facecolor=fc, edgecolor=ec, linewidth=lw, zorder=3,
    )
    ax.add_patch(box)
    ax.text(
        x + w / 2, y + h / 2, text,
        ha="center", va="center", fontsize=fontsize,
        color=tc, fontweight="bold" if bold else "normal",
        zorder=4, linespacing=1.28,
    )


def draw_arrow(ax, x1, y1, x2, y2, label="", color="#334155", rad=0.0, lw=1.5, ls="-", lpos=(0, 0)):
    ax.annotate(
        "", xy=(x2, y2), xytext=(x1, y1),
        arrowprops=dict(arrowstyle="-|>", color=color, lw=lw, ls=ls, connectionstyle=f"arc3,rad={rad}", shrinkA=2, shrinkB=2),
        zorder=2,
    )
    if label:
        mx, my = (x1 + x2) / 2 + lpos[0], (y1 + y2) / 2 + lpos[1]
        ax.text(mx, my, label, ha="center", va="center", fontsize=8.0, color=color,
                bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="none", alpha=0.88), zorder=5)


def make_diagram_canvas(title: str, subtitle: str = "", figsize=(11.5, 6.2)):
    fig, ax = plt.subplots(figsize=figsize)
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.axis("off")
    ax.text(0.5, 0.965, title, ha="center", va="top", fontsize=13.5, fontweight="bold", color="#0F172A")
    if subtitle:
        ax.text(0.5, 0.922, subtitle, ha="center", va="top", fontsize=9.2, color="#475569")
    return fig, ax


MMD_SPECS: Dict[str, str] = {
    "fig01_architecture_overview": """flowchart TB
  subgraph L4["4. Observability & Audit Reporting"]
    R1["DtnmaAuditReport (21-col ARI TSV)"]
    R2["DtnmaMetricsReport (Cohorts / CDFs / Energy)"]
    R3["DtnmaRecoveryTimelineReport"]
  end
  subgraph L3["3. Local Autonomic Coordinator (DtnmaAgent)"]
    EDD["EddTelemetrySampler (15 EDDs)"]
    COORD["M2_MOBILITY_AWARE Coordinator / SRL Rules"]
    SHIELD["DtnmaSafetyShield (S1..S4 + Liveness P0)"]
    PROF["Active Control Profile (P0..P7)"]
    EDD --> COORD --> SHIELD --> PROF
  end
  subgraph L2["2. Substrate Adapter Layer"]
    MR["DtnmaManagedRouter Hooks + 4-Tier Queue + ByteBudget"]
  end
  subgraph L1["1. Forwarding & Physical Substrates (The ONE)"]
    SUB["SprayAndWait | PRoPHETv2 | MaxProp | Epidemic | DtnmaRouter"]
  end
  L1 <--> L2
  L2 --> EDD
  PROF --> MR
  SHIELD --> R1
""",
    "fig02_three_timescales": """sequenceDiagram
  participant Fast as Fast Protection (0.1s-0.5s Tick)
  participant Local as Local Coordinator (1.0s Eval)
  participant Remote as Remote Manager (min-hr)
  Fast->>Fast: checkReceiving / ACK-first critical-safe eviction / ingressMode
  Local->>Fast: Sample 15 EDDs -> M2-Mob or SRL -> Safety Shield S1..S4
  Remote-->>Local: Delayed CTRL_UPDATE_ Overlay (PRIO 110)
""",
    "fig03_intervention_tiers": """flowchart LR
  T0["Unmanaged Stock Router"] --> T1["Tier 1: Protection Only (Crit Reserve + ByteGuard + 4B ACKs)"]
  T1 --> T2["Tier 2: + Ingress Throttling & Energy Shield (S1..S4 + M2-Mob/SRL)"]
  T2 --> T3["Tier 3: + Dynamic Replica Caps (L=1..12) in Adapter"]
""",
    "fig04_edd_to_profile_pipeline": """flowchart LR
  EDDS["15 EDDs"] --> EVAL["M2_MOBILITY_AWARE or SRL"]
  EVAL --> MASK["DtnmaSafetyShield (S1..S4 mask)"]
  MASK --> PROF["Selected Profile (P0..P7)"]
  PROF --> ACT["Actuators: L_i, bpRatio, ingressMode, backpressure"]
""",
    "fig05_priority_queue_and_eviction": """flowchart TB
  F0["Forward 0: CRIT_ (L>1)"] --> F1["Forward 1: CRIT_ (L<=1)"]
  F1 --> F2["Forward 2: BULK_ (L>1)"]
  F2 --> F3["Forward 3: BULK_ (L<=1)"]
  E0["Evict 1st: deliveredAckSet CRIT_ Replicas (LRU 512)"] --> E1["Evict 2nd: Oldest BULK_ (Safe Lock)"]
  E1 --> E2["Evict Last: Live Un-ACKed CRIT_"]
""",
    "fig06_srl_priority_flow": """flowchart TD
  P100["PRIO 100: VDTN_BUFFER_CRITICAL (SoC<0.20 || CritQ>=0.50)"] -->|False| P80["PRIO 80: VDTN_SURGE_THROTTLE (Q>=0.88 && dQ>=0.008)"]
  P80 -->|False| P60["PRIO 60: VDTN_TRAFFIC_JAM (Q>=0.75 && Dens>=5 && Speed<2)"]
  P60 -->|False| P10["PRIO 10: VDTN_URBAN_NOMINAL (Q>=0.0 Catch-All Recovery)"]
""",
    "fig07_safety_shield_bitmask": """flowchart LR
  PROP["Proposed P0..P7"] --> S1["S1: CritQ / Surge Guard"]
  S1 --> S2["S2: Battery SoC < Min -> {P0,P1}"]
  S2 --> S3["S3: Q>=0.78 & Dens>=5 -> Block {P6,P7}"]
  S3 --> S4["S4: Remote Clamp + Liveness mask[0]=1"]
  S4 --> OUT["PASS or OVERRIDE <= Proposed"]
""",
    "fig08_ack_pruning_seq": """sequenceDiagram
  participant Src as Source Node
  participant Rel as Relay Node
  participant Dst as Destination
  Src->>Rel: CRITICAL_FAULT_12 (Protected Replica)
  Rel->>Dst: Deliver CRITICAL_FAULT_12
  Dst-->>Rel: 4B ACK ID on Next Contact (<=32 IDs)
  Rel->>Rel: Promote Replicas -> First-Evictable deliveredAckSet
""",
    "fig09_atomic_replay_flow": """flowchart LR
  SNAP["Atomic Lock: Pre-Decision Telemetry Snapshot"] --> DEC["Evaluate M2-Mob / SRL & Shield"]
  DEC --> LOG["Write 21-Col DtnmaAuditReport"]
  LOG --> REPLAY["Verify ARI Traceability: 100.00% Match"]
""",
    "fig10_adapter_composition": """flowchart TB
  INTF["DtnmaManagedRouter Interface"]
  A1["ManagedSprayAndWaitRouter (233 LOC)"] --> INTF
  A2["ManagedProphetV2Router (244 LOC)"] --> INTF
  A3["ManagedMaxPropRouter (305 LOC)"] --> INTF
  A4["ManagedEpidemicRouter (201 LOC)"] --> INTF
  INTF <--> AG["Shared DtnmaAgent Engine (956 non-comment LOC)"]
""",
    "fig11_dissemination_modes": """flowchart LR
  M1R["M1-Remote: Manager Unicast CTRL_UPDATE_ (50.07% EvC Bulk, 90.89 alive)"]
  M2S["M2-SRL: Local 1s State-Based Rules (67.33% EvC Bulk, 84.00 alive)"]
  M2M["M2-Mob: Local Mobility-Aware Coordinator (102.42 alive, 70.18% Bulk, 98.48% Crit)"]
""",
    "fig13_codebase_dag": """flowchart TB
  CFG["DtnmaConfig (26 keys) / ControlAction / DtnmaPolicyProfile (P0..P7) / DtnmaContactBeacon (16B)"] --> AST["PredicateExpression -> StateBasedRule -> DtnmaRuleParser"]
  CFG --> EDD["EddTelemetrySampler (15 EDDs)"]
  CFG --> SH["DtnmaSafetyShield (S1..S4)"]
  AST & EDD & SH --> AG["DtnmaAgent (M2-Mob / M2-SRL / M1-Remote / M0)"]
  AG <--> MR["DtnmaManagedRouter & Managed*Router (4) / DtnmaRouter + Presets"]
  AG --> REP["src/report/* (4 Reports)"]
""",
    "fig14_seed_governance": """flowchart LR
  CONF["Confirmatory Suite (1,216 runs): 19 primes 8101..8269 (mob_*.txt, 8 Holm Families)"] -. Matched Disjoint Scope .-> EXP["Exploratory Sensitivity (176 runs): 11 primes 8101..8209 (sens_*.txt, Non-Holm)"]
""",
}


def build_all_diagrams() -> None:
    MMD_DIR.mkdir(parents=True, exist_ok=True)
    for k, mmd_txt in MMD_SPECS.items():
        (MMD_DIR / f"{k}.mmd").write_text(mmd_txt.strip() + "\n", encoding="utf-8")

    # Fig 01: Layered Management-Substrate Architecture
    fig, ax = make_diagram_canvas(
        "Figure 01: ADTNM Layered Autonomic Management Architecture",
        "IETF RFC 9675 & IRTF RFC 7575-inspired decoupling across 25 routing + 4 report Java classes",
    )
    draw_box(ax, 0.06, 0.72, 0.88, 0.14, "4. OBSERVABILITY & AUDIT REPORTING\nDtnmaAuditReport (21-col ARI)  |  DtnmaMetricsReport (Cohorts / CDFs)  |  DtnmaRecoveryTimelineReport", fc="#F5F3FF", ec=OI["purple"], bold=True)
    draw_box(ax, 0.06, 0.45, 0.88, 0.21, "3. LOCAL AUTONOMIC COORDINATOR (DtnmaAgent)", fc="#EFF6FF", ec=OI["blue"], bold=False)
    ax.texts[-1].set_position((0.5, 0.625))
    ax.texts[-1].set_fontweight("bold")
    draw_box(ax, 0.09, 0.48, 0.19, 0.11, "15 EDDs Sampler\n(Mob + Buf + Eng)", fc="#FFFFFF", ec=OI["sky"], fontsize=8.5)
    draw_box(ax, 0.32, 0.48, 0.19, 0.11, "M2_MOBILITY_AWARE\nor SRL Rules (1.0 s)", fc="#FFFFFF", ec=OI["blue"], fontsize=8.5)
    draw_box(ax, 0.55, 0.48, 0.19, 0.11, "Safety Shield\n(S1-S4 + Liveness)", fc="#FEF3C7", ec=OI["orange"], fontsize=8.5, bold=True)
    draw_box(ax, 0.77, 0.48, 0.14, 0.11, "Profile\nP0 .. P7", fc="#ECFDF5", ec=OI["green"], fontsize=8.5, bold=True)
    draw_arrow(ax, 0.28, 0.535, 0.32, 0.535)
    draw_arrow(ax, 0.51, 0.535, 0.55, 0.535)
    draw_arrow(ax, 0.74, 0.535, 0.77, 0.535)
    draw_box(ax, 0.06, 0.25, 0.88, 0.14, "2. SUBSTRATE ADAPTER LAYER\nDtnmaManagedRouter Contract  |  Managed*Router (mean 245.8 non-comment LOC)  |  4-Tier Queue + ACK Prune", fc="#ECFDF5", ec=OI["green"], bold=True)
    draw_box(ax, 0.06, 0.06, 0.88, 0.13, "1. FORWARDING & PHYSICAL SUBSTRATE (The ONE)\nSprayAndWait  |  PRoPHETv2 (RFC 6693)  |  MaxProp  |  Epidemic  |  DTNHost Energy/Radio", fc="#FFF7ED", ec=OI["vermillion"], bold=True)
    draw_arrow(ax, 0.30, 0.19, 0.30, 0.25, "Hooks")
    draw_arrow(ax, 0.18, 0.39, 0.18, 0.48, "15 EDDs")
    draw_arrow(ax, 0.84, 0.48, 0.84, 0.39, "L, bp, ing")
    draw_arrow(ax, 0.64, 0.59, 0.64, 0.72, "ARI Trace")
    save_fig(fig, "fig01_architecture_overview")

    # Fig 02: Three Timescales
    fig, ax = make_diagram_canvas("Figure 02: Three Coupled Control Timescales in ADTNM", "Why 1.0 s local coordination prevents remote surge lag and battery collapse")
    draw_box(ax, 0.06, 0.58, 0.26, 0.24, "Fast Protection\n(Every 0.1s-0.5s Tick)\n\n• CheckReceiving reserve\n• ACK-first safe eviction\n• 4B ACK dupe prune", fc="#ECFDF5", ec=OI["green"])
    draw_box(ax, 0.37, 0.58, 0.26, 0.24, "Local Coordinator\n(1.0 s Decision Cadence)\n\n• 15 EDDs EWMA & TTD\n• M2-Mob or SRL rules\n• Hard Shield S1..S4", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.68, 0.58, 0.26, 0.24, "Remote Manager Overlay\n(Minutes to Hours Delay)\n\n• Unicast CTRL_UPDATE_\n• Multi-hop DTN lag\n• PRIO 110 Overlay Slot", fc="#FFF7ED", ec=OI["orange"])
    draw_box(ax, 0.10, 0.14, 0.80, 0.25, "Env 1 Stress Empirical Contrast (n=19, 12h):  M2-Mob sustains 102.42 alive, 70.18% Bulk, 67.22% Event C\nvs M1-Remote (90.89 alive, collapses to 50.07% during Event C) & M2-SRL (84.00 alive, 66.50% Bulk)", fc="#F8FAFC", ec=OI["vermillion"], fontsize=9.3)
    draw_arrow(ax, 0.19, 0.58, 0.25, 0.39, "Instant guard")
    draw_arrow(ax, 0.50, 0.58, 0.50, 0.39, "1.0s coordination")
    draw_arrow(ax, 0.81, 0.58, 0.75, 0.39, "Delayed arrival")
    save_fig(fig, "fig02_three_timescales")

    # Fig 03: Intervention Tiers
    fig, ax = make_diagram_canvas("Figure 03: Additive Intervention Tiers (DtnmaConfig.InterventionTier)", "Progressive separation of priority protection, energy/ingress shielding, and replica caps")
    draw_box(ax, 0.05, 0.45, 0.19, 0.30, "Stock Router\n(Unmanaged)\n\nEpidemic / PRoPHET /\nMaxProp / SnW\nDrops CRIT_ under load", fc="#F1F5F9", ec=OI["slate"])
    draw_box(ax, 0.28, 0.45, 0.21, 0.30, "Tier 1: Protection Only\n\n• Priority crit reserve\n• Byte overshoot guard\n• 4B ACK pruning\nZero core routing edits", fc="#EFF6FF", ec=OI["sky"])
    draw_box(ax, 0.53, 0.45, 0.21, 0.30, "Tier 2: + Ingress/Energy\n\n• BULK_ ingress modes\n• S1-S4 Shield\n• Energy-horizon gate\nPreserves battery fleet", fc="#ECFDF5", ec=OI["green"], bold=True)
    draw_box(ax, 0.78, 0.45, 0.18, 0.30, "Tier 3: + Replica Caps\n\n• Dynamic L quota\n• Contact-duration caps\n• Sparse boost (L=8)\nFull M2-Mob control", fc="#FFF7ED", ec=OI["orange"])
    draw_arrow(ax, 0.24, 0.60, 0.28, 0.60)
    draw_arrow(ax, 0.49, 0.60, 0.53, 0.60)
    draw_arrow(ax, 0.74, 0.60, 0.78, 0.60)
    draw_box(ax, 0.05, 0.14, 0.91, 0.18, "F4 Cross-Substrate Proof (n=19): Wrapping Stock Substrates eliminates unique_critical_lost (70.8-124.0 -> 0.0)\nand lifts CritDel +24.94 pp (SnW), +63.56 pp (PRoPHETv2), +33.18 pp (MaxProp), +53.71 pp (Epidemic)", fc="#F8FAFC", ec=OI["blue"], fontsize=9.1)
    save_fig(fig, "fig03_intervention_tiers")

    # Fig 04: EDD to Profile Pipeline
    fig, ax = make_diagram_canvas("Figure 04: Closed-Loop Control Pipeline (15 EDDs -> Coordinator/SRL -> Shield -> Profile)")
    draw_box(ax, 0.04, 0.45, 0.19, 0.32, "15 EDD Metrics\nQ, CritQ, dQ, Dens,\nConn, Speed, SoC,\nDrain, NbrQ, Grad,\nDestProb, DurMean,\nICT, EncRate, TTD", fc="#EFF6FF", ec=OI["sky"], fontsize=8.2)
    draw_box(ax, 0.27, 0.45, 0.20, 0.32, "Policy Coordinator\nM2-Mob Closed Law\n(Energy/ICT/Contact)\nor SRL Priority AST", fc="#EFF6FF", ec=OI["blue"], fontsize=8.6)
    draw_box(ax, 0.52, 0.45, 0.20, 0.32, "Safety Shield\n8-Bit Bitmask Filter\nS1 Critical / Surge\nS2 SoC / S3 Storm\nS4 Remote Clamp", fc="#FEF3C7", ec=OI["orange"], fontsize=8.6, bold=True)
    draw_box(ax, 0.77, 0.45, 0.19, 0.32, "Active Profile P0..P7\nL in {1..12}\ning in {0, 1, 2}\nbp in {0.05..0.30}", fc="#ECFDF5", ec=OI["green"], fontsize=8.6, bold=True)
    draw_arrow(ax, 0.23, 0.61, 0.27, 0.61)
    draw_arrow(ax, 0.47, 0.61, 0.52, 0.61, "a_prop")
    draw_arrow(ax, 0.72, 0.61, 0.77, 0.61, "a_safe")
    draw_box(ax, 0.12, 0.14, 0.76, 0.16, "Decision snapshot + SafeMask + ActiveRuleARI emitted in 21-column DtnmaAuditReport (traceable_ari_ratio: 1.0000)", fc="#F5F3FF", ec=OI["purple"])
    save_fig(fig, "fig04_edd_to_profile_pipeline")

    # Fig 05: 4-Tier Forwarding + ACK-First Critical-Safe Eviction
    fig, ax = make_diagram_canvas("Figure 05: 4-Tier Forwarding Priority & Critical-Safe Eviction Hierarchy", "DtnmaRouter.java:221-245 (Forwarding Buckets) & DtnmaAgent.java:789-818 (selectNextMessageToRemove)")
    draw_box(ax, 0.06, 0.72, 0.41, 0.12, "FORWARDING SCHEDULING (O(N) 4-Tier)", fc="#EFF6FF", ec=OI["blue"], bold=True)
    fwd = [
        ("Bucket 0 (1st)", "Critical/Control with L > 1 copies", "#DCFCE7", OI["green"]),
        ("Bucket 1 (2nd)", "Critical/Control with L <= 1 (direct)", "#ECFDF5", OI["green"]),
        ("Bucket 2 (3rd)", "BULK_ bundles with L > 1 copies", "#EFF6FF", OI["sky"]),
        ("Bucket 3 (4th)", "BULK_ bundles with L <= 1 (direct)", "#F1F5F9", OI["slate"]),
    ]
    for idx, (t, desc, fc, ec) in enumerate(fwd):
        y = 0.55 - idx * 0.135
        draw_box(ax, 0.06, y, 0.15, 0.10, t, fc=fc, ec=ec, bold=True, fontsize=8.3)
        draw_box(ax, 0.22, y, 0.25, 0.10, desc, fc="#FFFFFF", ec=ec, fontsize=8.2)

    draw_box(ax, 0.53, 0.72, 0.41, 0.12, "EVICTION ORDER (ACK-First Safe Lock)", fc="#FEF3C7", ec=OI["orange"], bold=True)
    ev = [
        ("Evict 1st", "Delivered ACKed CRIT_ (deliveredAckSet LRU 512)", "#FFEDD5", OI["orange"]),
        ("Evict 2nd", "Worst BULK_ via tieBreaker / oldest FIFO", "#FEE2E2", OI["vermillion"]),
        ("Protected", "Live Un-ACKed CRIT_ / CTRL_ (skipped under Safe Lock)", "#DCFCE7", OI["green"]),
    ]
    for idx, (t, desc, fc, ec) in enumerate(ev):
        y = 0.55 - idx * 0.175
        draw_box(ax, 0.53, y, 0.13, 0.115, t, fc=fc, ec=ec, bold=True, fontsize=8.3)
        draw_box(ax, 0.67, y, 0.27, 0.115, desc, fc="#FFFFFF", ec=ec, fontsize=8.2)
    save_fig(fig, "fig05_priority_queue_and_eviction")

    # Fig 06: SRL Priority Cascade
    fig, ax = make_diagram_canvas("Figure 06: State-Based Rule Priority Evaluation (rules_vdtn.txt)", "Priority-descending scan guarantees prompt catch-all recovery (PRIO 10)")
    rules = [
        ("PRIO 100 | VDTN_BUFFER_CRITICAL", "SoC < 0.20 || CritQ >= 0.50 -> L=1, ing=2 (Hold)", "#FEE2E2", OI["vermillion"]),
        ("PRIO 80  | VDTN_SURGE_THROTTLE", "Q >= 0.88 && dQ >= 0.008 -> L=2, ing=1 (Throttle)", "#FFEDD5", OI["orange"]),
        ("PRIO 60  | VDTN_TRAFFIC_JAM", "Q >= 0.75 && Dens >= 5 && Speed < 2 -> L=4, ing=0", "#EFF6FF", OI["blue"]),
        ("PRIO 10  | VDTN_URBAN_NOMINAL", "Q >= 0.0 (Catch-All Release) -> L=6, ing=0", "#DCFCE7", OI["green"]),
    ]
    for i, (rtitle, rcond, fc, ec) in enumerate(rules):
        y = 0.70 - i * 0.175
        draw_box(ax, 0.10, y, 0.80, 0.115, f"{rtitle}\nIF: {rcond}", fc=fc, ec=ec, fontsize=9.2)
        if i < 3:
            draw_arrow(ax, 0.50, y, 0.50, y - 0.06, "False -> next")
    save_fig(fig, "fig06_srl_priority_flow")

    # Fig 07: Safety Shield Bitmask
    fig, ax = make_diagram_canvas("Figure 07: Deterministic Safety Shield (S1-S4 + Liveness)", "Bitmask intersection guarantees P0 SAFE_EMERGENCY_HOLD is never excluded")
    draw_box(ax, 0.05, 0.48, 0.17, 0.28, "Proposed Action\n(e.g. P5/P6)\nHIGH_SPRAY_\nBACKPRESSURE", fc="#EFF6FF", ec=OI["blue"])
    gates = [
        ("S1 Crit/Surge", "CritQ>=0.50 -> {P0}\nCritQ>=0.35 -> {0..2}"),
        ("S2 Battery", "SoC < min -> {P0,P1}"),
        ("S3 Dense Storm", "Q>=0.78 & Dens>=5\n-> block {P6, P7}"),
        ("S4 Remote/Live", "Overlay min-clamp\nmask[0] = 1 ALWAYS"),
    ]
    for i, (gt, gd) in enumerate(gates):
        x = 0.26 + i * 0.145
        draw_box(ax, x, 0.46, 0.13, 0.32, f"{gt}\n\n{gd}", fc="#FEF3C7", ec=OI["orange"], fontsize=8.0)
        draw_arrow(ax, x - 0.04 if i == 0 else x - 0.015, 0.62, x, 0.62)
    draw_box(ax, 0.84, 0.48, 0.12, 0.28, "mask=11111100\nOVERRIDE -> P3\n(or PASS)", fc="#DCFCE7", ec=OI["green"], fontsize=8.5, bold=True)
    draw_arrow(ax, 0.825, 0.62, 0.84, 0.62)
    draw_box(ax, 0.12, 0.16, 0.76, 0.16, "Bit Positions [P0 P1 P2 P3 P4 P5 P6 P7]:  mask = 11111100 disables {P6, P7} -> max admissible <= P6 is P3 (BALANCED_AGILE_FOCUS)", fc="#F8FAFC", ec=OI["slate"])
    save_fig(fig, "fig07_safety_shield_bitmask")

    # Fig 08: ACK Pruning Sequence
    fig, ax = make_diagram_canvas("Figure 08: Delayed 4-Byte Delivery ACK Exchange & Duplicate Copy Pruning")
    actors = [("Source Node", 0.16), ("Relay Buffer", 0.42), ("Destination", 0.68), ("Peer Contact", 0.88)]
    for name, xp in actors:
        draw_box(ax, xp - 0.085, 0.78, 0.17, 0.09, name, fc="#EFF6FF", ec=OI["blue"], bold=True)
        ax.plot([xp, xp], [0.12, 0.78], "--", color="#94A3B8", lw=1.2, zorder=1)
    draw_arrow(ax, 0.16, 0.68, 0.42, 0.68, "CRIT_12 (Safe-Locked)", color=OI["green"])
    draw_arrow(ax, 0.42, 0.56, 0.68, 0.56, "Forward CRIT_12 -> Delivered!", color=OI["blue"])
    draw_arrow(ax, 0.68, 0.44, 0.88, 0.44, "4B ACK ID (max 32/contact)", color=OI["orange"])
    draw_arrow(ax, 0.88, 0.32, 0.42, 0.32, "Sync ACK -> Promote to Evict-First", color=OI["vermillion"])
    draw_box(ax, 0.25, 0.13, 0.50, 0.11, "Result: Duplicate copies freed cleanly for fresh BULK_ without losing un-ACKed CRIT_", fc="#DCFCE7", ec=OI["green"], fontsize=8.8)
    save_fig(fig, "fig08_ack_pruning_seq")

    # Fig 09: Atomic Snapshot & Replay
    fig, ax = make_diagram_canvas("Figure 09: Atomic Telemetry Snapshot & Deterministic ARI Provenance")
    draw_box(ax, 0.05, 0.48, 0.20, 0.28, "1. Lock Pre-Decision\nEddTelemetrySampler\n15 EDDs before updates", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.29, 0.48, 0.20, 0.28, "2. Evaluate Mode\nM2-Mob or SRL\n+ Shield S1..S4 Mask", fc="#FEF3C7", ec=OI["orange"])
    draw_box(ax, 0.53, 0.48, 0.20, 0.28, "3. Stream 21-Col Row\nto DtnmaAuditReport\nARI://... + mask + expl", fc="#F5F3FF", ec=OI["purple"])
    draw_box(ax, 0.77, 0.48, 0.19, 0.28, "4. Audit Summary\ntraceable_ari_ratio:\n1.0000 Exact Match", fc="#DCFCE7", ec=OI["green"], bold=True)
    for x1, x2 in [(0.25, 0.29), (0.49, 0.53), (0.73, 0.77)]:
        draw_arrow(ax, x1, 0.62, x2, 0.62)
    draw_box(ax, 0.12, 0.16, 0.76, 0.15, "Every 30 s decision (or 1.0 s smoke check) records exact proposed, fallback, and executed profiles with causal explanations", fc="#F8FAFC", ec=OI["slate"])
    save_fig(fig, "fig09_atomic_replay_flow")

    # Fig 10: Adapter Composition
    fig, ax = make_diagram_canvas("Figure 10: Composition Pattern vs. Custom Subclass Re-Implementation")
    draw_box(ax, 0.06, 0.50, 0.41, 0.32, "ADTNM Composition Adapters (4 Substrates)\n\nManagedSprayAndWaitRouter (233 LOC)\nManagedProphetV2Router (244 LOC)\nManagedMaxPropRouter (305 LOC)\nManagedEpidemicRouter (201 LOC)", fc="#DCFCE7", ec=OI["green"])
    draw_box(ax, 0.53, 0.50, 0.41, 0.32, "Why DtnmaManagedRouter Works\n\n• Keeps native Dijkstra / PRoPHET / Spray logic\n• Intercepts checkReceiving, eviction, relay\n• Eliminates unique_critical_lost -> 0.0\n  across all 4 routers (F4, n=19 seeds)", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.06, 0.14, 0.88, 0.22, "Shared DtnmaAgent Engine (956 non-comment LOC) + Mobility Coordinator / SRL\nLifts CritDel by +24.94 pp (SnW), +63.56 pp (PRoPHETv2), +33.18 pp (MaxProp), and +53.71 pp (Epidemic)\nwhile slashing Epidemic overhead from 172.8x to 53.8x", fc="#FEF3C7", ec=OI["orange"], bold=True)
    draw_arrow(ax, 0.26, 0.50, 0.35, 0.36, "Delegates hooks")
    save_fig(fig, "fig10_adapter_composition")

    # Fig 11: Control Paradigms Comparison
    fig, ax = make_diagram_canvas("Figure 11: Controller Paradigms Compared in Env 1 Stress (n = 19, 12 h)")
    modes = [
        ("M0b-Static (Protection Only, Static L=6)", "54.95/126 alive  |  96.49% CritDel  |  76.09% BulkDel  |  0 lost", "#FEE2E2", OI["vermillion"]),
        ("M0e-25 (Static Energy SoC < 0.25 Cutoff Only)", "84.32/126 alive  |  98.00% CritDel  |  62.11% BulkDel  |  58.08% EvC", "#FFEDD5", OI["orange"]),
        ("M1-Remote (Delayed Unicast CTRL_UPDATE_ Waves)", "90.89/126 alive  |  97.91% CritDel  |  73.85% BulkDel  |  50.07% EvC", "#FFF7ED", OI["purple"]),
        ("M2-SRL (Local 1 s Declarative State Rules)", "84.00/126 alive  |  97.52% CritDel  |  66.50% BulkDel  |  67.33% EvC", "#EFF6FF", OI["blue"]),
        ("M2-Mob (Closed-Form Mobility Coordinator + Shield)", "102.42/126 alive (+18.42 vs SRL)  |  98.48% CritDel  |  70.18% BulkDel  |  67.22% EvC", "#DCFCE7", OI["green"]),
    ]
    for i, (mt, ms, fc, ec) in enumerate(modes):
        y = 0.72 - i * 0.145
        draw_box(ax, 0.06, y, 0.88, 0.11, f"{mt}\n{ms}", fc=fc, ec=ec, fontsize=9.0, bold=(i == 4))
    save_fig(fig, "fig11_dissemination_modes")

    # Fig 13: Codebase DAG
    fig, ax = make_diagram_canvas("Figure 13: ADTNM Package & Class Dependency DAG (29 Java Files, 5,660 Non-Comment LOC)")
    draw_box(ax, 0.06, 0.72, 0.88, 0.13, "Value Objects & Config:  DtnmaConfig (26 keys)  |  ControlAction  |  DtnmaPolicyProfile (P0-P7)  |  DtnmaContactBeacon (16B)", fc="#F8FAFC", ec=OI["slate"])
    draw_box(ax, 0.06, 0.51, 0.39, 0.14, "Rule AST\nPredicateExpression -> StateBasedRule\n-> DtnmaRuleParser", fc="#EFF6FF", ec=OI["sky"])
    draw_box(ax, 0.55, 0.51, 0.39, 0.14, "Telemetry & Guard\nEddTelemetrySampler (15 EDDs)\nDtnmaSafetyShield (S1-S4)", fc="#FEF3C7", ec=OI["orange"])
    draw_box(ax, 0.18, 0.27, 0.64, 0.16, "Orchestrator Core:  DtnmaAgent (M2-Mob / M2-SRL / M1 / M0)  <-->  DtnmaManagedRouter", fc="#DCFCE7", ec=OI["green"], bold=True)
    draw_box(ax, 0.06, 0.07, 0.43, 0.13, "Substrates (15 files): Managed*Router (4), DtnmaRouter + 8 Presets, CA-SnW", fc="#EFF6FF", ec=OI["blue"], fontsize=8.4)
    draw_box(ax, 0.52, 0.07, 0.42, 0.13, "Reports (4 files): DtnmaMetrics, DtnmaAudit, Timeline, Decision", fc="#FFEDD5", ec=OI["vermillion"], fontsize=8.4)
    draw_arrow(ax, 0.25, 0.51, 0.40, 0.43)
    draw_arrow(ax, 0.74, 0.51, 0.60, 0.43)
    draw_arrow(ax, 0.40, 0.27, 0.27, 0.20)
    draw_arrow(ax, 0.60, 0.27, 0.73, 0.20)
    save_fig(fig, "fig13_codebase_dag")

    # Fig 14: Seed Governance
    fig, ax = make_diagram_canvas("Figure 14: Seed Isolation & Statistical Family Governance (SEED_ROLES.md)")
    draw_box(ax, 0.05, 0.44, 0.42, 0.34, "Confirmatory Mobility Suite (1,216 Runs)\n\n• 19 Primes: 8101..8269 (coprime to {4, 8, 10})\n• 9 mob_*.txt scenarios across 4 environments +\n  Cross-Substrate + Scalability (N=60..500)\n• 8 Predeclared Holm-Bonferroni Families\n  (F1..F5, 112 confirmatory paired tests)", fc="#DCFCE7", ec=OI["green"], bold=True, fontsize=8.8)
    draw_box(ax, 0.53, 0.44, 0.42, 0.34, "Exploratory Sensitivity Sweeps (176 Runs)\n\n• First 11 Matched Primes: 8101..8209\n• Strictly isolated from Holm confirmatory pools\n• sens_range_{20m,55m}.txt (BT range sweep)\n• sens_energy_{075x,135x}.txt (battery sweep)\n• Exposes 0.75x survival-vs-bulk tradeoff", fc="#FEF3C7", ec=OI["orange"], fontsize=8.8)
    draw_arrow(ax, 0.47, 0.61, 0.53, 0.61, "Isolated")
    draw_box(ax, 0.10, 0.14, 0.80, 0.18, "Stochastic Discipline: MovementModel.rngSeed varies per trajectory while traffic & energy stay matched.\nIntegrity Verified: 4,864 + 704 files, 0 zero-size, SHA-256 manifest (2,432 checks, 0 mismatches).", fc="#EFF6FF", ec=OI["blue"], fontsize=9.0)
    save_fig(fig, "fig14_seed_governance")


def main() -> None:
    build_all_diagrams()
    svgs = sorted(SVG_DIR.glob("*.svg"))
    pngs = sorted(PNG_DIR.glob("*.png"))
    mmds = sorted(MMD_DIR.glob("*.mmd"))
    print(f"Diagrams built: {len(mmds)} MMDs, {len(svgs)} SVGs; total PNGs (incl. empirical plots): {len(pngs)}")
    assert len(mmds) == 13 and len(svgs) == 13


if __name__ == "__main__":
    main()
