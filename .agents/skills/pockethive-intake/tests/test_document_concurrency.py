"""Public process-level checks of revision guards and cooperative write exclusion."""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class DocumentConcurrencyTests(CliTestCase):
    def test_existing_lock_is_never_guessed_stale_or_removed(self):
        self.initialise()
        lock = self.documents / ".intake-write.lock"
        lock.mkdir()
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        for command in ("finalise", "prepare-review", "validate"):
            arguments = [command, "--documents", self.documents]
            if command != "finalise":
                arguments.extend(["--stage", "draft"])
            _, result = self.invoke(*arguments, expected_exit=2)
            self.assertEqual("DOCUMENTS_BUSY", result["errors"][0]["code"])
            self.assertTrue(lock.is_dir())
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_failed_operation_releases_its_lock(self):
        self.initialise()
        target = self.documents / "requirements.yaml"
        original = target.read_bytes()
        target.write_bytes(b"invalid: [\n")
        self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        self.assertFalse((self.documents / ".intake-write.lock").exists())
        target.write_bytes(original)
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)

    def test_two_batches_from_one_revision_cannot_overwrite_each_other(self):
        _, initial = self.initialise()
        processes = []
        for index in range(2):
            answer = self.workspace / f"answer-{index}.txt"
            statement = f"Explicit client objective {index}"
            answer.write_text(statement, encoding="utf-8")
            target = {"document": "requirements", "pointer": "/project/objective"}
            payload = {"expectedDocumentsSha256": initial["documentsSha256"], "updates": [{
                "target": target, "value": statement, "provenance": {"target": target, "kind": "client-statement",
                "sources": [{"artifactRef": str(answer), "sha256": hashlib.sha256(answer.read_bytes()).hexdigest(), "pointer": None}],
                "confirmationRef": None, "calculation": None}}]}
            request = self.workspace / f"update-{index}.json"
            request.write_text(json.dumps(payload), encoding="utf-8")
            processes.append(subprocess.Popen([sys.executable, "-B", "-I", "-S", str(PACKAGE / "scripts/intake.py"),
                                               "apply-updates", "--documents", str(self.documents), "--input", str(request)],
                                              cwd=self.workspace, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True))
        results = []
        for process in processes:
            stdout, stderr = process.communicate(timeout=30)
            results.append((process.returncode, json.loads(stdout)))
            self.assertFalse(stderr)
        self.assertEqual([0, 2], sorted(code for code, _ in results), results)
        winner = next(index for index, (code, _) in enumerate(results) if code == 0)
        failure = next(output for code, output in results if code == 2)
        self.assertIn(failure["errors"][0]["code"], {"DOCUMENTS_BUSY", "STALE_DOCUMENTS"})
        self.assertEqual(f"Explicit client objective {winner}", self.read_document("requirements.yaml")["project"]["objective"])
        self.assertFalse((self.documents / ".intake-write.lock").exists())
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=0)

    def test_external_edit_during_validation_is_preserved_and_rejects_stale_save(self):
        request, answer = self._request()
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        hook = """
import pathlib, runpy, sys
script, docs, evidence, request = sys.argv[1:]
changed = False
def audit(event, arguments):
    global changed
    if not changed and event == 'open' and str(arguments[0]) == evidence:
        changed = True
        with (pathlib.Path(docs) / 'requirements.yaml').open('ab') as handle:
            handle.write(b'\\n# explicit external edit during operation\\n')
sys.addaudithook(audit)
sys.argv = [script, 'apply-updates', '--documents', docs, '--input', request]
runpy.run_path(script, run_name='__main__')
"""
        process = subprocess.run([sys.executable, "-B", "-I", "-S", "-c", hook,
                                  str(PACKAGE / "scripts/intake.py"), str(self.documents), str(answer), str(request)],
                                 capture_output=True, text=True, timeout=30, cwd=self.workspace)
        self.assertEqual(2, process.returncode, process.stdout + process.stderr)
        output = json.loads(process.stdout)
        self.assertEqual("STALE_DOCUMENTS", output["errors"][0]["code"])
        self.assertIsNone(self.read_document("requirements.yaml")["project"]["objective"])
        self.assertEqual(before["requirements.yaml"] + b"\n# explicit external edit during operation\n",
                         (self.documents / "requirements.yaml").read_bytes())
        for name in DOCUMENT_NAMES[1:]:
            self.assertEqual(before[name], (self.documents / name).read_bytes())

    def test_interrupted_set_write_reports_failure_and_stale_hashes(self):
        request, _ = self._request()
        hook = """
import pathlib, runpy, sys
script, docs, request = sys.argv[1:]
def audit(event, arguments):
    if event == 'os.rename' and pathlib.Path(arguments[1]) == pathlib.Path(docs) / 'test-plan.yaml':
        raise OSError('Synthetic write failure')
sys.addaudithook(audit)
sys.argv = [script, 'apply-updates', '--documents', docs, '--input', request]
runpy.run_path(script, run_name='__main__')
"""
        process = subprocess.run([sys.executable, "-B", "-I", "-S", "-c", hook,
                                  str(PACKAGE / "scripts/intake.py"), str(self.documents), str(request)],
                                 capture_output=True, text=True, timeout=30, cwd=self.workspace)
        self.assertEqual(2, process.returncode, process.stdout + process.stderr)
        output = json.loads(process.stdout)
        self.assertEqual("WRITE_FAILED", output["errors"][0]["code"])
        self.assertFalse(output.get("applied", False))
        self.assertFalse((self.documents / ".intake-write.lock").exists())
        _, checked = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
        self.assertTrue(any(issue["code"] == "STALE_PROJECTION" for issue in checked["errors"]))
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)

    def test_post_write_change_cannot_become_the_successful_saved_revision(self):
        request, _ = self._request()
        hook = """
import pathlib, runpy, sys
script, docs, request = sys.argv[1:]
last_write = False
changed = False
def audit(event, arguments):
    global last_write, changed
    if event == 'os.rename' and pathlib.Path(arguments[1]) == pathlib.Path(docs) / 'traceability.yaml':
        last_write = True
    if last_write and not changed and event == 'open' and str(arguments[0]) == str(pathlib.Path(docs) / 'traceability.yaml'):
        changed = True
        with (pathlib.Path(docs) / 'requirements.yaml').open('ab') as handle:
            handle.write(b'\\n# external edit after per-file readback\\n')
sys.addaudithook(audit)
sys.argv = [script, 'apply-updates', '--documents', docs, '--input', request]
runpy.run_path(script, run_name='__main__')
"""
        process = subprocess.run([sys.executable, "-B", "-I", "-S", "-c", hook,
                                  str(PACKAGE / "scripts/intake.py"), str(self.documents), str(request)],
                                 capture_output=True, text=True, timeout=30, cwd=self.workspace)
        self.assertEqual(2, process.returncode, process.stdout + process.stderr)
        output = json.loads(process.stdout)
        self.assertEqual("STALE_DOCUMENTS", output["errors"][0]["code"])
        self.assertFalse(output.get("applied", False))
        self.assertTrue((self.documents / "requirements.yaml").read_bytes().endswith(b"# external edit after per-file readback\n"))
        self.assertFalse((self.documents / ".intake-write.lock").exists())

    def _request(self):
        _, initial = self.initialise()
        answer = self.workspace / "client-answer.txt"
        answer.write_text("An explicit revised objective.", encoding="utf-8")
        target = {"document": "requirements", "pointer": "/project/objective"}
        request = self.workspace / "edit.json"
        request.write_text(json.dumps({"expectedDocumentsSha256": initial["documentsSha256"], "updates": [{
            "target": target, "value": answer.read_text(), "provenance": {"target": target, "kind": "client-statement",
            "sources": [{"artifactRef": str(answer), "sha256": hashlib.sha256(answer.read_bytes()).hexdigest(), "pointer": None}],
            "confirmationRef": None, "calculation": None}}]}), encoding="utf-8")
        return request, answer
