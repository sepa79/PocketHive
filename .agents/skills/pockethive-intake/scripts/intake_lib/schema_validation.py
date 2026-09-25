"""Responsibility: resolve local canonical schemas and validate document structure.
Must not: download schemas, insert defaults or decide domain readiness. Local contract: schemas/*.schema.json, field-help.md.
Contract: RESP-INTAKE-SCHEMA — docs/architecture/intake-runtime.md#resp-intake-schema.
"""
from __future__ import annotations

import json
from pathlib import PurePosixPath
from urllib.parse import urldefrag

from .errors import IntakeError
from .package_context import PackageContext
from .pointers import escape, parts, resolve


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

    def field_schema(self, role: str, pointer: str, document: dict) -> dict:
        """Project structural branches; retain enclosing conditions without deciding them."""
        schema = self.expanded(self.package.manifest["templates"][role]["schema"])
        branches, contexts = [], {}

        def visit(node: object, remaining: list[str], value: object, data_path: str, schema_path: str) -> None:
            if not remaining:
                branches.append({"schemaPointer": schema_path, "schema": node})
                return
            if node is False:
                return
            if node is True:
                node = {}
            conditions = {key: node[key] for key in ("allOf", "if", "then", "else", "dependentSchemas") if key in node}
            if conditions:
                contexts[schema_path] = {"documentPointer": data_path, "schemaPointer": schema_path,
                                         "constraints": conditions}
            combinations = [key for key in ("anyOf", "oneOf") if key in node]
            for combination in combinations:
                for index, candidate in enumerate(node[combination]):
                    visit(candidate, remaining, value, data_path, f"{schema_path}/{combination}/{index}")
            if combinations and not any(key in node for key in ("type", "properties", "items", "additionalProperties")):
                return
            token, tail = remaining[0], remaining[1:]
            data_child = f"{data_path}/{escape(token)}"
            declared = node.get("type")
            allowed = [declared] if isinstance(declared, str) else declared
            if isinstance(value, dict) and (allowed is None or "object" in allowed):
                if token in node.get("properties", {}):
                    child, schema_child = node["properties"][token], f"{schema_path}/properties/{escape(token)}"
                else:
                    if "additionalProperties" not in node:
                        branches.append({"schemaPointer": None, "schema": True, "unconstrainedBelow": schema_path})
                        return
                    child, schema_child = node["additionalProperties"], f"{schema_path}/additionalProperties"
                visit(child, tail, value[token], data_child, schema_child)
            elif isinstance(value, list) and (allowed is None or "array" in allowed):
                if "items" not in node:
                    branches.append({"schemaPointer": None, "schema": True, "unconstrainedBelow": schema_path})
                    return
                item = node["items"]
                index = int(token)
                if isinstance(item, list):
                    child = item[index] if index < len(item) else node.get("additionalItems", True)
                    schema_child = f"{schema_path}/items/{index}" if index < len(item) else f"{schema_path}/additionalItems"
                else:
                    child, schema_child = item, f"{schema_path}/items"
                visit(child, tail, value[index], data_child, schema_child)

        resolve(document, pointer, role)
        visit(schema, parts(pointer, role), document, "", "")
        if not branches:
            raise IntakeError("SCHEMA_FIELD", "No canonical schema branch describes this existing field.", role, pointer)
        return {"schemaBranches": branches, "contextConstraints": list(contexts.values())}

    def field_children(self, role: str, pointer: str, document: dict) -> dict:
        """Project bounded schema property names; never list client-defined keys."""
        keys = set()

        def declared(node: object) -> None:
            if not isinstance(node, dict):
                return
            keys.update(node.get("properties", {}))
            for combination in ("anyOf", "oneOf", "allOf"):
                for child in node.get(combination, []):
                    declared(child)
            for condition in ("if", "then", "else"):
                declared(node.get(condition))
            for child in node.get("dependentSchemas", {}).values():
                declared(child)

        value = resolve(document, pointer, role)
        if isinstance(value, dict):
            for branch in self.field_schema(role, pointer, document)["schemaBranches"]:
                declared(branch["schema"])
        maximum = self.package.manifest["limits"]["fieldHintKeys"]
        result = {"schemaChildKeys": sorted(keys)[:maximum], "schemaChildKeysTruncated": len(keys) > maximum}
        if isinstance(value, list):
            result["arrayLength"] = len(value)
        return result

    def validate(self, role: str, value: dict) -> list[dict]:
        import fastjsonschema
        version = self.package.manifest["templates"][role]["version"]
        if "version" in value and value["version"] != version:
            return [IntakeError("DOCUMENT_VERSION", f"Expected enriched {role} document version {version}; review raw human forms with review-input, then enrich explicitly using the current template and contract.",
                                role, "/version", detail={"expectedVersion": version}).issue]
        if role not in self.compiled:
            schema = self.expanded(self.package.manifest["templates"][role]["schema"])
            self.compiled[role] = fastjsonschema.compile(schema, use_default=False)
        try:
            self.compiled[role](value)
            return []
        except fastjsonschema.JsonSchemaException as exc:
            path = "".join("/" + str(part).replace("~", "~0").replace("/", "~1") for part in exc.path[1:])
            detail = {"rule": exc.rule}
            if exc.rule in ("type", "enum", "required"):
                detail["expected"] = exc.rule_definition
            return [IntakeError("SCHEMA", f"Value does not satisfy the packaged schema constraint: {exc.rule}.",
                                role, path, detail=detail).issue]
