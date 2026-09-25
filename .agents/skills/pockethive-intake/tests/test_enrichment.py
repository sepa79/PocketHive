"""Public CLI outcomes for human-form enrichment and content-based resume."""
import hashlib
import json
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class EnrichmentTests(CliTestCase):
    def plan(self, text="project:\n  name: Client service\n  objective: Verify sustained journeys\nopenQuestions: [How long should steady state last?]\ncustom: Keep this requirement\n", name="client.yaml"):
        source = self.workspace / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(text)
        plan = {"sources": [{"id": "client", "path": str(source), "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}],
                "transfers": [{"source": "client", "pointer": "/project/name", "target": {"document": "requirements", "pointer": "/project/name"}, "kind": "client-statement"},
                              {"source": "client", "pointer": "/project/objective", "target": {"document": "requirements", "pointer": "/project/objective"}, "kind": "client-statement"}],
                "questions": [{"source": "client", "pointer": "/openQuestions/0", "targets": [], "blockingStage": "handoff"}], "omissions": []}
        return source, plan

    def enrich(self, plan, expected_exit=0, *extra):
        path = self.workspace / "transfer.json"
        path.write_text(json.dumps(plan))
        return self.invoke("enrich-input", "--output", self.documents, "--input", path,
                           "--mode", "new-requirements", *extra, expected_exit=expected_exit)[1]

    def test_raw_form_becomes_sourced_draft_with_original_and_unmapped_scope_retained(self):
        source, plan = self.plan()
        original = source.read_bytes()
        result = self.enrich(plan)
        self.assertTrue(result["applied"])
        self.assertEqual(1, result["enrichment"]["untransferredFields"])
        self.assertEqual("Client service", self.read_document("requirements.yaml")["project"]["name"])
        trace = self.read_document("traceability.yaml")["instance"]
        self.assertEqual(2, len(trace["provenance"]))
        self.assertEqual("How long should steady state last?", trace["questions"][0]["question"])
        self.assertEqual("review", trace["questions"][1]["blockingStage"])
        self.assertEqual(original, (self.documents / trace["provenance"][0]["sources"][0]["artifactRef"]).read_bytes())
        self.assertEqual(original, source.read_bytes())
        receipt = json.loads((self.documents / "enrichment-report.json").read_text())
        self.assertEqual(["/custom"], receipt["untransferred"][0]["pointers"])
        self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", "--view", "summary", "--write-review", expected_exit=0)
        self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)

    def test_raw_working_filename_is_archived_before_replacement_and_repeat_cannot_overwrite(self):
        source, plan = self.plan(name="documents/requirements.yaml")
        original = source.read_bytes()
        self.enrich(plan)
        self.assertNotEqual(original, source.read_bytes())
        self.assertIn(original, [p.read_bytes() for p in (self.documents / "evidence").iterdir()])
        before = {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES}
        result = self.enrich(plan, expected_exit=2)
        self.assertEqual("SOURCE_HASH", result["errors"][0]["code"])
        self.assertEqual(before, {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES})

    def test_source_change_and_protected_transfer_fail_without_creating_output(self):
        source, plan = self.plan()
        plan["sources"][0]["sha256"] = "0" * 64
        self.assertEqual("SOURCE_HASH", self.enrich(plan, expected_exit=2)["errors"][0]["code"])
        self.assertFalse(self.documents.exists())
        plan["sources"][0]["sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
        plan["transfers"][0]["target"]["pointer"] = "/requirementId"
        self.assertEqual("UPDATE_PROTECTED", self.enrich(plan, expected_exit=2)["errors"][0]["code"])
        self.assertFalse(self.documents.exists())

    def test_wrong_target_type_preserves_originals_and_does_not_publish_partial_set(self):
        source, plan = self.plan()
        plan["transfers"][0]["target"]["pointer"] = "/templates"
        original = source.read_bytes()
        result = self.enrich(plan, expected_exit=2)
        self.assertFalse(result["outputWritten"])
        self.assertFalse(result["applied"])
        self.assertFalse(self.documents.exists())
        self.assertEqual(original, source.read_bytes())

    def test_explicit_omission_resolves_only_named_scope_and_overlap_is_rejected(self):
        _, plan = self.plan()
        plan["omissions"] = [{"source": "client", "pointer": "/custom", "reason": "Client identified this as illustrative text."}]
        result = self.enrich(plan)
        self.assertEqual(0, result["enrichment"]["untransferredFields"])
        self.assertEqual(1, len(self.read_document("traceability.yaml")["instance"]["questions"]))
        self.documents = self.workspace / "other"
        plan["omissions"][0]["pointer"] = "/project"
        self.assertEqual("ENRICHMENT_OVERLAP", self.enrich(plan, expected_exit=2)["errors"][0]["code"])
        self.assertFalse(self.documents.exists())

    def test_example_requires_adoption_and_cannot_import_approval(self):
        sample = PACKAGE / "assets/source/PERFORMANCE_TEST_REQUIREMENTS_SAMPLE_V1.yaml"
        plan = {"sources": [{"id": "sample", "path": str(sample), "sha256": hashlib.sha256(sample.read_bytes()).hexdigest()}],
                "transfers": [{"source": "sample", "pointer": "/project/name", "target": {"document": "requirements", "pointer": "/project/name"}, "kind": "client-statement"}],
                "questions": [], "omissions": []}
        result = self.enrich(plan)
        self.assertIn("SAMPLE_ADOPTION", {g["code"] for g in result["gaps"]})
        self.documents = self.workspace / "approval-attempt"
        plan["transfers"][0]["kind"] = "approval"
        self.assertEqual("ENRICHMENT_KIND", self.enrich(plan, expected_exit=2)["errors"][0]["code"])

    def test_bundle_enrichment_preserves_runtime_identity(self):
        bundle = self.workspace / "bundle"
        shutil.copytree(PACKAGE / "fixtures/http-population-bundle", bundle)
        self.documents = bundle / "intake"
        _, plan = self.plan()
        transfer = self.workspace / "transfer.json"
        transfer.write_text(json.dumps(plan))
        before = self.invoke("inspect-bundle", "--source", bundle, expected_exit=0)[1]
        self.invoke("enrich-input", "--output", self.documents, "--input", transfer, "--mode", "from-bundle", "--source", bundle, expected_exit=0)
        self.assertEqual("..", self.read_document("traceability.yaml")["instance"]["intake"]["source"]["artifactRef"])
        after = self.invoke("inspect-bundle", "--source", bundle, expected_exit=0)[1]
        self.assertEqual(before["sha256"], after["sha256"])

    def test_assessment_uses_contents_for_raw_complete_and_mixed_sets(self):
        self.documents.mkdir()
        for name in DOCUMENT_NAMES:
            (self.documents / name).write_text("scope:\nname:\n")
        result = self.invoke("assess-workspace", "--documents", self.documents, expected_exit=0)[1]
        self.assertEqual("raw", result["directoryState"])
        self.assertIn("review-input", result["nextAction"])
        self.documents = self.workspace / "working"
        self.initialise()
        result = self.invoke("assess-workspace", "--documents", self.documents, expected_exit=0)[1]
        self.assertEqual("complete", result["directoryState"])
        (self.documents / "another-form.yaml").write_text("scope: revised\n")
        result = self.invoke("assess-workspace", "--documents", self.documents, expected_exit=0)[1]
        self.assertEqual("mixed", result["directoryState"])
        self.assertTrue(result["workingDocumentsComplete"])
        self.assertIn("prepare-review", result["nextAction"])
        _, plan = self.plan()
        self.assertEqual("ENRICHMENT_WORKSPACE", self.enrich(plan, expected_exit=2)["errors"][0]["code"])

    def test_unchanged_working_scaffolds_are_raw_until_enriched_metadata_exists(self):
        self.documents.mkdir()
        for path in (PACKAGE / "assets/templates").glob("*.yaml"):
            shutil.copyfile(path, self.documents / path.name)
        result = self.invoke("assess-workspace", "--documents", self.documents, expected_exit=0)[1]
        self.assertEqual("raw", result["directoryState"])
        self.assertFalse(result["workingDocumentsComplete"])

    def test_invalid_imported_question_fails_without_publishing(self):
        _, plan = self.plan()
        plan["questions"][0]["blockingStage"] = "invented-stage"
        result = self.enrich(plan, expected_exit=2)
        self.assertEqual("candidate", result["validation"]["subject"])
        self.assertFalse(self.documents.exists())
