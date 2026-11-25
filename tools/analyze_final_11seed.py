#!/usr/bin/env python3
"""
analyze_final_11seed.py
Read-only statistical evaluation, Holm-Bonferroni confirmatory testing,
TOST equivalence testing, Comparison A multi-regime/tier/parity summary,
and deterministic RQ4 audit replay verification over the held-out 11 prime
seeds (7103..7207) in reports/final_11seed/.
"""

import argparse
import json
import math
import re
import sys
from pathlib import Path

import numpy as np
from scipy.stats import t as student_t

TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))
from evaluate_autonomic_framework import apply_holm_bonferroni, verify_audit_replay

SEEDS_11 = [7103, 7109, 7121, 7127, 7129, 7151, 7159, 7177, 7187, 7193, 7207]
SEED_SET = set(SEEDS_11)
SEED_SUFFIX_RE = re.compile(r"_(\d+)_(DtnmaRlMetricsReport|MessageStatsReport)\.txt$")

COMPB_MODES = [
    ("M0a", "SprayAndWaitRouter"),
    ("M0b", "DtnmaM0bStaticRouter"),
    ("M0c", "DtnmaM0cV1GuardRouter"),
    ("M0d", "DtnmaM0dConservativeRouter"),
    ("M1-U", "DtnmaM1RemoteRouter"),
    ("M1-F", "DtnmaM1FloodRouter"),
    ("M2", "DtnmaM2SrlRouter"),
    ("M3", "DtnmaM3RlRouter"),
]

COMPA_PAIRS = [
    ("SnW", "SprayAndWaitRouter", "ManagedSprayAndWaitRouter"),
    ("PRoPHETv2", "ProphetV2Router", "ManagedProphetV2Router"),
    ("MaxProp", "MaxPropRouter", "ManagedMaxPropRouter"),
    ("Epidemic", "EpidemicRouter", "ManagedEpidemicRouter"),
]

TIERS = [
    "TIER1_PROTECTION_ONLY",
    "TIER2_PROTECTION_INGRESS_ENERGY",
    "TIER3_FULL_WITH_REPLICA_CAPS",
]

EXPECTED_COUNTS = [
    ("compB_multitimescale", 88),
    ("compB_benign", 88),
    ("compA_equivalence", 22),
    ("compA_equivalence_default", 22),
    ("compA_vdtn_overload", 88),
    ("compA_energy_blackout", 99),
    ("compA_benign", 88),
    ("compA_tiers", 132),
]


def parse_kv_file(path: Path):
    data = {}
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#") or ": " not in s:
                continue
            k, v = s.split(": ", 1)
            k = k.strip()
            v = v.strip()
            try:
                val = float(v)
                if not math.isnan(val) and not math.isinf(val):
                    data[k] = val
            except ValueError:
                pass
    return data


def load_campaign_records(campaign_dir: Path, prefix_strip: str):
    """Loads { (router_or_key, seed): kv_dict } strictly for SEEDS_11."""
    records = {}
    for p in sorted(campaign_dir.glob("*_DtnmaRlMetricsReport.txt")):
        m = SEED_SUFFIX_RE.search(p.name)
        if not m:
            continue
        seed = int(m.group(1))
        if seed not in SEED_SET:
            continue
        if p.stat().st_size == 0:
            continue
        stem = p.name[: m.start()]
        if stem.startswith(prefix_strip):
            key = stem[len(prefix_strip) :]
        else:
            key = stem
        kv = parse_kv_file(p)
        if "alive_nodes_end" in kv and "n_hosts" in kv and kv["n_hosts"] > 0:
            kv["alive_frac"] = kv["alive_nodes_end"] / kv["n_hosts"]
        records[(key, seed)] = kv
    return records


def mean_ci95(vals):
    arr = np.asarray([v for v in vals if v is not None and not math.isnan(v)], dtype=float)
    n = len(arr)
    if n == 0:
        return {"n": 0, "mean": float("nan"), "sd": float("nan"), "ci95": float("nan"), "lo": float("nan"), "hi": float("nan")}
    mean = float(np.mean(arr))
    if n == 1:
        return {"n": 1, "mean": mean, "sd": 0.0, "ci95": 0.0, "lo": mean, "hi": mean}
    sd = float(np.std(arr, ddof=1))
    se = sd / math.sqrt(n)
    tcrit = float(student_t.ppf(0.975, n - 1))
    ci = tcrit * se
    return {"n": n, "mean": mean, "sd": sd, "ci95": ci, "lo": mean - ci, "hi": mean + ci}


