"""Responsibility: project the canonical review result into a smaller conversational view.
Must not: validate, choose questions, infer readiness or write document state.
Local contract: intake-contract.md, Decision review and reproducible stakeholder output.
Contract: RESP-INTAKE-REVIEW-VIEWS — docs/architecture/intake-runtime.md#resp-intake-review-views.
"""
from __future__ import annotations


def review_summary(result: dict) -> dict:
    output = {key: value for key, value in result.items() if key not in ("gaps", "warnings", "brief")}
    output["view"] = "summary"
    brief = result.get("brief")
    output["diagnostics"] = {
        "counts": {key: len(result[key]) for key in ("errors", "gaps", "warnings")},
        "groups": [],
        "detailView": "full",
    }
    if brief is None:
        return output
    output["diagnostics"]["groups"] = [
        {key: value for key, value in group.items() if key not in ("errors", "gaps", "warnings")}
        for group in brief["diagnosticGroups"]
    ]
    output["brief"] = {key: value for key, value in brief.items()
                       if key not in ("evidenceFields", "diagnosticGroups", "blankFields", "decisions")}
    output["brief"]["blankFields"] = {key: value for key, value in brief["blankFields"].items() if key != "fields"}
    output["brief"]["decisions"] = {key: value for key, value in brief["decisions"].items()
                                   if key != "engineeringTriageGroupIndexes"}
    output["brief"]["decisions"]["engineeringTriageGroupCount"] = len(brief["decisions"]["engineeringTriageGroupIndexes"])
    return output
