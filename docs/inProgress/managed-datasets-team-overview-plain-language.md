# Managed Datasets — Plain-language Guide

Status: proposed MVP; implementation and qualification pending

For exact requirements, see the
[Managed Test Data MVP Specification](managed-test-data-lifecycle-generic-spec.md).

## One-minute version

A Managed Dataset is a proposed durable synthetic system-under-test (SUT) data
option. One provider swarm creates it for many compatible consumers. Existing
Redis Dataset, CSV Dataset and Scheduler adapters do not change.

```text
provider -> named Managed Dataset -> explicit consumer selection -> normal scenario -> SUT
```

Every choice is explicit. PocketHive never substitutes another adapter, source,
Dataset, Group or View. A swarm that needs no Managed Dataset declares empty
requirement and selection arrays.

## Choose the right model

| Need | Use |
|---|---|
| Partition records by stable schema-defined values | One Dataset with Groups |
| Reuse immutable records concurrently | `REPLAY + SHARED` |
| Make one record temporarily unavailable | `EXCLUSIVE_LEASE` |
| Track processing stage or outcome | `WORKFLOW` Record State and named Views |
| Create independently reusable output records | One bounded derived Dataset |
| Copy a whole Dataset unchanged | Not MVP; future explicit clone operation |

Groups may use arbitrary schema-defined fields. They are frozen before provider
work starts and are not PocketHive business fields.

A View selects records whose current Record State matches its fixed rule.
Success, retry, failure and unknown can be Views over the same records without
copying them. Create another Dataset only for independent output records.

## How data arrives

Every provider binding selects exactly one source:

| Source | Behaviour |
|---|---|
| `SCHEDULER` | Bounded provider work until the Group reaches its stored target |
| `CSV` | One finite validated import from a mounted file |
| `REDIS` | One finite import from an immutable copy of a referenced list; the live list is never popped or changed |
| `MANAGED_DATASET` | Bounded derived work from one exact upstream workflow View |

CSV and Redis validate and fingerprint the complete input before any Group is
visible. Failure blocks the import without fallback.

A Managed Dataset source requires one upstream
`WORKFLOW + EXCLUSIVE_LEASE` selection and one downstream output. A four-case
Outcome Mapping handles `SUCCESS`, `RETRYABLE_FAILURE`, `TERMINAL_FAILURE` and
`UNKNOWN`, with no default or SUT-response inference.

On `SUCCESS`, one PostgreSQL transaction creates `1..N` downstream records,
stores lineage, changes upstream state and releases the lease. Other outcomes
create none. Failure changes neither Dataset; exact retry returns the result.

MVP records do not expire and are never purged. Shared replay can reuse them
continuously. A workflow that moves records out of its ready View stops accepting
new records at its stored limit, so its operating horizon must be capacity-funded.

## How consumers stay fast

PostgreSQL is authoritative for Managed Dataset records, state, Views, leases,
imports, lineage and idempotency. Redis remains authoritative only for the
existing Redis Dataset option.

Orchestrator grants a frozen publication but never proxies its bytes. The Swarm
Controller reads it through one restricted PostgreSQL function and writes it to
shared storage. Atomic `ACTIVE.json` selects the completed revision; workers
never scan directories. Applicable input workers mount it read-only, verify it
and load local memory before readiness. Normal traffic makes no filesystem,
PostgreSQL, Orchestrator or credential-provider call.

Workflow state and leases always come from bounded background authority calls,
not snapshot files. An already-loaded safe worker may continue through a short
Controller or storage outage; a new or restarted worker stays unready. MVP has
one active Controller and deterministic restart recovery. This is continuity,
not Controller high availability.

## How PocketHive shows correct use

Each selected WorkItem carries a structured Dataset Context inside the normal
JSON body. The Worker SDK preserves it and checks Dataset, Group, revision,
Profile, allocation, validity and any lease/View/state revision immediately
before SUT network I/O.

Orchestrator derives one status used unchanged by REST, UI and PocketHive Model
Context Protocol (MCP).
`CONSUMING` requires fresh matching evidence for:

1. the authority revision and schema;
2. the Controller publication;
3. every expected worker's loaded snapshot;
4. local selection and the guarded SUT attempt;
5. any required lease and workflow transition; and
6. for Derivation, the frozen source/destination bindings and committed count.

Missing, stale or mismatched evidence is never green. Status exposes no record
identity, Outcome code or value. It proves the declared Dataset path operated,
not SUT acceptance, business correctness or exactly-once delivery.

## Release boundary

Implementation starts only after one canonical contract owns every Scenario,
worker, API, Context, status and snapshot shape. Production also requires
concurrency, failure, restart, storage, capacity and every-node reschedule tests;
zero/one-to-many Derivation and rollback tests; maximum-size performance tests;
and a target-scale 24-hour soak.

The MVP has no record expiry, reclamation or purge. Deployment limits and an
approved retention runbook must fund every stored record within the declared
operating horizon. Expiring supply requires a later governed reclamation design.
