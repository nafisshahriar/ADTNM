"""
Parallel Batch Runner for The ONE Simulator
Maximizes hardware utilization across multi-core CPUs with tuned JVM parameters.
"""

import os
import sys
import time
import argparse
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
import multiprocessing

def run_single(run_id, scenario_file, classpath, java_opts, cwd):
    cmd = ["java"] + java_opts + ["-cp", classpath, "core.DTNSim", "-b", f"{run_id}:{run_id}", scenario_file]
    t0 = time.time()
    try:
        proc = subprocess.run(
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
    parser.add_argument("--end", type=int, default=210, help="Ending run index (inclusive)")
    parser.add_argument("--workers", "-w", type=int, default=None, help="Parallel worker threads (default: CPU logical cores - 1)")
    parser.add_argument("--mem", "-m", default="1536M", help="Max heap per process (default: 1536M)")
    args = parser.parse_args()

    the_one_dir = os.path.dirname(os.path.abspath(__file__))
    classpath = f"target;lib/ECLA.jar;lib/DTNConsoleConnection.jar"
    scenario_path = args.scenario
    if not os.path.isabs(scenario_path):
        scenario_path = os.path.join(the_one_dir, scenario_path)

    if not os.path.exists(scenario_path):
        print(f"Error: Scenario file not found: {scenario_path}")
        sys.exit(1)

    cpu_count = multiprocessing.cpu_count()
    workers = args.workers if args.workers else max(1, cpu_count - 1)

    # High-throughput JVM flags tailored for non-interactive batch simulation
    java_opts = [
        "-server",
        f"-Xms512M",
        f"-Xmx{args.mem}",
        "-XX:+UseParallelGC",
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=4",
        "-Djava.awt.headless=true"
    ]

    total_runs = args.end - args.start + 1
    run_list = list(range(args.start, args.end + 1))

    print("=" * 70)
    print("THE ONE SIMULATOR: MULTI-CORE HARDWARE BATCH ACCELERATOR")
    print("=" * 70)
    print(f"Total CPU Cores/Threads : {cpu_count}")
    print(f"Active Worker Processes : {workers}")
    print(f"Heap per Worker         : {args.mem} (Parallel GC, Headless)")
    print(f"Scenario File           : {scenario_path}")
    print(f"Run Range               : {args.start} to {args.end} ({total_runs} total runs)")
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
                print(f"[{completed:3d}/{total_runs}] Run {r_id:3d} FAILED in {dur:5.1f}s: {err[:80]}")

    total_time = time.time() - start_all
    print("=" * 70)
    print(f"BATCH EXECUTION COMPLETE!")
    print(f"Total Time: {total_time:.2f}s ({total_time/60:.2f} minutes)")
    print(f"Successful Runs: {completed - failed}/{total_runs}")
    if failed > 0:
        print(f"Failed Runs: {failed}")
    print("=" * 70)

if __name__ == "__main__":
    main()
