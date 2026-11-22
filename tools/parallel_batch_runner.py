"""
Parallel Batch Runner for The ONE Simulator
Maximizes hardware utilization across multi-core CPUs with tuned JVM parameters
and enforces pairwise-coprime parameter list validation to prevent ONE modulo collisions.
"""

import os
import re
import sys
import math
import time
import argparse
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
import multiprocessing

LIST_SETTING_RE = re.compile(r"^\s*([A-Za-z0-9_.]+)\s*=\s*\[([^\]]+)\]")

def parse_swept_parameters(scenario_path):
    """Parses [a; b; ...] parametric sweep lists from a ONE scenario file."""
    swept = []
    with open(scenario_path, "r", encoding="utf-8") as f:
        for line in f:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            m = LIST_SETTING_RE.match(stripped)
            if m:
                key = m.group(1).strip()
                items = [item.strip() for item in m.group(2).split(";") if item.strip()]
                if len(items) > 1:
                    swept.append((key, len(items), items))
    return swept

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

def run_single(run_id, scenario_file, classpath, java_opts, cwd):
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

def main():
    parser = argparse.ArgumentParser(description="Multi-threaded hardware-accelerated batch runner for The ONE")
    parser.add_argument("--scenario", "-s", default="scenarios/dtnma_batch_scenario.txt", help="Scenario configuration file")
    parser.add_argument("--start", type=int, default=1, help="Starting run index (1-based)")
    parser.add_argument("--end", type=int, default=None, help="Ending run index (inclusive; auto-computed if omitted)")
    parser.add_argument("--workers", "-w", type=int, default=None, help="Parallel worker threads (default: CPU logical cores - 1)")
    parser.add_argument("--mem", "-m", default="1536M", help="Max heap per process (default: 1536M)")
    parser.add_argument("--force", action="store_true", help="Bypass coprime parameter validation guard")
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

    print("=" * 70)
    print("THE ONE SIMULATOR: MULTI-CORE HARDWARE BATCH ACCELERATOR")
    print("=" * 70)
    swept = parse_swept_parameters(scenario_path)
    total_cartesian = validate_coprime_sweeps(swept, force=args.force)

    end_idx = args.end if args.end is not None else total_cartesian
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

    total_runs = end_idx - args.start + 1
    run_list = list(range(args.start, end_idx + 1))

    print("-" * 70)
    print(f"Total CPU Cores/Threads : {cpu_count}")
    print(f"Active Worker Processes : {workers}")
    print(f"Heap per Worker         : {args.mem} (Parallel GC, Headless)")
    print(f"Scenario File           : {scenario_path}")
    print(f"Run Range               : {args.start} to {end_idx} ({total_runs} total runs)")
    print("=" * 70)

    start_all = time.time()
    completed = 0
    failed = 0

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {
            executor.submit(run_single, r_id, scenario_path, classpath, java_opts, the_one_dir): r_id
            for r_id in run_list
        }

        for future in as_completed(futures):
            r_id, success, dur, err = future.result()
            completed += 1
            elapsed = time.time() - start_all
            avg_per_run = elapsed / completed
            remaining = (total_runs - completed) * avg_per_run / workers

            if success:
                print(f"[{completed:3d}/{total_runs}] Run {r_id:3d} finished in {dur:5.1f}s | Elapsed: {elapsed:5.1f}s | ETA: {remaining/60:4.1f}m")
            else:
                failed += 1
                print(f"[{completed:3d}/{total_runs}] Run {r_id:3d} FAILED in {dur:5.1f}s: {err[:120]}")

    total_time = time.time() - start_all
    print("=" * 70)
    print("BATCH EXECUTION COMPLETE!")
    print(f"Total Time: {total_time:.2f}s ({total_time/60:.2f} minutes)")
    print(f"Successful Runs: {completed - failed}/{total_runs}")
    if failed > 0:
        print(f"Failed Runs: {failed}")
        sys.exit(1)
    print("=" * 70)

if __name__ == "__main__":
    main()
