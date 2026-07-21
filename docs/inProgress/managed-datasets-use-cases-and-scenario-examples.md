# Managed Datasets — Components, Use Cases and Scenario Examples

Status: in progress — visualisation guide for the design-ready proposal;
cross-functional approval is pending

This document shows where each component sits, what makes records flow, how
common long-running scenarios behave, and how the target scenario configuration
could look. It is intentionally generic and uses no organisation-specific data
fields.

> **Architecture rule:** Managed Datasets add no new control, data or Dataset
> messaging plane. Swarm lifecycle stays on the existing `ph.control` plane;
> bounded source work reuses the existing controller-owned WorkItem route; the
> Dataset API is an application boundary over the Dataset's explicitly selected
> storage adapter. PostgreSQL is the recommended full managed-records adapter;
> Redis is a separately capability-gated adapter, never a fallback.

## 1. The idea in one picture

```mermaid
flowchart LR
  USER[Operator or approved API client]
  SM[Scenario Manager<br/>packages, Spaces, registrations,<br/>local requirements and bindings]

  subgraph O[Orchestrator service]
    DS[Managed Dataset application<br/>desired state, health and reconciliation]
    LIFE[Existing swarm lifecycle application]
    API[Dataset API]
    OUTBOX[Transactional outbox relay]
  end

  STORE[(Explicit Dataset storage adapter<br/>PostgreSQL or Redis capability profile)]

  subgraph SC[Swarm Controller]
    CTRL[Lifecycle and topology authority]
  end

  subgraph R[Existing RabbitMQ]
    CONTROL[ph.control<br/>the one swarm control plane]
    WORK[Existing controller-owned WorkItem route]
  end

  subgraph PS[Producer swarm — often called the seeder]
    PIN[DATASET_SUPPLY input]
    FLOW[Configured source flow]
    POUT[DATASET_UPSERT output]
    PIN --> FLOW --> POUT
  end

  subgraph C1[Consumer swarm A]
    IN1[MANAGED_DATASET input]
    TRAFFIC1[Normal traffic flow]
    IN1 --> TRAFFIC1
  end

  subgraph C2[Consumer swarm B]
    IN2[MANAGED_DATASET input]
    TRAFFIC2[Normal traffic flow]
    IN2 --> TRAFFIC2
  end

  USER --> DS
  SM -->|admitted immutable definitions| DS
  DS <--> STORE
  DS -->|ensure producer is running| LIFE
  LIFE --> CTRL
  CTRL <--> CONTROL
  CONTROL <--> PS
  CONTROL <--> C1
  CONTROL <--> C2
  DS -->|committed bounded intent| OUTBOX
  OUTBOX -->|DATASET_SUPPLY metadata| WORK
  WORK --> PIN
  POUT -->|claim, checkpoint, commit and receipt| API
  API --> DS
  IN1 -->|background snapshot hydration| API
  IN2 -->|background snapshot hydration| API
```

The producer does not feed either consumer directly. It feeds the authoritative
Dataset. Each consumer receives the Dataset revision it is authorised to use.

## 2. Where responsibilities sit

| Component | Runs in | Owns | Does not own |
|---|---|---|---|
| Dataset package, Space, registration and scenario binding | Scenario Manager | Standalone package lifecycle; deployment-scoped Space authority policy; explicit package-to-Space/storage registration; validation of each bundle's requirements; and one mapping per Scenario Binding | A Dataset-level consumer list, runtime records or RabbitMQ topology |
| Managed Dataset application | Orchestrator | Desired target, observed supply, health, reconciliation, operations, schedules and evidence | Swarm lifecycle implementation or source-specific logic |
| Existing lifecycle application | Orchestrator | `ensureRunning`, start/stop intent and current readiness result | Dataset deficit or record truth |
| Swarm Controller | Swarm Controller service | Applied swarm lifecycle, exact worker incarnation and controller-owned WorkItem topology | Dataset target or committed records |
| `ph.control` | Existing RabbitMQ | Delivery of canonical swarm lifecycle/status events | Dataset records or supply counts |
| WorkItem route | Existing RabbitMQ | Delivery of bounded `DATASET_SUPPLY` work to an already-ready producer | Lifecycle authority or completion proof |
| Producer swarm | Normal PocketHive swarm | Executes the configured source flow for one bounded operation | Choosing the target or declaring queues |
| Dataset API | Orchestrator application boundary | Authorised claim, checkpoint, commit, snapshot and receipt operations | RabbitMQ routing decisions |
| Storage adapter | Orchestrator infrastructure | Only the operations promised by its published capability profile | Domain policy, adapter fallback or inflated durability claims |
| PostgreSQL adapter | Orchestrator persistence | `MANAGED_RECORDS_V1` durable truth and transactional outbox | Worker execution |
| Redis adapter (deferred) | Orchestrator persistence | Explicit `REDIS_COLLECTION_V1` collection operations and receipts only after separate qualification | Core MVP support or undeclared relational/revision/outbox/proof semantics |
| Consumer swarm | Normal PocketHive swarm | Background hydration and local selection during traffic | Direct PostgreSQL access or Dataset mutation |

