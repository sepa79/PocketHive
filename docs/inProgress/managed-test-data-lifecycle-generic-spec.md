# Managed Dataset Shared-Replay MVP Specification

Status: proposed; architecture approval, implementation and qualification pending

This document is the normative proposal for the first Managed Dataset release.
It does not change the existing Scheduler, `CSV_DATASET` or `REDIS_DATASET`
adapters.

## Decision

Adopt one small vertical slice:

```text
version-controlled Dataset catalogue
  -> Scenario Manager validates and publishes
  -> PostgreSQL authoritative catalogue and runtime records

SCHEDULER WorkInput
  -> provider scenario and SUT calls
  -> MANAGED_DATASET WorkOutput(CREATE_RECORD)
  -> PostgreSQL

Orchestrator admits and fences
  -> Swarm Controller reads one sealed PostgreSQL revision
  -> binding-scoped immutable Redis projection
  -> workers verify and load local memory
  -> normal scenario pipeline
  -> SUT
```

The MVP is `REPLAY + SHARED`. Records are immutable, non-expiring and reusable.
They have no queue, checkout, lease, state, View, used flag or consume-on-read
lifecycle.

PostgreSQL is authoritative. Redis is a rebuildable per-swarm runtime projection.
Worker memory is the only source used by the measured selection-to-SUT-attempt
path.

## Goal

A provider swarm can create domain-neutral SUT records once and make them
available to many compatible consumer swarms. Dataset Definitions may partition
records by arbitrary schema-defined Group fields. PocketHive defines no business
fields.

The design must:

- keep authoring reviewable and runtime identity reproducible;
- prevent partial or cross-SUT data visibility;
- preserve shared-record semantics under concurrency and failures;
- keep remote storage and control-plane calls outside measured traffic;
- expose evidence that a scenario loaded, selected and attempted the declared
  data; and
- leave clear capability gates for future leases, mutable workflow and refill.

## Hard rules

1. Git is authoring history; PostgreSQL is the published runtime authority.
2. Scenario Manager is the single Dataset catalogue and Scenario Bundle
   validator and publisher.
3. A published `id + version` is immutable. There is no `latest`, range,
   fallback, automatic migration or automatic rebinding.
4. A Scenario Binding freezes one SUT Environment, Dataset Space, Dataset,
   Group, Definition version/digest, Contract versions/digests and authority
   revision.
5. One Provider Run freezes exactly one caller-selected finite upstream
   Scheduler authoring role. Validated topology and fully rendered runtime
   wiring must agree that it supplies every Managed Dataset output binding in
   that run, with no competing Scheduler path.
6. Provider records are unavailable until the Scheduler has durably closed
   issuance, every issued item is terminal, the record-creation fence is
   closed and PostgreSQL atomically seals an exact-target revision 1.
7. Orchestrator provisions, authorises, fences and reports. It never proxies
   record bytes or selects records for measured traffic.
8. Swarm Controller creates and reconciles Redis projections. Workers never
   access PostgreSQL.
9. A Redis projection is immutable and invisible until its manifest and records
   are complete and its Active Projection Reference is atomically advanced.
   Every Activation Generation is durably reserved before Redis mutation and
   is never assigned to another publication.
10. Workers load in the background, verify the complete projection and atomically
   replace local memory. Traffic makes no Redis, PostgreSQL, Controller,
   Orchestrator, Scenario Manager or credential-provider call.
11. `REPLAY + SHARED` never makes a record unavailable after use. Redis access,
    dequeue or movement is not consumption evidence.
12. Missing, unknown, stale or incompatible configuration fails explicitly.
    PocketHive never substitutes another Dataset, Group, adapter, source or
    revision.
13. Existing `SCHEDULER`, `CSV_DATASET` and `REDIS_DATASET` contracts and
    behavior remain unchanged.
14. Swarm Controller is the trusted sole projection writer. Redis ACLs restrict
    commands and binding keys; the Redis Function provides atomic validation
    and fencing, not an independent authorisation boundary.

## MVP boundary

### Included

- versioned Dataset Definitions and composed Schema Contracts;
- transactional publication into a PostgreSQL catalogue;
- optional Scenario Bundle `datasets/requirements.yaml` version 1;
- one scheduled, bounded provider fill through terminal
  `MANAGED_DATASET CREATE_RECORD`;
- a durable, fenced provider-item and completion barrier contract;
- immutable JSON-object records and frozen Groups;
- `REPLAY + SHARED` consumer bindings;
- PostgreSQL authority revisions;
- binding-scoped Redis projections and worker-local indexes;
- Create Swarm admission, fencing, capacity and recovery;
- REST/MCP consumption evidence from the Orchestrator read model;
- security, failure, performance and 24-hour qualification gates.

### Deferred

| Capability | Decision trigger |
|---|---|
| `EXCLUSIVE_LEASE` | An approved scenario must make a record temporarily unavailable. PostgreSQL must remain lease and fencing authority. |
| Mutable workflow state and Views | An approved flow must persist operational state across swarms and cannot model independent output as a new immutable record. |
| `MANAGED_DATASET_PROVIDER` input and automatic refill | Approved expiring/depleting supply or mutable workflow requires controlled replenishment. |
| Additional Managed Dataset CSV/Redis/import sources | A named ingestion use case cannot use the existing adapters plus the scheduled provider pipeline. |
| Managed Dataset derivation/copy | A named chained flow needs independently reusable output and defines lineage, idempotency and capacity. |
| Expiry, reclamation, retirement and purge | The bounded initial-fill storage horizon is insufficient and active-binding/evidence safety is specified. |
| Record browsing, arbitrary selectors, tags, payload replacement and audit history | A concrete use case justifies bounded query, privacy, retention and mutation contracts. |
| Read-only UI | REST/MCP contracts and the shared status projection are stable. The UI must not calculate a second status. |

Deferred does not mean implicitly supported. A capability is absent from the
capability catalogue and fails admission until its own contract and qualification
are approved.

## Canonical terms

| Term | Meaning |
|---|---|
| Dataset Catalogue Source | Version-controlled Definition and Contract artifacts plus their repository revision and paths. |
| Published Catalogue | Append-only, validated runtime catalogue in PostgreSQL. |
| Dataset Definition | Immutable named contract for record shape, Profile and grouping. |
| Schema Contract | Reusable, exact-version JSON Schema contract referenced by a Definition. |
| Dataset Instance | One provider-created runtime Dataset in one SUT Environment and Dataset Space. |
| Group | Frozen partition identified by an opaque `groupId` and exact schema-defined key. |
| Provider Binding | Frozen plan that creates one Dataset Instance and its Groups. |
| Provider Run | One fenced initial-fill execution bound to one explicit finite upstream Scheduler role, agreeing logical topology and resolved runtime wiring, and the terminal Managed Dataset bindings it supplies. |
| Provider Item ID | Proposed Scheduler-owned identity allocated once for one logical provider item, unique within its Provider Run and preserved through retry, redelivery and restart. It is not the optional general WorkItem `messageId`. |
| Provider Completion Barrier | Proposed bounded PostgreSQL ledger proving issuance is closed, every issued item is terminal, in-flight count is zero and record creation is fenced before seal evaluation. It is not a workflow engine. |
| Consumer Binding | Frozen selection of one sealed Dataset Instance and Group. |
| Authority Revision | Immutable PostgreSQL record set sealed for a Dataset. MVP has revision 1 only. |
| Redis Projection | Rebuildable, binding-scoped copy of one Group at one Authority Revision. |
| Activation Generation | Orchestrator-owned monotonic number durably reserved for one projection publication before Redis mutation. A new reservation exceeds every prior reservation for the binding. It is not an Authority Revision. |
| Dataset Context | SDK-owned WorkItem context correlating the frozen binding, local selection and SUT-attempt guard. |

Dataset, Group and projection identities are opaque. Dataset `name` is required
and human-readable but is never identity, secret or selection fallback.

## Ownership

