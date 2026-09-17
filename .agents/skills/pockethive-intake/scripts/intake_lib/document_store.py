"""Responsibility: persist document bytes with revision checks and cooperative writer exclusion.
Must not: construct business projections or grant whole-set atomicity. Contract: intake-contract.md.
"""
from __future__ import annotations

import os
from contextlib import contextmanager
from pathlib import Path
import tempfile

from .errors import IntakeError
from .package_context import PackageContext, canonical_hash, sha256
from .yaml_codec import YamlCodec


class DocumentStore:
    def __init__(self, package: PackageContext, codec: YamlCodec) -> None:
        self.package = package
        self.codec = codec

    def load(self, root: Path) -> dict:
        docs = {}
        for role in self.package.manifest["templates"]:
            path = self.package.document_path(root, role)
            docs[role] = self.codec.parse(self.package.read(path), role)
            if not isinstance(docs[role], dict):
                raise IntakeError("DOCUMENT_OBJECT", "Each intake document must be a YAML object.", role)
        return docs

    def revision(self, root: Path) -> str:
        return self.revision_bytes({role: self.package.read(self.package.document_path(root, role))
                                    for role in self.package.manifest["templates"]})

    @staticmethod
    def revision_bytes(encoded: dict[str, bytes]) -> str:
        return canonical_hash({role: sha256(data) for role, data in encoded.items()})

    def assert_revision(self, root: Path, expected: str) -> None:
        if self.revision(root) != expected:
            raise IntakeError("STALE_DOCUMENTS", "The document set changed; read the current revision before applying edits.")

    def assert_unlocked(self, root: Path) -> None:
        if self.package.lock_path(root).exists():
            raise self._busy(root)

    def _busy(self, root: Path) -> IntakeError:
        return IntakeError("DOCUMENTS_BUSY", "Another writer or an interrupted operation owns the document lock.",
                           detail={"lockPath": str(self.package.lock_path(root)),
                                   "nextAction": "Wait for an active writer to finish. If interrupted, establish that no writer remains, inspect the document set for partial writes, then explicitly remove only the empty abandoned lock directory. Never infer staleness from its age."})

    @contextmanager
    def mutation(self, root: Path, *, create: bool = False):
        if create:
            try:
                root.mkdir(parents=True, exist_ok=True)
            except OSError:
                raise IntakeError("OUTPUT_CREATE", "Output directory cannot be created.") from None
        lock = self.package.lock_path(root)
        try:
            lock.mkdir()
        except FileExistsError:
            raise self._busy(root) from None
        except OSError:
            raise IntakeError("WRITE_LOCK", "Cannot acquire the document write lock in the selected directory.",
                              detail={"lockPath": str(lock), "nextAction": "Check the explicit document directory and its write permissions; do not bypass locking."}) from None
        try:
            yield
        finally:
            try:
                lock.rmdir()
            except OSError:
                raise IntakeError("WRITE_LOCK_RELEASE", "The document write lock could not be released; inspect it explicitly.",
                                  detail={"lockPath": str(lock), "nextAction": "Establish that no writer remains and inspect the saved documents and lock before removing only an empty abandoned lock directory."}) from None

    @staticmethod
    def write_bytes(path: Path, data: bytes) -> None:
        temporary: str | None = None
        try:
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".intake-", delete=False) as handle:
                temporary = handle.name
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, path)
            temporary = None
            if path.read_bytes() != data:
                raise IntakeError("WRITE_READBACK", "Written artifact differs from the intended bytes.", path.name)
        except OSError:
            raise IntakeError("WRITE_FAILED", "Document persistence failed; verify the set before continuing.", path.name) from None
        finally:
            if temporary is not None:
                Path(temporary).unlink(missing_ok=True)
