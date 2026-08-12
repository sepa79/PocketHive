# Managed Datasets — Team Design Brief

Status: proposed shared-replay MVP; implementation and qualification pending

The exact contract is the
[Managed Dataset Shared-Replay MVP Specification](managed-test-data-lifecycle-generic-spec.md).

## Decision

Build one durable shared-record path:

```text
Git Dataset catalogue
  -> Scenario Manager validates/publishes
  -> PostgreSQL authority

SCHEDULER -> provider/SUT pipeline -> MANAGED_DATASET CREATE_RECORD

Orchestrator admits/fences
  -> Swarm Controller projects one sealed revision to Redis
  -> workers verify and load local memory
  -> normal traffic -> SUT
```

The MVP is only `REPLAY + SHARED`. Records are immutable, non-expiring and
reusable by many swarms. Groups partition them by arbitrary fields declared by
the Dataset schema; PocketHive supplies no business fields.

Existing Scheduler, `CSV_DATASET` and `REDIS_DATASET` adapters do not change.

## Why this split

| Component | Owns |
|---|---|
| Git | Definition/Contract review history |
| Scenario Manager | the single validator and transactional catalogue publisher |
| PostgreSQL | published catalogue, provider completion ledger, records, Groups, revisions and idempotency |
| Orchestrator | provisioning, admission, capacity, provider completion, fencing and status |
| Swarm Controller | exact PostgreSQL reads and binding-scoped Redis projection/recovery |
| Redis | rebuildable per-swarm projection only |
| Workers | verified local index and measured-path selection |

PostgreSQL remains correct after Redis loss. Redis HA may shorten an outage but
is not authoritative. Orchestrator never proxies record bytes; workers never
access PostgreSQL.

## Authoring and binding

Scenario Manager publishes exact-version Definitions and Contracts from Git to
PostgreSQL in one transaction. Changed content under an existing version fails.
Bindings use exact versions/digests—never `latest`, ranges or auto-rebinding.

A consumer bundle may add:

```text
datasets/requirements.yaml
```

This extension leaves Scenario Protocol v2 unchanged. Absence means no Managed
Dataset consumer input; provider-only output remains valid. Create Swarm freezes
the SUT Environment, Dataset Space, Dataset, Group, digests and revision.

## Provider fill

The provider keeps a normal `SCHEDULER` WorkInput. Its terminal output is:

```text
MANAGED_DATASET WorkOutput(CREATE_RECORD)
```

Before start, Orchestrator creates the Dataset and Groups in `BUILDING`. The
Create Swarm plan explicitly names one finite `schedulerRole`; PocketHive never
infers it. Validated logical topology and fully rendered `work.out`/`work.in`
queue wiring must agree that this role reaches every exact terminal binding,
and no other Scheduler may reach one through either representation. Missing or
disagreeing ports, work entries, queues, terminal roles or `bindingRef` values
fail before any Dataset, ledger or capacity reservation.

For each terminal, Orchestrator uses bounded forward/reverse reachability to
compare the complete relevant logical and runtime subgraphs; it never enumerates
paths. Equal queue suffixes create every possible publisher-consumer edge, so
duplicate suffixes cannot silently select one connection. An unrelated graph
component is ignored, but a second Scheduler reaching the terminal through
either graph is ambiguous.

Before graph work, Orchestrator reserves bounded concurrency and memory, then
checks explicit qualified limits for plan structure, suffix bytes, derived
Cartesian edges and canonical evidence bytes. Overflow or saturation fails
before any Dataset, ledger or capacity reservation; no unbounded or caller-runs
executor is allowed.

Orchestrator freezes the role, canonical `topologyDigest`, canonical
`runtimeBindingDigest`, Scheduler config digest, positive `maxMessages`, run
ID/fence and binding set. The graph digests use closed versioned shapes, stable
identity sorting, RFC 8785 and SHA-256. A covered change on retry conflicts.
This admission rule does not change Scenario Protocol v2 or let runtime wiring
choose a role.

The finite Scheduler ledger allocates one stable
`providerItemId` per logical item and preserves it through retry, redelivery and
restart. It is provider-specific and does not make general WorkItem `messageId`
mandatory. Every output passes the strict Record Codec, maps to one frozen Group
and commits under an RFC 8785-derived idempotency key.

Before creating anything, each Dataset binding requires
`sum(targetRecords) <= frozenProviderRun.maxMessages`. An absent, ambiguous,
unbounded, mismatched or impossible plan fails without a Dataset, ledger or
capacity reservation; extra scheduled items remain valid.

