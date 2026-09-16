"""Public CLI tests for extraction coverage without changing source interpretation."""
from __future__ import annotations

import hashlib
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class InspectionCoverageTests(CliTestCase):
    def source(self):
        source = self.workspace / "bundle"
        source.mkdir()
        (source / "scenario.yaml").write_text("rate: 25\nenabled: true\n")
        return source

    def inspect(self, source):
        return self.invoke("inspect-bundle", "--source", source, expected_exit=0, offline=True)

    def test_coverage_distinguishes_extracted_omitted_sensitive_and_unreadable_content(self):
        source = self.source()
        (source / "mixed.yaml").write_text(
            "method: GET\nsteps:\n  - callId: lookup\n    binding: from-first-step\n"
            "headers:\n  Authorization: synthetic-secret-canary\nbody:\n  rate: 999\n"
        )
        (source / "invalid.yaml").write_text("value: [unterminated\n")
        (source / "accounts.csv").write_text("accountId\nprivate-dataset-canary\n")
        (source / "binary.bin").write_bytes(bytes([0, 255, 128, 13, 10]))
        (source / "setup.sh").write_text('touch "$INTAKE_EXECUTION_MARKER"\n')
        before = {p.name: p.read_bytes() for p in source.iterdir()}
        raw, result = self.inspect(source)
        rows = {row["path"]: row for row in result["coverage"]["files"]}
        self.assertEqual(set(before), set(rows))
        self.assertEqual({
            "path": "mixed.yaml", "status": "structured", "observationCount": 1,
            "unextractedScalarCount": 2, "sensitiveScalarCount": 2, "reviewRequired": True,
        }, rows["mixed.yaml"])
        self.assertEqual({
            "path": "scenario.yaml", "status": "structured", "observationCount": 2,
            "unextractedScalarCount": 0, "sensitiveScalarCount": 0, "reviewRequired": False,
        }, rows["scenario.yaml"])
        for name, status in (("invalid.yaml", "unreadable"), ("accounts.csv", "not-extracted"),
                             ("binary.bin", "not-extracted"), ("setup.sh", "not-extracted")):
            self.assertEqual({"path": name, "status": status, "observationCount": None,
                              "unextractedScalarCount": None, "sensitiveScalarCount": None,
                              "reviewRequired": True}, rows[name])
        self.assertEqual({"fileCount": 6, "structuredFileCount": 2, "notExtractedFileCount": 3,
                          "unreadableFileCount": 1, "observationCount": 3, "reviewRequiredFileCount": 5},
                         result["coverage"]["summary"])
        for canary in ("synthetic-secret-canary", "private-dataset-canary", "from-first-step",
                       "/headers/Authorization", "/body/rate", "unterminated"):
            self.assertNotIn(canary, raw.stdout + raw.stderr)
        self.assertEqual(before, {p.name: p.read_bytes() for p in source.iterdir()})
        self.assertEqual("7f08678035b6eba5bd34f25f942bfb467b40d618c36c1855ae69fda953b8d862", result["sha256"])
        self.assertFalse(any(result["claims"].values()))
        self.assertEqual(len(result["observations"]), result["coverage"]["summary"]["observationCount"])
        self.assertEqual([
            {"artifactRef": "mixed.yaml", "pointer": "/method", "value": "GET",
             "sha256": hashlib.sha256(before["mixed.yaml"]).hexdigest(), "kind": "bundle-observation"},
            {"artifactRef": "scenario.yaml", "pointer": "/rate", "value": 25,
             "sha256": hashlib.sha256(before["scenario.yaml"]).hexdigest(), "kind": "bundle-observation"},
            {"artifactRef": "scenario.yaml", "pointer": "/enabled", "value": True,
             "sha256": hashlib.sha256(before["scenario.yaml"]).hexdigest(), "kind": "bundle-observation"},
        ], result["observations"])
        self.assertTrue(any(row["document"] == "invalid.yaml" for row in result["limitations"]))

    def test_initialisation_saves_the_same_derived_coverage_and_preserves_draft_gaps(self):
        source = self.source()
        (source / "nested.yaml").write_text("steps:\n  - callId: already-supplied\n")
        _, inspected = self.inspect(source)
        self.initialise("from-bundle", source)
        saved = json.loads((self.documents / "source-inspection.json").read_text())
        self.assertEqual(inspected["coverage"], saved["coverage"])
        self.assertEqual(inspected["sha256"], saved["sha256"])
        self.assertEqual("open", self.read_document("traceability.yaml")["instance"]["questions"][0]["status"])
        self.assertIsNone(self.read_document("requirements.yaml")["project"]["objective"])
        self.assertIsNone(self.read_document("test-plan.yaml")["sutId"])
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        self.inspect(source)
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_empty_structures_and_all_sensitive_content_require_review(self):
        source = self.source()
        (source / "empty.yaml").write_text("{}\n")
        (source / "sensitive.yaml").write_text("credentials:\n  token: do-not-expose\n")
        _, result = self.inspect(source)
        rows = {row["path"]: row for row in result["coverage"]["files"]}
        self.assertEqual(0, rows["empty.yaml"]["observationCount"])
        self.assertTrue(rows["empty.yaml"]["reviewRequired"])
        self.assertEqual(1, rows["sensitive.yaml"]["sensitiveScalarCount"])
        self.assertTrue(rows["sensitive.yaml"]["reviewRequired"])

    def test_current_inspection_reflects_changed_bytes_without_adopting_a_saved_report(self):
        source = self.source()
        self.initialise("from-bundle", source)
        report_path = self.documents / "source-inspection.json"
        old_report = report_path.read_bytes()
        old = json.loads(old_report)
        (source / "scenario.yaml").write_text("steps:\n  - callId: revised\n")
        _, current = self.inspect(source)
        self.assertNotEqual(old["sha256"], current["sha256"])
        self.assertTrue(current["coverage"]["files"][0]["reviewRequired"])
        self.assertEqual(0, current["coverage"]["summary"]["observationCount"])
        self.assertEqual(old_report, report_path.read_bytes())
        for row in current["files"]:
            self.assertEqual(hashlib.sha256((source / row["path"]).read_bytes()).hexdigest(), row["sha256"])
        self.assertEqual(old["sha256"], self.read_document("traceability.yaml")["instance"]["intake"]["source"]["sha256"])
