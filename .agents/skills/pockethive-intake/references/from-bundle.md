# Mode: existing bundle intake

Use `from-bundle` when the user wants requirements and a plan reconstructed from
an explicitly selected bundle. Apply the shared workflow and writing guidance
loaded by `SKILL.md`; this mode adds source-inspection instructions only.

## Select and inspect the source

Use the Git/workspace bundle or deployed bundle identified by the user. Ask only
when the source is missing or ambiguous. Record its location, identity and exact
content evidence. Do not silently switch source mode after an access failure.

For a local source, use an existing bundle directory with the CLI inspection
command. A ZIP must be explicitly unpacked before it can be supplied as that
directory; the CLI does not accept ZIP input. Inspect the source files the
report identifies: scenario YAML, request templates, selected variable context,
SUT definitions, datasets/schemas, assertions, plans and supplied run evidence.
Use the CLI's supported input shape; an unsupported archive or file type is an
explicit gap, not permission to write another parser. Preserve binary files as
evidence references when their contents cannot be inspected.

An observation's `artifactRef` is relative to the report's `sourceRoot`. Join
that declared root explicitly when recording local provenance. Evidence paths
in the document set must be absolute or relative to its documents directory;
the validator does not search the bundle or package as an alternative root.

For a deployed source, use the selected environment's authorised MCP reads as
described in [PocketHive context](pockethive-context.md). Record the actual
environment and returned artifact identity. Do not claim that a local directory
was deployed merely because its name matches. Record retained MCP exports as
source snapshots, not as verified Git history.

## Produce useful drafts

1. Initialise a new/empty output directory using the selected mode, or resume the
   existing document set. Save all four documents before waiting for business
   answers. The generated source inspection report is read-only metadata, not a
   second source of client requirements.
2. Populate supported fields with observed configuration and exact provenance.
   Preserve unresolved template expressions; do not select variable profiles or
   evaluate arbitrary expressions to manufacture a value.
3. Assess whether the observed design meets the stated objective. Record
   improvements as engineering proposals, preserving the original observations.
4. Reuse any explicit adoption of this bundle for the stated requirement or plan
   scope. Ask the shared workflow's small question batch for remaining decisions.
5. Apply stakeholder writing, finalise and validate. Return the documents,
   observed validation result and a consolidated review with explicit gaps.

| Source observation | What still needs evidence or a client decision |
| --- | --- |
| Rate, duration, topology, retries and timeout | Intended workload, accepted targets and safe operating limits |
| Endpoint, authentication reference and payload binding | Connectivity, credential readiness, data eligibility and permission to use them |
| Response mapping or assertion | Accepted business criterion and actual execution outcome |
| Historical run observations | Applicability to this assessment; another run's results remain unknown |

For example, a configured 100 requests/second is an offered rate. It does not
populate the required successful-transaction target or define a transaction.

Keep several bundles as separate candidates unless the user explicitly supplies
their shared requirement or relationship. Similar names or payloads do not
justify merging them. One document set selects one SUT and active plan; report
unsupported simultaneous multi-plan linkage instead of cloning editable shared
requirements or overwriting a plan reference.
