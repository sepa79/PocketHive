"""Responsibility: bind human review to the material content and intake ledger.
Must not: grant, refresh or infer approval. Local contract: intake-contract.md, provenance-policy.json.
Contract: RESP-INTAKE-PROVENANCE — docs/architecture/intake-runtime.md#resp-intake-provenance.
"""
from .package_context import canonical_hash
from .pointers import covers, leaves


def review_digest(docs: dict, policy: dict) -> str:
    material = {}
    for role in ("requirements", "plan", "results"):
        excluded = policy["reviewExcludedPointers"][role]
        material[role] = {pointer: value for pointer, value in leaves(docs[role])
                          if not any(covers(parent, pointer) for parent in excluded)}
    instance = docs["traceability"]["instance"]
    material["ledger"] = {key: instance[key] for key in ("intake", "coverage")}
    for key, fields in policy["reviewLedgerFields"].items():
        material["ledger"][key] = [{field: row[field] for field in fields} for row in instance[key]]
    return canonical_hash(material)