| Owner | Responsibility | Must not do |
|---|---|---|
| Git/repository | Authoring review and source history | Store runtime records or serve admitted traffic |
| Scenario Manager | Validate complete catalogue imports and Scenario Bundles; publish one transaction | Mutate runtime records or perform data-plane work |
| PostgreSQL | Published catalogue, Dataset identity, provider-item/completion ledger, records, Groups, revisions, idempotency and future state/leases | Serve workers directly |
| Orchestrator | Provision, admit, reserve, authorise, fence, close provider runs, assign generations and own the status read model | Proxy record bytes or select measured-path records |
| Swarm Controller | Read exact sealed revisions, create/reconcile Redis projections and aggregate worker evidence | Become record authority or measured-path selector |
| Redis | Hold immutable, per-swarm runtime projections | Become durable authority, lease authority or consumption evidence |
| Provider Scheduler | Claim bounded provider items, preserve Provider Item IDs and report terminal outcomes | Infer completion from local counters or issue after durable closure |
| Worker SDK | Preserve provider/consumer Dataset Context, verify/load projections, select locally and guard SUT attempts | Query PostgreSQL or use remote data in measured traffic |
| Scenario | Create domain data and interpret SUT outcomes | Ask PocketHive to infer business success |

This refines, rather than replaces, the approved PocketHive ownership rule:
Scenario Manager owns authoring/registry semantics and PostgreSQL owns the
published runtime representation.

## Versioned Dataset catalogue

### Source layout

Physical repository separation is not a runtime protocol. An initial source may
use a dedicated directory in the scenarios repository:

```text
scenarios/managed-dataset/<dataset-id>/<version>/dataset.yaml
scenarios/managed-dataset/<dataset-id>/<version>/record.schema.yaml
scenarios/dataset-contracts/<contract-id>/<version>/schema.yaml
```

It may move to another repository later without changing runtime DTOs. Catalogue
source configuration explicitly identifies repository, immutable revision and
root; no local-path fallback is allowed.

Each Definition stores:

- stable `id`, exact SemVer `version` and required `name`;
- literal `profile: REPLAY` and `allocation: SHARED`;
- exact Record Schema root;
- exact Contract IDs, versions and SHA-256 digests;
- grouping mode and field definitions; and
- fixed `classification: SYNTHETIC_NON_SENSITIVE`.

### Publication

Scenario Manager parses YAML as a closed safe subset: UTF-8 only, unique keys,
no custom tags, aliases or executable values. It converts content to the
canonical internal JSON form, applies RFC 8785 canonicalisation and computes
SHA-256 digests.

One import:

1. resolves one immutable repository revision;
2. inventories the complete configured catalogue root;
3. parses, resolves and validates every discovered Definition and Contract;
4. verifies exact references, Schema Profile and capability compatibility;
5. writes all new immutable artifacts and one catalogue revision in a single
   PostgreSQL transaction; and
6. activates that catalogue revision only after the transaction commits.

Each published artifact records stable ID, exact SemVer, canonical content,
SHA-256 digest, first-publication repository revision/path,
publication/catalogue revision and schema/profile compatibility evidence.
Subsequent identical imports may record import-run provenance but do not mutate
the artifact.

Publication rules:

- exact replay is idempotent;
- changed content under an existing `id + version` fails the complete import;
- a Definition reference contains exact Contract version and digest;
- one invalid artifact prevents every change in that import;
- failure leaves the previous valid catalogue active;
- removing or renaming a source artifact does not delete its published version
  or affect a running binding;
- Git availability is not required after admission; and
- runtime Dataset Instances and records are never written to Git.

Catalogue revision selects a validated published set; it is not a version
substitute. Runtime selection still freezes each exact artifact version/digest.

## Dataset Definition and Schema Profile

### Grouping

`UNGROUPED` has no fields and creates one internal Group. `GROUPED` declares
1..8 ordered fields and `maximumGroups` from 1..64. Field names are unique and
match `[A-Za-z][A-Za-z0-9_]{0,63}`.

Supported types are `STRING`, signed 64-bit `INTEGER` and `BOOLEAN`.
Strings declare `maximumLength` from 1..256 and reject empty, control,
leading-space and trailing-space values. A Group key contains every declared
field and no other field. Null, nesting and coercion fail. String comparison is
case-sensitive.

Each field has one absolute JSON Pointer `recordPath` to a required primitive
leaf in the resolved Record Schema. Paths are unique and fixed by the
Definition; they are not consumer selectors.

```yaml
id: shared-records
version: 1.0.0
name: Shared Records
profile: REPLAY
allocation: SHARED
classification: SYNTHETIC_NON_SENSITIVE
recordSchema:
  path: record.schema.yaml
  digest: sha256:...
contracts:
  - id: common-identity
    version: 2.1.0
    digest: sha256:...
grouping:
  type: GROUPED
  maximumGroups: 8
  fields:
    - name: category
      recordPath: /category
      type: STRING
      maximumLength: 40
    - name: variant
      recordPath: /variant
      type: STRING
      maximumLength: 40
```

Definitions declare field shape, not concrete Group values. The Provider
Binding renders the complete Group set once from allowlisted non-secret
provider `vars` and `sut` values. Unresolved, duplicate or invalid keys fail
before Dataset creation. Consumers select opaque published `groupId` values;
they never evaluate templates or arbitrary selectors.

### Composed schemas

Record roots are JSON Schema Draft 2020-12 under one closed Managed Dataset
Schema Profile. The allowlist is:

- identity/composition: `$schema`, `$id`, `$defs`, exact `$ref`,
  `$comment` and bounded `allOf`;
- annotations: `title` and `description`;
- assertions: `type`, `enum`, `const`, numeric bounds, `multipleOf`,
  string/array/object size bounds, `required` and `dependentRequired`; and
- structural applicators: `properties`, `propertyNames`, `prefixItems`,
  `items` and `unevaluatedProperties`.

All other keywords fail publication. In particular, `additionalProperties`,
`pattern`, `patternProperties`, `format`, `anyOf`, `oneOf`, `not`,
conditionals, `contains`, `uniqueItems`, dynamic references, external URLs
and content evaluation are forbidden.

Every applicable schema has an explicit semantic anchor: one non-null `type`
optionally plus `null`, one exact `const`, one non-empty single-type `enum`,
one exact typed `$ref`, or bounded `allOf` resolving to one compatible type.
Empty, annotation-only, contradictory and always-valid schemas fail.
Type-specific keywords require the matching resolved type.

Every non-constant object declares explicit `properties`, explicit `required`
including `[]`, and exactly one:

- `unevaluatedProperties: false` for a closed object; or
- `unevaluatedProperties: <anchored-value-schema>` for a typed map.

The closing rule sits on the resolved composition boundary. Direct `allOf`
object fragments may omit it only under that boundary. Record roots always use
`unevaluatedProperties: false`; arbitrary maps live under a declared property.

Every non-constant array uses anchored `items`, or non-empty anchored
`prefixItems` plus explicit `items: false` or anchored `items`.

References are exact immutable
`pockethive://dataset-contracts/<id>/<version>` values or local `$defs`.
Ranges, `latest`, anchors, HTTP(S), cycles and unresolved references fail.
Scenario Manager compiles one cycle-free graph and enforces explicit limits for
schema bytes, references, depth, total nodes, collection entries, applicator
branches and `allOf` branches. Repeated references count repeatedly.

No regular-expression or network evaluation occurs. Validation runs on a
dedicated bounded executor with item-count, queued-byte, concurrent-working-
memory and p99 limits. One qualified authority-serving replica must sustain the
complete admitted ingress.

### Record Codec

Every record is one non-null JSON object. The shared Record Codec is the only
parser/canonicaliser used by provider-side validation, authoritative ingress and
worker projection loading.

Before parsing, it limits raw octets. During parsing it rejects invalid UTF-8,
duplicate keys, trailing content, non-object roots and excess nesting, object
members, array elements, decoded UTF-8 string bytes or number-token characters.
It emits RFC 8785 canonical bytes, applies the canonical-byte limit and validates
the exact compiled schema. Precise identifiers and higher-precision numbers use
schema-typed strings.

