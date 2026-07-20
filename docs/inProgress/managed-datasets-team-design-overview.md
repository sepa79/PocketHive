# Managed Datasets — Team Guide

**Design assessment: GREEN — ready for team approval and implementation
planning.**

This is a design-readiness result, not a claim that the feature already exists
or has passed production-scale testing. The implementation must still pass the
release gates defined in the assurance strategy, including the exact
50,000-record workload.

## The idea in one minute

A Managed Dataset is a durable, reusable collection of test records that one
PocketHive swarm can create and other swarms can use.

PocketHive stores the authoritative Dataset in PostgreSQL. A reconciler keeps
the available supply close to an operator-defined target. When more records are
needed, PocketHive first starts the configured producer swarm through the
existing RabbitMQ control plane. Only after that swarm reports that it is
running and ready does PocketHive send it a bounded `DATASET_SUPPLY` work item.
The producer runs its normal flow and commits the resulting records through the
Dataset API.

Consumer swarms read an immutable snapshot through a Managed Dataset input
adapter. A separately gated later profile may add explicit leases. Records
never travel on the control plane, and RabbitMQ is never the authoritative
record store.

## What the team is being asked to approve

Approve this architecture and its implementation plan:

- keep all swarm lifecycle events on PocketHive's existing `ph.control` plane;
- keep bounded Dataset production on PocketHive's existing controller-owned
  WorkItem route;
- keep Dataset definitions, records, schedules, operations, leases and receipts
  authoritative in PostgreSQL;
- add Managed Dataset input/output adapters through the Worker SDK's existing
  extension points; and
- prove the feature against the named functional, recovery, accessibility and
  50,000-record release gates before making a support claim.

## Where each component sits

```mermaid
flowchart LR
  UI[Operator UI / approved API client]
  SM[Scenario Manager<br/>versioned definitions and bindings]

  subgraph ORCH[Orchestrator service]
    DS[Managed Dataset application<br/>domain rules and reconciler]
    LC[Existing swarm lifecycle application]
    PORTS[Application-owned ports]
    DS --> PORTS
    LC --> PORTS
  end

  subgraph SC[Swarm Controller]
    LIFE[Lifecycle and topology authority]
  end

  subgraph RMQ[Existing RabbitMQ]
    CONTROL[ph.control<br/>lifecycle and status only]
    WORK[Controller-owned WorkItem route<br/>DATASET_SUPPLY]
  end

  subgraph SWARMS[PocketHive swarms]
    PRODUCER[Producer swarm<br/>DatasetSupplyInput → normal flow<br/>→ ManagedDatasetOutput]
    CONSUMER[Consumer swarm<br/>ManagedDatasetInput → normal traffic]
  end

  API[Authorised Dataset API]
  PG[(PostgreSQL<br/>definitions, records, targets,<br/>operations, leases and outbox)]

  UI --> DS
  SM -->|admitted immutable definition| DS
  DS -->|ensure running| LC
  LC --> LIFE
  LIFE <--> CONTROL
  CONTROL <--> PRODUCER
  CONTROL <--> CONSUMER
  DS -->|bounded work metadata| WORK
  WORK --> PRODUCER
  PRODUCER -->|claim, checkpoint, upsert, receipt| API
  CONSUMER -->|snapshot or lease| API
  API --> DS
  DS <--> PG
```

## One control plane, no new plane

Managed Datasets cross three existing interface boundaries. This does **not**
mean three planes. The only swarm control plane remains `ph.control`.

| Existing boundary | What it is | Carries | Must not carry |
|---|---|---|---|
| `ph.control` | PocketHive's single RabbitMQ swarm control plane | Swarm plan/start/stop/remove, approved live configuration, outcomes, status and alerts | Dataset records, leases or requested record counts |
| Controller-owned WorkItem route | Existing swarm data/work path; not a control plane | A bounded `DATASET_SUPPLY` request for an already-ready producer swarm | Swarm lifecycle commands or authoritative Dataset state |
| Dataset API backed by PostgreSQL | Application boundary and durable store; not a message plane | Claim, checkpoint, record upsert, snapshot and durable operation receipts; separately gated lease operations if enabled | RabbitMQ topology decisions |

The MVP adds no Dataset exchange, control queue, event bus or notification
lane. Event-driven wake-ups use existing application observations, and the
periodic repair sweep guarantees convergence when a wake-up is missed.

