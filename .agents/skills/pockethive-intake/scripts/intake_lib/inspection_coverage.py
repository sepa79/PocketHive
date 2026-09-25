"""Responsibility: project extraction coverage from the canonical inspector's traversal.
Must not: read sources, parse content or infer completed QA review. Local contract: intake-contract.md.
Contract: RESP-INTAKE-INSPECTION — docs/architecture/intake-runtime.md#resp-intake-inspection.
"""
from __future__ import annotations


STRUCTURED = "structured"
NOT_EXTRACTED = "not-extracted"
UNREADABLE = "unreadable"


def file_coverage(path: str, status: str, observed: int | None,
                  unextracted: int | None, sensitive: int | None) -> dict:
    return {
        "path": path,
        "status": status,
        "observationCount": observed,
        "unextractedScalarCount": unextracted,
        "sensitiveScalarCount": sensitive,
        "reviewRequired": status != STRUCTURED or observed == 0 or unextracted > 0 or sensitive > 0,
    }


def coverage_view(files: list[dict]) -> dict:
    return {
        "files": files,
        "summary": {
            "fileCount": len(files),
            "structuredFileCount": sum(row["status"] == STRUCTURED for row in files),
            "notExtractedFileCount": sum(row["status"] == NOT_EXTRACTED for row in files),
            "unreadableFileCount": sum(row["status"] == UNREADABLE for row in files),
            "observationCount": sum(row["observationCount"] for row in files if row["status"] == STRUCTURED),
            "reviewRequiredFileCount": sum(row["reviewRequired"] for row in files),
        },
    }
