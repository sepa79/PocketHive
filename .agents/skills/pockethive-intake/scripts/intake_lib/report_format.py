"""Responsibility: render the stakeholder report from canonical intake facts and validation.
Must not: infer missing facts, classify provenance or decide field applicability.
Local contract: intake-contract.md, stakeholder report presentation.
Contract: RESP-INTAKE-REPORT — docs/architecture/intake-runtime.md#resp-intake-report.
"""
from __future__ import annotations

import html
import re

from .field_context import field_context
from .package_context import sha256
from .pointers import escape, resolve


PROVENANCE_LABELS = {
    "client-statement": "Supplied by you",
    "bundle-observation": "Observed in the bundle",
    "engineer-proposal": "Proposed approach",
}

WORKLOAD_LABELS = {
    "/executionModel/relationship": {
        "sequential": "Calls run in sequence (sequential)",
        "independent": "Calls run independently (independent)",
    },
    "/executionModel/arrivalModel": {
        "open-loop": "Arrivals follow a schedule (open-loop)",
        "closed-loop": "Concurrent users repeat the work (closed-loop)",
    },
    "/executionModel/rateUnit": {
        "journeys-per-second": "Journeys per second (journeys-per-second)",
        "requests-per-second": "Requests per second (requests-per-second)",
    },
}


def text(value):
    return re.sub(r"([\\`*_{}\[\]()#+.!|>-])", r"\\\1", html.escape(" ".join(str(value).split())))


def populated(value):
    if isinstance(value, dict):
        return any(populated(child) for child in value.values())
    if isinstance(value, list):
        return any(populated(child) for child in value)
    return value is not None and value != ""


def value_lines(value, depth=1, *, pointer="", attribution=None):
    indent = "  " * depth
    if isinstance(value, dict):
        for key, child in value.items():
            child_pointer = pointer + "/" + escape(str(key))
            label = re.sub(r"([a-z])([A-Z])", r"\1 \2", str(key)).capitalize()
            if isinstance(child, (dict, list)) and populated(child):
                yield f"{indent}- **{text(label)}:**"
                yield from value_lines(child, depth + 1, pointer=child_pointer, attribution=attribution)
            else:
                shown = "Not yet specified" if not populated(child) else text(child)
                source = f" ({attribution(child_pointer)})" if populated(child) and attribution else ""
                yield f"{indent}- **{text(label)}:** {shown}{source}"
    elif isinstance(value, list):
        for index, child in enumerate(value):
            child_pointer = pointer + "/" + str(index)
            if not populated(child):
                continue
            if isinstance(child, (dict, list)):
                yield f"{indent}- Item {index + 1}:"
                yield from value_lines(child, depth + 1, pointer=child_pointer, attribution=attribution)
            else:
                source = f" ({attribution(child_pointer)})" if attribution else ""
                yield f"{indent}- {text(child)}{source}"


