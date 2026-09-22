"""QA review regressions for existing semantic gates through the public CLI."""

from test_intake_cli import CliTestCase
import test_measurements as measurement_fixtures
import test_references as reference_fixtures


class ExistingQaGatesTests(CliTestCase):
    partial_references = reference_fixtures.ReferenceTests.partial_references
    finalise_and_validate = reference_fixtures.ReferenceTests.finalise_and_validate
    participating_data = measurement_fixtures.MeasurementTests.participating_data

    def test_finalise_success_does_not_replace_draft_or_handoff_validation(self):
        self.initialise()
        _, finalised = self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.assertFalse(finalised["errors"])
        _, draft = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        _, handoff = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        self.assertEqual("incomplete", draft["status"])
        self.assertEqual("incomplete", handoff["status"])
        self.assertTrue(draft["gaps"])
        self.assertEqual(draft["gaps"], handoff["gaps"])

    def test_used_data_requires_usage_decisions_and_explicit_reset_responsibility(self):
        requirements, _ = self.participating_data()
        entity = requirements["testData"]["entities"][0]
        entity["owner"] = None
        entity["usage"] = {
            "reuseAllowed": None,
            "maxConcurrentRequestsPerRecord": None,
            "onExhaustion": None,
        }
        entity["sideEffects"].update({"resetRequired": True, "resetInstructions": None, "owner": None})
        self.write_document("requirements.yaml", requirements)
        draft = self.finalise_and_validate()
        _, handoff = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        expected = {
            "/testData/entities/0/owner",
            "/testData/entities/0/usage/reuseAllowed",
            "/testData/entities/0/usage/maxConcurrentRequestsPerRecord",
            "/testData/entities/0/usage/onExhaustion",
            "/testData/entities/0/sideEffects/resetInstructions",
            "/testData/entities/0/sideEffects/owner",
        }
        for output in (draft, handoff):
            actual = {item["pointer"] for item in output["gaps"] if item["code"] == "REQUIRED_INPUT"}
            self.assertTrue(expected.issubset(actual), actual)

    def test_explicit_false_data_decisions_are_complete_without_reset_instructions(self):
        requirements, _ = self.participating_data()
        entity = requirements["testData"]["entities"][0]
        entity["owner"] = "Synthetic data owner"
        entity["sideEffects"].update({"mutatesState": False, "resetRequired": False})
        self.write_document("requirements.yaml", requirements)
        output = self.finalise_and_validate()
        self.assertFalse([
            issue for issue in output["gaps"]
            if issue["code"] == "REQUIRED_INPUT" and issue["pointer"].startswith("/testData/entities/0/")
        ])

    def test_unused_data_does_not_create_required_usage_decisions(self):
        requirements, plan = self.participating_data()
        requirements["testData"]["entities"][0]["usage"] = {
            "reuseAllowed": None,
            "maxConcurrentRequestsPerRecord": None,
            "onExhaustion": None,
        }
        plan["apiExecution"][0].update({"mode": "excluded", "reason": "Explicitly excluded synthetic API"})
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate()
        self.assertFalse([
            issue for issue in output["gaps"]
            if issue["code"] == "REQUIRED_INPUT" and issue["pointer"].startswith("/testData/entities/0/")
        ])

    def test_retry_policy_requires_idempotency_decision_without_guessing_from_http_method(self):
        requirements, plan = self.partial_references()
        requirements["templates"][0]["idempotency"].update({"required": None, "rule": None})
        self.write_document("requirements.yaml", requirements)
        expected = {"/templates/0/idempotency/required", "/templates/0/idempotency/rule"}
        for retries in (0, 1):
            with self.subTest(retries=retries):
                plan["apiExecution"][0]["runtime"]["retries"] = retries
                self.write_document("test-plan.yaml", plan)
                output = self.finalise_and_validate()
                actual = {
                    issue["pointer"] for issue in output["gaps"]
                    if issue["code"] == "REQUIRED_INPUT" and "/idempotency/" in issue["pointer"]
                }
                self.assertEqual(expected if retries else set(), actual)

    def test_empty_kpi_target_is_a_gap_only_for_its_selected_participating_scope(self):
        requirements, plan = self.partial_references()
        kpi = requirements["kpis"][0]
        kpi.update({"apiRef": "API-READ", "targetTps": None, "maxResponseTimeMs": None})
        for sut, mode, required in (("selected-sut", "load", True),
                                    ("other-sut", "load", False),
                                    ("selected-sut", "excluded", False)):
            with self.subTest(sut=sut, mode=mode):
                kpi["sutRef"] = sut
                plan["apiExecution"][0].update({"mode": mode, "reason": "Synthetic scope fixture"})
                self.write_document("requirements.yaml", requirements)
                self.write_document("test-plan.yaml", plan)
                output = self.finalise_and_validate()
                self.assertEqual(required, any(issue["code"] == "KPI_TARGET" for issue in output["gaps"]))
                self.assertFalse(any(issue["pointer"].startswith("/productionUsage") for issue in output["gaps"]))

    def test_smoke_check_uses_existing_evidence_owner_without_claiming_load_execution(self):
        self.partial_references()
        results = self.read_document("execution-results.yaml")
        results["preRunChecks"] = [{
            "check": "Synthetic WireMock smoke prerequisite",
            "status": "pass",
            "observed": "Synthetic smoke fixture completed",
            "evidenceRef": None,
        }]
        self.write_document("execution-results.yaml", results)
        output = self.finalise_and_validate(expected_exit=2)
        self.assertTrue(any(issue["code"] == "RESULT_EVIDENCE" and issue["pointer"] == "/preRunChecks/0/evidenceRef"
                            for issue in output["errors"]), output["errors"])
        evidence = self.workspace / "synthetic-smoke-evidence.txt"
        evidence.write_text("Synthetic fixture only; no service was contacted.\n", encoding="utf-8")
        results["preRunChecks"][0]["evidenceRef"] = str(evidence)
        self.write_document("execution-results.yaml", results)
        output = self.finalise_and_validate()
        self.assertFalse(output["errors"])
        retained = self.read_document("execution-results.yaml")
        self.assertEqual("not-run", retained["runInfo"]["executionStatus"])
        self.assertEqual("not-run", retained["overallResult"]["result"])
        self.assertTrue(output["gaps"], "Naming synthetic evidence alone must not complete intake.")
