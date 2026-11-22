#!/usr/bin/env python3
"""
plot_adaptation_trace.py
Parses DtnmaAuditReport logs from a non-stationary VDTN congestion-shock sweep
and generates a 3-panel adaptation timeline:
  1. Mean Primal-Dual Multiplier lambda(t)
  2. Mean Executed Spray Quota L(t)
  3. Safety Shield Intervention Rate (%)
with Pre-Shock (0-2h), Burst Shock (2-4h), and Recovery (4-6h) regime shading.
"""
import argparse
import glob
import os
import sys
from pathlib import Path
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

PROFILE_QUOTA_MAP = {
    "SAFE_EMERGENCY_HOLD": 1,
    "SAFE_THROTTLE_STRICT": 2,
    "BALANCED_CONSERVATIVE": 4,
    "BALANCED_AGILE_FOCUS": 6,
    "STANDARD_SPRAY_FOCUS": 6,
    "EXPLORE_AGILE_SPRAY": 8,
    "HIGH_SPRAY_BACKPRESSURE": 10,
    "HIGH_SPRAY_OPEN": 12,
}

HIGH_SPRAY_PROFILES = {
    "EXPLORE_AGILE_SPRAY",
    "HIGH_SPRAY_BACKPRESSURE",
    "HIGH_SPRAY_OPEN",
}


def load_audit_files(audit_dir: Path, router: str) -> pd.DataFrame:
    pattern = str(audit_dir / f"*{router}*_DtnmaAuditReport.txt")
    files = sorted(glob.glob(pattern))
    if not files:
        return pd.DataFrame()

    frames = []
    for fpath in files:
        rows = []
        with open(fpath, "r", encoding="utf-8") as f:
            for line in f:
                if not line.strip() or line.startswith("#"):
                    continue
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 8:
                    continue
                try:
                    t = float(parts[0])
                    exec_act = parts[4].strip()
                    shield_override = 1.0 if parts[5].strip() == "OVERRIDE" else 0.0
                    lam = float(parts[7])
                    quota = PROFILE_QUOTA_MAP.get(exec_act, 6)
                    high_spray = 100.0 if exec_act in HIGH_SPRAY_PROFILES else 0.0
                    rows.append((t, quota, shield_override, lam, high_spray))
                except ValueError:
                    continue
        if rows:
            df = pd.DataFrame(rows, columns=["time_sec", "quota", "override", "lambda", "high_spray"])
            df["source_file"] = os.path.basename(fpath)
            frames.append(df)

    if not frames:
        return pd.DataFrame()
    return pd.concat(frames, ignore_index=True)


