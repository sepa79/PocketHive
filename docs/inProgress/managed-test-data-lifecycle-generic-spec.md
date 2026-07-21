# Managed Test Data Architecture and Lifecycle Specification

Status: in progress — ready for team approval; implementation and qualification remain pending

Decision target: PocketHive managed-dataset MVP

Last updated: 2026-07-21

Team introduction:
[Managed Datasets team design overview](managed-datasets-team-design-overview.md)

This is the normative design specification. It defines what must be built; it
is not evidence that the current implementation supports the capability.

## 1. Purpose

PocketHive needs durable test data that can be created or obtained by one swarm
and safely reused by other swarms over long-running simulations.

The MVP must provide:

- standalone, versioned Dataset packages;
- SUT-scoped authority and access boundaries;
- durable records, material generations, revisions and supply operations;
- continuous provision, refresh, validation, replacement and retirement;
- explicit scenario-to-Dataset bindings without cross-scenario awareness;
- worker-local selection with no central lookup on the measured request path;
- deterministic HTTP and TCP source-result classification;
- PostgreSQL managed-record storage;
- an explicit, separately qualified Redis adapter extension;
- real MCP and operator UI authoring/status functions with no fixture fallback;
- restart-safe, observable and testable lifecycle behavior.

The terms **must**, **shall**, and **required** are normative. **Should** records
the preferred implementation where an equivalent design still satisfies the
acceptance criteria.

## 2. Scope and decisions

### 2.1 MVP

The core MVP includes:

- the MANAGED_RECORDS_V1 capability profile using PostgreSQL;
- reusable SHARED records;
- package-local Dataset Contracts;
- explicit Dataset Space registration and scenario binding;
- source operations for provision, refresh, validate, replace and deprovision;
- background snapshot hydration and worker-local selection;
- the DATASET_SUPPLY, DATASET_UPSERT and MANAGED_DATASET worker capabilities;
- DATASET_UPSERT as the preferred producer output;
- an opt-in managed-dataset publisher interceptor for workers that must retain
  another primary output;
- source-result routing to completed, failed or reconciliation outcomes;
- authorised package authoring and runtime inspection through product APIs,
  MCP and UI;
- Docker deployment using existing PocketHive application containers.

### 2.2 Deferred

The following are explicit extensions, not implicit MVP behavior:

- REDIS_COLLECTION_V1;
- exclusive leases, single-use/consumable records and stateful transitions;
- high-availability or backup-restore claims;
- multi-region operation;
- regulated-data profiles and specialised cryptography;
- autonomous agent mutation of runtime Dataset state;
- a dedicated Dataset service/container.

Selecting an unavailable profile or capability must fail admission. PocketHive
must not substitute another adapter, allocation mode or transport.

### 2.3 Non-goals

This design does not:

- replace Redis for existing scenarios;
- turn PocketHive into a general-purpose test-data management product;
- place Dataset storage or an API call on the measured transaction path;
- embed SUT-specific provisioning workflows in Orchestrator;
- make Scenario Manager, MCP, UI, RabbitMQ or filesystem state the runtime
  record authority;
- promise exactly-once external side effects when the provider cannot support
  idempotency or reconciliation;
- claim universal throughput, resilience, security or regulatory compliance.

## 3. Architectural alignment

This design elaborates, and does not replace,
[SUT + Dataset + Simulation Model](../architecture/sut-dataset-simulation-model.md).
Existing PocketHive architecture and routing rules remain authoritative.

Managed Dataset is a bounded module inside orchestrator-service. This avoids
another container while keeping the domain isolated behind application ports.
It is the only approved co-location exception for this feature and does not
move source execution or measured-path work into Orchestrator.

The module must follow these rules:

- domain code imports no HTTP, RabbitMQ, persistence, scheduler, UI, MCP or
  worker implementation types;
- infrastructure adapters depend on domain/application-owned ports;
- PostgreSQL entities and repositories do not escape their adapter;
- UI and MCP call the same authorised product services as other clients;
- all swarm lifecycle commands continue to use the existing ph.control
  contract and shared routing utility;
- DATASET_SUPPLY is bounded work on the existing controller-owned WorkItem
  route, not a second control plane;
- the selected storage adapter is always explicit and never falls back.

## 4. Domain model

### 4.1 Dataset Space

A Dataset Space is a deployment-scoped authority boundary for Datasets that
belong to exactly one immutable SUT Environment identity. It owns identity,
lifecycle, SUT Environment scope, access policy, classification ceiling,
quotas and allowed storage capability profiles. It does not own Dataset
schemas, contracts, mappings, sources or records.

