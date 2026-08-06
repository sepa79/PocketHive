# Dataset Proposal — Zbig

> **Review disposition (2026-08-05):** non-normative design input. The
> [Managed Test Data Release 1 Specification](managed-test-data-lifecycle-generic-spec.md)
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

The original comparison above is superseded. The canonical design now supports
`REPLAY + SHARED`, `REPLAY + EXCLUSIVE_LEASE` and bounded mutable
`WORKFLOW + EXCLUSIVE_LEASE`. It adopts the useful durability and sharing
ideas without adopting open-ended tag/query/database semantics.

### Original-need traceability

This table interprets each original concern independently from the mechanism
proposed above. `Need coverage` is `FULL`, `PARTIAL` or `NONE`. `Proposed
mechanism` is `ADOPTED`, `BOUNDED`, `DEFERRED` or `REJECTED`; a rejected
mechanism may still have full need coverage. The canonical specification remains
authoritative.

| Original pain point | Interpreted need | Need coverage | Proposed mechanism | Canonical response | Acceptance evidence or future decision trigger |
|---|---|---|---|---|---|
| Records must not cross SUTs | Isolate reusable data by target environment | `FULL` | `ADOPTED` | Scenario Binding freezes one SUT Environment and Dataset Space. | Cross-SUT discovery/admission tests fail closed. |
| Created records must survive dirty/in-use state and Redis loss | Keep one durable inventory independent of delivery adapters | `FULL` | `BOUNDED` | PostgreSQL owns non-expiring Managed Dataset records, state and leases; snapshots and Redis remain separate concerns. | Restart, finite-import and authority-recovery tests preserve committed records. |
| Records may have arbitrary shapes | Represent domain-neutral structured records | `PARTIAL` | `BOUNDED` | Release 1 accepts one closed, bounded, non-null JSON object per record. Other JSON roots and binary are excluded. | Reconsider only with a concrete need plus schema, source, snapshot, WorkItem and pipeline qualification for every new root type. |
| Tests need different subsets or projections | Reuse the same canonical data for different flows | `FULL` | `BOUNDED` | Groups provide frozen record partitions; workflow Views provide fixed state subsets; Create Swarm selects one exact Dataset, Group and optional View. Normal scenario processing may shape its downstream payload. | Selection tests cover Groups/Views. Dataset projection expressions remain prohibited. |
| Many swarms may use one record; some flows require exclusivity | Support concurrent reuse and temporary unavailability explicitly | `FULL` | `ADOPTED` | `REPLAY + SHARED` permits concurrent immutable reuse. `REPLAY` or `WORKFLOW + EXCLUSIVE_LEASE` permits one active lease. | Shared fan-out and duplicate-lease concurrency tests pass. |
| Flows need durable operational metadata such as dirty/ready | Move records through declared operational states | `FULL` | `BOUNDED` | `WORKFLOW` uses typed Record State, fixed Views and declared transitions under an exclusive lease. Shared replay has no mutable state. | Transition tests cover the declared workflow; concurrent shared mutation is rejected. |
| Free-form tags should remain schema-valid | Prevent typos and undeclared state changes | `FULL` | `REJECTED` | No tag bag exists. A closed state schema plus allowed paths and transitions provides the bounded equivalent. | Unknown paths, invalid states and undeclared transitions fail atomically. |
| Redis restart must not lose authority | Keep durability outside a volatile list | `FULL` | `BOUNDED` | PostgreSQL is authoritative for Managed Dataset; the existing Redis Dataset remains unchanged. | Redis import/restart tests never treat the live list as Managed Dataset authority. |
| Redis should optionally distribute Managed Dataset records | Publish to an external Redis projection | `NONE` | `DEFERRED` | Release 1 distributes snapshots through qualified shared storage and worker memory; no outbox/projection is added. | Reconsider when a named consumer cannot use Managed Dataset or an existing explicit Redis path. |
| Definitions need stable identity, name and versions | Make contracts discoverable and immutable | `FULL` | `ADOPTED` | Dataset Definition, required name, opaque runtime id and exact schema-contract versions are frozen without fallback. | Authoring and compatibility tests reject changed or unsupported versions. |
| Read/allocation modes must be explicit | Prevent silent switching between shared and exclusive behaviour | `FULL` | `ADOPTED` | Profile and allocation are closed, frozen and capability-gated. | Unsupported or mismatched Profile/allocation fails admission. |
| Selectable/indexed fields should be declared | Bound selection semantics and cost | `FULL` | `REJECTED` | Dataset-level field selectors and queries are excluded; Groups and fixed workflow Views are the bounded substitutes. | Admission accepts only an exact Dataset, Group and optional View. |
| Data classification and access must be controlled | Prevent unsafe data exposure | `PARTIAL` | `BOUNDED` | Release 1 fixes `SYNTHETIC_NON_SENSITIVE`, has no per-Dataset override and authorises SUT, Dataset, Group, binding, run and operation. PocketHive does not infer sensitivity from values. | Schemas reject classification fields and surfaces expose no values; reconsider policy levels only with storage, log, backup and inspection controls. |
| Dataset must remain domain-neutral | Avoid built-in customer/card/business fields | `FULL` | `ADOPTED` | Record, state and Group schemas own domain fields; PocketHive defines none. | Schema tests accept arbitrary declared object fields within the closed root contract. |
| Ordinary reads must not mutate payload | Preserve stable reusable content | `FULL` | `ADOPTED` | Record payload is immutable and workers load the canonical record from a verified snapshot. | Digest and replay tests prove unchanged canonical records. |
| Business data may need replacement | Change an existing record payload | `NONE` | `REJECTED` | `PAYLOAD_REPLACE` is excluded because it breaks immutable snapshot identity. Derivation creates new independent records. | Reconsider only through a separate versioned migration/derivation design. |
| Reservations must be separate from business metadata | Provide safe technical exclusivity | `FULL` | `ADOPTED` | Record Lease is authority-owned state with owner, token and expiry, never payload or Record State. | Ownership, expiry, conflict and recovery tests pass. |
| A long-running reservation may need renewal | Extend an exclusive claim beyond its admitted operation bound | `PARTIAL` | `REJECTED` | Release 1 has no lease renewal. Admission requires the fixed lease duration to cover the declared operation, completion and clock bounds. | Reconsider only if a qualified workflow cannot fit a bounded lease and defines renewal fencing, failure and capacity behaviour. |
| One durable source of truth is required | Keep runtime records and mutations restart-safe | `FULL` | `BOUNDED` | PostgreSQL owns Managed Dataset runtime data; Scenario Manager separately owns mounted authoring contracts. | Authority and authoring-owner tests reject duplicate ownership. |
| Transition and metadata history should be retained | Reconstruct every past record-state change | `NONE` | `DEFERRED` | Release 1 retains current typed state/`stateRevision`, correctness idempotency/provenance and aggregate operational evidence, not an inspectable per-record transition log. | Reconsider when a proven diagnostic or audit case defines retention, authorisation, capacity and inspection. |
| All records should be discoverable | Diagnose inventory and operational health | `PARTIAL` | `BOUNDED` | REST/UI/MCP expose bounded Dataset/Group counts, availability, publication and consumption status. Record browsing, value search and record-level inspection are excluded. | Reconsider when bounded status cannot answer a concrete QA/operations diagnosis and a safe paginated inspection contract is approved. |
| Worker I/O must be explicit | Keep one supported pipeline without hidden defaults | `FULL` | `BOUNDED` | Managed Dataset adds explicit adapters to the existing WorkInput/WorkOutput pipeline and SDK-owned typed Context. | M0 contract and capability tests reject missing/unknown adapter settings. |
| Create, mutate and release must be atomic | Avoid partial state or lease outcomes | `PARTIAL` | `BOUNDED` | Record creation, declared workflow transition/release and bounded Derivation are atomic; generic tag patch and payload replacement are rejected. | Transaction, idempotency and failure-injection tests prove all-or-none outcomes. |
| PostgreSQL selectors should filter records dynamically | Select eligible records predictably at bounded cost | `FULL` | `REJECTED` | Workers select locally from one frozen Group; workflow claims use one fixed materialised View. | No API or adapter accepts an arbitrary selector/query. |
| Throughput near 1,000 records/s should be practical | Protect traffic-generation performance at target scale | `PARTIAL` | `BOUNDED` | Workers use verified local memory; PostgreSQL export and mutable authority calls stay off the measured replay path. Performance remains unproven until qualification. | Paired performance, restart fan-out and 24-hour soak gates must pass at declared scale. |
| Provider-created supply and renewal may be needed | Create reusable data without manual imports | `PARTIAL` | `BOUNDED` | The MVP includes bounded `SCHEDULER + REPLAY + SHARED`; later Release 1 sources are finite CSV/Redis and bounded Derivation. Provider lifecycle remains explicit. | Source-specific capacity, failure and publication tests gate advertisement. |
| Dataset must not infer SUT truth | Keep business outcome ownership in the scenario | `FULL` | `ADOPTED` | A scenario-owned Outcome Normaliser emits one closed Outcome; only explicit workflow completion changes state. | Missing/ambiguous output changes no state; no SUT-response inference exists. |
| Authorisation scopes need definition | Restrict reads and mutations to the admitted run | `FULL` | `BOUNDED` | Every API authorises SUT, Dataset, Group, binding, run and operation; lease mutation requires its owner. | Cross-run/binding and stale-token tests fail closed. |
| Batch, lease, record-size and capacity limits are needed | Bound resource and concurrency cost | `FULL` | `ADOPTED` | Deployment limits and admission formulas cover schema evaluation, records, parser structure, validation queue/working memory per replica, leases, mutations, snapshots and workers. | Adversarial schema, hot-replica/failover, boundary, parser, concurrent-admission and capacity-isolation tests pass. |
| REST, Worker I/O, capability and observability contracts are needed | Give delivery teams executable interfaces | `PARTIAL` | `BOUNDED` | The normative design fixes owners and shapes; M0 executable schemas/types remain pending. | No implementation starts until the named M0 contracts and tests exist. |
| Inventory should be retained indefinitely | Avoid accidental data loss | `PARTIAL` | `BOUNDED` | Release 1 records are non-expiring and never purged, but operation is bounded by declared storage horizon and admission limits. | A future reclamation design must define active-run, evidence and recovery safety. |