def paired_stats(vals_a, vals_b, better="higher", scale=1.0):
    a = np.asarray(vals_a, dtype=float) * scale
    b = np.asarray(vals_b, dtype=float) * scale
    assert len(a) == len(b) and len(a) == 11, f"Expected 11 paired seeds, got {len(a)} vs {len(b)}"
    d = a - b
    n = len(d)
    df = n - 1
    ma = float(np.mean(a))
    mb = float(np.mean(b))
    sda = float(np.std(a, ddof=1))
    sdb = float(np.std(b, ddof=1))
    md = float(np.mean(d))
    sdd = float(np.std(d, ddof=1))
    se = sdd / math.sqrt(n)
    tcrit95 = float(student_t.ppf(0.975, df))
    ci95 = tcrit95 * se
    if se > 1e-12:
        t_val = md / se
        p_val = float(2.0 * student_t.sf(abs(t_val), df))
    else:
        t_val = float("inf") if md > 0 else (float("-inf") if md < 0 else 0.0)
        p_val = 0.0 if abs(md) > 1e-12 else 1.0
    pooled_sd = math.sqrt(0.5 * (sda * sda + sdb * sdb))
    if pooled_sd > 1e-12:
        d_av = md / pooled_sd
        j_corr = 1.0 - (3.0 / (4.0 * df - 1.0))
        hedges_gav = d_av * j_corr
    else:
        hedges_gav = 0.0
    if better == "lower":
        wins = int(np.sum(d < -1e-12))
    else:
        wins = int(np.sum(d > 1e-12))
    ties = int(np.sum(np.abs(d) <= 1e-12))
    return {
        "n": n,
        "df": df,
        "mean_a": ma,
        "sd_a": sda,
        "mean_b": mb,
        "sd_b": sdb,
        "mean_diff": md,
        "sd_diff": sdd,
        "ci95": ci95,
        "lo95": md - ci95,
        "hi95": md + ci95,
        "t": t_val,
        "p_val": p_val,
        "hedges_gav": hedges_gav,
        "wins": wins,
        "ties": ties,
    }


def extract_paired(records, key_a, key_b, metric, filter_nonneg=False):
    va, vb = [], []
    for s in SEEDS_11:
        ra = records.get((key_a, s), {})
        rb = records.get((key_b, s), {})
        xa = ra.get(metric, float("nan"))
        xb = rb.get(metric, float("nan"))
        if filter_nonneg and ((xa is not None and xa < 0) or (xb is not None and xb < 0)):
            xa = float("nan")
            xb = float("nan")
        va.append(xa)
        vb.append(xb)
    return va, vb


def count_noncomment_lines(path: Path) -> int:
    count = 0
    in_block = False
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for raw in f:
            s = raw.strip()
            if not s:
                continue
            if in_block:
                if "*/" in s:
                    in_block = False
                continue
            if s.startswith("/*"):
                if "*/" not in s:
                    in_block = True
                continue
            if s.startswith("//") or s.startswith("#"):
                continue
            count += 1
    return count


