# Managed Datasets Specification (comparison draft)

Status: proposed target; not implemented or qualified

Scope: Scenario Manager, Orchestrator, Swarm Controller, Worker SDK, producer
and consumer swarms, PostgreSQL, PocketHive MCP and operator UI

This file is a temporary comparison draft. It does not replace the current
normative specification.

## Goal

Allow one PocketHive swarm to create or maintain durable test records that
independent scenarios can reuse safely. Consumers must use worker-local data
during measured traffic. The first release uses PostgreSQL and existing
PocketHive application containers.

~~~text
producer swarm -> Managed Dataset -> local consumer snapshot -> SUT traffic
~~~

## Hard rules

| Rule | Reason |
|---|---|
| Every storage registration declares its adapter and settings | No fallback or inferred backend |
| Scenario consumers are bound explicitly | No alias discovery or cross-scenario awareness |
| Each Dataset owns immutable package-local contracts | A contract change cannot alter unrelated Datasets |
| PostgreSQL is the authority for the first managed profile | Files, RabbitMQ and process memory cannot decide record truth |
| Producer swarms perform SUT calls | Orchestrator remains a coordinator |
| All swarm lifecycle uses the existing ph.control path | No second control plane |
| Supply work uses the controller-owned WorkItem route | No hand-crafted or alternate route |
| A durable Dataset receipt completes an operation | Broker acknowledgement is not record evidence |
| Measured traffic uses worker-local data | No central lookup per request |
| UI and MCP call product services | No second authority or fixture fallback |

## Supported

- Standalone Dataset packages under
  scenarios/managed-datasets/<datasetPackageId>/.
- SUT-scoped Dataset Spaces and deployment registrations.
- An optional scenario-local datasets/requirements.yaml contract.
- Explicit per-scenario bindings.
- Reusable SHARED records.
- PostgreSQL MANAGED_RECORDS_V1 storage.
- Provision, refresh, validate, replace, retire and deprovision operations.
- DATASET_SUPPLY producer input.
- Preferred DATASET_UPSERT output.
- One shared, opt-in publisher interceptor for workers that retain another
  primary output.
- Deterministic HTTP and TCP result classification.
- Background snapshot hydration and worker-local selection.
- Product-backed package authoring and redacted runtime inspection.

## Out of scope

- Managed REDIS_COLLECTION_V1 until separately implemented and qualified.
- Exclusive leases, consumable records and stateful record transitions.
- Automatic consumer discovery or Dataset-name construction from responses.
- High availability, backup restore and multi-region claims.
- Regulated-data or specialised-cryptography profiles.
- Agent mutation of runtime record state.
- A separate Dataset application container.

Unavailable features fail admission. They are not emulated.

## Ownership

| Concern | Owner | Must not own |
|---|---|---|
| Packages, Spaces and registrations | Scenario Manager | Runtime records |
| Scenario requirements and bindings | Scenario Manager | Automatic consumer discovery |
| Admission and resolved runtime bindings | Orchestrator | Request-time selection |
| Records, revisions, supply and readiness | Managed Dataset module | SUT calls or swarm topology |
| Swarm lifecycle, current topology and work routes | Swarm Controller | Global Dataset inventory |
| Creation, refresh, validation and deprovision calls | Producer swarm | Demand calculation |
| Background hydration and local selection | Consumer worker | Direct database access |
| Durable managed-record state | PostgreSQL adapter | Message delivery |
| Definition/status interfaces | UI and MCP | Raw storage or independent authority |

## Architecture

~~~mermaid
flowchart LR
  SM[Scenario Manager<br/>definitions and bindings]

  subgraph ORCH[Existing Orchestrator service]
    API[Authorised Dataset API]
    MD[Managed Dataset application and domain]
    PGAD[PostgreSQL adapter]
    OUT[Outbox relay]
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
  C -->|background snapshot| API
~~~

