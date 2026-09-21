# RabbitMQ configuration and ownership audit — 2026-09-07

Scope: current working tree on `refactor/control-plane-critical-restart`, including
the uncommitted CP-N01 repair. This is an audit, not a RabbitMQ refactor or an
approval of the whole repository. No production code or deployment configuration
was changed during this audit.

Provenance follow-up: a [baseline-to-closeout comparison](sink-refactor-finding-provenance-2026-09-07.md)
confirmed that RAB-01 through RAB-07 existed before the sink-splitting phase. These findings
describe inherited debt, not regressions introduced by that refactor.

**Result: RabbitMQ configuration still has competing authorities.** CP-N01 fixes
Orchestrator control queue/binding declarations. It does not establish repository-wide
SSOT for queue naming, advertised bindings, effective configuration or exchanges.
Severity below follows `AGENTS.md` and `docs/REVIEW_RULES.md`: competing active
authorities are CRITICAL even when their default values currently agree.

## Findings

### RAB-01 — CRITICAL: Controller independently resolves control queue names

Canonical declaration paths:

- `common/control-plane-core/.../topology/SwarmControllerControlPlaneTopologyDescriptor.java:36`
  resolves the Controller queue; its name builder starts at line 99.
- `common/control-plane-core/.../topology/AbstractWorkerTopologyDescriptor.java:38`
  resolves worker queues.
- Shared manager/worker auto-configuration materializes those descriptors.

Competing implementation:
`swarm-controller-service/.../config/SwarmControllerProperties.java:94,147`
constructs names and heuristically decides whether the prefix already contains
the swarm ID. The Controller descriptor uses a different segment-based heuristic;
the worker descriptor always appends the swarm ID.

Active consumers of the competing result:

- `RabbitConfig.java:32` -> `SwarmSignalListener.java:86`: actual listener queue.
- `SwarmControllerControlQueueVerifier.java:27`: startup check.
- `SwarmControllerStatusPublisher.java:92`: advertised queue.
- `runtime/SwarmRuntimeInfrastructure.java:73`: worker control queue deletion.

Executable probes against current production sources confirmed:

| Input | Descriptor result | Controller-local result |
| --- | --- | --- |
| prefix `demo.control`, swarm `demo`, Controller `ctrl-1` | `demo.control.swarm-controller.ctrl-1` | `demo.control.demo.swarm-controller.ctrl-1` |
| prefix `ph.control`, swarm `control`, worker `processor/worker-1` | `ph.control.control.processor.worker-1` | `ph.control.processor.worker-1` |

The first case makes declaration and subscription disagree. The second targets
the wrong queue during removal and can leave the actual worker queue behind.
These inputs pass the examined production properties/descriptor constructors.
The probes do not claim that either swarm was deployed.

Repair boundary: one explicit base-prefix contract and descriptor-derived queue
names for declaration, subscription, observation and cleanup. Remove both
"already contains swarm" compatibility heuristics rather than copying either
into another resolver. Controller-local properties should bind only their own
settings and consume shared resolved identity/topology.

### RAB-02 — CRITICAL: status independently reconstructs control topology

`swarm-controller-service/.../SwarmControllerRoutes.java:20` constructs a second
list of Controller signal bindings for `SwarmControllerStatusPublisher.java:94`.
It is not a projection obtained from the descriptor. A production-source probe
found an existing difference with default naming: status omits
`signal.status-request.demo.ALL.ALL`, which the descriptor actually binds.
This comparison concerns signal bindings only, not the additional metric/alert
bindings legitimately present on the same queue.

Workers have the same structural problem:

- `common/control-plane-spring/.../WorkerControlPlaneProperties.java:106`
  reconstructs the queue name and config/status route catalogue.
- `common/control-plane-core/.../topology/AbstractWorkerTopologyDescriptor.java`
  independently constructs those values for actual topology.
- `common/worker-sdk/.../runtime/WorkerControlPlaneRuntime.java:134,801`
  uses the properties-derived values for status.
- The actual worker listener uses `workerControlQueueName`, which correctly
  delegates to the descriptor. This is **not** a second worker queue declaration.

There is also overlapping configuration ownership:
`ControlPlaneProperties`, `WorkerControlPlaneProperties` and
`SwarmControllerProperties` bind the same `pockethive.control-plane` root and
independently validate overlapping identity/exchange/prefix fields. In the worker
Spring probe, both shared properties beans were active simultaneously. The
common exchange resolver preferentially reads `ControlPlaneProperties`, while
worker topology uses `WorkerControlPlaneProperties`; these are not named,
read-only projections of a single resolved settings object.

