# Managed Datasets — Team Decision Brief

Status: in progress — `G-DESIGN-READY-v1` passed;
`G-TEAM-APPROVED-v1` is pending

**Design result: GREEN — ready for a cross-functional approval decision, not
yet approved.**

This result confirms that the proposed architecture, responsibilities, states,
scheduler and acceptance gates are coherent. It does not claim that the feature
is implemented, accessible, secure at runtime or qualified for 50,000 records.
Those are explicit delivery gates.

The plain-language explanation is the
[Managed Datasets team guide](managed-datasets-team-design-overview.md). The
lifecycle specification, UI specification and assurance strategy remain the
normative design sources.

## Decision requested

**Architecture constraint:** this feature introduces no new control, data or
Dataset messaging plane. It reuses the existing `ph.control` lifecycle path and
the existing controller-owned WorkItem route; the Dataset API is an application
boundary over PostgreSQL.

Approve:

1. Managed Datasets as a bounded module inside Orchestrator for the first
   delivery;
2. one explicitly selected Dataset storage adapter per published package:
   PostgreSQL as the recommended full managed-records profile and Redis as a
   capability-gated collection profile, with no implicit default or fallback;
3. PocketHive's existing `ph.control` plane as the only swarm lifecycle plane;
4. a controller-owned WorkItem route for bounded `DATASET_SUPPLY` work after
   fresh producer readiness;
5. Worker SDK input/output adapters for snapshot and record commit, with
   `DATASET_UPSERT` preferred and one shared fail-closed publisher interceptor
   available when another primary output must remain; lease support stays
   gated separately;
6. one shared HTTP/TCP-capable result evaluator plus a frozen exhaustive
   `SourceResultPolicy/v1` for business outcome and Dataset target routing;
7. the desired-state reconciler and database-backed scheduler; and
8. the learning and release evidence plan below.

Do not treat this decision as a production support claim or delivery estimate.

## Why the feature is needed

Long-running tests need reusable records that can be prepared by one swarm,
used by other swarms and kept at a controlled supply level. Current worker-local
CSV rotation and Redis pop/push behavior do not provide a shared, durable,
versioned Dataset with explicit readiness, allocation and recovery evidence.

The proposed feature provides that missing lifecycle without putting a central
Dataset call on every measured request.

## Closed architecture decisions

