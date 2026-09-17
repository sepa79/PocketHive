"""Measurement and data-constraint regressions through the offline public CLI."""

from copy import deepcopy

from test_intake_cli import CliTestCase
import test_references as reference_fixtures


class MeasurementTests(CliTestCase):
    partial_references = reference_fixtures.ReferenceTests.partial_references
    finalise_and_validate = reference_fixtures.ReferenceTests.finalise_and_validate

    def test_missing_mapped_rule_fields_have_one_completeness_owner(self):
        _, plan, _ = self.mapped_kpis()
        for key in ("unit", "operator", "threshold", "window"):
            plan["acceptanceCriteria"][0]["measurableRules"][0][key] = None
        self.write_document("test-plan.yaml", plan)
        result = self.finalise_and_validate()
        for key in ("unit", "operator", "threshold", "window"):
            pointer = "/acceptanceCriteria/0/measurableRules/0/" + key
            gaps = [row for row in result["gaps"] if row["document"] == "plan" and row["pointer"] == pointer]
            self.assertEqual(["REQUIRED_INPUT"], [row["code"] for row in gaps])

    def mapped_kpis(self):
        requirements, plan = self.partial_references()
        requirements["successCriteria"] = [{"criterionId": "CRIT-READ", "description": "Synthetic qualification goal"}]
        requirements["kpis"] = [{
            "criterionRef": "CRIT-READ", "apiRef": "API-READ", "sutRef": "selected-sut",
            "transactionDefinition": "One successful read", "targetTps": 5,
            "responseTimePercentile": 95, "maxResponseTimeMs": 500, "measurementWindow": "2 seconds",
        }]
        criterion = plan["acceptanceCriteria"][0]
        criterion["criterionRef"] = "CRIT-READ"
        rate = criterion["measurableRules"][0]
        rate.update({"metric": "synthetic-rate", "operator": ">=", "threshold": 5,
                     "window": "2 seconds", "apiRef": "API-READ", "sutRef": "selected-sut",
                     "transactionDefinition": "One successful read", "unit": "successful-transactions-per-second"})
        latency = deepcopy(rate)
        latency.update({"metric": "synthetic-latency", "operator": "<=", "threshold": 500,
                        "unit": "milliseconds", "percentile": 95})
        criterion["measurableRules"].append(latency)
        trace = self.read_document("traceability.yaml")
        trace["instance"]["coverage"] = [{
            "criterionRef": "CRIT-READ", "apiRef": "API-READ", "sutRef": "selected-sut",
            "kpiPointer": f"/kpis/0/{target}",
            "planRulePointer": f"/acceptanceCriteria/0/measurableRules/{index}",
            "bundleArtifactRef": None, "bundlePointer": None, "resultPointer": None,
            "evidenceRefs": [], "status": "mapped", "reason": None,
        } for index, target in enumerate(("targetTps", "maxResponseTimeMs"))]
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        self.write_document("traceability.yaml", trace)
        return requirements, plan, trace

    def participating_data(self):
        requirements, plan = self.partial_references()
        requirements["templates"][0]["authorization"] = {"type": "none"}
        requirements["templates"][0]["payloadBindings"] = [{
            "location": "path", "path": "accountId",
            "source": {"type": "dataset", "entityRef": "accounts", "column": "account_id"},
        }]
        entity = requirements["testData"]["entities"][0]
        entity.update({"entityId": "accounts", "requiredCount": 10,
                       "columns": [{"name": "account_id", "type": "string"}],
                       "sources": [{"sutRef": "selected-sut", "type": "generated", "generatedColumns": {
                           "account_id": {"type": "sequence", "start": 1, "prefix": "synthetic-", "padTo": 4}}}],
                       "usage": {"reuseAllowed": False, "maxConcurrentRequestsPerRecord": 1, "onExhaustion": "stop"}})
        plan["dataPreparation"][0].update({"entityRef": "accounts", "sourceSutRef": "selected-sut",
                                           "sourceType": "generated", "rowCount": 10})
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        return requirements, plan

    def assert_issue(self, output, collection, code, pointer=None):
        self.assertTrue(any(issue["code"] == code and (pointer is None or issue["pointer"] == pointer)
                            for issue in output[collection]), output[collection])

    def test_exact_kpi_mappings_allow_future_results_and_require_active_coverage(self):
        _, plan, trace = self.mapped_kpis()
        output = self.finalise_and_validate()
        self.assertFalse(any(issue["code"].startswith("KPI_") for issue in output["gaps"]))
        trace["instance"]["coverage"] = []
        self.write_document("traceability.yaml", trace)
        self.assert_issue(self.finalise_and_validate(), "gaps", "KPI_MAPPING", "/kpis/0/targetTps")
        plan["apiExecution"][0]["mode"] = "excluded"
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate()
        self.assertFalse(any(issue["code"] == "KPI_MAPPING" for issue in output["gaps"]))

    def test_mapped_rules_cannot_weaken_or_change_the_target_dimensions(self):
        _, plan, _ = self.mapped_kpis()
        rules = plan["acceptanceCriteria"][0]["measurableRules"]
        rules[0]["threshold"] = 4
        rules[1].update({"threshold": 501, "unit": "seconds", "percentile": 90})
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_issue(output, "errors", "KPI_WEAKENED", "/acceptanceCriteria/0/measurableRules/0/threshold")
        self.assert_issue(output, "errors", "KPI_WEAKENED", "/acceptanceCriteria/0/measurableRules/1/threshold")
        self.assert_issue(output, "errors", "KPI_UNIT")
        self.assert_issue(output, "errors", "KPI_PERCENTILE")

    def test_populated_coverage_links_must_resolve_and_identify_rule_occurrences(self):
        _, _, trace = self.mapped_kpis()
        original = deepcopy(trace["instance"]["coverage"])
        for field, value, code in (
            ("kpiPointer", "/kpis/9/targetTps", "UNKNOWN_REFERENCE"),
            ("planRulePointer", "/acceptanceCriteria/0/measurableRules/9", "UNKNOWN_REFERENCE"),
            ("resultPointer", "/criterionResults/9/ruleResults/0", "UNKNOWN_REFERENCE"),
            ("apiRef", "UNKNOWN-API", "UNKNOWN_REFERENCE"),
            ("planRulePointer", "/owner", "KPI_RULE"),
        ):
            with self.subTest(field=field, value=value):
                trace["instance"]["coverage"] = deepcopy(original)
                trace["instance"]["coverage"][0][field] = value
                if value == "/owner":
                    trace["instance"]["coverage"][0]["kpiPointer"] = None
                self.write_document("traceability.yaml", trace)
                self.assert_issue(self.finalise_and_validate(expected_exit=2), "errors", code)

    def test_result_rule_snapshot_cannot_silently_change_the_plan_threshold(self):
        _, plan, trace = self.mapped_kpis()
        trace["instance"]["coverage"][0]["resultPointer"] = "/criterionResults/0/ruleResults/0"
        results = self.read_document("execution-results.yaml")
        result = results["criterionResults"][0]
        result["criterionRef"] = "CRIT-READ"
        result["ruleResults"][0]["rule"] = deepcopy(plan["acceptanceCriteria"][0]["measurableRules"][0])
        self.write_document("traceability.yaml", trace)
        self.write_document("execution-results.yaml", results)
        self.finalise_and_validate()
        result["ruleResults"][0]["rule"]["threshold"] = 4
        self.write_document("execution-results.yaml", results)
        self.assert_issue(self.finalise_and_validate(expected_exit=2), "errors", "RESULT_RULE_SNAPSHOT")

    def test_unexecuted_results_reject_zero_measurements_and_pass_claims(self):
        self.partial_references()
        results = self.read_document("execution-results.yaml")
        results["apiResults"][0]["apiRef"] = "API-READ"
        results["apiResults"][0]["assertions"][0]["evaluatedCount"] = 0
        results["overallResult"]["result"] = "pass"
        self.write_document("execution-results.yaml", results)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_issue(output, "errors", "UNEXECUTED_RESULT", "/apiResults/0/assertions/0")
        self.assert_issue(output, "errors", "UNEXECUTED_RESULT", "/overallResult")

    def test_preparation_cannot_loosen_data_usage_or_undersupply_required_records(self):
        _, plan = self.participating_data()
        self.finalise_and_validate()
        prepared = plan["dataPreparation"][0]
        prepared["rowCount"] = 9
        prepared["usageOverride"] = {"reuseAllowed": True, "maxConcurrentRequestsPerRecord": 2, "onExhaustion": "recycle"}
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate(expected_exit=2)
        self.assert_issue(output, "errors", "DATA_COUNT")
        self.assert_issue(output, "errors", "DATA_USAGE_WEAKENED", "/dataPreparation/0/usageOverride/reuseAllowed")
        self.assert_issue(output, "errors", "DATA_USAGE_WEAKENED", "/dataPreparation/0/usageOverride/maxConcurrentRequestsPerRecord")
        self.assert_issue(output, "errors", "DATA_EXHAUSTION")

    def test_data_export_credentials_require_injection_even_when_api_has_no_auth(self):
        requirements, plan = self.participating_data()
        requirements["testData"]["entities"][0]["sources"] = [{
            "sutRef": "selected-sut", "type": "database-export", "databaseRef": "DB-READ", "queryRef": "accounts-query",
        }]
        requirements["databases"] = [{
            "dbId": "DB-READ", "sutRef": "selected-sut", "engine": "postgresql",
            "host": "synthetic.invalid", "port": 5432, "databaseName": "synthetic",
            "credentialRef": "synthetic-db-credential", "sqlQueries": [{
                "queryId": "accounts-query", "purpose": "Synthetic fixture text only",
                "sql": "SELECT account_id FROM synthetic_accounts",
            }],
        }]
        plan["dataPreparation"][0]["sourceType"] = "database-export"
        plan["secretInjection"]["bindings"] = []
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        output = self.finalise_and_validate()
        self.assert_issue(output, "gaps", "DATA_CREDENTIAL", "/secretInjection/bindings")
        self.assertTrue(any(issue["pointer"] == "/secretInjection/type" for issue in output["gaps"]), output["gaps"])
