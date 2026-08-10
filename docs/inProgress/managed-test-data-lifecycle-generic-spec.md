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
5. Provider records are unavailable until every configured Group reaches its
   exact target and PostgreSQL atomically seals revision 1.
6. Orchestrator provisions, authorises, fences and reports. It never proxies
   record bytes or selects records for measured traffic.
7. Swarm Controller creates and reconciles Redis projections. Workers never
   access PostgreSQL.
8. A Redis projection is immutable and invisible until its manifest and records
   are complete and its Active Projection Reference is atomically advanced.
9. Workers load in the background, verify the complete projection and atomically
   replace local memory. Traffic makes no Redis, PostgreSQL, Controller,
   Orchestrator, Scenario Manager or credential-provider call.
10. `REPLAY + SHARED` never makes a record unavailable after use. Redis access,
    dequeue or movement is not consumption evidence.
11. Missing, unknown, stale or incompatible configuration fails explicitly.
    PocketHive never substitutes another Dataset, Group, adapter, source or
    revision.
12. Existing `SCHEDULER`, `CSV_DATASET` and `REDIS_DATASET` contracts and
    behavior remain unchanged.

## MVP boundary

### Included

- versioned Dataset Definitions and composed Schema Contracts;
- transactional publication into a PostgreSQL catalogue;
- optional Scenario Bundle `datasets/requirements.yaml` version 1;
- one scheduled, bounded provider fill through terminal
  `MANAGED_DATASET CREATE_RECORD`;
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
| Consumer Binding | Frozen selection of one sealed Dataset Instance and Group. |
| Authority Revision | Immutable PostgreSQL record set sealed for a Dataset. MVP has revision 1 only. |
| Redis Projection | Rebuildable, binding-scoped copy of one Group at one Authority Revision. |
| Activation Generation | Orchestrator-owned monotonic number for projection activation/reprojection. It is not an Authority Revision. |
| Dataset Context | SDK-owned WorkItem context correlating the frozen binding, local selection and SUT-attempt guard. |

Dataset, Group and projection identities are opaque. Dataset `name` is required
and human-readable but is never identity, secret or selection fallback.

## Ownership

| Owner | Responsibility | Must not do |
|---|---|---|
| Git/repository | Authoring review and source history | Store runtime records or serve admitted traffic |
| Scenario Manager | Validate complete catalogue imports and Scenario Bundles; publish one transaction | Mutate runtime records or perform data-plane work |
| PostgreSQL | Published catalogue, Dataset identity, records, Groups, revisions, idempotency and future state/leases | Serve workers directly |
| Orchestrator | Provision, admit, reserve, authorise, fence, assign generations and own the status read model | Proxy record bytes or select measured-path records |
| Swarm Controller | Read exact sealed revisions, create/reconcile Redis projections and aggregate worker evidence | Become record authority or measured-path selector |
| Redis | Hold immutable, per-swarm runtime projections | Become durable authority, lease authority or consumption evidence |
| Worker SDK | Verify/load projections, select locally, preserve Dataset Context and guard SUT attempts | Query PostgreSQL or use remote data in measured traffic |
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

The provider Create Swarm request supplies one explicit plan for each terminal
`MANAGED_DATASET` output:

```yaml
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

The Orchestrator renders Group keys once from the frozen Provider Scenario
Binding, validates positive targets and atomically reserves the sum of all
targets against PostgreSQL record/byte/idempotency and validation capacity.
The admitted maximum stored record count for this Dataset equals that sum in
the MVP.

Provisioning is idempotent on
`providerSwarmId + providerRunId + providerBindingRef`. Exact replay returns
the same `datasetId`; changed content conflicts. PostgreSQL creates the
Dataset and every Group in `BUILDING` before the provider starts.

The provider has a normal PocketHive WorkInput, initially `SCHEDULER`.
`output-only` means Managed Dataset is only its terminal WorkOutput; it never
means a source-less swarm.

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

### CREATE_RECORD

Every write:

1. resolves the authenticated provider run and frozen binding;
2. requires a stable non-empty WorkItem `messageId`;
3. derives
   `operationKey = sha256(providerRunId, bindingRef, messageId, outputOrdinal)`;
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

When the finite provider run ends, Orchestrator invokes one fenced completion:

- if every Group count equals its exact target, one PostgreSQL transaction
  freezes record order and Group digests, assigns Authority Revision 1 and moves
  the Dataset to `SEALED`;
- if a Group is under target or the provider failed/timed out, the Dataset moves
  to `FAILED` with a closed reason and remains unavailable; and
- a unique write above a Group target or total admitted capacity fails and
  cannot be hidden by completion.

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
- worker loading, polling, memory and evidence limits.

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
      pipelineLagTolerance: PT30S
```

