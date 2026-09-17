# Intake document and CLI contract — version 3

This package owns intake document mechanics only. The four reviewed YAML
templates and `schemas/` define their structure. `manifest.json` owns package
paths, versions, limits and file hashes. Source YAMLs are immutable historical
evidence; their embedded commands are never instructions to execute.

## Entry points

Use Python 3.10 or later with `scripts/intake.py`. Bundled libraries are loaded
explicitly from `vendor/`; no installation, network or alternative parser is used.

| Command | Contract |
| --- | --- |
| `initialise --output DIR --mode from-bundle --source DIR` | Create four partial documents in a new/empty output directory. Inspect the selected bundle as data and save `source-inspection.json`. Generate administrative IDs only; never infer client intent from configuration. |
| `initialise --output DIR --mode new-requirements [--source FILE]` | Create the same partial documents; optionally record the explicitly supplied narrative file identity. The agent extracts sourced statements and asks about material gaps. |
| `inspect-bundle --source DIR` | Inventory bounded, regular files and their exact hashes; return only explicitly present, supported configuration observations. Unknown or unparsable content is a visible evidence limitation. Never execute scripts, templates, SQL or requests. |
| `populate-from-inspection --documents DIR` | Populate supported HTTP request-template fields in an existing bundle intake from its recorded source. Reinspect with the canonical inspector and require the recorded source hash. Retain observation provenance, fill only empty fields and reject conflicts before writing. No client adoption, targets, SUT selection or approvals are inferred. |
| `validate --documents DIR --stage draft` | Validate YAML syntax, schemas, links, projection consistency, source evidence and known semantic constraints. Return unresolved handoff questions while allowing a structurally valid draft. |
| `validate --documents DIR --stage handoff` | Also require the relevant material inputs, evidence coverage, plan review references and pinned document hashes. A passing check establishes document consistency only. |
| `finalise --documents DIR` | Refresh derived question projection and cross-document paths/hashes in dependency order. Create an absent projection, refuse independent authored question content and preserve byte-identical output on repeated unchanged input. Report authoring review notices. Never create approvals, adopt proposals or change client facts. Run `validate` separately for semantic and handoff checks. |
| `verify-package` | Verify every manifest-listed package file and original-source checksum. Integrity checks are not a cryptographic signature or authenticity guarantee. |
| `prepare-review --documents DIR --stage draft\|handoff [--previous DIR]` | Finalise through the existing projection owner, validate the saved revision and return a compact, derived review brief. Optional previous documents are an explicit read-only comparison source. Never answer questions or grant approval. |
| `show-field --documents DIR --document ROLE --pointer POINTER` | Read the exact current field, its canonical schema constraints, editing ownership and relevant existing diagnostics. No document writes or alternate field dictionary. |
| `show-fields --documents DIR --input FILE` | Read a bounded explicit list of fields with one validation pass and shared document revision. See [field help](field-help.md). |
| `apply-updates --documents DIR --input FILE [--dry-run]` | Apply or preview an explicit, evidence-linked batch against an expected document revision through the existing owners. See [authoring outcomes](authoring-outcomes.md) for persistence and validation results. No inferred values or automatic approval. |
| `compare-source --documents DIR --previous-source DIR --source DIR` | Inspect two explicitly supplied bundle snapshots, require the previous inventory to match the recorded source, and report changed files/observations plus provenance-linked targets. Never replace the recorded source or author updates. |

`scripts/package.py --output FILE` builds a deterministic ZIP from the manifest.
`--refresh-manifest` is an explicit maintainer operation after reviewed package
changes. It does not run during intake or repair package corruption automatically.

Commands emit one JSON object. `status` is `ok`, `incomplete` or `error`; issues
have `code`, `document`, `pointer` and `message`. Exit 0 means the requested
operation completed, including a valid partial draft; exit 3 means handoff is
incomplete; exit 2 means invalid input, validation errors or failed I/O. No source
payload, credential or parser excerpt is emitted in error messages.

Issues may include a `detail` object with safe diagnostic metadata: the failed
schema rule, expected document version or exception type. Schema and projection
input checks identify their owning document and JSON Pointer. Unexpected
failures retain `COMMAND_FAILED` and report the exception type plus a safe cause
description; raw exception messages can contain client data and are not emitted.