~~~yaml
schemaVersion: pockethive.dataset-space/v1
datasetSpaceId: performance-test
version: 2
displayName: Performance test data
sutEnvironmentRef: example-environment
accessPolicyRef: performance-test-datasets-access@1
classificationCeiling: INTERNAL
quotaPolicyRef: performance-test-datasets-quota@1
allowedStorageProfiles:
  - MANAGED_RECORDS_V1
  - REDIS_COLLECTION_V1
~~~

Lifecycle: DRAFT -> ACTIVE -> RETIRED.

- Only an active Space accepts registrations.
- A policy change creates a new version.
- Retirement prevents new registrations.
- A referenced active or retired Space cannot be hard-deleted.
- Every API and record carries exact Space scope; missing scope is not inferred.

### 4.2 Dataset package

A Dataset package is the portable, versioned definition of exactly one
Dataset. Its source layout is:

~~~text
scenarios/managed-datasets/<datasetPackageId>/
├── dataset.yaml
├── schema/
│   └── record.yaml
├── contracts/
├── mappings/
├── projections/
├── policies/
├── sources/
└── assets/
~~~

dataset.yaml is the sole entry point. It explicitly lists referenced files;
recursive discovery is forbidden. Paths must be package-relative, normalized,
content-digested and unable to escape the package. Packages must not contain
credentials, live records, backend settings or runtime state.

~~~yaml
schemaVersion: pockethive.dataset-package/v1
packageId: reusable-records
version: 1
recordSchemaPath: schema/record.yaml
contractPaths:
  - contracts/traffic.yaml
requiredStorageCapabilities:
  - SNAPSHOT_READ
  - SHARED_SELECTION
supportedStorageProfiles:
  - MANAGED_RECORDS_V1
sourceBindingPaths:
  - sources/card-provisioning.yaml
mappingPaths:
  - mappings/card-result.yaml
projectionPaths:
  - projections/card-traffic.yaml
policyPaths:
  - policies/supply.yaml
assetPaths: []
~~~

Lifecycle: DRAFT -> PUBLISHED -> RETIRED.

- Drafts may be created, edited and deleted.
- Publishing freezes an immutable version and digest.
- Editing published content creates a new version.
- Retirement prevents new bindings but preserves existing frozen bindings.
- Packages never select a deployment, Dataset Space, credential or backend.

### 4.3 Dataset registration

A registration joins one published package version to one active Dataset
Space. It provides the Space-local alias and selects exactly one storage
adapter, settings reference and capability profile.

~~~yaml
schemaVersion: pockethive.dataset-registration/v1
registrationId: performance-test-reusable-records
datasetSpaceId: performance-test
datasetSpaceVersion: 2
datasetPackageId: reusable-records
datasetPackageVersion: 1
datasetPackageDigest: sha256:4c91d9e2c0a7a8f8b1418398dd2de18290965d06e1a21e70eeaf7dcff14b82ad
datasetAlias: reusable-records
storage:
  adapter: POSTGRESQL
  settingsRef: datasets-postgres-primary
  capabilityProfile: MANAGED_RECORDS_V1
~~~

There is no default adapter. Missing or incompatible combinations fail
registration. Replacement creates a new immutable version using optimistic
concurrency; it never edits a running binding in place.

### 4.4 Record schema and Dataset Contract

The package record schema defines the complete canonical record shape,
including types, nullability, keys, relationships, field ownership and
classification.

A DatasetContract/v1 exposes a guaranteed subset for one declared use:

~~~yaml
schemaVersion: pockethive.dataset-contract/v1
contractId: traffic
fields:
  - recordKey
  - accountId
  - cardId
  - expiryDate
~~~

Rules:

- every field must exist in the owning record schema;
- the contract cannot redefine a field;
- fields outside the subset are not exposed;
- contracts are package-local and immutable;
- there is no cross-package inheritance, composition or fallback;
- changing a contract creates a new package version and affects no other
  Dataset.

### 4.5 Dataset reference

A runtime reference is logical and storage-neutral:

~~~yaml
datasetRef:
  datasetSpaceId: performance-test
  datasetAlias: reusable-records
  partition: baseline
  pool: traffic-ready
~~~

partition and pool are required, descriptor-defined values. They are not tags,
table partitions, Redis keys, physical shards or wildcards.

### 4.6 Scenario requirement and binding

A scenario declares only its own requirements:

~~~text
scenarios/bundles/<scenarioId>/
├── scenario.yaml
└── datasets/
    └── requirements.yaml
~~~

