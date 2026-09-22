"""Responsibility: separate recorded decisions by stage and expose unlinked diagnostic groups.
Must not: infer question owners, invent questions or calculate readiness.
Contract: intake-contract.md, Decision review and reproducible stakeholder output.
"""
from __future__ import annotations


def decision_review(questions: list, groups: list) -> dict:
    current, execution = [], []
    for row in questions:
        if row["status"] == "answered":
            continue
        target = execution if row["blockingStage"] == "execution" else current
        target.append({"questionId": row["id"], "owner": row["owner"], "blockingStage": row["blockingStage"]})
    return {"current": current, "execution": execution,
            "unassignedQuestionIds": [row["id"] for row in questions if row["status"] != "answered" and row["owner"] is None],
            "engineeringTriageGroupIndexes": [index for index, group in enumerate(groups)
                                             if group["kind"] != "questions" and any(group[key] for key in ("errors", "gaps", "warnings"))],
            "currentDecisionCount": len(current), "executionDecisionCount": len(execution)}