### Adopted

| Proposal idea | Canonical outcome | Reason |
|---|---|---|
| Durable records shared by many swarms | One named Managed Dataset may serve zero or many compatible consumer swarms. | Reuses provider-created data without copying provider logic. |
| One SUT scope | Dataset Space and Scenario Binding validate and freeze one SUT Environment. | Prevents cross-SUT selection and fallback. |
| Name plus stable identity | Required `name` and opaque `datasetId`; Groups remain subordinate. | Keeps discovery human-readable and runtime identity stable. |
| Generic versioned schema | One root schema composes exact immutable contracts and local `$defs` within an explicitly typed, composition-safe and cost-bounded grammar. | Supports arbitrary domains without PocketHive business fields, implicit openness or unbounded evaluation. |
| PostgreSQL durability | PostgreSQL owns Managed Dataset runtime records, revisions, imports, state, Views and leases. | Provides atomic restart-safe authority. |
| Direct bounded Controller snapshot read | Under a fenced grant, the Controller streams one immutable revision through the least-privilege PostgreSQL function adapter; Orchestrator never proxies bytes. | Keeps workers database-free without making Orchestrator a snapshot-byte bottleneck. |
| Immutable payload | Payload is immutable in both canonical Profiles. | Keeps publication, caching and evidence deterministic. |
| Shared and exclusive use | `REPLAY` supports `SHARED` or `EXCLUSIVE_LEASE`; `WORKFLOW` requires `EXCLUSIVE_LEASE`. | Covers concurrent reuse and temporary unavailability. |
| Lease separate from business data | Record Leases remain authority state, never tags or payload fields. | Separates allocation safety from scenario state. |
| Durable mutable availability | `WORKFLOW` uses versioned Record State, fixed Views and declared State Transitions. | Retains needed mutation through a bounded state machine. |
| One bounded cross-Dataset transaction | Derivation atomically creates bounded independent downstream records, changes upstream state and releases its lease. | Supports chained provider/consumer flows without arbitrary cross-Dataset mutation. |
| Explicit configuration and qualification | Sources, adapters, selections and transitions are explicit; target-scale performance and failure gates block release. | Follows NFF and prevents unproven behaviour reaching production. |

