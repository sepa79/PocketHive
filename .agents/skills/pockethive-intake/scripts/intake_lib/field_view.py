"""Responsibility: project an explicitly requested field with canonical authoring guidance.
Must not: write values, infer evidence support or revalidate schema branches. Contract: intake-contract.md.
"""
from __future__ import annotations

from .authoring_policy import AuthoringPolicy
from .errors import IntakeError
from .pointers import covers, resolve
from .schema_validation import SchemaValidation


def field_view(package, codec, store, root, docs, validation_result, role, pointer) -> dict:
    if role not in package.manifest["templates"]:
        raise IntakeError("DOCUMENT_ROLE", "Choose a document role declared by the package.", role)
    document = codec.plain(docs[role])
    value = resolve(document, pointer, role)
    diagnostics = {category: [issue for issue in validation_result[category]
                              if isinstance(issue, str) or issue["document"] == role and (
                                  covers(pointer, issue["pointer"]) or covers(issue["pointer"], pointer))]
                   for category in ("errors", "gaps", "warnings")}
    return {
        **diagnostics,
        "validationSummary": {"errorCount": len(validation_result["errors"]),
                              "gapCount": len(validation_result["gaps"]),
                              "warningCount": len(validation_result["warnings"])},
        "documentsSha256": store.revision(root),
        "reviewContentSha256": validation_result["reviewContentSha256"],
        "field": {
            "document": role,
            "pointer": pointer,
            "value": value,
            "ownership": AuthoringPolicy(package, codec, store, docs).describe(role, pointer),
            **SchemaValidation(package).field_schema(role, pointer, document),
        },
    }
