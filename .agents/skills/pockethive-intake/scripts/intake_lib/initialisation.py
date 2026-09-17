"""Responsibility: create a new partial intake set from the mandatory templates.
Must not: infer client facts, adopt bundle configuration or overwrite existing work. Contract: intake-contract.md.
"""
from __future__ import annotations

import json
from pathlib import Path
from uuid import uuid4

from .bundle_inspector import BundleInspector
from .document_store import DocumentStore
from .errors import IntakeError
from .package_context import PackageContext, sha256
from .projections import Projections
from .yaml_codec import YamlCodec


def initialise(package: PackageContext, codec: YamlCodec, root: Path, mode: str, source: Path | None) -> dict:
    if mode == "from-bundle" and source is not None:
        package.check_bundle_documents(source, root)
    _require_empty(package, root)
    inspection = None
    if mode == "from-bundle":
        if source is None:
            raise IntakeError("SOURCE_REQUIRED", "from-bundle requires an explicit source directory.")
        inspection = BundleInspector(package, codec).inspect(source)
        selected = {"artifactRef": str(source), "sha256": inspection["sha256"], "kind": "directory"}
    elif source is not None:
        selected = {"artifactRef": str(source), "sha256": sha256(package.read(source)), "kind": "file"}
    else:
        selected = None
    docs = {role: codec.parse(package.read(package.asset(item["path"])), role)
            for role, item in package.manifest["templates"].items()}
    token = uuid4().hex[:12]
    docs["requirements"]["requirementId"] = f"REQ-{token}"
    docs["plan"]["planId"] = f"PLAN-{token}"
    docs["results"]["executionResultId"] = f"EXEC-{token}"
    instance = docs["traceability"]["instance"]
    instance["intake"].update({"mode": mode, "source": selected, "packageVersion": package.manifest["version"]})
    initial = [
        ("Q-OBJECTIVE", [{"document": "requirements", "pointer": "/project/objective"}], "What client outcome must this test demonstrate?"),
        ("Q-SCOPE", [{"document": "plan", "pointer": "/sutId"}], "Which SUT and business/API scope does this document set cover?"),
        ("Q-WORKLOAD", [{"document": "plan", "pointer": "/executionModel"}, {"document": "requirements", "pointer": "/successCriteria"}],
         "What workload and acceptance goals are stated, and which engineering choices still need review?"),
    ]
    instance["questions"] = [{"id": key, "targets": targets, "question": question, "owner": None,
                               "blockingStage": "handoff", "status": "open", "answerRef": None}
                              for key, targets, question in initial]
    store = DocumentStore(package, codec)
    with store.mutation(root, create=True):
        _require_empty(package, root)
        artifacts = Projections(package, codec, store).save(root, docs)
        if inspection is not None:
            store.write_bytes(root / "source-inspection.json", (json.dumps(inspection, indent=2) + "\n").encode())
            artifacts["inspection"] = "source-inspection.json"
    return artifacts


def _require_empty(package: PackageContext, root: Path) -> None:
    if root.exists() and (not root.is_dir() or any(path != package.lock_path(root) for path in root.iterdir())):
        declared = {role: package.document_path(root, role) for role in package.manifest["templates"]}
        existing = sorted(path.name for path in declared.values() if path.is_file())
        missing = sorted(path.name for path in declared.values() if not path.is_file())
        state = "not-directory" if not root.is_dir() else "complete" if not missing else "partial" if existing else "unrelated"
        action = {
            "complete": "Resume with prepare-review --documents; initialise never overwrites an existing set.",
            "partial": "Preserve these files and restore the missing documents from the same intake revision, or initialise a separate empty directory. Do not mix generated identities.",
            "unrelated": "Select a new or empty document directory; existing unrelated files will not be overwritten.",
            "not-directory": "Select a new or empty directory; the selected path is a file.",
        }[state]
        raise IntakeError("OUTPUT_EXISTS", "Initialisation requires a new or empty directory.",
                          detail={"documentsRoot": str(root), "directoryState": state,
                                  "existingDocuments": existing, "missingDocuments": missing, "nextAction": action})
