"""Responsibility: derive a compact review/resume view from saved intake evidence.
Must not: author questions, assess source fidelity or decide readiness. Contract: intake-contract.md.
"""
from __future__ import annotations

from .pointers import covers, escape


def _changes(previous: object, current: object, pointer: str = "") -> list[dict]:
    if type(previous) is not type(current):
        return [{"pointer": pointer, "change": "changed"}]
    if isinstance(current, dict):
        changes = []
        for key in sorted(previous.keys() | current.keys()):
            target = f"{pointer}/{escape(key)}"
            if key not in previous:
                changes.append({"pointer": target, "change": "added"})
            elif key not in current:
                changes.append({"pointer": target, "change": "removed"})
            else:
                changes.extend(_changes(previous[key], current[key], target))
        return changes
    if isinstance(current, list):
        changes = []
        for index in range(max(len(previous), len(current))):
            target = f"{pointer}/{index}"
            if index >= len(previous):
                changes.append({"pointer": target, "change": "added"})
            elif index >= len(current):
                changes.append({"pointer": target, "change": "removed"})
            else:
                changes.extend(_changes(previous[index], current[index], target))
        return changes
    return [] if previous == current else [{"pointer": pointer, "change": "changed"}]


def _diagnostic_groups(result: dict, questions: list[dict]) -> list[dict]:
    groups = {}
    for category in ("errors", "gaps", "warnings"):
        for issue_index, issue in enumerate(result[category]):
            if isinstance(issue, str):
                key = ("validation",)
                if key not in groups:
                    groups[key] = {"kind": "validation", "errors": [], "gaps": [], "warnings": []}
                groups[key][category].append(issue_index)
                continue
            document, pointer = issue["document"], issue["pointer"]
            question_ids = []
            for index, question in enumerate(questions):
                owns_issue = document == "traceability" and covers(f"/instance/questions/{index}", pointer)
                targets_issue = any(target["document"] == document and isinstance(target["pointer"], str) and (
                    covers(target["pointer"], pointer) or covers(pointer, target["pointer"]))
                    for target in question["targets"])
                if owns_issue or targets_issue:
                    question_ids.append(question["id"])
            if question_ids:
                key = ("questions", *question_ids)
                descriptor = {"kind": "questions", "questionIds": question_ids}
            else:
                section = "/".join(pointer.split("/")[:3 if document == "traceability" else 2])
                key = ("section", document, section)
                descriptor = {"kind": "section", "document": document, "pointer": section}
            if key not in groups:
                groups[key] = {**descriptor, "errors": [], "gaps": [], "warnings": []}
            groups[key][category].append(issue_index)
    return list(groups.values())


def build_brief(package, codec, store, root, docs, validation_result, stage, previous_root=None) -> dict:
    plain = codec.plain(docs)
    instance = plain["traceability"]["instance"]
    questions = instance["questions"]
    brief = {
        "documentsSha256": store.revision(root),
        "reviewContentSha256": validation_result["reviewContentSha256"],
        "stage": stage,
        "sourceMode": instance["intake"]["mode"],
        "source": instance["intake"]["source"],
        "currentReview": instance["review"],
        "evidenceFields": [{"target": row["target"], "kind": row["kind"],
                            **{key: row[key] for key in ("sources", "confirmationRef") if key in row}}
                           for row in instance["provenance"]],
        "unansweredQuestions": [row for row in questions if row["status"] != "answered"],
        "answeredQuestionCount": sum(row["status"] == "answered" for row in questions),
        "proposals": instance["proposals"],
        "diagnosticGroups": _diagnostic_groups(validation_result, questions),
    }
    if previous_root is not None:
        store.assert_unlocked(previous_root)
        revision = store.revision(previous_root)
        previous = codec.plain(store.load(previous_root))
        store.assert_revision(previous_root, revision)
        store.assert_unlocked(previous_root)
        brief["comparison"] = {
            "previousDocumentsSha256": revision,
            "changedFields": [{"document": role, **change}
                              for role in package.manifest["templates"]
                              for change in _changes(previous[role], plain[role])],
        }
    return brief
