"""Public CLI checks for observed run identity without inferred execution or outcomes."""
from __future__ import annotations

import hashlib
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class ResultIdentityTests(CliTestCase):
    def setUp(self):
        super().setUp()
        _, initial = self.initialise()
        self.revision = initial["documentsSha256"]
        self.source_number = 0
        self.canary = "PRIVATE-RUNTIME-EVIDENCE-CANARY"

    def record(self, fields, *, expected_exit=0, source_hash=None):
        self.source_number += 1
        source = self.workspace / f"runtime-observation-{self.source_number}.json"
        source.write_text(json.dumps({**fields, "privateDiagnostic": self.canary}), encoding="utf-8")
        digest = source_hash or hashlib.sha256(source.read_bytes()).hexdigest()
        updates = []
        for field, value in fields.items():
            target = {"document": "results", "pointer": "/runInfo/" + field}
            updates.append({"target": target, "value": value, "provenance": {
                "target": target, "kind": "execution-evidence",
                "sources": [{"artifactRef": str(source), "pointer": "/" + field, "sha256": digest}],
                "confirmationRef": None, "calculation": None,
            }})
        request = self.workspace / f"runtime-updates-{self.source_number}.json"
        request.write_text(json.dumps({"expectedDocumentsSha256": self.revision,
                                       "updates": updates}), encoding="utf-8")
        raw, output = self.invoke("apply-updates", "--documents", self.documents,
                                  "--input", request, expected_exit=expected_exit)
        self.assertNotIn(self.canary, raw.stdout + raw.stderr)
        if output.get("applied"):
            self.revision = output["documentsSha256"]
        return output

    def snapshot(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def assert_not_run(self):
        results = self.read_document("execution-results.yaml")
        self.assertEqual("not-run", results["runInfo"]["executionStatus"])
        self.assertEqual("not-run", results["overallResult"]["result"])
        self.assertIsNone(results["runInfo"]["startTime"])
        self.assertIsNone(results["runInfo"]["endTime"])

    def test_blank_run_identity_stays_unknown_when_only_swarm_is_observed(self):
        self.assertIsNone(self.read_document("execution-results.yaml")["runInfo"]["runId"])
        output = self.record({"swarmId": "synthetic-reused-swarm"})
        self.assertTrue(output["applied"], output)
        self.assertFalse(output["errors"], output)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        results = self.read_document("execution-results.yaml")
        self.assertEqual("synthetic-reused-swarm", results["runInfo"]["swarmId"])
        self.assertIsNone(results["runInfo"]["runId"])
        self.assert_not_run()

    def test_supplied_run_identity_is_retained_with_evidence_without_implying_execution(self):
        output = self.record({"swarmId": "synthetic-reused-swarm", "runId": "synthetic-run-002"})
        self.assertTrue(output["applied"], output)
        self.assertFalse(output["errors"], output)
        self.assertEqual("incomplete", output["status"])
        self.assertEqual("synthetic-run-002", self.read_document("execution-results.yaml")["runInfo"]["runId"])
        self.assert_not_run()
        records = self.read_document("traceability.yaml")["instance"]["provenance"]
        record = next(row for row in records if row["target"] == {
            "document": "results", "pointer": "/runInfo/runId",
        })
        self.assertEqual("execution-evidence", record["kind"])
        self.assertEqual("/runId", record["sources"][0]["pointer"])
        raw, handoff = self.invoke("validate", "--documents", self.documents,
                                   "--stage", "handoff", expected_exit=3)
        self.assertFalse(handoff["errors"], handoff)
        self.assertTrue(handoff["gaps"], handoff)
        self.assertNotIn(self.canary, raw.stdout + raw.stderr)
        self.assert_not_run()

    def test_executed_result_requires_run_id_and_only_its_specific_gap_is_closed(self):
        partial = self.record({"swarmId": "synthetic-reused-swarm", "executionStatus": "inconclusive"})
        self.assertFalse(partial["errors"], partial)
        identity_gaps = [issue for issue in partial["gaps"] if issue["code"] == "RUN_IDENTITY"]
        self.assertTrue(any(issue["document"] == "results" and issue["pointer"] == "/runInfo/runId"
                            for issue in identity_gaps), partial)
        complete = self.record({"runId": "synthetic-run-002"})
        self.assertFalse(complete["errors"], complete)
        self.assertFalse(any(issue["code"] == "RUN_IDENTITY" and issue["pointer"] == "/runInfo/runId"
                             for issue in complete["gaps"]), complete)
        self.assertTrue(any(issue["code"] == "RUN_IDENTITY" for issue in complete["gaps"]), complete)
        results = self.read_document("execution-results.yaml")
        self.assertEqual("synthetic-run-002", results["runInfo"]["runId"])
        self.assertEqual("inconclusive", results["runInfo"]["executionStatus"])
        self.assertEqual("not-run", results["overallResult"]["result"])
        self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)

    def test_run_identity_with_mismatched_evidence_is_rejected_without_document_writes(self):
        before = self.snapshot()
        output = self.record({"runId": "synthetic-run-002"}, expected_exit=2, source_hash="0" * 64)
        self.assertFalse(output["applied"], output)
        self.assertTrue(any(issue["code"] == "SOURCE_HASH" for issue in output["errors"]), output)
        self.assertEqual(before, self.snapshot())
