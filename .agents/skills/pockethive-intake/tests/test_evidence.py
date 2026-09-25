"""Adversarial evidence and ledger checks through the public CLI."""

from copy import deepcopy
import hashlib
import json

from test_intake_cli import CliTestCase


class EvidenceTests(CliTestCase):
    def record_name(self, artifact, pointer, digest):
        requirements = self.read_document("requirements.yaml")
        requirements["project"]["name"] = "Synthetic evidence example"
        self.write_document("requirements.yaml", requirements)
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["provenance"].append({
            "target": {"document": "requirements", "pointer": "/project/name"},
            "kind": "client-statement",
            "sources": [{"artifactRef": str(artifact), "pointer": pointer, "sha256": digest}],
            "confirmationRef": None, "calculation": None,
        })
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)

    def test_array_pointer_aliases_do_not_resolve(self):
        source = self.workspace / "input.json"
        source.write_text(json.dumps({"names": ["first", "Synthetic evidence example"]}), encoding="utf-8")
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        for token in ("-1", "01"):
            with self.subTest(token=token):
                self.documents = self.workspace / f"documents-{token}"
                self.initialise()
                self.record_name(source, "/names/" + token, digest)
                _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
                self.assertTrue(any(issue["code"] == "POINTER" and "provenance" in issue["pointer"]
                                    for issue in output["errors"]), output)

    def test_external_only_source_is_an_unverified_gap_without_network_access(self):
        self.initialise()
        self.record_name("https://source.invalid/client-input.json", "/name", "1" * 64)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff",
                                expected_exit=3, offline=True)
        self.assertTrue(any(issue["code"] == "EXTERNAL_SOURCE_UNVERIFIED" for issue in output["gaps"]), output)
        self.assertFalse(output["errors"], output)

    def test_document_cannot_supply_its_own_client_fact_evidence(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        requirements["project"]["name"] = "A document asserting its own fact"
        self.write_document("requirements.yaml", requirements)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        source = self.documents / "requirements.yaml"
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["provenance"].append({
            "target": {"document": "requirements", "pointer": "/project/name"},
            "kind": "client-statement",
            "sources": [{"artifactRef": str(source), "pointer": "/project/name",
                         "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None,
        })
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any(issue["code"] == "CIRCULAR_SOURCE"
                            for issue in output["errors"]), output)

    def test_duplicate_question_ids_are_rejected(self):
        self.initialise()
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["questions"].append(deepcopy(traceability["instance"]["questions"][0]))
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any(issue["code"] == "DUPLICATE_ID" and "/instance/questions" in issue["pointer"]
                            for issue in output["errors"]), output)

    def test_duplicate_proposal_ids_are_rejected(self):
        self.initialise()
        traceability = self.read_document("traceability.yaml")
        proposal = {
            "id": "PROPOSAL-1", "targets": [{"document": "plan", "pointer": "/mockSetup/mode"}],
            "rationale": "Synthetic proposal; no accepted client choice.",
            "sources": [], "unresolvedPremises": ["The client has not selected a target."],
            "status": "proposed", "decisionRef": None,
        }
        traceability["instance"]["proposals"] = [proposal, deepcopy(proposal)]
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any(issue["code"] == "DUPLICATE_ID" and "/instance/proposals" in issue["pointer"]
                            for issue in output["errors"]), output)

    def test_question_and_proposal_targets_reject_array_aliases(self):
        for collection in ("questions", "proposals"):
            for token in ("-1", "01"):
                with self.subTest(collection=collection, token=token):
                    self.documents = self.workspace / f"documents-{collection}-{token}"
                    self.initialise()
                    traceability = self.read_document("traceability.yaml")
                    targets = [{"document": "requirements", "pointer": f"/templates/{token}/apiId"}]
                    if collection == "questions":
                        traceability["instance"]["questions"][0]["targets"] = targets
                    else:
                        traceability["instance"]["proposals"] = [{
                            "id": "PROPOSAL-1", "targets": targets, "rationale": "Synthetic unresolved proposal.",
                            "sources": [], "unresolvedPremises": [], "status": "proposed", "decisionRef": None,
                        }]
                    self.write_document("traceability.yaml", traceability)
                    self.invoke("finalise", "--documents", self.documents, expected_exit=0)
                    _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
                    self.assertTrue(any(issue["code"] == "UNKNOWN_REFERENCE" and
                                        f"/instance/{collection}" in issue["pointer"]
                                        for issue in output["errors"]), output)