The canonical WorkItem decoder rejects missing, blank, padded, mixed-case and
unknown `payloadEncoding` values before enum conversion. Managed Dataset
accepts only exact `utf-8`; `base64` fails.

Canonical record bytes are used for PostgreSQL storage and all content digests.
Record projection never removes fields. A later normal pipeline step may shape
the SUT request without changing the authority record.

## Scenario Bundle requirements

`datasets/requirements.yaml` is an optional, independently versioned Scenario
Bundle extension. It does not add a field to `scenario.yaml` and does not
change Scenario Protocol v2.

```yaml
version: 1
requirements:
  - bindingRef: inputRecords
    datasetDefinition:
      id: shared-records
      version: 1.0.0
      digest: sha256:...
    profile: REPLAY
    allocation: SHARED
```

The file contains a non-empty `requirements` array with unique `bindingRef`
values. Unknown fields/versions, duplicate entry points, traversal, symlink
escape, templates, `vars`, `sut`, credentials and concrete runtime
Dataset/Group IDs fail.

The Managed Dataset adapter graph must match exactly:

- `ABSENT` is valid only when the Scenario has no `MANAGED_DATASET`
  consumer input. A provider-only `MANAGED_DATASET` output remains valid.
- With `PRESENT`, every requirement maps to exactly one
  `MANAGED_DATASET` consumer input and every such input has one requirement.

Scenario Manager returns:

```text
ABSENT  = {status: ABSENT, artifactDigest}
PRESENT = {status: PRESENT, version: 1, artifactDigest, requirements: non-empty[]}
```

`artifactDigest` covers the complete immutable validated bundle. Runtime
preparation supplies `acceptedDatasetRequirementsVersion: 1` and
`expectedArtifactDigest`. A mismatch returns HTTP 412
`SCENARIO_ARTIFACT_DIGEST_CONFLICT`; it creates no binding, reservation,
projection or swarm runtime. Changed digest or selections require a new command
and idempotency key.

Version skew disables only Managed Dataset discovery/admission. Ordinary v2
scenarios continue in either rolling-upgrade order. A present requirements file
fails closed until Scenario Manager and Orchestrator both advertise version 1.

## Provider contract

### Binding and initial fill

The provider Create Swarm request supplies one explicit `schedulerRole` and one
plan for each terminal `MANAGED_DATASET` output in that Provider Run:

```yaml
schedulerRole: provider-source
providerDatasets:
  - bindingRef: supplyRecords
    name: Shared Records
    datasetDefinition:
      id: shared-records
      version: 1.0.0
      digest: sha256:...
    groups:
      - groupKey:
          category: "{{ vars.recordCategory }}"
          variant: "{{ sut.type }}"
        targetRecords: 1000
```

`schedulerRole` is the exact unique `template.bees[].role` authoring key. The
caller selects it; PocketHive never infers it from array order, role naming,
image, topology, queue wiring or a fallback. It is Managed Dataset admission
metadata, not a change to the Scheduler or Scenario Protocol v2 contracts.
Ordinary scenarios still need no such field.

PocketHive's Scenario Contract makes `topology` the logical graph and `work`
queue suffixes the runtime wiring. Managed Dataset admission therefore accepts
a Scheduler source only when the explicit `schedulerRole` reaches every
terminal Managed Dataset output in both the validated topology and the fully
rendered, validated SwarmPlan wiring, and no other Scheduler reaches those
outputs through either representation.

For every edge on the selected provider path, admission proves that:

- source and destination roles exist;
- the topology port IDs exist on those roles with `out` and `in` directions;
- the source port maps to the corresponding `work.out` entry and the
  destination port maps to the corresponding `work.in` entry;
- both fully resolved queue suffixes exist and are equal;
- the terminal role owns the declared `MANAGED_DATASET` output and exact
  `bindingRef`;
- the selected role declares `inputs.type: SCHEDULER`, its exact resolved input
  configuration has positive `maxMessages` and, on retry, matches the frozen
  digest; and
- no different Scheduler reaches a terminal binding through either topology
  or equal resolved runtime queues.

Every listed binding must belong to that Provider Run. Runtime wiring verifies
the explicit selection and detects competing sources; it never selects the
Scheduler or supplies missing logical edges. Missing topology, missing `work`
wiring, unresolved queues or any disagreement fails before Dataset, Provider
Run ledger or capacity reservation creation with
`PROVIDER_SCHEDULER_TOPOLOGY_MISMATCH`. An unrelated Scheduler outside both
representations remains unrelated. A second independent Scheduler requires a
separate provider Create Swarm command and Provider Run. Aggregate multi-source
issuance and a multi-source completion barrier are outside the MVP.

Source validation completes before every provisioning side effect and returns
one closed error on failure:

```text
PROVIDER_SCHEDULER_SOURCE_REQUIRED
PROVIDER_SCHEDULER_SOURCE_AMBIGUOUS
PROVIDER_SCHEDULER_UNBOUNDED
PROVIDER_SCHEDULER_TOPOLOGY_MISMATCH
```

Only successful admission may persist a Provider Run. Its immutable evidence
freezes the explicit Scheduler role, validated topology digest, fully resolved
runtime-binding digest, exact resolved Scheduler input-configuration digest,
positive `maxMessages`, Provider Run ID and fence, and complete terminal
Managed Dataset binding set. The M0 digest contract covers the relevant roles,
ports, edges, `work` directions, port keys, resolved queue suffixes, terminal
outputs and binding references from the same immutable rendered SwarmPlan.

An exact retry revalidates and compares the frozen role, digests and binding
set before any new side effect. A changed topology, runtime wiring, Scheduler
configuration or binding membership under the same command/idempotency identity
conflicts; it never reuses or mutates the existing Provider Run.

The Orchestrator then renders Group keys once from the frozen Provider Scenario
Binding and validates positive targets. Because MVP `outputOrdinal: 0` permits
one record per issued item for each Dataset binding, admission checks each
binding independently before provisioning:

```text
for each Dataset binding in the Provider Run:
  sum(group.targetRecords) <= frozenProviderRun.maxMessages
```

An overflow or failed inequality returns `PROVIDER_PLAN_UNATTAINABLE` and
creates no Dataset, Provider Run ledger or capacity reservation. Equality is
not required: a provider may process items without emitting records. This check
proves only numerical feasibility; it does not predict scenario or SUT output.

Only after every binding passes does Orchestrator atomically reserve each
binding's target sum against PostgreSQL record/byte/idempotency and validation
capacity. The admitted maximum stored record count for a Dataset equals that
sum in the MVP.

Provisioning is idempotent on
`providerSwarmId + providerRunId + providerBindingRef`. Exact replay returns
the same `datasetId`; changed content conflicts. PostgreSQL creates the
Dataset and every Group in `BUILDING` before the provider starts.

The provider has the selected normal PocketHive WorkInput, initially
`SCHEDULER`.
`output-only` means Managed Dataset is only its terminal WorkOutput; it never
means a source-less swarm. The selected role and exact finite configuration are
the same ones frozen in the Provider Run.

```yaml
# first provider bee
inputs:
  type: SCHEDULER
  scheduler:
    ratePerSec: 50
    maxMessages: 1000

# terminal provider bee
outputs:
  type: MANAGED_DATASET
  managedDataset:
    bindingRef: supplyRecords
    operation: CREATE_RECORD
```

The scenario decides whether and what to write after its SUT work. Managed
Dataset never interprets a response, regex, header or status code.

### Provider item identity

The general WorkItem `messageId` remains unchanged and may be absent. Managed
Dataset providers instead use a protected Provider Item ID contract.

At admission, PostgreSQL creates a bounded Provider Run ledger with
`frozenProviderRun.maxMessages` issuance slots. Each slot receives one opaque
`providerItemId`.
The ID is unique within `providerRunId`; no wider uniqueness is claimed. Before
dispatch, the Scheduler claims one unissued slot under its current run fence.
The claim commits before the WorkInput is delivered. A lost response, retry,
redelivery or restart recovers and reuses that slot and ID; it never allocates
a replacement for the same logical item.

