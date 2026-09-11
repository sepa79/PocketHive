# Rabbit technology boundary

Status: approved target direction, 2026-09-11; not a claim of implemented isolation.
The [execution plan](../inProgress/work-plane-module-boundaries.md#current-execution-contract)
owns sequence and completion. This design replaces the previous B02–B07 technology split.
Current wire and lifecycle contracts in [ARCHITECTURE](../ARCHITECTURE.md) remain effective.

## 1. What is being separated

Worker input/output is a capability boundary, broader than Work Plane transport.
Rabbit and future Artemis may carry work between workers. Redis supplies dataset/output
capabilities; CSV and generator/scheduler provide input only in the current design.
Implementing Input or Output does not itself make a capability an inter-worker transport.
Do not force broker administration methods onto every input/output interface.

Rabbit also carries Control Plane traffic. Both planes must use one Rabbit technology
module, with separate explicit configuration. A separate implementation of the same broker
operation in each plane violates SSOT even if each is behind its own interface.

## 2. Artifacts and namespaces

Target artifact: `common/rabbit-adapter`; namespace: `io.pockethive.rabbit`.

| Package | Responsibility |
| --- | --- |
| `io.pockethive.rabbit.api` | Supported public contracts for configuration, topology/resource operations and transport; composition entrypoints |
| `io.pockethive.rabbit.config` | Sole implementation of Rabbit settings/defaults/normalization/validation and environment projections |
| `io.pockethive.rabbit.topology` | Physical naming and broker resource operations: declaration, bindings, inspection, removal/purge |
| `io.pockethive.rabbit.transport` | Connections, publishing, consuming and delivery/settlement mechanisms |

One module does not mean one class. Split implementation types by responsibility and
keep implementation packages inaccessible to consumers through visibility and boundary
rules. Public API contract types must not expose Rabbit client or Spring-AMQP types.
Use existing Spring integration internally where suitable; do not invent a client framework.

Absorb Rabbit-specific code currently in rabbit-config, worker-sdk, control-plane-spring,
Controller and Orchestrator as the corresponding consumers migrate. Existing domain
code in those modules remains there. Do not preserve old aliases or duplicate implementations.

## 3. Ports, owners and state transitions

Control Plane owns message meaning, recipients, correlation and operation state.
Work Plane owns logical worker connections and work semantics. The Rabbit module owns
how those requirements are realized in RabbitMQ. A caller decides when a permitted
operation is needed; the module performs it and reports its actual outcome.

Keep domain wire/routing contracts in their existing canonical definitions. The Rabbit
module consumes those definitions and owns broker-specific name/binding realization;
it must not create another copy of the control routing grammar. Move existing physical
name formulas to their sole owner without changing public names as an incidental refactor.

Offer narrow capabilities for the operation required. Do not expose raw clients, arbitrary
client callbacks or a universal administration handle. Cleanup authorization remains with
the governing domain boundary; module ownership grants no additional permission.

### Physical resource naming transfer

`RabbitResourceNames` in rabbit-adapter owns the existing Work prefix/queue/exchange,
Control queue and debug tap name formulas. WorkResourceNamesPort and ControlResourceNamesPort
are neutral capabilities in topology-core; Control descriptors consume the latter explicitly.
ControlPlaneRouting remains the canonical domain signal/event grammar. Descriptors retain
recipient/binding policy, not broker name construction. The transfer preserves names,
including the Controller's existing swarm-segment handling. No second default constructor
may reconstruct a name without the owner. Debug tap shortening belongs to the same owner.

WorkResourceNamesPort.address exposes a read-only WorkAddress (exchange, queue, routingKey).
RabbitResourceNames alone realizes the existing routingKey-equals-queue rule. Provisioning,
worker configuration, status and debug taps consume that projection; they must not infer
routing keys from queue names. This adds no topology registry or mutable state.

RabbitDebugTapSpec owns the mapping of debug tap TTL/size intent to Rabbit queue arguments,
flags and binding. Orchestrator retains tap lifecycle and request limits. The mapping
preserves existing seconds-to-milliseconds conversion and queue behavior.

## 4. Configuration and topology SSOT

One Rabbit owner defines each setting, default, normalization and constraint. All producers
and consumers use its API/result: Spring startup, Scenario authoring, Controller plans,
environment projections, runtime and diagnostics. Boundary decoding may adapt the input
representation but must not independently redefine its semantics.

AUTHORING contains logical intent and applicable tuning. Physical destinations are resolved
by the naming owner for runtime use. Preserve required explicit selection and validation;
no fallback adapter, raw-shape recovery or silent compatibility path is introduced.

Choose and consolidate existing properties/parser implementations by responsibility, not
by class name. Do not simply empty properties into a new parser while leaving actual
adapter consumption untouched. Resolved values must reach the operations they configure.

Work and Control have explicit separate settings, connections and resource scopes even
when configured to use the same broker. No ambient shared mutable configuration or global
customizer may silently transfer one plane's tuning to the other.

Direct properties in scenario bee.env may not override the environment-owned Control Rabbit configuration
(`spring.rabbitmq`, including Spring environment-name aliases). RabbitConnectionEnvironment
owns this rejection policy; Scenario validation and worker planning invoke it before effects.
Other worker environment variables remain supported. This explicitly replaces the early
per-worker Control connection override behavior; delivery and ACK policies are unchanged.

### Accepted CP override limitation

**Warning — explicitly accepted by the user, 2026-09-11:** indirect startup configuration
can bypass this direct-key guard. For example, `SPRING_APPLICATION_JSON` can contain
`spring.rabbitmq` settings, and `JAVA_TOOL_OPTIONS` can supply
`-Dspring.rabbitmq.host=other-broker`. The worker can then connect elsewhere while its
controller and cleanup still use the environment-owned connection.

PocketHive assumes a cooperative tester configuring a test system. This guard prevents
ordinary direct overrides; it is not an isolation boundary against deliberate configuration
bypasses. Keep ordinary ENV support. Do not add parsing/filtering of indirect startup
configuration or further hardening for this case. This is an accepted limitation, not a
review blocker or deferred repair task. Reopening it requires a new explicit user request.

### Connection split prerequisite: resource identity

Approved contract addition: resource identity includes its explicit plane. This prevents
Control and Work queues with equal names from collapsing into one removal target.
`RuntimeRabbitResourcePlanner` derives scoped targets from the ownership manifest;
`RabbitTopologyPort` carries that scope into execution and absence verification.

- Add one canonical `ResourcePlane` enum in `common/swarm-model`: CONTROL, WORK, NONE.
  NONE is required for non-messaging resources; Rabbit resources require CONTROL or WORK.
  Contract validation owns those combinations. Do not copy plane enums into adapters/services.
- Include required `plane` in RemoveResource and Rabbit cleanup/debug resource identities.
  Cleanup candidates/results/blocked entries preserve it; non-Rabbit candidates use NONE.
  Candidate identity and candidate-set hashing include plane. RemoveResource equality and
  deduplication include plane as well as type/id. An unscoped historical Rabbit target is
  rejected rather than inferred or silently assigned to one plane.
- Example remove target: `{"type":"RABBIT_QUEUE","id":"jobs","plane":"WORK"}`.
  A CONTROL queue also named jobs is a distinct target. Cleanup candidate ids:
  `rabbit:WORK:queue:jobs` and `rabbit:CONTROL:queue:jobs`.
- Pass typed plane/name through the topology port, execution and absence verification.
  Resolve plane from the manifest's distinct lists or the emitting domain owner, never
  from resource-name prefixes and never by probing both brokers until one contains the name.
- Extend `docs/spec/swarm-lifecycle.schema.json` and the REST cleanup/debug documentation
  first. Control events reuse the existing schema reference; update event/REST/MCP consumers
  and their tests in the same change. This is a breaking contract addition, not a migration
  alias or a fallback path. Public names and routing grammar remain unchanged.

After this identity path closes, introduce explicit CP/Work connection configurations and
wire resource/publisher/receiver/listener APIs to the selected connection. Export both through
the Rabbit-owned environment codec; validate selected settings before creating clients.
No Work connection inherits CP settings implicitly. Even equal broker addresses have independent
connection instances and policies. Changing connection identity must invalidate a previously
planned cleanup target, rather than applying its approval to a different broker.

Required behavior checks: identical queue names on different planes remain distinct; cleanup
of Work leaves Control untouched; a resource still present on the target plane prevents success;
a broker error is not absence; old unscoped Rabbit targets fail before effects; exported worker
settings produce the same plane selection as provisioning and transport.

The resource-plane contract addition was approved and implemented. Connection activation now
uses explicit `spring.rabbitmq` Control settings and `pockethive.rabbit.work` Work settings
(host, port, username, password, virtual-host). Work settings are required and never inherit
Control values. Both sets are exported by RabbitConnectionEnvironment to Controller/workers.
Work clients have their own lifecycle and do not receive Control listener/template customizers.
No deployment is included; existing deployments must explicitly supply the new Work settings.

## 5. Delivery and failure decisions

Preserve existing wire, ordering, error classification, acknowledgment and confirmation
contracts during migration. Explicitly identify settings previously ignored by execution
and specify their supported behavior before implementation. Never report a publish,
resource deletion or lifecycle transition as complete merely because it was attempted.
Domain state writers consume operation outcomes; Rabbit does not own swarm convergence.

### Preserved Work delivery behavior

This migration preserves the pre-extraction behavior by explicit user decision. Work uses
AUTO acknowledgement when the listener callback returns. With asynchronous execution this
is after executor submission, not after processing/publication. Existing SDK decode/dispatch
error reporting swallows those exceptions. Executor rejection retains the historical
synchronous dispatch path. Disabled invocation returns null as before. STOP retains the
existing listener lifecycle; no drain/wait-for-completion policy is introduced.

WorkItem decoding reads only the message body and ignores AMQP transport headers, including
null-valued entries. The Rabbit message projection must carry those entries without rejecting
delivery before the SDK callback; envelope headers remain owned by WorkItemJsonCodec.

RabbitPublisher submits via send. The existing publisherConfirms setting remains represented
and validated but is not activated, as in the pre-extraction implementation. No SIMPLE-confirm
activation, confirmation timeout, sendConfirmed API, completion-based settlement or admission
requeue policy is part of this refactor. Correcting these behaviors requires separate scope.

Control Plane receive integration uses `RabbitListenerBinding`: explicit listener id, resolved
queue, a message callback and a fatal-failure classifier. CP owns its contract-error classification;
Rabbit applies broker rejection together with its standard transport conversion-error policy.
Normal handler return keeps existing AUTO acknowledgement behavior. Work subscriptions retain
their separately supplied tuning/executor; CP uses its configured listener policy. Registration
happens before the registry starts. No Spring listener annotation or raw container escapes the API.

## 6. Build and composition enforcement

Rabbit libraries and Spring-AMQP implementation imports belong only to rabbit-adapter.
Other modules depend on its API or neutral capabilities. Deployable applications may
contain Rabbit transitively through this module; do not ban that necessary packaging.
They must not declare alternate direct client implementations or import module internals.

Use the existing root Maven Enforcer configuration and RepositoryImportBoundaryTest.
Their declared rules accompany source review of actual calls; they do not prove SSOT.
No new custom scanner or wiring/bean-identity acceptance test is required.

## 7. Ordered migration slices and deletion ledger

The execution plan alone owns order. Implementation records a compact table of actual
owner, consumers and deleted paths there. Former B/C phases are retired as instructions;
there is no separate later Control Plane Rabbit implementation phase.

## 8. Verification catalogue

Verify non-default values at the operation boundary, rejection before effects, unchanged
accepted state on rejection, explicit resource failure outcomes, preserved message/error
contracts and independent Work/Control settings. Move meaningful existing tests with their
owner and add only missing behavioral coverage. Test fixtures may emulate broker endpoints;
production consumers and operational tools may not bypass the owner.

## 9. Design review and handoff

Follow REVIEW_RULES and the responsibility workflow. Existing responsibility records
identify current code; update each affected record/header when its implementation moves.
Their historical B02/B05 sequencing does not override this target. A passing parser test,
module move or previous narrow acceptance cannot certify the complete Rabbit boundary.

Old design and evidence are archived, not part of the implementation reading path.

### UI Control Plane STOMP projection

RabbitResourceNames owns the STOMP exchange destination grammar and derives an immutable
RabbitStompSubscription from the accepted ControlPlaneProperties exchange. Orchestrator's
ControlPlaneInfoController exposes that projection through GET /api/control-plane/info;
it does not reconstruct names. UI subscribes verbatim and normalizes incoming destinations
once against the returned prefix before wire-log/domain handling. UI contains no physical
exchange default or STOMP destination builder. STOMP transport and existing authentication
remain in place; no SSE or generic broker-management REST surface is introduced.
