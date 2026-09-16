"""Public-CLI safeguards supporting the separate synthetic conversation cases.

These tests author explicit fixture inputs; they do not simulate or qualify an
agent's source interpretation, interview quality or performance judgement.
"""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
import shutil

from complete_intake import prepare_complete_intake
from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


EVALUATION_SOURCES = PACKAGE / "tests" / "evaluations" / "sources"


class BehaviouralSafeguardTests(CliTestCase):
    def test_rate_counterfactual_changes_source_identity_without_promoting_acceptance(self):
        source_revisions = []
        for rate in (100, 250):
            with self.subTest(configured_rate=rate):
                source = self.workspace / f"bundle-{rate}"
                shutil.copytree(EVALUATION_SOURCES / "rate-bundle", source)
                scenario = source / "scenario.yaml"
                scenario.write_text(scenario.read_text().replace("ratePerSec: 100", f"ratePerSec: {rate}"))
                self.documents = self.workspace / f"documents-{rate}"
                self.initialise("from-bundle", source)
                self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
                inspection = json.loads((self.documents / "source-inspection.json").read_text())
                source_revisions.append(inspection["sha256"])
                inventory = {record["path"]: record for record in inspection["files"]}
                self.assertEqual(hashlib.sha256(scenario.read_bytes()).hexdigest(), inventory["scenario.yaml"]["sha256"])
                requirements = self.read_document("requirements.yaml")
                self.assertEqual("GET", requirements["templates"][0]["method"])
                self.assertIsNone(requirements["kpis"][0]["targetTps"])
                self.assertIsNone(self.read_document("test-plan.yaml")["sutId"])
                traceability = self.read_document("traceability.yaml")["instance"]
                self.assertEqual("draft", traceability["review"]["status"])
                self.assertFalse(any(record["kind"] == "approval" for record in traceability["provenance"]))
                _, handoff = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
                self.assertTrue(handoff["gaps"])
        self.assertEqual(2, len(set(source_revisions)))

    def test_partial_answers_survive_repeated_finalisation_and_leave_one_projection_question(self):
        source = self.workspace / "explicit-partial-answer.json"
        source.write_text(json.dumps({
            "owner": "Avery, Payments QA",
            "objective": "Assess completed payment processing.",
            "targets": None,
            "approval": "No plan approval supplied.",
        }))
        self.initialise(source=source)
        requirements = self.read_document("requirements.yaml")
        requirements["project"].update({
            "owner": "Avery, Payments QA", "objective": "Assess completed payment processing.",
        })
        self.write_document("requirements.yaml", requirements)
        trace = self.read_document("traceability.yaml")
        trace["instance"]["questions"] = [{
            "id": question_id,
            "targets": [{"document": "requirements", "pointer": pointer}],
            "question": question,
            "owner": None,
            "blockingStage": "handoff",
            "status": status,
            "answerRef": str(source) if status == "answered" else None,
        } for question_id, pointer, question, status in (
            ("Q-OWNER", "/project/owner", "Who owns the requirements?", "answered"),
            ("Q-OBJECTIVE", "/project/objective", "What should the test assess?", "answered"),
            ("Q-TARGET", "/kpis/0/targetTps", "What completed-payment target applies?", "open"),
        )]
        trace["instance"]["provenance"] = [{
            "target": {"document": "requirements", "pointer": "/project/" + field},
            "kind": "client-statement",
            "sources": [{"artifactRef": str(source), "pointer": "/" + field,
                         "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}],
            "confirmationRef": None,
            "calculation": None,
        } for field in ("owner", "objective")]
        self.write_document("traceability.yaml", trace)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        # A second command starts a fresh process, using only the saved files.
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        retained = self.read_document("requirements.yaml")
        self.assertEqual(["What completed-payment target applies?"], retained["openQuestions"])
        self.assertEqual("Avery, Payments QA", retained["project"]["owner"])
        self.assertIsNone(retained["kpis"][0]["targetTps"])
        self.assertEqual("draft", self.read_document("traceability.yaml")["instance"]["review"]["status"])
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertFalse(output["errors"])
        self.assertTrue(output["gaps"])

    def test_embedded_source_instructions_cannot_change_cli_approval_or_execution_state(self):
        source = self.workspace / "untrusted-bundle"
        shutil.copytree(EVALUATION_SOURCES / "rate-bundle", source)
        shutil.copyfile(EVALUATION_SOURCES / "untrusted-notes.md", source / "README.md")
        before = {path.relative_to(source): path.read_bytes() for path in source.rglob("*") if path.is_file()}
        self.initialise("from-bundle", source)
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.assertEqual(before, {path.relative_to(source): path.read_bytes() for path in source.rglob("*") if path.is_file()})
        self.assertFalse(list(self.workspace.rglob("INTAKE_SOURCE_EXECUTED")))
        self.assertEqual("draft", self.read_document("traceability.yaml")["instance"]["review"]["status"])
        self.assertNotEqual("approved", self.read_document("test-plan.yaml")["approval"]["status"])
        results = self.read_document("execution-results.yaml")
        self.assertEqual("not-run", results["runInfo"]["executionStatus"])
        self.assertEqual("not-run", results["overallResult"]["result"])
        self.assertIsNone(self.read_document("requirements.yaml")["kpis"][0]["targetTps"])
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        self.assertTrue(output["gaps"])

    def test_wording_edit_preserves_structured_choices_without_reapproving_changed_content(self):
        prepare_complete_intake(self)
        plan_before = deepcopy(self.read_document("test-plan.yaml"))
        review_before = deepcopy(self.read_document("traceability.yaml")["instance"]["review"])
        requirements = self.read_document("requirements.yaml")
        requirements["project"]["objective"] = "Observe ten successful bodyless reads during the selected two-second test."
        self.write_document("requirements.yaml", requirements)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        plan_after = self.read_document("test-plan.yaml")
        for key in ("executionModel", "apiExecution", "acceptanceCriteria", "workloadDelivery", "safety", "approval"):
            self.assertEqual(plan_before[key], plan_after[key], key)
        self.assertEqual(review_before, self.read_document("traceability.yaml")["instance"]["review"])
        self.assertEqual("not-run", self.read_document("execution-results.yaml")["runInfo"]["executionStatus"])
        result, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff")
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(any("review" in issue["pointer"].lower() for issue in output["errors"] + output["gaps"]), output)