`--debug` is accepted before or after the subcommand. It writes diagnostic JSON
to stderr, retaining one result JSON object on stdout and the normal exit code.
Diagnostics include exception classes and package-relative code locations,
without local variables, source lines, client payloads or absolute implementation
file paths. Explicit workspace paths in layout and lock diagnostics are retained.
Known boundary errors retain their code/document/pointer; debug does not alter
validation, repair inputs or turn a failed command into success.

## Ownership

- Requirements own client goals; plans own proposed/executable choices.
- `traceability.instance` owns source references, proposals, questions and review
  records. `requirements.openQuestions` is generated from unresolved questions.
- Results own recorded observations and the existing overall decision rule.
  Unexecuted results never become measured outcomes during intake.
- One YAML codec owns safe round-trip parsing, serialization and reload checks.
  One resolver owns package/document paths. One semantic validator owns intake
  conclusions. No Scenario Manager validation or worker-template engine is copied.

## Bundled intake layout

Keep the four forms with their scenario in the bundle-root `intake/` directory:

```text
bundle/
  scenario.yaml
  templates/
  intake/
    requirements.yaml
    test-plan.yaml
    traceability.yaml
    execution-results.yaml
    source-inspection.json
```

`manifest.json.bundleIntakeDirectory` owns this reserved directory name;
`PackageContext` resolves it and checks document placement. `from-bundle`
initialisation accepts `--source /client/bundle --output /client/bundle/intake`.
An explicitly selected external document directory also remains supported.
The bundle root itself and other directories inside its source tree are invalid
document roots (`OUTPUT_IN_SOURCE`), including on resume and explicit source updates.
No directory is inferred from a filename or found by searching other roots.
New-requirements intake can start in a future bundle's `intake/` before a scenario
exists; it retains narrative mode until an explicit later authoring task.

`BundleInspector` excludes exactly the root-relative `intake/` subtree from
scenario-source inventory, hashing, observations and coverage. It prunes that
directory before traversal and reports `excludedDirectories: ["intake/"]` even
when absent. A symbolic link or non-directory at that reserved path fails explicitly.
Other directories named `intake`, such as `templates/intake/`, remain source.
The reserved directory must contain forms and supporting intake artifacts only;
runtime scenario assets belong outside it. No arbitrary exclusion flag is provided.
Inspection, population, source comparison and source validation use this same owner.
Adding or editing forms, review output, the write lock or the saved inspection report
there cannot change the recorded scenario-source hash.

Intake integrity remains separate: document revisions, projections, review digests
and explicitly referenced evidence files retain their existing byte checks, including
evidence stored under `intake/`. A bundle archive includes the forms. This inspection
hash is not the runtime bundle digest or ZIP digest; those may include every file.
Existing recorded hashes are never silently refreshed when the inspection scope or
source changes. Review and explicitly update the source identity and affected evidence.
Moving/copying a bundle does not automatically rebind existing absolute source or
evidence paths; review those references explicitly at the destination.

Retain old source snapshots outside the bundle: runtime descriptor discovery is
recursive and a second `scenario.yaml` can make a bundle ambiguous. The runtime
validator also scans YAML/JSON for variable references, including intake prose.
Use the supported PocketHive validation at authoring handoff; this skill does not
change runtime validation or declare a bundle deployable.

## Friction-reducing authoring operations

These operations preserve every source YAML, working template and document schema.
They add no client database, second questionnaire or independent readiness rules.
`DocumentStore` owns the raw document-set revision: `documentsSha256` hashes the
canonical mapping of document roles to their exact byte hashes. It differs from
the existing material review digest and detects formatting and ledger changes too.

All CLI document mutations share one exclusive `.intake-write.lock` directory in
the explicit document root. Lock acquisition fails immediately with `DOCUMENTS_BUSY`;
there is no retry, stale-lock takeover or age-based guess. Normal completion releases
the lock. After a killed process, an operator must establish that no writer remains
before explicitly removing that empty lock directory. Initialisation checks emptiness
while holding the same lock, excluding only its own lock directory. Cooperating CLI
writers are serialised; arbitrary external editors are not fenced. Read operations
compare the input revision before and after reading; changed inputs fail explicitly.
Writes remain atomic per file, not a four-file transaction.

Lock failures include `detail.lockPath` and `detail.nextAction`. Busy-lock guidance
requires establishing that no writer remains, inspecting the document set for partial
writes, and explicitly removing only an empty abandoned lock. No command guesses that
a lock is stale or removes it automatically. These explicit workspace paths are safe
diagnostic metadata; errors still omit source contents and credentials.