## 3. What makes records flow

There are three independent flows. They must not be collapsed into one
scheduler or one message.

```mermaid
flowchart TB
  subgraph A[1 — Dataset lifecycle reconciliation]
    A1[Target, expiry or record event] --> A2[Calculate durable deficit]
    A2 --> A3[Start or reuse producer]
    A3 --> A4[Dispatch bounded supply work]
    A4 --> A5[Commit records and recalculate]
  end

  subgraph B[2 — Scenario lifecycle]
    B1[Scenario plan] --> B2[Start, configure or stop swarm through ph.control]
  end

  subgraph C[3 — Traffic pacing]
    C1[Running consumer with local Dataset view] --> C2[Emit traffic at configured rate]
  end
```

### Dataset triggers

The Dataset reconciler wakes immediately when durable observed state changes
and also runs a periodic repair sweep. A wake-up is a request to reassess; it
does not itself create records.

```text
deficit = max(0, targetReady - eligibleTotal - pendingExpected)
```

Supply is dispatched only when the deficit is positive, policy permits it, the
producer is freshly ready and a current controller-issued WorkItem route exists.

| Trigger or observation | Typical operation | Expected behavior |
|---|---|---|
| New Dataset is below its initial target | `PROVISION_NEW` | Warm to `minimumReady`, admit consumers, then continue toward `targetReady` |
| Eligible supply crosses `lowWatermark` | `PROVISION_NEW` or `REPLACE_RECORD` | Open a bounded fill cycle; Dataset may remain `READY` while above `minimumReady` |
| Target is increased | `PROVISION_NEW` | Create only the incremental deficit under the newest target generation |
| Target is decreased | No source operation initially | Stop new supply and move deterministic safe surplus to `STANDBY`; do not delete active material |
| Material is approaching `refreshAt` or `expiresAt` | `REFRESH_MATERIAL` | Refresh early enough to preserve the declared validity/reserve horizon |
| A record is invalid, revoked or quarantined | `REPLACE_RECORD` | Remove it from eligibility and replace only the resulting safe-supply deficit |
| Periodic validation is due | `VALIDATE_RECORD` | Retain eligibility only when the validation receipt is conclusive and current |
| An external entity reaches retirement policy | `DEPROVISION_ENTITY` | Execute governed cleanup independently of ordinary replenishment |
| A producer operation partially fails | Same original operation identity | Reconcile committed effects; resume or redrive only when the durable ledger proves it is safe |
| A notification or process restart was missed | No automatic operation kind | Periodic repair sweep reconstructs the decision from PostgreSQL truth |

`REPLENISH` is not an MVP wire operation. “Replenishment” is the operator idea;
the system maps its reason to one of the explicit operation kinds above.

## 4. Use case A — fixed-size Dataset used in a loop

For the MVP shared mode, use is non-destructive. A consumer selects from an
immutable local revision; the record never leaves the Dataset, so it is not
“added back” after each request.

```mermaid
sequenceDiagram
  participant D as Dataset application
  participant P as PostgreSQL
  participant C as Consumer swarm
  participant S as System under test
  participant R as Reconciler
  participant SP as Producer swarm

  D->>P: Publish eligible revision at target size
  C->>D: Hydrate authorised snapshot before traffic
  D-->>C: Immutable local view + validity boundary
  loop Measured traffic
    C->>C: Select next local record
    C->>S: Execute normal request
    S-->>C: Response
    Note over C,P: No per-request Dataset call and no record removal/add-back
  end
  P-->>R: Expiry, invalidation or target observation
  R->>R: Calculate bounded deficit
  R->>SP: DATASET_SUPPLY after lifecycle/readiness gate
  SP->>D: Idempotent Dataset commit
  D->>P: Publish replacement/refresh revision
  C->>D: Hydrate and atomically activate newer revision
```

If a later approved profile needs exclusive use, acquire and release must use a
fenced Dataset lease API. RabbitMQ acknowledge/requeue must never implement the
business return.

### If “consume and add back” means checkout and return

That is a different allocation mode, not ordinary shared reuse. It is a
separately gated future profile:

