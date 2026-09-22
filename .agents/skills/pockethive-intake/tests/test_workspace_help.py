"""Public CLI diagnostics for existing document directories and retained locks."""
from __future__ import annotations

import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class WorkspaceHelpTests(CliTestCase):
    def test_complete_and_partial_sets_get_distinct_non_destructive_guidance(self):
        self.initialise()
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        _, result = self.invoke("initialise", "--output", self.documents, "--mode", "new-requirements", expected_exit=2)
        issue = result["errors"][0]
        self.assertEqual("OUTPUT_EXISTS", issue["code"])
        self.assertEqual("complete", issue["detail"]["directoryState"])
        self.assertEqual([], issue["detail"]["missingDocuments"])
        self.assertIn("prepare-review", issue["detail"]["nextAction"])
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        partial = self.workspace / "partial"
        partial.mkdir()
        shutil.copyfile(self.documents / "requirements.yaml", partial / "requirements.yaml")
        _, result = self.invoke("initialise", "--output", partial, "--mode", "new-requirements", expected_exit=2)
        detail = result["errors"][0]["detail"]
        self.assertEqual("partial", detail["directoryState"])
        self.assertEqual(["requirements.yaml"], detail["existingDocuments"])
        self.assertEqual(sorted(set(DOCUMENT_NAMES) - {"requirements.yaml"}), detail["missingDocuments"])
        self.assertEqual(["requirements.yaml"], [path.name for path in partial.iterdir()])
        self.assertEqual(before["requirements.yaml"], (partial / "requirements.yaml").read_bytes())

    def test_unrelated_files_and_non_directory_output_are_identified_without_dumping_names(self):
        self.documents.mkdir()
        private = self.documents / "client-confidential-filename.txt"
        private.write_text("client-confidential-payload")
        for root, state in ((self.documents, "unrelated"), (private, "not-directory")):
            with self.subTest(state=state):
                process, result = self.invoke("initialise", "--output", root, "--mode", "new-requirements", expected_exit=2)
                detail = result["errors"][0]["detail"]
                self.assertEqual(state, detail["directoryState"])
                self.assertEqual([], detail["existingDocuments"])
                self.assertNotIn("client-confidential-payload", process.stdout)
                if state == "unrelated":
                    self.assertNotIn(private.name, process.stdout)
        self.assertEqual("client-confidential-payload", private.read_text())

    def test_read_and_write_lock_failures_name_lock_and_never_remove_it(self):
        self.initialise()
        lock = self.documents / ".intake-write.lock"
        lock.mkdir()
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        outputs = []
        for args in (("show-field", "--document", "requirements", "--pointer", "/project/objective"),
                     ("prepare-review", "--stage", "draft")):
            _, result = self.invoke(*args, "--documents", self.documents, expected_exit=2)
            outputs.append(result["errors"][0])
            self.assertEqual("DOCUMENTS_BUSY", outputs[-1]["code"])
            self.assertEqual(str(lock), outputs[-1]["detail"]["lockPath"])
            self.assertIn("no writer remains", outputs[-1]["detail"]["nextAction"])
            self.assertTrue(lock.is_dir())
            self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})
        self.assertEqual(outputs[0], outputs[1])
