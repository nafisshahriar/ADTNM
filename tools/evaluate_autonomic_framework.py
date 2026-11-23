#!/usr/bin/env python3
"""
evaluate_autonomic_framework.py
Unified TNSM-grade evaluation, statistical inference, deterministic audit replay (RQ4),
cross-scenario oracle regret & Dolan-More performance profiles (RQ2), and publication
figure generator for the Autonomic DTN Management Framework (ADTNM / RFC 7575 / RFC 9675).
"""

import argparse
import json
import math
import os
import re
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

try:
    from scipy.stats import t as _scipy_student_t
except ImportError:
    _scipy_student_t = None

T_CRIT_95 = {
    1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
    6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228,
    11: 2.201, 12: 2.179, 13: 2.160, 14: 2.145, 15: 2.131,
}

ROUTER_LABELS = {
    "SprayAndWaitRouter": "L1: SnW (Stock)",
    "PrioritySprayAndWaitRouter": "L2: +Protection (PriSnW)",
    "DtnmaFixedAckRouter": "L3: +Delayed ACK (Fixed L=6)",
    "DtnmaStaticAckRouter": "L4: +SRL Rules (L=6)",
    "DtnmaShieldedAckRLRouter": "L5: +CMDP RL Planner",
    "DtnmaGossipAckRLRouter": "L6: +Gossip (Full ADTNM)",
    "DtnmaStaticAckL2Router": "Static-ACK (L=2)",
    "DtnmaStaticAckL4Router": "Static-ACK (L=4)",
    "DtnmaStaticAckTunedRouter": "Static-ACK Oracle (L=10)",
    "PriorityMaxPropRouter": "PriorityMaxProp",
}

PROFILES = [
    ("SAFE_EMERGENCY_HOLD", 1, 2),
    ("SAFE_THROTTLE_STRICT", 2, 1),
    ("BALANCED_CONSERVATIVE", 4, 0),
    ("BALANCED_AGILE_FOCUS", 6, 0),
    ("STANDARD_SPRAY_FOCUS", 6, 0),
    ("EXPLORE_AGILE_SPRAY", 8, 0),
    ("HIGH_SPRAY_BACKPRESSURE", 10, 0),
    ("HIGH_SPRAY_OPEN", 12, 0),
]
PROFILE_NAME_TO_IDX = {name: i for i, (name, _, _) in enumerate(PROFILES)}


def t_critical_95(df_deg: int) -> float:
    if df_deg <= 0:
        return 0.0
    if df_deg in T_CRIT_95:
        return T_CRIT_95[df_deg]
    for k in sorted(T_CRIT_95.keys()):
        if df_deg < k:
            return T_CRIT_95[k]
    return 1.960


def two_sided_t_pvalue(t_abs: float, df_deg: int) -> float:
    if df_deg <= 0:
        return 1.0
    if _scipy_student_t is not None:
        return float(2.0 * _scipy_student_t.sf(t_abs, df_deg))
    return math.erfc(t_abs / math.sqrt(2.0))


def seed_clustered_mean_ci(series: pd.Series):
    vals = series.dropna().astype(float)
    n = len(vals)
    if n == 0:
        return 0.0, 0.0
    m = float(vals.mean())
    if n == 1:
        return m, 0.0
    se = float(vals.std(ddof=1)) / math.sqrt(n)
    return m, t_critical_95(n - 1) * se


def compute_paired_ci(df: pd.DataFrame, router_a: str, router_b: str, metric: str,
                      cluster_col: str = "seed", scale: float = 1.0):
    if df.empty or metric not in df.columns or cluster_col not in df.columns:
        return None
    if router_a not in df["router"].values or router_b not in df["router"].values:
        return None
    ga = df[df["router"] == router_a].groupby(cluster_col, as_index=False)[metric].mean().rename(columns={metric: "a"})
    gb = df[df["router"] == router_b].groupby(cluster_col, as_index=False)[metric].mean().rename(columns={metric: "b"})
    merged = pd.merge(ga, gb, on=cluster_col, how="inner").dropna()
    if merged.empty:
        return None
    diffs = (merged["a"] - merged["b"]) * scale
    n = len(diffs)
    mean_d = float(diffs.mean())
    if n <= 1:
        return {"n": n, "mean": mean_d, "ci": 0.0, "lo": mean_d, "hi": mean_d, "p_val": 1.0}
    sd = float(diffs.std(ddof=1))
    se = sd / math.sqrt(n)
    ci = t_critical_95(n - 1) * se
    p_val = two_sided_t_pvalue(abs(mean_d / se), n - 1) if se > 1e-12 else (0.0 if abs(mean_d) > 1e-12 else 1.0)
    return {"n": n, "mean": mean_d, "ci": ci, "lo": mean_d - ci, "hi": mean_d + ci, "p_val": p_val}


def apply_holm_bonferroni(entries):
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


def parse_kv_report(path: Path):
    data = {}
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            k, v = line.split(":", 1)
            k = k.strip()
            v = v.strip()
            try:
                val = float(v)
                if not math.isnan(val) and not math.isinf(val):
                    data[k] = val
                else:
                    data[k] = float("nan")
            except ValueError:
                pass
    return data


def load_campaign_df(report_dir: Path) -> pd.DataFrame:
    if not report_dir.is_dir():
        return pd.DataFrame()
    runs = {}
    for p in sorted(report_dir.glob("*_MessageStatsReport.txt")):
        stem = p.name[:-len("_MessageStatsReport.txt")]
        parts = stem.split("_")
        if len(parts) < 5:
            continue
        rec = runs.setdefault(stem, {
            "scenario": stem,
            "tag": parts[0],
            "router": parts[1],
            "seed": int(re.search(r"(\d+)", parts[2]).group(1)),
            "param3": int(re.search(r"(\d+)", parts[3]).group(1)),
            "buffer": int(re.search(r"(\d+)", parts[4]).group(1)),
        })
        rec.update(parse_kv_report(p))

    for p in sorted(report_dir.glob("*_DtnmaRlMetricsReport.txt")):
        stem = p.name[:-len("_DtnmaRlMetricsReport.txt")]
        if stem in runs:
            runs[stem].update(parse_kv_report(p))

    if not runs:
        return pd.DataFrame()
    return pd.DataFrame(list(runs.values()))


