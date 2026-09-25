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

Use `inspect-bundle`'s current `coverage` to separate inventoried files from
extracted facts. Its `summary` counts files, observations and files needing source
review. Each file is `structured`, `not-extracted` or `unreadable`; even a
structured file can contain omitted values. `reviewRequired` concerns extraction
coverage only: false does not establish source faithfulness or completed QA.
Sensitive-value counts never authorise copying sensitive content into the intake.
The [contract](../contract/intake-contract.md#inspection-coverage-and-question-selection)
owns these fields. Follow the shared workflow's source review before asking about
an apparent gap; a saved report for old source bytes cannot establish current coverage.

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

1. Initialise the bundle's new/empty `intake/` directory using the selected mode, or resume the
   existing document set. Save all four documents before waiting for business
   answers. The generated source inspection report is read-only metadata, not a
   second source of client requirements. Keep forms and their supporting intake
   artifacts in this directory so they travel with the bundle. The canonical
   inspector excludes only this root-relative directory from scenario-source
   hashing; edits to runtime files still invalidate the recorded source identity.
2. Run the explicit population operation:

   ```sh
   python3 "/path/to/pockethive-intake/scripts/intake.py" populate-from-inspection --documents "/client/bundle/intake"
   ```

   It reinspects the recorded bundle source and requires its inventory hash to
   remain unchanged. Explicit top-level HTTP request templates populate
   `requirements.templates`: `serviceId`, `callId`, `protocol`, `method` and
   `pathTemplate` (retained verbatim as `path` only when admitted by the inspector's
   [string value boundary](../contract/intake-contract.md#observation-string-value-boundary)).
   Potentially credential-bearing URI forms are withheld in full, with a
   value-free limitation and source pointer; the path stays unknown. Do not copy
   a withheld value from the source or invent a replacement. New API identifiers are
   administrative links derived from the explicit service/call identity.
   Existing matching rows retain their identifiers; only null fields are filled.
   Repeating the operation is idempotent. Conflicting populated values,
   ambiguous identities or changed source bytes fail before documents are saved.

   Each copied fact retains source-file hash and exact pointer as an unconfirmed
   bundle observation. Population does not select SUTs or variable profiles,
   classify APIs as load, fill workload/KPI targets, grant approval or populate
   execution results. Headers, bodies, auth and data configuration need the
   agent's source review. Missing/unsupported templates and nested sequence
   representations remain visible extraction limits; the operation never invents
   a substitute. These limits do not mean the client omitted the facts.

   Continue the supported source review and fill remaining representable facts.
   Preserve unresolved template expressions; do not select variable profiles or
   evaluate arbitrary expressions to manufacture a value.
3. Assess whether the observed design meets the stated objective. Record
   improvements as engineering proposals, preserving the original observations.
4. Reuse any explicit adoption of this bundle for the stated requirement or plan
   scope. Ask the shared workflow's small question batch for remaining decisions.
5. Apply stakeholder writing and run `prepare-review` at the requested stage. Return the documents,
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
