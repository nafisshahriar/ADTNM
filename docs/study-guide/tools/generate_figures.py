#!/usr/bin/env python3
"""Generate all 21 Study Guide Figures (.svg + 300-DPI .png) and 14 .mmd sources.

Diagrams 01-14:
  - Canonical Mermaid sources saved to docs/study-guide/figures/mmd/figXX_*.mmd
  - Publication-grade vector SVG + 300-DPI PNG rendered via matplotlib patches (Okabe-Ito palette)
Quantitative Plots 15-21:
  - Generated strictly from docs/study-guide/data/provenance_ledger.json and raw timeline logs
  - Visible [CONFIRMATORY v1.0] vs [EXPLORATORY] badges on every plot
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Tuple

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch
import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
GUIDE_DIR = SCRIPT_DIR.parent
FIG_DIR = GUIDE_DIR / "figures"
MMD_DIR = FIG_DIR / "mmd"
SVG_DIR = FIG_DIR / "svg"
PNG_DIR = FIG_DIR / "png"
LEDGER_PATH = GUIDE_DIR / "data" / "provenance_ledger.json"
FINAL_DIR = Path(r"n:\R&D\cog\the-one\reports\final_11seed")

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


# ---------------------------------------------------------------------------
# Diagrams 01-14: Save .mmd AND render crisp SVG/PNG
# ---------------------------------------------------------------------------
MMD_SPECS: Dict[str, str] = {
    "fig01_architecture_overview": """flowchart TB
  subgraph P4["4. Observability & Provenance Plane"]
    R1["DtnmaAuditReport (16-col ARI TSV)"]
    R2["DtnmaRlMetricsReport (Cohorts / CDFs / Energy)"]
    R3["DtnmaRecoveryTimelineReport"]
  end
  subgraph P3["3. Autonomic Decision & Policy Plane (DtnmaAgent)"]
    EDD["EddTelemetrySampler (11 EDDs)"]
    SRL["DtnmaRuleParser (SRL Rules)"]
    RL["ShieldedCmdpRlAgent [EXPERIMENTAL]"]
    SHIELD["DtnmaSafetyShield (S1..S4 + Liveness P0)"]
    PROF["Active Control Profile (P0..P7)"]
    EDD --> SRL --> SHIELD
    EDD --> RL -.-> SHIELD
    SHIELD --> PROF
  end
  subgraph P2["2. Substrate Adaptation Plane"]
    MR["DtnmaManagedRouter Hooks + 5-Tier Queue + ByteBudget"]
  end
  subgraph P1["1. Managed Substrates (The ONE)"]
    SUB["SprayAndWait | PRoPHETv2 | MaxProp | Epidemic | DtnmaRouter"]
  end
  P1 <--> P2
  P2 --> EDD
  PROF --> MR
  SHIELD --> R1
""",
    "fig02_three_timescales": """sequenceDiagram
  participant Fast as Fast Protection (0.1s Tick)
  participant Local as Local SRL (1s/30s Eval)
  participant Remote as Remote Manager (min-hr)
  Fast->>Fast: checkReceiving / 5-tier ACK eviction / ingressMode
  Local->>Fast: Sample 11 EDDs -> Match SRL -> Safety Shield S1..S4
  Remote-->>Local: Delayed CTRL_UPDATE_ / CTRL_GROUP_ Overlay (PRIO 100)
""",
    "fig03_intervention_tiers": """flowchart LR
  T0["Unmanaged Stock Router"] --> T1["Tier 1: Protection Only (5-Tier Queue + ByteGuard + 4B ACKs)"]
  T1 --> T2["Tier 2: + Ingress Throttling & Energy Shield (S1..S4 + SRL)"]
  T2 --> T3["Tier 3: + Dynamic Replica Caps (L=1..12) in Adapter"]
""",
    "fig04_edd_to_profile_pipeline": """flowchart LR
  EDDS["11 EDDs"] --> EVAL["Rule / [Opt. M3]"]
  EVAL --> MASK["DtnmaSafetyShield (S1..S4 mask)"]
  MASK --> PROF["Selected Profile (P0..P7)"]
  PROF --> ACT["Actuators: L_i, bpRatio, ingressMode, backpressure"]
""",
    "fig05_five_tier_eviction": """flowchart TB
  T0["Tier 0 (Evict First): BULK_ / SCIENCE_DATA_"] --> T1["Tier 1: Already-ACKed CRIT_ Copies"]
  T1 --> T2["Tier 2: Stale / Other"]
  T2 --> T3["Tier 3: Active CTRL_ Control Bundles"]
  T3 --> T4["Tier 4 (Evict Last): Un-ACKed Live CRIT_ Bundles"]
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
  Src->>Rel: CRITICAL_FAULT_12 (Tier 4 Protected)
  Rel->>Dst: Deliver CRITICAL_FAULT_12
  Dst-->>Rel: 4B ACK ID on Next Contact (<=32 IDs)
  Rel->>Rel: Move Copy Tier 4 -> Tier 1 (Reclaimable)
""",
    "fig09_atomic_replay_flow": """flowchart LR
  SNAP["Atomic Lock: eddSnapshot[11]"] --> DEC["Evaluate SRL / RL / Shield"]
  DEC --> LOG["Write 16-Col DtnmaAuditReport"]
  LOG --> REPLAY["Offline verify_audit_replay (Python): 100.00% Match"]
""",
    "fig10_adapter_composition": """flowchart TB
  INTF["DtnmaManagedRouter Interface"]
  A1["ManagedSprayAndWaitRouter"] --> INTF
  A2["ManagedProphetV2Router"] --> INTF
  A3["ManagedMaxPropRouter"] --> INTF
  A4["ManagedEpidemicRouter"] --> INTF
  INTF <--> AG["Shared DtnmaAgent Core (2,095 non-comment LOC)"]
""",
    "fig11_dissemination_modes": """flowchart LR
  M1U["M1-U: 120 Unicast Bundles (2.51 kJ, T90=3336s)"]
  M1F["M1-F: 120 Per-Node Floods (118.7 kJ, 13.5 alive)"]
  M1FG["M1-F-Group: 1 Shared Wave Bundle (201.6 J, T50=483s, T90=841s)"]
  M2["M2: Local 30s SRL + Remote 0.25 Guard Wave (0 EvA Stale)"]
""",
    "fig12_m3_rl_architecture": """flowchart LR
  S["20 Normalized EDD Features (phi)"] --> Q["wReward[8][20] Linear Critic (lambda=0)"]
  Q --> SH["DtnmaSafetyShield Masking"]
  SH --> EPS["Epsilon-Greedy Action"]
  GOS["648B Symmetric Gossip on Contact"] <--> Q
""",
    "fig13_codebase_dag": """flowchart TB
  CFG["DtnmaConfig / ControlAction / DtnmaPolicyProfile"] --> AST["PredicateExpression -> StateBasedRule -> DtnmaRuleParser"]
  CFG --> EDD["EddTelemetrySampler"]
  CFG --> SH["DtnmaSafetyShield"]
  CFG --> RL["ShieldedCmdpRlAgent [Exp]"]
  AST & EDD & SH & RL --> AG["DtnmaAgent"]
  AG <--> MR["DtnmaManagedRouter & Managed*Router / DtnmaRouter"]
  AG --> REP["src/report/*"]
