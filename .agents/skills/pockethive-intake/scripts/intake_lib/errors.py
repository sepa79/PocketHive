"""Responsibility: define safe intake boundary errors.
Must not: retain payloads or decide validation outcomes. Contract: intake-contract.md.
"""
from __future__ import annotations


class IntakeError(Exception):
    def __init__(self, code: str, message: str, document: str = "", pointer: str = "") -> None:
        super().__init__(message)
        self.issue = {"code": code, "message": message, "document": document, "pointer": pointer}
