"""Responsibility: construct and refresh derived paths, questions and document hashes.
Must not: fill client facts, adopt proposals or grant approvals. Local contract: intake-contract.md.
Contract: RESP-INTAKE-PROJECTIONS — docs/architecture/intake-runtime.md#resp-intake-projections.
"""
from __future__ import annotations

from pathlib import Path
import json

from .document_store import DocumentStore
from .errors import IntakeError
from .package_context import PackageContext, canonical_hash, sha256
from .yaml_codec import YamlCodec
from .review_digest import review_digest
from .pointers import covers, resolve


_IDENTITY_OWNERS = {"/generation/bundleId": "/bundleGeneration/bundleId",
                    "/generation/scenarioId": "/bundleGeneration/scenarioId"}


def projected_questions(instance: dict) -> list[str | None]:
    questions = resolve(instance, "/questions", "traceability")
    return [question["question"] for question in questions if question["status"] != "answered"]


class Projections:
    def __init__(self, package: PackageContext, codec: YamlCodec, store: DocumentStore) -> None:
        self.package, self.codec, self.store = package, codec, store

    def check_question_owner(self, docs: dict) -> None:
        instance = resolve(docs["traceability"], "/instance", "traceability")
        expected = resolve(docs["traceability"], "/instance/projections/openQuestionsSha256", "traceability")
        if "openQuestions" not in docs["requirements"]:
            return
        projection = self.codec.plain(docs["requirements"]["openQuestions"])
        if expected is None:
            if projection == [] or projection == projected_questions(instance):
                return
            raise IntakeError("PROJECTION_EDIT", "Move authored questions to traceability.instance.questions; then remove requirements.openQuestions and finalise.",
                              "requirements", "/openQuestions")
        current = canonical_hash(self.codec.plain(docs["requirements"]["openQuestions"]))
        if current != expected:
            raise IntakeError("PROJECTION_EDIT", "Edit traceability questions; requirements.openQuestions is generated.",
                              "requirements", "/openQuestions")

    def assignments(self, docs: dict, hashes: dict) -> dict:
        """The single constructor for generated values; reads and writes consume it."""
        templates = self.package.manifest["templates"]
        paths = {role: entry["path"] for role, entry in templates.items()}
        names = {role: entry["output"] for role, entry in templates.items()}
        req, plan = docs["requirements"], docs["plan"]
        questions = projected_questions(resolve(docs["traceability"], "/instance", "traceability"))
        requirement_id = resolve(req, "/requirementId", "requirements")
        requirement_version = resolve(req, "/version", "requirements")
        plan_version = resolve(plan, "/version", "plan")
        plan_id = resolve(plan, "/planId", "plan")
        plan_revision = resolve(plan, "/revision", "plan")
        refs = {"requirementsRef": names["requirements"], "requirementsVersion": requirement_version,
                "requirementsSha256": hashes.get("requirements"), "planRef": names["plan"],
                "planVersion": plan_version, "planRevision": plan_revision,
                "planSha256": hashes.get("plan"), "traceabilityMap": names["traceability"]}
        return {
            "requirements": {
                "/templateMapping": {"testPlanTemplate": paths["plan"], "testExecutionResultsTemplate": paths["results"], "traceabilityMap": paths["traceability"]},
                "/bundleGeneration/testPlanRef": names["plan"], "/openQuestions": questions},
            "plan": {
                "/contract/referenceDocuments": {"requirementsTemplate": paths["requirements"], "executionTemplate": paths["results"], "traceabilityMap": paths["traceability"]},
                "/requirementId": requirement_id, "/requirementsRef": names["requirements"],
                "/requirementsSnapshot": {"version": requirement_version, "sha256": hashes.get("requirements")},
                **{target: resolve(req, source, "requirements") for target, source in _IDENTITY_OWNERS.items()}},
            "results": {"/requirementId": requirement_id, "/planId": plan_id,
                        **{"/references/" + key: value for key, value in refs.items()}},
            "traceability": {
                "/templateRegistry": {"requirements": paths["requirements"], "requirementsSample": self.package.manifest["requirementsSample"], "testPlan": paths["plan"], "testPlanSample": None, "executionResults": paths["results"]},
                "/instance/projections/openQuestionsSha256": canonical_hash(questions),
                **{"/instance/documents/" + role: {"path": names[role], "sha256": hashes.get(role)} for role in ("requirements", "plan", "results")}}
        }

    def prepare(self, docs: dict, *, edited_targets: tuple[dict, ...] = ()) -> dict[str, bytes]:
        self.check_question_owner(docs)
        for target, source in _IDENTITY_OWNERS.items():
            recorded = resolve(docs["plan"], target, "plan")
            owner = resolve(docs["requirements"], source, "requirements")
            edited = any(row["document"] == "requirements" and covers(row["pointer"], source) for row in edited_targets)
            if not edited and recorded is not None and recorded != owner:
                raise IntakeError("IDENTITY_PROJECTION_CONFLICT",
                                  "Author intended identity through requirements.bundleGeneration with apply-updates and its evidence; the plan copy is generated.",
                                  "plan", target)
        encoded, hashes = {}, {}
        for role in ("requirements", "plan", "results", "traceability"):
            for pointer, value in self.assignments(docs, hashes)[role].items():
                parent, key = pointer.rsplit("/", 1)
                container = resolve(docs[role], parent, role)
                if not isinstance(container, dict):
                    raise IntakeError("PROJECTION_CONTAINER", "Generated fields require an object at this location.", role, parent)
                # Update existing mappings to retain template comments in round trips.
                if isinstance(value, dict) and isinstance(container.get(key), dict):
                    container[key].update(value)
                else:
                    container[key] = value
            encoded[role] = self.codec.dump(docs[role])
            hashes[role] = sha256(encoded[role])
        return encoded

    def save(self, root: Path, docs: dict, *, expected_revision: str | None = None) -> dict:
        return self.save_prepared(root, self.prepare(docs), expected_revision=expected_revision)

    def save_prepared(self, root: Path, encoded: dict[str, bytes], *, expected_revision: str | None = None) -> dict:
        """Persist the exact bytes already prepared and checked by the owning workflow."""
        saved_revision = self.store.revision_bytes(encoded)
        if expected_revision is not None:
            self.store.assert_revision(root, expected_revision)
        for role in ("requirements", "plan", "results", "traceability"):
            self.store.write_bytes(self.package.document_path(root, role), encoded[role])
        docs = self.store.load(root)
        self.store.assert_revision(root, saved_revision)
        policy = json.loads(self.package.read(self.package.asset("contract/provenance-policy.json")))
        return {"documents": {role: self.package.manifest["templates"][role]["output"] for role in encoded},
                "documentsSha256": saved_revision,
                "reviewContentSha256": review_digest(self.codec.plain(docs), policy),
                "traceabilitySha256": sha256(encoded["traceability"])}