""",
    "fig14_seed_governance": """flowchart LR
  CAL["Calibration (M0 Only): 4001..4003"] --> FREEZE["Tag v1.0-tnsm-freeze (ca5d9ac)"]
  FREEZE --> CONF["Confirmatory (715 runs): 7103..7207 (Holm m=4)"]
  CONF -. Disjoint Fresh Seeds .-> EXP["Exploratory (176 runs): 7211..7307 (p_unadj)"]
""",
    "fig22_evaluation_methodology_timeline": """flowchart LR
  S1["1. .txt Sweep & Seed Pairing"] --> S2["2. Multi-Worker DTNSim (dt=0.5s, warmup=1000s)"]
  S2 --> S3["3. 4-Stream Report Capture (MsgStats, RlMetrics, Audit, Timeline)"]
  S3 --> S4["4. Offline Deterministic ARI Replay (100.00%)"]
  S4 --> S5["5. Paired Student-t, Holm-Bonferroni & TOST Inference"]
""",
}


def build_all_diagrams() -> None:
    MMD_DIR.mkdir(parents=True, exist_ok=True)
    for k, mmd_txt in MMD_SPECS.items():
        (MMD_DIR / f"{k}.mmd").write_text(mmd_txt.strip() + "\n", encoding="utf-8")

    # Fig 01: 4-Plane Architecture
    fig, ax = make_diagram_canvas(
        "Figure 01: ADTNM Four-Plane Architecture (Managed Substrate Separation)",
        "IETF RFC 9675 & IRTF RFC 7575-inspired layer decoupling at commit d4a159f",
    )
    draw_box(ax, 0.06, 0.72, 0.88, 0.14, "4. OBSERVABILITY & PROVENANCE PLANE\nDtnmaAuditReport (16-col ARI)  |  DtnmaRlMetricsReport (Cohorts / CDFs)  |  Offline 100% Replay", fc="#F5F3FF", ec=OI["purple"], bold=True)
    draw_box(ax, 0.06, 0.45, 0.88, 0.21, "3. AUTONOMIC DECISION & POLICY PLANE (DtnmaAgent)", fc="#EFF6FF", ec=OI["blue"], bold=False)
    ax.texts[-1].set_position((0.5, 0.625))
    ax.texts[-1].set_fontweight("bold")
    draw_box(ax, 0.09, 0.48, 0.19, 0.11, "11 EDDs Sampler\n(dt = 30 s)", fc="#FFFFFF", ec=OI["sky"], fontsize=8.5)
    draw_box(ax, 0.32, 0.48, 0.19, 0.11, "SRL Rules (12)\nor [Exp. M3 RL]", fc="#FFFFFF", ec=OI["blue"], fontsize=8.5)
    draw_box(ax, 0.55, 0.48, 0.19, 0.11, "Safety Shield\n(S1-S4 + Liveness)", fc="#FEF3C7", ec=OI["orange"], fontsize=8.5, bold=True)
    draw_box(ax, 0.77, 0.48, 0.14, 0.11, "Profile\nP0 .. P7", fc="#ECFDF5", ec=OI["green"], fontsize=8.5, bold=True)
    draw_arrow(ax, 0.28, 0.535, 0.32, 0.535)
    draw_arrow(ax, 0.51, 0.535, 0.55, 0.535)
    draw_arrow(ax, 0.74, 0.535, 0.77, 0.535)
    draw_box(ax, 0.06, 0.25, 0.88, 0.14, "2. SUBSTRATE ADAPTATION PLANE\nDtnmaManagedRouter Contract  |  Managed*Router (mean 253.5 LOC)  |  5-Tier Queue + ACK Prune", fc="#ECFDF5", ec=OI["green"], bold=True)
    draw_box(ax, 0.06, 0.06, 0.88, 0.13, "1. FORWARDING & PHYSICAL SUBSTRATE PLANE (The ONE)\nSprayAndWait  |  PRoPHETv2 (RFC 6693)  |  MaxProp  |  Epidemic  |  DTNHost Energy/Radio", fc="#FFF7ED", ec=OI["vermillion"], bold=True)
    draw_arrow(ax, 0.30, 0.19, 0.30, 0.25, "Hooks")
    draw_arrow(ax, 0.18, 0.39, 0.18, 0.48, "Metrics")
    draw_arrow(ax, 0.84, 0.48, 0.84, 0.39, "L, bp, ing")
    draw_arrow(ax, 0.64, 0.59, 0.64, 0.72, "ARI Trace")
    save_fig(fig, "fig01_architecture_overview")

    # Fig 02: Three Timescales
    fig, ax = make_diagram_canvas("Figure 02: Three Coupled Control Timescales in ADTNM", "Why sub-minute local autonomy avoids remote surge lag")
    draw_box(ax, 0.06, 0.58, 0.26, 0.24, "Fast Protection\n(Every 0.1s-0.5s Tick)\n\n• CheckReceiving reserve\n• 5-Tier O(N) eviction\n• 4B ACK dupe prune", fc="#ECFDF5", ec=OI["green"])
    draw_box(ax, 0.37, 0.58, 0.26, 0.24, "Local Autonomic SRL\n(1s Eval / 30s Telemetry)\n\n• 11 EDDs EWMA\n• PRIO-sorted SRL rules\n• Hard Shield S1..S4", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.68, 0.58, 0.26, 0.24, "Remote Manager Overlay\n(Minutes to Hours Delay)\n\n• CTRL_UPDATE / GROUP\n• T50=483s / T90=3336s\n• PRIO 100 Overlay Slot", fc="#FFF7ED", ec=OI["orange"])
    draw_box(ax, 0.10, 0.14, 0.80, 0.25, "300 s Surge Event Window (Event A: 3600-3900 s)\nLocal SRL triggers at ~3360s (0.0 stale node-s)  vs  Unicast/Group Flood arrives AFTER 3900s (389.1 / 117.5 stale node-s)", fc="#F8FAFC", ec=OI["vermillion"], fontsize=9.5)
    draw_arrow(ax, 0.19, 0.58, 0.25, 0.39, "Instant guard")
    draw_arrow(ax, 0.50, 0.58, 0.50, 0.39, "30s response")
    draw_arrow(ax, 0.81, 0.58, 0.75, 0.39, "Late arrival")
    save_fig(fig, "fig02_three_timescales")

    # Fig 03: Intervention Tiers
    fig, ax = make_diagram_canvas("Figure 03: Additive Intervention Tiers (CompA Ablation Design)", "Isolating critical protection from ingress/energy rules and routing caps")
    draw_box(ax, 0.05, 0.45, 0.19, 0.30, "Stock Router\n(Unmanaged)\n\nEpidemic / PRoPHET /\nMaxProp / SnW\nDrops CRIT_ under load", fc="#F1F5F9", ec=OI["slate"])
    draw_box(ax, 0.28, 0.45, 0.21, 0.30, "Tier 1: Protection Only\n\n• 5-tier priority queue\n• Byte reservation\n• 4B ACK pruning\nZero core routing edits", fc="#EFF6FF", ec=OI["sky"])
    draw_box(ax, 0.53, 0.45, 0.21, 0.30, "Tier 2: + Ingress/Energy\n\n• BULK_ ingress modes\n• S1-S4 Shield\n• SRL local rules\nDrives survival gain", fc="#ECFDF5", ec=OI["green"], bold=True)
    draw_box(ax, 0.78, 0.45, 0.18, 0.30, "Tier 3: + Replica Caps\n\n• Dynamic L quota\n• Adapter spray clamp\nMinimal marginal diff\nvs Tier 2 in CompA", fc="#FFF7ED", ec=OI["orange"])
    draw_arrow(ax, 0.24, 0.60, 0.28, 0.60)
    draw_arrow(ax, 0.49, 0.60, 0.53, 0.60)
    draw_arrow(ax, 0.74, 0.60, 0.78, 0.60)
    draw_box(ax, 0.05, 0.14, 0.91, 0.18, "CompA Proof (Epidemic Blackout, n=11):  Stock (14.5 alive, 1288 crit lost) -> Tier 1 (15.2 alive, 0 lost) -> Tier 2 (123.1 alive, 0 lost) -> Tier 3 (123.5 alive, 0 lost)", fc="#F8FAFC", ec=OI["blue"], fontsize=9.3)
    save_fig(fig, "fig03_intervention_tiers")

    # Fig 04: EDD to Profile Pipeline
    fig, ax = make_diagram_canvas("Figure 04: Closed-Loop Control Pipeline (EDD -> SRL/RL -> Shield -> Profile)")
    draw_box(ax, 0.04, 0.45, 0.18, 0.32, "11 EDD Metrics\nQ, CritQ, dQ,\nDens, Conn, Speed,\nSoC, Drain, NbrQ,\nGrad, DestProb", fc="#EFF6FF", ec=OI["sky"], fontsize=8.5)
    draw_box(ax, 0.27, 0.45, 0.20, 0.32, "Policy Proposer\nM2: SRL Parser\n(PRIO + Clause AST)\n[or Exp. M3 RL Q]", fc="#EFF6FF", ec=OI["blue"], fontsize=8.8)
    draw_box(ax, 0.52, 0.45, 0.20, 0.32, "Safety Shield\n8-Bit Bitmask Filter\nS1 Critical / Surge\nS2 SoC / S3 Storm\nS4 Remote Clamp", fc="#FEF3C7", ec=OI["orange"], fontsize=8.8, bold=True)
    draw_box(ax, 0.77, 0.45, 0.19, 0.32, "Active Profile P0..P7\nL in {1..12}\ning in {0, 1, 2}\nbp in {0.05..0.30}", fc="#ECFDF5", ec=OI["green"], fontsize=8.8, bold=True)
    draw_arrow(ax, 0.22, 0.61, 0.27, 0.61)
    draw_arrow(ax, 0.47, 0.61, 0.52, 0.61, "a_prop")
    draw_arrow(ax, 0.72, 0.61, 0.77, 0.61, "a_safe")
    draw_box(ax, 0.14, 0.14, 0.72, 0.16, "Atomic snapshot captured BEFORE Rule/RL/Shield execution -> Logged verbatim as ARI://DTNMA/... (100.00% deterministic offline replay)", fc="#F5F3FF", ec=OI["purple"])
    save_fig(fig, "fig04_edd_to_profile_pipeline")

    # Fig 05: Five-Tier Eviction
    fig, ax = make_diagram_canvas("Figure 05: Five-Tier O(N) Buffer Eviction & Protection Hierarchy", "DtnmaRouter.java:275-291 & DtnmaAgent.java:1004-1027")
    tiers = [
        ("Tier 0 (EVICT FIRST)", "BULK_ / SCIENCE_DATA_ Payloads", "#FEE2E2", OI["vermillion"]),
        ("Tier 1", "Already-ACKed CRIT_ Replicas (deliveredAckSet LRU 512)", "#FFEDD5", OI["orange"]),
        ("Tier 2", "Expired / Untagged Non-Critical Frames", "#F1F5F9", OI["slate"]),
        ("Tier 3", "Active CTRL_ Remote Management Bundles", "#EFF6FF", OI["blue"]),
        ("Tier 4 (EVICT LAST)", "Live Un-ACKed CRIT_ / CRITICAL_FAULT_ Payloads", "#DCFCE7", OI["green"]),
    ]
    for idx, (t, desc, fc, ec) in enumerate(tiers):
        y = 0.72 - idx * 0.145
        draw_box(ax, 0.12, y, 0.26, 0.105, t, fc=fc, ec=ec, bold=True)
        draw_box(ax, 0.41, y, 0.47, 0.105, desc, fc="#FFFFFF", ec=ec)
        if idx < 4:
            draw_arrow(ax, 0.25, y, 0.25, y - 0.04, "", color=OI["slate"])
    save_fig(fig, "fig05_five_tier_eviction")

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
    draw_box(ax, 0.05, 0.48, 0.17, 0.28, "Proposed Action\n(e.g. RL P6)\nHIGH_SPRAY_\nBACKPRESSURE", fc="#EFF6FF", ec=OI["blue"])
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
    fig, ax = make_diagram_canvas("Figure 08: Delayed 4-Byte Delivery ACK Exchange & Tier 1 Copy Pruning")
    actors = [("Source Node", 0.16), ("Relay Buffer", 0.42), ("Destination", 0.68), ("Peer Contact", 0.88)]
    for name, xp in actors:
        draw_box(ax, xp - 0.085, 0.78, 0.17, 0.09, name, fc="#EFF6FF", ec=OI["blue"], bold=True)
        ax.plot([xp, xp], [0.12, 0.78], "--", color="#94A3B8", lw=1.2, zorder=1)
    draw_arrow(ax, 0.16, 0.68, 0.42, 0.68, "CRIT_12 (Tier 4 Protected)", color=OI["green"])
    draw_arrow(ax, 0.42, 0.56, 0.68, 0.56, "Forward CRIT_12 -> Delivered!", color=OI["blue"])
    draw_arrow(ax, 0.68, 0.44, 0.88, 0.44, "4B ACK ID (max 32/contact)", color=OI["orange"])
    draw_arrow(ax, 0.88, 0.32, 0.42, 0.32, "Sync ACK -> Promote Tier 4 -> Tier 1", color=OI["vermillion"])
    draw_box(ax, 0.25, 0.13, 0.50, 0.11, "Result: Duplicate copies freed cleanly for fresh BULK_ without losing un-ACKed CRIT_", fc="#DCFCE7", ec=OI["green"], fontsize=8.8)
    save_fig(fig, "fig08_ack_pruning_seq")

    # Fig 09: Atomic Snapshot & Replay
    fig, ax = make_diagram_canvas("Figure 09: Atomic Snapshot Capture & 100.00% Offline Audit Replay")
    draw_box(ax, 0.05, 0.48, 0.20, 0.28, "1. Lock Pre-Decision\neddSnapshot[11]\nBefore transfers / mutations", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.29, 0.48, 0.20, 0.28, "2. Evaluate Rule/RL\n& Shield Mask\nEmit P_prop -> P_act", fc="#FEF3C7", ec=OI["orange"])
    draw_box(ax, 0.53, 0.48, 0.20, 0.28, "3. Stream 16-Col Row\nto DtnmaAuditReport\nARI://... + mask", fc="#F5F3FF", ec=OI["purple"])
    draw_box(ax, 0.77, 0.48, 0.19, 0.28, "4. verify_audit_replay\n27,099,616 CompB rows\n100.00% Exact Match", fc="#DCFCE7", ec=OI["green"], bold=True)
    for x1, x2 in [(0.25, 0.29), (0.49, 0.53), (0.73, 0.77)]:
        draw_arrow(ax, x1, 0.62, x2, 0.62)
    draw_box(ax, 0.12, 0.16, 0.76, 0.15, "Post-freeze fix (commit 3faa6fe): capturing snapshot atomically eliminated earlier 0.1% tick-order drift", fc="#F8FAFC", ec=OI["slate"])
    save_fig(fig, "fig09_atomic_replay_flow")

    # Fig 10: Adapter Composition
    fig, ax = make_diagram_canvas("Figure 10: Composition vs. Subclass Re-Implementation (A3 Evidence)")
    draw_box(ax, 0.06, 0.52, 0.40, 0.30, "ADTNM Composition Pattern\n\nManagedSprayAndWait (265 LOC)\nManagedProphetV2 (251 LOC)\nManagedMaxProp (265 LOC)\nManagedEpidemic (233 LOC)", fc="#DCFCE7", ec=OI["green"])
    draw_box(ax, 0.54, 0.52, 0.40, 0.30, "Bespoke Subclass Pattern\n\nEnergyAwareEpidemicRouter (394 LOC)\nMust copy-paste beacons, ACKs,\nqueues, and rules per protocol", fc="#FFEDD5", ec=OI["vermillion"])
    draw_box(ax, 0.06, 0.16, 0.88, 0.22, "Shared DtnmaAgent Core (2,095 LOC across 7 files) + 12-line rules_vdtn.txt\nRecovers ~97% of hand-coded EnergyAwareEpidemic throughput (+3.14/+3.39 pp) across ALL 4 protocols at 3% rule LOC", fc="#EFF6FF", ec=OI["blue"], bold=True)
    draw_arrow(ax, 0.26, 0.52, 0.35, 0.38, "Delegates hooks")
    save_fig(fig, "fig10_adapter_composition")

    # Fig 11: Dissemination Modes
    fig, ax = make_diagram_canvas("Figure 11: Remote Dissemination Costs & Timescales (v1.0 & Exploratory)")
    modes = [
        ("M1-F (Legacy Flood, Confirmatory)", "120 separate bundles flooded", "118.7 kJ  |  13.5/126 alive  |  T90=4292 s", "#FEE2E2", OI["vermillion"]),
        ("M1-U (Unicast, Confirmatory)", "120 queued unicast bundles", "2.51 kJ  |  90.7/126 alive  |  T90=3336 s (389.1 stale)", "#FFEDD5", OI["orange"]),
        ("M1-F-Group (Group Flood, Exploratory)", "1 shared wave bundle flooded", "201.6 J  |  99.5/126 alive  |  T50=483 s / T90=841 s", "#EFF6FF", OI["blue"]),
        ("M2 (Local SRL + 0.25 Wave, Confirmatory)", "30s local triggers + guard wave", "2.58 kJ  |  83.8/126 alive  |  0.0 EvA Stale  |  +19.2 pp EvC", "#DCFCE7", OI["green"]),
    ]
    for i, (mt, mc, ms, fc, ec) in enumerate(modes):
        y = 0.70 - i * 0.175
        draw_box(ax, 0.07, y, 0.86, 0.12, f"{mt}   [{mc}]\n{ms}", fc=fc, ec=ec, fontsize=9.3)
    save_fig(fig, "fig11_dissemination_modes")

    # Fig 12: Optional M3 RL Architecture
    fig, ax = make_diagram_canvas("Figure 12: Optional Shielded CMDP RL Planner [EXPERIMENTAL - Lost to M2 in v1.0]")
    draw_box(ax, 0.05, 0.50, 0.21, 0.26, "20 Normalized Features\nphi(s)\nHazard indices {6,15,16,17}\n16B beacon gradients", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.30, 0.50, 0.22, 0.26, "Linear Q-Critic\nQ(s,a) = wReward[a] . phi\nalpha=0.15, gamma=0.90\nlambda = 0.0 (retired)", fc="#FEF3C7", ec=OI["orange"])
    draw_box(ax, 0.56, 0.50, 0.20, 0.26, "Safety Shield Gate\nepsilon-greedy constrained\nto mask[a] == true\n(ZERO critical lost)", fc="#DCFCE7", ec=OI["green"])
    draw_box(ax, 0.79, 0.50, 0.17, 0.26, "648B Gossip\nSymmetric merge\nw_new = mix(w_i,w_j)\non contact", fc="#F5F3FF", ec=OI["purple"])
    draw_arrow(ax, 0.26, 0.63, 0.30, 0.63)
    draw_arrow(ax, 0.52, 0.63, 0.56, 0.63)
    draw_arrow(ax, 0.76, 0.63, 0.79, 0.63)
    draw_box(ax, 0.08, 0.16, 0.84, 0.18, "Honest v1.0 Empirical Note: M3 preserves 0 critical loss via Safety Shield yet trails hand-written SRL (M2)\non stress bulk (63% vs 66%), survival (76.0 vs 83.8), and benign bulk (80% vs 82%). Status: Implemented, experimental.", fc="#FFF7ED", ec=OI["vermillion"], bold=True)
    save_fig(fig, "fig12_m3_rl_architecture")

    # Fig 13: Codebase DAG
    fig, ax = make_diagram_canvas("Figure 13: ADTNM Package & Class Dependency DAG (Zero Circular Imports)")
    draw_box(ax, 0.06, 0.72, 0.88, 0.13, "Value Objects & Enums:  DtnmaConfig (48 keys)  |  ControlAction  |  DtnmaPolicyProfile (P0-P7)  |  DtnmaContactBeacon", fc="#F8FAFC", ec=OI["slate"])
    draw_box(ax, 0.06, 0.51, 0.26, 0.14, "Rule AST\nPredicateExpression\n-> StateBasedRule\n-> DtnmaRuleParser", fc="#EFF6FF", ec=OI["sky"])
    draw_box(ax, 0.37, 0.51, 0.26, 0.14, "Sensing & Guard\nEddTelemetrySampler\nDtnmaSafetyShield", fc="#FEF3C7", ec=OI["orange"])
    draw_box(ax, 0.68, 0.51, 0.26, 0.14, "Experimental Learner\nShieldedCmdpRlAgent\n(Optional M3)", fc="#F5F3FF", ec=OI["purple"])
    draw_box(ax, 0.22, 0.27, 0.56, 0.16, "Orchestrator Core:  DtnmaAgent  <-->  DtnmaManagedRouter Interface", fc="#DCFCE7", ec=OI["green"], bold=True)
    draw_box(ax, 0.06, 0.07, 0.42, 0.13, "Substrates: Managed*Router (4)  |  DtnmaRouter (M0b..M3)", fc="#EFF6FF", ec=OI["blue"])
    draw_box(ax, 0.52, 0.07, 0.42, 0.13, "Reports: Audit, RlMetrics, RecoveryTimeline, Decision", fc="#FFEDD5", ec=OI["vermillion"])
    draw_arrow(ax, 0.19, 0.51, 0.35, 0.43)
    draw_arrow(ax, 0.50, 0.51, 0.50, 0.43)
    draw_arrow(ax, 0.81, 0.51, 0.65, 0.43)
    draw_arrow(ax, 0.40, 0.27, 0.27, 0.20)
    draw_arrow(ax, 0.60, 0.27, 0.73, 0.20)
    save_fig(fig, "fig13_codebase_dag")

    # Fig 14: Seed Governance
    fig, ax = make_diagram_canvas("Figure 14: Seed Isolation & Anti-Leakage Governance (SEED_ROLES.md)")
    draw_box(ax, 0.04, 0.46, 0.26, 0.30, "Stressor Calibration\nSeeds: 4001..4003\nUsed solely on M0a/M0b\nto calibrate Events A/B/C\n(Never in inference)", fc="#F1F5F9", ec=OI["slate"])
    draw_box(ax, 0.35, 0.46, 0.30, 0.30, "Confirmatory Held-Out [FROZEN]\nTag: v1.0-tnsm-freeze (ca5d9ac)\nSeeds: 7103..7207 (n=11 primes)\n715 Runs, Holm m=4 Corrected", fc="#DCFCE7", ec=OI["green"], bold=True)
    draw_box(ax, 0.70, 0.46, 0.26, 0.30, "Post-Freeze Exploratory\nv1.0-exp-controls\nSeeds: 7211..7307 (n=11 primes)\n176 Runs, Unadjusted p_unadj\n(M0e-20/25, M2-Local, M1-F-Grp)", fc="#FEF3C7", ec=OI["orange"])
    draw_arrow(ax, 0.30, 0.61, 0.35, 0.61, "Freeze")
    draw_arrow(ax, 0.65, 0.61, 0.70, 0.61, "Disjoint")
    draw_box(ax, 0.14, 0.14, 0.72, 0.16, "Regression Check on d4a159f (seeds 7103, 7109): 6/6 MsgStats SHA-256 & 6/6 Timeline SHA-256 identical to freeze", fc="#EFF6FF", ec=OI["blue"])
    save_fig(fig, "fig14_seed_governance")


# ---------------------------------------------------------------------------
# Quantitative Plots 15-21 (from provenance_ledger.json & raw logs)
# ---------------------------------------------------------------------------
def build_all_plots() -> None:
    ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
    conf = ledger["confirmatory_v1_0"]
    exp = ledger["exploratory_v1_0_controls"]
    inv = json.loads((GUIDE_DIR / "data" / "code_inventory.json").read_text(encoding="utf-8"))

    # Fig 15: CompA 4 Regimes x 4 Substrates Critical & Bulk Delivery
    fig, axes = plt.subplots(1, 2, figsize=(12.2, 4.8), sharey=True)
    regimes = ["energy_blackout", "vdtn_overload", "ntn_space", "benign"]
    rlabels = ["Blackout", "Overload", "NTN Space", "Benign"]
    routers = [
        ("SprayAndWaitRouter", "ManagedSprayAndWaitRouter", "SnW"),
        ("ProphetV2Router", "ManagedProphetV2Router", "PRoPHETv2"),
        ("MaxPropRouter", "ManagedMaxPropRouter", "MaxProp"),
        ("EpidemicRouter", "ManagedEpidemicRouter", "Epidemic"),
    ]
    x = np.arange(len(regimes))
    width = 0.19
    for idx, (stk, mng, lbl) in enumerate(routers):
        st_cr = [100 * conf["compA"]["regimes"][r]["routers"][stk]["critical_delivery_prob"]["mean"] for r in regimes]
        mn_cr = [100 * conf["compA"]["regimes"][r]["routers"][mng]["critical_delivery_prob"]["mean"] for r in regimes]
        st_bk = [100 * conf["compA"]["regimes"][r]["routers"][stk]["bulk_delivery_prob"]["mean"] for r in regimes]
        mn_bk = [100 * conf["compA"]["regimes"][r]["routers"][mng]["bulk_delivery_prob"]["mean"] for r in regimes]
        off = (idx - 1.5) * width
        cols = [OI["blue"], OI["green"], OI["orange"], OI["purple"]]
        axes[0].bar(x + off, st_cr, width * 0.48, color=cols[idx], alpha=0.35, edgecolor=cols[idx], hatch="//", label=f"{lbl} Stock" if idx == 0 else None)
        axes[0].bar(x + off + width * 0.48, mn_cr, width * 0.48, color=cols[idx], alpha=0.92, edgecolor="black", linewidth=0.5, label=f"{lbl} Managed")
        axes[1].bar(x + off, st_bk, width * 0.48, color=cols[idx], alpha=0.35, edgecolor=cols[idx], hatch="//")
        axes[1].bar(x + off + width * 0.48, mn_bk, width * 0.48, color=cols[idx], alpha=0.92, edgecolor="black", linewidth=0.5)
    axes[0].set_title("Critical Delivery (%) — 0 Un-ACKed Loss Managed", fontweight="bold")
    axes[1].set_title("Bulk Delivery (%) — Preserved / Rescued", fontweight="bold")
    for a in axes:
        a.set_xticks(x)
        a.set_xticklabels(rlabels)
        a.set_ylim(0, 108)
        a.grid(axis="y", ls=":", alpha=0.5)
    axes[0].set_ylabel("Delivery Ratio (%)")
    axes[0].legend(fontsize=8, loc="lower left", ncol=2)
    fig.suptitle("Figure 15 [CONFIRMATORY v1.0-tnsm-freeze, 7103..7207]: Managed vs. Stock Across 4 Regimes", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    save_fig(fig, "fig15_compA_regimes")

    # Fig 16: Tiers Survival Bar + Raw Seed 7103 Blackout Trajectory
    fig, axes = plt.subplots(1, 2, figsize=(12.2, 4.6))
    tiers_list = ["TIER1_PROTECTION_ONLY", "TIER2_PROTECTION_INGRESS_ENERGY", "TIER3_FULL_WITH_REPLICA_CAPS"]
    t_lbls = ["Tier 1\n(Prot Only)", "Tier 2\n(+Ing/Energy)", "Tier 3\n(+Quota Caps)"]
    sub_names = ["SnW", "PRoPHETv2", "MaxProp", "Epidemic"]
    sub_cols = [OI["blue"], OI["green"], OI["orange"], OI["purple"]]
    xt = np.arange(3)
    for idx, sn in enumerate(sub_names):
        vals = [conf["compA"]["tiers"][sn][t]["alive_nodes_end"]["mean"] for t in tiers_list]
        errs = [conf["compA"]["tiers"][sn][t]["alive_nodes_end"]["ci95"] for t in tiers_list]
        axes[0].bar(xt + (idx - 1.5) * 0.19, vals, 0.18, yerr=errs, capsize=3, color=sub_cols[idx], label=sn, edgecolor="black", lw=0.5)
    axes[0].axhline(126, ls="--", color=OI["slate"], lw=1, label="Fleet (126)")
    axes[0].set_xticks(xt)
    axes[0].set_xticklabels(t_lbls)
    axes[0].set_ylabel("Surviving Nodes at 12h (Mean +/- 95% CI)")
    axes[0].set_title("A2 Tier Decomposition Across Substrates (n=11)", fontweight="bold")
    axes[0].legend(fontsize=8)
    axes[0].grid(axis="y", ls=":", alpha=0.5)

    # Panel B: Raw Seed 7103 timelines on Epidemic
    tl_specs = [
        ("Stock Epidemic", FINAL_DIR / "compA_energy_blackout" / "CompA_EnergyBlackout_EpidemicRouter_7103_DtnmaRecoveryTimelineReport.txt", OI["vermillion"], "--"),
        ("Managed Tier 1", FINAL_DIR / "compA_tiers" / "CompA_Tiers_ManagedEpidemicRouter_TIER1_PROTECTION_ONLY_7103_DtnmaRecoveryTimelineReport.txt", OI["orange"], "-."),
        ("Managed Tier 2", FINAL_DIR / "compA_tiers" / "CompA_Tiers_ManagedEpidemicRouter_TIER2_PROTECTION_INGRESS_ENERGY_7103_DtnmaRecoveryTimelineReport.txt", OI["green"], "-"),
        ("EnergyAware (Java)", FINAL_DIR / "compA_energy_blackout" / "CompA_EnergyBlackout_EnergyAwareEpidemicRouter_7103_DtnmaRecoveryTimelineReport.txt", OI["blue"], ":"),
    ]
    for label, p, col, ls in tl_specs:
        ts, alive = [], []
        for line in p.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.startswith("#") or line.startswith("time"):
                continue
            parts = line.split("\t")
            try:
                ts.append(float(parts[0]) / 3600.0)
                alive.append(float(parts[1]))
            except (ValueError, IndexError):
                pass
        axes[1].plot(ts, alive, ls=ls, color=col, lw=2.0, label=label)
    axes[1].set_xlabel("Simulation Time (hours)")
    axes[1].set_ylabel("Alive Nodes (Seed 7103 Raw Trace)")
    axes[1].set_title("Epidemic Blackout Survival Curve (Seed 7103)", fontweight="bold")
    axes[1].legend(fontsize=8)
    axes[1].grid(True, ls=":", alpha=0.5)
    fig.suptitle("Figure 16 [CONFIRMATORY v1.0-tnsm-freeze]: Where Survival Gains Come From (Tier 2 > Tier 1)", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    save_fig(fig, "fig16_compA_tiers_blackout")

    # Fig 17: A3 LOC Efficiency
    loc = conf["a3"]["loc"]
    fig, ax = plt.subplots(figsize=(9.5, 4.2))
    labels = ["Shared Core\n(7 Engine Files)", "Hand-Coded\nEnergyAwareEpidemic", "Mean Thin Adapter\n(per Router)", "Declarative Policy\n(rules_vdtn.txt)"]
    vals = [loc["core_total"], loc["handcoded_energy_aware_epidemic"], loc["adapter_mean"], loc["rules_vdtn_noncomment"]]
    cols = [OI["blue"], OI["vermillion"], OI["sky"], OI["green"]]
    bars = ax.barh(labels[::-1], vals[::-1], color=cols[::-1], edgecolor="black", lw=0.6)
    for b, v in zip(bars, vals[::-1]):
        ax.text(v + 35, b.get_y() + b.get_height() / 2, f"{v:.1f} non-comment LOC" if v % 1 else f"{int(v)} LOC", va="center", fontsize=9.2, fontweight="bold")
    ax.set_xlim(0, 2550)
    ax.set_xlabel("Non-Comment Lines of Code / Rules")
    ax.set_title("Figure 17 [CONFIRMATORY v1.0 A3]: Architectural Code Reuse vs. Hand-Coded Router", fontweight="bold")
    ax.grid(axis="x", ls=":", alpha=0.5)
    fig.tight_layout()
    save_fig(fig, "fig17_a3_loc_efficiency")

    # Fig 18: Confirmatory H1-H4 Forest + Failed H2_orig
    fam = {h["id"]: h for h in conf["confirmatory_family"]}
    h2s = conf["h2_original_survival_ni"]
    fig, axes = plt.subplots(1, 2, figsize=(12.0, 4.4))
    hids = ["H4 (Benign Bulk, M2-M0c)", "H3 (Stress Surv, M2-M0b)", "H2 (EvC Bulk, M2-M1U)", "H2_orig FAILED Surv NI (M2-M1U)"]
    means = [fam["H4"]["mean_diff"], fam["H3"]["mean_diff"], fam["H2"]["mean_diff"], h2s["mean_diff"]]
    cis = [fam["H4"]["ci95"], fam["H3"]["ci95"], fam["H2"]["ci95"], h2s["ci95"]]
    pcols = [OI["green"], OI["green"], OI["green"], OI["vermillion"]]
    y_pos = np.arange(len(hids))
    axes[0].axvline(0, color="#334155", lw=1)
    axes[0].axvline(-5.0, color=OI["vermillion"], ls="--", lw=1, label="-5 pp NI margin")
    axes[0].errorbar(means, y_pos, xerr=cis, fmt="o", capsize=5, color="black", elinewidth=2)
    for y_i, m_i, c_i in zip(y_pos, means, pcols):
        axes[0].scatter([m_i], [y_i], color=c_i, s=80, zorder=5)
        axes[0].text(m_i, y_i + 0.18, f"{m_i:+.2f} pp", ha="center", fontsize=8.8, fontweight="bold", color=c_i)
    axes[0].set_yticks(y_pos)
    axes[0].set_yticklabels(hids, fontsize=9)
    axes[0].set_xlabel("Paired Difference +/- 95% CI (percentage points, df=10)")
    axes[0].set_title("Percentage-Point Endpoints + Failed H2_orig NI", fontweight="bold")
    axes[0].legend(fontsize=8, loc="lower right")
    axes[0].grid(axis="x", ls=":", alpha=0.5)

    # Panel B: H1 Stale Throttle
    h1 = fam["H1"]
    axes[1].bar(["M2 (Local SRL)", "M1-U (Remote Unicast)"], [h1["mean_a"], h1["mean_b"]], yerr=[0.0, h1["ci95"]], capsize=6, color=[OI["green"], OI["orange"]], edgecolor="black", lw=0.6)
    axes[1].set_ylabel("Stale Throttle (node-seconds / N)")
    axes[1].set_title(f"H1 Confirmed: M2=0.0 vs M1-U={h1['mean_b']:.1f}\n(p_holm={h1['p_holm']:.2e}, 11/11)", fontweight="bold")
    axes[1].grid(axis="y", ls=":", alpha=0.5)
    fig.suptitle("Figure 18 [CONFIRMATORY v1.0-tnsm-freeze, n=11]: Holm Family H1-H4 & Honest Failed NI Check", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.93])
    save_fig(fig, "fig18_compB_hypotheses")

    # Fig 19: CompB Modes Stress vs Event C & M3 Negative
    modes_b = ["M0a", "M0b", "M0c", "M0d", "M1-U", "M1-F", "M2", "M3"]
    fig, axes = plt.subplots(1, 2, figsize=(12.4, 4.6))
    xb = np.arange(len(modes_b))
    surv = [conf["compB"]["stress"][m]["alive_nodes_end"]["mean"] for m in modes_b]
    bk12 = [100 * conf["compB"]["stress"][m]["bulk_delivery_prob"]["mean"] for m in modes_b]
    evc = [100 * conf["compB"]["per_event"]["eventC"][m]["bulk_deliv"]["mean"] for m in modes_b]
    ben = [100 * conf["compB"]["benign"][m]["bulk_delivery_prob"]["mean"] for m in modes_b]
    axes[0].bar(xb - 0.19, surv, 0.36, color=OI["blue"], label="Stress Alive Nodes (/126)")
    axes[0].bar(xb + 0.19, bk12, 0.36, color=OI["sky"], label="Stress 12h Bulk (%)")
    axes[0].set_xticks(xb); axes[0].set_xticklabels(modes_b, rotation=25)
    axes[0].set_title("Whole-Mission 12h Stress (Alive & Bulk)", fontweight="bold")
    axes[0].legend(fontsize=8); axes[0].grid(axis="y", ls=":", alpha=0.5)

    axes[1].bar(xb - 0.19, evc, 0.36, color=OI["green"], label="Stress Event C Bulk (%)")
    axes[1].bar(xb + 0.19, ben, 0.36, color=OI["orange"], label="Benign 12h Bulk (%)")
    axes[1].set_xticks(xb); axes[1].set_xticklabels(modes_b, rotation=25)
    axes[1].set_title("Event C Cohort vs Benign Throughput (M3 Trails M2)", fontweight="bold")
    axes[1].legend(fontsize=8); axes[1].grid(axis="y", ls=":", alpha=0.5)
    fig.suptitle("Figure 19 [CONFIRMATORY v1.0-tnsm-freeze, 7103..7207]: CompB Mode Tradeoffs & M3 Negative Result", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.93])
    save_fig(fig, "fig19_compB_global_vs_event")

    # Fig 20: Exploratory Survival Attribution & Ctrl Energy
    fig, axes = plt.subplots(1, 2, figsize=(12.0, 4.5))
    cs = exp["tables"]["controls_stress"]
    gs = exp["tables"]["groupflood_stress"]
    m_exp = ["M0b", "M0e-20", "M2-LocalOnly", "M0e-25", "M2", "M1-U", "M1-F-Group"]
    alives = [cs[m]["alive_nodes_end"]["mean"] if m != "M1-F-Group" else gs["M1-F-Group"]["alive_nodes_end"]["mean"] for m in m_exp]
    acis = [cs[m]["alive_nodes_end"]["ci95"] if m != "M1-F-Group" else gs["M1-F-Group"]["alive_nodes_end"]["ci95"] for m in m_exp]
    ecols = [OI["slate"], OI["sky"], OI["blue"], OI["purple"], OI["green"], OI["orange"], OI["vermillion"]]
    bars = axes[0].bar(m_exp, alives, yerr=acis, capsize=4, color=ecols, edgecolor="black", lw=0.6)
    axes[0].set_xticklabels(m_exp, rotation=25, ha="right")
    axes[0].set_ylabel("Stress Alive Nodes at 12h (/126)")
    axes[0].set_title("Causal Survival Attribution (7211..7307)", fontweight="bold")
    axes[0].grid(axis="y", ls=":", alpha=0.5)
    for b, v in zip(bars, alives):
        axes[0].text(b.get_x() + b.get_width()/2, v + 3.2, f"{v:.1f}", ha="center", fontsize=8.3, fontweight="bold")

    ct_lbls = ["M1-F (Confirmatory\n120 Floods)", "M1-U\n(Unicast)", "M1-F-Group (Exploratory\nShared Flood)"]
    ct_vals = [conf["compB"]["stress"]["M1-F"]["total_ctrl_tx_J"]["mean"] / 1000.0,
               conf["compB"]["stress"]["M1-U"]["total_ctrl_tx_J"]["mean"] / 1000.0,
               gs["M1-F-Group"]["total_ctrl_tx_J"]["mean"] / 1000.0]
    axes[1].bar(ct_lbls, ct_vals, color=[OI["vermillion"], OI["orange"], OI["green"]], edgecolor="black", lw=0.6)
    axes[1].set_yscale("log")
    axes[1].set_ylabel("Control Tx Energy (kJ, log scale)")
    axes[1].set_title("Remote Flooding Cost: 118.7 kJ -> 201.6 J", fontweight="bold")
    axes[1].grid(axis="y", ls=":", alpha=0.5)
    for i, v in enumerate(ct_vals):
        axes[1].text(i, v * 1.18, f"{v*1000:.0f} J" if v < 1 else f"{v:.2f} kJ", ha="center", fontsize=8.8, fontweight="bold")
    fig.suptitle("Figure 20 [POST-FREEZE EXPLORATORY v1.0-exp-controls, 7211..7307]: Controls & Best-Case Remote", fontsize=12, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.93])
    save_fig(fig, "fig20_exp_survival_attribution")

    # Fig 21: Exploratory Wave Adoption CDF from Raw Seed 7211 Lines
    fig, ax = plt.subplots(figsize=(9.8, 4.6))
    t_mins = np.arange(0, 61, 1)
    for tag_line, lbl, col, ls in [
        (conf["worked_excerpts"]["wave_cdf_7211_m1u"][0], "M1-U Unicast Wave 1 (Seed 7211, T50=1131s)", OI["orange"], "--"),
        (conf["worked_excerpts"]["wave_cdf_7211_m1fg"][0], "M1-F-Group Wave 1 (Seed 7211, T50=441.5s)", OI["blue"], "-"),
    ]:
        bins_str = tag_line.split("\t")[-1]
        ys = [100.0 * float(x) for x in bins_str.split(",")]
        ax.plot(t_mins, ys, ls=ls, color=col, lw=2.3, label=lbl)
    ax.axvspan(0, 5.0, color=OI["vermillion"], alpha=0.14, label="Event A Surge Duration (300 s = 5 min)")
    ax.axhline(50, ls=":", color=OI["slate"], lw=1)
    ax.axhline(90, ls=":", color=OI["slate"], lw=1)
    ax.set_xlim(0, 45)
    ax.set_ylim(0, 105)
    ax.set_xlabel("Minutes After Wave W1 Issue (t0 = 3601 s)")
    ax.set_ylabel("Target Nodes Adopting Command (%)")
    ax.set_title("Figure 21 [POST-FREEZE EXPLORATORY, Seed 7211 Raw CDF]: Even Group-Flood Misses 300s Surge", fontweight="bold")
    ax.legend(fontsize=9, loc="lower right")
    ax.grid(True, ls=":", alpha=0.5)
    fig.tight_layout()
    save_fig(fig, "fig21_exp_wave_adoption_cdf")

    # Fig 22: 5-Stage Evaluation Methodology & 12h Multi-Timescale Disturbance Timeline
    fig = plt.figure(figsize=(12.2, 6.2))
    gs = fig.add_gridspec(2, 1, height_ratios=[1.0, 1.35], hspace=0.35)
    ax_top = fig.add_subplot(gs[0])
    ax_top.set_xlim(0, 1)
    ax_top.set_ylim(0, 1)
    ax_top.axis("off")
    ax_top.set_title("Figure 22: Experimental Evaluation Pipeline & 12-Hour Multi-Timescale Mission Timeline", fontsize=12.5, fontweight="bold", pad=8)
    stages = [
        ("1. Coprime Sweep", "Scenario .txt\nMatched Seeds\n(n=11 primes)", "#EFF6FF", OI["blue"]),
        ("2. Batch Execution", "parallel_batch_runner\ndt=0.5s, 43,200s\nwarmup=1000s", "#ECFDF5", OI["green"]),
        ("3. 4-Stream Reports", "MsgStats, RlMetrics\nAudit (16-col ARI)\nTimeline (300s)", "#F5F3FF", OI["purple"]),
        ("4. Offline Replay", "verify_audit_replay\n100.00% ARI &\nShield Bitmask", "#FEF3C7", OI["orange"]),
        ("5. Statistical Tests", "Paired t (df=10)\nHolm (m=4)\nTOST (+/-1 pp)", "#FFF7ED", OI["vermillion"]),
    ]
    for i, (st, sd, fc, ec) in enumerate(stages):
        x = 0.01 + i * 0.202
        draw_box(ax_top, x, 0.16, 0.152, 0.68, f"{st}\n\n{sd}", fc=fc, ec=ec, fontsize=8.2)
        if i < 4:
            draw_arrow(ax_top, x + 0.167, 0.50, x + 0.187, 0.50, color=OI["slate"])

    ax_bot = fig.add_subplot(gs[1])
    ax_bot.set_xlim(0, 12.2)
    ax_bot.set_ylim(0, 3.45)
    ax_bot.set_xlabel("Simulation Wall Time (hours; total run endTime = 43,200 s = 12.0 h)", fontsize=10)
    ax_bot.set_yticks([0.6, 1.6, 2.55])
    ax_bot.set_yticklabels(["Remote Waves\n(W1-W3)", "Stressor Windows\n& +1h Horizon", "Baseline &\nWarm-up"], fontsize=9, fontweight="bold")
    ax_bot.grid(axis="x", ls=":", alpha=0.5)

    # Track 3: Baseline & warmup
    ax_bot.broken_barh([(0, 1000/3600)], (2.25, 0.55), facecolors=OI["slate"], alpha=0.45)
    ax_bot.broken_barh([(1000/3600, 12.0 - 1000/3600)], (2.25, 0.55), facecolors=OI["sky"], alpha=0.28)
    ax_bot.text(6.2, 2.52, "Steady Mixed Workload: BULK_ (14-20s), TELEM_NORM_ (30-45s), CRITICAL_FAULT_ (90-140s)", ha="center", va="center", fontsize=8.3)
    ax_bot.text(0.14, 2.92, "Warm-up\n(0-1000s)", ha="center", va="bottom", fontsize=7.5, color=OI["slate"])

    # Track 2: Events A, B, C + 3600s Horizon
    events = [
        (1.0, 300/3600, "Event A\n(5m Burst)\n3600-3900s", OI["vermillion"]),
        (2.0, 0.5, "Event B\n(30m Shock)\n7200-9000s", OI["orange"]),
        (6.0, 2.0, "Event C (2h Shift)\n21600-28800s", OI["blue"]),
    ]
    for st_h, dur_h, lbl, col in events:
        ax_bot.broken_barh([(st_h, dur_h)], (1.30, 0.55), facecolors=col, alpha=0.88)
        ax_bot.broken_barh([(st_h + dur_h, 1.0)], (1.30, 0.55), facecolors=col, alpha=0.22, hatch="//", edgecolor=col)
        tx = st_h + dur_h / 2 if dur_h >= 0.4 else st_h + 0.05
        ax_bot.text(tx, 1.93, lbl, ha="center", va="bottom", fontsize=7.7, fontweight="bold", color=col)
    ax_bot.text(8.5, 1.57, "+1h Horizon", ha="center", va="center", fontsize=7.6, color=OI["blue"])
    ax_bot.axvline(9.0, ls="--", color=OI["green"], lw=1.2)
    ax_bot.text(10.55, 1.57, "No Right-Censoring\n(9.0h < 12.0h end)", ha="center", va="center", fontsize=8.2, color=OI["green"], fontweight="bold")

    # Track 1: Downlink waves W1, W2, W3
    for w_h, rel_h, wlbl in [(1.0, 3900/3600, "W1"), (2.0, 2.5, "W2"), (6.0, 8.0, "W3")]:
        ax_bot.scatter([w_h], [0.6], marker="^", s=95, color=OI["vermillion"], zorder=4)
        ax_bot.scatter([rel_h], [0.6], marker="v", s=95, color=OI["green"], zorder=4)
        ax_bot.text(w_h, 0.80, f"{wlbl} Throt", ha="center", fontsize=7.5, color=OI["vermillion"])
        ax_bot.text(rel_h + 0.18, 0.30, f"{wlbl} Nom", ha="center", fontsize=7.5, color=OI["green"])
    fig.tight_layout()
    save_fig(fig, "fig22_evaluation_methodology_timeline")


def main() -> None:
    build_all_diagrams()
    build_all_plots()
    svgs = sorted(SVG_DIR.glob("*.svg"))
    pngs = sorted(PNG_DIR.glob("*.png"))
    mmds = sorted(MMD_DIR.glob("*.mmd"))
    print(f"Generated: {len(mmds)} MMDs, {len(svgs)} SVGs, {len(pngs)} PNGs")
    assert len(mmds) == 15 and len(svgs) == 22 and len(pngs) == 22


if __name__ == "__main__":
    main()
