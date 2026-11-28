"""
Confirmatory Multi-Mobility Campaign Reproducibility Analyzer.

Reads raw ONE Simulator report outputs (`*_DtnmaRlMetricsReport.txt` and
`*_MessageStatsReport.txt`) plus optional `campaign.log` execution logs and
exports:
  - per_seed_metrics.csv : complete per-run table with critical lifecycle columns
  - paired_families.csv  : predeclared multiple-testing family results (Delta, 95% CI,
                           Hedges' g_z / Cohen's d_z, paired t, Wilcoxon, Holm p)
  - summary_stats.json   : complete structured aggregates, critical lifecycle stats,
                           per-substrate tables, scalability trends, and overhead metrics
  - tables_summary.md    : formatted summary tables for reproducible inspection
"""

import argparse
import csv
import json
import math
import os
import re
import statistics
import sys
from collections import defaultdict

try:
    from scipy import stats as sp_stats
    HAS_SCIPY = True
except Exception:
    HAS_SCIPY = False

RL_METRICS = [
    "sim_time",
    "critical_created",
    "critical_delivered",
    "critical_buffer_dropped",
    "critical_buffer_dropped_acked",
    "unique_critical_lost",
    "critical_ttl_expired",
    "critical_creation_rejected",
    "bulk_creation_rejected",
    "effective_critical_loss",
    "critical_delivery_prob",
    "critical_latency_avg",
    "critical_latency_p95",
    "bulk_created",
    "bulk_delivered",
    "bulk_buffer_dropped",
    "bulk_ttl_expired",
    "bulk_delivery_prob",
    "bulk_latency_avg",
    "alive_nodes_2h",
    "alive_nodes_4h",
    "alive_nodes_end",
    "eventA_stale_throttle_nodesec",
    "eventA_bulk_deliv",
    "eventB_bulk_deliv",
    "eventC_bulk_deliv",
    "avg_ict_s",
    "avg_contact_duration_s",
    "avg_encounter_rate_per_min",
    "decision_cpu_us_mean",
    "agent_state_bytes_per_node",
    "ctrl_energy_share",
    "control_to_payload_ratio",
    "control_bytes_total",
    "shield_interventions_total",
    "max_buffer_occupancy",
    "total_energy_j",
]

MS_METRICS = [
    "created",
    "relayed",
    "aborted",
    "dropped",
    "delivered",
    "delivery_prob",
    "overhead_ratio",
    "latency_avg",
    "hopcount_avg",
    "buffertime_avg",
]

FNAME_RL_RE = re.compile(r"^(.+?)_([A-Za-z0-9]+Router)_(\d+)_DtnmaRlMetricsReport\.txt$")

# Fallback exact two-sided t(0.975, df=n-1) when scipy is unavailable
T_CRIT_FALLBACK = {
    10: 2.2622,
    11: 2.2281,
    15: 2.1448,
    18: 2.1009,
    19: 2.0930,
    23: 2.0739,
    29: 2.0484,
}


