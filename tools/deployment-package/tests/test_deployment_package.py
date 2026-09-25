"""Check the canonical deployment archive without deploying or accessing the network.

Run: python3 -m unittest discover -s tools/deployment-package/tests -v
Requires Python 3, Git, Bash, tar and Docker Compose. Maven's version query alone
is stubbed; file copying, archive creation/extraction and Compose rendering are real.
Owned temporary evidence is retained and its location printed. The packager's
cleanup is guarded and preserved, so this suite never executes recursive deletion.
"""

import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[3]
TEST_VERSION = "package-regression"
REQUIRED_ASSETS = (
    "clickhouse/init",
    "clickhouse/clickhouse-entrypoint.sh",
    "clickhouse/migrate-tx-outcome-v1-to-v2.sh",
    "tcp-mock-server/mappings",
    "tcp-mock-server/__files",
)
CANARY = b"DEPLOYMENT_TEST_LOCAL_ONLY_DO_NOT_DISTRIBUTE\n"


class DeploymentPackageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = Path(tempfile.mkdtemp(prefix="pockethive-package-test-"))
        print(f"\nDeployment package evidence retained: {cls.evidence}", flush=True)
        cls.source = cls.evidence / "source"
        cls.source.mkdir()
        cls.home = cls.evidence / "home"
        cls.home.mkdir()
        cls.environment = {
            "PATH": os.defpath,
            "HOME": str(cls.home),
            "LANG": "C.UTF-8",
            "COMPOSE_DISABLE_ENV_FILE": "1",
        }
        cls.run_command(["docker", "compose", "version"])
        tracked = cls.run_command(["git", "ls-files", "-z"], cwd=REPOSITORY).stdout
        for name in tracked.split("\0"):
            if not name:
                continue
            destination = cls.source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPOSITORY / name, destination, follow_symlinks=False)
        for name in (".env", "local.env", "secrets/private-key.pem", "src/LocalOnly.java"):
            destination = cls.source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(CANARY)

        cls.guard_bin = cls.evidence / "guard-bin"
        cls.guard_bin.mkdir()
        cls.write_guard("mvn", """import sys
if sys.argv[1:] != ['help:evaluate', '-Dexpression=revision', '-q', '-DforceStdout']:
    raise SystemExit('Unexpected Maven command; network/build execution is forbidden')
print('package-regression')
""")
        cls.write_guard("rm", """import json, os, pathlib, sys
args = sys.argv[1:]
if len(args) != 2 or args[0] != '-rf':
    raise SystemExit('Unexpected cleanup command')
target = pathlib.Path(args[1]).resolve()
owner = pathlib.Path(os.environ['TMPDIR']).resolve()
if target.parent != owner or not target.name.startswith('tmp.') or not (target / 'pockethive').is_dir():
    raise SystemExit('Cleanup outside owned package staging refused')
with (owner / 'preserved-cleanup.jsonl').open('a') as record:
    record.write(json.dumps({'preserved': str(target)}) + '\\n')
""")
        cls.package_source, cls.package_run = cls.assemble("complete")
        cls.archive = cls.package_source / f"pockethive-deployment-{TEST_VERSION}.tar.gz"
        if cls.package_run.returncode != 0 or not cls.archive.is_file():
            raise AssertionError(f"Canonical packaging failed; see {cls.evidence / 'complete/package.log'}")

        cls.extracted = cls.evidence / "extracted"
        cls.extracted.mkdir()
        with tarfile.open(cls.archive) as archive:
            cls.members = archive.getmembers()
            for member in cls.members:
                path = PurePosixPath(member.name)
                if (path.is_absolute() or ".." in path.parts or not path.parts
                        or path.parts[0] != "pockethive" or not (member.isfile() or member.isdir())):
                    raise AssertionError(f"Unsafe deployment archive entry: {member.name}")
        cls.run_command(["tar", "xzf", str(cls.archive), "-C", str(cls.extracted)])
        cls.deployment = cls.extracted / "pockethive"
        cls.original_config = cls.compose(cls.source / "docker-compose.yml", "source")
        cls.relative_config = cls.compose(cls.deployment / "docker-compose.yml", "relative")
        cls.opt_config = cls.compose(cls.deployment / "docker-compose.opt.yml", "opt")

    @classmethod
    def run_command(cls, command, cwd=None, env=None, check=True):
        return subprocess.run(command, cwd=cwd or cls.evidence,
                              env=cls.environment if env is None else env,
                              text=True, capture_output=True, timeout=60, check=check)

    @classmethod
    def write_guard(cls, name, program):
        guard = cls.guard_bin / name
        guard.write_text("#!/usr/bin/env python3\n" + program)
        guard.chmod(0o755)

    @classmethod
    def assemble(cls, name, missing=None, wrong_type=False):
        case = cls.evidence / name
        source = case / "source"

        def omit_missing(directory, names):
            return [name for name in names
                    if (Path(directory) / name).relative_to(cls.source).as_posix() == missing]

        shutil.copytree(cls.source, source, ignore=omit_missing)
        if wrong_type:
            invalid = source / missing
            if (cls.source / missing).is_dir():
                invalid.write_text("A required directory was replaced with a file.\n")
            else:
                invalid.mkdir()
        staging = case / "staging"
        staging.mkdir()
        environment = dict(cls.environment, PATH=f"{cls.guard_bin}:{os.defpath}", TMPDIR=str(staging))
        result = cls.run_command(["bash", str(source / "package-deployment.sh")],
                                 cwd=case, env=environment, check=False)
        (case / "package.log").write_text(result.stdout + result.stderr)
        return source, result

    @classmethod
    def compose(cls, filename, label):
        result = cls.run_command(["docker", "compose", "--env-file", os.devnull,
                                  "--project-name", "deployment-package-regression",
                                  "-f", str(filename), "config", "--format", "json"])
        (cls.evidence / f"compose-{label}.json").write_text(result.stdout)
        return json.loads(result.stdout)

    @staticmethod
    def binds(config):
        return {(service, volume["target"]): Path(volume["source"])
                for service, settings in config["services"].items()
                for volume in settings.get("volumes", []) if volume["type"] == "bind"}

    def test_every_repository_bind_source_is_present_in_the_archive(self):
        original_binds = self.binds(self.original_config)
        packaged_binds = self.binds(self.relative_config)
        checked = set()
        for key, original in original_binds.items():
            if not original.is_relative_to(self.source):
                continue  # Host socket and separately created runtime state are not release assets.
            relative = original.relative_to(self.source)
            with self.subTest(service=key[0], target=key[1]):
                packaged = self.deployment / relative
                self.assertEqual(packaged_binds[key], packaged)
                self.assertTrue(packaged.exists(), f"Missing bind source: {relative}")
                self.assertEqual(packaged.is_dir(), original.is_dir())
                if original.is_file():
                    self.assertEqual(packaged.read_bytes(), original.read_bytes())
                checked.add(relative.as_posix())
        self.assertTrue(set(REQUIRED_ASSETS).issubset(checked))

    def test_clickhouse_and_tcp_assets_keep_all_bytes_and_executable_modes(self):
        for name in REQUIRED_ASSETS:
            original = self.source / name
            expected = self.asset_files(self.source, name)
            self.assertTrue(expected, f"Required asset fixture is empty: {name}")
            packaged_root = self.deployment / name
            self.assertEqual(packaged_root.is_dir(), original.is_dir(), name)
            self.assertEqual(packaged_root.is_file(), original.is_file(), name)
            actual = self.asset_files(self.deployment, name)
            self.assertEqual(actual, expected, name)
            for relative in expected:
                with self.subTest(asset=str(relative)):
                    packaged = self.deployment / relative
                    original_file = self.source / relative
                    self.assertEqual(packaged.read_bytes(), original_file.read_bytes())
                    self.assertEqual(packaged.stat().st_mode & 0o111, original_file.stat().st_mode & 0o111)
        self.assertTrue((self.deployment / "tcp-mock-server/__files/.gitkeep").is_file())
        for name in ("start.sh", "stop.sh"):
            self.assertTrue((self.deployment / name).stat().st_mode & 0o111, name)

    @staticmethod
    def asset_files(root, name):
        asset = root / name
        if asset.is_dir():
            return sorted(path.relative_to(root) for path in asset.rglob("*") if path.is_file())
        return [Path(name)] if asset.is_file() else []

    def test_relative_and_opt_compose_are_portable_and_equivalent(self):
        self.assertEqual((self.deployment / "docker-compose.yml").read_bytes(),
                         (self.source / "docker-compose.yml").read_bytes())
        normalized = json.loads(json.dumps(self.relative_config))
        for settings in normalized["services"].values():
            for volume in settings.get("volumes", []):
                if volume["type"] != "bind":
                    continue
                source = Path(volume["source"])
                if source.is_relative_to(self.deployment):
                    volume["source"] = str(Path("/opt/pockethive") / source.relative_to(self.deployment))
        self.assertEqual(self.opt_config, normalized)
        self.assertNotIn("du:", self.package_run.stderr)

    def test_archive_excludes_local_environment_secrets_and_application_source(self):
        forbidden_roots = {".env", "local.env", "secrets", "src", ".git", ".mvn", "mvnw", "pom.xml"}
        with tarfile.open(self.archive) as archive:
            for member in self.members:
                relative = PurePosixPath(member.name).relative_to("pockethive")
                with self.subTest(member=member.name):
                    self.assertFalse(relative.parts and relative.parts[0] in forbidden_roots)
                    self.assertNotIn("src", relative.parts)
                    self.assertNotIn(relative.suffix, {".java", ".class", ".jar", ".pyc"})
                    if member.isfile():
                        self.assertNotIn(CANARY, archive.extractfile(member).read())

    def test_missing_required_assets_fail_without_creating_an_archive(self):
        self.assert_invalid_assets_rejected(wrong_type=False)

    def test_wrong_type_required_assets_fail_without_creating_an_archive(self):
        self.assert_invalid_assets_rejected(wrong_type=True)

    def assert_invalid_assets_rejected(self, wrong_type):
        for index, missing in enumerate(REQUIRED_ASSETS):
            with self.subTest(missing=missing):
                kind = "wrong-type" if wrong_type else "missing"
                source, result = self.assemble(f"{kind}-{index}", missing=missing, wrong_type=wrong_type)
                self.assertNotEqual(result.returncode, 0, f"Packager accepted {kind} {missing}")
                self.assertIn(missing, result.stderr)
                self.assertFalse(list(source.glob("pockethive-deployment-*.tar.gz")))


if __name__ == "__main__":
    unittest.main()
