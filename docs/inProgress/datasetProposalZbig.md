# Dataset Proposal — Zbig

> **Review disposition (2026-08-05):** non-normative design input. The
> [Managed Dataset Shared-Replay MVP Specification](managed-test-data-lifecycle-generic-spec.md)
> is the proposed canonical design. The original proposal below is preserved
> unchanged for traceability; its status, MVP boundary and open decisions are
> historical and must not be used as implementation requirements. The
> PocketHive response at the end records what was adopted, rejected or
> deferred.

Status: in progress; draft proposal, decision and canonical-contract approval pending

## Goal

Dataset is a durable, generic test-data registry **for one SUT**. Multiple
independent swarms can read records from it and explicitly update their current
operational metadata.

```text
Onboarding swarm
  -> creates a customer / account / card in the SUT
  -> Output DATASET stores the record as available

Transaction swarm
  -> Input DATASET selects a card
  -> executes a transaction against the SUT
  -> Output DATASET adds a dirty or noFunds tag

Reconditioning swarm
  -> Input DATASET selects records tagged dirty
  -> checks or tops up the account against the SUT
  -> Output DATASET removes dirty and makes the record available again
```

Dataset does not interpret `dirty`, `noFunds`, or `flowStep`. Its responsibility
is durability, structure validation, record selection, tags, optional leases,
and atomic operations. The business meaning of tags belongs to the scenario.

## Requirements

- Every Dataset belongs to one SUT; records are never selected across SUTs.
- All records created by onboarding remain in the durable inventory, regardless
  of whether they are dirty, currently in use, or absent from Redis.
- A record can have any Dataset-defined shape: a number, a JSON object, a JSON
  array, or a customer-account-card structure with references.
- Different tests can select different projections or subsets of the same data.
- Multiple swarms can deliberately use the same record; exclusivity is
  available only to flows that require it.
- A flow can add, remove, or update declared tags, such as `dirty`, `noFunds`,
  `flow`, and `flowStep`.
- Redis is an optional distribution channel; a Redis restart must not lose the
  inventory or the authoritative tags and leases.

## Dataset Definition

A Dataset Definition is a versioned definition for exactly one `sutId`. It
contains at least:

- `datasetId` and name;
- immutable `sutId`;
- versioned `recordSchema`;
- versioned `tagSchema`;
- explicitly allowed read modes: `SHARED` and/or `EXCLUSIVE`;
- explicitly configured fields that are eligible for selection or indexing;
- data classification and access requirements.

A data-contract change creates a new version. There is no implicit mapping or
fallback between versions: an Input requiring an unsupported version fails, and
a migration is a separate, explicitly configured flow.

## Record: inventory, tags, and lease

Dataset has no built-in `Card`, `Account`, or `Customer` type. A record has a
generic envelope:

```text
recordId
datasetId
schemaVersion
payload                any value valid under recordSchema
tags                   dynamic values valid under tagSchema
recordRevision
createdAt / provenance
optional active lease
```

A payload written by the basic `CREATE` operation is an immutable inventory
entry. If a flow needs to change business data, it uses explicit
`PAYLOAD_REPLACE` with `recordRevision` checking and provenance. An ordinary
read never mutates the payload.

Tags are not a global state machine. A record can have several tags at once:

```yaml
dirty: true
noFunds: false
flow: onboarding
flowStep: 1
productCode: GOLD
```

`tagSchema` declares the allowed keys, types, and values so typos or unknown
meanings cannot silently create new behaviour.

A reservation is not a tag. It is a technical Dataset lease with an owner,
token, and expiry time. The UI may present it like a tag, but only an atomic
Dataset operation can create, renew, or release it.

## Source of truth

PostgreSQL is the sole authoritative store for:

- Dataset Definitions and their versions;
- every record, including dirty, reserved, and disabled records;
- current tags, revisions, and leases;
- transition and metadata history;
- outbox entries for optional projections to other adapters.

