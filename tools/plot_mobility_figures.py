"""
Publication-Ready Figure Generator for the Multi-Mobility DTN Campaign.

Consumes `summary_stats.json` produced by `analyze_mobility_campaign.py` and
generates four figures (both vector .pdf and 300-DPI .png):
  - fig1_env1_tradeoffs       : Env 1 Stress survival-vs-delivery Pareto + Benign parity
  - fig2_scalability          : N=60..500 bulk, critical/eviction, and CPU/memory scaling
  - fig3_cross_substrate_forest : Matched-seed Managed - Stock forest plot across 4 substrates
  - fig4_resource_overhead    : CPU/state/control-energy overhead trade-offs across regimes
"""

import argparse
import json
import math
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

# Global clean publication typography
plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["DejaVu Sans", "Arial", "Helvetica"],
    "font.size": 9.5,
    "axes.titlesize": 10.5,
    "axes.titleweight": "bold",
    "axes.labelsize": 9.5,
    "xtick.labelsize": 8.5,
    "ytick.labelsize": 8.5,
    "legend.fontsize": 8.0,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "figure.dpi": 150,
    "savefig.dpi": 300,
})

# Colorblind-safe Okabe-Ito inspired palette
C_MOB = "#0072B2"      # Strong Blue (Proposed M2-Mob)
C_SRL = "#E69F00"      # Orange (Reactive M2-SRL)
C_M1  = "#CC79A7"      # Reddish Purple (M1-Remote)
C_M0E = "#009E73"      # Bluish Green (M0e-25)
C_SNW = "#56B4E9"      # Sky Blue (Stock SnW)
C_CAS = "#D55E00"      # Vermilion (CongestionAwareSnW)
C_M0B = "#7F7F7F"      # Neutral Grey (M0b-Static)
C_ABL = "#4D4D4D"      # Dark Grey (Ablations)

SHORT_NAMES = {
    "DtnmaM2MobRouter": "M2-Mob (Joint)",
    "DtnmaM2SrlRouter": "M2-SRL (Reactive)",
    "DtnmaM1RemoteRouter": "M1-Remote",
    "DtnmaM0eEnergy25Router": "M0e-25 (Static Guard)",
    "DtnmaM0bStaticRouter": "M0b-Static",
    "DtnmaM2MobEnergyOnlyRouter": "Abl: Energy-Only",
    "DtnmaM2MobBufferOnlyRouter": "Abl: Buffer-Only",
    "DtnmaM2MobReplicaOnlyRouter": "Abl: Replica-Only",
    "SprayAndWaitRouter": "Stock SnW",
    "CongestionAwareSnWRouter": "CongestionAware SnW",
}


def save_both(fig, out_dir, stem):
    pdf_p = os.path.join(out_dir, f"{stem}.pdf")
    png_p = os.path.join(out_dir, f"{stem}.png")
    fig.savefig(pdf_p, bbox_inches="tight")
    fig.savefig(png_p, bbox_inches="tight", dpi=300)
    plt.close(fig)
    print(f"Saved {stem}.pdf and {stem}.png")