`initialise` keeps `OUTPUT_EXISTS` for a nonempty target and reports
`detail.directoryState`: `complete`, `partial`, `unrelated` or `not-directory`.
`existingDocuments` and `missingDocuments` contain only canonical document filenames;
complete means all four regular files exist, not that they validate. `nextAction`
directs callers to resume a complete set, restore a partial set from one consistent
revision, or select a new/empty directory. It never overwrites work or invents the
missing documents' identities. Additional files alone are not an existing intake.

### Review and field views

Safe pointer help and bulk field views are defined in [field help](field-help.md).
Per-group counts and update preview/persistence results are defined in
[authoring outcomes](authoring-outcomes.md). They project the existing owners;
neither introduces another schema or readiness calculation.

`prepare-review` returns the normal validator errors/gaps/warnings plus `brief`:
source mode, source identity, current review record, provenance-linked field
references (with their declared evidence kinds), unanswered questions, proposals,
and diagnostics grouped by question target or document section. Group category
arrays contain indexes into the result's canonical `errors`, `gaps` and `warnings`
arrays; messages are emitted once. Answered questions
are counted rather than asked again; a contradictory validator finding still appears.
No new question text is generated. The agent selects a small relevant question batch
from these facts. A source reference never establishes semantic support or adoption.

The brief identifies `documentsSha256`, `reviewContentSha256` and the requested
stage. Draft gaps do not block saving; handoff gaps produce exit 3 as in `validate`.
Optional previous-set comparison reports changed exact pointers and the previous
byte revision, without echoing replaced values or transferring acceptance. The
stakeholder summary is authored from this brief and requested field views, with
the same revision identified. It is never a second editable authority.

`brief.comparison.ledgerChanges` supplements the exact pointer diff with
`questions` and `proposals` arrays matched by their existing canonical `id`.
Each change names `id`, `change`, `previousPointer` and `pointer` (null when the
row is absent). Questions use `added`, `removed`, `answered`, `reopened` or
`changed`; proposals use `added`, `removed` or `changed`. `answered` means a
recorded transition into that status; `reopened` means a recorded transition out
of it. Other row content changes remain `changed`. Reordering unchanged rows
produces no ledger change. Removed questions remain removals, never inferred
answers. The view does not echo replaced content, validate source fidelity,
change statuses or transfer approval. Existing canonical validation owns record
shape and identity; ambiguous identities fail explicitly before comparison.
For stable comparison, null or blank ledger IDs return `IDENTITY_REQUIRED` and
duplicates retain `DUPLICATE_ID`. This requirement applies to the comparison;
ordinary draft validation keeps its existing severity and never generates IDs.
Both snapshots must explicitly contain the question and proposal collections
for ledger comparison. Missing paths use the canonical `POINTER` diagnostic with
the snapshot identified; an absent collection is never treated as empty.

`show-field` requires a declared role and exact existing JSON Pointer. The schema
view derives from the existing schema resolver, including applicable structural
branches; it is guidance, not a second validator. Output explicitly requested field
content is client data. Only matching field diagnostics appear at the result's
top level; `validationSummary` reports whole-set error/gap/warning counts without
duplicating unrelated findings. A successful field read is not document readiness.
Diagnostics retain their existing redaction policy. Package
hash verification remains automatic; agents need not load the manifest hash list.

`field.context` contains only overlapping `provenance`, `questions` and
`proposals` from `traceability.instance`. A target overlaps when it names the
same document and the exact requested pointer, an ancestor or a descendant.
A directly requested question/proposal ledger row also includes its own record.
The same existing validator diagnostics for these linked records are included
with the field diagnostics, once per canonical diagnostic. Context is a
read-only projection of the recorded rows, including their source references
and explicit statuses. No source artifact is fetched or excerpted by this view,
and a linked reference is never labelled verified solely because it exists.

### Inspection coverage and question selection

`inspect-bundle` and the initial `source-inspection.json` include `coverage`.
It describes extraction by the existing `BundleInspector`, not performance-test
coverage, client intent or completed engineering review. `coverage.files` has one
row per inventoried file: `path`, `status`, `observationCount`,
`unextractedScalarCount`, `sensitiveScalarCount` and `reviewRequired`. Status is
`structured`, `not-extracted` or `unreadable`. Counts are null for files whose
structured contents were not read; a parsed file has exact counts from the
canonical allowlist traversal. Sensitive contents and their pointers are never
emitted. A structured file needs source review if it has omitted values or no
observations; every not-extracted/unreadable file needs source review. A false
`reviewRequired` only means all scalar values were emitted by extraction, not
that the source has passed QA review. Non-structured files remain references;
scripts and binary content are never executed or decoded implicitly.

