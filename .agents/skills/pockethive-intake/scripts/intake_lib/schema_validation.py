"""Responsibility: resolve local canonical schemas and validate document structure.
Must not: download schemas, insert defaults or decide domain readiness. Contract: schemas/*.schema.json.
"""
from __future__ import annotations

import json
from pathlib import PurePosixPath
from urllib.parse import urldefrag

from .errors import IntakeError
from .package_context import PackageContext
from .pointers import resolve


class SchemaValidation:
    def __init__(self, package: PackageContext) -> None:
        self.package = package
        self.compiled = {}

    def expanded(self, name: str, chain: tuple = ()) -> dict:
        data = json.loads(self.package.read(self.package.asset(name)))

        def visit(value: object, base: str, stack: tuple) -> object:
            if isinstance(value, list):
                return [visit(item, base, stack) for item in value]
            if not isinstance(value, dict):
                return value
            if "$ref" in value:
                ref = value["$ref"]
                file_part, pointer = urldefrag(ref)
                if ":" in file_part or ".." in PurePosixPath(file_part).parts:
                    raise IntakeError("SCHEMA_REF", "Schemas may reference only packaged local definitions.")
                target = str(PurePosixPath(base).parent / file_part) if file_part else base
                key = (target, pointer)
                if key in stack:
                    raise IntakeError("SCHEMA_CYCLE", "Recursive schema references are not supported by this contract.")
                referred = json.loads(self.package.read(self.package.asset(target)))
                return visit(resolve(referred, pointer), target, (*stack, key))
            return {key: visit(item, base, stack) for key, item in value.items() if key not in ("$id", "$schema")}

        return visit(data, name, chain)

    def validate(self, role: str, value: dict) -> list[dict]:
        import fastjsonschema
        if role not in self.compiled:
            schema = self.expanded(self.package.manifest["templates"][role]["schema"])
            self.compiled[role] = fastjsonschema.compile(schema, use_default=False)
        try:
            self.compiled[role](value)
            return []
        except fastjsonschema.JsonSchemaException as exc:
            path = "/" + "/".join(str(part).replace("~", "~0").replace("/", "~1") for part in exc.path[1:])
            return [IntakeError("SCHEMA", "Value does not satisfy the packaged document schema.", role, path).issue]