The Worker SDK carries `providerRunId`, `providerItemId` and the run fence in a
protected Managed Dataset Provider Context through WorkInput. Terminal
`MANAGED_DATASET` WorkOutput echoes that Context unchanged. Authors cannot set
or rewrite it. This is a provider-specific extension and does not change
Scenario Protocol v2.

### `CREATE_RECORD`

Every write:

1. resolves the authenticated provider run and frozen binding;
2. requires the non-empty protected `providerItemId` and matching current run
   fence;
3. derives the operation key from RFC 8785 canonical UTF-8 JSON:

   ```json
   {
     "bindingRef": "<bindingRef>",
     "outputOrdinal": 0,
     "providerItemId": "<providerItemId>",
     "providerRunId": "<providerRunId>"
   }
   ```

   The stored key is `sha256:` plus lowercase SHA-256 hex of those canonical
   bytes. Fixed field names and canonical encoding prevent concatenation
   ambiguity;
4. accepts MVP `outputOrdinal: 0` only;
5. validates the current UTF-8 payload with the Record Codec;
6. derives and validates exactly one frozen Group from the record;
7. transactionally enforces Dataset `BUILDING`, Group target, total admitted
   capacity and all byte/idempotency limits; and
8. returns stable `recordId`, Group and receipt data.

The operation-key record stores request content digest and result. Exact retry
returns the original result. Different content under the same key returns
`IDEMPOTENCY_CONFLICT`. Unique constraints and transactional counter locking
prevent concurrent duplicates or over-target commits. A duplicate retry never
increments Group or Dataset counts.

An output is acknowledged only after its record or idempotent replay result is
durably committed. A provider item may become terminal only after every
successful Managed Dataset output from that item has such a receipt. An item
that intentionally emits no record may still become terminal; final exact
target evaluation decides whether the Dataset is usable.

### Provider completion barrier

PostgreSQL stores the bounded Provider Completion Barrier: planned and issued
item counts, each issued Provider Item ID and terminal outcome, derived
`inFlightCount = issuedCount - terminalCount`, issuance closure, the
record-creation fence, completion fence and final result. Terminal outcomes are
the closed enum `SUCCEEDED`, `FAILED`, `TIMED_OUT` or `CANCELLED`.

Completion follows this order:

1. The current Scheduler run fence durably closes issuance. No unissued slot
   can be claimed afterwards and no new logical WorkInput can be created.
2. Every issued item reaches one terminal outcome. Terminal-outcome replay is
   idempotent; changed outcome content conflicts. After a bounded run timeout,
   only the current fenced Orchestrator may mark an abandoned item `TIMED_OUT`.
3. Orchestrator requires `inFlightCount == 0`.
4. One serializable PostgreSQL transaction locks the Provider Run and Dataset,
   rechecks steps 1–3, closes the record-creation fence and only then evaluates
   every Group count.
5. Exact targets with every issued item `SUCCEEDED` freeze record order and
   Group digests, assign Authority Revision 1 and move the Dataset to `SEALED`.
   Underfill or any `FAILED`, `TIMED_OUT` or `CANCELLED` outcome moves it to
   `FAILED` with a closed reason.

The fence close, count evaluation and final result commit atomically. A crash
before commit leaves the previous state retryable; a crash after commit replays
the stored result. Completion is idempotent on `providerRunId + completionFence`.
An exact duplicate returns the original result; a stale fence or changed command
fails.

Delivery rules are exact:

| Arrival | Result |
|---|---|
| Exact `CREATE_RECORD` replay already committed, before or after closure | Return the original receipt; do not increment counts. |
| Same operation key with changed content | `IDEMPOTENCY_CONFLICT`. |
| New operation while the record-creation fence is open | Validate and commit normally. |
| New operation after the record-creation fence closes | `PROVIDER_RECORD_FENCE_CLOSED`; no write. |
| Completion while an output/item is in flight | `PROVIDER_RUN_NOT_DRAINED`; no fence close or count evaluation. |
| Duplicate completion under the same fence | Return the stored completion result. |

The idempotency lookup precedes the creation-fence check, so a committed output
whose acknowledgement was lost remains replayable after closure. A new output
cannot race the barrier: its issued item remains in flight until its durable
receipt is recorded. Recovery uses only the PostgreSQL ledger and current
fences, never local Scheduler counters.

The completion result is therefore:

- `SEALED` only when every Group equals its exact target;
- `FAILED` for underfill, provider failure, timeout or cancellation; and
- unchanged for a retryable `PROVIDER_RUN_NOT_DRAINED` response.

No record is discoverable or projectable while `BUILDING`. Completion is
idempotent and fenced. It never reconciles, replaces, refills or falls back.
Creating another supply requires an explicit new Dataset Instance.

## Consumer admission and WorkInput

Create Swarm discovery lists only `SEALED` Dataset Instances with the same SUT
Environment and Dataset Space and exact Definition/Profile/schema compatibility.
The request supplies one exact Dataset/Group selection per requirement.
Absence requires `datasetSelections: []`. Empty discovery never causes an
automatic choice.

The admitted Consumer Binding freezes:

- Scenario Bundle digest and requirements version;
- SUT Environment and Dataset Space;
- Dataset/Group IDs and Dataset name;
- Definition/Contract versions and digests;
- `REPLAY + SHARED`;
- Authority Revision and Group content digest; and
- worker loading, polling, memory, qualified observation-delivery bounds and
  evidence limits.

```yaml
inputs:
  type: MANAGED_DATASET
  managedDataset:
    bindingRef: inputRecords
    ratePerSec: 500
    selectionStrategy: ROUND_ROBIN
    sutAttemptRole: sut-client
    sutAttemptGuard:
      maximumInvocationDuration: PT5S
      maximumClockSkew: PT1S
    consumptionObservation:
      reportInterval: PT5S
      staleAfter: PT20S
      evidenceWindow: PT1M
      pipelineLagTolerance: PT30S
```

MVP supports only explicit `ROUND_ROBIN`. Each worker has an independent local
cursor; duplicate concurrent use is valid. Selection does not move a record or
change its availability.

Managed Dataset evidence uses the existing worker-to-Controller-to-Orchestrator
status path; it adds no event bus or immediate evidence channel. M0 qualifies
and versions this deployment bound:

```text
maximumObservationDeliveryAge =
  maximumTimeUntilNextWorkerReport
  + maximumWorkerToControllerDeliveryDuration
  + maximumControllerAggregationDuration
  + maximumControllerToOrchestratorDeliveryDuration
  + maximumAcceptedClockSkew
```

`maximumTimeUntilNextWorkerReport` is no shorter than `reportInterval` and
includes the actual periodic worker `status-delta` cadence and scheduling
jitter. Controller aggregation includes the bounded `status-full` refresh
required by a reporter-set or epoch change. The Controller-to-Orchestrator term
includes its actual `status-delta` publication cadence. The transport terms
include the qualified at-least-once delivery and processing bounds.
`maximumAcceptedClockSkew` is the frozen
`sutAttemptGuard.maximumClockSkew`; there is no second clock-skew setting. No
component is assumed to take zero time.

Admission freezes the resolved component bounds and requires:

```text
reportInterval > 0
staleAfter > 0
evidenceWindow > 0
reportInterval < staleAfter
maximumObservationDeliveryAge <= staleAfter
maximumObservationDeliveryAge <= evidenceWindow
```

`staleAfter` and `evidenceWindow` remain independent semantics; either may be
shorter only when both cover the qualified delivery bound. An age equal to its
limit is current; an age greater than the limit is stale or expired. A window
that cannot cover the bound fails before swarm creation with
`DATASET_OBSERVATION_WINDOW_UNATTAINABLE`. Unknown fields and other invalid
duration relationships also fail admission.

Traffic rate, ramp, conditional execution and distribution are not
observation-configuration validity checks. Create Swarm does not require a
minimum attempt count or guaranteed all-worker traffic.