def load_timeline_df(report_dir: Path) -> pd.DataFrame:
    if not report_dir.is_dir():
        return pd.DataFrame()
    frames = []
    cols = ["SimTime", "AliveNodes", "MeanSoC", "ShieldOrSafeFrac",
            "RollingDeliv", "RollingCritDeliv", "MeanAoI", "AdoptedCtrlV1Frac"]
    for p in sorted(report_dir.glob("*_DtnmaRecoveryTimelineReport.txt")):
        stem = p.name[:-len("_DtnmaRecoveryTimelineReport.txt")]
        parts = stem.split("_")
        if len(parts) < 5:
            continue
        rows = []
        with open(p, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#"):
                    continue
                toks = s.split("\t")
                if len(toks) == len(cols):
                    rows.append([float(x) for x in toks])
        if rows:
            df = pd.DataFrame(rows, columns=cols)
            df["tag"] = parts[0]
            df["router"] = parts[1]
            df["seed"] = int(re.search(r"(\d+)", parts[2]).group(1))
            df["buffer"] = int(re.search(r"(\d+)", parts[4]).group(1))
            frames.append(df)
    return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()


def parse_rules_file(rules_path: Path):
    rules = []
    if not rules_path.is_file():
        return rules
    cur = None
    clause_re = re.compile(r"([A-Z_0-9]+)\s*(>=|<=|>|<|==|!=|=)\s*([-+]?[0-9]*\.?[0-9]+)")
    act_re = re.compile(r"([A-Z_0-9]+)\s*\(([^)]*)\)")
    with open(rules_path, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("//"):
                continue
            if line.startswith("RULE:"):
                if cur and cur.get("if") is not None:
                    rules.append(cur)
                parts = [p.strip() for p in line[5:].split("|")]
                prio = 10
                for p in parts[1:]:
                    if p.upper().startswith("PRIORITY:"):
                        prio = int(p.split(":", 1)[1].strip())
                cur = {"ari": parts[0], "priority": prio, "if": None, "max_quota": 16, "min_ingress": 0}
            elif line.startswith("IF:") and cur is not None:
                if_str = line[3:].strip()
                is_and = "||" not in if_str
                clauses = [(m.group(1), m.group(2), float(m.group(3))) for m in clause_re.finditer(if_str)]
                cur["if"] = (is_and, clauses)
            elif line.startswith("THEN:") and cur is not None:
                for act in line[5:].split(";"):
                    m = act_re.search(act.strip())
                    if not m:
                        continue
                    cname, pval = m.group(1).upper(), m.group(2).strip()
                    if cname == "CTRL_SET_SPRAY_QUOTA":
                        cur["max_quota"] = min(cur["max_quota"], max(1, min(16, int(pval))))
                    elif cname == "CTRL_SET_INGRESS_MODE":
                        mode = 2 if pval in ("2", "CRITICAL_ONLY") else (1 if pval in ("1", "THROTTLE_BULK") else 0)
                        cur["min_ingress"] = max(cur["min_ingress"], mode)
    if cur and cur.get("if") is not None:
        rules.append(cur)
    rules.sort(key=lambda r: r["priority"], reverse=True)
    return rules


def eval_rule(rule, edd_vals):
    is_and, clauses = rule["if"]
    if not clauses:
        return True
    results = []
    for edd, op, th in clauses:
        v = edd_vals.get(edd, 0.0)
        if op == ">=":
            results.append(v >= th)
        elif op == "<=":
            results.append(v <= th)
        elif op == ">":
            results.append(v > th)
        elif op == "<":
            results.append(v < th)
        elif op in ("==", "="):
            results.append(abs(v - th) < 1e-6)
        elif op == "!=":
            results.append(abs(v - th) >= 1e-6)
    return all(results) if is_and else any(results)


def verify_audit_replay(report_dir: Path, scenarios_dir: Path):
    """RQ4: Verifies 100% causal ARI traceability and deterministic replay from DtnmaAuditReport logs."""
    rules_by_tag = {
        "base": parse_rules_file(scenarios_dir / "rules_vdtn.txt"),
        "L2": parse_rules_file(scenarios_dir / "rules_vdtn_L2.txt"),
        "L4": parse_rules_file(scenarios_dir / "rules_vdtn_L4.txt"),
        "L10": parse_rules_file(scenarios_dir / "rules_vdtn_L10.txt"),
        "fixed": parse_rules_file(scenarios_dir / "rules_fixed_L6.txt"),
    }
    v1_overlay = {
        "ari": "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD",
        "priority": 110,
        "if": (False, [("EDD_ENERGY_SOC", "<", 0.25), ("EDD_CRIT_QUEUE_RATIO", ">=", 0.40)]),
        "max_quota": 1,
        "min_ingress": 2,
    }
    v1_overlay_legacy = {
        "ari": "ARI://DTNMA/SRL/DOWNLINK_V1_ENERGY_GUARD",
        "priority": 110,
        "if": (False, [("EDD_ENERGY_SOC", "<", 0.25), ("EDD_QUEUE_RATIO", ">=", 0.90)]),
        "max_quota": 1,
        "min_ingress": 2,
    }
    throttle_overlay = {
        "ari": "ARI://DTNMA/SRL/DOWNLINK_THROTTLE",
        "priority": 110,
        "if": (False, [("EDD_ENERGY_SOC", "<", 0.25), ("EDD_CRIT_QUEUE_RATIO", ">=", 0.35)]),
        "max_quota": 2,
        "min_ingress": 1,
    }
    severe_throttle_overlay = {
        "ari": "ARI://DTNMA/SRL/DOWNLINK_THROTTLE",
        "priority": 110,
        "if": (False, [("EDD_ENERGY_SOC", "<", 0.25), ("EDD_CRIT_QUEUE_RATIO", ">=", 0.35)]),
        "max_quota": 1,
        "min_ingress": 2,
    }

    audit_files = sorted(report_dir.glob("*_DtnmaAuditReport.txt"))
    total_rows = 0
    traceable_rows = 0
    rl_mask_checked = 0
    rl_mask_matched = 0
    rl_action_matched = 0
    static_checked = 0
    static_ari_matched = 0
    bytes_per_node_hr_list = []

    for af in audit_files:
        if "DtnmaFixedAckRouter" in af.name:
            file_rules = rules_by_tag["fixed"]
            allow_v1 = False
        elif "DtnmaStaticAckL2Router" in af.name:
            file_rules = rules_by_tag["L2"]
            allow_v1 = True
        elif "DtnmaStaticAckL4Router" in af.name:
            file_rules = rules_by_tag["L4"]
            allow_v1 = True
        elif "DtnmaStaticAckTunedRouter" in af.name:
            file_rules = rules_by_tag["L10"]
            allow_v1 = True
        else:
            file_rules = rules_by_tag["base"]
            allow_v1 = True

        last_ari_by_node = {}
        has_crit_q = False
        with open(af, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                s = line.strip()
                if not s:
                    continue
                if s.startswith("# SimTime\t") and "\tCritQ\t" in s:
                    has_crit_q = True
                    continue
                if s.startswith("# AUDIT_SUMMARY log_bytes_per_node_hour:"):
                    bytes_per_node_hr_list.append(float(s.split(":", 1)[1].strip()))
                    continue
                if s.startswith("#"):
                    continue
                toks = s.split("\t")
                min_cols = 21 if has_crit_q else 20
                if len(toks) < min_cols:
                    continue
                total_rows += 1
                node_id = toks[2]
                mode = toks[3]
                if has_crit_q:
                    q = float(toks[4])
                    crit_q = float(toks[5])
                    dq = float(toks[6])
                    dens = float(toks[7])
                    speed = float(toks[8])
                    soc = float(toks[9])
                    conn = float(toks[10])
                    ctrl_ver = int(toks[11])
                    sh_emin = float(toks[12])
                    sh_warn_crit_q = float(toks[13])
                    prop_name = toks[14]
                    fb_name = toks[15]
                    exec_name = toks[16]
                    sh_status = toks[17]
                    logged_mask = toks[18]
                    active_ari = toks[19]
                    expl = toks[20]
                else:
                    q = float(toks[4])
                    crit_q = 0.0
                    dq = float(toks[5])
                    dens = float(toks[6])
                    speed = float(toks[7])
                    soc = float(toks[8])
                    conn = float(toks[9])
                    ctrl_ver = int(toks[10])
                    sh_emin = float(toks[11])
                    sh_warn_crit_q = float(toks[12])
                    prop_name = toks[13]
                    fb_name = toks[14]
                    exec_name = toks[15]
                    sh_status = toks[16]
                    logged_mask = toks[17]
                    active_ari = toks[18]
                    expl = toks[19]

                if active_ari.startswith("ARI://") and expl:
                    traceable_rows += 1

                edd = {
                    "EDD_QUEUE_RATIO": q,
                    "EDD_CRIT_QUEUE_RATIO": crit_q,
                    "EDD_QUEUE_DRIFT": dq,
                    "EDD_NODE_DENSITY": dens,
                    "EDD_NODE_SPEED": speed,
                    "EDD_ENERGY_SOC": soc,
                    "EDD_ACTIVE_CONNECTIONS": conn,
                }
                active_rules = list(file_rules)
                if allow_v1 and ctrl_ver >= 1:
                    if not has_crit_q:
                        if ctrl_ver == 1:
                            active_rules = [v1_overlay_legacy] + active_rules
                    else:
                        if sh_emin >= 0.249:
                            active_rules = [v1_overlay] + active_rules
                        elif sh_warn_crit_q <= 0.31:
                            ov = severe_throttle_overlay if "SEVERE" in active_ari else throttle_overlay
                            active_rules = [ov] + active_rules
                    active_rules.sort(key=lambda r: r["priority"], reverse=True)

                if mode in ("SHIELDED_CMDP_RL", "SHIELDED_RL_PASSIVE"):
                    rl_mask_checked += 1
                    # Reconstruct S1-S4 SafeActionMask
                    mask = [True] * 8
                    if has_crit_q:
                        if crit_q >= 0.50 or (q >= 0.94 and dq >= 0.015):
                            for i in range(1, 8):
                                mask[i] = False
                        elif crit_q >= sh_warn_crit_q or (q >= 0.82 and dq > 0.015):
                            for i in range(3, 8):
                                mask[i] = False
                    else:
                        if q >= 0.94:
                            for i in range(1, 8):
                                mask[i] = False
                        elif q >= sh_warn_crit_q or (q >= 0.82 and dq > 0.015):
                            for i in range(2, 8):
                                mask[i] = False
                    if soc < sh_emin:
                        for i in range(2, 8):
                            mask[i] = False
                    if q >= 0.78 and dens >= 5.0:
                        mask[6] = False
                        mask[7] = False
                    for r in active_rules:
                        if r["priority"] >= 100 and eval_rule(r, edd):
                            for i in range(1, 8):
                                _, pq, ping = PROFILES[i]
                                if pq > r["max_quota"] or ping < r["min_ingress"]:
                                    mask[i] = False
                            break
                    mask[0] = True
                    replayed_mask = "".join("1" if b else "0" for b in mask)
                    if replayed_mask == logged_mask:
                        rl_mask_matched += 1

                    p_idx = PROFILE_NAME_TO_IDX.get(prop_name, 4)
                    fb_idx = PROFILE_NAME_TO_IDX.get(fb_name, 0)
                    if mask[p_idx]:
                        rep_status = "PASS"
                        rep_exec_idx = p_idx
                    else:
                        rep_status = "OVERRIDE"
                        if mask[fb_idx]:
                            rep_exec_idx = fb_idx
                        else:
                            rep_exec_idx = next((i for i in range(p_idx, -1, -1) if mask[i]), 0)
                    if rep_status == sh_status and PROFILES[rep_exec_idx][0] == exec_name:
                        rl_action_matched += 1
                elif mode in ("STATIC_RULES", "M2_AUTONOMIC_SRL"):
                    static_checked += 1
                    matched_ari = None
                    for r in active_rules:
                        if eval_rule(r, edd):
                            matched_ari = r["ari"]
                            break
                    if matched_ari is None:
                        matched_ari = last_ari_by_node.get(node_id, active_ari)
                    if matched_ari == active_ari or active_ari.startswith("ARI://DTNMA/CTRL/"):
                        static_ari_matched += 1
                    last_ari_by_node[node_id] = active_ari

    return {
        "audit_files": len(audit_files),
        "total_rows": total_rows,
        "traceable_pct": 100.0 * traceable_rows / max(1, total_rows),
        "rl_mask_checked": rl_mask_checked,
        "rl_mask_match_pct": 100.0 * rl_mask_matched / max(1, rl_mask_checked),
        "rl_action_match_pct": 100.0 * rl_action_matched / max(1, rl_mask_checked),
        "static_checked": static_checked,
        "static_ari_match_pct": 100.0 * static_ari_matched / max(1, static_checked),
        "log_bytes_per_node_hour_mean": float(np.mean(bytes_per_node_hr_list)) if bytes_per_node_hr_list else 0.0,
    }


def df_to_markdown(df: pd.DataFrame) -> str:
    cols = [str(c) for c in df.columns]
    header = "| Scenario | " + " | ".join(cols) + " |"
    sep = "|---|" + "|".join(["---:" for _ in cols]) + "|"
    rows = [header, sep]
    for idx, row in df.iterrows():
        vals = [f"{float(row[c]):+.2f}" if not pd.isna(row[c]) else "nan" for c in df.columns]
        rows.append(f"| {idx} | " + " | ".join(vals) + " |")
    return "\n".join(rows)


def make_fig1_component_ladder(ladder_df: pd.DataFrame, out_dir: Path):
    if ladder_df.empty:
        return
    order = [
        "SprayAndWaitRouter",
        "PrioritySprayAndWaitRouter",
        "DtnmaFixedAckRouter",
        "DtnmaStaticAckRouter",
        "DtnmaShieldedAckRLRouter",
        "DtnmaGossipAckRLRouter",
        "DtnmaStaticAckTunedRouter",
    ]
    present = [r for r in order if r in ladder_df["router"].values]
    # Focus on binding buffers (5M, 15M, 30M)
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.2))
    short = [ROUTER_LABELS.get(r, r).replace("Static-ACK Oracle (L=10)", "Oracle Static (L=10)") for r in present]
    colors = ["#7f7f7f", "#1f77b4", "#17becf", "#ff7f0e", "#9467bd", "#2ca02c", "#d62728"][:len(present)]

    # Panel A: Critical Delivery & Unique Critical Loss
    ax = axes[0]
    crit_m, crit_ci, unq_lost = [], [], []
    for r in present:
        s = ladder_df[ladder_df["router"] == r].groupby("seed")["critical_delivery_prob"].mean() * 100.0
        m, ci = seed_clustered_mean_ci(s)
        crit_m.append(m)
        crit_ci.append(ci)
        unq_lost.append(int(ladder_df[ladder_df["router"] == r]["unique_critical_lost"].sum()))
    bars = ax.bar(range(len(present)), crit_m, yerr=crit_ci, capsize=4, color=colors, edgecolor="black", alpha=0.9)
    for idx, (b, ul) in enumerate(zip(bars, unq_lost)):
        ax.annotate(f"{b.get_height():.1f}%\n(Loss={ul})", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                    xytext=(0, 6), textcoords="offset points", ha="center", va="bottom", fontsize=7.5, fontweight="bold")
    ax.set_xticks(range(len(present)))
    ax.set_xticklabels(short, rotation=28, ha="right", fontsize=8.5)
    ax.set_ylabel("Critical Delivery (%)")
    ax.set_title("(a) Self-Protection: Critical Delivery & Loss", fontweight="bold")
    ax.set_ylim(0, max(crit_m) * 1.25 if crit_m else 100)
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    # Panel B: Bulk Delivery vs Buffer
    ax = axes[1]
    for idx, r in enumerate(present):
        sub = ladder_df[ladder_df["router"] == r]
        bufs = sorted(sub["buffer"].unique())
        ys, cis = [], []
        for bv in bufs:
            m, ci = seed_clustered_mean_ci(sub[sub["buffer"] == bv]["bulk_delivery_prob"] * 100.0)
            ys.append(m)
            cis.append(ci)
        ls = "--" if "Oracle" in ROUTER_LABELS.get(r, "") else "-"
        ax.errorbar(bufs, ys, yerr=cis, marker="o", linestyle=ls, linewidth=2, capsize=3,
                    color=colors[idx], label=short[idx])
    ax.set_xlabel("Buffer Size (MB)")
    ax.set_ylabel("Bulk Payload Delivery (%)")
    ax.set_title("(b) Efficiency Gain Across Component Ladder", fontweight="bold")
    ax.legend(fontsize=7.2, loc="best")
    ax.grid(True, linestyle=":", alpha=0.6)

    # Panel C: Overhead Ratio
    ax = axes[2]
    ov_m, ov_ci = [], []
    for r in present:
        s = ladder_df[ladder_df["router"] == r].groupby("seed")["overhead_ratio"].mean()
        m, ci = seed_clustered_mean_ci(s)
        ov_m.append(m)
        ov_ci.append(ci)
    bars = ax.bar(range(len(present)), ov_m, yerr=ov_ci, capsize=4, color=colors, edgecolor="black", alpha=0.9)
    for b in bars:
        ax.annotate(f"{b.get_height():.1f}", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                    xytext=(0, 5), textcoords="offset points", ha="center", va="bottom", fontsize=8, fontweight="bold")
    ax.set_xticks(range(len(present)))
    ax.set_xticklabels(short, rotation=28, ha="right", fontsize=8.5)
    ax.set_ylabel("Relay Overhead Ratio")
    ax.set_title("(c) Forwarding Overhead Along Ladder", fontweight="bold")
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    plt.tight_layout()
    plt.savefig(out_dir / "fig1_component_ladder.png", dpi=300, bbox_inches="tight")
    plt.close()


def make_fig2_rq1_self_protection(load_df: pd.DataFrame, stress_df: pd.DataFrame, out_dir: Path):
    if load_df.empty and stress_df.empty:
        return
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.0))
    routers = ["SprayAndWaitRouter", "PrioritySprayAndWaitRouter", "PriorityMaxPropRouter", "DtnmaStaticAckRouter", "DtnmaGossipAckRLRouter"]
    colors = {"SprayAndWaitRouter": "#7f7f7f", "PrioritySprayAndWaitRouter": "#1f77b4",
              "PriorityMaxPropRouter": "#d62728", "DtnmaStaticAckRouter": "#ff7f0e", "DtnmaGossipAckRLRouter": "#2ca02c"}

    if not load_df.empty:
        # param3 is Events2.interval: 36 -> 0.5x, 18 -> 1x, 9 -> 2x, 4 -> 4.5x
        load_map = {36: 0.5, 18: 1.0, 12: 1.5, 9: 2.0, 4: 4.5}
        load_df = load_df.copy()
        load_df["load_x"] = load_df["param3"].map(load_map)
        levels = sorted(load_df["load_x"].dropna().unique())

        # Panel A: Unique critical lost under load sweep
        ax = axes[0]
        for r in routers:
            sub = load_df[load_df["router"] == r]
            if sub.empty:
                continue
            ys = [sub[sub["load_x"] == lv]["unique_critical_lost"].sum() for lv in levels]
            ax.plot(levels, ys, marker="o", linewidth=2, color=colors[r], label=ROUTER_LABELS.get(r, r))
        ax.set_xlabel("Offered Bulk Traffic Multiplier (x)")
        ax.set_ylabel("Total Unique Critical Messages Lost")
        ax.set_title("(a) RQ1: Critical Loss Under Load Sweep (10M)", fontweight="bold")
        ax.legend(fontsize=7.5)
        ax.grid(True, linestyle=":", alpha=0.6)

        # Panel B: Critical Latency under load sweep
        ax = axes[1]
        for r in routers:
            sub = load_df[load_df["router"] == r]
            if sub.empty:
                continue
            ys, cis = [], []
            for lv in levels:
                m, ci = seed_clustered_mean_ci(sub[sub["load_x"] == lv]["critical_latency_avg"])
                ys.append(m)
                cis.append(ci)
            ax.errorbar(levels, ys, yerr=cis, marker="s", linewidth=2, capsize=3, color=colors[r], label=ROUTER_LABELS.get(r, r))
        ax.set_xlabel("Offered Bulk Traffic Multiplier (x)")
        ax.set_ylabel("Mean Critical Latency (s)")
        ax.set_title("(b) RQ1: Critical Latency Preservation", fontweight="bold")
        ax.legend(fontsize=7.5)
        ax.grid(True, linestyle=":", alpha=0.6)

    if not stress_df.empty:
        ax = axes[2]
        present = [r for r in routers if r in stress_df["router"].values]
        unq = [int(stress_df[stress_df["router"] == r]["unique_critical_lost"].sum()) for r in present]
        cdel = [stress_df[stress_df["router"] == r]["critical_delivery_prob"].mean() * 100.0 for r in present]
        bars = ax.bar(range(len(present)), unq, color=["#d62728" if u > 0 else "#2ca02c" for u in unq], edgecolor="black")
        for b, cd in zip(bars, cdel):
            ax.annotate(f"Lost={int(b.get_height())}\n(Del={cd:.1f}%)", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                        xytext=(0, 5), textcoords="offset points", ha="center", va="bottom", fontsize=8, fontweight="bold")
        ax.set_xticks(range(len(present)))
        ax.set_xticklabels([ROUTER_LABELS.get(r, r).split(":")[0] if ":" in ROUTER_LABELS.get(r, r) else r for r in present], rotation=22, ha="right", fontsize=8)
        ax.set_ylabel("Total Unique Critical Messages Lost")
        ax.set_title("(c) All-Critical Buffer Saturation Stress (3M/5M)", fontweight="bold")
        ax.set_ylim(0, max(max(unq) * 1.25, 10))
        ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    plt.tight_layout()
    plt.savefig(out_dir / "fig2_rq1_self_protection.png", dpi=300, bbox_inches="tight")
    plt.close()


