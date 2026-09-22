"""Public CLI checks for one intended identity owner and independent observed results."""
import hashlib
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class IdentityOwnershipTests(CliTestCase):
    def test_owner_updates_project_and_plan_edits_are_rejected(self):
        _, initial = self.initialise()
        revision = initial["documentsSha256"]
        for value in ("bundle-first", "bundle-second", None):
            source = self.workspace / "identity.txt"
            source.write_text(json.dumps({"bundleId": value}))
            target = {"document": "requirements", "pointer": "/bundleGeneration/bundleId"}
            batch = {"expectedDocumentsSha256": revision, "updates": [{
                "target": target, "value": value,
                "provenance": {"target": target, "kind": "client-statement", "confirmationRef": None,
                               "calculation": None, "sources": [{"artifactRef": str(source), "pointer": None,
                               "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}]}}]}
            path = self.workspace / "update.json"
            path.write_text(json.dumps(batch))
            _, result = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=0)
            revision = result["documentsSha256"]
            self.assertEqual(value, self.read_document("test-plan.yaml")["generation"]["bundleId"])
            self.assertIsNone(self.read_document("execution-results.yaml")["runInfo"]["bundleId"])
        for key in ("bundleId", "scenarioId"):
            _, view = self.invoke("show-field", "--documents", self.documents, "--document", "plan",
                                  "--pointer", "/generation/" + key, expected_exit=0)
            self.assertEqual("generated", view["field"]["ownership"]["owner"])
            batch["expectedDocumentsSha256"] = revision
            batch["updates"][0].update({"target": {"document": "plan", "pointer": "/generation/" + key},
                                        "value": "forbidden", "provenance": None})
            path.write_text(json.dumps(batch))
            _, result = self.invoke("apply-updates", "--documents", self.documents, "--input", path, expected_exit=2)
            self.assertEqual("UPDATE_PROTECTED", result["errors"][0]["code"])

    def test_legacy_plan_only_identity_is_not_silently_discarded(self):
        self.initialise()
        plan = self.read_document("test-plan.yaml")
        plan["generation"]["scenarioId"] = "unrelocated-identity"
        self.write_document("test-plan.yaml", plan)
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        _, result = self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        self.assertEqual("IDENTITY_PROJECTION_CONFLICT", result["errors"][0]["code"])
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        _, result = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any(row["code"] == "STALE_PROJECTION" and row["pointer"] == "/generation/scenarioId"
                            for row in result["errors"]))
