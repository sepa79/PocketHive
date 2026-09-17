"""Responsibility: explain editable nulls using recorded context and canonical population preview.
Must not: infer source absence, applicability, adoption or new readiness gates.
Contract: intake-contract.md, Decision review and reproducible stakeholder output.
"""
from __future__ import annotations

from copy import deepcopy

from .authoring_policy import AuthoringPolicy
from .errors import IntakeError
from .field_context import field_context
from .pointers import leaves
from .population_fields import TemplatePopulation


def blank_review(package, codec, store, docs, inspection, validation):
    preview, issues = {}, []
    if inspection is not None:
        candidate = deepcopy(docs)
        try:
            result = TemplatePopulation(package, codec).apply(candidate, inspection)
            issues.extend(result["gaps"])
            filled = set(result["population"]["filledFields"])
            preview = {record["target"]["pointer"]: record["sources"]
                       for record in candidate["traceability"]["instance"]["provenance"]
                       if record["target"]["document"] == "requirements"
                       and record["target"]["pointer"] in filled}
        except IntakeError as error:
            issues.append(error.issue)
    policy = AuthoringPolicy(package, codec, store, docs)
    instance = docs["traceability"]["instance"]
    rows = []
    for role in ("requirements", "plan", "results"):
        for pointer, value in leaves(docs[role]):
            if value is not None or not policy.describe(role, pointer)["editable"]:
                continue
            context, records = field_context(instance, role, pointer)
            questions = [row for row in context["questions"] if row["status"] != "answered"]
            sources = preview.get(pointer, []) if role == "requirements" else []
            state = "source-review-needed" if instance["intake"]["mode"] == "from-bundle" else "unexplained"
            if context["provenance"] or context["proposals"] or context["questions"]:
                state = "explanation-recorded"
            if questions:
                state = "question-recorded"
            if sources:
                state = "population-available"
            diagnostics = {category: [index for index, issue in enumerate(validation[category])
                                      if isinstance(issue, dict) and issue["document"] == role and issue["pointer"] == pointer]
                           for category in ("errors", "gaps", "warnings")}
            details = {"contextPointers": records, "questionIds": [row["id"] for row in questions],
                       "provenanceKinds": sorted({row["kind"] for row in context["provenance"]}),
                       "populationSources": sources,
                       "diagnostics": {category: indexes for category, indexes in diagnostics.items() if indexes}}
            rows.append({"target": {"document": role, "pointer": pointer}, "state": state,
                         **{key: value for key, value in details.items() if value}})
    return {"fields": rows, "populationPreviewIssues": issues,
            "populationPreviewAvailable": inspection is not None,
            "counts": {state: sum(row["state"] == state for row in rows)
                       for state in sorted({row["state"] for row in rows})}}