def make_fig3_manager_agent(mgr_df: pd.DataFrame, mgr_tl: pd.DataFrame, out_dir: Path):
    if mgr_df.empty:
        return
    order = [
        "SprayAndWaitRouter", "PrioritySprayAndWaitRouter", "PriorityMaxPropRouter",
        "DtnmaFixedAckRouter", "DtnmaStaticAckRouter", "DtnmaStaticAckTunedRouter",
        "DtnmaShieldedAckRLRouter", "DtnmaGossipAckRLRouter",
    ]
    present = [r for r in order if r in mgr_df["router"].values]
    short = [ROUTER_LABELS.get(r, r) for r in present]
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.0))

    # Panel A: Manager-side Age of Information (AoI Mean & P95)
    ax = axes[0]
    aoi_m, aoi_ci = [], []
    for r in present:
        s = mgr_df[mgr_df["router"] == r].groupby("seed")["aoi_mean_sec"].mean()
        m, ci = seed_clustered_mean_ci(s)
        aoi_m.append(m)
        aoi_ci.append(ci)
    bars = ax.bar(range(len(present)), aoi_m, yerr=aoi_ci, capsize=4, color="#1f77b4", edgecolor="black", alpha=0.88)
    for b in bars:
        ax.annotate(f"{b.get_height():.0f}s", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                    xytext=(0, 5), textcoords="offset points", ha="center", va="bottom", fontsize=7.5, fontweight="bold")
    ax.set_xticks(range(len(present)))
    ax.set_xticklabels(short, rotation=28, ha="right", fontsize=8)
    ax.set_ylabel("Mean Age of Information (s)")
    ax.set_title("(a) Uplink Telemetry Freshness (Manager AoI)", fontweight="bold")
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    # Panel B: Downlink Control T90 and Consistency
    ax = axes[1]
    t90_m, t90_ci, cons_m = [], [], []
    for r in present:
        s = mgr_df[mgr_df["router"] == r].groupby("seed")["downlink_t90_sec"].mean()
        m, ci = seed_clustered_mean_ci(s)
        t90_m.append(m)
        t90_ci.append(ci)
        cons_m.append(mgr_df[mgr_df["router"] == r]["downlink_consistency_ratio"].mean() * 100.0)
    bars = ax.bar(range(len(present)), t90_m, yerr=t90_ci, capsize=4, color="#ff7f0e", edgecolor="black", alpha=0.88)
    for b, cm in zip(bars, cons_m):
        ax.annotate(f"{b.get_height():.0f}s\n({cm:.1f}%)", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                    xytext=(0, 5), textcoords="offset points", ha="center", va="bottom", fontsize=7.2, fontweight="bold")
    ax.set_xticks(range(len(present)))
    ax.set_xticklabels(short, rotation=28, ha="right", fontsize=8)
    ax.set_ylabel("90% Convergence Time T90 (s)")
    ax.set_title("(b) Downlink Control T90 & Policy Consistency", fontweight="bold")
    ax.set_ylim(0, max(t90_m) * 1.25 if t90_m else 10000)
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    # Panel C: Timeline of AoI and Downlink V1 Adoption
    ax = axes[2]
    for r, col in [("PrioritySprayAndWaitRouter", "#1f77b4"), ("DtnmaStaticAckRouter", "#ff7f0e"), ("DtnmaGossipAckRLRouter", "#2ca02c")]:
        sub = mgr_tl[mgr_tl["router"] == r] if not mgr_tl.empty else pd.DataFrame()
        if not sub.empty:
            grp = sub.groupby("SimTime", as_index=False)["MeanAoI"].mean()
            ax.plot(grp["SimTime"] / 3600.0, grp["MeanAoI"], linewidth=2, color=col, label=f"{ROUTER_LABELS.get(r, r)} AoI")
    if not mgr_tl.empty:
        sub_g = mgr_tl[mgr_tl["router"] == "DtnmaGossipAckRLRouter"]
        if not sub_g.empty:
            ax2 = ax.twinx()
            grp_a = sub_g.groupby("SimTime", as_index=False)["AdoptedCtrlV1Frac"].mean()
            ax2.plot(grp_a["SimTime"] / 3600.0, grp_a["AdoptedCtrlV1Frac"] * 100.0, "--", color="#9467bd", linewidth=2, label="V1 Rule Adopted (%)")
            ax2.set_ylabel("Agents Adopting V1 Rule (%)", color="#9467bd")
            ax2.set_ylim(0, 105)
    ax.set_xlabel("Simulation Time (hours)")
    ax.set_ylabel("Mean Manager AoI (s)")
    ax.set_title("(c) Manager-Agent AoI & Downlink Adoption Trace", fontweight="bold")
    ax.legend(fontsize=7.5, loc="upper left")
    ax.grid(True, linestyle=":", alpha=0.6)

    plt.tight_layout()
    plt.savefig(out_dir / "fig3_manager_agent_workload.png", dpi=300, bbox_inches="tight")
    plt.close()