def plot_fig1_env1_tradeoffs(summary, out_dir):
    stress = summary["stages"]["env1_vdtn_stress"]
    benign = summary["stages"]["env1_vdtn_benign"]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12.2, 4.8), gridspec_kw={"width_ratios": [1.35, 1.0]})

    # Left panel: Stress survival vs bulk delivery with 95% CI crosshairs
    order = [
        ("DtnmaM2MobRouter", C_MOB, "o", 9.0, (8, 5)),
        ("DtnmaM2MobReplicaOnlyRouter", "#332288", "^", 7.0, (-88, 9)),
        ("DtnmaM2MobEnergyOnlyRouter", "#44AA99", "s", 7.0, (8, -11)),
        ("DtnmaM2MobBufferOnlyRouter", "#882255", "D", 6.8, (-90, 4)),
        ("DtnmaM2SrlRouter", C_SRL, "o", 8.0, (8, -10)),
        ("DtnmaM0eEnergy25Router", C_M0E, "P", 8.0, (8, -4)),
        ("DtnmaM1RemoteRouter", C_M1, "v", 7.5, (-64, 7)),
        ("DtnmaM0bStaticRouter", C_M0B, "X", 7.5, (8, -4)),
        ("SprayAndWaitRouter", C_SNW, "*", 9.5, (8, -6)),
        ("CongestionAwareSnWRouter", C_CAS, "d", 8.5, (-90, -16)),
    ]

    for r, col, mk, ms, (dx, dy) in order:
        st = stress[r]
        x = st["alive_nodes_end"]["mean"]
        xerr = st["alive_nodes_end"]["ci95"]
        y = st["bulk_delivery_prob"]["mean"] * 100.0
        yerr = st["bulk_delivery_prob"]["ci95"] * 100.0
        crit = st["critical_delivery_prob"]["mean"] * 100.0
        ax1.errorbar(
            x, y, xerr=xerr, yerr=yerr, fmt=mk, color=col, markersize=ms,
            ecolor=col, elinewidth=1.1, capsize=2.5, zorder=4 if r == "DtnmaM2MobRouter" else 3,
        )
        lbl = f"{SHORT_NAMES[r]}\n({crit:.1f}% crit)"
        weight = "bold" if r == "DtnmaM2MobRouter" else "normal"
        ax1.annotate(
            lbl, xy=(x, y), xytext=(dx, dy), textcoords="offset points",
            fontsize=7.4, fontweight=weight, color=col,
            arrowprops=dict(arrowstyle="-", color=col, lw=0.6, alpha=0.65) if abs(dx) > 10 or abs(dy) > 10 else None,
        )

    ax1.set_title("(a) Env 1 Stress: Node Survival vs. Bulk Delivery (95% CI)")
    ax1.set_xlabel("Surviving Nodes at End of 12 h Run (out of 126)")
    ax1.set_ylabel("Bulk Delivery Ratio (%)")
    ax1.set_xlim(48, 132)
    ax1.set_ylim(59.5, 79.8)
    ax1.grid(True, linestyle="--", alpha=0.3)

    # Right panel: Benign parity check (BulkDel & Event C Bulk)
    b_routers = [
        "DtnmaM2MobRouter",
        "DtnmaM2MobReplicaOnlyRouter",
        "DtnmaM2SrlRouter",
        "DtnmaM0eEnergy25Router",
        "SprayAndWaitRouter",
        "CongestionAwareSnWRouter",
        "DtnmaM1RemoteRouter",
    ]
    b_labels = [SHORT_NAMES[r].replace(" (Joint)", "").replace(" (Reactive)", "").replace(" (Static Guard)", "") for r in b_routers]
    y_pos = np.arange(len(b_routers))
    bulk_m = [benign[r]["bulk_delivery_prob"]["mean"] * 100.0 for r in b_routers]
    bulk_c = [benign[r]["bulk_delivery_prob"]["ci95"] * 100.0 for r in b_routers]
    evc_m = [benign[r]["eventC_bulk_deliv"]["mean"] * 100.0 for r in b_routers]
    evc_c = [benign[r]["eventC_bulk_deliv"]["ci95"] * 100.0 for r in b_routers]

    h = 0.34
    bars1 = ax2.barh(y_pos - h / 2, bulk_m, height=h, xerr=bulk_c, color=C_MOB, alpha=0.88, capsize=2.5, label="Overall Bulk (%)")
    bars2 = ax2.barh(y_pos + h / 2, evc_m, height=h, xerr=evc_c, color=C_SRL, alpha=0.85, capsize=2.5, label="Event C Overload (%)")

    bars1[0].set_edgecolor("black")
    bars1[0].set_linewidth(1.0)
    ax2.set_yticks(y_pos)
    ax2.set_yticklabels(b_labels)
    ax2.invert_yaxis()
    ax2.set_xlim(42, 86)
    ax2.set_xlabel("Delivery Ratio (%) [Benign 5.5-7.5 kJ]")
    ax2.set_title("(b) Env 1 Benign: Non-Interference Parity (126/126 Alive)")
    ax2.legend(loc="lower right", frameon=True, framealpha=0.92)
    ax2.grid(True, axis="x", linestyle="--", alpha=0.3)

    fig.tight_layout(w_pad=2.2)
    save_both(fig, out_dir, "fig1_env1_tradeoffs")


