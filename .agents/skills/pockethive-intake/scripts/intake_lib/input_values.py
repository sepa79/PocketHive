"""Responsibility: identify explicitly missing or placeholder intake scalar values.
Must not: infer applicability or replace missing values. Contract: intake-contract.md.
"""
from __future__ import annotations

import re


def absent(value: object) -> bool:
    return value is None or value == "" or (isinstance(value, str) and re.search(r"<[^>]+>", value) is not None)
