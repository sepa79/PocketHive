"""Responsibility: safely parse and round-trip the supported YAML data model.
Must not: infer defaults, execute tags or evaluate embedded expressions. Contract: intake-contract.md.
"""
from __future__ import annotations

from collections.abc import Mapping
from datetime import date, datetime
from io import StringIO
import math

from .errors import IntakeError


class YamlCodec:
    def __init__(self, limits: dict) -> None:
        from ruamel.yaml import YAML
        self.yaml = YAML(typ="rt", pure=True)
        self.yaml.allow_duplicate_keys = False
        self.yaml.preserve_quotes = True
        self.yaml.width = 100
        self.yaml.indent(mapping=2, sequence=4, offset=2)
        self.limits = limits

    def parse(self, data: bytes, document: str = "") -> object:
        from ruamel.yaml.tokens import TagToken
        try:
            text = data.decode("utf-8")
            for token in self.yaml.scan(text):
                if isinstance(token, TagToken):
                    raise IntakeError("YAML_TAG", "Explicit YAML tags are not accepted.", document)
            value = self.yaml.load(text)
            self.plain(value)
            return value
        except IntakeError as error:
            if not error.issue["document"]:
                raise IntakeError(error.issue["code"], error.issue["message"], document,
                                  error.issue["pointer"], detail=error.issue.get("detail")) from None
            raise
        except Exception as exc:
            from ruamel.yaml.constructor import DuplicateKeyError
            code = "YAML_DUPLICATE_KEY" if isinstance(exc, DuplicateKeyError) else "YAML_PARSE"
            raise IntakeError(code, "YAML is invalid; inspect the named source locally.", document) from None

    def plain(self, value: object) -> object:
        count = 0
        active: set[int] = set()

        def walk(item: object, depth: int) -> object:
            nonlocal count
            count += 1
            if count > self.limits["yamlNodes"] or depth > self.limits["yamlDepth"]:
                raise IntakeError("YAML_LIMIT", "YAML exceeds the declared structure limits.")
            if isinstance(item, (Mapping, list)):
                if id(item) in active:
                    raise IntakeError("YAML_CYCLE", "Recursive YAML aliases are not supported.")
                active.add(id(item))
                try:
                    if isinstance(item, Mapping):
                        if any(not isinstance(key, str) for key in item):
                            raise IntakeError("YAML_KEY", "YAML mapping keys must be strings.")
                        if getattr(item, "merge", None):
                            raise IntakeError("YAML_MERGE", "YAML merge keys must be made explicit before intake.")
                        return {str(key): walk(val, depth + 1) for key, val in item.items()}
                    return [walk(val, depth + 1) for val in item]
                finally:
                    active.remove(id(item))
            if isinstance(item, (date, datetime)):
                return item.isoformat()
            if item is None or isinstance(item, (str, bool, int)):
                return item
            if isinstance(item, float) and math.isfinite(item):
                return item
            raise IntakeError("YAML_TYPE", "YAML contains a value outside the supported data model.")

        return walk(value, 0)

    def dump(self, value: object) -> bytes:
        output = StringIO()
        self.yaml.dump(value, output)
        data = output.getvalue().encode("utf-8")
        if self.plain(self.parse(data)) != self.plain(value):
            raise IntakeError("YAML_READBACK", "YAML serialization changed document values.")
        return data
