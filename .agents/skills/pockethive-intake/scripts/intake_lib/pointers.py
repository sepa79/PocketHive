"""Responsibility: resolve exact JSON Pointers and enumerate scalar facts.
Must not: infer path aliases or domain applicability. Contract: intake-contract.md.
"""
from __future__ import annotations

import re

from .errors import IntakeError


def escape(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def resolve(value: object, pointer: str, document: str = "") -> object:
    if pointer == "":
        return value
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        raise IntakeError("POINTER", "Expected an exact JSON Pointer.", document, str(pointer))
    current = value
    for part in pointer[1:].split("/"):
        if re.search(r"~(?![01])", part):
            raise IntakeError("POINTER", "Invalid JSON Pointer escape.", document, pointer)
        part = part.replace("~1", "/").replace("~0", "~")
        try:
            if isinstance(current, list) and not re.fullmatch(r"0|[1-9][0-9]*", part):
                raise ValueError
            current = current[int(part)] if isinstance(current, list) else current[part]
        except (TypeError, KeyError, IndexError, ValueError):
            raise IntakeError("POINTER", "Required field or JSON Pointer does not resolve.", document, pointer) from None
    return current


def leaves(value: object, pointer: str = ""):
    if isinstance(value, dict):
        for key, item in value.items():
            yield from leaves(item, f"{pointer}/{escape(key)}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from leaves(item, f"{pointer}/{index}")
    else:
        yield pointer, value


def covers(parent: str, child: str) -> bool:
    return parent == "" or parent == child or child.startswith(parent + "/")
