"""Responsibility: inventory explicitly supplied human forms for evidence-led intake.
Must not: validate working schemas, migrate facts or decide readiness. Local contract: intake-contract.md.
Contract: RESP-INTAKE-HUMAN-INPUT — docs/architecture/intake-runtime.md#resp-intake-human-input.
"""
from __future__ import annotations

from pathlib import Path

from .errors import IntakeError
from .package_context import sha256
from .schema_validation import SchemaValidation


def review_input(package, codec, sources: list[str]) -> dict:
    limits = package.manifest["limits"]
    paths: dict[Path, None] = {}
    for value in sources:
        source = package.workspace(value)
        if source.is_dir():
            try:
                entries = []
                for count, path in enumerate(source.iterdir(), 1):
                    if count > limits["bundleFiles"]:
                        raise IntakeError("INPUT_LIMIT", "The selected directory exceeds the declared file-count limit.")
                    if path.suffix.lower() in (".yaml", ".yml"):
                        entries.append(path)
                paths.update(dict.fromkeys(sorted(entries)))
            except OSError:
                raise IntakeError("INPUT_READ", "The selected source directory cannot be read.", str(source)) from None
        else:
            paths[source] = None
        if len(paths) > limits["bundleFiles"]:
            raise IntakeError("INPUT_LIMIT", "The selected sources exceed the declared file-count limit.")
    if not paths:
        raise IntakeError("INPUT_EMPTY", "No YAML forms were selected; supply a file or a directory containing YAML forms.")

    schemas = SchemaValidation(package)
    roles = {schemas.expanded(entry["schema"])["properties"]["templateType"]["const"]: role
             for role, entry in package.manifest["templates"].items()}
    documents, errors, gaps = [], [], []
    total = 0
    for path in paths:
        try:
            data = package.read(path)
            total += len(data)
            if total > limits["bundleBytes"]:
                raise IntakeError("INPUT_LIMIT", "The selected sources exceed the declared total-byte limit.")
            value = codec.parse(data, str(path))
            if value is not None and not isinstance(value, dict):
                raise IntakeError("DOCUMENT_OBJECT", "A human intake form must be a YAML object or blank.", str(path))
            declared_type = value.get("templateType") if value is not None else None
            role = roles.get(declared_type) if isinstance(declared_type, str) else None
            documents.append({"artifactRef": str(path), "sha256": sha256(data), "role": role})
            if value is None or not value:
                gaps.append(IntakeError("INPUT_BLANK", "Blank form accepted; supplied requirements remain to be recorded.", str(path)).issue)
            elif role is None:
                gaps.append(IntakeError("INPUT_ROLE_REVIEW", "Form accepted; identify its intended role during review.", str(path), "/templateType").issue)
        except IntakeError as error:
            errors.append(error.issue)
            if error.issue["code"] == "INPUT_LIMIT":
                break

    for role in package.manifest["templates"]:
        matching = [row["artifactRef"] for row in documents if row["role"] == role]
        if len(matching) > 1:
            gaps.append(IntakeError("INPUT_SELECTION_REQUIRED", "Multiple source forms have this role; preserve each and select the applicable statements explicitly.",
                                    role, detail={"artifactRefs": matching}).issue)
    if documents:
        gaps.append(IntakeError("ENRICHMENT_REVIEW_REQUIRED", "Human forms accepted as source material. Review supplied facts, examples and missing decisions before enriching; handoff has not been assessed.").issue)
    return {"assessment": "human-input", "handoffAssessment": "not-run", "documents": documents,
            "errors": errors, "gaps": gaps, "warnings": []}
