"""Responsibility: bind human review to the material content and intake ledger.
Must not: grant, refresh or infer approval. Local contract: intake-contract.md, provenance-policy.json.
Contract: RESP-INTAKE-PROVENANCE — docs/architecture/intake-runtime.md#resp-intake-provenance.
"""
from .package_context import canonical_hash
from .pointers import covers, escape


def review_digest(docs: dict, policy: dict) -> str:
    material = {}
    for role in ("requirements", "plan", "results"):
        excluded = policy["reviewExcludedPointers"][role]
        material[role] = dict(_review_nodes(docs[role], excluded))
    instance = docs["traceability"]["instance"]
    material["ledger"] = {key: instance[key] for key in ("intake", "coverage")}
    for key, fields in policy["reviewLedgerFields"].items():
        material["ledger"][key] = [{field: row[field] for field in fields} for row in instance[key]]
    return canonical_hash(material)


def _review_nodes(value, excluded, pointer=""):
    """Retain structure without hashing policy-excluded subtrees through their parents."""
    if any(covers(parent, pointer) for parent in excluded):
        return
    if isinstance(value, dict):
        yield pointer, ["object"]
        for key, child in value.items():
            yield from _review_nodes(child, excluded, f"{pointer}/{escape(key)}")
    elif isinstance(value, list):
        yield pointer, ["array"]
        for index, child in enumerate(value):
            yield from _review_nodes(child, excluded, f"{pointer}/{index}")
    else:
        yield pointer, ["value", value]