def main():
    parser = argparse.ArgumentParser(description="Plot online RL/Shield adaptation trace from DtnmaAuditReport")
    parser.add_argument("--audit-dir", default="reports/phase1_shock", help="Directory containing *_DtnmaAuditReport.txt files")
    parser.add_argument("--router", default="DtnmaGossipAckRLRouter", help="Target router name filter")
    parser.add_argument("--bin-sec", type=int, default=300, help="Aggregation bin width in seconds (default: 300s)")
    parser.add_argument("--out", default="plots/phase1_shock/adaptation_trace.png", help="Output image path")
    args = parser.parse_args()

    audit_dir = Path(args.audit_dir)
    df = load_audit_files(audit_dir, args.router)
    if df.empty:
        print(f"ERROR: No matching audit logs for router '{args.router}' in {audit_dir}")
        sys.exit(1)

    df["bin_sec"] = (df["time_sec"] // args.bin_sec) * args.bin_sec
    df["time_hr"] = (df["bin_sec"] + args.bin_sec * 0.5) / 3600.0

    # Aggregate across runs/nodes per bin
    per_run = df.groupby(["source_file", "time_hr"], as_index=False).agg(
        lam_mean=("lambda", "mean"),
        high_spray_mean=("high_spray", "mean"),
        quota_mean=("quota", "mean"),
        shield_pct=("override", lambda s: 100.0 * s.mean()),
    )

    stats = per_run.groupby("time_hr", as_index=False).agg(
        lam_mean=("lam_mean", "mean"),
        lam_std=("lam_mean", "std"),
        high_spray_mean=("high_spray_mean", "mean"),
        high_spray_std=("high_spray_mean", "std"),
        quota_mean=("quota_mean", "mean"),
        quota_std=("quota_mean", "std"),
        shield_mean=("shield_pct", "mean"),
        shield_std=("shield_pct", "std"),
    ).fillna(0.0)

    single_critic_mode = (stats["lam_mean"].max() < 1e-6)

    # Print numerical phase summaries
    def phase_summary(lo_hr, hi_hr, name):
        sub = per_run[(per_run["time_hr"] >= lo_hr) & (per_run["time_hr"] < hi_hr)]
        if sub.empty:
            return
        if single_critic_mode:
            print(
                f"  {name:<18}: high_spray_P5_P7={sub['high_spray_mean'].mean():5.2f}% | "
                f"spray_quota L={sub['quota_mean'].mean():.2f} | "
                f"shield_override={sub['shield_pct'].mean():.2f}%"
            )
        else:
            print(
                f"  {name:<18}: lambda={sub['lam_mean'].mean():.4f} | "
                f"spray_quota L={sub['quota_mean'].mean():.2f} | "
                f"shield_override={sub['shield_pct'].mean():.2f}%"
            )

    print("=" * 78)
    print(f"ONLINE ADAPTATION TRACE SUMMARY ({args.router}, files={df['source_file'].nunique()})")
    phase_summary(0.0, 2.0, "Pre-Shock (0-2h)")
    phase_summary(2.0, 4.0, "Burst Shock (2-4h)")
    phase_summary(4.0, 6.1, "Recovery (4-6h)")
    if single_critic_mode:
        print("  Note: Single-critic RL mode detected (lambda retired at 0.0); Panel 1 displays")
        print("        Active High-Spray Profile Share (P5-P7, %) over time.")
    else:
        print("  Note: Dual multiplier lambda(t) acts as an integral congestion accumulator and")
        print("        lags past t=4h before residual backlog drains during recovery.")
    print("=" * 78)

    fig, axes = plt.subplots(3, 1, figsize=(11, 10), sharex=True)
    plt.subplots_adjust(hspace=0.22)

    panel1 = (
        (axes[0], "high_spray_mean", "high_spray_std", "High-Spray Share P5-P7 (%)", "#8c564b", "Active High-Spray Profile Allocation (P5-P7) Under Congestion Shock")
        if single_critic_mode
        else (axes[0], "lam_mean", "lam_std", "Mean Dual Variable $\\lambda(t)$", "#d62728", "Primal-Dual Multiplier (Integral Congestion Lag Response)")
    )

    panels = [
        panel1,
        (axes[1], "quota_mean", "quota_std", "Mean Spray Quota $L(t)$", "#1f77b4", "Active Spray Quota Adaptation Under Congestion Shock"),
        (axes[2], "shield_mean", "shield_std", "Shield Override Rate (%)", "#ff7f0e", "Safety Shield Intervention Rate Over Time"),
    ]

    for ax, mean_col, std_col, ylabel, color, title in panels:
        ax.axvspan(2.0, 4.0, color="#ffcccc", alpha=0.35, label="Burst Congestion Shock (2h-4h)")
        ax.axvline(2.0, color="#b22222", linestyle="--", linewidth=1.2)
        ax.axvline(4.0, color="#2e8b57", linestyle="--", linewidth=1.2)
        ax.plot(stats["time_hr"], stats[mean_col], color=color, linewidth=2.0, label=args.router)
        if df["source_file"].nunique() > 1:
            ax.fill_between(
                stats["time_hr"],
                stats[mean_col] - stats[std_col],
                stats[mean_col] + stats[std_col],
                color=color,
                alpha=0.18,
            )
        ax.set_ylabel(ylabel, fontsize=11)
        ax.set_title(title, fontsize=11, fontweight="bold")
        ax.grid(True, linestyle=":", alpha=0.6)
        ax.legend(loc="upper right", fontsize=9)

    axes[2].set_xlabel("Simulation Time (hours)", fontsize=12)
    axes[2].set_xlim(0.0, max(6.0, stats["time_hr"].max()))

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out_path, dpi=300, bbox_inches="tight")
    plt.close()
    print(f"Adaptation trace saved to: {out_path.resolve()}")


if __name__ == "__main__":
    main()
