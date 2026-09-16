"""Reference checks at the public CLI boundary, with incomplete drafts allowed."""

from __future__ import annotations

from copy import deepcopy

from test_intake_cli import CliTestCase


class ReferenceTests(CliTestCase):
    def partial_references(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        first = requirements["suts"][0]
        first["sutId"] = "selected-sut"
        first["endpoints"] = {"read": {"kind": "HTTP", "baseUrl": "https://selected.invalid"}}
        second = deepcopy(first)
        second["sutId"] = "other-sut"
        second["endpoints"] = {"other": {"kind": "HTTP", "baseUrl": "https://other.invalid"}}
        requirements["suts"].append(second)
        api = requirements["templates"][0]
        api["apiId"] = "API-READ"
        api["endpointRef"] = "read"
        api["protocol"] = "HTTP"
        api["method"] = "GET"
        api["payloadBindings"] = []
        plan = self.read_document("test-plan.yaml")
        plan["sutId"] = "selected-sut"
        plan["apiExecution"][0]["apiRef"] = "API-READ"
        plan["apiExecution"][0]["mode"] = "load"
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        return requirements, plan

    def finalise_and_validate(self, expected_exit=0):
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        return self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=expected_exit)[1]

    def assert_reference_error(self, output, document, pointer):
        self.assertTrue(any(issue["document"] == document and issue["pointer"].startswith(pointer)
                            for issue in output["errors"]), output["errors"])

    def test_partial_selected_sut_does_not_require_other_sut_endpoint(self):
        self.partial_references()
        output = self.finalise_and_validate()
        self.assertFalse(output["errors"])
        self.assertEqual("incomplete", output["status"])

    def test_other_sut_endpoint_cannot_satisfy_selected_sut_reference(self):
        requirements, _ = self.partial_references()
        requirements["templates"][0]["endpointRef"] = "other"
        self.write_document("requirements.yaml", requirements)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "requirements", "/templates/0/endpointRef")

    def test_duplicate_populated_ids_fail_while_unknown_ids_are_allowed(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        requirements["suts"].append(deepcopy(requirements["suts"][0]))
        self.write_document("requirements.yaml", requirements)
        self.finalise_and_validate()
        for sut in requirements["suts"]:
            sut["sutId"] = "duplicate-sut"
        self.write_document("requirements.yaml", requirements)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "requirements", "/suts")

    def test_unknown_api_reference_is_not_resolved_by_position(self):
        _, plan = self.partial_references()
        plan["apiExecution"][0]["apiRef"] = "UNKNOWN-API"
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "plan", "/apiExecution/0/apiRef")

    def test_explicit_token_dependencies_reject_self_and_cross_api_cycles(self):
        for cycle in ("self", "cross"):
            with self.subTest(cycle=cycle):
                self.documents = self.workspace / f"documents-token-{cycle}"
                requirements, plan = self.partial_references()
                api = requirements["templates"][0]
                if cycle == "cross":
                    token_api = deepcopy(api)
                    token_api["apiId"] = "API-TOKEN"
                    requirements["templates"].append(token_api)
                    token_execution = deepcopy(plan["apiExecution"][0])
                    token_execution.update({"apiRef": "API-TOKEN", "mode": "auth-prerequisite"})
                    plan["apiExecution"].append(token_execution)
                    api["authorization"] = {"type": "bearer", "tokenSource": {"apiRef": "API-TOKEN"}}
                self.write_document("requirements.yaml", requirements)
                self.write_document("test-plan.yaml", plan)
                self.finalise_and_validate()
                requirements["templates"][-1]["authorization"] = {
                    "type": "bearer", "tokenSource": {"apiRef": "API-READ"},
                }
                self.write_document("requirements.yaml", requirements)
                output = self.finalise_and_validate(expected_exit=2)
                expected = {f"/templates/{index}/authorization/tokenSource/apiRef"
                            for index in range(len(requirements["templates"]))}
                actual = {issue["pointer"] for issue in output["errors"]
                          if issue["code"] == "TOKEN_DEPENDENCY_CYCLE"
                          and issue["document"] == "requirements"}
                self.assertEqual(expected, actual, output["errors"])

    def test_unknown_entity_or_column_does_not_resolve_to_another_binding(self):
        for entity, column in (("UNKNOWN-ENTITY", "account_id"), ("known-entity", "unknown_column")):
            with self.subTest(entity=entity, column=column):
                self.documents = self.workspace / f"documents-{entity}-{column}"
                requirements, _ = self.partial_references()
                requirements["testData"]["entities"][0]["entityId"] = "known-entity"
                requirements["testData"]["entities"][0]["columns"] = [{"name": "account_id", "type": "string"}]
                requirements["templates"][0]["payloadBindings"] = [{
                    "location": "path", "path": "accountId",
                    "source": {"type": "dataset", "entityRef": entity, "column": column},
                }]
                self.write_document("requirements.yaml", requirements)
                output = self.finalise_and_validate(expected_exit=2)
                self.assert_reference_error(output, "requirements", "/templates/0/payloadBindings/0/source")

    def test_repeated_api_occurrences_have_distinct_step_identities(self):
        _, plan = self.partial_references()
        plan["executionModel"]["relationship"] = "sequential"
        plan["executionModel"]["rateUnit"] = "journeys-per-second"
        plan["executionModel"]["sequence"] = [
            {"stepId": "first-read", "apiRef": "API-READ", "thinkTimeMs": 0, "correlations": []},
            {"stepId": "second-read", "apiRef": "API-READ", "thinkTimeMs": 0, "correlations": []},
        ]
        self.write_document("test-plan.yaml", plan)
        self.finalise_and_validate()
        plan["executionModel"]["sequence"][1]["stepId"] = "first-read"
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "plan", "/executionModel/sequence")

    def test_correlation_cannot_guess_an_unknown_step_occurrence(self):
        _, plan = self.partial_references()
        plan["executionModel"]["relationship"] = "sequential"
        plan["executionModel"]["rateUnit"] = "journeys-per-second"
        plan["executionModel"]["sequence"] = [{
            "stepId": "first-read", "apiRef": "API-READ", "thinkTimeMs": 0,
            "correlations": [{"fromStepRef": "first-read", "toStepRef": "missing-step",
                              "responsePath": "/id", "location": "path", "path": "accountId", "required": True}],
        }]
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "plan", "/executionModel/sequence/0/correlations")

    def test_native_mock_dependency_requires_selected_sut_endpoint(self):
        _, plan = self.partial_references()
        plan["mockSetup"]["dependencies"] = [{
            "endpointRef": "other", "disposition": "mocked",
            "configurationRef": "native-owner-snapshot", "limitations": [],
        }]
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "plan", "/mockSetup/dependencies/0/endpointRef")
