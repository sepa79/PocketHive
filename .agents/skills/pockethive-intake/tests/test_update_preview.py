"""Public CLI checks for optional previews and truthful saved-batch outcomes."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class UpdatePreviewTests(CliTestCase):
    def setUp(self):
        super().setUp()
        self.source = self.workspace / "client-answer.txt"
        self.source.write_text("Synthetic client statement: measure checkout completion.\n", encoding="utf-8")
        _, initial = self.initialise(source=self.source)
        self.revision = initial["documentsSha256"]

    def batch(self, *, value="Measure checkout completion."):
        target = {"document": "requirements", "pointer": "/project/objective"}
        update = {"target": target, "value": value, "provenance": {
            "target": target, "kind": "client-statement",
            "sources": [{"artifactRef": str(self.source), "pointer": None,
                         "sha256": hashlib.sha256(self.source.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None}}
        path = self.workspace / "updates.json"
        path.write_text(json.dumps({"expectedDocumentsSha256": self.revision, "updates": [update]}), encoding="utf-8")
        return path

    def snapshot(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def assert_counts(self, output):
        for category, count in (("errors", "errorCount"), ("gaps", "gapCount"), ("warnings", "warningCount")):
            self.assertEqual(len(output[category]), output["validation"][count])

    def test_preview_checks_same_batch_as_apply_without_writes(self):
        path = self.batch()
        before = self.snapshot()
        _, preview = self.invoke("apply-updates", "--documents", self.documents, "--input", path,
                                 "--dry-run", expected_exit=0)
        self.assertEqual(before, self.snapshot())
        self.assertTrue(preview["preview"])
        self.assertFalse(preview["applied"])
        self.assertEqual({"state": "not-written", "checkedDocumentsSha256": self.revision,
                          "savedDocumentsSha256": None}, preview["persistence"])
        self.assertEqual("candidate", preview["validation"]["subject"])
        self.assertEqual(self.revision, preview["documentsSha256"])
        self.assert_counts(preview)
        _, applied = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=0)
        self.assertFalse(applied["preview"])
        self.assertTrue(applied["applied"])
        self.assertEqual("verified", applied["persistence"]["state"])
        self.assertEqual(applied["documentsSha256"], applied["persistence"]["savedDocumentsSha256"])
        self.assertEqual("saved-documents", applied["validation"]["subject"])
        self.assertEqual("Measure checkout completion.", self.read_document("requirements.yaml")["project"]["objective"])
        for category in ("errors", "gaps", "warnings"):
            self.assertEqual(preview[category], applied[category])
        self.assert_counts(applied)

    def test_schema_rejection_is_not_written_in_either_mode(self):
        path = self.batch(value={"invalid": "objective must be text"})
        before = self.snapshot()
        results = []
        for flags in (("--dry-run",), ()):
            with self.subTest(flags=flags):
                _, output = self.invoke("apply-updates", "--documents", self.documents, "--input", path,
                                        *flags, expected_exit=2)
                results.append(output)
                self.assertEqual(before, self.snapshot())
                self.assertFalse(output["applied"])
                self.assertEqual("not-written", output["persistence"]["state"])
                self.assertIsNone(output["persistence"]["savedDocumentsSha256"])
                self.assertEqual("candidate", output["validation"]["subject"])
                self.assertTrue(any(issue["code"] == "SCHEMA" for issue in output["errors"]))
                self.assert_counts(output)
        self.assertEqual(results[0]["errors"], results[1]["errors"])

    def test_stale_review_distinguishes_preview_and_verified_persistence(self):
        # A synthetic existing human-review record is test input, never CLI approval.
        _, baseline = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["review"] = {"status": "confirmed", "evidenceRef": str(self.source),
                                               "contentSha256": baseline["reviewContentSha256"]}
        self.write_document("traceability.yaml", traceability)
        _, current = self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.revision = current["documentsSha256"]
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        path = self.batch()
        before = self.snapshot()
        _, preview = self.invoke("apply-updates", "--documents", self.documents, "--input", path,
                                 "--dry-run", expected_exit=2)
        self.assertFalse(preview["applied"])
        self.assertEqual("not-written", preview["persistence"]["state"])
        self.assertEqual(before, self.snapshot())
        _, applied = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=2)
        self.assertTrue(applied["applied"])
        self.assertEqual("verified", applied["persistence"]["state"])
        self.assertNotEqual(before, self.snapshot())
        self.assertEqual(traceability["instance"]["review"], self.read_document("traceability.yaml")["instance"]["review"])
        for output in (preview, applied):
            self.assertEqual(["STALE_REVIEW"], [issue["code"] for issue in output["errors"]])
            self.assertIn("explicit human review", output["nextAction"])
            self.assert_counts(output)

    def test_preview_refuses_an_existing_writer_lock(self):
        path = self.batch()
        before = self.snapshot()
        (self.documents / ".intake-write.lock").mkdir()
        _, output = self.invoke("apply-updates", "--documents", self.documents, "--input", path,
                                "--dry-run", expected_exit=2)
        self.assertEqual("DOCUMENTS_BUSY", output["errors"][0]["code"])
        self.assertEqual(before, self.snapshot())

    def test_preview_is_not_a_reservation_for_a_later_apply(self):
        path = self.batch()
        self.invoke("apply-updates", "--documents", self.documents, "--input", path, "--dry-run", expected_exit=0)
        with (self.documents / "requirements.yaml").open("a", encoding="utf-8") as stream:
            stream.write("\n# A concurrent editor changed this revision.\n")
        before = self.snapshot()
        _, output = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=2)
        self.assertEqual("STALE_DOCUMENTS", output["errors"][0]["code"])
        self.assertEqual(before, self.snapshot())

    def test_source_changed_during_save_keeps_truthful_persistence_outcome(self):
        path = self.batch()
        # Exercise the public CLI with a filesystem observer, not replacement
        # validators. The external evidence changes while the documents save.
        guard = """
import os, pathlib, runpy, sys
script, documents, source = sys.argv[1:4]
def audit(event, arguments):
    if event == 'os.rename' and pathlib.Path(os.fsdecode(arguments[1])) == pathlib.Path(documents) / 'traceability.yaml':
        pathlib.Path(source).write_text('Synthetic evidence changed during save.\\n', encoding='utf-8')
sys.addaudithook(audit)
sys.argv = [script, *sys.argv[4:]]
runpy.run_path(script, run_name='__main__')
"""
        result = subprocess.run([sys.executable, "-B", "-I", "-S", "-c", guard,
                                 str(PACKAGE / "scripts" / "intake.py"), str(self.documents), str(self.source),
                                 "apply-updates", "--documents", str(self.documents), "--input", str(path)],
                                cwd=self.workspace, text=True, capture_output=True, timeout=30, check=False)
        output = json.loads(result.stdout)
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertTrue(output["applied"])
        self.assertEqual("verified", output["persistence"]["state"])
        self.assertTrue(any(issue["code"] == "SOURCE_HASH" for issue in output["errors"]))
        self.assertEqual("Measure checkout completion.", self.read_document("requirements.yaml")["project"]["objective"])
        self.assert_counts(output)