def render_report(docs, brief, validation):
    revision = brief["documentsSha256"]
    lines = ["# Performance test intake review", "",
             "A review of the recorded test design. This report does not authorise a test run.", ""]
    if validation["errors"]:
        lines.extend(["**Document checks found errors. Treat this review as provisional; see the engineering appendix.**", ""])

    def attribution(role, pointer):
        context, _ = field_context(docs["traceability"]["instance"], role, pointer)
        kinds = sorted({row["kind"] for row in context["provenance"]})
        return ", ".join(text(PROVENANCE_LABELS.get(kind, kind)) for kind in kinds) if kinds else "Source not classified"

    def fact(label, role, pointer):
        value = resolve(docs[role], pointer, role)
        if not populated(value):
            lines.append(f"- **{label}:** Not yet specified.")
        elif isinstance(value, (dict, list)):
            lines.append(f"- **{label}:**")
            lines.extend(value_lines(value, pointer=pointer, attribution=lambda target: attribution(role, target)))
        else:
            if (role, pointer, value) == ("results", "/runInfo/executionStatus", "not-run"):
                lines.append(f"- **{label}:** Test not run.")
                return
            display = text(WORKLOAD_LABELS.get(pointer, {}).get(value, value)) if role == "plan" else text(value)
            lines.append(f"- **{label}:** {display} ({attribution(role, pointer)}).")

    lines.extend(["## At a glance", ""])
    for label, role, pointer in (
        ("Purpose", "requirements", "/project/objective"),
        ("Target environment", "plan", "/sutId"),
        ("Outside scope", "requirements", "/project/outOfScope"),
        ("Execution", "results", "/runInfo/executionStatus"),
    ):
        fact(label, role, pointer)
    lines.extend(["", "## Workload", ""])
    for label, pointer in (
        ("How calls relate", "/executionModel/relationship"),
        ("Load model", "/executionModel/arrivalModel"),
        ("Rate unit", "/executionModel/rateUnit"),
    ):
        fact(label, "plan", pointer)
    model = docs["plan"]["executionModel"]
    if populated(model["journeyTimeline"]):
        fact("Journey schedule", "plan", "/executionModel/journeyTimeline")
    if model["arrivalModel"] == "closed-loop":
        fact("Concurrent-user workload", "plan", "/executionModel/closedLoop")
    for index, row in enumerate(docs["plan"]["apiExecution"]):
        if row["mode"] == "load" and populated(row["timeline"]):
            fact(f"API schedule {text(row['apiRef'])}", "plan", f"/apiExecution/{index}/timeline")
    if not populated(model["journeyTimeline"]) and model["arrivalModel"] != "closed-loop" and not any(
            row["mode"] == "load" and populated(row["timeline"]) for row in docs["plan"]["apiExecution"]):
        lines.append("- **Schedule and duration:** Not yet specified.")
    lines.extend(["", "## How success will be judged", ""])
    fact("Success criteria", "requirements", "/successCriteria")
    for index, row in enumerate(docs["plan"]["acceptanceCriteria"]):
        if populated(row["criterionRef"]) or populated(row["measurableRules"]):
            fact("Acceptance rule", "plan", f"/acceptanceCriteria/{index}")
    lines.extend(["", "## Decisions to discuss", "",
                  "Open choices recorded for this draft:", ""])
    questions = {row["id"]: row for row in brief["unansweredQuestions"]}
    for item in brief["decisions"]["current"]:
        lines.append(f"- {text(questions[item['questionId']]['question'])}")
    if not brief["decisions"]["current"]:
        lines.append("No current questions are recorded. This does not establish completeness; check the engineering findings below.")
    lines.extend(["", "## What remains unproven", ""])
    fact("Recorded limitations", "plan", "/environmentQualification/limitations")
    lines.extend(["", "## Engineering appendix", "",
                  "Generated from the owning YAML fields. Edit those fields and regenerate; this report is read-only.", "",
                  f"Validation: {len(validation['errors'])} errors, {len(validation['gaps'])} missing-input/evidence findings, {len(validation['warnings'])} warnings.", "",
                  "Diagnostic totals are not a count of questions for the client. Optional and scaffold blanks are not automatically blockers.", ""])
    fact("Pre-run checks", "plan", "/readiness/prechecks")
    lines.extend(["", "### Question ownership and later decisions", ""])
    for title, key in (("Current review or handoff", "current"), ("Later execution", "execution")):
        lines.extend([f"#### {title}", ""])
        for item in brief["decisions"][key]:
            row = questions[item["questionId"]]
            owner = "Unassigned" if row["owner"] is None else text(row["owner"])
            lines.append(f"- **{text(row['id'])}:** {text(row['question'])} Owner: {owner}. Stage: {text(row['blockingStage'])}.")
        if not brief["decisions"][key]:
            lines.append("No unresolved questions recorded for this stage.")
        lines.append("")
    count = len(brief["decisions"]["engineeringTriageGroupIndexes"])
    lines.extend([f"Engineering triage: {count} diagnostic groups are not linked to recorded questions.", "",
                  "Use the full CLI review for all findings and blank-field targets; summary counts do not waive unresolved findings.", "",
                  f"Document revision: `{revision}`", "",
                  f"Material review digest: `{brief['reviewContentSha256']}`", ""])
    body = ("\n".join(lines) + "\n").encode("utf-8")
    marker = f"<!-- PocketHive intake generated review; documentsSha256={revision}; contentSha256={sha256(body)} -->\n"
    return marker.encode() + body