Repair boundary: canonical resolved participant settings plus descriptor-derived
status projections. Shared routing helpers assemble individual routing keys;
using them in two independently maintained catalogues does not establish SSOT
for the set of subscribed routes.

### RAB-03 — CRITICAL: Control Plane independently binds Work Plane settings

`common/control-plane-spring/.../WorkerControlPlaneAutoConfiguration.java:162`
reads `pockethive.inputs.rabbit.queue`; lines 78 and 178 require and reconstruct
an exchange from `pockethive.outputs.rabbit.exchange` whenever worker topology
declaration is enabled.

The Worker SDK already owns Work IO binding and validation through
`WorkInputConfigBinder`, `WorkOutputConfigBinder`, the Rabbit properties and
`PocketHiveWorkerSdkAutoConfiguration.resolveIo` (line 469).

This is an active second configuration decision, even though it no longer
declares a second Work exchange. `ControlPlaneTopologyDeclarableFactory.java:47`
excludes worker additional queues, and the temporary `TopicExchange` object
constructed by worker auto-configuration is not returned as a declarable.

A Spring composition probe with `inputs.type=REDIS_DATASET`, `outputs.type=NONE`
and valid Control Plane settings fails with:

```text
pockethive.outputs.rabbit.exchange must be configured to declare worker queues
```

Adding an unused Rabbit Work exchange makes the same composition start. It
returns nine declarables (one control queue and eight bindings), with zero Work
exchange declarations. This probe covers shared auto-configuration, not a full
Redis worker application or broker integration.

Repair boundary: remove Work IO settings and the unused traffic-exchange
requirement from Control Plane topology composition; retain Work topology
provisioning in its actual owner. Do not supply a dummy exchange as a fix.

### RAB-04 — CRITICAL: Work queue/exchange naming is reconstructed by consumers

The active normal lifecycle mostly shares queue resolution:
`ControlPlaneContainerEnvironmentFactory.swarmTrafficQueueName` ->
`SwarmControllerProperties.Traffic.queueName` -> `SwarmWorkTopologyManager` and
`SwarmWorkerSpecFactory`.

However, effective naming has other independent implementations:

- `orchestrator-service/.../app/ContainerLifecycleManager.java:157` builds
  `ph.<swarm>` and `ph.<swarm>.hive` when creating Controller settings.
- `common/control-plane-spring/.../ControlPlaneContainerEnvironmentFactory.java:47`
  independently supplies the same prefix/exchange conventions as fallback values.
  That fallback is callable but normally bypassed by the current Orchestrator,
  which supplies both values explicitly.
- `orchestrator-service/.../app/DebugTapService.java:178` reconstructs both the
  exchange and routing key from swarm ID and logical port suffix. It neither
  consumes the resolved settings nor the Controller's runtime binding projection.

The temporary debug queue has distinct, legitimate ownership. The duplicated
behavior is **resolving the existing Work destination**, not owning that temporary
queue. Default names agree today; changing the provisioning convention/settings
can make a tap bind to the wrong destination.

Repair boundary: resolve effective Work topology once. Provisioning, environment,
ownership manifests and debug taps consume that result or its explicit projection.
Remove the unused environment fallback. Do not move Work naming into another
Control Plane class as part of CP-N01.

### RAB-05 — CRITICAL: the shared exchange has independent declaration authorities

The Java authority is
`common/control-plane-spring/.../ControlPlaneCommonAutoConfiguration.java:34`:
a durable topic exchange from the resolved `pockethive.control-plane.exchange`.
Multiple services using this one shared implementation are not separate definitions.

Other active declaration definitions exist:

- `rabbitmq/definitions.json:35` statically declares durable topic `ph.control`
  in vhost `/`. `rabbitmq/rabbitmq.conf:1` loads it; both local compose and the
  HiveForge compose template mount the file.
- `tools/mcp-orchestrator-debug/client.mjs:957,1188` independently assert the
  control exchange as a durable topic in diagnostics/status-request paths.
- `tools/mcp-orchestrator-debug/rabbit-recorder.mjs:50` repeats that assertion.
- `client.mjs:1103` additionally asserts any requested tap exchange as a durable
  topic, including an existing swarm Work exchange owned by the Controller.

With matching settings these declarations are idempotent; they do not create
multiple exchanges with the same name. The SSOT issue is independently deciding
resource name/type/durability. Broker bootstrap always creates `ph.control` even
when applications are configured for another exchange. Diagnostic commands can
create an exchange when their configuration is wrong instead of detecting its
absence. No examined architecture contract assigns these independent definitions
distinct ownership of the same exchange specification.

