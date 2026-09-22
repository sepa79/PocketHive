"""Responsibility: enumerate declared local evidence records and reference-only links.
Must not: interpret payload strings or validate evidence. Local contract: intake-contract.md, traceability schema.
Contract: RESP-INTAKE-PROVENANCE — docs/architecture/intake-runtime.md#resp-intake-provenance.
"""


def evidence_references(docs):
    instance = docs["traceability"]["instance"]
    records, links = [], []
    selected = instance["intake"]["source"]
    if selected and selected["kind"] == "file":
        records.append(selected)
    for record in instance["provenance"]:
        records.extend(record["sources"])
        links.append((record, "confirmationRef"))
    for proposal in instance["proposals"]:
        records.extend(proposal["sources"])
        links.append((proposal, "decisionRef"))
    links.extend((question, "answerRef") for question in instance["questions"])
    links.append((instance["review"], "evidenceRef"))
    for coverage in instance["coverage"]:
        links.extend((coverage["evidenceRefs"], index) for index in range(len(coverage["evidenceRefs"])))
    return records, links