~~~yaml
schemaVersion: pockethive.dataset-requirements/v1
requirements:
  - requirementId: reusableRecords
    required: true
    requiredFields:
      - accountId
      - cardId
    allocation: SHARED
    requiredRemainingValidity: PT30M
    requiredStorageCapabilities:
      - SNAPSHOT_READ
      - SHARED_SELECTION
    deliveryEffectProfile: AT_LEAST_ONCE
    fitnessContractRef: traffic-ready@1
    selectionPolicyRef: round-robin@1
    trustedTimePolicyRef: qualified-host-time@1
    bindingSlotsRef: reusable-records-http-slots@1
~~~

The requirement contains no concrete Dataset, storage identifier, other
scenario, swarm or consumer identity. scenario.yaml.requires.datasets is not a
supported location.

A versioned ScenarioBinding explicitly maps each requirementId to one concrete
datasetRef, published package version and digest, package-local contractId, and
resolved policy/projection versions.

Consumer registration is therefore **preconfigured through the binding**, not
automatic discovery. Each scenario is unaware of other scenarios. Missing,
duplicate, extra, wildcard, incompatible or cross-Space mappings fail before
runtime creation.

Orchestrator materializes an immutable ResolvedDatasetBinding/v1 for the run.
It freezes identities, versions, policy references and capability choices, but
not live records, material generations, counts, health or supply operations.

### 4.7 Record and material generation

A record is one stable logical test entity or aggregate. Refreshable values are
stored as immutable material generations with usableFrom, refreshAt, expiresAt,
conservative usableUntil, provenance and fencing information.

Refresh or correction appends a generation. It never mutates the meaning of a
generation already visible in an immutable Dataset revision.

### 4.8 Fitness Contract

Each binding freezes one allowed, immutable Dataset Fitness Contract. It
evaluates the exact declared use against:

- schema and required relationships;
- partition, pool and minimum eligible count;
- freshness and remaining validity;
- provenance and classification;
- retention and permitted destination/SUT Environment.

The result is PASS, FAIL or UNKNOWN. Count alone is never fitness. Only a
current PASS can satisfy initial readiness. UNKNOWN fails closed for new
activation; an already active local view may continue only until the
contract's previously established safeUntil.

## 5. Component ownership

| Component | Owns | Must not own |
|---|---|---|
| Scenario Manager | Spaces, package drafts/versions, registrations, scenario requirements/bindings, validation and authorisation metadata | runtime records, supply decisions, SUT calls |
| Orchestrator | external Dataset API, resolved bindings, admission and coordination | measured-path selection, SUT-specific workflows |
| Managed Dataset module | records, revisions, fitness, supply state, schedules, reservations, receipts, outbox and recovery | swarm topology, SUT calls, UI/MCP policy bypass |
| Swarm Controller | per-swarm topology, current fenced WorkItem route, lifecycle/status aggregation and Dataset readiness guard | global Dataset inventory or refresh policy |
| Producer swarm | configured SUT provision/refresh/validate/deprovision calls and result submission | demand calculation or Dataset authority |
| Traffic workers | background hydration, local bounded projections, selection and local expiry checks | direct PostgreSQL access |
| PocketHive MCP/UI | authorised commands and read models backed by product services | independent authority, raw records, SQL/Redis access |

Shared modules remain narrow:

- common/dataset-contracts: canonical generated contract types and enums;
- common/swarm-model: resolved binding references in plans;
- common/worker-sdk: Dataset input/output adapters and local projections;
- common/manager-sdk: readiness aggregation and DatasetGuard;
- common/auth-contracts: resource and permission identifiers.

Persistence entities and domain policy do not move into common.

## 6. Runtime architecture

~~~mermaid
flowchart LR
  SM[Scenario Manager] -->|published definitions and bindings| API

  subgraph ORCH[orchestrator-service]
    API[Authorised Dataset API]
    DM[Managed Dataset application/domain]
    PGAD[PostgreSQL adapter]
    OUT[Transactional outbox relay]
    LIFE[Swarm lifecycle adapter]
    API --> DM
    DM --> PGAD
    DM --> LIFE
    PGAD --> OUT
  end

  PG[(Existing PostgreSQL)]
  CP[Existing ph.control]
  WQ[Controller-owned WorkItem route]
  SC[Swarm Controller]
  PROD[Producer swarm]
  TRAFFIC[Traffic workers]

  PGAD <--> PG
  LIFE <--> CP
  CP <--> SC
  OUT -->|DATASET_SUPPLY| WQ
  SC -->|declares and fences| WQ
  WQ --> PROD
  PROD -->|claim/checkpoint/upsert| API
  TRAFFIC -->|background snapshot hydration| API
~~~

### 6.1 Two-stage supply rule

Supply is dispatched only through this sequence:

1. The reconciler observes a durable deficit.
2. It asks the existing lifecycle authority to ensure the configured producer
   swarm is running.