MVP supports only explicit `ROUND_ROBIN`. Each worker has an independent local
cursor; duplicate concurrent use is valid. There is no checkout, pop, source,
lease or used collection.

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

The Controller has a `projection-writer` credential: prefix-restricted mutation
commands and the closed activation/reconciliation functions only. It has no
general record retrieval, key discovery or administrative commands. The closed
functions may inspect only named metadata/cardinality and return bounded
verification results.

Workers have binding-scoped `projection-reader` credentials with only the
exact Active Reference, manifest and bounded sorted-set read commands. They have
no mutation, discovery, scripting, publication or cross-binding access.
Credentials are short-lived, injected privately and absent from status/logs.

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
  reconciliation function. Exact active state is idempotently confirmed;
  uncertainty never advances or deletes it.
- Redis failover loses recent writes: workers reject a missing, partial or older
  generation. Loaded workers keep their local generation; cold workers remain
  unready. Controller reconciles from PostgreSQL.
- Complete Redis loss: Orchestrator issues a generation greater than every
  confirmed generation and Controller deterministically reprojects the frozen
  PostgreSQL revision.
- Controller outage: already-loaded workers may continue for the admitted
  `maximumLoadedWorkerContinuityDuration`. A local monotonic deadline stops new
  dispatch after that bound unless fresh Controller reconciliation extends it.
  New/restarted workers remain unready.

Orchestrator records every publication/projection ID before Redis writes, so
recovery never scans Redis. After a confirmed activation, Controller may remove
only named non-active, non-staging derivative keys after atomically rechecking
the Active Reference. Cleanup failure consumes reserved derivative capacity and
may block another publication; it cannot delete PostgreSQL authority or the
active projection. There is no filesystem marker, deletion acknowledgement or
authority-evidence chain.

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

`CONSUMING` requires fresh matching evidence from every enabled applicable
worker for the frozen Dataset/Group/schema/revision/projection generation, at
least one local selection and its correlated guarded SUT attempt within the
observation window. A Redis read, record count, projection activation or local
selection alone cannot produce `CONSUMING`.

Workers report to Swarm Controller. Controller `status-full` retains bounded
worker identities and epochs. `status-delta` contains only per-binding counts,
reporter-set digest, freshness watermark, minimum loaded generation and
aggregate counters. A reporter-set/epoch change requires a new full snapshot.
Orchestrator consumes Controller status only and owns the shared read model used
unchanged by REST, MCP and future UI.

Required read surfaces:

- Dataset/Group status, including `NO_ACTIVE_CONSUMER`;
- swarm/binding Projection and Consumption Status;
- expected/loaded/selecting/attempting reporter counts;
- frozen identities/digests/revision and minimum generation;
- last evidence times, stale flags and closed failure reasons.

REST and MCP expose no record values, record IDs, Redis keys, credentials or
business outcome. Evidence proves the declared data path operated; it does not
prove SUT acceptance, business correctness or exactly-once delivery.

## Capacity and performance

Every limit is positive and explicit. Admission atomically reserves worst-case
logical and physical use; there are no defaults or best-effort overcommit.

Required limit groups include:

- catalogue artifacts/import bytes, Definitions, Contracts and requirements;
- compiled Schema Profile nodes/depth/branches and validation throughput;
- raw/canonical record bytes and parser structure;
- PostgreSQL Dataset, record, byte and idempotency capacity;
- concurrent provider writes and validation queue count/bytes/memory;
- projection count/bytes/pages, concurrent publications and Controller DB/Redis
  throughput;
- Redis current/staging/recovery memory, allocator/failover headroom,
  connections, operations and output buffers;
- worker concurrent loads, Redis read throughput, startup SLO and local
  current/next/decode/index/base/direct-buffer/GC memory;
- polling, status samples/reporters/payload bytes and recovery time; and
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
PostgreSQL failover, maximum topology and a target-scale 24-hour soak. Smaller
topology success does not qualify a larger topology.

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
- Controller has no general PostgreSQL or Redis record browsing. Workers have no
  PostgreSQL credential and read only their binding prefix.
- Redis requires authenticated TLS where traffic crosses a trust boundary,
  explicit command allowlists, read/write key permissions and disabled
  dangerous/admin commands.
