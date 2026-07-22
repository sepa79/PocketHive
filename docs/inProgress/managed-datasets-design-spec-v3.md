# Managed Datasets Specification v3 (comparison draft)

Status: proposed target; not implemented or qualified

Scope: Scenario Manager, Orchestrator, Swarm Controller, Worker software
development kit (SDK), producer and consumer swarms, PostgreSQL, PocketHive
Model Context Protocol (MCP) tools and operator user interface (UI)

## Goal

A **Managed Dataset** is proposed live reusable test records plus the lifecycle
state that keeps them safe and available.

One producer swarm makes system-under-test (SUT) calls to maintain them for
independent consumers. Measured traffic uses worker-local data. The first
release uses PostgreSQL and existing PocketHive containers.

~~~text
producer swarm -> Managed Dataset -> local consumer snapshot -> SUT traffic
~~~

## Definition register

Every term below is `PROPOSED`, not implemented. No shorthand is allowed in
this draft.

| Canonical term | Meaning and ownership | Not the same as | Source |
|---|---|---|---|
| Managed Dataset | Live reusable test data plus its lifecycle state | A file, table or Dataset package | [§2.4](managed-test-data-lifecycle-generic-spec.md#24-short-glossary) |
| Dataset package | Immutable blueprint owning the definitions for exactly one kind of Managed Dataset | An application, deployment or live data | [§4.2](managed-test-data-lifecycle-generic-spec.md#42-dataset-package) |
| Dataset Space | Authority boundary owning environment scope, access, classification, quotas and allowed storage profiles | A container for definitions or live data | [§4.1](managed-test-data-lifecycle-generic-spec.md#41-dataset-space) |
| Dataset registration | Link selecting one Dataset package version, Dataset Space, alias, adapter, settings and capability profile | Publication or live-data creation | [§4.3](managed-test-data-lifecycle-generic-spec.md#43-dataset-registration) |
| Scenario requirement | Scenario-local data need without a concrete Managed Dataset | A swarm identity or cross-scenario dependency | [§4.6](managed-test-data-lifecycle-generic-spec.md#46-scenario-requirement-and-binding) |
| Dataset Contract | Immutable field subset owned by one Dataset package | A shared registry or full record schema | [§4.4](managed-test-data-lifecycle-generic-spec.md#44-record-schema-and-dataset-contract) |
| Scenario Binding | Explicit mapping from each Scenario requirement to one Dataset registration and Dataset Contract | Discovery or alias matching | [§4.6](managed-test-data-lifecycle-generic-spec.md#46-scenario-requirement-and-binding) |
| Record | Stable logical test entity | Its refreshable values | [§4.7](managed-test-data-lifecycle-generic-spec.md#47-record-and-material-generation) |
| Material generation | Immutable refreshable values, safe-use times, provenance and fences | A mutable current-value row | [§4.7](managed-test-data-lifecycle-generic-spec.md#47-record-and-material-generation) |
| Dataset revision | Immutable eligible-membership view | A live query or mutable list | [§7.2](managed-test-data-lifecycle-generic-spec.md#72-revision-invariants) |
| Fitness Contract | Rules returning `PASS`, `FAIL` or `UNKNOWN` for one use | A count threshold | [§4.8](managed-test-data-lifecycle-generic-spec.md#48-fitness-contract) |
| Supply Policy | Sole owner of inventory targets, watermarks, batch bounds, validity and refresh | Infrastructure safety limits | [§8.2](managed-test-data-lifecycle-generic-spec.md#82-demand) |
| Supply operation | Bounded idempotent producer work ending in one durable receipt | Swarm lifecycle or Rabbit delivery | [§8.1](managed-test-data-lifecycle-generic-spec.md#81-states) |
| `SourceResultPolicy/v1` | Policy mapping protocol evidence to one business outcome and action | Transport success or worker guesswork | [§9.2](managed-test-data-lifecycle-generic-spec.md#92-source-result-policy) |

## Hard rules

| Rule | Reason |
|---|---|
| Every Dataset registration declares one adapter and settings reference | No fallback or inferred backend |
| Every consumer uses an explicit Scenario Binding | No discovery or cross-scenario awareness |
| Each Dataset package owns immutable Dataset Contracts | One contract change cannot alter unrelated Managed Datasets |
| PostgreSQL is authoritative for `MANAGED_RECORDS_V1` | Files, RabbitMQ and process memory cannot decide record truth |
| Producer swarms perform system-under-test calls | Orchestrator remains a coordinator |
| Swarm lifecycle uses the existing `ph.control` path | No second control plane |
| Supply uses the Swarm Controller-owned `WorkItem` route | No handcrafted or alternate route |
| A typed durable receipt completes supply | Broker acknowledgement is not record evidence |
| Measured traffic uses worker-local data | No central request-time lookup |
| UI and MCP call authorised product services | No independent authority or fixture fallback |

## Delivery boundary

### Supported in the first release

- Dataset packages under
  `scenarios/managed-datasets/<datasetPackageId>/`.
- Dataset Spaces and explicit Dataset registrations.
- Optional `datasets/requirements.yaml`; every listed Scenario requirement is
  mandatory and maps exactly once.
- Reusable `SHARED` Records through PostgreSQL `MANAGED_RECORDS_V1`.
- Provision, refresh, validate, replace, retire and deprovision operations.
- `DATASET_SUPPLY` input and preferred `DATASET_UPSERT` output.
- Opt-in `managedDatasetPublisher` when another primary output must remain.
- Deterministic Hypertext Transfer Protocol (HTTP) and Transmission Control
  Protocol (TCP) outcome classification.
- Background Dataset revision hydration and worker-local selection.
- Real product-backed authoring and redacted runtime inspection.

### Deferred or excluded

- Managed Redis `REDIS_COLLECTION_V1` pending separate implementation and
  qualification. Existing `REDIS_DATASET` is unchanged.
- Exclusive leases, consumable Records and stateful Record transitions.
- Per-Scenario-requirement optionality or automatic consumer discovery.
- High availability, backup restore and multi-region claims.
- Regulated-data or specialised-cryptography profiles.
- Agent mutation of runtime Record state.
- A separate Dataset application container.

Unavailable capabilities fail admission; PocketHive does not substitute them.

## Ownership

| Concern | Owner | Must not own |
|---|---|---|
| Dataset packages, Dataset Spaces and Dataset registrations | Scenario Manager | Runtime Records |
| Scenario requirements and Scenario Bindings | Scenario Manager | Automatic consumer discovery |
| Admission and resolved Scenario Bindings | Orchestrator | Request-time selection |
| Records, Dataset revisions, supply and readiness | Managed Dataset module in Orchestrator | System-under-test calls or swarm topology |
| Swarm lifecycle, current topology and work routes | Swarm Controller | Global Managed Dataset inventory |
| Create, refresh, validate and deprovision calls | Producer swarm | Demand calculation |
| Background hydration and local selection | Consumer worker | Direct database access |
| Durable `MANAGED_RECORDS_V1` state | PostgreSQL adapter | Message delivery |
| Authoring and status clients | UI and MCP | Raw storage or independent authority |

## Architecture

~~~mermaid
flowchart LR
  SM[Scenario Manager<br/>definitions and Scenario Bindings]

  subgraph ORCH[Existing Orchestrator service]
    API[Authorised Dataset service]
    MD[Managed Dataset application and domain]
    PGAD[PostgreSQL adapter]
    OUT[Transactional outbox relay]
    LIFE[Lifecycle adapter]
    API --> MD
    MD --> PGAD
    MD --> LIFE
    PGAD --> OUT
  end

  PG[(Existing PostgreSQL)]
  CP[Existing ph.control]
  SC[Swarm Controller]
  WQ[Controller-owned WorkItem route]
  P[Producer swarm]
  C[Consumer swarm]

  SM --> API
  PGAD <--> PG
  LIFE <--> CP
  CP <--> SC
  SC --> WQ
  OUT -->|DATASET_SUPPLY| WQ
  WQ --> P
  P -->|claim and commit| API
  C -->|background Dataset revision| API
~~~

The domain depends on application-owned ports; PostgreSQL, RabbitMQ, HTTP and
scheduling are adapters. Inside Orchestrator it has a dedicated database
schema, role, connection pool and bounded executors.

## Domain invariants

- A Dataset package version and digest never change after publication.
- A Dataset package contains no Dataset Space, backend settings, credentials
  or live Records.
- One Record belongs to one declared partition and inventory-owning pool.
- At most one Material generation is eligible for new selection in a Dataset
  revision.
- One Supply Policy controls one inventory scope.
- A scenario without `datasets/requirements.yaml` has no Scenario Binding or
  Managed Dataset readiness behaviour.
- A present requirements file is non-empty; each Scenario requirement is
  mandatory and maps exactly once.
- Missing, duplicate, extra, wildcard or incompatible mappings, including
  mappings across Dataset Spaces, fail before runtime creation.

### Closed runtime states

| Concern | Allowed states |
|---|---|
| Record | `READY`, `STANDBY`, `QUARANTINED`, `RETIRED` |
| Material generation | `PENDING`, `USABLE`, `REFRESH_DUE`, `EXPIRED`, `REVOKED`, `FAILED` |
| Supply operation | `PLANNED -> RESERVED -> DISPATCHED -> CLAIMED -> SUCCEEDED / PARTIAL / FAILED / TIMED_OUT / CANCELLED / UNCERTAIN` |
| Managed Dataset availability | `RECONCILING`, `READY`, `DEGRADED`, `STARVED`, `BLOCKED` |

Domain commands validate transitions using expected versions and fences.

## Authoring contracts

M0 creates one canonical executable schema for each contract before loaders,
handlers or clients. These shapes are illustrative only.

### Dataset package

~~~text
scenarios/managed-datasets/<datasetPackageId>/
├── dataset.yaml
├── schema/
├── contracts/
├── mappings/
├── projections/
├── policies/
├── sources/
└── assets/
~~~

`dataset.yaml` is the sole entry point and lists every file. Absolute paths,
path traversal, symlink escape and recursive discovery are rejected.

~~~text
DRAFT -> PUBLISHED -> RETIRED
~~~

Publishing freezes version and digest. Published Dataset packages are retired
or replaced by a new version, never overwritten.

### Dataset Space and Dataset registration

~~~text
DRAFT -> ACTIVE -> RETIRED
~~~

Only an active Dataset Space accepts a Dataset registration. It selects one
published Dataset package version and declares:

| Required field | Purpose |
|---|---|
| `registrationId` and `version` | Stable deployment identity |
| `datasetSpaceId` and version | Exact authority boundary |
| `datasetPackageId`, version and digest | Exact immutable definition |
| `datasetAlias` | Logical name within the Dataset Space |
| `storage.adapter` | `POSTGRESQL` in the first release |
| `storage.settingsRef` | Named deployment configuration |
| `storage.capabilityProfile` | `MANAGED_RECORDS_V1` |

There is no default adapter. Replacement creates a new immutable Dataset
registration version; running Scenario Bindings remain frozen.

### Scenario requirement and Scenario Binding

The current implementation has no Managed Dataset requirement contract. M0
must freeze the schema for:

~~~text
scenarios/bundles/<scenarioId>/datasets/requirements.yaml
~~~

Illustrative shape:

~~~yaml
schemaVersion: pockethive.dataset-requirements/v1
requirements:
  - requirementId: reusableRecords
    requiredFields: [accountId, cardId]
    allocation: SHARED
    requiredRemainingValidity: PT30M
    requiredStorageCapabilities: [SNAPSHOT_READ, SHARED_SELECTION]
    deliveryEffectProfile: AT_LEAST_ONCE
    fitnessContractRef: traffic-ready@1
    selectionPolicyRef: round-robin@1
    trustedTimePolicyRef: qualified-host-time@1
    bindingSlotsRef: reusable-records-http-slots@1
~~~

The file is optional. Absence keeps the existing runtime path. When present it
is non-empty; `required` is not a supported per-item field.

A Scenario requirement names no Managed Dataset, storage, other scenario,
swarm or consumer. `scenario.yaml.requires.datasets` is unsupported. A Scenario
Binding maps each `requirementId` to an exact scope, Dataset package version
and digest, Dataset Contract and resolved policies.

### Dataset Contract

Each field exists in the owning Dataset package Record schema. A Dataset
Contract cannot redefine type, classification, key or nullability.
Inheritance, composition and fallback between Dataset packages are forbidden.

## Runtime behaviour

### Supply

For one Managed Dataset, partition and pool:

~~~text
deficit = max(0, targetReady - eligible - reserved - safelyInFlight)
request = min(deficit, batchLimit, providerAllowance, storageAllowance)
~~~

Supply order:

1. Reconciliation observes a durable deficit.
2. The lifecycle adapter confirms the exact producer swarm incarnation,
   workload, input and route through the existing control path.
3. One connection from the Managed Dataset pool reserves capacity and writes
   outbox intent in one local PostgreSQL transaction.
4. The relay sends `DATASET_SUPPLY` through the Swarm Controller-owned route.
5. The producer claims the operation and performs the configured SUT flow.
6. The producer submits a typed result through the Dataset application
   programming interface (API).
7. A durable receipt updates inventory and completes the operation.

Missing or stale readiness publishes no work. `DATASET_SUPPLY` cannot start a
swarm, and `swarm-start` cannot carry Managed Dataset demand.

### Producer output and result routing

`DATASET_UPSERT` is preferred. A worker retaining another primary output may
enable `managedDatasetPublisher`. Both use the same commit contract,
idempotency key and durable receipt. Both outputs are required; ambiguous
completion is reconciled. The interceptor defaults off and never replaces or
duplicates the primary output.

Each configured source freezes one `SourceResultPolicy/v1`. HTTP and TCP use
different evidence but the same closed outcomes:

| Outcome | Managed Dataset effect |
|---|---|
| `COMPLETED` | May commit to the authorised primary Managed Dataset and count as supply |
| `FAILED` | May commit safe failure material to a separately authorised remediation Managed Dataset |
| `PENDING` | Remains under reconciliation |
| `UNCERTAIN` | Remains under reconciliation and blocks blind retry |

HTTP status or socket write does not prove business completion. Invalid
evidence is `UNCERTAIN`. Targets are pre-authorised; routes cannot construct
Managed Dataset names.

### Consumer path

Workers hydrate one exact Dataset revision in bounded background pages, verify
it and atomically activate a local view. Selection, validity checks and request
materialisation then use local memory.

PostgreSQL, Redis, RabbitMQ, Orchestrator APIs and credential providers are
forbidden on the measured request thread.

## Storage and recovery

`MANAGED_RECORDS_V1` uses existing PostgreSQL with a dedicated schema, role and
connection pool. Durable state includes:

State changes and their outbox rows use the same connection and local
transaction. RabbitMQ publication occurs after commit. No other connection
pool or distributed transaction manager participates.

- Records, Material generations and Dataset revisions;
- Fitness evaluations and worker activation acknowledgements;
- Supply Policy activation, fill cycles and reservations;
- supply operations, attempts, fences and receipts;
- lifecycle schedules and source checkpoints;
- bounded encrypted intermediate source values;
- transactional outbox and refresh attempts.

After restart, `RECONCILING` fences stale claims, rebuilds work and resolves
ambiguity before mutation. Database failure makes the Managed Dataset
unavailable; file, memory and Redis fallback are forbidden.

The first release claims named-volume container-restart recovery, not
destructive volume-loss, backup restore or exact interrupted-swarm recovery.

## Readiness

A declared Managed Dataset is ready only when:

- its Scenario Binding and Dataset registration remain authorised;
- the exact Fitness Contract returns `PASS`;
- enough Records meet field, provenance and remaining-validity rules;
- required workers activated the same approved Dataset revision;
- trusted time is within policy;
- no storage, quota, source or security blocker invalidates use.

Count alone is insufficient. `FAIL` or `UNKNOWN` blocks activation. Depletion
pauses only the dependent input. No requirements file means no readiness gate.

## Security and observability

- least-privilege system-under-test and Dataset Space scope on every API;
- metadata-only Rabbit messages and strict bounded schemas;
- encrypted permitted material and opaque credential references;
- source destination allowlists and bounded response parsing;
- no credentials or Record values in Dataset packages, logs, metrics, UI,
  MCP, browser storage or evidence;
- no raw database or Redis query, Record value or secret retrieval through UI
  or MCP;
- product authorisation for every agent request, even after HiveGate approval.

Telemetry covers inventory, Fitness Contract results, supply, uncertainty,
refresh, Dataset revisions, activation, snapshots, schedules, outbox and
resource pressure.
Labels contain bounded identifiers and reason codes, never Record values or
provider text.

## UI and MCP

Scenario Manager provides authorised create, edit, list, validate, publish and
retire operations for Dataset packages; Dataset Space and Dataset registration
management; and Scenario requirement and Scenario Binding validation. Only
unreferenced drafts may be deleted.

Orchestrator provides paginated redacted inventory, readiness, supply, consumer,
Dataset revision and proof views. UI and MCP use these services, not storage.
Release builds contain no fixture fallback or dummy rows.

Agents are untrusted. MCP cannot act as an authority or mutate runtime Records.

## Delivery plan

### M0 — contracts

- Freeze Dataset package, Dataset Space, Dataset registration, Scenario
  requirement, Scenario Binding, worker, result-policy, receipt and read-model
  schemas.
- Add shared enums/constants and contract fixtures.
- Approve one deterministic non-sensitive conformance provider.

### M1 — authoring and durable core

- Implement Scenario Manager authoring and Scenario Binding services.
- Add the Orchestrator module, PostgreSQL schema, authorisation, idempotency,
  Dataset revisions, schedules and outbox.

### M2 — producer and consumer flow

- Add controller-fenced `DATASET_SUPPLY`.
- Add `DATASET_UPSERT` and the shared opt-in interceptor.
- Add HTTP/TCP `SourceResultPolicy/v1` evaluation.
- Add local snapshots, readiness, and real UI and MCP functions.

### M3 — qualification

- Run mandatory tests and publish reproducible evidence.
- Enable only passing capability profiles.

## Acceptance criteria

The first release must prove:

- Dataset package versions and Dataset Contracts remain immutable;
- absent `datasets/requirements.yaml` leaves an existing scenario unaffected;
- every declared Scenario requirement maps exactly once;
- incompatible adapters and profiles fail before runtime;
- `COMPLETED`, `FAILED`, `PENDING` and `UNCERTAIN` HTTP/TCP outcomes are
  deterministic;
- duplicate commands and Rabbit delivery do not duplicate authoritative
  Records or external effects;
- ambiguous provider results are reconciled before retry;
- expired, revoked, unfit data or data from the wrong Dataset Space is never
  selected;
- restart resumes schedules, operations and outbox intent;
- state and outbox intent commit or roll back together; the relay cannot
  publish uncommitted intent, and RabbitMQ failure leaves committed intent
  pending for retry;
- stale route, operation and worker fences are rejected;
- measured request threads make zero central Managed Dataset calls;
- 50,000 eligible Records, a 55,000 maximum, two consumer swarms and 1,000
  requests per second pass for the agreed qualification period;
- live target changes converge without unsafe deletion or duplicate supply;
- Managed Dataset work stays within agreed Orchestrator control-plane limits;
- UI and MCP add, edit, list, validate, publish, retire and remove drafts using
  real product data and the same permissions;
- secrets and Record values do not leak through secondary channels.

Tests use official ingress and APIs. Every requirement links to reviewed
evidence; missing or contradictory evidence is not a pass.

## Main risks

| Risk | Control |
|---|---|
| Central storage slows traffic | Worker-local Dataset revisions and a request-thread input/output detector |
| Managed Dataset work disrupts Orchestrator | Separate pools, bounded executors and non-interference gates |
| Producers create too much data | Transactional reservations and count, byte and provider limits |
| An uncertain external effect is repeated | Stable idempotency or status reconciliation; otherwise `UNCERTAIN` |
| Rabbit and database state diverge | Transactional outbox and idempotent receipts |
| Redis weakens guarantees | Explicit capability profile and pre-runtime rejection |
| Count hides invalid Records | Exact Fitness Contract `PASS` gate |
| UI or MCP becomes an authority | Product services only; no raw store access |

## Open decisions before implementation

- Approve the first real use case and its measurable business outcome.
- Freeze `datasets/requirements.yaml` and the Scenario Binding API.
- Name owners for Dataset Space policy, source adapters and qualification.

## References

- [Normative specification](managed-test-data-lifecycle-generic-spec.md)
- [PocketHive architecture](../ARCHITECTURE.md)
- [SUT, Dataset Space and Simulation Program proposal](../architecture/sut-dataset-simulation-model.md)
- [Scenario contract](../scenarios/SCENARIO_CONTRACT.md)
- [Control-plane testing](../ci/control-plane-testing.md)
