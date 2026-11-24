#!/usr/bin/env python3
"""Statistical analysis and audit-replay verification for post-freeze exploratory controls (v1.0-exp-controls).

Analyzes all 176 fresh-seed runs (seeds 7211..7307, n=11 paired primes) across:
  - exploratory_v1_0_controls/controls_stress     (M0b, M0e-20, M0e-25, M1-U, M2 -> 55 runs)
  - exploratory_v1_0_controls/controls_benign     (M0b, M0e-20, M0e-25, M1-U, M2 -> 55 runs)
  - exploratory_v1_0_controls/m2_localonly_stress (M2-LocalOnly                  -> 11 runs)
  - exploratory_v1_0_controls/m2_localonly_benign (M2-LocalOnly                  -> 11 runs)
  - exploratory_v1_0_controls/groupflood_stress   (M1-F-Group, M2                 -> 22 runs)
  - exploratory_v1_0_controls/groupflood_benign   (M1-F-Group, M2                 -> 22 runs)

Does NOT modify or mix with the frozen v1.0-tnsm-freeze campaign (seeds 7103..7207).
"""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
from scipy import stats

SCRIPT_DIR = Path(__file__).resolve().parent
ADTNM_ROOT = SCRIPT_DIR.parent
REPO_ROOT = ADTNM_ROOT.parent
THE_ONE_DIR = REPO_ROOT / "the-one"
REPORTS_BASE = THE_ONE_DIR / "reports" / "exploratory_v1_0_controls"
SCENARIOS_DIR = THE_ONE_DIR / "scenarios"
OUT_DIR = ADTNM_ROOT / "figures"

sys.path.insert(0, str(SCRIPT_DIR))
from evaluate_autonomic_framework import verify_audit_replay  # noqa: E402

EXP_SEEDS_11 = [7211, 7213, 7219, 7229, 7237, 7243, 7247, 7253, 7283, 7297, 7307]

ROUTER_TO_MODE = {
    "DtnmaM0bStaticRouter": "M0b",
    "DtnmaM0eEnergy20Router": "M0e-20",
    "DtnmaM0eEnergy25Router": "M0e-25",
    "DtnmaM2LocalOnlyRouter": "M2-LocalOnly",
    "DtnmaM1RemoteRouter": "M1-U",
    "DtnmaM1FloodGroupRouter": "M1-F-Group",
    "DtnmaM2SrlRouter": "M2",
}

CONTROLS_MODES = ["M0b", "M0e-20", "M0e-25", "M2-LocalOnly", "M1-U", "M2"]
GROUPFLOOD_MODES = ["M1-F-Group", "M2"]


def parse_kv_report(path: Path) -> Tuple[Dict[str, float], Dict[str, Dict[str, float]]]:
    kv: Dict[str, float] = {}
    waves: Dict[str, Dict[str, float]] = {}
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                continue
            if line.startswith("# WAVE_ADOPTION_CDF\t"):
                parts = line.split("\t")
                if len(parts) >= 11:
                    tag = parts[1].rstrip("_")
                    waves[tag] = {
                        "start": float(parts[2]),
                        "targets": float(parts[3]),
                        "deliv": float(parts[4]),
                        "q10": float(parts[5]),
                        "q25": float(parts[6]),
                        "q50": float(parts[7]),
                        "q75": float(parts[8]),
                        "q90": float(parts[9]),
                        "q95": float(parts[10]),
                    }
                continue
            if line.startswith("#") or ":" not in line:
                continue
            k, v = line.split(":", 1)
            k = k.strip()
            v = v.strip()
            try:
                kv[k] = float(v)
            except ValueError:
                pass
    return kv, waves


def extract_seed_and_router(stem: str) -> Optional[Tuple[str, int]]:
    for rname in ROUTER_TO_MODE:
        m = re.search(rf"_{re.escape(rname)}_(\d+)$", stem)
        if m:
            return ROUTER_TO_MODE[rname], int(m.group(1))
    return None