def plot_fig2_scalability(summary, out_dir):
    scales = [60, 126, 250, 500]
    st_keys = [f"scale_{n}" for n in scales]
    comps = [
        ("DtnmaM2MobRouter", "M2-Mob (Joint)", C_MOB, "o", "-"),
        ("DtnmaM2SrlRouter", "M2-SRL (Reactive)", C_SRL, "s", "--"),
        ("DtnmaM1RemoteRouter", "M1-Remote", C_M1, "^", "-."),
        ("SprayAndWaitRouter", "Stock SnW", C_SNW, "d", ":"),
    ]

    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(13.6, 4.2))
    x = np.arange(len(scales))

    # (a) Bulk delivery vs N
    for r, lbl, col, mk, ls in comps:
        means = [summary["stages"][sk][r]["bulk_delivery_prob"]["mean"] * 100.0 for sk in st_keys]
        cis = [summary["stages"][sk][r]["bulk_delivery_prob"]["ci95"] * 100.0 for sk in st_keys]
        ax1.errorbar(x, means, yerr=cis, color=col, marker=mk, linestyle=ls, linewidth=1.8, capsize=3, label=lbl)

    # Annotate M2-Mob vs M2-SRL delta & Holm status at each N
    f5 = summary["families"]["F5_Scalability_Joint"]
    for idx, sk in enumerate(st_keys):
        rec = next(r for r in f5 if r["stage"] == sk and r["comparator"] == "DtnmaM2SrlRouter" and r["metric"] == "bulk_delivery_prob")
        mob_y = summary["stages"][sk]["DtnmaM2MobRouter"]["bulk_delivery_prob"]["mean"] * 100.0
        sig = "ns" if rec["p_holm"] >= 0.05 else ("**" if rec["p_holm"] < 0.001 else "*")
        ax1.annotate(
            f"+{rec['mean_diff']*100:.2f}pp ({sig})",
            xy=(idx, mob_y), xytext=(0, 11), textcoords="offset points",
            ha="center", fontsize=7.6, fontweight="bold", color=C_MOB,
        )

    ax1.set_xticks(x)
    ax1.set_xticklabels([str(n) for n in scales])
    ax1.set_xlabel("Network Size N (hosts)")
    ax1.set_ylabel("Bulk Delivery Ratio (%)")
    ax1.set_title("(a) Bulk Throughput Scaling vs. N")
    ax1.set_ylim(69.5, 86.6)
    ax1.legend(loc="lower left", frameon=True, framealpha=0.92, fontsize=7.5)
    ax1.grid(True, linestyle="--", alpha=0.3)

    # (b) Critical delivery + loss count for stock SnW
    for r, lbl, col, mk, ls in comps:
        means = [summary["stages"][sk][r]["critical_delivery_prob"]["mean"] * 100.0 for sk in st_keys]
        cis = [summary["stages"][sk][r]["critical_delivery_prob"]["ci95"] * 100.0 for sk in st_keys]
        ax2.errorbar(x, means, yerr=cis, color=col, marker=mk, linestyle=ls, linewidth=1.8, capsize=3, label=lbl)

    for idx, sk in enumerate(st_keys):
        lost = summary["stages"][sk]["SprayAndWaitRouter"]["unique_critical_lost"]["mean"]
        snw_y = summary["stages"][sk]["SprayAndWaitRouter"]["critical_delivery_prob"]["mean"] * 100.0
        ax2.annotate(
            f"loss:{lost:.0f}", xy=(idx, snw_y), xytext=(0, -14), textcoords="offset points",
            ha="center", fontsize=7.3, color=C_SNW, fontweight="bold",
        )
    ax2.annotate("0.0 critical buffer losses\nfor all managed modes", xy=(1.5, 96.6), xytext=(1.5, 90.0),
                 ha="center", fontsize=7.6, color="#222222",
                 bbox=dict(boxstyle="round,pad=0.25", fc="#F4F6F8", ec="#BBBBBB", lw=0.7),
                 arrowprops=dict(arrowstyle="->", color="#666666", lw=0.8))

    ax2.set_xticks(x)
    ax2.set_xticklabels([str(n) for n in scales])
    ax2.set_xlabel("Network Size N (hosts)")
    ax2.set_ylabel("Critical Telemetry Delivery (%)")
    ax2.set_title("(b) Critical Protection Under Scaling")
    ax2.set_ylim(73.5, 99.5)
    ax2.grid(True, linestyle="--", alpha=0.3)

    # (c) Dual-axis CPU latency and memory footprint
    cpu_mob = [summary["stages"][sk]["DtnmaM2MobRouter"]["decision_cpu_us_mean"]["mean"] for sk in st_keys]
    cpu_srl = [summary["stages"][sk]["DtnmaM2SrlRouter"]["decision_cpu_us_mean"]["mean"] for sk in st_keys]
    mem_kb  = [summary["stages"][sk]["DtnmaM2MobRouter"]["agent_state_bytes_per_node"]["mean"] / 1024.0 for sk in st_keys]

    l1 = ax3.plot(x, cpu_mob, color=C_MOB, marker="o", linewidth=1.8, label="M2-Mob CPU (us)")
    l2 = ax3.plot(x, cpu_srl, color=C_SRL, marker="s", linestyle="--", linewidth=1.6, label="M2-SRL CPU (us)")
    ax3.set_xticks(x)
    ax3.set_xticklabels([str(n) for n in scales])
    ax3.set_xlabel("Network Size N (hosts)")
    ax3.set_ylabel("Decision Latency (us / decision)")
    ax3.set_ylim(10.0, 26.0)
    ax3.grid(True, linestyle="--", alpha=0.3)

    ax3_r = ax3.twinx()
    ax3_r.spines["top"].set_visible(False)
    l3 = ax3_r.plot(x, mem_kb, color=C_M0E, marker="^", linestyle="-.", linewidth=1.8, label="Agent State (KB/node)")
    ax3_r.set_ylabel("Agent State Footprint (KB / node)", color=C_M0E)
    ax3_r.tick_params(axis="y", labelcolor=C_M0E)
    ax3_r.set_ylim(10.0, 62.0)

    lines_all = l1 + l2 + l3
    labels_all = [l.get_label() for l in lines_all]
    ax3.legend(lines_all, labels_all, loc="upper left", frameon=True, framealpha=0.92, fontsize=7.5)
    ax3.set_title("(c) Per-Node Decision CPU & State Footprint")

    fig.tight_layout(w_pad=1.8)
    save_both(fig, out_dir, "fig2_scalability")


