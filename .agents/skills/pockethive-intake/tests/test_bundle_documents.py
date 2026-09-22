"""Public CLI regressions for bundled forms and the scenario-source boundary."""
from __future__ import annotations

import hashlib
import json
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class BundleDocumentsTests(CliTestCase):
    def bundle(self):
        self.source = self.workspace / "bundle"
        shutil.copytree(PACKAGE / "fixtures/http-population-bundle", self.source)
        self.documents = self.source / "intake"

    def inspect(self):
        return self.invoke("inspect-bundle", "--source", self.source, expected_exit=0)[1]

    def document_bytes(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def review(self, expected_exit=0):
        return self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft",
                           expected_exit=expected_exit)[1]

    def test_bundled_population_and_repeated_reviews_preserve_source_identity(self):
        self.bundle()
        before = self.inspect()
        self.initialise("from-bundle", self.source)
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        first = self.review()
        saved = self.document_bytes()
        second = self.review()
        self.assertEqual(saved, self.document_bytes())
        self.assertEqual(first["documentsSha256"], second["documentsSha256"])
        self.assertEqual(before["sha256"], self.inspect()["sha256"])
        self.assertEqual(before["files"], self.inspect()["files"])
        self.assertEqual(["intake/"], before["excludedDirectories"])
        self.assertEqual(2, len(self.read_document("requirements.yaml")["templates"]))
        self.assertFalse(second["errors"])
        self.assertTrue(second["gaps"], "Observation population is not client acceptance")
        self.assertEqual(before["sha256"], json.loads((self.documents / "source-inspection.json").read_text())["sha256"])

    def test_intake_edits_change_document_digest_without_changing_source(self):
        self.bundle()
        self.initialise("from-bundle", self.source)
        original = self.inspect()
        first = self.review()
        requirements = self.read_document("requirements.yaml")
        requirements["project"]["objective"] = "Draft objective still awaiting evidence"
        self.write_document("requirements.yaml", requirements)
        (self.documents / "review.json").write_text('{"method":"DELETE"}')
        (self.documents / "oversize-evidence.bin").write_bytes(b"x" * 2097153)
        second = self.review()
        self.assertNotEqual(first["documentsSha256"], second["documentsSha256"])
        self.assertNotEqual(first["reviewContentSha256"], second["reviewContentSha256"])
        self.assertEqual(original["sha256"], self.inspect()["sha256"])
        self.assertEqual(original["coverage"], self.inspect()["coverage"])
        self.assertIn("MISSING_PROVENANCE", {row["code"] for row in second["gaps"]})

    def test_source_changes_still_fail_verification(self):
        self.bundle()
        nested = self.source / "templates/intake"
        nested.mkdir()
        nested_file = nested / "retained.yaml"
        nested_file.write_text("method: GET\n")
        self.initialise("from-bundle", self.source)
        original = self.inspect()
        self.assertIn("templates/intake/retained.yaml", {row["path"] for row in original["files"]})
        for relative in ("scenario.yaml", "templates/account/read.yaml", "templates/intake/retained.yaml"):
            with self.subTest(relative=relative):
                path = self.source / relative
                content = path.read_bytes()
                path.write_bytes(content + b"\n# reviewed source changed\n")
                _, result = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
                self.assertIn("SOURCE_HASH", {row["code"] for row in result["errors"]})
                path.write_bytes(content)

    def test_reserved_intake_path_cannot_be_a_file_or_symlink(self):
        self.bundle()
        self.documents.write_text("not a directory")
        _, result = self.invoke("inspect-bundle", "--source", self.source, expected_exit=2)
        self.assertEqual("BUNDLE_INTAKE_DIRECTORY", result["errors"][0]["code"])
        self.documents.rename(self.workspace / "saved-intake-file")
        target = self.workspace / "external-docs"
        target.mkdir()
        self.documents.symlink_to(target, target_is_directory=True)
        _, result = self.invoke("inspect-bundle", "--source", self.source, expected_exit=2)
        self.assertEqual("SYMLINK", result["errors"][0]["code"])
        self.assertEqual([], list(target.iterdir()))

    def test_other_nested_output_locations_are_rejected_before_creation(self):
        self.bundle()
        for root in (self.source, self.source / "docs", self.source / "templates/intake", self.documents / "other"):
            with self.subTest(root=root):
                _, result = self.invoke("initialise", "--mode", "from-bundle", "--source", self.source,
                                        "--output", root, expected_exit=2)
                self.assertEqual("OUTPUT_IN_SOURCE", result["errors"][0]["code"])
                self.assertEqual(str(self.documents), result["errors"][0]["detail"]["intakePath"])
                if root != self.source:
                    self.assertFalse(root.exists())

    def test_resuming_manually_relocated_forms_rejects_invalid_layout_before_writes(self):
        self.bundle()
        self.initialise("from-bundle", self.source)
        wrong = self.source / "docs"
        self.documents.rename(wrong)
        self.documents = wrong
        before = self.document_bytes()
        for command in ("finalise", "prepare-review", "populate-from-inspection"):
            with self.subTest(command=command):
                args = [command, "--documents", self.documents]
                if command == "prepare-review":
                    args.extend(["--stage", "draft"])
                _, result = self.invoke(*args, expected_exit=2)
                self.assertEqual("OUTPUT_IN_SOURCE", result["errors"][0]["code"])
                self.assertEqual(before, self.document_bytes())

    def test_comparison_ignores_forms_but_reports_runtime_changes(self):
        self.bundle()
        self.initialise("from-bundle", self.source)
        previous = self.workspace / "previous"
        current = self.workspace / "current"
        shutil.copytree(self.source, previous)
        shutil.copytree(self.source, current)
        (current / "intake/requirements.yaml").write_text("changed forms\n")
        args = ("compare-source", "--documents", self.documents, "--previous-source", previous, "--source", current)
        _, result = self.invoke(*args, expected_exit=0)
        self.assertEqual([], result["comparison"]["changedFiles"])
        (current / "datasets").mkdir()
        (current / "datasets/accounts.csv").write_text("account\n42\n")
        _, result = self.invoke(*args, expected_exit=0)
        self.assertEqual(["datasets/accounts.csv"], [row["path"] for row in result["comparison"]["changedFiles"]])

    def test_evidence_under_intake_keeps_its_own_integrity_check(self):
        self.bundle()
        self.initialise("from-bundle", self.source)
        evidence = self.documents / "client-answer.txt"
        evidence.write_text("Test account lookup responsiveness.\n")
        requirements = self.read_document("requirements.yaml")
        requirements["project"]["objective"] = evidence.read_text().strip()
        self.write_document("requirements.yaml", requirements)
        trace = self.read_document("traceability.yaml")
        trace["instance"]["provenance"].append({
            "target": {"document": "requirements", "pointer": "/project/objective"},
            "kind": "client-statement", "sources": [{"artifactRef": "client-answer.txt", "pointer": None,
                                                       "sha256": hashlib.sha256(evidence.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None})
        self.write_document("traceability.yaml", trace)
        self.review()
        evidence.write_text("The client changed their answer.\n")
        result = self.review(expected_exit=2)
        issues = [row for row in result["errors"] if row["code"] == "SOURCE_HASH"]
        self.assertEqual(["/instance/provenance/0/sources/0"], [row["pointer"] for row in issues])

    def test_new_requirements_can_start_before_a_scenario_exists(self):
        self.documents = self.workspace / "future-bundle/intake"
        self.initialise()
        self.review()
        self.assertFalse((self.documents.parent / "scenario.yaml").exists())
        self.assertIsNone(self.read_document("traceability.yaml")["instance"]["intake"]["source"])
