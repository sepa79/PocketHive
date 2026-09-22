#!/usr/bin/env python3
"""Responsibility: invoke the intake CLI from any installation directory.
Must not: own parsing, validation or document state. Contract: intake-contract.md.
"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from intake_lib.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
