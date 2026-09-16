"""Responsibility: index explicit document identities and report duplicate ownership.
Must not: infer missing identities, repair rows or decide reference applicability.
Contract: intake-contract.md; shared single-field and compound identity semantics.
"""
from __future__ import annotations

from .errors import IntakeError
from .pointers import escape


def index_identities(rows: list, keys: str | tuple[str, ...], document: str, pointer: str) -> tuple[dict, list]:
    names = (keys,) if isinstance(keys, str) else keys
    indexed, issues = {}, []
    for position, row in enumerate(rows):
        values = tuple(row.get(key) for key in names)
        if any(value is None for value in values):
            continue
        identity = values[0] if isinstance(keys, str) else values
        path = f"{pointer}/{position}"
        if identity in indexed:
            issues.append(IntakeError("DUPLICATE_ID", "Populated identities must be unique within their declared scope.",
                                      document, f"{path}/{escape(names[-1])}").issue)
            indexed[identity] = None
        else:
            indexed[identity] = (row, path)
    return indexed, issues
