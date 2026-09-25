"""Distribution qualification against the public package and intake commands."""

from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

from test_intake_cli import CliTestCase, DOCUMENT_NAMES, PACKAGE


class PackageTests(CliTestCase):
    def build(self, package, output, refresh=False):
        arguments = [sys.executable, "-B", "-I", "-S", str(package / "scripts" / "package.py"), "--output", str(output)]
        if refresh:
            arguments.append("--refresh-manifest")
        environment = os.environ.copy()
        environment.pop("PYTHONPATH", None)
        environment.pop("PYTHONHOME", None)
        return subprocess.run(arguments, cwd=self.workspace, env=environment, capture_output=True, text=True, timeout=30, check=False)

    def copy_package(self):
        destination = self.workspace / "maintainer-copy" / "pockethive-intake"
        shutil.copytree(PACKAGE, destination, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        return destination

    def test_zip_relocates_and_runs_without_repo_or_site_packages(self):
        archive = self.workspace / "intake.zip"
        result = self.build(PACKAGE, archive)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        extracted = self.workspace / "relocated skill with spaces"
        with zipfile.ZipFile(archive) as bundle:
            for name in bundle.namelist():
                path = Path(name)
                self.assertFalse(path.is_absolute(), name)
                self.assertNotIn("..", path.parts, name)
                self.assertEqual("pockethive-intake", path.parts[0])
            bundle.extractall(extracted)
        relocated = extracted / "pockethive-intake"
        self.invoke("verify-package", package=relocated, expected_exit=0, isolated_repository=True)
        self.invoke("initialise", "--output", self.documents, "--mode", "new-requirements", package=relocated, expected_exit=0, isolated_repository=True)
        self.invoke("validate", "--documents", self.documents, "--stage", "draft", package=relocated, expected_exit=0, isolated_repository=True)
        self.invoke("validate", "--documents", self.documents, "--stage", "handoff", package=relocated, expected_exit=3, isolated_repository=True)
        for name in DOCUMENT_NAMES:
            self.assertTrue((self.documents / name).is_file())
        bundle_documents = self.workspace / "bundle documents"
        bundle_source = relocated / "fixtures" / "fragmented-bundle"
        self.invoke("initialise", "--output", bundle_documents, "--mode", "from-bundle", "--source", bundle_source,
                    package=relocated, expected_exit=0, isolated_repository=True)
        self.assertTrue((bundle_documents / "source-inspection.json").is_file())
        self.invoke("validate", "--documents", bundle_documents, "--stage", "draft",
                    package=relocated, expected_exit=0, isolated_repository=True)
        self.invoke("validate", "--documents", bundle_documents, "--stage", "handoff",
                    package=relocated, expected_exit=3, isolated_repository=True)

    def test_two_builds_have_identical_archive_bytes(self):
        first, second = self.workspace / "first.zip", self.workspace / "second.zip"
        for output in (first, second):
            result = self.build(PACKAGE, output)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_changed_mandatory_template_fails_integrity_check(self):
        package = self.copy_package()
        templates = list((package / "assets" / "templates").glob("*.yaml"))
        self.assertTrue(templates, "Working templates must be shipped in assets/templates")
        with templates[0].open("a", encoding="utf-8") as stream:
            stream.write("\n# unreviewed change\n")
        self.invoke("verify-package", package=package, expected_exit=2)
        archive = self.workspace / "tampered.zip"
        result = self.build(package, archive)
        self.assertNotEqual(0, result.returncode)

    def test_missing_mandatory_template_is_not_reconstructed(self):
        package = self.copy_package()
        templates = list((package / "assets" / "templates").glob("*.yaml"))
        self.assertTrue(templates)
        missing = templates[0]
        missing.rename(self.workspace / "saved-template.yaml")
        self.invoke("verify-package", package=package, expected_exit=2)
        self.assertFalse(missing.exists())

    def test_manifest_traversal_cannot_include_external_file(self):
        package = self.copy_package()
        manifest_path = package / "contract" / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        # Corrupt the public manifest contract, not the packager implementation.
        files = manifest["files"]
        external = self.workspace / "do-not-package.txt"
        external.write_text("outside-package-canary", encoding="utf-8")
        original = next(iter(files))
        files["../../do-not-package.txt"] = files.pop(original)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        archive = self.workspace / "unsafe.zip"
        result = self.build(package, archive)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.invoke("verify-package", package=package, expected_exit=2)

    def test_refresh_cannot_follow_symlink_outside_package(self):
        package = self.copy_package()
        external = self.workspace / "outside-package.txt"
        external.write_text("outside-package-canary", encoding="utf-8")
        (package / "assets" / "external-link.txt").symlink_to(external)
        archive = self.workspace / "symlink.zip"
        result = self.build(package, archive, refresh=True)
        if result.returncode == 0:
            with zipfile.ZipFile(archive) as bundle:
                self.assertFalse(any("external-link.txt" in name for name in bundle.namelist()))
                self.assertFalse(any(b"outside-package-canary" in bundle.read(name) for name in bundle.namelist() if not name.endswith("/")))
        else:
            self.assertFalse(archive.exists())
