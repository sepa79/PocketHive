"""Public CLI qualification of bounded multi-field views from one document revision."""
from __future__ import annotations

import json
import subprocess
import sys

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, FIXTURE, PACKAGE


class BulkFieldTests(CliTestCase):
    def setUp(self):
        super().setUp()
        self.initialise()

    def request(self, content, expected_exit=0):
        source = self.workspace / "field-targets.json"
        source.write_text(json.dumps(content), encoding="utf-8")
        return self.invoke("show-fields", "--documents", self.documents,
                           "--input", source, expected_exit=expected_exit)

    @staticmethod
    def target(role, pointer):
        return {"document": role, "pointer": pointer}

    def test_bulk_fields_match_single_views_in_requested_order_without_writes(self):
        targets = [self.target("plan", "/approval"), self.target("requirements", "/project/objective"),
                   self.target("traceability", "/instance/questions")]
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        _, output = self.request({"targets": targets})
        for index, target in enumerate(targets):
            _, single = self.invoke("show-field", "--documents", self.documents,
                                     "--document", target["document"], "--pointer", target["pointer"], expected_exit=0)
            expected = {**single["field"], **{category: single[category] for category in ("errors", "gaps", "warnings")}}
            self.assertEqual(expected, output["fields"][index])
            for key in ("documentsSha256", "reviewContentSha256", "validationSummary"):
                self.assertEqual(single[key], output[key])
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_overlapping_requested_fields_keep_one_top_level_diagnostic(self):
        targets = [self.target("requirements", "/project"), self.target("requirements", "/project/objective")]
        _, output = self.request({"targets": targets})
        _, single = self.invoke("show-field", "--documents", self.documents,
                                 "--document", "requirements", "--pointer", "/project", expected_exit=0)
        for category in ("errors", "gaps", "warnings"):
            self.assertEqual(single[category], output[category])
            self.assertEqual(len(output[category]), len({json.dumps(issue, sort_keys=True) for issue in output[category]}))

    def test_target_shape_count_and_duplicates_fail_before_returning_fields(self):
        target = self.target("requirements", "/project")
        invalid = [{}, {"targets": []}, {"targets": [target], "extra": True}, {"targets": [target] * 51},
                   {"targets": [target, target]}, {"targets": [{"document": "requirements"}]},
                   {"targets": [{"document": "requirements", "pointer": None}]}]
        for content in invalid:
            with self.subTest(content=content):
                _, output = self.request(content, expected_exit=2)
                self.assertEqual("FIELD_TARGETS", output["errors"][0]["code"])
                self.assertNotIn("fields", output)

    def test_any_bad_target_fails_without_partial_field_values(self):
        for target, code in ((self.target("unknown", "/project"), "DOCUMENT_ROLE"),
                             (self.target("requirements", "/project/ownr"), "POINTER")):
            with self.subTest(target=target):
                _, output = self.request({"targets": [self.target("requirements", "/project"), target]}, expected_exit=2)
                self.assertEqual(code, output["errors"][0]["code"])
                self.assertNotIn("fields", output)
                if code == "POINTER":
                    self.assertEqual("/project", output["errors"][0]["detail"]["resolvedAncestor"])

    def test_input_uses_canonical_safe_yaml_codec(self):
        source = self.workspace / "field-targets.yaml"
        source.write_text("targets:\n  - document: requirements\n    pointer: /project/owner\n", encoding="utf-8")
        self.invoke("show-fields", "--documents", self.documents, "--input", source, expected_exit=0)
        source.write_text("targets: !!python/object:unsafe {}\n", encoding="utf-8")
        _, output = self.invoke("show-fields", "--documents", self.documents, "--input", source, expected_exit=2)
        self.assertEqual("YAML_TAG", output["errors"][0]["code"])


class BulkValidationReadTests(CliTestCase):
    def test_many_field_views_do_not_repeat_source_validation(self):
        self.initialise("from-bundle", FIXTURE)
        request = self.workspace / "fields.json"
        request.write_text(json.dumps({"targets": [{"document": "requirements", "pointer": pointer}
                           for pointer in ("/project", "/project/owner", "/project/objective")]}), encoding="utf-8")
        guard = """
import atexit, json, os, pathlib, runpy, sys
source = pathlib.Path(sys.argv[1]).resolve()
script = sys.argv[2]
reads = []
def audit(event, args):
    if event == 'open' and isinstance(args[0], (str, bytes, os.PathLike)):
        path = pathlib.Path(os.fsdecode(args[0])).resolve()
        if path.is_relative_to(source):
            reads.append(str(path.relative_to(source)))
sys.addaudithook(audit)
atexit.register(lambda: print(json.dumps({'sourceReads': reads}), file=sys.stderr))
sys.argv = [script, *sys.argv[3:]]
runpy.run_path(script, run_name='__main__')
"""
        observed = []
        commands = [("show-field", "--documents", self.documents, "--document", "requirements", "--pointer", "/project"),
                    ("show-fields", "--documents", self.documents, "--input", request)]
        for arguments in commands:
            result = subprocess.run([sys.executable, "-B", "-I", "-S", "-c", guard, str(FIXTURE),
                                     str(PACKAGE / "scripts/intake.py"), *map(str, arguments)],
                                    cwd=self.workspace, capture_output=True, text=True, timeout=30, check=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse(json.loads(result.stdout)["errors"])
            observed.append(json.loads(result.stderr)["sourceReads"])
        self.assertTrue(observed[0])
        self.assertEqual(observed[0], observed[1])
