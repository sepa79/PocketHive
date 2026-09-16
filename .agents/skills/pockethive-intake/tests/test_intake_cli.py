"""Offline public-CLI qualification; no PocketHive service or AI client calls."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


PACKAGE = Path(__file__).resolve().parents[1]
FIXTURE = PACKAGE / "fixtures" / "fragmented-bundle"
DOCUMENT_NAMES = (
    "requirements.yaml",
    "test-plan.yaml",
    "traceability.yaml",
    "execution-results.yaml",
)


def strings_in(value):
    """Inspect public JSON recursively without depending on inventory nesting."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for entry in value.values():
            yield from strings_in(entry)
    elif isinstance(value, list):
        for entry in value:
            yield from strings_in(entry)


def dictionaries_in(value):
    if isinstance(value, dict):
        yield value
        for entry in value.values():
            yield from dictionaries_in(entry)
    elif isinstance(value, list):
        for entry in value:
            yield from dictionaries_in(entry)


def yaml_codec():
    # Artifact editing uses the bundled YAML library, never CLI implementation
    # internals or a globally installed parser.
    vendor = str(PACKAGE / "vendor")
    if vendor not in sys.path:
        sys.path.insert(0, vendor)
    from ruamel.yaml import YAML

    codec = YAML(typ="rt")
    codec.preserve_quotes = True
    return codec


