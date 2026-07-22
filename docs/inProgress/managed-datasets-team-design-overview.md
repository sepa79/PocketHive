# Managed Datasets — Design Decisions and Rationale

Status: in progress — design ready; team approval pending

This document explains the important architecture decisions and their
trade-offs. Start with
[Managed Datasets, explained](managed-datasets-team-overview-plain-language.md)
if the feature is new to you. Use the
[normative specification](managed-test-data-lifecycle-generic-spec.md) for exact
implementation and acceptance requirements.

## Decision requested

Approve a Managed Dataset capability that:

- stores reusable test records durably;
- lets independent scenarios use those records through explicit bindings;
- keeps records near an operator-defined target;
- supports PostgreSQL first and Redis through a separate capability profile;
- runs as a bounded module inside Orchestrator, with no new container;
- keeps SUT calls in producer swarms;
- keeps measured traffic on worker-local data;
- uses existing PocketHive control and WorkItem paths;
- exposes real authoring and status through product APIs, UI and MCP.

This is approval of the design direction, not a claim that the feature already
exists or has passed qualification.

## The proposed shape

~~~mermaid
flowchart LR
  SM[Scenario Manager<br/>packages, Spaces, registrations<br/>and bindings]

  subgraph ORCH[Existing Orchestrator service]
    OA[Orchestrator application]
    MD[Managed Dataset module<br/>records, supply, readiness<br/>and recovery]
    OA <--> MD
  end

  PG[(Existing PostgreSQL)]
  RMQ[Existing RabbitMQ]
  SC[Swarm Controller]
  PS[Producer swarm]
  CS[Consumer swarm]
  SUT[System under test]

  SM --> OA
  MD <--> PG
  OA -->|lifecycle through ph.control| RMQ
  RMQ <--> SC
  SC <--> PS
  SC <--> CS
  MD -->|bounded DATASET_SUPPLY| RMQ
  PS -->|create or refresh| SUT
  PS -->|commit results| MD
  CS -->|background snapshot| MD
  CS -->|traffic using local data| SUT
~~~

## Decision 1: Datasets are independent packages

Dataset definitions live under:

~~~text
scenarios/managed-datasets/<datasetPackageId>/
~~~

A Dataset package is the versioned blueprint for one kind of Dataset. It is a
folder of definition files that Scenario Manager validates and publishes as
one unit, like a scenario bundle for reusable data.

It is not a running application, Docker container, database, archive format or
collection of records. Publishing it makes a definition available; it does not
create runtime data.

The package owns one Dataset's schema, field-subset contracts, mappings,
projections, source definitions and policies. It contains no deployment
settings, credentials or live records. A separate registration installs one
published package version into a Dataset Space; producer swarms then create the
live Managed Dataset records.

Scenario bundles contain only their own Dataset requirements and scenario-owned
assets. They do not copy Dataset definitions.

**Why:** a Dataset can be reused and versioned without coupling scenario
bundles or making one scenario aware of another.

**Trade-off:** Scenario Manager must support a second package type alongside
scenario bundles.

## Decision 2: requirements and selections are separate

A scenario requirement says what data the scenario needs. A Scenario Binding
selects the concrete Dataset that will satisfy it.

Consumer registration is explicit. PocketHive does not discover consumers by
matching aliases, scanning other bundles or inspecting runtime workers.

The current implementation has no Dataset requirement contract. Before
implementation, the team must freeze one canonical contract for the reserved
scenario-local datasets/requirements.yaml file and add fail-fast Scenario
Manager loading and validation.

The file is optional at bundle level:

- absent means the scenario has no Managed Dataset dependency and follows the
  existing runtime path;
- present means it contains at least one requirement;
- every declared requirement is mandatory in the MVP and must have exactly one
  valid Scenario Binding mapping.

There is no per-requirement optional flag. Unknown, empty, duplicate or
unmapped requirements fail validation rather than being silently ignored.

**Why:** scenarios remain portable while deployment-specific choices stay
visible and auditable.

**Trade-off:** a scenario that declares Dataset requirements cannot run until
every mapping is supplied and validated. Scenarios that declare none are
unaffected.

