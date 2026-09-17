"""Responsibility: resolve exact JSON Pointers and enumerate scalar facts.
Must not: infer path aliases or domain applicability. Contract: intake-contract.md, field-help.md.
"""
from __future__ import annotations

import re

from .errors import IntakeError


def escape(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def resolve(value: object, pointer: str, document: str = "") -> object:
    current = value
    ancestor = ""
    for part in parts(pointer, document):
        try:
            if isinstance(current, list) and not re.fullmatch(r"0|[1-9][0-9]*", part):
                raise ValueError
            current = current[int(part)] if isinstance(current, list) else current[part]
        except (TypeError, KeyError, IndexError, ValueError):
            raise IntakeError("POINTER", "Required field or JSON Pointer does not resolve.", document, pointer,
                              detail={"resolvedAncestor": ancestor}) from None
        ancestor += "/" + escape(part)
    return current


def parts(pointer: str, document: str = "") -> list[str]:
    if pointer == "":
        return []
    if not isinstance(pointer, str) or not pointer.startswith("/"):
        raise IntakeError("POINTER", "Expected an exact JSON Pointer.", document, str(pointer))
    result = []
    for part in pointer[1:].split("/"):
        if re.search(r"~(?![01])", part):
            raise IntakeError("POINTER", "Invalid JSON Pointer escape.", document, pointer)
        result.append(part.replace("~1", "/").replace("~0", "~"))
    return result


def replace(value: object, pointer: str, replacement: object, document: str = "") -> None:
    tokens = parts(pointer, document)
    if not tokens:
        raise IntakeError("POINTER", "Replace a declared field rather than the whole document.", document, pointer)
    resolve(value, pointer, document)
    parent = resolve(value, pointer.rsplit("/", 1)[0], document)
    key = int(tokens[-1]) if isinstance(parent, list) else tokens[-1]
    parent[key] = replacement


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
