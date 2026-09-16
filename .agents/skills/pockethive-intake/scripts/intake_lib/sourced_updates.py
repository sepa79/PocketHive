"""Responsibility: apply explicit revision-bound field edits with their evidence.
Must not: infer source meaning, accept reviews or own schema/projection validation.
Contract: intake-contract.md, explicit update batch.
"""
from __future__ import annotations

from copy import deepcopy
import re

from .authoring_policy import AuthoringPolicy
from .errors import IntakeError
from .pointers import covers, replace, resolve
from .projections import Projections


def apply_updates(package, codec, store, root, docs, validation, input_path, revision):
    batch = codec.plain(codec.parse(package.read(input_path), "updates"))
    _envelope(batch)
    if batch["expectedDocumentsSha256"] != revision:
        raise IntakeError("STALE_DOCUMENTS", "The edit batch refers to another document revision; read the current set first.")
    store.assert_revision(root, revision)
    policy = AuthoringPolicy(package, codec, store, docs)
    candidate = deepcopy(docs)
    targets, evidence_targets = [], []
    for index, update in enumerate(batch["updates"]):
        location = f"/updates/{index}"
        _keys(update, {"target", "value", "provenance"}, location)
        target = update["target"]
        _keys(target, {"document", "pointer"}, location + "/target")
        role, pointer = target["document"], target["pointer"]
        if not isinstance(role, str) or role not in docs or not isinstance(pointer, str):
            raise IntakeError("UPDATE_TARGET", "Use a declared document role and exact field pointer.", "updates", location + "/target")
        resolve(docs[role], pointer, role)
        owner = policy.describe(role, pointer)
        if not owner["editable"]:
            raise IntakeError("UPDATE_PROTECTED", "This target belongs to generated, administrative, immutable or review state; use its documented owner.", role, pointer)
        if any(other["document"] == role and
               (covers(other["pointer"], pointer) or covers(pointer, other["pointer"])) for other in targets):
            raise IntakeError("UPDATE_OVERLAP", "An edit batch cannot contain duplicate or overlapping targets.", role, pointer)
        targets.append(target)
        if owner["evidenceRequired"]:
            evidence = update["provenance"]
            if not isinstance(evidence, dict) or evidence.get("target") != target:
                raise IntakeError("UPDATE_EVIDENCE", "Supply one canonical provenance record for this exact target.", role, pointer)
            records = candidate["traceability"]["instance"]["provenance"]
            for record in records:
                previous = record["target"]
                if previous["document"] == role and previous["pointer"] != pointer and (
                        covers(previous["pointer"], pointer) or covers(pointer, previous["pointer"])):
                    raise IntakeError("PROVENANCE_OVERLAP", "Existing broader or narrower evidence intersects this edit; review its scope explicitly before replacing it.", role, pointer)
            records[:] = [record for record in records if record["target"] != target]
            records.append(deepcopy(evidence))
            evidence_targets.append(target)
        elif update["provenance"] is not None:
            raise IntakeError("UPDATE_EVIDENCE", "Question, proposal and intake-source edits use their existing evidence fields; provenance must be null.", role, pointer)
        replace(candidate[role], pointer, deepcopy(update["value"]), role)
    structure = validation.structure(candidate)
    if structure:
        return {"applied": False, "errors": structure}
    projections = Projections(package, codec, store)
    encoded = projections.prepare(candidate)
    checked = validation.check(root, candidate, encoded=encoded)
    errors = [issue for issue in checked["errors"] if issue["code"] != "STALE_REVIEW"]
    records = candidate["traceability"]["instance"]["provenance"]
    evidence_paths = [f"/instance/provenance/{index}/sources" for index, record in enumerate(records)
                      if record["target"] in evidence_targets]
    errors.extend(issue for issue in checked["gaps"]
                  if issue["code"] in ("SOURCE_REFERENCE", "EXTERNAL_SOURCE_UNVERIFIED") and
                  any(covers(path, issue["pointer"]) for path in evidence_paths))
    # Evidence belongs to the complete question/proposal row, including siblings
    # of a changed status or target. The canonical validator emits its exact field.
    ledger_paths = ["/".join(target["pointer"].split("/")[:4])
                    for target in targets if target["document"] == "traceability"]
    errors.extend(issue for issue in checked["gaps"]
                  if issue["code"] in ("SOURCE_REFERENCE", "EXTERNAL_SOURCE_UNVERIFIED", "ANSWER_EVIDENCE", "PROPOSAL_DECISION")
                  and issue["document"] == "traceability" and
                  any(covers(path, issue["pointer"]) or covers(issue["pointer"], path) for path in ledger_paths))
    if errors:
        return {**checked, "applied": False, "errors": errors}
    saved = projections.save_prepared(root, encoded, expected_revision=revision)
    checked = validation.check(root, store.load(root))
    store.assert_revision(root, saved["documentsSha256"])
    return {**saved, **checked, "applied": True, "updatedTargets": targets}


def _keys(value: object, required: set[str], pointer: str) -> None:
    if not isinstance(value, dict) or set(value) != required:
        raise IntakeError("UPDATE_INPUT", "Use exactly the documented batch fields; values and evidence must be explicit.", "updates", pointer)


def _envelope(batch: object) -> None:
    _keys(batch, {"expectedDocumentsSha256", "updates"}, "")
    digest = batch["expectedDocumentsSha256"]
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise IntakeError("UPDATE_REVISION", "Supply the exact SHA-256 document revision returned by the CLI.", "updates", "/expectedDocumentsSha256")
    if not isinstance(batch["updates"], list) or not batch["updates"]:
        raise IntakeError("UPDATE_INPUT", "Supply a nonempty list of explicit updates.", "updates", "/updates")