def plot_fig3_cross_substrate_forest(summary, out_dir):
    f4 = summary["families"]["F4_CrossSubstrate"]
    substrates = [
        ("ManagedSprayAndWaitRouter", "Spray-and-Wait\n[Quota L + Backpressure]"),
        ("ManagedProphetV2Router", "PRoPHETv2\n[Pred. Margin + Backpressure]"),
        ("ManagedMaxPropRouter", "MaxProp\n[Hop-Cap + Backpressure]"),
        ("ManagedEpidemicRouter", "Epidemic\n[Hop-Cap + Backpressure]"),
    ]
    panels = [
        ("critical_delivery_prob", "(a) Critical Delivery Gain (+pp)", 100.0, True),
        ("bulk_delivery_prob", "(b) Bulk Delivery Gain (+pp)", 100.0, False),
        ("alive_nodes_end", "(c) Survival Gain (+nodes)", 1.0, False),
    ]

    fig, axes = plt.subplots(1, 3, figsize=(13.2, 4.1), sharey=True)
    y_pos = np.arange(len(substrates))

    for ax, (metric, title, scale, show_lost) in zip(axes, panels):
        ax.axvline(0.0, color="#666666", linestyle="--", linewidth=0.9)
        for idx, (tgt, _lbl) in enumerate(substrates):
            rec = next(r for r in f4 if r["target"] == tgt and r["metric"] == metric)
            val = rec["mean_diff"] * scale
            ci = rec["ci95_diff"] * scale
            ax.errorbar(
                val, idx, xerr=ci, fmt="o", color=C_MOB, ecolor=C_MOB,
                markersize=7.5, elinewidth=1.8, capsize=3.5, zorder=4,
            )
            txt = f"{val:+.2f}" if scale == 1.0 else f"{val:+.2f} pp"
            if show_lost:
                lost_rec = next(r for r in f4 if r["target"] == tgt and r["metric"] == "unique_critical_lost")
                txt += f" (loss {lost_rec['mean_diff']:+.1f})"
            ax.annotate(
                txt, xy=(val, idx), xytext=(0, 10), textcoords="offset points",
                ha="center", fontsize=7.6, color="#1A1A1A", fontweight="bold",
            )
        ax.set_title(title)
        ax.grid(True, axis="x", linestyle="--", alpha=0.3)

    axes[0].set_yticks(y_pos)
    axes[0].set_yticklabels([lbl for _, lbl in substrates])
    axes[0].set_ylim(3.45, -0.58)
    axes[0].set_xlim(-5, 78)
    axes[0].set_xlabel("Matched-Seed Difference (Managed - Stock, 95% CI)")
    axes[1].set_xlim(-2, 31)
    axes[1].set_xlabel("Matched-Seed Difference (Managed - Stock, 95% CI)")
    axes[2].set_xlim(-4, 72)
    axes[2].set_xlabel("Matched-Seed Difference (Managed - Stock, 95% CI)")

    fig.suptitle("Cross-Substrate Local Autonomy (M2_MOBILITY_AWARE, No Downlink CTRL_UPDATE, n = 19 Seeds)", fontsize=10.8, fontweight="bold", y=1.02)
    fig.tight_layout(w_pad=1.6)
    save_both(fig, out_dir, "fig3_cross_substrate_forest")


