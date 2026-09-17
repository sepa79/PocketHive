"""Responsibility: render and persist a revision-bound stakeholder projection of intake documents.
Must not: author facts, infer acceptance, execute sources or decide readiness.
Contract: intake-contract.md, Decision review and reproducible stakeholder output.
"""
from __future__ import annotations

import html
import re

from .errors import IntakeError
from .field_context import field_context
from .package_context import sha256
from .pointers import resolve


_MARKER = re.compile(rb"<!-- PocketHive intake generated review; documentsSha256=([0-9a-f]{64}); contentSha256=([0-9a-f]{64}) -->\n")


def _text(value):
    text = str(value)
    return re.sub(r"([\\`*_{}\[\]()#+.!|>-])", r"\\\1", html.escape(" ".join(text.split())))


def _value_lines(value, depth=1):
    indent = "  " * depth
    if isinstance(value, dict):
        for key, child in value.items():
            label = re.sub(r"([a-z])([A-Z])", r"\1 \2", str(key)).capitalize()
            if isinstance(child, (dict, list)) and child:
                yield f"{indent}- **{_text(label)}:**"
                yield from _value_lines(child, depth + 1)
            else:
                shown = "Not supplied" if child is None else "None recorded" if child == [] or child == {} else _text(child)
                yield f"{indent}- **{_text(label)}:** {shown}"
    elif isinstance(value, list):
        for index, child in enumerate(value):
            if isinstance(child, (dict, list)):
                yield f"{indent}- Item {index + 1}:"
                yield from _value_lines(child, depth + 1)
            else:
                yield f"{indent}- {_text(child)}"


def render_report(docs, brief, validation):
    revision = brief["documentsSha256"]
    lines = ["# Performance test intake review", "",
             "Generated view: edit the owning YAML fields and regenerate this report.", "",
             "## Review status", "",
             f"Validation reports {len(validation['errors'])} errors and {len(validation['gaps'])} findings of missing input or evidence.", "",
             "These are diagnostic findings, not a count of questions for the client. This report grants no approval or permission to run.", ""]

    def fact(label, role, pointer):
        value = resolve(docs[role], pointer, role)
        context, _ = field_context(docs["traceability"]["instance"], role, pointer)
        kinds = sorted({row["kind"] for row in context["provenance"]})
        classification = ", ".join(kinds) if kinds else "unclassified"
        if isinstance(value, (dict, list)) and value:
            lines.append(f"- **{label}** ({_text(classification)}):")
            lines.extend(_value_lines(value))
        else:
            display = "Not supplied" if value is None else "None recorded" if value == [] else _text(value)
            lines.append(f"- **{label}:** {display} ({_text(classification)}).")

    lines.extend(["## Stated objective and proposed test", ""])
    for label, role, pointer in (
        ("Objective", "requirements", "/project/objective"),
        ("Selected SUT", "plan", "/sutId"),
        ("Workload relationship", "plan", "/executionModel/relationship"),
        ("Arrival model", "plan", "/executionModel/arrivalModel"),
        ("Rate unit", "plan", "/executionModel/rateUnit"),
        ("Journey schedule", "plan", "/executionModel/journeyTimeline"),
    ):
        fact(label, role, pointer)
    if docs["plan"]["executionModel"]["arrivalModel"] == "closed-loop":
        fact("Concurrent-user workload", "plan", "/executionModel/closedLoop")
    for index, row in enumerate(docs["plan"]["apiExecution"]):
        if row["mode"] == "load" and row["timeline"]:
            fact(f"API schedule {_text(row['apiRef'])}", "plan", f"/apiExecution/{index}/timeline")
    lines.extend(["", "## Acceptance and measurement", ""])
    fact("Stated success criteria", "requirements", "/successCriteria")
    for index, row in enumerate(docs["plan"]["acceptanceCriteria"]):
        if row["criterionRef"] is not None:
            fact(f"Rules for {_text(row['criterionRef'])}", "plan", f"/acceptanceCriteria/{index}/measurableRules")
    lines.extend(["", "## Evidence limits and preparation", ""])
    fact("Recorded limitations", "plan", "/environmentQualification/limitations")
    fact("Proposed pre-run checks", "plan", "/readiness/prechecks")
    fact("Recorded execution status", "results", "/runInfo/executionStatus")
    lines.extend(["", "## Decisions needed", ""])
    questions = {row["id"]: row for row in brief["unansweredQuestions"]}
    for title, key in (("Current review or handoff", "current"), ("Later execution", "execution")):
        lines.extend([f"### {title}", ""])
        for item in brief["decisions"][key]:
            row = questions[item["questionId"]]
            owner = "Unassigned" if row["owner"] is None else _text(row["owner"])
            lines.append(f"- **{_text(row['id'])}:** {_text(row['question'])} Owner: {owner}. Stage: {_text(row['blockingStage'])}.")
        if not brief["decisions"][key]:
            lines.append("No unresolved questions recorded for this stage; this does not establish completeness.")
        lines.append("")
    count = len(brief["decisions"]["engineeringTriageGroupIndexes"])
    lines.extend([f"Engineering review: {count} diagnostic groups are not linked to recorded questions.", "",
                  "The detailed blank-field inventory is available in the CLI review. Optional and scaffold blanks are not automatically blockers.", ""])
    lines.extend(["", "## Document identity", "", f"Document revision: `{revision}`", "",
                  f"Material review digest: `{brief['reviewContentSha256']}`", ""])
    body = ("\n".join(lines) + "\n").encode("utf-8")
    marker = f"<!-- PocketHive intake generated review; documentsSha256={revision}; contentSha256={sha256(body)} -->\n"
    return marker.encode() + body


def write_report(package, store, root, docs, brief, validation):
    path = package.review_path(root)
    data = render_report(docs, brief, validation)
    previous = "absent"
    if path.exists():
        old = package.read(path)
        marker = _MARKER.match(old)
        if marker is None:
            raise IntakeError("REPORT_NOT_GENERATED", "Existing report has no generated revision marker; preserve it and explicitly relocate it before writing this projection.", path.name)
        if sha256(old[marker.end():]) != marker[2].decode():
            raise IntakeError("REPORT_EDIT", "The report differs from its canonical rendering at this revision; preserve authored content in its YAML owner and explicitly relocate the edited report.", path.name)
        previous = "current" if old == data else "stale"
    store.assert_revision(root, brief["documentsSha256"])
    store.write_bytes(path, data)
    store.assert_revision(root, brief["documentsSha256"])
    return {"path": path.name, "previousState": previous, "documentsSha256": brief["documentsSha256"], "persistence": "verified"}
