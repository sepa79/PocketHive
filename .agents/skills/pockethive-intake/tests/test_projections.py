"""Public CLI regressions for generated-question ownership and fresh drafts."""
from __future__ import annotations

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class ProjectionTests(CliTestCase):
    def snapshot(self):
        return {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}

    def test_raw_working_templates_finalise_without_authored_projection(self):
        self.documents.mkdir()
        self.assertNotIn("openQuestions:", (PACKAGE / "assets/templates/requirements.yaml").read_text())
        for name in DOCUMENT_NAMES:
            (self.documents / name).write_bytes((PACKAGE / "assets/templates" / name).read_bytes())
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.assertEqual([], self.read_document("requirements.yaml")["openQuestions"])
        first = self.snapshot()
        self.invoke("finalise", "--documents", self.documents, expected_exit=0)
        self.assertEqual(first, self.snapshot())

    def test_absent_projection_is_regenerated_with_or_without_prior_digest(self):
        self.initialise()
        original = self.read_document("requirements.yaml")["openQuestions"]
        for clear_digest in (False, True):
            with self.subTest(clear_digest=clear_digest):
                requirements = self.read_document("requirements.yaml")
                del requirements["openQuestions"]
                self.write_document("requirements.yaml", requirements)
                if clear_digest:
                    trace = self.read_document("traceability.yaml")
                    trace["instance"]["projections"]["openQuestionsSha256"] = None
                    self.write_document("traceability.yaml", trace)
                _, stale = self.invoke("validate", "--documents", self.documents, "--stage", "draft", expected_exit=2)
                self.assertTrue(any(e["code"] == "STALE_PROJECTION" and e["pointer"] == "/openQuestions"
                                    for e in stale["errors"]), stale)
                self.invoke("finalise", "--documents", self.documents, expected_exit=0)
                self.assertEqual(original, self.read_document("requirements.yaml")["openQuestions"])
                before = self.snapshot()
                self.invoke("finalise", "--documents", self.documents, expected_exit=0)
                self.assertEqual(before, self.snapshot())

    def test_first_projection_does_not_discard_independently_authored_questions(self):
        self.initialise()
        trace = self.read_document("traceability.yaml")
        trace["instance"]["projections"]["openQuestionsSha256"] = None
        self.write_document("traceability.yaml", trace)
        requirements = self.read_document("requirements.yaml")
        requirements["openQuestions"] = ["This question must not disappear."]
        self.write_document("requirements.yaml", requirements)
        before = self.snapshot()
        for args in (("finalise",), ("validate", "--stage", "draft")):
            _, result = self.invoke(*args, "--documents", self.documents, expected_exit=2)
            self.assertTrue(any(e["code"] == "PROJECTION_EDIT" and e["document"] == "requirements"
                                and e["pointer"] == "/openQuestions" for e in result["errors"]), result)
            self.assertEqual(before, self.snapshot())

    def test_first_projection_accepts_empty_or_already_matching_output(self):
        self.initialise()
        original = self.read_document("requirements.yaml")["openQuestions"]
        for value in ([], original):
            requirements = self.read_document("requirements.yaml")
            requirements["openQuestions"] = value
            self.write_document("requirements.yaml", requirements)
            trace = self.read_document("traceability.yaml")
            trace["instance"]["projections"]["openQuestionsSha256"] = None
            self.write_document("traceability.yaml", trace)
            self.invoke("finalise", "--documents", self.documents, expected_exit=0)
            self.assertEqual(original, self.read_document("requirements.yaml")["openQuestions"])

    def test_missing_digest_reports_its_document_and_pointer(self):
        self.initialise()
        trace = self.read_document("traceability.yaml")
        del trace["instance"]["projections"]["openQuestionsSha256"]
        self.write_document("traceability.yaml", trace)
        before = self.snapshot()
        _, result = self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        self.assertEqual("POINTER", result["errors"][0]["code"])
        self.assertEqual("traceability", result["errors"][0]["document"])
        self.assertEqual("/instance/projections/openQuestionsSha256", result["errors"][0]["pointer"])
        self.assertEqual(before, self.snapshot())

    def test_missing_generated_parent_is_an_actionable_input_error(self):
        self.initialise()
        plan = self.read_document("test-plan.yaml")
        del plan["contract"]
        self.write_document("test-plan.yaml", plan)
        before = self.snapshot()
        _, result = self.invoke("finalise", "--documents", self.documents, expected_exit=2)
        self.assertEqual("POINTER", result["errors"][0]["code"])
        self.assertEqual("plan", result["errors"][0]["document"])
        self.assertEqual("/contract", result["errors"][0]["pointer"])
        self.assertEqual(before, self.snapshot())