`coverage.summary` reports `fileCount`, `structuredFileCount`,
`notExtractedFileCount`, `unreadableFileCount`, `observationCount` and
`reviewRequiredFileCount`. This output is derived from the same inspection pass
and adds no parser, editable coverage ledger or alternative validation gate.
Existing source hashes and observation values are unchanged by this projection.
Source-review guidance uses a current explicit inspection and its source identity;
an old saved report never proves current coverage.

Before selecting client questions, inspect supplied evidence for the relevant
field and distinguish an extraction limitation, conflicting sources and a
remaining client decision. Record sourced representable facts through the
existing authoring path. Keep unsupported evidence and unresolved conflicts
visible; never fill them with guesses or treat configuration as accepted targets.
Ask only the material decision still needed. These are agent review steps, not
a client calibration ceremony or another approval gate.

### Explicit update batch

The input is one bounded YAML or JSON object parsed by the canonical YAML codec:

```yaml
expectedDocumentsSha256: <digest returned by prepare-review or show-field>
updates:
  - target: {document: requirements, pointer: /project/objective}
    value: <exact supplied statement>
    provenance:
      target: {document: requirements, pointer: /project/objective}
      kind: client-statement
      sources:
        - artifactRef: /explicit/path/to/client-answer.txt
          pointer: null
          sha256: <exact source byte hash>
      confirmationRef: null
      calculation: null
```

Each update replaces one existing field or subtree. Missing paths, duplicate or
overlapping targets, unknown envelope keys, immutable policy, generated fields and
administrative identities fail before writing. A business-field update requires
one exact-target provenance record in the existing canonical shape. It replaces
that target's previous provenance; intersecting ancestor/descendant provenance
fails explicitly so broader evidence is not silently retained for changed facts.
Batch values may be incomplete drafts, but invalid schemas or semantic/evidence
errors fail without persisting the batch. Supplied local evidence must have valid
bytes and pointers; absent, remote-only or invalid evidence cannot author new facts.
Existing unrelated draft gaps remain gaps. A stale human review is reported after
a material change; the old confirmation is never rebound or erased automatically.

The same batch may explicitly replace existing fields under
`traceability:/instance/questions` or `/instance/proposals`, or the exact
`/instance/intake/source`, using `provenance: null`. These already have their own
canonical evidence structures and validation. An answered question or accepted
proposal with missing decision evidence is rejected before writing. Bundle mode
must retain a declared directory source with an absolute identity path; narrative mode permits a file or no source
yet. The validator owns this relationship; clearing a bundle identity cannot make
source verification disappear. Review/approval records remain the
existing explicit human-evidence workflow; this helper cannot set them. It does not
rewrite answered questions merely because a fact was populated. An empty batch is
invalid. Stale input revisions return `STALE_DOCUMENTS` before writes. Success
reports verified persistence and the resulting validation; incomplete intake is
not silently promoted to readiness.

### Source comparison

`compare-source` reuses `BundleInspector` for both supplied directories. A retained
hash or old inspection JSON cannot reconstruct missing prior source bytes. A previous
snapshot whose inventory does not match the recorded bundle returns `SOURCE_HASH`.
Results identify additions, removals, changed observations and provenance-linked
targets. Unsupported/unmapped changes remain explicit review limitations. Old
relative evidence paths resolve only against their declared documents root; original
bundle evidence paths are related to the recorded source root, not guessed from a
basename. Both snapshots and the intake remain unchanged. Subsequent selective edits
use the same explicit update path; no automatic source switch, merge or adoption.

If the exact previous bytes are unavailable, historical comparison is unavailable.
First try a retained snapshot or explicitly selected Git revision and verify its
inventory matches. Otherwise inspect the current source, review the affected facts
and all supporting provenance, then explicitly update those records and
`/instance/intake/source` through the existing authoring workflow. Record the missing
historical evidence as a limitation. `populate-from-inspection` requires the old
recorded hash and cannot repair source drift; refreshing only the hash is not review.

## Evidence and review

