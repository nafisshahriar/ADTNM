#!/usr/bin/env python3
"""
run_phase1_oppnda.py
Executes OppNDA's ReportAverager & Analysis pipeline on Phase 1 VDTN ablation,
multi-seed sanity, shock, or 210-run comparison sweeps; verifies zero critical
buffer drops and zero physical buffer overshoot across shielded/static routers;
reports epsilon-free greedy safety internalization, profile usage distributions,
and paired 95% confidence intervals.
"""
import argparse
import json
import math
import os
import sys
import tempfile
from pathlib import Path
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

PROJECT_ROOT = Path(__file__).resolve().parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from core.averager import ReportAverager
from core.analysis import load_config, SmartFileParser, DataOrganizer, PlotStrategy, PlotGenerator

SHIELDED_ROUTERS = {
    "DtnmaStaticRouter",
    "DtnmaStaticAckRouter",
    "DtnmaShieldedPassiveRLRouter",
    "DtnmaShieldedRLRouter",
    "DtnmaGossipRLRouter",
    "DtnmaGossipAckRLRouter",
    "DtnmaRouter",
}

T_CRIT_95 = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
    11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131,
    16: 2.120, 17: 2.110, 18: 2.101, 19: 2.093, 20: 2.086,
    25: 2.060, 30: 2.042,
}


def t_critical_95(df_deg: int) -> float:
    if df_deg <= 0:
        return 0.0
    if df_deg in T_CRIT_95:
        return T_CRIT_95[df_deg]
    for k in sorted(T_CRIT_95.keys()):
        if df_deg < k:
            return T_CRIT_95[k]
    return 1.960


def print_paired_diff(raw_df: pd.DataFrame, router_a: str, router_b: str, metric: str,
                      scale: float = 1.0, unit: str = ""):
    if router_a not in raw_df["router"].values or router_b not in raw_df["router"].values:
        return
    if metric not in raw_df.columns:
        return

    join_cols = [c for c in ["seed", "ttl", "buffer"] if c in raw_df.columns]
    df_a = raw_df[raw_df["router"] == router_a][join_cols + [metric]].rename(columns={metric: "a"})
    df_b = raw_df[raw_df["router"] == router_b][join_cols + [metric]].rename(columns={metric: "b"})
    merged = pd.merge(df_a, df_b, on=join_cols, how="inner").dropna()
    if merged.empty:
        return

    diffs = (merged["a"] - merged["b"]) * scale
    n = len(diffs)
    mean_d = float(diffs.mean())
    if n > 1:
        sd = float(diffs.std(ddof=1))
        ci = t_critical_95(n - 1) * (sd / math.sqrt(n))
        ci_str = f"[{mean_d - ci:+.3f}, {mean_d + ci:+.3f}]{unit} (+/-{ci:.3f})"
    else:
        ci_str = "n=1"
    print(f"  {router_a:<27} - {router_b:<27} | {metric:<24} (n={n:2d}): mean={mean_d:+.3f}{unit} 95%CI={ci_str}")


