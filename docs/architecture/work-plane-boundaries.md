# Work Plane boundaries — implementation design

Status: target design; B01 accepted in separate review on 2026-09-08. B02 patch-policy, request-template, Rabbit connection export, Redis route, dataset-source, source-mode, output-target and write-settings transfers are implemented. Individual review status is tracked in `docs/inProgress/boundary-design/b02/README.md`; remaining B02 work is open.
Acceptance evidence: `docs/inProgress/boundary-design/b01/rv2-correction-review.md`.
Source revision: `e0d37871`, branch `refactor/control-plane-critical-restart`.
Execution order and scope are owned by `docs/inProgress/work-plane-module-boundaries.md`.
This document defines its step 2 design, not another execution plan. Existing wire,
lifecycle and authorization contracts remain effective; changes identified below are
explicit migration deltas to apply contract-first in their assigned slice.

## 1. What is being separated

The unit of migration is a complete responsibility and its consumers. Module creation
alone is not completion. A worker's business core must compile with its public Work API
and assigned use-case ports only. Service startup selects infrastructure implementations.
One writer owns worker configuration/enablement, one resolver owns effective Work
resources, and one implementation validates each configuration contract.

Work Plane covers delivery into/out of workers and the supporting capabilities needed
to run it. It does not take ownership of Orchestrator operations, Controller convergence,
authorization grants, or the whole compute lifecycle. Those remain in their domain owners.
Worker desired state and actual input state are distinct facts: a failed adapter reports
an observation; it cannot silently change desired enablement or declare an operation done.

### Source constraints checked

- `common/worker-sdk` combines API, state, input/output adapters, configuration binding,
  auth, templating and Spring composition. Its public `WorkInput.update` takes a nested
  `WorkerControlPlaneRuntime.WorkerStateSnapshot`; `WorkInputContext` exposes that runtime
  and arbitrary attributes. These are actual coupling points, not just POM dependencies.
- `DefaultWorkerRuntime` owns output publishing but accepts an unrestricted bean resolver.
  `RabbitMessageWorkerAdapter` has another publishing hook, asynchronous dispatch and
  control callbacks. The SDK factory currently disables its alternate publisher.
- `control-plane-core` directly depends on `spring-rabbit` for
  `AmqpControlPlanePublisher`; `control-plane-spring` also binds Work settings and globally
  customizes Rabbit factories. Removing the SDK's direct client dependencies is insufficient.
- `templating` imports Lettuce in `RedisSequenceGenerator`, reached through
  `SpelTemplateEvaluator`; `observability` depends on `sink-clickhouse` and Spring startup.
  Both are prerequisites for an infrastructure-free API, not optional later cleanup.
- Some `io.pockethive.worker.sdk.auth` types already live in `auth-contracts`. Artifact
  ownership must be determined from source/POM paths, not inferred from package names.
- Work topology is provisioned by `SwarmWorkTopologyManager`, but its settings/name
  decisions are reconstructed in Controller properties, worker environment construction,
  Orchestrator `ContainerLifecycleManager` and `DebugTapService`, and Node diagnostics.

Design/review evidence is in `docs/inProgress/boundary-design/`. Former generated
inventory/graph snapshots are archived under `docs/Archive/work-plane-boundary-scans/`.
They are historical source-reference candidates, not current acceptance evidence. The ownership decisions below
resolve the traced production paths; each migration repeats discovery at its own revision.

## 2. Artifacts and namespaces

Current implementation ownership for the B01 adoption scope is recorded in
[runtime responsibility records](runtime-responsibilities.md). The tables below
describe the target design; current records explicitly retain pending migration work.

All new shared artifacts use `io.pockethive:${artifact}:${revision}` and live under
`common/${artifact}`. Existing artifact names are retained where they already express
the responsibility. Every concrete Java type gets its own file. Names below are target
names; moves remove the original definition, with no alias or compatibility implementation.
New infrastructure adapter artifacts end in `-adapter` or `-adapters`; the graph gate
derives their forbidden-core classification from that convention, including service adapters.
Retained infrastructure artifacts with other names remain explicitly classified by the gate.

| Artifact | Namespace / contents | Allowed project dependencies |
|---|---|---|
| `work-api` | `io.pockethive.work.api`: WorkItem, wire envelopes/codec/schema, worker function/context/info, history, diagnostic/capture and status contribution API | `observability-core`, `swarm-model`, `auth-contracts` |
| `work-config` | `io.pockethive.work.config`: selected IO types, immutable settings, parsers, validation, patch/mutability policy | `swarm-model`, `rabbit-config` |
| `rabbit-config` | `io.pockethive.rabbit.config`: shared connection settings/parser and environment encoding; no Spring/client types or plane topology | None |
| `work-runtime-spi` | `io.pockethive.work.spi`: dispatch, input lifecycle, output, state views, commands and effects; no resource administration | `work-api`, `work-config` |
| `work-resource-api` | `io.pockethive.work.resource`: immutable resource plans/observations and scoped provisioning/observation/removal/tap ports | `work-config`, `swarm-model` |
| `work-runtime` | `io.pockethive.work.runtime`: invocation pipeline, worker state coordinator/store, IO coordination and status contributions | `work-api`, `work-config`, `work-runtime-spi`, `templating-api` |
| `work-topology-core` | `io.pockethive.work.topology`: `WorkTopologyResolver`, resource selection and read-only projection | `work-resource-api`, `work-config`, `swarm-model` |
| `work-rabbit-adapter` | `io.pockethive.work.rabbit`: Work codec boundary, input, output, resource management/observation and tap implementations | `work-runtime-spi`, `work-resource-api`, `work-config` |
| `work-local-adapters` | `io.pockethive.work.local.csv` and `.scheduler`: file dataset and timed source implementations, separate types/factories | `work-runtime-spi`, `work-config` |
| `redis-adapter` | `io.pockethive.redis.dataset`, `.output`, `.sequence`, `.auth`, `.capture`; private shared connection creation | `work-runtime-spi`, `work-config`, `templating-api`, `auth-contracts` |
| `worker-control-adapter` | `io.pockethive.worker.control`: decoded CP command bridge, status/error publisher and scheduler | `work-runtime-spi`, `control-plane-core`, `observability-core` |
| `worker-sdk` (repurposed) | `io.pockethive.worker.bootstrap`: explicit worker Spring composition, annotation discovery, binding of environment documents | `work-runtime`, `work-rabbit-adapter`, `work-local-adapters`, `redis-adapter`, `worker-control-adapter`, `control-plane-spring`, `observability`, `templating`, `worker-auth-core`, `worker-auth-adapter` |
| `observability-core` | Existing context/hop utilities, status builders and identity utility; no startup/exporter classes | `topology-core` |
| `observability` (retained) | Spring configuration, metrics properties/lifecycle/exporter; delegates context ownership to `observability-core` | `observability-core`, existing `sink-clickhouse` |
| `control-plane-core` (retained) | Existing codec, identity, routing, descriptors and CP policy; remove AMQP publisher and dependency | `topology-core`, `swarm-model`, `observability-core` |
| `control-plane-spring` (retained) | CP Rabbit adapter and CP composition; receives `AmqpControlPlanePublisher`; no Work config parsing | `control-plane-core`, `observability`, `sink-clickhouse`, `rabbit-config` |
| `templating-api` | `io.pockethive.templating.api`: `TemplateRenderer`, syntax validation and `SequenceAccess` interfaces and immutable values | None |
| `templating` (retained) | Existing single Pebble/SpEL implementation, injected `SequenceAccess`; no Redis client or implicit static configuration | `templating-api` |
| `auth-contracts` (extended by moves) | Existing auth contracts plus worker auth values and narrow `TokenStore`, profile/token endpoint and material-resolution ports | None |
| `worker-auth-core` | `io.pockethive.worker.auth`: token refresh/use-case policy extracted from `AuthRuntime` | `auth-contracts`, `templating-api` |
| `worker-auth-adapter` | `io.pockethive.worker.auth.infra`: explicit profile file loader and HTTP token endpoint client | `auth-contracts` |
| `request-templates` (retained) | Single request-template parser, definitions and validation over decoded documents | `auth-contracts`, `swarm-model` |
| `request-template-files` | `io.pockethive.requesttemplates.files`: file enumeration/loading; calls canonical parser | `request-templates` |
| `scenario-validation-contracts` (retained) | Bundle findings/reference validation contracts; Work mutability definitions move to `work-config` | `work-config` |
| `work-test-fixtures` | `io.pockethive.work.testing`: canonical producer and Spring composition fixtures, test scope only; never on production classpaths | `work-api`, `control-plane-core`, `control-plane-spring` |