Every material populated client/plan fact needs provenance covering its exact
JSON Pointer or a containing subtree. Metadata, immutable policy text, generated
identifiers/references and empty scaffolding are not business facts. A source
record names an artifact, pointer when applicable, and SHA-256 digest. Local
artifacts are checked; remote records are never fetched implicitly and remain
explicitly externally unverified. A known sample cannot be its own adoption or approval evidence, even when
renamed. A separate explicit decision artifact is required. A source locator cannot prove that its contents
support a statement: a human must review fidelity.

Local evidence `artifactRef` paths are absolute or relative to the documents
directory. Bundle inspection observation paths are relative to its reported
`sourceRoot`; join that explicit root before recording an evidence path, or
record an explicit relative path from the documents directory. Never try another
root after a path fails. Remote evidence needs a retained local copy for the
validator's handoff checks; retaining a copy does not authenticate its author.

Bundle observations remain observations until explicitly adopted for a named
scope. Engineer proposals retain their rationale and status. Accepted proposals
require a decision reference. Exact calculations retain inputs and formula;
neither arithmetic nor naming conventions establishes a client target.

Handoff requires a named human review record and the plan's existing approvals.
Setting a status alone is insufficient. This validator cannot authenticate an
approver, grant approval, create a valid MCP confirmation or authorise operations.
It reports these limits even when all mechanical checks pass.

### Review content binding

`finalise` reports `reviewContentSha256` for the material content currently
available for review. The canonical review-digest owner hashes document facts,
the intake source, coverage and substantive question/proposal content. It
excludes generated references, plan approval fields and evidence bookkeeping:
the provenance ledger, question status/answer reference, and proposal
status/decision reference. The packaged policy and digest implementation own
these rules; clients must not reimplement them.

Present the identified material content for human review. Only actual acceptance
of that content permits recording `traceability.instance.review.status`,
`evidenceRef` and `contentSha256`. The CLI never writes a confirmed status or
accepted content digest. Record the acceptance evidence, finalise derived
metadata again, then validate the requested stage. Acceptance metadata alone
does not change the material digest or add another approval round.

If an answer changes material facts or unresolved premises, record those changes
before presenting the final content for confirmation. Never replace the digest
of an old confirmation with a newly calculated digest automatically. The final
`traceabilitySha256` is a separate byte hash binding the saved document set for
later MCP `ConfirmedSource` confirmation; it is not the local review-content hash.

## Drafts and relevant requirements

Nulls and scaffold arrays are valid draft values. Missing optional production
context and future execution evidence do not block intake. Handoff requires
client identity/objective, selected SUT and explicit API/transport choices, an
applicable workload, data/auth/mock decisions, test limits, measurable acceptance
rules and source-linked review. Requirements are checked only for the selected
SUT and applicable API mode; excluded/readiness APIs do not need load timelines.

The validator checks declared enum/shape constraints, reference identity, unique
IDs, workload model consistency, applicable measurement definitions, KPI mapping,
data allocation, async completion, selected-SUT boundaries, proposal/review
references and unexecuted-result integrity. Unsupported authoring/runtime
capabilities remain gaps; it never substitutes another protocol or model.

### Authoring review notices

`finalise` is a metadata operation, not a readiness gate. Both `finalise` and
`validate` use one read-only authoring-advisory owner to return the following
notices in `warnings`. These notices do not change status, exit codes, document
bytes, approvals or requiredness. Schemas must pass before these checks run.

| Code | Explicit trigger and limit |
| --- | --- |
| `PAYLOAD_BINDINGS_REVIEW` | A participating API has a nonempty object/array `requestSample` but no body binding. Review the body contract. Samples are illustrative; the checker never derives fields, values or a complete request contract from them. A body binding does not prove complete coverage. Opaque strings are not parsed. |
| `DATE_CONSTANT_REVIEW` | A participating API's explicit constant contains a string in exact `YYYY-MM-DD` form representing a calendar date. Review its intended time semantics and validity for the planned run. The checker traverses typed constant objects/arrays and identifies the exact value pointer. It uses no current clock, field-name guess, expiry interpretation or automatic replacement. Other formats require the engineer's review. |
| `PRODUCTION_CONTEXT_REVIEW` | Production evidence availability is unknown and a KPI row for a selected-SUT load API has an unknown TPS target. Invite a scoped decision about the target's basis. Production evidence remains optional; this notice neither requires a TPS target for every test nor derives one. |

