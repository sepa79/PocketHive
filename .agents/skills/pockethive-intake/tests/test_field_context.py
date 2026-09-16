"""Public CLI qualification of bounded, read-only field evidence context."""
from __future__ import annotations

import hashlib
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class FieldContextTests(CliTestCase):
    def setUp(self):
        super().setUp()
        self.initialise()

    def show(self, role, pointer, expected_exit=0):
        return self.invoke("show-field", "--documents", self.documents, "--document", role,
                           "--pointer", pointer, expected_exit=expected_exit)

    def save_ledger(self, *, provenance=(), questions=(), proposals=()):
        traceability = self.read_document("traceability.yaml")
        traceability["instance"].update(provenance=list(provenance), questions=list(questions),
                                          proposals=list(proposals))
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)

    @staticmethod
    def question(identifier, target):
        return {"id": identifier, "targets": [target], "question": identifier, "owner": "client",
                "blockingStage": "handoff", "status": "open", "answerRef": None}

    @staticmethod
    def proposal(identifier, target, sources=()):
        return {"id": identifier, "targets": [target], "rationale": identifier, "sources": list(sources),
                "unresolvedPremises": [], "status": "proposed", "decisionRef": None}

    @staticmethod
    def provenance(target, sources=()):
        return {"target": target, "kind": "client-statement", "sources": list(sources),
                "confirmationRef": None, "calculation": None}

    def test_context_uses_exact_role_and_pointer_ancestry_including_escaped_segments(self):
        requirements = self.read_document("requirements.yaml")
        requirements["templates"][0]["requestSample"] = {"a/b": {"value": None}, "a/bc": None}
        self.write_document("requirements.yaml", requirements)
        pointer = "/templates/0/requestSample/a~1b"
        targets = [
            {"document": "requirements", "pointer": pointer},
            {"document": "requirements", "pointer": "/templates/0/requestSample"},
            {"document": "requirements", "pointer": pointer + "/value"},
            {"document": "requirements", "pointer": pointer + "c"},
            {"document": "plan", "pointer": pointer},
            {"document": "requirements", "pointer": None},
        ]
        questions = [self.question(f"Q-{index}", target) for index, target in enumerate(targets)]
        proposals = [self.proposal(f"P-{index}", target) for index, target in enumerate(targets)]
        provenance = [self.provenance(target) for target in targets[:5]]
        self.save_ledger(provenance=provenance, questions=questions, proposals=proposals)
        raw, output = self.show("requirements", pointer)
        self.assertEqual({"provenance": provenance[:3], "questions": questions[:3], "proposals": proposals[:3]},
                         output["field"]["context"])
        for identifier in ("Q-3", "Q-4", "Q-5", "P-3", "P-4", "P-5"):
            self.assertNotIn(identifier, raw.stdout + raw.stderr)
        self.assertEqual({"value": None}, output["field"]["value"])

    def test_linked_diagnostics_reuse_validator_entries_once_without_document_writes(self):
        target = {"document": "requirements", "pointer": "/project/objective"}
        unrelated = {"document": "requirements", "pointer": "/project/owner"}
        source = self.workspace / "client-evidence.txt"
        source.write_text("Source content must not appear in a field context.\n", encoding="utf-8")
        reference = {"artifactRef": str(source), "pointer": None, "sha256": "0" * 64}
        self.save_ledger(
            provenance=[self.provenance(target, [reference]), self.provenance(unrelated)],
            questions=[self.question("Q-objective", target), self.question("Q-owner", unrelated)],
            proposals=[self.proposal("P-objective", target, [reference]), self.proposal("P-owner", unrelated)],
        )
        _, validation = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        raw, output = self.show("requirements", "/project/objective", expected_exit=2)
        row_pointers = [f"/instance/{name}/0" for name in ("provenance", "questions", "proposals")]
        for category in ("errors", "gaps", "warnings"):
            expected = [issue for issue in validation[category] if isinstance(issue, str) or (
                issue["document"] == "requirements" and issue["pointer"] == "/project/objective") or (
                issue["document"] == "traceability" and any(issue["pointer"] == pointer or
                    issue["pointer"].startswith(pointer + "/") for pointer in row_pointers))]
            self.assertEqual(expected, output[category])
        self.assertEqual(2, sum(issue["code"] == "SOURCE_HASH" for issue in output["errors"]))
        self.assertEqual(len(output["gaps"]), len({json.dumps(issue, sort_keys=True) for issue in output["gaps"]}))
        self.assertEqual({"errorCount": len(validation["errors"]), "gapCount": len(validation["gaps"]),
                          "warningCount": len(validation["warnings"])}, output["validationSummary"])
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        self.assertNotIn(source.read_text(encoding="utf-8").strip(), raw.stdout + raw.stderr)
        self.assertNotIn("Q-owner", raw.stdout + raw.stderr)
        self.assertNotIn("P-owner", raw.stdout + raw.stderr)

    def test_direct_ledger_fields_include_own_record_without_following_its_business_targets(self):
        target = {"document": "requirements", "pointer": "/project/objective"}
        questions = [self.question("Q-requested", target), self.question("Q-unrelated", target)]
        proposals = [self.proposal("P-requested", target), self.proposal("P-unrelated", target)]
        self.save_ledger(questions=questions, proposals=proposals)
        _, question_output = self.show("traceability", "/instance/questions/0/status")
        self.assertEqual({"provenance": [], "questions": questions[:1], "proposals": []},
                         question_output["field"]["context"])
        self.assertEqual("open", question_output["field"]["value"])
        self.assertEqual(1, sum(issue["code"] == "OPEN_QUESTION" for issue in question_output["gaps"]))
        _, proposal_output = self.show("traceability", "/instance/proposals/0")
        self.assertEqual({"provenance": [], "questions": [], "proposals": proposals[:1]},
                         proposal_output["field"]["context"])
        self.assertEqual(proposals[0], proposal_output["field"]["value"])
        self.assertEqual(1, sum(issue["code"] == "UNADOPTED_PROPOSAL" for issue in proposal_output["gaps"]))

    def test_null_scaffold_targets_are_not_guessed_and_explicit_statuses_are_preserved(self):
        question = self.question("Q-unscoped", {"document": None, "pointer": None})
        proposal = self.proposal("P-unscoped", {"document": "requirements", "pointer": None})
        proposal["status"] = "rejected"
        self.save_ledger(questions=[question], proposals=[proposal])
        _, unrelated = self.show("requirements", "/project/objective")
        self.assertEqual({"provenance": [], "questions": [], "proposals": []}, unrelated["field"]["context"])
        _, own_question = self.show("traceability", "/instance/questions/0/status")
        self.assertEqual([question], own_question["field"]["context"]["questions"])
        _, own_proposal = self.show("traceability", "/instance/proposals/0/status")
        self.assertEqual([proposal], own_proposal["field"]["context"]["proposals"])
        self.assertEqual("rejected", own_proposal["field"]["value"])

    def test_context_keeps_recorded_references_without_source_excerpts_or_verification_labels(self):
        target = {"document": "requirements", "pointer": "/project/objective"}
        source = self.workspace / "private-client-evidence.txt"
        canary = "PRIVATE-SOURCE-CONTENT-NOT-A-REQUESTED-FIELD"
        source.write_text(canary, encoding="utf-8")
        reference = {"artifactRef": str(source), "pointer": None,
                     "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
        record = self.provenance(target, [reference])
        self.save_ledger(provenance=[record])
        raw, output = self.show("requirements", "/project/objective")
        self.assertEqual([record], output["field"]["context"]["provenance"])
        self.assertNotIn(canary, raw.stdout + raw.stderr)
        self.assertNotIn("verified", output["field"]["context"]["provenance"][0])