## PostgreSQL projection reader

Orchestrator begins one binding publication by persisting a descriptor containing
binding, exact Dataset/Group/revision/digests, Controller identity, fencing token,
reserved Activation Generation, expiry and page/byte limits. At most one
publication is open per binding.

The Controller receives an opaque reader grant. It calls one least-privilege
PostgreSQL function that:

- validates `session_user`, grant, Controller, binding, fence and expiry;
- accepts only the exact sealed revision and bounded page cursor;
- returns immutable records in frozen order plus final count/digest evidence;
- exposes no table, arbitrary SQL, selector or cross-binding access; and
- remains stable across pages because a sealed revision cannot change.

Orchestrator never receives the returned bytes. Grant lifetime covers response
transit, maximum export, operation timeout, clock skew and safety margin.
Expiry leaves no active partial projection.

The function is `SECURITY DEFINER`, revokes `PUBLIC`, fully qualifies
objects and uses a trusted search path ending in `pg_temp`, for example
`pg_catalog, managed_dataset, pg_temp`. It validates `session_user`, not
`current_user`, because `current_user` changes to the function owner.

## Redis runtime projection

### Key and data model

Redis keys are versioned and binding-scoped:

```text
ph:md:{<swarmId>|<bindingId>}:projection:<projectionId>:records
ph:md:{<swarmId>|<bindingId>}:projection:<projectionId>:manifest
ph:md:{<swarmId>|<bindingId>}:active
```

The common hash tag puts every key needed by one atomic operation in the same
Redis Cluster slot. A shared key builder encodes opaque IDs; user-controlled
Dataset or Group names never form keys. Cross-slot activation is invalid.

`records` is an immutable sorted set. Score is the bounded one-based frozen
record ordinal; each unique member is a canonical
`{recordId, record}` envelope. The ordinal is an exactly representable positive
integer no greater than `2^53`; the deployment record limit is lower and is
checked at admission. Workers use bounded rank pages.

`manifest` is a closed hash containing:

- projection, swarm, binding, Dataset and Group IDs;
- Definition and Schema digests;
- Authority Revision;
- exact record count and SHA-256 length-framed content digest;
- creation fence and timestamps; and
- format version.

`active` is a closed reference containing projection ID, Authority Revision,
manifest digest, fencing token and monotonic Activation Generation.

No projection key has a record-expiry TTL. The deployment requires
`maxmemory-policy noeviction`. Redis returning out-of-memory preserves the old
active projection and fails the new publication.

Managed Dataset projection requires Redis 7 or later so Functions and separate
read/write key permissions have one qualified contract. Deployment loads one
versioned activation/reconciliation function library and verifies its digest
before admission. Missing or mismatched functions fail; Controller never loads
an ad-hoc script as fallback.

Each binding names an explicitly admitted Managed Dataset projection Redis
deployment profile. PocketHive never infers or reconfigures an existing
`REDIS_DATASET` endpoint. A deployment may be shared only when its Redis
version, `noeviction` policy, ACL isolation and complete Managed Dataset plus
non-Dataset capacity have been qualified together. Sharing changes no existing
adapter contract or configuration.

### Write and activation

Before writing, admission reserves measured Redis memory for the active
projection, one complete staging projection, one bounded recovery projection,
key/data-structure overhead, allocator fragmentation, client buffers,
replication/failover headroom and non-Dataset use.

Before any Redis mutation, Orchestrator locks the binding generation
high-water mark, reserves a generation greater than every generation ever
reserved for that binding, persists the publication ID/fence/generation as
`RESERVED` and advances the high-water mark in one PostgreSQL transaction. A
reserved generation belongs only to that publication. Exact retries of that
publication retain it; no new or replacement publication may reuse it, even if
the original publication failed, was abandoned or was never confirmed.

The Controller:

1. streams the exact PostgreSQL revision and verifies authority count/digest;
2. writes bounded, pipelined sorted-set batches and one manifest to new
   projection keys;
3. verifies every command acknowledgement;
4. invokes one versioned, bounded, same-slot activation function; and
5. confirms the result to Orchestrator.

The activation function checks the exact key prefix, projection format,
cardinality, manifest fields/digest, fencing token and generation. It atomically
advances `active` only when the generation is greater than the current value.
Exact retry returns the same result. Different content, lower/equal generation,
missing keys or wrong slot fails without changing `active`.

The Redis Function is the bounded atomic validation and fencing mechanism. It
is not a separate authorisation principal. Swarm Controller is the trusted,
prefix-restricted sole projection writer; the design does not claim that Redis
ACLs alone force every active-reference write through the Function.

The `projection-writer` ACL starts from `reset -@all` and generated exact
binding/environment key patterns. Its complete application command allowlist
is `PING`, `ZADD`, `ZCARD`, `HSET`, `HGET`, `HMGET`, `HGETALL`, `DEL` and
`FCALL`. Key access is limited to:

```text
ph:md:{<exact-swarmId>|<exact-bindingId>}:projection:*:records
ph:md:{<exact-swarmId>|<exact-bindingId>}:projection:*:manifest
ph:md:{<exact-swarmId>|<exact-bindingId>}:active
```

Writer code exposes only staged record/manifest writes, named cleanup and the
constant approved activation/reconciliation Function calls. Direct Active
Reference mutation is prohibited by the Controller port. Where a deployment
also claims Redis ACL rejection of direct mutation, M0 must prove that exact
selector configuration against the deployed Redis version; no such independent
ACL guarantee is assumed by this MVP.

`FCALL` ACLs restrict commands and keys, not the function name. The admitted
Redis deployment therefore contains exactly the digest-pinned Managed Dataset
library and approved entry points; an extra or changed function fails admission.
Only those functions may inspect the declared keys and return bounded results.
The Controller credential cannot call `FUNCTION` administration, `EVAL`,
`EVALSHA`, `EVAL_RO`, `EVALSHA_RO`, `SCRIPT`, `FCALL_RO`, key discovery or any
other command. `FUNCTION LOAD` with or without `REPLACE`, delete/flush/restore,
ACL/configuration, module, debug and equivalent administration are deployment
operator operations and unavailable to the Controller.

Workers have binding-scoped `projection-reader` credentials with only the
exact Active Reference, manifest and bounded `HGET`, `HMGET`, `HGETALL`,
`ZCARD`, `ZRANGE` and `PING` commands. They have no mutation, discovery,
scripting, publication or cross-binding access. Writer and reader credentials
are binding- and environment-scoped, injected and rotated through the
deployment's existing external secret mechanism, and absent from status/logs.

### Worker loading

A coalesced Controller control signal is a load hint, not a correctness
dependency. Each applicable worker also polls the exact Active Reference on a
bounded jittered background interval.

For a higher permitted generation the worker:

1. reads the Active Reference and exact manifest;
2. rejects identity, format, revision, digest, count or generation mismatch;
3. reads records in bounded pages and validates every envelope/record;
4. recomputes count, content and manifest digests;
5. rereads the Active Reference and requires the same generation/projection;
6. builds a complete next local index within admitted memory; and
7. atomically swaps local generation only after all checks pass.

Failure preserves the prior verified local index. A worker never downgrades.
A restarted worker receives the Orchestrator-confirmed minimum generation from
Controller configuration and rejects an older Redis reference even if its local
memory is empty.

The measured path performs local `ROUND_ROBIN` selection, attaches Dataset
Context and executes the normal pipeline. It performs no remote call.

### Recovery and cleanup

- Crash before activation: the old Active Reference remains; the recorded
  staging projection is deleted or rebuilt after fence reconciliation.
- Crash after activation but before confirmation: recovery invokes the closed
  reconciliation function. Exact active state is idempotently confirmed while
  Redis retains it; uncertainty never advances or deletes it.
- Redis failover loses recent writes: workers reject a missing, partial or older
  generation. Loaded workers keep their local generation; cold workers remain
  unready. Controller reconciles from PostgreSQL.