```mermaid
sequenceDiagram
  participant C as Consumer swarm
  participant A as Dataset API
  participant P as PostgreSQL

  C->>A: Acquire exclusive batch
  A->>P: AVAILABLE → LEASED<br/>holder + expiry + fencing epoch
  A-->>C: Opaque lease token + local batch
  C->>C: Use records locally
  C->>A: Release with exact token and fence
  A->>P: LEASED → AVAILABLE
  alt Consumer crashes or lease expires
    A->>P: Reconcile expiry; reject stale holder
    A->>P: Return safely or quarantine according to policy
  end
```

The Dataset target still describes membership. Leasing changes availability,
not membership, and does not automatically create replacement records. This
profile must not ship until stale-holder fencing, expiry and recovery tests
pass; the recommended MVP remains `SHARED`.

## 5. Use case B — one producer prepares data for two consumer swarms

```mermaid
flowchart LR
  POLICY[Target policy<br/>1,000 eligible]
  REC[Dataset reconciler]
  SEED[Producer swarm]
  API[Dataset API]
  DB[(One authoritative Dataset revision)]
  A[Consumer scenario A<br/>local snapshot A]
  B[Consumer scenario B<br/>local snapshot B]

  POLICY --> REC
  REC -->|bounded deficit| SEED
  SEED -->|idempotent commits| API
  API --> DB
  DB -->|authorised snapshot| A
  DB -->|same authorised revision| B
```

The producer runs once for the shared deficit, not once per consumer. Consumers
may use different traffic rates and local selection offsets, but they do not
own separate durable copies of the Dataset.

## 6. Use case C — continuous 24/7 run with expiring records

```mermaid
sequenceDiagram
  participant T as Trusted time / schedule
  participant D as Dataset reconciler
  participant L as Existing lifecycle application
  participant C as ph.control / Swarm Controller
  participant P as Producer swarm
  participant W as Existing WorkItem route
  participant DB as Dataset API / selected store
  participant S as Consumer swarms

  loop Periodic reconciliation and durable time events
    T->>D: Records approach refreshAt/expiresAt
    D->>DB: Recalculate eligible supply and reserve horizon
    alt Safe reserve remains
      D->>L: ensureRunning(producer)
      L->>C: swarm-start only if required
      C-->>L: fresh RUNNING + input-ready + current route
      D->>W: bounded REFRESH_MATERIAL or REPLACE_RECORD
      W->>P: deliver at least once
      P->>DB: checkpoint and durable receipt
      DB-->>S: newer revision available for background activation
    else Safe reserve is threatened
      D->>DB: Set DEGRADED and continue bounded recovery
      DB-->>S: Keep only still-safe local material active
    else No safe material remains
      D->>DB: Set STARVED
      DB-->>S: Pause affected Dataset input; fail closed
    end
  end
```

The source operation should begin before expiry, using `minimumValidity` and
`reserveHorizon`. Waiting until the last record expires turns routine refresh
into an outage.

## 7. Dataset and producer lifecycles

### Dataset availability

```mermaid
stateDiagram-v2
  [*] --> INITIALISING
  INITIALISING --> WARMING: first reconciliation complete
  WARMING --> READY: minimum + Fitness + validity satisfied
  READY --> READY: below low watermark but safe; replenish in background
  READY --> DEGRADED: safe data remains but reserve/minimum/evidence is threatened
  DEGRADED --> READY: recovery receipt restores safe threshold
  DEGRADED --> STARVED: no safe eligible record / proof horizon exhausted
  STARVED --> WARMING: bounded recovery starts
  WARMING --> READY: recovery threshold restored
  WARMING --> ERROR: structural lifecycle failure
  DEGRADED --> AUTH_REQUIRED: source recovery needs authorised credential action
  AUTH_REQUIRED --> WARMING: authorised action completed
  ERROR --> WARMING: operator resolves structural fault
```

### Producer runtime and work are separate

```mermaid
stateDiagram-v2
  state "Swarm runtime" as SR {
    [*] --> SR_STOPPED
    state "STOPPED" as SR_STOPPED
    state "STARTING" as SR_STARTING
    state "RUNNING" as SR_RUNNING
    state "STOPPING" as SR_STOPPING
    state "FAILED" as SR_FAILED
    SR_STOPPED --> SR_STARTING
    SR_STARTING --> SR_RUNNING
    SR_RUNNING --> SR_STOPPING
    SR_STOPPING --> SR_STOPPED
    SR_STARTING --> SR_FAILED
  }
  state "Producer work while RUNNING" as PW {
    [*] --> PW_IDLE
    state "IDLE" as PW_IDLE
    state "CLAIMED" as PW_CLAIMED
    state "EXECUTING" as PW_EXECUTING
    state "COMMITTING" as PW_COMMITTING
    state "UNCERTAIN" as PW_UNCERTAIN
    state "FAILED" as PW_FAILED
    PW_IDLE --> PW_CLAIMED: bounded DATASET_SUPPLY
    PW_CLAIMED --> PW_EXECUTING
    PW_EXECUTING --> PW_COMMITTING
    PW_COMMITTING --> PW_IDLE: durable receipt
    PW_EXECUTING --> PW_UNCERTAIN: possible unconfirmed effect
    PW_EXECUTING --> PW_FAILED: conclusive failure
  }
```

