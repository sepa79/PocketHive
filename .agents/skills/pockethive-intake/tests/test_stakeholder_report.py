"""Public CLI qualification of decision views and deterministic stakeholder projections."""
from test_intake_cli import CliTestCase


class StakeholderReportTests(CliTestCase):
    def review(self, expected_exit=0):
        return self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft",
                           "--write-review", expected_exit=expected_exit)[1]

    def test_report_is_opt_in_idempotent_and_flags_stale_revision(self):
        self.initialise()
        report = self.documents / "stakeholder-review.generated.md"
        self.assertFalse(report.exists())
        first = self.review()
        self.assertEqual("absent", first["report"]["previousState"])
        before = report.read_bytes()
        self.assertIn(first["documentsSha256"].encode(), before)
        repeated = self.review()
        self.assertEqual("current", repeated["report"]["previousState"])
        self.assertEqual(before, report.read_bytes())
        req = self.read_document("requirements.yaml")
        req["project"]["objective"] = "Exercise the journey against WireMock; real service capacity remains unproven."
        self.write_document("requirements.yaml", req)
        changed = self.review()
        self.assertEqual("stale", changed["report"]["previousState"])
        self.assertIn(changed["documentsSha256"].encode(), report.read_bytes())
        self.assertIn(b"unclassified", report.read_bytes())
        self.assertNotEqual("confirmed", self.read_document("traceability.yaml")["instance"]["review"]["status"])

    def test_authored_report_and_tampering_are_not_silently_overwritten(self):
        self.initialise()
        report = self.documents / "stakeholder-review.generated.md"
        report.write_text("An independently authored document")
        output = self.review(expected_exit=2)
        self.assertEqual("REPORT_NOT_GENERATED", output["errors"][0]["code"])
        self.assertEqual(output["documentsSha256"], output["report"]["documentsSha256"])
        self.assertEqual("unverified", output["report"]["persistence"])
        report.rename(self.workspace / "saved-client-report.md")
        self.review()
        report.write_bytes(report.read_bytes() + b"Invented approval\n")
        tampered = report.read_bytes()
        output = self.review(expected_exit=2)
        self.assertEqual("REPORT_EDIT", output["errors"][0]["code"])
        self.assertEqual(tampered, report.read_bytes())

    def test_report_symlink_cannot_overwrite_an_external_document(self):
        self.initialise()
        external = self.workspace / "external.md"
        external.write_text("Preserve client work")
        (self.documents / "stakeholder-review.generated.md").symlink_to(external)
        result = self.review(expected_exit=2)
        self.assertEqual("SYMLINK", result["errors"][0]["code"])
        self.assertEqual("Preserve client work", external.read_text())

    def test_future_decisions_are_separate_and_unassigned_owners_are_preserved(self):
        self.initialise()
        req = self.read_document("requirements.yaml")
        req["templates"][0]["authorization"]["scope"] = None
        self.write_document("requirements.yaml", req)
        trace = self.read_document("traceability.yaml")
        trace["instance"]["questions"][0]["blockingStage"] = "execution"
        self.write_document("traceability.yaml", trace)
        output = self.review()
        decisions = output["brief"]["decisions"]
        self.assertEqual(1, decisions["executionDecisionCount"])
        self.assertEqual(2, decisions["currentDecisionCount"])
        self.assertIsNone(decisions["execution"][0]["owner"])
        self.assertEqual(3, len(decisions["unassignedQuestionIds"]))
        self.assertTrue(decisions["engineeringTriageGroupIndexes"])
        scope = next(row for row in output["brief"]["blankFields"]["fields"]
                     if row["target"] == {"document": "requirements", "pointer": "/templates/0/authorization/scope"})
        self.assertEqual("unexplained", scope["state"])
        self.assertEqual([], scope.get("diagnostics", {}).get("gaps", []))

    def test_report_refreshes_when_source_validation_changes_at_same_document_revision(self):
        import shutil
        from test_population import HTTP_FIXTURE

        source = self.workspace / "source"
        shutil.copytree(HTTP_FIXTURE, source)
        self.initialise("from-bundle", source)
        first = self.review()
        path = source / "scenario.yaml"
        path.write_text(path.read_text() + "\n# changed source evidence\n")
        second = self.review(expected_exit=2)
        self.assertEqual(first["documentsSha256"], second["documentsSha256"])
        self.assertEqual("stale", second["report"]["previousState"])
        self.assertEqual("verified", second["report"]["persistence"])
        self.assertTrue(any(row["code"] == "SOURCE_HASH" for row in second["errors"]))
        self.assertFalse(any(row["code"] == "REPORT_EDIT" for row in second["errors"]))
