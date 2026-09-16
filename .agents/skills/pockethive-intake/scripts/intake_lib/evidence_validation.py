"""Responsibility: verify source identities and material-fact provenance coverage.
Must not: infer source meaning, fetch remote sources or authenticate human approval. Contract: intake-contract.md.
"""
from __future__ import annotations

from pathlib import Path

from .bundle_inspector import BundleInspector
from .errors import IntakeError
from .package_context import sha256
from .pointers import covers, leaves, resolve
from .review_digest import review_digest


class EvidenceValidation:
    def __init__(self, package, codec, policy, templates) -> None:
        self.package, self.codec, self.policy, self.templates = package, codec, policy, templates
        self.sample_hash = sha256(package.read(package.asset(package.manifest["requirementsSample"])))

    def check(self, root: Path, docs: dict) -> tuple[list, list, list]:
        gaps, errors, warnings = [], [], []
        instance = docs["traceability"]["instance"]
        verified = set()
        sample_sources = set()

        def issue(collection, code, pointer, message, role="traceability"):
            collection.append(IntakeError(code, message, role, pointer).issue)

        def source(record, pointer):
            ref = record.get("artifactRef")
            if not ref or not record.get("sha256"):
                issue(gaps, "SOURCE_REFERENCE", pointer, "Supply an exact source artifact and its byte hash.")
                return False
            try:
                path = self.package.evidence_path(root, ref)
                if path in {self.package.document_path(root, role) for role in self.package.manifest["templates"]}:
                    raise IntakeError("CIRCULAR_SOURCE", "Generated intake documents cannot be their own supporting evidence.")
                raw = self.package.read(path)
                if sha256(raw) != record["sha256"]:
                    raise IntakeError("SOURCE_HASH", "Recorded source bytes have changed.")
                if record.get("pointer"):
                    if path.suffix.lower() not in (".yaml", ".yml", ".json"):
                        raise IntakeError("SOURCE_POINTER", "JSON Pointers require a structured YAML or JSON source.")
                    resolve(self.codec.parse(raw, "source"), record["pointer"])
                verified.add(ref)
                if record["sha256"] == self.sample_hash:
                    sample_sources.add(ref)
                return True
            except IntakeError as error:
                collection = gaps if error.issue["code"] == "EXTERNAL_SOURCE_UNVERIFIED" else errors
                issue(collection, error.issue["code"], pointer, error.issue["message"])
                return False

        intake_source = instance["intake"]["source"]
        mode = instance["intake"]["mode"]
        if mode == "from-bundle" and (not intake_source or intake_source["kind"] != "directory"):
            issue(errors, "INTAKE_SOURCE_MODE", "/instance/intake/source", "from-bundle requires its explicitly selected directory source.")
        if mode == "new-requirements" and intake_source and intake_source["kind"] != "file":
            issue(errors, "INTAKE_SOURCE_MODE", "/instance/intake/source", "new-requirements accepts an explicit narrative file source, or no source yet.")
        if intake_source:
            if intake_source["kind"] == "directory":
                try:
                    if not intake_source.get("artifactRef") or not intake_source.get("sha256"):
                        raise IntakeError("SOURCE_REFERENCE", "A directory source needs its explicit path and inventory hash.")
                    if not Path(intake_source["artifactRef"]).is_absolute():
                        raise IntakeError("INTAKE_SOURCE_PATH", "The selected bundle identity requires an explicit absolute directory path.")
                    path = self.package.evidence_path(root, intake_source["artifactRef"])
                    result = BundleInspector(self.package, self.codec).inspect(path)
                    if result["sha256"] != intake_source["sha256"]:
                        issue(errors, "SOURCE_HASH", "/instance/intake/source", "Selected bundle inventory has changed; review the changed source explicitly.")
                except IntakeError as error:
                    issue(errors, error.issue["code"], "/instance/intake/source", error.issue["message"])
            else:
                source(intake_source, "/instance/intake/source")

        records = []
        for index, record in enumerate(instance["provenance"]):
            pointer = f"/instance/provenance/{index}"
            target = record["target"]
            try:
                resolve(docs[target["document"]], target["pointer"])
            except IntakeError:
                issue(errors, "PROVENANCE_TARGET", pointer + "/target", "Provenance target does not resolve exactly.")
            local = [source(row, f"{pointer}/sources/{i}") for i, row in enumerate(record["sources"])]
            if not local:
                issue(gaps, "SOURCE_REFERENCE", pointer + "/sources", "A populated provenance record needs supplied evidence.")
            if record["kind"] == "calculation":
                calculation = record.get("calculation")
                if not calculation or not calculation["inputs"] or not calculation["formula"] or not calculation["unit"]:
                    issue(gaps, "CALCULATION_EVIDENCE", pointer, "Keep the exact calculation inputs, formula and unit.")
                elif calculation:
                    for target_input in calculation["inputs"]:
                        try:
                            resolve(docs[target_input["document"]], target_input["pointer"])
                        except IntakeError:
                            issue(errors, "CALCULATION_INPUT", pointer, "A declared calculation input does not resolve.")
            records.append((record, bool(local) and all(local)))

        # Confirmations and decisions point to evidence recorded in this same ledger.
        for index, (record, valid) in enumerate(records):
            pointer = f"/instance/provenance/{index}"
            if record["kind"] in ("bundle-observation", "engineer-proposal", "approval", "not-applicable"):
                if record.get("confirmationRef") not in verified - sample_sources:
                    issue(gaps, "CONFIRMATION_EVIDENCE", pointer + "/confirmationRef", "Reference explicit adoption or approval in a verified supplied artifact.")
            if valid:
                for entry in record["sources"]:
                    if entry["sha256"] == self.sample_hash and record.get("confirmationRef") not in verified - sample_sources:
                        issue(gaps, "SAMPLE_ADOPTION", pointer, "An example cannot establish a client fact without explicit adoption.")
        for index, proposal in enumerate(instance["proposals"]):
            pointer = f"/instance/proposals/{index}"
            for i, record in enumerate(proposal["sources"]):
                source(record, f"{pointer}/sources/{i}")
            if proposal["status"] == "proposed":
                issue(gaps, "UNADOPTED_PROPOSAL", pointer, "A proposal remains a proposal until explicitly accepted or rejected.")
            elif proposal["status"] == "accepted" and (proposal["decisionRef"] not in verified - sample_sources or proposal["unresolvedPremises"]):
                issue(gaps, "PROPOSAL_DECISION", pointer, "Accepted proposals need verified decision evidence and resolved premises.")
        for index, question in enumerate(instance["questions"]):
            if question["status"] == "answered" and question["answerRef"] not in verified:
                issue(gaps, "ANSWER_EVIDENCE", f"/instance/questions/{index}/answerRef", "The answer reference must identify a verified source artifact.")

        for role in ("requirements", "plan", "results"):
            exempt = self.policy["administrativePointers"][role] + self.policy["immutablePointers"][role]
            for pointer, value in leaves(docs[role]):
                if value is None or value == "" or any(covers(parent, pointer) for parent in exempt):
                    continue
                try:
                    original = resolve(self.templates[role], pointer)
                    if original == value:
                        continue
                except IntakeError:
                    pass
                matching = [(record, valid) for record, valid in records if record["target"]["document"] == role and covers(record["target"]["pointer"], pointer)]
                if not any(valid for _, valid in matching):
                    issue(gaps, "MISSING_PROVENANCE", pointer, "Record the explicit source for this populated fact or mark its proposal status.", role)
                if role == "plan" and (covers("/approval", pointer) or covers("/generation/approval", pointer)):
                    if not any(valid and record["kind"] == "approval" and record.get("confirmationRef") in verified - sample_sources for record, valid in matching):
                        issue(gaps, "APPROVAL_EVIDENCE", pointer, "An approval label needs explicit supplied human decision evidence.", role)
            for pointer in self.policy["immutablePointers"][role]:
                if resolve(docs[role], pointer) != resolve(self.templates[role], pointer):
                    issue(errors, "POLICY_EDIT", pointer, "Package-owned template policy cannot be independently rewritten.", role)

        review = instance["review"]
        digest = review_digest(self.codec.plain(docs), self.policy)
        if review["status"] == "confirmed":
            if review.get("contentSha256") != digest:
                issue(errors, "STALE_REVIEW", "/instance/review/contentSha256", "The recorded confirmation does not bind the current content. Obtain review of the changed content; finalise cannot approve it.")
            if review["evidenceRef"] not in verified - sample_sources:
                issue(gaps, "REVIEW_EVIDENCE", "/instance/review/evidenceRef", "The human review must reference a verified supplied artifact.")
        warnings.append("Consistency checks cannot authenticate people, prove source meaning, validate live PocketHive capabilities or authorise execution.")
        return gaps, errors, warnings