`RUNNING + IDLE` is the recommended hot-idle producer state. It is healthy and
does not mean that work is missing.

## 8. What consumers do when the Dataset is degraded

| Dataset state | New consumer start | Already-running consumer | Producer/reconciler action |
|---|---|---|---|
| `READY` | Allowed for the exact admitted binding | Continue with the activated local revision | Replenish in the background when below low watermark |
| `WARMING` | Wait; admission occurs only after the exact binding becomes `READY` | No prior revision: remain paused | Start/reuse producer and fill bounded deficit |
| `DEGRADED` | Block new admission | Continue only with still-valid, already-activated material inside its proven safe horizon; show warning and deadline | Prioritise refresh/replacement and expose blocker |
| `STARVED` | Block | Pause the affected Dataset input and fail closed; unrelated inputs may continue | Recover bounded supply; never substitute stale/unknown data |
| `AUTH_REQUIRED` | Block affected binding | Preserve only material whose existing authorisation and validity remain conclusive | Wait for approved credential/identity action; no AI or automatic bypass |
| `ERROR` | Block | Pause affected input when correctness cannot be established | Surface structural fault and require repair/reconciliation |

Missing or stale evidence is never interpreted as zero, healthy or ready.

## 9. Producer setup options

| Setup | Use when | Recommendation |
|---|---|---|
| Dedicated hot-idle producer per Dataset/source binding | First delivery and most predictable ownership | **Recommended MVP.** Simple readiness, routing, capacity and failure isolation |
| Dedicated producer stopped when idle | Resource saving is proven necessary | Allowed only with an explicit policy; repeat the full start/readiness gate before every supply operation |
| One producer with create, refresh, validate and retire capabilities | The operations share identity, security and capacity boundaries | Accept only when every operation kind is explicitly declared and independently bounded |
| Separate create and refresh producers | Refresh has different credentials, limits or failure modes | Good separation when each has its own exact source binding and route lease |
| One producer serving several Datasets | High-volume platform optimisation | Defer until fairness, isolation, exact routing and durable per-owner `SwarmRunDemand` are qualified |
| Multi-step colocated producer | Intermediate values must not cross generic queues | Use one declared `COLOCATED_SEQUENCE` capability with durable per-item checkpoints |
| CSV-backed producer | A finite versioned catalog drives initial or refreshed records | Treat CSV as the source catalog inside a bounded supply operation; rotation must not create unbounded work |

## 10. YAML status and naming

The following examples show the **target authoring contract** for discussion.
They are not executable in the current branch: the current Worker SDK does not
yet contain `DATASET_SUPPLY`, `MANAGED_DATASET` or `DATASET_UPSERT`, and the
Scenario Manager does not yet admit the proposed source-binding artifact.

Before implementation, each new field must be frozen in one canonical schema
and capability manifest. Implementations must not silently accept a second
shape or fall back to CSV/Redis behavior.

“Seeder” is a useful team label, but the contract calls it a **Dataset
producer** because it can provision, replace, refresh, validate or retire data
throughout a long-running Dataset lifecycle.

## 11. Example — standalone Dataset package with a CSV catalog

The Dataset definition is independent of every scenario bundle:

```text
scenarios/managed-datasets/reusable-records/
├── dataset.yaml
├── schema/record.yaml
├── contracts/traffic.yaml
├── mappings/csv-v1.yaml
├── projections/selector-v1.yaml
├── projections/material-v1.yaml
├── policies/supply-v1.yaml
├── policies/fitness-v1.yaml
├── sources/csv-catalog-v1.yaml
└── assets/reusable-records.csv
```

```yaml
# scenarios/managed-datasets/reusable-records/dataset.yaml
schemaVersion: pockethive.dataset-package/v1
packageId: reusable-records
version: 1
recordSchemaPath: schema/record.yaml
contractPaths: [contracts/traffic.yaml]
requiredStorageCapabilities: [SNAPSHOT_READ, SHARED_SELECTION]
supportedStorageProfiles: [MANAGED_RECORDS_V1, REDIS_COLLECTION_V1]
sourceBindingPaths: [sources/csv-catalog-v1.yaml]
```