## Decision 3: each Dataset owns its contracts

The record schema defines every field in one Dataset. Package-local Dataset
Contracts expose approved field subsets for particular uses.

A contract is immutable with its package version. There is no shared live
contract registry, inheritance or cross-Dataset fallback.

**Why:** changing one contract cannot unexpectedly change many Datasets.

**Trade-off:** similar Datasets may repeat a small field-subset definition.
That duplication is safer than shared mutable coupling.

## Decision 4: Dataset Space is the authority boundary

A Dataset Space belongs to one immutable SUT Environment identity. It owns
access policy, classification ceiling, quotas and allowed storage profiles.

A registration installs one exact Dataset package version into one active
Space and selects:

- the Space-local Dataset alias;
- one storage adapter;
- one settings reference;
- one capability profile.

The package is portable; the registration is deployment-specific.

**Why:** several Datasets can share an authority boundary without putting
environment details into their definitions.

**Trade-off:** package publication and deployment registration are separate
operator actions.

## Decision 5: the runtime module sits inside Orchestrator

Dataset state is shared across swarms and outlives any individual Swarm
Controller. The team does not want another application container for the first
release.

The Managed Dataset module therefore runs inside orchestrator-service, but
behind a hexagonal boundary:

- domain code depends on application-owned ports;
- PostgreSQL, RabbitMQ, HTTP and scheduling are adapters;
- Dataset tables use a dedicated schema, role and connection pool;
- Dataset work uses bounded executors and bulkheads;
- other Orchestrator packages cannot access Dataset repositories directly.

**Why:** Managed Dataset state and its outbox row commit through one connection
in one local PostgreSQL transaction. The relay publishes only after commit.
This avoids another service and a distributed transaction manager.

**Trade-off:** Dataset and Orchestrator share a process and availability
boundary. Resource isolation and non-interference tests are release
requirements.

This placement does not move SUT-specific work into Orchestrator. Producer
swarms still create, refresh, validate and remove records.

## Decision 6: use existing control and work paths

Managed Datasets add no new RabbitMQ control plane.

| Existing path | Purpose |
|---|---|
| ph.control | Start, stop, configure and observe swarms |
| Controller-owned WorkItem route | Deliver a bounded DATASET_SUPPLY operation to a ready producer |
| Dataset API | Claim operations, commit records, download snapshots and read status |

The order is mandatory:

1. Orchestrator confirms through the existing lifecycle path that the producer
   and its input are ready.
2. The Dataset module reserves capacity and stores outbox intent.
3. The outbox relay sends bounded work through the controller-owned route.
4. The producer commits through the Dataset API.
5. The durable Dataset receipt completes the operation.

Rabbit acknowledgement is delivery evidence, not proof that a record was
committed.

## Decision 7: PostgreSQL first, Redis explicit

The first full profile is POSTGRESQL/MANAGED_RECORDS_V1. It supports records,
immutable revisions, schedules, operations, fences, receipts and transactional
outbox state.

REDIS/REDIS_COLLECTION_V1 is a separate, deferred profile. It may expose only
the operations it implements and qualifies.

Every registration names its adapter and settings explicitly. There is no
default, automatic conversion or fallback.

**Why:** Redis remains useful for collection-style scenarios without weakening
the stronger managed-record lifecycle.

**Trade-off:** a scenario requiring revisions or other unsupported behavior
cannot bind to Redis.

## Decision 8: output is preferred; interceptor is optional

DATASET_UPSERT is the preferred producer output.

Some workers already need RabbitMQ, Redis or another primary output. Those
workers can explicitly enable one shared Managed Dataset publisher interceptor.
It uses the same validation, idempotency and commit service as DATASET_UPSERT
and reconciles partial completion.

All workers gain this through the Worker SDK. They do not implement private
variants.

**Why:** the normal path stays simple while multi-output flows remain possible.

**Trade-off:** the interceptor has a more complex failure boundary and must be
used only when a second required output is unavoidable.

## Decision 9: business outcome controls routing

Network success alone does not prove that a source operation succeeded.

