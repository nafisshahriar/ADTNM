"""
Separately Labelled Exploratory Sensitivity Study Orchestrator (176 runs total).

Runs 4 exploratory sweeps relative to the Env 1 Stress reference (35 m BT,
2650-3650 J):
  - range_20m   (44 runs)
  - range_55m   (44 runs)
  - energy_075x (44 runs)
  - energy_135x (44 runs)

Routes child logs into reports/sensitivity_exploratory/sensitivity.log and
stage state into reports/sensitivity_exploratory/sensitivity_status.json.
Never touches the frozen confirmatory reports/mobility_suite directory.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone

SENS_STAGES = [
    ("range", "range_20m", "scenarios/sens_range_20m.txt", "1536M"),
    ("range", "range_55m", "scenarios/sens_range_55m.txt", "1536M"),
    ("energy", "energy_075x", "scenarios/sens_energy_075x.txt", "1536M"),
    ("energy", "energy_135x", "scenarios/sens_energy_135x.txt", "1536M"),
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
    parser = argparse.ArgumentParser(description="Run exploratory sensitivity sweeps.")
    parser.add_argument("--one-dir", required=True, help="Path to the-one simulator directory")
    parser.add_argument("--stage", default="all", choices=["all", "range", "energy"])
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--priority", default="low", choices=["low", "normal"])
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    one_dir = os.path.abspath(args.one_dir)
    runner_script = os.path.join(one_dir, "parallel_batch_runner.py")
    if not os.path.isfile(runner_script):
        print(f"ERROR: parallel_batch_runner.py not found in {one_dir}", file=sys.stderr)
        return 2

    suite_dir = os.path.join(one_dir, "reports", "sensitivity_exploratory")
    log_path = os.path.join(suite_dir, "sensitivity.log")
    status_path = os.path.join(suite_dir, "sensitivity_status.json")
    selected = [s for s in SENS_STAGES if args.stage == "all" or s[0] == args.stage]

    state = {
        "started_utc": utc_now(),
        "updated_utc": utc_now(),
        "one_dir": one_dir,
        "stage_filter": args.stage,
        "workers": args.workers,
        "priority": args.priority,
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
                sys.executable, runner_script, "--scenario", scen_rel,
                "--workers", str(args.workers), "--priority", args.priority,
                "--mem", mem, "--dry-run",
            ]
            res = subprocess.run(cmd, cwd=one_dir, capture_output=True, text=True)
            planned = next((ln.strip() for ln in (res.stdout or "").splitlines() if "Total Planned Runs:" in ln), "NA")
            print(f"[DRY-RUN] {name:16s} | mem={mem} | {planned}", flush=True)
            if res.returncode != 0:
                print(res.stdout, res.stderr, file=sys.stderr)
                return res.returncode
        return 0

    os.makedirs(suite_dir, exist_ok=True)
    write_status(status_path, state)
    all_ok = True

    with open(log_path, "a", encoding="utf-8") as log_fh:
        log_fh.write(f"\n================ EXPLORATORY SENSITIVITY START {utc_now()} ================\n")
        for _, name, scen_rel, mem in selected:
            scen_abs = os.path.join(one_dir, scen_rel.replace("/", os.sep))
            rep_dir = read_report_dir(scen_abs, one_dir)
            cmd = [
                sys.executable, runner_script, "--scenario", scen_rel,
                "--workers", str(args.workers), "--priority", args.priority,
                "--mem", mem, "--resume",
            ]
            t0 = time.time()
            state["stages"][name]["status"] = "running"
            state["updated_utc"] = utc_now()
            write_status(status_path, state)
            print(f"[START] {name} (mem={mem}, workers={args.workers})", flush=True)

            rc = run_runner(cmd, one_dir, log_fh)
            if rc != 0:
                print(f"[RETRY] {name} rc={rc}; running one --resume retry", flush=True)
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
