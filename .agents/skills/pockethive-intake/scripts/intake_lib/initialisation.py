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
    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        raise IntakeError("OUTPUT_EXISTS", "Initialisation requires a new or empty directory; resume existing documents instead.")
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
    if source is not None and (root == source or root.is_relative_to(source)):
        raise IntakeError("OUTPUT_IN_SOURCE", "Place generated documents outside the selected source directory.")
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
    try:
        root.mkdir(parents=True, exist_ok=True)
    except OSError:
        raise IntakeError("OUTPUT_CREATE", "Output directory cannot be created.") from None
    store = DocumentStore(package, codec)
    artifacts = Projections(package, codec, store).save(root, docs)
    if inspection is not None:
        store.write_bytes(root / "source-inspection.json", (json.dumps(inspection, indent=2) + "\n").encode())
        artifacts["inspection"] = "source-inspection.json"
    return artifacts