- Secrets, source paths, Redis keys, record values and record identities are
  absent from normal logs, status, REST, MCP and future UI.
- All external requests, records, schemas, pages and status payloads have byte,
  item, time and concurrency bounds.

## Delivery plan and canonical contracts

No implementation starts until M0 establishes one executable SSOT for:

1. Dataset Definition, Schema Contract and catalogue import/evidence;
2. Dataset Requirements Document version 1 and Scenario Manager projection;
3. provider plan, Dataset selection and frozen Scenario Binding;
4. `MANAGED_DATASET` input/output capability and config;
5. Record Codec, WorkItem Dataset Context and provider output receipt;
6. PostgreSQL catalogue, Dataset lifecycle, idempotency and Reader Grant API;
7. Redis key/manifest/Active Reference and activation/reconciliation function;
8. worker loading/selection/SUT-attempt guard;
9. Controller full/delta aggregate; and
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
| A3 | Concurrent exact provider retries create one record/result; changed content conflicts. |
| A4 | All Groups become visible only in one exact-target seal transaction; no partial Dataset/Group is discoverable or projectable. |
| A5 | Underfill, overfill, duplicate retry, provider failure and completion retry produce the documented closed states/reasons. |
| A6 | Partial Redis write, missing manifest, wrong count/digest/identity and stale/lower generation never replace Active Reference or worker memory. |
| A7 | Crash injection before/after staging, activation and Orchestrator confirmation recovers idempotently without exposing partial data. |
| A8 | Complete Redis loss deterministically rebuilds the exact PostgreSQL revision with a higher generation. |
| A9 | Redis failover losing recent acknowledged projection writes cannot lose authority or downgrade loaded/restarted workers. |
| A10 | `noeviction` memory exhaustion preserves current active projections, fails staging explicitly and isolates admitted bindings. |
| A11 | Loaded workers continue for the bounded outage window; cold/restarted workers stay unready until the exact projection is restored. |
| A12 | Instrumented measured traffic proves zero Redis, PostgreSQL and control-plane/credential calls. |
| A13 | Maximum projection creation, every-worker loading/restart, worker memory/GC, recovery, maximum topology and 24-hour soak gates pass. |
| A14 | REST/MCP `CONSUMING` requires matching worker load, local selection and guarded SUT-attempt evidence; Redis access/dequeue/count alone never suffices. |
| A15 | Existing scenarios and Scheduler, `CSV_DATASET` and `REDIS_DATASET` tests remain unchanged and pass through official ingress. |
| A16 | Schema/Profile adversarial tests reject duplicate keys, open roots, unsupported keywords, excessive cost/structure and invalid WorkItem encodings consistently at every ingress. |
| A17 | PostgreSQL and Redis cross-SUT/binding, stale fence, expired grant, wrong ACL and secret-redaction tests fail closed. |
| A18 | Statistical performance trials meet the preserved 2% bounds with predeclared power/confidence and no result selection. |

Documentation is design evidence only. None of these gates is proven until its
executable contract, implementation and recorded test result exist.

## Cross-discipline review record

| Review | Result |
|---|---|
| Engineering | One authority, one immutable projection model and one local measured path. No filesystem or provider-input architecture remains. |
| QA | Lifecycle, idempotency, crash windows, downgrade, partial visibility and evidence are covered by A1-A18. |
| Operations | `noeviction`, peak memory, cold/loaded outage behavior, total-loss reprojection and maximum-topology/soak gates are explicit. |
| Security | Least-privilege PostgreSQL function, prefix/command-restricted Redis roles, closed status and fixed non-sensitive classification are explicit. |
| RST | Requirements, canonical terms, owners, deferred triggers and acceptance evidence trace to one architecture; no queue/used lifecycle or implicit fallback exists. |

## Remaining risks

| Severity | Risk | Required closure |
|---|---|---|
| High | Redis and worker memory amplification at maximum fan-out is unmeasured. | M2/M3 capacity measurements and maximum-topology qualification. |
| High | Controller activation/recovery function and stale-fence behavior are design-only. | Executable same-slot contract plus crash/failover tests A6-A10. |
| High | Provider output concurrency and exact-target sealing are design-only. | PostgreSQL transaction/idempotency tests A3-A5. |
| Medium | Strict Schema Profile may reject valid but complex team schemas. | M0 corpus review; expand only with bounded semantics and performance evidence. |
| Medium | One active Controller provides continuity, not Controller HA. | Publish recovery time objective and test it; design HA separately if required. |
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
