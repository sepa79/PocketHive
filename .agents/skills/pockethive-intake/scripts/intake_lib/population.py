"""Responsibility: populate a recorded bundle intake from its verified inspection.
Must not: replace its source, infer client adoption or own projection construction.
Local contract: intake-contract.md; bundle-observations.json.
Contract: RESP-INTAKE-POPULATION — docs/architecture/intake-runtime.md#resp-intake-population.
"""
from __future__ import annotations

from pathlib import Path

from .bundle_inspector import BundleInspector
from .errors import IntakeError
from .population_fields import TemplatePopulation
from .pointers import resolve
from .projections import Projections


def populate_from_inspection(package, codec, store, root: Path, docs: dict, *, expected_revision: str) -> dict:
    intake = resolve(docs["traceability"], "/instance/intake", document="traceability")
    selected = intake.get("source")
    if intake.get("mode") != "from-bundle" or not selected or selected.get("kind") != "directory":
        raise IntakeError("POPULATION_SOURCE_MODE", "Population requires a recorded from-bundle directory source.",
                          "traceability", "/instance/intake/source")
    if not selected.get("artifactRef"):
        raise IntakeError("POPULATION_SOURCE_PATH", "The recorded bundle source must name an explicit directory.",
                          "traceability", "/instance/intake/source/artifactRef")
    source = package.evidence_path(root, selected["artifactRef"])
    package.check_bundle_documents(source, root)
    inspection = BundleInspector(package, codec).inspect(source)
    if inspection["sha256"] != selected.get("sha256"):
        raise IntakeError("SOURCE_HASH", "Selected bundle bytes changed; review the changed source explicitly.",
                          "traceability", "/instance/intake/source")
    projection = Projections(package, codec, store)
    projection.check_question_owner(docs)
    result = TemplatePopulation(package, codec).apply(docs, inspection)
    artifacts = projection.save(root, docs, expected_revision=expected_revision)
    return {**artifacts, **result}