class CliTestCase(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="pockethive-intake-test-")
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        self.documents = self.workspace / "documents"
        self.marker = self.workspace / "executed-source"

    def invoke(self, *arguments, package=PACKAGE, expected_exit=None, isolated_repository=False, offline=False):
        environment = os.environ.copy()
        environment.pop("PYTHONPATH", None)
        environment.pop("PYTHONHOME", None)
        environment["INTAKE_EXECUTION_MARKER"] = str(self.marker)
        command = [sys.executable, "-B", "-I", "-S"]
        script = str(package / "scripts" / "intake.py")
        if isolated_repository or offline:
            # Relocation must not accidentally load a file from the original
            # checkout or use the network. This hook observes public file/socket
            # operations and does not import intake implementation code.
            guard = """
import os, pathlib, runpy, sys
denied = pathlib.Path(sys.argv[1]).resolve() if sys.argv[1] else None
script = sys.argv[2]
def audit(event, arguments):
    if event in {'socket.connect', 'socket.getaddrinfo', 'subprocess.Popen'}:
        raise RuntimeError('Offline qualification forbids network and child processes')
    if event == 'open' and isinstance(arguments[0], (str, bytes, os.PathLike)):
        path = pathlib.Path(os.fsdecode(arguments[0])).resolve()
        if denied is not None and path.is_relative_to(denied):
            raise RuntimeError('Relocated package accessed the original repository')
sys.addaudithook(audit)
sys.argv = [script, *sys.argv[3:]]
runpy.run_path(script, run_name='__main__')
"""
            denied_root = str(PACKAGE.parents[2]) if isolated_repository else ""
            command.extend(["-c", guard, denied_root, script])
        else:
            command.append(script)
        command.extend(map(str, arguments))
        result = subprocess.run(
            command,
            cwd=self.workspace,
            env=environment,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if expected_exit is not None:
            self.assertEqual(expected_exit, result.returncode, result.stdout + result.stderr)
        try:
            output = json.loads(result.stdout)
        except json.JSONDecodeError:
            self.fail(f"CLI did not return its JSON contract: {result.stdout!r}; stderr={result.stderr!r}")
        self.assertIn(output["status"], {"ok", "incomplete", "error"})
        self.assertIsInstance(output["errors"], list)
        self.assertIsInstance(output["gaps"], list)
        self.assertFalse(self.marker.exists(), "Intake executed source material")
        return result, output

    def initialise(self, mode="new-requirements", source=None):
        arguments = ["initialise", "--output", self.documents, "--mode", mode]
        if source is not None:
            arguments.extend(["--source", source])
        return self.invoke(*arguments, expected_exit=0)

    def read_document(self, name):
        return yaml_codec().load((self.documents / name).read_text(encoding="utf-8"))

    def write_document(self, name, document):
        with (self.documents / name).open("w", encoding="utf-8") as stream:
            yaml_codec().dump(document, stream)


class IntakeCliTests(CliTestCase):
    def test_blank_intake_is_a_usable_draft_but_not_handoff_ready(self):
        self.initialise()
        for name in DOCUMENT_NAMES:
            self.assertTrue((self.documents / name).is_file(), name)
        _, draft = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertEqual("incomplete", draft["status"])
        self.assertFalse(draft["errors"])
        self.assertTrue(draft["gaps"])
        _, handoff = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        self.assertEqual("incomplete", handoff["status"])
        self.assertTrue(handoff["gaps"])

    def test_bundle_mode_requires_an_explicit_source(self):
        self.invoke("initialise", "--output", self.documents, "--mode", "from-bundle", expected_exit=2)
        self.assertFalse(self.documents.exists())

    def test_duplicate_yaml_keys_fail_visibly(self):
        self.initialise()
        target = self.documents / "requirements.yaml"
        with target.open("a", encoding="utf-8") as stream:
            stream.write("\nversion: 2\n")
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertEqual("error", output["status"])
        self.assertTrue(output["errors"])

    def test_executable_yaml_tags_are_rejected_without_side_effects(self):
        self.initialise()
        target = self.documents / "requirements.yaml"
        target.write_text(
            "!!python/object/apply:os.system\n- 'printf executed > " + str(self.marker) + "'\n",
            encoding="utf-8",
        )
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertEqual("error", output["status"])
        self.assertTrue(output["errors"])

    def test_missing_document_is_not_reconstructed_by_validation(self):
        self.initialise()
        missing = self.documents / "test-plan.yaml"
        missing.rename(self.workspace / "saved-test-plan.yaml")
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertFalse(missing.exists())

    def test_fragmented_bundle_inventory_binds_each_file_to_its_bytes(self):
        _, output = self.invoke("inspect-bundle", "--source", FIXTURE, expected_exit=0)
        values = set(strings_in(output))
        for source in FIXTURE.rglob("*"):
            if source.is_file():
                relative = source.relative_to(FIXTURE).as_posix()
                self.assertIn(relative, values, f"Missing source inventory entry: {relative}")
                digest = hashlib.sha256(source.read_bytes()).hexdigest()
                self.assertIn(digest, values, f"Missing source digest: {relative}")

    def test_every_bundle_observation_is_an_explicit_source_scalar(self):
        _, output = self.invoke("inspect-bundle", "--source", FIXTURE, expected_exit=0)
        observations = [entry for entry in dictionaries_in(output) if entry.get("kind") == "bundle-observation"]
        self.assertTrue(observations, "The fixture contains explicit method, protocol and configured rate observations")
        for entry in observations:
            source = FIXTURE / entry["artifactRef"]
            value = yaml_codec().load(source.read_text(encoding="utf-8"))
            for token in entry["pointer"].split("/")[1:]:
                token = token.replace("~1", "/").replace("~0", "~")
                value = value[int(token)] if isinstance(value, list) else value[token]
            self.assertEqual(value, entry["value"], entry)
            self.assertEqual(type(value), type(entry["value"]), entry)
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), entry["sha256"])
        # A custom-image worker called http-processor has no declared capability
        # or protocol field. Neighbouring templates cannot establish either one.
        self.assertFalse(any("workerType" in entry["pointer"] or "capability" in entry["pointer"] for entry in observations))

    def test_bundle_intake_records_source_without_execution_or_client_completion(self):
        self.initialise(mode="from-bundle", source=FIXTURE)
        inspection = self.documents / "source-inspection.json"
        self.assertTrue(inspection.is_file())
        data = json.loads(inspection.read_text(encoding="utf-8"))
        self.assertTrue(data)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff", expected_exit=3)
        self.assertTrue(output["gaps"])

    def test_inspector_does_not_export_arbitrary_config_values(self):
        source = self.workspace / "bundle"
        shutil.copytree(FIXTURE, source)
        scenario = source / "scenario.yaml"
        canary = "synthetic-private-value-not-for-output"
        with scenario.open("a", encoding="utf-8") as stream:
            stream.write(f"\nprivatePayload: {canary}\n")
        result, _ = self.invoke("inspect-bundle", "--source", source, expected_exit=0)
        self.assertNotIn(canary, result.stdout)

    def test_inspection_does_not_modify_bundle_sources(self):
        source = self.workspace / "bundle"
        shutil.copytree(FIXTURE, source)
        before = {path.relative_to(source): path.read_bytes() for path in source.rglob("*") if path.is_file()}
        self.invoke("inspect-bundle", "--source", source, expected_exit=0)
        after = {path.relative_to(source): path.read_bytes() for path in source.rglob("*") if path.is_file()}
        self.assertEqual(before, after)

    def test_blank_templates_do_not_choose_client_business_facts(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        plan = self.read_document("test-plan.yaml")
        results = self.read_document("execution-results.yaml")
        self.assertIsNone(requirements["productionUsage"]["available"])
        self.assertIsNone(requirements["kpis"][0]["targetTps"])
        self.assertIsNone(plan["sutId"])
        self.assertIsNone(plan["executionModel"]["arrivalModel"])
        self.assertNotEqual("approved", plan["approval"]["status"])
        self.assertEqual("not-run", results["runInfo"]["executionStatus"])

    def test_changed_narrative_invalidates_its_recorded_source_hash(self):
        source = self.workspace / "client-requirements.md"
        source.write_text("The client needs account lookup testing. The target is unknown.\n", encoding="utf-8")
        self.initialise(source=source)
        source.write_text("The client changed the requested operation to account creation.\n", encoding="utf-8")
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(output["errors"])

    def test_finalise_hashes_match_saved_bytes_and_are_idempotent(self):
        self.initialise()
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        first = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        traceability = self.read_document("traceability.yaml")["instance"]
        self.assertNotIn("traceability", traceability["documents"])
        for role, filename in (("requirements", "requirements.yaml"), ("plan", "test-plan.yaml"), ("results", "execution-results.yaml")):
            entry = traceability["documents"][role]
            self.assertEqual(filename, entry["path"])
            self.assertEqual(hashlib.sha256(first[filename]).hexdigest(), entry["sha256"])
        plan = self.read_document("test-plan.yaml")
        results = self.read_document("execution-results.yaml")
        self.assertEqual(hashlib.sha256(first["requirements.yaml"]).hexdigest(), plan["requirementsSnapshot"]["sha256"])
        self.assertEqual(hashlib.sha256(first["test-plan.yaml"]).hexdigest(), results["references"]["planSha256"])
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.assertEqual(first, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_question_owner_updates_projection_but_projection_cannot_be_edited(self):
        self.initialise()
        traceability = self.read_document("traceability.yaml")
        question = "Which successful business transaction does the client want measured?"
        traceability["instance"]["questions"].append({
            "id": "qualification-question",
            "targets": [{"document": "requirements", "pointer": "/kpis/0/targetTps"}],
            "question": question,
            "owner": None,
            "blockingStage": "handoff",
            "status": "open",
            "answerRef": None,
        })
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        requirements = self.read_document("requirements.yaml")
        self.assertTrue(any(question in value for value in strings_in(requirements["openQuestions"])))
        requirements["openQuestions"] = ["An independent editor silently removed the actual question."]
        self.write_document("requirements.yaml", requirements)
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_forged_review_and_approval_statuses_do_not_enable_handoff(self):
        self.initialise()
        plan = self.read_document("test-plan.yaml")
        plan["approval"]["status"] = "approved"
        plan["approval"]["approvedBy"] = "fictional-approver"
        plan["generation"]["approval"]["status"] = "approved"
        self.write_document("test-plan.yaml", plan)
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["review"]["status"] = "confirmed"
        traceability["instance"]["review"]["evidenceRef"] = None
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff")
        self.assertNotEqual("ok", output["status"])
        issues = output["errors"] + output["gaps"]
        self.assertTrue(any("approval" in issue["pointer"].lower() or "review" in issue["pointer"].lower() for issue in issues))

    def test_unsourced_sample_target_remains_an_explicit_gap(self):
        self.initialise()
        requirements = self.read_document("requirements.yaml")
        requirements["kpis"][0]["targetTps"] = 5
        self.write_document("requirements.yaml", requirements)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff")
        self.assertNotEqual("ok", output["status"])
        issues = output["errors"] + output["gaps"]
        self.assertTrue(any(issue["pointer"].startswith("/kpis") for issue in issues), issues)

    def test_renamed_example_is_not_client_evidence_without_explicit_adoption(self):
        self.initialise()
        example = PACKAGE / "assets" / "source" / "PERFORMANCE_TEST_REQUIREMENTS_SAMPLE_V1.yaml"
        renamed = self.workspace / "client-supplied-requirements.yaml"
        renamed.write_bytes(example.read_bytes())
        requirements = self.read_document("requirements.yaml")
        requirements["kpis"][0]["targetTps"] = 5
        self.write_document("requirements.yaml", requirements)
        traceability = self.read_document("traceability.yaml")
        traceability["instance"]["provenance"].append({
            "target": {"document": "requirements", "pointer": "/kpis/0/targetTps"},
            "kind": "client-statement",
            "sources": [{"artifactRef": str(renamed), "pointer": "/kpis/0/targetTps",
                         "sha256": hashlib.sha256(renamed.read_bytes()).hexdigest()}],
            "confirmationRef": None, "calculation": None,
        })
        self.write_document("traceability.yaml", traceability)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "handoff")
        self.assertTrue(any(issue["code"] == "SAMPLE_ADOPTION" for issue in output["gaps"]), output)

    def test_no_execution_cannot_be_reported_as_pass(self):
        self.initialise()
        results = self.read_document("execution-results.yaml")
        results["overallResult"]["result"] = "pass"
        self.write_document("execution-results.yaml", results)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        _, output = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any("overallResult" in issue["pointer"] or "executionStatus" in issue["pointer"] for issue in output["errors"]))

    def test_initialise_never_overwrites_existing_documents(self):
        self.initialise()
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        self.invoke("initialise", "--output", self.documents, "--mode", "new-requirements", expected_exit=2)
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_parser_errors_do_not_echo_untrusted_source_contents(self):
        self.initialise()
        canary = "synthetic-source-value-must-not-appear-in-errors"
        with (self.documents / "requirements.yaml").open("a", encoding="utf-8") as stream:
            stream.write(f"\nunparseable: [{canary}\n")
        result, _ = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertNotIn(canary, result.stdout + result.stderr)

    def test_bundle_symlink_cannot_expose_an_outside_file(self):
        source = self.workspace / "bundle"
        shutil.copytree(FIXTURE, source)
        outside = self.workspace / "outside.txt"
        outside.write_text("synthetic-outside-private-content", encoding="utf-8")
        (source / "external.yaml").symlink_to(outside)
        result, _ = self.invoke("inspect-bundle", "--source", source, expected_exit=2)
        self.assertNotIn("synthetic-outside-private-content", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
