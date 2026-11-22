"""
Parallel Batch Runner for The ONE Simulator
Maximizes hardware utilization politely across multi-core CPUs (50% default core cap,
BELOW_NORMAL/nice OS priority), enforces pairwise-coprime parameter list validation,
guarantees zero-orphan child process tree termination on exit or Ctrl+C / SIGTERM,
supports --dry-run and --resume, and displays live active simulation run numbers.
"""

import os
import re
import sys
import math
import time
import atexit
import signal
import argparse
import threading
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
import multiprocessing

SETTING_LINE_RE = re.compile(r"^\s*([A-Za-z0-9_.]+)\s*=\s*(.+)$")
PLACEHOLDER_RE = re.compile(r"%%([^%]+)%%")

_PROC_LOCK = threading.Lock()
_ACTIVE_PROCS = set()
_STOP_EVENT = threading.Event()


def _kill_proc_tree(proc):
    """Terminates a subprocess and its entire OS process tree."""
    if proc is None or proc.poll() is not None:
        return
    try:
        proc.terminate()
    except Exception:
        pass
    try:
        proc.wait(timeout=0.5)
        return
    except Exception:
        pass
    if sys.platform == "win32":
        try:
            subprocess.run(
                ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        except Exception:
            pass
    else:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
    try:
        proc.wait(timeout=2.0)
    except Exception:
        pass


def terminate_all_children():
    """Signals cancellation and forcefully reaps all running child JVM processes."""
    _STOP_EVENT.set()
    with _PROC_LOCK:
        procs = list(_ACTIVE_PROCS)
    for proc in procs:
        try:
            if proc.poll() is None:
                proc.terminate()
        except Exception:
            pass
    if procs:
        time.sleep(0.5)
    for proc in procs:
        _kill_proc_tree(proc)
    with _PROC_LOCK:
        _ACTIVE_PROCS.clear()


def _handle_termination_signal(signum, frame):
    terminate_all_children()
    raise KeyboardInterrupt(f"Received signal {signum}")


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


def _build_popen_kwargs(cwd, priority):
    kwargs = {
        "cwd": cwd,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.PIPE,
        "text": True,
    }
    if sys.platform == "win32":
        flags = subprocess.CREATE_NEW_PROCESS_GROUP
        if priority == "low":
            flags |= subprocess.BELOW_NORMAL_PRIORITY_CLASS
        kwargs["creationflags"] = flags
    else:
        kwargs["start_new_session"] = True
        if priority == "low":
            kwargs["preexec_fn"] = lambda: os.nice(10)
    return kwargs


def run_single(run_id, total_runs, scenario_file, classpath, java_opts, cwd, lock, active_runs, priority):
    if _STOP_EVENT.is_set():
        return run_id, False, 0.0, "Cancelled"

    with lock:
        active_runs.add(run_id)
        active_list = sorted(active_runs)
        print(f"[START] Run {run_id:3d} queued/running | Active ({len(active_list)}): {active_list}", flush=True)

    cmd = ["java"] + java_opts + ["-cp", classpath, "core.DTNSim", "-b", f"{run_id}:{run_id}", scenario_file]
    t0 = time.time()
    proc = None
    try:
        with _PROC_LOCK:
            if _STOP_EVENT.is_set():
                return run_id, False, 0.0, "Cancelled"
            proc = subprocess.Popen(cmd, **_build_popen_kwargs(cwd, priority))
            _ACTIVE_PROCS.add(proc)

        out, err = proc.communicate()
        dur = time.time() - t0
        if proc.returncode == 0:
            return run_id, True, dur, ""
        msg = (err or out or f"Exit code {proc.returncode}").strip()
        return run_id, False, dur, msg
    except Exception as e:
        return run_id, False, time.time() - t0, str(e)
    finally:
        with _PROC_LOCK:
            if proc is not None:
                _ACTIVE_PROCS.discard(proc)
        if proc is not None and proc.poll() is None:
            _kill_proc_tree(proc)
        with lock:
            active_runs.discard(run_id)


def main():
    atexit.register(terminate_all_children)
    signal.signal(signal.SIGINT, _handle_termination_signal)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, _handle_termination_signal)
    if hasattr(signal, "SIGBREAK"):
        signal.signal(signal.SIGBREAK, _handle_termination_signal)

    parser = argparse.ArgumentParser(description="Polite Multi-Core Batch Accelerator for The ONE")
    parser.add_argument("--scenario", "-s", default="scenarios/dtnma_batch_scenario.txt", help="Scenario configuration file")
    parser.add_argument("--start", type=int, default=1, help="Starting run index (1-based)")
    parser.add_argument("--end", type=int, default=None, help="Ending run index (inclusive; auto-computed if omitted)")
    parser.add_argument("--workers", "-w", type=int, default=None, help="Parallel worker threads (default: 50%% of CPU cores)")
    parser.add_argument("--priority", choices=["low", "normal"], default="low", help="Child JVM OS process priority (default: low / BelowNormal)")
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
            print("  ...")
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
    workers = args.workers if args.workers else max(1, cpu_count // 2)

    java_opts = [
        "-server",
        "-Xms512M",
        f"-Xmx{args.mem}",
        "-XX:+UseParallelGC",
        "-XX:+TieredCompilation",
        "-XX:TieredStopAtLevel=4",
        "-Djava.awt.headless=true",
        "-Djava.util.Arrays.useLegacyMergeSort=true",
    ]

    total_to_run = len(run_list)

    print("-" * 72)
    print(f"Total CPU Cores/Threads : {cpu_count}")
    print(f"Active Worker Processes : {workers} (default 50% cap; OS priority={args.priority})")
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

    executor = ThreadPoolExecutor(max_workers=workers)
    try:
        futures = {
            executor.submit(
                run_single, r_id, total_to_run, scenario_path, classpath, java_opts,
                the_one_dir, lock, active_runs, args.priority
            ): r_id
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
                    flush=True,
                )
            else:
                failed += 1
                print(
                    f"[FAIL ] [{completed:3d}/{total_to_run}] Run {r_id:3d} FAILED ({dur:5.1f}s): {err[:120]}",
                    flush=True,
                )
    except KeyboardInterrupt:
        terminate_all_children()
        executor.shutdown(wait=False, cancel_futures=True)
        print("\n[ABORT] Interrupted -- terminated all child simulations cleanly.", flush=True)
        sys.exit(130)
    finally:
        terminate_all_children()
        executor.shutdown(wait=True, cancel_futures=True)

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