def load_exploratory_dir(dir_path: Path, expected_modes: List[str]) -> Dict[str, Dict[int, Dict[str, Any]]]:
    if not dir_path.is_dir():
        raise FileNotFoundError(f"Missing exploratory report directory: {dir_path}")

    msg_files = list(dir_path.glob("*_MessageStatsReport.txt"))
    rl_files = list(dir_path.glob("*_DtnmaRlMetricsReport.txt"))

    expected_total = len(expected_modes) * len(EXP_SEEDS_11)
    records: Dict[str, Dict[int, Dict[str, Any]]] = {m: {} for m in expected_modes}

    msg_map: Dict[Tuple[str, int], Path] = {}
    for p in msg_files:
        if p.stat().st_size == 0:
            raise RuntimeError(f"Empty MessageStatsReport: {p}")
        stem = p.name[:-len("_MessageStatsReport.txt")]
        parsed = extract_seed_and_router(stem)
        if parsed and parsed[1] in EXP_SEEDS_11 and parsed[0] in records:
            msg_map[parsed] = p

    rl_map: Dict[Tuple[str, int], Path] = {}
    for p in rl_files:
        if p.stat().st_size == 0:
            raise RuntimeError(f"Empty DtnmaRlMetricsReport: {p}")
        stem = p.name[:-len("_DtnmaRlMetricsReport.txt")]
        parsed = extract_seed_and_router(stem)
        if parsed and parsed[1] in EXP_SEEDS_11 and parsed[0] in records:
            rl_map[parsed] = p

    if len(msg_map) != expected_total or len(rl_map) != expected_total:
        raise RuntimeError(
            f"{dir_path.name}: expected {expected_total} runs for seeds {EXP_SEEDS_11}, "
            f"found msg={len(msg_map)}, rl={len(rl_map)}"
        )

    for mode in expected_modes:
        for seed in EXP_SEEDS_11:
            key = (mode, seed)
            if key not in msg_map or key not in rl_map:
                raise RuntimeError(f"Missing pair for {key} in {dir_path}")
            mk, _ = parse_kv_report(msg_map[key])
            rk, waves = parse_kv_report(rl_map[key])
            rec: Dict[str, Any] = {}
            rec.update(mk)
            rec.update(rk)
            n_hosts = max(1.0, rec.get("n_hosts", 126.0))
            rec["alive_frac_pct"] = 100.0 * rec["alive_nodes_end"] / n_hosts
            rec["critical_delivery_pct"] = 100.0 * rec["critical_delivery_prob"]
            rec["bulk_delivery_pct"] = 100.0 * rec["bulk_delivery_prob"]
            rec["pre_fault_pct"] = 100.0 * rec.get("pre_fault_delivery_prob", 0.0)
            rec["in_fault_pct"] = 100.0 * rec.get("in_fault_delivery_prob", 0.0)
            rec["post_fault_pct"] = 100.0 * rec.get("post_fault_delivery_prob", 0.0)
            for ev in ("eventA", "eventB", "eventC"):
                rec[f"{ev}_bulk_pct"] = 100.0 * rec[f"{ev}_bulk_deliv"]
                rec[f"{ev}_crit_pct"] = 100.0 * rec[f"{ev}_crit_deliv"]
                rec[f"{ev}_cov_pct"] = 100.0 * rec[f"{ev}_throttle_coverage"]
            rec["waves"] = waves
            records[mode][seed] = rec

    return records


def mean_ci95(vals: List[float]) -> Dict[str, float]:
    clean = [float(x) for x in vals if x is not None and not math.isnan(float(x))]
    n = len(clean)
    if n == 0:
        return {"n": 0, "mean": float("nan"), "sd": float("nan"), "ci95": float("nan"), "low": float("nan"), "high": float("nan")}
    arr = np.asarray(clean, dtype=float)
    mean = float(np.mean(arr))
    if n == 1:
        return {"n": 1, "mean": mean, "sd": 0.0, "ci95": float("nan"), "low": float("nan"), "high": float("nan")}
    sd = float(np.std(arr, ddof=1))
    tcrit = float(stats.t.ppf(0.975, df=n - 1))
    ci95 = tcrit * sd / math.sqrt(n)
    return {
        "n": n,
        "mean": mean,
        "sd": sd,
        "ci95": ci95,
        "low": mean - ci95,
        "high": mean + ci95,
    }