### Rejected for Release 1

| Rejected choice | Reason | Canonical replacement |
|---|---|---|
| A second `DATASET` model with alternative semantics | Creates duplicate authority and incompatible worker behaviour. | One `MANAGED_DATASET` design; existing Dataset adapters stay separate and unchanged. |
| Workers reading PostgreSQL directly | Couples workers to storage, credentials and indexes and adds database work to traffic generation. | Swarm Controller publishes one verified snapshot per binding; workers use local memory. |
| PostgreSQL owning Dataset Definitions | Conflicts with the mounted, versioned Dataset Space registry. | Scenario Manager owns authoring contracts; PostgreSQL owns runtime data. |
| Free-form mutable tags | Creates an unbounded state, query, indexing and concurrency model. | Typed Record State with declared paths, Views and transitions. |
| Arbitrary selectors | Makes admission and query cost unpredictable. | Create Swarm freezes one exact Dataset/Group and optional workflow View. |
| `PAYLOAD_REPLACE` | Breaks immutable revision identity and local snapshot safety. | Immutable payload plus separately versioned Record State. |
| Tag mutation combined with lease release | Conflates allocation and business mutation and makes partial failure unclear. | One exact atomic transition, or an explicitly allowed unchanged release. |
| Redis projection/outbox as the Release 1 distribution path | Adds another service, cache lifecycle and consistency plane without evidence it is needed. | Deployment-owned filesystem publication and worker local memory. |
| Inferring state from a SUT result | A response or timeout cannot prove the intended business outcome. | Only explicit workflow completion may mutate state. |
| Business-specific types or tags as PocketHive concepts | Leaks one domain into a generic platform contract. | Dataset schemas and scenario mappings own every domain field. |

