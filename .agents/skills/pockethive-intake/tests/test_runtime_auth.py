"""Public CLI regressions for canonical runtime auth and intake representation gaps."""
from __future__ import annotations

from copy import deepcopy
import hashlib
import json

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE, dictionaries_in


class RuntimeAuthTests(CliTestCase):
    def setUp(self):
        super().setUp()
        _, initial = self.initialise()
        self.revision = initial["documentsSha256"]
        self.batch_number = 0

    def auth_types(self):
        vocabulary = json.loads((PACKAGE / "contract/schemas/runtime-vocabulary.schema.json").read_text())
        return vocabulary["definitions"]["authType"]["enum"]

    def apply(self, changes, expected_exit=0):
        """Submit exact values and local evidence through the published authoring path."""
        self.batch_number += 1
        source = self.workspace / f"auth-source-{self.batch_number}.json"
        source.write_text(json.dumps([value for _, _, value in changes]), encoding="utf-8")
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        updates = []
        for index, (role, pointer, value) in enumerate(changes):
            target = {"document": role, "pointer": pointer}
            updates.append({"target": target, "value": value, "provenance": {
                "target": target, "kind": "client-statement",
                "sources": [{"artifactRef": str(source), "pointer": f"/{index}", "sha256": digest}],
                "confirmationRef": None, "calculation": None,
            }})
        request = self.workspace / f"auth-updates-{self.batch_number}.json"
        request.write_text(json.dumps({"expectedDocumentsSha256": self.revision,
                                       "updates": updates}), encoding="utf-8")
        raw, output = self.invoke("apply-updates", "--documents", self.documents,
                                  "--input", request, expected_exit=expected_exit)
        if output.get("applied"):
            self.revision = output["documentsSha256"]
        return raw, output

    def select_api(self):
        self.apply([
            ("requirements", "/templates/0/apiId", "API-AUTH"),
            ("plan", "/apiExecution/0/apiRef", "API-AUTH"),
            ("plan", "/apiExecution/0/mode", "load"),
        ])

    def snapshot(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def representation_gaps(self, output):
        return [issue for issue in output["gaps"] if issue["code"] == "AUTH_REPRESENTATION"]

    def assert_representation_gap(self, output):
        self.assertFalse(output["errors"], output)
        gaps = self.representation_gaps(output)
        self.assertEqual(1, len(gaps), output)
        self.assertEqual("requirements", gaps[0]["document"])
        self.assertEqual("/templates/0/authorization/type", gaps[0]["pointer"])

    def test_version_three_field_help_exposes_runtime_keys_and_separate_unknown_choice(self):
        canonical = self.auth_types()
        self.assertTrue(canonical)
        self.assertEqual(len(canonical), len(set(canonical)))
        self.assertNotIn(None, canonical)
        self.assertIn("bearer-token", canonical)
        self.assertNotIn("bearer", canonical)
        self.assertEqual(3, self.read_document("requirements.yaml")["version"])
        before = self.snapshot()
        _, output = self.invoke("show-field", "--documents", self.documents,
                                "--document", "requirements", "--pointer",
                                "/templates/0/authorization/type", expected_exit=0)
        enums = [row["enum"] for row in dictionaries_in(output["field"]) if "enum" in row]
        self.assertIn(canonical, enums)
        self.assertIsNone(output["field"]["value"])
        self.assertEqual(before, self.snapshot())

    def test_all_runtime_auth_types_and_null_can_be_authored_with_evidence(self):
        self.select_api()
        for auth_type in [*self.auth_types(), None]:
            with self.subTest(auth_type=auth_type):
                _, output = self.apply([
                    ("requirements", "/templates/0/authorization", {"type": auth_type}),
                ])
                self.assertTrue(output["applied"], output)
                self.assertFalse(output["errors"], output)
                self.assertEqual(auth_type, self.read_document("requirements.yaml")["templates"][0]["authorization"]["type"])
                _, draft = self.invoke("validate", "--documents", self.documents,
                                       "--stage", "draft", expected_exit=0)
                self.assertFalse(draft["errors"], draft)

    def test_version_two_requirements_are_rejected_without_implicit_migration(self):
        requirements = self.read_document("requirements.yaml")
        requirements["version"] = 2
        self.write_document("requirements.yaml", requirements)
        before = self.snapshot()
        _, output = self.invoke("validate", "--documents", self.documents,
                                "--stage", "draft", expected_exit=2)
        issue = next(issue for issue in output["errors"] if issue["code"] == "DOCUMENT_VERSION")
        self.assertEqual("requirements", issue["document"])
        self.assertEqual("/version", issue["pointer"])
        self.assertEqual(3, issue["detail"]["expectedVersion"])
        self.assertEqual(before, self.snapshot())

    def test_old_bearer_typo_and_unknown_names_fail_without_writes_or_value_disclosure(self):
        for auth_type in ("bearer", "oauth2-http-signatre", "OAUTH2_HTTP_SIGNATURE", "PRIVATE-AUTH-CANARY"):
            with self.subTest(auth_type=auth_type):
                before = self.snapshot()
                raw, output = self.apply([
                    ("requirements", "/templates/0/authorization", {"type": auth_type}),
                ], expected_exit=2)
                self.assertFalse(output["applied"], output)
                self.assertTrue(any(issue["code"] == "SCHEMA" for issue in output["errors"]), output)
                self.assertEqual(before, self.snapshot())
                self.assertNotIn(json.dumps(auth_type), raw.stdout + raw.stderr)

    def test_recognized_types_without_details_cannot_pass_handoff(self):
        self.select_api()
        for auth_type in self.auth_types():
            if auth_type in ("none", "oauth2-client-credentials"):
                continue
            with self.subTest(auth_type=auth_type):
                _, output = self.apply([
                    ("requirements", "/templates/0/authorization", {"type": auth_type}),
                ])
                self.assert_representation_gap(output)
                _, handoff = self.invoke("validate", "--documents", self.documents,
                                         "--stage", "handoff", expected_exit=3)
                self.assert_representation_gap(handoff)

    def test_signed_oauth_is_retained_as_a_draft_without_claiming_representation(self):
        self.select_api()
        _, output = self.apply([
            ("requirements", "/templates/0/authorization", {"type": "oauth2-http-signature"}),
        ])
        self.assert_representation_gap(output)
        self.assertEqual("oauth2-http-signature",
                         self.read_document("requirements.yaml")["templates"][0]["authorization"]["type"])

    def test_client_credentials_keep_the_existing_detailed_readiness_requirements(self):
        self.select_api()
        _, output = self.apply([
            ("requirements", "/templates/0/authorization", {"type": "oauth2-client-credentials"}),
        ])
        self.assertFalse(self.representation_gaps(output), output)
        missing = {issue["pointer"] for issue in output["gaps"]}
        for field in ("clientAuthentication", "reuse", "refreshBeforeExpirySeconds"):
            self.assertIn("/templates/0/authorization/" + field, missing)
        _, complete = self.apply([
            ("requirements", "/templates/0/authorization", {
                "type": "oauth2-client-credentials", "clientAuthentication": "client_secret_basic",
                "reuse": "per-credential-set", "refreshBeforeExpirySeconds": 30,
            }),
        ])
        self.assertFalse(complete["errors"], complete)
        self.assertFalse(self.representation_gaps(complete), complete)
        self.assertFalse(any(issue["pointer"].startswith("/templates/0/authorization/")
                             for issue in complete["gaps"]), complete)

    def test_upstream_bearer_provider_retains_token_source_and_checks_its_details(self):
        api = deepcopy(self.read_document("requirements.yaml")["templates"][0])
        api.update(apiId="API-AUTH", authorization={
            "type": "bearer-token", "tokenSource": {"apiRef": "API-TOKEN"},
        })
        provider = deepcopy(api)
        provider.update(apiId="API-TOKEN", authorization={"type": "none"})
        _, partial = self.apply([
            ("requirements", "/templates", [api, provider]),
            ("plan", "/apiExecution/0/apiRef", "API-AUTH"),
            ("plan", "/apiExecution/0/mode", "load"),
        ])
        self.assertFalse(self.representation_gaps(partial), partial)
        missing = {issue["pointer"] for issue in partial["gaps"]}
        for field in ("tokenJsonPointer", "expiresInJsonPointer", "reuse", "refreshBeforeExpirySeconds"):
            self.assertIn("/templates/0/authorization/tokenSource/" + field, missing)
        token_source = {"apiRef": "API-TOKEN", "tokenJsonPointer": "/access_token",
                        "expiresInJsonPointer": "/expires_in", "reuse": "per-credential-set",
                        "refreshBeforeExpirySeconds": 30}
        api["authorization"]["tokenSource"] = token_source
        _, complete = self.apply([("requirements", "/templates", [api, provider])])
        self.assertFalse(complete["errors"], complete)
        self.assertFalse(self.representation_gaps(complete), complete)
        self.assertFalse(any(issue["pointer"].startswith("/templates/0/authorization/")
                             for issue in complete["gaps"]), complete)
        retained = self.read_document("requirements.yaml")["templates"][0]["authorization"]
        self.assertEqual({"type": "bearer-token", "tokenSource": token_source}, retained)

    def test_file_injection_is_shared_by_plan_and_results_and_upstream_url_is_retained(self):
        endpoint = {"kind": "HTTP", "baseUrl": "https://proxy.invalid",
                    "upstreamBaseUrl": "https://origin.invalid"}
        _, output = self.apply([
            ("plan", "/secretInjection/type", "file"),
            ("results", "/actualRunConfig/secretInjection/type", "file"),
            ("requirements", "/suts/0/endpoints", {"read": endpoint}),
        ])
        self.assertTrue(output["applied"], output)
        self.assertFalse(output["errors"], output)
        self.assertEqual("file", self.read_document("test-plan.yaml")["secretInjection"]["type"])
        self.assertEqual("file", self.read_document("execution-results.yaml")["actualRunConfig"]["secretInjection"]["type"])
        self.assertEqual(endpoint, self.read_document("requirements.yaml")["suts"][0]["endpoints"]["read"])
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)

    def test_file_injection_requires_explicit_resolver_and_nonempty_field_mapping(self):
        self.select_api()
        _, partial = self.apply([
            ("requirements", "/templates/0/authorization", {"type": "oauth2-client-credentials"}),
            ("plan", "/secretInjection/type", "file"),
            ("plan", "/secretInjection/bindings", [{"credentialRef": "fixture-file-credential",
                                                     "resolverRef": None, "fieldMapping": {}}]),
        ])
        required = {"/secretInjection/bindings/0/resolverRef", "/secretInjection/bindings/0/fieldMapping"}
        self.assertFalse(partial["errors"], partial)
        missing = {issue["pointer"] for issue in partial["gaps"] if issue["document"] == "plan"}
        self.assertTrue(required.issubset(missing), partial)
        binding = {"credentialRef": "fixture-file-credential", "resolverRef": "fixture-mounted-file-resolver",
                   "fieldMapping": {"clientId": "/run/secrets/client-id", "clientSecret": "/run/secrets/client-secret"}}
        _, complete = self.apply([("plan", "/secretInjection/bindings", [binding])])
        self.assertFalse(complete["errors"], complete)
        remaining = {issue["pointer"] for issue in complete["gaps"] if issue["document"] == "plan"}
        self.assertFalse(required & remaining, complete)
        self.assertEqual([binding], self.read_document("test-plan.yaml")["secretInjection"]["bindings"])