| Question | Decision |
|---|---|
| Where are authored definitions? | Each definition is a standalone package under `scenarios/managed-datasets/<datasetPackageId>/`. Scenario Manager validates and owns its `DRAFT -> PUBLISHED -> RETIRED` lifecycle. A scenario bundle stores only its own `datasets/requirements.yaml`; no cross-bundle consumer list exists. |
| What is a Dataset Space? | A deployment-scoped shared authority boundary for SUT scope, access policy, classification ceiling, quota and allowed storage profiles. Packages do not embed it. `DatasetRegistration/v1` explicitly binds one package version and local contract to one active Space, alias and storage tuple. |
| How are packages managed? | The UI creates/edits drafts. PocketHive MCP lists, validates, uploads, publishes and retires through the same Scenario Manager services; agent mutations are governed through HiveGate. Published versions are immutable. |
| Where is runtime Dataset truth? | The Managed Dataset module owns runtime decisions. Core registrations explicitly select `POSTGRESQL`/`MANAGED_RECORDS_V1` within an active Dataset Space. `REDIS`/`REDIS_COLLECTION_V1` uses the same port only after separate qualification; until then registration fails unavailable. |
| Who starts and stops swarms? | The existing lifecycle application and Swarm Controller, using only `ph.control`. |
| How is more data requested? | After a current `RUNNING` and input-ready result, the reconciler publishes a bounded, idempotent `DATASET_SUPPLY` WorkItem on a route declared by the Swarm Controller. |
| Where do source results go? | Prefer the producer's `DATASET_UPSERT` output adapter. A worker that must retain another primary output may explicitly select the shared `managedDatasetPublisher` interceptor. Both use the same authorised Dataset API, typed committer, idempotency and durable receipt; PostgreSQL receipts, not Rabbit acknowledgements, prove the result. |
| How is a wrong-state create detected and routed? | The producer applies the shared canonical `ResultRules` evaluator and one frozen `SourceResultPolicy/v1`. Transport success plus an unexpected terminal business state becomes `FAILED_WRONG_STATE`; an exhaustive enum router may commit it only to a pre-authorised failure Dataset or operation ledger. `PENDING` and `UNCERTAIN` remain under reconciliation, and only `COMPLETED` contributes to primary supply. HTTP and TCP use explicit protocol extractors but the same outcome contract. |
| How do consumers use records? | MVP workers hydrate a paged immutable snapshot before measured traffic, then select from a bounded local view. Fenced leases are a separately gated extension. |
| How are bundle files referenced? | Source bindings use validated bundle-relative `assetPath` values such as `datasets/input.csv`; only runtime-plan materialisation derives the existing worker container path and freezes the file digest. |
| Can size change while running? | Yes. A target increase creates only the incremental deficit. A decrease stops new reservations and atomically applies a deterministic `READY -> STANDBY` revision; it does not depend on leases, delete records or deprovision external entities. |
| How is work scheduled? | `MANAGED_RECORDS_V1` uses a PostgreSQL-backed reconciler and periodic repair sweep. A Redis-backed package may request only lifecycle operations its capability profile implements. Worker `maxMessages` is not Dataset size. |
| How are records “added back”? | Shared snapshots are non-destructive. Exclusive records are returned through a fenced Dataset lease API, never through Rabbit requeue. |
| Is 50,000 supported today? | No current support claim. The design has a mandatory 50,000-record, two-consumer qualification gate before release. |

Consumer registration therefore has two explicit stages: the bundle-local
requirement and that scenario's separately stored mapping. Runtime registration
is derived automatically only after both validate; it is not discovery, and no
scenario bundle names another.

## Mandatory sequence

```text
Dataset target changes
        │
        ▼
Reconciler calculates a durable deficit
        │
        ▼
Existing lifecycle application ensures producer swarm is running
        │
        ▼
ph.control returns fresh RUNNING + input-ready evidence
        │
        ▼
Reconciler sends bounded DATASET_SUPPLY WorkItem
        │
        ▼
Producer executes its normal flow and commits through Dataset API
        │
        ▼
The registration-selected adapter records typed receipts; the reconciler recalculates observed state
```

`swarm-start` cannot replace `DATASET_SUPPLY`: the former changes runtime
lifecycle while the latter describes finite work. `DATASET_SUPPLY` cannot be
sent first because the worker input is enabled by control-plane state.

## MVP scope

The first releasable capability includes:

- non-sensitive synthetic records;
- versioned definitions with generic custom classification fields;
- one dedicated producer swarm per Dataset supply policy;
- `SHARED` allocation, presented to operators as shared-snapshot reuse;
- `EXCLUSIVE_LEASE` deferred as a separate extension unless its fencing,
  recovery and product gates are approved and passed;
- live target increase and decrease;
- idempotent supply, record upsert, outbox and crash recovery;
- operator inventory, detail, supply, consumer and evidence views;
- governed agentic/AI status reads and bounded proof creation; and
- a 50,000-record, at-least-two-consumer release profile.

The qualified core has no hardware security or hosted-service dependency.
Application encryption uses purpose-separated keys supplied as Docker secrets.
Database/image restore is not a core claim and remains disabled unless the
separate `Q-HA-RESTORE-v1` profile selects and qualifies its external deletion
and key-custody evidence.

## Non-goals

- Automatic conversion of every existing Redis/CSV path into a Managed
  Dataset. Those paths join the umbrella only through an explicit Dataset
  package, adapter/profile and validated source contract.