3. Current status must prove the exact swarm/controller incarnation, plan,
   workload, input and route lease are ready.
4. The Dataset transaction reserves bounded capacity and writes an outbox row.
5. The relay publishes DATASET_SUPPLY to the exact controller-owned WorkItem
   route.
6. The producer claims the operation and submits its result through the
   authorised Dataset API.
7. A typed durable storage receipt, not a control event, Rabbit acknowledgement
   or container state, completes the operation.

Missing, stale or expired readiness publishes no work. swarm-start never
carries a Dataset demand, and DATASET_SUPPLY never starts a swarm.

### 6.2 Hot-path rule

Workers hydrate immutable bounded projections in the background and atomically
swap the active local revision. A measured request may perform only local
selection, projection lookup, remaining-validity check, replay/idempotency
check and final request materialisation.

PostgreSQL, Redis, RabbitMQ, Orchestrator APIs and credential providers are
forbidden on the measured request thread.

## 7. Storage adapters

| Adapter | Profile | Scope |
|---|---|---|
| POSTGRESQL | MANAGED_RECORDS_V1 | Core MVP: records, immutable revisions, operations, schedules, fences, receipts and transactional outbox |
| REDIS | REDIS_COLLECTION_V1 | Deferred: explicit collection semantics only; no relational/revision/proof claim unless separately implemented and qualified |

The registration selects one adapter and one settings reference. Required
capabilities are checked at package, registration and binding admission.
Missing operations are not emulated.

Existing REDIS_DATASET and REDIS worker behavior remains unchanged. It joins
the Managed Dataset umbrella only through an explicit Redis registration;
there is no automatic conversion.

### 7.1 PostgreSQL authority

The core profile uses the existing PostgreSQL deployment with a dedicated
schema, least-privilege role, explicit connection budget and separate pool.
No new database container is introduced. Files may hold disposable caches or
diagnostics only.

Minimum logical persistence:

| Aggregate/table | Purpose |
|---|---|
| dataset_record | stable record identity and immutable attributes |
| dataset_material_generation | immutable refreshable material |
| dataset_revision | committed scope revision and counts/digests |
| dataset_membership_version | revision-visible record/generation/state membership |
| dataset_fitness_evaluation | immutable PASS/FAIL/UNKNOWN decision and evidence refs |
| dataset_supply_policy_activation | sole active policy pointer per inventory scope |
| dataset_fill_cycle | durable hysteresis and reservation accounting |
| dataset_supply_operation | bounded idempotent work, attempts, fences and terminal receipt |
| dataset_lifecycle_schedule | durable due time, claim, retry and backoff |
| dataset_source_step_checkpoint | restart-safe external-step progress |
| dataset_operation_staging | bounded encrypted intermediate source values |
| dataset_outbox | atomic publication intent |
| dataset_refresh_attempt | refresh claim and result |

Post-MVP leases and business-state transitions use separate aggregates; they
must not be simulated with mutable list operations in the MVP.

### 7.2 Revision invariants

- One record belongs to exactly one partition and inventory-owning pool.
- At most one generation is eligible for new selection at a revision.
- Membership uses immutable revision visibility, not mutable current rows.
- Snapshot pages are scoped to one exact revision and use stable keyset
  pagination.
- Old revisions/generations remain until no valid snapshot or evidence
  reference requires them.
- Exactly one Supply Policy is active per inventory scope.
- Capacity is reserved transactionally before work is published.

## 8. Lifecycle and supply

### 8.1 States

Record state:

~~~text
READY | STANDBY | QUARANTINED | RETIRED
~~~

Material generation state:

~~~text
PENDING | USABLE | REFRESH_DUE | EXPIRED | REVOKED | FAILED
~~~

Supply operation state:

~~~text
PLANNED -> RESERVED -> DISPATCHED -> CLAIMED
        -> SUCCEEDED | PARTIAL | FAILED | TIMED_OUT | CANCELLED | UNCERTAIN
~~~

Transitions are validated by domain commands with expected version and fencing
epoch. Infrastructure code must not update state directly. Ambiguous external
effects become UNCERTAIN and block blind retry until reconciled.

### 8.2 Demand

For one exact Dataset/partition/pool:

~~~text
deficit =
  max(0, targetReady - eligible - reserved - safelyInFlight)

request =
  min(deficit, batchLimit, providerAllowance, storageAllowance)
~~~

The Supply Policy owns minimumReady, targetReady, maximumReady, watermarks,
batch bounds, validity requirements and refresh policy. Scheduler and
infrastructure limits are safety bounds, not a second demand controller.

The first qualification profile is:

- targetReady: 50000;
- maximumReady: 55000;
- at least two concurrent traffic swarms;
- safe live target increase and decrease;
- no central lookup on a measured 1,000 request/second path.

These values are release requirements until measured; they are not current
capacity claims.

### 8.3 Continuous reconciliation

Lifecycle schedules are durable. Reconciliation:

- replenishes below policy thresholds;
- refreshes before usableUntil;
- validates externally mutable records;
- replaces invalid or expiring records;
- retires and deprovisions according to policy;
- applies stable jitter, provider quotas and bounded concurrency;
- resumes safely after restart.

Target increases open new bounded supply. Target decreases publish a new
constraint-preserving revision that moves excess records to STANDBY; they do
not delete in-use data.

### 8.4 External side effects

Every source operation has a stable idempotency key, attempt fence, deadline,
capacity reservation and operating-profile limits. If a provider cannot offer
idempotency, the source profile must provide authoritative status
reconciliation or reject automatic retry.

Cumulative create/replace/unresolved/deprovision budgets survive restarts and
binding changes. Exceeding a budget opens a fail-closed circuit and requires an
authorised, audited reset.

## 9. Producer contracts and result routing

### 9.1 Worker capabilities

DATASET_SUPPLY input carries only bounded metadata: operation ID and kind,
exact Dataset scope, requested count, source binding version, deadline, route
and attempt fences, and idempotency context. It contains no record values,
credentials, ciphertext, provider bodies or free-form errors.

DATASET_UPSERT is the preferred output. It submits a closed, schema-validated
aggregate to the Dataset API and returns a typed durable receipt containing
accepted, inserted, duplicate and rejected counts plus the resulting Dataset
revision when state changed.

When a worker must keep another primary output, it may explicitly enable the
shared managedDatasetPublisher interceptor. The interceptor:

- uses the same canonical upsert contract and validation;
- stages the Dataset write, publishes the primary output, then finalises;
- treats both outcomes as required and reconciles ambiguous completion;
- is disabled unless configured;
- never silently replaces or duplicates the primary output.

All applicable workers gain the capability through the shared worker SDK;
individual workers do not implement independent variants.

### 9.2 Source result policy

Success is not inferred from transport completion alone. Each source binding
freezes a SourceResultPolicy/v1 that normalizes protocol-specific evidence into
COMPLETED, FAILED, PENDING or UNCERTAIN.

For HTTP, the policy may inspect status, headers and a schema-validated body.
For TCP, it may inspect connection outcome, framed response, protocol status,
acknowledgement and validated response fields. A successful socket write does
not prove business completion.

The policy defines deterministic ordered routes:

~~~yaml
schemaVersion: pockethive.source-result-policy/v1
policyId: card-create-result
transport: HTTP
routes:
  - id: completed
    when:
      normalizedState: COMPLETED
    action:
      type: DATASET_UPSERT
      datasetTarget: primary
  - id: failed
    when:
      normalizedState: FAILED
    action:
      type: DATASET_UPSERT
      datasetTarget: remediation
  - id: unresolved
    when:
      normalizedState:
        anyOf: [PENDING, UNCERTAIN]
    action:
      type: RECONCILE
~~~

Rules:

- one shared evaluator implements the closed policy contract;
- every possible normalized result matches exactly one route;
- route targets are resolved and authorised at binding admission;
- a route cannot construct a Dataset name dynamically;
- only COMPLETED contributes to primary ready supply;
- a conclusive failure may enter a separately configured failed/remediation
  Dataset;
- PENDING and UNCERTAIN remain operation state until reconciled;
- malformed, missing or contradictory evidence is UNCERTAIN, never success.

This gives HTTP and TCP the same lifecycle semantics without pretending their
wire evidence is identical.

## 10. Traffic consumption

MANAGED_DATASET is a source/input capability that selects an opaque local
record reference under the frozen Selection Policy. It does not expose a
database row or backend key.

The final declared processor resolves the purpose-limited material projection
immediately before the SUT write. Selector and material projections are
separate contracts and principals. A worker may access only fields permitted
by its Dataset Contract and projection.

The reusable MVP supports deterministic round-robin selection with a frozen
seed/cursor contract. Random, weighted, exclusive and single-use behavior must
not be inferred from configuration strings.

Workers:

- hydrate bounded pages in the background;
- validate manifest, revision, digest and scope;
- build a new local view before atomic activation;
- retain only the bounded old view needed by in-flight work;
- reject expired, revoked, wrong-Space or wrong-revision material locally;
- stop or throttle only the affected input when no safe record remains;
- report loaded revision and readiness through existing status contracts.

## 11. Readiness and failure behavior