def plot_fig4_resource_overhead(summary, out_dir):
    stress = summary["stages"]["env1_vdtn_stress"]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12.2, 4.4))

    # Left: Env 1 Stress survival vs CPU latency (bubble area scaled by control energy share)
    items = [
        ("DtnmaM2MobRouter", C_MOB, "o", (-86, 6)),
        ("DtnmaM2SrlRouter", C_SRL, "s", (-10, -19)),
        ("DtnmaM0eEnergy25Router", C_M0E, "P", (8, -4)),
        ("DtnmaM1RemoteRouter", C_M1, "v", (8, 3)),
        ("DtnmaM0bStaticRouter", C_M0B, "X", (8, -6)),
        ("DtnmaM2MobEnergyOnlyRouter", "#44AA99", "D", (8, -11)),
        ("DtnmaM2MobBufferOnlyRouter", "#882255", "^", (-86, -11)),
        ("DtnmaM2MobReplicaOnlyRouter", "#332288", "<", (8, 3)),
    ]
    for r, col, mk, (dx, dy) in items:
        cpu = stress[r]["decision_cpu_us_mean"]["mean"]
        alive = stress[r]["alive_nodes_end"]["mean"]
        alive_ci = stress[r]["alive_nodes_end"]["ci95"]
        ce = stress[r]["ctrl_energy_share"]["mean"] * 100.0
        ax1.errorbar(cpu, alive, yerr=alive_ci, fmt=mk, color=col, ecolor=col, markersize=8, capsize=2.5)
        ax1.annotate(
            f"{SHORT_NAMES[r]}\n(CtrlE={ce:.2f}%)",
            xy=(cpu, alive), xytext=(dx, dy), textcoords="offset points",
            fontsize=7.2, color=col, fontweight="bold" if r == "DtnmaM2MobRouter" else "normal",
        )
    ax1.set_xlabel("Mean Management Decision Latency (us / decision)")
    ax1.set_ylabel("Surviving Nodes in Env 1 Stress (out of 126)")
    ax1.set_title("(a) Env 1 Stress: Survival Gain vs. Decision Latency")
    ax1.set_xlim(1.0, 21.8)
    ax1.set_ylim(50, 110)
    ax1.grid(True, linestyle="--", alpha=0.3)

    # Right: Multi-environment deployability profile for M2-Mob
    envs = [
        ("env1_vdtn_stress", "Env 1\nStress"),
        ("env1_vdtn_benign", "Env 1\nBenign"),
        ("env2_workingday", "Env 2\nWorkingDay"),
        ("env3_disaster_relief", "Env 3\nDisaster"),
        ("cross_substrate", "Cross-Sub\n(SnW)"),
    ]
    x = np.arange(len(envs))
    cpu_vals = []
    mem_vals = []
    ce_vals = []
    for st, _lbl in envs:
        rname = "ManagedSprayAndWaitRouter" if st == "cross_substrate" else "DtnmaM2MobRouter"
        cpu_vals.append(summary["stages"][st][rname]["decision_cpu_us_mean"]["mean"])
        mem_vals.append(summary["stages"][st][rname]["agent_state_bytes_per_node"]["mean"] / 1024.0)
        ce_vals.append(summary["stages"][st][rname]["ctrl_energy_share"]["mean"] * 100.0)

    w = 0.36
    b1 = ax2.bar(x - w / 2, cpu_vals, width=w, color=C_MOB, alpha=0.88, label="Decision CPU (us)")
    b2 = ax2.bar(x + w / 2, mem_vals, width=w, color=C_M0E, alpha=0.85, label="Agent State (KB/node)")
    for i, ce in enumerate(ce_vals):
        top = max(cpu_vals[i], mem_vals[i])
        ax2.text(i, top + 0.8, f"CtrlE:\n{ce:.2f}%", ha="center", va="bottom", fontsize=7.2, color="#333333")

    ax2.set_xticks(x)
    ax2.set_xticklabels([lbl for _, lbl in envs])
    ax2.set_ylabel("CPU (us) / Memory State (KB per node)")
    ax2.set_ylim(0, 31)
    ax2.set_title("(b) M2-Mob Resource Footprint Across Operating Regimes")
    ax2.legend(loc="upper right", frameon=True, framealpha=0.92, fontsize=8.0)
    ax2.grid(True, axis="y", linestyle="--", alpha=0.3)

    fig.tight_layout(w_pad=2.0)
    save_both(fig, out_dir, "fig4_resource_overhead")


def main():
    parser = argparse.ArgumentParser(description="Generate publication figures from campaign summary JSON.")
    parser.add_argument("--summary-json", required=True, help="Path to summary_stats.json")
    parser.add_argument("--out-dir", required=True, help="Output directory for figures")
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    with open(args.summary_json, "r", encoding="utf-8") as fh:
        summary = json.load(fh)

    plot_fig1_env1_tradeoffs(summary, args.out_dir)
    plot_fig2_scalability(summary, args.out_dir)
    plot_fig3_cross_substrate_forest(summary, args.out_dir)
    plot_fig4_resource_overhead(summary, args.out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