## Why start and supply are separate

The two messages answer different questions:

- `swarm-start`: **should this swarm runtime be active?**
- `DATASET_SUPPLY`: **what finite work should the active swarm perform?**

The WorkItem input is enabled by control-plane state. Sending work before a
fresh running/readiness result risks leaving the request unconsumed. Putting the
supply request inside `swarm-start` would mix runtime lifecycle with workload
arguments, make retries ambiguous and break the existing PocketHive contract.

The mandatory sequence is therefore:

```mermaid
sequenceDiagram
  participant R as Dataset reconciler
  participant L as Existing lifecycle application
  participant C as ph.control / Swarm Controller
  participant P as Producer swarm
  participant W as WorkItem route
  participant D as Dataset API / PostgreSQL

  R->>D: Persist operation and outbox intent
  R->>L: ensureRunning(producerSwarm, operation)
  L->>C: canonical swarm-start when required
  C->>P: enable runtime and WorkInput
  P-->>C: current RUNNING + input-ready status
  C-->>L: correlated outcome and fresh status
  L-->>R: producer ready
  R->>W: DATASET_SUPPLY(operationId, datasetId, generation, requestedCount)
  W->>P: bounded supply work
  P->>D: claim, checkpoint and idempotent record upserts
  P->>D: terminal operation receipt
  D-->>R: observed state changed
  R->>D: reconcile target and next due time
```

If the producer is already `RUNNING` and its input is freshly confirmed ready,
the lifecycle application performs no new start. `RUNNING + IDLE` is a normal,
healthy hot-idle producer state.

For the MVP, use a dedicated producer swarm. If a later version allows several
features to demand the same swarm, persist one `SwarmRunDemand` per owner and
aggregate desired state. Do not use an in-memory reference count and do not let
the Dataset module stop a swarm still required by another owner.

## Responsibilities and boundaries

| Component | Owns | Does not own |
|---|---|---|
| Scenario Manager | Versioned Dataset definitions, grouping fields, producer/consumer bindings and capability declarations | Runtime records, fill operations or RabbitMQ topology |
| Managed Dataset module in Orchestrator | Target, availability, records, operations, schedules, allocation, evidence and reconciliation decisions | Direct worker logic or swarm lifecycle implementation |
| Existing lifecycle application and Swarm Controller | Swarm desired state, canonical start/stop behavior, readiness and declared routes | Dataset target calculations or record truth |
| Producer swarm | Running the configured source flow for one bounded supply operation | Deciding the target or declaring queues |
| Consumer adapter | Background snapshot hydration or lease acquisition and local selection | Direct PostgreSQL access |
| PostgreSQL | Durable positive authority and outbox | Message delivery |
| RabbitMQ | Delivery of control events and bounded work | Proof that records were committed |

The Dataset domain depends on small application ports, not RabbitMQ, HTTP, JDBC
or worker runtime classes. Typical ports are:

- inbound: `SetDatasetTarget`, `ReconcileDataset`, `CommitRecord`,
  `AcquireSnapshot`, `AcquireLease`, `ReleaseLease`;
- outbound: `DatasetRepository`, `SwarmLifecyclePort`, `WorkDispatchPort`,
  `Clock`, `OperationEventPort`; and
- infrastructure adapters: PostgreSQL, the existing lifecycle application,
  Rabbit WorkItem publishing, the Dataset API and Worker SDK adapters.

This keeps the domain independently testable and follows dependency inversion:
infrastructure implements ports owned by the application core.

## Generic grouping and naming

Managed Datasets do not contain fixed company-specific fields. Each Dataset
definition declares its own typed classification fields and a `groupBy` list.
The UI allows an authorised author to add a custom field name and validates it
against that definition's schema.

Templates may use declared values generically, for example
`<name>-{{vars.groupA}}-{{vars.groupB}}`. The Dataset feature treats these as
ordinary schema fields and does not know their organisation-specific meaning.

## State model operators can understand

There is no overloaded state called simply “Dataset status.” The UI presents
three independent runtime facts:

| Dimension | Values | Question answered |
|---|---|---|
| `SwarmRuntimeState` | `READY`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, `FAILED` | Can the producer swarm receive work? |
| `ProducerWorkState` | `IDLE`, `CLAIMED`, `EXECUTING`, `COMMITTING`, `FAILED`, `UNCERTAIN` | What is the producer doing now? |
| `DatasetAvailabilityState` | `INITIALISING`, `WARMING`, `READY`, `DEGRADED`, `STARVED`, `ERROR`, `AUTH_REQUIRED` | Can this Dataset safely support its declared use? |

