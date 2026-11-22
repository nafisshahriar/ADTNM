"""
Parallel Batch Runner for The ONE Simulator
Maximizes hardware utilization across multi-core CPUs with tuned JVM parameters,
enforces pairwise-coprime parameter list validation, supports --dry-run and --resume,
and displays live active simulation run numbers in real time.
"""

import os
import re
import sys
import math
import time
import argparse
import threading
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
import multiprocessing

SETTING_LINE_RE = re.compile(r"^\s*([A-Za-z0-9_.]+)\s*=\s*(.+)$")
PLACEHOLDER_RE = re.compile(r"%%([^%]+)%%")

def parse_scenario_settings(scenario_path):
    """Parses all scenario settings and identifies [a; b; ...] swept parameter lists."""
    all_settings = {}
    swept = []
    with open(scenario_path, "r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            m = SETTING_LINE_RE.match(stripped)
            if not m:
                continue
            key = m.group(1).strip()
            raw_val = m.group(2).strip()
            if raw_val.startswith("[") and raw_val.endswith("]"):
                items = [item.strip() for item in raw_val[1:-1].split(";") if item.strip()]
                all_settings[key] = items
                if len(items) > 1:
                    swept.append((key, len(items), items))
            else:
                all_settings[key] = [raw_val]
    return all_settings, swept

def resolve_run_setting(all_settings, key, run_id):
    """Resolves a single setting for 1-based run_id using ONE's (run_id - 1) % len indexing."""
    vals = all_settings.get(key)
    if not vals:
        return ""
    if len(vals) == 1:
        return vals[0]
    return vals[(run_id - 1) % len(vals)]

def resolve_scenario_name(all_settings, run_id):
    """Resolves Scenario.name %%Key%% placeholders identically to ONE's Settings.valueFillString."""
    template = resolve_run_setting(all_settings, "Scenario.name", run_id)
    def repl(match):
        k = match.group(1).strip()
        val = resolve_run_setting(all_settings, k, run_id)
        return val if val else match.group(0)
    return PLACEHOLDER_RE.sub(repl, template)

def validate_coprime_sweeps(swept, force=False):
    """Validates that all swept list lengths are pairwise coprime (gcd == 1)."""
    if not swept:
        return 1

    total_cartesian = 1
    print("Swept Scenario Parameters:")
    for key, length, items in swept:
        total_cartesian *= length
        preview = ", ".join(items[:4]) + ("..." if length > 4 else "")
        print(f"  - {key:<28}: {length:2d} values [{preview}]")

    collisions = []
    for i in range(len(swept)):
        for j in range(i + 1, len(swept)):
            k1, l1, _ = swept[i]
            k2, l2, _ = swept[j]
            g = math.gcd(l1, l2)
            if g > 1:
                collisions.append((k1, l1, k2, l2, g))

    if collisions:
        print("\n" + "!" * 70)
        print("COPRIME PARAMETER GUARD FAILED!")
        print("The ONE uses `runIndex % arrayLength` to index parameter arrays.")
        print("Non-coprime parameter list lengths cause silent modulo aliasing")
        print("and omit combinations from the Cartesian sweep:")
        for k1, l1, k2, l2, g in collisions:
            print(f"  * {k1} (len={l1}) vs {k2} (len={l2}) -> gcd = {g}")
        print("Fix list lengths so all swept counts are pairwise coprime, or pass --force.")
        print("!" * 70 + "\n")
        if not force:
            sys.exit(2)
    else:
        lengths_str = " x ".join(str(x[1]) for x in swept)
        print(f"Coprime Guard PASSED: {lengths_str} = {total_cartesian} unique runs (all pairwise coprime).")

    return total_cartesian

def run_single(run_id, total_runs, scenario_file, classpath, java_opts, cwd, lock, active_runs):
    with lock:
        active_runs.add(run_id)
        active_list = sorted(active_runs)
        print(f"[START] Run {run_id:3d} queued/running | Active ({len(active_list)}): {active_list}", flush=True)

    cmd = ["java"] + java_opts + ["-cp", classpath, "core.DTNSim", "-b", f"{run_id}:{run_id}", scenario_file]
    t0 = time.time()
    try:
        subprocess.run(
            cmd,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=True
        )
        return run_id, True, time.time() - t0, ""
    except subprocess.CalledProcessError as e:
        err = e.stderr.strip() if e.stderr else e.stdout.strip()
        return run_id, False, time.time() - t0, err
    finally:
        with lock:
            active_runs.discard(run_id)

def main():
    parser = argparse.ArgumentParser(description="Multi-threaded hardware-accelerated batch runner for The ONE")
    parser.add_argument("--scenario", "-s", default="scenarios/dtnma_batch_scenario.txt", help="Scenario configuration file")
    parser.add_argument("--start", type=int, default=1, help="Starting run index (1-based)")
    parser.add_argument("--end", type=int, default=None, help="Ending run index (inclusive; auto-computed if omitted)")
    parser.add_argument("--workers", "-w", type=int, default=None, help="Parallel worker threads (default: CPU logical cores - 1)")
    parser.add_argument("--mem", "-m", default="1536M", help="Max heap per process (default: 1536M)")
    parser.add_argument("--force", action="store_true", help="Bypass coprime parameter validation guard")
    parser.add_argument("--dry-run", action="store_true", help="Validate coprime sweeps and preview resolved runs without executing")
    parser.add_argument("--resume", action="store_true", help="Skip runs whose MessageStatsReport already exists and is non-empty")
    args = parser.parse_args()

    the_one_dir = os.path.dirname(os.path.abspath(__file__))
    cp_sep = ";" if sys.platform == "win32" else ":"
    classpath = f"target{cp_sep}lib/ECLA.jar{cp_sep}lib/DTNConsoleConnection.jar"

    scenario_path = args.scenario
    if not os.path.isabs(scenario_path):
        scenario_path = os.path.join(the_one_dir, scenario_path)

    if not os.path.exists(scenario_path):
        print(f"Error: Scenario file not found: {scenario_path}")
        sys.exit(1)

    print("=" * 72)
    print("THE ONE SIMULATOR: MULTI-CORE HARDWARE BATCH ACCELERATOR")
    print("=" * 72)
    all_settings, swept = parse_scenario_settings(scenario_path)
    total_cartesian = validate_coprime_sweeps(swept, force=args.force)

    end_idx = args.end if args.end is not None else total_cartesian
    report_dir_rel = resolve_run_setting(all_settings, "Report.reportDir", 1) or "reports/"
    report_dir_abs = os.path.join(the_one_dir, report_dir_rel) if not os.path.isabs(report_dir_rel) else report_dir_rel

    full_range = list(range(args.start, end_idx + 1))

    if args.dry_run:
        print("-" * 72)
        print(f"Dry-Run Preview (Report Dir: {report_dir_rel})")
        preview_count = min(5, len(full_range))
        for r_id in full_range[:preview_count]:
            r_name = resolve_scenario_name(all_settings, r_id)
            print(f"  Run {r_id:3d} -> {r_name}")
        if len(full_range) > preview_count:
            last_id = full_range[-1]
            print(f"  ...")
            print(f"  Run {last_id:3d} -> {resolve_scenario_name(all_settings, last_id)}")
        print(f"Total Planned Runs: {len(full_range)} (Indices {args.start}..{end_idx})")
        print("=" * 72)
        sys.exit(0)

    run_list = []
    skipped = 0
    if args.resume:
        for r_id in full_range:
            r_name = resolve_scenario_name(all_settings, r_id)
            expected_stat = os.path.join(report_dir_abs, f"{r_name}_MessageStatsReport.txt")
            if os.path.isfile(expected_stat) and os.path.getsize(expected_stat) > 0:
                skipped += 1
            else:
                run_list.append(r_id)
        print(f"Resume Mode: Skipping {skipped} already-completed runs; {len(run_list)} remaining.")
    else:
        run_list = full_range

    if not run_list:
        print("All specified runs are already completed! Nothing to execute.")
        print("=" * 72)
        sys.exit(0)

    cpu_count = multiprocessing.cpu_count()
    workers = args.workers if args.workers else max(1, cpu_count - 1)

    java_opts = [
        "-server",
        "-Xms512M",
        f"-Xmx{args.mem}",
        "-XX:+UseParallelGC",
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=4",
        "-Djava.awt.headless=true"
    ]

    total_to_run = len(run_list)

    print("-" * 72)
    print(f"Total CPU Cores/Threads : {cpu_count}")
    print(f"Active Worker Processes : {workers}")
    print(f"Heap per Worker         : {args.mem} (Parallel GC, Headless)")
    print(f"Scenario File           : {scenario_path}")
    print(f"Report Directory        : {report_dir_rel}")
    print(f"Run Range               : {args.start} to {end_idx} ({total_to_run} runs to execute)")
    print("=" * 72)

    start_all = time.time()
    completed = 0
    failed = 0
    lock = threading.Lock()
    active_runs = set()

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(run_single, r_id, total_to_run, scenario_path, classpath, java_opts, the_one_dir, lock, active_runs): r_id
            for r_id in run_list
        }

        for future in as_completed(futures):
            r_id, success, dur, err = future.result()
            completed += 1
            elapsed = time.time() - start_all
            avg_per_run = elapsed / completed
            remaining = (total_to_run - completed) * avg_per_run / workers

            with lock:
                active_snapshot = sorted(active_runs)

            if success:
                print(
                    f"[DONE ] [{completed:3d}/{total_to_run}] Run {r_id:3d} OK ({dur:5.1f}s) | "
                    f"Elapsed: {elapsed/60:5.1f}m | ETA: {remaining/60:5.1f}m | Active: {active_snapshot}",
                    flush=True
                )
            else:
                failed += 1
                print(
                    f"[FAIL ] [{completed:3d}/{total_to_run}] Run {r_id:3d} FAILED ({dur:5.1f}s): {err[:100]}",
                    flush=True
                )

    total_time = time.time() - start_all
    print("=" * 72)
    print("BATCH EXECUTION COMPLETE!")
    print(f"Total Time: {total_time:.2f}s ({total_time/60:.2f} minutes)")
    print(f"Successful Runs: {completed - failed}/{total_to_run}")
    if failed > 0:
        print(f"Failed Runs: {failed}")
        sys.exit(1)
    print("=" * 72)

if __name__ == "__main__":
    main()
