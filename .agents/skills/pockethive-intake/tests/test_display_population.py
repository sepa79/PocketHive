"""Public CLI checks of exact descriptor labels, ambiguity and blank review."""
from copy import deepcopy
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, yaml_codec
from test_population import HTTP_FIXTURE


class DisplayPopulationTests(CliTestCase):
    def source(self, *, duplicate=False, wrong_path=False):
        source = self.workspace / "bundle"
        shutil.copytree(HTTP_FIXTURE, source)
        endpoint = {"callId": "read", "method": "GET", "path": "/wrong" if wrong_path else "/accounts/{{ vars.accountId }}",
                    "description": "Read the account status"}
        scenario = yaml_codec().load((source / "scenario.yaml").read_text())
        scenario["plan"] = {"endpoints": [endpoint, deepcopy(endpoint)] if duplicate else [endpoint]}
        with (source / "scenario.yaml").open("w") as stream:
            yaml_codec().dump(scenario, stream)
        return source

    def test_preview_and_population_share_exact_description_provenance(self):
        self.initialise("from-bundle", self.source())
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        req = self.read_document("requirements.yaml")
        index = next(i for i, row in enumerate(req["templates"]) if row["callId"] == "read")
        self.assertEqual("Read the account status", req["templates"][index]["name"])
        self.assertIsNone(req["project"]["name"])
        self.assertIsNone(req["templates"][index]["authorization"].get("scope"))
        trace = self.read_document("traceability.yaml")
        record = next(row for row in trace["instance"]["provenance"] if row["target"]["pointer"] == f"/templates/{index}/name")
        self.assertEqual("/plan/endpoints/0/description", record["sources"][0]["pointer"])
        self.assertEqual("bundle-observation", record["kind"])
        self.assertIsNone(record["confirmationRef"])
        req["templates"][index]["name"] = None
        self.write_document("requirements.yaml", req)
        _, reviewed = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        blank = next(row for row in reviewed["brief"]["blankFields"]["fields"]
                     if row["target"] == {"document": "requirements", "pointer": f"/templates/{index}/name"})
        self.assertEqual("population-available", blank["state"])
        self.assertEqual(record["sources"], blank["populationSources"])
        self.assertIsNone(self.read_document("requirements.yaml")["templates"][index]["name"])

    def test_ambiguous_descriptor_fails_without_writes_and_preview_reports_it(self):
        self.initialise("from-bundle", self.source(duplicate=True))
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        _, result = self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=2)
        self.assertEqual("POPULATION_AMBIGUOUS_DESCRIPTION", result["errors"][0]["code"])
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        _, result = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertEqual("POPULATION_AMBIGUOUS_DESCRIPTION", result["brief"]["blankFields"]["populationPreviewIssues"][0]["code"])

    def test_nonmatching_descriptor_does_not_supply_a_name(self):
        self.initialise("from-bundle", self.source(wrong_path=True))
        self.invoke("populate-from-inspection", "--documents", self.documents, expected_exit=0)
        self.assertTrue(all(row["name"] is None for row in self.read_document("requirements.yaml")["templates"]))

    def test_changed_source_cannot_supply_preview_candidates(self):
        source = self.source()
        self.initialise("from-bundle", source)
        (source / "scenario.yaml").write_text((source / "scenario.yaml").read_text() + "\n# changed\n")
        _, result = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertFalse(result["brief"]["blankFields"]["populationPreviewAvailable"])
        self.assertFalse(any(row.get("populationSources") for row in result["brief"]["blankFields"]["fields"]))
