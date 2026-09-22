"""Public-CLI regressions for explicit workload and authentication decisions."""
from complete_intake import prepare_complete_intake
from test_intake_cli import CliTestCase


class ReadinessTests(CliTestCase):
    def draft(self):
        trace = self.read_document("traceability.yaml")
        trace["instance"]["review"] = {"status": "draft", "evidenceRef": None, "contentSha256": None}
        self.write_document("traceability.yaml", trace)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        return self.invoke("validate", "--documents", self.documents, "--stage", "draft")[1]

    def test_journey_cannot_keep_a_competing_per_api_schedule(self):
        prepare_complete_intake(self)
        plan = self.read_document("test-plan.yaml")
        plan["executionModel"].update({
            "relationship": "sequential", "rateUnit": "journeys-per-second",
            "sequence": [{"stepId": "read-once", "apiRef": "API-READ", "thinkTimeMs": 0, "correlations": []}],
            "journeyTimeline": [{"phase": "constant", "durationSeconds": 2, "startRate": 5, "endRate": 5, "interpolation": "constant"}],
        })
        plan["apiExecution"][0]["requestsPerJourney"] = 1
        self.write_document("test-plan.yaml", plan)
        output = self.draft()
        self.assertTrue(any(issue["code"] == "WORKLOAD_MODEL" and issue["pointer"] == "/apiExecution/0/timeline" for issue in output["errors"]))

    def test_journey_count_must_describe_the_explicit_step_occurrences(self):
        prepare_complete_intake(self)
        plan = self.read_document("test-plan.yaml")
        plan["executionModel"].update({
            "relationship": "sequential", "rateUnit": "journeys-per-second",
            "sequence": [{"stepId": name, "apiRef": "API-READ", "thinkTimeMs": 0, "correlations": []} for name in ("first", "second")],
        })
        plan["apiExecution"][0].update({"timeline": [], "requestsPerJourney": 1})
        self.write_document("test-plan.yaml", plan)
        output = self.draft()
        self.assertTrue(any(issue["code"] == "JOURNEY_COUNT" for issue in output["errors"]))

    def test_oauth_type_and_unrelated_secret_binding_do_not_complete_auth(self):
        prepare_complete_intake(self)
        requirements = self.read_document("requirements.yaml")
        requirements["templates"][0]["authorization"] = {"type": "oauth2-client-credentials"}
        requirements["suts"][0]["oauth2"] = {"tokenUrl": None, "credentialRef": "selected-oauth-credential"}
        plan = self.read_document("test-plan.yaml")
        plan["secretInjection"].update({"owner": "synthetic-owner", "type": "env", "implementationRef": "synthetic-resolver",
                                         "bindings": [{"credentialRef": "unrelated-credential", "environmentVariables": {"clientId": "CLIENT_ID"}}]})
        self.write_document("requirements.yaml", requirements)
        self.write_document("test-plan.yaml", plan)
        output = self.draft()
        self.assertTrue(any(issue["pointer"] == "/suts/0/oauth2/tokenUrl" for issue in output["gaps"]))
        self.assertTrue(any(issue["code"] == "AUTH_CREDENTIAL" for issue in output["gaps"]))
        self.assertTrue(any(issue["pointer"] == "/templates/0/authorization/clientAuthentication" for issue in output["gaps"]))
