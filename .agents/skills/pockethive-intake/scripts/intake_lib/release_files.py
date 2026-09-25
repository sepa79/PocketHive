"""Responsibility: enumerate exactly the manifest-declared release roots.
Must not: include client workspaces, caches or follow links. Local contract: manifest.json.
Contract: RESP-INTAKE-PACKAGING — docs/architecture/intake-runtime.md#resp-intake-packaging.
"""
from .errors import IntakeError


def release_files(package):
    found = {}
    for name in package.manifest["releaseRoots"]:
        root = package.asset(name)
        if not root.exists():
            raise IntakeError("PACKAGE_MISSING", "A declared release root is missing.", name)
        paths = sorted(root.rglob("*")) if root.is_dir() else [root]
        for path in paths:
            relative = path.relative_to(package.root)
            if "__pycache__" in relative.parts or path.suffix in (".pyc", ".pyo"):
                continue
            package.reject_links(path, package.root)
            if path.is_dir() or path == package.manifest_path:
                continue
            found[relative.as_posix()] = path
    return dict(sorted(found.items()))