- Complete Redis loss: Orchestrator reserves a new generation greater than
  every generation ever reserved for the binding, including failed, abandoned
  and unconfirmed publications, then Controller deterministically reprojects
  the frozen PostgreSQL revision. A crash after Redis activation but before
  PostgreSQL confirmation cannot cause reuse or downgrade after that loss.
- Controller outage: already-loaded workers may continue for the admitted
  `maximumLoadedWorkerContinuityDuration`. A local monotonic deadline stops new
  dispatch after that bound unless fresh Controller reconciliation extends it.
  New/restarted workers remain unready.

Orchestrator records every publication/projection ID before Redis writes, so
recovery never scans Redis. After a confirmed activation, Controller may remove
only named non-active, non-staging derivative keys after atomically rechecking
the Active Reference. Cleanup failure consumes reserved derivative capacity and
may block another publication; it cannot delete PostgreSQL authority or the
active projection.

Redis persistence, replication, Sentinel/Cluster or managed-service HA may
reduce outage and rebuild time. They are continuity improvements only.
Asynchronous replication and persistence settings can lose acknowledged recent
writes, so correctness always comes from PostgreSQL plus deterministic
reprojection.

The MVP has one active Controller with fenced deterministic restart recovery.
Loaded-worker continuity is not multi-replica Controller HA.

## Dataset Context and consumption evidence

The Worker SDK owns
`WorkItem.headers[ph.dataset.context]` inside the canonical JSON WorkItem body.
It includes:

- context schema version, binding, Dataset and Group IDs;
- Definition/Schema digests and Authority Revision;
- projection ID and Activation Generation;
- `REPLAY + SHARED`;
- record ID and local selection sequence/time; and
- worker instance and restart epoch.

Authors cannot create or mutate this Context. Every intermediate worker
preserves it. The declared SUT-attempt role must advertise the guard capability.
Immediately before network invocation, the SDK checks the frozen binding,
loaded generation, pipeline-lag and invocation/clock bounds. It emits attempt
evidence only when the guarded SUT network attempt begins.

Status has three independent planes:

| Plane | Meaning |
|---|---|
| Group Availability | Catalogue/schema/authority/fill/seal health. A Group may be `AVAILABLE` with no consumer. |
| Projection Status | Per binding: requested, building, active, loaded or failed generation. |
| Consumption Status | Per binding: expected reporters, load, local selection and guarded SUT-attempt evidence. |

Projection loading readiness and observed consumption are independent. A
healthy worker may load the exact generation but receive no work during a low
or skewed traffic window. Absence of an attempt is missing evidence, not proof
of incorrect Dataset use.

Consumption Status uses this closed MVP set:

| Status | Meaning |
|---|---|
| `NOT_READY` | There is no active consumer, or an expected reporter is missing, stale or has not loaded the exact projection; Consumption Status makes no use claim. |
| `AWAITING_EVIDENCE` | Every expected reporter is current and loaded, but no current or historical qualifying guarded attempt exists for the current reporter epochs. |
| `PARTIAL_EVIDENCE` | At least one but fewer than all expected reporters has current matching selection and guarded-attempt evidence. |
| `CONSUMING` | Every expected reporter has current matching load, selection and guarded-attempt evidence. |
| `STALE_EVIDENCE` | Every expected reporter is current and loaded, no qualifying attempt is within `evidenceWindow`, and historical qualifying evidence exists for a current reporter epoch. |
| `FAILED` | A closed mismatch, guard rejection or evidence-contract failure occurred. Lack of traffic alone is not `FAILED`. |

`staleAfter` controls worker/Controller report freshness. `evidenceWindow`
controls guarded-attempt freshness. `expected` is the current enabled
applicable reporter-epoch set from authoritative swarm topology;
`status-full` materialises that set. Report timeout does not change it. A stale
reporter remains in that denominator until an authoritative topology or epoch
change removes it, but cannot satisfy `currentReporters`, `loaded` or
`observed`. `observed` counts only current expected reporters with matching
qualifying evidence inside `evidenceWindow`.

Evaluation uses this precedence:

```text
closed contract or guard error
  -> FAILED

expected == 0
  -> NOT_READY with reason NO_ACTIVE_CONSUMER

any expected reporter missing, stale or not loaded
  -> NOT_READY

expected > 0
AND currentReporters == expected
AND loaded == expected
AND observed == expected
  -> CONSUMING

some but not all expected reporters have current qualifying evidence
  -> PARTIAL_EVIDENCE

no current evidence, but historical qualifying evidence exists
  -> STALE_EVIDENCE

otherwise
  -> AWAITING_EVIDENCE
```

Every surface reports attempt evidence coverage as `observed/expected` and
reports loaded coverage separately. `CONSUMING` requires fresh matching
evidence from every enabled applicable worker for the frozen
Dataset/Group/schema/revision/projection generation, at least one local
selection and its correlated guarded SUT attempt within `evidenceWindow`. A
Redis read, record count, projection activation or local selection alone cannot
produce `CONSUMING`.

Create Swarm does not require a traffic plan to guarantee all-worker evidence.
Low rate, ramping, conditional execution or skew may leave status at
`AWAITING_EVIDENCE` or `PARTIAL_EVIDENCE`; that is truthful runtime observation,
not a Dataset correctness failure. PocketHive does not lengthen the window,
reduce the expected set or infer coverage. A future complete-evidence policy is
out of scope until a concrete use case defines it.

Workers report to Swarm Controller. Controller `status-full` retains bounded
worker identities and epochs. `status-delta` contains only per-binding counts,
reporter-set digest, freshness watermark, minimum loaded generation and
aggregate counters. A reporter-set/epoch change requires a new full snapshot.
Worker churn removes the old epoch from `expected`, adds the new epoch and
requires new evidence; attempts from the replaced epoch never satisfy the new
coverage set.

The M0 status contract adds monotonic ordering to this existing path. Each
worker report carries its worker instance, restart epoch and increasing report
sequence; each Controller aggregate carries its Controller fence/epoch and
increasing aggregate sequence. An exact duplicate is idempotent. Reused sequence
with changed content fails, and an older sequence cannot regress current load,
reporter or evidence state.

Evidence age is evaluated from validated event time, never arrival order. A
report arriving after its attempt evidence has expired cannot create
`CONSUMING`; a future or otherwise invalid timestamp outside the accepted
clock-skew bound fails closed. Bounded report loss makes the unchanged expected
reporter stale and therefore `NOT_READY`; it never shrinks the denominator or
creates false coverage. With continuous valid attempts and delivery inside all
qualified component bounds, current evidence becomes observable within
`maximumObservationDeliveryAge`.

Orchestrator consumes Controller status only and owns the shared read model used
unchanged by REST, MCP and future UI.

Required read surfaces:

- Dataset/Group status, including `NO_ACTIVE_CONSUMER`;
- swarm/binding Projection and Consumption Status;
- expected/current/loaded/selecting/attempting reporter counts and explicit
  `observed/expected` evidence coverage;
- frozen identities/digests/revision and minimum generation;
- last evidence times, stale flags and closed failure reasons.

REST and MCP expose no record values, record IDs, Redis keys, credentials or
business outcome. Evidence proves the declared data path operated; it does not
prove SUT acceptance, response, business correctness or exactly-once delivery.

## Capacity and performance

Every limit is positive and explicit. Admission atomically reserves worst-case
logical and physical use; there are no defaults or best-effort overcommit.

Required limit groups include:

- catalogue artifacts/import bytes, Definitions, Contracts and requirements;
- compiled Schema Profile nodes/depth/branches and validation throughput;
- raw/canonical record bytes and parser structure;
- PostgreSQL Dataset, provider-item/completion/generation rows, record, byte and
  idempotency capacity;
- concurrent provider writes and validation queue count/bytes/memory;
- projection count/bytes/pages, concurrent publications and Controller DB/Redis
  throughput;
- Redis current/staging/recovery memory, allocator/failover headroom,
  connections, operations and output buffers;
- worker concurrent loads, Redis read throughput, startup SLO and local
  current/next/decode/index/base/direct-buffer/GC memory;
