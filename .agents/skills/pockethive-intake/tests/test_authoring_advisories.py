"""Public-CLI checks for nonblocking, evidence-limited authoring review notices."""
from __future__ import annotations

from copy import deepcopy
from datetime import date

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


ADVISORY_CODES = {"PAYLOAD_BINDINGS_REVIEW", "DATE_CONSTANT_REVIEW", "PRODUCTION_CONTEXT_REVIEW"}


class AuthoringAdvisoryTests(CliTestCase):
    def prepare(self, mode="load"):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        requirements["suts"][0]["sutId"] = "review-sut"
        requirements["templates"][0]["apiId"] = "API-REVIEW"
        requirements["templates"][0]["payloadBindings"] = []
        requirements["kpis"] = []
        plan = self.read_document("test-plan.yaml")
        plan["sutId"] = "review-sut"
        plan["apiExecution"][0].update({"apiRef": "API-REVIEW", "mode": mode})
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        return requirements, plan

    def finalise(self):
        return self.invoke("finalise", "--documents", self.documents, expected_exit=0)[1]

    def notices(self, result):
        return [row for row in result["warnings"] if isinstance(row, dict) and row["code"] in ADVISORY_CODES]

    def snapshot(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def test_finalise_reports_shared_notices_without_readiness_or_question_changes(self):
        requirements, _ = self.prepare()
        requirements["templates"][0]["requestSample"] = {"example": "synthetic-private-sample"}
        requirements["templates"][0]["payloadBindings"] = [
            {"location": "query", "path": "reviewDate", "source": {"type": "constant", "value": "2026-02-28"}}
        ]
        requirements["kpis"] = [{"apiRef": "API-REVIEW", "sutRef": "review-sut", "targetTps": None}]
        self.write_document("requirements.yaml", requirements)
        before_trace = deepcopy(self.read_document("traceability.yaml")["instance"])
        before_approval = deepcopy(self.read_document("test-plan.yaml")["approval"])
        output = self.finalise()
        self.assertEqual("ok", output["status"])
        self.assertEqual([], output["gaps"])
        self.assertEqual(ADVISORY_CODES, {row["code"] for row in self.notices(output)})
        self.assertNotIn("synthetic-private-sample", str(output))
        self.assertNotIn("2026-02-28", str(output))
        after_trace = self.read_document("traceability.yaml")["instance"]
        for field in ("questions", "review", "proposals"):
            self.assertEqual(before_trace[field], after_trace[field])
        self.assertEqual(before_approval, self.read_document("test-plan.yaml")["approval"])
        saved = self.snapshot()
        _, draft = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertEqual("incomplete", draft["status"])
        self.assertEqual(self.notices(output), self.notices(draft))
        self.assertEqual(saved, self.snapshot())
        repeated = self.finalise()
        self.assertEqual(self.notices(output), self.notices(repeated))
        self.assertEqual(saved, self.snapshot())

    def test_samples_only_flag_nonempty_structures_without_body_bindings(self):
        requirements, _ = self.prepare()
        api = requirements["templates"][0]
        for sample, body_binding, expected in (({}, False, False), ([], False, False),
                                              ('{"field":"opaque"}', False, False),
                                              ({"field": "illustrative"}, False, True),
                                              (["illustrative"], False, True),
                                              ({"field": "illustrative"}, True, False)):
            with self.subTest(sample=sample, body_binding=body_binding):
                api["requestSample"] = sample
                api["payloadBindings"] = ([{"location": "body", "path": "/otherField", "source": {"type": "constant", "value": None}}]
                                          if body_binding else [])
                self.write_document("requirements.yaml", requirements)
                notices = self.notices(self.finalise())
                self.assertEqual(expected, any(row["code"] == "PAYLOAD_BINDINGS_REVIEW" for row in notices))

    def test_dates_use_valid_calendar_strings_and_exact_nested_value_pointers(self):
        requirements, _ = self.prepare()
        values = {
            "notAnExpiryField": ["2000-02-29", {"a/b~c": "2400-02-29"}],
            "invalid": ["1900-02-29", "2026-02-30", "2026-13-01", "0000-01-01"],
            "otherFormats": ["2026-2-01", "20260201", "2026-02-01T00:00:00Z", " 2026-02-01", "２０２６-02-01"],
            "otherTypes": [None, False, 20260201, date(2026, 2, 1)],
        }
        requirements["templates"][0]["payloadBindings"] = [
            {"location": "body", "path": "", "source": {"type": "constant", "value": values}},
            {"location": "query", "path": "date", "source": {"type": "generated", "generator": "2026-02-01", "scope": "logical-request"}},
        ]
        self.write_document("requirements.yaml", requirements)
        output = self.finalise()
        notices = [row for row in self.notices(output) if row["code"] == "DATE_CONSTANT_REVIEW"]
        prefix = "/templates/0/payloadBindings/0/source/value/notAnExpiryField"
        self.assertEqual([prefix + "/0", prefix + "/1/a~1b~0c",
                          "/templates/0/payloadBindings/0/source/value/otherTypes/3"],
                         [row["pointer"] for row in notices])
        for value in ("2000-02-29", "2400-02-29"):
            self.assertNotIn(value, str(output))
        _, draft = self.invoke("validate", "--documents", self.documents, "--stage", "draft")
        self.assertEqual(notices, [row for row in self.notices(draft) if row["code"] == "DATE_CONSTANT_REVIEW"])

    def test_unknown_or_excluded_api_modes_do_not_trigger_notices(self):
        requirements, plan = self.prepare()
        requirements["templates"][0].update({"requestSample": {"example": "sample"}, "payloadBindings": [
            {"location": "query", "path": "date", "source": {"type": "constant", "value": "2026-02-01"}}
        ]})
        requirements["kpis"] = [{"apiRef": "API-REVIEW", "sutRef": "review-sut", "targetTps": None}]
        self.write_document("requirements.yaml", requirements)
        for mode in (None, "excluded"):
            with self.subTest(mode=mode):
                plan["apiExecution"][0]["mode"] = mode
                self.write_document("test-plan.yaml", plan)
                self.assertEqual([], self.notices(self.finalise()))

    def test_readiness_and_auth_apis_get_payload_notices_without_load_target_notice(self):
        requirements, plan = self.prepare()
        requirements["templates"][0]["requestSample"] = ["sample"]
        requirements["kpis"] = [{"apiRef": "API-REVIEW", "sutRef": "review-sut", "targetTps": None}]
        self.write_document("requirements.yaml", requirements)
        for mode in ("readiness-only", "auth-prerequisite"):
            with self.subTest(mode=mode):
                plan["apiExecution"][0]["mode"] = mode
                self.write_document("test-plan.yaml", plan)
                self.assertEqual(["PAYLOAD_BINDINGS_REVIEW"], [row["code"] for row in self.notices(self.finalise())])

    def test_production_notice_requires_unknown_availability_and_selected_load_target(self):
        requirements, plan = self.prepare()
        cases = ((None, None, "review-sut", "review-sut", True),
                 (False, None, "review-sut", "review-sut", False),
                 (True, None, "review-sut", "review-sut", False),
                 (None, 0, "review-sut", "review-sut", False),
                 (None, 10, "review-sut", "review-sut", False),
                 (None, None, None, "review-sut", False),
                 (None, None, "review-sut", "other-sut", False),
                 (None, None, "undeclared-sut", "undeclared-sut", False))
        for available, target, selected, kpi_sut, expected in cases:
            with self.subTest(available=available, target=target, selected=selected, kpi_sut=kpi_sut):
                requirements["productionUsage"]["available"] = available
                requirements["kpis"] = [{"apiRef": "API-REVIEW", "sutRef": kpi_sut, "targetTps": target}]
                plan["sutId"] = selected
                self.write_document("requirements.yaml", requirements)
                self.write_document("test-plan.yaml", plan)
                notices = self.notices(self.finalise())
                self.assertEqual(expected, any(row["code"] == "PRODUCTION_CONTEXT_REVIEW" for row in notices))

    def test_omitted_optional_production_context_is_unknown_not_an_inferred_target(self):
        requirements, _ = self.prepare()
        del requirements["productionUsage"]
        requirements["kpis"] = [{"apiRef": "API-REVIEW", "sutRef": "review-sut", "targetTps": None}]
        self.write_document("requirements.yaml", requirements)
        self.assertEqual(["PRODUCTION_CONTEXT_REVIEW"], [row["code"] for row in self.notices(self.finalise())])
        saved = self.read_document("requirements.yaml")
        self.assertNotIn("productionUsage", saved)
        self.assertIsNone(saved["kpis"][0]["targetTps"])

    def test_schema_errors_prevent_notices_and_finalise_writes(self):
        requirements, _ = self.prepare()
        requirements["templates"][0]["payloadBindings"] = "invalid shape"
        requirements["templates"][0]["requestSample"] = ["sample"]
        self.write_document("requirements.yaml", requirements)
        before = self.snapshot()
        for arguments in (("finalise",), ("validate", "--stage", "draft")):
            _, result = self.invoke(*arguments, "--documents", self.documents, expected_exit=2)
            self.assertTrue(any(row["code"] == "SCHEMA" for row in result["errors"]), result)
            self.assertEqual([], self.notices(result))
            self.assertEqual(before, self.snapshot())

    def test_finalise_notices_do_not_require_schema_optional_authoring_fields(self):
        requirements, plan = self.prepare()
        requirements["templates"][0]["requestSample"] = ["sample"]
        requirements["templates"][0]["payloadBindings"] = [{"location": "query"}]
        self.write_document("requirements.yaml", requirements)
        self.assertEqual(["PAYLOAD_BINDINGS_REVIEW"], [row["code"] for row in self.notices(self.finalise())])
        for role, document, field in (("requirements.yaml", requirements, "templates"),
                                      ("requirements.yaml", requirements, "suts"),
                                      ("test-plan.yaml", plan, "apiExecution")):
            del document[field]
            self.write_document(role, document)
            self.assertEqual([], self.notices(self.finalise()))
