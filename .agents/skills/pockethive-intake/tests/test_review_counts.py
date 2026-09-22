"""Public CLI checks that review counts describe existing diagnostic indexes."""
from __future__ import annotations

from test_intake_cli import CliTestCase


class ReviewCountTests(CliTestCase):
    def test_counts_cover_errors_gaps_and_warnings_without_suppressing_findings(self):
        self.initialise()
        results = self.read_document("execution-results.yaml")
        results["overallResult"]["result"] = "pass"
        self.write_document("execution-results.yaml", results)
        _, output = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        groups = output["brief"]["diagnosticGroups"]
        self.assertTrue(groups)
        for category, count in (("errors", "errorCount"), ("gaps", "gapCount"), ("warnings", "warningCount")):
            self.assertTrue(output[category], category)
            indexes = []
            for group in groups:
                self.assertEqual(len(group[category]), group["counts"][count])
                indexes.extend(group[category])
            self.assertEqual(list(range(len(output[category]))), sorted(indexes))
            self.assertEqual(len(output[category]), sum(group["counts"][count] for group in groups))

    def test_question_groups_and_repeated_review_keep_stable_counts(self):
        self.initialise()
        _, first = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        _, second = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertEqual(first["brief"]["diagnosticGroups"], second["brief"]["diagnosticGroups"])
        question_groups = [group for group in first["brief"]["diagnosticGroups"] if group["kind"] == "questions"]
        self.assertTrue(question_groups)
        for group in question_groups:
            self.assertEqual(len(group["gaps"]), group["counts"]["gapCount"])
            self.assertEqual(0, group["counts"]["errorCount"])