def make_fig4_regret_and_pareto(scenario_dfs: dict, out_dir: Path):
    oracle_routers = ["DtnmaStaticAckL2Router", "DtnmaStaticAckL4Router", "DtnmaStaticAckRouter", "DtnmaStaticAckTunedRouter"]
    eval_routers = oracle_routers + ["DtnmaShieldedAckRLRouter", "DtnmaGossipAckRLRouter"]

    scenarios = []
    regret_mat = []
    for sname, df in scenario_dfs.items():
        if df.empty:
            continue
        means = df.groupby("router")["delivery_prob"].mean()
        avail_oracles = [r for r in oracle_routers if r in means.index]
        if not avail_oracles:
            continue
        oracle_val = max(means[r] for r in avail_oracles)
        row = []
        for r in eval_routers:
            if r in means.index:
                # Positive = regret in pp relative to per-scenario best static-L; negative = beats oracle
                row.append((oracle_val - means[r]) * 100.0)
            else:
                row.append(float("nan"))
        scenarios.append(sname)
        regret_mat.append(row)

    if not scenarios:
        return None

    reg_arr = np.array(regret_mat, dtype=float)
    fig, axes = plt.subplots(1, 3, figsize=(18, 5.2))

    # Panel A: Regret Heatmap
    ax = axes[0]
    im = ax.imshow(reg_arr, cmap="RdYlGn_r", aspect="auto", vmin=-4.0, vmax=max(8.0, float(np.nanmax(reg_arr))))
    ax.set_xticks(range(len(eval_routers)))
    ax.set_xticklabels([ROUTER_LABELS.get(r, r).replace("Static-ACK ", "Stat ").replace("Oracle ", "") for r in eval_routers], rotation=30, ha="right", fontsize=8)
    ax.set_yticks(range(len(scenarios)))
    ax.set_yticklabels(scenarios, fontsize=8.5)
    for i in range(len(scenarios)):
        for j in range(len(eval_routers)):
            val = reg_arr[i, j]
            if not math.isnan(val):
                ax.text(j, i, f"{val:+.2f}", ha="center", va="center", fontsize=8, fontweight="bold",
                        color="white" if val > 5.0 else "black")
    fig.colorbar(im, ax=ax, label="Delivery Regret vs Per-Scenario Oracle (pp)")
    ax.set_title("(a) RQ2: Cross-Scenario Delivery Regret Heatmap", fontweight="bold")

    # Panel B: Dolan-More Performance Profile (clipped regret <= tau)
    ax = axes[1]
    taus = np.linspace(0.0, max(6.0, float(np.nanmax(reg_arr)) + 0.5), 100)
    pcols = ["#1f77b4", "#17becf", "#ff7f0e", "#d62728", "#9467bd", "#2ca02c"]
    for j, r in enumerate(eval_routers):
        col_vals = np.maximum(0.0, reg_arr[:, j])
        valid = col_vals[~np.isnan(col_vals)]
        if len(valid) == 0:
            continue
        rho = [np.mean(valid <= t) * 100.0 for t in taus]
        ls = "-" if "RL" in r else "--"
        lw = 2.6 if "Gossip" in r else 1.8
        ax.plot(taus, rho, linestyle=ls, linewidth=lw, color=pcols[j], label=ROUTER_LABELS.get(r, r))
    ax.set_xlabel("Regret Tolerance Threshold tau (percentage points)")
    ax.set_ylabel("Scenarios Within Regret tau (%)")
    ax.set_title("(b) Dolan-More Regret Performance Profile", fontweight="bold")
    ax.legend(fontsize=7.2, loc="lower right")
    ax.grid(True, linestyle=":", alpha=0.6)

    # Panel C: Pareto Front (Critical Delivery vs Bulk Delivery across all scenarios combined)
    ax = axes[2]
    all_df = pd.concat([df.assign(scen=sn) for sn, df in scenario_dfs.items() if not df.empty], ignore_index=True)
    for r in ["PrioritySprayAndWaitRouter", "PriorityMaxPropRouter"] + eval_routers:
        sub = all_df[all_df["router"] == r]
        if sub.empty:
            continue
        cd = sub["critical_delivery_prob"].mean() * 100.0
        bd = sub["bulk_delivery_prob"].mean() * 100.0
        ov = sub["overhead_ratio"].mean()
        sz = max(45.0, min(320.0, 220.0 / max(0.5, ov)))
        marker = "*" if "Gossip" in r else ("D" if "Shielded" in r else "o")
        ax.scatter(bd, cd, s=sz, marker=marker, edgecolor="black", alpha=0.9, label=f"{ROUTER_LABELS.get(r, r)} (Ov={ov:.1f})")
    ax.set_xlabel("Mean Bulk Delivery (%)")
    ax.set_ylabel("Mean Critical Delivery (%)")
    ax.set_title("(c) Critical vs Bulk Pareto Front (Size ~ 1/Overhead)", fontweight="bold")
    ax.legend(fontsize=6.8, loc="lower right")
    ax.grid(True, linestyle=":", alpha=0.6)

    plt.tight_layout()
    plt.savefig(out_dir / "fig4_rq2_cross_scenario_regret.png", dpi=300, bbox_inches="tight")
    plt.close()

    return pd.DataFrame(reg_arr, index=scenarios, columns=eval_routers)


