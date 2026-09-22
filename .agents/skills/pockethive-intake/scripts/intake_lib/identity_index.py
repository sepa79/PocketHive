"""Responsibility: index explicit document identities and report duplicate ownership.
Must not: infer missing identities, repair rows or decide reference applicability.
Local contract: intake-contract.md; shared single-field and compound identity semantics.
Contract: RESP-INTAKE-DOCUMENT-VALUES — docs/architecture/intake-runtime.md#resp-intake-document-values.
"""
from __future__ import annotations

from .errors import IntakeError
from .pointers import escape


def index_identities(rows: list, keys: str | tuple[str, ...], document: str, pointer: str,
                     *, require_present: bool = False) -> tuple[dict, list]:
    names = (keys,) if isinstance(keys, str) else keys
    indexed, issues = {}, []
    for position, row in enumerate(rows):
        values = tuple(row.get(key) for key in names)
        path = f"{pointer}/{position}"
        if require_present:
            missing = [key for key, value in zip(names, values)
                       if value is None or isinstance(value, str) and not value.strip()]
            if missing:
                issues.extend(IntakeError("IDENTITY_REQUIRED", "Comparison requires an explicit nonblank identity.",
                                          document, f"{path}/{escape(key)}").issue for key in missing)
                continue
        if any(value is None for value in values):
            continue
        identity = values[0] if isinstance(keys, str) else values
        if identity in indexed:
            issues.append(IntakeError("DUPLICATE_ID", "Populated identities must be unique within their declared scope.",
                                      document, f"{path}/{escape(names[-1])}").issue)
            indexed[identity] = None
        else:
            indexed[identity] = (row, path)
    return indexed, issues
