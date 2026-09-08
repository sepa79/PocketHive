# Current runtime responsibility records

Status: B01 adoption accepted in separate review, 2026-09-08.
RV2 descriptions/headers agree with the inspected source. Current review evidence:
`docs/inProgress/boundary-design/b01/rv2-correction-review.md`.
RV1's wiring-test requirement remains superseded by the human boundary-verification
policy in `docs/REVIEW_RULES.md`. B02–B07 ownership changes remain pending.
This is the canonical current-owner record for the 157 production files in the B01
adoption scope. The [boundary design](work-plane-boundaries.md) owns target module
placement and migration gates; it is not evidence that later slices are implemented.
Other services/responsibilities are outside this adoption and retain their existing
architecture sections. This is not a complete repository SSOT certification.

Each ID names a bounded concern. Where several classes appear, the record states
their distinct roles (value contract, selected implementation, projection or delegate),
not competing authorities over one fact. Current mixed implementations and incomplete
configuration/client separation remain explicit debt; descriptions do not waive the
engineering rules or authorize adding behavior to mixed files. Headers describe the
role of their own type and reference these records. Keep wire/schema/API definitions
at their existing owners rather than copying them here.

The source import table stays in `RepositoryImportBoundaryTest`; artifact bans stay
in the root POM. Neither proves the action restrictions below. Verification names
are existing test entrypoints, not a claim that every postcondition is covered or
that every test was rerun. Fresh execution, call-path evidence and gaps are recorded
in `docs/inProgress/boundary-design/b01/responsibility-adoption.md`. Acceptance uses
[the separate review workflow](../ai/RESPONSIBILITY_WORKFLOW.md#separate-review-task).

## RESP-WORK-ITEM

**Current module(s):** `common/work-api`.

WorkItem owns immutable payload/step history; WorkItemBuilder constructs it and WorkStep/HistoryPolicy/WorkPayloadEncoding express that model.

Worker functions and transport codecs use the same item model; payload JSON convenience conversion is distinct from envelope serialization.

**Forbidden:** perform transport IO or reimplement the Work envelope codec.

**Required effect:** Building/appending preserves explicit history and immutable snapshots; consumers see the same payload encoding.

**Verification entrypoints:** `WorkItemTest`.

**Migration status:** Current B01 extraction; delivery and runtime lifecycle remain B03/B05.

## RESP-WORK-WIRE

**Current module(s):** `common/work-api`.

WorkItemJsonCodec owns the Work envelope encode/decode boundary; its package-private WorkItemSchemaValidator performs schema validation.

RabbitWorkItemConverter calls the codec; packaged docs/spec/workitem-envelope.schema.json remains the wire authority. Redis payload capture is not a consumer of this envelope codec.

**Forbidden:** load a runtime schema from a configurable filesystem path or create another envelope codec.

**Required effect:** Valid envelopes round-trip through one codec; invalid wire documents and missing packaged schema fail explicitly.

**Verification entrypoints:** `WorkItemJsonCodecTest`.

**Migration status:** Current. Classpath schema loading is permitted; this is not a blanket IO-purity claim.

## RESP-WORK-HTTP-CONTRACT

**Current module(s):** `common/work-api`.

The HTTP request/result records own the shared HTTP payload shape, with request metadata, outcome and metric value components.

Request Builder creates requests; the HTTP protocol handler produces results; consumers use these records.

**Forbidden:** execute HTTP calls, select clients or decide worker lifecycle.

**Required effect:** Producers and consumers exchange the same HTTP request/result field shape without transport client types.

**Verification entrypoints:** `TransportEnvelopeDtosTest`.

**Migration status:** Current B01 extraction; no transport implementation is moved here.

## RESP-WORK-TCP-CONTRACT

**Current module(s):** `common/work-api`.

The TCP request/result records own the shared TCP payload shape, with request metadata, outcome and metric value components.

Request Builder and TcpProtocolHandler exchange the shared values.

**Forbidden:** open sockets, select clients or decide worker lifecycle.

**Required effect:** Producers and consumers exchange the same TCP request/result field shape without socket types.

**Verification entrypoints:** `TransportEnvelopeDtosTest`.

**Migration status:** Current B01 extraction.

## RESP-WORK-ISO-CONTRACT

**Current module(s):** `common/work-api`.

The ISO8583 request/result records and IsoSchemaRef own the shared ISO payload and schema-reference shape.

Request Builder, ISO schema handling and Iso8583ProtocolHandler exchange these values.

**Forbidden:** load schema files, open sockets or duplicate ISO wire encoding.

**Required effect:** Producers and consumers exchange the same ISO request/result/schema-reference shape without an embedded schema loader.

**Verification entrypoints:** `TransportEnvelopeDtosTest`.

**Migration status:** Current B01 extraction; the referenced schema implementation remains in its service.

## RESP-WORK-CAPABILITY

**Current module(s):** `common/work-api`, `common/worker-sdk`.

PocketHiveWorker declares capabilities/configuration type; WorkerDefinitionDiscovery owns discovery of the one worker bean and produces WorkerDefinition.

SDK composition supplies the Spring bean inventory and IO binders; WorkerInfo is runtime identity metadata, not a topology resolver.

**Forbidden:** construct transport clients, declare topology or choose an adapter by role-name heuristics.

**Required effect:** Exactly one declared worker yields a definition with its declared capabilities and selected IO; ambiguous discovery fails.

**Verification entrypoints:** `PocketHiveWorkerIoFromConfigTest`.

**Migration status:** Current discovery remains in worker-sdk; canonical IO parsing is B02.

## RESP-WORK-CONTEXT

**Current module(s):** `common/work-api`, `common/worker-sdk`.

DefaultWorkerContextFactory implements WorkerContextFactory and creates the read view passed to business workers.

The view exposes the selected worker state, history policy and observability facilities; it does not own accepted configuration.

**Forbidden:** mutate accepted configuration, select IO implementations or provision resources.

**Required effect:** An invocation receives the selected worker's state and facilities; reading the view does not apply a configuration update.

**Verification entrypoints:** `DefaultWorkerContextFactoryTest`.

**Migration status:** Current SDK implementation; narrower runtime ports are B03/B07.

## RESP-WORK-STATUS

**Current module(s):** `common/work-api`, `common/worker-sdk`.

WorkerStatusPublisher owns worker-provided status contributions through StatusPublisher/MutableStatus, stored with WorkerState.

Worker functions contribute data; WorkerControlPlaneRuntime builds/emits the control status projection.

**Forbidden:** publish control envelopes directly or treat contributed metrics as authoritative lifecycle state.

**Required effect:** Worker contributions appear in emitted status without overriding the runtime's reserved control state.

**Verification entrypoints:** `WorkerStatusPublisherTest`.

**Migration status:** Current; canonical worker state is separately scoped under RESP-WORK-STATE.

## RESP-AUTH-VALUES

**Current module(s):** `common/auth-contracts`.

The moved worker-auth profile/reference/material records and enums own their shared value contracts, distinct from the product auth-service API.

Request templates and worker AuthRuntime share these values; profile file loading remains AuthRuntime behavior.

**Forbidden:** load files, refresh credentials or select a storage implementation.

**Required effect:** Worker-auth consumers share profile/reference/material types without loading infrastructure from the contracts module.

**Verification entrypoints:** `AuthRuntimeTest`.

**Migration status:** Values moved in B01; worker auth parsing/runtime separation remains B06/B07.

## RESP-AUTH-TOKEN-STORE

**Current module(s):** `common/auth-contracts`, `common/worker-sdk`.

TokenStore owns the worker token storage/claim port; token keys and claim/result values define its shared contract. RedisTokenStore remains its Redis implementation.

AuthRuntime calls the selected store for cached credentials and refresh claims; profile validation is outside the store contract.

**Forbidden:** implement refresh HTTP flows or create an independent token identity/claim contract.

**Required effect:** The selected store returns scoped tokens/claims and enforces its refresh lease contract; callers do not fabricate a successful claim.

**Verification entrypoints:** `RedisTokenStoreTest`, `AuthRuntimeTest`.

**Migration status:** Port/values moved in B01. Redis implementation and connection ownership remain B06.

## RESP-OBS-CONTEXT

**Current module(s):** `common/observability-core`, `common/worker-sdk`.

ObservabilityContextUtil owns context propagation/encoding over ObservabilityContext/Hop; WorkerObservabilityInterceptor applies it around one invocation.

WorkItem carries the context; transport boundaries and worker interceptors propagate it.

**Forbidden:** own workload enablement, configure exporters or reconstruct a second context format.

**Required effect:** Correlation/trace context and hop history are propagated through an invocation without becoming control intent.

**Verification entrypoints:** `WorkerObservabilityInterceptorTest`.

**Migration status:** Context helpers moved in B01; exporter composition remains in observability.

## RESP-CP-JSON-CONFIG

**Current module(s):** `common/observability-core`.

ControlPlaneJson supplies shared Jackson configuration for non-wire runtime JSON projections. ControlPlaneCodec owns control wire encoding/decoding and creates its own mapper.

Controller RabbitConfig exposes ControlPlaneJson.mapper() as its ObjectMapper bean; SwarmControllerControlPlaneConfiguration also supplies it to JournalControlPlanePublisher for journal projections. ControlPlaneCodec does not consume this helper.

**Forbidden:** own control wire encoding/decoding or validation, or select infrastructure clients.

**Required effect:** Non-wire consumers receive the shared projection mapper; control wire acceptance and serialization remain exclusively in ControlPlaneCodec.

**Verification entrypoints:** `ControlPlaneCodecTest` covers the separate wire boundary; it does not verify the projection helper. Helper consumers are traced in the RV2 correction evidence in `docs/inProgress/boundary-design/b01/review-responsibilities.md`.

**Migration status:** Current helper extraction. Review callers separately; presence of this helper does not authorize direct envelope serialization.

## RESP-CP-STATUS-ENVELOPE

**Current module(s):** `common/observability-core`.

StatusEnvelopeBuilder constructs the canonical control status envelope from supplied identity, scope and metrics.

Worker/control status emitters supply observations; the builder maps them into shared topology-core control values.

**Forbidden:** infer lifecycle success or mutate the underlying observed/desired state.

**Required effect:** Supplied status data is represented in the canonical envelope without constructing a terminal lifecycle outcome.

**Verification entrypoints:** `WorkerControlPlaneRuntimeTest`.

**Migration status:** Current extraction; status data collection is outside this builder.

## RESP-IDENTITY-BEE-NAME

**Current module(s):** `common/observability-core`.

BeeNameGenerator owns generation of bee display/instance name components.

Identity composition consumes the generated value; it is not a Work queue/exchange naming policy.

**Forbidden:** choose topology names, control routes or configuration defaults.

**Required effect:** Name generation preserves the utility's supported format; queue/exchange resolution is not added.

**Verification entrypoints:** `BeeNameGeneratorTest`.

**Migration status:** Current utility extraction; callers still own identity composition.

## RESP-TEMPLATE-RENDER

**Current module(s):** `common/templating-api`, `common/templating`.

PebbleTemplateRenderer owns rendering and syntax validation through the API ports; its SpEL/weighted-selection helpers implement the engine's expression facilities.

SDK and service consumers supply templates/context. Sequence calls use the injected SequenceAccess. SpelFunctions includes clock/random helpers; it is not mathematically pure.

**Forbidden:** select sequence connections, resolve application beans or expose an alternative engine contract.

**Required effect:** Configured templates render through the shared engine; syntax checks run without sequence effects and errors use the API contract.

**Verification entrypoints:** `PebbleTemplateRendererTest`, `SequencePortRenderingTest`.

**Migration status:** Port extraction current; retained engine behavior is not newly certified as a complete sandbox.

## RESP-TEMPLATE-SEQUENCE

**Current module(s):** `common/templating-api`, `common/templating`.

SequenceAccess defines next/reset; SequenceFunctions maps expression arguments; ConfiguredRedisSequenceAccess delegates to the existing RedisSequenceGenerator. DisabledSequenceAccess rejects effects explicitly.

Pebble/SpEL invoke the selected port. RedisSequenceConfiguration/RedisSequenceGenerator still own existing global configuration/generation outside the API.

**Forbidden:** create a second generator or switch from a disabled port to Redis.

**Required effect:** Arguments and reset reach the injected port; selecting DisabledSequenceAccess never activates Redis.

**Verification entrypoints:** `SequencePortRenderingTest` (argument/reset behavior and effect-free syntax validation).

**Migration status:** B01 injects the port on the SDK renderer path. Global Redis generator/configuration removal remains B06. Convenience constructors in RedisPushSupport, RedisUploaderInterceptor and ProcessorWorkerImpl still construct a configured sequence adapter directly; they do not prove application-wide selection isolation.

## RESP-CP-COMPOSITION

**Current module(s):** `common/control-plane-spring`.

Common CP configuration wires shared codec/infrastructure; Manager and Worker auto-configurations separately own their identity/emitter/topology composition.

CP descriptors and ControlPlaneTopologyDeclarableFactory supply declarations; CP listener policy has its own record.

**Forbidden:** read Work configuration, declare Work resources or decide workload lifecycle.

**Required effect:** CP remains active for non-Rabbit/NONE Work without a fictitious Work exchange.

**Verification entrypoints:** `WorkControlCompositionTest`, `ControlPlaneWireShapeTest`.

**Migration status:** Current B01 CP/Work startup isolation.

## RESP-CP-LISTENER-POLICY

**Current module(s):** `common/control-plane-spring`.

ControlPlaneRabbitListenerConfiguration creates the dedicated CP factory; its poison customizer installs CP error handling and ControlPlaneFatalExceptionStrategy classifies fatal decoding failures.

All CP listeners select controlPlaneRabbitListenerContainerFactory; policy applies to that factory only.

**Forbidden:** change Work error handlers/executors or acknowledge Work deliveries.

**Required effect:** Changing or stopping Work policy leaves CP policy/executor intact, and conversely.

**Verification:** Import/dependency enforcement and source review of named CP factory scope, customizer and listener consumers. Tests of factory/handler identity were removed under the boundary-verification policy; they are not acceptance requirements.

**Migration status:** Current B01 isolation; later CP adapter packaging must preserve it.

## RESP-CP-DECLARATIONS

**Current module(s):** `common/control-plane-spring`.

ControlPlaneTopologyDeclarableFactory converts canonical CP descriptors into AMQP declarations.

Manager/Worker composition provides descriptors; the shared topology contracts own their names.

**Forbidden:** declare Work traffic resources or independently choose CP resource names.

**Required effect:** CP composition declares only descriptor-derived CP resources; Work settings are not required.

**Verification entrypoints:** `ControlTopologyOwnershipTest`, `WorkControlCompositionTest`.

**Migration status:** Current. Work provisioning remains Controller-owned until B04.

## RESP-CP-PUBLISH

**Current module(s):** `common/control-plane-spring`.

AmqpControlPlanePublisher owns publication of already canonical CP messages over AMQP.

ControlPlaneEmitter/codec supply message and route; the adapter uses the configured CP exchange.

**Forbidden:** serialize an alternative envelope, publish Work outputs or declare resources.

**Required effect:** The canonical message reaches its configured CP exchange/route without Work publication or re-encoding.

**Verification entrypoints:** `AmqpControlPlanePublisherTest`, `ControlPlaneWireShapeTest`.

**Migration status:** Moved from CP core in B01.

## RESP-WORK-COMPOSITION

**Current module(s):** `common/worker-sdk`.

PocketHiveWorkerSdkAutoConfiguration wires worker discovery, runtime, policies, contexts and selected IO/sequence ports.

Dedicated binders and registries supply values/selection. Spring compatibility/default bean construction already present is not evidence of complete configuration SSOT.

**Forbidden:** implement worker business behavior or silently resolve ambiguous adapter matches.

**Required effect:** Only the selected adapters/sequence port are wired; missing or duplicate matches fail startup.

**Verification:** Source review of bootstrap/port ownership plus one `WorkControlCompositionTest` regression: Scheduler/NONE starts with CP declarations and no Work Rabbit settings. This checks context startup and declaration construction, not live broker behavior; no bean-selection test gate.

**Migration status:** Current composition. Configuration consolidation B02 and bootstrap extraction B07 remain.

## RESP-WORK-IO-CONFIG

**Current module(s):** `common/worker-sdk`.

PocketHiveWorkerProperties holds bound worker settings; WorkOutputConfig is the selected output settings contract. Existing WorkInputConfigBinder/WorkOutputConfigBinder perform startup binding.

Discovery and adapter factories consume bound settings. Runtime raw patches still have separate paths in WorkerControlPlaneRuntime and IO adapters.

**Forbidden:** make settings objects open connections or infer successful publication from configuration.

**Required effect:** Settings reach the selected adapter; consistent startup/patch validation is a B02 requirement still unverified here.

**Verification entrypoints:** `WorkIOConfigBinderTest`, `PocketHiveWorkerIoFromConfigTest`.

**Migration status:** B02 owns consolidation of startup/patch parsing. Current duplicated parsing paths remain debt, not accepted SSOT.

## RESP-WORK-ADAPTER-SELECTION

**Current module(s):** `common/worker-sdk`.

WorkInputRegistryInitializer selects one input factory; WorkOutputRegistryInitializer selects one output factory. Each owns its distinct direction; WorkOutputRegistry retains the selected outputs and dispatches publication.

SDK composition supplies available factories and bound definitions. NONE is an explicit output implementation.

**Forbidden:** choose by ordering, suppress missing factories or independently reopen adapter selection at dispatch.

**Required effect:** Each direction has exactly one matching factory; missing and duplicate matches fail, including NONE cases.

**Verification entrypoints:** `WorkInputRegistryInitializerTest`, `WorkOutputRegistryInitializerTest`; source review against the selection contract. `WorkControlCompositionTest` covers only Scheduler/NONE startup, not factory rejection.

**Migration status:** Current B01 exact-match selection.

## RESP-WORK-STATE

**Current module(s):** `common/worker-sdk`.

WorkerControlPlaneRuntime owns accepted worker control updates over WorkerState; WorkerControlQueueListener receives/dispatches CP messages. WorkerState also stores invocation counters and status contributions with separate callers.

State snapshots feed inputs and WorkerContext; counters and contributed status are not additional configuration writers.

**Forbidden:** let a listener introduce its own configuration state machine or infer control success from attempted Work effects.

**Required effect:** Accepted control updates reach the worker state and its snapshots; one accepted revision/state owner must survive B03 extraction.

**Verification entrypoints:** `WorkerControlPlaneRuntimeTest`, `WorkerStateTest`.

**Migration status:** Current implementation mixes control update, status and configuration concerns. B02/B03 separate them; this record does not certify that separation.

## RESP-WORK-INVOCATION

**Current module(s):** `common/worker-sdk`.

DefaultWorkerRuntime selects WorkerInvocation and routes its non-null result to WorkOutputRegistry; WorkerInvocation executes the function/interceptor chain. WorkMessageDispatcher is the transport-independent dispatch hook.

Input adapters dispatch through WorkerRuntime; invocation context carries values, not control authority.

**Forbidden:** reimplement service business logic or introduce a second output publication for the same result.

**Required effect:** A named dispatch invokes the selected function/interceptors and publishes its result through the selected output once on the normal SDK path.

**Verification entrypoints:** `DefaultWorkerRuntimeTest`, `RabbitMessageWorkerAdapterTest`.

**Migration status:** Current path. Settlement/dispatch and lifecycle separation remain B03/B05; separate callback output paths need review.

## RESP-WORK-METRICS

**Current module(s):** `common/worker-sdk`.

WorkerMetricsInterceptor projects invocation timing/results into Micrometer measurements around the interceptor chain.

WorkerInvocation supplies execution context; meters observe execution rather than deciding it.

**Forbidden:** change accepted worker configuration or decide domain outcomes from metric samples.

**Required effect:** Measurements describe the executed invocation without changing its result or accepted configuration.

**Verification entrypoints:** `WorkerMetricsInterceptorTest`.

**Migration status:** Current SDK projection.

## RESP-WORK-MESSAGE-TEMPLATE

**Current module(s):** `common/worker-sdk`.

MessageTemplateRenderer maps a MessageTemplate's body/path/method/headers through the injected TemplateRenderer for Generator, Request Builder and ScenarioTemplateValidator diagnostics. MessageTemplate defines those fields; request-template bundle/file loading remains separate.

TemplatingInterceptor separately applies an invocation body template directly through TemplateRenderer and appends the rendered payload as a Work step. It does not call MessageTemplateRenderer. Each caller currently builds its own context; both delegate expression evaluation to the same template engine contract.

**Forbidden:** open transport connections or implement a second Pebble/SpEL evaluator.

**Required effect:** MessageTemplateRenderer returns rendered message fields from its supplied definition/context. TemplatingInterceptor renders the selected invocation body and passes the updated Work item down the chain. Both use their injected TemplateRenderer.

**Verification entrypoints:** `MessageTemplateRendererTest`, `TemplatingInterceptorTest`.

**Migration status:** Current SDK callers have separate payload-context construction and default handling; B02 must address that retained parsing/context debt. This record does not claim those mappings are consolidated or approve their duplication.

## RESP-WORK-SCHEDULE-CONTRACT

**Current module(s):** `common/work-api`.

ScheduledInvocationPolicy owns the update/plan scheduling contract; SchedulingState is a read-only input projection with revision and explicit configured state.

SchedulerWorkInput delivers updates; RateSchedulePolicy and TriggerSchedulePolicy implement distinct rate versus trigger policies.

**Forbidden:** read Control Plane directly, execute work or make the projection a configuration writer.

**Required effect:** Ordered updates reach the policy without consuming quota; plan consumes quota using monotonic tick time.

**Verification entrypoints:** `RateSchedulePolicyTest`, `TriggerSchedulePolicyTest`.

**Migration status:** Current B01 port; updates and planning serialize in each policy.

## RESP-WORK-SCHEDULE-INPUT

**Current module(s):** `common/worker-sdk`.

SchedulerWorkInput owns timed intake, finite-run count and dispatch; its factory/builder wire the selected policy and callbacks.

It projects WorkerControlPlaneRuntime snapshots, delivers each revision to the policy, and dispatches the returned quota through WorkerRuntime.

**Forbidden:** reimplement trigger interval/single-request rules or select a policy by worker role.

**Required effect:** Each state revision reaches the policy before a subsequent tick; finite-run intake dispatches through WorkerRuntime.

**Verification entrypoints:** `WorkControlCompositionTest`, `TriggerSchedulerIntegrationTest`.

**Migration status:** Current input still applies raw scheduling overrides; B02 owns that parsing migration, B03/B07 lifecycle/packaging.

## RESP-WORK-RATE-POLICY

**Current module(s):** `common/worker-sdk`.

RateSchedulePolicy owns fractional rate quota accumulation and reset on disabled revisions.

SchedulerWorkInput supplies monotonic tick time and ordered SchedulingState updates.

**Forbidden:** read CP, mutate settings or dispatch messages.

**Required effect:** Fractional quotas accumulate at the configured rate and disabled updates reset carry, including between ticks.

**Verification entrypoints:** `RateSchedulePolicyTest`.

**Migration status:** Current B01 policy.

## RESP-TRIGGER-POLICY

**Current module(s):** `trigger-service`.

TriggerSchedulePolicy owns interval/single-request pending quota; TriggerSchedulingConfiguration selects it as the service scheduling policy.

SchedulerWorkInput delivers revisions even between ticks; TriggerWorkerImpl executes the subsequently dispatched action.

**Forbidden:** read CP directly, select IO adapters or execute trigger actions.

**Required effect:** A true-to-false single-request update between ticks is retained until consumed; disabled/re-enabled behavior preserves the documented pending request.

**Verification entrypoints:** `TriggerSchedulePolicyTest`, `TriggerSchedulerIntegrationTest`.

**Migration status:** Current B01 policy; pending true-to-false transitions must survive until consumed.

## RESP-WORK-RABBIT-POLICY

**Current module(s):** `common/worker-sdk`.

VirtualThreadRabbitContainerCustomizer owns the Work listener executor and its shutdown.

Work factory composition installs it; the CP factory/executor has a separate owner.

**Forbidden:** modify CP listener policies or close CP executors.

**Required effect:** Only Work containers receive the owned executor and only its lifecycle closes it.

**Verification:** Source review of the customizer's Work factory scope and owned executor lifecycle, with import/dependency enforcement. No private factory-field or bean-selection test gate.

**Migration status:** Current B01 policy isolation.

## RESP-WORK-RABBIT-TRANSPORT

**Current module(s):** `common/worker-sdk`.

RabbitMessageWorkerAdapter owns existing Rabbit intake/container/execution plumbing; RabbitWorkItemConverter delegates envelope conversion to WorkItemJsonCodec; RabbitWorkOutput publishes to its immutable destination.

RabbitWorkInputFactory dispatches through WorkerRuntime and explicitly installs an empty result-publisher callback; the normal path publishes through WorkOutputRegistry. Other adapter callback/direct-publish construction paths remain available and require separate review.

**Forbidden:** create another Work envelope codec, declare CP resources or mutate a captured output destination.

**Required effect:** The codec remains shared and Rabbit output destination is immutable; acknowledgement/confirmation semantics are separately gated by B05.

**Verification entrypoints:** `RabbitMessageWorkerAdapterTest`, `RabbitWorkItemConverterTest`, `RabbitWorkOutputTest`.

**Migration status:** Current SDK infrastructure. B05 owns delivery/settlement split; this record does not claim callbacks are a proven single-output path.

## RESP-WORK-CSV-INPUT

**Current module(s):** `common/worker-sdk`.

CsvDataSetWorkInput owns file-backed dataset iteration and intake lifecycle in the current SDK.

It consumes selected CSV settings, observes worker state and dispatches records through WorkerRuntime.

**Forbidden:** declare broker resources or own accepted worker configuration.

**Required effect:** Records are read in the configured order and exhaustion/stop is observed without broker provisioning.

**Verification entrypoints:** `CsvDataSetWorkInputTest`.

**Migration status:** Local adapter packaging B07; raw configuration/lifecycle consolidation B02/B03.

## RESP-WORK-REDIS-DATASET

**Current module(s):** `common/worker-sdk`.

RedisDataSetWorkInput owns Redis dataset reads, cursor/exhaustion handling and current intake lifecycle.

Selected Redis settings and worker state drive reads; records dispatch through WorkerRuntime.

**Forbidden:** refresh auth tokens, generate sequences or declare Rabbit resources.

**Required effect:** Configured dataset reads preserve cursor/order/exhaustion; no switch to a different source on failure.

**Verification entrypoints:** `RedisDataSetWorkInputTest`.

**Migration status:** Current SDK client implementation; B02/B03/B06 migration remains.

## RESP-WORK-REDIS-PUSH

**Current module(s):** `common/worker-sdk`.

RedisPushSupport owns route/payload selection and Redis list write execution; RedisWorkOutput applies output policy, while RedisUploaderInterceptor applies diagnostic-capture policy.

Both consumers delegate the push operation; RedisWorkOutputFactory wires the selected output. Diagnostic capture and business output are distinct uses, not duplicate authority for one result.

**Forbidden:** turn capture into business output or independently reimplement the shared Redis push operation.

**Required effect:** The selected payload is pushed to the resolved list through shared support; capture and business output retain separate explicit policies.

**Verification entrypoints:** `RedisWorkOutputTest`, `RedisUploaderInterceptorTest`.

**Migration status:** Existing parsing/configuration and nested contracts remain mixed; B02/B06 must separate them. This is not proof of consolidated Redis settings.

## RESP-WORK-NONE-OUTPUT

**Current module(s):** `common/worker-sdk`.

NoopWorkOutput owns the explicitly selected NONE output behavior.

The output registry delegates to it without requiring Rabbit/Redis output settings.

**Forbidden:** open an output connection or switch to another adapter.

**Required effect:** Selecting NONE requires no output connection or Rabbit exchange.

**Verification entrypoints:** `WorkControlCompositionTest`.

**Migration status:** Current B01 NONE composition.

## RESP-WORK-AUTH-RUNTIME

**Current module(s):** `common/worker-sdk`.

AuthRuntime currently loads/validates worker auth profiles, applies auth material and coordinates token refresh through TokenStore and HTTP.

Template workers call it; shared profile/claim values live in auth-contracts. File/path and HTTP effects remain here in the current implementation.

**Forbidden:** own product auth-service identity/authorization or duplicate token storage/claim behavior.

**Required effect:** Configured auth material/refresh uses the selected TokenStore; its current path/HTTP effects remain visible for later extraction.

**Verification entrypoints:** `AuthRuntimeTest`.

**Migration status:** Mixed implementation retained; B06/B07 separate profile parsing, paths, HTTP and storage adapters. No added runtime behavior in this adoption.

## RESP-GENERATOR-WORK

**Current module(s):** `generator-service`.

GeneratorWorkerImpl owns creation of generated Work payloads and service-specific metadata from configured templates.

Scheduler/runtime supplies invocations and WorkerContext; TemplateRenderer supplies expression evaluation.

**Forbidden:** provision broker topology or decide CP lifecycle outcomes.

**Required effect:** Configured generation produces a Work item; scheduling and final publication remain runtime responsibilities.

**Verification entrypoints:** `GeneratorTest`.

**Migration status:** Current worker still imports Rabbit MessageProperties; B07 must remove transport coupling.

## RESP-MODERATOR-WORK

**Current module(s):** `moderator-service`.

ModeratorWorkerImpl owns configured traffic moderation for its Work invocation.

WorkerContext supplies accepted configuration; runtime controls intake and output publication.

**Forbidden:** become the accepted configuration writer or publish a second Work result.

**Required effect:** Configured moderation changes the intended Work flow without becoming a control configuration writer.

**Verification entrypoints:** `ModeratorTest`.

**Migration status:** Current business worker; B07 packaging remains.

## RESP-REQUEST-BUILD

**Current module(s):** `request-builder-service`.

RequestBuilderWorkerImpl owns request construction; Iso8583SchemaPackRegistry loads/caches selected ISO schema packs and J8583FieldListXmlCodec encodes their field-list XML.

Shared request/transport contracts and TemplateRenderer carry values; schema loading is a distinct local infrastructure responsibility.

**Forbidden:** execute the target transaction or create another shared Work envelope codec.

**Required effect:** A configured request and schema selection yields the shared request envelope without executing the target transaction.

**Verification entrypoints:** `RequestBuilderWorkerImplTest`.

**Migration status:** Current service still combines template/auth/schema loading; B07 separates infrastructure from request construction.

## RESP-PROCESSOR-EXECUTE

**Current module(s):** `processor-service`.

ProcessorWorkerImpl dispatches a request to ProtocolHandler; Http/Tcp/Iso8583 handlers each own their distinct protocol execution; ResponseBuilder constructs shared result envelopes.

Request/result DTOs come from work-api; protocol handlers own actual HTTP/socket effects and produce observations consumed downstream.

**Forbidden:** provision Work/CP topology or let one protocol handler reinterpret another protocol's result.

**Required effect:** A selected protocol produces its shared result envelope from observed transport effects; dispatch does not provision broker topology.

**Verification entrypoints:** `ProcessorTest`, `ProcessorTopologyProvisioningTest`.

**Migration status:** Current service contains concrete transports; B07 extraction remains. Protocol scopes are distinct, not multiple writers for one transaction.

## RESP-HTTP-SEQUENCE-WORK

**Current module(s):** `http-sequence-service`.

HttpSequenceWorkerImpl delegates the configured sequence to HttpSequenceRunner, which owns ordered step execution and current HTTP/capture behavior.

Request templates, TemplateRenderer and AuthRuntime provide their existing capabilities.

**Forbidden:** own another service's lifecycle or reimplement the shared template engine.

**Required effect:** Configured steps execute in sequence and return the observed result through the worker; renderer and auth capabilities retain their owners.

**Verification entrypoints:** `HttpSequenceRunnerTest`.

**Migration status:** Mixed HTTP/template/auth/debug-capture implementation remains B06/B07 debt.

## RESP-DB-QUERY-WORK

**Current module(s):** `db-query-service`.

DbQueryWorkerImpl delegates to DbQueryRunner with DbQueryTemplateLoader, NamedSqlParser and the selected DbStatementExecutor. JdbcDbStatementExecutor implements the JDBC execution port.

The runner uses NamedSqlParser to map SQL placeholders to ordered parameters and DbValueResolver to read payload/headers/vars paths and convert values to declared parameter types. It passes the resulting BoundSql to DbStatementExecutor and builds the Work result from query/update observations. This path does not use TemplateRenderer or a Pebble/SpEL engine.

**Forbidden:** implement a second JDBC executor or declare Work/CP topology.

**Required effect:** Resolved arguments and statements reach the executor and returned DB observations determine the worker result.

**Verification entrypoints:** `DbQueryRunnerTest`, `JdbcDbStatementExecutorPostgresTest`.

**Migration status:** Current service; executor boundary is retained and packaging remains B07.

## RESP-POSTPROCESSOR-WORK

**Current module(s):** `postprocessor-service`.

PostProcessorWorkerImpl coordinates postprocessing; TxOutcomeProjector maps incoming outcome headers to sink events and DetailedTransactionMetrics retains a bounded metrics projection.

Existing sink adapters consume the projected transaction; upstream protocol outcomes remain the evidence source.

**Forbidden:** treat metrics as domain state or infer a successful upstream transaction from sink publication.

**Required effect:** Sink events and metrics derive from received outcome evidence; unavailable-value semantics require explicit separate review.

**Verification entrypoints:** `PostProcessorTest`, `TxOutcomeProjectorTest`, `DetailedTransactionMetricsTest`.

**Migration status:** Current sink separation retained. Projection fallback/unknown-value semantics still require separate review.

## RESP-CLEARING-EXPORT

**Current module(s):** `clearing-export-service`.

ClearingExportWorkerImpl coordinates batch records; StructuredRecordProjector maps/validates record fields; ClearingExportFileAssembler renders file content/name and delegates XML formatting.

The existing batch writer owns file persistence; TemplateRenderer owns expression evaluation. Record mapping and final file assembly are distinct steps.

**Forbidden:** make rendering helpers persist files or create a second template evaluator.

**Required effect:** Projected records assemble into the configured file content/name; the existing writer performs persistence.

**Verification entrypoints:** `ClearingExportWorkerImplTest`, `ClearingExportFileAssemblerTest`, `StructuredRecordProjectorTest`.

**Migration status:** Current service composition; physical IO/core split remains B07.

## RESP-TRIGGER-WORK

**Current module(s):** `trigger-service`.

TriggerWorkerImpl owns one configured trigger action per runtime invocation.

Scheduling quota comes from TriggerSchedulePolicy through SchedulerWorkInput; HTTP/auth effects still occur in the current service implementation.

**Forbidden:** recalculate interval/single-request quota or own CP state.

**Required effect:** One runtime invocation executes the configured trigger action without recalculating scheduling quota.

**Verification entrypoints:** `TriggerWorkerImplTest`, `TriggerSchedulerIntegrationTest`.

**Migration status:** Current action/policy separation; client packaging remains B07.

## RESP-ORCHESTRATOR-INGRESS

**Current module(s):** `orchestrator-service`.

Orchestrator SwarmSignalListener dispatches canonical signals; ControllerStatusListener consumes controller observations and delegates convergence to SwarmOperationObservationHandler.

ControlPlaneCodec decodes and the dedicated CP listener factory supplies transport policy. Operation owners retain desired state/terminalization.

**Forbidden:** consume worker fan-out as another intent owner or independently terminalize operations.

**Required effect:** Decoded controller observations reach the existing observation/operation owners; deltas require their canonical full baseline.

**Verification entrypoints:** `ControllerStatusListenerTest`, `SwarmSignalListenerTest`.

**Migration status:** Only CP factory wiring changed in B01. Existing listener observation/journal logic remains C-stage thin-listener debt.

## RESP-CONTROLLER-CONTROL

**Current module(s):** `swarm-controller-service`.

SwarmControllerControlPlaneConfiguration wires controller collaborators; SwarmSignalListener dispatches to the named lifecycle/config/remove/observation handlers; SwarmLifecycleManager composes infrastructure and delegates local lifecycle to SwarmRuntimeCore.

SwarmRuntimeCore owns local runtime state; SwarmLifecycleCommandHandler, SwarmConfigUpdateHandler and SwarmRemoveCommandHandler own their command workflows. QueueStatsPort reads observations; SwarmQueueMetrics is only a Micrometer projection.

**Forbidden:** write Orchestrator desired intent or publish its public terminal operation outcome.

**Required effect:** Decoded control messages reach the named handlers; SwarmLifecycleManager delegates runtime state to SwarmRuntimeCore.

**Verification entrypoints:** `SwarmSignalListenerTest`, `SwarmLifecycleManagerTest`, `ControlTopologyOwnershipTest`.

**Migration status:** B01 isolates the CP factory. Existing listener handlers/core remain their owners; Work provisioning/stats extraction is B04 and composition cleanup is later work.

## RESP-SCENARIO-VALIDATE

**Current module(s):** `scenario-manager-service`, `tools/scenario-templating-check`.

ScenarioBundleValidator owns bundle acceptance checks; ScenarioTemplateValidator is an offline template-rendering diagnostic, not another bundle acceptance authority.

Both use the canonical template API with DisabledSequenceAccess; diagnostic rendering must not decide persisted scenario validity.

**Forbidden:** execute sequence effects during syntax checks or claim diagnostic success is bundle acceptance.

**Required effect:** Bundle acceptance uses its validator; syntax/diagnostic rendering executes no sequence effects and does not become a second acceptance path.

**Verification entrypoints:** `ScenarioRepositoryValidationTest`, `SequencePortRenderingTest`.

**Migration status:** Current syntax-port integration; startup/runtime configuration validation SSOT remains B02.

## RESP-TEST-WORK-FIXTURES

**Current module(s):** `common/work-test-fixtures`.

ControlPlaneTestFixtures owns reusable test setup for Work/CP contracts and Spring descriptors.

Consumers depend on work-test-fixtures with test scope only.

**Forbidden:** be packaged as production worker behavior or replace production codecs/validators.

**Required effect:** Production sources do not import the fixture package and production artifacts do not acquire its test dependencies.

**Verification entrypoints:** `RepositoryImportBoundaryTest`, `WorkControlCompositionTest`.

**Migration status:** Current B01 fixture extraction; Maven Enforcer excludes test-scope dependencies explicitly.
