# Managed Datasets — Plain-language Guide

Status: proposed Release 1; implementation and qualification pending

For exact requirements, see the
[Managed Test Data Release 1 Specification](managed-test-data-lifecycle-generic-spec.md).

## One-minute version

A Managed Dataset is a proposed durable synthetic system-under-test (SUT) data
option. One provider swarm creates it for many compatible consumers. Existing
Redis Dataset, CSV Dataset and Scheduler adapters do not change.
`scenario.yaml` stays on Protocol v2. A consumer dependency uses one versioned
`datasets/requirements.yaml` file inside its bundle.

```text
provider -> named Managed Dataset -> explicit consumer selection -> normal scenario -> SUT
```

Every choice is explicit. PocketHive never substitutes another adapter, source,
Dataset, Group or View. If the requirements file is absent, the scenario has no
Managed Dataset consumer input or derived source and Create Swarm sends
`datasetSelections: []`. A provider-only scenario can still create a Dataset
through its explicit output binding. When the file is present, each requirement
must match exactly one Managed Dataset consumer input or derived source, and each
such binding must have one requirement.

The full design is Release 1, not one MVP. Delivery starts with scheduled shared
replay, then adds mutable-workflow parity, then the remaining sources and
Derivation. Safety and evidence are not deferred from the MVP.

## Delivery boundary

| Boundary | What it delivers |
|---|---|
| Shared-replay MVP | `SCHEDULER + REPLAY + SHARED`, named/grouped records, exact or empty consumer selection, local snapshots and REST/MCP evidence |
| Mutable parity | `WORKFLOW + EXCLUSIVE_LEASE`, Record State, Views, transitions and complete Outcome Mapping |
| Release 1 extensions | Replay exclusive, finite CSV/Redis import and bounded Managed Dataset Derivation |
| Release 1 completion | Shared MVP, mutable workflow, replay exclusive, CSV, Redis, Managed Dataset Derivation, read-only UI and full qualification |

The MVP still includes bundle validation, fencing, safe activation and cleanup,
terminal abandonment, capacity checks, restart recovery and security. A later
capability is absent from the catalogue until its own gates pass; PocketHive does
not substitute another capability. The later UI only displays the same
Orchestrator status model; it does not calculate another result.

## Choose the right model

| Need | Use |
|---|---|
| Partition records by stable schema-defined values | One Dataset with Groups |
| Reuse immutable records concurrently | `REPLAY + SHARED` |
| Make one record temporarily unavailable | `EXCLUSIVE_LEASE` |
| Track processing stage or outcome | `WORKFLOW` Record State and named Views |
| Create independently reusable output records | One bounded derived Dataset |
| Copy a whole Dataset unchanged | Not Release 1; future explicit clone operation |

Groups may use arbitrary schema-defined fields. They are frozen before provider
work starts and are not PocketHive business fields.

A View selects records whose current Record State matches its fixed rule.
Success, retry, failure and unknown can be Views over the same records without
copying them. Create another Dataset only for independent output records.

Each Release 1 record is one non-null JSON object. Array, primitive, `null` and
binary records are not supported. Managed Dataset never projects selected
fields: the verified local snapshot retains the complete canonical object. The
normal scenario pipeline may still transform that object into the exact request
sent to the SUT. This is payload shaping, not a data-redaction boundary.

Shared replay has no mutable Record State or View. Many swarms may reuse the same
immutable record concurrently. If flows must move a record between operational
states, they use `WORKFLOW + EXCLUSIVE_LEASE`; only one flow may hold that record
at a time. Shared reuse combined with concurrent metadata mutation is not
supported.

## How data arrives

Every provider binding selects exactly one source:

| Source | Behaviour | First delivery |
|---|---|---|
| `SCHEDULER` | Bounded provider work until the Group reaches its stored target | Shared-replay MVP |
| `CSV` | One finite validated import from a mounted file | Release 1 extension |
| `REDIS` | One finite import from an immutable copy of a referenced list; the live list is never popped or changed | Release 1 extension |
| `MANAGED_DATASET` | Bounded derived work from one exact upstream workflow View | Release 1 extension |

CSV and Redis validate and fingerprint the complete input before any Group is
visible. Failure blocks the import without fallback.

A Managed Dataset source requires one upstream
`WORKFLOW + EXCLUSIVE_LEASE` selection and one downstream output. A four-case
Outcome Mapping handles `SUCCESS`, `RETRYABLE_FAILURE`, `TERMINAL_FAILURE` and
`UNKNOWN`, with no default or SUT-response inference.

On `SUCCESS`, one PostgreSQL transaction creates `1..N` downstream records,
stores lineage, changes upstream state and releases the lease. Other outcomes
create none. Failure changes neither Dataset; exact retry returns the result.

Release 1 records do not expire and are never purged. Shared replay can reuse them
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

An exclusive workflow or derived-input claim returns `recordId`, current state
and lease, not a second copy of the immutable record. The worker finds that id
only in the exact verified local snapshot. Missing or mismatched local data stops
dispatch without an API or database fallback.

When an authority revision advances, Orchestrator sends the Controller a hint.
The hint marks the binding dirty. The Controller also checks authoritative
metadata on a bounded schedule, so a lost hint cannot prevent refresh. It
publishes only the latest observed revision after a required minimum interval,
which limits publication start rate. If one export lasts longer than the
interval, the next may start when it finishes, so admission budgets both. Workers
poll `ACTIVE.json` on an explicit jittered background interval and atomically
load a verified newer generation. Refresh failure preserves the old safe
snapshot; filesystem events are not the correctness mechanism.

