#!/usr/bin/env python3
"""
install.py - Autonomic DTNMA Framework Installer & Collaborative Sync Tool
==========================================================================
Provides seamless 2-way integration between the ADTNM research repository
and The ONE Simulator (https://github.com/akeranen/the-one).

Designed for iterative, collaborative multi-developer workflows:
- Forward deployment: Inject or update ADTNM files in The ONE.
- Live-Link mode: Hard-link / symlink files for instantaneous live editing across IDEs.
- Sync-back mode: Automatically pull edits made in The ONE back into ADTNM for git commit.
- Status audit: Compare file timestamps and hashes between ADTNM and The ONE.

Usage:
    python install.py --one-dir <path-to-the-one>            # Standard copy & compile
    python install.py --mode link --one-dir <path>          # Live-link for simultaneous edits
    python install.py --sync-back --one-dir <path>          # Pull changes from simulator into repo
    python install.py --status --one-dir <path>             # Check sync status between repo & simulator
    python install.py --help
"""

import os
import sys
import shutil
import hashlib
import argparse
import subprocess
from pathlib import Path

COLOR_GREEN = "\033[92m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_YELLOW = "\033[93m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_RED = "\033[91m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_CYAN = "\033[96m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_MAGENTA = "\033[95m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""
COLOR_RESET = "\033[0m" if sys.platform != "win32" or "WT_SESSION" in os.environ else ""

def log_info(msg):
    print(f"{COLOR_CYAN}[INFO]{COLOR_RESET} {msg}")

def log_success(msg):
    print(f"{COLOR_GREEN}[SUCCESS]{COLOR_RESET} {msg}")

def log_warn(msg):
    print(f"{COLOR_YELLOW}[WARN]{COLOR_RESET} {msg}")

def log_error(msg):
    print(f"{COLOR_RED}[ERROR]{COLOR_RESET} {msg}")

def file_hash(path: Path) -> str:
    """Calculates SHA256 of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

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

def link_or_copy(src_file: Path, dest_file: Path, mode: str):
    """Copies or hardlinks/symlinks a file based on mode."""
    dest_file.parent.mkdir(parents=True, exist_ok=True)
    if dest_file.exists():
        try:
            dest_file.unlink()
        except Exception:
            pass

    if mode == "link":
        try:
            # On Windows/POSIX, os.link creates a hard link (same inode/MFT entry)
            os.link(src_file, dest_file)
            return True
        except Exception as e:
            log_warn(f"Hard-link failed for {src_file.name} ({e}), falling back to copy.")
            shutil.copy2(src_file, dest_file)
            return True
    else:
        shutil.copy2(src_file, dest_file)
        return True

def deploy_tree(src_dir: Path, dest_dir: Path, pattern: str = "*", mode: str = "copy"):
    """Copies or links matching files from source to destination directory."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    for src_file in src_dir.glob(pattern):
        if src_file.is_file():
            link_or_copy(src_file, dest_dir / src_file.name, mode)
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

def get_tracked_file_pairs(repo_dir: Path, one_dir: Path):
    """Returns mapping of (relative_path, repo_path, simulator_path) for tracked files."""
    pairs = []

    # Java routing files
    for f in (repo_dir / "src" / "routing").glob("*.java"):
        pairs.append((Path("src") / "routing" / f.name, f, one_dir / "src" / "routing" / f.name))

    # Java report files
    for f in (repo_dir / "src" / "report").glob("*.java"):
        pairs.append((Path("src") / "report" / f.name, f, one_dir / "src" / "report" / f.name))

    # Scenarios and rules
    for f in (repo_dir / "scenarios").glob("*.txt"):
        pairs.append((Path("scenarios") / f.name, f, one_dir / "scenarios" / f.name))

    # Runner scripts
    for name in ["parallel_batch_runner.py", "verify_and_plot.py"]:
        rf = repo_dir / "tools" / name
        if rf.is_file():
            pairs.append((Path(name), rf, one_dir / name))

    # OppNDA core
    for f in (repo_dir / "tools" / "core").glob("*.py"):
        pairs.append((Path("core") / f.name, f, one_dir / "core" / f.name))

    # Automation configs
    for f in (repo_dir / "tools" / "config").glob("*.json"):
        pairs.append((Path("config") / f.name, f, one_dir / "config" / f.name))

    return pairs

def audit_status(repo_dir: Path, one_dir: Path):
    """Compares files between ADTNM repo and The ONE simulator directory."""
    pairs = get_tracked_file_pairs(repo_dir, one_dir)
    print("\n" + "=" * 78)
    print(f"{'FILE':<42} | {'STATUS':<15} | {'DIFF NOTE'}")
    print("=" * 78)

    in_sync = 0
    sim_newer = 0
    repo_newer = 0
    missing = 0

    for rel, repo_p, sim_p in pairs:
        if not sim_p.is_file():
            print(f"{str(rel):<42} | {COLOR_RED}MISSING IN SIM{COLOR_RESET} | Run install to deploy")
            missing += 1
        else:
            h_repo = file_hash(repo_p)
            h_sim = file_hash(sim_p)
            if h_repo == h_sim:
                print(f"{str(rel):<42} | {COLOR_GREEN}IN SYNC{COLOR_RESET}        | Hash match")
                in_sync += 1
            else:
                m_repo = repo_p.stat().st_mtime
                m_sim = sim_p.stat().st_mtime
                if m_sim > m_repo:
                    print(f"{str(rel):<42} | {COLOR_YELLOW}SIMULATOR NEWER{COLOR_RESET}| Needs sync-back to repo")
                    sim_newer += 1
                else:
                    print(f"{str(rel):<42} | {COLOR_CYAN}REPO NEWER{COLOR_RESET}     | Needs install deploy to sim")
                    repo_newer += 1

    print("=" * 78)
    print(f"Summary: {in_sync} in sync, {sim_newer} simulator newer, {repo_newer} repo newer, {missing} missing.")
    if sim_newer > 0:
        print(f"\n{COLOR_YELLOW}[TIP]{COLOR_RESET} Use 'python install.py --sync-back' to pull simulator changes into repo.")
    if repo_newer > 0 or missing > 0:
        print(f"\n{COLOR_CYAN}[TIP]{COLOR_RESET} Use 'python install.py' to deploy latest repo code into simulator.")
    print("=" * 78 + "\n")

def sync_back_to_repo(repo_dir: Path, one_dir: Path):
    """Pulls updated files from The ONE back into ADTNM for git commit."""
    pairs = get_tracked_file_pairs(repo_dir, one_dir)
    print("\n" + "=" * 78)
    print("  SYNC-BACK: PULLING MODIFICATIONS FROM THE ONE SIMULATOR INTO ADTNM REPO")
    print("=" * 78)

    synced_count = 0
    for rel, repo_p, sim_p in pairs:
        if sim_p.is_file():
            h_repo = file_hash(repo_p)
            h_sim = file_hash(sim_p)
            if h_repo != h_sim:
                m_repo = repo_p.stat().st_mtime
                m_sim = sim_p.stat().st_mtime
                if m_sim > m_repo:
                    shutil.copy2(sim_p, repo_p)
                    log_success(f"Synced back: {rel} (Simulator version -> Repo)")
                    synced_count += 1
                else:
                    log_warn(f"Skipped {rel}: Repo version has newer timestamp than simulator.")

    if synced_count == 0:
        log_info("All tracked files are already up-to-date with ADTNM repo.")
    else:
        log_success(f"Successfully synced {synced_count} updated file(s) into ADTNM.")
        print(f"\nYou can now commit the changes in ADTNM:")
        print("  cd ADTNM")
        print("  git status")
        print("  git add .")
        print("  git commit -m \"Update DTNMA framework components from simulation run\"")
        print("  git push origin main")
    print("=" * 78 + "\n")

def main():
    parser = argparse.ArgumentParser(
        description="Install & Sync Autonomic DTNMA Framework with The ONE Simulator."
    )
    parser.add_argument(
        "--one-dir", "-d",
        type=str,
        default=None,
        help="Path to The ONE Simulator directory (default: auto-detects '../the-one', etc.)"
    )
    parser.add_argument(
        "--mode", "-m",
        choices=["copy", "link"],
        default="copy",
        help="'copy' = regular file deployment; 'link' = hard-links files for instantaneous live editing across IDEs"
    )
    parser.add_argument(
        "--sync-back", "-s",
        action="store_true",
        help="Pull updated files from The ONE simulator back into ADTNM repo for git committing."
    )
    parser.add_argument(
        "--status",
        action="store_true",
        help="Display sync / comparison status between ADTNM repo and simulator."
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

    # Status check mode
    if args.status:
        audit_status(repo_dir, target_dir)
        sys.exit(0)

    # Sync-back mode
    if args.sync_back:
        sync_back_to_repo(repo_dir, target_dir)
        sys.exit(0)

    # Deploy mode
    print("=" * 72)
    print("  AUTONOMIC DTNMA FRAMEWORK INSTALLER & DEPLOYER")
    print(f"  Mode: {args.mode.upper()} {'(Live-Linked Edits)' if args.mode == 'link' else '(File Copy)'}")
    print("=" * 72)
    log_info(f"Target ONE Simulator directory: {target_dir}")

    # 1. Deploy Java Source Files
    log_info("Deploying Java source code...")
    routing_src = repo_dir / "src" / "routing"
    routing_dest = target_dir / "src" / "routing"
    c_routing = deploy_tree(routing_src, routing_dest, "*.java", mode=args.mode)

    report_src = repo_dir / "src" / "report"
    report_dest = target_dir / "src" / "report"
    c_report = deploy_tree(report_src, report_dest, "*.java", mode=args.mode)
    log_success(f"Deployed {c_routing} routing classes and {c_report} report classes.")

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
    c_scenarios = deploy_tree(scenarios_src, scenarios_dest, "*.txt", mode=args.mode)
    log_success(f"Deployed {c_scenarios} scenario and rule configuration files.")

    # 4. Deploy Tools, Core, and Config
    log_info("Deploying evaluation scripts and OppNDA analysis modules...")
    tools_src = repo_dir / "tools"
    for tool_name in ["parallel_batch_runner.py", "verify_and_plot.py"]:
        tf = tools_src / tool_name
        if tf.is_file():
            link_or_copy(tf, target_dir / tool_name, mode=args.mode)
            log_info(f"Installed {tool_name}")

    core_src = tools_src / "core"
    if core_src.is_dir():
        core_dest = target_dir / "core"
        core_dest.mkdir(parents=True, exist_ok=True)
        deploy_tree(core_src, core_dest, "*.py", mode=args.mode)
        log_info("Installed OppNDA core package.")

    config_src = tools_src / "config"
    if config_src.is_dir():
        config_dest = target_dir / "config"
        config_dest.mkdir(parents=True, exist_ok=True)
        deploy_tree(config_src, config_dest, "*.json", mode=args.mode)
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
    print("Workflow Tips:")
    print("  - Check sync status:     python install.py --status")
    print("  - Pull edits to repo:    python install.py --sync-back")
    print("  - Run simulation:        ./one.bat -b 1 scenarios/scenario_dtnma.txt")
    print("  - Verify 0-drop safety:  python verify_and_plot.py")
    print("=" * 72 + "\n")

if __name__ == "__main__":
    main()
