"""Public-CLI regressions for withholding credential-bearing observation values."""
from __future__ import annotations

import hashlib
import json
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


WITHHELD = "OBSERVATION_VALUE_WITHHELD"


class ObservationRedactionTests(CliTestCase):
    def source(self, name="bundle"):
        source = self.workspace / name
        source.mkdir()
        self.write_json(source / "scenario.yaml", {"rate": 25})
        return source

    @staticmethod
    def write_json(path, value):
        # JSON is also a supported YAML representation; quote opaque fixture
        # strings without relying on application parsing or redaction helpers.
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

    def template(self, source, name, path, **overrides):
        value = {"serviceId": "account", "callId": name, "protocol": "HTTP",
                 "method": "GET", "pathTemplate": path, **overrides}
        target = source / f"{name}.yaml"
        self.write_json(target, value)
        return target

    @staticmethod
    def tree_bytes(root):
        return {path.relative_to(root).as_posix(): path.read_bytes()
                for path in root.rglob("*") if path.is_file()}

    def assert_not_emitted(self, markers, *processes):
        output = "\n".join(process.stdout + process.stderr for process in processes)
        for marker in markers:
            self.assertNotIn(marker, output)

    def assert_inventory(self, inspection, before):
        self.assertEqual(set(before), {row["path"] for row in inspection["files"]})
        for row in inspection["files"]:
            self.assertEqual(hashlib.sha256(before[row["path"]]).hexdigest(), row["sha256"])
            self.assertEqual(len(before[row["path"]]), row["bytes"])

    def test_unsafe_paths_and_endpoint_aliases_are_withheld_without_key_guessing(self):
        source = self.source()
        values = [
            "/items?api_key=synthetic-query-key-canary",
            "/items?ordinary=synthetic-ordinary-query-canary",
            "/items?a%70i%5Fkey=synthetic-encoded-name-canary",
            "/items?x=synthetic-repeated-query-canary&x=second",
            "/items#synthetic-fragment-canary",
            "https://user:synthetic-userinfo-canary@example.invalid/items",
            "//user:synthetic-network-userinfo-canary@example.invalid/items",
            "/items%3Fapi_key%3Dsynthetic-encoded-delimiter-canary",
            "/items%253Fapi_key%253Dsynthetic-double-encoded-canary",
            "/items/{{ '/nested?key=synthetic-expression-query-canary' }}",
            "/items\\synthetic-backslash-canary",
            "/items\nsynthetic-control-canary",
            "/items\tsynthetic-tab-canary",
            "urn:synthetic-uri-scheme-canary",
            "https://[synthetic-malformed-uri-canary",
            "/items?",
            "/items#",
            "/items%20encoded-path",
        ]
        endpoints = []
        for index, value in enumerate(values):
            name = f"request-{index:02d}"
            self.template(source, name, value)
            endpoints.append({"callId": name, "method": "GET", "path": value,
                              "description": "Read the account status"})
        self.write_json(source / "scenario.yaml", {"plan": {"endpoints": endpoints}})
        before = self.tree_bytes(source)

        process, result = self.invoke("inspect-bundle", "--source", source,
                                      expected_exit=0, offline=True)

        self.assert_not_emitted(["synthetic-", *values], process)
        self.assertEqual(before, self.tree_bytes(source))
        self.assert_inventory(result, before)
        observed = {(row["artifactRef"], row["pointer"]) for row in result["observations"]}
        limitations = {(row["document"], row["pointer"])
                       for row in result["limitations"] if row["code"] == WITHHELD}
        coverage = {row["path"]: row for row in result["coverage"]["files"]}
        for index in range(len(values)):
            template_pointer = (f"request-{index:02d}.yaml", "/pathTemplate")
            endpoint_pointer = ("scenario.yaml", f"/plan/endpoints/{index}/path")
            for identity in (template_pointer, endpoint_pointer):
                self.assertNotIn(identity, observed)
                self.assertIn(identity, limitations)
            row = coverage[template_pointer[0]]
            self.assertEqual(1, row["sensitiveScalarCount"])
            self.assertEqual(4, row["observationCount"])
            self.assertEqual(0, row["unextractedScalarCount"])
            self.assertTrue(row["reviewRequired"])
        self.assertEqual(len(values), coverage["scenario.yaml"]["sensitiveScalarCount"])
        self.assertTrue(coverage["scenario.yaml"]["reviewRequired"])
        self.assertFalse(any(result["claims"].values()))

    def test_direct_urls_in_other_allowlisted_strings_use_the_same_boundary(self):
        source = self.source()
        self.template(source, "read", "/accounts/read",
                      serviceId="https://user:synthetic-identity-canary@example.invalid/api")
        self.write_json(source / "scenario.yaml", {"plan": {"endpoints": [{
            "callId": "read", "method": "GET", "path": "/accounts/read",
            "description": "Read via https://user:synthetic-description-canary@example.invalid/api",
        }]}})
        process, result = self.invoke("inspect-bundle", "--source", source,
                                      expected_exit=0, offline=True)
        self.assert_not_emitted(["synthetic-identity-canary", "synthetic-description-canary"], process)
        observed = {(row["artifactRef"], row["pointer"]) for row in result["observations"]}
        self.assertNotIn(("read.yaml", "/serviceId"), observed)
        self.assertNotIn(("scenario.yaml", "/plan/endpoints/0/description"), observed)
        self.assertIn(("read.yaml", "/pathTemplate"), observed)
        withheld = {(row["document"], row["pointer"]) for row in result["limitations"]
                    if row["code"] == WITHHELD}
        self.assertEqual({("read.yaml", "/serviceId"),
                          ("scenario.yaml", "/plan/endpoints/0/description")}, withheld)

    def test_initialise_and_population_never_persist_withheld_path_values(self):
        source = self.source()
        marker = "synthetic-persisted-query-canary"
        self.template(source, "read", "/items?unremarkable=" + marker)
        before = self.tree_bytes(source)
        inspect_process, inspected = self.invoke("inspect-bundle", "--source", source,
                                                 expected_exit=0, offline=True)
        initialise_process, _ = self.initialise("from-bundle", source)
        saved = json.loads((self.documents / "source-inspection.json").read_text())
        self.assertEqual(inspected["sha256"], saved["sha256"])
        self.assertEqual(inspected["observations"], saved["observations"])
        self.assertEqual(inspected["coverage"], saved["coverage"])
        self.assert_inventory(saved, before)

        populate_process, populated = self.invoke("populate-from-inspection", "--documents", self.documents,
                                                 expected_exit=0, offline=True)
        self.assert_not_emitted([marker], inspect_process, initialise_process, populate_process)
        row = self.read_document("requirements.yaml")["templates"][0]
        self.assertEqual("read", row["callId"])
        self.assertEqual("GET", row["method"])
        self.assertIsNone(row["path"])
        self.assertTrue(any(issue["code"] == WITHHELD and issue["pointer"] == "/pathTemplate"
                            for issue in populated["gaps"]))
        self.assertTrue(any(issue["code"] == "TEMPLATE_FIELD_UNAVAILABLE"
                            and issue["pointer"] == "/pathTemplate" for issue in populated["gaps"]))
        for path in self.documents.rglob("*"):
            if path.is_file():
                self.assertNotIn(marker.encode(), path.read_bytes(), path.name)
        self.assertEqual(before, self.tree_bytes(source))
        provenance = self.read_document("traceability.yaml")["instance"]["provenance"]
        self.assertFalse(any(item["target"]["pointer"].endswith("/path") for item in provenance))

    def test_plain_path_templates_and_exact_source_provenance_are_preserved(self):
        source = self.source()
        paths = {"literal": "/accounts/submit", "templated": "/accounts/{{ vars.accountId }}"}
        for name, value in paths.items():
            self.template(source, name, value)
        self.write_json(source / "scenario.yaml", {"plan": {"endpoints": [{
            "callId": "templated", "method": "GET", "path": paths["templated"],
            "description": "Read the account status",
        }]}})
        before = self.tree_bytes(source)
        _, inspection = self.invoke("inspect-bundle", "--source", source, expected_exit=0, offline=True)
        self.assertFalse(any(issue["code"] == WITHHELD for issue in inspection["limitations"]))
        self.initialise("from-bundle", source)
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0, offline=True)
        rows = self.read_document("requirements.yaml")["templates"]
        self.assertEqual(paths, {row["callId"]: row["path"] for row in rows})
        templated = next(row for row in rows if row["callId"] == "templated")
        self.assertEqual("Read the account status", templated["name"])
        provenance = self.read_document("traceability.yaml")["instance"]["provenance"]
        path_records = [record for record in provenance if record["target"]["pointer"].endswith("/path")]
        self.assertEqual(2, len(path_records))
        for record in path_records:
            index = int(record["target"]["pointer"].split("/")[2])
            name = rows[index]["callId"]
            self.assertEqual("bundle-observation", record["kind"])
            self.assertEqual([{"artifactRef": str(source / f"{name}.yaml"), "pointer": "/pathTemplate",
                               "sha256": hashlib.sha256(before[f"{name}.yaml"]).hexdigest()}], record["sources"])
        self.assertEqual(before, self.tree_bytes(source))

    def test_comparison_withholds_both_snapshots_and_preserves_file_change_evidence(self):
        previous = self.source("previous")
        old = {"both": "/items?key=synthetic-old-canary", "removed": "/safe-before",
               "added": "/items?key=synthetic-removed-canary"}
        new = {"both": "/items?other=synthetic-new-canary", "removed": "/items#synthetic-fragment-canary",
               "added": "/safe-after"}
        for name, value in old.items():
            self.template(previous, name, value)
        self.initialise("from-bundle", previous)
        current = self.workspace / "current"
        shutil.copytree(previous, current)
        for name, value in new.items():
            self.template(current, name, value)
        before_documents = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        before_sources = [self.tree_bytes(root) for root in (previous, current)]

        process, output = self.invoke("compare-source", "--documents", self.documents,
                                      "--previous-source", previous, "--source", current,
                                      expected_exit=0, offline=True)
        self.assert_not_emitted(["synthetic-"], process)
        comparison = output["comparison"]
        self.assertEqual({f"{name}.yaml" for name in old}, {row["path"] for row in comparison["changedFiles"]})
        changed = {row["artifactRef"]: row for row in comparison["changedObservations"]}
        self.assertNotIn("both.yaml", changed)
        self.assertEqual({"artifactRef": "removed.yaml", "pointer": "/pathTemplate", "change": "removed",
                          "previousValue": "/safe-before", "value": None}, changed["removed.yaml"])
        self.assertEqual({"artifactRef": "added.yaml", "pointer": "/pathTemplate", "change": "added",
                          "previousValue": None, "value": "/safe-after"}, changed["added.yaml"])
        withheld = {(issue["snapshot"], issue["document"], issue["pointer"])
                    for issue in comparison["limitations"] if issue["code"] == WITHHELD}
        self.assertIn(("previous", "both.yaml", "/pathTemplate"), withheld)
        self.assertIn(("current", "both.yaml", "/pathTemplate"), withheld)
        self.assertTrue(any(issue["code"] == "SOURCE_CHANGE_UNOBSERVED" and issue["document"] == "both.yaml"
                            for issue in comparison["limitations"]))
        self.assertEqual(before_documents, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        self.assertEqual(before_sources, [self.tree_bytes(root) for root in (previous, current)])

    def test_stale_saved_inspection_is_never_reused_or_silently_rewritten(self):
        source = self.source()
        marker = "synthetic-stale-report-canary"
        self.template(source, "read", "/items?key=" + marker)
        self.initialise("from-bundle", source)
        report_path = self.documents / "source-inspection.json"
        stale = json.loads(report_path.read_text())
        stale["observations"].append({"artifactRef": "read.yaml", "pointer": "/pathTemplate",
                                      "value": "/items?key=" + marker,
                                      "sha256": hashlib.sha256((source / "read.yaml").read_bytes()).hexdigest(),
                                      "kind": "bundle-observation"})
        self.write_json(report_path, stale)
        stale_bytes = report_path.read_bytes()
        process, result = self.invoke("populate-from-inspection", "--documents", self.documents,
                                      expected_exit=0, offline=True)
        self.assert_not_emitted([marker], process)
        self.assertIsNone(self.read_document("requirements.yaml")["templates"][0]["path"])
        self.assertTrue(any(issue["code"] == WITHHELD for issue in result["gaps"]))
        reviewed, _ = self.invoke("prepare-review", "--documents", self.documents,
                                 "--stage", "draft", expected_exit=0, offline=True)
        self.assert_not_emitted([marker], reviewed)
        for name in DOCUMENT_NAMES:
            self.assertNotIn(marker.encode(), (self.documents / name).read_bytes())
        # Fixing new extraction is not an implicit migration of existing client artifacts.
        self.assertEqual(stale_bytes, report_path.read_bytes())
