"""Render public endpoint deployment contracts without deploying or using real secrets.

Run: python3 -m unittest discover -s deploy/hiveforge/tests
Requires PyYAML and Jinja2. This does not execute Ansible or qualify live ingress.
"""

from pathlib import Path
import json
import unittest

import jinja2
import yaml


ANSIBLE = Path(__file__).resolve().parents[1] / "components/stack/ansible"


class PublicEndpointTest(unittest.TestCase):
    def render(self, action, profile, ingress, allowance, work_type="RABBITMQ"):
        runtime = {
            "HIVEFORGE_PROFILE": profile,
            "HIVEFORGE_BIND_SOURCE_DIR": "/fixture/pockethive",
            "DOCKER_REGISTRY": "example.invalid/pockethive/",
            "POCKETHIVE_VERSION": "test",
            "POCKETHIVE_WORK_TYPE": work_type,
            "POCKETHIVE_HAPROXY_NODE": "worker-a",
            "POCKETHIVE_NETWORK_PROXY_MANAGER_NODE": "worker-b",
            "POCKETHIVE_RABBITMQ_ROOT": "/fixture/rabbitmq",
            "POCKETHIVE_POSTGRES_ROOT": "/fixture/postgres",
            "POCKETHIVE_CLICKHOUSE_ROOT": "/fixture/clickhouse",
            "POCKETHIVE_REDIS_ROOT": "/fixture/redis",
            "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX": "example.invalid/pockethive",
            "POCKETHIVE_PUBLIC_INGRESS": ingress,
            "POCKETHIVE_PUBLIC_HOST": "pockethive.example:8088",
        }
        if allowance is not None:
            runtime["POCKETHIVE_ALLOW_REMOTE_HTTP"] = allowance
        env = jinja2.Environment(undefined=jinja2.StrictUndefined)
        env.filters["to_json"] = json.dumps
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
        proxy_placement = next(task["ansible.builtin.set_fact"] for task in tasks
                               if task["name"] == "Read optional proxy placement once for deploy and update")
        values.update({key: env.from_string(value).render(values)
                       for key, value in proxy_placement.items()})
        public_guard = next(task["ansible.builtin.assert"] for task in tasks
                            if task["name"] == "Require explicit PocketHive MCP public identity")
        work_guard = next(task["ansible.builtin.assert"] for task in tasks
                          if task["name"] == "Require explicit WorkPlane selection")
        for guard in (public_guard, work_guard):
            for expression in guard["that"]:
                if not env.compile_expression(expression)(**values):
                    raise ValueError(guard["fail_msg"])
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

    def test_http_opt_in_preserves_work_plane_and_swarm_runtime_settings(self):
        for action in ("deploy", "update"):
            for profile in ("swarm-reduced", "swarm-full"):
                for work_type in ("RABBITMQ", "ARTEMIS"):
                    for scheme, allowance in (("http", "true"), ("https", "false")):
                        with self.subTest(action=action, profile=profile, work_type=work_type, scheme=scheme):
                            services = self.render(action, profile, f"{scheme}://pockethive.example:8088",
                                                   allowance, work_type)
                            orchestrator = services["orchestrator"]["environment"]
                            self.assertEqual(orchestrator["POCKETHIVE_WORK_TYPE"], work_type)
                            self.assertEqual(orchestrator["SPRING_RABBITMQ_HOST"], "rabbitmq")
                            self.assertEqual("artemis" in services, work_type == "ARTEMIS")
                            if work_type == "ARTEMIS":
                                self.assertEqual(orchestrator["POCKETHIVE_WORK_ARTEMIS_BROKERURL"],
                                                 "tcp://artemis:61616")
                                self.assertNotIn("POCKETHIVE_RABBIT_WORK_HOST", orchestrator)
                                self.assertEqual(services["artemis"]["image"],
                                                 "example.invalid/pockethive/artemis:test")
                                self.assertEqual(services["artemis"]["deploy"]["update_config"]["order"],
                                                 "stop-first")
                                self.assertIn("/fixture/pockethive/state/artemis/data:/var/lib/artemis-instance",
                                              services["artemis"]["volumes"])
                            else:
                                self.assertEqual(orchestrator["POCKETHIVE_RABBIT_WORK_HOST"], "rabbitmq")
                                self.assertNotIn("POCKETHIVE_WORK_ARTEMIS_BROKERURL", orchestrator)
                            self.assertTrue(any(port["published"] == 8443 for port in services["ui"]["ports"]))
                            self.assertIn("node.hostname == worker-a",
                                          services["haproxy"]["deploy"]["placement"]["constraints"])
                            self.assertIn("node.hostname == worker-b",
                                          services["network-proxy-manager"]["deploy"]["placement"]["constraints"])
                            mcp = services["pockethive-mcp"]
                            self.assertTrue(mcp["read_only"])
                            self.assertIn({"type": "tmpfs", "target": "/tmp", "tmpfs": {"size": 134217728}},
                                          mcp["volumes"])
                            self.assertEqual(mcp["environment"]["PH_MCP_UPLOAD_SPOOL_PATH"],
                                             "/tmp/pockethive-mcp-spool")

    def test_unknown_or_missing_work_plane_rejects_before_render(self):
        for action in ("deploy", "update"):
            for work_type in ("", "KAFKA"):
                with self.subTest(action=action, work_type=work_type):
                    with self.assertRaisesRegex(ValueError, "POCKETHIVE_WORK_TYPE"):
                        self.render(action, "swarm-reduced", "https://pockethive.example:8088", "false", work_type)

    def test_missing_invalid_or_disabled_http_allowance_rejects_before_render(self):
        for action in ("deploy", "update"):
            for profile in ("swarm-reduced", "swarm-full"):
                for scheme, allowance in (("http", "false"), ("http", None),
                                          ("https", None), ("http", "yes"),
                                          ("http", ""), ("ftp", "true")):
                    with self.subTest(action=action, profile=profile, scheme=scheme, allowance=allowance):
                        with self.assertRaisesRegex(ValueError, "POCKETHIVE_ALLOW_REMOTE_HTTP"):
                            self.render(action, profile, f"{scheme}://pockethive.example:8088", allowance)