The Managed Dataset domain depends on application-owned ports. PostgreSQL,
RabbitMQ, HTTP and scheduling are adapters. The module has a dedicated database
schema, role, connection pool and bounded executors inside Orchestrator.

## Domain model

| Term | Definition |
|---|---|
| Dataset package | Versioned blueprint for one kind of Dataset; contains schemas, local contracts, mappings, projections, sources and policies but no live records |
| Dataset Space | Deployment boundary for one immutable SUT Environment identity; owns access, classification, quotas and allowed storage profiles |
| Registration | Versioned link from one published package to one active Space; selects alias, adapter, settings and capability profile |
| Requirement | Scenario-local declaration of needed fields and behaviour without a concrete Dataset |
| Scenario Binding | Explicit mapping from each requirement to one registered Dataset and package-local contract |
| Record | Stable logical test entity |
| Material generation | Immutable refreshable values and safe-use times for a record |
| Dataset revision | Immutable membership view for one Dataset, partition and pool |
| Fitness Contract | Rules that decide whether one Dataset revision is safe for a declared use |

### Invariants

- A package version and digest never change after publication.
- A package contains no Dataset Space, backend settings, credentials or live
  records.
- One record belongs to one declared partition and inventory-owning pool.
- At most one material generation is eligible for new selection at a revision.
- One Supply Policy controls one inventory scope.
- A scenario with no requirements file has no Dataset binding or readiness
  behaviour.
- A present requirements file is non-empty; every declared requirement is
  mandatory and maps exactly once.
- Missing, duplicate, extra, wildcard, cross-Space or incompatible mappings
  fail before runtime creation.

## Authoring contracts

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

dataset.yaml is the only entry point. It lists every package-relative file.
Absolute paths, path traversal, symlink escape and recursive discovery are
rejected.

Package lifecycle:

~~~text
DRAFT -> PUBLISHED -> RETIRED
~~~

Drafts may be edited or deleted while unreferenced. Publishing freezes a
version and digest. Published versions are retired, not overwritten.

### Dataset Space and registration

Space lifecycle:

~~~text
DRAFT -> ACTIVE -> RETIRED
~~~

A registration targets one active Space and one published package version. It
must declare:

| Field | Purpose |
|---|---|
| registrationId and version | Stable deployment identity |
| datasetSpaceId and version | Exact authority boundary |
| datasetPackageId, version and digest | Exact definition |
| datasetAlias | Space-local logical name |
| storage.adapter | POSTGRESQL for the first release |
| storage.settingsRef | Named deployment configuration |
| storage.capabilityProfile | MANAGED_RECORDS_V1 |

There is no default adapter. Replacement creates a new immutable registration
version; running bindings keep their frozen version.

### Scenario requirement

The current implementation has no Dataset requirement contract. Implementation
must define one canonical schema before adding loaders or clients.

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

The requirement contains no concrete Dataset, other scenario, swarm, worker or
storage identifier. scenario.yaml does not gain a second requirement location.

### Dataset Contract

Each package owns field-subset contracts for declared uses. Every field must
exist in the package record schema. A contract cannot redefine field type,
classification, key or nullability. There is no cross-package inheritance,
composition or fallback.

## Runtime behaviour

### Supply

For one Dataset, partition and pool:

~~~text
deficit = max(0, targetReady - eligible - reserved - safelyInFlight)
request = min(deficit, batchLimit, providerAllowance, storageAllowance)
~~~

The Supply Policy owns targets, watermarks, batch limits, validity requirements
and refresh rules. Infrastructure limits protect resources but do not become a
second demand controller.

Supply follows this order:

1. Reconciliation observes a durable deficit.
2. The lifecycle adapter confirms the exact producer swarm, workload, input
   and route are ready through the existing control path.
3. One connection from the Managed Dataset pool reserves capacity and stores
   outbox intent in one local PostgreSQL transaction.
