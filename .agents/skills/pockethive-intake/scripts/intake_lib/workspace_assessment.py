"""Responsibility: derive start/resume guidance from explicit workspace contents.
Must not: mutate files, infer client intent or implement working schemas. Local contract: intake-contract.md.
Contract: RESP-INTAKE-WORKSPACE — docs/architecture/intake-runtime.md#resp-intake-workspace.
"""
from .errors import IntakeError
from .schema_validation import SchemaValidation


def assess_workspace(package, codec, root):
    declared = {role: package.document_path(root, role) for role in package.manifest["templates"]}
    existing = sorted(path.name for path in declared.values() if path.is_file())
    missing = sorted(path.name for path in declared.values() if not path.is_file())
    errors, valid, raw, marked = [], [], [], False
    entries = []
    if root.is_dir():
        for path in root.iterdir():
            if path == package.lock_path(root):
                continue
            entries.append(path)
            if len(entries) > package.manifest["limits"]["bundleFiles"]:
                raise IntakeError("INPUT_LIMIT", "The workspace exceeds the declared file-count limit.")
        schemas = SchemaValidation(package)
        total = 0
        for path in sorted(entries):
            if path.suffix.lower() not in (".yaml", ".yml"):
                continue
            try:
                data = package.read(path)
                total += len(data)
                if total > package.manifest["limits"]["bundleBytes"]:
                    raise IntakeError("INPUT_LIMIT", "The workspace exceeds the declared total-byte limit.")
                value = codec.parse(data, path.name)
                role = next((key for key, target in declared.items() if target == path), None)
                if role == "traceability" and isinstance(value, dict):
                    instance = value.get("instance")
                    intake = instance.get("intake") if isinstance(instance, dict) else None
                    marked = (isinstance(intake, dict) and intake.get("mode") in ("new-requirements", "from-bundle")
                              and isinstance(intake.get("packageVersion"), str) and bool(intake["packageVersion"]))
                if role and isinstance(value, dict) and not schemas.validate(role, codec.plain(value)):
                    valid.append(role)
                else:
                    raw.append(path.name)
            except IntakeError as error:
                errors.append(error.issue)
                if error.issue["code"] == "INPUT_LIMIT":
                    break
    if root.exists() and not root.is_dir():
        state = "not-directory"
    elif not entries:
        state = "empty"
    elif len(valid) == len(declared) and marked and not raw and not errors:
        state = "complete"
    elif len(valid) == len(declared) and not marked and not errors:
        state = "raw"
    elif (valid or marked) and raw:
        state = "mixed"
    elif valid or marked:
        state = "partial"
    elif raw or errors:
        state = "raw"
    else:
        state = "unrelated"
    actions = {
        "empty": "Initialise with the explicitly selected source mode, or enrich supplied human forms.",
        "complete": "Resume with prepare-review --documents; preserve the existing identities and answers.",
        "partial": "Preserve files and recover the missing or malformed working documents from the same revision; never initialise over them.",
        "mixed": "Review raw forms with review-input and retain the existing working set; resolve the intended source scope before enrichment or resume.",
        "raw": "Accept these human forms with review-input, then enrich explicitly; generated metadata is not required from the client.",
        "unrelated": "Select an empty output directory or explicitly select the human source files; preserve existing files.",
        "not-directory": "Select a directory; the selected path is a file.",
    }
    if state == "mixed" and len(valid) == len(declared) and marked:
        actions[state] = ("Resume the structurally complete working set with prepare-review. "
                          "Review additional raw forms only for explicitly supplied changes; retained originals are not new instructions to re-import.")
    return {"documentsRoot": str(root), "directoryState": state, "existingDocuments": existing,
            "missingDocuments": missing, "workingDocumentsComplete": len(valid) == len(declared) and marked and not errors,
            "nextAction": actions[state], "errors": errors}
