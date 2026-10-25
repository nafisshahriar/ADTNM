#!/usr/bin/env bash
# ====================================================================
#  Linux / macOS 1-Click Installer for Autonomic DTNMA Framework
# ====================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/install.py" "$@"