A pool such as “cards ready for transactions” is a query/view over the durable
inventory and tags, not the only copy of the data. All cards for a `sutId` are
therefore discoverable even after a Redis restart.

## Worker I/O contracts

M0 defines canonical Worker I/O values and their closed, explicit settings
schemas. There are no undocumented headers or default configuration paths.

### Output DATASET

Output performs one explicit operation:

| Operation | Meaning |
|---|---|
| `CREATE` | Validates the payload and appends a record to the inventory. |
| `TAG_PATCH` | Atomically adds, removes, or sets allowed tags. |
| `PAYLOAD_REPLACE` | Replaces payload after explicit revision checking. |
| `RELEASE_LEASE` | Finishes the caller's reservation, optionally with `TAG_PATCH`. |

The work result carries `recordId`, `recordRevision`, and a lease token when
Input created one. An operation requiring exclusivity fails without a matching
token. A shared operation uses an atomic tag patch or required revision; a
conflict is explicit and is never silently overwritten.

### Input DATASET

Input selects records from PostgreSQL through an explicit selector valid for
the record/tag schema. Selection can use `sutId`, `datasetId`, allowed payload
fields, and tags declared selectable.

| Mode | Behaviour |
|---|---|
| `SHARED` | Returns matching records without a reservation. Multiple swarms can receive the same card concurrently. |
| `EXCLUSIVE` | Atomically selects a record and creates a lease. Another exclusive consumer cannot receive it until the lease is released or expires. |

There is no automatic switch between `EXCLUSIVE` and `SHARED`. Batch size,
lease duration, in-flight limits, and selector are required adapter-specific
settings.

Examples:

```text
onboarding worker -> Output DATASET(CREATE, dataset=sut-a.daily-cards)

Input DATASET(SHARED, selector=productCode:GOLD)
  -> transaction worker
  -> Output DATASET(TAG_PATCH, add=dirty)

Input DATASET(EXCLUSIVE, selector=dirty:true)
  -> reconditioning worker
  -> Output DATASET(RELEASE_LEASE, remove=dirty, set=noFunds:false)
```

## Optional Redis projection

A swarm can use the explicit connection:

```text
Input DATASET -> Output REDIS
```

Redis is not authoritative for records, tags, or leases. A successful
PostgreSQL mutation writes an outbox entry in the same transaction, and a
projector delivers it to Redis. A Redis restart may empty a list, but it does
not lose records: the projector rebuilds the list from the durable Dataset.

Redis can receive `recordId` and lease token, or an exactly defined payload
projection. The choice is part of explicit configuration and data
classification. A duplicate or empty Redis list never silently changes the
durable record state.

Any future product API to “publish records to a Redis list” uses this same
Dataset + outbox model; it does not create a second path that bypasses
validation, authorisation, or provenance.

## Performance

The initial variant uses PostgreSQL-backed `Input DATASET` directly. A target
in the order of 1,000 records/s does not by itself require Redis. Reads should
be batched, use short transactions, and have indexes for `sutId`, `datasetId`,
selectable tags, declared selector fields, and active leases.

Throughput must be measured with the target record model, indexes, number of
concurrent swarms, and infrastructure. Load tests use the public Dataset API
or official Input/Output Dataset path, not a direct PostgreSQL port. Redis is
added only after a concrete need is demonstrated.

## MVP boundary

MVP includes SUT-scoped Definitions, versioned schemas, a durable inventory,
`CREATE`, `SHARED`/`EXCLUSIVE`, tags, leases, filtering, history, and a
PostgreSQL benchmark.

MVP excludes automatic provider swarms, refill, grants, receipts, expiry
cohorts, automatic data renewal, inference from SUT responses, and default
Redis projection.

Dataset does not call the SUT or interpret a test result. A worker expresses a
flow result through Output DATASET or the Dataset API.

## Difference from Managed Test Data MVP Specification