def paired_stats(vals_a: List[float], vals_b: List[float], better: str = "higher") -> Dict[str, Any]:
    pairs = [
        (float(a), float(b))
        for a, b in zip(vals_a, vals_b)
        if not (math.isnan(float(a)) or math.isnan(float(b)))
    ]
    n = len(pairs)
    a = np.asarray([p[0] for p in pairs], dtype=float)
    b = np.asarray([p[1] for p in pairs], dtype=float)
    diff = a - b
    mean_diff = float(np.mean(diff))
    sd_diff = float(np.std(diff, ddof=1)) if n > 1 else 0.0
    tcrit = float(stats.t.ppf(0.975, df=n - 1)) if n > 1 else float("nan")
    ci95 = tcrit * sd_diff / math.sqrt(n) if n > 1 else float("nan")

    if sd_diff == 0.0:
        t_stat = 0.0
        p_raw = 1.0
    else:
        tres = stats.ttest_rel(a, b)
        t_stat = float(tres.statistic)
        p_raw = float(tres.pvalue)

    s_a = float(np.std(a, ddof=1)) if n > 1 else 0.0
    s_b = float(np.std(b, ddof=1)) if n > 1 else 0.0
    s_av = math.sqrt(0.5 * (s_a * s_a + s_b * s_b))
    hedges_g_av = (mean_diff / s_av) * (1.0 - 3.0 / (4.0 * (2 * n - 2) - 1.0)) if (s_av > 0.0 and n > 2) else 0.0

    if better == "higher":
        wins = int(np.sum(diff > 1e-12))
    else:
        wins = int(np.sum(diff < -1e-12))
    ties = int(np.sum(np.abs(diff) <= 1e-12))

    # One-sided t-test against -5.0 margin for non-inferiority (H0: diff <= -5.0 vs H1: diff > -5.0)
    if sd_diff > 0.0 and n > 1:
        t_ni5 = (mean_diff - (-5.0)) / (sd_diff / math.sqrt(n))
        p_ni5 = float(1.0 - stats.t.cdf(t_ni5, df=n - 1))
    else:
        t_ni5 = float("inf") if mean_diff > -5.0 else float("-inf")
        p_ni5 = 0.0 if mean_diff > -5.0 else 1.0

    return {
        "n": n,
        "mean_a": float(np.mean(a)),
        "ci95_a": mean_ci95(a.tolist())["ci95"],
        "mean_b": float(np.mean(b)),
        "ci95_b": mean_ci95(b.tolist())["ci95"],
        "diff": mean_diff,
        "ci95_diff": ci95,
        "low": mean_diff - ci95,
        "high": mean_diff + ci95,
        "t_stat": t_stat,
        "p_raw": p_raw,
        "hedges_g_av": hedges_g_av,
        "wins": wins,
        "ties": ties,
        "ni_5pp_p": p_ni5,
        "ni_5pp_pass": bool(p_ni5 < 0.025),
    }


def summarize_mode_table(records: Dict[str, Dict[int, Dict[str, Any]]], modes: List[str], metrics: List[Tuple[str, str]]) -> Dict[str, Dict[str, Dict[str, float]]]:
    out: Dict[str, Dict[str, Dict[str, float]]] = {}
    for m in modes:
        out[m] = {}
        for key, mode_filter in metrics:
            vals = [records[m][s].get(key, float("nan")) for s in EXP_SEEDS_11]
            if mode_filter == "nonneg":
                vals = [v if (not math.isnan(v) and v >= 0.0) else float("nan") for v in vals]
            out[m][key] = mean_ci95(vals)
    return out


