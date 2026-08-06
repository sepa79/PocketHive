# Managed Datasets — Plain-language Guide

Status: in progress; proposed MVP, implementation and approval pending

For exact requirements, see the
[Managed Test Data MVP Specification](managed-test-data-lifecycle-generic-spec.md).

## One-minute version

Managed Datasets let many test swarms safely reuse durable synthetic SUT data.

```text
provider binding -> chooses exactly one Scheduler, CSV, Redis or Managed Dataset source
provider run -> creates one named Managed Dataset
consumer run -> chooses one exact Dataset Group or workflow View/transition
Controller -> publishes the chosen records once for that swarm
consumer workers -> verify and use them from local memory
```

The feature is optional and additive. Existing Redis Dataset, CSV Dataset and
Scheduler adapters do not change. A swarm that needs no Managed Dataset uses
explicit empty requirement and selection arrays. PocketHive never silently
switches adapter, source, Dataset, Group or View.

## What is shared

A provider swarm can create reusable SUT records once for many consumer
swarms. A Dataset keeps both a readable `name` and an opaque identity. Its
records may be split by arbitrary schema-defined Group keys; these are not
PocketHive fields. A Dataset without visible grouping has one internal Group.

Each Dataset chooses one behaviour:

| Profile | Plain meaning |
|---|---|
| `REPLAY` | The record payload never changes. `SHARED` allows repeated concurrent reuse; `EXCLUSIVE_LEASE` temporarily reserves one record. |
| `WORKFLOW` | The payload stays fixed, but separate versioned Record State may change through named Views and declared transitions. Every use is exclusively leased. |

`WORKFLOW` keeps the mutable capability needed by current scenarios without
turning PocketHive into a general database. One consumer requirement names one
View and exactly one transition. A completion sends the full next state.
PocketHive checks the live lease, current state revision, allowed fields, state
schema and target View, then changes state, View memberships and lease together.
A failure changes none of them.

Use state and named Views for success, retry, failure or other workflow stages.
Views are overlapping selections over the same records; they are not extra
Datasets and do not copy data.

| Need | Use |
|---|---|
| Partition records by stable schema-defined values | One Dataset with Groups |
| Track processing stage or outcome | `WORKFLOW` Record State and named Views |
| Temporarily prevent concurrent use | `EXCLUSIVE_LEASE` |
| Create independently reusable output records | One bounded derived Dataset |
| Copy an entire Dataset unchanged | Not MVP; use a future explicit clone operation |

Create a derived Dataset only when its records need their own identity, schema,
allocation, retention or lifecycle. Exact copy is a different concern: a
future clone operation must pin one immutable source revision. Arbitrary
filters or patches, free-form tags, payload replacement and multi-destination
changes are outside this MVP.

## Where the choices live

| Place | Owns |
|---|---|
| Dataset Definition | Name, Profile, grouping, record schema and any workflow state schema, Views and transitions |
| Versioned Dataset contracts | Reusable immutable schema parts; roots select exact versions |
| Provider binding | Exactly one source, concrete Groups, provider-only templates/mappings, allocation, lifecycle and supply |
| Consumer template | Required Profile and allocation; workflow View, one transition, completion role and release rule |
| Consumer binding | Checks those requirements against one SUT Environment and Dataset Space |
| Create Swarm | One exact compatible Dataset/Group or View per requirement, or `datasetSelections: []` |
| Deployment profile | Connections, storage adapter, clock health and every safety/capacity limit |
| Operator runbook | Capacity horizon, alerts, response, backup/restore and escalation |

Definitions are mounted like Scenario Bundles:

```text
scenarios/managed-dataset/<name>/
scenarios/dataset-contracts/<name>/<version>/
```

Only Scenario Manager reads these folders. Schema references use exact
versions. Invalid packages fail as a unit; PocketHive keeps the last valid
registry revision rather than guessing another version.

## How data arrives

Every provider binding has exactly one source:

| Source | Behaviour |
|---|---|
| `SCHEDULER` | Renewable provider work from bounded refill grants. A completed cohort publishes one new revision. |
| `CSV` | Validate and import one mounted provider-bundle file in stable row order. |
| `REDIS` | Copy one referenced list and import the fixed copy in stable index order. Never pop or alter the live list. |
| `MANAGED_DATASET` | Lease records from one exact upstream workflow View and derive bounded independent records into one downstream Dataset. |