`payloadBindings` owns authored request-field bindings. Samples never supply
missing bindings, correlation extraction or literal runtime defaults. Completeness
requires review against the identified API contract and intended request; it
cannot be proven against an illustrative sample. No new binding types or
template fields are introduced by these checks.

Existing semantic owners still enforce selected dataset usage/reset decisions,
applicable KPI targets and positive-retry idempotency decisions during `validate`.
The agent records unresolved material decisions once in the question ledger,
grouping related pointers. Warnings alone neither author nor answer questions.
See [QA review guidance](../references/qa-review.md) for decisions and evidence
that use the existing fields. All working templates, source YAMLs and schemas
remain unchanged for this refinement.

## Reference integrity

Reference integrity is checked after all four schemas pass. Populated SUT, API,
entity, database and criterion identities must be unique in their declared lists;
serviceId/callId pairs identify one API template. Query IDs are scoped to their
database, column names to their entity, and step IDs to their sequence. Repeated
calls to an API require distinct step IDs; correlations resolve exact step
occurrences, never a guessed nearest call. Null references remain draft gaps.

Question IDs and proposal IDs are each unique within their own ledger collection.
Their populated document targets resolve exact JSON Pointers in the declared
requirements, plan or results document. Coverage resolves each populated
`kpiPointer`, `planRulePointer` and `resultPointer` against its respective document
and checks populated criterion/API/SUT identities. An unresolved pointer is an
error even when its record is labelled mapped; no status can make a dangling
reference valid. Schema-permitted null pointers remain incomplete placeholders.
Measurement mapping rules separately own target shape and KPI dimensions.

Populated references must resolve in their declared scope and populated document
identities must agree. Resolve participating APIs and native mock dependency
endpoints against the explicitly selected SUT. Excluded APIs do not need endpoint
resolution. Data used by the selected plan must resolve its selected-SUT source,
database and query; unrelated unselected-SUT details may remain incomplete.
Reference validation does not choose an adapter or decide missing-field readiness.

## Paths, hashes and writes

Package paths are manifest-relative and remain inside the installed root.
Document paths resolve from the explicit documents root; selected source paths
are recorded separately. Symlinks, archive traversal, unbounded files and writes
inside packaged assets are rejected. Original source files remain byte-identical
apart from their explicitly renamed sample filename.

Question projection ownership is checked against the previous projection digest
stored in the traceability instance. Editing the question owner is permitted;
editing its generated requirements projection independently is rejected with
`PROJECTION_EDIT` at `requirements:/openQuestions`.

The working requirements template omits `openQuestions`; author questions only
in `traceability.instance.questions`. An absent projection has no authored
content to protect and `finalise` regenerates it from that owner, including after
removal of a previously generated field. A null stored digest denotes no prior
projection: an absent/empty projection or one already equal to the owner can be
generated; different nonempty content is rejected rather than discarded. With
a stored digest and a present projection, the previous digest must match before
the owner can refresh it. A missing digest field is malformed metadata, not an
implicit null. `validate` reports an absent or stale projection and directs the
caller to `finalise`; it does not create one. Generated nonempty projections
remain valid saved output.

Finalize in order: requirements, plan, results, traceability. The traceability
file records the other hashes, never its own. Its external byte hash identifies
the set for later MCP review. Changed material content invalidates its local
confirmation; other byte changes still require refreshed integrity hashes and
the applicable MCP source-revision handling.

Writes use staged files, readback verification and atomic per-file replacement.
Final readback must match the prepared document-set byte revision; observed disk
bytes cannot replace the intended bytes as the definition of successful persistence.
The four-file set is not a filesystem transaction: an interrupted finalisation
leaves visible stale hashes and must be explicitly finalised again. It never
reports success from attempted writes alone. Existing non-empty output directories
are not overwritten by initialisation.

## Observed draft population

`initialise` establishes the authoring workspace. The explicit
`populate-from-inspection` operation then reuses the inspector's supported
observations to populate template identifiers and HTTP protocol/method/path
fields with exact file hashes and pointers. API identifiers generated by this
operation are administrative identities, not client requirements. Unsupported
source shapes and absent declarations remain visible gaps. The operation does
not evaluate path templates, resolve endpoint context, copy authentication
payloads or select a different source when the recorded source is unavailable.

Existing authored values, IDs and evidence survive a repeated operation.
Conflicting populated values or ambiguous identities fail before document
writes. A changed source revision requires explicit review; neither stale
inspection JSON nor a new on-disk source silently replaces the recorded input.
Bundle observations still require scoped client adoption for handoff.

