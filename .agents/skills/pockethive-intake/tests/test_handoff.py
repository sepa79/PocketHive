"""Positive and invalidated-review checks for the published intake CLI."""

from complete_intake import prepare_complete_intake
from test_intake_cli import CliTestCase


class HandoffTests(CliTestCase):
    def test_complete_review_can_pass_without_production_context_or_run_evidence(self):
        prepare_complete_intake(self)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=0)
        self.assertEqual("ok", output["status"], output)
        self.assertFalse(output["errors"])
        self.assertFalse(output["gaps"])
        requirements = self.read_document("requirements.yaml")
        results = self.read_document("execution-results.yaml")
        plan = self.read_document("test-plan.yaml")
        self.assertIsNone(requirements["productionUsage"]["available"])
        self.assertEqual([], requirements["templates"][0]["payloadBindings"])
        self.assertEqual(0, plan["apiExecution"][0]["runtime"]["retries"])
        self.assertFalse(requirements["templates"][0]["idempotency"]["required"])
        self.assertEqual("not-run", results["runInfo"]["executionStatus"])
        self.assertEqual("not-run", results["overallResult"]["result"])
        self.assertIsNone(results["runInfo"]["startTime"])

    def test_refreshed_hashes_do_not_reconfirm_a_changed_plan(self):
        prepare_complete_intake(self)
        self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=0)
        plan = self.read_document("test-plan.yaml")
        plan["apiExecution"][0]["runtime"]["timeoutMs"] = 2000
        self.write_document("test-plan.yaml", plan)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        result, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff")
        self.assertNotEqual(0, result.returncode)
        self.assertNotEqual("ok", output["status"])
        self.assertTrue(any("review" in issue["pointer"].lower() for issue in output["errors"] + output["gaps"]),
                        output["errors"] + output["gaps"])