- Record bodies on `ph.control`, WorkItem status or logs.
- A Dataset-owned RabbitMQ lifecycle or topology path.
- Per-request central Dataset API calls during measured traffic.
- Exactly-once claims from RabbitMQ.
- Autonomous AI mutation or AI-derived authority.
- Multi-region storage or sensitive production data in the first release.
- Silent fallback from one adapter, allocation mode or protocol to another.

## Delivery slices and evidence

### Learning slice

Prove the architecture with a bounded non-sensitive Dataset, one producer and
two consumers. Demonstrate:

- start → fresh readiness → supply ordering;
- target reconciliation and idempotent record commit;
- shared reuse without add-back;
- live increase and decrease;
- producer crash, duplicate delivery and outbox recovery;
- no record values on the control plane or in logs; and
- no Dataset API call on the measured request path.

This slice is architecture evidence, not capacity evidence.

### Release qualification

The release build must additionally pass:

- 50,000 eligible records plus agreed headroom;
- representative maximum record size and classification shape;
- at least two concurrent consumer swarms;
- target changes during active use;
- restart, redelivery, partial commit and uncertain-operation recovery;
- lease expiry and stale-holder fencing when leases are included;
- accessibility checks against the implemented UI;
- security and least-privilege verification;
- p95/p99 API, hydration, lock-time and memory budgets; and
- the agreed sustained soak with zero correctness violations.

## State and operator-language decision

The product exposes three independent state dimensions:

- `SwarmRuntimeState`: runtime availability;
- `ProducerWorkState`: idle, executing, committing or uncertain work; and
- `DatasetAvailabilityState`: whether the Dataset can safely support its
  declared use.

The UI must never collapse these into one color or “healthy” label.
`RUNNING + IDLE` is normal. A Dataset may be `READY` while replenishment is in
progress if its safe minimum remains satisfied. A stale or unknown readiness
result blocks new supply dispatch.

## Team ownership needed before implementation starts

| Decision | Accountable role |
|---|---|
| Select the first non-sensitive source flow and two representative consumers | Product owner |
| Freeze the first Dataset definition, target bounds and record-size profile | Product owner with QA and architecture review |
| Approve the Dataset API and WorkItem contracts | Architecture/contract owner |
| Approve the controller-owned Rabbit route profile | Platform owner |
| Set readiness freshness, operation deadline and retry budgets | Platform and operations owners |
| Set the 50,000-record performance and soak thresholds | QA and performance owners |
| Approve identity, authorisation, encryption and logging controls | Security owner |
| Approve operator terminology and accessibility acceptance | Product and UX owners |

Each decision must become an explicit configuration or canonical contract. The
implementation must not invent defaults or compatibility fallbacks.

## Approval statement

Permitted after team approval:

> The Managed Dataset design is approved for implementation planning. Swarm
> lifecycle uses PocketHive's existing control plane; bounded supply uses the
> existing controller-owned WorkItem data path after readiness; the Dataset API
> and PostgreSQL form an application boundary, not another plane. Runtime
> support remains conditional on the documented implementation and release
> gates.

Not permitted until evidence exists: “production ready,” “50,000 records
supported,” “accessible,” “secure,” “exactly once” or “release qualified.”

## Research basis

The design applies established patterns rather than inventing new ones:

- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
  for application-owned ports and replaceable adapters;
- [Kubernetes controller pattern](https://kubernetes.io/docs/concepts/architecture/controller/)
  for desired-versus-observed reconciliation;
- [AWS transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
  for atomic state and publication intent;
- [RabbitMQ confirms and acknowledgements](https://www.rabbitmq.com/docs/confirms)
  for at-least-once delivery boundaries;
- [PostgreSQL row claiming](https://www.postgresql.org/docs/current/sql-select.html)
  for short `SKIP LOCKED` scheduler claims;
- [WCAG 2.2 status messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)
  for accessible asynchronous progress; and
- [Rapid Software Testing foundations](https://rapid-software-testing.com/what-are-the-foundations-of-rst/)
  for risk-led checking, investigation and evidence confidence.