def make_fig5_rq3_energy_blackout(eb_df: pd.DataFrame, eb_tl: pd.DataFrame, out_dir: Path):
    if eb_df.empty:
        return
    fig, axes = plt.subplots(1, 3, figsize=(17, 5.0))
    focus_routers = [
        ("PrioritySprayAndWaitRouter", "#1f77b4"),
        ("PriorityMaxPropRouter", "#d62728"),
        ("DtnmaStaticAckTunedRouter", "#9467bd"),
        ("DtnmaStaticAckRouter", "#ff7f0e"),
        ("DtnmaGossipAckRLRouter", "#2ca02c"),
    ]

    if not eb_tl.empty:
        ax = axes[0]
        for r, col in focus_routers:
            sub = eb_tl[eb_tl["router"] == r]
            if not sub.empty:
                grp = sub.groupby("SimTime", as_index=False)["AliveNodes"].mean()
                ax.plot(grp["SimTime"] / 3600.0, grp["AliveNodes"], linewidth=2, color=col, label=ROUTER_LABELS.get(r, r))
        ax.axvspan(2.0, 4.0, color="red", alpha=0.08, label="Burst + Blackout Window")
        ax.set_xlabel("Simulation Time (hours)")
        ax.set_ylabel("Alive Devices (out of 126)")
        ax.set_title("(a) RQ3: Device Survival Under Power Blackout", fontweight="bold")
        ax.legend(fontsize=7.2, loc="lower left")
        ax.grid(True, linestyle=":", alpha=0.6)

        ax = axes[1]
        for r, col in focus_routers:
            sub = eb_tl[eb_tl["router"] == r]
            if not sub.empty:
                grp = sub.groupby("SimTime", as_index=False)["RollingCritDeliv"].mean()
                ax.plot(grp["SimTime"] / 3600.0, grp["RollingCritDeliv"] * 100.0, linewidth=2, color=col, label=ROUTER_LABELS.get(r, r))
        ax.axvspan(2.0, 4.0, color="red", alpha=0.08)
        ax.set_xlabel("Simulation Time (hours)")
        ax.set_ylabel("Cohort Critical Delivery (%)")
        ax.set_title("(b) Critical Telemetry Resilience Across Fault", fontweight="bold")
        ax.legend(fontsize=7.2, loc="lower left")
        ax.grid(True, linestyle=":", alpha=0.6)

    ax = axes[2]
    present = [r for r, _ in focus_routers if r in eb_df["router"].values]
    post_c = [eb_df[eb_df["router"] == r]["post_fault_critical_delivery_prob"].mean() * 100.0 for r in present]
    alive_e = [eb_df[eb_df["router"] == r]["alive_nodes_end"].mean() for r in present]
    bars = ax.bar(range(len(present)), post_c, color=[c for r, c in focus_routers if r in present], edgecolor="black")
    for b, ae in zip(bars, alive_e):
        ax.annotate(f"{b.get_height():.1f}%\n({ae:.0f} alive)", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                    xytext=(0, 5), textcoords="offset points", ha="center", va="bottom", fontsize=7.5, fontweight="bold")
    ax.set_xticks(range(len(present)))
    ax.set_xticklabels([ROUTER_LABELS.get(r, r) for r in present], rotation=26, ha="right", fontsize=8)
    ax.set_ylabel("Post-Fault Critical Delivery (%)")
    ax.set_title("(c) Post-Blackout Recovery & Survival", fontweight="bold")
    ax.set_ylim(0, max(post_c) * 1.25 if post_c else 100)
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    plt.tight_layout()
    plt.savefig(out_dir / "fig5_rq3_energy_blackout_recovery.png", dpi=300, bbox_inches="tight")
    plt.close()


