"""Responsibility: describe field editing boundaries from the existing owning contracts.
Must not: validate client facts or duplicate projection assignments. Contract: intake-contract.md.
"""
from __future__ import annotations

import json

from .pointers import covers
from .projections import Projections


class AuthoringPolicy:
    def __init__(self, package, codec, store, docs):
        policy = json.loads(package.read(package.asset("contract/provenance-policy.json")))
        self.protected = {}
        generated = Projections(package, codec, store).assignments(docs, {})
        for role in package.manifest["templates"]:
            owners = {pointer: "administrative" for pointer in policy["administrativePointers"].get(role, [])}
            owners.update({pointer: "immutable" for pointer in policy["immutablePointers"].get(role, [])})
            owners.update({pointer: "generated" for pointer in generated[role]})
            if role == "plan":
                owners.update({"/approval": "human-review", "/generation/approval": "human-review"})
            if role == "traceability":
                owners.update({"/instance/review": "human-review", "/instance/provenance": "ledger"})
            self.protected[role] = owners

    def describe(self, role: str, pointer: str) -> dict:
        protected = [{"pointer": path, "owner": owner} for path, owner in self.protected[role].items()
                     if covers(path, pointer) or covers(pointer, path)]
        ledger = role == "traceability" and (
            pointer == "/instance/intake/source" or
            any(covers(parent, pointer) for parent in ("/instance/questions", "/instance/proposals")))
        editable = bool(pointer) and not protected and (role != "traceability" or ledger)
        owner = "ledger" if ledger else "business" if role != "traceability" else "administrative"
        if protected:
            containing = [item for item in protected if covers(item["pointer"], pointer)]
            owner = max(containing, key=lambda item: len(item["pointer"]))["owner"] if containing else "mixed"
        return {"editable": editable, "owner": owner, "protected": protected,
                "evidenceRequired": editable and role != "traceability"}