- polling, evidence-window reporter samples/payload bytes, status queue and
  processing bounds, `maximumObservationDeliveryAge`, monotonic-ordering state
  and recovery time; and
- `maximumLoadedWorkerContinuityDuration` and the Controller recovery-time
  objective.

Admission uses measured representation sizes for the qualified PostgreSQL,
Redis and client versions:

```text
requiredAuthorityIngressPerSecond =
  sum(maximumProviderRecordWritesPerSecond(binding))

requiredAuthorityValidationUtilisationPercent =
  100 * requiredAuthorityIngressPerSecond
  / qualifiedAuthorityRecordValidationsPerSecondPerReplica

requiredAuthorityValidationMemoryPerReplica =
  concurrentValidations * maximumValidationWorkingMemory
  + maximumValidationQueuedBytes
  + admittedCompiledSchemaBytes

requiredRedisProjectionMemory(binding) =
  measuredActiveProjectionBytes
  + measuredStagingProjectionBytes
  + measuredRecoveryProjectionBytes
  + allocatorFragmentationHeadroom
  + replicationFailoverHeadroom
  + clientAndOperationalHeadroom

requiredPeakPostgresProjectionReadBytesPerSecond =
  sum(concurrentProjectionBytes) / projectionCreationSlo

requiredPeakControllerRedisWriteBytesPerSecond =
  sum(concurrentProjectionBytes) / projectionCreationSlo

requiredPeakWorkerRedisReadBytesPerSecond =
  sum(projectionBytes(binding) * applicableWorkerCount(binding))
  / workerProjectionLoadStartupSlo

activeReferenceReadOpsPerSecond =
  sum(applicableWorkerCount(binding)
      / workerActiveReferencePollInterval(binding))

requiredWorkerMemory(worker) =
  qualifiedBaseApplicationMemory
  + maximumDirectBufferMemory
  + minimumGcHeadroom
  + sum(currentLocalIndex + nextLocalIndex + decodeAndIndexOverhead)

maximumObservationDeliveryAge <=
  min(staleAfter, evidenceWindow)
```

`maximumManagedDatasetValidationUtilisationPercent` is greater than 0 and
less than 100. One authority-serving replica sustains complete admitted ingress,
including hot-replica and N-1 failover tests. Validation uses a dedicated bounded
executor and never caller-runs on control threads.

There is no wave-loading protocol. Concurrent load capacity therefore assumes
every applicable worker can restart together. Redis capacity is based on peak
measured memory because deleting keys need not immediately reduce process RSS.
`noeviction` out-of-memory blocks staging writes; it never evicts an active
projection.

The preserved performance budget compares the maximum approved topology and
projection with an equivalent preloaded-memory fixture. Managed Dataset
throughput reduction and p95/p99 SUT-attempt latency increase must each be at
most 2%. The measured span is local selection through guarded SUT network write
and must show zero Redis, PostgreSQL, Controller, Orchestrator, Scenario Manager
or credential-provider calls.

The qualification plan predeclares versions, topology, placement, data,
background load, warm-up, steady-state window, invalidation rules and confidence
method. A pilot determines sample count for one-sided 5% significance, at least
90% power and a 2% detectable regression; five valid paired runs is a floor, not
proof. Qualification uses fixed alternating paired trials and requires the
one-sided 95% confidence bound for throughput, p95 and p99 regression to be at
most 2%.

Qualification also covers projection creation, Redis page loading, maximum
worker memory/GC, every-worker restart, Controller restart, Redis failover/loss,
PostgreSQL failover, observation delivery/ordering under bounded queue pressure
and churn, maximum topology and a target-scale 24-hour soak. Smaller topology
success does not qualify a larger topology.

## Security

- Every operation authorises SUT Environment, Dataset Space, Dataset, Group,
  binding, swarm/run and operation.
- Dataset classification is fixed to `SYNTHETIC_NON_SENSITIVE`. PocketHive
  validates shape, not sensitivity; providers must not write sensitive values.
- Catalogue import accepts only trusted configured repositories and immutable
  revisions. Signatures/protected-branch policy are deployment governance, not
  runtime fallback.
- PostgreSQL roles have no table access. Reader and mutation functions are
  separately granted, bounded and audited.
- Controller has no general PostgreSQL or Redis record browsing. It is the
  trusted sole Redis writer and can invoke only the shipped projection port.
  Workers have no PostgreSQL credential and read only their binding prefix.
- Redis requires authenticated TLS where traffic crosses a trust boundary,
  deny-by-default command allowlists, exact binding key patterns and a
  digest-pinned Function catalogue. The executable ACL manifest and deployed
  Redis version are M0 contract evidence; unsupported selectors fail admission.
- Controller and worker credentials cannot administer Functions, scripts,
  ACLs, configuration or modules. `EVAL*`, `SCRIPT` and unapproved `FCALL`
  attempts fail. The Function supplies atomicity/fencing, not a second identity
  or privilege boundary.
- Secrets, source paths, Redis keys, record values and record identities are
  absent from normal logs, status, REST, MCP and future UI.
- All external requests, records, schemas, pages and status payloads have byte,
  item, time and concurrency bounds.

## Delivery plan and canonical contracts

No implementation starts until M0 establishes one executable SSOT for:

1. Dataset Definition, Schema Contract and catalogue import/evidence;
2. Dataset Requirements Document version 1 and Scenario Manager projection;
3. provider plan, Provider Item ID/Run ledger, completion barrier, Dataset
   selection and frozen Scenario Binding;
4. `MANAGED_DATASET` input/output capability and config;
5. Record Codec, WorkItem Provider/Dataset Context and provider output receipt;
6. PostgreSQL catalogue, Dataset lifecycle, provider completion, idempotency,
   generation reservation and Reader Grant API;
7. Redis key/manifest/Active Reference, exact ACL manifest and
   activation/reconciliation Function;
8. worker loading/selection/SUT-attempt guard;
9. Controller full/delta aggregate, reporter epochs, monotonic ordering,
   qualified observation-delivery bounds and evidence coverage; and
10. Orchestrator REST/MCP status and error codes.

Delivery sequence:

```text
M0 contracts
  -> M1 catalogue + PostgreSQL authority + scheduled provider fill
  -> M2 Controller Redis projection + worker local loading
  -> M3 consumer admission + evidence + failure/performance qualification
  = shared-replay MVP
```

Capability manifests remain the runtime advertisement gate. An unavailable
milestone is absent and rejected, not silently accepted.

## Acceptance and qualification matrix

