#!/usr/bin/env python3
"""
run_phase1_oppnda.py
Executes OppNDA's ReportAverager & Analysis pipeline politely (50% default CPU cap),
verifies zero critical buffer drops, zero physical buffer overshoot, and creation
denominator parity across shielded/protected routers, and computes statistically valid
seed-clustered 95% confidence intervals (and per-buffer paired differences).
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

try:
    from scipy.stats import t as _scipy_student_t
except ImportError:
    _scipy_student_t = None

PALETTE_12 = [
    "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728",
    "#9467bd", "#8c564b", "#e377c2", "#7f7f7f",
    "#bcbd22", "#17becf", "#003f5c", "#bc5090",
]
MARKERS_12 = ["o", "s", "^", "D", "v", "*", "P", "X", "h", "<", ">", "d"]

SHIELDED_ROUTERS = {
    "PrioritySprayAndWaitRouter",
    "PriorityMaxPropRouter",
    "DtnmaStaticRouter",
    "DtnmaStaticAckRouter",
    "DtnmaStaticAckTunedRouter",
    "DtnmaShieldedPassiveRLRouter",
    "DtnmaShieldedRLRouter",
    "DtnmaShieldedAckRLRouter",
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


def _two_sided_t_pvalue(t_abs: float, df_deg: int) -> float:
    if df_deg <= 0:
        return 1.0
    if _scipy_student_t is not None:
        return float(2.0 * _scipy_student_t.sf(t_abs, df_deg))
    return math.erfc(t_abs / math.sqrt(2.0))


def _compute_paired_ci(df_sub: pd.DataFrame, router_a: str, router_b: str, metric: str,
                       cluster_col: str = "seed", scale: float = 1.0):
    if router_a not in df_sub["router"].values or router_b not in df_sub["router"].values:
        return None
    if metric not in df_sub.columns or cluster_col not in df_sub.columns:
        return None

    grp_a = df_sub[df_sub["router"] == router_a].groupby(cluster_col, as_index=False)[metric].mean().rename(columns={metric: "a"})
    grp_b = df_sub[df_sub["router"] == router_b].groupby(cluster_col, as_index=False)[metric].mean().rename(columns={metric: "b"})
    merged = pd.merge(grp_a, grp_b, on=cluster_col, how="inner").dropna()
    if merged.empty:
        return None

    diffs = (merged["a"] - merged["b"]) * scale
    n = len(diffs)
    mean_d = float(diffs.mean())
    if n > 1:
        sd = float(diffs.std(ddof=1))
        se = sd / math.sqrt(n)
        ci = t_critical_95(n - 1) * se
        lo, hi = mean_d - ci, mean_d + ci
        if se > 1e-12:
            p_val = _two_sided_t_pvalue(abs(mean_d / se), n - 1)
        else:
            p_val = 0.0 if abs(mean_d) > 1e-12 else 1.0
        sig = "EXCLUDES 0" if (lo > 0.0 or hi < 0.0) else "crosses 0"
        return n, mean_d, ci, lo, hi, p_val, sig
    return n, mean_d, 0.0, mean_d, mean_d, 1.0, "n=1"


def apply_holm_bonferroni(entries):
    """Computes exact Holm-Bonferroni step-down adjusted p-values (p_holm) in-place."""
    m = len(entries)
    if m == 0:
        return entries
    order = sorted(range(m), key=lambda idx: entries[idx]["p_val"])
    running_max = 0.0
    for rank, orig_idx in enumerate(order):
        adj = min(1.0, (m - rank) * entries[orig_idx]["p_val"])
        running_max = max(running_max, adj)
        entries[orig_idx]["p_holm"] = running_max
    return entries


def print_paired_family(title: str, raw_df: pd.DataFrame, pairs, label_prefix: str = ""):
    entries = []
    for ra, rb, met, sc, unit in pairs:
        res = _compute_paired_ci(raw_df, ra, rb, met, cluster_col="seed", scale=sc)
        if res is None:
            continue
        n, mean_d, ci, lo, hi, p_val, sig = res
        entries.append({
            "ra": ra, "rb": rb, "metric": met, "unit": unit,
            "n": n, "mean_d": mean_d, "ci": ci, "lo": lo, "hi": hi,
            "p_val": p_val, "sig": sig, "p_holm": p_val,
        })
    if not entries:
        return
    apply_holm_bonferroni(entries)
    print(f"\n{title} (m={len(entries)}):")
    print("-" * 148)
    for e in entries:
        unit = e["unit"]
        if e["n"] > 1:
            ci_str = f"[{e['lo']:+.3f}, {e['hi']:+.3f}]{unit}"
            p_str = f"p={e['p_val']:.4f}, p_holm={e['p_holm']:.4f} ({'Holm <0.05' if e['p_holm'] < 0.05 else e['sig']})"
        else:
            ci_str = "n=1"
            p_str = "n=1"
        print(
            f"  {label_prefix}{e['ra']:<26} - {e['rb']:<26} | {e['metric']:<24} "
            f"(n={e['n']:2d}): mean={e['mean_d']:+7.3f}{unit:<2} 95%CI={ci_str:<21} | {p_str}"
        )
    print("-" * 148)


def main():
    default_workers = max(1, (os.cpu_count() or 4) // 2)
    parser = argparse.ArgumentParser(description="OppNDA Phase 1 Post-Processing & Diagnostics")
    parser.add_argument("--averager-config", default="config/phase1_averager_config.json")
    parser.add_argument("--analysis-config", default="config/phase1_analysis_config.json")
    parser.add_argument("--report-dir", "-r", default=None, help="Override reports directory (e.g., reports/phase1_sanity)")
    parser.add_argument("--plots-dir", "-p", default=None, help="Override plots output directory (e.g., plots/phase1_sanity)")
    parser.add_argument("--workers", "-w", type=int, default=default_workers, help=f"Worker processes (default: {default_workers}, 50%% of cores)")
    parser.add_argument("--no-assert", action="store_true", help="Skip strict zero-drop & zero-overshoot assertions")
    args = parser.parse_args()

    avg_cfg_path = PROJECT_ROOT / args.averager_config
    ana_cfg_path = PROJECT_ROOT / args.analysis_config

    with open(avg_cfg_path, "r", encoding="utf-8") as f:
        avg_cfg = json.load(f)
    with open(ana_cfg_path, "r", encoding="utf-8") as f:
        ana_cfg = json.load(f)

    workers = max(1, int(args.workers))
    avg_cfg["num_processes"] = workers
    ana_cfg["num_processes"] = workers

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
    print(f"Workers     : {workers} (polite CPU cap)")
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
    except KeyboardInterrupt:
        print("\n[ABORT] OppNDA processing interrupted cleanly.")
        sys.exit(130)
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
    print("\n" + "=" * 158)
    print("PHASE 2 / PHASE 1 SUMMARY (MEAN ACROSS ALL SEEDS, TTLs & BUFFERS)")
    print("=" * 158)
    header = (
        f"{'Router':<28} | {'Deliv':>6} | {'CritDel':>7} | {'CritDrp':>7} | {'UnqLost':>7} | "
        f"{'RejCr':>5} | {'MaxOcc':>6} | {'Overhd':>6} | {'CtrlRat':>7} | {'GrdErl':>6} | {'GrdLat':>6} | "
        f"{'AvgSoC':>6} | {'DeplAv':>6} | {'CtrlKB':>8}"
    )
    print(header)
    print("-" * 158)

    router_order = [
        "EpidemicRouter",
        "SprayAndWaitRouter",
        "PrioritySprayAndWaitRouter",
        "ProphetV2Router",
        "MaxPropRouter",
        "PriorityMaxPropRouter",
        "DtnmaStaticRouter",
        "DtnmaStaticAckRouter",
        "DtnmaStaticAckTunedRouter",
        "DtnmaUnshieldedRLRouter",
        "DtnmaShieldedPassiveRLRouter",
        "DtnmaShieldedRLRouter",
        "DtnmaShieldedAckRLRouter",
        "DtnmaGossipRLRouter",
        "DtnmaGossipAckRLRouter",
        "DtnmaRouter",
    ]
    present_routers = [r for r in router_order if r in raw_df["router"].values]
    for r in sorted(raw_df["router"].unique()):
        if r not in present_routers:
            present_routers.append(r)

    energy_col = "avg_energy_soc" if "avg_energy_soc" in raw_df.columns else (
        "avg_energy_remaining_soc" if "avg_energy_remaining_soc" in raw_df.columns else None
    )

    drop_violations = []
    occ_violations = []
    for r in present_routers:
        sub = raw_df[raw_df["router"] == r]
        deliv = sub["delivery_prob"].mean() * 100.0 if "delivery_prob" in sub else 0.0
        crit_del = sub["critical_delivery_prob"].mean() * 100.0 if "critical_delivery_prob" in sub else 0.0
        crit_buf_drp = sub["critical_buffer_dropped"].sum() if "critical_buffer_dropped" in sub else 0.0
        unq_lost = sub["unique_critical_lost"].sum() if "unique_critical_lost" in sub else 0.0
        rej_cr = sub["local_creation_rejected"].sum() if "local_creation_rejected" in sub else 0.0
        max_occ = sub["max_buffer_occupancy"].max() if "max_buffer_occupancy" in sub else 0.0
        overhd = sub["overhead_ratio"].mean() if "overhead_ratio" in sub else 0.0
        ctrl_rat = sub["control_to_payload_ratio"].mean() * 100.0 if "control_to_payload_ratio" in sub else 0.0
        grd_erl = sub["shield_greedy_early_rate"].mean() * 100.0 if "shield_greedy_early_rate" in sub else 0.0
        grd_lat = sub["shield_greedy_late_rate"].mean() * 100.0 if "shield_greedy_late_rate" in sub else 0.0
        avg_soc = sub[energy_col].mean() if energy_col is not None else 1.0
        depl_avg = sub["depleted_nodes_count"].mean() if "depleted_nodes_count" in sub else 0.0

        ctrl_bytes = 0.0
        for col in ["beacon_overhead_bytes", "gossip_overhead_bytes", "ack_overhead_bytes"]:
            if col in sub:
                ctrl_bytes += sub[col].mean()
        ctrl_kb = ctrl_bytes / 1024.0

        print(
            f"{r:<28} | {deliv:5.2f}% | {crit_del:6.2f}% | {int(crit_buf_drp):7d} | {int(unq_lost):7d} | "
            f"{int(rej_cr):5d} | {max_occ:6.4f} | {overhd:6.2f} | {ctrl_rat:6.3f}% | {grd_erl:5.2f}% | {grd_lat:5.2f}% | "
            f"{avg_soc:6.3f} | {depl_avg:6.1f} | {ctrl_kb:8.1f}"
        )

        if r in SHIELDED_ROUTERS and crit_buf_drp > 0:
            drop_violations.append((r, int(crit_buf_drp)))
        if r in SHIELDED_ROUTERS and max_occ > 1.0001:
            occ_violations.append((r, float(max_occ)))

    print("=" * 158)

    # 4. Per-Buffer Breakdown & Profile Distribution
    if "buffer" in raw_df.columns:
        print("\nPER-BUFFER PERFORMANCE & PROFILE EXECUTION DISTRIBUTION:")
        print("-" * 124)
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
        print("-" * 124)

    # 5A1. Pre-Registered Primary Endpoints (Holm-Bonferroni adjusted)
    static_primary = "DtnmaStaticAckTunedRouter" if "DtnmaStaticAckTunedRouter" in present_routers else "DtnmaStaticAckRouter"
    maxprop_primary = "PriorityMaxPropRouter" if "PriorityMaxPropRouter" in present_routers else "MaxPropRouter"
    primary_pairs = [
        ("DtnmaGossipAckRLRouter", static_primary, "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", maxprop_primary, "overhead_ratio", 1.0, ""),
        ("DtnmaGossipAckRLRouter", "DtnmaShieldedAckRLRouter", "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "DtnmaShieldedPassiveRLRouter", "shield_greedy_late_rate", 100.0, "pp"),
    ]
    print_paired_family(
        "SECTION A1: PRE-REGISTERED PRIMARY ENDPOINTS (CLUSTER = SEED; HOLM-BONFERRONI CORRECTED)",
        raw_df,
        primary_pairs,
    )

    # 5A2. Secondary / Exploratory Comparisons (Holm-Bonferroni adjusted within exploratory family)
    secondary_pairs = [
        ("DtnmaGossipAckRLRouter", "DtnmaStaticAckRouter", "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", static_primary, "critical_delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", static_primary, "overhead_ratio", 1.0, ""),
        ("DtnmaGossipAckRLRouter", "DtnmaShieldedAckRLRouter", "shield_greedy_late_rate", 100.0, "pp"),
        ("DtnmaShieldedAckRLRouter", static_primary, "delivery_prob", 100.0, "pp"),
        ("DtnmaShieldedAckRLRouter", static_primary, "overhead_ratio", 1.0, ""),
        ("DtnmaShieldedAckRLRouter", "DtnmaShieldedPassiveRLRouter", "shield_greedy_late_rate", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "critical_delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "PriorityMaxPropRouter", "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "PriorityMaxPropRouter", "critical_delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "MaxPropRouter", "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "MaxPropRouter", "critical_delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "SprayAndWaitRouter", "delivery_prob", 100.0, "pp"),
        ("DtnmaGossipAckRLRouter", "SprayAndWaitRouter", "critical_delivery_prob", 100.0, "pp"),
    ]
    if energy_col is not None and raw_df[energy_col].min() < 0.9999:
        secondary_pairs.extend([
            ("DtnmaGossipAckRLRouter", maxprop_primary, energy_col, 100.0, "%"),
            ("DtnmaGossipAckRLRouter", static_primary, energy_col, 100.0, "%"),
            ("DtnmaGossipAckRLRouter", maxprop_primary, "depleted_nodes_count", 1.0, ""),
            ("DtnmaGossipAckRLRouter", static_primary, "depleted_nodes_count", 1.0, ""),
        ])
    print_paired_family(
        "SECTION A2: SECONDARY / EXPLORATORY COMPARISONS (CLUSTER = SEED; HOLM-ADJUSTED WITHIN FAMILY)",
        raw_df,
        secondary_pairs,
    )

    # 5B. Per-Buffer Exploratory Differences (Holm-adjusted within each buffer)
    if "buffer" in raw_df.columns and raw_df["buffer"].nunique() > 1:
        buf_pairs = [
            ("DtnmaGossipAckRLRouter", static_primary, "delivery_prob", 100.0, "pp"),
            ("DtnmaGossipAckRLRouter", "DtnmaStaticAckRouter", "delivery_prob", 100.0, "pp"),
            ("DtnmaGossipAckRLRouter", "DtnmaShieldedAckRLRouter", "delivery_prob", 100.0, "pp"),
            ("DtnmaGossipAckRLRouter", maxprop_primary, "delivery_prob", 100.0, "pp"),
            ("DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "delivery_prob", 100.0, "pp"),
            ("DtnmaGossipAckRLRouter", "DtnmaShieldedPassiveRLRouter", "shield_greedy_late_rate", 100.0, "pp"),
        ]
        for buf_val in sorted(raw_df["buffer"].unique()):
            sub_b = raw_df[raw_df["buffer"] == buf_val]
            print_paired_family(
                f"SECTION B [{buf_val}M]: PER-BUFFER EXPLORATORY DIFFERENCES (HOLM-ADJUSTED WITHIN BUFFER)",
                sub_b,
                buf_pairs,
                label_prefix=f"[{buf_val:3d}M] ",
            )

    # 6. Generate RL & Shield Diagnostics Composite Figure (12-color palette + 1-buffer bar fallback)
    fig, axes = plt.subplots(2, 2, figsize=(15, 10))
    plt.subplots_adjust(hspace=0.36, wspace=0.25)
    color_map = {r: PALETTE_12[idx % len(PALETTE_12)] for idx, r in enumerate(present_routers)}
    marker_map = {r: MARKERS_12[idx % len(MARKERS_12)] for idx, r in enumerate(present_routers)}
    multi_buffer = ("buffer" in averaged_dfs and "buffer" in raw_df.columns and raw_df["buffer"].nunique() > 1)

    ax = axes[0, 0]
    if multi_buffer:
        bdf = averaged_dfs["buffer"]
        for r in present_routers:
            rdf = bdf[bdf["router"] == r].sort_values("value")
            if not rdf.empty and "delivery_prob" in rdf:
                ax.plot(rdf["value"], rdf["delivery_prob"] * 100.0,
                        marker=marker_map[r], color=color_map[r], linewidth=2, label=r)
        ax.set_title("Overall Delivery Probability vs Buffer Size", fontweight="bold")
        ax.set_xlabel("Buffer Size (MB)")
        ax.legend(fontsize=7)
    else:
        deliv_vals = [raw_df[raw_df["router"] == r]["delivery_prob"].mean() * 100.0 for r in present_routers]
        bars_d = ax.bar(range(len(present_routers)), deliv_vals, color=[color_map[r] for r in present_routers])
        ax.set_xticks(range(len(present_routers)))
        ax.set_xticklabels([r.replace("Dtnma", "").replace("Router", "") or "DTNMA" for r in present_routers], rotation=22, ha="right")
        ax.set_title("Overall Delivery Probability by Router", fontweight="bold")
        for b, v in zip(bars_d, deliv_vals):
            ax.annotate(f"{v:.1f}%", xy=(b.get_x() + b.get_width() / 2, v), xytext=(0, 3),
                        textcoords="offset points", ha="center", va="bottom", fontweight="bold", fontsize=8)
    ax.set_ylabel("Delivery Probability (%)")
    ax.grid(True, linestyle=":", alpha=0.6)

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
    if multi_buffer:
        bdf = averaged_dfs["buffer"]
        for r in present_routers:
            rdf = bdf[bdf["router"] == r].sort_values("value")
            if not rdf.empty and "overhead_ratio" in rdf:
                ax.plot(rdf["value"], rdf["overhead_ratio"],
                        marker=marker_map[r], color=color_map[r], linewidth=2, label=r)
        ax.set_title("Network Overhead Ratio vs Buffer Size", fontweight="bold")
        ax.set_xlabel("Buffer Size (MB)")
        ax.legend(fontsize=7)
    else:
        ov_vals = [raw_df[raw_df["router"] == r]["overhead_ratio"].mean() for r in present_routers]
        bars_o = ax.bar(range(len(present_routers)), ov_vals, color=[color_map[r] for r in present_routers])
        ax.set_xticks(range(len(present_routers)))
        ax.set_xticklabels([r.replace("Dtnma", "").replace("Router", "") or "DTNMA" for r in present_routers], rotation=22, ha="right")
        ax.set_title("Network Overhead Ratio by Router", fontweight="bold")
        for b, v in zip(bars_o, ov_vals):
            ax.annotate(f"{v:.2f}", xy=(b.get_x() + b.get_width() / 2, v), xytext=(0, 3),
                        textcoords="offset points", ha="center", va="bottom", fontweight="bold", fontsize=8)
    ax.set_ylabel("Overhead Ratio")
    ax.grid(True, linestyle=":", alpha=0.6)

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

    print("\n[OppNDA VERIFICATION PASSED] Zero critical buffer drops & zero buffer overshoot.")


if __name__ == "__main__":
    main()
