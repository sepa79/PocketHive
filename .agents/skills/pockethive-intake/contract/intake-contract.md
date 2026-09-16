# Intake document and CLI contract — version 1

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
| `validate --documents DIR --stage draft` | Validate YAML syntax, schemas, links, projection consistency, source evidence and known semantic constraints. Return unresolved handoff questions while allowing a structurally valid draft. |
| `validate --documents DIR --stage handoff` | Also require the relevant material inputs, evidence coverage, plan review references and pinned document hashes. A passing check establishes document consistency only. |
| `finalise --documents DIR` | Refresh derived question projection and cross-document paths/hashes in dependency order. Refuse independent edits to the generated projection. Never create approvals, adopt proposals or change client facts. |
| `verify-package` | Verify every manifest-listed package file and original-source checksum. Integrity checks are not a cryptographic signature or authenticity guarantee. |

`scripts/package.py --output FILE` builds a deterministic ZIP from the manifest.
`--refresh-manifest` is an explicit maintainer operation after reviewed package
changes. It does not run during intake or repair package corruption automatically.

Commands emit one JSON object. `status` is `ok`, `incomplete` or `error`; issues
have `code`, `document`, `pointer` and `message`. Exit 0 means the requested
operation completed, including a valid partial draft; exit 3 means handoff is
incomplete; exit 2 means invalid input, validation errors or failed I/O. No source
payload, credential, parser excerpt or stack trace is emitted in error messages.

## Ownership

- Requirements own client goals; plans own proposed/executable choices.
- `traceability.instance` owns source references, proposals, questions and review
  records. `requirements.openQuestions` is generated from unresolved questions.
- Results own recorded observations and the existing overall decision rule.
  Unexecuted results never become measured outcomes during intake.
- One YAML codec owns safe round-trip parsing, serialization and reload checks.
  One resolver owns package/document paths. One semantic validator owns intake
  conclusions. No Scenario Manager validation or worker-template engine is copied.

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
editing its generated requirements projection independently is rejected.
Finalize in order: requirements, plan, results, traceability. The traceability
file records the other hashes, never its own. Its external byte hash identifies
the set for later MCP review. Changed material content invalidates its local
confirmation; other byte changes still require refreshed integrity hashes and
the applicable MCP source-revision handling.

Writes use staged files, readback verification and atomic per-file replacement.
The four-file set is not a filesystem transaction: an interrupted finalisation
leaves visible stale hashes and must be explicitly finalised again. It never
reports success from attempted writes alone. Existing non-empty output directories
are not overwritten by initialisation.

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