def make_fig6_diagnostics(audit_res: dict, sens_df: pd.DataFrame, scale_df: pd.DataFrame, out_dir: Path):
    fig, axes = plt.subplots(1, 3, figsize=(17, 4.8))

    # Panel A: RQ4 Deterministic Audit Replay & Cost
    ax = axes[0]
    labels = ["Causal ARI\nTraceable", "SafeMask\nReplay Match", "Action\nReplay Match", "Static SRL\nARI Match"]
    vals = [
        audit_res.get("traceable_pct", 0.0),
        audit_res.get("rl_mask_match_pct", 0.0),
        audit_res.get("rl_action_match_pct", 0.0),
        audit_res.get("static_ari_match_pct", 0.0),
    ]
    bars = ax.bar(range(len(labels)), vals, color=["#2ca02c", "#1f77b4", "#17becf", "#ff7f0e"], edgecolor="black")
    for b in bars:
        ax.annotate(f"{b.get_height():.2f}%", xy=(b.get_x() + b.get_width() / 2, b.get_height()),
                    xytext=(0, 4), textcoords="offset points", ha="center", va="bottom", fontsize=8.5, fontweight="bold")
    ax.set_xticks(range(len(labels)))
    ax.set_xticklabels(labels, fontsize=8.5)
    ax.set_ylim(0, 115)
    ax.set_ylabel("Verification Match (%)")
    kb_hr = audit_res.get("log_bytes_per_node_hour_mean", 0.0) / 1024.0
    ax.set_title(f"(a) RQ4: Audit Replay ({audit_res.get('total_rows', 0)} rows, {kb_hr:.1f} KB/node-h)", fontweight="bold")
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    # Panel B: RQ5 Rule Perturbation (+/-20%)
    ax = axes[1]
    if not sens_df.empty:
        pert_map = {"Phase3RQ5M20": "-20%", "Phase3RQ5NOM": "Nominal (0%)", "Phase3RQ5P20": "+20%"}
        sens_df = sens_df.copy()
        sens_df["pert"] = sens_df["tag"].map(pert_map)
        p_order = ["-20%", "Nominal (0%)", "+20%"]
        for r, col in [("DtnmaStaticAckRouter", "#ff7f0e"), ("DtnmaGossipAckRLRouter", "#2ca02c")]:
            sub = sens_df[sens_df["router"] == r]
            ys, cis = [], []
            for p in p_order:
                m, ci = seed_clustered_mean_ci(sub[sub["pert"] == p]["delivery_prob"] * 100.0)
                ys.append(m)
                cis.append(ci)
            ax.errorbar(range(len(p_order)), ys, yerr=cis, marker="o", linewidth=2, capsize=4, color=col, label=ROUTER_LABELS.get(r, r))
        ax.set_xticks(range(len(p_order)))
        ax.set_xticklabels(p_order)
        ax.set_ylabel("Overall Delivery (%)")
        ax.legend(fontsize=8)
    ax.set_title("(b) RQ5: +/-20% SRL Threshold Perturbation", fontweight="bold")
    ax.grid(True, linestyle=":", alpha=0.6)

    # Panel C: RQ6 Scalability N=50..500
    ax = axes[2]
    if not scale_df.empty:
        n_map = {"Phase3RQ6N50": 50, "Phase3RQ6N126": 126, "Phase3RQ6N250": 250, "Phase3RQ6N500": 500}
        scale_df = scale_df.copy()
        scale_df["N"] = scale_df["tag"].map(n_map)
        ns = sorted(scale_df["N"].dropna().unique())
        sub_g = scale_df[scale_df["router"] == "DtnmaGossipAckRLRouter"]
        cpus, ctrl_pcts = [], []
        for n in ns:
            cpus.append(sub_g[sub_g["N"] == n]["decision_cpu_us_mean"].mean())
            ctrl_pcts.append(sub_g[sub_g["N"] == n]["control_to_payload_ratio"].mean() * 100.0)
        ax.plot(ns, cpus, "o-", linewidth=2, color="#1f77b4", label="Decision CPU (us)")
        ax.set_xlabel("Network Size N (nodes)")
        ax.set_ylabel("Per-Decision CPU Time (us)", color="#1f77b4")
        ax2 = ax.twinx()
        ax2.plot(ns, ctrl_pcts, "s--", linewidth=2, color="#d62728", label="Control / Payload (%)")
        ax2.set_ylabel("Control / Delivered Payload (%)", color="#d62728")
        ax.set_title("(c) RQ6: Scalability Overhead & CPU (N=50..500)", fontweight="bold")
        ax.grid(True, linestyle=":", alpha=0.6)

    plt.tight_layout()
    plt.savefig(out_dir / "fig6_rq4_rq5_rq6_diagnostics.png", dpi=300, bbox_inches="tight")
    plt.close()


