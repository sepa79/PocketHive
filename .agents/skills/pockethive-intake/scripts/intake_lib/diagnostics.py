"""Responsibility: describe failed CLI operations without disclosing client data.
Must not: repair inputs, expose exception messages/locals or choose command outcomes. Local contract: intake-contract.md.
Contract: RESP-INTAKE-CLI — docs/architecture/intake-runtime.md#resp-intake-cli.
"""
from __future__ import annotations

import json
from pathlib import Path
import sys
import traceback

from .errors import IntakeError


def command_failure(error: Exception) -> IntakeError:
    explanations = {
        KeyError: "A required mapping key was missing.",
        TypeError: "An operation received an incompatible value type.",
        ValueError: "A value could not be interpreted.",
        OSError: "A filesystem operation failed.",
        ImportError: "A required Python component could not be loaded.",
        RecursionError: "The operation exceeded the Python recursion limit.",
    }
    explanation = next((message for kind, message in explanations.items() if isinstance(error, kind)),
                       "An internal operation failed.")
    name = type(error).__name__
    return IntakeError("COMMAND_FAILED", f"{name}: {explanation} Use --debug for package code locations.",
                       detail={"exceptionType": name})


def write_debug(command: str | None, issues: list[dict], error: Exception | None, package_root: Path | None) -> None:
    frames = []
    if error is not None and package_root is not None:
        for frame, line in traceback.walk_tb(error.__traceback__):
            path = Path(frame.f_code.co_filename)
            if path.is_absolute() and path.is_relative_to(package_root):
                frames.append({"file": path.relative_to(package_root).as_posix(),
                               "line": line, "function": frame.f_code.co_name})
    diagnostic = {"command": command, "errors": issues, "frames": frames[-8:]}
    if error is not None:
        diagnostic["exceptionType"] = type(error).__name__
        cause = error.__cause__ if error.__cause__ is not None else error.__context__
        if cause is not None:
            diagnostic["causeType"] = type(cause).__name__
    print(json.dumps(diagnostic, ensure_ascii=False), file=sys.stderr)
