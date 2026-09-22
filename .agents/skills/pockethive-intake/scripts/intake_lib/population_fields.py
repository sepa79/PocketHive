"""Responsibility: map explicit HTTP template observations into empty intake fields.
Must not: parse bundle files, overwrite client facts or adopt observed configuration.
Local contract: bundle-observations.json; intake-contract.md.
Contract: RESP-INTAKE-POPULATION — docs/architecture/intake-runtime.md#resp-intake-population.
"""
from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

from .errors import IntakeError
from .identity_index import index_identities
from .package_context import canonical_hash
from .pointers import resolve


class TemplatePopulation:
    def __init__(self, package, codec) -> None:
        self.codec = codec
        contract = json.loads(package.read(package.asset("contract/bundle-observations.json")))
        self.rules = contract["templatePopulation"]
        self.labels = contract["endpointDescriptions"]
        self.descriptor = package.manifest["bundleDescriptor"]
        template = package.manifest["templates"]["requirements"]["path"]
        self.blank = codec.parse(package.read(package.asset(template)), "requirements")["templates"][0]

    def apply(self, docs: dict, inspection: dict) -> dict:
        rows = deepcopy(resolve(docs["requirements"], "/templates", document="requirements"))
        provenance = deepcopy(resolve(docs["traceability"], "/instance/provenance", document="traceability"))
        gaps = list(inspection["limitations"])
        grouped = {}
        for observation in inspection["observations"]:
            grouped.setdefault(observation["artifactRef"], {})[observation["pointer"]] = observation
        candidates = self._candidates(grouped, gaps)
        created, filled, unchanged = [], [], []
        indexed = self._indexes(rows)
        for identity, fields in candidates:
            index = self._target(rows, indexed, identity)
            if index is None:
                index = next((i for i, row in enumerate(rows) if self.codec.plain(row) == self.codec.plain(self.blank)), len(rows))
                if index == len(rows):
                    rows.append(deepcopy(self.blank))
                created.append(f"/templates/{index}")
            row = rows[index]
            if row.get("apiId") is None:
                identifier = "API-OBS-" + canonical_hash(list(identity))[:16]
                row["apiId"] = identifier
                pointer = f"/templates/{index}/apiId"
                inputs = [{"document": "requirements", "pointer": f"/templates/{index}/{key}"}
                          for key in self.rules["identityKeys"]]
                sources = [self._source(inspection, fields[key]) for key in self.rules["identityKeys"]]
                provenance.append({"target": {"document": "requirements", "pointer": pointer},
                                   "kind": "calculation", "sources": sources, "confirmationRef": None,
                                   "calculation": {"inputs": inputs,
                                                   "formula": "API-OBS- + first 16 hexadecimal characters of SHA-256 of the canonical JSON [serviceId, callId] array",
                                                   "unit": "administrative-identifier"}})
                filled.append(pointer)
            fields_to_copy = {**self.rules["fields"], self.labels["valueField"]: self.labels["targetField"]}
            for source_key, target_key in fields_to_copy.items():
                observation = fields.get(source_key)
                if observation is None:
                    continue
                pointer = f"/templates/{index}/{target_key}"
                value = observation["value"]
                if row.get(target_key) is None:
                    row[target_key] = value
                    provenance.append({"target": {"document": "requirements", "pointer": pointer},
                                       "kind": "bundle-observation", "sources": [self._source(inspection, observation)],
                                       "confirmationRef": None, "calculation": None})
                    filled.append(pointer)
                elif row[target_key] == value:
                    unchanged.append(pointer)
                else:
                    self._conflict(pointer, "An observed value conflicts with an already populated intake field; neither was overwritten.")
        self._indexes(rows)
        if not candidates:
            gaps.append(IntakeError("NO_SUPPORTED_HTTP_TEMPLATES", "No supported explicit top-level HTTP request templates could be populated.").issue)
        docs["requirements"]["templates"] = rows
        docs["traceability"]["instance"]["provenance"] = provenance
        return {"population": {"createdTemplates": created, "filledFields": filled, "unchangedFields": unchanged},
                "gaps": gaps}

    def _candidates(self, grouped: dict, gaps: list) -> list:
        result, seen = [], set()
        for artifact, observations in sorted(grouped.items()):
            fields = {key: observations["/" + key] for key in self.rules["fields"] if "/" + key in observations}
            if not fields:
                if any(pointer.count("/") > 1 and pointer.endswith("/protocol") for pointer in observations):
                    gaps.append(IntakeError("NESTED_TEMPLATE_UNSUPPORTED", "Nested protocol observations require source review; automatic population supports top-level HTTP request templates only.", artifact).issue)
                continue
            identity = tuple(fields.get(key, {}).get("value") for key in self.rules["identityKeys"])
            if any(not self._literal_identity(value) for value in identity):
                gaps.append(IntakeError("TEMPLATE_IDENTITY_REQUIRED", "A template needs explicit literal serviceId and callId before it can be populated.", artifact).issue)
                continue
            if identity in seen:
                raise IntakeError("POPULATION_DUPLICATE_TEMPLATE", "Multiple source files declare the same serviceId/callId; resolve the duplicate source identity before population.", artifact)
            seen.add(identity)
            if fields.get("protocol", {}).get("value") != self.rules["protocol"]:
                gaps.append(IntakeError("HTTP_TEMPLATE_PROTOCOL", "Automatic population requires an explicit HTTP protocol; review this source's transport representation.", artifact, "/protocol").issue)
                continue
            for key in self.rules["fields"]:
                if key not in fields or fields[key]["value"] == "":
                    gaps.append(IntakeError("TEMPLATE_FIELD_UNAVAILABLE", "This template field has no supported source observation; retain the gap for source review.", artifact, "/" + key).issue)
                    fields.pop(key, None)
            result.append((identity, fields))
        self._display_names(result, grouped)
        return result

    def _display_names(self, candidates: list, grouped: dict) -> None:
        endpoints = {}
        for pointer, observation in grouped.get(self.descriptor, {}).items():
            prefix = self.labels["pointer"] + "/"
            if pointer.startswith(prefix):
                tail = pointer[len(prefix):].split("/")
                if len(tail) == 2 and tail[0].isdigit() and tail[1] in self.labels["fields"]:
                    endpoints.setdefault(tail[0], {})[tail[1]] = observation
        for _, fields in candidates:
            matching = [endpoint for endpoint in endpoints.values()
                        if all(key in endpoint and source in fields and endpoint[key]["value"] == fields[source]["value"]
                               for key, source in self.labels["matches"].items())]
            if not matching:
                continue
            peers = [other for _, other in candidates
                     if all(source in other and source in fields and other[source]["value"] == fields[source]["value"]
                            for source in self.labels["matches"].values())]
            if len(matching) != 1 or len(peers) != 1:
                raise IntakeError("POPULATION_AMBIGUOUS_DESCRIPTION", "An endpoint display description must match exactly one declaration and one HTTP template.",
                                  self.descriptor, self.labels["pointer"])
            label = matching[0].get(self.labels["valueField"])
            if label is not None and isinstance(label["value"], str) and label["value"].strip():
                fields[self.labels["valueField"]] = label

    def _target(self, rows: list, indexed: dict, identity: tuple) -> int | None:
        if identity in indexed:
            return int(indexed[identity][1].rsplit("/", 1)[1])
        for index, row in enumerate(rows):
            pair = tuple(row.get(key) for key in self.rules["identityKeys"])
            if any(value is None for value in pair) and any(value is not None and value == identity[i] for i, value in enumerate(pair)):
                self._conflict(f"/templates/{index}", "A partially identified intake row could refer to this source; complete its identity explicitly before population.")
        return None

    def _indexes(self, rows: list) -> dict:
        _, identifier_issues = index_identities(rows, "apiId", "requirements", "/templates")
        pairs, pair_issues = index_identities(rows, tuple(self.rules["identityKeys"]), "requirements", "/templates")
        if issues := identifier_issues + pair_issues:
            raise IntakeError(**issues[0])
        return pairs

    @staticmethod
    def _literal_identity(value: object) -> bool:
        return isinstance(value, str) and bool(value.strip()) and not any(token in value for token in ("{{", "{%", "${", "<", ">", "\n", "\r", "\x00"))

    @staticmethod
    def _source(inspection: dict, observation: dict) -> dict:
        return {"artifactRef": str(Path(inspection["sourceRoot"]) / observation["artifactRef"]),
                "pointer": observation["pointer"], "sha256": observation["sha256"]}

    @staticmethod
    def _conflict(pointer: str, message: str) -> None:
        raise IntakeError("POPULATION_CONFLICT", message, "requirements", pointer)
