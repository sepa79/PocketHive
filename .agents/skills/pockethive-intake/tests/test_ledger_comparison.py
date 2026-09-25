"""Public CLI qualification of stable question and proposal review comparisons."""
from __future__ import annotations

from copy import deepcopy
import shutil

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, yaml_codec


class LedgerComparisonTests(CliTestCase):
    def question(self, identity, status="open", text="Which acceptance decision is needed?"):
        return {"id": identity, "targets": [{"document": "requirements", "pointer": "/project/objective"}],
                "question": text, "owner": "client", "blockingStage": "handoff", "status": status,
                "answerRef": str(self.workspace / "answer.txt") if status == "answered" else None}

    def proposal(self, identity, status="proposed", rationale="Measure the stated business outcome."):
        return {"id": identity, "targets": [{"document": "requirements", "pointer": "/project/objective"}],
                "rationale": rationale, "sources": [], "unresolvedPremises": [],
                "status": status, "decisionRef": None}

    def set_ledgers(self, questions, proposals, root=None):
        root = self.documents if root is None else root
        target = root / "traceability.yaml"
        document = yaml_codec().load(target.read_text(encoding="utf-8"))
        document["instance"]["questions"] = questions
        document["instance"]["proposals"] = proposals
        with target.open("w", encoding="utf-8") as stream:
            yaml_codec().dump(document, stream)

    def snapshot(self, questions, proposals=()):
        self.initialise()
        (self.workspace / "answer.txt").write_text("Explicit client response retained for this review.\n")
        self.set_ledgers(questions, list(proposals))
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        previous = self.workspace / "previous"
        shutil.copytree(self.documents, previous)
        return previous

    def compare(self, previous, expected_exit=0):
        return self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft",
                           "--previous", previous, expected_exit=expected_exit)

    def document_bytes(self, root):
        return {name: (root / name).read_bytes() for name in DOCUMENT_NAMES}

    def test_question_changes_follow_identity_and_explicit_status_transitions(self):
        old_text = "prior-question-content-not-requested-in-comparison"
        previous = self.snapshot([
            self.question("Q-reopen", "answered"), self.question("Q-answer"),
            self.question("Q-remove"), self.question("Q-remove-answered", "answered"),
            self.question("Q-edit", text=old_text), self.question("Q-decline"),
        ])
        previous_bytes = self.document_bytes(previous)
        current = [self.question("Q-edit", text="Which completion window applies?"),
                   self.question("Q-add", "answered"), self.question("Q-answer", "answered"),
                   self.question("Q-reopen", "declined"), self.question("Q-decline", "declined")]
        self.set_ledgers(current, [])
        raw, output = self.compare(previous)
        self.assertEqual([
            {"id": "Q-add", "change": "added", "previousPointer": None, "pointer": "/instance/questions/1"},
            {"id": "Q-answer", "change": "answered", "previousPointer": "/instance/questions/1", "pointer": "/instance/questions/2"},
            {"id": "Q-decline", "change": "changed", "previousPointer": "/instance/questions/5", "pointer": "/instance/questions/4"},
            {"id": "Q-edit", "change": "changed", "previousPointer": "/instance/questions/4", "pointer": "/instance/questions/0"},
            {"id": "Q-remove", "change": "removed", "previousPointer": "/instance/questions/2", "pointer": None},
            {"id": "Q-remove-answered", "change": "removed", "previousPointer": "/instance/questions/3", "pointer": None},
            {"id": "Q-reopen", "change": "reopened", "previousPointer": "/instance/questions/0", "pointer": "/instance/questions/3"},
        ], output["brief"]["comparison"]["ledgerChanges"]["questions"])
        self.assertEqual(previous_bytes, self.document_bytes(previous))
        self.assertEqual(current, self.read_document("traceability.yaml")["instance"]["questions"])
        self.assertNotIn(old_text, raw.stdout + raw.stderr)
        # A recorded status transition does not manufacture verified answer evidence.
        self.assertTrue(any(issue["code"] == "ANSWER_EVIDENCE" for issue in output["gaps"]))

    def test_unchanged_reordering_has_no_ledger_changes_and_retains_exact_pointer_diff(self):
        questions = [self.question("Q-a"), self.question("Q-b", "declined")]
        proposals = [self.proposal("P-a"), self.proposal("P-b", "rejected")]
        previous = self.snapshot(questions, proposals)
        self.set_ledgers(list(reversed(questions)), list(reversed(proposals)))
        _, first = self.compare(previous)
        comparison = first["brief"]["comparison"]
        self.assertEqual({"questions": [], "proposals": []}, comparison["ledgerChanges"])
        for collection in ("questions", "proposals"):
            self.assertIn({"document": "traceability", "pointer": f"/instance/{collection}/0/id", "change": "changed"},
                          comparison["changedFields"])
        current_bytes = self.document_bytes(self.documents)
        _, repeated = self.compare(previous)
        self.assertEqual(comparison, repeated["brief"]["comparison"])
        self.assertEqual(current_bytes, self.document_bytes(self.documents))

    def test_proposal_changes_remain_observations_without_adoption_or_old_content(self):
        old_rationale = "prior-proposal-rationale-must-not-appear-in-diff"
        previous = self.snapshot([], [self.proposal("P-remove"), self.proposal("P-status"),
                                      self.proposal("P-edit", rationale=old_rationale)])
        current = [self.proposal("P-edit", rationale="Use the explicitly supplied completion definition."),
                   self.proposal("P-add"), self.proposal("P-status", "accepted")]
        self.set_ledgers([], current)
        raw, output = self.compare(previous)
        self.assertEqual([
            {"id": "P-add", "change": "added", "previousPointer": None, "pointer": "/instance/proposals/1"},
            {"id": "P-edit", "change": "changed", "previousPointer": "/instance/proposals/2", "pointer": "/instance/proposals/0"},
            {"id": "P-remove", "change": "removed", "previousPointer": "/instance/proposals/0", "pointer": None},
            {"id": "P-status", "change": "changed", "previousPointer": "/instance/proposals/1", "pointer": "/instance/proposals/2"},
        ], output["brief"]["comparison"]["ledgerChanges"]["proposals"])
        self.assertNotIn(old_rationale, raw.stdout + raw.stderr)
        self.assertEqual(current, self.read_document("traceability.yaml")["instance"]["proposals"])
        self.assertTrue(any(issue["code"] == "PROPOSAL_DECISION" for issue in output["gaps"]))
        self.assertEqual("draft", output["brief"]["currentReview"]["status"])

    def test_null_status_transitions_are_reported_only_when_recorded(self):
        previous = self.snapshot([self.question("Q-in", None), self.question("Q-out", "answered")])
        self.set_ledgers([self.question("Q-in", "answered"), self.question("Q-out", None)], [])
        _, output = self.compare(previous)
        self.assertEqual([("Q-in", "answered"), ("Q-out", "reopened")],
                         [(row["id"], row["change"]) for row in output["brief"]["comparison"]["ledgerChanges"]["questions"]])

    def test_duplicate_identity_in_either_snapshot_is_rejected_by_canonical_index(self):
        previous = self.snapshot([self.question("Q-valid")], [self.proposal("P-valid")])
        original_previous = self.document_bytes(previous)
        for snapshot in ("previous", "current"):
            for collection in ("questions", "proposals"):
                with self.subTest(snapshot=snapshot, collection=collection):
                    self.set_ledgers([self.question("Q-valid")], [self.proposal("P-valid")], previous)
                    self.set_ledgers([self.question("Q-valid")], [self.proposal("P-valid")])
                    questions, proposals = [self.question("Q-valid")], [self.proposal("P-valid")]
                    rows = questions if collection == "questions" else proposals
                    rows.append(deepcopy(rows[0]))
                    target = previous if snapshot == "previous" else self.documents
                    self.set_ledgers(questions, proposals, target)
                    _, output = self.compare(previous, expected_exit=2)
                    self.assertEqual("DUPLICATE_ID", output["errors"][0]["code"])
                    self.assertEqual(f"/instance/{collection}/1/id", output["errors"][0]["pointer"])
                    self.assertEqual(snapshot, output["errors"][0]["detail"]["snapshot"])
                    self.assertNotIn("brief", output)
        # Reading an invalid previous source does not finalise or repair it.
        for name, data in original_previous.items():
            if name != "traceability.yaml":
                self.assertEqual(data, (previous / name).read_bytes())

    def test_comparison_requires_present_ids_without_tightening_partial_drafts(self):
        previous = self.snapshot([], [])
        for snapshot in ("previous", "current"):
            for collection in ("questions", "proposals"):
                for identity in (None, "", "  "):
                    with self.subTest(snapshot=snapshot, collection=collection, identity=identity):
                        self.set_ledgers([], [], previous)
                        self.set_ledgers([], [])
                        questions = [self.question(identity)] if collection == "questions" else []
                        proposals = [self.proposal(identity)] if collection == "proposals" else []
                        self.set_ledgers(questions, proposals, previous if snapshot == "previous" else self.documents)
                        _, output = self.compare(previous, expected_exit=2)
                        self.assertEqual("IDENTITY_REQUIRED", output["errors"][0]["code"])
                        self.assertEqual(f"/instance/{collection}/0/id", output["errors"][0]["pointer"])
                        self.assertEqual(snapshot, output["errors"][0]["detail"]["snapshot"])
                        self.assertNotIn("brief", output)
        self.set_ledgers([self.question(None)], [self.proposal(None)])
        _, draft = self.invoke("prepare-review", "--documents", self.documents, "--stage", "draft", expected_exit=0)
        self.assertFalse(draft["errors"])
        self.assertIsNone(draft["brief"]["unansweredQuestions"][0]["id"])

    def test_previous_ledger_shape_uses_the_packaged_schema_and_safe_errors(self):
        previous = self.snapshot([], [])
        for collection in ("questions", "proposals"):
            for invalid_field, value in (("id", ["private-source-canary"]), ("status", "private-source-canary")):
                with self.subTest(collection=collection, invalid_field=invalid_field):
                    questions = [self.question("Q-valid")] if collection == "questions" else []
                    proposals = [self.proposal("P-valid")] if collection == "proposals" else []
                    rows = questions if collection == "questions" else proposals
                    rows[0][invalid_field] = value
                    self.set_ledgers(questions, proposals, previous)
                    before = self.document_bytes(previous)
                    raw, output = self.compare(previous, expected_exit=2)
                    self.assertEqual("SCHEMA", output["errors"][0]["code"])
                    self.assertEqual("previous", output["errors"][0]["detail"]["snapshot"])
                    self.assertEqual(f"/instance/{collection}/0/{invalid_field}", output["errors"][0]["pointer"])
                    self.assertNotIn("private-source-canary", raw.stdout + raw.stderr)
                    self.assertEqual(before, self.document_bytes(previous))
                    self.assertNotIn("brief", output)

    def test_sparse_previous_ledger_reports_exact_missing_pointer_without_repair(self):
        previous = self.snapshot([], [])
        path = previous / "traceability.yaml"
        original = yaml_codec().load(path.read_text())
        for missing in ("instance", "questions", "proposals"):
            with self.subTest(missing=missing):
                sparse = deepcopy(original)
                if missing == "instance":
                    del sparse["instance"]
                else:
                    del sparse["instance"][missing]
                with path.open("w", encoding="utf-8") as stream:
                    yaml_codec().dump(sparse, stream)
                before = self.document_bytes(previous)
                _, output = self.compare(previous, expected_exit=2)
                issue = output["errors"][0]
                self.assertEqual("POINTER", issue["code"])
                self.assertEqual("traceability", issue["document"])
                self.assertEqual("/instance/proposals" if missing == "proposals" else "/instance/questions", issue["pointer"])
                self.assertEqual("previous", issue["detail"]["snapshot"])
                self.assertEqual("" if missing == "instance" else "/instance", issue["detail"]["resolvedAncestor"])
                self.assertEqual(before, self.document_bytes(previous))
                self.assertNotIn("brief", output)
