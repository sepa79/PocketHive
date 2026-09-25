"""Responsibility: resolve and verify the installed package and explicit workspace roots.
Must not: search home/Git/cwd for defaults or repair package files. Local contract: manifest.json.
Contract: RESP-INTAKE-PACKAGE-CONTEXT — docs/architecture/intake-runtime.md#resp-intake-package-context.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
from urllib.parse import urlsplit

from .errors import IntakeError


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_hash(value: object) -> str:
    return sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode())


class PackageContext:
    def __init__(self) -> None:
        self.root = Path(__file__).resolve().parents[2]
        self.manifest_path = self.root / "contract/manifest.json"
        try:
            self.manifest = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise IntakeError("PACKAGE_MANIFEST", "Package manifest is missing or invalid.") from None
        minimum = self.manifest["runtime"]["pythonMinimum"]
        if sys.version_info[:2] < tuple(minimum):
            raise IntakeError("RUNTIME", "The declared minimum Python runtime is required.")

    def asset(self, value: str) -> Path:
        relative = PurePosixPath(value)
        if not value or "\\" in value or relative.is_absolute() or ".." in relative.parts:
            raise IntakeError("PACKAGE_PATH", "Package paths must stay inside the skill.")
        path = self.root.joinpath(*relative.parts)
        self.reject_links(path, self.root)
        if not path.resolve().is_relative_to(self.root):
            raise IntakeError("PACKAGE_PATH", "Package path escapes the skill.")
        return path

    @staticmethod
    def reject_links(path: Path, boundary: Path | None = None) -> None:
        for part in (path, *path.parents):
            if part.is_symlink():
                raise IntakeError("SYMLINK", "Symbolic links are not accepted for package or document paths.")
            if boundary is not None and part == boundary:
                break

    def workspace(self, value: str, *, write: bool = False) -> Path:
        path = Path(value).absolute()
        self.reject_links(path)
        path = path.resolve()
        if write and (path.is_relative_to(self.root) or self.root.is_relative_to(path)):
            raise IntakeError("OUTPUT_ROOT", "Output must be separate from the installed skill.")
        return path

    def document_path(self, root: Path, role: str) -> Path:
        path = root / self.manifest["templates"][role]["output"]
        self.reject_links(path, root)
        return path

    def review_path(self, root: Path) -> Path:
        name = self.manifest["reviewOutput"]
        if (not isinstance(name, str) or not name or len(PurePosixPath(name).parts) != 1
                or name in (".", "..", self.manifest["documentWriteLock"])
                or "\\" in name or PurePosixPath(name).is_absolute()
                or name in {row["output"] for row in self.manifest["templates"].values()}):
            raise IntakeError("REPORT_PATH", "The generated review must use a distinct relative filename.")
        path = root / name
        self.reject_links(path, root)
        return path

    def bundle_intake_path(self, source: Path) -> Path:
        name = self.manifest["bundleIntakeDirectory"]
        if not isinstance(name, str) or not name or len(PurePosixPath(name).parts) != 1 or name in (".", "..") or "\\" in name or PurePosixPath(name).is_absolute():
            raise IntakeError("BUNDLE_INTAKE_DIRECTORY", "The bundle intake directory must use one declared relative directory name.")
        path = source / name
        self.reject_links(path, source)
        if path.exists() and not path.is_dir():
            raise IntakeError("BUNDLE_INTAKE_DIRECTORY", "The reserved bundle intake path must be a directory.",
                              detail={"intakePath": str(path)})
        return path

    def check_bundle_documents(self, source: Path, root: Path) -> None:
        intake = self.bundle_intake_path(source)
        if root.is_relative_to(source) and root != intake:
            raise IntakeError("OUTPUT_IN_SOURCE", "Store bundled forms in the reserved intake directory or select an external document directory.",
                              detail={"documentsRoot": str(root), "sourceRoot": str(source), "intakePath": str(intake)})

    def bundle_reference(self, source: Path, root: Path) -> str:
        self.check_bundle_documents(source, root)
        return ".." if root == self.bundle_intake_path(source) else str(source)

    def local_name(self, setting: str) -> str:
        name = self.manifest[setting]
        if (not isinstance(name, str) or not name or "\\" in name
                or PurePosixPath(name).is_absolute() or len(PurePosixPath(name).parts) != 1
                or name in (".", "..", self.manifest["documentWriteLock"])
                or name in {row["output"] for row in self.manifest["templates"].values()}):
            raise IntakeError("PACKAGE_PATH", "The configured artifact name must be a distinct local path component.")
        return name

    def evidence_path(self, root: Path, ref: str) -> Path:
        path = Path(ref)
        if urlsplit(ref).scheme and not path.is_absolute():
            raise IntakeError("EXTERNAL_SOURCE_UNVERIFIED", "Supply a local immutable evidence copy; remote sources are not fetched.")
        return self.workspace(str(path if path.is_absolute() else root / path))

    def lock_path(self, root: Path) -> Path:
        name = self.manifest["documentWriteLock"]
        if not isinstance(name, str) or len(PurePosixPath(name).parts) != 1 or name in (".", "..") or "\\" in name:
            raise IntakeError("DOCUMENT_PATH", "The write lock must use one declared relative directory name.")
        path = root / name
        self.reject_links(path, root)
        return path

    def read(self, path: Path, *, limit: int | None = None) -> bytes:
        self.reject_links(path)
        try:
            if not path.is_file():
                raise IntakeError("MISSING_FILE", "Required regular file is unavailable.", path.name)
            maximum = limit if limit is not None else self.manifest["limits"]["fileBytes"]
            if path.stat().st_size > maximum:
                raise IntakeError("FILE_LIMIT", "Input exceeds the declared file-size limit.", path.name)
            with path.open("rb") as handle:
                data = handle.read(maximum + 1)
            if len(data) > maximum:
                raise IntakeError("FILE_LIMIT", "Input exceeds the declared file-size limit.", path.name)
            return data
        except OSError:
            raise IntakeError("FILE_READ", "Input file cannot be read.", path.name) from None

    def verify(self) -> dict:
        records = self.manifest.get("files")
        if not isinstance(records, dict) or not records:
            raise IntakeError("PACKAGE_UNSEALED", "Package file manifest has not been finalised.")
        from .release_files import release_files
        if set(records) != set(release_files(self)):
            raise IntakeError("PACKAGE_CONTENTS", "Package files do not match the declared release manifest.")
        for entry in self.manifest["templates"].values():
            for key in ("path", "schema"):
                if entry[key] not in records:
                    raise IntakeError("PACKAGE_MISSING", "A mandatory template or schema is not listed.")
            output = entry["output"]
            if len(PurePosixPath(output).parts) != 1 or output in (".", "..") or "\\" in output:
                raise IntakeError("DOCUMENT_PATH", "Document filenames must be single relative filenames.")
        for name, expected in records.items():
            path = self.asset(name)
            if sha256(self.read(path)) != expected:
                raise IntakeError("PACKAGE_INTEGRITY", "A packaged file differs from its manifest.", name)
        sums = self.asset(self.manifest["sourceChecksums"])
        for line in self.read(sums).decode("utf-8").splitlines():
            digest, name = line.split("  ", 1)
            path = self.asset(f"{sums.parent.relative_to(self.root).as_posix()}/{name}")
            if sha256(self.read(path)) != digest:
                raise IntakeError("SOURCE_INTEGRITY", "An original template snapshot has changed.", name)
        return {"package": self.manifest["name"], "version": self.manifest["version"], "verifiedFiles": len(records)}

    def load_libraries(self) -> None:
        vendor = self.asset("vendor")
        sys.path.insert(0, str(vendor))
        # Explicit packages only; an installed global copy is never a substitute.
        import importlib
        for name in ("ruamel.yaml", "fastjsonschema"):
            try:
                module = importlib.import_module(name)
            except ImportError:
                raise IntakeError("DEPENDENCY", "A required bundled library is unavailable.") from None
            if not Path(module.__file__).resolve().is_relative_to(vendor):
                raise IntakeError("DEPENDENCY_OWNER", "A library was resolved outside the package.")
