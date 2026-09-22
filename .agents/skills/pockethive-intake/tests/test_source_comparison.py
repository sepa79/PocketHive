"""Public CLI qualification for read-only comparison of explicit bundle snapshots."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


HTTP_FIXTURE = PACKAGE / "fixtures" / "http-population-bundle"
READ_TEMPLATE = "templates/account/read.yaml"


class SourceComparisonTests(CliTestCase):
    def sources(self, populate=True):
        self.original = self.workspace / "original"
        self.previous = self.workspace / "previous"
        self.current = self.workspace / "current"
        shutil.copytree(HTTP_FIXTURE, self.original)
        self.initialise("from-bundle", self.original)
        if populate:
            self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        shutil.copytree(self.original, self.previous)
        shutil.copytree(self.original, self.current)

    def compare(self, expected_exit=0):
        return self.invoke("compare-source", "--documents", self.documents,
                           "--previous-source", self.previous, "--source", self.current,
                           expected_exit=expected_exit)

    def document_bytes(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    @staticmethod
    def tree_bytes(root):
        return {path.relative_to(root).as_posix(): path.read_bytes() for path in root.rglob("*") if path.is_file()}

    def change_read(self):
        path = self.current / READ_TEMPLATE
        text = path.read_text(encoding="utf-8")
        self.assertIn("method: GET", text)
        path.write_text(text.replace("method: GET", "method: POST"), encoding="utf-8")

    def test_identical_retained_snapshots_report_no_changes_and_write_nothing(self):
        self.sources()
        before = self.document_bytes()
        sources = [self.tree_bytes(root) for root in (self.original, self.previous, self.current)]
        _, output = self.compare()
        result = output["comparison"]
        self.assertEqual([], result["changedFiles"])
        self.assertEqual([], result["changedObservations"])
        self.assertEqual([], result["impactedTargets"])
        self.assertEqual([], result["limitations"])
        self.assertEqual(result["previousSource"]["sha256"], result["source"]["sha256"])
        self.assertEqual(before, self.document_bytes())
        self.assertEqual(sources, [self.tree_bytes(root) for root in (self.original, self.previous, self.current)])

    def test_changed_added_and_removed_files_report_only_supported_observation_values(self):
        self.sources()
        self.change_read()
        removed = self.current / "templates/account/submit.yaml"
        removed.rename(self.workspace / "removed-template.yaml")
        (self.current / "added.json").write_text('{"rate": 71, "privatePayload": "must-not-be-exported"}')
        before = self.document_bytes()
        process, output = self.compare()
        result = output["comparison"]
        self.assertEqual({READ_TEMPLATE: "modified", "templates/account/submit.yaml": "removed", "added.json": "added"},
                         {row["path"]: row["change"] for row in result["changedFiles"]})
        method = next(row for row in result["changedObservations"] if row["artifactRef"] == READ_TEMPLATE)
        self.assertEqual({"artifactRef": READ_TEMPLATE, "pointer": "/method", "change": "modified",
                          "previousValue": "GET", "value": "POST"}, method)
        added = next(row for row in result["changedObservations"] if row["artifactRef"] == "added.json")
        self.assertEqual(71, added["value"])
        self.assertEqual("added", added["change"])
        self.assertNotIn("must-not-be-exported", process.stdout)
        self.assertTrue(any(READ_TEMPLATE in row["sources"] for row in result["impactedTargets"]))
        self.assertEqual(before, self.document_bytes())
        recorded = self.read_document("traceability.yaml")["instance"]["intake"]["source"]
        self.assertEqual(str(self.original), recorded["artifactRef"])
        self.assertNotEqual(recorded["sha256"], result["source"]["sha256"])

    def test_mapping_uses_recorded_root_when_original_bundle_has_moved(self):
        self.sources()
        self.original.rename(self.workspace / "archived-original")
        self.change_read()
        _, output = self.compare()
        self.assertTrue(any(READ_TEMPLATE in row["sources"] for row in output["comparison"]["impactedTargets"]))

    def test_relative_evidence_resolves_documents_root_without_basename_guessing(self):
        self.sources(populate=False)
        trace = self.read_document("traceability.yaml")
        digest = hashlib.sha256((self.original / READ_TEMPLATE).read_bytes()).hexdigest()
        source = {"artifactRef": "../original/" + READ_TEMPLATE, "sha256": digest, "pointer": "/method"}
        record = {"target": {"document": "requirements", "pointer": "/project/objective"},
                  "kind": "bundle-observation", "sources": [source], "confirmationRef": None, "calculation": None}
        misleading = deepcopy(record)
        misleading["target"]["pointer"] = "/project/name"
        misleading["sources"][0]["artifactRef"] = READ_TEMPLATE
        trace["instance"]["provenance"] = [record, misleading]
        self.write_document("traceability.yaml", trace)
        self.change_read()
        _, output = self.compare()
        self.assertEqual([{"target": {"document": "requirements", "pointer": "/project/objective"},
                           "sources": [READ_TEMPLATE]}], output["comparison"]["impactedTargets"])

    def test_remote_provenance_is_explicitly_unmapped_and_is_not_fetched(self):
        self.sources()
        trace = self.read_document("traceability.yaml")
        trace["instance"]["provenance"][0]["sources"][0]["artifactRef"] = "https://example.invalid/private-evidence"
        self.write_document("traceability.yaml", trace)
        _, output = self.invoke("compare-source", "--documents", self.documents,
                                "--previous-source", self.previous, "--source", self.current,
                                expected_exit=0, offline=True)
        self.assertIn("SOURCE_REFERENCE_UNMAPPED", {row["code"] for row in output["comparison"]["limitations"]})

    def test_changed_unsupported_and_unparseable_files_remain_explicit_review_limitations(self):
        self.sources(populate=False)
        (self.current / "notes.txt").write_text("private-unsupported-payload")
        (self.current / "invalid.yaml").write_text("broken: [\n")
        process, output = self.compare()
        result = output["comparison"]
        self.assertEqual([], result["changedObservations"])
        codes = {row["code"] for row in result["limitations"]}
        self.assertIn("SOURCE_CHANGE_UNOBSERVED", codes)
        self.assertIn("SOURCE_IMPACT_UNMAPPED", codes)
        self.assertTrue(any(row.get("snapshot") == "current" for row in result["limitations"]))
        self.assertNotIn("private-unsupported-payload", process.stdout)

    def test_previous_snapshot_must_match_recorded_inventory(self):
        self.sources()
        (self.previous / "added.txt").write_text("changed previous snapshot")
        before = self.document_bytes()
        _, output = self.compare(expected_exit=2)
        self.assertEqual("SOURCE_HASH", output["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_new_requirements_cannot_implicitly_switch_to_bundle_mode(self):
        self.initialise()
        self.previous = self.current = HTTP_FIXTURE
        before = self.document_bytes()
        _, output = self.compare(expected_exit=2)
        self.assertEqual("SOURCE_COMPARISON_MODE", output["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_a_previous_hash_or_inspection_file_is_not_a_source_snapshot(self):
        self.sources()
        self.previous = self.documents / "source-inspection.json"
        _, output = self.compare(expected_exit=2)
        self.assertEqual("BUNDLE_DIRECTORY", output["errors"][0]["code"])

    def test_source_change_during_comparison_fails_instead_of_returning_a_mixed_snapshot(self):
        self.sources()
        guard = """
import os, pathlib, runpy, sys
target = pathlib.Path(sys.argv[1])
original = target.read_bytes()
count = 0
def audit(event, arguments):
    global count
    if event == 'open' and isinstance(arguments[0], (str, bytes, os.PathLike)) and arguments[1] == 'r':
        if pathlib.Path(os.fsdecode(arguments[0])).absolute() == target:
            count += 1
            if count == 2:
                target.write_bytes(original + b'\\n# changed during comparison\\n')
sys.addaudithook(audit)
script = sys.argv[2]
sys.argv = [script, *sys.argv[3:]]
runpy.run_path(script, run_name='__main__')
"""
        before = self.document_bytes()
        command = [sys.executable, "-B", "-I", "-S", "-c", guard, str(self.previous / "scenario.yaml"),
                   str(PACKAGE / "scripts/intake.py"), "compare-source", "--documents", str(self.documents),
                   "--previous-source", str(self.previous), "--source", str(self.current)]
        process = subprocess.run(command, cwd=self.workspace, capture_output=True, text=True, timeout=30, check=False)
        self.assertEqual(2, process.returncode, process.stdout + process.stderr)
        self.assertEqual("SOURCE_CHANGED_DURING_COMPARISON", json.loads(process.stdout)["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())
