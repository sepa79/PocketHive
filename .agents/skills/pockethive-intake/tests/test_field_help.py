"""Public CLI qualification of exact-pointer help without implicit correction."""
from __future__ import annotations

from test_intake_cli import CliTestCase, DOCUMENT_NAMES


class FieldHelpTests(CliTestCase):
    def setUp(self):
        super().setUp()
        self.initialise()

    def missing(self, role, pointer):
        raw, output = self.invoke("show-field", "--documents", self.documents,
                                  "--document", role, "--pointer", pointer, expected_exit=2)
        issue = output["errors"][0]
        self.assertEqual("POINTER", issue["code"])
        self.assertEqual(role, issue["document"])
        self.assertEqual(pointer, issue["pointer"])
        self.assertNotIn("field", output)
        return raw, issue

    def test_typo_returns_nearest_ancestor_and_schema_children_without_writing(self):
        before = {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES}
        _, issue = self.missing("requirements", "/project/ownr")
        self.assertEqual({"resolvedAncestor": "/project",
                          "schemaChildKeys": ["name", "objective", "outOfScope", "owner"],
                          "schemaChildKeysTruncated": False}, issue["detail"])
        self.assertEqual(before, {name: (self.documents / name).read_bytes() for name in DOCUMENT_NAMES})

    def test_hints_are_bounded_sorted_and_explicit_about_truncation(self):
        _, issue = self.missing("plan", "/unknown")
        detail = issue["detail"]
        self.assertEqual("", detail["resolvedAncestor"])
        self.assertEqual(20, len(detail["schemaChildKeys"]))
        self.assertEqual(sorted(detail["schemaChildKeys"]), detail["schemaChildKeys"])
        self.assertTrue(detail["schemaChildKeysTruncated"])

    def test_invalid_array_index_has_length_without_listing_items(self):
        for pointer in ("/templates/999/method", "/templates/-1", "/templates/01"):
            with self.subTest(pointer=pointer):
                _, issue = self.missing("requirements", pointer)
                self.assertEqual({"resolvedAncestor": "/templates", "schemaChildKeys": [],
                                  "schemaChildKeysTruncated": False, "arrayLength": 1}, issue["detail"])

    def test_dynamic_keys_and_payload_values_never_become_suggestions(self):
        document = self.read_document("requirements.yaml")
        private_key, private_value = "PRIVATE-DYNAMIC-KEY", "PRIVATE-PAYLOAD-VALUE"
        document["templates"][0]["requestSample"] = {"a/b": {private_key: private_value}}
        self.write_document("requirements.yaml", document)
        raw, issue = self.missing("requirements", "/templates/0/requestSample/a~1b/absent")
        self.assertEqual({"resolvedAncestor": "/templates/0/requestSample/a~1b",
                          "schemaChildKeys": [], "schemaChildKeysTruncated": False}, issue["detail"])
        self.assertNotIn(private_key, raw.stdout + raw.stderr)
        self.assertNotIn(private_value, raw.stdout + raw.stderr)

    def test_scalar_ancestor_does_not_invent_a_child(self):
        _, issue = self.missing("requirements", "/project/name/unknown")
        self.assertEqual({"resolvedAncestor": "/project/name", "schemaChildKeys": [],
                          "schemaChildKeysTruncated": False}, issue["detail"])

    def test_malformed_pointer_has_no_guessed_ancestor(self):
        for pointer in ("project/owner", "/project/~2owner"):
            with self.subTest(pointer=pointer):
                _, issue = self.missing("requirements", pointer)
                self.assertNotIn("detail", issue)