This document proposes a different model from
`managed-test-data-lifecycle-generic-spec.md`. That model assumes shared,
immutable records replayed without checkout or mutation after use. This model
has a durable inventory, explicit tag mutation, and optional reservations.
One architectural model must be chosen before implementation; they must not be
combined as alternative semantics of the same adapter.

## Open decisions before M0

1. Canonical `recordSchema` and `tagSchema` format and versioning rules.
2. Whether MVP supports JSON only or explicitly typed binary payloads too.
3. Revision-history retention and `PAYLOAD_REPLACE` semantics.
4. Dataset/SUT/scenario-role/run-scope authorisation model.
5. Batch, lease, record-size, and selector limits.
6. Whether Redis projection is needed after the PostgreSQL benchmark.
7. REST, Worker I/O, capability-manifest, and observability contracts for MVP.

## PocketHive review response

The original proposal above remains non-normative and unchanged. The
[Managed Dataset Shared-Replay MVP Specification](managed-test-data-lifecycle-generic-spec.md)
supersedes its architecture and records which needs are covered now, bounded,
rejected or deferred.

### Original-need traceability

`Need coverage` describes the need, not the proposed mechanism. A rejected
mechanism may still have full coverage through a safer design.

| Original need | Need coverage | Proposed mechanism | Canonical MVP response | Evidence or decision trigger |
|---|---|---|---|---|
| Keep records within one SUT | `FULL` | `ADOPTED` | Scenario Binding freezes one SUT Environment and Dataset Space. | Cross-SUT discovery/admission fails. |
| Survive Redis loss | `FULL` | `BOUNDED` | PostgreSQL owns catalogue and records; Redis is a rebuildable per-swarm projection. | Total-loss reprojection and failover-loss tests. |
| Domain-neutral record shape | `PARTIAL` | `BOUNDED` | One closed, bounded JSON object under exact composed schemas. | New root types need end-to-end qualification. |
| Stable subsets | `FULL` | `BOUNDED` | Frozen Groups replace arbitrary selectors. | Exact Group selection tests. |
| Concurrent shared use | `FULL` | `ADOPTED` | `REPLAY + SHARED` records are immutable, non-expiring and reusable. | Maximum-fan-out tests. |
| Temporary exclusivity | `NONE` in MVP | `DEFERRED` | No lease or used lifecycle. PostgreSQL remains the future lease authority. | Approved flow must require temporary unavailability. |
| Mutable dirty/ready state | `NONE` in MVP | `DEFERRED` | Shared replay has no state or View. | Approved cross-swarm workflow cannot use independent immutable output. |
| Prevent tag typos | `FULL` for MVP | `REJECTED` | Free-form tag bags do not exist; the immutable Record Schema is closed. | Future workflow requires a separate typed state contract. |
| Redis distribution | `FULL` | `BOUNDED` | Trusted Controller writes an immutable binding-scoped projection; the Function provides atomic fencing and workers load local memory. | ACL/Function bypass, partial, stale, OOM and loss tests. |
| Stable identity, name and versions | `FULL` | `ADOPTED` | Required name, opaque IDs, exact SemVer/digest and transactional catalogue publication. | Changed-content import fails. |
| One durable source of truth | `FULL` | `BOUNDED` | Git is authoring history; PostgreSQL is published runtime authority. | Authority/owner anti-drift tests. |
| Explicit worker I/O | `FULL` | `BOUNDED` | Normal `SCHEDULER` provider input, terminal `MANAGED_DATASET CREATE_RECORD`, explicit consumer input. | M0 capability/config tests. |
| Atomic creation | `FULL` | `BOUNDED` | Stable Provider Item IDs, per-binding feasibility, a drained completion barrier and exact-target seal transaction. Generic mutation is absent. | Unattainable-plan, redelivery, in-flight race, duplicate completion, underfill and overfill tests. |
| Practical high throughput | `PARTIAL` | `BOUNDED` | Workers select from verified local memory; no remote measured-path calls. | 2% performance gates and 24-hour soak. |
| Provider-created supply | `FULL` for initial fill | `BOUNDED` | One finite scheduled provider run closes issuance, drains issued items and creates one sealed revision. | Fill, completion-race, crash and failure tests. |
| Renewal/refill | `NONE` in MVP | `DEFERRED` | Shared records do not expire or deplete. | Approved expiring/depleting or mutable supply. |
| Do not infer SUT truth | `FULL` | `ADOPTED` | Scenario decides whether to emit a record; Dataset never interprets SUT output. | Missing/ambiguous output creates nothing. |
| Bounded status without value exposure | `FULL` | `BOUNDED` | REST/MCP separate reporter freshness, load and guarded-attempt freshness and report coverage as `observed/expected`. | Low/skewed traffic remains observable without false admission failure or false `CONSUMING`. |
| Retain inventory | `PARTIAL` | `BOUNDED` | MVP records are non-expiring; admission funds the declared storage horizon. | Retirement/reclamation needs a separate safety contract. |

