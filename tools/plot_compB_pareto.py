#!/usr/bin/env python3
"""
plot_compB_pareto.py - Comparison B Benign-vs-Stress Pareto & Per-Event Plotter
Panel A: Benign bulk delivery (compB_benign) vs Stress survival alive_end
         (compB_multitimescale), highlighting ctrl_tx_J on M1-F vs M1-U.
Panel B: Per-event (A: 5m, B: 30m, C: 2h) Q>=0.90 stress and stale-throttle
         node-seconds across modes.
"""

import argparse
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


MODES = [
    ("SprayAndWaitRouter",        "M0a Stock SnW",     "#7f7f7f", "o", (6, -6)),
    ("DtnmaM0bStaticRouter",      "M0b Static",        "#8c564b", "s", (6, -6)),
    ("DtnmaM0cV1GuardRouter",     "M0c Pre-V1 Guard",  "#9467bd", "D", (-8, -14)),
    ("DtnmaM0dConservativeRouter","M0d Always-Cons.",  "#bcbd22", "v", (6, -4)),
    ("DtnmaM1RemoteRouter",       "M1-U Remote (L=8)", "#e377c2", "^", (-12, 8)),
    ("DtnmaM1FloodRouter",        "M1-F Remote Flood", "#d62728", "P", (6, 4)),
    ("DtnmaM2SrlRouter",          "M2 Autonomic SRL",  "#1f77b4", "*", (6, 4)),
    ("DtnmaM3RlRouter",           "M3 Autonomic RL",   "#2ca02c", "X", (6, -8)),
]


def load_mode_metrics(report_dir: Path):
    out = {}
    for tag, _, _, _, _ in MODES:
        files = sorted(report_dir.glob(f"*_{tag}_*_DtnmaRlMetricsReport.txt"))
        rows = []
        for f in files:
            d = {}
            with open(f, "r", encoding="utf-8", errors="ignore") as fh:
                for line in fh:
                    s = line.strip()
                    if ": " in s and not s.startswith("#"):
                        k, v = s.split(": ", 1)
                        try:
                            d[k.strip()] = float(v.strip())
                        except ValueError:
                            pass
            if d:
                rows.append(d)
        if rows:
            agg = {k: float(np.mean([r[k] for r in rows if k in r])) for k in rows[0]}
            out[tag] = agg
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stress-dir", default="n:/R&D/cog/the-one/reports/compB_multitimescale")
    ap.add_argument("--benign-dir", default="n:/R&D/cog/the-one/reports/compB_benign")
    ap.add_argument("--out", default="n:/R&D/cog/ADTNM/figures/fig_pilot_compB_pareto.png")
    args = ap.parse_args()

    stress = load_mode_metrics(Path(args.stress_dir))
    benign = load_mode_metrics(Path(args.benign_dir))
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.6, 5.4), gridspec_kw={"width_ratios": [1.15, 1.0]})

    # Panel A: Benign Bulk vs Stress Survival Pareto
    xs, ys = [], []
    for tag, label, col, marker, off in MODES:
        if tag not in stress or tag not in benign:
            continue
        x = stress[tag].get("alive_nodes_end", 0.0)
        y = 100.0 * benign[tag].get("bulk_delivery_prob", 0.0)
        xs.append(x)
        ys.append(y)
        ms = 140 if marker == "*" else 85
        ax1.scatter([x], [y], color=col, marker=marker, s=ms, zorder=5, edgecolors="#222222", linewidths=0.7, label=label)
        note = label
        if tag == "DtnmaM1FloodRouter":
            cj = stress[tag].get("total_ctrl_tx_J", 0.0)
            note = f"{label}\n(ctrl_tx={cj/1000.0:.1f}kJ)"
        ax1.annotate(note, xy=(x, y), xytext=off, textcoords="offset points", fontsize=8.0, fontweight="bold", color=col)

    ax1.set_title("A. Benign Bulk Delivery vs Stress Node Survival", fontweight="bold", fontsize=11)
    ax1.set_xlabel("Stress Scenario: Alive Nodes at End (/126)")
    ax1.set_ylabel("Benign Scenario: Bulk Delivery (%)")
    if xs and ys:
        ax1.set_xlim(max(0, min(xs) - 10), min(132, max(xs) + 18))
        ax1.set_ylim(max(0, min(ys) - 8), min(100, max(ys) + 6))
    ax1.grid(True, alpha=0.25)

    # Panel B: Per-Event Stale Throttle & Q>=0.90 Node-Seconds across key modes
    show_tags = [
        ("DtnmaM0bStaticRouter", "M0b"),
        ("DtnmaM0cV1GuardRouter", "M0c"),
        ("DtnmaM1RemoteRouter", "M1-U"),
        ("DtnmaM1FloodRouter", "M1-F"),
        ("DtnmaM2SrlRouter", "M2"),
        ("DtnmaM3RlRouter", "M3"),
    ]
    present = [(t, s) for t, s in show_tags if t in stress]
    if present:
        x = np.arange(len(present))
        w = 0.26
        q90_a = [stress[t].get("eventA_nodesec_q90", 0.0) for t, _ in present]
        stale_a = [stress[t].get("eventA_stale_throttle_nodesec", 0.0) for t, _ in present]
        stale_b = [stress[t].get("eventB_stale_throttle_nodesec", 0.0) for t, _ in present]

        ax2.bar(x - w, q90_a, width=w, color="#1f77b4", alpha=0.88, label="Event A (5m): Q>=0.90 node-sec/N")
        ax2.bar(x,     stale_a, width=w, color="#ff7f0e", alpha=0.88, label="Event A+H: Stale throttle node-sec/N")
        ax2.bar(x + w, stale_b, width=w, color="#d62728", alpha=0.82, label="Event B+H: Stale throttle node-sec/N")
        ax2.set_xticks(x)
        ax2.set_xticklabels([s for _, s in present], fontsize=9.0, fontweight="bold")
        ax2.set_ylabel("Node-Seconds per Node (s)")
        ax2.set_title("B. Congestion Exposure vs Late Stale Throttling", fontweight="bold", fontsize=11)
        ax2.legend(loc="upper right", fontsize=8.0, framealpha=0.92)
        ax2.grid(True, axis="y", alpha=0.25)

    fig.suptitle("Comparison B Pilot (Seed 4001): Two-Scenario Pareto & Stale-Throttle Cost",
                 fontsize=12.5, fontweight="bold", y=0.99)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    fig.savefig(out_path, dpi=220)
    plt.close(fig)
    print(f"Saved: {out_path}")


if __name__ == "__main__":
    main()