The durable operation ledger provides finer detail such as waiting for swarm,
dispatched, running, committing, succeeded, partial, failed, cancelled and
uncertain. Record allocation is also separate: an eligible record may be
`AVAILABLE`, `LEASED`, `STANDBY` or `RETIRING`.

Examples:

| Scenario | What operators see | System decision |
|---|---|---|
| New Dataset, producer stopped | `STARTING`, then `RUNNING`; Dataset `WARMING` | Wait for fresh readiness, then dispatch the bounded supply operation |
| Producer running with no work | Swarm `RUNNING`, producer `IDLE` | Healthy hot-idle; dispatch only when a deficit exists |
| Minimum is met but target is not | Dataset `READY`, supply operation active | Consumer starts may proceed while supply converges |
| No safe records remain | Dataset `STARVED` | Block new use and start recovery; do not report ready from count alone |
| Producer outcome cannot be proved | Producer and operation `UNCERTAIN` | Retain the reservation and reconcile; never blindly create a second operation |
| Authorisation cannot be evaluated | Dataset `AUTH_REQUIRED` | Fail closed for new access while preserving authorised, still-valid local views according to policy |
| Target is reduced while records are leased | Dataset remains usable; surplus is visible | Stop new supply, let leases drain, then move deterministic surplus to standby/retiring |
| Control-plane readiness is stale | Swarm state is not accepted as current | Do not dispatch new supply until readiness is refreshed |

## How the target and scheduler work

`targetSize` is desired eligible inventory. It is not a traffic rate, scenario
schedule or `maxMessages` value.

```text
deficit = max(0, targetSize - eligibleRecords - pendingExpected)
```

- `eligibleRecords` includes both available and currently leased records, so
  normal use does not trigger unnecessary replacement.
- `pendingExpected` is the reserved output of active supply operations, so two
  reconcilers cannot both fill the same deficit.
- Supply work is split into bounded batches; the target may be 50,000 without
  creating one 50,000-record message.
- Every target edit increments an observed generation. Rapid edits coalesce to
  the latest target instead of replaying every intermediate size.

The scheduler uses two triggers:

1. an immediate reconciliation when a target, operation, record or lease event
   changes observed state; and
2. a periodic repair sweep for lost notifications, expired claims and uncertain
   operations.

PostgreSQL stores `nextReconcileAt`, operation identity, reservations, claims,
deadlines and retry policy. A reconciler claims a due row in a short fenced
transaction, calculates one decision, stores that decision and an outbox event,
then releases the lock before calling the lifecycle application or RabbitMQ.

The current worker `SchedulerWorkInput.maxMessages` counter is deliberately not
used: it is a worker-local dispatch cap and cannot prove durable Dataset size
after a restart.

## Changing the size while swarms run

Target changes are accepted immediately and converge asynchronously:

- increase: create only the new deficit under the latest generation;
- decrease: stop new supply and claims, preserve leased records, and move
  deterministic unleased surplus to `STANDBY` or `RETIRING`;
- repeated edits: converge directly to the latest accepted generation; and
- unsafe or unauthorised edit: reject it with no partial effect.

The UI shows desired size, eligible records, available records, leased records,
pending expected output, observed generation and the reason when convergence is
blocked. “Real time” therefore means immediate acceptance plus visible,
measurable convergence—not synchronous creation or deletion of thousands of
records.

## Reuse and “add back”

The architecture names two allocation modes so future behavior cannot become
ambiguous. The MVP requires the first; the second remains deferred unless its
separate acceptance gates pass:

1. `SHARED_SNAPSHOT` is the default. Many swarms use an immutable published
   revision non-destructively. Records never leave the Dataset, so there is
   nothing to add back.
2. `EXCLUSIVE_LEASE` is used only where concurrent reuse is unsafe. Acquire and
   release update allocation state, not membership. Each lease has a holder,
   opaque token, expiry and monotonically increasing fencing epoch. A stale
   holder cannot release or overwrite a newer lease.

RabbitMQ acknowledgement/requeue is transport recovery, not Dataset return
semantics. A business-level release always goes through the Dataset API.