Repair boundary: decide one exchange-definition owner, derive deployment artifacts
from it when bootstrap is required, and make observers check existing exchanges.
Keep temporary observer queues separate. This requires a separately scoped repair
of protected deployment/shared configuration; this audit does not change it.

### RAB-06 — HIGH: UI and debug snapshots assume independent fixed names

`ui-v2/src/lib/controlPlane/subscriptions.ts:1` subscribes only to
`/exchange/ph.control/#`; `decoder.ts:142` separately hardcodes the same exchange
for destination normalization. `healthStore.ts:136` passes those subscriptions
to the active STOMP gateway. Neither obtains the configured application exchange.
Changing only the backend's supported exchange setting therefore leaves UI
observation on the old exchange. Changing only the UI subscription would still
leave its decoder's destination normalization inconsistent.

`tools/mcp-orchestrator-debug/client.mjs:880,891` similarly classifies snapshot
queues using `ph.<swarm>.` and `ph.control.<swarm>.` prefixes, rather than resolved
runtime names. A custom control prefix can produce an incomplete diagnostic
snapshot despite the queues being present.

Repair boundary: explicit shared runtime configuration for the UI subscription
and decoder; actual runtime binding/ownership projections for diagnostic queue
selection. This does not require the UI or tools to provision topology.

### RAB-07 — HIGH: Rabbit SDK settings do not control the effective listeners

`common/worker-sdk/.../config/RabbitInputProperties.java` accepts `enabled`,
`prefetch`, `concurrentConsumers`, `exclusive`, `autoStartup` and `deadLetterQueue`.
The active `RabbitWorkInputFactory.create` ignores its config argument, and
`RabbitWorkInputListenerConfigurer.java:38` supplies only listener ID, queue and
callback. Repository-wide searches found no production use of the Rabbit getters
for prefetch/concurrency/exclusive/autoStartup/deadLetterQueue.

`RabbitOutputProperties.publisherConfirms` likewise has no production consumer;
`RabbitWorkOutput.java:28` applies persistence but calls `RabbitTemplate.send`
without using that flag. Accepting `publisherConfirms=true` is not evidence that
publisher confirmation has been enabled or awaited.

These are misleading parallel configuration surfaces, not another queue owner.
Effective listener behavior comes from the shared Spring factory and its
customizers. In particular, `ControlPlaneRabbitPoisonMessageCustomizer.java:28`
sets the error handler on **every** `SimpleRabbitListenerContainerFactory`, including
the default factory used by Work IO. Its name does not limit its scope to Control
Plane listeners. The virtual-thread customizer touches the executor, a distinct
setting, so those two customizers do not themselves duplicate the same behavior.

Repair boundary: define which settings belong to the Work Rabbit adapter and wire
them to its actual container/template, or reject/remove unsupported fields. Make
the delivery/error policy scope explicit. Do not retain accepted no-op settings.

## Checked paths that are not competing production topology owners

| Area | Assessment |
| --- | --- |
| Orchestrator control queues | CP-N01 removes the service-local declarations. Both listener names use the shared descriptor. |
| Normal swarm Work declarations | `SwarmWorkTopologyManager` is the sole Java production provisioner of the durable swarm Work exchange/queues/bindings found in the scan. |
| Worker services | No additional service-local durable Work declarations found in generator, processor, moderator, postprocessor, trigger, request-builder, http-sequence, db-query or clearing-export. They use the SDK. |
| Shared worker topology factory | Suppresses worker Work declarations; its remaining Work configuration dependency is RAB-03. |
| Normal cleanup vs orphan cleanup | Controller removes workers/Work resources; Orchestrator removes its Controller queue/runtime. Governed reconciliation handles orphan resources and protects active shared resources. Multiple delete calls alone are not proof of overlapping lifecycle ownership. |
| `AmqpRabbitTopologyAdapter.exchange` | `exchangeDeclarePassive` observes an exchange; it does not provision one. |
| `SwarmQueueMetrics` | Queue depth/consumer observations; no topology provisioning. |
| Scenario Manager | Scenario topology is a logical authoring graph. No active Rabbit resource provisioner was found in the service; the shared Scenario Manager descriptor has no queue. |
| E2E captures | `ControlPlaneEvents` and `WorkQueueConsumer` declare separate server-named exclusive observation queues. `RabbitManagementClient` reads bindings. These do not recreate the production queues. |
| Product DebugTapService | Owns its temporary, exclusive, expiring debug queues legitimately; destination reconstruction remains RAB-04. |
| Work publishing | `DefaultWorkerRuntime` publishes through `WorkOutputRegistry`/`RabbitWorkOutput`. The active input factory installs a no-op result publisher in `RabbitMessageWorkerAdapter`, so its alternate RabbitTemplate publisher branch is not an active second publish path here. |
| Packaging | Compose templates/package scripts reference or copy `rabbitmq/definitions.json`; no second independent populated definitions file was found. The authoritative-file conflict is RAB-05. |
| Archive | Legacy UI was classified as archive, not counted as an active frontend authority. |