Dataset availability is RECONCILING, READY, DEGRADED, STARVED or BLOCKED.

A required binding is ready only when:

- the exact resolved binding and registration remain authorised;
- its current Fitness result is PASS;
- enough eligible records meet remaining-validity requirements;
- selector and material projections for the same revision are activated by all
  required worker incarnations;
- trusted time is within policy;
- no storage, source, quota or security blocker invalidates use.

Scenario start fails closed when a required Dataset is not ready. Runtime
depletion pauses or throttles only the dependent input before unsafe data can
be selected. Optional Dataset absence is explicit and cannot satisfy a required
gate.

## 12. Durability and recovery

For MANAGED_RECORDS_V1, PostgreSQL is the authority for acknowledged records,
material generations, revisions, activation state, operations, schedules,
reservations, fences, receipts and outbox publication intent.

On Orchestrator restart, the module enters RECONCILING, fences stale claims,
rebuilds due work from PostgreSQL, reconciles ambiguous outbox/operation state,
and permits mutation only after its invariants are restored.

Required claims:

- acknowledged Dataset state survives application/container restart with the
  named persistent volume;
- Rabbit redelivery cannot duplicate an authoritative commit;
- stale worker/controller incarnations cannot mutate newer state;
- an outage causes explicit unavailability, not fallback to files, Redis or
  in-memory state.

The MVP does not claim destructive volume-loss recovery, backup restore, exact
recovery of an interrupted whole-swarm lifecycle handoff, or automatic
reconstruction of every dynamic controller. Those require separate platform
qualification.

## 13. Security

The MVP admits only data classifications approved by the active Dataset Space
and deployment profile. Prohibited secrets or regulated data fail package,
registration or binding admission.

Required controls:

- least-privilege, SUT/Space-scoped authorisation on every API;
- encrypted storage for allowed sensitive material with versioned key
  references and authenticated encryption;
- credentials referenced by opaque authRef, never stored in packages,
  WorkItems, logs, metrics, MCP responses or UI state;
- metadata-only Rabbit messages;
- strict schemas, bounded values and closed enums at every boundary;
- source destination/egress allowlists and bounded response parsing;
- redacted, bounded error codes rather than provider bodies/free text;
- retention and deprovision policy with auditable tombstones;
- no raw record, SQL, Redis-query or secret-retrieval MCP/UI operations;
- no browser persistence of record values or credentials.

Agents are untrusted clients. HiveGate governance may approve a request, but
does not replace product-side authentication, authorisation, validation or
admission. Tool names and model decisions confer no authority.

## 14. APIs, MCP and operator UI

Implementation must define one canonical executable schema and product HTTP
contract before handlers and clients. Generated types derive from that source;
prose examples are not a second contract.

### 14.1 Authoring operations

Scenario Manager provides real, authorised operations for:

- list/get/create/update/delete Dataset Space drafts;
- activate/retire Dataset Spaces;
- list/get/create/update/delete Dataset package drafts;
- validate/upload/publish/retire Dataset package versions;
- list/get/create/replace/retire Dataset registrations;
- validate scenario requirements and edit explicit scenario bindings.

Delete is limited to unreferenced drafts. Published/active history is retired,
not overwritten.

PocketHive MCP delegates to those services. Canonical package tools are
dataset_package_list, dataset_package_validate, dataset_package_upload,
dataset_package_publish and dataset_package_retire.

Mutations are idempotent and require ordinary product permissions plus
HiveGate approval when invoked by an agent.

### 14.2 Runtime reads

Orchestrator provides authorised, paginated and redacted views for:

- Dataset inventory and detail;
- active registration/adapter/profile;
- fitness and readiness reasons;
- supply operations and lifecycle health;
- consumer bindings and applied revisions;
- bounded evidence/proof;
- Swarm Inspector Dataset dependencies.

MCP and UI use these same read models. They must not query repositories,
PostgreSQL or Redis directly.

### 14.3 UI requirements

The production UI supports add, edit, list, validate, publish/activate, retire
and allowed draft removal. Every dynamic fact comes from a canonical product
service.

Required states include loading, empty, filtered-empty, validation failure,
conflict, denied, revoked, stale, reconciling, partial, unavailable and
incompatible schema. Release bundles contain no dummy Dataset data or fixture
fallback.

## 15. Observability

Metrics use bounded identifiers/reason codes and never record values,
credentials, provider text or unbounded labels.

Minimum module telemetry:

- eligible, standby, quarantined and expired counts;
- Fitness state, age, reason codes and nearest safeUntil;
- target, deficit, reserved and in-flight supply;
- operation outcomes, duplicates, uncertainty and reconciliation age;
- refresh, validation and deprovision outcomes and lag;
- source quota, side-effect budget and circuit state;
- revision, projection activation and worker acknowledgement lag;
- snapshot rows, bytes, latency and digest rejection;
- lifecycle schedule due, claim and retry lag;
- outbox backlog, publish return/nack/unknown and retry;
- database pool, lock, transaction, storage and free-space pressure;
- budget/bulkhead admission, queue depth and deadline miss.

Minimum worker status includes loaded/active revision, local eligible count and
memory bytes, pool misses, blocked expired selections, hydration latency/failure,
measured-thread central-I/O attempts, and input readiness/dispatch state.

All logs and evidence propagate PocketHive correlation identifiers according to
the existing correlation/idempotency rules.

## 16. Deployment and capacity

The feature adds no PocketHive application container. It uses the existing
Orchestrator, Scenario Manager, PostgreSQL, RabbitMQ, workers, Swarm Controller,
MCP and UI.

The Dataset module has bounded executors and separate bulkheads for lifecycle,
commit, hydration and evidence work. Resource limits must protect existing
Orchestrator journal/control SLOs. Dataset readiness must report unavailable
rather than starving core orchestration.

Docker health checks do not themselves guarantee restart. Compose and runtime
configuration must declare the intended health, restart, dependency and
persistent-volume behavior explicitly.

## 17. Implementation sequence

### M0 — contracts

- Freeze canonical package, Space, registration, requirement, binding, worker,
  result-policy, receipt and read-model schemas.
- Add enums/constants before implementation strings.
- Add cross-language validation/TCK fixtures.
- Approve one deterministic, non-sensitive conformance provider/SUT double.

### M1 — authoring and core persistence

- Implement Scenario Manager package/Space/registration/binding services.
- Implement the Orchestrator Dataset module boundaries and PostgreSQL schema.
- Add authorisation, idempotency, revisions, schedules and outbox.
- Deliver real UI/MCP authoring against product services.

### M2 — producer and consumer flow

- Add controller-fenced DATASET_SUPPLY.
- Add DATASET_UPSERT and the optional shared interceptor.
- Add HTTP/TCP Source Result Policy evaluation.
- Add background hydration, local selection and readiness aggregation.
- Run the complete synthetic conformance scenario.

### M3 — qualification

- Pass the MVP acceptance criteria and mandatory test families.
- Publish a reproducible run manifest and evidence index.
- Enable only capability profiles that passed.

Redis, consumable allocation, sensitive-data and platform-recovery profiles are
separate later milestones.

## 18. Acceptance criteria

The MVP is releasable only when all of the following have linked evidence.

### Functional

- Packages under scenarios/managed-datasets validate, publish and remain
  immutable by version/digest.
- A scenario-local datasets/requirements.yaml maps explicitly through one
  Scenario Binding; no consumer auto-discovery or cross-scenario configuration
  exists.
- Package-local contracts validate required fields without affecting another
  Dataset.
- Adapter, settings and capability profile are explicit at registration.
- Existing Redis scenarios remain unchanged; unsupported managed Redis
  registration fails clearly.
- HTTP and TCP outcomes deterministically reach completed, failed or
  reconciliation state.
- Completed and failed records may target separately authorised Datasets.
- Primary output plus optional interceptor behavior is deterministic and
  recoverable.
- UI and MCP add/edit/list/validate/publish/retire/remove-draft operations use
  real product data and enforce the same permissions.

### Lifecycle and correctness

- Only a durable typed receipt completes supply.
- Duplicate commands and Rabbit redelivery do not duplicate authoritative
  records or external effects.
- Ambiguous provider results enter UNCERTAIN and are reconciled before retry.
- Expired, revoked, wrong-Space or unfit material is never selected.
- Start and continued use require the exact Fitness/activation conditions.
- Restart resumes schedules, operations and outbox without silent loss.
- Stale route, attempt and worker fences are rejected.

### Performance and operability

- The measured request thread makes zero Dataset API, database, Redis,
  RabbitMQ or credential-provider calls.
- The named profile sustains 50,000 eligible records, a 55,000 maximum, two
  traffic swarms and 1,000 requests/second for the agreed qualification period.
- Live target increase/decrease converges without unsafe deletion or duplicate
  supply.
- Dataset load does not breach agreed Orchestrator control/journal SLOs.
- Metrics, logs, UI and MCP expose bounded reasons sufficient to diagnose
  readiness and lifecycle failures without exposing record values.

### Security

- Cross-SUT, cross-Space, wildcard, stale-version and unauthorised requests
  fail with zero side effects.
- Secrets and record material do not appear in packages, Rabbit messages,
  logs, metrics, browser persistence, MCP results or evidence manifests.
