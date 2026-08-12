# Managed Datasets — Plain-language Guide

Status: proposed shared-replay MVP; implementation and qualification pending

For exact rules, see the
[Managed Dataset Shared-Replay MVP Specification](managed-test-data-lifecycle-generic-spec.md).

## One-minute version

A Managed Dataset lets one provider swarm create reusable SUT records for many
consumer swarms.

```text
scheduled provider -> SUT -> PostgreSQL Managed Dataset
                                  |
                                  v
consumer Controller -> Redis copy -> worker memory -> SUT traffic
```

PostgreSQL is the source of truth. Redis is a fast, rebuildable copy for one
consumer swarm. Workers load that copy before they become ready, then use only
local memory while generating traffic.

Existing Scheduler, CSV Dataset and Redis Dataset options keep working as they
do today.

## What the MVP supports

- immutable JSON-object records;
- one required Dataset name plus stable IDs and versions;
- Groups based on arbitrary fields declared by the Dataset schema;
- one bounded scheduled provider run;
- many consumers reusing the same records at the same time;
- exact Dataset/Group selection at Create Swarm;
- REST and MCP evidence for load, selection and the SUT-attempt boundary.

The shared record is not removed or marked used after selection. There is no
availability queue or record movement. A Redis read is not proof that traffic
reached the SUT.

## Where definitions live

Dataset Definitions and reusable Schema Contracts are stored in version control
for normal review. Scenario Manager validates a complete catalogue update and
publishes it to PostgreSQL.

Each published version has exact content and a SHA-256 digest. Changing content
without changing the version fails the whole import. Running swarms use the
published PostgreSQL copy, so a Git outage or later file removal does not change
their binding.

There is no `latest`, automatic upgrade or fallback to a similar Dataset.

## How a provider creates data

The provider remains a normal PocketHive scenario:

```text
SCHEDULER WorkInput
  -> scenario logic and SUT calls
  -> MANAGED_DATASET WorkOutput(CREATE_RECORD)
```

Before it starts, Orchestrator creates the Dataset and its full Group plan in
`BUILDING`.

The Create Swarm plan explicitly names one finite Scheduler role; PocketHive
never guesses it. The logical topology and the fully rendered runtime queue
wiring must agree that this Scheduler reaches every exact Managed Dataset output,
and no other Scheduler reaches one through either view. PocketHive checks every
port, `work.out`/`work.in` queue pair, terminal role and `bindingRef`. Missing or
disagreeing details fail before it creates a Dataset, ledger or capacity
reservation.

PocketHive freezes the role, topology digest, runtime-wiring digest, Scheduler
config digest, positive `maxMessages`, run ID/fence and binding set. A changed
retry conflicts. An unrelated Scheduler outside both paths is ignored; a second
source needs a separate Provider Run. These extra admission checks do not change
ordinary Scenario Protocol v2 scenarios, and queue wiring never chooses the
Scheduler.

Before creating anything, PocketHive checks each Dataset separately. Its total
record target must not exceed the frozen Provider Run `maxMessages`, because one
scheduled item can create at most one record in that Dataset. A larger schedule
is valid; an absent, ambiguous, unbounded, mismatched or impossible plan creates
no Dataset, ledger or capacity reservation.

Each successful output:

- identifies the frozen provider run and binding;
- carries a Scheduler-owned `providerItemId` allocated once for that logical
  item and preserved through retry, redelivery and restart;
- validates one record with the shared strict codec and schema;
- assigns exactly one frozen Group; and
- writes idempotently to PostgreSQL using an unambiguous canonical key.

An exact retry returns the original result. The same retry key with different
content fails. Concurrent duplicates do not increase counts. The provider ID is
separate from the optional general WorkItem message ID, so this does not change
Scenario Protocol v2.

The Dataset does not complete merely because a Scheduler process stops. First,
Scheduler durably closes issuance. Every issued item must then finish, every
successful output must be committed and in-flight count must reach zero.
PostgreSQL closes the record fence before checking exact Group counts. A
committed retry still returns its receipt after closure; a new late output
fails. Exact targets seal revision 1. Underfill, failure or timeout remains
unavailable. PocketHive does not refill, repair, replace or choose another
Dataset.

## How a consumer chooses data

A consumer bundle can contain the optional file:

```text
datasets/requirements.yaml
```

It declares the exact Definition version/digest required by each Managed Dataset
input. It does not change Scenario Protocol v2. If the file is absent, the
scenario has no Managed Dataset consumer and sends an empty Dataset selection.
A provider-only scenario may still write a Dataset.

Create Swarm shows compatible sealed Datasets and Groups for the same SUT
Environment and Dataset Space. The caller makes an explicit selection. An empty
list or mismatch fails; PocketHive never chooses automatically.

## Why Redis is used

The Controller reads one exact sealed PostgreSQL revision and creates a private
Redis projection for that consumer swarm. The projection contains:

- ordered immutable records;
- a manifest with Dataset, Group, schema, revision, count and content digests;
- an Active Projection Reference with a monotonic generation.

