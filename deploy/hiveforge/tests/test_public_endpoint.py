"""Render public endpoint deployment contracts without deploying or using real secrets.

Run: python3 -m unittest discover -s deploy/hiveforge/tests
Requires PyYAML and Jinja2. This does not execute Ansible or qualify live ingress.
"""

from pathlib import Path
import unittest

import jinja2
import yaml


ANSIBLE = Path(__file__).resolve().parents[1] / "components/stack/ansible"


class PublicEndpointTest(unittest.TestCase):
    def render(self, action, profile, ingress, allowance):
        runtime = {
            "HIVEFORGE_PROFILE": profile,
            "HIVEFORGE_BIND_SOURCE_DIR": "/fixture/pockethive",
            "DOCKER_REGISTRY": "example.invalid/pockethive/",
            "POCKETHIVE_VERSION": "test",
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX": "example.invalid/pockethive",
            "POCKETHIVE_PUBLIC_INGRESS": ingress,
            "POCKETHIVE_PUBLIC_HOST": "pockethive.example:8088",
        }
        if allowance is not None:
            runtime["POCKETHIVE_ALLOW_REMOTE_HTTP"] = allowance
        env = jinja2.Environment(undefined=jinja2.StrictUndefined)
        env.globals["lookup"] = lambda kind, key: runtime.get(key, "")
        play = yaml.safe_load((ANSIBLE / f"{action}.yml").read_text())[0]
        definitions = dict(play["vars"])
        for filename in play.get("vars_files", []):
            definitions.update(yaml.safe_load((ANSIBLE / filename).read_text()))
        values = {"playbook_dir": str(ANSIBLE)}
        for key, value in definitions.items():
            values[key] = env.from_string(str(value)).render(values)
        tasks = yaml.safe_load((ANSIBLE / "swarm-stack.yml").read_text())
        phase_one_auth = next(task["ansible.builtin.set_fact"] for task in tasks
                              if task["name"] == "Select explicit Phase 1 DEV authentication")
        values.update(phase_one_auth)
        public_guard = next(task["ansible.builtin.assert"] for task in tasks
                            if task["name"] == "Require explicit PocketHive MCP public identity")
        for expression in public_guard["that"]:
            if not env.compile_expression(expression)(**values):
                raise ValueError(public_guard["fail_msg"])
        template = (ANSIBLE / "templates/stack-compose.yml.j2").read_text()
        return yaml.safe_load(env.from_string(template).render(values))["services"]

    def test_deploy_and_update_publish_consistent_http_and_https_endpoints(self):
        for action in ("deploy", "update"):
            for profile in ("swarm-reduced", "swarm-full"):
                for scheme, allowance in (("http", "true"), ("https", "false")):
                    with self.subTest(action=action, profile=profile, scheme=scheme):
                        origin = f"{scheme}://pockethive.example:8088"
                        services = self.render(action, profile, origin, allowance)
                        auth = services["auth-service"]["environment"]
                        mcp = services["pockethive-mcp"]["environment"]
                        self.assertEqual(auth["POCKETHIVE_AUTH_SERVICE_PROVIDER"], "DEV")
                        self.assertEqual(auth["POCKETHIVE_AUTH_OAUTH_INTROSPECTION_SECRET"],
                                         mcp["PH_MCP_OAUTH_INTROSPECTION_CLIENT_SECRET"])
                        self.assertEqual(auth["POCKETHIVE_AUTH_SERVICE_ACCOUNT_MCP_SECRET"],
                                         mcp["PH_MCP_DOWNSTREAM_SERVICE_SECRET"])
                        self.assertNotEqual(mcp["PH_MCP_OAUTH_INTROSPECTION_CLIENT_SECRET"],
                                            mcp["PH_MCP_DOWNSTREAM_SERVICE_SECRET"])
                        self.assertEqual(auth["POCKETHIVE_ALLOW_REMOTE_HTTP"], allowance)
                        self.assertEqual(mcp["POCKETHIVE_ALLOW_REMOTE_HTTP"], allowance)
                        self.assertEqual(auth["POCKETHIVE_AUTH_OAUTH_ISSUER"], origin + "/auth-service")
                        self.assertEqual(mcp["PH_MCP_OAUTH_ISSUER"], origin + "/auth-service")
                        self.assertEqual(auth["POCKETHIVE_AUTH_OAUTH_RESOURCE"], origin + "/mcp")
                        self.assertEqual(mcp["PH_MCP_OAUTH_RESOURCE"], origin + "/mcp")
                        self.assertEqual(mcp["PH_MCP_POCKETHIVE_INGRESS"], origin)
                        self.assertEqual(mcp["PH_MCP_ALLOWED_ORIGINS"], origin)
                        self.assertEqual(mcp["PH_MCP_ALLOWED_HOSTS"], "pockethive.example:8088")

    def test_missing_invalid_or_disabled_http_allowance_rejects_before_render(self):
        for action in ("deploy", "update"):
            for profile in ("swarm-reduced", "swarm-full"):
                for scheme, allowance in (("http", "false"), ("http", None),
                                          ("https", None), ("http", "yes"),
                                          ("http", ""), ("ftp", "true")):
                    with self.subTest(action=action, profile=profile, scheme=scheme, allowance=allowance):
                        with self.assertRaisesRegex(ValueError, "POCKETHIVE_ALLOW_REMOTE_HTTP"):
                            self.render(action, profile, f"{scheme}://pockethive.example:8088", allowance)
