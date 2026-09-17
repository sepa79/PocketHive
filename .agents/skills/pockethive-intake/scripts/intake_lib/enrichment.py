"""Responsibility: stage, validate and publish explicitly sourced intake enrichment.
Must not: infer facts, overwrite working revisions or own validation. Contract: intake-contract.md.
"""
import json
from pathlib import Path
import tempfile

from .document_store import DocumentStore
from .enrichment_plan import prepare_enrichment
from .errors import IntakeError
from .evidence_snapshots import retain_snapshots
from .initialisation import initialise
from .projections import Projections
from .sourced_updates import apply_batch
from .validation import Validation
from .workspace_assessment import assess_workspace


def enrich_input(package, codec, root, input_path, mode, bundle):
    sources, snapshots, updates, receipt = prepare_enrichment(package, codec, input_path)
    store = DocumentStore(package, codec)
    if root.exists():
        store.assert_unlocked(root)
    state = assess_workspace(package, codec, root)
    if state["errors"]:
        return state
    if state["directoryState"] not in ("empty", "raw", "unrelated"):
        raise IntakeError("ENRICHMENT_WORKSPACE", "Preserve the existing working set; resume or resolve its mixture instead of enriching over it.", detail=state)
    if mode == "from-bundle":
        if bundle is None:
            raise IntakeError("SOURCE_REQUIRED", "Bundle enrichment requires an explicit source bundle.")
        package.check_bundle_documents(bundle, root)
    elif bundle is not None:
        raise IntakeError("ENRICHMENT_MODE", "New-requirements enrichment takes its human files from the transfer plan; omit --source.")
    originals = {source["path"]: source["data"] for source in sources.values()}
    previous = {}
    for role in package.manifest["templates"]:
        path = package.document_path(root, role)
        previous[role] = package.read(path) if path.exists() else None
        if previous[role] is not None and originals.get(path) != previous[role]:
            raise IntakeError("ENRICHMENT_COLLISION", "Every existing working filename must be an explicitly selected, archived raw source.", role)
    receipt_path = root / package.local_name("enrichmentReport")
    package.reject_links(receipt_path)
    if receipt_path.exists():
        raise IntakeError("ENRICHMENT_RECEIPT", "An enrichment receipt already exists; inspect and resume the previous work.")
    with tempfile.TemporaryDirectory(prefix="intake-enrichment-") as temporary:
        stage = Path(temporary) / "documents"
        initialise(package, codec, stage, mode, bundle)
        retain_snapshots(package, store, stage, snapshots)
        validation = Validation(package, codec, store)
        with store.mutation(stage):
            revision = store.revision(stage)
            result = apply_batch(package, codec, store, stage, store.load(stage), validation,
                                 {"expectedDocumentsSha256": revision, "updates": updates}, revision)
        if result["errors"]:
            return {key: result[key] for key in ("errors", "gaps", "warnings")} | {
                "applied": False, "outputWritten": False, "persistence": {"state": "not-written"},
                "validation": {"subject": "candidate"}}
        docs = store.load(stage)
        if mode == "from-bundle":
            docs["traceability"]["instance"]["intake"]["source"]["artifactRef"] = package.bundle_reference(bundle, root)
        projection = Projections(package, codec, store)
        encoded = projection.prepare(docs)
        with store.mutation(root, create=True):
            for path, data in originals.items():
                if package.read(path) != data:
                    raise IntakeError("SOURCE_HASH", "A source changed during enrichment; review it before retrying.")
            for role, data in previous.items():
                path = package.document_path(root, role)
                if (package.read(path) if path.exists() else None) != data:
                    raise IntakeError("STALE_DOCUMENTS", "The output changed during enrichment; no working documents were replaced.")
            retain_snapshots(package, store, root, snapshots)
            checked = validation.check(root, docs, encoded=encoded)
            if checked["errors"]:
                return {**checked, "applied": False, "outputWritten": False}
            # The receipt precedes publication so interrupted writes retain recovery evidence.
            store.write_bytes(receipt_path, (json.dumps(receipt, indent=2) + "\n").encode())
            saved = projection.save_prepared(root, encoded)
            checked = validation.check(root, store.load(root))
            store.assert_revision(root, saved["documentsSha256"])
            return {**saved, **checked, "applied": True, "outputWritten": True,
                    "enrichment": {"receipt": str(receipt_path), "transferredFields": len(updates) - 1,
                                   "untransferredFields": sum(len(row["pointers"]) for row in receipt["untransferred"])}}
