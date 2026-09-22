"""Responsibility: compare explicit bundle snapshots and map changed evidence to targets.
Must not: parse source payloads, infer semantic impacts or mutate intake/source state.
Contract: intake-contract.md, Source comparison; bundle-observations.json.
"""
from __future__ import annotations

from pathlib import Path

from .bundle_inspector import BundleInspector
from .errors import IntakeError
from .pointers import resolve


def compare_source(package, codec, store, root: Path, docs: dict,
                   previous_source: Path, source: Path) -> dict:
    intake = resolve(docs["traceability"], "/instance/intake", document="traceability")
    selected = intake.get("source")
    if intake.get("mode") != "from-bundle" or not selected or selected.get("kind") != "directory":
        raise IntakeError("SOURCE_COMPARISON_MODE", "Comparison requires a recorded from-bundle directory source.",
                          "traceability", "/instance/intake/source")
    if not selected.get("artifactRef"):
        raise IntakeError("SOURCE_COMPARISON_PATH", "The recorded bundle source must name an explicit directory.",
                          "traceability", "/instance/intake/source/artifactRef")
    recorded_root = package.evidence_path(root, selected["artifactRef"])
    inspector = BundleInspector(package, codec)
    previous = inspector.inspect(previous_source)
    if previous["sha256"] != selected.get("sha256"):
        raise IntakeError("SOURCE_HASH", "The previous snapshot inventory does not match the recorded bundle source.",
                          "traceability", "/instance/intake/source/sha256")
    current = inspector.inspect(source)
    files = _changed_files(previous, current)
    observations = _changed_observations(previous, current)
    targets, unlinked = _impacted_targets(package, root, docs, recorded_root, {row["path"] for row in files})
    limitations = _limitations(previous, current, files, observations, targets) + unlinked
    # Reuse the inventory owner: a changing source cannot produce a trusted diff.
    for path, snapshot in ((previous_source, previous), (source, current)):
        if inspector.inspect(path)["sha256"] != snapshot["sha256"]:
            raise IntakeError("SOURCE_CHANGED_DURING_COMPARISON", "A supplied snapshot changed during comparison; provide stable snapshots.")
    return {
        "comparison": {
            "previousSource": {"artifactRef": str(previous_source), "sha256": previous["sha256"]},
            "source": {"artifactRef": str(source), "sha256": current["sha256"]},
            "changedFiles": files,
            "changedObservations": observations,
            "impactedTargets": targets,
            "limitations": limitations,
        },
        "warnings": [
            "Observation deltas cover the inspector allowlist only; other source changes require explicit review.",
            "Impacted targets identify changed supporting file bytes, not proven changes to client facts or approvals.",
            "No source, requirement, proposal or approval was updated or adopted.",
        ],
    }


def _changed_files(previous: dict, current: dict) -> list[dict]:
    old = {row["path"]: row for row in previous["files"]}
    new = {row["path"]: row for row in current["files"]}
    result = []
    for path in sorted(old.keys() | new.keys()):
        before, after = old.get(path), new.get(path)
        if before == after:
            continue
        result.append({"path": path, "change": "added" if before is None else "removed" if after is None else "modified",
                       "previousSha256": before["sha256"] if before else None,
                       "sha256": after["sha256"] if after else None})
    return result


def _changed_observations(previous: dict, current: dict) -> list[dict]:
    old = {(row["artifactRef"], row["pointer"]): row for row in previous["observations"]}
    new = {(row["artifactRef"], row["pointer"]): row for row in current["observations"]}
    result = []
    for artifact, pointer in sorted(old.keys() | new.keys()):
        before, after = old.get((artifact, pointer)), new.get((artifact, pointer))
        if before is not None and after is not None and type(before["value"]) is type(after["value"]) and before["value"] == after["value"]:
            continue
        result.append({"artifactRef": artifact, "pointer": pointer,
                       "change": "added" if before is None else "removed" if after is None else "modified",
                       "previousValue": before["value"] if before else None,
                       "value": after["value"] if after else None})
    return result


def _impacted_targets(package, root: Path, docs: dict, recorded_root: Path, changed: set[str]) -> tuple[list[dict], list[dict]]:
    targets: dict[tuple[str, str], set[str]] = {}
    limitations = []
    for index, record in enumerate(resolve(docs["traceability"], "/instance/provenance", document="traceability")):
        for source_index, source in enumerate(record["sources"]):
            ref = source.get("artifactRef")
            if not ref:
                continue
            try:
                path = package.evidence_path(root, ref)
            except IntakeError as error:
                if error.issue["code"] != "EXTERNAL_SOURCE_UNVERIFIED":
                    raise
                limitations.append(IntakeError("SOURCE_REFERENCE_UNMAPPED", "Remote evidence cannot be mapped to a local bundle file.",
                                               "traceability", f"/instance/provenance/{index}/sources/{source_index}").issue)
                continue
            if not path.is_relative_to(recorded_root):
                continue
            relative = path.relative_to(recorded_root).as_posix()
            if relative in changed:
                target = record["target"]
                key = (target["document"], target["pointer"])
                targets.setdefault(key, set()).add(relative)
    return ([{"target": {"document": document, "pointer": pointer}, "sources": sorted(sources)}
             for (document, pointer), sources in sorted(targets.items())], limitations)


def _limitations(previous: dict, current: dict, files: list[dict], observations: list[dict], targets: list[dict]) -> list[dict]:
    result = [{**issue, "snapshot": name}
              for name, snapshot in (("previous", previous), ("current", current)) for issue in snapshot["limitations"]]
    observed = {row["artifactRef"] for row in observations}
    linked = {source for row in targets for source in row["sources"]}
    for file in files:
        path = file["path"]
        if path not in observed:
            result.append(IntakeError("SOURCE_CHANGE_UNOBSERVED", "Changed file bytes have no changed supported observations; inspect the source explicitly.", path).issue)
        if path not in linked:
            result.append(IntakeError("SOURCE_IMPACT_UNMAPPED", "No provenance target links this changed file; its intake impact is unknown.", path).issue)
    return result
