#!/usr/bin/env python3
"""
install.py - Autonomic DTNMA Framework Installer for The ONE Simulator
======================================================================
Automates the injection of the Autonomic DTNMA Framework (RFC 9675 / RFC 7575)
into a fresh, standard clone of The ONE Simulator (https://github.com/akeranen/the-one).

Usage:
    python install.py --one-dir <path-to-the-one>
    python install.py                              # Auto-detects ../the-one
    python install.py --help

What this installer does:
1. Validates that the target directory is a valid ONE Simulator installation.
2. Injects DTNMA router and report source files into src/routing/ and src/report/.
3. Safely patches ActiveRouter.java and ProphetRouter.java with backups (.bak).
4. Injects declarative rules and scenario configuration files into scenarios/.
5. Deploys multi-threaded parallel batch runner, verification, and analysis tools.
6. Optimizes one.bat and one.sh with production JVM memory flags (1G-4G, ParallelGC).
7. Compiles the updated simulator and verifies clean compilation.
"""

import os
import sys
import shutil
import argparse
import subprocess
from pathlib import Path

COLOR_GREEN = "\033[92m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_YELLOW = "\033[93m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_RED = "\033[91m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_CYAN = "\033[96m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_RESET = "\033[0m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""

def log_info(msg):
    print(f"{COLOR_CYAN}[INFO]{COLOR_RESET} {msg}")

def log_success(msg):
    print(f"{COLOR_GREEN}[SUCCESS]{COLOR_RESET} {msg}")

def log_warn(msg):
    print(f"{COLOR_YELLOW}[WARN]{COLOR_RESET} {msg}")

def log_error(msg):
    print(f"{COLOR_RED}[ERROR]{COLOR_RESET} {msg}")

def validate_one_dir(one_dir: Path) -> bool:
    """Verifies that the target directory looks like The ONE Simulator."""
    indicators = [
        one_dir / "src" / "core" / "DTNSim.java",
        one_dir / "src" / "routing" / "MessageRouter.java",
        one_dir / "src" / "routing" / "ActiveRouter.java",
        one_dir / "src" / "routing" / "ProphetRouter.java",
    ]
    return all(p.is_file() for p in indicators)

def patch_file(file_path: Path, old_str: str, new_str: str, description: str):
    """Safely patches a target file, creating a .bak copy first."""
    if not file_path.is_file():
        log_error(f"Cannot patch missing file: {file_path}")
        return False

    content = file_path.read_text(encoding="utf-8")
    if new_str in content:
        log_info(f"{file_path.name} already has {description} - skipping patch.")
        return True

    if old_str not in content:
        log_warn(f"Could not locate expected signature in {file_path.name} to apply {description}.")
        return False

    backup_path = file_path.with_suffix(file_path.suffix + ".bak")
    if not backup_path.exists():
        shutil.copy2(file_path, backup_path)
        log_info(f"Created backup: {backup_path.name}")

    updated_content = content.replace(old_str, new_str, 1)
    file_path.write_text(updated_content, encoding="utf-8")
    log_success(f"Successfully patched {file_path.name}: {description}")
    return True

def copy_tree_files(src_dir: Path, dest_dir: Path, pattern: str = "*"):
    """Copies matching files from source to destination directory."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    for src_file in src_dir.glob(pattern):
        if src_file.is_file():
            shutil.copy2(src_file, dest_dir / src_file.name)
            count += 1
    return count

def optimize_launch_scripts(one_dir: Path):
    """Updates one.bat and one.sh with tuned JVM parameters."""
    one_bat = one_dir / "one.bat"
    if one_bat.is_file():
        bat_content = one_bat.read_text(encoding="utf-8", errors="ignore")
        if "-Xmx512M" in bat_content:
            bat_content = bat_content.replace(
                "-Xmx512M",
                "-server -Xms1G -Xmx4G -XX:+UseParallelGC"
            )
            one_bat.write_text(bat_content, encoding="utf-8")
            log_success("Optimized one.bat with '-server -Xms1G -Xmx4G -XX:+UseParallelGC'")

    one_sh = one_dir / "one.sh"
    if one_sh.is_file():
        sh_content = one_sh.read_text(encoding="utf-8", errors="ignore")
        if "-Xmx512M" in sh_content:
            sh_content = sh_content.replace(
                "-Xmx512M",
                "-server -Xms1G -Xmx4G -XX:+UseParallelGC"
            )
            one_sh.write_text(sh_content, encoding="utf-8")
            log_success("Optimized one.sh with '-server -Xms1G -Xmx4G -XX:+UseParallelGC'")

def compile_one(one_dir: Path) -> bool:
    """Invokes compile.bat on Windows or compile.sh / javac on POSIX."""
    log_info("Compiling The ONE Simulator with DTNMA components...")
    try:
        if sys.platform == "win32":
            compile_bat = one_dir / "compile.bat"
            if compile_bat.is_file():
                proc = subprocess.run(["cmd.exe", "/c", "compile.bat"], cwd=str(one_dir), capture_output=True, text=True)
            else:
                cmd = ["javac", "-sourcepath", "src", "-d", "target", "-cp", "lib/ECLA.jar;lib/DTNConsoleConnection.jar", "src/core/*.java", "src/movement/*.java", "src/report/*.java", "src/routing/*.java", "src/gui/*.java", "src/input/*.java", "src/applications/*.java", "src/interfaces/*.java"]
                proc = subprocess.run(cmd, cwd=str(one_dir), capture_output=True, text=True)
        else:
            compile_sh = one_dir / "compile.sh"
            if compile_sh.is_file():
                proc = subprocess.run(["bash", "compile.sh"], cwd=str(one_dir), capture_output=True, text=True)
            else:
                cmd = ["javac", "-sourcepath", "src", "-d", "target", "-cp", "lib/ECLA.jar:lib/DTNConsoleConnection.jar", "src/core/*.java", "src/movement/*.java", "src/report/*.java", "src/routing/*.java", "src/gui/*.java", "src/input/*.java", "src/applications/*.java", "src/interfaces/*.java"]
                proc = subprocess.run(cmd, cwd=str(one_dir), capture_output=True, text=True)

        if proc.returncode == 0:
            log_success("Compilation finished successfully (Exit Code 0).")
            return True
        else:
            log_error(f"Compilation failed with exit code {proc.returncode}:\n{proc.stderr or proc.stdout}")
            return False
    except Exception as e:
        log_error(f"Failed to execute compiler: {e}")
        return False

def run_smoke_test(one_dir: Path) -> bool:
    """Executes a 1-run test using scenario_dtnma.txt to ensure runtime stability."""
    log_info("Running DTNMA benchmark smoke test...")
    scenario_path = one_dir / "scenarios" / "scenario_dtnma.txt"
    if not scenario_path.is_file():
        log_warn("scenario_dtnma.txt not found, skipping smoke test.")
        return True

    try:
        cp = "target;lib/ECLA.jar;lib/DTNConsoleConnection.jar" if sys.platform == "win32" else "target:lib/ECLA.jar:lib/DTNConsoleConnection.jar"
        cmd = ["java", "-cp", cp, "core.DTNSim", "-b", "1", "scenarios/scenario_dtnma.txt"]
        proc = subprocess.run(cmd, cwd=str(one_dir), capture_output=True, text=True)
        if proc.returncode == 0:
            log_success("Smoke test passed: Simulation completed with exit code 0.")
            return True
        else:
            log_warn(f"Smoke test returned code {proc.returncode}:\n{proc.stderr or proc.stdout}")
            return False
    except Exception as e:
        log_warn(f"Could not execute smoke test: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(
        description="Install Autonomic DTNMA Framework into a fresh clone of The ONE Simulator."
    )
    parser.add_argument(
        "--one-dir", "-d",
        type=str,
        default=None,
        help="Path to the directory of The ONE Simulator (default: checks '../the-one', '../the-one-master', './the-one')"
    )
    parser.add_argument(
        "--no-compile",
        action="store_true",
        help="Skip automatic compilation of simulator after installing files."
    )
    parser.add_argument(
        "--smoke-test",
        action="store_true",
        default=True,
        help="Execute a benchmark smoke test simulation after compilation."
    )
    args = parser.parse_args()

    repo_dir = Path(__file__).resolve().parent

    print("=" * 72)
    print("  AUTONOMIC DTNMA FRAMEWORK INSTALLER (RFC 9675 / RFC 7575)")
    print("=" * 72)

    # Locate ONE directory
    target_dir = None
    if args.one_dir:
        cand = Path(args.one_dir).resolve()
        if validate_one_dir(cand):
            target_dir = cand
        else:
            log_error(f"Provided path '{cand}' is not a valid ONE Simulator directory.")
            sys.exit(1)
    else:
        candidates = [
            repo_dir.parent / "the-one",
            repo_dir.parent / "the-one-master",
            repo_dir / "the-one",
        ]
        for c in candidates:
            if c.is_dir() and validate_one_dir(c):
                target_dir = c.resolve()
                break

    if not target_dir:
        print("\nCould not automatically locate The ONE Simulator directory.")
        user_input = input("Enter path to The ONE Simulator directory: ").strip()
        cand = Path(user_input).resolve()
        if cand.is_dir() and validate_one_dir(cand):
            target_dir = cand
        else:
            log_error(f"Invalid ONE Simulator directory: '{cand}'")
            sys.exit(1)

    log_info(f"Target ONE Simulator directory: {target_dir}")

    # 1. Inject Java Source Files
    log_info("Deploying Java source code...")
    routing_src = repo_dir / "src" / "routing"
    routing_dest = target_dir / "src" / "routing"
    c_routing = copy_tree_files(routing_src, routing_dest, "*.java")

    report_src = repo_dir / "src" / "report"
    report_dest = target_dir / "src" / "report"
    c_report = copy_tree_files(report_src, report_dest, "*.java")
    log_success(f"Copied {c_routing} routing classes and {c_report} report classes.")

    # 2. Patch ActiveRouter.java and ProphetRouter.java
    log_info("Patching base ONE classes for DTNMA compatibility...")
    active_router = target_dir / "src" / "routing" / "ActiveRouter.java"
    patch_file(
        active_router,
        "private EnergyModel energy;",
        "protected EnergyModel energy;",
        "accessible EnergyModel field (private -> protected)"
    )

    prophet_router = target_dir / "src" / "routing" / "ProphetRouter.java"
    patch_file(
        prophet_router,
        "private Map<DTNHost, Double> getDeliveryPreds()",
        "public Map<DTNHost, Double> getDeliveryPreds()",
        "accessible delivery predictions (private -> public)"
    )

    # 3. Deploy Scenarios & Rules
    log_info("Deploying scenario configurations and declarative rules...")
    scenarios_src = repo_dir / "scenarios"
    scenarios_dest = target_dir / "scenarios"
    c_scenarios = copy_tree_files(scenarios_src, scenarios_dest, "*.txt")
    log_success(f"Copied {c_scenarios} scenario and rule configuration files.")

    # 4. Deploy Tools, Core, and Config
    log_info("Deploying evaluation scripts and OppNDA analysis modules...")
    tools_src = repo_dir / "tools"
    for tool_name in ["parallel_batch_runner.py", "verify_and_plot.py"]:
        tf = tools_src / tool_name
        if tf.is_file():
            shutil.copy2(tf, target_dir / tool_name)
            log_info(f"Installed {tool_name}")

    core_src = tools_src / "core"
    if core_src.is_dir():
        core_dest = target_dir / "core"
        core_dest.mkdir(parents=True, exist_ok=True)
        copy_tree_files(core_src, core_dest, "*.py")
        log_info("Installed OppNDA core package.")

    config_src = tools_src / "config"
    if config_src.is_dir():
        config_dest = target_dir / "config"
        config_dest.mkdir(parents=True, exist_ok=True)
        copy_tree_files(config_src, config_dest, "*.json")
        log_info("Installed automation configurations.")

    # 5. Optimize memory launch scripts
    optimize_launch_scripts(target_dir)

    # 6. Compilation
    if not args.no_compile:
        comp_ok = compile_one(target_dir)
        if not comp_ok:
            log_warn("Compilation encountered issues. Please review error output above.")
        elif args.smoke_test:
            run_smoke_test(target_dir)

    print("\n" + "=" * 72)
    print("  INSTALLATION COMPLETE!")
    print("=" * 72)
    print("You can now run simulations in The ONE with Autonomic DTNMA:")
    print(f"  cd {target_dir}")
    print("  # Run 1-hour DTNMA benchmark:")
    print("  ./one.bat -b 1 scenarios/scenario_dtnma.txt        (Windows)")
    print("  ./one.sh -b 1 scenarios/scenario_dtnma.txt         (Linux/macOS)")
    print("\n  # Parse results & verify zero drops:")
    print("  python verify_and_plot.py")
    print("\n  # Run multi-core parallel batch parametric study:")
    print("  python parallel_batch_runner.py --scenario scenarios/dtnma_batch_scenario.txt")
    print("=" * 72 + "\n")

if __name__ == "__main__":
    main()
