"""Responsibility: decode CLI arguments and expose the canonical command result.
Must not: own document state, review decisions or validation rules. Local contract: intake-contract.md.
Contract: RESP-INTAKE-CLI — docs/architecture/intake-runtime.md#resp-intake-cli.
"""
from __future__ import annotations

import argparse
import json

from .diagnostics import command_failure, write_debug
from .errors import IntakeError
from .package_context import PackageContext


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise IntakeError("ARGUMENTS", "Invalid arguments; use --help for the explicit command interface.")


def main() -> int:
    command = None
    package = None
    failure = None
    summary_view = None
    args = argparse.Namespace(debug=False)
    try:
        parser = ArgumentParser(description="Create and check sourced PocketHive intake documents; never execute tests.")
        parser.add_argument("--debug", action="store_true", help="Write safe failure diagnostics to stderr.")
        subparsers = parser.add_subparsers(dest="command", required=True)
        create = subparsers.add_parser("initialise")
        create.add_argument("--output", required=True)
        create.add_argument("--mode", required=True, choices=("from-bundle", "new-requirements"))
        create.add_argument("--source")
        inspect = subparsers.add_parser("inspect-bundle")
        inspect.add_argument("--source", required=True)
        human = subparsers.add_parser("review-input", help="Review raw human YAML forms without requiring enriched document metadata.")
        human.add_argument("--source", required=True, action="append", help="Explicit file or directory of forms; repeat to include separate sources.")
        assess = subparsers.add_parser("assess-workspace", help="Read contents to choose start, raw enrichment or resume.")
        assess.add_argument("--documents", required=True)
        enrich = subparsers.add_parser("enrich-input", help="Stage and publish a sourced draft from an explicit transfer plan.")
        enrich.add_argument("--output", required=True)
        enrich.add_argument("--input", required=True)
        enrich.add_argument("--mode", required=True, choices=("from-bundle", "new-requirements"))
        enrich.add_argument("--source", help="Explicit runtime bundle for from-bundle mode.")
        portable = subparsers.add_parser("make-portable", help="Retain verified evidence and prepare relative references for delivery.")
        portable.add_argument("--documents", required=True)
        validate = subparsers.add_parser("validate")
        validate.add_argument("--documents", required=True)
        validate.add_argument("--stage", required=True, choices=("draft", "handoff"))
        finalise = subparsers.add_parser("finalise")
        finalise.add_argument("--documents", required=True)
        populate = subparsers.add_parser("populate-from-inspection")
        populate.add_argument("--documents", required=True)
        review = subparsers.add_parser("prepare-review")
        review.add_argument("--documents", required=True)
        review.add_argument("--stage", required=True, choices=("draft", "handoff"))
        review.add_argument("--previous")
        review.add_argument("--view", choices=("full", "summary"), default="full",
                            help="Summary keeps decisions and diagnostic counts; full includes all field evidence and findings.")
        review.add_argument("--write-review", action="store_true", help="Write the generated stakeholder Markdown projection beside the forms.")
        field = subparsers.add_parser("show-field")
        field.add_argument("--documents", required=True)
        field.add_argument("--document", required=True)
        field.add_argument("--pointer", required=True)
        fields = subparsers.add_parser("show-fields")
        fields.add_argument("--documents", required=True)
        fields.add_argument("--input", required=True)
        update = subparsers.add_parser("apply-updates")
        update.add_argument("--documents", required=True)
        update.add_argument("--input", required=True)
        update.add_argument("--dry-run", action="store_true", help="Validate and preview the candidate without saving documents.")
        compare = subparsers.add_parser("compare-source")
        compare.add_argument("--documents", required=True)
        compare.add_argument("--previous-source", required=True)
        compare.add_argument("--source", required=True)
        verify = subparsers.add_parser("verify-package")
        for child in (create, inspect, human, assess, enrich, portable, validate, finalise, populate, review, field, fields, update, compare, verify):
            child.add_argument("--debug", action="store_true", default=argparse.SUPPRESS,
                               help="Write safe failure diagnostics to stderr.")
        args = parser.parse_args(namespace=args)
        command = args.command
        package = PackageContext()
        integrity = package.verify()
        if command == "prepare-review" and args.view == "summary":
            from .review_summary import review_summary
            summary_view = review_summary
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
        code = 2 if result["errors"] else 3 if command in ("validate", "prepare-review") and args.stage == "handoff" and result["gaps"] else 0
    except IntakeError as error:
        failure = error
        result, code = {"command": command, "status": "error", "errors": [error.issue], "gaps": [], "warnings": []}, 2
    except Exception as error:
        failure = error
        result, code = {"command": command, "status": "error", "errors": [command_failure(error).issue], "gaps": [], "warnings": []}, 2
    if args.debug and result["errors"]:
        write_debug(command, result["errors"], failure, package.root if package is not None else None)
    if summary_view is not None:
        result = summary_view(result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return code