CSV and Redis imports are finite. PocketHive fingerprints and validates the
whole input before publishing all Groups together. Redis uses a bounded owned
staging key and treats copy, expiry or cleanup failure as an explicit failure;
it never falls back to the live list.

For a Managed Dataset source, Create Swarm freezes one upstream
`WORKFLOW + EXCLUSIVE_LEASE` selection and one downstream output. The scenario
normalises its result to exactly one of `SUCCESS`, `RETRYABLE_FAILURE`,
`TERMINAL_FAILURE` or `UNKNOWN`, then an explicit four-case mapping produces
the upstream record's complete next state. There is no default case and
PocketHive does not guess from a SUT response.

`SUCCESS` creates the configured bounded `1..N` downstream records. Every
other outcome creates zero. PocketHive commits those records, their lineage,
the upstream state change and lease release in one PostgreSQL transaction. A
failure changes neither Dataset; exact replay is idempotent. This supports
chains of provider/consumer swarms without creating separate success, retry
and failure Datasets.

## How consumers get fast local data

PostgreSQL remains the authority for Managed Dataset records, revisions, state,
Views, leases, imports, derivation lineage and workflow changes. Redis remains
the authority for the existing Redis Dataset option only.

For each selected Managed Dataset binding, the swarm's Controller asks
Orchestrator for one exact record revision. It writes bounded record chunks, a
manifest and a final `READY` marker to deployment-owned shared storage. Digests
prove the files match the authority revision.

The Controller can write only inside its swarm directory. Only workers that
consume that binding receive a read-only mount; unrelated workers receive no
Dataset mount. Scenario authors cannot set storage paths or permissions.
Missing or unhealthy storage stops provisioning instead of being ignored.

Each consumer worker checks the marker, manifest, schema and file digests, then
loads an immutable snapshot into local memory before becoming ready. Normal
traffic selects records from that memory. It does not call the filesystem,
PostgreSQL, Controller, Orchestrator or a credential service on the measured
SUT-request path.

An already-loaded safe worker may continue during a temporary Controller or
storage failure. A new or restarted worker stays unready until it can verify a
publication. Workflow state and lease decisions always come from bounded
background authority calls, never from the snapshot files.

## How PocketHive proves the right data is used

Each selected WorkItem carries a small structured Dataset Context inside the
normal JSON body. It is not an observability or broker header. The SDK preserves
it and checks the Dataset, Group, revision, Profile, allocation, validity and
any lease/View/state revision immediately before SUT network I/O.

Orchestrator calculates one status used unchanged by REST, the Datasets UI and
PocketHive MCP. `CONSUMING` requires fresh matching evidence that:

1. PostgreSQL identifies the expected revision and schema;
2. the active Controller published that exact revision and digest;
3. every expected input worker loaded that exact publication;
4. local selection and the guarded SUT attempt report the same frozen Dataset,
   Group, Profile, optional View and allocation;
5. exclusive use had a valid lease; and
6. workflow use completed the exact declared transition, or an explicitly
   allowed unchanged release, for the claimed state revision, accepted Outcome
   class and frozen mapping digest; and
7. derivation used the frozen source/destination bindings and reports the
   atomically committed downstream count.

Missing, stale, partial or mismatched evidence can never appear green. The
status proves which Dataset contract and declared mapping reached PocketHive's
scenario boundaries. It exposes neither Outcome codes nor record identities.
It does not prove that the SUT accepted the request, that delivery happened
exactly once or that an authored business classification was correct.

## What must pass before implementation and release

Implementation starts only after the Dataset Space/Scenario Binding model and
one canonical contract pack are approved. That pack owns Scenario, worker,
API, Context, status, snapshot and restricted-schema shapes; examples in the
requirements do not become duplicate contracts.

Production release requires concurrency and failure tests, typed mount and
digest tests, complete Outcome Mapping tests, zero/one-to-many derivation,
atomic rollback, lineage and replay tests, every-node rescheduling,
capacity/overload tests, Controller and storage outages, and a target-scale
24-hour soak. At the largest approved snapshot and worker fan-out, throughput
and p95/p99 latency overhead versus the same preloaded-memory workload must
each be at most 2%.

The deployment must also set hard count, rate, byte, filesystem, memory and
concurrency limits and approve a retention/capacity runbook. The MVP has no
purge state machine, so neither operators nor cache cleanup may directly delete
authoritative PostgreSQL data or an active publication.

`EXCLUSIVE_LEASE` and snapshot publication are fully specified but remain
unproven until these qualification gates pass.