def summarize_waves(records: Dict[str, Dict[int, Dict[str, Any]]], mode: str) -> Dict[str, Any]:
    wave_names = [
        "CTRL_GROUP_THROTTLE_W1", "CTRL_GROUP_NOMINAL_W1",
        "CTRL_GROUP_THROTTLE_W2", "CTRL_GROUP_NOMINAL_W2",
        "CTRL_GROUP_THROTTLE_W3", "CTRL_GROUP_NOMINAL_W3",
        "CTRL_UPDATE_THROTTLE_W1", "CTRL_UPDATE_NOMINAL_W1",
        "CTRL_UPDATE_THROTTLE_W2", "CTRL_UPDATE_NOMINAL_W2",
        "CTRL_UPDATE_THROTTLE_W3", "CTRL_UPDATE_NOMINAL_W3",
        "CTRL_THROTTLE_W1", "CTRL_NOMINAL_W1",
        "CTRL_THROTTLE_W2", "CTRL_NOMINAL_W2",
        "CTRL_THROTTLE_W3", "CTRL_NOMINAL_W3",
    ]
    out: Dict[str, Any] = {}
    for wn in wave_names:
        q50s, q90s, delivs, targets = [], [], [], []
        for s in EXP_SEEDS_11:
            w = records[mode][s]["waves"].get(wn)
            if w is not None:
                targets.append(w["targets"])
                delivs.append(w["deliv"])
                if w["q50"] >= 0:
                    q50s.append(w["q50"])
                if w["q90"] >= 0:
                    q90s.append(w["q90"])
        if targets:
            out[wn] = {
                "targets": mean_ci95(targets),
                "deliv": mean_ci95(delivs),
                "q50_s": mean_ci95(q50s),
                "q90_s": mean_ci95(q90s),
            }
    return out


def fmt_mc(d: Dict[str, float], prec: int = 2) -> str:
    if d["n"] == 0 or math.isnan(d["mean"]):
        return "n/a"
    if d["n"] == 1 or math.isnan(d["ci95"]):
        return f"{d['mean']:.{prec}f} (n={d['n']})"
    return f"{d['mean']:.{prec}f} ± {d['ci95']:.{prec}f}"