## Correlated request bindings

A requirements payload binding may use
`source: {type: correlation, correlationRef: account-id}`. The binding owns the
destination `location` and `path`. Its reference resolves to a `correlationId`
on each applicable destination sequence step in the plan. That existing sequence
correlation owns `fromStepRef`, `responsePath`, `toStepRef` and `required`.
`responsePath` is an exact JSON Pointer, not an expression or prose workaround.

The source step must precede the destination; `toStepRef` must identify the
containing step. Step IDs identify exact occurrences, including repeated calls
to one API. Local correlation IDs permit separate explicit source occurrences
for repeated destination calls. Unknown, ambiguous, orphan or forward references
fail; incomplete fields remain declared draft gaps. Authentication tokens retain
their existing owner and are not inferred as ordinary payload correlations.

Working plan version **5** removes the correlation's duplicate `location` and
`path`: use the requirements binding destination and the step-local reference.
Version 4 documents require explicit edits to that representation and their
version before finalisation; there is no implicit migration. Requirements version
2 gains the additive binding type. Original supplied YAMLs remain unchanged.

## Scope limits

The package inspects local directories and narrative files, not ZIP inputs.
Package qualification uses offline relocation and adversarial fixtures. Client
skill discovery, MCP authentication, Scenario Manager semantics and actual load
execution each require their separate qualification. No command starts a swarm,
queries a database, configures mocks, sends traffic or performs cleanup.

## Measurement mappings and recorded results

Coverage `kpiPointer` identifies an exact numeric target leaf:
`/kpis/N/targetTps` or `/kpis/N/maxResponseTimeMs`.
`planRulePointer` identifies `/acceptanceCriteria/N/measurableRules/N`.
Missing mappings are handoff gaps only for the selected SUT's explicitly active
APIs. Criteria without a stakeholder KPI remain valid generic criteria.

Mapped rules preserve API, SUT, criterion, transaction definition and observation
window. TPS uses `successful-transactions-per-second` with `>=` or `>`;
latency uses `milliseconds` with `<=` or `<` and the exact stated percentile.
Thresholds may be equal or stricter; missing targets, operator aliases, incompatible
units and weaker thresholds are never repaired by interpreting metric names.

When a coverage row links an observed acceptance rule, `resultPointer` identifies
`/criterionResults/N/ruleResults/N`. That record's `rule` is a read-only exact
snapshot of the referenced plan rule. Future result links may remain absent.
The checker compares only explicit links; it does not select a rule by its text.

The source status vocabulary represents executed reports with `pass`, `fail` or
`inconclusive` in `runInfo.executionStatus`. An unexecuted scaffold may retain
inconclusive placeholder criteria, but cannot contain test passes/failures or
achieved observations, including invented zero counts. Evidenced pre-run checks
and data preparation do not establish that load ran. Recorded run outcomes need
the actual run identity and runtime snapshot, plus their named supporting evidence.
Producer stop, remaining work and drain completion must be evidenced; incomplete
draining needs the declared and actual timeout response. These are consistency
checks, not a new outcome calculator or a source of operational approval.

Sample payloads, blueprints and free-form observations intentionally accept opaque
JSON-shaped content. The canonical YAML codec bounds their nested data; structural
schema acceptance does not validate their business meaning or runtime behavior.

## Data preparation constraints

Only explicitly participating API bindings select required datasets. Selected-SUT
source and column settings must be complete; no alternate environment is inferred.
Preparation cannot undershoot the required row count, permit forbidden reuse or
increase the declared per-record concurrency limit. The contract defines no
ordering between exhaustion actions, so a changed action requires revising the
owning requirement explicitly. Database-export credentials need an explicit
injection binding even when the tested API itself is public. Redis connection
configuration remains owned by its declared `connectionRef`; intake does not
infer authentication or verify a live connection.

## Workload and authentication consistency

A sequential workload has one journey schedule. Load APIs declare their exact
number of sequence occurrences and do not also define per-API rate schedules.
Null counts and incomplete sequences remain missing decisions; a populated
contradictory count is an error. Closed-loop population/pacing never substitutes
for an open-loop rate schedule.

Protected APIs need explicit token acquisition/reuse settings and a declared
secret-injection mechanism. A selected OAuth credential must have its named
binding; a different credential cannot satisfy it. Custom token-provider
references must resolve without cycles. These checks establish document
consistency, not working credentials or authenticated live access.
