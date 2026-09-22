"""Responsibility: define safe intake boundary errors.
Must not: retain payloads or decide validation outcomes. Local contract: intake-contract.md.
Contract: RESP-INTAKE-CLI — docs/architecture/intake-runtime.md#resp-intake-cli.
"""
from __future__ import annotations


class IntakeError(Exception):
    def __init__(self, code: str, message: str, document: str = "", pointer: str = "", *, detail: dict | None = None) -> None:
        super().__init__(message)
        self.issue = {"code": code, "message": message, "document": document, "pointer": pointer}
        if detail is not None:
            self.issue["detail"] = detail