| ID | Required evidence |
|---|---|
| A1 | Atomic catalogue import; exact replay is idempotent; changed content under an existing version rejects the whole import and preserves the previous catalogue. |
| A2 | Runtime binding freezes exact Definition/Contract versions and digests; `latest`, range, mismatch and automatic rebind fail. |
| A3 | Provider Item IDs are non-empty, run-scoped and stable across retry, redelivery and restart; RFC 8785 operation-key vectors are collision-unambiguous. Concurrent exact retries create one record/result and changed content conflicts. |
| A4 | All Groups become visible only in one exact-target seal transaction; no partial Dataset/Group is discoverable or projectable. |
| A5 | Completion racing an in-flight output returns `PROVIDER_RUN_NOT_DRAINED`; issuance closure, zero in-flight, record-fence closure and count evaluation occur in order. Underfill, overfill, duplicate completion and completion retry produce the documented states/reasons. |
| A6 | Partial Redis write, missing manifest, wrong count/digest/identity and stale/lower generation never replace Active Reference or worker memory. |
| A7 | Crash injection before/after staging, generation reservation, activation and PostgreSQL confirmation recovers idempotently without exposing partial data. |
| A8 | Complete Redis loss deterministically rebuilds the exact PostgreSQL revision with a generation higher than every prior reservation, including an activation that crashed before confirmation. |
| A9 | Redis failover losing recent acknowledged projection writes cannot lose authority or downgrade loaded/restarted workers. |
| A10 | `noeviction` memory exhaustion preserves current active projections, fails staging explicitly and isolates admitted bindings. |
| A11 | With Redis unavailable, loaded workers continue only for the bounded outage window; cold/restarted workers fail closed until the exact projection is restored and verified. |
| A12 | Instrumented measured traffic proves zero Redis, PostgreSQL, filesystem and control-plane/credential calls. |
| A13 | Maximum projection creation, every-worker loading/restart, worker memory/GC, recovery, maximum topology and 24-hour soak gates pass. |
| A14 | REST/MCP report load separately and expose attempt coverage as `observed/expected`. Tests cover zero expected workers, a stale report with recent attempt evidence, a current report with expired evidence and `staleAfter < evidenceWindow`; precedence produces the documented state and never false `CONSUMING`. Redis access/dequeue/count alone never suffices. |
| A15 | Existing scenarios and Scheduler, `CSV_DATASET` and `REDIS_DATASET` tests remain unchanged and pass through official ingress. |
| A16 | Schema/Profile adversarial tests reject duplicate keys, open roots, unsupported keywords, excessive cost/structure and invalid WorkItem encodings consistently at every ingress. |
| A17 | PostgreSQL and Redis cross-SUT/binding, stale fence/generation, expired grant, wrong ACL and secret-redaction tests fail closed. |
| A18 | Statistical performance trials meet the preserved 2% bounds with predeclared power/confidence and no result selection. |
| A19 | Output redelivery before closure replays one receipt; committed redelivery after closure still replays it; a new late operation fails `PROVIDER_RECORD_FENCE_CLOSED`. Crash recovery uses the durable Provider Run ledger, not local counters. |
| A20 | Several failed, abandoned and unconfirmed publications never reuse a generation. Crash after Redis activation, before PostgreSQL confirmation, followed by total Redis loss reserves a strictly higher generation. |
| A21 | Executable Redis tests reject cross-binding reads/writes, unknown or unapproved `FCALL`, all `FUNCTION` administration including load/replace, `EVAL*`, `SCRIPT` and stale-generation activation. The Controller port rejects direct Active Reference mutation; any claimed ACL-level rejection is separately proven against the deployed version. |
| A22 | Low-rate, skewed-worker and worker-churn tests produce `NOT_READY`, `AWAITING_EVIDENCE`, `PARTIAL_EVIDENCE`, `CONSUMING` and `STALE_EVIDENCE` truthfully. Replacement changes the expected epoch; old evidence cannot satisfy it. Partial coverage never becomes `CONSUMING`, and uncertain traffic distribution does not reject an otherwise valid Create Swarm. |
| A23 | For each binding, target total above `frozenProviderRun.maxMessages` returns `PROVIDER_PLAN_UNATTAINABLE` before provisioning; equality and a larger frozen bound pass. Two bindings using the same run are checked independently, and rejection leaves no Dataset, Provider Run ledger or capacity reservation. |
| A24 | One exact finite `schedulerRole` succeeds only when topology ports and fully rendered `work.out`/`work.in` queues agree through every path to the exact terminal role and `bindingRef`. Queue-suffix disagreement, absent topology, missing/wrong-direction `work` entries, unresolved queues or terminal-plan mismatch fails with `PROVIDER_SCHEDULER_TOPOLOGY_MISMATCH`; a second Scheduler reaching through only topology or only runtime queues fails as ambiguous, while one outside both paths is ignored. Missing/unknown/non-Scheduler and zero/unbounded sources retain their closed errors. A retry with a changed topology or runtime-binding digest conflicts, and every rejection creates no Dataset, Provider Run ledger or capacity reservation. |
| A25 | Admission rejects `staleAfter` or `evidenceWindow` below `maximumObservationDeliveryAge` with `DATASET_OBSERVATION_WINDOW_UNATTAINABLE`; equality passes. Delayed, expired, clock-skewed, duplicate, out-of-order and replaced-epoch reports cannot regress or falsely advance status. Continuous valid attempts become observable within the qualified bound; Controller delay, bounded report loss and worker churn preserve precedence and the expected denominator. |

Documentation is design evidence only. None of these gates is proven until its
executable contract, implementation and recorded test result exist.

## Cross-discipline review record

| Review | Result |
|---|---|
| Engineering | One authority, one immutable projection model and one local measured path. No remote measured-path storage or provider-input architecture remains. |
| QA | Lifecycle, idempotency, crash windows, downgrade, partial visibility, Redis bypass and evidence coverage are covered by A1-A25. |
| Operations | `noeviction`, peak memory, cold/loaded outage behavior, total-loss reprojection and maximum-topology/soak gates are explicit. |
| Security | Least-privilege PostgreSQL functions, trusted sole Redis writer, deny-by-default ACLs, Function non-authority and fixed non-sensitive classification are explicit. |
| RST | Requirements, canonical terms, owners, deferred triggers and acceptance evidence trace to one architecture; no mutable availability lifecycle or implicit fallback exists. |

## Remaining risks

| Severity | Risk | Required closure |
|---|---|---|
| High | Redis and worker memory amplification at maximum fan-out is unmeasured. | M2/M3 capacity measurements and maximum-topology qualification. |
| High | Controller activation/recovery Function, deployed ACL selectors and stale-fence behavior are design-only. | Executable same-slot/ACL contract plus bypass, crash and failover tests A6-A10/A21. |
| High | Provider Item ID, completion-barrier concurrency and exact-target sealing are design-only. | PostgreSQL/Scheduler transaction, redelivery and crash tests A3-A5/A19. |
| Medium | Strict Schema Profile may reject valid but complex team schemas. | M0 corpus review; expand only with bounded semantics and performance evidence. |
| Medium | One active Controller provides continuity, not Controller HA. | Publish recovery time objective and test it; design HA separately if required. |
| Medium | Scheduler-source topology/wiring agreement and Provider Run freezing are design-only. | Implement exact-role/digest freezing and dual-representation source tests A23/A24. |
| Medium | Observation delivery, monotonic ordering and churn transitions are design-only. | Qualify every delivery component, clock skew, queue pressure and precedence under A14/A22/A25. |
| Medium | Non-expiring PostgreSQL records need an operating-horizon capacity/runbook. | Admission forecast, backup/restore and explicit future retirement trigger. |
| Low | Per-swarm projections duplicate shared data. | Revisit only after measured memory pressure; do not introduce cross-swarm cache implicitly. |

## Primary references

- [PocketHive Architecture](../ARCHITECTURE.md)
- [Scenario Contract](../scenarios/SCENARIO_CONTRACT.md)
- [Scenario Manager Bundle REST](../scenarios/SCENARIO_MANAGER_BUNDLE_REST.md)
- [Worker Capability Catalogue](../architecture/workerCapabilities.md)
- [SUT, Dataset Space and Simulation Program proposal](../architecture/sut-dataset-simulation-model.md)
- [Redis key eviction and `noeviction`](https://redis.io/docs/latest/develop/reference/eviction/)
- [Redis replication and acknowledged-write loss](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)
- [Redis persistence trade-offs](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)
- [Redis ACL key permissions](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/)
- [Redis `FCALL` key declaration and invocation](https://redis.io/docs/latest/commands/fcall/)
- [Redis programmability and Function atomicity](https://redis.io/docs/latest/develop/programmability/)
- [Redis Cluster hash tags](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)
- [Redis Functions and atomic execution](https://redis.io/docs/latest/develop/programmability/functions-intro/)
- [Redis pipelining](https://redis.io/docs/latest/develop/using-commands/pipelining/)
- [Redis sorted-set complexity](https://redis.io/docs/latest/develop/data-types/sorted-sets/)
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL unique constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)
- [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [PostgreSQL `SECURITY DEFINER` guidance](https://www.postgresql.org/docs/current/sql-createfunction.html)
- [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12)
- [JSON Schema validation security considerations](https://json-schema.org/draft/2020-12/json-schema-validation)
- [RFC 8785 JSON Canonicalization Scheme](https://www.rfc-editor.org/rfc/rfc8785)
- [RFC 9110 idempotent retry semantics](https://www.rfc-editor.org/rfc/rfc9110)
