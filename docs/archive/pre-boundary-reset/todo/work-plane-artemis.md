> Status: superseded / archived on 2026-09-07.
> Original path: `docs/todo/work-plane-artemis.md`.
> Broker replacement is deferred until enforced Work boundaries exist; not the active execution order.
> Current execution plan: `docs/inProgress/work-plane-module-boundaries.md`.
> Original text below is historical; unchecked work is not an active instruction and is not marked fixed.

Status: idea / future plan

# Apache ActiveMQ Artemis for the Work Plane

## Decision summary

PocketHive should evaluate replacing RabbitMQ with Apache ActiveMQ Artemis for the **Work Plane only**.
The Control Plane remains on RabbitMQ in this scope.

This is not a two-class adapter swap. Worker message processing is already broker-neutral at its domain/runtime
boundary, but the complete Work Plane also owns broker resource provisioning, bindings, queue statistics,
reconciliation, cleanup, diagnostics, configuration, and deployment. Those responsibilities must be behind one
complete Work Plane broker boundary before an Artemis implementation is added.

The work starts only after the current control-plane refactor and its prerequisite ownership cleanup are complete.

## Why this is being considered

The immediate driver is a new 3DS test flow:

1. a challenge is created;
2. an automatic response is published immediately with a requested delivery time 1–10 minutes in the future;
3. the broker holds the message;
4. after the delay, the message is delivered to the existing challenge input and processed like ordinary work.

The expected scale is thousands to tens of thousands of scheduled messages. Artemis has native scheduled delivery,
which keeps ownership of durable waiting messages in the broker. This is preferable to building a separate timer
subsystem from Redis expirations plus a polling/recovery service.

RabbitMQ delayed retry, queue TTL plus dead-letter routing, and scheduled business delivery are not the same
contract. RabbitMQ alternatives may remain useful for retry, but they are not the target design for this flow.

## Alternatives discussed

### Upgrade RabbitMQ and retain it for the Work Plane

Upgrading RabbitMQ is acceptable maintenance and will be needed independently. It does not by itself establish the
required native scheduled-delivery contract. RabbitMQ TTL/DLX patterns and delayed-message extensions should not be
described as equivalent to durable first-class scheduling without a dedicated PoC proving all required semantics.
This is not the preferred 3DS direction.

### Redis expiry plus a dispatcher service

A Redis sorted set or expiring-data design plus a service that finds due messages can implement scheduling, but it
creates another durable state owner, polling/recovery logic, delivery claiming, idempotency, monitoring, and HA
concerns. Keep it out of the design unless Artemis fails the PoC; it must never activate as a silent fallback.

### Another broker or managed service

A broker abstraction remains valuable because a PocketHive deployment in AWS may prefer SQS over operating
RabbitMQ. That future option is a design pressure on the Work Plane port, not a requirement to implement a generic
multi-broker framework now. Kafka and other brokers are likewise not candidates until a concrete Work Plane use
case and semantic fit are defined.

## Decisions already made

- Migrate only the Work Plane in the first stage.
- Keep the Control Plane on RabbitMQ.
- Introduce one complete Work Plane adapter boundary, including provisioning and cleanup, before adding Artemis.
- Preserve the existing broker-neutral `WorkInput`, `WorkOutput`, and canonical `WorkItemJsonCodec` model.
- Do not introduce a JMS-specific `WorkMessage` or a second JMS-to-`WorkItem` converter. Artemis transport code
  must use the canonical WorkItem JSON envelope.
- Broker selection and settings are explicit. There is no automatic RabbitMQ/Artemis fallback.
- The 3DS scheduled publisher is a narrow, typed Work Plane capability, not a generic application timer service.
- Existing tests and the current E2E test system are not changed as part of this work. The whole test system will
  be rewritten separately. The 3DS PoC gets purpose-built tests outside that legacy system.
- RabbitMQ-to-Artemis protocol compatibility is not assumed. The current Rabbit adapter uses Spring AMQP and the
  RabbitMQ AMQP 0-9-1 client model; selection of an Artemis client/protocol is an explicit design decision.
- Artemis is open-source software under the Apache License 2.0; no broker runtime licence is expected. Hosting,
  operations, support, and any third-party managed offering are separate costs.

## Existing boundaries to retain

The worker runtime already exposes transport-neutral Work Plane contracts:

- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/input/WorkInput.java`
- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/input/WorkInputFactory.java`
- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/output/WorkOutput.java`
- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/api/WorkItemJsonCodec.java`

Rabbit-specific implementations exist beneath those boundaries, including `RabbitWorkInput`,
`RabbitWorkInputFactory`, `RabbitWorkOutput`, `RabbitWorkInputListenerConfigurer`, and
`RabbitMessageWorkerAdapter`.

