"""
Lightweight Config, Coprimality & 10-Second Smoke Validator for Mobility Scenarios.

Validates scenarios/mob_*.txt files in ADTNM/the-one without running any full
simulations:
  1. Checks pairwise coprimality of every `[a; b; ...]` sweep parameter list.
  2. Verifies total host count matches Event host-index bounds.
  3. Confirms all referenced map/route/config/rules files exist inside --one-dir.
  4. With --smoke, executes a 10-second (`Scenario.endTime = 10`, warmup = 0)
     ONE simulator dry pass (using the first and last router entries) into a
     temporary scratch directory to verify zero SettingsError or WKT faults.
"""

import argparse
import glob
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile

SETTING_RE = re.compile(r"^\s*([A-Za-z0-9_.]+)\s*=\s*(.+)$")
FILE_KEYS = (
    "routeFile",
    "meetingSpotsFile",
    "officeLocationsFile",
    "homeLocationsFile",
    "rulesFile",
    "configFile",
    "mapFile",
)


def parse_settings(path):
    entries = {}
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            m = SETTING_RE.match(line)
            if m:
                entries[m.group(1)] = m.group(2).strip()
    return entries


def split_array(val):
    if val.startswith("[") and val.endswith("]"):
        return [item.strip() for item in val[1:-1].split(";") if item.strip()]
    return [val]


def validate_file(scen_path, one_dir, run_smoke=False):
    errors = []
    entries = parse_settings(scen_path)

    # 1. Check coprimality across all array parameters
    arrays = {}
    for k, v in entries.items():
        items = split_array(v)
        if len(items) > 1:
            arrays[k] = items
    arr_keys = list(arrays.keys())
    for i in range(len(arr_keys)):
        for j in range(i + 1, len(arr_keys)):
            li = len(arrays[arr_keys[i]])
            lj = len(arrays[arr_keys[j]])
            if math.gcd(li, lj) != 1:
                errors.append(
                    f"Non-coprime sweep lengths: {arr_keys[i]}({li}) vs {arr_keys[j]}({lj})"
                )

    # 2. Check referenced WKT/rules/config files exist inside one_dir
    for k, v in entries.items():
        if any(tag in k for tag in FILE_KEYS):
            for item in split_array(v):
                rel = os.path.join(one_dir, item.replace("/", os.sep))
                if not os.path.exists(rel):
                    errors.append(f"Missing referenced file for {k}: {item}")

    # 3. Check total host count covers all Event index bounds
    n_groups = int(entries.get("Scenario.nrofHostGroups", "0"))
    total_hosts = 0
    for gi in range(1, n_groups + 1):
        total_hosts += int(entries.get(f"Group{gi}.nrofHosts", "0"))
    n_events = int(entries.get("Events.nrof", "0"))
    for ei in range(1, n_events + 1):
        for suffix in ("hosts", "tohosts"):
            rng = entries.get(f"Events{ei}.{suffix}")
            if rng:
                parts = [int(x.strip()) for x in rng.split(",")]
                if parts[0] < 0 or parts[1] > total_hosts:
                    errors.append(
                        f"Events{ei}.{suffix}={rng} out of range for {total_hosts} hosts"
                    )

    # 4. Optional 10-second ONE Simulator smoke execution
    if run_smoke and not errors:
        tmp_dir = tempfile.mkdtemp(prefix="dtnma_mob_smoke_")
        try:
            routers = split_array(entries.get("Group.router", "DtnmaM2MobRouter"))
            smoke_routers = [routers[0]]
            if len(routers) > 1:
                smoke_routers.append(routers[-1])
            seeds = split_array(entries.get("MovementModel.rngSeed", "1"))
            rep_dir = os.path.join(tmp_dir, "reports").replace("\\", "/")
            os.makedirs(rep_dir, exist_ok=True)
            for idx, rname in enumerate(smoke_routers):
                override = os.path.join(tmp_dir, f"smoke_{idx}.txt")
                shutil.copyfile(scen_path, override)
                with open(override, "a", encoding="utf-8") as fh:
                    fh.write("\n# Smoke overrides\n")
                    fh.write(f"Scenario.endTime = 10\n")
                    fh.write(f"MovementModel.warmup = 0\n")
                    fh.write(f"Group.router = {rname}\n")
                    fh.write(f"MovementModel.rngSeed = {seeds[0]}\n")
                    fh.write(f"Report.reportDir = {rep_dir}\n")
                cmd = [
                    "java",
                    "-Xmx1536m",
                    "-cp",
                    "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar",
                    "core.DTNSim",
                    "-b",
                    "1",
                    override,
                ]
                res = subprocess.run(
                    cmd,
                    cwd=one_dir,
                    capture_output=True,
                    text=True,
                    timeout=120,
                )
                combined = (res.stdout or "") + "\n" + (res.stderr or "")
                if res.returncode != 0 or "Exception" in combined or "SettingsError" in combined:
                    errors.append(
                        f"Smoke run failed for router={rname} (rc={res.returncode}): {combined[-600:]}"
                    )
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

    return len(errors) == 0, total_hosts, len(arrays.get("Group.router", [1])), len(arrays.get("MovementModel.rngSeed", [1])), errors


def main():
    parser = argparse.ArgumentParser(description="Validate mobility scenario files.")
    parser.add_argument("--one-dir", required=True, help="Path to the-one simulator directory")
    parser.add_argument(
        "--pattern",
        default="scenarios/mob_*.txt",
        help="Glob pattern relative to --one-dir (or ADTNM root)",
    )
    parser.add_argument(
        "--smoke",
        action="store_true",
        help="Run a 10-second ONE simulation check on each scenario",
    )
    args = parser.parse_args()

    one_dir = os.path.abspath(args.one_dir)
    paths = sorted(glob.glob(os.path.join(one_dir, args.pattern)))
    if not paths:
        print(f"No scenario files found for pattern: {args.pattern}", file=sys.stderr)
        return 2

    all_ok = True
    for p in paths:
        ok, hosts, n_r, n_s, errs = validate_file(p, one_dir, run_smoke=args.smoke)
        rel = os.path.relpath(p, one_dir)
        status = "PASS" if ok else "FAIL"
        print(f"[{status}] {rel} | hosts={hosts} | routers={n_r} | seeds={n_s} | runs={n_r * n_s}")
        for err in errs:
            all_ok = False
            print(f"       ERROR: {err}", file=sys.stderr)

    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