def parse_kv_file(path, allowed_keys):
    out = {}
    with open(path, "r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            if ": " not in line:
                continue
            k, v = line.split(": ", 1)
            k = k.strip()
            if k not in allowed_keys:
                continue
            vs = v.strip()
            if vs in ("NaN", "Infinity", "-Infinity", ""):
                continue
            try:
                out[k] = float(vs)
            except ValueError:
                pass
    return out


def t_crit_95(n):
    if n <= 1:
        return 0.0
    if HAS_SCIPY:
        return float(sp_stats.t.ppf(0.975, df=n - 1))
    if n in T_CRIT_FALLBACK:
        return T_CRIT_FALLBACK[n]
    return 1.96 + 2.4 / max(1, n - 1)


def hedges_correction(df):
    """Exact small-sample Hedges' J(df) factor = 1 - 3 / (4*df - 1)."""
    if df <= 1:
        return 1.0
    return 1.0 - (3.0 / (4.0 * df - 1.0))


def summarize_vector(vals):
    n = len(vals)
    if n == 0:
        return {"n": 0, "mean": 0.0, "sd": 0.0, "ci95": 0.0, "min": 0.0, "max": 0.0}
    mu = statistics.mean(vals)
    sd = statistics.stdev(vals) if n > 1 else 0.0
    ci = t_crit_95(n) * sd / math.sqrt(n) if n > 1 else 0.0
    return {
        "n": n,
        "mean": mu,
        "sd": sd,
        "ci95": ci,
        "min": min(vals),
        "max": max(vals),
    }


def compute_paired(a, b):
    diffs = [x - y for x, y in zip(a, b)]
    n = len(diffs)
    mu = statistics.mean(diffs) if n else 0.0
    sd = statistics.stdev(diffs) if n > 1 else 0.0
    ci = t_crit_95(n) * sd / math.sqrt(n) if n > 1 else 0.0
    dz = (mu / sd) if sd > 1e-12 else 0.0
    gz = dz * hedges_correction(max(1, n - 1))

    p_t = float("nan")
    p_w = float("nan")
    if HAS_SCIPY and n >= 2:
        if sd > 1e-12:
            _, p_t_raw = sp_stats.ttest_rel(a, b)
            p_t = float(p_t_raw)
        else:
            p_t = 1.0 if abs(mu) < 1e-12 else 0.0
        nz = [d for d in diffs if abs(d) > 1e-12]
        if len(nz) >= 5:
            try:
                _, p_w_raw = sp_stats.wilcoxon(a, b, zero_method="wilcox")
                p_w = float(p_w_raw)
            except Exception:
                p_w = float("nan")
        elif len(nz) == 0:
            p_w = 1.0

    return {
        "n": n,
        "mean": mu,
        "sd": sd,
        "ci95": ci,
        "dz": dz,
        "gz": gz,
        "wins": sum(1 for d in diffs if d > 1e-12),
        "losses": sum(1 for d in diffs if d < -1e-12),
        "ties": sum(1 for d in diffs if abs(d) <= 1e-12),
        "p_t": p_t,
        "p_w": p_w,
    }


def holm_bonferroni(pvals):
    m = len(pvals)
    indexed = sorted(
        enumerate(pvals),
        key=lambda kv: (1.0 if kv[1] is None or math.isnan(kv[1]) else kv[1]),
    )
    adj = [float("nan")] * m
    running_max = 0.0
    for rank, (orig_idx, p) in enumerate(indexed):
        if p is None or math.isnan(p):
            adj[orig_idx] = float("nan")
        else:
            val = min(1.0, p * (m - rank))
            val = max(running_max, val)
            running_max = val
            adj[orig_idx] = val
    return adj


def parse_campaign_log_runtimes(log_path):
    """Extracts per-run wall durations grouped by scenario filename if log exists."""
    durs = defaultdict(list)
    if not log_path or not os.path.isfile(log_path):
        return {}
    cur_scen = None
    with open(log_path, "r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            if "Scenario File" in line and ":" in line:
                cur_scen = os.path.basename(line.split(":", 1)[1].strip())
            m = re.search(r"Run\s+\d+\s+OK\s*\(\s*([\d.]+)s\)", line)
            if m and cur_scen:
                durs[cur_scen].append(float(m.group(1)))
    out = {}
    for scen, arr in sorted(durs.items()):
        out[scen] = {
            "runs_logged": len(arr),
            "wall_sec_mean": statistics.mean(arr),
            "wall_sec_sd": statistics.stdev(arr) if len(arr) > 1 else 0.0,
            "wall_sec_min": min(arr),
            "wall_sec_max": max(arr),
        }
    return out


def build_predeclared_families():
    """
    Defines explicit hypothesis-testing families for Holm-Bonferroni control.
    Each tuple: (family_id, [(stage, target_router, comp_router, metric), ...])
    """
    families = []

    # Family 1: Env 1 Stress primary effectiveness & ablation comparisons (m = 18)
    f1_comps = [
        "DtnmaM2SrlRouter",
        "DtnmaM0eEnergy25Router",
        "DtnmaM1RemoteRouter",
        "DtnmaM2MobEnergyOnlyRouter",
        "DtnmaM2MobBufferOnlyRouter",
        "DtnmaM2MobReplicaOnlyRouter",
    ]
    f1_items = [
        ("env1_vdtn_stress", "DtnmaM2MobRouter", c, m)
        for c in f1_comps
        for m in ("alive_nodes_end", "critical_delivery_prob", "bulk_delivery_prob")
    ]
    families.append(("F1_Env1_Stress_Core", f1_items))

    # Family 1b: Env 1 Stress sustained overload (Event C) cohort (m = 6)
    families.append(
        (
            "F1b_Env1_Stress_EventC",
            [("env1_vdtn_stress", "DtnmaM2MobRouter", c, "eventC_bulk_deliv") for c in f1_comps],
        )
    )

    # Family 1c: Env 1 Stress non-DTNMA baselines (m = 8)
    families.append(
        (
            "F1c_Env1_Stress_NonDtnma",
            [
                ("env1_vdtn_stress", "DtnmaM2MobRouter", c, m)
                for c in ("SprayAndWaitRouter", "CongestionAwareSnWRouter")
                for m in ("alive_nodes_end", "critical_delivery_prob", "bulk_delivery_prob", "eventC_bulk_deliv")
            ],
        )
    )

    # Family 2: Env 1 Benign non-interference (m = 10)
    f2_comps = [
        "DtnmaM2SrlRouter",
        "DtnmaM0eEnergy25Router",
        "DtnmaM1RemoteRouter",
        "SprayAndWaitRouter",
        "CongestionAwareSnWRouter",
    ]
    families.append(
        (
            "F2_Env1_Benign_NonInterference",
            [
                ("env1_vdtn_benign", "DtnmaM2MobRouter", c, m)
                for c in f2_comps
                for m in ("bulk_delivery_prob", "eventC_bulk_deliv")
            ],
        )
    )

    # Family 3a & 3b: Zero-shot regime checks in Env 2 & Env 3 (m = 9 each)
    f3_comps = ["DtnmaM2SrlRouter", "DtnmaM0eEnergy25Router", "DtnmaM1RemoteRouter"]
    families.append(
        (
            "F3a_Env2_WorkingDay",
            [
                ("env2_workingday", "DtnmaM2MobRouter", c, m)
                for c in f3_comps
                for m in ("alive_nodes_end", "critical_delivery_prob", "bulk_delivery_prob")
            ],
        )
    )
    families.append(
        (
            "F3b_Env3_DisasterRelief",
            [
                ("env3_disaster_relief", "DtnmaM2MobRouter", c, m)
                for c in f3_comps
                for m in ("alive_nodes_end", "critical_delivery_prob", "bulk_delivery_prob")
            ],
        )
    )

    # Family 4: Cross-substrate composability (m = 16)
    sub_pairs = [
        ("ManagedSprayAndWaitRouter", "SprayAndWaitRouter"),
        ("ManagedProphetV2Router", "ProphetV2Router"),
        ("ManagedMaxPropRouter", "MaxPropRouter"),
        ("ManagedEpidemicRouter", "EpidemicRouter"),
    ]
    families.append(
        (
            "F4_CrossSubstrate",
            [
                ("cross_substrate", tgt, cmp_r, m)
                for tgt, cmp_r in sub_pairs
                for m in ("alive_nodes_end", "critical_delivery_prob", "bulk_delivery_prob", "unique_critical_lost")
            ],
        )
    )

    # Family 5: Scalability N in {60, 126, 250, 500} joint family (m = 36)
    scale_comps = ["DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "SprayAndWaitRouter"]
    families.append(
        (
            "F5_Scalability_Joint",
            [
                (st, "DtnmaM2MobRouter", c, m)
                for st in ("scale_60", "scale_126", "scale_250", "scale_500")
                for c in scale_comps
                for m in ("critical_delivery_prob", "bulk_delivery_prob", "eventC_bulk_deliv")
            ],
        )
    )

    return families


def format_val(val, metric):
    if val is None or (isinstance(val, float) and math.isnan(val)):
        return "NA"
    if "prob" in metric or "deliv" in metric or metric == "ctrl_energy_share":
        return f"{val * 100.0:.2f}%"
    if metric in ("alive_nodes_end", "unique_critical_lost", "critical_creation_rejected", "critical_ttl_expired"):
        return f"{val:.2f}"
    return f"{val:.2f}"


def write_markdown_summary(out_path, stage_summary, paired_rows, runtime_summary):
    lines = ["# Multi-Mobility Campaign Statistical Tables", ""]
    lines.append("## 1. Per-Stage Aggregates (Mean ± SD [95% CI]) & Critical Lifecycle\n")
    for stage in sorted(stage_summary.keys()):
        lines.append(f"### `{stage}`")
        lines.append(
            "| Router | n | Alive | CritCreated | CritRej | CritBufLost | CritTTLExp | CritEndUndeliv | CritDeliv | BulkDeliv | EvC Bulk | CPU (us) | Mem (B) | CtrlE |"
        )
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for router, md in sorted(stage_summary[stage].items()):
            def g(k, scale=1.0, dec=2):
                s = md.get(k)
                if not s or s["n"] == 0:
                    return "NA"
                return f"{s['mean']*scale:.{dec}f}±{s['sd']*scale:.{dec}f}"

            lines.append(
                f"| `{router}` | {md['bulk_delivery_prob']['n']} | "
                f"{g('alive_nodes_end',1,1)} | {g('critical_created',1,1)} | "
                f"{g('critical_creation_rejected',1,2)} | {g('unique_critical_lost',1,2)} | "
                f"{g('critical_ttl_expired',1,2)} | {g('critical_undelivered_at_end',1,1)} | "
                f"{g('critical_delivery_prob',100,2)}% | {g('bulk_delivery_prob',100,2)}% | "
                f"{g('eventC_bulk_deliv',100,2)}% | {g('decision_cpu_us_mean',1,1)} | "
                f"{g('agent_state_bytes_per_node',1,0)} | {g('ctrl_energy_share',100,2)}% |"
            )
        lines.append("")

    lines.append("## 2. Predeclared Holm-Bonferroni Paired Comparisons\n")
    lines.append("| Family (m) | Stage | Comparison | Metric | Mean Δ [95% CI] | d_z | g_z | W/L/T | p_t | p_w | p_holm |")
    lines.append("|---|---|---|---|---:|---:|---:|---|---:|---:|---:|")
    for r in paired_rows:
        is_pct = "prob" in r["metric"] or "deliv" in r["metric"]
        sc = 100.0 if is_pct else 1.0
        unit = " pp" if is_pct else ""
        pw_s = f"{r['p_w']:.2e}" if r["p_w"] is not None and not math.isnan(r["p_w"]) else "—"
        pt_s = f"{r['p_t']:.2e}" if r["p_t"] is not None and not math.isnan(r["p_t"]) else "—"
        ph_s = f"{r['p_holm']:.2e}" if r["p_holm"] is not None and not math.isnan(r["p_holm"]) else "—"
        lines.append(
            f"| `{r['family']}` ({r['family_m']}) | `{r['stage']}` | `{r['target']}` vs `{r['comparator']}` | `{r['metric']}` | "
            f"{r['mean_diff']*sc:+.2f}{unit} [±{r['ci95_diff']*sc:.2f}] | {r['dz']:+.2f} | {r['gz']:+.2f} | "
            f"{r['wins']}/{r['losses']}/{r['ties']} | {pt_s} | {pw_s} | {ph_s} |"
        )
    if runtime_summary:
        lines.append("\n## 3. Per-Run Wall-Clock Runtimes (`campaign.log`)\n")
        lines.append("| Scenario | Runs Logged | Mean Wall (s) | Min (s) | Max (s) |")
        lines.append("|---|---:|---:|---:|---:|")
        for sc, info in sorted(runtime_summary.items()):
            lines.append(
                f"| `{sc}` | {info['runs_logged']} | {info['wall_sec_mean']:.1f}±{info['wall_sec_sd']:.1f} | "
                f"{info['wall_sec_min']:.1f} | {info['wall_sec_max']:.1f} |"
            )

    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description="Analyze frozen multi-mobility campaign results.")
    parser.add_argument(
        "--reports-dir",
        default=os.path.join(".", "reports", "mobility_suite"),
        help="Directory containing stage subdirs with ONE report files",
    )
    parser.add_argument(
        "--out-dir",
        required=True,
        help="Output directory for CSV, JSON, and Markdown tables",
    )
    parser.add_argument(
        "--log-path",
        default=None,
        help="Optional campaign.log path for per-run wall-clock timing",
    )
    args = parser.parse_args()

    reports_dir = os.path.abspath(args.reports_dir)
    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)
    log_path = args.log_path or os.path.join(reports_dir, "campaign.log")

    if not os.path.isdir(reports_dir):
        print(f"ERROR: reports dir not found: {reports_dir}", file=sys.stderr)
        return 2

    stages = sorted([d for d in os.listdir(reports_dir) if os.path.isdir(os.path.join(reports_dir, d))])
    per_seed_rows = []
    # stage -> seed -> router -> metrics
    grid = defaultdict(lambda: defaultdict(dict))
    # stage -> router -> metric -> [vals]
    by_stage_router = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))

    rl_set = set(RL_METRICS)
    ms_set = set(MS_METRICS)

    for stage in stages:
        sdir = os.path.join(reports_dir, stage)
        rl_paths = sorted(
            f for f in os.listdir(sdir) if f.endswith("_DtnmaRlMetricsReport.txt")
        )
        for fname in rl_paths:
            m = FNAME_RL_RE.match(fname)
            if not m:
                continue
            router, seed = m.group(2), int(m.group(3))
            rl_path = os.path.join(sdir, fname)
            vals = parse_kv_file(rl_path, rl_set)
            ms_path = rl_path.replace("_DtnmaRlMetricsReport.txt", "_MessageStatsReport.txt")
            if os.path.isfile(ms_path):
                ms_vals = parse_kv_file(ms_path, ms_set)
                for mk, mv in ms_vals.items():
                    vals[f"ms_{mk}"] = mv

            # Derived full critical-message lifecycle accounting
            c_created = vals.get("critical_created", 0.0)
            c_deliv = vals.get("critical_delivered", 0.0)
            c_buf_drop = vals.get("unique_critical_lost", 0.0)
            c_ttl = vals.get("critical_ttl_expired", 0.0)
            c_undeliv = max(0.0, c_created - c_deliv)
            vals["critical_undelivered_at_end"] = c_undeliv
            vals["critical_in_flight_at_end"] = max(0.0, c_undeliv - c_buf_drop - c_ttl)

            grid[stage][seed][router] = vals
            for k, v in vals.items():
                by_stage_router[stage][router][k].append(v)

            row = {"stage": stage, "seed": seed, "router": router}
            row.update(vals)
            per_seed_rows.append(row)

    # Aggregate statistics per stage and router
    stage_summary = {}
    for stage in stages:
        stage_summary[stage] = {}
        for router, mdict in sorted(by_stage_router[stage].items()):
            stage_summary[stage][router] = {
                k: summarize_vector(vlist) for k, vlist in mdict.items()
            }

    # Predeclared hypothesis families with exact Holm-Bonferroni correction
    paired_rows = []
    families_json = {}
    for fam_id, items in build_predeclared_families():
        raw_p = []
        comp_records = []
        for st, tgt, cmp_r, metric in items:
            seed_map = grid.get(st, {})
            matched_seeds = sorted(
                s for s, rmap in seed_map.items() if tgt in rmap and cmp_r in rmap
            )
            va = [seed_map[s][tgt].get(metric, float("nan")) for s in matched_seeds]
            vb = [seed_map[s][cmp_r].get(metric, float("nan")) for s in matched_seeds]
            valid_pairs = [(x, y) for x, y in zip(va, vb) if not (math.isnan(x) or math.isnan(y))]
            xa = [p[0] for p in valid_pairs]
            xb = [p[1] for p in valid_pairs]
            res = compute_paired(xa, xb)
            raw_p.append(res["p_t"])
            comp_records.append((st, tgt, cmp_r, metric, res))

        adj_p = holm_bonferroni(raw_p)
        fam_m = len(items)
        fam_list = []
        for (st, tgt, cmp_r, metric, res), ap in zip(comp_records, adj_p):
            rec = {
                "family": fam_id,
                "family_m": fam_m,
                "stage": st,
                "target": tgt,
                "comparator": cmp_r,
                "metric": metric,
                "n": res["n"],
                "mean_diff": res["mean"],
                "sd_diff": res["sd"],
                "ci95_diff": res["ci95"],
                "dz": res["dz"],
                "gz": res["gz"],
                "wins": res["wins"],
                "losses": res["losses"],
                "ties": res["ties"],
                "p_t": res["p_t"],
                "p_w": res["p_w"],
                "p_holm": ap,
            }
            paired_rows.append(rec)
            fam_list.append(rec)
        families_json[fam_id] = fam_list

    # Also compute per-scale m=9 sub-family p_holm inside scalability for transparency
    for st in ("scale_60", "scale_126", "scale_250", "scale_500"):
        sub = [r for r in paired_rows if r["family"] == "F5_Scalability_Joint" and r["stage"] == st]
        if sub:
            sub_adj = holm_bonferroni([r["p_t"] for r in sub])
            for r, sp in zip(sub, sub_adj):
                r["p_holm_perscale"] = sp

    runtime_summary = parse_campaign_log_runtimes(log_path)

    # 1. Export per_seed_metrics.csv
    extra_cols = ["critical_undelivered_at_end", "critical_in_flight_at_end"]
    fieldnames = ["stage", "seed", "router"] + RL_METRICS + extra_cols + [f"ms_{k}" for k in MS_METRICS]
    csv_path = os.path.join(out_dir, "per_seed_metrics.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fieldnames)
        w.writeheader()
        for row in sorted(per_seed_rows, key=lambda r: (r["stage"], r["router"], r["seed"])):
            w.writerow(row)

    # 2. Export paired_families.csv
    paired_cols = [
        "family", "family_m", "stage", "target", "comparator", "metric", "n",
        "mean_diff", "sd_diff", "ci95_diff", "dz", "gz", "wins", "losses", "ties",
        "p_t", "p_w", "p_holm", "p_holm_perscale",
    ]
    paired_csv = os.path.join(out_dir, "paired_families.csv")
    with open(paired_csv, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=paired_cols)
        w.writeheader()
        for row in paired_rows:
            w.writerow(row)

    # 3. Export summary_stats.json
    summary_payload = {
        "has_scipy": HAS_SCIPY,
        "total_runs": len(per_seed_rows),
        "stages": stage_summary,
        "families": families_json,
        "runtime_summary": runtime_summary,
    }
    json_path = os.path.join(out_dir, "summary_stats.json")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(summary_payload, fh, indent=2)

    # 4. Export tables_summary.md
    md_path = os.path.join(out_dir, "tables_summary.md")
    write_markdown_summary(md_path, stage_summary, paired_rows, runtime_summary)

    print(f"Analyzed {len(per_seed_rows)} runs across {len(stages)} stages (HAS_SCIPY={HAS_SCIPY}).")
    print(f"Outputs written to: {out_dir}")
    print(f"  - {os.path.basename(csv_path)}")
    print(f"  - {os.path.basename(paired_csv)} ({len(paired_rows)} family-adjusted tests)")
    print(f"  - {os.path.basename(json_path)}")
    print(f"  - {os.path.basename(md_path)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
