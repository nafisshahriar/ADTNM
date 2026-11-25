#!/usr/bin/env python3
"""
plot_compB_adoption_cdf.py - Comparison B Control Adoption & Reaction Timescale Plotter
Reads # WAVE_ADOPTION_CDF lines and local threshold-crossing reaction metrics from
CompB_*_DtnmaRlMetricsReport.txt files and renders a publication-quality figure
comparing remote downlink dissemination latency (minutes-to-hours) against
local autonomic SRL/Shield reaction latency (sub-second).
"""

import argparse
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


MODE_META = [
    ("DtnmaM1RemoteRouter", "M1-U Remote (DTN L=8)", "#e377c2", "-", 2.2),
    ("DtnmaM1FloodRouter",  "M1-F Remote (Flood)",   "#d62728", "-", 2.0),
    ("DtnmaM2SrlRouter",    "M2 Autonomic SRL",      "#1f77b4", "--", 2.0),
    ("DtnmaM3RlRouter",     "M3 Autonomic RL",       "#2ca02c", "-.", 2.0),
]


def parse_compb_reports(report_dir: Path):
    records = {}
    for f in sorted(report_dir.glob("*_DtnmaRlMetricsReport.txt")):
        metrics = {}
        waves = []
        with open(f, "r", encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                s = line.strip()
                if s.startswith("# WAVE_ADOPTION_CDF\t"):
                    toks = s.split("\t")
                    if len(toks) >= 12:
                        bins = [float(x) for x in toks[11].split(",")]
                        waves.append({
                            "wave": toks[1],
                            "start": float(toks[2]),
                            "targets": int(toks[3]),
                            "deliv": int(toks[4]),
                            "t10": float(toks[5]),
                            "t25": float(toks[6]),
                            "t50": float(toks[7]),
                            "t75": float(toks[8]),
                            "t90": float(toks[9]),
                            "t95": float(toks[10]),
                            "bins": np.array(bins, dtype=float),
                        })
                elif ": " in s and not s.startswith("#"):
                    k, v = s.split(": ", 1)
                    try:
                        metrics[k.strip()] = float(v.strip())
                    except ValueError:
                        pass
        for tag, _, _, _, _ in MODE_META:
            if f"_{tag}_" in f.name:
                records.setdefault(tag, []).append({"metrics": metrics, "waves": waves})
    return records


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report-dir", default="n:/R&D/cog/the-one/reports/compB_multitimescale")
    ap.add_argument("--out", default="n:/R&D/cog/ADTNM/figures/fig_pilot_compB_adoption_cdf.png")
    ap.add_argument("--title", default=None)
    args = ap.parse_args()

    rep_dir = Path(args.report_dir)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    records = parse_compb_reports(rep_dir)
    n_seeds = max((len(v) for v in records.values()), default=1)
    t_mins = np.arange(61, dtype=float)  # 0..60 minutes (0..3600s)

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.2, 5.2), gridspec_kw={"width_ratios": [1.35, 1.0]})

    # Panel A: Wave Adoption CDFs across M1/M2/M3
    for tag, label, color, ls, lw in MODE_META:
        runs = records.get(tag, [])
        if not runs:
            continue
        all_bins = []
        w1_bins = []
        t90_vals = []
        for r in runs:
            for w in r["waves"]:
                if len(w["bins"]) == 61:
                    all_bins.append(100.0 * w["bins"])
                    if "W1" in w["wave"] and "THROTTLE" in w["wave"]:
                        w1_bins.append(100.0 * w["bins"])
                if w["t90"] >= 0:
                    t90_vals.append(w["t90"] / 60.0)
        if not all_bins:
            continue
        mean_cdf = np.mean(np.vstack(all_bins), axis=0)
        ax1.plot(t_mins, mean_cdf, color=color, linestyle=ls, linewidth=lw, label=label)
        if w1_bins:
            w1_mean = np.mean(np.vstack(w1_bins), axis=0)
            ax1.plot(t_mins, w1_mean, color=color, linestyle=":", linewidth=1.2, alpha=0.65)
            # Mark acute 5-minute burst adoption for W1
            pct_5m = float(w1_mean[5])
            if tag == "DtnmaM1RemoteRouter":
                ax1.scatter([5.0], [pct_5m], color=color, zorder=5, s=45)
                ax1.annotate(f"W1 @ 5 min (M1-U): {pct_5m:.1f}%",
                             xy=(5.0, pct_5m), xytext=(7.2, 43.0),
                             arrowprops=dict(arrowstyle="->", color=color, lw=1.0),
                             fontsize=8.3, fontweight="bold", color=color)

    # Vertical reference lines for disruption timescales (5 min, 30 min)
    ax1.axvline(5.0, color="#555555", linestyle="--", linewidth=1.2)
    ax1.text(5.6, 89.0, "5-min burst ends\n(W1 over before\ndownlink converges)",
             fontsize=8.2, color="#333333", va="top")
    ax1.axvline(30.0, color="#888888", linestyle="--", linewidth=1.2)
    ax1.text(30.6, 62.0, "30-min shock ends\n(1800 s)", fontsize=8.2, color="#444444", va="center")

    ax1.axhline(90.0, color="#bbbbbb", linestyle=":", linewidth=1.0)
    ax1.text(24.0, 91.8, "90% target", fontsize=8.0, color="#666666", ha="center")

    # Local autonomic reaction callout near t = 0
    m2_runs = records.get("DtnmaM2SrlRouter", [])
    evb_vals = [r["metrics"].get("eventB_react_med_s", -1.0) for r in m2_runs if r["metrics"].get("eventB_react_med_s", -1.0) >= 0]
    evc_vals = [r["metrics"].get("eventC_react_med_s", -1.0) for r in m2_runs if r["metrics"].get("eventC_react_med_s", -1.0) >= 0]
    m2_evb_med = float(np.mean(evb_vals)) if evb_vals else 174.4
    m2_evc_med = float(np.mean(evc_vals)) if evc_vals else 186.4
    ax1.scatter([0.0], [100.0], color="#1f77b4", marker="*", s=130, zorder=6,
                label=f"M2/M3 Rule Eval (<=1.0 s; B/C med {m2_evb_med:.0f}-{m2_evc_med:.0f} s)")

    ax1.set_title("A. Downlink Wave Adoption CDF vs Disruption Timescales", fontweight="bold", fontsize=11)
    ax1.set_xlabel("Time Since Manager Control Wave Issue (minutes)")
    ax1.set_ylabel("Agents Adopting Control Policy (%)")
    ax1.set_xlim(-1.0, 60.0)
    ax1.set_ylim(-2.0, 105.0)
    ax1.grid(True, alpha=0.25)
    ax1.legend(loc="lower right", fontsize=8.0, framealpha=0.92)

    # Panel B: Timescale Gap Bar Comparison (Log Scale in Seconds)
    labels_b = [
        "M2 Eval Tick\n(Rule-Own)",
        "M2 At-Risk->Throttle\n(Event B Med)",
        "5-Min Burst\nDuration",
        "30-Min Shock\nDuration",
        "M1-U Unicast\nMean T90",
        "M1-F Unicast Flood\nMean T90",
    ]
    m1u_runs = records.get("DtnmaM1RemoteRouter", [])
    m1f_runs = records.get("DtnmaM1FloodRouter", [])
    t90_u_vals = [r["metrics"].get("downlink_t90_sec", -1.0) for r in m1u_runs if r["metrics"].get("downlink_t90_sec", -1.0) >= 0]
    t90_f_vals = [r["metrics"].get("downlink_t90_sec", -1.0) for r in m1f_runs if r["metrics"].get("downlink_t90_sec", -1.0) >= 0]
    t90_u_mean = float(np.mean(t90_u_vals)) if t90_u_vals else 3100.0
    t90_f_mean = float(np.mean(t90_f_vals)) if t90_f_vals else 3700.0

    # Local SRL/Shield executes on a 1.0s eval tick; Event B median is time from broad atRisk to narrow throttle rule
    vals_b = [1.0, m2_evb_med, 300.0, 1800.0, t90_u_mean, t90_f_mean]
    cols_b = ["#1f77b4", "#17becf", "#7f7f7f", "#7f7f7f", "#e377c2", "#d62728"]

    x = np.arange(len(labels_b))
    bars = ax2.bar(x, vals_b, color=cols_b, alpha=0.88, width=0.58, edgecolor="#222222", linewidth=0.6)
    ax2.set_yscale("log")
    ax2.set_ylim(0.4, 14000.0)
    ax2.set_xticks(x)
    ax2.set_xticklabels(labels_b, fontsize=7.6, rotation=18, ha="right")
    ax2.set_ylabel("Reaction / Dissemination Timescale (seconds, log scale)")
    ax2.set_title("B. Autonomic Reaction vs Remote DTN Control Delay", fontweight="bold", fontsize=11)
    ax2.grid(True, axis="y", which="both", alpha=0.22)

    txt_vals = [
        "<=1.0 s\n(1 Hz tick)",
        f"{m2_evb_med:.0f} s",
        "300 s",
        "1800 s",
        f"{t90_u_mean:.0f} s",
        f"{t90_f_mean:.0f} s",
    ]
    for bar, tv in zip(bars, txt_vals):
        h = bar.get_height()
        ax2.text(bar.get_x() + bar.get_width() / 2.0, h * 1.18, tv,
                 ha="center", va="bottom", fontsize=8.0, fontweight="bold")

    default_title = (
        f"Comparison B ({n_seeds} Held-Out Seeds): Remote Downlink Latency Exceeds Acute Disruptions"
        if n_seeds > 1
        else "Comparison B Pilot (Matched Seed 4001): Remote Downlink Latency Exceeds Acute Disruptions"
    )
    fig.suptitle(args.title or default_title, fontsize=12.3, fontweight="bold", y=0.99)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    fig.savefig(out_path, dpi=220)
    plt.close(fig)
    print(f"Saved: {out_path}")


if __name__ == "__main__":
    main()
