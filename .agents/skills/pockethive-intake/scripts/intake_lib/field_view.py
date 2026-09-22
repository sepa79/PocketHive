"""Responsibility: project explicitly requested fields with canonical authoring guidance.
Must not: write values, infer evidence support or revalidate schema branches. Contract: intake-contract.md, field-help.md.
"""
from __future__ import annotations

from .authoring_policy import AuthoringPolicy
from .errors import IntakeError
from .field_context import field_context
from .pointers import covers, resolve
from .schema_validation import SchemaValidation


class FieldView:
    def __init__(self, package, codec, store, docs, validation_result, revision) -> None:
        self.documents = codec.plain(docs)
        self.roles = package.manifest["templates"]
        self.validation = validation_result
        self.policy = AuthoringPolicy(package, codec, store, docs)
        self.schemas = SchemaValidation(package)
        self.summary = {
            "validationSummary": {"errorCount": len(validation_result["errors"]),
                                  "gapCount": len(validation_result["gaps"]),
                                  "warningCount": len(validation_result["warnings"])},
            "documentsSha256": revision,
            "reviewContentSha256": validation_result["reviewContentSha256"],
        }

    def view(self, role: str, pointer: str) -> dict:
        if role not in self.roles:
            raise IntakeError("DOCUMENT_ROLE", "Choose a document role declared by the package.", role)
        document = self.documents[role]
        try:
            value = resolve(document, pointer, role)
        except IntakeError as error:
            detail = error.issue.get("detail", {})
            if error.issue["code"] == "POINTER" and "resolvedAncestor" in detail:
                detail.update(self.schemas.field_children(role, detail["resolvedAncestor"], document))
            raise
        context, record_pointers = field_context(self.documents["traceability"]["instance"], role, pointer)
        diagnostic_targets = [(role, pointer)] + [("traceability", target) for target in record_pointers]
        diagnostics = {category: [issue for issue in self.validation[category]
                                  if isinstance(issue, str) or any(issue["document"] == document_role and (
                                      covers(target, issue["pointer"]) or covers(issue["pointer"], target))
                                      for document_role, target in diagnostic_targets)]
                       for category in ("errors", "gaps", "warnings")}
        return {
            **diagnostics,
            **self.summary,
            "field": {
                "document": role,
                "pointer": pointer,
                "value": value,
                "context": context,
                "ownership": self.policy.describe(role, pointer),
                **self.schemas.field_schema(role, pointer, document),
            },
        }


def field_view(package, codec, store, root, docs, validation_result, role, pointer) -> dict:
    return FieldView(package, codec, store, docs, validation_result, store.revision(root)).view(role, pointer)
