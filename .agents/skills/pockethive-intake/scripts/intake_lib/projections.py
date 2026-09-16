"""Responsibility: construct and refresh derived paths, questions and document hashes.
Must not: fill client facts, adopt proposals or grant approvals. Contract: intake-contract.md.
"""
from __future__ import annotations

from pathlib import Path
import json

from .document_store import DocumentStore
from .errors import IntakeError
from .package_context import PackageContext, canonical_hash, sha256
from .yaml_codec import YamlCodec
from .review_digest import review_digest
from .pointers import resolve


def projected_questions(instance: dict) -> list[str]:
    return [str(question["question"]) for question in instance["questions"] if question["status"] != "answered"]


class Projections:
    def __init__(self, package: PackageContext, codec: YamlCodec, store: DocumentStore) -> None:
        self.package, self.codec, self.store = package, codec, store

    def check_question_owner(self, docs: dict) -> None:
        instance = docs["traceability"]["instance"]
        expected = instance["projections"]["openQuestionsSha256"]
        current = canonical_hash(self.codec.plain(docs["requirements"]["openQuestions"]))
        if expected is not None and current != expected:
            raise IntakeError("PROJECTION_EDIT", "Edit traceability questions; requirements.openQuestions is generated.",
                              "requirements", "/openQuestions")

    def assignments(self, docs: dict, hashes: dict) -> dict:
        """The single constructor for generated values; reads and writes consume it."""
        templates = self.package.manifest["templates"]
        paths = {role: entry["path"] for role, entry in templates.items()}
        names = {role: entry["output"] for role, entry in templates.items()}
        req, plan = docs["requirements"], docs["plan"]
        questions = projected_questions(docs["traceability"]["instance"])
        refs = {"requirementsRef": names["requirements"], "requirementsVersion": req["version"],
                "requirementsSha256": hashes.get("requirements"), "planRef": names["plan"],
                "planVersion": plan["version"], "planRevision": plan["revision"],
                "planSha256": hashes.get("plan"), "traceabilityMap": names["traceability"]}
        return {
            "requirements": {
                "/templateMapping": {"testPlanTemplate": paths["plan"], "testExecutionResultsTemplate": paths["results"], "traceabilityMap": paths["traceability"]},
                "/bundleGeneration/testPlanRef": names["plan"], "/openQuestions": questions},
            "plan": {
                "/contract/referenceDocuments": {"requirementsTemplate": paths["requirements"], "executionTemplate": paths["results"], "traceabilityMap": paths["traceability"]},
                "/requirementId": req["requirementId"], "/requirementsRef": names["requirements"],
                "/requirementsSnapshot": {"version": req["version"], "sha256": hashes.get("requirements")}},
            "results": {"/requirementId": req["requirementId"], "/planId": plan["planId"],
                        **{"/references/" + key: value for key, value in refs.items()}},
            "traceability": {
                "/templateRegistry": {"requirements": paths["requirements"], "requirementsSample": self.package.manifest["requirementsSample"], "testPlan": paths["plan"], "testPlanSample": None, "executionResults": paths["results"]},
                "/instance/projections/openQuestionsSha256": canonical_hash(questions),
                **{"/instance/documents/" + role: {"path": names[role], "sha256": hashes.get(role)} for role in ("requirements", "plan", "results")}}
        }

    def prepare(self, docs: dict) -> dict[str, bytes]:
        self.check_question_owner(docs)
        encoded, hashes = {}, {}
        for role in ("requirements", "plan", "results", "traceability"):
            for pointer, value in self.assignments(docs, hashes)[role].items():
                parent, key = pointer.rsplit("/", 1)
                container = resolve(docs[role], parent)
                # Update existing mappings to retain template comments in round trips.
                if isinstance(value, dict) and isinstance(container.get(key), dict):
                    container[key].update(value)
                else:
                    container[key] = value
            encoded[role] = self.codec.dump(docs[role])
            hashes[role] = sha256(encoded[role])
        return encoded

    def save(self, root: Path, docs: dict) -> dict:
        encoded = self.prepare(docs)
        for role in ("requirements", "plan", "results", "traceability"):
            self.store.write_bytes(self.package.document_path(root, role), encoded[role])
        self.store.load(root)
        policy = json.loads(self.package.read(self.package.asset("contract/provenance-policy.json")))
        return {"documents": {role: self.package.manifest["templates"][role]["output"] for role in encoded},
                "reviewContentSha256": review_digest(self.codec.plain(docs), policy),
                "traceabilitySha256": sha256(encoded["traceability"])}