Each source binding freezes a result policy. HTTP and TCP adapters extract
protocol-specific evidence, then one shared evaluator produces:

- COMPLETED;
- FAILED;
- PENDING;
- UNCERTAIN.

Completed records may enter the primary Dataset. Confirmed failures may enter a
separately authorised remediation Dataset. Pending or uncertain operations stay
under reconciliation and do not count as supply.

Targets are configured and authorised before runtime. A response cannot invent
a Dataset name.

## Decision 10: traffic uses local snapshots

Consumer workers download bounded, immutable Dataset revisions in the
background. They validate the scope, revision and digest, then atomically
activate the local view.

During measured traffic, selection and remaining-validity checks are local.
There is no request-time call to PostgreSQL, Redis, RabbitMQ, Orchestrator or a
credential provider.

**Why:** shared data management must not become a latency or availability
dependency for every request.

**Trade-off:** workers need bounded memory and an explicit revision-activation
protocol.

## Decision 11: readiness means fit for use

Record count is only one readiness input.

The frozen Dataset Fitness Contract also checks schema, relationships, source,
classification, SUT Environment, validity and consumer projection. Its result
is PASS, FAIL or UNKNOWN.

A required Dataset starts new traffic only with a current PASS and matching
worker revision acknowledgements. Unsafe depletion pauses the affected input
before an invalid record can be selected.

## Decision 12: UI and MCP are clients, not authorities

The UI and PocketHive MCP use the same authorised Scenario Manager and
Orchestrator services as other clients.

They support real package/Space/registration/binding authoring and redacted
runtime inspection. Release builds contain no dummy Dataset rows or successful
fixture fallback.

Agents remain untrusted clients. HiveGate approval does not replace product
authentication, authorisation or validation. UI and MCP expose no raw record,
secret, SQL or Redis-query operation.

## What the MVP must prove

The first qualified profile must demonstrate:

- 50,000 eligible records with a 55,000 maximum;
- at least two concurrent consumer swarms;
- 1,000 measured requests per second for the agreed duration;
- safe target increase and decrease during use;
- zero central Dataset calls on measured request threads;
- restart-safe operations, schedules and outbox delivery;
- duplicate delivery without duplicate authoritative effects;
- deterministic HTTP and TCP result classification;
- no record or secret leakage through RabbitMQ, logs, metrics, UI or MCP;
- Dataset load staying within agreed Orchestrator control-plane SLOs.

Until that evidence exists, these are design targets rather than support
claims.

## Review checklist

The team should confirm:

- [ ] independent packages and package-local contracts are the right ownership
  model;
- [ ] the new scenario-local requirements contract is acceptable;
- [ ] Dataset Space and registration responsibilities are clear;
- [ ] the Orchestrator co-location trade-off is accepted;
- [ ] existing control and WorkItem paths remain authoritative;
- [ ] PostgreSQL-first and explicit Redis behavior are acceptable;
- [ ] the output/interceptor split covers producer needs;
- [ ] completed, failed and uncertain routing matches real source workflows;
- [ ] local snapshots meet traffic performance needs;
- [ ] the MVP qualification profile represents the first intended use case.

## Gate status

| Gate | Current state | Meaning |
|---|---|---|
| Design ready | Passed | The proposal is coherent enough for team review |
| Team approved | Pending | Product, Architecture, Security, Operations, UX and QA accept the decisions |
| Implementation ready | Pending | Canonical contracts, first use case and owners are approved |
| MVP qualified | Not run | Implementation has passed correctness, recovery, capacity and security gates |

## Research basis

These sources explain the selected patterns. PocketHive contracts remain the
authority:

- [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
  for application-owned ports and replaceable adapters;
- [Kubernetes controller pattern](https://kubernetes.io/docs/concepts/architecture/controller/)
  for desired-versus-observed reconciliation;
- [AWS transactional outbox guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
  for committing state and publication intent together;
- [RabbitMQ confirms and acknowledgements](https://www.rabbitmq.com/docs/confirms)
  for the delivery boundary;
- [PostgreSQL SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html)
  for short competing scheduler claims;
- [WCAG 2.2 status messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)
  for accessible asynchronous status.
