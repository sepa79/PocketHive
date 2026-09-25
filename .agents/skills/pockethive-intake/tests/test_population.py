"""Public CLI qualification of sourced, non-destructive HTTP template population."""
from __future__ import annotations

from copy import deepcopy
import hashlib
from pathlib import Path
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, FIXTURE, PACKAGE


HTTP_FIXTURE = PACKAGE / "fixtures" / "http-population-bundle"


class PopulationTests(CliTestCase):
    def source_copy(self):
        root = self.workspace / "source"
        shutil.copytree(HTTP_FIXTURE, root)
        return root

    def populate(self, expected_exit=0):
        return self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=expected_exit)

    def document_bytes(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def test_explicit_http_facts_populate_without_adopting_client_intent(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        self.populate()
        req = self.read_document("requirements.yaml")
        rows = {row["callId"]: row for row in req["templates"]}
        self.assertEqual({"read", "submit"}, set(rows))
        self.assertEqual("GET", rows["read"]["method"])
        self.assertEqual("/accounts/{{ vars.accountId }}", rows["read"]["path"])
        self.assertTrue(all(row["protocol"] == "HTTP" for row in rows.values()))
        self.assertTrue(all(row["apiId"].startswith("API-OBS-") for row in rows.values()))
        self.assertTrue(all(row["name"] is None and row["endpointRef"] is None for row in rows.values()))
        self.assertIsNone(req["kpis"][0]["targetTps"])
        self.assertIsNone(self.read_document("test-plan.yaml")["sutId"])
        ledger = self.read_document("traceability.yaml")["instance"]
        self.assertEqual("draft", ledger["review"]["status"])
        observed = [row for row in ledger["provenance"] if row["kind"] == "bundle-observation"]
        self.assertEqual(10, len(observed))
        self.assertTrue(all(row["confirmationRef"] is None for row in observed))
        for row in observed:
            source = row["sources"][0]
            path = Path(source["artifactRef"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), source["sha256"])
            self.assertIn(source["pointer"], {"/serviceId", "/callId", "/protocol", "/method", "/pathTemplate"})
        raw = b"\n".join(self.document_bytes().values())
        self.assertNotIn(b"synthetic-sensitive-header", raw)
        self.assertNotIn(b"synthetic-sensitive-body", raw)
        _, validation = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertFalse(validation["errors"])
        self.assertTrue(any(row["code"] == "CONFIRMATION_EVIDENCE" for row in validation["gaps"]))

    def test_repeat_population_preserves_bytes_and_never_duplicates_provenance(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        self.populate()
        before = self.document_bytes()
        _, result = self.populate()
        self.assertEqual(before, self.document_bytes())
        self.assertEqual([], result["population"]["createdTemplates"])
        self.assertEqual([], result["population"]["filledFields"])

    def test_existing_api_identity_and_client_fields_are_preserved(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        req = self.read_document("requirements.yaml")
        req["templates"][0].update({"apiId": "CLIENT-READ", "serviceId": "account", "callId": "read",
                                    "name": "Client supplied account read name"})
        self.write_document("requirements.yaml", req)
        self.populate()
        row = next(row for row in self.read_document("requirements.yaml")["templates"] if row["callId"] == "read")
        self.assertEqual("CLIENT-READ", row["apiId"])
        self.assertEqual("Client supplied account read name", row["name"])
        self.assertEqual("GET", row["method"])

    def test_conflict_fails_before_any_document_write(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        req = self.read_document("requirements.yaml")
        req["templates"][0].update({"apiId": "CLIENT-SUBMIT", "serviceId": "account", "callId": "submit", "method": "DELETE"})
        self.write_document("requirements.yaml", req)
        before = self.document_bytes()
        _, result = self.populate(expected_exit=2)
        self.assertEqual("POPULATION_CONFLICT", result["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_changed_recorded_source_fails_before_any_document_write(self):
        source = self.source_copy()
        self.initialise("from-bundle", source)
        path = source / "templates/account/read.yaml"
        path.write_text(path.read_text().replace("method: GET", "method: DELETE"))
        before = self.document_bytes()
        _, result = self.populate(expected_exit=2)
        self.assertEqual("SOURCE_HASH", result["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_duplicate_source_identity_fails_without_population(self):
        source = self.source_copy()
        shutil.copyfile(source / "templates/account/read.yaml", source / "templates/account/duplicate.yaml")
        self.initialise("from-bundle", source)
        before = self.document_bytes()
        _, result = self.populate(expected_exit=2)
        self.assertEqual("POPULATION_DUPLICATE_TEMPLATE", result["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_partial_existing_identity_is_not_guessed(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        req = self.read_document("requirements.yaml")
        req["templates"][0].update({"apiId": "CLIENT-PARTIAL", "serviceId": "account"})
        self.write_document("requirements.yaml", req)
        before = self.document_bytes()
        _, result = self.populate(expected_exit=2)
        self.assertEqual("POPULATION_CONFLICT", result["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_existing_duplicates_use_the_same_diagnostic_as_validation(self):
        for key in ("apiId", "serviceId/callId"):
            with self.subTest(identity=key):
                self.documents = self.workspace / key.replace("/", "-")
                self.initialise("from-bundle", HTTP_FIXTURE)
                req = self.read_document("requirements.yaml")
                first = req["templates"][0]
                first.update({"apiId": "CLIENT-A", "serviceId": "manual", "callId": "one"})
                second = deepcopy(first)
                second.update({"apiId": "CLIENT-B", "callId": "two"})
                if key == "apiId":
                    second["apiId"] = first["apiId"]
                else:
                    second["callId"] = first["callId"]
                req["templates"].append(second)
                self.write_document("requirements.yaml", req)
                self.invoke("finalise", "--documents", self.documents, expected_exit=0)
                before = self.document_bytes()
                _, validation = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
                _, population = self.populate(expected_exit=2)
                duplicates = [issue for issue in validation["errors"] if issue["code"] == "DUPLICATE_ID"]
                self.assertEqual(1, len(duplicates))
                self.assertEqual(duplicates, population["errors"])
                self.assertEqual(before, self.document_bytes())

    def test_planned_api_identity_collision_fails_before_writing(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        self.populate()
        observed_id = next(row["apiId"] for row in self.read_document("requirements.yaml")["templates"]
                           if row["callId"] == "read")
        self.documents = self.workspace / "conflicting-documents"
        self.initialise("from-bundle", HTTP_FIXTURE)
        req = self.read_document("requirements.yaml")
        req["templates"][0].update({"apiId": observed_id, "serviceId": "manual", "callId": "other"})
        self.write_document("requirements.yaml", req)
        before = self.document_bytes()
        _, result = self.populate(expected_exit=2)
        self.assertEqual("DUPLICATE_ID", result["errors"][0]["code"])
        self.assertEqual("requirements", result["errors"][0]["document"])
        self.assertEqual("/templates/1/apiId", result["errors"][0]["pointer"])
        self.assertEqual(before, self.document_bytes())

    def test_missing_population_containers_report_the_required_pointer(self):
        for document, pointer in (("requirements", "/templates"), ("traceability", "/instance/intake"),
                                  ("traceability", "/instance/provenance")):
            with self.subTest(document=document, pointer=pointer):
                self.documents = self.workspace / pointer.replace("/", "-")
                self.initialise("from-bundle", HTTP_FIXTURE)
                data = self.read_document(document + ".yaml")
                parent = data if pointer == "/templates" else data["instance"]
                del parent[pointer.rsplit("/", 1)[1]]
                self.write_document(document + ".yaml", data)
                before = self.document_bytes()
                _, result = self.populate(expected_exit=2)
                self.assertEqual("POINTER", result["errors"][0]["code"])
                self.assertEqual(document, result["errors"][0]["document"])
                self.assertEqual(pointer, result["errors"][0]["pointer"])
                self.assertEqual(before, self.document_bytes())

    def test_unrelated_incomplete_references_do_not_block_observed_population(self):
        self.initialise("from-bundle", HTTP_FIXTURE)
        plan = self.read_document("test-plan.yaml")
        plan["apiExecution"][0]["apiRef"] = "CLIENT-STILL-TO-DECLARE"
        self.write_document("test-plan.yaml", plan)
        self.populate()
        self.assertEqual(2, len(self.read_document("requirements.yaml")["templates"]))
        self.assertEqual("CLIENT-STILL-TO-DECLARE", self.read_document("test-plan.yaml")["apiExecution"][0]["apiRef"])

    def test_unsupported_transport_and_missing_native_path_stay_visible(self):
        self.initialise("from-bundle", FIXTURE)
        _, result = self.populate()
        rows = self.read_document("requirements.yaml")["templates"]
        self.assertEqual(1, len(rows))
        self.assertEqual("read-account", rows[0]["callId"])
        self.assertIsNone(rows[0]["path"])
        self.assertIn("HTTP_TEMPLATE_PROTOCOL", {row["code"] for row in result["gaps"]})
        self.assertIn("TEMPLATE_FIELD_UNAVAILABLE", {row["code"] for row in result["gaps"]})
        self.assertFalse(self.marker.exists())

    def test_no_supported_templates_returns_explicit_gap(self):
        source = self.workspace / "empty-source"
        source.mkdir()
        shutil.copyfile(HTTP_FIXTURE / "scenario.yaml", source / "scenario.yaml")
        self.initialise("from-bundle", source)
        _, result = self.populate()
        self.assertEqual("incomplete", result["status"])
        self.assertIn("NO_SUPPORTED_HTTP_TEMPLATES", {row["code"] for row in result["gaps"]})
        self.assertIsNone(self.read_document("requirements.yaml")["templates"][0]["apiId"])

    def test_new_requirements_cannot_silently_switch_source_mode(self):
        self.initialise()
        before = self.document_bytes()
        _, result = self.populate(expected_exit=2)
        self.assertEqual("POPULATION_SOURCE_MODE", result["errors"][0]["code"])
        self.assertEqual(before, self.document_bytes())

    def test_nested_template_protocol_is_not_promoted_to_top_level(self):
        source = self.workspace / "nested-source"
        source.mkdir()
        shutil.copyfile(HTTP_FIXTURE / "scenario.yaml", source / "scenario.yaml")
        (source / "sequence.yaml").write_text("steps:\n  - serviceId: nested\n    callId: read\n    protocol: HTTP\n    method: GET\n    pathTemplate: /nested\n")
        self.initialise("from-bundle", source)
        _, result = self.populate()
        self.assertIn("NESTED_TEMPLATE_UNSUPPORTED", {row["code"] for row in result["gaps"]})
        self.assertIsNone(self.read_document("requirements.yaml")["templates"][0]["apiId"])

    def test_http_filename_does_not_supply_missing_protocol(self):
        source = self.workspace / "undeclared-source"
        source.mkdir()
        shutil.copyfile(HTTP_FIXTURE / "scenario.yaml", source / "scenario.yaml")
        folder = source / "templates/http"
        folder.mkdir(parents=True)
        (folder / "http-read.yaml").write_text("serviceId: declared\ncallId: read\nmethod: GET\npathTemplate: /read\n")
        self.initialise("from-bundle", source)
        _, result = self.populate()
        self.assertIn("HTTP_TEMPLATE_PROTOCOL", {row["code"] for row in result["gaps"]})
        self.assertIsNone(self.read_document("requirements.yaml")["templates"][0]["apiId"])