Inactive snapshots remain for a qualified grace period covering storage
visibility, a hard worker-load maximum and clock skew. A slow load aborts safely.
Active and live staging revisions are never removed; if protected revisions fill
the available space, PocketHive blocks another publication instead of deleting
one early. After switching `ACTIVE.json`, the Controller records a fenced
Snapshot Activation Confirmation—the Orchestrator record of the durable
switch—before publishing the previous revision's deactivation marker. The marker
stays outside that revision, so it survives deletion and a lost response. After
the grace, the Controller rechecks safety, deletes the revision, verifies durable
absence and idempotently acknowledges deletion. Only a successful acknowledgement
allows marker removal.

Orchestrator always retains the latest Activation Confirmation. It retains each
older confirmation indefinitely until predecessor deletion is acknowledged.
Acknowledgement starts a fresh evidence period, so a lost successful response
can still be replayed after a late deletion. Recovery looks up the exact
confirmation for each predecessor; it never treats a truncated list as complete.
A missing lookup protects the revision. Every confirmation with a predecessor
but no stored deletion acknowledgement occupies one binding-local pending slot,
even after its predecessor files are gone. PocketHive reserves a free slot before
another applicable publication. Storing the acknowledgement releases that slot
and starts the separately reserved replay-evidence period. Exhaustion blocks only
that binding; other admitted bindings keep their reserved capacity. The retained
snapshot limit separately bounds files and never substitutes for this evidence
limit.

Snapshot Reader grant lifetime covers begin-response transit, hard export,
completion, clock skew and safety margin. After receiving the grant, the
Controller separately verifies enough time remains for the work. Expiry never
activates partial output.

A failed export cannot consume authority capacity forever. The Controller asks
Orchestrator to abandon it, or Orchestrator does so after both its descriptor and
work lease expire. One transaction proves it never completed, prevents its fence
from completing later and releases the reserved authority capacity. A completed
export, lost completion response or uncertain Active Reference is never
abandoned; recovery retains its capacity and finishes activation. Staging space
remains counted until qualified cleanup removes it safely.

Workflow state and leases always come from bounded background authority calls,
not snapshot files. An already-loaded safe worker may continue through a short
Controller or storage outage; a new or restarted worker stays unready. Release 1
has one active Controller and deterministic restart recovery. This is
continuity, not Controller high availability.

## How PocketHive shows correct use

Each selected WorkItem carries a structured Dataset Context inside the normal
JSON body. The Worker SDK preserves it and checks Dataset, Group, revision,
Profile, allocation, validity and any lease/View/state revision immediately
before SUT network I/O.

Workers report to the Swarm Controller. Controller full status preserves bounded
worker identity and restart epoch detail. Periodic deltas contain only small
per-binding counts, a reporter-set digest, freshness and minimum loaded activation
generation. A changed digest requests a new full status and remains unknown until
it arrives. Orchestrator consumes only Controller status and derives the read
model used unchanged by REST, UI and PocketHive Model Context Protocol (MCP).

Group Availability covers authority source, schema, integrity, supply and
storage health. A Group can be available with no consumer. Publication Status is
per admitted binding. Consumption Status covers worker loading, selection and
the SUT-attempt boundary. A Group status REST endpoint and matching MCP tool make
consumer-free availability visible.

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

Release 1 supports only the fixed `SYNTHETIC_NON_SENSITIVE` classification; it
has no per-Dataset override. Providers must keep sensitive values out because
PocketHive validates shape, not sensitivity. REST, UI and MCP expose bounded
counts and operational status, not record browsing, value search or a record's
transition history. Current state and aggregate evidence cannot reconstruct
every past transition.

## Release 1 completion

The shared-replay MVP is useful on its own but does not complete Release 1.
Mutable `WORKFLOW + EXCLUSIVE_LEASE` remains required parity. Replay exclusive,
finite CSV/Redis import and the bounded Managed Dataset derived source remain
Release 1 extensions. Each boundary passes its applicable operational gates
before PocketHive advertises it. The capability catalogue reports runtime
availability; it cannot remove a named target from Release 1.

Before implementation, M0 defines Dataset Requirements Document version 1 at
`datasets/requirements.yaml`. The file is optional, but when present it contains
at least one requirement. Scenario Manager alone parses it, checks its Scenario
roles, capabilities, Dataset Definitions and schemas, and includes it in the
validated bundle `artifactDigest`. UI, MCP, CLI, CI and Orchestrator preserve
that result instead of running their own YAML checks.

Scenario Manager reports `ABSENT` with bundle evidence, or `PRESENT` with
version, requirements and the same evidence. A present file is admitted only
when Scenario Manager and Orchestrator both advertise version 1. An invalid,
empty, unsupported or ignored file fails; it never becomes “no Dataset”.
Runtime preparation must present the exact validated bundle `artifactDigest`.
Scenario Manager renders from that immutable snapshot; a changed bundle returns
a conflict and requires explicit rediscovery rather than automatic Dataset
reselection. The verified digest is frozen with the Scenario Binding.

Existing Protocol v2 bundles with no Managed Dataset binding keep working in
either rolling-upgrade order, so this design needs no offline migration or swarm
drain. Missing version support disables only Managed Dataset discovery and
admission. A present requirements file stays unavailable until Scenario Manager
and Orchestrator both support version 1.

Each capability starts only after one canonical contract owns its Scenario,
worker, API, Context, status and snapshot shapes. The MVP does not wait for
executable contracts for post-MVP capabilities. Before advertisement, each
capability passes its applicable acceptance criteria. The MVP passes only the
shared-path, bundle-extension, publication, evidence, security, capacity, recovery,
performance and 24-hour soak gates. The normative specification contains the
complete test matrix.

Release 1 has no record expiry, reclamation or purge. Deployment limits and an
approved retention runbook must fund every stored record within the declared
operating horizon. Expiring supply requires a later governed reclamation design.
