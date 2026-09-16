"""Responsibility: inventory an explicit bundle and report safe literal observations.
Must not: resolve endpoints, infer adapters or execute source content. Contract: bundle-observations.json.
"""
from __future__ import annotations

import json
from pathlib import Path

from .errors import IntakeError
from .package_context import PackageContext, canonical_hash, sha256
from .pointers import leaves
from .yaml_codec import YamlCodec


class BundleInspector:
    def __init__(self, package: PackageContext, codec: YamlCodec) -> None:
        self.package = package
        self.codec = codec
        self.rules = json.loads(package.read(package.asset("contract/bundle-observations.json")))

    def inspect(self, root: Path) -> dict:
        if not root.is_dir():
            raise IntakeError("BUNDLE_DIRECTORY", "Bundle source must be an existing directory.")
        if not (root / self.package.manifest["bundleDescriptor"]).is_file():
            raise IntakeError("SCENARIO_SOURCE", "The selected bundle must explicitly contain scenario.yaml.")
        inventory, observations, limitations = [], [], []
        total = 0
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root).as_posix()
            if any(part in (".git", "__pycache__") for part in path.relative_to(root).parts):
                continue
            self.package.reject_links(path, root)
            if path.is_dir():
                continue
            if len(inventory) >= self.package.manifest["limits"]["bundleFiles"]:
                raise IntakeError("BUNDLE_LIMIT", "Bundle has too many files.")
            data = self.package.read(path)
            total += len(data)
            if total > self.package.manifest["limits"]["bundleBytes"]:
                raise IntakeError("BUNDLE_LIMIT", "Bundle exceeds the total input-size limit.")
            digest = sha256(data)
            inventory.append({"path": relative, "sha256": digest, "bytes": len(data)})
            if path.suffix.lower() not in (".yaml", ".yml", ".json"):
                continue
            try:
                value = self.codec.plain(self.codec.parse(data, relative))
                for pointer, scalar in leaves(value):
                    segments = pointer.casefold().split("/")
                    if any(any(term in segment for term in self.rules["sensitiveSegments"]) for segment in segments):
                        continue
                    key = pointer.rsplit("/", 1)[-1]
                    allowed = (
                        key in self.rules["numberKeys"] and type(scalar) in (int, float)
                        or key in self.rules["booleanKeys"] and type(scalar) is bool
                        or key in self.rules["enumKeys"] and scalar in self.rules["enumKeys"][key]
                    )
                    if allowed:
                        observations.append({"artifactRef": relative, "pointer": pointer, "value": scalar,
                                             "sha256": digest, "kind": "bundle-observation"})
            except IntakeError as exc:
                limitations.append(IntakeError(exc.issue["code"], "File inventoried, but structured observations could not be read.", relative).issue)
        return {"sourceRoot": str(root), "sha256": canonical_hash(inventory), "files": inventory,
                "observations": observations, "limitations": limitations,
                "claims": {"clientIntentConfirmed": False, "scenarioValidated": False, "scriptsExecuted": False}}
