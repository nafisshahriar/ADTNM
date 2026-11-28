"""
Resumable Multi-Mobility Campaign Orchestrator for ADTNM in The ONE Simulator.

Executes all 9 mobility-aware evaluation scenarios sequentially by stage using
parallel_batch_runner.py with --resume support, routes verbose per-run child
logs to reports/mobility_suite/campaign.log, writes compact machine-readable
stage state to reports/mobility_suite/campaign_status.json, and emits only
concise stage-level progress summaries to stdout.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone

STAGES = [
    ("env3", "env3_disaster_relief", "scenarios/mob_env3_disaster_relief.txt", "1536M"),
    ("env1", "env1_vdtn_stress", "scenarios/mob_env1_vdtn_stress.txt", "1536M"),
    ("env1", "env1_vdtn_benign", "scenarios/mob_env1_vdtn_benign.txt", "1536M"),
    ("env2", "env2_workingday", "scenarios/mob_env2_workingday.txt", "1536M"),
    ("cross", "cross_substrate", "scenarios/mob_cross_substrate.txt", "2048M"),
    ("scale", "scale_60", "scenarios/mob_scale_60.txt", "1536M"),
    ("scale", "scale_126", "scenarios/mob_scale_126.txt", "1536M"),
    ("scale", "scale_250", "scenarios/mob_scale_250.txt", "1536M"),
    ("scale", "scale_500", "scenarios/mob_scale_500.txt", "2048M"),
]


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def count_completed_reports(report_dir):
    if not os.path.isdir(report_dir):
        return 0
    return sum(
        1
        for name in os.listdir(report_dir)
        if name.endswith("_MessageStatsReport.txt")
        and os.path.getsize(os.path.join(report_dir, name)) > 0
    )


def read_report_dir(scen_path, one_dir):
    with open(scen_path, "r", encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if s.startswith("Report.reportDir"):
                _, val = s.split("=", 1)
                return os.path.join(one_dir, val.strip().replace("/", os.sep))
    return ""


def write_status(status_path, state):
    os.makedirs(os.path.dirname(status_path), exist_ok=True)
    tmp = status_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(state, fh, indent=2)
    os.replace(tmp, status_path)


def run_runner(cmd, one_dir, log_fh):
    log_fh.write(f"\n>>> [{utc_now()}] CMD: {' '.join(cmd)}\n")
    log_fh.flush()
    proc = subprocess.Popen(
        cmd,
        cwd=one_dir,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    try:
        for line in proc.stdout:
            log_fh.write(line)
            log_fh.flush()
        return proc.wait()
    except BaseException:
        proc.terminate()
        raise


def main():
    parser = argparse.ArgumentParser(description="Run multi-mobility campaign cleanly.")
    parser.add_argument("--one-dir", required=True, help="Path to the-one directory")
    parser.add_argument(
        "--stage",
        default="all",
        choices=["all", "env3", "env1", "env2", "cross", "scale"],
        help="Stage group to run",
    )
    parser.add_argument("--workers", type=int, default=4, help="Parallel JVM workers")
    parser.add_argument(
        "--priority",
        default="low",
        choices=["low", "normal"],
        help="OS process priority passed to parallel_batch_runner.py",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Resolve and print stage plans without executing simulations",
    )
    args = parser.parse_args()

    one_dir = os.path.abspath(args.one_dir)
    runner_script = os.path.join(one_dir, "parallel_batch_runner.py")
    if not os.path.isfile(runner_script):
        print(f"ERROR: parallel_batch_runner.py not found in {one_dir}", file=sys.stderr)
        return 2

    suite_dir = os.path.join(one_dir, "reports", "mobility_suite")
    log_path = os.path.join(suite_dir, "campaign.log")
    status_path = os.path.join(suite_dir, "campaign_status.json")
    selected = [s for s in STAGES if args.stage == "all" or s[0] == args.stage]

    state = {
        "started_utc": utc_now(),
        "updated_utc": utc_now(),
        "one_dir": one_dir,
        "stage_filter": args.stage,
        "workers": args.workers,
        "priority": args.priority,
        "dry_run": args.dry_run,
        "stages": {
            name: {
                "scenario": scen_rel,
                "mem": mem,
                "status": "pending",
                "elapsed_sec": 0.0,
                "completed_runs": 0,
            }
            for _, name, scen_rel, mem in selected
        },
    }

    if args.dry_run:
        for _, name, scen_rel, mem in selected:
            cmd = [
                sys.executable,
                runner_script,
                "--scenario",
                scen_rel,
                "--workers",
                str(args.workers),
                "--priority",
                args.priority,
                "--mem",
                mem,
                "--dry-run",
            ]
            res = subprocess.run(cmd, cwd=one_dir, capture_output=True, text=True)
            planned = "NA"
            for ln in (res.stdout or "").splitlines():
                if "Total Planned Runs:" in ln:
                    planned = ln.strip()
                    break
            print(f"[DRY-RUN] {name:22s} | mem={mem:5s} | {planned}", flush=True)
            if res.returncode != 0:
                print(res.stdout, res.stderr, file=sys.stderr)
                return res.returncode
        return 0

    os.makedirs(suite_dir, exist_ok=True)
    write_status(status_path, state)
    all_ok = True

    with open(log_path, "a", encoding="utf-8") as log_fh:
        log_fh.write(f"\n==================== CAMPAIGN START {utc_now()} ====================\n")
        for _, name, scen_rel, mem in selected:
            scen_abs = os.path.join(one_dir, scen_rel.replace("/", os.sep))
            rep_dir = read_report_dir(scen_abs, one_dir)
            cmd = [
                sys.executable,
                runner_script,
                "--scenario",
                scen_rel,
                "--workers",
                str(args.workers),
                "--priority",
                args.priority,
                "--mem",
                mem,
                "--resume",
            ]
            t0 = time.time()
            state["stages"][name]["status"] = "running"
            state["updated_utc"] = utc_now()
            write_status(status_path, state)
            print(f"[START] {name} (mem={mem}, workers={args.workers})", flush=True)

            rc = run_runner(cmd, one_dir, log_fh)
            if rc != 0:
                print(f"[RETRY] {name} exited rc={rc}; running one --resume retry", flush=True)
                rc = run_runner(cmd, one_dir, log_fh)

            dur = round(time.time() - t0, 1)
            done_cnt = count_completed_reports(rep_dir) if rep_dir else 0
            st = "done" if rc == 0 else "failed"
            if rc != 0:
                all_ok = False
            state["stages"][name]["status"] = st
            state["stages"][name]["elapsed_sec"] = dur
            state["stages"][name]["completed_runs"] = done_cnt
            state["updated_utc"] = utc_now()
            write_status(status_path, state)
            print(f"[END  ] {name} | status={st} | completed={done_cnt} | {dur/60.0:.1f} min", flush=True)

        state["finished_utc"] = utc_now()
        state["updated_utc"] = state["finished_utc"]
        write_status(status_path, state)

    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
