#!/usr/bin/env python3
"""
plot_compA_energy_depletion.py - Comparison A Energy Blackout Trajectory Plotter
Reads CompA_EnergyBlackout_*_DtnmaRecoveryTimelineReport.txt across matched runs
of {SprayAndWait, PRoPHETv2, MaxProp, Epidemic} (unmanaged vs composed +DTNMA)
and plots alive node count and fleet mean battery SoC across the 12-hour mission.
"""

import argparse
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


PAIRS = [
    ("SprayAndWaitRouter", "ManagedSprayAndWaitRouter", "Spray-and-Wait", "#1f77b4"),
    ("ProphetV2Router",    "ManagedProphetV2Router",    "PRoPHETv2",      "#ff7f0e"),
    ("MaxPropRouter",      "ManagedMaxPropRouter",      "MaxProp",        "#9467bd"),
    ("EpidemicRouter",     "ManagedEpidemicRouter",     "Epidemic",       "#2ca02c"),
]


def load_series(report_dir: Path, router_name: str):
    files = sorted(report_dir.glob(f"CompA_EnergyBlackout_{router_name}_*_DtnmaRecoveryTimelineReport.txt"))
    if not files:
        return None
    runs_t, runs_alive, runs_soc = [], [], []
    for f in files:
        ts, al, sc = [], [], []
        with open(f, "r", encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                s = line.strip()
                if not s or s.startswith("#"):
                    continue
                toks = s.split("\t")
                if len(toks) >= 3:
                    ts.append(float(toks[0]) / 3600.0)
                    al.append(float(toks[1]))
                    sc.append(float(toks[2]))
        if ts:
            runs_t.append(np.array(ts))
            runs_alive.append(np.array(al))
            runs_soc.append(np.array(sc))
    if not runs_t:
        return None
    return runs_t[0], np.mean(np.vstack(runs_alive), axis=0), np.mean(np.vstack(runs_soc), axis=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report-dir", default="n:/R&D/cog/the-one/reports/compA_energy_blackout")
    ap.add_argument("--out", default="n:/R&D/cog/ADTNM/figures/fig_pilot_compA_energy_depletion.png")
    args = ap.parse_args()

    rep_dir = Path(args.report_dir)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.2, 5.2))
    for ax in (ax1, ax2):
        ax.axvspan(2.0, 4.0, color="#fde2e2", alpha=0.55, label="Burst Overload (2-4 h)" if ax is ax1 else None)

    for unm, man, label, col in PAIRS:
        s_u = load_series(rep_dir, unm)
        s_m = load_series(rep_dir, man)
        if s_u is not None:
            t, al, sc = s_u
            ax1.plot(t, al, color=col, linestyle="--", linewidth=1.9, alpha=0.85, label=f"{label} (Stock)")
            ax2.plot(t, sc, color=col, linestyle="--", linewidth=1.9, alpha=0.85, label=f"{label} (Stock)")
        if s_m is not None:
            t, al, sc = s_m
            ax1.plot(t, al, color=col, linestyle="-", linewidth=2.3, label=f"{label} + DTNMA")
            ax2.plot(t, sc, color=col, linestyle="-", linewidth=2.3, label=f"{label} + DTNMA")

    ax1.set_title("A. Node Survival Trajectory (126 Total: 120 Field + 6 Mgr)", fontweight="bold", fontsize=11)
    ax1.set_xlabel("Simulation Time (hours)")
    ax1.set_ylabel("Alive Nodes")
    ax1.set_xlim(0.0, 12.0)
    ax1.set_ylim(0, 132)
    ax1.grid(True, alpha=0.25)
    ax1.legend(loc="lower left", fontsize=7.6, ncol=2, framealpha=0.92)

    ax2.set_title("B. Mean Fleet Battery SoC Over Time", fontweight="bold", fontsize=11)
    ax2.set_xlabel("Simulation Time (hours)")
    ax2.set_ylabel("Normalized Energy SoC (0..1)")
    ax2.set_xlim(0.0, 12.0)
    ax2.set_ylim(0.0, 1.05)
    ax2.axhline(0.20, color="#666666", linestyle=":", linewidth=1.1)
    ax2.text(0.25, 0.215, "Shield SoC=0.20 guard", fontsize=7.8, color="#555555", ha="left")
    ax2.grid(True, alpha=0.25)

    fig.suptitle("Comparison A Pilot (Matched Seed 4001): Router-Agnostic Survival & Energy Preservation",
                 fontsize=12.5, fontweight="bold", y=0.99)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    fig.savefig(out_path, dpi=220)
    plt.close(fig)
    print(f"Saved: {out_path}")


if __name__ == "__main__":
    main()
