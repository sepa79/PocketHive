# Managed Datasets — Team Decision Brief

**Design result: GREEN — ready for a cross-functional approval decision.**

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
2. PostgreSQL as the durable authority for Dataset runtime state;
3. PocketHive's existing `ph.control` plane as the only swarm lifecycle plane;
4. a controller-owned WorkItem route for bounded `DATASET_SUPPLY` work after
   fresh producer readiness;
5. Worker SDK input/output adapters for snapshot and record commit, with lease
   support gated separately;
6. the desired-state reconciler and database-backed scheduler; and
7. the learning and release evidence plan below.

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
| Where are authored definitions? | Scenario Manager owns versioned definitions, schemas, custom grouping fields and producer/consumer bindings. |
| Where is runtime Dataset truth? | The Managed Dataset module in Orchestrator owns runtime decisions; PostgreSQL stores targets, records, schedules, operations, allocation, evidence and outbox rows. |
| Who starts and stops swarms? | The existing lifecycle application and Swarm Controller, using only `ph.control`. |
| How is more data requested? | After a current `RUNNING` and input-ready result, the reconciler publishes a bounded, idempotent `DATASET_SUPPLY` WorkItem on a route declared by the Swarm Controller. |
| Where do source results go? | The producer's Managed Dataset output adapter commits through the authorised Dataset API. PostgreSQL receipts, not Rabbit acknowledgements, prove the result. |
| How do consumers use records? | MVP workers hydrate a paged immutable snapshot before measured traffic, then select from a bounded local view. Fenced leases are a separately gated extension. |
| Can size change while running? | Yes. A target increase creates only the incremental deficit. A decrease stops new supply and retires deterministic unleased surplus after active leases drain. |
| How is work scheduled? | Events wake a PostgreSQL-backed reconciler; a periodic repair sweep recovers missed events and expired claims. Worker `maxMessages` is not Dataset size. |
| How are records “added back”? | Shared snapshots are non-destructive. Exclusive records are returned through a fenced Dataset lease API, never through Rabbit requeue. |
| Is 50,000 supported today? | No current support claim. The design has a mandatory 50,000-record, two-consumer qualification gate before release. |

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
PostgreSQL records receipts; reconciler recalculates observed state
```

`swarm-start` cannot replace `DATASET_SUPPLY`: the former changes runtime
lifecycle while the latter describes finite work. `DATASET_SUPPLY` cannot be
sent first because the worker input is enabled by control-plane state.

## MVP scope

The first releasable capability includes:

- non-sensitive synthetic records;
- versioned definitions with generic custom classification fields;
- one dedicated producer swarm per Dataset supply policy;
- `SHARED_SNAPSHOT` reuse;
- `EXCLUSIVE_LEASE` deferred as a separate extension unless its fencing,
  recovery and product gates are approved and passed;
- live target increase and decrease;
- idempotent supply, record upsert, outbox and crash recovery;
- operator inventory, detail, supply, consumer and evidence views;
- read-only agentic/AI explanation access; and
- a 50,000-record, at-least-two-consumer release profile.

## Non-goals

- A general-purpose data platform or replacement for all Redis/CSV uses.
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
