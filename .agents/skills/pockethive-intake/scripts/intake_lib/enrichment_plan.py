"""Responsibility: translate explicit source pointers into canonical authoring edits.
Must not: infer mappings, adopt examples or persist working documents. Local contract: intake-contract.md.
Contract: RESP-INTAKE-ENRICHMENT — docs/architecture/intake-runtime.md#resp-intake-enrichment.
"""
from copy import deepcopy

from .errors import IntakeError
from .evidence_snapshots import snapshot_ref
from .package_context import sha256
from .pointers import covers, leaves, resolve


def prepare_enrichment(package, codec, input_path):
    plan = codec.plain(codec.parse(package.read(input_path), "enrichment"))
    _shape(plan, {"sources", "transfers", "questions", "omissions"})
    for key in plan:
        if not isinstance(plan[key], list):
            raise IntakeError("ENRICHMENT_INPUT", "Each transfer-plan collection must be an array.", "enrichment", "/" + key)
    if not plan["sources"] or len(plan["sources"]) > package.manifest["limits"]["bundleFiles"]:
        raise IntakeError("ENRICHMENT_INPUT", "Supply a bounded, nonempty source list.")
    sources, snapshots, claims = {}, {}, {}
    total = 0
    for source in plan["sources"]:
        _shape(source, {"id", "path", "sha256"})
        identity = source["id"]
        if not isinstance(identity, str) or not identity or identity in sources or not isinstance(source["path"], str):
            raise IntakeError("ENRICHMENT_SOURCE", "Sources need unique explicit IDs and paths.")
        path = package.evidence_path(input_path.parent, source["path"])
        data = package.read(path)
        total += len(data)
        if total > package.manifest["limits"]["bundleBytes"]:
            raise IntakeError("INPUT_LIMIT", "Selected source bytes exceed the declared limit.")
        if sha256(data) != source["sha256"]:
            raise IntakeError("SOURCE_HASH", "Selected source bytes differ from the reviewed transfer plan.")
        value = codec.parse(data, str(path))
        if value is not None and not isinstance(value, dict):
            raise IntakeError("DOCUMENT_OBJECT", "A human form must be an object or blank.", str(path))
        ref = snapshot_ref(package, path, data)
        snapshots[ref] = data
        sources[identity] = {"path": path, "data": data, "value": value, "ref": ref, "sha256": source["sha256"]}
        claims[identity] = []

    def take(row):
        identity, pointer = row["source"], row["pointer"]
        if not isinstance(identity, str) or identity not in sources or not isinstance(pointer, str):
            raise IntakeError("ENRICHMENT_SOURCE", "Use a declared source ID and exact pointer.")
        value = resolve(sources[identity]["value"], pointer, identity)
        if any(covers(old, pointer) or covers(pointer, old) for old in claims[identity]):
            raise IntakeError("ENRICHMENT_OVERLAP", "Source dispositions must not overlap; select each scope explicitly once.", identity, pointer)
        claims[identity].append(pointer)
        return sources[identity], value

    updates, questions = [], []
    for row in plan["transfers"]:
        _shape(row, {"source", "pointer", "target", "kind"})
        if row["kind"] not in ("client-statement", "bundle-observation", "engineer-proposal"):
            raise IntakeError("ENRICHMENT_KIND", "Transfer facts or proposals; approvals and run evidence require their existing explicit workflow.")
        source, value = take(row)
        target = row["target"]
        _shape(target, {"document", "pointer"})
        if target["document"] not in ("requirements", "plan"):
            raise IntakeError("ENRICHMENT_TARGET", "Transfer into requirements or plan; use questions for unresolved source content.")
        updates.append({"target": target, "value": deepcopy(value), "provenance": {
            "target": deepcopy(target), "kind": row["kind"],
            "sources": [{"artifactRef": source["ref"], "pointer": row["pointer"], "sha256": source["sha256"]}],
            "confirmationRef": None, "calculation": None}})
    for index, row in enumerate(plan["questions"]):
        _shape(row, {"source", "pointer", "targets", "blockingStage"})
        _, value = take(row)
        if not isinstance(value, str) or not value.strip():
            raise IntakeError("ENRICHMENT_QUESTION", "An imported question pointer must identify nonempty text.")
        questions.append({"id": f"Q-IMPORT-{index + 1}", "targets": row["targets"], "question": value,
                          "owner": None, "blockingStage": row["blockingStage"], "status": "open", "answerRef": None})
    for row in plan["omissions"]:
        _shape(row, {"source", "pointer", "reason"})
        if not isinstance(row["reason"], str) or not row["reason"].strip():
            raise IntakeError("ENRICHMENT_OMISSION", "An explicit omission needs its reason.")
        take(row)
    untransferred = []
    for identity, source in sources.items():
        pending = [pointer for pointer, value in leaves(source["value"])
                   if value is not None and value != "" and not any(covers(parent, pointer) for parent in claims[identity])]
        if pending:
            untransferred.append({"source": identity, "artifactRef": source["ref"], "pointers": pending})
            questions.append({"id": f"Q-SOURCE-REVIEW-{len(untransferred)}", "targets": [],
                              "question": f"Engineering review: {len(pending)} populated source fields remain untransferred from {source['ref']}; inspect the enrichment receipt and retained original.",
                              "owner": None, "blockingStage": "review", "status": "open", "answerRef": None})
    updates.append({"target": {"document": "traceability", "pointer": "/instance/questions"}, "value": questions, "provenance": None})
    receipt = {"sources": [{"id": identity, "artifactRef": source["ref"], "sha256": source["sha256"]} for identity, source in sources.items()],
               "transfers": plan["transfers"], "questions": plan["questions"], "omissions": plan["omissions"], "untransferred": untransferred}
    return sources, snapshots, updates, receipt


def _shape(value, keys):
    if not isinstance(value, dict) or set(value) != keys:
        raise IntakeError("ENRICHMENT_INPUT", "Use exactly the documented enrichment fields.")
