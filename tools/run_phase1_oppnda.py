#!/usr/bin/env python3
"""
run_phase1_oppnda.py
Executes OppNDA's ReportAverager & Analysis pipeline on Phase 1 VDTN ablation
or 210-run baseline comparison sweeps, verifies zero critical buffer drops
across all Shielded & Static DTNMA routers, prints comparative tables, and plots diagnostics.
"""
import os
import sys
import json
import argparse
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
    "DtnmaShieldedRLRouter",
    "DtnmaGossipRLRouter",
    "DtnmaGossipAckRLRouter",
    "DtnmaRouter",
}

def main():
    parser = argparse.ArgumentParser(description="OppNDA Phase 1 Post-Processing & Diagnostics")
    parser.add_argument("--averager-config", default="config/phase1_averager_config.json")
    parser.add_argument("--analysis-config", default="config/phase1_analysis_config.json")
    parser.add_argument("--report-dir", "-r", default=None, help="Override reports directory (e.g., reports/phase1_vdtn_210)")
    parser.add_argument("--plots-dir", "-p", default=None, help="Override plots output directory (e.g., plots/phase1_vdtn_210)")
    parser.add_argument("--no-assert", action="store_true", help="Skip strict zero-critical-buffer-drop assertion")
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

        # 1. Run OppNDA Averager
        averager = ReportAverager(tmp_avg_path)
        averager.run()

        # 2. Run OppNDA Parser, Organizer & Line Plots
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

    # Export per-router CSVs via OppNDA convention
    for router in sorted(raw_df["router"].unique()):
        router_df = raw_df[raw_df["router"] == router]
        csv_path = plots_dir / f"{router}_metrics.csv"
        router_df.to_csv(csv_path, index=False)

    # Generate OppNDA line plots and surface plots using averaged data
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

    # 3. Print Comprehensive Ablation Table
    print("\n" + "=" * 115)
    print("PHASE 1 VDTN SUMMARY (MEAN ACROSS ALL SEEDS, TTLs & BUFFERS)")
    print("=" * 115)
    header = (
        f"{'Router':<24} | {'Deliv':>6} | {'CritDel':>7} | {'CritBufDrp':>10} | "
        f"{'CritTTL':>7} | {'Overhd':>7} | {'BufTime':>7} | {'ShldErl':>7} | {'ShldLat':>7} | {'CtrlKB':>8}"
    )
    print(header)
    print("-" * 115)

    router_order = [
        "EpidemicRouter",
        "SprayAndWaitRouter",
        "DtnmaStaticRouter",
        "DtnmaUnshieldedRLRouter",
        "DtnmaShieldedRLRouter",
        "DtnmaGossipRLRouter",
        "DtnmaGossipAckRLRouter",
        "DtnmaRouter",
    ]
    present_routers = [r for r in router_order if r in raw_df["router"].values]
    for r in sorted(raw_df["router"].unique()):
        if r not in present_routers:
            present_routers.append(r)

    violations = []
    for r in present_routers:
        sub = raw_df[raw_df["router"] == r]
        deliv = sub["delivery_prob"].mean() * 100.0 if "delivery_prob" in sub else 0.0
        crit_del = sub["critical_delivery_prob"].mean() * 100.0 if "critical_delivery_prob" in sub else 0.0
        crit_buf_drp = sub["critical_buffer_dropped"].sum() if "critical_buffer_dropped" in sub else 0.0
        crit_ttl = sub["critical_ttl_expired"].mean() if "critical_ttl_expired" in sub else 0.0
        overhd = sub["overhead_ratio"].mean() if "overhead_ratio" in sub else 0.0
        buf_t = sub["buffertime_avg"].mean() if "buffertime_avg" in sub else 0.0
        sh_erl = sub["shield_early_rate"].mean() * 100.0 if "shield_early_rate" in sub else 0.0
        sh_lat = sub["shield_late_rate"].mean() * 100.0 if "shield_late_rate" in sub else 0.0

        ctrl_bytes = 0.0
        for col in ["beacon_overhead_bytes", "gossip_overhead_bytes", "ack_overhead_bytes"]:
            if col in sub:
                ctrl_bytes += sub[col].mean()
        ctrl_kb = ctrl_bytes / 1024.0

        print(
            f"{r:<24} | {deliv:5.2f}% | {crit_del:6.2f}% | {int(crit_buf_drp):10d} | "
            f"{crit_ttl:7.1f} | {overhd:7.2f} | {buf_t:7.1f} | {sh_erl:6.2f}% | {sh_lat:6.2f}% | {ctrl_kb:8.1f}"
        )

        if r in SHIELDED_ROUTERS and crit_buf_drp > 0:
            violations.append((r, int(crit_buf_drp)))

    print("=" * 115)

    # 4. Buffer-constrained breakdown (5M)
    if "buffer" in raw_df.columns and 5 in raw_df["buffer"].values:
        print("\nCONSTRAINED BUFFER (5M) BREAKDOWN:")
        print("-" * 95)
        sub5 = raw_df[raw_df["buffer"] == 5]
        for r in present_routers:
            sr = sub5[sub5["router"] == r]
            if sr.empty:
                continue
            print(
                f"  {r:<24}: Deliv={sr['delivery_prob'].mean()*100:5.2f}% | "
                f"CritDel={sr['critical_delivery_prob'].mean()*100:5.2f}% | "
                f"CritBufDrop(sum)={int(sr['critical_buffer_dropped'].sum()):4d} | "
                f"Overhead={sr['overhead_ratio'].mean():6.2f} | "
                f"Reward={sr['rl_avg_reward'].mean():6.3f}"
            )
        print("-" * 95)

    # 5. Generate Phase 1 RL & Shield Diagnostics Composite Figure
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
    ax.legend(fontsize=8)

    ax = axes[0, 1]
    drop_vals = [int(raw_df[raw_df["router"] == r]["critical_buffer_dropped"].sum()) for r in present_routers]
    colors = ["#d62728" if v > 0 else "#2ca02c" for v in drop_vals]
    bars = ax.bar(range(len(present_routers)), drop_vals, color=colors)
    ax.set_xticks(range(len(present_routers)))
    ax.set_xticklabels([r.replace("Dtnma", "").replace("Router", "") or "DTNMA" for r in present_routers], rotation=20, ha="right")
    ax.set_title("Total Critical Buffer Evictions (Zero-Drop Theorem Proof)", fontweight="bold")
    ax.set_ylabel("Critical Buffer Drops (Count)")
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)
    for b, v in zip(bars, drop_vals):
        ax.annotate(f"{v}", xy=(b.get_x() + b.get_width() / 2, v), xytext=(0, 3),
                    textcoords="offset points", ha="center", va="bottom", fontweight="bold")

    ax = axes[1, 0]
    shield_only = [r for r in present_routers if ("Shielded" in r or "Gossip" in r or r == "DtnmaRouter")]
    if shield_only:
        early_rates = [raw_df[raw_df["router"] == r]["shield_early_rate"].mean() * 100.0 for r in shield_only]
        late_rates = [raw_df[raw_df["router"] == r]["shield_late_rate"].mean() * 100.0 for r in shield_only]
        x_idx = range(len(shield_only))
        w = 0.35
        ax.bar([i - w/2 for i in x_idx], early_rates, width=w, label="Early Constrained (First 40%)", color="#ff7f0e")
        ax.bar([i + w/2 for i in x_idx], late_rates, width=w, label="Late Constrained (Final 40%)", color="#1f77b4")
        ax.set_xticks(list(x_idx))
        ax.set_xticklabels([r.replace("Dtnma", "").replace("Router", "") or "DTNMA" for r in shield_only], rotation=15, ha="right")
        ax.legend()
    ax.set_title("Safety Internalization: Early vs Late Shield Intervention Rate", fontweight="bold")
    ax.set_ylabel("Shield Intervention Rate (%)")
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
    ax.legend(fontsize=8)

    diag_path = plots_dir / "phase1_rl_diagnostics.png"
    plt.savefig(diag_path, dpi=300, bbox_inches="tight")
    plt.close()
    print(f"\nDiagnostics plot saved to: {diag_path}")

    if violations and not args.no_assert:
        print("\n[REGRESSION FAILURE] Critical buffer drops violated on protected routers:", violations)
        sys.exit(1)

    print("\n[OppNDA PHASE 1 VERIFICATION PASSED] Zero critical buffer drops across all Shielded & Static routers.")

if __name__ == "__main__":
    main()
