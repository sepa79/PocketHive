"""Public CLI qualification of derived review/resume and focused field views."""
from __future__ import annotations

import json
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class ReviewViewTests(CliTestCase):
    def document_bytes(self, root=None):
        root = self.documents if root is None else root
        return {name: (root / name).read_bytes() for name in DOCUMENT_NAMES}

    def review(self, *extra, stage="draft", expected_exit=0):
        return self.invoke("prepare-review", "--documents", self.documents, "--stage", stage,
                           *extra, expected_exit=expected_exit)

    def show(self, role, pointer, expected_exit=0):
        return self.invoke("show-field", "--documents", self.documents, "--document", role,
                           "--pointer", pointer, expected_exit=expected_exit)

    def questions(self):
        answer = self.workspace / "client-answer.txt"
        answer.write_text("The client supplied the project owner in this answer.\n")
        document = self.read_document("traceability.yaml")
        document["instance"]["questions"] = [
            {"id": "Q-objective", "targets": [{"document": "requirements", "pointer": "/project/objective"}],
             "question": "Which business outcome should this test establish?", "owner": "client",
             "blockingStage": "handoff", "status": "open", "answerRef": None},
            {"id": "Q-owner", "targets": [{"document": "requirements", "pointer": "/project/owner"}],
             "question": "Who owns the project?", "owner": "client", "blockingStage": "handoff",
             "status": "answered", "answerRef": str(answer)},
        ]
        self.write_document("traceability.yaml", document)

    def test_draft_brief_reuses_validator_findings_and_keeps_questions_explicit(self):
        self.initialise()
        self.questions()
        _, output = self.review()
        _, validation = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        for category in ("errors", "gaps", "warnings"):
            self.assertEqual(validation[category], output[category])
            indexes = [index for group in output["brief"]["diagnosticGroups"] for index in group[category]]
            self.assertEqual(list(range(len(validation[category]))), sorted(indexes))
            grouped = [output[category][index] for index in indexes]
            self.assertEqual(sorted(map(json.dumps, validation[category])), sorted(map(json.dumps, grouped)))
        brief = output["brief"]
        self.assertEqual("new-requirements", brief["sourceMode"])
        self.assertIsNone(brief["source"])
        self.assertEqual("draft", brief["stage"])
        self.assertEqual("draft", brief["currentReview"]["status"])
        self.assertEqual(1, brief["answeredQuestionCount"])
        self.assertEqual(["Q-objective"], [row["id"] for row in brief["unansweredQuestions"]])
        self.assertTrue(any(group.get("questionIds") == ["Q-objective"] for group in brief["diagnosticGroups"]))
        # An answered question does not conceal a contradictory missing field.
        self.assertTrue(any(group.get("questionIds") == ["Q-owner"] and group["gaps"]
                            for group in brief["diagnosticGroups"]))
        self.assertEqual(output["documentsSha256"], brief["documentsSha256"])
        self.assertEqual(validation["reviewContentSha256"], brief["reviewContentSha256"])

    def test_repeat_review_preserves_documents_and_answered_questions(self):
        self.initialise()
        self.questions()
        _, first = self.review()
        before = self.document_bytes()
        _, repeated = self.review()
        self.assertEqual(before, self.document_bytes())
        self.assertEqual(first["brief"], repeated["brief"])
        self.assertEqual(["Which business outcome should this test establish?"],
                         self.read_document("requirements.yaml")["openQuestions"])
        self.assertEqual("answered", self.read_document("traceability.yaml")["instance"]["questions"][1]["status"])

    def test_handoff_uses_the_same_incomplete_exit_as_validate(self):
        self.initialise()
        _, output = self.review(stage="handoff", expected_exit=3)
        self.assertEqual("incomplete", output["status"])
        self.assertEqual("handoff", output["brief"]["stage"])
        self.assertTrue(output["gaps"])

    def test_previous_comparison_reports_pointers_without_replaced_values_or_writes(self):
        self.initialise()
        previous = self.workspace / "previous"
        shutil.copytree(self.documents, previous)
        previous_bytes = self.document_bytes(previous)
        document = self.read_document("requirements.yaml")
        canary = "client-business-statement-not-requested-in-a-diff"
        document["project"]["objective"] = canary
        self.write_document("requirements.yaml", document)
        raw, output = self.review("--previous", previous)
        comparison = output["brief"]["comparison"]
        self.assertIn({"document": "requirements", "pointer": "/project/objective", "change": "changed"},
                      comparison["changedFields"])
        self.assertNotEqual(comparison["previousDocumentsSha256"], output["documentsSha256"])
        self.assertEqual(previous_bytes, self.document_bytes(previous))
        self.assertNotIn(canary, raw.stdout + raw.stderr)

    def test_locked_previous_snapshot_is_not_used_as_a_stable_comparison(self):
        self.initialise()
        previous = self.workspace / "previous"
        shutil.copytree(self.documents, previous)
        (previous / ".intake-write.lock").mkdir()
        _, output = self.review("--previous", previous, expected_exit=2)
        self.assertEqual("DOCUMENTS_BUSY", output["errors"][0]["code"])

    def test_field_view_shows_requested_value_schema_and_existing_diagnostics_without_writes(self):
        self.initialise()
        before = self.document_bytes()
        _, output = self.show("requirements", "/project/objective")
        field = output["field"]
        self.assertIsNone(field["value"])
        self.assertEqual("business", field["ownership"]["owner"])
        self.assertTrue(field["ownership"]["editable"])
        self.assertTrue(field["ownership"]["evidenceRequired"])
        self.assertEqual(["string", "null"], field["schemaBranches"][0]["schema"]["type"])
        self.assertNotIn("diagnostics", field)
        self.assertTrue(any(issue["code"] == "REQUIRED_INPUT" for issue in output["gaps"]))
        self.assertEqual(["Q-OBJECTIVE"], [row["id"] for row in field["context"]["questions"]])
        self.assertTrue(all((issue["document"] == "requirements" and issue["pointer"] == "/project/objective")
                            or (issue["document"] == "traceability" and (
                                issue["pointer"] == "/instance/questions/0"
                                or issue["pointer"].startswith("/instance/questions/0/")))
                            for issue in output["gaps"]))
        self.assertGreater(output["validationSummary"]["gapCount"], len(output["gaps"]))
        self.assertEqual(before, self.document_bytes())

    def test_field_view_uses_shared_ownership_for_projections_policy_and_review(self):
        self.initialise()
        for role, pointer, owner in (("requirements", "/openQuestions", "generated"),
                                     ("requirements", "/ownership", "immutable"),
                                     ("requirements", "/requirementId", "administrative"),
                                     ("traceability", "/instance/review", "human-review"),
                                     ("traceability", "/instance/questions", "ledger")):
            with self.subTest(role=role, pointer=pointer):
                _, output = self.show(role, pointer)
                self.assertEqual(owner, output["field"]["ownership"]["owner"])
                self.assertEqual(owner == "ledger", output["field"]["ownership"]["editable"])

    def test_valid_field_view_reports_whole_set_counts_without_unrelated_findings(self):
        self.initialise()
        document = self.read_document("execution-results.yaml")
        document["overallResult"]["result"] = "pass"
        self.write_document("execution-results.yaml", document)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.show("requirements", "/requirementId")
        self.assertEqual("ok", output["status"])
        self.assertEqual([], output["errors"])
        self.assertEqual([], output["gaps"])
        self.assertGreater(output["validationSummary"]["errorCount"], 0)
        self.assertGreater(output["validationSummary"]["gapCount"], 0)
        self.assertTrue(output["warnings"])
        self.assertNotIn("diagnostics", output["field"])

    def test_field_view_retains_correlation_conditions_from_the_schema_owner(self):
        self.initialise()
        _, output = self.show("requirements", "/templates/0/payloadBindings/0/source/type")
        field = output["field"]
        self.assertIn("correlation", field["schemaBranches"][0]["schema"]["enum"])
        context = next(row for row in field["contextConstraints"]
                       if row["documentPointer"] == "/templates/0/payloadBindings/0/source")
        self.assertEqual("correlation", context["constraints"]["allOf"][0]["if"]["properties"]["type"]["const"])

    def test_field_view_resolves_nullable_dynamic_and_opaque_structures_with_exact_escapes(self):
        self.initialise()
        document = self.read_document("requirements.yaml")
        document["suts"][0]["oauth2"] = {"tokenUrl": None, "credentialRef": None}
        document["suts"][0]["endpoints"]["api/internal~1"] = {"kind": "HTTP", "baseUrl": None}
        document["templates"][0]["requestSample"] = {"a/b": ["explicit sample"]}
        self.write_document("requirements.yaml", document)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        before = self.document_bytes()
        for pointer in ("/suts/0/oauth2/tokenUrl", "/suts/0/endpoints/api~1internal~01/baseUrl"):
            with self.subTest(pointer=pointer):
                _, output = self.show("requirements", pointer)
                self.assertEqual(["string", "null"], output["field"]["schemaBranches"][0]["schema"]["type"])
        _, output = self.show("requirements", "/templates/0/requestSample/a~1b/0")
        self.assertEqual("explicit sample", output["field"]["value"])
        self.assertTrue(output["field"]["schemaBranches"][0]["schema"])
        self.assertEqual(before, self.document_bytes())

    def test_field_view_does_not_guess_missing_fields_or_repair_invalid_pointers(self):
        self.initialise()
        before = self.document_bytes()
        for pointer in ("/unknown", "/templates/01", "/project/~2name"):
            with self.subTest(pointer=pointer):
                _, output = self.show("requirements", pointer, expected_exit=2)
                self.assertEqual("POINTER", output["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())