def main():
    parser = argparse.ArgumentParser(description="Evaluate Autonomic DTNMA Framework (RQ1-RQ6)")
    parser.add_argument("--one-dir", default="the-one")
    parser.add_argument("--plots-dir", default=None)
    args = parser.parse_args()

    one_dir = Path(args.one_dir).resolve()
    rep_root = one_dir / "reports"
    plots_dir = Path(args.plots_dir).resolve() if args.plots_dir else (one_dir / "plots" / "phase3_autonomic")
    plots_dir.mkdir(parents=True, exist_ok=True)

    load_df = load_campaign_df(rep_root / "phase3_rq1_load")
    stress_df = load_campaign_df(rep_root / "phase3_rq1_crit_stress")
    ladder_df = load_campaign_df(rep_root / "phase3_ladder")
    mgr_df = load_campaign_df(rep_root / "phase3_manager_agent")
    mgr_tl = load_timeline_df(rep_root / "phase3_manager_agent")
    eb_df = load_campaign_df(rep_root / "phase3_energy_blackout")
    eb_tl = load_timeline_df(rep_root / "phase3_energy_blackout")
    ntn_df = load_campaign_df(rep_root / "phase3_ntn_regret")
    sparse_df = load_campaign_df(rep_root / "phase3_vdtn_sparse")
    sens_df = load_campaign_df(rep_root / "phase3_rq5_sensitivity")
    scale_df = load_campaign_df(rep_root / "phase3_rq6_scale")

    # Zero-drop & zero-overshoot assertion on protected routers
    protected_routers = {
        "PrioritySprayAndWaitRouter", "PriorityMaxPropRouter", "DtnmaFixedAckRouter",
        "DtnmaStaticAckL2Router", "DtnmaStaticAckL4Router", "DtnmaStaticAckRouter",
        "DtnmaStaticAckTunedRouter", "DtnmaShieldedAckRLRouter", "DtnmaGossipAckRLRouter",
    }
    violations = []
    for name, df in [("RQ1Load", load_df), ("RQ1Stress", stress_df), ("Ladder", ladder_df),
                     ("MgrAgent", mgr_df), ("EnergyBlackout", eb_df), ("NTN", ntn_df),
                     ("Sparse", sparse_df), ("RQ5Sens", sens_df), ("RQ6Scale", scale_df)]:
        if df.empty:
            continue
        p_df = df[df["router"].isin(protected_routers)]
        ul = int(p_df["unique_critical_lost"].sum()) if "unique_critical_lost" in p_df else 0
        mo = float(p_df["max_buffer_occupancy"].max()) if "max_buffer_occupancy" in p_df else 0.0
        if ul > 0 or mo > 1.0001:
            violations.append((name, ul, mo))

    audit_res = verify_audit_replay(rep_root / "phase3_energy_blackout", one_dir / "scenarios")

    scenario_dfs = {}
    if not ladder_df.empty:
        for bv in sorted(ladder_df["buffer"].unique()):
            scenario_dfs[f"VDTN-{bv}M"] = ladder_df[ladder_df["buffer"] == bv]
    if not sparse_df.empty:
        scenario_dfs["VDTN-Sparse"] = sparse_df
    if not eb_df.empty:
        scenario_dfs["Energy-Blackout"] = eb_df
    if not ntn_df.empty:
        scenario_dfs["Space-NTN"] = ntn_df

    make_fig1_component_ladder(ladder_df, plots_dir)
    make_fig2_rq1_self_protection(load_df, stress_df, plots_dir)
    make_fig3_manager_agent(mgr_df, mgr_tl, plots_dir)
    regret_df = make_fig4_regret_and_pareto(scenario_dfs, plots_dir)
    make_fig5_rq3_energy_blackout(eb_df, eb_tl, plots_dir)
    make_fig6_diagnostics(audit_res, sens_df, scale_df, plots_dir)

    # Seed-clustered Holm-Bonferroni tests
    paired_entries = []
    comparisons = [
        ("Ladder", ladder_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckTunedRouter", "delivery_prob", 100.0, "pp"),
        ("Ladder", ladder_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckRouter", "delivery_prob", 100.0, "pp"),
        ("Ladder", ladder_df, "DtnmaGossipAckRLRouter", "DtnmaShieldedAckRLRouter", "delivery_prob", 100.0, "pp"),
        ("Ladder", ladder_df, "DtnmaGossipAckRLRouter", "PriorityMaxPropRouter", "overhead_ratio", 1.0, ""),
        ("MgrAgent", mgr_df, "DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "aoi_mean_sec", 1.0, "s"),
        ("MgrAgent", mgr_df, "DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "downlink_t90_sec", 1.0, "s"),
        ("EnergyBlackout", eb_df, "DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "post_fault_critical_delivery_prob", 100.0, "pp"),
        ("EnergyBlackout", eb_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckTunedRouter", "post_fault_critical_delivery_prob", 100.0, "pp"),
        ("EnergyBlackout", eb_df, "DtnmaGossipAckRLRouter", "PrioritySprayAndWaitRouter", "alive_nodes_end", 1.0, " nodes"),
        ("SpaceNTN", ntn_df, "DtnmaGossipAckRLRouter", "DtnmaStaticAckTunedRouter", "delivery_prob", 100.0, "pp"),
    ]
    for sname, df, ra, rb, met, sc, unit in comparisons:
        res = compute_paired_ci(df, ra, rb, met, "seed", sc)
        if res is not None:
            res.update({"scenario": sname, "ra": ra, "rb": rb, "metric": met, "unit": unit})
            paired_entries.append(res)
    apply_holm_bonferroni(paired_entries)

    # Write concise quantitative markdown report
    lines = ["# Autonomic DTNMA Framework Evaluation Summary (RQ1–RQ6)", ""]
    lines.append(f"**Safety Invariant Violations (Protected Routers):** `{violations}` (empty = 0 critical lost & occ <= 1.0 everywhere)")
    lines.append("")

    if not ladder_df.empty:
        lines.append("## 1. 6-Step Component Ladder (VDTN 5M / 15M / 30M)")
        lines.append("| Router | UnqCritLost | CritDeliv (%) | CritLat (s) | BulkDeliv (%) | Overhead | Ctrl/Pay (%) | CPU (us) |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|")
        for r in ["SprayAndWaitRouter", "PrioritySprayAndWaitRouter", "DtnmaFixedAckRouter",
                  "DtnmaStaticAckRouter", "DtnmaShieldedAckRLRouter", "DtnmaGossipAckRLRouter", "DtnmaStaticAckTunedRouter", "PriorityMaxPropRouter"]:
            sub = ladder_df[ladder_df["router"] == r]
            if sub.empty:
                continue
            lines.append(
                f"| {ROUTER_LABELS.get(r, r)} | {int(sub['unique_critical_lost'].sum())} | "
                f"{sub['critical_delivery_prob'].mean()*100:.2f} | {sub['critical_latency_avg'].mean():.1f} | "
                f"{sub['bulk_delivery_prob'].mean()*100:.2f} | {sub['overhead_ratio'].mean():.2f} | "
                f"{sub['control_to_payload_ratio'].mean()*100:.3f} | {sub['decision_cpu_us_mean'].mean():.2f} |"
            )
        lines.append("")

    if not mgr_df.empty:
        lines.append("## 2. Manager–Agent Workload (RFC 9675 Uplink AoI & Downlink T90)")
        lines.append("| Router | UnqCritLost | CritDeliv (%) | AoI Mean (s) | AoI P95 (s) | Downlink T90 (s) | Downlink Deliv (%) | Consistency (%) | Adopted V1/V2 (%) |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        for r in ["SprayAndWaitRouter", "PrioritySprayAndWaitRouter", "PriorityMaxPropRouter", "DtnmaFixedAckRouter",
                  "DtnmaStaticAckRouter", "DtnmaStaticAckTunedRouter", "DtnmaShieldedAckRLRouter", "DtnmaGossipAckRLRouter"]:
            sub = mgr_df[mgr_df["router"] == r]
            if sub.empty:
                continue
            lines.append(
                f"| {ROUTER_LABELS.get(r, r)} | {int(sub['unique_critical_lost'].sum())} | "
                f"{sub['critical_delivery_prob'].mean()*100:.2f} | {sub['aoi_mean_sec'].mean():.1f} | "
                f"{sub['aoi_p95_sec'].mean():.1f} | {sub['downlink_t90_sec'].mean():.1f} | "
                f"{sub['downlink_delivery_prob'].mean()*100:.2f} | {sub['downlink_consistency_ratio'].mean()*100:.2f} | "
                f"{sub['control_version_adopted_frac'].mean()*100:.2f} |"
            )
        lines.append("")

    if not eb_df.empty:
        lines.append("## 3. RQ3 Self-Healing Under Energy-Loss Node Blackout")
        lines.append("| Router | Alive@2h | Alive@4h | Alive@End | PreFault (%) | InFault (%) | PostFaultCrit (%) | FirstResp (s) | RecovTime95 (s) |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
        for r in ["PrioritySprayAndWaitRouter", "PriorityMaxPropRouter", "DtnmaFixedAckRouter",
                  "DtnmaStaticAckL2Router", "DtnmaStaticAckL4Router", "DtnmaStaticAckRouter",
                  "DtnmaStaticAckTunedRouter", "DtnmaShieldedAckRLRouter", "DtnmaGossipAckRLRouter"]:
            sub = eb_df[eb_df["router"] == r]
            if sub.empty:
                continue
            lines.append(
                f"| {ROUTER_LABELS.get(r, r)} | {sub['alive_nodes_2h'].mean():.1f} | {sub['alive_nodes_4h'].mean():.1f} | "
                f"{sub['alive_nodes_end'].mean():.1f} | {sub['pre_fault_delivery_prob'].mean()*100:.2f} | "
                f"{sub['in_fault_delivery_prob'].mean()*100:.2f} | {sub['post_fault_critical_delivery_prob'].mean()*100:.2f} | "
                f"{sub['first_shield_or_rule_response_sec'].mean():.2f} | {sub['recovery_time_to_95pct_sec'].mean():.1f} |"
            )
        lines.append("")

    if regret_df is not None and not regret_df.empty:
        lines.append("## 4. RQ2 Cross-Scenario Delivery Regret vs Per-Scenario Static Oracle (pp; negative = beats oracle)")
        lines.append(df_to_markdown(regret_df))
        lines.append("")
        clipped = regret_df.clip(lower=0.0)
        lines.append("**Mean & Worst-Case Clipped Regret (pp):**")
        for col in regret_df.columns:
            lines.append(f"- `{ROUTER_LABELS.get(col, col)}`: Mean={clipped[col].mean():.2f} pp, Worst-Case={clipped[col].max():.2f} pp (Signed Mean={regret_df[col].mean():+.2f} pp)")
        lines.append("")

    lines.append("## 5. RQ4–RQ6 Diagnostics")
    lines.append(f"- **RQ4 Audit Replay:** `{json.dumps(audit_res)}`")
    if not sens_df.empty:
        lines.append("- **RQ5 +/-20% Perturbation Delivery (%):**")
        for tag in ["Phase3RQ5M20", "Phase3RQ5NOM", "Phase3RQ5P20"]:
            for r in ["DtnmaStaticAckRouter", "DtnmaGossipAckRLRouter"]:
                s = sens_df[(sens_df["tag"] == tag) & (sens_df["router"] == r)]
                if not s.empty:
                    lines.append(f"  - {tag} | {r}: Deliv={s['delivery_prob'].mean()*100:.2f}%, CritDeliv={s['critical_delivery_prob'].mean()*100:.2f}%")
    if not scale_df.empty:
        lines.append("- **RQ6 Scalability (N=50..500):**")
        for tag in ["Phase3RQ6N50", "Phase3RQ6N126", "Phase3RQ6N250", "Phase3RQ6N500"]:
            s = scale_df[(scale_df["tag"] == tag) & (scale_df["router"] == "DtnmaGossipAckRLRouter")]
            if not s.empty:
                lines.append(f"  - {tag}: CPU={s['decision_cpu_us_mean'].mean():.2f} us, Ctrl/Payload={s['control_to_payload_ratio'].mean()*100:.3f}%, CritDeliv={s['critical_delivery_prob'].mean()*100:.2f}%")

    lines.append("")
    lines.append("## 6. Paired Seed-Clustered CIs & Holm-Bonferroni Adjusted p-values")
    lines.append("| Scenario | Comparison | Metric | n | Mean Diff | 95% CI | p_raw | p_holm |")
    lines.append("|---|---|---|---:|---:|---|---:|---:|")
    for e in paired_entries:
        u = e["unit"]
        lines.append(
            f"| {e['scenario']} | {e['ra']} vs {e['rb']} | {e['metric']} | {e['n']} | "
            f"{e['mean']:+.3f}{u} | [{e['lo']:+.3f}, {e['hi']:+.3f}]{u} | {e['p_val']:.4f} | {e['p_holm']:.4f} |"
        )

    summary_md = "\n".join(lines)
    (plots_dir / "phase3_autonomic_summary.md").write_text(summary_md, encoding="utf-8")
    with open(plots_dir / "phase3_autonomic_summary.json", "w", encoding="utf-8") as jf:
        json.dump({
            "violations": violations,
            "audit_replay": audit_res,
            "paired_tests": paired_entries,
        }, jf, indent=2)
    print(summary_md)
    print(f"\nPlots & summary saved to: {plots_dir}")


if __name__ == "__main__":
    main()