`RabbitWorkItemConverter` is transport plumbing around the canonical codec; it is not evidence that the Work Plane
domain needs another message model. The Artemis adapter should serialize and deserialize the same canonical
WorkItem envelope directly.

## Missing complete Work Plane boundary

The adapter must cover more than worker input and output. The broker-neutral contract needs explicit capabilities
for:

- declaring the Work Plane addresses/queues and routing bindings for one swarm;
- deleting exactly the resources owned by that swarm;
- recording owned resources so cleanup never guesses by name;
- querying queue depth, consumer count, and supported age/health information;
- verifying provisioning and removal postconditions;
- publishing ordinary work;
- consuming and acknowledging work with explicit concurrency and credit/prefetch semantics;
- publishing work with an absolute scheduled delivery time or validated delay;
- exposing broker-specific diagnostics without leaking them into the domain contract.

Current Rabbit-specific infrastructure around these concerns includes:

- `swarm-controller-service/.../infra/amqp/SwarmWorkTopologyManager.java`;
- `swarm-controller-service/.../runtime/SwarmRuntimeInfrastructure.java`;
- `swarm-controller-service/.../runtime/SwarmQueueStatsPortAdapter.java`;
- Rabbit topology and removal verification under `orchestrator-service/.../runtime/`;
- worker and swarm configuration that currently names RabbitMQ explicitly;
- Docker/Compose, monitoring, debug tooling, and operational credentials.

The extraction must produce one authoritative owner of Work Plane topology and resource lifecycle. RabbitMQ and
Artemis are implementations of that owner, not parallel sources of routing truth.

## Prerequisites

1. Resolve CP-N01 from `control-plane-post-simplification-review.md`: remove duplicate topology ownership. Even
   though Control Plane remains on RabbitMQ, the shared topology/configuration ownership must be unambiguous.
2. Resolve CP-N05: separate broker resource lifecycle from container lifecycle, image resolution, environment
   assembly, manifests, and metrics configuration.
3. Confirm that the canonical WorkItem JSON envelope in `workitem-transport-agnostic.md` is the only Work Plane wire
   payload contract.
4. Define the complete Work Plane broker port and its provisioning/removal postconditions in architecture docs
   before implementing either adapter against the new boundary.

These prerequisites do not authorize changes to the legacy E2E system.

## Proposed target shape

```text
worker/runtime + swarm lifecycle
              |
              v
   complete Work Plane ports
     |                    |
     v                    v
RabbitMQ adapter     Artemis adapter
input/output         input/output
provision/cleanup    provision/cleanup
stats/health         stats/health
                     scheduled publish
```

There is no JMS-domain mapping layer. Client-specific message construction stays inside the Artemis transport
adapter, while `WorkItemJsonCodec` remains the single payload codec.

## Artemis topology direction

The PoC must validate the mapping rather than assuming AMQP 0-9-1 semantics:

- Rabbit topic exchange intent maps to an Artemis multicast address plus queues/subscriptions and explicit
  filters/routing rules where required.
- A worker work queue maps to an anycast queue with competing consumers.
- PocketHive routing-key wildcards must be tested against the chosen Artemis protocol/client; they must not be
  treated as automatically equivalent to Rabbit topic bindings.
- acknowledgements, redelivery, dead-lettering, expiry, retry, credit/prefetch, persistence, and ordering must be
  declared explicitly per adapter.
- scheduled delivery must be broker-native and durable across an Artemis restart.

The Artemis client choice remains open until the PoC. Candidates are Artemis Core API, JMS over the Artemis client,
and AMQP 1.0. The decision should favour the smallest adapter that preserves the existing Work Plane ports,
supports scheduled delivery explicitly, exposes acknowledgement/credit behavior, and does not introduce a second
wire model. AMQP 1.0 must not be selected merely because RabbitMQ uses a protocol named AMQP.

## Minimal 3DS proof of concept

The PoC is deliberately narrower than a migration:

1. Start one persistent Artemis broker in an isolated development profile.
2. Provision one challenge input address/queue through the proposed Work Plane provisioning port.
3. Implement a typed `ScheduledWorkPublisher` behind the Work Plane boundary.
4. Encode every payload with `WorkItemJsonCodec` and attach the broker-native scheduled-delivery instruction.
5. Publish a configurable batch representative of expected load, initially 10,000 messages, distributed across
   delays from 1 to 10 minutes.
6. Consume them through the Artemis Work Input adapter and deliver them to a minimal challenge-input test handler.
7. Restart Artemis while messages are waiting and verify that durable scheduled messages still arrive.
8. Record publish latency, scheduled backlog, delivery lateness distribution, throughput after release, duplicates,
   loss, redeliveries, ordering observations, storage growth, and recovery time.
