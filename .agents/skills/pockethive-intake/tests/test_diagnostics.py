"""CLI diagnostics preserve machine-readable output and redact source values."""
from __future__ import annotations

import json
import subprocess
import sys

from test_intake_cli import CliTestCase, PACKAGE


class DiagnosticTests(CliTestCase):
    def test_root_schema_error_uses_the_empty_document_root_pointer(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        del requirements["version"]
        self.write_document("requirements.yaml", requirements)
        _, output = self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        error = output["errors"][0]
        self.assertEqual("requirements", error["document"])
        self.assertEqual("", error["pointer"])
        self.assertEqual("required", error["detail"]["rule"])

    def test_debug_yaml_errors_keep_document_context_and_hide_parser_content(self):
        self.initialise()
        for source, expected in (("42: PRIVATE-KEY-PAYLOAD\n", "YAML_KEY"),
                                 ('broken: ["PRIVATE-PARSER-PAYLOAD",\n', "YAML_PARSE")):
            (self.documents / "requirements.yaml").write_text(source)
            result, output = self.invoke("finalise", "--documents", self.documents, "--debug", expected_exit=2)
            self.assertEqual(expected, output["errors"][0]["code"])
            self.assertEqual("requirements", output["errors"][0]["document"])
            self.assertNotIn("PRIVATE-", result.stdout + result.stderr)
            self.assertEqual("IntakeError", json.loads(result.stderr)["exceptionType"])

    def test_schema_error_names_document_pointer_rule_and_expected_shape(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        requirements["openQuestions"] = "PRIVATE-PAYLOAD-MUST-NOT-LEAK"
        self.write_document("requirements.yaml", requirements)
        for placement in ("before", "after"):
            args = ["finalise", "--documents", self.documents]
            args.insert(0 if placement == "before" else len(args), "--debug")
            result, output = self.invoke(*args, expected_exit=2)
            error = output["errors"][0]
            self.assertEqual(("SCHEMA", "requirements", "/openQuestions"),
                             (error["code"], error["document"], error["pointer"]))
            self.assertEqual("type", error["detail"]["rule"])
            self.assertEqual("array", error["detail"]["expected"])
            self.assertNotIn("PRIVATE-PAYLOAD-MUST-NOT-LEAK", result.stdout + result.stderr)
            self.assertEqual("finalise", json.loads(result.stderr)["command"])

    def test_unknown_binding_type_reports_allowed_values_without_source_content(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        requirements["templates"][0]["payloadBindings"][0]["source"]["type"] = "PRIVATE-UNKNOWN-TYPE"
        self.write_document("requirements.yaml", requirements)
        result, output = self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        error = output["errors"][0]
        self.assertEqual("enum", error["detail"]["rule"])
        self.assertIn("correlation", error["detail"]["expected"])
        self.assertNotIn("PRIVATE-UNKNOWN-TYPE", result.stdout)
        self.assertEqual("", result.stderr)

    def test_prior_plan_version_has_an_explicit_migration_error(self):
        self.initialise()
        plan = self.read_document("test-plan.yaml")
        plan["version"] = 4
        self.write_document("test-plan.yaml", plan)
        _, output = self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        error = output["errors"][0]
        self.assertEqual(("DOCUMENT_VERSION", "plan", "/version"),
                         (error["code"], error["document"], error["pointer"]))
        self.assertEqual(5, error["detail"]["expectedVersion"])

    def test_unexpected_exception_reports_type_and_safe_code_frames(self):
        # Inject at the CLI operation boundary, leaving package verification and
        # JSON/error handling real. Exception text deliberately contains secrets.
        driver = """
import pathlib, sys
sys.path.insert(0, sys.argv[1])
from intake_lib import cli, commands
kind = getattr(__import__('builtins'), sys.argv[2])
def broken(*args):
    raise kind('PRIVATE-CREDENTIAL-IN-EXCEPTION')
commands.execute = broken
sys.argv = ['intake.py', *sys.argv[3:]]
raise SystemExit(cli.main())
"""
        for kind in ("KeyError", "TypeError", "RuntimeError"):
            with self.subTest(kind=kind):
                result = subprocess.run([sys.executable, "-B", "-I", "-S", "-c", driver,
                                         str(PACKAGE / "scripts"), kind, "--debug", "finalise",
                                         "--documents", str(self.documents)], capture_output=True,
                                        text=True, timeout=30)
                self.assertEqual(2, result.returncode, result.stdout + result.stderr)
                output, debug = json.loads(result.stdout), json.loads(result.stderr)
                self.assertEqual("COMMAND_FAILED", output["errors"][0]["code"])
                self.assertEqual(kind, output["errors"][0]["detail"]["exceptionType"])
                self.assertEqual(kind, debug["exceptionType"])
                self.assertTrue(any(f["file"] == "scripts/intake_lib/cli.py" for f in debug["frames"]))
                self.assertNotIn("PRIVATE-CREDENTIAL-IN-EXCEPTION", result.stdout + result.stderr)
                self.assertNotIn(str(PACKAGE), result.stdout + result.stderr)

    def test_debug_success_does_not_add_stdout_objects(self):
        result, output = self.invoke("verify-package", "--debug", expected_exit=0)
        self.assertEqual("ok", output["status"])
        self.assertEqual("", result.stderr)
