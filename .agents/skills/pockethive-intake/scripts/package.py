#!/usr/bin/env python3
"""Responsibility: expose explicit deterministic release packaging.
Must not: install dependencies, discover client sources or silently reseal edits. Local contract: intake-contract.md.
Contract: RESP-INTAKE-PACKAGING — docs/architecture/intake-runtime.md#resp-intake-packaging.
"""
from pathlib import Path
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

from intake_lib.packaging import main

if __name__ == "__main__":
    raise SystemExit(main())