9. Verify explicit acknowledgements and a failure case that reaches the configured dead-letter destination.
10. Delete the PoC resources through the same provisioning/cleanup abstraction and verify the removal
    postcondition.

Acceptance criteria:

- all accepted messages are eventually observed or explicitly accounted for in the dead-letter path;
- no message is delivered before its scheduled time beyond an agreed clock tolerance;
- duplicate delivery is measured and handled by the existing idempotency contract rather than assumed impossible;
- scheduled messages survive broker restart;
- resource cleanup removes only resources recorded as owned by the PoC;
- RabbitMQ Control Plane behavior is unchanged.

## Suggested implementation sequence after the PoC

1. Specify the complete Work Plane ports and ownership manifest.
2. Move current RabbitMQ provisioning, cleanup, queue statistics, input, and output behind that boundary without
   changing behavior.
3. Add the Artemis implementation and explicit configuration discriminator.
4. Add Artemis deployment, persistence, health, metrics, credentials, TLS, and optional HA configuration.
5. Run the standalone 3DS tests and Work Plane adapter contract tests.
6. Migrate one isolated swarm/profile to Artemis while the Control Plane remains on RabbitMQ.
7. Drain or explicitly discard owned RabbitMQ Work Plane resources according to an approved cutover plan.
8. Retain rollback by selecting the RabbitMQ adapter explicitly and reprovisioning from the ownership manifest;
   do not dual-consume the same work queue implicitly.

Dual publish is not the default because it creates duplicate business work. If used for migration evidence, the
secondary path must be a shadow sink with explicit identity and deduplication semantics.

## Effort framing

Treat this as two implementation units rather than an input/output class replacement:

1. extract and prove the complete Rabbit-backed Work Plane boundary, including provisioning, ownership inventory,
   cleanup, statistics, and removal postconditions;
2. implement the Artemis adapter, deployment, scheduled-delivery PoC, and standalone adapter/3DS verification.

A calendar estimate should be made after CP-N01 and CP-N05 are closed and the port surface is approved. Until then,
an estimate for only `WorkInput` and `WorkOutput` would omit material work and would not be a reliable commitment.

## Operational scope

Before production use, define:

- persistent journal storage sizing for scheduled backlog and restart recovery;
- Docker/Kubernetes image, readiness/liveness checks, graceful shutdown, disruption budget, and storage class;
- broker and queue metrics, scheduled-message count if available, oldest scheduled/deliverable age, DLQ depth,
  consumer count, disk pressure, paging, connection failures, and delivery lateness alerts;
- separate credentials and least-privilege permissions for provisioning, publishers, and consumers;
- TLS trust, certificate rotation, secret distribution, and disabled anonymous access;
- backup/restore expectations and whether Artemis HA/replication is needed for the 3DS environment;
- capacity tests for tens of thousands of scheduled messages and burst release behavior.

## Explicitly out of scope

- migrating the Control Plane to Artemis;
- abstracting every PocketHive transport to support arbitrary future brokers in one step;
- SQS implementation, although the Work Plane boundary should not prevent one later;
- adapting, extending, or incrementally refactoring the current E2E test system;
- using Redis expiry polling as an implicit fallback for failed Artemis scheduling;
- preserving RabbitMQ-specific configuration names as a compatibility layer.

## Open questions

- Which Artemis client gives the cleanest explicit scheduled-delivery and acknowledgement semantics for the current
  Spring Boot/Java 21 worker SDK: Core API, JMS, or AMQP 1.0?
- Does PocketHive need Rabbit-style topic wildcard behavior in the Work Plane, or can the canonical topology be
  expressed as explicit address/queue bindings?
- Is scheduled time an absolute instant or a relative delay at the PocketHive port? The contract must choose one
  representation and define clock ownership.
- What delivery lateness and clock tolerance are acceptable for the 3DS test flow?
- What is the required duplicate/idempotency behavior at challenge input?
- Must delayed messages be cancellable or reschedulable after publication?
- What retention, expiry, DLQ, and retry policy applies when a scheduled 3DS response can no longer be consumed?
- Is a single persistent broker sufficient for test environments, and what production/HA target is anticipated?
- Which operational surfaces currently named `Rabbit*` belong to Work Plane and which remain correctly Rabbit-bound
  because they serve the Control Plane?

## Related plans and findings

- `docs/archive/pre-boundary-reset/todo/workitem-transport-agnostic.md`
- `docs/archive/pre-boundary-reset/todo/control-plane-post-simplification-review.md` (especially CP-N01 and CP-N05)
- `docs/ARCHITECTURE.md`