```yaml
# schema/record.yaml
schemaVersion: pockethive.dataset-record-schema/v1
fields:
  - { name: recordKey, type: STRING, required: true }
  - { name: accountId, type: STRING, required: true }
  - { name: cardId, type: STRING, required: true }
  - { name: expiryDate, type: DATE, required: true }
naturalKey:
  fields: [recordKey]
```

```yaml
# contracts/traffic.yaml
schemaVersion: pockethive.dataset-contract/v1
contractId: traffic
fields: [recordKey, accountId, cardId, expiryDate]
```

`traffic` belongs only to this Dataset package. Another Dataset may define its
own `traffic` contract, but the objects are unrelated and may not be substituted
by name. Scenario Binding explicitly selects the Dataset package/version and
its local contract ID; validation compares the scenario's required fields with
that exact contract. Editing the contract creates a new version of only this
Dataset package.

Scenario Manager validates the complete package, stores a draft and publishes
an immutable version only through an explicit publish operation. The UI and
PocketHive MCP use the same application services. Published files are never
edited in place.

A deployment registration binds that portable package to its shared authority
boundary and concrete storage:

```yaml
schemaVersion: pockethive.dataset-registration/v1
registrationId: performance-test-reusable-records
datasetSpaceId: performance-test
datasetPackageRef: reusable-records@1
datasetAlias: reusable-records
storage:
  adapter: POSTGRESQL
  settingsRef: datasets-postgres-primary
  capabilityProfile: MANAGED_RECORDS_V1
```

A future Redis deployment registration instead selects:

```yaml
storage:
  adapter: REDIS
  settingsRef: datasets-redis-primary
  capabilityProfile: REDIS_COLLECTION_V1
  collection: { type: LIST, keyRef: reusable-records-ready }
```

`Dataset Space` is deployment-scoped and shared by its registrations. It owns
access policy, SUT scope, classification ceiling, quotas and allowed storage
profiles; it does not own or rewrite package schemas/contracts. The package
contains no `datasetSpaceId` or backend settings.

`REDIS_COLLECTION_V1` is not presented as equivalent to
`MANAGED_RECORDS_V1`. Its published capability descriptor determines whether
snapshot, shared selection, destructive pop, expiry, proof and recovery are
supported. A scenario requiring an absent capability is rejected at binding;
PocketHive never falls back to PostgreSQL or silently changes Redis semantics.
The core MVP exposes Redis registration as unavailable; enablement requires a
separate adapter TCK, recovery and non-interference qualification.

### 11.1 Proposed Dataset source binding

This package artifact defines how bounded source work uses the package's
versioned CSV catalog; it is not worker-local state.

```yaml
id: reusable-records-csv-source
version: 1

target:
  datasetRegistration: SELF
  partition: default
  pool: ready

executionMode: COLOCATED_SEQUENCE
capabilities:
  - PROVISION_NEW
  - REFRESH_MATERIAL
  - REPLACE_RECORD

source:
  type: CSV_CATALOG
  csv:
    assetPath: assets/reusable-records.csv
    expectedRows: 1000
    skipHeader: true
    rotate: true
    maximumRowsPerOperation: 1000
    identityColumn: record_key

mappingRef: reusable-records-csv-mapping-v1
recordSchemaRef: reusable-records-schema-v1
```

`assetPath` is relative to the exact owning Dataset package. Scenario Manager
rejects absolute, traversing, out-of-`assets/`, missing, non-file or
symlink-escaping values and freezes the file digest. Orchestrator resolves the
validated path once to the package mount only in the immutable runtime plan
supplied to the existing CSV worker. The authored contract never contains a
container path and never accepts `filePath` as an alternate form.

`rotate: true` means the producer can continue from the beginning of the
catalog when a later bounded operation requires it. It does not mean “emit
forever”: `requestedCount` and `maximumRowsPerOperation` bound each operation.
Stable `record_key` plus the Dataset operation identity makes repeat processing
idempotent.

### 11.2 Proposed supply policy

```yaml
id: reusable-records-supply
version: 1

target:
  datasetRegistration: SELF
  partition: default
  pool: ready

sourceBindingRef: reusable-records-csv-source@1

supplyPolicy:
  minimumReady: 800
  lowWatermark: 900
  targetReady: 1000
  maximumReady: 1100
  provisioningBatchSize: 100
  maximumInFlight: 200
  minimumValidity: PT30M
  reserveHorizon: PT10M
  reconciliationPeriod: PT5S
  targetDecreaseMode: STANDBY_TO_TARGET
```

The policy—not `scheduler.maxMessages` and not CSV rotation—keeps the Dataset
near 1,000 eligible records.

### 11.3 Producer `scenario.yaml`