4. The relay publishes DATASET_SUPPLY to the current controller-owned route.
5. The producer claims the operation and performs the configured SUT flow.
6. The producer commits a typed result through the Dataset API.
7. The durable receipt updates observed inventory and completes the operation.

Missing or stale readiness publishes no work. DATASET_SUPPLY cannot start a
swarm, and swarm-start cannot carry Dataset demand.

### Producer outputs and result routing

DATASET_UPSERT is the normal producer output. The optional
managedDatasetPublisher interceptor exists only for a worker that must retain
another required primary output. Both use one canonical committer,
idempotency key and durable receipt. Ambiguous partial completion is reconciled
and never reported as success.

Each source binding freezes one SourceResultPolicy. HTTP and TCP use
protocol-specific evidence but produce the same closed outcomes:

| Outcome | Dataset effect |
|---|---|
| COMPLETED | May commit to the configured primary Dataset and count as supply |
| FAILED | May commit a safe record to a separately authorised remediation Dataset |
| PENDING | Remains under reconciliation |
| UNCERTAIN | Remains under reconciliation and blocks blind retry |

Malformed, missing or contradictory evidence is UNCERTAIN. A successful socket
write or HTTP exchange does not by itself prove business completion. Every
route target is configured and authorised before runtime.

### Consumer path

Workers download bounded pages for one exact revision in the background. They
verify scope, digest and revision, build a local view and activate it
atomically. During measured traffic, selection, validity checks and request
materialisation use local memory only.

PostgreSQL, Redis, RabbitMQ, Orchestrator APIs and credential providers are
forbidden on the measured request thread.

## Storage and recovery

MANAGED_RECORDS_V1 uses the existing PostgreSQL deployment with a dedicated
schema, role and connection pool. Minimum durable state includes:

State changes and their outbox rows use the same connection and local
transaction. RabbitMQ publication occurs after commit. No other connection
pool or distributed transaction manager participates.

- stable records and immutable material generations;
- revisions and revision-visible membership;
- Fitness evaluations and activation acknowledgements;
- Supply Policy activation, fill cycles and capacity reservations;
- supply operations, attempts, fences and receipts;
- durable lifecycle schedules and source checkpoints;
- encrypted, limited intermediate source values;
- transactional outbox and refresh attempts.

On restart, the module enters RECONCILING, fences stale claims, rebuilds due
work and resolves ambiguous operations before accepting mutation. Database
unavailability makes the Dataset unavailable; the module does not switch to
files, memory or Redis.

The first release claims container-restart recovery with the named persistent
volume. It does not claim destructive volume-loss recovery, backup restore or
exact reconstruction of every interrupted swarm lifecycle operation.

## Readiness

A declared Dataset is ready only when:

- the binding and registration remain authorised;
- the exact Fitness Contract returns PASS;
- enough records meet field, source and remaining-validity rules;
- required workers have activated the same approved revision;
- trusted time is within policy;
- no storage, quota, source or security blocker invalidates use.

Count alone is not readiness. FAIL or UNKNOWN blocks new activation. Runtime
depletion pauses or throttles only the dependent input before unsafe use.

## Security and observability

Required security controls:

- least-privilege SUT and Space scope on every API;
- metadata-only Rabbit messages;
- strict schemas, closed enums and limited payload sizes;
- encrypted permitted material and opaque credential references;
- source destination allowlists and limited response parsing;
- no credentials or record values in packages, logs, metrics, UI, MCP,
  browser storage or evidence;
- no raw SQL, Redis-query, record-value or secret-retrieval UI/MCP operation;
- product-side authorisation for every agent request, even when HiveGate
  approves its execution.

Required telemetry covers inventory, Fitness, supply, uncertainty, refresh,
revisions, worker activation, snapshot size/latency, schedules, outbox,
database pressure and resource-budget rejection. Labels use limited
identifiers and reason codes, never record values or provider text.

## UI and MCP

