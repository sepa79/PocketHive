"""Public CLI regressions for evidence-linked edits and preservation of client work."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, FIXTURE


class SourcedUpdateTests(CliTestCase):
    def setUp(self):
        super().setUp()
        _, initial = self.initialise()
        self.revision = initial["documentsSha256"]
        self.source = self.workspace / "client-answer.txt"
        self.source.write_text("Demonstrate checkout completion under the agreed load.\n", encoding="utf-8")

    def update(self, pointer="/project/objective", value="Demonstrate checkout completion under the agreed load."):
        target = {"document": "requirements", "pointer": pointer}
        return {"target": target, "value": value, "provenance": {
            "target": target, "kind": "client-statement",
            "sources": [{"artifactRef": str(self.source), "pointer": None,
                         "sha256": hashlib.sha256(self.source.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None}}

    def batch(self, updates, *, revision=None):
        path = self.workspace / "explicit-updates.json"
        path.write_text(json.dumps({"expectedDocumentsSha256": revision or self.revision,
                                   "updates": updates}), encoding="utf-8")
        return self.invoke("apply-updates", "--documents", self.documents, "--input", path)

    def snapshot(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def test_sourced_edit_updates_fact_and_evidence_without_answering_questions(self):
        before_questions = self.read_document("traceability.yaml")["instance"]["questions"]
        result, output = self.batch([self.update()])
        self.assertEqual(0, result.returncode, output)
        self.assertTrue(output["applied"])
        self.assertEqual("incomplete", output["status"])
        self.assertNotEqual(self.revision, output["documentsSha256"])
        self.assertEqual(self.update()["value"], self.read_document("requirements.yaml")["project"]["objective"])
        instance = self.read_document("traceability.yaml")["instance"]
        self.assertEqual(before_questions, instance["questions"])
        self.assertEqual([self.update()["provenance"]], instance["provenance"])
        self.assertNotEqual("confirmed", instance["review"]["status"])
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)

    def test_exact_evidence_is_replaced_without_accumulating_old_records(self):
        _, first = self.batch([self.update()])
        self.source.write_text("Measure checkout recovery.\n", encoding="utf-8")
        second = self.update(value="Measure checkout recovery.")
        result, output = self.batch([second], revision=first["documentsSha256"])
        self.assertEqual(0, result.returncode, output)
        self.assertEqual([second["provenance"]], self.read_document("traceability.yaml")["instance"]["provenance"])

    def test_stale_batch_does_not_overwrite_manual_edit(self):
        requirements = self.read_document("requirements.yaml")
        requirements["project"]["objective"] = "Client edit to preserve"
        self.write_document("requirements.yaml", requirements)
        before = self.snapshot()
        result, output = self.batch([self.update()])
        self.assertEqual(2, result.returncode)
        self.assertEqual("STALE_DOCUMENTS", output["errors"][0]["code"])
        self.assertEqual(before, self.snapshot())

    def test_invalid_evidence_rejects_complete_batch_without_writes(self):
        for mode in ("missing", "changed", "remote", "empty", "pointer"):
            with self.subTest(mode=mode):
                update = self.update()
                source = update["provenance"]["sources"][0]
                if mode == "missing":
                    source["artifactRef"] = str(self.workspace / "missing.txt")
                elif mode == "changed":
                    source["sha256"] = "0" * 64
                elif mode == "remote":
                    source["artifactRef"] = "https://invalid.example/client-answer"
                elif mode == "empty":
                    update["provenance"]["sources"] = []
                else:
                    source["pointer"] = "/not-a-structured-source"
                before = self.snapshot()
                result, output = self.batch([update])
                self.assertEqual(2, result.returncode, output)
                self.assertFalse(output.get("applied", False))
                self.assertEqual(before, self.snapshot())

    def test_no_evidence_and_wrong_target_are_rejected(self):
        for evidence in (None, {}, self.update("/project/name")["provenance"]):
            with self.subTest(evidence=evidence):
                update = self.update()
                update["provenance"] = evidence
                before = self.snapshot()
                result, output = self.batch([update])
                self.assertEqual(2, result.returncode, output)
                self.assertEqual("UPDATE_EVIDENCE", output["errors"][0]["code"])
                self.assertEqual(before, self.snapshot())

    def test_generated_policy_approval_and_identity_edits_are_blocked(self):
        for role, pointer in (("requirements", "/openQuestions"), ("requirements", "/ownership"),
                              ("requirements", "/requirementId"), ("plan", "/approval"),
                              ("plan", "/generation"), ("traceability", "/instance/review"),
                              ("traceability", "/instance/provenance")):
            with self.subTest(role=role, pointer=pointer):
                update = {"target": {"document": role, "pointer": pointer}, "value": None, "provenance": None}
                before = self.snapshot()
                result, output = self.batch([update])
                self.assertEqual(2, result.returncode)
                self.assertEqual("UPDATE_PROTECTED", output["errors"][0]["code"])
                self.assertEqual(before, self.snapshot())

    def test_duplicate_overlapping_and_missing_targets_are_rejected(self):
        cases = [([self.update(), self.update()], "UPDATE_OVERLAP"),
                 ([self.update("/project", {}), self.update()], "UPDATE_OVERLAP"),
                 ([self.update("/project/absent")], "POINTER")]
        for updates, code in cases:
            with self.subTest(code=code):
                before = self.snapshot()
                result, output = self.batch(updates)
                self.assertEqual(2, result.returncode)
                self.assertEqual(code, output["errors"][0]["code"])
                self.assertEqual(before, self.snapshot())

    def test_unknown_fields_and_invalid_values_are_not_silently_coerced(self):
        for mode in ("unknown", "schema", "empty-batch"):
            with self.subTest(mode=mode):
                update = self.update()
                if mode == "unknown":
                    update["guessMissingValues"] = True
                elif mode == "schema":
                    update["value"] = {"unexpected": "object"}
                before = self.snapshot()
                result, output = self.batch([] if mode == "empty-batch" else [update])
                self.assertEqual(2, result.returncode, output)
                self.assertEqual(before, self.snapshot())

    def test_broader_provenance_cannot_remain_attached_to_changed_fact(self):
        provenance = self.update("/project", {"name": None, "objective": None})["provenance"]
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["provenance"] = [provenance]
        self.write_document("traceability.yaml", traceability)
        _, finalised = self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        before = self.snapshot()
        result, output = self.batch([self.update()], revision=finalised["documentsSha256"])
        self.assertEqual(2, result.returncode)
        self.assertEqual("PROVENANCE_OVERLAP", output["errors"][0]["code"])
        self.assertEqual(before, self.snapshot())

    def test_explicit_answer_can_share_fact_evidence_and_preserve_remaining_questions(self):
        questions = deepcopy(self.read_document("traceability.yaml")["instance"]["questions"])
        questions[0].update({"status": "answered", "answerRef": str(self.source)})
        change = {"target": {"document": "traceability", "pointer": "/instance/questions"},
                  "value": questions, "provenance": None}
        result, output = self.batch([self.update(), change])
        self.assertEqual(0, result.returncode, output)
        self.assertEqual(questions, self.read_document("traceability.yaml")["instance"]["questions"])
        projected = self.read_document("requirements.yaml")["openQuestions"]
        self.assertEqual([q["question"] for q in questions[1:]], projected)

    def test_document_revision_detects_formatting_changes(self):
        target = self.documents / "requirements.yaml"
        with target.open("a", encoding="utf-8") as handle:
            handle.write("\n# client review note\n")
        result, output = self.batch([self.update()])
        self.assertEqual(2, result.returncode)
        self.assertEqual("STALE_DOCUMENTS", output["errors"][0]["code"])

    def test_answer_without_evidence_is_rejected_before_saving(self):
        questions = deepcopy(self.read_document("traceability.yaml")["instance"]["questions"])
        questions[0]["status"] = "answered"
        before = self.snapshot()
        result, output = self.batch([{"target": {"document": "traceability", "pointer": "/instance/questions"},
                                      "value": questions, "provenance": None}])
        self.assertEqual(2, result.returncode)
        self.assertTrue(any(issue["code"] == "ANSWER_EVIDENCE" for issue in output["errors"]))
        self.assertEqual(before, self.snapshot())

    def test_status_only_answer_edit_checks_sibling_evidence(self):
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["questions"][0]["answerRef"] = str(self.workspace / "unverified-answer.txt")
        self.write_document("traceability.yaml", traceability)
        _, revision = self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        before = self.snapshot()
        update = {"target": {"document": "traceability", "pointer": "/instance/questions/0/status"},
                  "value": "answered", "provenance": None}
        result, output = self.batch([update], revision=revision["documentsSha256"])
        self.assertEqual(2, result.returncode, output)
        self.assertTrue(any(issue["code"] == "ANSWER_EVIDENCE" for issue in output["errors"]))
        self.assertEqual(before, self.snapshot())


class SourceIdentityUpdateTests(CliTestCase):
    def test_bundle_source_cannot_be_cleared_or_changed_to_narrative_kind(self):
        _, initial = self.initialise(mode="from-bundle", source=FIXTURE)
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        narrative = self.workspace / "narrative.txt"
        narrative.write_text("Synthetic source", encoding="utf-8")
        for source in (None, {"kind": "file", "artifactRef": str(narrative),
                              "sha256": hashlib.sha256(narrative.read_bytes()).hexdigest()}):
            with self.subTest(source=source):
                batch = {"expectedDocumentsSha256": initial["documentsSha256"], "updates": [{
                    "target": {"document": "traceability", "pointer": "/instance/intake/source"},
                    "value": source, "provenance": None}]}
                path = self.workspace / "source-update.json"
                path.write_text(json.dumps(batch), encoding="utf-8")
                _, output = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=2)
                self.assertTrue(any(issue["code"] == "INTAKE_SOURCE_MODE" for issue in output["errors"]))
                self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_manual_source_removal_is_also_detected_by_canonical_validation(self):
        self.initialise(mode="from-bundle", source=FIXTURE)
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["intake"]["source"] = None
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any(issue["code"] == "INTAKE_SOURCE_MODE" for issue in output["errors"]))

    def test_explicit_relative_bundle_identity_resolves_from_documents(self):
        import os
        _, initial = self.initialise(mode="from-bundle", source=FIXTURE)
        source = deepcopy(self.read_document("traceability.yaml")["instance"]["intake"]["source"])
        source["artifactRef"] = os.path.relpath(FIXTURE, self.documents)
        batch = {"expectedDocumentsSha256": initial["documentsSha256"], "updates": [{
            "target": {"document": "traceability", "pointer": "/instance/intake/source"},
            "value": source, "provenance": None}]}
        path = self.workspace / "source-update.json"
        path.write_text(json.dumps(batch), encoding="utf-8")
        _, output = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=0)
        self.assertFalse(output["errors"])
        self.assertEqual(source, self.read_document("traceability.yaml")["instance"]["intake"]["source"])
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