### Adopted

| Proposal strength | Canonical outcome |
|---|---|
| Durable shared inventory | PostgreSQL owns immutable records for many consumer swarms. |
| One SUT scope | Scenario Binding freezes SUT Environment and Dataset Space. |
| Name plus stable identity | Dataset name is retained beside opaque runtime identity. |
| Versioned generic schema | Git-authored exact Definitions/Contracts publish immutably to PostgreSQL. |
| Redis distribution | Redis is now the immutable per-swarm projection, never authority. |
| Local hot path | Workers verify the projection and select only from local memory. |
| Explicit configuration | Dataset, Group, adapter, version, digest and generation never fall back. |

### Rejected for the MVP

| Rejected mechanism | Reason | Replacement |
|---|---|---|
| Redis `source/leased/used` queues | Shared records are reusable; list movement cannot prove a SUT attempt. | Immutable projection plus local selection/evidence. |
| Redis lease authority | Failover/loss could violate exclusivity. | Future PostgreSQL lease/fencing authority. |
| Workers reading PostgreSQL | Adds credentials and database calls to worker behavior. | Controller reads; workers load Redis in the background. |
| Direct PostgreSQL table export | Weakens grant/fence and least privilege. | Exact bounded reader function. |
| Free-form tags, selectors and queries | Unbounded semantics, indexing and concurrency. | Closed records and frozen Groups. |
| `PAYLOAD_REPLACE` | Breaks immutable revision/digest identity. | New immutable Dataset output when approved. |
| Source-less provider or `MANAGED_DATASET_PROVIDER` MVP input | Duplicates Scheduler and requires refill protocol. | Normal Scheduler input and terminal Managed Dataset output. |
| Refill for shared records | Non-expiring reusable records do not deplete. | One exact-target initial fill. |
| Inferred SUT outcomes | Transport results cannot prove business intent. | Scenario explicitly chooses record output. |
| Shared filesystem snapshots | Adds mounts, markers and cleanup state machines. | Binding-scoped Redis projection. |

### Deferred

| Capability | Reconsider when |
|---|---|
| `EXCLUSIVE_LEASE` and lease renewal | A qualified scenario requires temporary record unavailability. |
| Mutable state and Views | A cross-swarm workflow needs durable state rather than independent output records. |
| Managed Dataset provider input and refill | Approved records expire/deplete or mutable workflow requires replenishment. |
| Additional Managed Dataset import/derivation sources | A named ingestion/chaining use case cannot use existing adapters and scheduled output. |
| Dataset retirement, purge or reclamation | The initial-fill storage horizon is insufficient and active-binding/evidence safety is defined. |
| Record browsing and audit history | A diagnostic/audit need defines privacy, authorisation, capacity and retention. |
| Cross-swarm projection reuse | Measured per-swarm Redis memory exceeds approved bounds. |

Deferred does not mean approved. Each item needs one canonical contract,
capacity model, failure behavior and qualification evidence.