def fmt_ps(p: Dict[str, Any], prec: int = 2, unit: str = "") -> str:
    u = f" {unit}" if unit else ""
    return (
        f"{p['diff']:+.{prec}f} ± {p['ci95_diff']:.{prec}f}{u} "
        f"[{p['low']:+.{prec}f}, {p['high']:+.{prec}f}] "
        f"(p_unadj={p['p_raw']:.3e}, g={p['hedges_g_av']:+.2f}, {p['wins']}/{p['n']})"
    )


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    ctrl_stress_modes = ["M0b", "M0e-20", "M0e-25", "M1-U", "M2"]
    ctrl_stress = load_exploratory_dir(REPORTS_BASE / "controls_stress", ctrl_stress_modes)
    ctrl_benign = load_exploratory_dir(REPORTS_BASE / "controls_benign", ctrl_stress_modes)
    loc_stress = load_exploratory_dir(REPORTS_BASE / "m2_localonly_stress", ["M2-LocalOnly"])
    loc_benign = load_exploratory_dir(REPORTS_BASE / "m2_localonly_benign", ["M2-LocalOnly"])
    ctrl_stress["M2-LocalOnly"] = loc_stress["M2-LocalOnly"]
    ctrl_benign["M2-LocalOnly"] = loc_benign["M2-LocalOnly"]

    grp_stress = load_exploratory_dir(REPORTS_BASE / "groupflood_stress", GROUPFLOOD_MODES)
    grp_benign = load_exploratory_dir(REPORTS_BASE / "groupflood_benign", GROUPFLOOD_MODES)

    metrics_spec = [
        ("alive_nodes_end", "all"),
        ("alive_frac_pct", "all"),
        ("avg_energy_soc", "all"),
        ("critical_delivery_pct", "all"),
        ("unique_critical_lost", "all"),
        ("bulk_delivery_pct", "all"),
        ("overhead_ratio", "all"),
        ("aoi_mean_sec", "all"),
        ("pre_fault_pct", "all"),
        ("in_fault_pct", "all"),
        ("post_fault_pct", "all"),
        ("recovery_ratio", "all"),
        ("recovery_time_to_95pct_sec", "nonneg"),
        ("total_ctrl_tx_J", "all"),
        ("total_energy_J", "all"),
        ("downlink_created", "all"),
        ("downlink_delivered", "all"),
        ("downlink_delivery_prob", "all"),
        ("downlink_t90_sec", "nonneg"),
        ("downlink_90pct_reached", "all"),
        ("eventA_bulk_pct", "all"),
        ("eventA_crit_pct", "all"),
        ("eventA_crit_unique_lost", "all"),
        ("eventA_nodesec_q90", "all"),
        ("eventA_cov_pct", "all"),
        ("eventA_stale_throttle_nodesec", "all"),
        ("eventA_ctrl_tx_J", "all"),
        ("eventB_bulk_pct", "all"),
        ("eventB_crit_pct", "all"),
        ("eventB_nodesec_q90", "all"),
        ("eventB_cov_pct", "all"),
        ("eventB_stale_throttle_nodesec", "all"),
        ("eventB_ctrl_tx_J", "all"),
        ("eventC_bulk_pct", "all"),
        ("eventC_crit_pct", "all"),
        ("eventC_crit_unique_lost", "all"),
        ("eventC_nodesec_q90", "all"),
        ("eventC_depleted_new", "all"),
        ("eventC_cov_pct", "all"),
        ("eventC_stale_throttle_nodesec", "all"),
        ("eventC_ctrl_tx_J", "all"),
    ]

    summary: Dict[str, Any] = {
        "campaign": "v1.0-exp-controls",
        "seeds": EXP_SEEDS_11,
        "runs_verified": 176,
        "p_value_note": "All exploratory paired p-values (n=11, df=10) are unadjusted (no multiplicity correction).",
        "tables": {
            "controls_stress": summarize_mode_table(ctrl_stress, CONTROLS_MODES, metrics_spec),
            "controls_benign": summarize_mode_table(ctrl_benign, CONTROLS_MODES, metrics_spec),
            "groupflood_stress": summarize_mode_table(grp_stress, GROUPFLOOD_MODES, metrics_spec),
            "groupflood_benign": summarize_mode_table(grp_benign, GROUPFLOOD_MODES, metrics_spec),
        },
        "waves": {
            "M1-U_stress": summarize_waves(ctrl_stress, "M1-U"),
            "M1-U_benign": summarize_waves(ctrl_benign, "M1-U"),
            "M1-F-Group_stress": summarize_waves(grp_stress, "M1-F-Group"),
            "M1-F-Group_benign": summarize_waves(grp_benign, "M1-F-Group"),
        },
        "paired": {},
        "audit_replay": {},
    }

    # Paired contrasts within controls (matched 7211..7307)
    ctrl_pairs = [
        ("M2_vs_M0e-20", "M2", "M0e-20"),
        ("M2_vs_M0e-25", "M2", "M0e-25"),
        ("M2_vs_M0b", "M2", "M0b"),
        ("M2_vs_M1-U", "M2", "M1-U"),
        ("M2_vs_M2-LocalOnly", "M2", "M2-LocalOnly"),
        ("M2-LocalOnly_vs_M0e-20", "M2-LocalOnly", "M0e-20"),
        ("M2-LocalOnly_vs_M0e-25", "M2-LocalOnly", "M0e-25"),
        ("M2-LocalOnly_vs_M1-U", "M2-LocalOnly", "M1-U"),
        ("M2-LocalOnly_vs_M0b", "M2-LocalOnly", "M0b"),
        ("M0e-25_vs_M1-U", "M0e-25", "M1-U"),
        ("M0e-25_vs_M0b", "M0e-25", "M0b"),
        ("M0e-20_vs_M0b", "M0e-20", "M0b"),
        ("M0e-25_vs_M0e-20", "M0e-25", "M0e-20"),
    ]
    pair_metrics = [
        ("alive_frac_pct", "higher"),
        ("alive_nodes_end", "higher"),
        ("bulk_delivery_pct", "higher"),
        ("critical_delivery_pct", "higher"),
        ("eventA_bulk_pct", "higher"),
        ("eventA_stale_throttle_nodesec", "lower"),
        ("eventB_bulk_pct", "higher"),
        ("eventC_bulk_pct", "higher"),
        ("total_ctrl_tx_J", "lower"),
    ]
    for label, ma, mb in ctrl_pairs:
        summary["paired"][f"controls_stress_{label}"] = {
            m: paired_stats([ctrl_stress[ma][s][m] for s in EXP_SEEDS_11], [ctrl_stress[mb][s][m] for s in EXP_SEEDS_11], better)
            for m, better in pair_metrics
        }
        summary["paired"][f"controls_benign_{label}"] = {
            m: paired_stats([ctrl_benign[ma][s][m] for s in EXP_SEEDS_11], [ctrl_benign[mb][s][m] for s in EXP_SEEDS_11], better)
            for m, better in pair_metrics
        }

    # Group-flood contrasts on matched 7211..7307:
    summary["paired"]["groupflood_stress_M1-F-Group_vs_M2"] = {
        m: paired_stats([grp_stress["M1-F-Group"][s][m] for s in EXP_SEEDS_11], [grp_stress["M2"][s][m] for s in EXP_SEEDS_11], better)
        for m, better in pair_metrics
    }
    summary["paired"]["groupflood_benign_M1-F-Group_vs_M2"] = {
        m: paired_stats([grp_benign["M1-F-Group"][s][m] for s in EXP_SEEDS_11], [grp_benign["M2"][s][m] for s in EXP_SEEDS_11], better)
        for m, better in pair_metrics
    }
    summary["paired"]["matched7211_stress_M1-F-Group_vs_M1-U"] = {
        m: paired_stats([grp_stress["M1-F-Group"][s][m] for s in EXP_SEEDS_11], [ctrl_stress["M1-U"][s][m] for s in EXP_SEEDS_11], better)
        for m, better in pair_metrics + [("eventA_cov_pct", "higher"), ("eventB_cov_pct", "higher"), ("eventC_cov_pct", "higher")]
    }
    summary["paired"]["matched7211_benign_M1-F-Group_vs_M1-U"] = {
        m: paired_stats([grp_benign["M1-F-Group"][s][m] for s in EXP_SEEDS_11], [ctrl_benign["M1-U"][s][m] for s in EXP_SEEDS_11], better)
        for m, better in pair_metrics
    }
    summary["paired"]["sanity_M2_groupflood_vs_controls_stress"] = {
        m: paired_stats([grp_stress["M2"][s][m] for s in EXP_SEEDS_11], [ctrl_stress["M2"][s][m] for s in EXP_SEEDS_11], better)
        for m, better in [("alive_frac_pct", "higher"), ("bulk_delivery_pct", "higher"), ("critical_delivery_pct", "higher")]
    }

    # Audit replay verification on all 6 exploratory report folders
    for folder in (
        "controls_stress", "controls_benign",
        "m2_localonly_stress", "m2_localonly_benign",
        "groupflood_stress", "groupflood_benign",
    ):
        summary["audit_replay"][folder] = verify_audit_replay(REPORTS_BASE / folder, SCENARIOS_DIR)

    # Save JSON
    json_path = OUT_DIR / "exploratory_v1_0_controls_summary.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    # Generate concise Markdown tables
    lines: List[str] = []
    lines.append("# Post-Freeze Exploratory Controls Summary (`v1.0-exp-controls`)")
    lines.append("")
    lines.append(f"- **Seed partition:** `{EXP_SEEDS_11}` (`n = 11` fresh primes `> 7207`, completely disjoint from `v1.0-tnsm-freeze` `7103..7207`)")
    lines.append(f"- **Total runs verified:** `{summary['runs_verified']}/176` (`55 + 55 + 11 + 11 + 22 + 22`)")
    lines.append("- **Inference note:** All `p_unadj` values below are unadjusted paired `t`-test exploratory checks (`n = 11`, `df = 10`, no multiplicity correction).")
    lines.append("")

    def add_global_table(title: str, tbl: Dict[str, Any], modes: List[str]) -> None:
        lines.append(f"## {title}")
        lines.append("")
        lines.append("| Mode | Alive /126 | Survival (%) | End SoC | Crit Deliv (%) | Uniq Crit Lost | Bulk Deliv (%) | Pre-Fault (%) | In-Fault (%) | Post-Fault (%) | Recovery Ratio | Ctrl Tx (J) | T90 (s) |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for m in modes:
            d = tbl[m]
            lines.append(
                f"| `{m}` | {fmt_mc(d['alive_nodes_end'], 1)} | {fmt_mc(d['alive_frac_pct'], 2)} | {fmt_mc(d['avg_energy_soc'], 4)} | "
                f"{fmt_mc(d['critical_delivery_pct'], 2)} | {fmt_mc(d['unique_critical_lost'], 2)} | {fmt_mc(d['bulk_delivery_pct'], 2)} | "
                f"{fmt_mc(d['pre_fault_pct'], 2)} | {fmt_mc(d['in_fault_pct'], 2)} | {fmt_mc(d['post_fault_pct'], 2)} | "
                f"{fmt_mc(d['recovery_ratio'], 3)} | {fmt_mc(d['total_ctrl_tx_J'], 1)} | {fmt_mc(d['downlink_t90_sec'], 1)} |"
            )
        lines.append("")

    add_global_table("1A. Energy-Only & Hybrid-Isolation Controls — Stress (`n=11`)", summary["tables"]["controls_stress"], CONTROLS_MODES)
    add_global_table("1B. Energy-Only & Hybrid-Isolation Controls — Benign (`n=11`)", summary["tables"]["controls_benign"], CONTROLS_MODES)

    lines.append("## 1C. Event Windows A, B, C — Controls Stress (`n=11`)")
    lines.append("")
    lines.append("| Mode | EvA Bulk (%) | EvA Stale (node·s) | EvA Cov (%) | EvB Bulk (%) | EvB Stale (node·s) | EvB Cov (%) | EvC Bulk (%) | EvC New Depl | EvC Cov (%) |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for m in CONTROLS_MODES:
        d = summary["tables"]["controls_stress"][m]
        lines.append(
            f"| `{m}` | {fmt_mc(d['eventA_bulk_pct'], 2)} | {fmt_mc(d['eventA_stale_throttle_nodesec'], 2)} | {fmt_mc(d['eventA_cov_pct'], 1)} | "
            f"{fmt_mc(d['eventB_bulk_pct'], 2)} | {fmt_mc(d['eventB_stale_throttle_nodesec'], 2)} | {fmt_mc(d['eventB_cov_pct'], 1)} | "
            f"{fmt_mc(d['eventC_bulk_pct'], 2)} | {fmt_mc(d['eventC_depleted_new'], 2)} | {fmt_mc(d['eventC_cov_pct'], 1)} |"
        )
    lines.append("")

    add_global_table("2A. Group-Addressed Control Flooding (`M1-F-Group`) — Stress (`n=11`)", summary["tables"]["groupflood_stress"], GROUPFLOOD_MODES)
    add_global_table("2B. Group-Addressed Control Flooding (`M1-F-Group`) — Benign (`n=11`)", summary["tables"]["groupflood_benign"], GROUPFLOOD_MODES)

    lines.append("## 2C. Group-Addressed Control Flooding — Event Windows & Wave Adoption (`n=11`)")
    lines.append("")
    lines.append("| Wave Tag | Targets | Delivered | Median q50 (s) | T90 q90 (s) |")
    lines.append("|---|---:|---:|---:|---:|")
    for mode_lbl in ("M1-U_stress", "M1-F-Group_stress"):
        for wn, wd in summary["waves"][mode_lbl].items():
            lines.append(
                f"| `{mode_lbl}: {wn}` | {fmt_mc(wd['targets'], 1)} | {fmt_mc(wd['deliv'], 1)} | {fmt_mc(wd['q50_s'], 1)} | {fmt_mc(wd['q90_s'], 1)} |"
            )
    lines.append("")

    lines.append("## 3. Key Exploratory Paired Contrasts (Matched `7211..7307`, `df = 10`, Unadjusted)")
    lines.append("")
    lines.append("| Contrast | Scenario | Survival Δ (pp) | Stress/Benign Bulk Δ (pp) | Event A Stale Δ (node·s) | Event C Bulk Δ (pp) | Ctrl Tx Energy Δ (J) |")
    lines.append("|---|---|---|---|---|---|---|")
    contrast_rows = [
        ("controls_stress_M2_vs_M0e-20", "M2 vs M0e-20", "Stress"),
        ("controls_benign_M2_vs_M0e-20", "M2 vs M0e-20", "Benign"),
        ("controls_stress_M2_vs_M0e-25", "M2 vs M0e-25", "Stress"),
        ("controls_benign_M2_vs_M0e-25", "M2 vs M0e-25", "Benign"),
        ("controls_stress_M2-LocalOnly_vs_M0e-20", "M2-LocalOnly vs M0e-20", "Stress"),
        ("controls_benign_M2-LocalOnly_vs_M0e-20", "M2-LocalOnly vs M0e-20", "Benign"),
        ("controls_stress_M2-LocalOnly_vs_M0e-25", "M2-LocalOnly vs M0e-25", "Stress"),
        ("controls_stress_M2_vs_M2-LocalOnly", "M2 vs M2-LocalOnly", "Stress"),
        ("controls_benign_M2_vs_M2-LocalOnly", "M2 vs M2-LocalOnly", "Benign"),
        ("controls_stress_M2-LocalOnly_vs_M1-U", "M2-LocalOnly vs M1-U", "Stress"),
        ("controls_stress_M0e-25_vs_M1-U", "M0e-25 vs M1-U", "Stress"),
        ("controls_benign_M0e-25_vs_M1-U", "M0e-25 vs M1-U", "Benign"),
        ("controls_stress_M0e-25_vs_M0b", "M0e-25 vs M0b", "Stress"),
        ("controls_stress_M2_vs_M1-U", "M2 vs M1-U", "Stress"),
        ("groupflood_stress_M1-F-Group_vs_M2", "M1-F-Group vs M2", "Stress"),
        ("groupflood_benign_M1-F-Group_vs_M2", "M1-F-Group vs M2", "Benign"),
        ("matched7211_stress_M1-F-Group_vs_M1-U", "M1-F-Group vs M1-U", "Stress"),
        ("matched7211_benign_M1-F-Group_vs_M1-U", "M1-F-Group vs M1-U", "Benign"),
    ]
    for key, label, scen in contrast_rows:
        p = summary["paired"][key]
        lines.append(
            f"| `{label}` | {scen} | {fmt_ps(p['alive_frac_pct'], 2, 'pp')} | {fmt_ps(p['bulk_delivery_pct'], 2, 'pp')} | "
            f"{fmt_ps(p['eventA_stale_throttle_nodesec'], 2)} | {fmt_ps(p['eventC_bulk_pct'], 2, 'pp')} | "
            f"{fmt_ps(p['total_ctrl_tx_J'], 1, 'J')} |"
        )
    lines.append("")

    lines.append("## 4. Exploratory Audit Replay Verification")
    lines.append("")
    lines.append("| Folder | Audit Files | Total Rows | Traceable (%) | RL Mask Match (%) | RL Action Match (%) | Static/SRL Checked | Static/SRL Match (%) |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for folder, rep in summary["audit_replay"].items():
        lines.append(
            f"| `{folder}` | {rep['audit_files']} | {rep['total_rows']} | {rep['traceable_pct']:.2f}% | "
            f"{rep['rl_mask_match_pct']:.2f}% | {rep['rl_action_match_pct']:.2f}% | {rep['static_checked']} | {rep['static_ari_match_pct']:.2f}% |"
        )
    lines.append("")

    md_path = OUT_DIR / "exploratory_v1_0_controls_summary.md"
    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"Wrote {json_path} and {md_path}")
    for folder in ("controls_stress", "controls_benign", "m2_localonly_stress", "m2_localonly_benign", "groupflood_stress", "groupflood_benign"):
        print(f"Replay audit {folder}: {summary['audit_replay'][folder]}")


if __name__ == "__main__":
    main()
