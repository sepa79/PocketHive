"""Public CLI qualification of decision views and deterministic stakeholder projections."""
from test_intake_cli import CliTestCase


class StakeholderReportTests(CliTestCase):
    def test_blank_scaffold_is_hidden_without_hiding_validation_gaps(self):
        self.initialise()
        output = self.review()
        report = (self.documents / "stakeholder-review.generated.md").read_text()
        client, appendix = report.split("## Engineering appendix", 1)
        self.assertNotIn("Criterion id", client)
        self.assertNotIn("Minimum samples", client)
        self.assertNotIn("missing-input/evidence findings", client)
        self.assertIn("Test not run", client)
        self.assertIn("Not yet specified", client)
        self.assertIn(str(len(output["gaps"])) + " missing-input/evidence findings", appendix)
        self.assertTrue(output["gaps"])

    def test_partially_authored_acceptance_retains_zero_operator_window_and_unknowns(self):
        self.initialise()
        plan = self.read_document("test-plan.yaml")
        rule = plan["acceptanceCriteria"][0]["measurableRules"][0]
        rule.update(metric="error-count", operator="<=", threshold=0, window="steady-state", unit="errors")
        # Missing criterion linkage must not suppress the authored rule.
        self.write_document("test-plan.yaml", plan)
        self.review()
        report = (self.documents / "stakeholder-review.generated.md").read_text()
        self.assertIn("**Threshold:** 0", report)
        self.assertIn("&lt;=", report)
        self.assertIn("steady\\-state", report)
        self.assertIn("**Minimum samples:** Not yet specified", report)
        retained = self.read_document("test-plan.yaml")["acceptanceCriteria"][0]
        self.assertIsNone(retained["criterionRef"])
        self.assertEqual(0, retained["measurableRules"][0]["threshold"])

    def test_sources_remain_distinct_within_a_workload_and_source_markup_is_escaped(self):
        import hashlib
        self.initialise()
        source = self.workspace / "statements.txt"
        source.write_text("Rate supplied: 15 journeys/second. Open-loop is only proposed.")
        plan = self.read_document("test-plan.yaml")
        plan["executionModel"]["rateUnit"] = "journeys-per-second"
        plan["executionModel"]["arrivalModel"] = "open-loop"
        plan["executionModel"]["journeyTimeline"] = [{"phase": "steady-state", "durationSeconds": None,
            "startRate": 15, "endRate": 15, "interpolation": None}]
        self.write_document("test-plan.yaml", plan)
        req = self.read_document("requirements.yaml")
        req["project"]["objective"] = "Exercise <script>alert('x')</script> [instructions](https://example.org)"
        self.write_document("requirements.yaml", req)
        trace = self.read_document("traceability.yaml")
        trace["instance"]["provenance"] = [{
            "target": {"document": "plan", "pointer": pointer}, "kind": kind,
            "sources": [{"artifactRef": str(source), "pointer": None,
                         "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None,
        } for pointer, kind in (
            ("/executionModel/rateUnit", "client-statement"),
            ("/executionModel/journeyTimeline/0/startRate", "client-statement"),
            ("/executionModel/arrivalModel", "engineer-proposal"))]
        self.write_document("traceability.yaml", trace)
        self.review()
        report = (self.documents / "stakeholder-review.generated.md").read_text()
        self.assertIn("15 (Supplied by you)", report)
        # A journey rate is not evidence of completed/successful throughput.
        self.assertNotIn("Complete journeys", report)
        self.assertIn("Journeys per second", report)
        self.assertIsNone(self.read_document("requirements.yaml")["kpis"][0]["targetTps"])
        self.assertIn("open\\-loop\\) (Proposed approach)", report)
        self.assertIn("**Duration seconds:** Not yet specified", report)
        self.assertNotIn("<script>", report)
        self.assertNotIn("[instructions](https://example.org)", report)
        self.assertEqual("draft", self.read_document("traceability.yaml")["instance"]["review"]["status"])

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
        self.assertIn(b"Source not classified", report.read_bytes())
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