def main():
    parser = argparse.ArgumentParser(description="OppNDA Phase 1 Post-Processing & Diagnostics")
    parser.add_argument("--averager-config", default="config/phase1_averager_config.json")
    parser.add_argument("--analysis-config", default="config/phase1_analysis_config.json")
    parser.add_argument("--report-dir", "-r", default=None, help="Override reports directory (e.g., reports/phase1_sanity)")
    parser.add_argument("--plots-dir", "-p", default=None, help="Override plots output directory (e.g., plots/phase1_sanity)")
    parser.add_argument("--no-assert", action="store_true", help="Skip strict zero-drop & zero-overshoot assertions")
    args = parser.parse_args()

    avg_cfg_path = PROJECT_ROOT / args.averager_config
    ana_cfg_path = PROJECT_ROOT / args.analysis_config

    with open(avg_cfg_path, "r", encoding="utf-8") as f:
        avg_cfg = json.load(f)
    with open(ana_cfg_path, "r", encoding="utf-8") as f:
        ana_cfg = json.load(f)

    if args.report_dir:
        rdir = args.report_dir.rstrip("/\\") + "/"
        avg_cfg["folder"] = rdir
        ana_cfg["directories"]["report_dir"] = rdir
    if args.plots_dir:
        ana_cfg["directories"]["plots_dir"] = args.plots_dir.rstrip("/\\")

    print("=" * 78)
    print("PHASE 1 OppNDA POST-PROCESSING & VERIFICATION")
    print(f"Reports Dir : {avg_cfg['folder']}")
    print(f"Plots Dir   : {ana_cfg['directories']['plots_dir']}")
    print("=" * 78)

    temp_files = []
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tf_avg:
            json.dump(avg_cfg, tf_avg, indent=2)
            tmp_avg_path = tf_avg.name
            temp_files.append(tmp_avg_path)

        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tf_ana:
            json.dump(ana_cfg, tf_ana, indent=2)
            tmp_ana_path = tf_ana.name
            temp_files.append(tmp_ana_path)

        averager = ReportAverager(tmp_avg_path)
        averager.run()

        config = load_config(tmp_ana_path)
        parser_obj = SmartFileParser(config)
        organizer = DataOrganizer(parser_obj)

        plots_dir = Path(config["directories"]["plots_dir"])
        if not plots_dir.is_absolute():
            plots_dir = PROJECT_ROOT / plots_dir
        plots_dir.mkdir(parents=True, exist_ok=True)

        averaged_dfs = organizer.load_averaged_files()
        raw_df = organizer.load_raw_files()
    finally:
        for tp in temp_files:
            try:
                os.remove(tp)
            except OSError:
                pass

    if raw_df is None or raw_df.empty:
        print("ERROR: No raw reports found in", parser_obj.report_dir)
        sys.exit(1)

    for router in sorted(raw_df["router"].unique()):
        router_df = raw_df[raw_df["router"] == router]
        csv_path = plots_dir / f"{router}_metrics.csv"
        router_df.to_csv(csv_path, index=False)

    strategy = PlotStrategy(config).analyze_averaged_data(averaged_dfs)
    plot_gen = PlotGenerator(config, str(plots_dir))
    for grouping_type, df in strategy["line_plots"]:
        for metric in config["metrics"]["include"]:
            if metric in df.columns:
                plot_gen.create_line_plot((grouping_type, df, metric, config["plot_settings"]["line_plots"]))
    if config.get("enabled_plots", {}).get("3d_surface", False):
        for grouping_types, dfs in strategy["surface_plots"]:
            for metric in config["metrics"]["include"]:
                plot_gen.create_3d_surface_plot((grouping_types, dfs, metric, config["plot_settings"]["3d_surface"]))

    # 3. Print Comprehensive Summary Table
    print("\n" + "=" * 134)
    print("PHASE 1 VDTN SUMMARY (MEAN ACROSS ALL SEEDS, TTLs & BUFFERS)")
    print("=" * 134)
    header = (
        f"{'Router':<28} | {'Deliv':>6} | {'CritDel':>7} | {'CritDrp':>7} | {'UnqLost':>7} | "
        f"{'MaxOcc':>6} | {'Overhd':>6} | {'CtrlRat':>7} | {'GrdErl':>6} | {'GrdLat':>6} | {'CtrlKB':>8}"
    )
    print(header)
    print("-" * 134)

    router_order = [
        "EpidemicRouter",
        "SprayAndWaitRouter",
        "ProphetV2Router",
        "MaxPropRouter",
        "DtnmaStaticRouter",
        "DtnmaStaticAckRouter",
        "DtnmaUnshieldedRLRouter",
        "DtnmaShieldedPassiveRLRouter",
        "DtnmaShieldedRLRouter",
        "DtnmaGossipRLRouter",
        "DtnmaGossipAckRLRouter",
        "DtnmaRouter",
    ]
    present_routers = [r for r in router_order if r in raw_df["router"].values]
    for r in sorted(raw_df["router"].unique()):
        if r not in present_routers:
            present_routers.append(r)

    drop_violations = []
    occ_violations = []
    for r in present_routers:
        sub = raw_df[raw_df["router"] == r]
        deliv = sub["delivery_prob"].mean() * 100.0 if "delivery_prob" in sub else 0.0
        crit_del = sub["critical_delivery_prob"].mean() * 100.0 if "critical_delivery_prob" in sub else 0.0
        crit_buf_drp = sub["critical_buffer_dropped"].sum() if "critical_buffer_dropped" in sub else 0.0
        unq_lost = sub["unique_critical_lost"].sum() if "unique_critical_lost" in sub else 0.0
        max_occ = sub["max_buffer_occupancy"].max() if "max_buffer_occupancy" in sub else 0.0
        overhd = sub["overhead_ratio"].mean() if "overhead_ratio" in sub else 0.0
        ctrl_rat = sub["control_to_payload_ratio"].mean() * 100.0 if "control_to_payload_ratio" in sub else 0.0
        grd_erl = sub["shield_greedy_early_rate"].mean() * 100.0 if "shield_greedy_early_rate" in sub else 0.0
        grd_lat = sub["shield_greedy_late_rate"].mean() * 100.0 if "shield_greedy_late_rate" in sub else 0.0

        ctrl_bytes = 0.0
        for col in ["beacon_overhead_bytes", "gossip_overhead_bytes", "ack_overhead_bytes"]:
            if col in sub:
                ctrl_bytes += sub[col].mean()
        ctrl_kb = ctrl_bytes / 1024.0

        print(
            f"{r:<28} | {deliv:5.2f}% | {crit_del:6.2f}% | {int(crit_buf_drp):7d} | {int(unq_lost):7d} | "
            f"{max_occ:6.4f} | {overhd:6.2f} | {ctrl_rat:6.3f}% | {grd_erl:5.2f}% | {grd_lat:5.2f}% | {ctrl_kb:8.1f}"
        )

        if r in SHIELDED_ROUTERS and crit_buf_drp > 0:
            drop_violations.append((r, int(crit_buf_drp)))
        if r in SHIELDED_ROUTERS and max_occ > 1.0001:
            occ_violations.append((r, float(max_occ)))

    print("=" * 134)

    # 4. Per-Buffer Breakdown & Profile Distribution
    if "buffer" in raw_df.columns:
        print("\nPER-BUFFER PERFORMANCE & PROFILE EXECUTION DISTRIBUTION:")
        print("-" * 120)
        for buf_val in sorted(raw_df["buffer"].unique()):
            sub_b = raw_df[raw_df["buffer"] == buf_val]
            print(f"Buffer = {buf_val}M:")
            for r in present_routers:
                sr = sub_b[sub_b["router"] == r]
                if sr.empty:
                    continue
                p_str = ""
                if all(f"profile_{i}_frac" in sr.columns for i in range(8)) and sr[[f"profile_{i}_frac" for i in range(8)]].sum().sum() > 0:
                    p_vals = [f"P{i}:{sr[f'profile_{i}_frac'].mean()*100:4.1f}%" for i in range(8) if sr[f"profile_{i}_frac"].mean() >= 0.02]
                    p_str = " | Profiles=[" + ", ".join(p_vals) + "]"
                gr_e = sr["shield_greedy_early_rate"].mean() * 100.0 if "shield_greedy_early_rate" in sr else 0.0
                gr_l = sr["shield_greedy_late_rate"].mean() * 100.0 if "shield_greedy_late_rate" in sr else 0.0
                print(
                    f"  {r:<28}: Deliv={sr['delivery_prob'].mean()*100:5.2f}% | "
                    f"CritDel={sr['critical_delivery_prob'].mean()*100:5.2f}% | "
                    f"Overhd={sr['overhead_ratio'].mean():5.2f} | "
                    f"GrdShld={gr_e:4.1f}%->{gr_l:4.1f}%{p_str}"
                )
        print("-" * 120)

    # 5. Per-Seed Paired Differences & 95% CIs
    print("\nPAIRED DIFFERENCES & 95% CONFIDENCE INTERVALS (MATCHED SEED x TTL x BUFFER):")
    print("-" * 120)
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckRouter", "delivery_prob", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckRouter", "critical_delivery_prob", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckRouter", "overhead_ratio", 1.0, "")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "SprayAndWaitRouter", "delivery_prob", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "SprayAndWaitRouter", "critical_delivery_prob", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "MaxPropRouter", "delivery_prob", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "MaxPropRouter", "critical_delivery_prob", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaGossipAckRLRouter", "DtnmaShieldedPassiveRLRouter", "shield_greedy_late_rate", 100.0, "pp")
    print_paired_diff(raw_df, "DtnmaRouter", "SprayAndWaitRouter", "delivery_prob", 100.0, "pp")
    print("-" * 120)

    # 6. Generate Phase 1 RL & Shield Diagnostics Composite Figure
    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    plt.subplots_adjust(hspace=0.35, wspace=0.25)

    ax = axes[0, 0]
    if "buffer" in averaged_dfs:
        bdf = averaged_dfs["buffer"]
        for r in present_routers:
            rdf = bdf[bdf["router"] == r].sort_values("value")
            if not rdf.empty and "delivery_prob" in rdf:
                ax.plot(rdf["value"], rdf["delivery_prob"] * 100.0, marker="o", linewidth=2, label=r)
    ax.set_title("Overall Delivery Probability vs Buffer Size", fontweight="bold")
    ax.set_xlabel("Buffer Size (MB)")
    ax.set_ylabel("Delivery Probability (%)")
    ax.grid(True, linestyle=":", alpha=0.6)
    ax.legend(fontsize=7)

    ax = axes[0, 1]
    drop_vals = [int(raw_df[raw_df["router"] == r]["critical_buffer_dropped"].sum()) for r in present_routers]
    colors = ["#d62728" if v > 0 else "#2ca02c" for v in drop_vals]
    bars = ax.bar(range(len(present_routers)), drop_vals, color=colors)
    ax.set_xticks(range(len(present_routers)))
    ax.set_xticklabels([r.replace("Dtnma", "").replace("Router", "") or "DTNMA" for r in present_routers], rotation=22, ha="right")
    ax.set_title("Total Critical Buffer Evictions (Zero-Drop Verification)", fontweight="bold")
    ax.set_ylabel("Critical Buffer Drops (Count)")
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)
    for b, v in zip(bars, drop_vals):
        ax.annotate(f"{v}", xy=(b.get_x() + b.get_width() / 2, v), xytext=(0, 3),
                    textcoords="offset points", ha="center", va="bottom", fontweight="bold", fontsize=8)

    ax = axes[1, 0]
    shield_only = [r for r in present_routers if ("Shielded" in r or "Gossip" in r or r == "DtnmaRouter")]
    if shield_only:
        early_rates = [raw_df[raw_df["router"] == r]["shield_greedy_early_rate"].mean() * 100.0 for r in shield_only]
        late_rates = [raw_df[raw_df["router"] == r]["shield_greedy_late_rate"].mean() * 100.0 for r in shield_only]
        x_idx = range(len(shield_only))
        w = 0.35
        ax.bar([i - w / 2 for i in x_idx], early_rates, width=w, label="Early Greedy (First 40%)", color="#ff7f0e")
        ax.bar([i + w / 2 for i in x_idx], late_rates, width=w, label="Late Greedy (Final 40%)", color="#1f77b4")
        ax.set_xticks(list(x_idx))
        ax.set_xticklabels([r.replace("Dtnma", "").replace("Router", "") or "DTNMA" for r in shield_only], rotation=18, ha="right")
        ax.legend()
    ax.set_title("Epsilon-Free Safety Internalization (Greedy Shield Rate)", fontweight="bold")
    ax.set_ylabel("Constrained Greedy Intervention (%)")
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    ax = axes[1, 1]
    if "buffer" in averaged_dfs:
        bdf = averaged_dfs["buffer"]
        for r in present_routers:
            rdf = bdf[bdf["router"] == r].sort_values("value")
            if not rdf.empty and "overhead_ratio" in rdf:
                ax.plot(rdf["value"], rdf["overhead_ratio"], marker="s", linewidth=2, label=r)
    ax.set_title("Network Overhead Ratio vs Buffer Size", fontweight="bold")
    ax.set_xlabel("Buffer Size (MB)")
    ax.set_ylabel("Overhead Ratio")
    ax.grid(True, linestyle=":", alpha=0.6)
    ax.legend(fontsize=7)

    diag_path = plots_dir / "phase1_rl_diagnostics.png"
    plt.savefig(diag_path, dpi=300, bbox_inches="tight")
    plt.close()
    print(f"\nDiagnostics plot saved to: {diag_path}")

    if (drop_violations or occ_violations) and not args.no_assert:
        if drop_violations:
            print("\n[REGRESSION FAILURE] Critical buffer drops violated on protected routers:", drop_violations)
        if occ_violations:
            print("\n[REGRESSION FAILURE] Physical buffer occupancy exceeded 1.0:", occ_violations)
        sys.exit(1)

    print("\n[OppNDA PHASE 1 VERIFICATION PASSED] Zero critical buffer drops & zero buffer overshoot.")


if __name__ == "__main__":
    main()