- Unsupported classification/profile combinations fail admission.
- Agent paths cannot bypass product authorisation or mutate runtime records.

## 19. Mandatory test families

| Area | Required coverage |
|---|---|
| Contracts | valid/min/max values; missing, extra, unresolved and incompatible fields; path escape; immutable digest; package-local contract subsets |
| Binding | missing/duplicate/extra requirement; wrong SUT/Space; retired version; unsupported adapter/capability; no alias discovery |
| Supply | watermark/target/maximum boundaries; capacity reservation; duplicate and late results; target changes; concurrent producers |
| Source | HTTP and TCP success/failure/pending/ambiguous evidence; malformed response; provider timeout; idempotency/status reconciliation; completed/failed routing |
| Storage | revision isolation; concurrent commits; schedule/outbox recovery; database timeout/lock/full disk; retention race |
| Rabbit/control | lifecycle-before-supply ordering; canonical routing; stale route fence; return/nack/unknown confirm; duplicate/redelivery; no alternate lane |
| Workers | snapshot corruption, mixed revision, activation race, memory bound, restart, expiry boundary and central-I/O detector |
| Readiness | Fitness PASS/FAIL/UNKNOWN; depletion, recovery, trusted-time loss, wrong worker incarnation and optional/required behavior |
| Security | authorisation scope, hostile paths/content, redaction canaries, secret scans, browser storage, MCP/UI bypass attempts |
| UI/MCP | real CRUD/lifecycle data; empty/error/conflict/denied/stale/reconciling states; pagination and idempotency |
| Performance | feature-off/idle/active comparison, 50k/55k profile, two-swarm 1k RPS, refresh storm, restart and 24-hour soak |

Tests must use official ingress/API paths in accordance with PocketHive rules.
Every release requirement has a test-to-evidence link. Missing, contradictory
or self-referential evidence is not a pass.

## 20. Main risks and mitigations

| Risk | Mitigation |
|---|---|
| Central store harms traffic | worker-local immutable projections and a measured-thread I/O detector |
| Orchestrator co-location causes starvation | bounded pools/executors/bulkheads and control-plane SLO gate |
| Producer oversupply | transactional reservations, maximum rows/bytes and provider budgets |
| Ambiguous external effect duplicates data | stable idempotency/status reconciliation or UNCERTAIN quarantine |
| Rabbit/DB dual-write loses work | PostgreSQL transactional outbox and idempotent receipts |
| Scenario coupling | bundle-local requirements plus explicit per-scenario bindings |
| Contract change affects many Datasets | contracts remain immutable and package-local |
| Redis weakens lifecycle guarantees | explicit capability profile; reject unsupported requirements; no fallback |
| Count-only readiness accepts bad data | immutable Fitness Contract and exact PASS gate |
| Refresh/clock failure serves expired data | conservative usableUntil, trusted-time policy and local final check |
| MCP/UI becomes another authority | same product services, least privilege, redacted read models and no raw store access |
| Scope grows into a new platform | core reusable PostgreSQL MVP; named deferred profiles |

## 21. Decision summary

The implementation direction is:

- standalone Dataset packages under scenarios/managed-datasets/;
- scenario-local requirements under each bundle's datasets/ directory;
- explicit per-scenario bindings, never automatic consumer discovery;
- package-local immutable Dataset Contracts;
- shared SUT-scoped Dataset Spaces and deployment registrations;
- an Orchestrator-hosted, hexagonally bounded Dataset module;
- PostgreSQL MANAGED_RECORDS_V1 as the explicit core profile;
- Redis as an explicit, separately qualified adapter profile;
- source swarms for SUT-aware creation and lifecycle work;
- DATASET_UPSERT preferred, shared interceptor optional;
- deterministic HTTP/TCP result policies with completed, failed and
  reconciliation routes;
- background hydration and worker-local request-path access;
- existing ph.control lifecycle plus controller-owned WorkItem supply;
- real product-backed MCP/UI authoring and inspection;
- evidence-gated capacity, recovery and security claims.

The design is ready for team review. Runtime support may be claimed only after
the contracts are implemented and section 18 passes.

## 22. References

- [PocketHive architecture](../ARCHITECTURE.md)
- [SUT + Dataset + Simulation Model](../architecture/sut-dataset-simulation-model.md)
- [Scenario contract](../scenarios/SCENARIO_CONTRACT.md)
- [Scenario variables](../scenarios/SCENARIO_VARIABLES.md)
- [Correlation and idempotency](../correlation-vs-idempotency.md)
- [Control-plane testing](../ci/control-plane-testing.md)
- [Managed Datasets team design overview](managed-datasets-team-design-overview.md)
