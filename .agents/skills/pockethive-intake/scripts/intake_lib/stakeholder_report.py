"""Responsibility: persist a revision-bound stakeholder report with verified edit protection.
Must not: author facts, infer acceptance, execute sources or decide readiness.
Contract: intake-contract.md, Decision review and reproducible stakeholder output.
"""
from __future__ import annotations

import re

from .errors import IntakeError
from .package_context import sha256
from .report_format import render_report


_MARKER = re.compile(rb"<!-- PocketHive intake generated review; documentsSha256=([0-9a-f]{64}); contentSha256=([0-9a-f]{64}) -->\n")


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