Do not create an artifact per interface. The splits above remove a concrete client leak,
separate application from implementation, or keep resource administration off workers'
compile classpaths. CSV and scheduler share one local-adapter artifact because neither
requires a third-party transport client; they still have separate settings and owners.
Redis capabilities share client infrastructure, not dataset/token/sequence semantics.
No public `RedisClientProvider`, `execute(command)` or client callback is permitted.
Shared Rabbit connection configuration is in `rabbit-config`, not in Work configuration
or a Control service: CP and Work adapters can use the same canonical connection contract
without either plane importing the other's configuration behavior. Connection and plane
delivery policies remain distinct responsibilities. The B02 transfer moved the existing
five-field RabbitProperties environment export from ControlPlaneContainerEnvironmentFactory
to that shared encoder; both launch paths now receive immutable RabbitConnectionSettings.
See [RESP-RABBIT-CONNECTION](runtime-responsibilities.md#resp-rabbit-connection) for its
implemented scope and remaining configuration limits. Other B02 settings work stays open.

Jackson/schema validation, SLF4J and Micrometer API types may remain in their current
public roles. Pebble/SpEL remain in the existing template implementation. No new framework
is needed. `observability-core` is required because the current convenience artifact
otherwise brings ClickHouse/Spring to every Work API consumer.

### Application packaging

Retain each existing `*-service` artifact and Docker build output as its boot/composition
artifact. Add sibling `<service>-core/` projects to the root reactor (for example
`generator-core`, artifact `generator-core`, namespace `io.pockethive.generator`). Move
business types and their tests there; leave only application entrypoint, wiring and
resources in the original service. No source-directory overlays or core files compiled
again into service JARs. This avoids changing existing JAR paths during the boundary move.

Apply to generator, moderator, processor, postprocessor, trigger, request-builder,
http-sequence, db-query and clearing-export. Controller/Orchestrator first get
`swarm-controller-work-core` and `orchestrator-work-core` for affected Work orchestration;
remaining CP application separation is stage 5. A service-specific external operation
(HTTP/TCP/JDBC/file/ClickHouse) goes into `<service>-adapters`, implements a narrow port
defined by that service core, and is selected in the service boot artifact. Exceptions
such as JDBC `SQLException` must not leak through those ports. The boot artifact depends
on core and adapters; the core never depends on boot, `worker-sdk`, another service's
implementation, or any infrastructure artifact.

`scenario-manager-service`, tools and E2E consume `work-api`/`work-config` as needed,
not `worker-sdk`. `request-templates` stops depending on the SDK after auth contract
moves. Production test helpers move from SDK main sources to a test-only fixture artifact.
Move `PocketHiveWorker` and `WorkerCapability` to `work-api`; retain worker function/config
metadata but remove annotation-based input/output config overrides. Adapter settings types
come solely from explicit IO selection and the canonical parser. Binding annotations and
properties beans stay in boot; worker core constructors/context receive resolved values.

The following table defines the complete application-core project edges for the Work
migration. An adapter project depends on its core's port contracts and only its selected
shared adapter/API dependencies; the existing service boot artifact depends on that core,
its adapters and `worker-sdk`. No adapter project is needed for a core with no direct
external operation. Service-specific ports stay with their sole core consumer; do not
create a shared-contract module for each worker.

| Core artifact / root directory | Allowed project dependencies | Service adapter extraction |
|---|---|---|
| `generator-core` | `work-api`, `templating-api` | None; template implementation supplied in boot |
| `moderator-core` | `work-api` | None |
| `processor-core` | `work-api`, `templating-api`, `auth-contracts`, `swarm-model` | `processor-adapters`: HTTP/TCP/ISO client execution behind `ProtocolCallPort`; keep result rules/response construction in core |
| `postprocessor-core` | `work-api`, `swarm-model` | `postprocessor-adapters`: existing ClickHouse writer behind `TransactionSink`; core never imports ClickHouseSinkProperties |
| `trigger-core` | `work-api` | `trigger-adapters`: HTTP/process actions behind `TriggerActionPort`; scheduling policy stays in core |
| `request-builder-core` | `work-api`, `templating-api`, `request-templates`, `auth-contracts` | No extra wrapper artifact; boot supplies existing shared template/profile file adapters |
| `http-sequence-core` | `work-api`, `templating-api`, `request-templates`, `auth-contracts` | `http-sequence-adapters`: HTTP execution/template source access; Redis capture supplied through DebugCaptureStore |
| `db-query-core` | `work-api`, `swarm-model` | `db-query-adapters`: JdbcDbStatementExecutor and file template loading; DbStatementExecutor port translates SQLException to typed execution failure |
| `clearing-export-core` | `work-api`, `templating-api` | `clearing-export-adapters`: file/stream output and source loading behind `ClearingOutputPort`/`ClearingSchemaSource`; journal publication via typed effects |
| `orchestrator-work-core` | `work-resource-api`, `work-config`, `swarm-model` | Existing Orchestrator boot supplies topology resolver, Rabbit tap/resource adapter and governed cleanup integration |
| `swarm-controller-work-core` | `work-resource-api`, `work-config`, `swarm-model`, `manager-sdk` | Existing Controller boot supplies topology resolver, resource adapter and worker settings exporter |

| Adapter artifact / root directory | Allowed project dependencies | Composition owner |
|---|---|---|
| `processor-adapters` | `processor-core` | `processor-service` |
| `postprocessor-adapters` | `postprocessor-core`, `sink-clickhouse` | `postprocessor-service` |
| `trigger-adapters` | `trigger-core` | `trigger-service` |
| `http-sequence-adapters` | `http-sequence-core` | `http-sequence-service` |
| `db-query-adapters` | `db-query-core` | `db-query-service` |
| `clearing-export-adapters` | `clearing-export-core` | `clearing-export-service` |

These adapter edges include their core's transitive API contracts. Infrastructure clients
are explicit external POM dependencies only of the corresponding adapter. Boot may also
depend directly on `request-template-files` for shared template loading; neither a worker
core nor a service adapter needs a duplicate loader/parser implementation.

Public worker business APIs do not import runtime command/admin packages. Boot injects
`AuthMaterials` (implemented by worker-auth-core) where worker logic needs credentials,
not the AuthRuntime implementation. Auth transport/storage ports expose immutable auth
values only and cannot depend back on WorkItem, avoiding an auth-contracts/work-api cycle.

## 3. Ports, owners and state transitions

Port names are exact proposed public types; operations below specify the required
signature semantics. Each request/result/enum is a separate immutable Java type.
Resource handles are owner-issued values scoped to swarm/run and resource identity,
never arbitrary names reconstructed by a consumer.

| Port / type | Owner and permitted callers | Contract |
|---|---|---|
| `WorkDispatcher.dispatch(WorkItem)` | `DefaultWorkerRuntime`; selected input only | Returns `WorkDispatchResult` after invocation and the configured output action finish; no adapter-side second publish |
| `WorkOutput.publish(WorkItem)` | Selected output adapter; runtime only | Bound to its resolved destination during composition; returns `WorkPublishReceipt` distinguishing `DISPATCHED`, `CONFIRMED`, `NO_OUTPUT`; failures are explicit |
| `WorkInput.start/stop/update` | Selected input adapter; `WorkerIoCoordinator` only | Takes typed settings and immutable desired-state revision; returns/observes actual lifecycle state; no CP runtime object |
| `WorkerStateView.snapshot/subscribe` | `WorkerStateCoordinator`; worker context, IO coordinator and CP status projection | Immutable config/enablement revision; no mutable state/maps or setters exposed |
| `WorkerCommands.apply` | `WorkerStateCoordinator`; decoded `WorkerControlCommandBridge` only | Validate complete candidate via canonical config owner, serialize acceptance and publish one new state revision; rejected patch leaves state unchanged |
| `WorkerEffects.report` | CP effect adapter; runtime/IO observations | Typed error/status contribution, not raw envelope/routing/publisher access or operation terminalization |
| `WorkerDiagnostics.emit(WorkerDiagnostic)` | Work API facade implemented by runtime; assigned worker functions | Typed business diagnostic/journal contribution forwarded through WorkerEffects; no CP envelope or publisher access |
| `WorkProvisioner.ensure(ResolvedWorkTopology)` | Rabbit resource adapter; Controller Work lifecycle only | Applies only supplied owner-resolved resources; observed result required before readiness |
| `WorkTopologyResolution.resolve` | `WorkTopologyResolver`; Controller/Orchestrator Work use cases | Pure canonical resolution from explicit naming settings and logical topology; no provisioning side effects |
| `WorkResourceObserver.observe(ResourceSelection)` | Rabbit resource adapter; Controller, reconciliation and diagnostics | `PRESENT`, `ABSENT`, `UNKNOWN` with observation identity/time; connection/authorization failure never means absent |
| `WorkResourceObserver.observeQueue(WorkQueueRef)` | Same Rabbit observation owner; Controller queue statistics and diagnostics | Returns `WorkQueueObservation`: scoped identity/time, existence and explicit statistics availability; measured depth, consumers and oldest-age evidence as specified below |
| `WorkResourceRemover.remove(ResourceSelection)` | Rabbit resource adapter; authorized Controller/removal reconciler only | Returns per-resource observed effects/errors; does not calculate public operation success |
| `WorkTap.open(WorkDestinationRef)/close(TapId)` | Rabbit tap adapter; Orchestrator tap use case | Only ephemeral tap lifecycle; resolves destination from canonical topology projection, never ensures the target exchange |
| `SequenceAccess.next/reset` | Redis sequence adapter; template implementation only | Existing sequence formats/start/max/reset behavior; explicit connection/profile, no global default instance |
| `TokenStore` | Existing token-store contract moved to `auth-contracts`; auth use case only | Preserve get/claim/store/release/due-refresh atomicity, fingerprint and lease ownership |
| `AuthProfiles.load`, `TokenEndpoint.exchange` | File/HTTP auth adapters; auth use case only | Explicit root/endpoint/settings and typed failures; never return clients |
| `AuthMaterials.resolve` | Worker auth policy core; assigned worker business functions | Typed resolved auth material/failure; no access to token-store mutation or HTTP clients |
| `DebugCaptureStore.append/read` | Redis capture adapter; HTTP sequence diagnostics only | Scoped capture values and explicit retention; no general Redis command access |
| `RedisUpload.publish(ResolvedRedisUpload, WorkItem)` | Redis sink implementation; runtime uploader interceptor only | Shares the canonical output target resolver/writer with Redis output; no client/config parsing in the interceptor |
| `ScheduledInvocationPolicy.update/plan` | Public Work API policy; scheduler adapter calls an explicitly supplied implementation | `update` observes every immutable config/enablement revision in delivery order without consuming quota; `plan` takes only monotonic tick time and returns quota from the latest observed state, never accesses CP or clients |
| `TemplateDocuments.load` | File adapter; request-builder/HTTP sequence composition | Loads bytes/document sources under the configured root and delegates parsing once |

### Queue observation and manager projection

`WorkQueueObservation` and its immutable result types live in `work-resource-api`'s
`.observe` package. The queue reference identifies the canonical swarm/run/resource;
the observation records its ID and read time. A queue observation carries the same
existence evidence as `observe`, enriched by the same adapter read, not a second lookup
or an independent interpretation of absence. Keep these cases distinct:

| Observation | Statistics contract | Manager-facing result |
|---|---|---|
| PRESENT, statistics available | `MeasuredQueueStatistics`: nonnegative message depth and consumer count; explicit oldest-age result | Map measured counts to existing `QueueStats`; zero counts are valid measurements |
| PRESENT, statistics unavailable | `UnavailableQueueStatistics` with typed reason; no fabricated counts | Throw typed `QueueObservationException` with reason and observation identity |
| ABSENT | Verified absence; statistics unavailable with `QUEUE_ABSENT` reason | Throw `QueueObservationException`; never map to `QueueStats.empty()` |
| UNKNOWN | Read/authorization/connection failure; statistics unavailable with its typed reason | Throw `QueueObservationException`; never interpret as an empty queue |

Oldest-age evidence is a separate immutable result: `MeasuredQueueAge(seconds >= 0)`
or `UnavailableQueueAge(reason)`, including unsupported measurement. Depth/consumer
availability does not imply age availability. The existing manager `QueueStats.oldestAgeSec`
receives a value only for measured age; its empty optional is an explicitly lossy read-only
projection of unavailable age. Canonical observations retain the reason for diagnostics.
This preserves the existing optional measurement contract without introducing optional
core lifecycle/configuration state.

In B04 extract the Rabbit read/coercion from `SwarmQueueStatsPortAdapter` into
`RabbitWorkResourceObserver`; the Controller adapter becomes the sole mapping from
`WorkQueueObservation` to the existing `QueueStatsPort`/`QueueStats` interface. Resolve
its supplied queue name against the identified topology projection; reject unknown or
wrong-scope references rather than constructing names. No `AmqpAdmin` remains in that
projection. `QueueObservationException` belongs to `manager-sdk` and exposes manager
failure values only, avoiding a dependency from manager-sdk back to Work ports.

Update every QueueStatsPort consumer in B04 for this explicit failure contract. BufferGuard
obtains all upstream/downstream observations needed for a tick before changing its sample,
drain or rate state; unavailable required counts skip that decision and emit diagnostics,
without assuming zero depth or issuing a rate command. `SwarmQueueStatsCollector` uses
canonical queue references and records unavailable observations separately from measured
snapshots. `SwarmQueueMetrics` moves to `swarm-controller-work-core` as a read-only
projection using only Micrometer API, with no broker access; remove gauges for unavailable
counts so a previous measurement is not published as current. Preserve existing measured-age
gauge behavior.
Status consumers retain availability/reason evidence instead of publishing zero-filled
QueueStats. This failure delta, its status mapping and all consumers are contract-first
B04 work. V07 must cover measured empty/nonempty queues, unavailable age/counts, absence,
read failure and wrong scope through observer, projection, guard and metrics together.

### Worker state and execution

Keep `WorkerStateStore` and `WorkerState` private to `work-runtime`; extract the state
transition/config logic from `WorkerControlPlaneRuntime` into `WorkerStateCoordinator`.
The bridge decodes through the existing CP codec/consumer, keeps its CP correlation and
deduplication owner, and calls `WorkerCommands`. It maps accepted effects to existing CP
result/status contracts; Orchestrator still owns public terminal outcomes.
Adapters can own local connection/cursor/in-flight state, but cannot own a second worker
configuration truth or revalidate settings independently. Their actual status is an
observation; a cached snapshot is explicitly read-only and revision-tagged.

`WorkerIoCoordinator` subscribes to the accepted state, drives exactly one input and
output selection and feeds actual state into the CP projection. Disable stops new intake
and drains accepted work according to the declared deadline. Stopping Work leaves CP
command/status handling running. The existing operation convergence owner decides when
the whole swarm is stopped from fresh observations, not a returned stop invocation.

Remove `WorkInputContext.attributes`, CP runtime accessors and nested snapshot contracts.
Remove generic bean/service resolvers from `DefaultWorkerRuntime` and
`DefaultWorkerContextFactory`: composition supplies a closed map of typed worker
invocations and configuration readers. No worker/adapter receives Spring `BeanFactory`,
`ApplicationContext`, arbitrary `Class -> Object` lookup, or infrastructure callbacks.
Interceptor ordering is an explicit ordered list assembled at startup; remove the core's
Spring `Ordered` dependency. Factory selection must match exactly one configured type;
replace first-match/priority selection with an explicit ambiguity error.

Within `io.pockethive.work.spi`, use `.execution`, `.io`, `.state`, `.command`, `.effect`
and `.upload` packages. `.state` is read-only; only worker-control-adapter and bootstrap
initialization may reference `.command`. Infrastructure IO adapters may reference their
execution/IO/view/effect packages, never command mutation. Resource API similarly separates
`.model`, `.observe`, `.provision`, `.remove` and `.tap`; diagnostics may reference model,
observe and tap only. These package edges are mandatory build checks in addition to the
artifact classpath bans. Immutable snapshots include nested collections; worker business
contexts expose their own resolved domain config, not adapter credentials or mutable IO
settings. Status/diagnostic projections use canonical redaction before publication.

The existing `TriggerWorkInputFactory` is a concrete exception that must be migrated:
it competes with the generic scheduler factory and selects by hardcoded role `trigger`.
Delete that factory. Keep one scheduler input implementation; trigger boot supplies
`TriggerSchedulePolicy` extracted from `TriggerSchedulerState` via the policy port.
Generic timed workers use the explicit rate policy. Preserve trigger interval/single-shot
and enablement behavior with focused before/after tests; changing its role name must not
change factory selection. Do this when first-match selection is removed in B01, before
its V03 gate. Keep the policy implementation in trigger-service until packaging in B07;
introduce its policy contract in work-api during B01. The existing scheduler consumes a
read-only policy input projected from the current sole worker state owner until B03 moves
that owner. Remove SchedulerState/States as competing policy contracts in B01; retain one
generic rate-policy implementation beside the existing scheduler until B07 moves it.

## 4. Configuration and topology SSOT

`WorkConfigurationParser` in `work-config` owns input/output selection, settings parsing,
normalization and complete candidate validation. `WorkPatchPolicy` owns mutable-field
classification and patch validation. The first B02 transfer consolidated `LiveIoConfigMutability` and
`LiveIoConfigUpdateGuard` there and deleted both previous definitions. Complete candidate
validation remains a separate required WorkConfigurationParser responsibility.
`WorkInputConfigBinder`/`WorkOutputConfigBinder` become one bootstrap decoder of raw
environment properties; they delegate decisions to the parser. Remove repeated validation
from Redis properties, `RedisWorkOutput.applyRawConfig`, dataset source parsing,
`RedisUploaderInterceptor`, and `ScenarioBundleValidator`.

Use separate typed `RabbitInputSettings`, `RabbitOutputSettings`, `RedisDatasetSettings`,
`RedisOutputSettings`, `CsvDatasetSettings`, `SchedulerSettings` and `NoOutputSettings`.
Shared `RedisConnectionSettings` has one parser; token, sequence, dataset and capture
scopes remain distinct. Every selected adapter/settings block is explicit. Missing,
unknown, conflicting or unsupported settings fail; configuration aliases/fallbacks are
removed and all packaged samples/producers are updated in the same slice.

The canonical record fields move from the current properties without adding parallel
DTOs. New `WorkExecutionSettings` requires `maxInFlight`, `dispatchTimeoutMs` and
`drainTimeoutMs`; `RabbitOutputSettings.confirmTimeoutMs` is required when confirmations
are selected. Their startup paths are `pockethive.worker.execution.*` and
`pockethive.outputs.rabbit.confirmTimeoutMs`; bootstrap alone maps environment spelling.
Timeouts are positive bounded durations; admission capacity is positive; existing rate,
maxMessages, cursor and route constraints keep their canonical limits. Remove all
input-local `enabled` fields (Rabbit, scheduler, Redis, CSV) as duplicate state controls,
not only the Rabbit no-op field. Seed required worker enablement through its one owner.
This is a declared B02 contract delta; update all configuration producers together.

Preserve the current live-mutability contract in ARCHITECTURE: adapter/endpoint/source-mode
changes require rematerialization; rates and scheduler controls use its allowlist; Redis
single-source listName alone can change while disabled. UI intent/observation STOPPED
checks remain mandatory. Accepted state is published only after full candidate validation;
adapter application failure is reported as non-convergence, never accepted success.

Scenario authoring validates the same contract over a symbolic document. The parser owns
both `AUTHORING` and `RESOLVED` modes: unresolved template expressions are identified and
reported as deferred constraints in authoring, then fully validated after rendering at
runtime. This is not a second permissive validator. Bundle file/reference existence stays
in Scenario Manager. `RequestTemplateParser` extracted from `TemplateLoader` owns shape,
required fields and protocol/auth values for both authoring and loading (SSOT-04).
UI consumes generated capability/mutability descriptions and server validation results;
TypeScript never reimplements Redis route/source or IO patch semantics.

`WorkTopologyResolver.resolve(swarmId, logicalTopology, WorkNamingSettings)` is the sole
name/resource-plan owner. Keep the current explicit base-prefix + logical-port convention
as the migration baseline; do not infer that a prefix already contains a swarm ID.
Orchestrator resolves required naming settings once at launch and stores them with the
startup plan. Controller resolves its immutable Work topology from that input. Environment
export, ownership manifests, status bindings, observers, taps and cleanup consume the same
resolved plan or its identified read-only projection. No class concatenates `ph.` names
outside the resolver; no Work name resolver remains in Control properties/environment code.

Use the existing startup artifact/configuration carrier where it already holds the data;
any required new field is a contract-first change, not a second side-channel. Queue routing
keys remain equal to canonical resolved Work queue names as currently provisioned.
Remove the old suffix-binding cleanup compatibility behavior explicitly in the topology
slice; migration of already-running topology is not silently performed on ensure.

Resource deletion authority remains scoped: Controller owns its Work resources; governed
reconciliation handles eligible orphans under the existing authorization path. Both use
the same deletion/observation adapter and canonical absence evaluation. Orchestrator
operation and reconciliation-result builders must call that evaluation; invocation counts
are never evidence of successful removal. CP resources are separate selections/owners.

## 5. Delivery and failure decisions

Preserve ARCHITECTURE §2.4: worker execution/config/payload failure is reported out of band
and the consumed item is dropped; no new automatic retry, deduplication database or
exactly-once promise is introduced. Work message identity is not a CP idempotency key.
Broker redelivery after a connection loss can repeat work; side-effecting workers retain
their explicitly documented idempotency responsibility. This design does not make Redis
dataset pops and external calls transactional.

The invocation/output chain has one completion result. For Rabbit input, retain the
delivery until dispatch/output completes, including `maxInFlight > 1`; asynchronous
submission is not completion. The adapter owns acknowledgement on its channel and
must serialize channel access or use container-supported async settlement. It must not
ack from arbitrary worker threads. Runtime failure uses the explicit DROP policy and
typed diagnostic evidence. An unknown broker settlement is reported as unknown; it is
not converted into successful processing. No synchronous fallback on executor rejection.

Rabbit settings decisions for RAB-07:

- `prefetch`, `concurrentConsumers`, `exclusive` and output `persistent` are supported,
  validated and applied to the Work-owned container/template. Explicit execution capacity
  and broker prefetch are different settings, with bounded admission and no hidden backlog.
- Remove input `enabled` and `autoStartup` as duplicate/no-op enablement controls. Worker
  desired state is the single lifecycle authority. Reject these fields and update their
  producers rather than silently ignoring them.
- Remove/reject the accepted no-op `deadLetterQueue` field for this DROP contract. A future
  dead-letter delivery policy requires its own topology and failure contract.
- Honor `publisherConfirms`: false yields `DISPATCHED` (local send completion only); true
  awaits the correlated broker confirmation within a required bounded timeout, yielding
  `CONFIRMED`. Nack/timeout/return is explicit failure/unknown as appropriate, not success
  and not an automatic retry. Neither receipt claims downstream consumption.

These are intentional deltas from current no-op/early-return behavior; record them in
the slice's before/after report and update canonical SDK/config docs and producers first.
Failure to emit an alert cannot turn failed work into success or replace its primary cause.
Malformed bytes remain diagnostic bytes/metadata; do not synthesize a valid WorkItem as
a fallback. Preserve correlation and step history through the canonical codec.

Scheduler and dataset inputs invoke the same completion path. Scheduler preserves rate,
maxMessages and reset behavior; dataset adapters own cursor/order/loop/exhaustion state
and report it through typed observations. No retry/replay guarantees are inferred for
destructive Redis reads. Stop prevents new intake, waits for admitted work up to its
explicit deadline and reports a timeout/non-converged state if it cannot drain.

Execution deadlines do not prove that an external side effect was cancelled. If a timed-out
invocation still runs, retain its in-flight observation, report unknown effects and prevent
drain success or a replacement invocation that assumes cancellation. Timing uses a
monotonic source; wall-clock timestamps remain evidence labels. Do not reuse the CP-N09
remote timestamp comparison as a Work completion/timeout decision.

## 6. Build and composition enforcement

Use standard Maven dependency enforcement and focused tests of concrete behavior.
The root POM owns the explicit transitive infrastructure bans for migrated core/API
artifacts. Existing focused architecture tests, including CP-N01, remain applicable.
They validate their stated dependency/ownership rules, not all possible IO or duplication.

Acceptance requires the mandatory boundary review in `docs/REVIEW_RULES.md`: trace
actual owners, constructors/overloads, effects, configuration and composition through
the changed consumers. Inspect state update ordering and verified postconditions.
No source preparser, heuristic scanner or generic blacklist may replace that review.
The removed Python/JSON/catch-all ArchUnit mechanism is not an acceptance requirement.
The sole retained custom source-scanning test has its human-approved scope and limits
in [the review rules](../REVIEW_RULES.md#sole-source-scanning-test-exception).

Worker cores depend on their Work API and assigned use-case ports. Privileged resource
commands and adapter construction remain in their named owning modules. Bootstrap wires
implementations but does not become another domain/configuration owner. The review must
verify these constraints in actual code, including Java and non-Java diagnostics.
Unmigrated responsibilities retain the exact scope and owner recorded in the deletion
ledger; a migration does not create exemptions for its old implementation.

For joint composition, enforce import/dependency boundaries and review the actual
bootstrap, consumers and resource owners against the architecture. The
[boundary-verification policy](../REVIEW_RULES.md#boundary-verification-and-test-value)
governs acceptance: no tests of module/bean selection or private factory identity.
Retain behavioral cases for Redis/CSV/scheduler + NONE startup without Rabbit Work
settings, explicit rejection of invalid configuration and concrete policy regressions.
`ControlPlaneRabbitPoisonMessageCustomizer` becomes CP-factory-specific; no global mutation.
Each output instance receives one immutable resolved destination. Shared physical
connections are allowed only if they cannot share mutable plane policy.

B01 establishes V03 through boundary review and relevant behavioral evidence before any later slice depends on it:
scope CP poison handling to CP factories and Work executor customization to Work factories;
remove Work queue/exchange binding and declaration from WorkerControlPlaneAutoConfiguration
and its CP declarable path; and make selected input/output factory matching exact, including
the trigger policy migration above. Existing Controller Work topology remains its sole
provisioning owner until B04; workers only attach to explicitly provisioned Work resources.
CP declaration must neither require Work settings nor be disabled to make a test pass.
Use current IO configuration/state contracts for B01 wiring, then migrate their owners in
B02/B03 without duplicate definitions. B05 moves Rabbit implementations and changes their
delivery semantics; it must preserve the isolation established in B01.

## 7. Ordered migration slices and deletion ledger

Each slice applies the execution plan's before/after protocol; acceptance uses a separate
review task under that plan's goal/review separation. IDs
below are stable references for the coverage matrix and inventory. B01 is accepted;
B02–B07 and C01–C03 remain pending.
Migrate all source/metadata/test consumers of each moved owner in the same slice. Adapters
not yet migrated may remain at their old location as the sole owner of that *different*
responsibility; they must consume migrated contracts, never retain old definitions.

| Slice | Concrete scope / final owners | Delete or replace in the same slice | Required checks |
|---|---|---|---|
| B01 — shared prerequisites and composition isolation | `work-api`, `observability-core`, auth value/port moves, `templating-api`; remove AMQP from CP core; full V03 isolation using existing IO contracts | SDK API/auth/observability originals; CP-core publisher moves to CP Spring; template API/sequence injection; CP Work binding/declaration and global customizer behavior; first-match input/output selection, TriggerWorkInputFactory and old scheduler policy contracts | V01, V02, full V03 including non-Rabbit/NONE, distinct policies and missing/duplicate factory rejection; trigger policy parity; all moved-type consumers compile; CP-N01 stays green |
| B02 — settings and authoring | `work-config`, `RequestTemplateParser`, file loader adapter, explicit connection/settings binding | SDK binder/auto-config decisions, property/runtime Redis validators, `LiveIoConfigMutability`/guard originals, ScenarioBundleValidator IO/template semantic copies | V04, V05; symbolic/resolved parity and existing scenario/config suites |
| B03 — execution/state boundary | `work-runtime`, `work-runtime-spi`, `WorkerStateCoordinator`, `WorkerIoCoordinator`, `worker-control-adapter` | WorkerControlPlaneRuntime mixed responsibilities; mutable state access, nested snapshots, generic input attributes and bean resolver hooks; old adapters and B01 scheduling policies consume new state/dispatch ports | V01, V03 regression from B01, V06; all four existing input implementations and outputs compile/use sole state owner |
| B04 — resource ownership | `work-topology-core`, Work resource/statistics ports and Rabbit observer; Controller/Orchestrator Work use cases and QueueStatsPort projection | Work naming in lifecycle/properties/environment/spec/tap/collector code; SwarmWorkTopologyManager effects; Rabbit reads/coercion in SwarmQueueStatsPortAdapter; zero-filled missing observations; Node Work provisioning copies | V07, V08; statistics/availability, BufferGuard and gauge behavior, non-default prefixes, taps/removal; RAB-04 and Work part of SSOT-01 |
| B05 — Rabbit delivery | Rabbit input/output implementation in `work-rabbit-adapter`; move Work factory wiring with B01 isolation preserved | SDK Rabbit input/output/transport originals, alternate publisher hook, swallowed/early asynchronous completion and unsupported property fields | V03 regression from B01, V06, V09; RAB-07 concurrent dispatch and confirm failure cases; preserve B01 RAB-03 isolation |
| B06 — Redis capabilities | Dataset/output/sequence/token/capture implementations in `redis-adapter`; worker auth policy/HTTP/file adapters separated | SDK RedisDataSetWorkInput/RedisWorkOutput/RedisPushSupport client code, static RedisSequenceGenerator/global configuration, AuthRuntime client construction, HttpSequenceRunner nested Redis store | V04, V10; source/route/template/auth scope parity and explicit no-retry failure behavior |
| B07 — local inputs and worker packaging | `work-local-adapters`; all nine worker cores/service adapters; SDK composition-only | SDK scheduler/CSV originals, business source copies in boot JARs, residual service client imports and direct template file/HTTP/JDBC/ClickHouse calls in cores | V01–V03, V06, V11, V12; complete 4-input/3-output coverage and ingress representative matrix |
| C01 — CP settings/topology/projections | Existing CP core canonical participant settings/descriptors; CP Spring adapter | Controller/worker duplicate queue/binding catalogues and properties, Node/UI hardcoded exchange copies, bootstrap independent definition | V02, V07, V12; RAB-01/02/05/06; protected deployment/schema changes require their existing scoped authorization |
| C02 — operations/cleanup/files | Orchestrator operation/absence owners, shared run-path resolver; scoped compute/filesystem adapters | CP-N09 chronology misuse, cleanup success reconstruction, FileJournal reader path reconstruction and lifecycle sink infra calls | Before/after audit reproductions + official-ingress lifecycle/cleanup; SSOT-01/02, CP-N05/09 |
| C03 — service/client projections | Scenario service contract owner; Controller observation freshness; auth owner-derived capability projection | Scenario wire copies, UI normalization/freshness/grant-policy replicas; remaining CP-N02–08 responsibilities | SSOT-05–08 reproductions, generated-client drift checks, focused service/UI tests and ingress |

B01→B02→B03→B04→B05→B06→B07 is the execution order. C01–C03 follow completed Work
acceptance. CP/Work factory isolation and removal of CP-owned Work bindings are B01
prerequisites (RAB-03), not B05 work. State integration is B03; Rabbit delivery is B05;
they cannot be moved into C01 to declare a premature Work success. Full C-slice public
contract details belong to their contract-first implementation preparation; no unresolved
C design choice is permitted to control a B-slice port or acceptance decision.

### External consumer disposition

The inventory enumerates file references for moved types and additional transport/config
candidates. Apply these decisions to all corresponding production paths, not only the
examples in the deletion table:

| Current consumers | Disposition / slice |
|---|---|
| Nine worker services, their Application/configuration classes and test suites | Update moved-type references in B01–B03; trigger policy/factory correction in B01, state-port update in B03; direct business/transport split in B07 |
| `common/manager-sdk` context/status consumers | Use sole moved observability/core contracts in B01; retain manager policy owner; no Work provider dependency |
| Scenario Manager `ScenarioBundleValidator`, capability catalogue, bundle/default resolution | B02 canonical settings/template validator; B04 resolved topology projection; C03 unrelated wire/UI policy findings |
| `SwarmWorkerSpecFactory`, `SwarmWorkBindingsProjector`, Controller traffic properties/environment export | B02 delegate settings export; B04 consume `ResolvedWorkTopology`; retain only unrelated compute/CP logic for C01/C02 |
| `SwarmQueueStatsPortAdapter`, `SwarmQueueStatsCollector`, `QueuePropertyCoercion`, BufferGuard and QueueStatsPort consumers | B04 Rabbit read/coercion moves to RabbitWorkResourceObserver; Controller core maps canonical observations to QueueStatsPort; typed unavailability and all guard/status consumer handling migrate together |
| `SwarmQueueMetrics` | B04 gauge-only projection in swarm-controller-work-core using Micrometer API; consume measured observations, remove unavailable gauges, no resource reads or naming authority |
| `SwarmRuntimeInfrastructure`, `SwarmLifecycleManager` | B04 scoped observation/provision/removal ports; remaining CP/compute concerns stay assigned to C slices |
| Orchestrator `DebugTapService`, `ContainerLifecycleManager`, `AmqpRabbitTopologyAdapter`, reconciliation ports/service | B04 extract Work responsibilities into Work cores/adapter and use canonical absence evidence; CP/compute remainders in C01/C02, never copied into Work implementations |
| CP listeners, `RabbitConfig`, Controller queue verifier/status/properties/catalogues | B01 codec/context move, CP/Work factory isolation and CP Work binding removal; C01 removes remaining CP naming/settings replicas |
| UI V2 subscriptions/decoder, editor controls; product MCP and VS Code scenario/runtime clients | B02 generated mutability/validation metadata, B04 Work binding projections; C01 CP effective exchange, C03 unrelated grant/freshness copies |
| Node debug client/recorder | B04 read Work destinations through existing public runtime/tap interfaces; remove Work exchange assertions and name reconstruction; C01 CP assertions/routing copies |
| Broker definitions, compose/HiveForge templates, Dockerfiles, package/build scripts and configuration resources | Update moved artifact/sample references in their owning B slice; C01 canonical CP bootstrap projection. Keep existing artifact paths where possible; protected edits retain their explicit approval requirement |
| E2E ControlPlaneEvents, WorkQueueConsumer, QueueProbe, RabbitSubscriptions; Java/unit test consumers; scenario-templating-check | Update canonical imports/producer fixtures in B01–B03; observation queues remain distinct legitimate ownership. V12 uses supported ingress, without extending the excluded SwarmLifecycleSteps |

Repository import/type-reference scanning does not prove that dynamically loaded factories,
reflection strings or generated files have no consumers. Review the Spring metadata and
all POM/resource/config references alongside the inventory at each move. Delete old class
names in auto-configuration files in the same slice; regenerate client/capability artifacts
from their canonical owners rather than patching generated output manually.

## 8. Verification catalogue

V-identifiers name acceptance obligations and proposed test classes, not tests already
implemented. Before each production slice, select and run the corresponding existing
tests/reproductions at its before revision and preserve results. Add missing reproductions
before changing behavior. No runtime tests or deployment are implied by this design task.

| ID | Concrete check / test destination | Observable pass condition |
|---|---|---|
| V01 | Root Maven Enforcer dependency rules, focused architecture tests and mandatory separate boundary review | Declared dependency bans hold; review traces owners, actual effects and composition, with explicit evidence and no duplicate authority |
| V02 | Existing CP codec/topology/WorkerControlPlane tests and `ControlTopologyOwnershipTest` | Canonical wire/routing fixtures and repaired CP-N01 remain unchanged; no Work dependency in CP core |
| V03 | Import/dependency enforcement and separate source review of CP/Work ownership; relevant startup, rejection and policy-regression behavior tests, including existing WorkControlCompositionTest cases | Effective Work/CP policies isolated in both directions; CP remains active without Work exchange for non-Rabbit/NONE; unselected adapters inactive; missing/duplicate configuration rejected; trigger policy preserved. No module/bean-selection test gate |
| V04 | `WorkConfigurationContractTest` in work-config; existing `WorkIOConfigBinderTest`, Redis properties/output/dataset and ScenarioBundleValidator suites | Identical decisions in authoring/resolved/patch paths for fully resolved values; duplicate sources/routes and unknown fields fail canonically |
| V05 | `RequestTemplateContractTest` in request-templates; existing TemplateLoader and scenario validation suites | Required fields/auth/protocol decisions agree; root/path failure explicit; parser has no filesystem effects |
| V06 | `WorkerStateCoordinatorTest`, `WorkerIoCoordinatorTest`, migrated DefaultWorkerRuntime/WorkerControlPlaneRuntime tests | One accepted revision per command, rejected patch unchanged; exactly one output action; fresh observations prove disable/drain, CP stays active |
| V07 | `ResolvedWorkTopologyTest`, `WorkQueueObservationContractTest`, existing spec/bindings/tap, QueueStatsPort, BufferGuard and queue-metrics tests | Same canonical names throughout; measured counts/age preserved; unavailable/absent/unknown never become zero counts; failed guard tick issues no rate update or state sample; unavailable gauges removed; wrong swarm/run rejected |
| V08 | `WorkRemovalEvidenceTest` plus reconciliation/lifecycle acceptance | Failed delete, remaining resource and unknown observation cannot yield successful absence; wrong swarm/run selection rejected |
| V09 | `RabbitWorkDeliveryContractTest`, migrated RabbitWorkOutput/adapter tests and ingress flow | Settings affect actual containers; dispatch is not settled early at maxInFlight>1; confirmation ack/nack/return/timeout and DROP paths match declared receipts |
| V10 | `RedisCapabilityContractTest`, existing dataset/output/sequence/token store tests and HTTP-sequence diagnostics tests | Ordering/exhaustion/output encoding preserved; token claim scope/lease and sequence operations preserved; failures never trigger an alternative connection/config |
| V11 | `LocalWorkInputContractTest`, existing CSV/Scheduler and worker implementation tests | Rates/reset/exhaustion/stop semantics preserved; NONE needs no output connection; each worker core builds without infrastructure |
| V12 | Public-ingress acceptance using existing lifecycle, WorkItem headers, history-policy, Redis dataset, auth and clearing-export scenarios | Intended behavior retained end to end and cleanup verified; evidence records exact built/deployed revision/configuration |

Normal build: `./mvnw -B -ntp test`. Focused before checks use existing modules, for example
`./mvnw -B -ntp -pl common/worker-sdk,common/control-plane-core,common/control-plane-spring,scenario-manager-service,swarm-controller-service,orchestrator-service -am test`.
After each slice add its newly introduced modules to that focused reactor command.
Hand off execution evidence for the separate boundary review defined above.
Verification of a future artifact must not silently use a previously installed Maven JAR.

For stack acceptance use the documented official ingress and `start-e2e-tests.sh`/product
MCP through that ingress. The existing `SwarmLifecycleSteps` is excluded from refactoring;
reuse suitable ingress-only scenarios, and add dedicated small boundary acceptance tests
where existing diagnostics use direct service ports. Do not use that fixture as permission
to bypass ingress or to create a second codec. Provisioning/adapter unit tests may use
mock clients in-process; real external checks follow the repository's ingress rule.

### Input/output coverage

The canonical IO selection accepts four inputs and three outputs independently. At the
Work runtime level all 12 pairs are supported: each input calls the same dispatcher and
each selected output handles a produced item, with explicit NONE behavior. This does not
claim that every worker business function accepts every payload or emits an item. Encode
the pair test as a parameterized composition contract with a deterministic worker fixture,
then test real worker/scenario restrictions through their existing canonical validators.

| Input | RABBITMQ output | REDIS output | NONE output |
|---|---|---|---|
| RABBITMQ | V03/V06/V09; existing local-rest ingress | V03/V06/V09/V10; existing WebAuth Redis ingress | V03/V06/V09; dedicated no-output ingress |
| REDIS_DATASET | V03/V06/V10; existing Redis dataset ingress after resolved config capture | V03/V06/V10; capability-controls-io-matrix fixture | V03/V06/V10; dedicated no-output composition/ingress |
| CSV_DATASET | V03/V06/V11; dedicated CSV fixture | V03/V06/V10/V11; dedicated CSV fixture | V03/V06/V11; dedicated no-output fixture |
| SCHEDULER | V03/V06/V11; existing generator/clearing-streaming ingress | V03/V06/V10/V11; dedicated timed-source fixture | V03/V06/V11; explicit NONE fixture |

Source YAML with both fields explicit currently exhibits five pairs. Many scenarios inherit
one field from worker configuration: absence of an explicit pair in YAML is not proof of
unsupported behavior. Before-state captures must record fully resolved config from the
official API and pair it with the canonical configuration tests. Preserve all seven enum
variants; new capability combinations outside this existing independent-selection contract
require an explicit contract change, not a permissive fallback.

## 9. Design review and handoff

Implementation may start only after the dependency graph, source/consumer inventory,
deletion ledger and acceptance coverage are internally consistent. The execution plan
records the final step 2 result and outstanding production work. A design review can close
design gaps; it cannot mark V-tests, migrated owners or inherited findings complete.

The baseline/evidence record for each slice contains before/after revision and patch digest,
configuration/scenario versions (without secrets), exact commands, results/artifact links,
intended deltas and individual finding closure. Unexplained behavior changes block the
slice. Store evidence durably in repo/CI, not only temporary logs or HiveMind summaries.

### B01 review corrections — scheduling and JDK effects

The scheduler delivers every CP projection to `ScheduledInvocationPolicy.update`,
serialized with projection revision assignment. Updating cannot dispatch work, advance
an interval, or consume a pending single request. Only `plan(tickMillis)` consumes quota;
it never receives a potentially stale captured snapshot. Policy updates and planning
are mutually exclusive. Trigger retains one pending single request across intervening
configuration changes and disablement, matching its prior coalescing semantics. The
policy holds only its scheduling projection and counters; CP remains the state owner
until B03. Rate policy clears fractional carry on disablement even between ticks.

SDK composition injects its selected `SequenceAccess` bean into the default renderer.
An explicitly disabled or alternative bean must prevent use of the global Redis
sequence adapter. Removal of the existing generator globals remains B06.

JDK availability does not authorize direct runtime infrastructure operations in cores.
Review actual calls, constructors and overloads under `docs/REVIEW_RULES.md`.
Canonical packaged-schema loading retains its existing owner. The former scanner,
JSON policy and catch-all ArchUnit test have been removed by explicit user decision;
B01-R3's automation remedy is superseded, not reported as a technically repaired scanner.
The underlying ownership and IO restrictions remain mandatory review obligations.