Exact retries return the same record. Changed retry content conflicts. Concurrent
duplicates do not increase counts and unique writes above target fail.

Completion requires durable issuance closure, terminal issued items and zero
in-flight. PostgreSQL then closes the record fence before counting. A committed
retry still replays; a new late output fails. Exact targets seal revision 1;
underfill, failure or timeout stays unavailable. There is no repair or fallback.

## Fast consumer path

For each admitted consumer binding, Controller streams the exact sealed Group
through a least-privilege PostgreSQL function. It writes new versioned,
same-slot Redis keys:

- immutable ordered records;
- a manifest with Dataset, Group, schema/revision, count and content digests;
- one Active Projection Reference with a monotonic Activation Generation.

The Active Reference advances only after complete verification. Partial data is
invisible. `noeviction` capacity covers active, staging, recovery and failover.

Orchestrator reserves each generation in PostgreSQL before Redis mutation. A
new reservation is greater than every generation ever reserved for that
binding, including failed or unconfirmed publications; no replacement reuses
one after a crash or complete Redis loss.

Each binding names an admitted Redis profile. Existing `REDIS_DATASET`
endpoints are never inferred or reconfigured; sharing requires joint
qualification.

Controller is the trusted sole writer with a deny-by-default command/key ACL.
The Redis Function supplies atomic validation and fencing, not separate
authorisation. Controller cannot load/replace Functions, run `EVAL*`, discover
keys or cross bindings; workers have binding-scoped read-only commands.
Binding/environment credentials use the deployment's existing external secret
injection and rotation mechanism.

Workers background-load and verify bounded pages, then swap complete local
indexes. Loaded workers may continue through a bounded outage; cold/restarted
workers stay unready. Redis loss reprojects from PostgreSQL at a higher
generation.

Normal traffic selects `ROUND_ROBIN` from local memory. It makes no Redis,
PostgreSQL or control-plane call.

## Evidence

The SDK attaches Dataset Context to the normal WorkItem body and preserves it
through the pipeline. The declared SUT-attempt role validates it immediately
before network I/O.

Loading and consumption remain separate. No active consumer, or any
missing/stale/unloaded expected reporter, is `NOT_READY`. Otherwise status
reports attempt evidence as `observed/expected`: `AWAITING_EVIDENCE` means none,
`PARTIAL_EVIDENCE` means some and `CONSUMING` means every current worker epoch
has fresh matching evidence for:

1. the frozen Dataset/Group/schema/revision;
2. active projection generation;
3. every applicable worker loading that generation;
4. local selection; and
5. the correlated guarded SUT attempt.

Redis access or selection alone is insufficient. Worker status flows through
Controller to the Orchestrator REST/MCP read model. No attempt means missing
evidence, not incorrect use. `staleAfter` controls reporter freshness;
`evidenceWindow` controls attempt freshness. Both windows must cover the
qualified worst-case next-report, worker-to-Controller, aggregation,
Controller-to-Orchestrator and clock-skew delay. Equality is current; a shorter
window fails before creation. Duplicate, older and replaced-epoch reports cannot
regress or create coverage. A stale reporter remains expected but cannot count
as loaded or observed. Low or skewed traffic may remain awaiting/partial and
does not block an otherwise valid Create Swarm; there is no traffic-rate or
all-worker admission gate. Guard arrival never proves SUT acceptance. Status
exposes no records, keys, credentials or business outcomes.

## MVP and later work

Included safety is not optional: exact versioning, fencing, idempotency, capacity,
crash recovery, ACLs, evidence, maximum-topology performance and 24-hour soak
qualification all gate the MVP.

Deferred capabilities remain absent and fail admission:

- `EXCLUSIVE_LEASE` when temporary unavailability is approved;
- mutable state/Views when a cross-swarm workflow needs them;
- Managed Dataset provider input/refill when supply can expire or deplete;
- additional Managed Dataset import/derivation sources for a named use case;
- retirement/purge when the bounded initial-fill storage horizon requires it;
- record queries, tags, replacement and audit history only with separate bounded
  contracts.

Future leases and mutable state stay authoritative in PostgreSQL. A Redis list
or Stream may later distribute candidates but can never grant a lease.

## Approval gate

This is ready for M0 executable-contract work, not implementation approval by
documentation alone. The main unproven risks are Redis/worker memory at maximum
fan-out, Controller activation/failover behavior, Scheduler-source
topology/wiring agreement, provider concurrency, qualified observation
delivery/ordering and target-scale
performance/soak results.
