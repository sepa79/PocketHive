"""Public-CLI checks for accepting human source forms without changing templates."""
from __future__ import annotations

import hashlib
import json
import shutil

from test_intake_cli import CliTestCase, PACKAGE


class HumanInputTests(CliTestCase):
    def source(self, text, name="client.yaml"):
        path = self.workspace / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_all_five_original_forms_in_bundle_intake_are_accepted_unchanged(self):
        intake = self.workspace / "bundle" / "intake"
        intake.mkdir(parents=True)
        originals = list((PACKAGE / "assets/source").glob("*.yaml"))
        self.assertEqual(5, len(originals))
        for original in originals:
            shutil.copy2(original, intake / original.name)
        before = {p.name: p.read_bytes() for p in intake.iterdir()}
        _, result = self.invoke("review-input", "--source", intake, expected_exit=0, offline=True)
        self.assertEqual("incomplete", result["status"])
        self.assertEqual("human-input", result["assessment"])
        self.assertEqual("not-run", result["handoffAssessment"])
        self.assertEqual(5, len(result["documents"]))
        self.assertFalse(result["errors"])
        self.assertEqual({"requirements", "plan", "traceability", "results"}, {d["role"] for d in result["documents"]})
        for row in result["documents"]:
            self.assertEqual(hashlib.sha256(before[row["artifactRef"].split("/")[-1]]).hexdigest(), row["sha256"])
        self.assertEqual(before, {p.name: p.read_bytes() for p in intake.iterdir()})
        self.assertIn("INPUT_SELECTION_REQUIRED", {g["code"] for g in result["gaps"]})
        _, repeated = self.invoke("review-input", "--source", intake, expected_exit=0)
        self.assertEqual(result, repeated)

    def test_partial_form_with_working_name_and_authored_questions_needs_no_metadata(self):
        path = self.source("version: 1\ntemplateType: bundle-generation-input\nproject: {name: null}\nopenQuestions: [How long?]\ncustomRequirement: {limit: 0, reuse: false}\n", "requirements.yaml")
        before = path.read_bytes()
        _, result = self.invoke("review-input", "--source", path, expected_exit=0)
        self.assertFalse(result["errors"])
        self.assertEqual("requirements", result["documents"][0]["role"])
        self.assertEqual(before, path.read_bytes())
        self.assertEqual([path], list(self.workspace.iterdir()))

    def test_missing_or_unrecognised_type_is_an_enrichment_gap_not_invalid(self):
        for text in ("scope:\nname:\n", "templateType: client-notes\n", "templateType: {custom: true}\n"):
            with self.subTest(text=text):
                path = self.source(text)
                _, result = self.invoke("review-input", "--source", path, expected_exit=0)
                self.assertIsNone(result["documents"][0]["role"])
                self.assertIn("INPUT_ROLE_REVIEW", {g["code"] for g in result["gaps"]})

    def test_blank_forms_are_accepted_but_not_complete(self):
        for text in ("", "# Fill later\n", "{}\n", "null\n"):
            with self.subTest(text=text):
                _, result = self.invoke("review-input", "--source", self.source(text), expected_exit=0)
                self.assertEqual("incomplete", result["status"])
                self.assertIn("INPUT_BLANK", {g["code"] for g in result["gaps"]})

    def test_selection_is_explicit_deduplicated_and_not_recursive(self):
        path = self.source("name: supplied\n", "client.YML")
        nested = self.workspace / "nested"
        nested.mkdir()
        (nested / "bad.yaml").write_text("[broken", encoding="utf-8")
        self.source("not YAML", "notes.txt")
        _, result = self.invoke("review-input", "--source", path, "--source", self.workspace, expected_exit=0)
        self.assertEqual([str(path)], [r["artifactRef"] for r in result["documents"]])

    def test_malformed_forms_report_real_errors_and_keep_readable_findings(self):
        good = self.source("name: supplied\n", "good.yaml")
        bad = self.source("scope: [\n", "bad.yaml")
        _, result = self.invoke("review-input", "--source", good, "--source", bad, expected_exit=2)
        self.assertEqual("YAML_PARSE", result["errors"][0]["code"])
        self.assertEqual(str(bad), result["errors"][0]["document"])
        self.assertEqual([str(good)], [r["artifactRef"] for r in result["documents"]])

    def test_unsafe_or_ambiguous_yaml_is_still_rejected(self):
        cases = (("a: 1\na: 2\n", "YAML_DUPLICATE_KEY"),
                 ("!!python/object/apply:os.system ['false']", "YAML_TAG"),
                 ("[not, a, form]", "DOCUMENT_OBJECT"))
        for text, expected in cases:
            with self.subTest(expected=expected):
                _, result = self.invoke("review-input", "--source", self.source(text), expected_exit=2)
                self.assertEqual(expected, result["errors"][0]["code"])

    def test_values_and_embedded_instructions_are_not_echoed_or_executed(self):
        secret = "private-source-value-never-echo"
        path = self.source(f"templateType: {secret}\ncredential: {secret}\ninstructions: 'touch {self.marker}'\n")
        process, result = self.invoke("review-input", "--source", path, "--debug", expected_exit=0)
        self.assertNotIn(secret, process.stdout + process.stderr)
        self.assertFalse(self.marker.exists())
        self.assertIsNone(result["documents"][0]["role"])

    def test_symlink_and_missing_file_fail_explicitly(self):
        target = self.source("name: source\n", "source.txt")
        link = self.workspace / "linked.yaml"
        link.symlink_to(target)
        _, result = self.invoke("review-input", "--source", self.workspace, expected_exit=2)
        self.assertEqual("SYMLINK", result["errors"][0]["code"])
        _, result = self.invoke("review-input", "--source", self.workspace / "missing.yaml", expected_exit=2)
        self.assertEqual("MISSING_FILE", result["errors"][0]["code"])

    def test_empty_directory_and_oversized_file_fail_explicitly(self):
        _, result = self.invoke("review-input", "--source", self.workspace, expected_exit=2)
        self.assertEqual("INPUT_EMPTY", result["errors"][0]["code"])
        limit = json.loads((PACKAGE / "contract/manifest.json").read_text())["limits"]["fileBytes"]
        large = self.source("x" * (limit + 1))
        _, result = self.invoke("review-input", "--source", large, expected_exit=2)
        self.assertEqual("FILE_LIMIT", result["errors"][0]["code"])

    def test_human_review_does_not_weaken_enriched_handoff_checks(self):
        self.initialise()
        _, source_review = self.invoke("review-input", "--source", self.documents, expected_exit=0)
        self.assertEqual("not-run", source_review["handoffAssessment"])
        _, handoff = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        self.assertTrue(handoff["gaps"])
        self.assertFalse(handoff["errors"])
