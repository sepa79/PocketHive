"""Responsibility: seal explicit release files and write a verified deterministic ZIP.
Must not: change source snapshots or install/update tools. Local contract: manifest.json and intake-contract.md.
Contract: RESP-INTAKE-PACKAGING — docs/architecture/intake-runtime.md#resp-intake-packaging.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import tempfile
import zipfile

from .errors import IntakeError
from .package_context import PackageContext, sha256
from .release_files import release_files


def build(package, output: Path, refresh: bool):
    if output.exists():
        raise IntakeError("OUTPUT_EXISTS", "Choose a new archive path; existing files are not overwritten.")
    if refresh:
        package.manifest["files"] = {name: sha256(package.read(path)) for name, path in release_files(package).items()}
        encoded = (json.dumps(package.manifest, indent=2, ensure_ascii=False) + "\n").encode()
        # Maintainer-only mutation; the input source checksum file is never rewritten.
        package.manifest_path.write_bytes(encoded)
        if package.manifest_path.read_bytes() != encoded:
            raise IntakeError("PACKAGE_WRITE", "Manifest readback did not match the requested bytes.")
    integrity = package.verify()
    files = release_files(package)
    files[package.manifest_path.relative_to(package.root).as_posix()] = package.manifest_path
    output.parent.mkdir(parents=True, exist_ok=True)
    pending = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".intake-zip-", delete=False) as handle:
            pending = Path(handle.name)
        with zipfile.ZipFile(pending, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, path in sorted(files.items()):
                entry = zipfile.ZipInfo(f"{package.manifest['name']}/{name}", date_time=(2020, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = 0o100644 << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, package.read(path), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        with zipfile.ZipFile(pending) as archive:
            if archive.testzip() is not None:
                raise IntakeError("ZIP_READBACK", "Archive readback failed.")
            for name, path in files.items():
                if archive.read(f"{package.manifest['name']}/{name}") != package.read(path):
                    raise IntakeError("ZIP_READBACK", "An archived file differs from the intended bytes.")
        os.replace(pending, output)
        pending = None
        return {**integrity, "archive": str(output), "sha256": sha256(output.read_bytes()), "files": len(files)}
    finally:
        if pending is not None:
            pending.unlink(missing_ok=True)


def main():
    try:
        parser = argparse.ArgumentParser(description="Build an offline PocketHive intake skill ZIP.")
        parser.add_argument("--output", required=True)
        parser.add_argument("--refresh-manifest", action="store_true")
        args = parser.parse_args()
        package = PackageContext()
        result = build(package, package.workspace(args.output, write=True), args.refresh_manifest)
        result.update({"status": "ok", "errors": []})
        code = 0
    except IntakeError as error:
        result, code = {"status": "error", "errors": [error.issue]}, 2
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
        result, code = {"status": "error", "errors": [IntakeError("PACKAGE_FAILED", "Packaging failed; no successful release was established.").issue]}, 2
    print(json.dumps(result, indent=2))
    return code