def analyze_equivalence(rep_dir: Path, prefix: str, do_tost: bool = False):
    stats_exact = 0
    rl_core_exact = 0
    p_bulk, m_bulk = [], []
    p_crit, m_crit = [], []
    max_bulk_abs_diff = 0.0
    max_crit_lost_m = 0
    diff_examples = []

    rl_ignore = {
        "decision_cpu_us_mean",
        "beacon_overhead_bytes",
        "control_bytes_total",
        "control_to_payload_ratio",
        "profile_0_frac",
        "profile_1_frac",
        "profile_2_frac",
        "profile_3_frac",
        "profile_4_frac",
        "profile_5_frac",
        "profile_6_frac",
        "profile_7_frac",
    }

    for s in SEEDS_11:
        ps_path = rep_dir / f"{prefix}PrioritySprayAndWaitRouter_{s}_MessageStatsReport.txt"
        ms_path = rep_dir / f"{prefix}ManagedSprayAndWaitRouter_{s}_MessageStatsReport.txt"
        ps_lines = [l for l in ps_path.read_text(encoding="utf-8").splitlines() if not l.startswith("Message stats for scenario")]
        ms_lines = [l for l in ms_path.read_text(encoding="utf-8").splitlines() if not l.startswith("Message stats for scenario")]
        if ps_lines == ms_lines:
            stats_exact += 1

        pr = parse_kv_file(rep_dir / f"{prefix}PrioritySprayAndWaitRouter_{s}_DtnmaRlMetricsReport.txt")
        mr = parse_kv_file(rep_dir / f"{prefix}ManagedSprayAndWaitRouter_{s}_DtnmaRlMetricsReport.txt")
        diffs = [
            (k, pr.get(k), mr.get(k))
            for k in (set(pr) | set(mr))
            if k not in rl_ignore and pr.get(k) != mr.get(k)
        ]
        if not diffs:
            rl_core_exact += 1
        elif len(diff_examples) < 3:
            diff_examples.append((s, diffs[:6]))

        pb = pr["bulk_delivery_prob"]
        mb = mr["bulk_delivery_prob"]
        p_bulk.append(pb)
        m_bulk.append(mb)
        p_crit.append(pr["critical_delivery_prob"])
        m_crit.append(mr["critical_delivery_prob"])
        max_bulk_abs_diff = max(max_bulk_abs_diff, abs(mb - pb))
        max_crit_lost_m = max(max_crit_lost_m, int(round(mr["unique_critical_lost"])))

    res = {
        "n_seeds": len(SEEDS_11),
        "msg_stats_exact": stats_exact,
        "rl_core_exact": rl_core_exact,
        "max_crit_lost_managed": max_crit_lost_m,
        "max_bulk_abs_diff_pp": 100.0 * max_bulk_abs_diff,
        "bulk_paired_pp": paired_stats(m_bulk, p_bulk, better="higher", scale=100.0),
        "crit_paired_pp": paired_stats(m_crit, p_crit, better="higher", scale=100.0),
    }
    if do_tost:
        d = (np.asarray(m_bulk) - np.asarray(p_bulk)) * 100.0  # in percentage points
        n = len(d)
        df = n - 1
        md = float(np.mean(d))
        sd = float(np.std(d, ddof=1))
        se = sd / math.sqrt(n)
        t90 = float(student_t.ppf(0.95, df))
        margin = 1.0  # +/- 1.0 pp
        t_low = (md - (-margin)) / se
        t_upp = (margin - md) / se
        p_low = float(student_t.sf(t_low, df))
        p_upp = float(student_t.sf(t_upp, df))
        p_tost = max(p_low, p_upp)
        res["tost_1pp"] = {
            "margin_pp": margin,
            "mean_diff_pp": md,
            "sd_diff_pp": sd,
            "se_pp": se,
            "ci90_lo_pp": md - t90 * se,
            "ci90_hi_pp": md + t90 * se,
            "t_lower": t_low,
            "p_lower": p_low,
            "t_upper": t_upp,
            "p_upper": p_upp,
            "p_tost": p_tost,
            "equivalent_at_alpha_05": p_tost < 0.05,
        }
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--final-dir", default="n:/R&D/cog/the-one/reports/final_11seed")
    ap.add_argument("--adtnm-dir", default="n:/R&D/cog/ADTNM")
    ap.add_argument("--out-json", default="n:/R&D/cog/ADTNM/figures/final_11seed_summary.json")
    ap.add_argument("--out-md", default="n:/R&D/cog/ADTNM/figures/final_11seed_tables.md")
    ap.add_argument("--skip-replay", action="store_true")
    args = ap.parse_args()

    final_dir = Path(args.final_dir)
    adtnm_dir = Path(args.adtnm_dir)

    # 1. Verify 627/627 valid files
    integrity = {}
    total_valid = 0
    for slug, exp in EXPECTED_COUNTS:
        d = final_dir / slug
        stats_ok = [
            f for f in d.glob("*_MessageStatsReport.txt")
            if f.stat().st_size > 0 and (m := SEED_SUFFIX_RE.search(f.name)) and int(m.group(1)) in SEED_SET
        ]
        rl_ok = [
            f for f in d.glob("*_DtnmaRlMetricsReport.txt")
            if f.stat().st_size > 0 and (m := SEED_SUFFIX_RE.search(f.name)) and int(m.group(1)) in SEED_SET
        ]
        assert len(stats_ok) == exp and len(rl_ok) == exp, f"{slug}: stats={len(stats_ok)}/{exp}, rl={len(rl_ok)}/{exp}"
        integrity[slug] = {"stats": len(stats_ok), "rl": len(rl_ok), "expected": exp}
        total_valid += len(stats_ok)

    # 2. Load Comparison B Stress & Benign
    stress = load_campaign_records(final_dir / "compB_multitimescale", "CompB_MultiScale_")
    benign = load_campaign_records(final_dir / "compB_benign", "CompB_Benign_")

    # Primary Confirmatory Family (m=4 Holm-Bonferroni)
    h1_va, h1_vb = extract_paired(stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "eventA_stale_throttle_nodesec")
    h1 = paired_stats(h1_va, h1_vb, better="lower", scale=1.0)
    h1.update({"id": "H1", "comparison": "M2 vs M1-U", "window": "Stress Event A (3600-3900s)", "endpoint": "eventA_stale_throttle_nodesec (lower better)"})

    h2_va, h2_vb = extract_paired(stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "eventC_bulk_deliv")
    h2 = paired_stats(h2_va, h2_vb, better="higher", scale=100.0)
    h2.update({"id": "H2", "comparison": "M2 vs M1-U", "window": "Stress Event C (21600-28800s)", "endpoint": "eventC_bulk_deliv (pp, higher better)"})

    h3_va, h3_vb = extract_paired(stress, "DtnmaM2SrlRouter", "DtnmaM0bStaticRouter", "alive_frac")
    h3 = paired_stats(h3_va, h3_vb, better="higher", scale=100.0)
    h3.update({"id": "H3", "comparison": "M2 vs M0b", "window": "Stress 12h", "endpoint": "alive_nodes_end / N (pp, higher better)"})

    h4_va, h4_vb = extract_paired(benign, "DtnmaM2SrlRouter", "DtnmaM0cV1GuardRouter", "bulk_delivery_prob")
    h4 = paired_stats(h4_va, h4_vb, better="higher", scale=100.0)
    h4.update({"id": "H4", "comparison": "M2 vs M0c", "window": "Benign 12h", "endpoint": "bulk_delivery_prob (pp, higher better)"})

    family = apply_holm_bonferroni([h1, h2, h3, h4])

    # CompB Exploratory Summaries (n=11)
    compb_summary = {"stress": {}, "benign": {}, "per_event": {}, "paired_exploratory": {}}
    global_metrics = [
        "critical_delivery_prob", "unique_critical_lost", "critical_buffer_dropped",
        "critical_buffer_dropped_acked", "bulk_delivery_prob", "alive_nodes_end",
        "alive_frac", "avg_energy_soc", "total_ctrl_tx_J", "downlink_t90_sec",
        "profile_0_frac", "profile_1_frac", "profile_2_frac", "profile_3_frac", "profile_4_frac",
    ]
    for label, rec in [("stress", stress), ("benign", benign)]:
        for tag, cls in COMPB_MODES:
            rows = [rec[(cls, s)] for s in SEEDS_11]
            compb_summary[label][tag] = {
                m: mean_ci95([r.get(m, float("nan")) for r in rows]) for m in global_metrics
            }
            compb_summary[label][tag]["unique_critical_lost_sum"] = int(sum(r["unique_critical_lost"] for r in rows))
            compb_summary[label][tag]["unique_critical_lost_max"] = int(max(r["unique_critical_lost"] for r in rows))
            compb_summary[label][tag]["critical_buffer_dropped_sum"] = int(sum(r["critical_buffer_dropped"] for r in rows))

    ev_metrics = [
        "crit_deliv", "crit_evicted", "crit_evicted_acked", "crit_unique_lost",
        "bulk_deliv", "nodesec_q90", "throttle_coverage", "stale_throttle_nodesec",
        "react_med_s", "react_censored_frac", "ctrl_tx_J", "depleted_new",
    ]
    for ev in ["eventA", "eventB", "eventC"]:
        compb_summary["per_event"][ev] = {}
        for tag, cls in COMPB_MODES:
            rows = [stress[(cls, s)] for s in SEEDS_11]
            ev_dict = {}
            for em in ev_metrics:
                key = f"{ev}_{em}"
                vals = [r.get(key, float("nan")) for r in rows]
                if em in ("throttle_coverage", "react_med_s", "react_censored_frac"):
                    vals = [v if v >= 0 else float("nan") for v in vals]
                ev_dict[em] = mean_ci95(vals)
            ev_dict["crit_evicted_sum"] = int(sum(r[f"{ev}_crit_evicted"] for r in rows))
            ev_dict["crit_unique_lost_sum"] = int(sum(r[f"{ev}_crit_unique_lost"] for r in rows))
            compb_summary["per_event"][ev][tag] = ev_dict

    paired_specs = [
        ("M2 vs M1-U Stress alive_frac (pp)", stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "alive_frac", "higher", 100.0),
        ("M2 vs M1-U Stress bulk_deliv (pp)", stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "bulk_delivery_prob", "higher", 100.0),
        ("M2 vs M1-U Benign bulk_deliv (pp)", benign, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "bulk_delivery_prob", "higher", 100.0),
        ("M2 vs M1-U EventA nodesec_q90 (s)", stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "eventA_nodesec_q90", "lower", 1.0),
        ("M2 vs M1-U EventA bulk_deliv (pp)", stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "eventA_bulk_deliv", "higher", 100.0),
        ("M2 vs M1-U EventB bulk_deliv (pp)", stress, "DtnmaM2SrlRouter", "DtnmaM1RemoteRouter", "eventB_bulk_deliv", "higher", 100.0),
        ("M2 vs M0b Benign bulk_deliv (pp)", benign, "DtnmaM2SrlRouter", "DtnmaM0bStaticRouter", "bulk_delivery_prob", "higher", 100.0),
        ("M3 vs M2 Stress crit_deliv (pp)", stress, "DtnmaM3RlRouter", "DtnmaM2SrlRouter", "critical_delivery_prob", "higher", 100.0),
        ("M3 vs M2 Stress bulk_deliv (pp)", stress, "DtnmaM3RlRouter", "DtnmaM2SrlRouter", "bulk_delivery_prob", "higher", 100.0),
        ("M3 vs M2 Stress alive_nodes", stress, "DtnmaM3RlRouter", "DtnmaM2SrlRouter", "alive_nodes_end", "higher", 1.0),
        ("M3 vs M2 Benign bulk_deliv (pp)", benign, "DtnmaM3RlRouter", "DtnmaM2SrlRouter", "bulk_delivery_prob", "higher", 100.0),
        ("M1-F vs M1-U Stress downlink_t90 (s)", stress, "DtnmaM1FloodRouter", "DtnmaM1RemoteRouter", "downlink_t90_sec", "lower", 1.0),
        ("M1-F vs M1-U Stress total_ctrl_tx_J", stress, "DtnmaM1FloodRouter", "DtnmaM1RemoteRouter", "total_ctrl_tx_J", "lower", 1.0),
        ("M1-F vs M1-U Stress alive_nodes", stress, "DtnmaM1FloodRouter", "DtnmaM1RemoteRouter", "alive_nodes_end", "higher", 1.0),
    ]
    for pname, rec, ka, kb, met, bet, sc in paired_specs:
        va, vb = extract_paired(rec, ka, kb, met)
        compb_summary["paired_exploratory"][pname] = paired_stats(va, vb, better=bet, scale=sc)

    # 3. Comparison A Regimes + A3 Parity
    compa_regimes = [
        ("energy_blackout", "compA_energy_blackout", "CompA_EnergyBlackout_"),
        ("benign", "compA_benign", "CompA_Benign_"),
        ("vdtn_overload", "compA_vdtn_overload", "CompA_Overload_"),
    ]
    compa_summary = {"regimes": {}, "tiers": {}, "parity_a3": {}}
    ca_metrics = [
        "critical_delivery_prob", "unique_critical_lost", "effective_critical_loss",
        "critical_buffer_dropped", "critical_buffer_dropped_acked",
        "bulk_delivery_prob", "alive_nodes_end", "avg_energy_soc", "max_buffer_occupancy",
    ]
    for rtag, cdir, pfx in compa_regimes:
        rec = load_campaign_records(final_dir / cdir, pfx)
        reg_out = {"routers": {}, "paired_deltas": {}}
        routers_here = []
        for pname, stk, mng in COMPA_PAIRS:
            routers_here.extend([stk, mng])
        if rtag == "energy_blackout":
            routers_here.append("EnergyAwareEpidemicRouter")
        for cls in routers_here:
            rows = [rec[(cls, s)] for s in SEEDS_11]
            d = {m: mean_ci95([r[m] for r in rows]) for m in ca_metrics}
            d["unique_critical_lost_sum"] = int(sum(r["unique_critical_lost"] for r in rows))
            d["unique_critical_lost_max"] = int(max(r["unique_critical_lost"] for r in rows))
            d["effective_critical_loss_sum"] = int(sum(r["effective_critical_loss"] for r in rows))
            d["effective_critical_loss_max"] = int(max(r["effective_critical_loss"] for r in rows))
            d["critical_buffer_dropped_sum"] = int(sum(r["critical_buffer_dropped"] for r in rows))
            reg_out["routers"][cls] = d
        for pname, stk, mng in COMPA_PAIRS:
            reg_out["paired_deltas"][pname] = {
                "crit_pp": paired_stats(*extract_paired(rec, mng, stk, "critical_delivery_prob"), better="higher", scale=100.0),
                "bulk_pp": paired_stats(*extract_paired(rec, mng, stk, "bulk_delivery_prob"), better="higher", scale=100.0),
                "alive_diff": paired_stats(*extract_paired(rec, mng, stk, "alive_nodes_end"), better="higher", scale=1.0),
            }
        compa_summary["regimes"][rtag] = reg_out

        if rtag == "energy_blackout":
            for metric, bet, sc in [
                ("critical_delivery_prob", "higher", 100.0),
                ("bulk_delivery_prob", "higher", 100.0),
                ("alive_nodes_end", "higher", 1.0),
                ("avg_energy_soc", "higher", 100.0),
            ]:
                va, vb = extract_paired(rec, "EnergyAwareEpidemicRouter", "ManagedEpidemicRouter", metric)
                compa_summary["parity_a3"][metric] = paired_stats(va, vb, better=bet, scale=sc)
            compa_summary["parity_a3"]["loc"] = {
                "EnergyAwareEpidemicRouter.java": count_noncomment_lines(adtnm_dir / "src/routing/EnergyAwareEpidemicRouter.java"),
                "rules_vdtn.txt": count_noncomment_lines(adtnm_dir / "scenarios/rules_vdtn.txt"),
            }

    # A2 Tiers
    tiers_rec = load_campaign_records(final_dir / "compA_tiers", "CompA_Tiers_")
    for pname, _, mng in COMPA_PAIRS:
        compa_summary["tiers"][pname] = {}
        for t in TIERS:
            key = f"{mng}_{t}"
            rows = [tiers_rec[(key, s)] for s in SEEDS_11]
            d = {m: mean_ci95([r[m] for r in rows]) for m in ca_metrics}
            d["unique_critical_lost_sum"] = int(sum(r["unique_critical_lost"] for r in rows))
            d["effective_critical_loss_sum"] = int(sum(r["effective_critical_loss"] for r in rows))
            d["critical_buffer_dropped_sum"] = int(sum(r["critical_buffer_dropped"] for r in rows))
            compa_summary["tiers"][pname][t] = d

    # 4. E1 & E2 Equivalence
    e1 = analyze_equivalence(final_dir / "compA_equivalence", "CompA_Equiv_", do_tost=False)
    e2 = analyze_equivalence(final_dir / "compA_equivalence_default", "CompA_EquivDefault_", do_tost=True)

    # 5. R1 Audit Replay
    if args.skip_replay:
        replay = {"skipped": True}
    else:
        replay = {
            "compB_multitimescale": verify_audit_replay(final_dir / "compB_multitimescale", adtnm_dir / "scenarios"),
            "compB_benign": verify_audit_replay(final_dir / "compB_benign", adtnm_dir / "scenarios"),
        }

    summary = {
        "seeds": SEEDS_11,
        "total_runs_verified": total_valid,
        "integrity": integrity,
        "confirmatory_family": family,
        "compB": compb_summary,
        "compA": compa_summary,
        "equivalence": {"E1": e1, "E2": e2},
        "replay_r1": replay,
    }

    Path(args.out_json).parent.mkdir(parents=True, exist_ok=True)
    with open(args.out_json, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    # Generate concise human-readable Markdown summary
    md = []
    md.append("# ADTNM 11-Seed Held-Out Evaluation Summary (`7103..7207`)\n")
    md.append(f"- Verified held-out runs: **{total_valid}/627**\n")
    md.append("\n## 1. Primary Confirmatory Hypotheses (Holm-Bonferroni m = 4, df = 10)\n")
    md.append("| ID | Comparison | Window | Endpoint | Mean A | Mean B | Paired Diff [95% CI] | t | p_raw | p_holm | Hedges g_av | Wins/11 |\n")
    md.append("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")
    for h in family:
        md.append(
            f"| **{h['id']}** | {h['comparison']} | {h['window']} | {h['endpoint']} | "
            f"{h['mean_a']:.4f} | {h['mean_b']:.4f} | {h['mean_diff']:+.4f} +/- {h['ci95']:.4f} [{h['lo95']:+.4f}, {h['hi95']:+.4f}] | "
            f"{h['t']:.3f} | {h['p_val']:.3e} | {h['p_holm']:.3e} | {h['hedges_gav']:+.3f} | {h['wins']}/11 |\n"
        )

    md.append("\n## 2. Comparison B Global Summary (n = 11, Mean +/- 95% CI)\n")
    md.append("| Mode | Stress CrDel | Stress CrLst Sum (CBD) | Stress BkDel | Stress Alive (/126) | Stress CtrlTx (J) | Benign CrDel | Benign BkDel |\n")
    md.append("|---|---:|---:|---:|---:|---:|---:|---:|\n")
    for tag, _ in COMPB_MODES:
        st = compb_summary["stress"][tag]
        bn = compb_summary["benign"][tag]
        md.append(
            f"| `{tag}` | {st['critical_delivery_prob']['mean']:.4f} +/- {st['critical_delivery_prob']['ci95']:.4f} | "
            f"{st['unique_critical_lost_sum']} ({st['critical_buffer_dropped_sum']}) | "
            f"{st['bulk_delivery_prob']['mean']:.4f} +/- {st['bulk_delivery_prob']['ci95']:.4f} | "
            f"{st['alive_nodes_end']['mean']:.1f} +/- {st['alive_nodes_end']['ci95']:.1f} | "
            f"{st['total_ctrl_tx_J']['mean']:.1f} +/- {st['total_ctrl_tx_J']['ci95']:.1f} | "
            f"{bn['critical_delivery_prob']['mean']:.4f} | "
            f"{bn['bulk_delivery_prob']['mean']:.4f} +/- {bn['bulk_delivery_prob']['ci95']:.4f} |\n"
        )

    md.append("\n## 3. Comparison B Per-Event Stress Summary (n = 11)\n")
    for ev in ["eventA", "eventB", "eventC"]:
        md.append(f"\n### {ev}\n")
        md.append("| Mode | CrDel | CrEv / CrEvAck / CrUL (sum) | BkDel | q90 | Stale | Cov | React med (cens) | CtrlTx (J) |\n")
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        for tag, _ in COMPB_MODES:
            ed = compb_summary["per_event"][ev][tag]
            md.append(
                f"| `{tag}` | {ed['crit_deliv']['mean']:.4f} | "
                f"{ed['crit_evicted']['mean']:.1f} / {ed['crit_evicted_acked']['mean']:.1f} / {ed['crit_unique_lost_sum']} | "
                f"{ed['bulk_deliv']['mean']:.4f} +/- {ed['bulk_deliv']['ci95']:.4f} | "
                f"{ed['nodesec_q90']['mean']:.2f} | {ed['stale_throttle_nodesec']['mean']:.2f} | "
                f"{ed['throttle_coverage']['mean']:.3f} | {ed['react_med_s']['mean']:.1f} ({ed['react_censored_frac']['mean']:.3f}) | "
                f"{ed['ctrl_tx_J']['mean']:.1f} |\n"
            )

    md.append("\n## 4. Exploratory Paired Contrasts (n = 11)\n")
    md.append("| Contrast | Mean A | Mean B | Paired Diff +/- 95% CI | p_raw | Wins/11 |\n")
    md.append("|---|---:|---:|---:|---:|---:|\n")
    for pname, ps in compb_summary["paired_exploratory"].items():
        md.append(f"| {pname} | {ps['mean_a']:.3f} | {ps['mean_b']:.3f} | {ps['mean_diff']:+.3f} +/- {ps['ci95']:.3f} [{ps['lo95']:+.3f}, {ps['hi95']:+.3f}] | {ps['p_val']:.3e} | {ps['wins']}/11 |\n")

    md.append("\n## 5. Comparison A Across 3 Urban VDTN Regimes (n = 11)\n")
    for rtag, _, _ in compa_regimes:
        md.append(f"\n### Regime: {rtag}\n")
        md.append("| Router | CrDel | CrLst Sum (EffMax) | CBD Sum (CBDA Mean) | BkDel | Alive Mean | SoC Mean |\n")
        md.append("|---|---:|---:|---:|---:|---:|---:|\n")
        for cls, d in compa_summary["regimes"][rtag]["routers"].items():
            md.append(
                f"| `{cls}` | {d['critical_delivery_prob']['mean']:.4f} +/- {d['critical_delivery_prob']['ci95']:.4f} | "
                f"{d['unique_critical_lost_sum']} ({d['effective_critical_loss_max']}) | "
                f"{d['critical_buffer_dropped_sum']} ({d['critical_buffer_dropped_acked']['mean']:.1f}) | "
                f"{d['bulk_delivery_prob']['mean']:.4f} +/- {d['bulk_delivery_prob']['ci95']:.4f} | "
                f"{d['alive_nodes_end']['mean']:.1f} +/- {d['alive_nodes_end']['ci95']:.1f} | "
                f"{d['avg_energy_soc']['mean']:.4f} |\n"
            )

    md.append("\n## 6. Comparison A Tiers (n = 11)\n")
    md.append("| Router | Tier | CrDel | CrLst Sum (EffSum) | BkDel | Alive | SoC |\n")
    md.append("|---|---|---:|---:|---:|---:|---:|\n")
    for pname, _, _ in COMPA_PAIRS:
        for t in TIERS:
            d = compa_summary["tiers"][pname][t]
            md.append(
                f"| {pname} | `{t}` | {d['critical_delivery_prob']['mean']:.4f} | "
                f"{d['unique_critical_lost_sum']} ({d['effective_critical_loss_sum']}) | "
                f"{d['bulk_delivery_prob']['mean']:.4f} | {d['alive_nodes_end']['mean']:.1f} | {d['avg_energy_soc']['mean']:.4f} |\n"
            )

    md.append("\n## 7. Tier-2 Hand-Coded Parity (A3), Equivalence (E1/E2), & Replay (R1)\n")
    pa3 = compa_summary["parity_a3"]
    md.append(
        f"- **A3 Parity (`EnergyAwareEpidemic` vs `ManagedEpidemic TIER2`)**: "
        f"`CrDel` diff `{pa3['critical_delivery_prob']['mean_diff']:+.3f} +/- {pa3['critical_delivery_prob']['ci95']:.3f} pp`, "
        f"`BkDel` diff `{pa3['bulk_delivery_prob']['mean_diff']:+.3f} +/- {pa3['bulk_delivery_prob']['ci95']:.3f} pp`, "
        f"`Alive` diff `{pa3['alive_nodes_end']['mean_diff']:+.2f} +/- {pa3['alive_nodes_end']['ci95']:.2f}` nodes; "
        f"LOC = `{pa3['loc']['EnergyAwareEpidemicRouter.java']}` Java lines vs `{pa3['loc']['rules_vdtn.txt']}` rule lines.\n"
    )
    md.append(
        f"- **E1 Bit-for-Bit (`equivalenceConnOuterOrder=true`)**: MessageStats exact = `{e1['msg_stats_exact']}/11`, "
        f"RL core exact = `{e1['rl_core_exact']}/11`, `max_crit_lost = {e1['max_crit_lost_managed']}`.\n"
    )
    tost = e2["tost_1pp"]
    md.append(
        f"- **E2 Default HoL Gap & TOST (`+/- 1.0 pp`)**: mean diff = `{tost['mean_diff_pp']:+.4f} pp`, "
        f"90% CI = `[{tost['ci90_lo_pp']:+.4f}, {tost['ci90_hi_pp']:+.4f}] pp`, "
        f"`t_low = {tost['t_lower']:.2f} (p={tost['p_lower']:.3e})`, "
        f"`t_upp = {tost['t_upper']:.2f} (p={tost['p_upper']:.3e})`, "
        f"`p_TOST = {tost['p_tost']:.3e}` (equivalent={tost['equivalent_at_alpha_05']}).\n"
    )
    md.append(f"- **R1 Replay**: `{json.dumps(replay)}`\n")

    Path(args.out_md).write_text("".join(md), encoding="utf-8")
    print("".join(md))


if __name__ == "__main__":
    main()
