"""Public CLI regressions for concise reviews without hidden outcome changes."""
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class ReviewSummaryTests(CliTestCase):
    def test_missing_summary_module_still_reports_the_integrity_error_as_json(self):
        import shutil
        self.initialise()
        package = self.workspace / "damaged-package"
        shutil.copytree(PACKAGE, package)
        (package / "scripts/intake_lib/review_summary.py").rename(self.workspace / "preserved-summary.py")
        _, output = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft",
                                "--view", "summary", package=package, expected_exit=2)
        self.assertEqual("error", output["status"])
        self.assertNotIn("brief", output)
        self.assertTrue(output["errors"])
        self.assertFalse(any(row["code"] == "COMMAND_FAILED" for row in output["errors"]))

    def test_summary_preserves_outcomes_decisions_and_documents_at_both_stages(self):
        self.initialise()
        for stage, exit_code in (("draft", 0), ("handoff", 3)):
            with self.subTest(stage=stage):
                args = ("prepare-review", "--documents", self.documents, "--stage", stage, "--write-review")
                _, full = self.invoke(*args, expected_exit=exit_code)
                before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
                report = (self.documents / "stakeholder-review.generated.md").read_bytes()
                _, summary = self.invoke(*args, "--view", "summary", expected_exit=exit_code)
                self.assertEqual(full["status"], summary["status"])
                self.assertEqual(full["errors"], summary["errors"])
                self.assertEqual(full["documentsSha256"], summary["documentsSha256"])
                for key in ("currentReview", "unansweredQuestions", "proposals", "reviewContentSha256"):
                    self.assertEqual(full["brief"][key], summary["brief"][key])
                self.assertEqual({key: len(full[key]) for key in ("errors", "gaps", "warnings")},
                                 summary["diagnostics"]["counts"])
                self.assertEqual(full["brief"]["blankFields"]["counts"], summary["brief"]["blankFields"]["counts"])
                self.assertNotIn("gaps", summary)
                self.assertNotIn("fields", summary["brief"]["blankFields"])
                self.assertNotIn("diagnosticGroups", summary["brief"])
                self.assertNotIn("engineeringTriageGroupIndexes", summary["brief"]["decisions"])
                for group in summary["diagnostics"]["groups"]:
                    self.assertFalse(set(group) & {"errors", "gaps", "warnings"})
                self.assertLess(len(json.dumps(summary)), len(json.dumps(full)))
                self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
                self.assertEqual(report, (self.documents / "stakeholder-review.generated.md").read_bytes())

    def test_summary_retains_report_failures_and_successful_document_save(self):
        self.initialise()
        path = self.documents / "stakeholder-review.generated.md"
        path.write_text("Client-owned notes")
        _, output = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft",
                                "--view", "summary", "--write-review", expected_exit=2)
        self.assertEqual("error", output["status"])
        self.assertEqual("REPORT_NOT_GENERATED", output["errors"][0]["code"])
        self.assertEqual(1, output["diagnostics"]["counts"]["errors"])
        self.assertEqual("unverified", output["report"]["persistence"])
        self.assertTrue(output["documentsSha256"])
        self.assertEqual("Client-owned notes", path.read_text())

    def test_summary_exposes_structure_errors_without_inventing_a_brief(self):
        self.initialise()
        plan = self.read_document("test-plan.yaml")
        plan["executionModel"]["rateUnit"] = "fifteen-ish"
        self.write_document("test-plan.yaml", plan)
        _, output = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft",
                                "--view", "summary", expected_exit=2)
        self.assertTrue(output["errors"])
        self.assertNotIn("brief", output)
        self.assertEqual(len(output["errors"]), output["diagnostics"]["counts"]["errors"])