## Additional debt and limits

- `SwarmWorkTopologyManager.java:67` still removes a legacy suffix binding on
  every ensure pass. This is migration behavior inside the existing owner, not
  another active declaration owner. It needs an explicit removal/migration decision
  under the no-implicit-compatibility rule.
- Node tooling still subscribes to old `event.status-*` aliases alongside current
  metric routes, and `sendStatusRequest` hand-builds routing and publishes `{}`.
  It should be reviewed against the canonical envelope contract; it is not
  evidence of a successful supported status request. No such request was sent here.
- The CP-N01 ArchUnit guard imports only `io.pockethive.orchestrator` and excludes
  the named debug tap class. It detects new Orchestrator AMQP declarations, not
  duplicated strings/resolvers/projections elsewhere, Node scripts, UI or broker
  bootstrap definitions. Passing that test is not a repository-wide SSOT guarantee.
- Architecture examples still use `ph.work.<swarm>.*`, while the current
  Orchestrator constructs `ph.<swarm>.*`. Reconcile these in the naming-contract
  repair; examples are not another active runtime writer.
- The shared container environment factory is the one Java Rabbit connection
  environment exporter found. It exports host, port, username, password and vhost.
  TLS/address-list propagation and all Spring Rabbit connection options were not
  integration-tested; this report does not certify their support.

## Verification and evidence

Repository-wide searches covered Java AMQP builders, annotations, declarations,
bindings, passive observations and removals; Node AMQP declarations; connection
factories and environment export; queue/exchange/prefix resolvers; properties
binding and setter/getter use; UI STOMP configuration; broker bootstrap; local and
HiveForge compose; packaging scripts; E2E/test helpers; and archived sources.
Generated build output, dependencies and Git internals were excluded from source
searches. Broad discovery found 161 candidate files; that is a search inventory,
not a claim that every repository file was reviewed line by line. Active call
sites and auto-configuration activation were then traced for the findings above.

Local artifacts:

- `/tmp/rabbit-audit-files.txt`: broad candidate inventory.
- `/tmp/rabbit-audit-production-writers.txt`: Java declaration/removal call sites.
- `/tmp/rabbit-audit-declarations.txt`: additional cross-language declaration search.
- `/tmp/RabbitOwnershipProbe.java`: executable offline probes.
- `/tmp/rabbit-ownership-probe.log`: probe output.

The probe compiled the affected current Java production sources with `javac
-parameters` into `/tmp/rabbit-audit-classes`, using existing Maven dependency
classpath entries. It compared the actual resolvers/catalogues and ran two Spring
`ApplicationContextRunner` compositions with a mocked RabbitTemplate. Final probe
exit: **0**, meaning the assertions confirming the defects passed. The initial
harness needed constructor-parameter metadata, a mocked template and required
runtime metadata; those setup failures were corrected before the final run.

```text
controller declared=demo.control.swarm-controller.ctrl-1
listener=demo.control.demo.swarm-controller.ctrl-1
worker declared=ph.control.control.processor.worker-1
cleanup-target=ph.control.processor.worker-1
controller status omits actual binding=[signal.status-request.demo.ALL.ALL]
non-Rabbit Work IO startup failure=pockethive.outputs.rabbit.exchange must be configured to declare worker queues
unused exchange supplied: startup succeeds; declarables=9; Work exchange declarations=0; both config owners=1/1
```

No broker connection, deployment, cleanup or new full Maven/E2E run was performed
for this audit. Earlier CP-N01 verification remains scoped to that fix. Runtime
effects of custom prefixes/exchanges are inferred from verified code paths and
the offline probes, not presented as a new ingress test result.

## Recommended repair order

1. RAB-01 and RAB-02 together: canonical participant settings, names and binding
   projections, with cross-module checks covering declaration/listener/status/removal.
2. RAB-03 and RAB-04: remove Work configuration decisions from Control Plane;
   establish one effective Work topology result consumed by provisioning and taps.
3. RAB-05 and RAB-06: resolve exchange bootstrap ownership and distribute the
   explicit effective configuration to UI/tools.
4. RAB-07: implement or remove adapter knobs and isolate delivery-policy scope.

These findings block a claim that repository-wide Rabbit configuration is SSOT.
They do not by themselves invalidate the narrower CP-N01 queue-declaration repair.