```yaml
id: reusable-records-producer
name: Reusable records producer
description: Performs bounded Dataset source operations from the approved CSV binding.

template:
  image: swarm-controller:latest
  bees:
    - role: dataset-producer
      image: dataset-producer:latest   # target capability; not implemented yet
      work:
        in:
          in: dataset-supply
      config:
        inputs:
          type: DATASET_SUPPLY
          datasetSupply:
            datasetRef: "{{ vars.datasetRef }}"
            sourceBindingId: "{{ vars.sourceBindingId }}"
            ratePerSec: "{{ vars.producerRatePerSec }}"
            maxConcurrent: "{{ vars.producerMaxConcurrent }}"
        outputs:
          type: DATASET_UPSERT
          datasetUpsert:
            deliveryMode: IN_PROCESS_DIRECT
            expectedDatasetRef: "{{ vars.datasetRef }}"
            expectedSourceBindingId: "{{ vars.sourceBindingId }}"
            mappingRef: "{{ vars.sourceMappingRef }}"
      ports:
        - { id: in, direction: in }

topology:
  version: 1
  edges: []
```

The producer has no scheduler input. It receives work only when the Dataset
reconciler finds a durable deficit and the existing control-plane readiness
gate has passed.

`DATASET_UPSERT` is the preferred publication path because the durable receipt
is the producer's primary output contract. A worker that must retain another
primary output may use the shared SDK side-output interceptor instead:

```yaml
config:
  outputs:
    type: RABBITMQ
  interceptors:
    managedDatasetPublisher:
      enabled: true
      deliveryMode: IN_PROCESS_DIRECT
      expectedDatasetRef: "{{ vars.datasetRef }}"
      expectedSourceBindingId: "{{ vars.sourceBindingId }}"
      mappingRef: "{{ vars.sourceMappingRef }}"
```

This is not a best-effort uploader. It reuses the same typed upsert config,
committer, stable idempotency key and durable receipt as `DATASET_UPSERT`, runs
before the Rabbit output is published, and fails the invocation if the Dataset
commit is not durable. It accepts only an allowlisted bounded non-sensitive
projection; sensitive producer results must terminate through
`DATASET_UPSERT`.

### 11.4 Producer `variables.yaml` excerpt

```yaml
profiles:
  - id: default
    name: Default

values:
  global:
    default:
      producerRatePerSec: 20
      producerMaxConcurrent: 4
  sut:
    default:
      example-environment:
        datasetRef:
          datasetSpaceId: performance-test
          datasetAlias: reusable-records
          partition: default
          pool: ready
        sourceBindingId: reusable-records-csv-source
        sourceMappingRef: reusable-records-csv-mapping-v1
```

### 11.5 Creating-call result classification and Dataset routing

A producer must not equate HTTP 2xx or a successful TCP exchange with business
success. The owning source binding defines stable target IDs and one result
policy; the deployment registration freezes each target ID to an authorised
Dataset registration:

```yaml
resultTargetBindings:
  - targetId: completedCards
    datasetRef:
      datasetSpaceId: performance-test
      datasetAlias: completed-cards
      partition: default
      pool: ready
    recordSchemaRef: completed-card-v1
    mappingRef: completed-card-mapping-v1
  - targetId: failedCards
    datasetRef:
      datasetSpaceId: performance-test
      datasetAlias: failed-cards
      partition: default
      pool: remediation
    recordSchemaRef: failed-card-attempt-v1
    mappingRef: failed-card-attempt-mapping-v1

sourceResultPolicyRef: card-create-result-v1
```

The policy classifies the canonical business code and routes by exact enum:

```yaml
schemaVersion: pockethive.source-result-policy/v1
id: card-create-result-v1
callId: create-card
resultRulesRef: card-create-state-v1
classification:
  completedBusinessCodes: [CREATED, ACTIVE]
  failedBusinessCodes: [FAILED, REJECTED]
  pendingBusinessCodes: [PENDING, PROCESSING]
  conclusiveTransportFailureOutcome: CALL_FAILED
  missingBusinessCodeOutcome: INVALID_RESPONSE
  unknownBusinessCodeOutcome: INVALID_RESPONSE
  ambiguousTransportOutcome: UNCERTAIN
routes:
  - outcome: COMPLETED
    action: UPSERT_DATASET
    datasetTargetId: completedCards
    contributesToPrimarySupply: true
  - outcome: FAILED_WRONG_STATE
    action: UPSERT_DATASET
    datasetTargetId: failedCards
    contributesToPrimarySupply: false
  - outcome: CALL_FAILED
    action: UPSERT_DATASET
    datasetTargetId: failedCards
    contributesToPrimarySupply: false
  - outcome: INVALID_RESPONSE
    action: UPSERT_DATASET
    datasetTargetId: failedCards
    contributesToPrimarySupply: false
  - outcome: PENDING
    action: RECONCILE_OPERATION
  - outcome: UNCERTAIN
    action: RECONCILE_OPERATION
```

