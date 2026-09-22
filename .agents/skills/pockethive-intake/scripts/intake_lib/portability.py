"""Responsibility: prepare verified intake references for portable local delivery.
Must not: search for missing evidence, approve reviews or rewrite client payloads. Local contract: intake-contract.md.
Contract: RESP-INTAKE-PORTABILITY — docs/architecture/intake-runtime.md#resp-intake-portability.
"""
from copy import deepcopy
import os

from .bundle_inspector import BundleInspector
from .errors import IntakeError
from .evidence_references import evidence_references
from .evidence_snapshots import retain_snapshots, snapshot_ref
from .package_context import sha256
from .projections import Projections


def make_portable(package, codec, store, root, docs, validation, revision):
    candidate = deepcopy(docs)
    records, links = evidence_references(candidate)
    selected = candidate["traceability"]["instance"]["intake"]["source"]
    bundle = None
    if selected and selected["kind"] == "directory":
        bundle = package.evidence_path(root, selected["artifactRef"])
        inspection = BundleInspector(package, codec).inspect(bundle)
        if inspection["sha256"] != selected["sha256"]:
            raise IntakeError("SOURCE_HASH", "Selected bundle changed; portability cannot rebind it.")
        if root != package.bundle_intake_path(bundle):
            raise IntakeError("PORTABILITY_LAYOUT", "Place the document set in the selected bundle's intake directory before preparing portable bundle references.")
        selected["artifactRef"] = package.bundle_reference(bundle, root)
    replacements, snapshots = {}, {}
    assignments = []
    for record in records:
        ref = record["artifactRef"]
        if not ref or not record["sha256"]:
            raise IntakeError("SOURCE_REFERENCE", "Complete evidence identity before preparing portability.")
        path = package.evidence_path(root, ref)
        if path in {package.document_path(root, role) for role in docs}:
            raise IntakeError("CIRCULAR_SOURCE", "Working documents cannot be supporting evidence.")
        data = package.read(path)
        if sha256(data) != record["sha256"]:
            raise IntakeError("SOURCE_HASH", "Recorded source bytes changed; portability cannot replace their identity.")
        relative = os.path.relpath(path, root) if bundle and path.is_relative_to(bundle) else snapshot_ref(package, path, data)
        replacements[ref] = relative
        assignments.append((record, relative))
        if not bundle or not path.is_relative_to(bundle):
            snapshots[relative] = data
    for container, key in links:
        ref = container[key]
        if ref and ref not in replacements:
            raise IntakeError("SOURCE_REFERENCE", "A decision or evidence link has no verified source record; record its provenance first.")
    link_assignments = [(container, key, replacements[container[key]]) for container, key in links if container[key]]
    for record, relative in assignments:
        record["artifactRef"] = relative
    for container, key, relative in link_assignments:
        container[key] = relative
    projection = Projections(package, codec, store)
    encoded = projection.prepare(candidate)
    store.assert_revision(root, revision)
    retain_snapshots(package, store, root, snapshots)
    checked = validation.check(root, candidate, encoded=encoded)
    if any(issue["code"] != "STALE_REVIEW" for issue in checked["errors"]):
        return {**checked, "applied": False}
    saved = projection.save_prepared(root, encoded, expected_revision=revision)
    checked = validation.check(root, store.load(root))
    store.assert_revision(root, saved["documentsSha256"])
    return {**saved, **checked, "applied": True, "portableEvidenceFiles": len(snapshots),
            "nextAction": "Copy the bundle with intake, or the narrative intake directory. Review any stale confirmation before handoff."}
