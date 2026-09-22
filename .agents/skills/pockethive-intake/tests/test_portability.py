"""Public CLI relocation checks with original source locations unavailable."""
import hashlib
import json
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class PortabilityTests(CliTestCase):
    def bundle(self):
        bundle = self.workspace / "bundle"
        shutil.copytree(PACKAGE / "fixtures/http-population-bundle", bundle)
        self.documents = bundle / "intake"
        self.initialise("from-bundle", bundle)
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        return bundle

    def portable(self, expected_exit=0):
        return self.invoke("make-portable", "--documents", self.documents, expected_exit=expected_exit)[1]

    def test_prepared_bundle_moves_without_old_workspace_and_repeated_preparation_is_stable(self):
        bundle = self.bundle()
        source = self.invoke("inspect-bundle", "--source", bundle, expected_exit=0)[1]
        first = self.portable()
        before = {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES}
        second = self.portable()
        self.assertEqual(first["documentsSha256"], second["documentsSha256"])
        self.assertEqual(before, {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES})
        moved = self.workspace / "moved-bundle"
        bundle.rename(moved)
        self.documents = moved / "intake"
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0, offline=True)
        self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", "--write-review", expected_exit=0)
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        self.assertEqual(source["sha256"], self.invoke("inspect-bundle", "--source", moved, expected_exit=0)[1]["sha256"])
        self.assertEqual(before, {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES})

    def test_portable_bundle_preserves_source_comparison_links(self):
        bundle = self.bundle()
        self.portable()
        previous, changed = self.workspace / "previous", self.workspace / "changed"
        shutil.copytree(bundle, previous)
        shutil.copytree(bundle, changed)
        path = changed / "templates/account/read.yaml"
        path.write_text(path.read_text().replace("GET", "POST"))
        result = self.invoke("compare-source", "--documents", self.documents, "--previous-source", previous,
                             "--source", changed, expected_exit=0)[1]
        self.assertTrue(result["comparison"]["impactedTargets"])

    def test_external_evidence_and_answer_links_survive_narrative_directory_move(self):
        source = self.workspace / "client.yaml"
        source.write_text("objective: Confirm sustained service behaviour\n")
        self.initialise(source=source)
        req = self.read_document("requirements.yaml")
        req["project"]["objective"] = "Confirm sustained service behaviour"
        self.write_document("requirements.yaml", req)
        trace = self.read_document("traceability.yaml")
        trace["instance"]["provenance"] = [{"target": {"document": "requirements", "pointer": "/project/objective"},
            "kind": "client-statement", "sources": [{"artifactRef": str(source), "pointer": "/objective", "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None}]
        trace["instance"]["questions"][0].update(status="answered", answerRef=str(source))
        self.write_document("traceability.yaml", trace)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.portable()
        source.rename(self.workspace / "unavailable-original.yaml")
        moved = self.workspace / "moved-intake"
        self.documents.rename(moved)
        self.documents = moved
        result = self.invoke("validate", "--documents", moved, "--stage", "draft", expected_exit=0)[1]
        self.assertFalse(result["errors"])
        self.assertNotIn("ANSWER_EVIDENCE", {g["code"] for g in result["gaps"]})
        self.assertTrue(self.read_document("traceability.yaml")["instance"]["questions"][0]["answerRef"].startswith("evidence/"))

    def test_changed_evidence_is_not_resnapshotted_and_documents_are_unchanged(self):
        source = self.workspace / "client.txt"
        source.write_text("original input")
        self.initialise(source=source)
        before = {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES}
        source.write_text("changed input")
        result = self.portable(expected_exit=2)
        self.assertEqual("SOURCE_HASH", result["errors"][0]["code"])
        self.assertEqual(before, {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES})
        self.assertFalse((self.documents / "evidence").exists())

    def test_existing_absolute_bundle_source_is_made_relative_without_approving_review(self):
        bundle = self.bundle()
        trace = self.read_document("traceability.yaml")
        trace["instance"]["intake"]["source"]["artifactRef"] = str(bundle)
        self.write_document("traceability.yaml", trace)
        current = self.invoke("finalise", "--documents", self.documents, expected_exit=0)[1]
        trace = self.read_document("traceability.yaml")
        trace["instance"]["review"].update(status="confirmed", contentSha256=current["reviewContentSha256"])
        self.write_document("traceability.yaml", trace)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        result = self.portable(expected_exit=2)
        self.assertTrue(result["applied"])
        self.assertIn("STALE_REVIEW", {e["code"] for e in result["errors"]})
        recorded = self.read_document("traceability.yaml")["instance"]
        self.assertEqual("..", recorded["intake"]["source"]["artifactRef"])
        self.assertEqual(current["reviewContentSha256"], recorded["review"]["contentSha256"])

    def test_external_document_set_cannot_claim_portable_runtime_bundle(self):
        bundle = self.workspace / "bundle"
        shutil.copytree(PACKAGE / "fixtures/http-population-bundle", bundle)
        self.initialise("from-bundle", bundle)
        self.assertEqual("PORTABILITY_LAYOUT", self.portable(expected_exit=2)["errors"][0]["code"])

    def test_snapshot_collision_does_not_overwrite_retained_evidence(self):
        source = self.workspace / "client.yaml"
        source.write_text("objective: explicit\n")
        self.initialise(source=source)
        evidence = self.documents / "evidence"
        evidence.mkdir()
        target = evidence / (hashlib.sha256(source.read_bytes()).hexdigest() + ".yaml")
        target.write_text("different bytes\n")
        before = {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES}
        result = self.portable(expected_exit=2)
        self.assertEqual("EVIDENCE_COLLISION", result["errors"][0]["code"])
        self.assertEqual("different bytes\n", target.read_text())
        self.assertEqual(before, {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES})

    def test_unrecorded_decision_reference_does_not_get_copied_or_approved(self):
        self.initialise()
        trace = self.read_document("traceability.yaml")
        trace["instance"]["questions"][0].update(status="answered", answerRef="unknown.txt")
        self.write_document("traceability.yaml", trace)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        before = {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES}
        self.assertEqual("SOURCE_REFERENCE", self.portable(expected_exit=2)["errors"][0]["code"])
        self.assertEqual(before, {n: (self.documents / n).read_bytes() for n in DOCUMENT_NAMES})