Consumer workers hydrate snapshots or leases before measured traffic and use a
bounded local view on the request path. They do not query PostgreSQL or the
Dataset API for each simulated request.

## Delivery, recovery and evidence

Rabbit delivery is at least once. Publisher confirms prove acceptance by the
broker; consumer acknowledgements prove handling of one delivery. Neither
proves that Dataset records were committed.

Correctness comes from:

- stable `operationId`, `datasetGeneration`, `recordId`, `correlationId` and
  idempotency keys;
- unique constraints and idempotent upserts;
- an outbox committed with domain state;
- durable checkpoints and terminal operation receipts;
- bounded retry with the same operation identity; and
- reconciliation of partial or uncertain outcomes against PostgreSQL truth.

The supply message acknowledgement may show that the producer accepted the
request. Only the Dataset receipt and committed records complete the operation.
After a crash, the reconciler resumes or redrives the same identity rather than
inventing a new request.

Logs and control status contain identifiers and counts, never record bodies,
credentials, lease tokens or secrets. Workers use scoped service identities and
the Dataset API; they do not receive direct database credentials.

Agentic tools and AI assistants are read-only explanation clients in the MVP.
They may summarise status and evidence but cannot become Dataset authority,
publish Rabbit messages or bypass the approved command API. Any future write
tool requires a separately reviewed, least-privilege command contract and human
approval policy.

## The 50,000-record requirement

The design is intended for a 50,000-record qualification profile through
bounded batches, paged hydration and local immutable views. That is a design
target, not yet a measured support claim.

Release qualification must use the exact deployment profile and prove:

- 50,000 eligible records plus agreed safety headroom;
- representative maximum record size and classification shape;
- at least two concurrent consumer swarms;
- shared snapshot and, if shipped, exclusive lease behavior;
- target increase and decrease during active use;
- producer crash, duplicate delivery, restart and outbox recovery;
- stale lease fencing and safe return;
- no record bodies on `ph.control` or in logs;
- bounded API latency, database lock time, worker memory and hydration time;
- no request-time Dataset API calls during measured traffic; and
- a sustained soak with documented p95/p99 thresholds and zero correctness
  violations.

## What the operator experience must show

The Dataset inventory and detail views must answer, without requiring log
inspection:

1. Is this Dataset safe to use now?
2. What is its desired size and observed supply?
3. Is the producer swarm running, and is it idle or executing work?
4. Is a target change still converging, blocked or failed?
5. Which swarms use this Dataset, in which allocation mode and revision?
6. What operation or evidence explains the current result?

Status changes are announced through a restrained live region; errors include
plain-language recovery guidance. Keyboard focus remains visible, tables reflow
or become labelled cards at narrow widths, and color is never the only status
indicator. The planning wireframe is an interaction contract, not evidence of
production WCAG conformance.

## Design approval checklist

| Team concern | Design result |
|---|---|
| Existing control plane owns every swarm lifecycle event | **GREEN** |
| Supply is part of the swarm without becoming a lifecycle command | **GREEN** |
| Component placement and decision flow are explicit | **GREEN** |
| Dataset, producer and swarm states cannot be confused | **GREEN** |
| Scheduling and missed-event recovery are durable | **GREEN** |
| Live increase/decrease behavior is deterministic | **GREEN** |
| 50,000 records and two-swarm reuse have a measurable release gate | **GREEN** |
| “Add back” semantics are explicit and not delegated to RabbitMQ | **GREEN** |
| SOLID and hexagonal boundaries are defined | **GREEN** |
| Operator and agentic interfaces cannot become authority | **GREEN** |

**Decision:** the idea is coherent and complete enough to show the team and
approve for implementation planning. Production readiness remains deliberately
gated on implementation, security, accessibility, recovery and capacity
evidence; those are testable delivery criteria, not unresolved architecture.

## Detailed companions

- [Component, use-case and scenario examples](managed-datasets-use-cases-and-scenario-examples.md)
- [Lifecycle and architecture specification](managed-test-data-lifecycle-generic-spec.md)
- [Architecture rationale](managed-test-data-architecture-recommendation.md)
- [Operator UI design specification](managed-datasets-operator-ui-design-spec.md)
- [Assurance and release strategy](managed-test-data-assurance-strategy.md)
- [Interactive planning wireframe](managed-datasets-wireframes/README.md)