New projection keys are separate from the current keys. The Active Reference is
changed atomically only after the complete projection is written and checked.
Partial data is invisible.

Before Redis is changed, Orchestrator permanently reserves the next generation
in PostgreSQL. Every new generation is higher than all previous reservations,
including failed and unconfirmed ones. A crash followed by total Redis loss can
therefore rebuild at a higher generation without reuse or downgrade.

Redis uses `noeviction`. Capacity is reserved for the current copy, a complete
new copy and recovery headroom. If memory is exhausted, the new copy fails and
the current copy remains.

The binding names the Redis deployment profile explicitly. PocketHive does not
silently reuse or change an existing Redis Dataset endpoint. Sharing one Redis
deployment is allowed only after both workloads and their capacity are checked.

Controller is the trusted sole writer. Its deny-by-default Redis account is
limited to the exact projection commands and binding prefix. The Redis Function
makes activation atomic and fenced; it is not a separate login or permission
boundary. Controller cannot run ad-hoc scripts, administer Functions or cross
bindings. Workers can only read their binding prefix. Binding/environment
credentials use the deployment's existing external secret injection and
rotation.

## How workers stay fast and safe

Workers fetch records in bounded pages in the background. They verify the
Dataset, Group, revision, schema, count, content digest and activation
generation, then build a complete local index. They swap local memory only when
the new copy is fully valid and newer.

Traffic uses local `ROUND_ROBIN` selection. From selection until the SUT network
attempt, there is no call to Redis, PostgreSQL, Controller, Orchestrator,
Scenario Manager or a credential service.

If Redis or Controller is briefly unavailable:

- an already-loaded worker may keep using its verified local copy for the
  admitted continuity window;
- a new or restarted worker stays unready; and
- total Redis loss is repaired by rebuilding the exact PostgreSQL revision with
  a higher generation.

Redis persistence and HA can reduce downtime, but PostgreSQL plus reprojection is
what keeps the design correct. This is loaded-worker continuity, not full
Controller HA.

## How MCP proves correct use

The SDK attaches protected Dataset Context to each selected WorkItem. Every
pipeline step preserves it. The declared SUT client checks it immediately before
starting network I/O.

PocketHive reports three separate facts:

1. Group Availability — is the sealed authority healthy?
2. Projection Status — is the exact Redis generation active and loaded?
3. Consumption Status — did each expected worker load, select and reach the
   guarded SUT-attempt boundary recently?

`CONSUMING` needs the complete matching chain. Redis access, a record count or
selection without a SUT attempt never turns it green.

Loading and observed use are separate. Status shows guarded-attempt coverage as
`observed/expected`:

- `NOT_READY` — no active consumer, or an expected worker is missing, stale or
  has not loaded the exact copy;
- `AWAITING_EVIDENCE` — loaded, but no current worker attempt observed;
- `PARTIAL_EVIDENCE` — some, but not all, expected workers observed;
- `CONSUMING` — every current worker epoch observed; and
- `STALE_EVIDENCE` — earlier evidence is no longer fresh.

Low or uneven traffic can therefore leave a healthy worker awaiting evidence;
that is not proof of incorrect Dataset use and does not block an otherwise
valid Create Swarm. `staleAfter` decides whether a worker report is current;
`evidenceWindow` decides whether an attempt is current. Both must be at least the
qualified worst-case time for the next worker report, both delivery hops,
Controller aggregation and accepted clock skew. Equality remains current; a
shorter window fails before creation. Older, duplicate, delayed or replaced-worker
reports cannot create false coverage. A stale worker stays in the expected count
but cannot count as loaded or observed. Worker replacement changes its epoch and
requires fresh evidence. Low traffic is still valid; no traffic-rate or
all-worker evidence promise is required at admission.

Workers report to Swarm Controller. Controller sends bounded aggregates to
Orchestrator. REST, MCP and later UI show that same status. They expose no record
values, record IDs, Redis keys, credentials or business outcome.

This evidence proves PocketHive used the declared Dataset path. It does not prove
that the SUT accepted or responded to the request, or that a business
transaction succeeded.

## What comes later

The MVP deliberately defers:

- exclusive leases and temporary unavailability;
- mutable workflow state and Views;
- Managed Dataset provider input and automatic refill;
- additional Managed Dataset imports or derivation;
- expiry, purge and retirement;
- tags, arbitrary queries, payload replacement and record browsing.

Provider input/refill is reconsidered only when approved data can expire/deplete
or mutable workflow needs controlled replenishment. Shared MVP records do not
need refill because they are immutable, non-expiring and reusable for 24/7
traffic.

Future lease and workflow authority stays in PostgreSQL. Redis may distribute
candidate IDs later, but a Redis list or Stream can never grant a lease.

## What “ready” means

The documentation is ready to define M0 executable contracts. The feature is not
proven until catalogue, provider concurrency, projection crash/failover, memory,
Scheduler-source topology/wiring agreement, evidence delivery/ordering,
maximum-topology
performance and 24-hour soak tests pass.
