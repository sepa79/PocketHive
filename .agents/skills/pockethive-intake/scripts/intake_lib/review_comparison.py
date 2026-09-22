"""Responsibility: derive exact field changes and stable-identity ledger changes.
Must not: validate schemas, infer answers or adoption, echo old values or mutate rows.
Local contract: intake-contract.md, Review and field views; identity_index owns identity checks.
Contract: RESP-INTAKE-REVIEW-VIEWS — docs/architecture/intake-runtime.md#resp-intake-review-views.
"""
from __future__ import annotations

from .errors import IntakeError
from .identity_index import index_identities
from .pointers import escape, resolve


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


def _ledger_index(document: dict, collection: str, snapshot: str) -> dict:
    try:
        rows = resolve(document, f"/instance/{collection}", "traceability")
    except IntakeError as error:
        detail = {**error.issue.get("detail", {}), "snapshot": snapshot}
        raise IntakeError(**{**error.issue, "detail": detail}) from None
    indexed, issues = index_identities(rows, "id", "traceability",
                                       f"/instance/{collection}", require_present=True)
    if issues:
        raise IntakeError(**issues[0], detail={"snapshot": snapshot})
    return indexed


def _ledger_changes(previous: dict, current: dict, collection: str) -> list[dict]:
    before = _ledger_index(previous, collection, "previous")
    after = _ledger_index(current, collection, "current")
    changes = []
    for identity in sorted(before.keys() | after.keys()):
        old, new = before.get(identity), after.get(identity)
        if old is not None and new is not None and old[0] == new[0]:
            continue
        if old is None:
            change = "added"
        elif new is None:
            change = "removed"
        elif collection == "questions" and old[0]["status"] != "answered" and new[0]["status"] == "answered":
            change = "answered"
        elif collection == "questions" and old[0]["status"] == "answered" and new[0]["status"] != "answered":
            change = "reopened"
        else:
            change = "changed"
        changes.append({"id": identity, "change": change,
                        "previousPointer": old[1] if old is not None else None,
                        "pointer": new[1] if new is not None else None})
    return changes


def compare_documents(previous: dict, current: dict, roles) -> dict:
    ledger_changes = {collection: _ledger_changes(previous["traceability"], current["traceability"], collection)
                      for collection in ("questions", "proposals")}
    return {
        "changedFields": [{"document": role, **change}
                          for role in roles for change in _changes(previous[role], current[role])],
        "ledgerChanges": ledger_changes,
    }