For structured HTTP, the target `ResultRules` extension uses a typed JSON
Pointer instead of a body regex:

```yaml
businessCode:
  source: RESPONSE_JSON_POINTER
  pointer: /state
  valueType: STRING
```

For a bounded text TCP response, the existing response-body extraction model
is valid:

```yaml
businessCode:
  source: RESPONSE_BODY
  pattern: 'STATE=([A-Z_]+)'
successRegex: '^(CREATED|ACTIVE)$'
```

Binary TCP requires the explicitly selected framing/payload adapter to decode a
typed field first. PocketHive never guesses framing, charset or schema. The
shared result evaluator produces the same `SourceResultOutcome` for HTTP and
TCP; the Dataset output or interceptor only validates and routes that outcome.
A 2xx/transport-success response with `REJECTED` is therefore
`FAILED_WRONG_STATE`, while timeout after a possible write is `UNCERTAIN` and
cannot enter either Dataset until reconciliation. If failed attempts are not
consumed for remediation, route conclusive failures to
`RECORD_OPERATION_FAILURE` instead of creating `failed-cards`.

## 12. Example — one producer, two consumer scenarios

Both consumer scenarios select the same immutable Dataset reference. They do
not name the producer swarm and are not connected to it by a direct queue.

```mermaid
flowchart LR
  P[reusable-records-producer]
  D[(reusable-records<br/>revision 42)]
  A[scenario-a<br/>MANAGED_DATASET]
  B[scenario-b<br/>MANAGED_DATASET]
  P -->|Dataset API commit| D
  D -->|snapshot binding A| A
  D -->|snapshot binding B| B
```

Each bundle declares only its own logical requirement in its reserved
`datasets/requirements.yaml`. Neither bundle names or loads the other:

```yaml
# scenarios/bundles/scenario-a/datasets/requirements.yaml
schemaVersion: pockethive.dataset-requirements/v1
requirements:
  - requirementId: reusableRecords
    required: true
    requiredFields: [recordKey, accountId, cardId, expiryDate]
    allocation: SHARED
    requiredRemainingValidity: PT30M
    deliveryEffectProfile: AT_LEAST_ONCE
    requiredStorageCapabilities: [SNAPSHOT_READ, SHARED_SELECTION]
    fitnessContractRef: reusable-records-traffic-fitness@1
    selectionPolicyRef: reusable-records-round-robin@1
    trustedTimePolicyRef: qualified-host-time@1
    bindingSlotsRef: reusable-records-http-slots@1

# scenarios/bundles/scenario-b/datasets/requirements.yaml contains its own
# requirement when needed. It contains no scenario-a reference.
```

Scenario Manager stores one independent `ScenarioBinding` per scenario. Each
maps only that scenario's requirement to a concrete Dataset:

```yaml
# ScenarioBinding for scenario-a
schemaVersion: pockethive.scenario-binding/v1
id: bind-scenario-a-performance-test
scenarioTemplateId: scenario-a
datasetBindings:
  - requirementId: reusableRecords
    datasetRef:
      datasetSpaceId: performance-test
      datasetAlias: reusable-records
      partition: default
      pool: ready
    datasetContractId: traffic

# Independently stored ScenarioBinding for scenario-b
---
schemaVersion: pockethive.scenario-binding/v1
id: bind-scenario-b-performance-test
scenarioTemplateId: scenario-b
datasetBindings:
  - requirementId: reusableRecords
    datasetRef:
      datasetSpaceId: performance-test
      datasetAlias: reusable-records
      partition: default
      pool: ready
    datasetContractId: traffic
```

These are separate admission records, not one cross-bundle consumer registry
and not runtime messages. Orchestrator resolves each record independently into
`ResolvedDatasetBinding/v1` before creating that scenario's swarm. Unknown,
missing, duplicate, extra or incompatible mappings fail explicitly.

## 13. Example — consumer scenario whose source is a Managed Dataset

