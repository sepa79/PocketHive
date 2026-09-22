"""Responsibility: decode explicit bulk field requests and compose the canonical views.
Must not: validate facts, enumerate private keys or maintain independent ownership rules.
Contract: field-help.md.
"""
from __future__ import annotations

from .errors import IntakeError
from .field_view import FieldView


DIAGNOSTIC_CATEGORIES = ("errors", "gaps", "warnings")


def field_views(package, codec, store, root, docs, validation_result, input_path, revision) -> dict:
    request = codec.plain(codec.parse(package.read(input_path), "fields"))
    targets = _targets(request, package.manifest["limits"]["fieldTargets"])
    store.assert_revision(root, revision)
    projection = FieldView(package, codec, store, docs, validation_result, revision)
    views = [projection.view(target["document"], target["pointer"]) for target in targets]
    diagnostics = {category: [issue for issue in validation_result[category]
                              if any(issue in view[category] for view in views)]
                   for category in DIAGNOSTIC_CATEGORIES}
    return {
        **projection.summary,
        **diagnostics,
        "fields": [{**view["field"], **{category: view[category] for category in DIAGNOSTIC_CATEGORIES}}
                   for view in views],
    }


def _targets(request: object, maximum: int) -> list[dict]:
    if not isinstance(request, dict) or set(request) != {"targets"}:
        raise IntakeError("FIELD_TARGETS", "Supply exactly the documented targets list.", "fields")
    targets = request["targets"]
    if not isinstance(targets, list) or not 1 <= len(targets) <= maximum:
        raise IntakeError("FIELD_TARGETS", f"Request between 1 and {maximum} explicit fields.", "fields", "/targets")
    seen = set()
    for index, target in enumerate(targets):
        pointer = f"/targets/{index}"
        if (not isinstance(target, dict) or set(target) != {"document", "pointer"} or
                not all(isinstance(target[key], str) for key in ("document", "pointer"))):
            raise IntakeError("FIELD_TARGETS", "Use exactly one document role and one pointer string per target.", "fields", pointer)
        identity = (target["document"], target["pointer"])
        if identity in seen:
            raise IntakeError("FIELD_TARGETS", "Request each document and pointer pair once.", "fields", pointer)
        seen.add(identity)
    return targets
