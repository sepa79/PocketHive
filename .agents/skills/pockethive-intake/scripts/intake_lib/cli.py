"""Responsibility: decode CLI arguments and expose the canonical command result.
Must not: own document state, review decisions or validation rules. Contract: intake-contract.md.
"""
from __future__ import annotations

import argparse
import json

from .errors import IntakeError
from .package_context import PackageContext


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise IntakeError("ARGUMENTS", "Invalid arguments; use --help for the explicit command interface.")


def main() -> int:
    command = None
    try:
        parser = ArgumentParser(description="Create and check sourced PocketHive intake documents; never execute tests.")
        subparsers = parser.add_subparsers(dest="command", required=True)
        create = subparsers.add_parser("initialise")
        create.add_argument("--output", required=True)
        create.add_argument("--mode", required=True, choices=("from-bundle", "new-requirements"))
        create.add_argument("--source")
        inspect = subparsers.add_parser("inspect-bundle")
        inspect.add_argument("--source", required=True)
        validate = subparsers.add_parser("validate")
        validate.add_argument("--documents", required=True)
        validate.add_argument("--stage", required=True, choices=("draft", "handoff"))
        finalise = subparsers.add_parser("finalise")
        finalise.add_argument("--documents", required=True)
        subparsers.add_parser("verify-package")
        args = parser.parse_args()
        command = args.command
        package = PackageContext()
        integrity = package.verify()
        if command == "verify-package":
            result = integrity
        else:
            package.load_libraries()
            from .commands import execute
            result = execute(package, args)
        result.setdefault("errors", [])
        result.setdefault("gaps", [])
        result.setdefault("warnings", [])
        result["command"] = command
        result["status"] = "error" if result["errors"] else "incomplete" if result["gaps"] else "ok"
        code = 2 if result["errors"] else 3 if command == "validate" and args.stage == "handoff" and result["gaps"] else 0
    except IntakeError as error:
        result, code = {"command": command, "status": "error", "errors": [error.issue], "gaps": [], "warnings": []}, 2
    except (OSError, ValueError, KeyError, TypeError, ImportError, RecursionError):
        result, code = {"command": command, "status": "error", "errors": [IntakeError("COMMAND_FAILED", "The command failed safely; check package integrity and declared input structure.").issue], "gaps": [], "warnings": []}, 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return code
