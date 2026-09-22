"""Responsibility: compose canonical structural, evidence and semantic intake checks.
Must not: repair documents, approve operations or validate PocketHive runtime configuration. Contract: intake-contract.md.
"""
from __future__ import annotations

import json

from .authoring_advisories import authoring_advisories
from .errors import IntakeError
from .data_rules import check_data
from .evidence_validation import EvidenceValidation
from .measurement_rules import check_measurements
from .package_context import sha256
from .projections import Projections
from .pointers import resolve
from .readiness_rules import check_readiness
from .reference_rules import check_references
from .review_digest import review_digest
from .schema_validation import SchemaValidation


class Validation:
    def __init__(self, package, codec, store):
        self.package, self.codec, self.store = package, codec, store
        self.schemas = SchemaValidation(package)
        self.policy = json.loads(package.read(package.asset("contract/provenance-policy.json")))

    def structure(self, docs):
        errors = []
        for role, document in docs.items():
            errors.extend(self.schemas.validate(role, self.codec.plain(document)))
        return errors

    def check(self, root, docs, *, encoded=None):
        self.inspection = None
        errors = self.structure(docs)
        if errors:
            return {"errors": errors, "gaps": [], "warnings": []}
        docs = self.codec.plain(docs)
        notices = authoring_advisories(docs)
        templates = {role: self.codec.plain(self.codec.parse(self.package.read(self.package.asset(item["path"])), role))
                     for role, item in self.package.manifest["templates"].items()}
        projections = Projections(self.package, self.codec, self.store)
        try:
            projections.check_question_owner(docs)
        except IntakeError as error:
            errors.append(error.issue)
        raw = encoded if encoded is not None else {
            role: self.package.read(self.package.document_path(root, role)) for role in self.package.manifest["templates"]}
        raw_hashes = {role: sha256(raw[role]) for role in ("requirements", "plan", "results")}
        generated = projections.assignments(docs, raw_hashes)
        for role, assignments in generated.items():
            for pointer, expected_value in assignments.items():
                try:
                    current_value = resolve(docs[role], pointer, role)
                    stale = current_value != expected_value
                except IntakeError:
                    stale = True
                if stale:
                    errors.append(IntakeError("STALE_PROJECTION", "Document reference, hash or generated value is stale; finalise explicitly.", role, pointer).issue)
        errors.extend(check_references(docs))
        gaps, missing_errors = check_readiness(docs)
        errors.extend(missing_errors)
        data_gaps, data_errors = check_data(docs)
        gaps.extend(data_gaps)
        errors.extend(data_errors)
        measure_gaps, measure_errors = check_measurements(docs)
        gaps.extend(measure_gaps)
        errors.extend(measure_errors)
        evidence = EvidenceValidation(self.package, self.codec, self.policy, templates, generated)
        evidence_gaps, evidence_errors, warnings = evidence.check(root, docs)
        self.inspection = evidence.inspection
        warnings.extend(notices)
        gaps.extend(evidence_gaps)
        errors.extend(evidence_errors)
        return {"errors": errors, "gaps": gaps, "warnings": warnings,
                "reviewContentSha256": review_digest(docs, self.policy),
                "traceabilitySha256": sha256(raw["traceability"])}
