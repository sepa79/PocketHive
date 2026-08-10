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
| PostgreSQL | published catalogue, Dataset identity, records, Groups, revisions and idempotency |
| Orchestrator | provisioning, admission, capacity, fencing and status |
| Swarm Controller | exact PostgreSQL reads and binding-scoped Redis projection/recovery |
| Redis | rebuildable per-swarm projection only |
| Workers | verified local index and measured-path selection |

PostgreSQL remains correct if Redis loses recent writes or all data. Redis HA and
persistence may shorten an outage but are not correctness dependencies.
Orchestrator never proxies record bytes. Workers never access PostgreSQL.

## Authoring and binding

Dataset Definitions and composed Schema Contracts are exact-version,
version-controlled artifacts. Scenario Manager publishes immutable
`id + SemVer + SHA-256 digest` entries to PostgreSQL in one all-or-nothing
import. Changed content under an existing version fails. Running bindings do not
depend on Git, `latest`, ranges or automatic rebinding.

A consumer bundle may add:

```text
datasets/requirements.yaml
```

This independently versioned extension leaves Scenario Protocol v2 unchanged.
Absence means no Managed Dataset consumer input. A provider-only output remains
valid. Create Swarm freezes one SUT Environment, Dataset Space, Dataset, Group,
Definition/Contract digests and authority revision.

## Provider fill

The provider keeps a normal `SCHEDULER` WorkInput. Its terminal output is:

```text
MANAGED_DATASET WorkOutput(CREATE_RECORD)
```

Before the provider starts, Orchestrator creates the Dataset and all rendered
Groups in `BUILDING`. Every output requires a stable WorkItem message identity,
passes the shared strict Record Codec, maps to exactly one frozen Group and
commits under a stable idempotency key.

Exact retries return the same record. Changed retry content conflicts. Concurrent
duplicates do not increase counts and unique writes above target fail.

Consumers see nothing until every Group reaches its exact admitted target.
PostgreSQL then seals revision 1 in one transaction. Underfill, provider failure
or timeout leaves the Dataset unavailable with a closed reason. The MVP does not
reconcile, replace, refill or fall back.

## Fast consumer path

For each admitted consumer binding, Controller streams the exact sealed Group
through a least-privilege PostgreSQL function. It writes new versioned,
same-slot Redis keys:

- immutable ordered records;
- a manifest with Dataset, Group, schema/revision, count and content digests;
- one Active Projection Reference with a monotonic Activation Generation.

The Active Reference advances atomically only after every write and bounded
verification succeeds. Partial projections stay invisible. Redis runs with
`noeviction`; admission funds active, staging and recovery memory plus measured
overhead and failover headroom.

Each binding names an admitted Managed Dataset Redis deployment profile.
PocketHive never silently reuses or reconfigures an existing `REDIS_DATASET`
endpoint; explicitly shared infrastructure must qualify both workloads.

Controller has a prefix-restricted writer role and closed
activation/reconciliation functions, not general record reads. Workers have
binding-scoped read-only commands.

Workers load bounded pages in the background, verify all identities, counts and
digests, build the next local index and atomically swap only to a newer valid
generation. Already-loaded workers may continue through a bounded Redis or
Controller outage. Cold/restarted workers remain unready. Complete Redis loss
causes deterministic reprojection from PostgreSQL at a higher generation.

Normal traffic selects `ROUND_ROBIN` from local memory. It makes no Redis,
PostgreSQL or control-plane call.

## Evidence

The SDK attaches Dataset Context to the normal WorkItem body and preserves it
through the pipeline. The declared SUT-attempt role validates it immediately
before network I/O.

`CONSUMING` requires fresh matching evidence for:

1. the frozen Dataset/Group/schema/revision;
2. active projection generation;
3. every applicable worker loading that generation;
4. local selection; and
5. the correlated guarded SUT attempt.

Redis access, record count or local selection alone is not enough. Worker status
flows to Swarm Controller, then to the Orchestrator read model used unchanged by
REST, MCP and future UI. Status exposes bounded identities/counts, not records,
keys, credentials or business outcomes.

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
fan-out, Controller activation/failover behavior, provider concurrency and
target-scale performance/soak results.