Scenario Manager supplies real, authorised operations to create, edit,
validate, publish and retire packages; manage Spaces and registrations; and
validate requirements and bindings. Only unreferenced drafts may be deleted.

Orchestrator supplies paginated, redacted views of inventory, readiness,
supply operations, consumers, applied revisions and proof. UI and MCP use these
services rather than repositories or storage adapters. Release builds contain
no successful fixture fallback or dummy Dataset rows.

Agents are untrusted clients. MCP provides no independent authority and cannot
mutate runtime records.

## Delivery plan

### M0 — contracts

- Freeze canonical package, Space, registration, requirement, binding, worker,
  result-policy, receipt and read-model schemas.
- Add shared enums/constants and contract fixtures.
- Approve one non-sensitive conformance provider and SUT double.

### M1 — definitions and durable core

- Implement Scenario Manager authoring and binding services.
- Implement the Orchestrator module boundary and PostgreSQL schema.
- Add authorisation, idempotency, revisions, schedules and outbox.

### M2 — producer and consumer flow

- Add controller-fenced DATASET_SUPPLY.
- Add DATASET_UPSERT and the shared opt-in interceptor.
- Add HTTP/TCP result evaluation.
- Add local snapshots and readiness aggregation.
- Deliver real UI and MCP functions.

### M3 — qualification

- Run the conformance scenario and mandatory test families.
- Publish a reproducible evidence index.
- Enable only profiles that pass.

## Acceptance criteria

The first release must prove:

- package versions and local contracts remain immutable;
- absent requirements leave an existing scenario unaffected;
- every declared requirement maps exactly once;
- incompatible adapters and profiles fail before runtime;
- completed, failed and uncertain HTTP/TCP outcomes are deterministic;
- duplicate commands and Rabbit delivery do not duplicate authoritative
  records or external effects;
- ambiguous provider results are reconciled before retry;
- expired, revoked, wrong-Space or unfit data is never selected;
- restart resumes schedules, operations and outbox intent;
- state and outbox intent commit or roll back together; the relay cannot
  publish uncommitted intent, and RabbitMQ failure leaves committed intent
  pending for retry;
- stale route, operation and worker fences are rejected;
- measured request threads perform zero central Dataset calls;
- 50,000 eligible records, a 55,000 maximum, two consumer swarms and 1,000
  requests per second pass for the agreed qualification period;
- live target changes converge without unsafe deletion or duplicate supply;
- Dataset work remains within agreed Orchestrator control-plane limits;
- UI and MCP use real product data and enforce the same permissions;
- secrets and record values do not leak through secondary channels.

Tests use official ingress and API paths. Every release requirement links to
reviewed evidence; missing or contradictory evidence is not a pass.

## Main risks

| Risk | Control |
|---|---|
| Central storage slows traffic | Worker-local revisions and a request-thread I/O detector |
| Dataset work disrupts Orchestrator | Separate pools, bounded executors and non-interference gates |
| Producers create too much data | Transactional reservations and count/byte/provider limits |
| An uncertain SUT effect is repeated | Stable idempotency or status reconciliation; otherwise UNCERTAIN |
| Rabbit and database state diverge | Transactional outbox and idempotent receipts |
| Redis weakens guarantees | Explicit capability profile and pre-runtime rejection |
| Count hides invalid records | Exact Fitness Contract PASS gate |
| UI or MCP becomes an authority | Product services only; no raw store access |

## Open decisions before implementation

- Approve the first real use case and its measurable business outcome.
- Freeze the new datasets/requirements.yaml schema and binding API.
- Name owners for Dataset Space policy, source adapters and qualification.

## References

- [Current normative specification](managed-test-data-lifecycle-generic-spec.md)
- [PocketHive architecture](../ARCHITECTURE.md)
- [SUT, Dataset and Simulation model](../architecture/sut-dataset-simulation-model.md)
- [Scenario contract](../scenarios/SCENARIO_CONTRACT.md)
- [Control-plane testing](../ci/control-plane-testing.md)