Rejected means excluded from this Release 1 architecture, not an undocumented
extension point. Reconsideration requires a separate approved design.

### Deferred

| Deferred item | Reason | Reconsider when |
|---|---|---|
| Direct Controller table access or unrestricted export | Bypasses the fenced grant, canonical read function and least-privilege boundary. | A separately approved reader contract cannot be expressed by the bounded function adapter. |
| Cross-swarm content-addressed snapshot reuse | Adds cache references, invalidation and cleanup complexity. | Per-swarm publication exceeds approved storage or startup targets. |
| Object storage, Redis snapshot cache or another distribution service | Adds another operational plane and failure model. | Qualified shared filesystem adapters cannot meet a concrete deployment requirement. |
| Dynamic tags, selectors, state patches or payload replacement | Requires a broader mutable-data, query and evidence contract. | A concrete use case cannot use bounded Record State, Views and transitions. |
| Lease renewal/transfer, use counts or queue/pop semantics | Broadens allocation and recovery beyond fixed temporary exclusive use. | A measured scenario cannot be served safely by fixed-expiry leases. |
| Runtime-created Views/transitions or arbitrary cross-Dataset transactions | Makes capacity and concurrency behaviour dynamic. Release 1 permits only its fixed bounded Derivation. | A separate bounded contract defines another exact transaction, ownership and atomicity. |
| Redis projection for external consumers | No current requirement justifies outbox, rebuild and consistency contracts. | A named consumer cannot use Managed Dataset or an existing explicit Redis path. |
| Dataset retirement, purge and automatic deletion | Active-run safety, evidence and recovery are not defined. | A governed lifecycle contract is approved; until then limits and the runbook bound growth. |
| Audit-grade history and delivery proof | Operational Consumption Status does not prove SUT acceptance or exactly-once delivery. | Audit requirements define trust, retention, privacy and verification owners. |
| SUT reconciliation | PocketHive cannot safely infer ambiguous external outcomes. | A SUT-specific reconciliation contract has an explicit authority and idempotency model. |

Deferred does not mean approved. Each item requires a separate design,
canonical contract and qualification evidence.
