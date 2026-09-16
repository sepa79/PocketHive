"""Responsibility: read and verify atomic per-file document writes.
Must not: calculate hashes/projections or grant whole-set atomicity. Contract: intake-contract.md.
"""
from __future__ import annotations

import os
from pathlib import Path
import tempfile

from .errors import IntakeError
from .package_context import PackageContext
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
