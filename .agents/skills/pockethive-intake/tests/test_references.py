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

    def test_http_template_reference_can_target_https_endpoint(self):
        requirements, _ = self.partial_references()
        requirements["suts"][0]["endpoints"]["read"]["kind"] = "HTTPS"
        self.write_document("requirements.yaml", requirements)
        output = self.finalise_and_validate()
        self.assertFalse(output["errors"])
        self.assertEqual("incomplete", output["status"])

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
                    api["authorization"] = {"type": "bearer-token", "tokenSource": {"apiRef": "API-TOKEN"}}
                self.write_document("requirements.yaml", requirements)
                self.write_document("test-plan.yaml", plan)
                self.finalise_and_validate()
                requirements["templates"][-1]["authorization"] = {
                    "type": "bearer-token", "tokenSource": {"apiRef": "API-READ"},
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
            "correlations": [{"correlationId": "account-id", "fromStepRef": "first-read",
                              "toStepRef": "missing-step", "responsePath": "/id", "required": True}],
        }]
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "plan", "/executionModel/sequence/0/correlations")

    def correlated_sequence(self):
        requirements, plan = self.partial_references()
        consumer = deepcopy(requirements["templates"][0])
        consumer["apiId"] = "API-USE"
        consumer["payloadBindings"] = [{"location": "path", "path": "accountId",
                                        "source": {"type": "correlation", "correlationRef": "account-id"}}]
        requirements["templates"].append(consumer)
        consumer_execution = deepcopy(plan["apiExecution"][0])
        consumer_execution["apiRef"] = "API-USE"
        plan["apiExecution"].append(consumer_execution)
        for execution in plan["apiExecution"]:
            execution["requestsPerJourney"] = 2
        model = plan["executionModel"]
        model["relationship"] = "sequential"
        model["rateUnit"] = "journeys-per-second"
        model["correlationFailureAction"] = "abort-journey"
        model["sequence"] = []
        for suffix in ("first", "second"):
            source = f"source-{suffix}"
            destination = f"use-{suffix}"
            model["sequence"].extend([
                {"stepId": source, "apiRef": "API-READ", "thinkTimeMs": 0, "correlations": []},
                {"stepId": destination, "apiRef": "API-USE", "thinkTimeMs": 0,
                 "correlations": [{"correlationId": "account-id", "fromStepRef": source,
                                   "responsePath": "/account/id", "toStepRef": destination, "required": True}]},
            ])
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        return requirements, plan

    def test_correlation_binding_resolves_each_repeated_api_occurrence(self):
        _, plan = self.correlated_sequence()
        plan["executionModel"]["sequence"][1]["correlations"][0]["responsePath"] = ""
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate()
        self.assertFalse(output["errors"])
        self.assertFalse(any(issue["code"] in ("REQUIRED_INPUT", "CORRELATION_SETTING") and
                             issue["pointer"].startswith("/executionModel/sequence")
                             for issue in output["gaps"]), output["gaps"])

    def test_correlation_rejects_unknown_forward_self_and_wrong_step_references(self):
        cases = (
            ("unknown", "fromStepRef", "API-READ", "UNKNOWN_REFERENCE"),
            ("forward", "fromStepRef", "source-second", "CORRELATION_ORDER"),
            ("self", "fromStepRef", "use-first", "CORRELATION_ORDER"),
            ("wrong-destination", "toStepRef", "use-second", "REFERENCE_MISMATCH"),
        )
        for case, field, value, code in cases:
            with self.subTest(case=case):
                self.documents = self.workspace / f"documents-correlation-{case}"
                _, plan = self.correlated_sequence()
                plan["executionModel"]["sequence"][1]["correlations"][0][field] = value
                self.write_document("test-plan.yaml", plan)
                output = self.finalise_and_validate(expected_exit=2)
                self.assertTrue(any(issue["code"] == code and issue["pointer"] ==
                                    f"/executionModel/sequence/1/correlations/0/{field}"
                                    for issue in output["errors"]), output["errors"])

    def test_correlation_ids_reject_unknown_bindings_duplicate_ids_and_orphans(self):
        for case in ("unknown-binding", "duplicate", "orphan"):
            with self.subTest(case=case):
                self.documents = self.workspace / f"documents-correlation-{case}"
                requirements, plan = self.correlated_sequence()
                correlations = plan["executionModel"]["sequence"][1]["correlations"]
                if case == "unknown-binding":
                    requirements["templates"][1]["payloadBindings"][0]["source"]["correlationRef"] = "missing-id"
                else:
                    added = deepcopy(correlations[0])
                    if case == "orphan":
                        added["correlationId"] = "unused-id"
                    correlations.append(added)
                self.write_document("requirements.yaml", requirements)
                self.write_document("test-plan.yaml", plan)
                output = self.finalise_and_validate(expected_exit=2)
                code = "DUPLICATE_ID" if case == "duplicate" else "UNKNOWN_REFERENCE"
                self.assertTrue(any(issue["code"] == code and
                                    issue["pointer"].startswith("/executionModel/sequence/1/correlations")
                                    for issue in output["errors"]), output["errors"])

    def test_correlation_requires_exact_json_pointer_and_single_extraction_owner(self):
        for case in ("jsonpath", "invalid-escape", "inline-extraction", "duplicate-destination", "stored-value"):
            with self.subTest(case=case):
                self.documents = self.workspace / f"documents-correlation-{case}"
                requirements, plan = self.correlated_sequence()
                correlation = plan["executionModel"]["sequence"][1]["correlations"][0]
                if case in ("jsonpath", "invalid-escape"):
                    correlation["responsePath"] = "$.account.id" if case == "jsonpath" else "/account~2id"
                elif case == "inline-extraction":
                    requirements["templates"][1]["payloadBindings"][0]["source"]["fromStepRef"] = "source-first"
                elif case == "stored-value":
                    requirements["templates"][1]["payloadBindings"][0]["source"]["value"] = "must-not-be-stored"
                else:
                    correlation["location"] = "path"
                    correlation["path"] = "accountId"
                self.write_document("requirements.yaml", requirements)
                self.write_document("test-plan.yaml", plan)
                _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
                self.assertTrue(any(issue["code"] == "SCHEMA" for issue in output["errors"]), output["errors"])

    def test_unknown_correlation_settings_are_valid_draft_gaps(self):
        requirements, plan = self.correlated_sequence()
        requirements["templates"][1]["payloadBindings"][0]["source"]["correlationRef"] = None
        for step in plan["executionModel"]["sequence"]:
            for correlation in step["correlations"]:
                for field in ("correlationId", "fromStepRef", "responsePath", "required"):
                    correlation[field] = None
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        draft = self.finalise_and_validate()
        self.assertTrue(any(issue["pointer"].endswith("/source/correlationRef") for issue in draft["gaps"]), draft["gaps"])
        _, handoff = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        self.assertTrue(any(issue["pointer"] == "/executionModel/sequence/1/correlations/0/responsePath"
                            for issue in handoff["gaps"]), handoff["gaps"])

    def test_correlation_cannot_run_under_an_independent_workload(self):
        _, plan = self.correlated_sequence()
        plan["executionModel"]["relationship"] = "independent"
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assertTrue(any(issue["code"] == "CORRELATION_MODEL" for issue in output["errors"]), output["errors"])

    def test_native_mock_dependency_requires_selected_sut_endpoint(self):
        _, plan = self.partial_references()
        plan["mockSetup"]["dependencies"] = [{
            "endpointRef": "other", "disposition": "mocked",
            "configurationRef": "native-owner-snapshot", "limitations": [],
        }]
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_reference_error(output, "plan", "/mockSetup/dependencies/0/endpointRef")