```yaml
# scenarios/bundles/scenario-a/scenario.yaml
id: scenario-a
name: Scenario A using reusable records
description: Hydrates a Managed Dataset before running normal traffic.

template:
  image: swarm-controller:latest
  bees:
    - role: dataset-source
      image: generator:latest
      work:
        out:
          out: request-build
      config:
        inputs:
          type: MANAGED_DATASET
          managedDataset:
            datasetRequirementId: reusableRecords
            ratePerSec: "{{ vars.trafficRatePerSec }}"
            snapshotPageSize: 250
            maximumLocalRecords: 1100
            maximumLocalBytes: 16777216
        message:
          bodyType: SIMPLE
          body: "{{ payload }}"
      ports:
        - { id: out, direction: out }

    - role: request-builder
      image: request-builder:latest
      work:
        in:
          in: request-build
        out:
          out: request-send
      config:
        templateRoot: /app/scenario/templates/http # current worker runtime contract
        serviceId: default
        passThroughOnMissingTemplate: false
      ports:
        - { id: in, direction: in }
        - { id: out, direction: out }

    - role: processor
      image: processor:latest
      work:
        in:
          in: request-send
        out:
          out: response
      config:
        baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
        mode: THREAD_COUNT
        threadCount: 4
        inputs:
          type: RABBITMQ
        outputs:
          type: RABBITMQ
        managedDatasetResolver:
          type: MANAGED_DATASET_RESOLVER
          datasetRequirementId: reusableRecords
          snapshotPageSize: 250
          maximumLocalRecords: 1100
          maximumLocalBytes: 16777216
      ports:
        - { id: in, direction: in }
        - { id: out, direction: out }

    - role: postprocessor
      image: postprocessor:latest
      work:
        in:
          in: response
      config:
        forwardToOutput: false
        txOutcomeSinkMode: NONE
        dropTxOutcomeWithoutCallId: true
      ports:
        - { id: in, direction: in }

topology:
  version: 1
  edges:
    - id: dataset-to-builder
      from: { role: dataset-source, port: out }
      to: { role: request-builder, port: in }
    - id: builder-to-processor
      from: { role: request-builder, port: out }
      to: { role: processor, port: in }
    - id: processor-to-postprocessor
      from: { role: processor, port: out }
      to: { role: postprocessor, port: in }
```

The `templateRoot` above is intentionally different from source-binding
`assetPath`: it is an existing worker container setting after the bundle has
been mounted. This proposal does not silently make current workers accept
bundle-relative `templateRoot`; that requires a separate scenario-contract
migration.

Scenario B can use the same structure with a different scenario ID, traffic
rate or request template. Its own Scenario Binding may map its local
`reusableRecords` requirement to the same Dataset reference.

### 13.1 Consumer `variables.yaml` excerpt

```yaml
profiles:
  - id: default
    name: Default

values:
  global:
    default:
      trafficRatePerSec: 25
```

The consumer bundle has no concrete `datasetRef` variable. Orchestrator obtains
that value only from this scenario's `ScenarioBinding` and places the resolved
binding in the immutable swarm plan.

## 14. End-to-end decision flow

```mermaid
flowchart TD
  A[Durable target, expiry, validation or record change] --> B[Reconcile desired and observed state]
  B --> C{Positive bounded deficit?}
  C -- No --> D[Remain stable or schedule next evaluation]
  C -- Yes --> E{Producer exact incarnation is<br/>RUNNING, input-ready and route-current?}
  E -- No --> F[Use existing lifecycle application]
  F --> G[swarm-start through ph.control if needed]
  G --> H{Fresh correlated readiness received?}
  H -- No --> I[Persist blocker<br/>publish no supply]
  H -- Yes --> J[Reserve operation and outbox atomically]
  E -- Yes --> J
  J --> K[Publish bounded DATASET_SUPPLY<br/>on existing WorkItem route]
  K --> L[Producer claims and executes source flow]
  L --> M{Outcome conclusive?}
  M -- Success --> N[Dataset API commits records and receipt]
  M -- Safe failure --> O[Record failure and bounded retry policy]
  M -- Possible external effect --> P[UNCERTAIN<br/>reconcile; do not blind-retry]
  N --> Q[Publish/activate newer Dataset revision]
  Q --> B
  O --> B
  P --> B
```

## 15. Team review questions

The diagrams and examples are ready for team discussion when the team can
answer these questions consistently:

1. Is `ph.control` still the only swarm control plane? **Yes.**
2. Does the producer receive bounded demand only after fresh readiness? **Yes.**
3. Does the producer feed the Dataset rather than consumer queues? **Yes.**
4. Do consumers use local immutable views during measured traffic? **Yes.**
5. Is shared use non-destructive, with no Rabbit “add back”? **Yes.**
6. Can every replenishment reason map to one explicit operation kind? **Yes.**
7. Are degraded, starved, uncertain and authentication-blocked behaviors
   observable and fail-closed? **Yes.**
8. Are the YAML additions clearly marked as target contracts requiring one
   canonical schema before implementation? **Yes.**

## Related design documents

- [Team design overview](managed-datasets-team-design-overview.md)
- [Architecture recommendation](managed-test-data-architecture-recommendation.md)
- [Lifecycle specification](managed-test-data-lifecycle-generic-spec.md)
- [Readiness assessment](managed-test-data-readiness-assessment.md)
- [Assurance strategy](managed-test-data-assurance-strategy.md)
