"""Responsibility: project recorded ledger context for one explicitly requested field.
Must not: read sources, infer evidence support or alter ledger records. Contract: intake-contract.md.
"""
from __future__ import annotations

from .pointers import covers


def field_context(instance: dict, role: str, pointer: str) -> tuple[dict, list[str]]:
    context = {name: [] for name in ("provenance", "questions", "proposals")}
    record_pointers = []
    for name in context:
        for index, record in enumerate(instance[name]):
            record_pointer = f"/instance/{name}/{index}"
            targets = [record["target"]] if name == "provenance" else record["targets"]
            linked = any(target["document"] == role and isinstance(target["pointer"], str) and (
                covers(pointer, target["pointer"]) or covers(target["pointer"], pointer)) for target in targets)
            requested_record = name in ("questions", "proposals") and role == "traceability" and (
                covers(pointer, record_pointer) or covers(record_pointer, pointer))
            if linked or requested_record:
                context[name].append(record)
                record_pointers.append(record_pointer)
    return context, record_pointers
