"""Public CLI checks for runtime vocabulary observations and evidence redaction."""
from __future__ import annotations

import hashlib

from test_intake_cli import CliTestCase


class RuntimeObservationTests(CliTestCase):
    def test_template_protocol_observations_preserve_values_and_exclude_sensitive_paths(self):
        source = self.workspace / "bundle"
        source.mkdir()
        descriptor = source / "scenario.yaml"
        descriptor.write_text(
            "requests:\n"
            "  - protocol: HTTP\n"
            "  - protocol: tcp\n"
            "  - protocol: ISO8583\n"
            "  - protocol: iso8583\n"
            "  - protocol: ' Iso8583 '\n"
            "  - protocol: HTTPS\n"
            "  - protocol: TCPS\n"
            "  - protocol: unsupported-protocol-canary\n"
            "headers:\n  protocol: ISO8583\n"
            "credentials:\n  protocol: iso8583\n"
            "body:\n  protocol: HTTP\n",
            encoding="utf-8",
        )
        original = descriptor.read_bytes()
        process, output = self.invoke("inspect-bundle", "--source", source,
                                      expected_exit=0, offline=True)
        digest = hashlib.sha256(original).hexdigest()
        self.assertEqual([
            {"artifactRef": "scenario.yaml", "pointer": f"/requests/{index}/protocol",
             "value": value, "sha256": digest, "kind": "bundle-observation"}
            for index, value in enumerate(("HTTP", "tcp", "ISO8583", "iso8583", " Iso8583 "))
        ], output["observations"])
        coverage = output["coverage"]["files"][0]
        self.assertEqual(5, coverage["observationCount"])
        self.assertEqual(3, coverage["unextractedScalarCount"])
        self.assertEqual(3, coverage["sensitiveScalarCount"])
        for omitted in ("unsupported-protocol-canary", "/headers/protocol",
                        "/credentials/protocol", "/body/protocol"):
            self.assertNotIn(omitted, process.stdout + process.stderr)
        self.assertEqual(original, descriptor.read_bytes())
        self.assertFalse(any(output["claims"].values()))
