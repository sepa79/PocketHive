"""Responsibility: retain immutable, bounded file evidence at content-addressed paths.
Must not: infer provenance or rewrite document references. Contract: intake-contract.md.
"""
from .errors import IntakeError
from .package_context import sha256


def snapshot_ref(package, path, data):
    suffix = path.suffix.lower() if path.suffix.lower() in (".yaml", ".yml", ".json") else ".txt"
    return f"{package.local_name('evidenceDirectory')}/{sha256(data)}{suffix}"


def retain_snapshots(package, store, root, snapshots):
    total = sum(len(data) for data in snapshots.values())
    if len(snapshots) > package.manifest["limits"]["bundleFiles"] or total > package.manifest["limits"]["bundleBytes"]:
        raise IntakeError("INPUT_LIMIT", "Evidence exceeds declared total-file or byte limits.")
    for ref, data in snapshots.items():
        path = package.evidence_path(root, ref)
        if not path.is_relative_to(root):
            raise IntakeError("EVIDENCE_PATH", "Snapshots must stay in the selected documents directory.")
        if path.exists() and package.read(path) != data:
            raise IntakeError("EVIDENCE_COLLISION", "An existing evidence snapshot differs; it will not be overwritten.")
    for ref, data in snapshots.items():
        path = package.evidence_path(root, ref)
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            store.write_bytes(path, data)
