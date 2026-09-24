# Current runtime responsibility records

> Migration direction updated 2026-09-11: the [Rabbit boundary design](work-plane-boundaries.md)
> and the repository plan `docs/inProgress/work-plane-module-boundaries.md` replace historical
> B02/B05 and later-phase sequencing in these records. Records describe existing code until
> their owners move; they do not authorize retaining duplicate Rabbit implementations.
> Update affected records with each migration. Other domain responsibilities remain effective.


Status: current ownership records, aligned with the Rabbit aggregate review corrections on
2026-09-14. B01/B02 review reports remain historical evidence in
`docs/archive/module-boundaries-before-rabbit-2026-09-11/`; they do not define current execution order.
`docs/inProgress/work-plane-module-boundaries.md` records Rabbit implementation, verification
and explicit exclusions. `docs/inProgress/functional-module-boundaries.md` owns the proposed
repair order for the remaining functionalities. The [boundary design](work-plane-boundaries.md)
owns the Rabbit boundary contract. Unrelated records below may retain historical slice labels;
those labels do not reopen completed Rabbit transfers or authorize deferred behavior changes.
This is not a complete repository SSOT certification.

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
in the applicable current plan; archived adoption reports describe their original revision. Acceptance uses
[the separate review workflow](../ai/RESPONSIBILITY_WORKFLOW.md#separate-review-task).

## RESP-WORK-ITEM

**Current module(s):** `common/work-api`.

WorkItem owns immutable payload/step history; WorkItemBuilder constructs it and WorkStep/HistoryPolicy/WorkPayloadEncoding express that model.

HistoryPolicy contains FULL (retain all recorded steps) and LATEST_ONLY (retain the
current step, reindexed to zero). These operations preserve the current payload and
its headers. The redundant DISABLED value was removed by user decision on 2026-09-16;
there is no compatibility alias. Retention operations remain unchanged; selection of
the effective policy belongs to RESP-WORK-STATE, not to the WorkItem model.

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
It captures the already parsed HistoryPolicy from WorkerState when an invocation starts.
It must not parse scenario fields or consult a separate service-level policy setting.

The executing worker's swarm and instance come exclusively from the required configured
ControlPlaneIdentity (the workerControlPlaneIdentity bean in Spring composition).
Incoming WorkItem headers describe message origin and cannot override WorkerInfo.
New steps use the executing identity; existing step authors and incoming trace context
remain unchanged. The factory has no identity-less construction path.

**Forbidden:** mutate accepted configuration, select IO implementations or provision resources.

**Required effect:** An invocation receives the selected worker's state and facilities; reading the view does not apply a configuration update.

**Verification entrypoints:** `DefaultWorkerContextFactoryTest`, `WorkerInvocationTest`; deployed producer identity in `WorkerRuntimeAcceptanceIT`.

**Migration status:** Current SDK implementation; narrower runtime ports are B03/B07.

## RESP-WORK-STATUS

**Current module(s):** `common/work-api`, `common/worker-sdk`.

WorkerStatusPublisher owns worker-provided status contributions through StatusPublisher/MutableStatus, stored with WorkerState.

Worker functions contribute data; WorkerControlPlaneRuntime builds/emits the control status projection.

**Forbidden:** publish control envelopes directly or treat contributed metrics as authoritative lifecycle state.

**Required effect:** Worker contributions appear in emitted status without overriding the runtime's reserved control state.

**Verification entrypoints:** `WorkerStatusPublisherTest`, `WorkerStatusContractTest`
(SDK → emitter → canonical codec: full/config/runtime, delta without config, next full preserves config).

**Migration status:** Current; canonical worker state is separately scoped under RESP-WORK-STATE.

## RESP-AUTH-VALUES

**Current module(s):** `common/auth-contracts`.

The moved worker-auth profile/reference/material records and enums own their shared value contracts, distinct from the product auth-service API.

Request templates and worker AuthRuntime share these values; profile file loading remains AuthRuntime behavior.

AuthType.requiredStorageMode is the sole type-to-storage policy consumed by
authored bundle findings and runtime profile preparation. It does not select or
connect a storage adapter and does not change serialized profile fields.

AuthType owns locale-independent parsing and canonical naming. Accepted kebab-case
and enum names must resolve identically regardless of the JVM default locale,
including profile serialization and preparation round trips.

**Forbidden:** load files, refresh credentials or select a storage implementation.

**Required effect:** Worker-auth consumers share profile/reference/material types without loading infrastructure from the contracts module.

**Verification entrypoints:** `AuthRuntimeTest`.

**Migration status:** Values moved in B01; worker auth parsing/runtime separation remains B06/B07.

## RESP-AUTH-TOKEN-STORE

**Current module(s):** `common/auth-contracts`, `common/redis-adapter`;
`common/worker-sdk` composes the store for AuthRuntime.

TokenStore owns the worker token storage/claim port; token keys and claim/result
values define its shared contract. `RedisTokenStore` in redis-adapter implements
that port through the shared `RedisConnections` owner (RESP-REDIS-ADAPTER).

AuthRuntime calls the selected store for cached credentials and refresh claims; profile validation is outside the store contract.

**Forbidden:** implement refresh HTTP flows or create an independent token identity/claim contract.

**Required effect:** The selected store returns scoped tokens/claims and enforces its refresh lease contract; callers do not fabricate a successful claim.

**Verification entrypoints:** `RedisTokenStoreTest`, `AuthRuntimeTest`.

**Migration status:** Port/values moved in B01; implementation and connection
ownership transferred in F01. Auth profile/refresh policy remains outside the adapter.

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

**Verification entrypoints:** `ControlPlaneCodecTest` covers the separate wire boundary; it does not verify the projection helper. Helper consumers are traced in the RV2 correction evidence in `docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b01/review-responsibilities.md`.

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

**Current modules:** `common/templating-api`, `common/templating`, `common/redis-adapter`, `common/worker-sdk`.

SequenceAccess defines next/reset; SequenceFunctions maps expression arguments.
Pebble/SpEL use the injected port. SDK ConfiguredSequenceAccess delegates to one
application-owned RedisSequenceConfiguration, which selects explicitly configured
RedisSequenceGenerator instances from redis-adapter. Worker configuration updates
validate through RedisConfigurationParser before changing that same selection.
RedisSequenceGenerator owns INCR/DEL and the sequence key prefix; SequenceFormatter
and its internal pattern/token/mode types own formatting without Redis effects.
DisabledSequenceAccess rejects effects explicitly.

**Forbidden:** process-global connection selection, another generator, hidden renderer
constructors selecting Redis, or switching from a disabled port to Redis.

**Required effect:** application configuration changes cannot redirect another
application's sequence requests. Invalid configuration leaves accepted selection intact.
Formatting, offsets, wrapping and reset retain existing behavior.

**Approved legacy exception (2026-09-22):** `redis.enabled=false` skips supplied
startup settings but sequences still use canonical RedisSequenceProperties defaults.
It does not disable sequence effects; later validated updates still apply. The human
explicitly approved preserving this behavior during extraction; it is not a general
permission to introduce defaults or fallback paths.

**Verification:** SequencePortRenderingTest, SequenceFormatterTest,
RedisSequenceConfigurationTest (including actual Redis effects and isolated selection).
**Migration status:** F01 removes the global sequence configuration/cache and hidden
configured-renderer constructors. The SDK owner closes all generators at shutdown.

## RESP-CP-COMPOSITION

**Current module(s):** `common/control-plane-spring`.

Common CP configuration wires shared codec/infrastructure; Manager and Worker auto-configurations separately own their identity/emitter/topology composition.

CP descriptors and ControlPlaneTopologyDeclarableFactory supply declarations; CP listener policy has its own record.

**Forbidden:** read Work configuration, declare Work resources or decide workload lifecycle.

**Required effect:** CP remains active for non-Rabbit/NONE Work without a fictitious Work exchange.

**Verification entrypoints:** `WorkControlCompositionTest`, `ControlPlaneWireShapeTest`.

**Migration status:** Current B01 CP/Work startup isolation.

## RESP-CP-LISTENER-POLICY

**Current module(s):** `common/control-plane-spring` for domain classification;
`common/rabbit-adapter` for listener construction and broker settlement.

ControlPlaneRabbitBindings attaches ControlPlaneFatalExceptionStrategy to each public
RabbitListenerBinding. Worker, Controller and Orchestrator composition provide resolved
queues and message callbacks. The Rabbit module registers those bindings before listener
startup and applies the CP classifier together with Spring's standard fatal transport policy.
The old CP factory and global poison customizer are deleted; services use no Rabbit annotations.

**Forbidden:** domain code constructing containers; applying CP failure policy to Work, or
Work prefetch/concurrency/executor settings to CP.

**Required effect:** A normal handler return is acknowledged. A thrown classified fatal
failure is rejected without requeue; other failures preserve configured retry behavior.
Existing CP handlers' explicit catch/drop behavior remains their current contract.

**Verification:** ControlPlaneRabbitBindingsTest, SpringRabbitControlDeliveryTest and
RabbitInboundCompositionTest verify classification, delivery, broker ACK/NACK effects,
startup registration and independent CP/Work tuning with a mocked connection. No live broker test.

**Migration status:** CP listener mechanics and separate Control/Work connections are implemented.
The Rabbit execution plan records current verification and separately requested review results.

## RESP-CP-DECLARATIONS

**Current modules:** `common/control-plane-spring` (domain projection),
`common/rabbit-adapter` (Rabbit declarations).

ControlPlaneTopologyDeclarableFactory projects canonical CP descriptors into public
RabbitTopologySpec/RabbitExchangeSpec values. RabbitDeclarations is the sole mapper
from those resource specifications to Spring AMQP declarations, used by both startup
composition and imperative resource operations. Manager/Worker composition selects
CP intent; the shared topology contracts still own existing domain routing definitions.

**Forbidden:** direct broker declarations in CP projection; choosing Work resources or
copying physical naming rules. Rabbit resource imports outside the adapter are blocked
by RepositoryImportBoundaryTest.

**Required effect:** CP declares only descriptor-derived resources, with no Work settings.

**Verification:** Manager/WorkerControlPlaneAutoConfigurationTest,
WorkControlCompositionTest, SpringRabbitResourcesTest; no full migration acceptance.

## RESP-RABBIT-RESOURCES

**Current module:** `common/rabbit-adapter`.

RabbitResources exposes declarations, bindings, queue/exchange inspection and deletion.
SpringRabbitResources performs operations; RabbitDeclarations maps immutable API specs.
Queue inspection uses passive declaration and only broker Channel.Close 404 means absent;
connection/permission failures propagate. Deletion checks absence before returning.
Counts come from the broker; oldest-message age is unavailable through passive AMQP
inspection and is explicitly absent, never guessed. Domain projections retain their
own treatment of absent queues and must not decode raw broker properties.

Controller provisioning/cleanup/statistics/control-queue verification, Orchestrator
cleanup and DebugTap resource calls now consume this API. Domain lifecycle writers,
physical naming, transport and connection composition retain their distinct recorded owners.
Legacy Node diagnostic clients and test-fixture naming are explicitly deferred for replacement/removal;
they are not pending implementation steps in this Java transfer.

**Verification:** SpringRabbitResourcesTest and existing Controller/Orchestrator behavior
suites. This resource transfer is implementation progress, not complete Rabbit SSOT acceptance.

## RESP-RABBIT-TRANSPORT

**Current module:** `common/rabbit-adapter`. RabbitPublisher/SpringRabbitPublisher and
RabbitReceiver/SpringRabbitReceiver own send and polling mechanics. RabbitMessage and
RabbitMessages own the immutable transport value and its mapping to Spring AMQP metadata.
RabbitTransportAutoConfiguration and RabbitTransportBeans compose explicitly scoped capabilities.
Listener mechanics delegate to RESP-WORK-RABBIT-POLICY; CP classification stays in
RESP-CP-LISTENER-POLICY. Connections remain under RESP-RABBIT-CONNECTION.

**Forbidden:** domain envelope encoding, route/name construction, domain outcomes,
raw client types in the public API or activation of publisher confirms during extraction.

**Required effect:** The selected plane receives unchanged bytes/metadata; publishing
retains submission-only behavior. Incoming AMQP null header values remain representable.

**Verification:** SpringRabbitTransportTest and RabbitPlaneOperationsTest.

## RESP-CP-PUBLISH

**Current module(s):** `common/control-plane-spring`.

AmqpControlPlanePublisher maps canonical CP messages to RabbitPublisher.
The rabbit-adapter module owns client publication and transport metadata conversion.

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

## RESP-RABBIT-CONNECTION

**Current module(s):** `common/rabbit-adapter` owns the immutable
`RabbitConnectionSettings` contract and `RabbitConnectionEnvironment` encoding.
The contract covers the existing container connection fields: host, port, username,
password and virtualHost. All are required; port is 1–65535. Values, including
credentials, are preserved exactly. The settings' text representation hides credentials.

`RabbitConnections` retains explicit Control and Work settings. `RabbitConnectionEnvironment`
is the sole property/environment codec: `spring.rabbitmq` belongs to Control and
`pockethive.rabbit.work` belongs to Work. Missing Work settings never inherit Control values.
`RabbitConnectionConfiguration` supplies CONTROL alone. Explicitly imported
`RabbitWorkConnectionConfiguration` supplies WORK; its configuration and the manager WorkPlane
composition are not component-scan candidates. `RabbitConnectionClients` applies
canonical fields to either client. Control uses the Boot connection lifecycle with the
module's canonical field mapper; `WorkRabbitConnection` owns a separate client lifecycle.
Work does not consume Control listener/template customizers or automatic declarations.
Worker composition activates WORK through RabbitWorkerIoCondition, which combines the
same RabbitWorkerInputCondition/RabbitWorkerOutputCondition used by the transport factories.
Both directions delegate selector comparison to WorkIoTypeParser; they do not own normalization
or validate the provider catalogue. An absent or non-Rabbit direction never requires WORK settings.

ContainerLifecycleManager and SwarmLifecycleManager/SwarmWorkerSpecFactory receive CONTROL
settings separately from the selected WorkAdapterEnvironment. ControlPlaneContainerEnvironmentFactory
requires only CONTROL and composes its encoder result with participant settings. The selected Work
owner supplies WORK connection and topology exports. Final environment validation delegates CONTROL
to the shared Rabbit decoder and WORK to its selected configuration owner;
per-worker Work connection overrides are rejected by the Rabbit owner before provisioning.
Direct per-worker Control overrides of `spring.rabbitmq`, including Spring environment aliases,
are rejected by RabbitConnectionEnvironment in Scenario validation and worker planning.
Ordinary ENV remains supported. Indirect startup overrides through SPRING_APPLICATION_JSON
or JAVA_TOOL_OPTIONS remain the explicitly accepted limitation described in
[the boundary design](work-plane-boundaries.md#accepted-cp-override-limitation); no additional
hardening is part of this transfer.

`RabbitConnectionSettings.identity()` derives an opaque broker/port/vhost/principal identity
without passwords. The scoped cleanup port exposes it and the planner includes it in the
candidate-set hash. Changing a target connection invalidates the earlier plan.

**Forbidden:** service-local connection rules, raw clients outside rabbit-adapter, Work
inheriting Control settings, credentials in settings text and plane selection by resource prefix.

**Implemented effect:** Separate connections drive Work/Control publishing and Work resource
operations. Non-default settings reach the client and export unchanged; Work listeners use
only their subscription tuning. Missing settings and stale cleanup connection identities fail
before the corresponding effects. Tests use mocked broker clients; no deployment acceptance.
The connection contract covers five fields only, not TLS or address lists. Control address
lists are rejected because they would override canonical host/port identity.

CONTROL startup now decodes only its own settings. RabbitWorkPlaneConfiguration explicitly
activates WORK for current manager composition; RabbitWorkerConnectionAutoConfiguration does
so for declared Rabbit worker IO. RabbitConnectionEnvironment owns both decoders. WORK
resources/publisher/receiver require that connection; CONTROL listener registration does not.
The shared listener facade obtains its required WORK connection only for WORK registration.
No credentials select an adapter and no WORK field inherits from CONTROL. Connection export
consumers in Controller/Orchestrator still await the neutral selected-owner transfer (R2–R4).
Verification also includes RabbitConnectionActivationTest.

## RESP-WORK-REDIS-ROUTES

**Implemented transfer (B02), pending separate review:** The Redis adapter/config module
`common/redis-config` owns decoded Redis route declarations and their parsing/validation
through RedisConfigurationParser. RedisRouteDefinition is the
single decoded shape with original Object field values retained until validation;
RedisRoute is its immutable compiled projection. Neither
opens Redis or selects a target for a message. RedisPushSupport retains ordered runtime
matching and destination selection, consuming those compiled routes.

Startup RedisOutputProperties binds declarations without a service-local route DTO or
normalizer. Its Object-valued declaration fields prevent Spring's implicit number-to-string
conversion from erasing invalid route types. Business output and RedisUploaderInterceptor call the same parser. Scenario
Manager consumes its AUTHORING validation report through WorkConfigurationFindings; it
does not implement regex or cross-field route rules.

WorkConfigBindHandler rejects unrepresentable descendants during Work decoding; it
does not decide route field types, regexes or co-constraints. Spring's standard YAML
loader, source selection and precedence remain in use.

**Open B02 decoding gap:** Spring flattens an empty optional YAML object away and an
empty list to blank text before binding. Thus `header: {}` and `header: []` can still
lose their original types. The target Work-config decoder must retain those types before
canonical validation. The global SDK YAML-loader replacement was withdrawn by human
decision; do not reintroduce it as a local route fix. Full startup/raw parity is not accepted.

The route contract allows exactly match/header/headerMatch/list. List is required text;
match or header must be nonblank; header requires headerMatch. Patterns must compile as
Java regexes. Absent routes mean an empty list; destination-presence and target-text
requirements belong to RESP-WORK-REDIS-TARGETS. Non-string fields and unknown keys
fail. Nonblank text is preserved, including significant regex whitespace.

AUTHORING uses the existing `{{ ... }}` / `{% ... %}` configuration-expression markers:
constraints for symbolic fields/whole route arrays are reported as deferred, not accepted
runtime values. Syntax validation/rendering stays with templating. RESOLVED rejects such
unrendered values. All concrete route constraints run through the same code in both modes.
WorkConfigurationFindings projects each deferred path as the canonical
`WORK_CONFIGURATION_DEFERRED` warning; only resolved validation can accept runtime values.

RedisRoutesValidation is the parser's read-only result: a complete compiled list only
when all constraints have passed, canonical problems and deferred paths otherwise.
Its isEmpty result means a known empty/absent route list, never an invalid or deferred
one. Scenario Manager derives target presence from this result and delegates the selected
Redis routes field's type check to the parser; generic capability type/expression checks
do not revalidate that field. Required-field presence remains catalogue metadata.

Co-constraints involving symbolic fields defer when rendering can make them valid.
In particular, a symbolic header with a payload matcher may render blank and therefore
does not yet require headerMatch. With no payload matcher and no headerMatch, the route
is certainly invalid regardless of how the header renders. Concrete invalid values keep
their errors even when another field is deferred. No rendering runs inside the parser.

**Forbidden:** duplicate route shape/co-constraint/regex validation or normalization in
properties, adapters or authoring; silently coerce a number/object into route text; leak
partially validated compiled routes to runtime.

**Required effect:** Startup, runtime output/capture and resolved authoring agree for the
same route declarations. A runtime parse either yields the complete ordered immutable
list or fails with canonical path/message problems. Deferred authoring requires later
resolved validation. No network/filesystem effects occur in the parser.

**Verification entrypoints:** RedisConfigurationParserTest, WorkConfigurationFindingsTest,
WorkIOConfigBinderTest, RedisWorkOutputTest, RedisUploaderInterceptorTest and ScenarioControllerTest.

**Migration status:** Route responsibility is the first implemented RedisConfigurationParser transfer;
`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b02/redis-routes-transfer.md` records behavior tests and limits.
IO/connection/execution records, output target/connection constraints,
full candidate validation and all producer migration remain required before full B02
acceptance. This sub-transfer does not certify the whole configuration contract.

## RESP-REDIS-CONNECTION-SETTINGS

The Redis adapter/config module owns decoded Redis connection validation and partial-update
merging; RedisConnectionSettings is its immutable resolved value. The current physical
`work-config.redis` placement is B02 migration debt, not generic work-config ownership.
Host is required nonblank trimmed text; port is an exact integer from 1 to 65535;
SSL is required boolean (true/false property text is decoded here). Username is optional
trimmed text; password is optional text preserved byte-for-byte, including whitespace
and an explicitly empty password. A configured username requires an explicit password.
No invalid value is clamped, converted to text or replaced by the previous/default value.
An absent patch field retains its current value; explicit null is validated as supplied.
AUTHORING defers expressions; RESOLVED rejects them. Errors do not echo credentials.

RedisSequenceConfiguration delegates candidate resolution to the parser before applying
the resolved value. WorkerControlPlaneRuntime prepares typed/private configuration first,
then validates/applies the Redis connection before writing accepted configuration, enablement or
resetting seeded template selections. A rejected Redis candidate leaves these values
and the sequence connection unchanged; subsequent commands use the last accepted config.

RedisConnectionProperties is the shared SDK bootstrap carrier; dataset/output/sequence
properties delegate to it. Dataset, output, uploader, sequence and token consumers use
the resolved contract. Scenario Manager projects the same rules for selected Work IO;
full token/sequence/capture authoring remains open. RESP-REDIS-ADAPTER owns all
Redis client/URI construction. Sequence scope and its approved bootstrap semantics
are defined by RESP-TEMPLATE-SEQUENCE; no process-global connection selection remains.

RedisConnectionEnvironmentCodec in `common/redis-config`, namespace
`io.pockethive.redis.config`, owns encoding connection candidates for
`POCKETHIVE_INPUTS_REDIS_*` and `POCKETHIVE_OUTPUTS_REDIS_*`. It preserves password
text exactly, including empty strings, and omits absent fields. Numeric values use plain
decimal text with insignificant trailing zeros removed; strings remain unchanged until
binding and canonical validation. Raw declaration types are retained for validation;
exporting a value must not make an invalid typed declaration valid. The codec also reads
explicit connection overrides without expanding placeholders and projects accepted values
into bootstrap fields. RESP-WORK-CONNECTION-ENVIRONMENT owns composition and validation.
Other Work fields, selection and token/sequence/capture scope export remain B02 work.

**Forbidden:** independent host/port/credential/SSL parsing or default substitution in
consumers; credential values in validation errors or settings toString; trimming credentials
or duplicating the Redis connection environment mapping in a launching service.
**Verification:** shared connection unit tests and existing binder, Redis adapter,
authoring and auth/sequence behavior tests. Full B02 and deployed acceptance remain open.

## RESP-WORK-REDIS-WRITE-SETTINGS

RedisConfigurationParser owns sourceStep, pushDirection and maxLen for Redis output
and enabled diagnostic capture. RedisPayloadSource (FIRST/LAST) and RedisPushDirection
(LPUSH/RPUSH) replace the enums inside RedisPushSupport. RedisWriteSettings is the
immutable resolved value consumed by the push request; RedisWriteSettingsValidation
reports problems/deferred paths and exposes settings only after complete validation.

All three fields are required. Enum text is trimmed and case-insensitive; already
typed enum values remain valid. maxLen is an exact integer in [-1, Integer.MAX_VALUE];
numeric property text is decoded at this boundary. -1 and 0 preserve the existing
unbounded-write behavior; positive values preserve the current trim operation.
AUTHORING defers symbolic fields; RESOLVED rejects unrendered values. No field gains
a default. Startup retains original bound types and delegates to this parser.

RedisOutputProperties, RedisWorkOutput (startup and merged updates), enabled
RedisUploaderInterceptor and selected Redis-output authoring consume this contract.
Scenario Manager delegates required/type/option/range checks for these three fields;
the capability catalogue remains UI metadata. Capture enablement and BEFORE/AFTER
phase remain the interceptor's distinct concern. The uploader's full authoring block
and connection fields remain in the unfinished settings/producer migration.

**Forbidden:** local enum conversion or maxLen validation for these settings in the
properties, output, capture or scenario validator; parse raw settings in RedisPushSupport.
The push adapter selects a payload and executes LPUSH/RPUSH/LTRIM using resolved settings.
**Current B02 status:** complete Redis output settings/provider composition and the runtime
RESOLVED candidate gate are implemented. Scenario complete AUTHORING projection is implemented; Controller
early complete RESOLVED validation remains; standard Spring flattening is the accepted runtime boundary
and original empty-YAML shape preservation is closed. SEL-R1 stays user-deferred.
**Verification:** RedisWriteSettingsTest, WorkIOConfigBinderTest, RedisWorkOutputTest,
RedisUploaderInterceptorTest and RedisConfigurationValidationComponentTest.
Implementation evidence: `docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b02/redis-write-settings-transfer.md`.
Separate `redis-write-settings-review-2026-09-08.md` in that directory supports this
scoped transfer; full B02 remains open.

## RESP-WORK-REDIS-OUTPUT-SETTINGS

The Redis adapter/config module owns one complete selected-output contract.
`RedisConfigurationParser` validates the selected `outputs.redis` object once and creates
one immutable `RedisOutputSettings` only when connection, write and destination settings
are all complete. The aggregate contains `RedisConnectionSettings`, `RedisWriteSettings`,
routes, `defaultList` and `targetListTemplate`; it implements the neutral
`WorkOutputSettings` marker. Its validation result combines problems and deferred paths
from RESP-REDIS-CONNECTION-SETTINGS, RESP-WORK-REDIS-WRITE-SETTINGS and
RESP-WORK-REDIS-TARGETS and never exposes a partial aggregate.

The owned field set is exactly `host`, `port`, `username`, `password`, `ssl`,
`sourceStep`, `pushDirection`, `maxLen`, `routes`, `defaultList` and
`targetListTemplate`. Unknown fields fail at this boundary. The Redis
`WorkOutputSettingsParser` provider delegates to this operation; neither work-config nor
Scenario Manager assembles these component validations independently for complete Work
candidate acceptance.

**Forbidden:** construct Redis clients, apply output settings, add defaults, or retain a
second full-output parser in SDK properties/Scenario Manager.
**Verification:** Redis complete-output parser/provider tests plus existing component,
startup and authoring tests during consumer activation.

## RESP-WORK-REDIS-TARGETS

RedisConfigurationParser owns decoded Redis output destination settings: validated routes,
optional textual defaultList and targetListTemplate, with at least one configured target.
It delegates route semantics to RESP-WORK-REDIS-ROUTES. RedisOutputTargetsValidation
exposes immutable normalized values only when errors/deferred constraints are absent.
Already compiled RedisRoute values are immutable parser products and can be reused
when a raw update leaves the route list unchanged.

Startup properties, RedisWorkOutput, enabled RedisUploaderInterceptor and Scenario
Manager consume this validation. Startup retains raw target field types until validation;
numbers/objects are rejected, optional null/blank text is absent, and nonblank target
text is trimmed once by this owner. Updates merge target fields with the current request
and validate before replacing it; a patch cannot leave a destination-less request active.

AUTHORING defers symbolic routes and defaultList values through the existing expression
handling. RESOLVED requires those fields to be rendered. targetListTemplate is deliberately
a per-message template: its nonblank text is valid in both modes and remains unrendered
until RedisPushSupport receives a WorkItem. No template parser or renderer is added to
work-config. Matching route, rendered template and explicit default precedence, and the
possibility of no destination for an individual message, remain RESP-WORK-REDIS-PUSH.

**Forbidden:** duplicate target-presence/type/normalization rules in startup, output,
capture or authoring; perform Redis IO/rendering during configuration validation;
interpret authoring deferral as an accepted runtime request.
**Current B02 status:** the runtime full-candidate gate now runs before effects, accepted
state and acknowledgement. Scenario complete AUTHORING projection is implemented; Controller early
complete RESOLVED validation remains; original empty-YAML shape preservation is not required.
**Verification:** RedisOutputTargetsTest, WorkIOConfigBinderTest, RedisWorkOutputTest,
RedisUploaderInterceptorTest and RedisConfigurationValidationComponentTest.
Implementation is followed by separate review under the active workflow.

## RESP-WORK-REDIS-SOURCES

**B02 transfer:** The Redis adapter/config module owns Redis dataset source entries and collection
validation. RedisDatasetSource replaces the SDK's nested mutable Source type. Its
constructor receives original field values, trims listName and requires the normalized
text to be nonblank, and requires a finite positive numeric weight
(numeric property text is decoded here). RedisConfigurationParser owns collection shape,
unknown fields, duplicate normalized list names and AUTHORING/RESOLVED validation.
Explicit null sources are invalid in both parser modes. A missing sources field is
represented as an empty list by the caller; runtime updates without that field leave
the existing sources unchanged. Source-mode acceptance remains separate.
It uses the same expression classification as routes; authoring defers symbolic fields
or the whole list, while resolved parsing rejects unrendered expressions.

RedisSourcesValidation exposes an immutable validated list only when no errors or
deferred paths remain. Scenario Manager projects the report and delegates the selected
sources field's generic type check to it. Source-mode co-constraints, including
deferred choices, belong to RESP-WORK-REDIS-SELECTION. RedisDataSetInputProperties and runtime raw
source updates consume the same parser. Input/output binders share WorkConfigBindHandler
for unrepresentable nested fields and unknown properties. No global YAML loader is added.

**Forbidden:** local source-entry, weight, duplicate-name or source-list shape validators
in properties, the Redis adapter or Scenario Manager; partially validated runtime lists.

**Required effect:** resolved decoded sources receive the same constraints in startup,
raw updates and authoring. Existing source order, weighted selection and destructive reads
remain owned by RedisDataSetWorkInput. RedisDatasetPickStrategy is the shared enum;
it does not perform selection or IO.

**Current B02 status:** complete Redis dataset composition, environment export and runtime
RESOLVED candidate validation are implemented. Scenario complete AUTHORING projection is implemented;
Controller early complete RESOLVED validation remains; original empty-YAML shape preservation is closed.

**Verification:** RedisSourcesParsingTest, WorkIOConfigBinderTest,
RedisDataSetWorkInputTest and RedisConfigurationValidationComponentTest.
Implementation evidence: `docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b02/redis-sources-transfer.md`;
the separate `redis-selection-review-2026-09-08.md` in the same evidence directory
accepts RS-R1/RS-R2. The subsequent selection transfer has its own open SEL-R1 finding.

## RESP-WORK-REDIS-SELECTION

RedisConfigurationParser owns the dataset choice: exactly one nonblank textual listName
or nonempty validated sources list. Single names use the shared scalar decoder in RedisDatasetSource;
numeric/object names and unrendered resolved expressions fail. RedisDatasetSelectionValidation
exposes SINGLE/MULTIPLE only for a valid concrete choice, otherwise UNRESOLVED with
problems/deferred paths. Symbolic choices defer only constraints that rendering can change;
a literal conflict between listName and a nonempty list remains an error even if weights
are symbolic. The parser delegates entry/duplicate rules to RESP-WORK-REDIS-SOURCES.

Startup properties, RedisDataSetWorkInput and Scenario Manager delegate this choice to
the parser. Startup listName binding retains Object values until validation to prevent
implicit numeric-to-text coercion, then retains the canonical name. Each input tick uses
an immutable, read-only selection projection from that parser; properties remain the
configuration holder during this stage. WorkPatchPolicy consumes the same result when restricting disabled-only
single-list updates; it retains its existing requirement that patch text is already
normalized. Catalogue required-field checks remain metadata, not mode decisions.

Raw input updates merge declared selection fields with current settings and validate
that complete selection before any property mutation. An omitted field is unchanged;
explicit null sources fail. Neither branch silently clears the other mode. A transition
must explicitly clear the previous mode and supply the new one; WorkPatchPolicy still
requires rematerialization for mode changes after bootstrap. An empty candidate with
neither source is rejected. Redis reads and selection order remain in the input adapter.

**Forbidden:** independent mode predicates/normalizers in properties, input or authoring;
automatic source-mode switching; interpreting deferred authoring as runtime acceptance.
**Current B02 status:** complete Redis settings composition and the runtime full-candidate
gate before accepted state/effects/acknowledgement are implemented. Scenario complete
AUTHORING projection and Controller early RESOLVED validation remain. Standard Spring
flattening is accepted, so empty-YAML shape preservation is not required. This selection
record alone does not certify the complete B02 change.
**Verification:** RedisDatasetSelectionTest, WorkPatchPolicyTest, WorkIOConfigBinderTest,
RedisDataSetWorkInputTest and RedisConfigurationValidationComponentTest.
**Review status:** SEL-R1 HIGH remains open and is deferred by the user; it does not
block plan continuation. See `docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b02/known-issues.md`.

## RESP-WORK-REDIS-DATASET-SETTINGS

The Redis dataset adapter/config module owns complete typed Redis dataset settings
composition. `RedisConfigurationParser` composes one `RedisDatasetSettings` value only after the
connection, source selection, pick strategy, rate and Redis timing fields are all
concrete and valid. It delegates connection rules to RESP-REDIS-CONNECTION-SETTINGS,
source/selection rules to RESP-WORK-REDIS-SOURCES and RESP-WORK-REDIS-SELECTION,
numeric rate rules to RESP-WORK-INPUT-RATE, and timing/default rules to
RESP-WORK-INPUT-SCHEDULE. `RedisDatasetSettingsValidation` aggregates their problems
and deferred paths and never exposes a partial settings value.

The declared shape is an object with only `host`, `port`, `username`, `password`,
`ssl`, `listName`, `sources`, `pickStrategy`, `ratePerSec`, `initialDelayMs` and
`tickIntervalMs`. `pickStrategy` is required and accepts the shared
`ROUND_ROBIN`/`WEIGHTED_RANDOM` enum or case-insensitive nonblank text. Omitted
`initialDelayMs` and `tickIntervalMs` use the declared Redis defaults through
InputScheduleParser; explicit null remains invalid. `listName` remains optional for
the valid multiple-sources selection mode, while `sources` is required as a concrete
list by the existing selection contract. AUTHORING reports expressions as deferred;
RESOLVED rejects them. The composition itself never invents values for an incomplete
candidate or transforms a deferred selection into a runtime value.

SDK startup properties and RedisDataSetWorkInput consume the resolved value; Scenario
Manager projects the same report. The Redis dataset adapter/config module owns Redis
dataset property names, candidate composition, environment export and bootstrap projection;
these types are physically located in `common/redis-config`.
`RedisConnectionEnvironmentCodec` and WorkConnectionEnvironmentResolver remain the sole
owners of the five connection property/environment mappings and their final projection.
Controller composes the declared dataset candidate with explicit `bee.env` overrides
before connection environment freezing; it validates the complete RedisDatasetSettings
only from the final Spring-resolved snapshot and projects those accepted values into
bootstrap. Source list entries use the same indexed Spring property form as SDK binding.
No environment rewrite follows validation. Raw runtime updates compose a candidate from the currently accepted
settings plus explicit patch values before mutation, preserving the existing SEL-R1
list-switch lifecycle. Deferred startup candidate shape remains later B02 work.

**Review status:** complete settings composition is accepted within its scoped transfer
on 2026-09-10. The corrected Redis environment export is also accepted within scope after
separate review closed its duplicate-connection-mapping finding. Whole Work candidate
acceptance, deferred SEL-R1 and full B02 remain open.

**Forbidden:** duplicate pick-strategy parsing, aggregate source/connection/rate/timing
validation in SDK or Scenario Manager, or accept an incomplete/deferred settings value.
**Verification:** RedisDatasetSettingsTest, RedisDatasetEnvironmentTest,
WorkIOConfigBinderTest, SwarmWorkerSpecFactoryTest, RedisDataSetWorkInputTest and
RedisConfigurationValidationComponentTest. Full B02 candidate acceptance remains open.

## RESP-WORK-INPUT-RATE

**B02 transfer:** `common/work-config`, `io.pockethive.work.config.input.InputRateParser`
owns parsing and validation of `ratePerSec` for SCHEDULER, CSV_DATASET and REDIS_DATASET.
The required value accepts a Number or numeric property text, is finite and >= 0,
with no upper limit. Zero pauses rate-driven dispatch. Missing, null, blank, boolean,
non-numeric, negative and non-finite values fail; errors contain the field path, not
raw input. Numeric text uses Java double parsing, identically at all consuming boundaries.
An absent patch field leaves the current setting unchanged; explicit null is invalid.

AUTHORING defers configuration expressions; RESOLVED rejects unrendered expressions.
WorkConfigurationExpressions owns the existing Work-field deferral marker check,
extracted unchanged from RedisConfigurationParser. It does not validate template syntax
or render values. InputRateValidation exposes a value only for a fully valid result.

Startup property holders retain the decoded Object for the canonical parser; their
`ratePerSec()` accessor exposes the validated double. Scheduler, CSV and Redis inputs,
WorkPatchPolicy and Scenario Manager consume the same rule. Catalogue type/range/required
checks delegate for these selected fields; catalogue descriptors remain presentation
metadata. SchedulingState carries an accepted rate as a read-only projection and does
not independently validate its range. Moderator's mode.ratePerSec/SINE settings describe a separate work-processing
limiter, not input intake; their existing validation is outside this transfer.

Controller BufferGuardCoordinator also consumes InputRateParser for the source rates of
its supported SCHEDULER/REDIS_DATASET targets. It must validate before applying guard
adjustment bounds. Invalid or absent selected rates invalidate guard configuration:
active=false, currentSettings empty and lastProblem populated. Reconfiguration must
clear previous guard settings on failure. The existing bounds-only mode for targets
without a supported rate-controlled input remains separate; it does not read a source
rate or publish rate updates to that input.

**Forbidden:** local input-rate parsers/range validators, coercing arbitrary objects to
text, silent defaults or interpreting symbolic authoring as accepted runtime settings.
**Verification:** InputRateParserTest, WorkPatchPolicyTest, WorkIOConfigBinderTest and
scenario component validation plus existing input behavior tests.
**Scope:** rate only. Timing, limits, input enablement, complete candidate acceptance and
other B02 settings remain open. SEL-R1 stays explicitly deferred. The input-rate transfer
passed separate review on 2026-09-09 after RATE-R1 correction; see
`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b02/README.md`, section
"Separate RATE-R1 correction review — 2026-09-09".

## RESP-WORK-INPUT-SCHEDULE

**B02 transfer accepted within scope on 2026-09-10 after TIM-R1 correction:** `common/work-config`, `input.InputScheduleField` defines
the timing/limit fields and `InputScheduleParser` owns their exact integer parsing and
range validation. Startup properties, input adapters, WorkPatchPolicy and Scenario
Manager consume that owner. Full B02 candidate/settings acceptance remains open.

| Field | Inputs | Accepted range / meaning |
|---|---|---|
| initialDelayMs | Scheduler, Redis | 0..floor(Long.MAX_VALUE / 1,000,000) milliseconds |
| tickIntervalMs | Scheduler, Redis, CSV | 100..floor(Long.MAX_VALUE / 1,000,000) milliseconds |
| maxPendingTicks | Scheduler | 1..Integer.MAX_VALUE; currently a bound property without an execution consumer |
| maxMessages | Scheduler | 0..Long.MAX_VALUE; 0 is unlimited, changing the limit resets the dispatched counter |
| startupDelaySeconds | CSV | 0..floor(Long.MAX_VALUE / 1,000,000,000); conversion to milliseconds belongs to the parser |

Numbers and numeric property text must represent an exact integer within the field's
range. Fractions, overflow, null, blank, boolean, non-finite and structured values fail;
no truncation, saturation or clamping is allowed. Errors expose the field path, not raw
input. AUTHORING defers expressions using WorkConfigurationExpressions; RESOLVED rejects
unrendered expressions. InputScheduleValidation exposes only fully valid values.
Duration bounds prevent both CSV seconds-to-milliseconds overflow and silent saturation
when ScheduledExecutorService converts milliseconds to its nanosecond clock.

Omitted Scheduler/Redis timing fields keep the existing defaults: initialDelayMs=0,
tickIntervalMs=1000; omitted Scheduler maxPendingTicks=1. InputScheduleParser.initialValue
is their sole definition; declaredValue distinguishes omission from explicit null.
These are configuration defaults, never repairs for a declared invalid value. CSV timings
and scheduler maxMessages remain required. Startup and authoring consume the same omission
policy; omitted patch fields preserve accepted values. No new runtime effect is added for the unused maxPendingTicks knob;
execution/state ownership is B03. Existing live-mutability classification stays with
WorkPatchPolicy: only maxMessages is live-mutable among these fields.

TIM-R1 semantics, retained by F02: SchedulerRunState resolves initial maxMessages
from canonical startup settings once and owns its runtime projection. Valid updates
replace it only after rate/limit/reset pass canonical validation; they do not rewrite
the startup declaration. Ticks and diagnostics consume that projection without
parsing configuration. WorkerState remains the accepted-configuration writer.

**Forbidden:** local timing/limit decoders or range repair, accepting a rejected setting,
or presenting metadata validation as an implemented scheduling/backlog effect.
**Verification:** parser boundary/error tests, startup binding, scenario diagnostics and
finite-run scheduling behavior. Full candidate acceptance and other IO settings remain B02.

## RESP-WORK-SCHEDULER-RESET

**B02 transfer accepted within scope on 2026-09-10; current owner:**
`common/work-local-config`, `io.pockethive.work.local.scheduler.SchedulerResetParser` owns
the `inputs.scheduler.reset` value contract. WorkPatchPolicy, SchedulerRunState and
Scenario Manager consume it; the runtime's separate string decoder is removed.

A declared value must be a boolean: true requests a finite-run counter reset, false
does not. Omission requests no reset; explicit null, numbers, text (including "true"
and "false") and structures fail. AUTHORING defers expressions through the existing
WorkConfigurationExpressions contract; RESOLVED rejects them. Diagnostics expose paths,
not raw values. Scheduler validates reset with rate/limit before mutating any of them.
Changing maxMessages continues to reset the counter independently of this flag.

The flag belongs to raw scheduler configuration commands, not startup scheduling
properties. Bootstrap input binding does not add a reset environment property. Counter
execution and delivery/replay semantics remain with the current scheduler and future
B03 state work; this parsing transfer does not claim an exactly-once command protocol.

**Forbidden:** local reset boolean/string decoders, ignoring invalid declared reset
values, or partial rate/limit mutation before reset validation succeeds.
**Verification:** parser boundary tests, patch-policy tests, finite-run behavior and
scenario diagnostics. Full candidate acceptance remains B02 work.

## RESP-WORK-INPUT-LIFECYCLE-POLICY

**B02 transfer accepted within scope on 2026-09-10:** `work-config.policy.InputLifecyclePolicy` owns the removed
input-control paths and their canonical startup property spellings: `inputs.*.enabled`
for Rabbit/Scheduler/Redis/CSV, and Rabbit `autoStartup`. Any declared value is invalid,
including false, null and expressions; this is a field-presence rule, not a bool parser.
Use worker-level control enablement instead. No compatibility translation is allowed.

The policy checks supplied raw configuration and property-presence predicates without
reading process environment itself. WorkPatchPolicy, Scenario Manager, SDK binding and
Controller worker planning consume its errors. Existing Spring binding supplies property
lookup; it remains the naming/placeholder owner. Authoring raw-config diagnostics and
runtime/planner property checks do not claim full candidate or bee.env authoring validation.

ENBL-R1 correction: startup presence is supplied by SDK InputLifecyclePropertyCheck,
a Spring BindHandler that checks exact properties and present descendants in the
binding context's sources. It skips value binding entirely, including placeholder
expansion, and checks all removed input paths before selected settings binding.
The policy consumes a presence predicate; Controller supplies presence from its raw
environment lookup. Empty YAML objects already erased by the loader remain separate
startup-shape debt; nonempty indexed/nested declarations must be rejected.

**Forbidden:** adapter-local enablement settings, duplicate removed-field lists in services,
silently ignoring the fields or overriding worker desired state from input settings.
**Verification:** policy/binder rejection, worker-update rejection, Redis intake following
state snapshots, scenario validation and planner rejection before worker provisioning.

## RESP-WORK-IO-CONFIG

**Current module(s):** `common/work-config` for neutral binding contracts/descriptors;
`common/rabbit-adapter` for Rabbit properties/providers; `common/worker-sdk` for generic
Spring binding, discovery and existing local IO properties.

WorkInputConfig and WorkOutputConfig expose validation and read-only status routes.
WorkInputConfigProvider/WorkOutputConfigProvider declare the binding type supplied by an
adapter. WorkIoConfigurationCatalog requires exactly one descriptor per selected direction.
WorkerDefinitionDiscovery binds that class and consumes its route projection; it no longer
switches on Rabbit properties. RabbitInputProperties/RabbitOutputProperties retain their typed
fields, Rabbit-owned defaults and canonical parser delegation in `io.pockethive.rabbit.work`.
WorkIoBindingConfiguration declares the SDK-owned Scheduler/CSV/Redis/NONE bindings.
PocketHiveWorkerProperties holds worker business configuration binding. It no longer
contains a separate history-policy value; accepted runtime policy belongs to RESP-WORK-STATE.
WorkIoType carries the declared IO name/settings key. Existing enums implement this contract;
test composition can explicitly supply its own type. WorkIoTypeParser owns boundary name
normalization and rejects absent/ambiguous definitions. Startup type properties retain raw
text for that parser; adapter field binding/defaults remain unchanged.
Rabbit bootstrap conditions use WorkIoTypeParser.matches for the same normalized identity;
WorkIoConfigurationCatalog retains complete declared-type validation, including ambiguity rejection.

WorkInputConfigBinder/WorkOutputConfigBinder remain the startup Spring boundary, with
WorkConfigBindHandler rejecting unrepresentable or unknown fields. Rabbit environment values
are already exported from canonically validated settings; ordinary Spring type restoration
is not a second parser authority. RESP-WORK-INPUT-LIFECYCLE-POLICY rejects unsupported input
enablement; input rates, scheduling and CSV settings retain their existing canonical owners.
WorkerControlPlaneRuntime validates complete candidate settings through WorkConfigurationParser
before accepting state. No startup conversion/ACK/requeue behavior is changed by this extraction.

**Forbidden:** adapter-class switches in neutral discovery, duplicate settings rules, missing
or ambiguous binding descriptors, settings objects that open connections.

**Required effect:** selected settings and their status projection reach the chosen transport;
invalid settings or selection fail before input registration/publication. Rejected runtime
updates retain the last accepted worker state.

**Verification:** WorkIOConfigBinderTest, PocketHiveWorkerIoFromConfigTest,
MessageWorkInputFactoryTest, WorkOutputRegistryInitializerTest.

## RESP-WORK-ADAPTER-SELECTION

**Current module(s):** `common/worker-sdk`; neutral selection/IO types in `common/work-config`; deployment inventory in `common/work-config-composition`.

WorkInputRegistryInitializer selects one input factory; WorkOutputRegistryInitializer selects one output factory. Each owns its distinct direction; WorkOutputRegistry retains the selected outputs and dispatches publication.

SDK composition supplies available factories and bound definitions. Neutral
WorkInputTransportFactory/WorkOutputTransportFactory providers in work-api receive adapter
configuration without WorkerDefinition. MessageWorkInputFactory and TransportWorkOutputFactory
wrap these providers for the existing registries; Rabbit factory implementations live in
rabbit-adapter. Local input/Redis output factories retain their existing SDK composition.
NONE is an explicit output implementation.

`WorkInput` is the SDK composition lifecycle handle (start/stop/close), consumed by
WorkInputLifecycle. Control updates reach input coordinators through their existing
registered WorkerControlPlaneRuntime listeners; they are not a second lifecycle
callback on WorkInput. The unused snapshot-typed update method was removed in F02.
Factory WorkerDefinition parameters stay inside SDK composition; local execution
owners consume canonical settings and the existing neutral scheduling policy port.

WorkPlaneSelection owns the explicit pockethive.work.type / POCKETHIVE_WORK_TYPE
bootstrap projection; CurrentWorkPlaneSelection declares the current deployable
WorkPlane inventory using the existing adapter identities. One deployment selects
Rabbit or Artemis; no per-swarm registry is introduced. Adapter connection ENV
includes that owner's selection and is passed through existing provisioning.

**Forbidden:** choose by ordering, suppress missing factories or independently reopen adapter selection at dispatch.

**Required effect:** Each direction has exactly one matching factory; missing and duplicate matches fail, including NONE cases.

**Verification entrypoints:** `WorkInputRegistryInitializerTest`, `WorkOutputRegistryInitializerTest`; source review against the selection contract. `WorkControlCompositionTest` covers only Scheduler/NONE startup, not factory rejection.

**Migration status:** Current exact-match IO selection and A3 explicit deployment WorkPlane selection.

## RESP-WORK-STATE

**Current module(s):** `common/worker-sdk`.

WorkerControlPlaneRuntime owns accepted worker control updates over WorkerState; WorkerControlQueueListener receives/dispatches CP messages. WorkerState also stores invocation counters and status contributions with separate callers.

State snapshots feed inputs and WorkerContext; counters and contributed status are not additional configuration writers.
The current command execution assumptions are defined in
[Worker CONTROL command execution](../ARCHITECTURE.md#worker-control-command-execution).
Workers start disabled in WorkerState and input registration receives that state before
intake. Only accepted worker-level control enablement updates may enable intake;
input properties and container environment must not provide a second enablement flag.
ControlPlaneNotifier derives results and applied configuration digests from accepted raw
state. Configuration logs and external status views consume RESP-WORK-CONFIGURATION-DIAGNOSTICS;
redaction must not change the state, adapter view or digest.

When an accepted-state candidate contains either Work root, WorkerControlPlaneRuntime
must compose and validate one complete IO candidate before typed conversion, adapter
effects, reseeding, accepted-state writes or ready acknowledgement. Composition uses the
immutable WorkerDefinition selection, the neutral selected-input startup snapshot and
the previously accepted/raw patch merge; it may materialize the explicit NONE output.
It never invents a non-NONE adapter settings block. The parsed input/output selections
must match WorkerDefinition. Problems or deferred RESOLVED paths reject the command and
preserve state/listener-visible configuration. A candidate containing only non-Work roots
does not invoke the Work parser and passes through this boundary unchanged.

WorkerRuntimeConfiguration owns parsing the common runtime field `config.historyPolicy`
from the complete merged worker configuration. It accepts the exact HistoryPolicy names
FULL and LATEST_ONLY, defaults an absent field to FULL, and rejects invalid values before
any accepted-state write, enablement, reseeding or ready result. Explicit runtime fields
in an incoming patch pass through the same policy parser before general null filtering;
`historyPolicy: null` is invalid, not an omitted field. Rejected candidates
never reach listener-visible configuration; the existing failure notification may
republish the previously accepted snapshot.
ConfigMerger builds that immutable candidate; WorkerControlPlaneRuntime remains the
accepted-state writer. WorkerState stores the raw map and its parsed policy together;
the latter is a read-only derivation, never independently writable. Partial updates
preserve an accepted policy; explicit worker-config reset returns to the absent-field
default. Each invocation retains the policy captured when its context was created.
The former `pockethive.worker.history-policy` property and startup-bean selection are
removed without a compatibility path. Worker property beans must not maintain a
second effective-policy value or default outside accepted configuration.

**Forbidden:** let a listener introduce its own configuration state machine or infer control success from attempted Work effects.

**Required effect:** Accepted control updates reach the worker state and its snapshots; one accepted revision/state owner must survive B03 extraction.

**Verification entrypoints:** `WorkerControlPlaneRuntimeTest`, `WorkerStateTest`,
`WorkerHistoryPolicyTest`, `WorkerRuntimeConfigurationTest`; real retained steps in `WorkerRuntimeAcceptanceIT`.

**Migration status:** Current implementation mixes control update, status and configuration concerns. B02/B03 separate them; this record does not certify that separation.

## RESP-WORK-INVOCATION

**Current module(s):** `common/worker-sdk`.

DefaultWorkerRuntime selects WorkerInvocation and routes its non-null result to WorkOutputRegistry; WorkerInvocation executes the function/interceptor chain. WorkMessageDispatcher is the transport-independent dispatch hook.

Input adapters dispatch through WorkerRuntime; invocation context carries values, not control authority.

**Forbidden:** reimplement service business logic or introduce a second output publication for the same result.

**Required effect:** A named dispatch invokes the selected function/interceptors and publishes its result through the selected output once on the normal SDK path.

**Verification entrypoints:** `DefaultWorkerRuntimeTest`, `MessageWorkInputTest`.

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
Its rate is supplied from RESP-WORK-INPUT-RATE accepted settings; the projection does not
revalidate that setting.

SchedulerWorkInput delivers updates; RateSchedulePolicy and TriggerSchedulePolicy implement distinct rate versus trigger policies.

**Forbidden:** read Control Plane directly, execute work or make the projection a configuration writer.

**Required effect:** Ordered updates reach the policy without consuming quota; plan consumes quota using monotonic tick time.

**Verification entrypoints:** `RateSchedulePolicyTest`, `TriggerSchedulePolicyTest`.

**Migration status:** Current B01 port; updates and planning serialize in each policy.

## RESP-WORK-SCHEDULER-SETTINGS

**B02 implemented; provider extraction in progress:**
`common/work-local-config`, namespace `io.pockethive.work.local.scheduler`, owns the
`SchedulerSettingsParser` and
complete five-field scheduler settings contract. It delegates rate and integer constraints
and omission defaults to RESP-WORK-INPUT-RATE and RESP-WORK-INPUT-SCHEDULE. Required
ratePerSec/maxMessages and explicit null rejection remain unchanged. Unknown fields fail.
The optional reset command delegates RESP-WORK-SCHEDULER-RESET and is never retained in
the immutable settings value. AUTHORING defers expressions; RESOLVED requires valid values.
`SchedulerSettingsEnvironment` owns the canonical five-field property/environment mapping:
it composes declared values with Spring's raw `bee.env` lookup, exports the candidate before
the complete worker environment is frozen, then validates the final Spring-bound values with
this parser and projects the accepted settings into bootstrap. Declared raw types and explicit
null remain in the candidate until validation. The three omitted timing fields use the existing
shared defaults, including when another exported property references one of those defaults.
`reset` is a control-plane command: it is validated by this parser and retained in bootstrap,
but has no startup environment property or environment override. SDK raw properties and Scenario
Manager consume this parser; scheduler construction takes one immutable startup snapshot. Runtime rate/max/reset controls continue using their
canonical field parsers and the existing scheduling/counter policy, without mutating the
Spring property carrier. Controller worker planning consumes the codec before
RESP-WORK-CONNECTION-ENVIRONMENT freezes the environment. Runtime full Work candidate
validation is implemented; Scenario complete AUTHORING projection is implemented; Controller early
complete RESOLVED validation remains B02 work.

**Forbidden:** duplicate numeric/default/reset rules, schedule work or own worker state.
**Verification:** parser behavior tests, existing Spring binder, authoring and scheduler
runtime tests; no new boundary scanner or wiring tests.

## RESP-WORK-SCHEDULE-INPUT

**Current module(s):** `common/worker-sdk`.

`SchedulerWorkInput` owns timed intake and dispatch; its factory/builder wire the
selected `ScheduledInvocationPolicy` and callbacks. It projects worker snapshots
into ordered `SchedulingState` revisions and invokes the policy's update/plan port.
Runtime controls and finite-run accounting delegate to RESP-WORK-SCHEDULER-RUN.
Its builder consumes validated timing without local defaults or clamping.

A tick obtains policy quota before applying the current run limit. Seed creation
precedes counting; counting precedes dispatch. Worker/result-handler failures
continue through the existing error callback and never undo the count or retry.
The SDK maps the owner's remaining value to the existing WorkItem header and
forwards its diagnostic projection. It does not own a second counter/limit.

**Forbidden:** reimplement rate/trigger rules, finite-run/reset arithmetic or
select a policy by worker role.

**Required effect:** Each state revision reaches the policy before a subsequent
tick; finite-run intake dispatches through WorkerRuntime with unchanged accounting.

**Verification entrypoints:** `WorkControlCompositionTest`,
`TriggerSchedulerIntegrationTest`, `SchedulerWorkInputTest`.

**Migration status:** F02 run accounting and rate policy extracted; SDK retains
worker composition, snapshot projection, clock and execution.

## RESP-WORK-SCHEDULER-RUN

**Current module(s):** `common/work-local`.

`SchedulerRunState` owns the runtime projection of rate/maxMessages controls and
finite-run dispatch count. Startup consumes canonical `SchedulerSettings`. Raw
runtime controls delegate to the existing rate/integer/reset field parsers before
any field or count changes. WorkerState remains the accepted-config writer; this
projection never mutates the Spring property carrier or accepted worker state.

Changing maxMessages or explicit reset=true clears the count; repeated unchanged
limits, reset=false and enablement alone do not. A zero limit remains unlimited.
Quota clipping, per-dispatch remaining and diagnostic fields are derived here.
A tick captures its diagnostic limit before dispatch, while each dispatch samples
the then-current limit before incrementing. This preserves the existing behavior
when a config update arrives during dispatch; no new whole-tick lock is introduced.
Control updates are serialized by the SDK's projection lock; this API does not
promise atomicity between config updates and a whole dispatch batch.

**Forbidden:** own accepted worker configuration, reimplement field parsing, read
Control Plane/Worker SDK, run timers, build seeds or dispatch work.

**Verification entrypoints:** `SchedulerRunStateTest`, `SchedulerWorkInputTest`.

## RESP-WORK-RATE-POLICY

**Current module(s):** `common/work-local`.

`RateSchedulePolicy` implements the existing ScheduledInvocationPolicy port and
owns fractional rate quota accumulation and reset on disabled revisions.
SchedulerWorkInput supplies monotonic tick time and ordered SchedulingState updates.
The existing quota is per policy tick; this extraction does not reinterpret it as
elapsed-time compensation or change non-default tick interval behavior.

**Forbidden:** read CP, mutate settings or dispatch messages.

**Required effect:** Fractional quotas accumulate at the configured rate and disabled
updates reset carry, including between ticks.

**Verification entrypoints:** `RateSchedulePolicyTest`.

**Migration status:** F02 rate policy moved out of SDK without a compatibility copy.

## RESP-TRIGGER-POLICY

**Current module(s):** `trigger-service`.

TriggerSchedulePolicy owns interval/single-request pending quota; TriggerSchedulingConfiguration selects it as the service scheduling policy.

SchedulerWorkInput delivers revisions even between ticks; TriggerWorkerImpl executes the subsequently dispatched action.

**Forbidden:** read CP directly, select IO adapters or execute trigger actions.

**Required effect:** A true-to-false single-request update between ticks is retained until consumed; disabled/re-enabled behavior preserves the documented pending request.

**Verification entrypoints:** `TriggerSchedulePolicyTest`, `TriggerSchedulerIntegrationTest`.

**Migration status:** Current B01 policy; pending true-to-false transitions must survive until consumed.

## RESP-WORK-RABBIT-POLICY

**Current module(s):** `common/rabbit-adapter`, internal `SpringRabbitListeners`.

The module owns Work listener containers and their virtual-thread executor.
Work uses AUTO acknowledgement on callback return after SDK executor admission.
Only WorkNotAcceptedException maps to native requeue; accepted-task failures never reach settlement.
The SDK supplies
validated RabbitSubscription values and applies desired state through RabbitListeners.
Prefetch, fixed consumer count, exclusive and explicit startup intent reach the container.
CP bindings use the same Rabbit implementation with separately configured factories and domain failure policy.

**Forbidden:** mutate CP listener policy or close CP executors; expose raw listener containers.

**Required effect:** Selected Work tuning controls actual broker consumers. Exclusive requires
one consumer and is rejected by canonical settings validation before registration.

**Verification:** SpringRabbitListenersTest exercises real containers against a mocked broker
client and observes prefetch, consumer count, exclusivity and start/stop. No live broker test.

**Migration status:** Work listener mechanics and separate plane connections are implemented.

## RESP-WORK-RABBIT-SETTINGS

`common/rabbit-adapter` owns selected Work Rabbit settings and the neutral direction-specific
parser providers. RESOLVED `RabbitInputSettings` contains queue, prefetch,
concurrentConsumers and exclusive; RESOLVED `RabbitOutputSettings` contains exchange,
routingKey, persistent and publisherConfirms. Scenario AUTHORING uses distinct immutable
tuning projections containing only prefetch/concurrentConsumers/exclusive or
persistent/publisherConfirms. All implement their direction's neutral settings marker.
Connection/credential settings remain the separate RESP-RABBIT-CONNECTION contract and are
not repeated in either selected block.

The selected input/output block is required in RESOLVED mode. Queue, exchange and routingKey
are required nonblank text and normalized by trimming once. In AUTHORING the neutral parser
passes an omitted settings block as an empty map to the explicitly selected provider; the
provider decides required fields. Rabbit's empty map means canonical tuning defaults,
not an inferred adapter or recovered YAML object. The canonical defaults preserve current behavior:
prefetch=50, concurrentConsumers=1, exclusive=false, persistent=true and
publisherConfirms=false. Prefetch and concurrentConsumers must be positive exact 32-bit
integers; exclusive requires concurrentConsumers=1. Boolean fields accept booleans or exact case-insensitive boolean property text.
Unknown fields fail. `deadLetterQueue`, input `enabled` and `autoStartup` are rejected;
the latter two are also rejected by RESP-WORK-INPUT-LIFECYCLE-POLICY.

AUTHORING validates and normalizes only tuning fields. Rabbit queue, exchange and routingKey
must not appear in a scenario and are neither required nor deferred in AUTHORING. Symbolic
tuning values are deferred; concrete valid tuning produces a complete authoring projection.
RESOLVED requires physical destinations and rejects unresolved expressions. Rabbit
input/output mutation providers expose no live mutable fields. SDK properties delegate value rules to this module and expose resolved snapshots. Work input
consumes prefetch/concurrency/exclusive through RabbitListeners; output captures destination and
persistence. `publisherConfirms` remains represented and validated but inactive, preserving
the pre-extraction behavior. No confirmation wait or timeout field is introduced.

`RabbitWorkSettingsBootstrap` is the adapter-owned pure projection used by Controller
candidate composition. For a selected Rabbit direction it combines the optional declared
adapter-tuning object with explicit topology-resolved queue or exchange/routingKey values.
Omitted tuning is passed as an empty map; explicit null is invalid. It
then delegates RESOLVED validation and emits the complete normalized settings block.
Topology values are mandatory inputs and always own destination identity; the projection
does not infer an adapter, reconstruct names, inspect `Work`, read environment or fall
back to another transport. Scenario Manager never calls this physical-topology projection;
it builds only the AUTHORING selection+tuning candidate.

**Forbidden:** infer topology from worker role, read Spring/environment, open a Rabbit
client, duplicate defaults/coercion in SDK properties or treat accepted settings as
evidence that the broker applied them.
**Verification:** Rabbit settings/parser/provider/bootstrap tests and existing binding/composition
tests during consumer migration; delivery effects remain V09.
`RabbitWorkEnvironment` exports topology and tuning from the materialized settings map.
Legacy application-placeholder environment keys remain explicit exports of that same map;
they are not another destination authority. Competing Rabbit settings in bee.env (including
Spring aliases) fail; tuning belongs in config, destinations in the topology owner. Connection
environment overrides remain the separate connection contract.

**Migration status:** rabbit-config absorbed into rabbit-adapter; public configuration providers
exposed through RabbitConfiguration. Properties-local normalization/validation removed; scalar
rules shared by parsers and snapshots. Aggregate review pending.

## RESP-WORK-TRANSPORT

**Current module(s):** `common/work-api` for channel/delivery/output contracts; `common/worker-sdk` for execution and state integration.

WorkInputChannel exposes an already configured subscription without broker types or WorkerDefinition.
WorkDeliveryHandler separates decoded delivery from decode failure reporting. MessageWorkInput
applies accepted enabled state and max-in-flight configuration; MessageWorkExecution owns the
dispatch through MessageWorkExecutor and error reporting for every concurrency limit. It uses WorkMessageDispatcher;
the redundant RabbitWorkDispatcher is removed. WorkOutput accepts only a WorkItem, with the
selected target already captured by its instance. DefaultWorkerRuntime remains the sole result
publication path through WorkOutputRegistry. Local scheduled WorkInput lifecycle is unchanged.
For the application callers and ordering of enable/disable callbacks, see
[Worker CONTROL command execution](../ARCHITECTURE.md#worker-control-command-execution).

**Forbidden:** broker-specific state in this seam, a second dispatcher/publication path,
inline worker dispatch, retry of accepted work, completion-based ACK or a drain policy.

**Required effect:** the same SDK execution path accepts input from Rabbit, Artemis or a test-only stateful
in-memory channel; disabled workers return null, worker/decode failures are reported and swallowed,
and successful admission returns without waiting for task completion, even at maxInFlight=1.
WorkNotAcceptedException is the neutral not-submitted outcome, not a worker failure.
MessageWorkExecutor owns capacity, pause/resume and executor lifetime. Pausing wakes
capacity waiters before channel stop; accepted tasks are not cancelled. Core executor
threads remain alive while idle to preserve PER_THREAD resources; pool dimensions
are a projection of the single admission limit. MessageWorkInput records desired
state under a short lock distinct from serialized transport start/stop, so disable
can pause admission even during synchronous channel start. Close prevents subsequent
enablement. Its canonical
policy is the human-approved correction in work-plane-boundaries.md, 2026-09-15.

**Verification:** MessageWorkExecutorTest, MessageWorkExecutionTest, MessageWorkInputTest,
ArtemisWorkAdmissionTest, RabbitWorkAdmissionTest and DefaultWorkerRuntimeTest.
Admission component tests use the real SDK path with an embedded Artemis broker or
the real Spring Rabbit listener backed by a mocked AMQP client, respectively.

The test-only InMemoryWorkTransport indexes explicit single-process resources;
InMemoryWorkChannel owns each resource's pending items, listener state and removal.
Concurrent publication, intake and lifecycle operations must preserve that state. A handler
runs outside resource/index locks; taking an item from pending reserves a delivery,
while the handler owns execution admission. Already admitted work may finish after stop/removal. Removing a stopped resource discards
pending items and invalidates its input/output handles, including after address reuse.
A delivery rejected before SDK admission is restored to pending without a retry loop.
The fixture does not retry failures of accepted work, cancel them or wait for them to finish.
InMemoryWorkTransportTest verifies these effects through its public API.

## RESP-WORK-RABBIT-TRANSPORT

**Current module(s):** `common/rabbit-adapter`, package `io.pockethive.rabbit.work`.

RabbitWorkInputChannel registers resolved RabbitInputSettings via RabbitListeners, unwraps the
message body and calls RabbitWorkItemConverter/WorkItemJsonCodec. It reports decode failure to
the neutral handler with original bytes; AMQP headers remain ignored. RabbitWorkOutput alone
assembles outgoing Work Rabbit messages using the canonical codec and immutable
RabbitOutputSettings, then calls RabbitPublisher.send. Neither implementation depends on SDK
worker definitions or control snapshots. RabbitWorkInputFactory/RabbitWorkOutputFactory consume the neutral binding contracts; SDK wraps them through transport factory ports.

**Forbidden:** second result publisher, alternative envelope codec, raw client access outside
rabbit-adapter, mutable output destination or an SDK dependency from rabbit-adapter.

**Required effect:** Work envelopes preserve their canonical format; callback-return AUTO ACK
follows successful executor admission at every limit. Only explicit not-submitted
admission is returned to the broker. publisherConfirms remains represented and inactive;
CONTROL and accepted-work failure policy are unchanged.

**Verification:** MessageWorkInputFactoryTest, RabbitWorkItemConverterTest, RabbitWorkOutputTest,
SpringRabbitTransportTest and SpringRabbitListenersTest.

## RESP-WORK-CSV-SETTINGS

**B02 implemented; provider extraction in progress:** `common/work-local-config`,
namespace `io.pockethive.work.local.csv`, owns `CsvDatasetParser` and complete CSV settings
validation and partial-update merging into immutable `CsvDatasetSettings`. All eight
fields are required: filePath, ratePerSec, rotate, skipHeader, delimiter, charset,
startupDelaySeconds and tickIntervalMs. Unknown fields and explicit null fail. FilePath
is nonblank text representing a path, without filesystem checks or implicit root changes.
Delimiter preserves the existing Java regex split contract (nonblank, valid pattern);
quoted CSV parsing is not introduced. Charset must name a JVM-supported charset. Rotate
and skipHeader accept booleans or exact lowercase property text true/false; other strings
and coercions fail. Rate and timing delegate existing canonical parsers. AUTHORING defers
expressions, RESOLVED requires rendered values; no partially valid settings are exposed.

SDK properties carry raw values until parsing, runtime consumes only resolved settings,
and Scenario Manager projects the same complete validation instead of catalogue rules.
CSV input construction registers its validated startup settings once with WorkerState,
through WorkerControlPlaneRuntime. This immutable startup baseline is distinct from the
CP patch journal; it never changes on control updates or restart. WorkPatchPolicy builds
the candidate in explicit order: startup baseline, accepted CP fields, supplied patch.
The input uses the same parser merge semantics for its read-only settings projection.
The CSV mutation provider validates a supplied selected CSV candidate before accepted-state writes;
complete Work candidate/startup integration remains the larger B02 gate.

CsvDatasetEnvironment owns CSV property/environment names and export/projection. Controller
composes CSV declarations with explicit bee.env overrides before connection environment
freezing; final CSV validation uses that same frozen Spring-resolved environment and
preserves declared scalar types unless explicitly overridden. Bootstrap receives the
accepted CSV values. No environment rewrite follows validation; no process reads.

**Forbidden:** local CSV settings parsers/boolean coercion, per-tick settings decoding,
filesystem access in work-config, silent omission defaults or partial updates on rejection.
**Verification:** parser unit tests, existing binder/scenario/Controller suites and CSV
record/rotation/disable behavior. Full B02 acceptance and phase simplification are later.

## RESP-WORK-CSV-INPUT

**Current module(s):** `common/worker-sdk`.

`CsvDataSetWorkInput` owns CSV intake lifecycle, control-state subscription, rate
planning, WorkItem metadata and dispatch through WorkerRuntime. It consumes
RESP-WORK-CSV-SETTINGS for its read-only resolved settings projection; bootstrap
and raw updates delegate parsing before replacement. Accepted configuration stays
with WorkerState. Timing/rate validation and seconds-to-milliseconds conversion
remain with RESP-WORK-INPUT-SCHEDULE and RESP-WORK-INPUT-RATE.

Dataset loading, formatting and cursor operations delegate to
RESP-WORK-CSV-DATASET. Loading remains lazy on enablement. Disable/re-enable does
not reload the file or reset the cursor; stop/start reloads the file without
resetting the cursor. Rate updates do not reload data. Existing patch policy still
requires rematerialization for CSV source/format/timing changes.

**Forbidden:** read/split/format dataset files, maintain a second dataset cursor,
declare broker resources or own accepted worker configuration.

**Required effect:** Configured records reach WorkerRuntime in order, with unchanged
CSV headers, rate, enablement and exhaustion behavior.

**Verification entrypoints:** `CsvDataSetWorkInputTest`.

**Migration status:** F02 CSV dataset mechanics extracted. Scheduler extraction is
described separately under RESP-WORK-SCHEDULE-INPUT; SDK retains input composition.

## RESP-WORK-CSV-DATASET

**Current module(s):** `common/work-local`.

`CsvDatasetCursor` is the sole owner of loaded CSV rows, JSON record formatting and
cursor movement. Its API accepts canonical `CsvDatasetSettings` from
`common/work-local-config`; it does not parse configuration. The SDK consumes its
row index, JSON and read-only size/position/remaining projections.

The reader preserves the existing charset and regex-delimiter contract, skips blank
lines, retains trailing empty fields and trims JSON field names/values. Headerless
rows use col0, col1, etc.; header rows map only the common field count. No quoted-CSV
parser is introduced. Each selection attempt advances the cursor, including EOF;
rotation returns row zero and sets the next position to one. Reloading data does
not reset cursor position. Normal ticks run on one scheduler thread. Callers must
serialize loading and iteration; the cursor does not support concurrent operations.
The existing SDK stop requests interruption without waiting for an in-flight tick,
so stop/start does not itself guarantee that serialization.

**Forbidden:** depend on Worker SDK/control-plane state, schedule ticks, dispatch
WorkItems, resolve configuration defaults or mutate accepted configuration.

**Verification entrypoints:** `CsvDatasetCursorTest`, plus SDK CSV intake tests.

## RESP-WORK-REDIS-DATASET

**Current module(s):** `common/worker-sdk`.

RedisDataSetWorkInput owns Redis dataset reads, cursor/exhaustion handling and current intake lifecycle.

Selected Redis settings and worker state drive reads; records dispatch through WorkerRuntime.
Input rates/timing consume RESP-WORK-INPUT-RATE and RESP-WORK-INPUT-SCHEDULE; timing is
validated before start registers callbacks or creates an executor.
Enablement is a read-only projection of RESP-WORK-STATE snapshots. Listener registration
supplies the current worker state before intake starts, including after stop/start;
Redis input properties do not supply an independent startup flag.

**Forbidden:** own worker enablement, refresh auth tokens, generate sequences or declare Rabbit resources.

**Required effect:** Configured dataset reads preserve cursor/order/exhaustion; no switch to a different source on failure.

**Verification entrypoints:** `RedisDataSetWorkInputTest`.

**Migration status:** Current SDK client implementation; B02/B03/B06 migration remains.

## RESP-WORK-REDIS-PUSH

**Current module(s):** `common/worker-sdk`.

RedisPushSupport owns route/payload selection and delegates list writes to RedisListWriter; RedisWorkOutput applies output policy, while RedisUploaderInterceptor applies diagnostic-capture policy.

Both consumers delegate the push operation; RedisWorkOutputFactory wires the selected output. Diagnostic capture and business output are distinct uses, not duplicate authority for one result.

**Forbidden:** turn capture into business output or independently reimplement the shared Redis push operation.

**Required effect:** The selected payload is pushed to the resolved list through shared support; capture and business output retain separate explicit policies.

**Verification entrypoints:** `RedisWorkOutputTest`, `RedisUploaderInterceptorTest`.

**Migration status:** Write settings and their enum decoding now belong to
RESP-WORK-REDIS-WRITE-SETTINGS; destination validation belongs to RESP-WORK-REDIS-TARGETS.
RedisPushSupport consumes their resolved products and RESP-REDIS-CONNECTION-SETTINGS.
RedisPushRequest carries the resolved selection inputs; RedisListWriter belongs to
redis-adapter. RedisWorkOutputFactory and RedisUploaderInterceptor own their respective
RedisPushSupport lifetimes; Spring shutdown closes every cached writer, including
writers retained across settings updates. Push and trim remain separate operations.
No per-message close, retry or failure-policy change is introduced.

## RESP-WORK-NONE-OUTPUT

**Current module(s):** `common/worker-sdk`.

NoopWorkOutput owns the explicitly selected NONE output behavior.

The output registry delegates to it without requiring Rabbit/Redis output settings.

**Forbidden:** open an output connection or switch to another adapter.

**Required effect:** Selecting NONE requires no output connection or Rabbit exchange.

**Verification entrypoints:** `WorkControlCompositionTest`.

**Migration status:** Current B01 NONE composition.

## RESP-MCP-CATALOGUE

**Current owner:** `pockethive-mcp-service` `ToolCatalogue` publishes the canonical
tool descriptors and versioned connected-skill content. `McpToolId` owns tool
identifiers; the catalogue binds schemas, scopes, owner dispatch metadata and
skill references to those identifiers.

**Consumers:** MCP discovery/resources and the scope-enforcing invocation facade
consume these descriptors. Skills guide clients through the existing owner APIs;
they are not an additional auth-profile schema or validator.

**Forbidden:** execute tools, call owner services, infer runtime configuration,
resolve secrets, or duplicate worker auth and Scenario Manager validation.

**Required effect:** each tool has one descriptor and discoverable skill content
with its version and content digest. Auth authoring guidance preserves complete
file contents and distinguishes proposals, owner validation and runtime acceptance.
See [the MCP agent contract](../mcp/README.md#agent-contract).

**Verification entrypoints:** `ToolCatalogueTest`, `McpToolExecutionIntegrationTest`,
and `McpStreamableHttpIntegrationTest`.

**Migration status:** documents the existing catalogue owner; no ownership transfer
or tool/auth schema change.

## RESP-WORK-AUTH-RUNTIME

**Current module(s):** `common/worker-sdk`.

AuthRuntime activates worker auth profiles, applies auth material and coordinates
ordinary OAuth refresh through TokenStore and HTTP. It delegates resolved profile
preparation to RESP-WORK-AUTH-PROFILE-PREPARATION and signed OAuth acquisition to
RESP-WORK-SIGNED-OAUTH-TOKENS. HTTP header replacement is delegated to
RESP-WORK-AUTH-HTTP-HEADERS. Factory-created runtimes own their token store and HTTP
client; injected resources are borrowed. Callers close factory runtimes at the end
of request/journey scope, including failure and interruption. Initialization
failures release resources already acquired.

Template workers call it; shared profile/claim values live in auth-contracts.
YAML reading, profile-file discovery, activation-set tokenKey collision detection
and Redis policy selection remain here; AuthRuntimeResources constructs the store. Preparation completes before opening
the token store. Existing ordinary OAuth request/parser behavior is unchanged.

**Forbidden:** own product auth-service identity/authorization or duplicate token storage/claim behavior.

**Required effect:** Configured auth material/refresh uses the selected TokenStore;
profile resolution, validation, fingerprinting and collision checks precede store
construction. Current discovery/ordinary HTTP effects remain visible for later extraction.

**Verification entrypoints:** `AuthRuntimeTest`, `OAuth2HttpSignatureRuntimeTest`,
`AuthRuntimeLifecycleTest`, `AuthHttpHeadersTest`, `HttpSequenceSecondPassAuthTest`.

**Migration status:** Profile preparation and signed acquisition have separate
owners. Discovery, credential application and ordinary OAuth acquisition remain
in this existing mixed owner; broader B06/B07 separation is not claimed here.

## RESP-WORK-AUTH-PROFILE-PREPARATION

**Current module(s):** `common/worker-sdk`.

AuthProfilePreparation owns worker-context rendering, env/file secret resolution,
resolved profile validation and cache fingerprints. AuthRuntime supplies the
auth-contracts profile and selected TemplateRenderer. The shared
AuthType.requiredStorageMode policy and AuthTokenKeys own storage classification
and key syntax; RESP-WORK-OAUTH-SIGNATURE owns resolved signing-specific validation.

**Forbidden:** discover/read profile YAML, connect to Redis, acquire tokens, mutate
downstream requests, or define an alternative auth schema or storage policy.

**Required effect:** Detect missing SUT context before rendering; resolve before
validation and fingerprinting. Preserve signed file bytes, legacy file trimming,
legacy fingerprint serialization and signed-only canonical map ordering. Failure
does not activate profiles or open a token store.

**Verification entrypoints:** `AuthProfilePreparationTest`, `AuthRuntimeTest`,
`OAuth2HttpSignatureRuntimeTest`.

**Migration status:** This concern moves out of AuthRuntime with its behavior tests.
Filesystem and environment reads remain explicit SDK effects, not pure domain logic.

## RESP-WORK-OAUTH-SIGNATURE

**Current module(s):** `common/worker-sdk`.

OAuth2HttpSignature owns validation of resolved signed OAuth settings and
construction of the signed token request, including form encoding, digest,
canonical signature input and RSA signing. Profile preparation validates before
activation; the signed token provider requests a fresh signature for acquisition.

**Forbidden:** resolve secret references, perform HTTP/Redis IO, own refresh claims,
or sign downstream resource requests.

**Required effect:** One validation/signing implementation supplies an HTTPS token
request; private-key and protocol failures are explicit. Ordinary OAuth stays unsigned.

**Verification entrypoints:** `OAuth2HttpSignatureTest`, `OAuth2HttpSignatureWireTest`.

**Migration status:** Isolated implementation for the new profile; no existing schema changes.

## RESP-WORK-SIGNED-OAUTH-TOKENS

**Current module(s):** `common/worker-sdk`.

OAuth2HttpSignatureTokenProvider owns signed OAuth acquisition, bounded contention,
strict token-response parsing and refresh-claim coordination through the selected
TokenStore and HttpClient. OAuth2HttpSignature constructs each token request.
AuthRuntime alone applies the returned material to downstream requests.

**Forbidden:** implement token persistence/claim arbitration, resolve profiles,
own product auth-service identity, or change ordinary OAuth acquisition behavior.

**Required effect:** Reuse valid tokens, bound HTTP work by the claim lease, publish
only under the owned claim and release claims on failure without masking the
primary error. Do not apply a token whose publication failed.

**Verification entrypoints:** `OAuth2HttpSignatureRuntimeTest`,
`OAuth2HttpSignatureConcurrencyTest`, `OAuth2HttpSignatureTimeoutTest`,
`OAuth2HttpSignatureRedisTest`.

**Migration status:** New isolated lifecycle; shared TokenStore retains storage authority.

## RESP-SCENARIO-AUTH-STORAGE-FINDINGS

**Current module(s):** `scenario-manager-service`.

AuthProfileStorageFindings owns the authored profile type/storage boundary and
maps failures to canonical bundle findings. It consumes AuthType.parse and
AuthType.requiredStorageMode from auth-contracts and AuthTokenKeys for token-key
validation. ScenarioBundleValidator delegates each authored profile after
document parsing and retains bundle/profile-reference visibility.

**Forbidden:** read secret sources, validate resolved signing keys/token endpoints/
scopes, acquire tokens, or duplicate shared type/storage/token-key policy.

**Required effect:** Preserve existing authored storage parsing/defaults and
finding codes, paths, messages and ordering while applying the shared type-to-storage policy.

**Verification entrypoints:** `AuthProfileStorageFindingsTest`, signed-auth
`ScenarioControllerTest` cases.

**Migration status:** Authored storage concern extracted from ScenarioBundleValidator;
resolved profile preparation stays in the worker SDK.

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

ProcessorWorkerImpl dispatches a request to ProtocolHandler; Http/Tcp/Iso8583 handlers each own their distinct protocol execution; ResponseBuilder constructs shared result envelopes. All three handlers delegate processor request pacing to RESP-PROCESSOR-PACING, sharing one instance per worker. HTTP client construction/selection and capacity projection belong to RESP-PROCESSOR-HTTP-CLIENT; the worker receives its API through composition. TCP/ISO8583 pool replacement and selection delegate to RESP-PROCESSOR-TCP-RUNTIME with separate protocol instances.

Request/result DTOs come from work-api; protocol handlers own actual HTTP/socket effects and produce observations consumed downstream.

**Forbidden:** provision Work/CP topology or let one protocol handler reinterpret another protocol's result.

**Required effect:** A selected protocol produces its shared result envelope from observed transport effects; dispatch does not provision broker topology.

**Verification entrypoints:** `ProcessorTest`, `ProcessorTopologyProvisioningTest`.

**Migration status:** Current service contains concrete transports; B07 extraction remains. Protocol scopes are distinct, not multiple writers for one transaction.

## RESP-PROCESSOR-PACING

**Current module(s):** `processor-service`.

ProcessorPacer owns the processor pacing state and algorithm, instantiated once per
ProcessorWorkerImpl and supplied to all three protocol handlers. Its `await`
operation consumes the already validated ProcessorWorkerConfig and returns the
existing planned pacing duration in whole milliseconds. Handlers retain the call
at their existing pre-transport point and retain metrics/error handling.

The processor reserves one interval before every RATE_PER_SEC call, including
the first. Concurrent calls reserve successive slots atomically. Rate changes use
the new interval after any outstanding reservations; THREAD_COUNT neither waits
nor clears reservations. After an idle period the schedule starts from the current
monotonic time. Interrupted waiting propagates InterruptedException and does not
roll back the reservation. Integer truncation, the initial zero timestamp and
existing nanoTime arithmetic remain unchanged.

**Forbidden:** independent pacing state or interval calculations in protocol
handlers; configuration defaults/validation, protocol IO, ACK policy or result
construction in ProcessorPacer. No global limiter shared between worker instances.

Moderator OperationModeLimiter and work-local RateSchedulePolicy are different
policies (moderator resets/shaping and scheduler per-tick quotas). They are not
alternate owners of processor request pacing and are outside this transfer.

**Verification entrypoints:** ProcessorPacerTest for clock/wait effects, updates,
concurrent reservations and interruption; existing ProcessorTest and protocol
transport tests for result/error behavior. No wire/config field is added.

## RESP-PROCESSOR-HTTP-CLIENT

**Current module(s):** `processor-service`.

ApacheProcessorHttpClient owns verified/unverified pool construction, selection and
capacity projection behind the local ProcessorHttpClient API. ProcessorConfiguration
supplies one owner to ProcessorWorkerImpl and HttpProtocolHandler. The worker consumes only its capacity
projection; the handler submits a request and decodes a response through the API.
This is a service-local Apache HTTP boundary, not a transport-neutral Work contract.
Response callbacks receive only responses, never a raw client or connection manager.

Preserve four eager clients (verified/unverified, pooled/non-reusing) and two lazy
per-thread clients. Preserve system proxy/properties, verified TLS and the existing
explicit sslVerify=false behavior, pool limits of 200 total/route, keepAlive=false
precedence over connectionReuse, and PER_THREAD selection. Status keeps the existing
configured capacity projection (200 GLOBAL, threadCount PER_THREAD, zero when reuse
is off); this projection is not a live connection count. Configuration validation
and defaults remain in ProcessorWorkerConfig.

HttpProtocolHandler retains envelope parsing, target/body/header preparation,
response decoding, timing/metrics and result extraction. Preserve callback timing
(before body read), response release and exception propagation by retaining Apache's
response-handler execution API. Do not add retries, timeouts or client shutdown
hooks in this extraction; existing client lifetime behavior remains separate debt.
HTTP Sequence has its own functional client and policy, outside this transfer.

**Forbidden:** raw HTTP clients or pool construction in ProcessorWorkerImpl;
client selection or capacity formulas outside the owner; config normalization,
protocol result construction or pacing inside the HTTP client owner.

**Verification entrypoints:** ApacheProcessorHttpClientTest for real request/proxy,
reuse, TLS and capacity behavior; ProcessorTest and HttpAuthSecondPassSecurityTest
for response/metrics/error and diagnostic redaction. Observable proxy traffic replaces the old reflective route-planner identity assertion.

## RESP-PROCESSOR-TCP-RUNTIME

**Current module(s):** `processor-service`.

TcpTransportRuntime owns transport configuration/replacement and GLOBAL/PER_THREAD/NONE
selection for TCP and ISO8583. Each handler retains its own runtime instance; sharing
one implementation does not merge the previously independent protocol pools.

The runtime owns active configuration, the eager GLOBAL transport and lazily created
per-thread transports. `configure` retains the current equality check, locking and
replacement/close order. `currentConfig` supplies the existing read-only projection.
`acquire` returns a TcpTransportLease that executes through TcpTransport and releases
only a NONE transport when the handler finishes its complete result/error path.
Close exceptions remain suppressed as before. Retrying remains in the handlers and
uses the same lease; no new retries, reconnection, framing or timeout behavior.

Preserve update timing: TCP configures before target/auth work; ISO8583 configures
after its protocol/auth validation. Handlers read the active config after pacing as
before. This extraction does not make configuration replacement atomic across
in-flight work, roll back failed construction, or add shutdown hooks. Those existing
lifecycle/concurrency limitations need a separate behavior decision.

TcpTransportFactory remains the sole concrete Socket/NIO/Netty constructor selector,
internal to the transport package. Its active config-based behavior is unchanged.
The uncalled string/global-pool helpers and TcpTransportPool are removed; they are
not an alternate runtime API. TcpPerThreadTransports owns only lazy per-thread
construction and release for one configuration generation.

**Forbidden:** pool state, transport construction/selection/replacement or release
policy in protocol handlers; protocol parsing, authentication, pacing, retries,
metrics or result construction in TcpTransportRuntime.

**Verification entrypoints:** TcpTransportRuntimeTest for execution/release effects,
reconfiguration and per-thread isolation; existing processor TCP/ISO8583 tests for
framing, auth options, results and failures; existing transport IO tests.

## RESP-HTTP-SEQUENCE-WORK

**Current module(s):** `http-sequence-service`.

HttpSequenceWorkerImpl delegates the configured sequence to HttpSequenceRunner,
which owns ordered step execution, retries, capture selection and journey budgets.
Capture projection delegates to RESP-HTTP-SEQUENCE-DEBUG-CAPTURE and persistence
to RESP-WORK-REDIS-DEBUG-CAPTURE. The worker closes its runner and pooled HTTP client.

Request templates, TemplateRenderer and AuthRuntime provide their existing capabilities.
HttpSequenceRequestRenderer owns per-step request rendering. The runner closes
its factory-created AuthRuntime when the journey exits, including failure and
interruption; Redis cache entries and refresh leases retain their existing owner.

**Forbidden:** own another service's lifecycle or reimplement the shared template engine.

**Required effect:** Configured steps execute in sequence and return the observed result through the worker; renderer and auth capabilities retain their owners.

**Verification entrypoints:** `HttpSequenceRunnerTest`.

**Migration status:** Request rendering and capture projection/storage have distinct owners;
remaining mixed orchestration/template/extraction responsibilities remain B06/B07 debt.

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

Output location is runtime-owned, not scenario configuration: `ClearingExportStorageConfiguration`
uses `RuntimeFilesystemLayout` with the mounted container root and current swarm/run/worker
identity. `LocalDirectoryClearingExportSink` receives that immutable directory. The
`localTargetDir` field is removed; config updates cannot change the base directory.
File names, temporary suffixes and manifest paths must resolve inside that directory.
No migration or compatibility alias is provided. Relative manifest subdirectories remain supported.

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

## RESP-ORCHESTRATOR-CONTAINER-LIFECYCLE

**Current module(s):** `orchestrator-service`.

ContainerLifecycleManager prepares controller container settings, invokes the configured
compute adapter, records the resulting Swarm runtime identity and stores the ownership manifest
constructed by RuntimeOwnershipManifestFactory,
pre-pulls requested images and removes controller compute/control queues. It consumes
RESP-RABBIT-CONNECTION through the participant environment factory, plus the existing
runtime filesystem mount, metrics and compute contracts. ClickHouse launch fields
consume RESP-CLICKHOUSE-ENVIRONMENT; the lifecycle manager does not map them.
Swarm operation handlers invoke
these infrastructure operations; public operation terminalization remains with its owner.

**Forbidden:** independently validate/encode Rabbit connections, redefine control routing
or terminalize the public swarm operation based only on an attempted infrastructure action.

**Required effect:** Explicit launch settings reach compute; actual runtime identity and
ownership artifacts are recorded, and removal reports its concrete results to the caller.

**Verification entrypoints:** `ContainerLifecycleManagerTest`.

**Migration status:** This records current mixed lifecycle/environment/image/manifest/cleanup
code, not accepted isolation. Work naming moves in B04; CP-N05/C02 must separate remaining
infrastructure and absence ownership. The Rabbit base export dependency is already extracted.

## RESP-CONTROLLER-BUFFER-GUARD

**Current module:** `swarm-controller-service`, guard.BufferGuardCoordinator.
It integrates swarm plans with the Manager SDK guard lifecycle: selects eligible queue
producers, maps traffic-policy settings and forwards computed rates through Control Plane.
Queue sampling/adjustment remains in Manager SDK; source input-rate decoding belongs to
RESP-WORK-INPUT-RATE. Only SCHEDULER and REDIS_DATASET are currently rate-update targets.
Other input selections retain the existing bounds-only mode and receive no rate publication.

**RATE-R1 transfer:** canonical input-rate validation precedes guard-specific clamping.
A malformed selected source rate invalidates the entire guard configuration, empties
current settings and exposes a problem; it cannot become an active guard at minimum rate.
Valid rates still obey adjustment.minRatePerSec/maxRatePerSec. Input-to-role mapping is
rebuilt for each plan so a previous rate-controlled target cannot receive updates after
reconfiguration to bounds-only mode. No source-rate default or validator remains here.

**Forbidden:** parse or repair source input rates locally, read Rabbit directly, or
implement the Manager SDK feedback algorithm. Existing traffic-policy default/duration
mapping and broader plan/transport separation remain debt outside RATE-R1.
**Verification:** BufferGuardCoordinatorTest and existing SwarmLifecycleManagerTest guard behavior.

## RESP-CONTROLLER-WORKER-PLAN

**Current module:** `swarm-controller-service`.

SwarmWorkerSpecFactory maps Bee and SUT environment into PlannedSwarmWorker. It owns
worker identity, participant/network environment composition, SUT enrichment,
volumes and spec assembly. Work configuration is supplied by WorkerWorkConfigurationPort;
the factory does not construct settings implementations or interpret Work fields.
ClickHouse launch values delegate to RESP-CLICKHOUSE-ENVIRONMENT.
SwarmRuntimeCore consumes the plan and owns lifecycle/state; compute executes the spec.

**Forbidden:** provision workers, publish bootstrap, mutate Bee/runtime state, decode Work
settings, construct Work providers or reconstruct Work destinations.

**Required effect:** return a spec using exactly the environment and bootstrap projection
returned by RESP-CONTROLLER-WORK-CONFIGURATION; propagate rejection before plan return.

**Verification:** SwarmWorkerSpecFactoryTest, SwarmLifecycleManagerTest.
**Migration status:** Work composition extracted for separate review. Existing non-Work
planning concerns remain; full candidate validation now delegates through the configuration port (pending review).

## RESP-CONTROLLER-WORK-CONFIGURATION

**Module:** `swarm-controller-service`. Controller-local port/result live in
`runtime`; selected implementation in `infra.configuration`; startup in `config`.

WorkerWorkConfigurationPort.validateDeclaration(Bee) preserves early input-control rejection
before the spec factory resolves external network context. compose repeats that same
canonical preflight so its standalone callers cannot bypass declaration validation.
WorkerWorkConfigurationPort.compose(Bee, effectiveConfig, baseEnvironment, resolvedTopology) returns
WorkerWorkConfigurationResult(environment, bootstrapConfig). Bee supplies logical Work
bindings and explicit environment overrides; effectiveConfig is the existing SUT-enriched
configuration; baseEnvironment contains participant/diagnostic/network values. Input maps
are borrowed read-only. The result owns unmodifiable outer maps and its frozen environment;
unchanged non-Work nested values remain borrowed projections, not a new domain state owner.
Its string representation redacts both maps.

WorkerWorkConfigurationAdapter is the sole owner of the extracted Work composition flow:
validate removed controls and competing IO selectors; consume resolved Work channel/environment
projections and delegate selected adapter bootstrap to WorkAdapterEnvironment; compose
remaining bee.env/CSV/scheduler/Redis exports through adapter-owned projections; validate environment input controls; delegate
connection freeze/validation; project final local/dataset/output settings. RedisOutputEnvironment
owns output write/target overrides and exports; the Controller has no Redis output field mapping. It returns the connection
owner's frozen environment without subsequent mutation. Existing field parsers and codecs
remain their semantic owners. After connection freezing and adapter bootstrap projection, Controller calls the injected
WorkConfigurationParser in RESOLVED mode on the exact configuration returned to worker
planning. Any problems or deferred paths reject before a plan is returned; no absent IO
selector is inferred. Provider ports retain all adapter field rules.

ResolvedWorkTopology supplies the same addresses used by resource provisioning and status
under RESP-WORK-RESOURCE-NAMES. The Controller consumes it without resolving or mapping Rabbit
fields. SwarmControllerProperties supplies explicit traffic settings only at composition.
WorkerWorkConfigurationComposition explicitly supplies the adapter and its collaborators.
SwarmLifecycleManager passes the port to the spec factory without choosing its implementation.

**Forbidden:** SUT/volume/identity decisions, process environment reads, clients/provisioning,
accepted-state writes, duplicate field rules, or bypassing the selected neutral parser before returning the Work candidate. The narrower ResolvedWorkConnectionEnvironment remains connection-owned.

**Verification:** adapter behavior tests, existing worker-plan/lifecycle component tests and
RepositoryImportBoundaryTest. Rejection must leave source maps/state/effects untouched.
**Migration status:** the bounded Work configuration adapter, full RESOLVED gate and Rabbit
name-owner transfer are implemented. The current Rabbit plan records review and correction status.

## RESP-WORK-CONNECTION-ENVIRONMENT

WorkAdapterEnvironment in work-config is the selected WorkPlane configuration capability.
It exports its connection, validates the final connection projection and materializes selected
adapter settings into WorkBootstrapProjection. RabbitWorkBootstrapEnvironment implements it
inside rabbit-adapter by delegating to RabbitWorkSettingsBootstrap, RabbitWorkEnvironment and
RabbitConnectionEnvironment. Controller composition consumes this port; adapter parsing and
mapping remain with those existing owners. CONTROL validation/export remain separate.

**Current module:** `swarm-controller-service`,
`io.pockethive.swarmcontroller.runtime.environment`, consuming connection contracts/codecs
from `common/rabbit-adapter` and `common/redis-config`.

WorkConnectionEnvironmentResolver produces ResolvedWorkConnectionEnvironment from the
raw bootstrap configuration, composed container environment, unexpanded override lookup
and a factory for the final property lookup. The human-approved FENV-R1 correction composes
both Redis directions first, freezes the complete environment and only then expands/binds
and validates connections. WorkerWorkConfigurationAdapter delegates Spring lookup to
SpringConnectionEnvironment: raw lookup uses Binder without placeholder expansion; final
lookup uses Binder with strict Spring placeholder resolution over the supplied snapshot.
The same raw Spring lookup also supplies RESP-WORK-INPUT-LIFECYCLE-POLICY during worker
planning; SpringConnectionEnvironment does not decide which input fields are supported.
Names/precedence and successful expansion follow worker binding. Missing/cyclic references
fail planning with a property name, without exposing the input or exception cause.
No process properties are consulted, and no custom placeholder parser or retry loop exists.
The resolver validates CONTROL fields through RabbitConnectionEnvironment and delegates WORK
connection validation to WorkAdapterEnvironment. RabbitWorkBootstrapEnvironment uses the same
canonical Rabbit connection decoder and settings contract. For each declared Redis IO block, selected Redis IO direction,
or direction with connection overrides, explicit unexpanded environment values replace
corresponding declared fields before encoding. Final text comes from the complete snapshot;
non-text declaration types stay intact for RedisConfigurationParser to validate.
Missing overrides retain declared fields; empty text is an explicit value, and invalid
overrides fail. A valid override may supply a missing field or correct an invalid base field.

Control Rabbit override correction, explicitly approved 2026-09-11: scenario bee.env must
not directly supply properties under spring.rabbitmq, including Spring environment aliases.
RabbitConnectionEnvironment.controlOverrideProblems owns this policy; ScenarioEnvironmentFindings
projects it into descriptor errors and WorkerWorkConfigurationAdapter rejects it before returning
a worker plan. The final resolver still validates the environment-owned connection. Other ENV
uses and Redis override semantics remain supported; no delivery or ACK change is implied.
Indirect startup overrides are an explicitly accepted limitation, not a blocker or repair
task; see [the canonical warning](work-plane-boundaries.md#accepted-cp-override-limitation).

The returned environment is exactly the frozen candidate checked by final binding; its
strings/placeholders are not rewritten after validation. Bootstrap projects the accepted
Redis values and must not restore the pre-override connection. Unrelated config fields and
environment entries are retained, and source maps are not mutated. Rejection yields no
worker spec; RESP-CONTROLLER-CONTROL validates all specs before infrastructure/state effects.

**Forbidden:** duplicate connection constraints, read process environment, construct clients,
choose adapters, mutate accepted runtime state or present this slice as full Work validation.

**Verification:** resolver/codec and SpringConnectionEnvironment unit tests, worker
environment binding and SwarmWorkerSpecFactory/SwarmLifecycleManager behavioral tests.

**Migration status:** explicit Rabbit connection composition is implemented. Additional Rabbit
transport options are outside the accepted five-field contract, not unfinished migration steps.
Further Redis/I/O ownership work follows the functional module plan.

## RESP-WORK-CONFIGURATION-DIAGNOSTICS

WorkConfigurationRedactor in `common/work-config`, `.config.projection`, owns the
read-only diagnostic projection of decoded Work configuration. It replaces values of
the exact `password` field with `[redacted]` throughout maps/lists, including per-worker
status and config-diff wrappers. It preserves other fields and never mutates its input.
This rule covers Redis IO passwords; it is not a heuristic classifier of arbitrary
secret names or values. Other private material retains its existing privateConfig policy.

WorkerConfigurationLog in worker-sdk owns configuration logging extracted from
ControlPlaneNotifier. Both this logger and WorkerControlPlaneRuntime's status projections
consume the shared redactor. Raw connection settings, adapter/state-listener views and
the canonical appliedConfigSha256 calculation retain the full accepted values.

**Forbidden:** raw configuration/passwords in configuration logs or status projections;
redacted values used for validation, adapter application, accepted state or outcome digests;
local copies of the redaction rule in status/log consumers.

**Verification:** redactor unit tests cover nested worker/diff wrappers; existing runtime
tests exercise config-update, INFO/DEBUG logs, status, unchanged raw adapter configuration
and the applied configuration digest.

## RESP-ORCHESTRATOR-INGRESS

**Current module(s):** `orchestrator-service`.

Orchestrator SwarmSignalListener dispatches canonical signals; ControllerStatusListener consumes controller observations and delegates convergence to SwarmOperationObservationHandler.

ControlPlaneCodec decodes; a public Rabbit binding attaches CP error classification to module-owned transport policy. Operation owners retain desired state/terminalization.

**Forbidden:** consume worker fan-out as another intent owner or independently terminalize operations.

**Required effect:** Decoded controller observations reach the existing observation/operation owners; deltas require their canonical full baseline.

**Verification entrypoints:** `ControllerStatusListenerTest`, `SwarmSignalListenerTest`.

**Migration status:** Only CP factory wiring changed in B01. Existing listener observation/journal logic remains C-stage thin-listener debt.

## RESP-CONTROLLER-CONTROL

**Current module(s):** `swarm-controller-service`.

SwarmControllerControlPlaneConfiguration wires controller collaborators; SwarmSignalListener dispatches to the named lifecycle/config/remove/observation handlers; SwarmLifecycleManager composes infrastructure and delegates local lifecycle to SwarmRuntimeCore.

SwarmRuntimeCore owns local runtime state; SwarmLifecycleCommandHandler, SwarmConfigUpdateHandler and SwarmRemoveCommandHandler own their command workflows. QueueStatsPort reads observations; SwarmQueueMetrics is only a Micrometer projection.

For preparation, SwarmRuntimeCore first builds all PlannedSwarmWorker candidates through
RESP-CONTROLLER-WORKER-PLAN and validates their identities in a local SwarmRuntimeState.
Only after that succeeds may it replace the accepted template/context/traffic policy,
reset readiness, declare topology, register bootstrap configuration and provision workers.
A worker planning or identity error must propagate while preserving the previously
accepted state and issuing no topology/provisioning/bootstrap changes. A corrected start
after an initial rejection must perform preparation. This ordering does not provide
rollback for infrastructure failures after validation.

**Forbidden:** write Orchestrator desired intent or publish its public terminal operation outcome.

**Required effect:** Decoded control messages reach the named handlers; SwarmLifecycleManager delegates runtime state to SwarmRuntimeCore.

**Verification entrypoints:** `SwarmSignalListenerTest`, `SwarmLifecycleManagerTest`, `ControlTopologyOwnershipTest`.

**Migration status:** CP transport is registered through the Rabbit API. Existing domain handlers remain their owners; explicit connection isolation remains open in the Rabbit migration.

## RESP-SCENARIO-HTTP-CONTRACT

**Current module(s):** `common/scenario-api` (producer-owned Java contracts).

Scenario Manager owns the existing runtime materialization and variable-resolution
HTTP shapes: `RuntimeRequest`, `ScenarioRuntimeResponse` and
`VariablesResolveResponse`, under `io.pockethive.scenarios.api`. Each has one Java
definition used by ScenarioController and ScenarioManagerClient. The wire contract
remains in `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` and
`docs/scenarios/SCENARIO_VARIABLES.md`; moving the records does not add validation,
defaults, fields or unknown-field policy.

These records carry boundary values only. ScenarioRuntimeMaterializer and
ScenarioVariablesService retain runtime effects and variable resolution. The
Orchestrator application port's `ResolvedVariables` is a local normalized view,
constructed from the shared wire response with the existing empty-map/list policy;
it is not independently decoded from HTTP or an alternative variable resolver.

**Forbidden:** service-local copies of these request/response records, domain
behavior in the shared contracts or importing service implementations into this module.

**Verification entrypoints:** ScenarioManagerClientTest (producer-contract payloads
through the actual client), ScenarioControllerTest and ScenarioVariablesServiceTest.

## RESP-SCENARIO-HTTP-CLIENT

**Current module(s):** `orchestrator-service`.

ScenarioManagerClient implements ScenarioClient over the Scenario Manager HTTP
interface. It owns requests, response decoding and the existing transport error and
auth-refresh handling; contract records come from RESP-SCENARIO-HTTP-CONTRACT.
It checks the required runtimeDir before returning it and preserves the existing
resolved-variable projection. No variable resolution or runtime materialization is
performed by the client.

ScenarioTemplateDescriptor is the application's read-only subset of template
metadata. The client decodes that existing projection directly, ignoring additional
template fields as before, without changing ObjectMapper behavior for other responses.
ScenarioPlan is a separate, intentional plan projection; it is not replaced by a
copy of the producer's full authoring model in this slice.

**Forbidden:** local copies of the shared wire records, domain configuration or
filesystem decisions, global changes to decoder unknown-field policy.

**Verification entrypoints:** ScenarioManagerClientTest, ScenarioManagerClientAuthRetryTest.

## RESP-SCENARIO-VALIDATE

**Current module(s):** `scenario-manager-service`, `tools/scenario-templating-check`.

ScenarioBundleValidator owns bundle acceptance checks; ScenarioTemplateValidator is an offline template-rendering diagnostic, not another bundle acceptance authority.

Both use the canonical template API with DisabledSequenceAccess; diagnostic rendering must not decide persisted scenario validity.

Request-template shape/auth/protocol checks delegate to RequestTemplateParser.
AuthProfileStorageFindings projects authored auth type/storage checks using the
shared auth-contracts policy; see RESP-SCENARIO-AUTH-STORAGE-FINDINGS.
RequestTemplateFindings projects its problems into bundle findings; profile existence and
bundle visibility stay here. The offline diagnostic delegates file loading to
request-template-files. See RESP-REQUEST-TEMPLATE-PARSE for these transferred owners.
WorkConfigurationFindings projects one injected WorkConfigurationParser AUTHORING result,
prefixing canonical errors/deferred paths without adapter-specific decisions. The existing
ScenarioWorkConfigurationComposition bean selects CurrentWorkConfigurationProviders; Scenario
validation imports no concrete adapter settings/parser packages. Both Work roots and explicit
selectors are required. Omitted selected settings are checked by the provider as an empty
AUTHORING block; Rabbit permits optional tuning, other required fields still fail canonically.
Catalogue type/range/options/required checks exclude inputs/outputs entirely; local IO-selector
and per-adapter validation assembly have been removed. Bundle files, references, template
checks and non-Work catalogue fields remain Scenario-owned. Rabbit physical destinations
are rejected in AUTHORING; RESP-WORK-RESOURCE-NAMES and RabbitWorkSettingsBootstrap own the
subsequent named topology/materialization path.

**Forbidden:** execute sequence effects during syntax checks or claim diagnostic success is bundle acceptance.

**Required effect:** Bundle acceptance uses its validator; syntax/diagnostic rendering executes no sequence effects and does not become a second acceptance path.

**Verification entrypoints:** `ScenarioRepositoryValidationTest`, `SequencePortRenderingTest`.

**Migration status:** Current syntax-port and piecewise Work integration. Complete Scenario
AUTHORING candidate composition is the remaining B02 validation migration; runtime RESOLVED
candidate validation is implemented.

## RESP-TEST-WORK-FIXTURES

**Current module(s):** `common/work-test-fixtures`.

ControlPlaneTestFixtures owns reusable test setup for Work/CP contracts and Spring descriptors.

Consumers depend on work-test-fixtures with test scope only.

**Forbidden:** be packaged as production worker behavior or replace production codecs/validators.

**Required effect:** Production sources do not import the fixture package and production artifacts do not acquire its test dependencies.

**Verification entrypoints:** `RepositoryImportBoundaryTest`, `WorkControlCompositionTest`.

**Migration status:** Current B01 fixture extraction; Maven Enforcer excludes test-scope dependencies explicitly.

## RESP-WORK-PATCH-POLICY

**Current modules:** neutral outer policy/ports in `common/work-config`; concrete
direction-specific descriptors and semantic validators in the selected adapter/config
modules.

The framework-free `common/work-config-composition` module owns the single current
provider inventory shared by Worker SDK and Scenario Manager. Their Spring composition
adapts that inventory into application beans; neither application repeats the supported
provider list.

WorkPatchPolicy owns outer validation of proposed IO updates/reset against prior
configuration and current enablement. Each adapter owns its live mutable/disabled-only
field catalogue and semantic validation through exactly one selected
WorkInputMutationPolicy or WorkOutputMutationPolicy.
WorkerInputType and WorkerOutputType remain shared selection values in `io.pockethive.work.config`. The policy uses
those values and a worker name for diagnostics; it does not depend on SDK WorkerDefinition.

WorkerControlPlaneRuntime delegates before merging/publishing accepted configuration.
WorkPatchPolicy first consumes RESP-WORK-INPUT-LIFECYCLE-POLICY on proposed inputs,
including the first bootstrap update, before its existing patch classifications.
CapabilityCatalogueService consumes the composed adapter descriptors for authoring metadata.
LiveIoConfigUpdateGuard and LiveIoConfigMutability have been removed; neither
SDK nor scenario-validation-contracts retains another implementation of these decisions.

**Forbidden:** mutate worker state, apply configuration to infrastructure, select clients,
read Spring/environment state, or claim full candidate validation from patch classification.

**Required effect:** Endpoint/adapter changes require rematerialization; operational fields
keep their existing value constraints. Redis single-source listName changes require a
disabled worker already in that mode. Rejected updates cannot reach the SDK merge path.

**Verification entrypoints:** migrated WorkPatchPolicyTest; existing capability and SDK
runtime tests. Boundary ownership is checked through the import test and separate review.

**Migration status:** Neutral mutation ports, all current Rabbit/Redis/local/NONE providers
and shared composition activation are implemented. The runtime complete-candidate gate is
also active; Scenario AUTHORING is implemented; Controller early complete RESOLVED validation remains. Patch
validation does not replace either complete-parser boundary.

## RESP-REQUEST-TEMPLATE-PARSE

**Current modules:** `common/request-templates` owns RequestTemplateParser and
request-template definitions; `common/request-template-files` owns TemplateLoader and
LoadedTemplate file provenance.

RequestTemplateParser accepts an already decoded document and owns required fields,
protocol selection, legacy-auth rejection, canonical AuthRef conversion and typed
request-template construction. HTTP/TCP/ISO8583 share the existing public template
contract: explicit protocol, serviceId and callId; HTTP also requires method/pathTemplate.
The parser owns key construction. It performs no filesystem reads or template evaluation.

TemplateLoader enumerates and decodes files once, delegates parsing and rejects missing
roots and duplicate keys explicitly. Runtime workers and offline diagnostics use that
adapter. Scenario Manager delegates template semantics to the parser and retains bundle
file visibility, duplicate/reference findings and auth-profile existence checks.

The file adapter maps the parser's INLINE_AUTH category to the existing
AuthFailureException configuration contract for runtime callers, retaining the parser
failure as its cause. It does not inspect the document to repeat auth validation.
Request Builder and HTTP Sequence retain their existing first-failure/repeated-failure
handling. Other template failures retain their parsing/IO classification.

**Forbidden:** parser filesystem/network access; runtime/authoring copies of template
shape/auth/protocol decisions; silent missing-root, serviceId or duplicate-key fallback.

**Required effect:** The same decoded template receives the same semantic decision in
runtime loading and authoring. Validation failures carry field/category evidence for
Scenario Manager; auth reference values retain their owner in AuthRef.

**Verification entrypoints:** request parser unit tests, migrated TemplateLoaderTest
component tests, existing ScenarioRepositoryValidationTest and worker behavior suites.

**Migration status:** Parser/file transfer implemented, pending separate review. The existing diagnostics contract already
requires explicit serviceId/callId; runtime loading is tightened to the same rule.


The HTTP/TCP `schemaRef` authoring hint remains supported as defined in
`docs/scenarios/SCENARIO_CONTRACT.md#optional-authoring-helpers`; the parser explicitly
allows that metadata without adding it to the runtime request definition. Unknown
runtime fields are rejected. ISO8583 schemaRef remains its distinct typed schema reference.


## RESP-WORK-RESOURCE-NAMES

R1–R3 transfer, 2026-09-14: WorkTopologyChannels in topology-core owns extraction of logical
channel requirements from worker ports. WorkTopologyResolver returns an immutable
ResolvedWorkTopology with native resource identities and channel ENV/status projections.
RabbitWorkTopologyResolver is the production implementation in rabbit.work; RabbitResourceNames
remains the only Rabbit physical-name formula owner. Explicit settings are supplied at composition.

RabbitControllerTopologyEnvironment owns the existing Controller traffic property
mapping and delegates validation to RabbitResourceNames. SwarmControllerProperties
no longer binds this adapter-specific block; selected Rabbit composition consumes
it. Artemis requires only its own connection/namespace for WORK, while CONTROL
keeps its existing Rabbit configuration. No wire rename or compatibility path.

Controller worker planning, resource creation, bindings and statistics consume that resolved
result. WorkPlaneResources exposes native ensure/observe/remove operations; RabbitWorkResources
owns the existing declaration cache and Rabbit operation mapping. appliedResources is a read-only
projection of completed channel declarations/bindings, not proof of current broker presence.
Before the first accepted plan, the Controller retains the existing partial-prepare behavior by
projecting completed declarations from attempted topology. Once accepted, the runtime plan owns
the resource intent; this transfer does not repair broader lifecycle/reset behavior.
A resource-observation exception propagates; existing queue-statistics behavior for an explicitly
absent queue remains QueueStats.empty(). Removal absence is independently verified in Orchestrator.

Rabbit settings/bootstrap exports remain in the Rabbit module. SwarmWorkTopologyManager and its
old Controller-side declaration/cache rules are removed. RabbitInput/OutputProperties remain
canonical startup configuration; the transfer does not introduce another parser/default owner.
Guard consumes accepted channel addresses; external downstream observation aliases are resolved
by the same selected owner without being declared as swarm resources. Guard math remains in manager-sdk.

Orchestrator controller bootstrap consumes the selected environment/topology projection.
RuntimeOwnershipManifestFactory consumes that result for the Rabbit-only diagnostic projection
under RESP-RUNTIME-CLEANUP; it does not gate native Work startup.
RuntimeRemovalPostconditionVerifier reads WORK absence through WorkPlaneResources and CONTROL
through the existing scoped Rabbit port; it alone classifies observations into removal evidence.
AmqpRabbitTopologyAdapter projects the current Rabbit cleanup contract through selected WorkPlaneResources
for WORK; its fingerprint and delete operations use that same owner. The user deferred native
manifest fields and orphan-cleanup actions on 2026-09-14. They are not implemented.
The separately approved WORK_RESOURCE lifecycle type requires WORK and an owner-issued address.
WorkResourceNamesPort is removed. RabbitWorkAddress and RabbitWorkTopologySettings are Rabbit API
types; neutral consumers use ResolvedWorkTopology. WorkDebugTaps/WorkDebugTap now carry selected
capture operations. RabbitWorkDebugTaps delegates TTL/capacity mapping to RabbitDebugTapSpec;
Orchestrator DebugTapSession owns bounded samples/lifetime, and DebugTapService maps an explicitly
unsupported selected capture to HTTP 501 without activating Rabbit. Explicit close
propagates adapter failure as HTTP 500 instead of claiming success after registry removal;
the removed registration is not proof of native cleanup. Scheduled expiry keeps its
existing best-effort policy.
Neither transfer changes addresses, delivery/ACK, or the accepted environment override policy.

Control names use the neutral ControlResourceNamesPort from topology-core. RabbitResourceNames
owns worker/controller/orchestrator/status queue names and debug tap names; descriptors retain
domain recipient/binding policy and require the naming port. Their old constructors without
the port and the core PrefixedWorkResourceNames implementation are removed.
WorkerControlTopology is a read-only descriptor projection: WorkerControlPlaneProperties no
longer builds names or duplicate config/status routing catalogs. SwarmControllerProperties
no longer normalizes a second derived control prefix; its projections delegate to Rabbit naming.
Controller status selects the Controller naming path; worker cleanup uses the worker path,
matching the resources' owning descriptors even when a configured prefix includes a swarm segment.
ControlPlaneRouting remains the sole signal/event grammar owner; no wire grammar changes.

Rabbit configuration consumes resolved destinations from the naming port and explicit
exchange settings. It materializes RESOLVED adapter settings first; environment destinations
are projections of those settings. Bee environment may not override topology-owned
addresses. Rabbit AUTHORING contains only tuning and rejects physical destination fields,
including placeholders. Distinct immutable tuning values represent authoring success;
they are never advertised as a runtime-resolved Rabbit configuration.

Scenario Work validation is a single call through the injected WorkConfigurationParser
and its selected parser ports, with prefixing of canonical diagnostics. Scenario catalogue
checks exclude both Work roots; bundle references, template/file checks and non-Work
capability validation remain Scenario-owned. No local adapter parsers or IO selector rules
remain in Scenario validation. Transfer evidence must follow the consuming call paths,
removed owners, import limits and rejection/state/bootstrap behavior; tests alone do not
constitute separate review acceptance.

### TS-R1–TS-R3 correction contract — 2026-09-10

Explicit human-authorized correction of the separate transfer review:

- Work selectors belong to config. Controller declaration preflight rejects environment
  input/output selector overrides using the shared Work selector policy, before external
  context lookup. No second selector is accepted or silently overwritten.
- Resource-name consumers, including DebugTap, receive owner projections (ResolvedWorkTopology). Traffic
  properties contain only explicit values; no static resolver selection or name methods.
  Guard, statistics, bindings, cleanup projections and Orchestrator ownership manifests
  use their startup-injected port. Control Plane environment/settings helpers no longer
  expose Work naming operations.
- RabbitWorkSettingsBootstrap requires a present object argument. Its caller uses field
  presence to pass an empty map only for genuinely omitted optional tuning. Explicit null
  remains invalid in Controller as in Scenario; canonical field validation remains in the
  Rabbit provider.

Correction evidence must reproduce the reviewed mismatch cases and demonstrate rejection
before effects, state retention, and coherent resource identity across consumers with a
non-default port behavior. This is not a change to lifecycle state-machine ownership.

### CG-R1 correction contract — 2026-09-11

RedisOutputEnvironment in redis-config owns the Controller output candidate and its
startup/bootstrap projections under RESP-WORK-REDIS-OUTPUT-SETTINGS. Scalar write/target
overrides use raw Spring property lookup before export; resolved values are validated
by RedisConfigurationParser after connection freezing. Routes remain config-owned;
environment route-list overrides are explicitly rejected using Spring canonical property
presence before composition. Declared route placeholders resolve through final properties.
Controller removes its Redis output field/export mapping and consumes this adapter owner.
Both projections derive from the same candidate; no field rules move into Controller.

## RESP-RUNTIME-CLEANUP

Canonical `RemoveResource` and `ResourcePlane` in swarm-model own the scoped resource identity
and valid type/plane combinations. CleanupScope carries request scope; Candidate, Blocked and
CandidateResult carry the planner/execution projections, never a second outcome calculation.
ScopedRabbitName and RabbitQueueSnapshot/RabbitExchangeSnapshot preserve that identity.

`RuntimeOwnershipManifestFactory` owns projection of compute identity and Rabbit resource intent
into the existing diagnostic ownership manifest. WORK_RESOURCE targets stay in the resolved
topology and normal lifecycle removal evidence; the factory excludes them from `rabbit` with
an explicit coverage warning. It must not gate native Work startup or claim complete native
inventory. Invalid planes and owner mapping failures still fail before compute effects.
`rabbit`, its topology snapshot/assessment check and orphan cleanup cover Rabbit resources only.
Empty Rabbit WORK lists are not evidence that Artemis resources are absent. Native orphan
cleanup and diagnostic completeness remain deferred; no second inventory or public field is added.
Verification: `RuntimeOwnershipManifestFactoryTest`, existing `ContainerLifecycleManagerTest`
and the Artemis A4 public-ingress create/traffic/remove evidence in the active plan.

`RuntimeRabbitResourcePlanner` owns Rabbit cleanup target selection and debug projections
from the ownership manifest's distinct Control/Work lists. `RuntimeReconciliationService`
retains request validation, plan hashing, authorization handoff and execution coordination.
The extracted `RabbitTopologyPort` requires canonical `ResourcePlane` plus resource name;
`AmqpRabbitTopologyAdapter` dispatches to the explicitly bound resource API. No caller probes
both planes or infers a plane from queue prefixes. `RuntimeRemovalPostconditionVerifier`
checks the plane reported by `RemoveResource`; its immutable `RuntimeRemovalVerification`
retains scoped evidence. Contract details are in
[the Rabbit boundary design](work-plane-boundaries.md#connection-split-prerequisite-resource-identity).
These bindings now select independent connections. Cleanup candidate-set hashing includes the selected connection identity.

## RESP-CONTROL-STOMP-INFO

RabbitResourceNames owns Control STOMP destination construction; RabbitStompSubscription is its
read-only projection. ControlPlaneInfoController authorizes HTTP reads and exposes that projection
from accepted ControlPlaneProperties, as specified in ORCHESTRATOR-REST.md. It must not reconstruct
addresses or publish credentials. UI connectionInfo validates the response shape, healthStore gates
connection on successful loading, and stompGateway strips the provided prefix once at reception.
Wire-log routingKey and lifecycle matching consume normalized domain keys. decoder validates domain
envelopes/routing only; it has no broker exchange knowledge. Removed subscriptions.ts and the
hardcoded decoder prefix. Stop/restart discards stale metadata responses and reconnect attempts.

Verification: ControlPlaneInfoControllerTest (non-default exchange and read denial), UI connectionInfo,
healthStore and stompGateway tests (verbatim subscription, normalization, failed load and stale work),
plus swarmLifecycleAction tests. No deployed broker connection is claimed.

## RESP-CONTROL-SCHEMA-BOOTSTRAP — Control Plane schema delivery

**Owner:** Orchestrator `ControlPlaneSchemaBundle` projects the canonical schema resources
packaged by control-plane-core into one compound JSON Schema and owns its content digest.
`ControlPlaneSchemaController` authorizes and maps HTTP/cache responses; UI `schemaRegistry`
owns loading, cached validator state and compilation with Ajv.

**Contract:** `docs/ORCHESTRATOR-REST.md` section 5.3. The projection preserves the root
and embedded lifecycle schema IDs/references and never edits their validation rules.
UI disables only Ajv's `strictTypes`/`strictRequired` authoring lint, because these canonical
schemas compose type/property constraints through refs and conditionals; instance validation
(including types, required properties, formats and enums) remains enabled.

**Forbidden:** independent schema copies, constraint rewriting, remote schema fallback,
starting STOMP when schema compilation fails, or treating a root-only digest as bundle identity.

**Required effect:** Canonical lifecycle refs compile in the browser; malformed events remain
rejected. Conditional requests reuse the validator only for identical complete schema content.

## RESP-ARTEMIS-CONFIGURATION

`ArtemisConnectionSettings`, `ArtemisInputSettings` and `ArtemisOutputSettings` in
`common/artemis-adapter` own validation of their disjoint typed values. The input
and output records are the immutable Work settings and bound configuration values.
`ArtemisSettingValues` owns shared scalar rules. `ArtemisWorkIoType` owns ARTEMIS
selection identity; `ArtemisEnvironmentKeys` owns its setting/property key literals.
They must not open connections, reconstruct topology or independently select an
adapter. `ArtemisConfiguration` provides the public parser/policy projection,
`ArtemisInputSettingsParser` and `ArtemisOutputSettingsParser` for boundary maps,
and `ArtemisInputTuning`/`ArtemisOutputTuning` for AUTHORING without physical destinations.
All scalar rules remain in ArtemisSettingValues; consumerWindowBytes and persistent
are explicit required values, including startup binding. `ArtemisConnectionEnvironment`
owns connection property/ENV mapping; `ArtemisWorkBootstrapEnvironment` combines
owner-resolved destinations with authored tuning and exports that same resolved result.
Per-worker overrides of owned Artemis connection/destination/tuning fields are rejected.
Spring composition supplies these existing ports only for explicit adapter selection;
parsing a scenario never opens a broker connection.
Contract: `docs/architecture/work-plane-boundaries.md#11-artemis-adapter--approved-implementation-slice-2026-09-15`.

## RESP-ARTEMIS-CONNECTION

`ArtemisSessions` owns the Core locator, session factory and opened session lifetime.
Construction validates/configures the client without opening a broker connection;
the first explicit session request opens the factory, which subsequent requests reuse.
A failed initial connection fails that WORK operation. A later explicit operation may
attempt its own connection; there is no background retry, readiness wait or failover.
Closing an unused or used owner is terminal and must not open a connection.
`ArtemisWorkPlane` is the explicit composition entrypoint returning existing Work
ports; it closes the owned infrastructure. Orchestrator/Controller port composition,
configuration export, name resolution and removal-target mapping need no live Artemis.
CONTROL startup still requires Rabbit independently; Artemis availability is checked
by operations that use it. This deferred activation was approved for A3-REV-1 on
2026-09-15 instead of adding an Artemis startup dependency/profile in Compose.
Broker client types must not escape to SDK/services. No per-message connection,
implicit Rabbit fallback or swarm lifecycle state.

## RESP-ARTEMIS-RESOURCE-NAMES

`ArtemisResourceNames` owns physical channel names and resource URI encoding/decoding.
`ArtemisResourceKind` owns the supported native kinds and owner check.
`ArtemisWorkTopologyResolver` projects those values into ResolvedWorkTopology and
WorkChannelAddress for transport, ENV, status and resource operations. It must not
create resources or maintain a second mutable topology registry.

## RESP-ARTEMIS-RESOURCES

`ArtemisWorkResources` implements WorkPlaneResources using Core resource operations,
including observations and removal. It obtains its reusable resource session only
when ensure/observe/remove performs broker I/O, after validating the request. An
unavailable broker is an operation error, never an absent resource or successful
removal. `ArtemisManagement` owns bounded Core management
request/response encoding for native resource and debug-tap operations, using the library management address
and requiring a successful reply; it does not discover targets. The resource owner's
applied set is only a receipt for completed
bindings during partial prepare, following the existing WorkPlane contract. Broker
state is read live; absence differs from an observation error. It must not construct
physical names, persist manifests, implement orphan cleanup or decide swarm outcomes.

## RESP-WORK-ARTEMIS-TRANSPORT

`ArtemisWorkInputChannel` owns subscription state, canonical WorkItem decoding and
delivery settlement after callback return. Its channel state is a read-only projection
of handler registration and the native consumer lifetime: a closed consumer cannot
remain RUNNING. Explicit start discards a dead subscription and either opens a new
one or fails; it does not enable automatic recovery. AR-REV-2 correction approved
2026-09-15. The subsequent approved uniform-admission correction pauses SDK admission
before channel stop and distinguishes not-submitted deliveries from accepted tasks.
Native acknowledgements are individual: consuming a later malformed or admitted
message must never settle an earlier unaccepted message (WA-REV-1 correction).
`ArtemisWorkOutput` owns canonical
encoding and sends to its captured resolved address. Their transport factories
consume typed settings and return the existing Work ports. They must not own worker
state/execution, select fallback adapters, merge broker headers or add another result
publication. A3 supplies service/SDK activation. RESP-WORK-DELIVERY owns neutral delivery intent; the Artemis output realizes its native scheduled timestamp.

ArtemisWorkDebugTaps opens diagnostic copies of owner-resolved channels;
ArtemisWorkDebugTap owns the native non-exclusive divert, temporary capture queue,
message TTL/ring limit and explicit release. Its exact capture-address settings
must explicitly disable expiry forwarding: expired diagnostic copies are discarded,
including when the broker supplies a wildcard expiry address. The source WORK
expiry policy remains unchanged. It never consumes the source queue.
ArtemisResourceNames owns capture names. Request expiry remains with the existing
Orchestrator debug service. Explicit close releases the divert, queue, address and
address settings; it reports management failures. This is not a crash-recovery or
orphan-cleanup mechanism: broker-side divert/settings may remain after process loss.
Both Rabbit and Artemis observations currently omit oldest-message age.

## RESP-RUNTIME-FILESYSTEM-LAYOUT

**Current module:** `common/control-plane-filesystem`; environment names and container
root are declared by `RuntimeFilesystemContract` in `common/swarm-model`.

`RuntimeFilesystemLayout` owns validated swarm, run, startup, remove-operation,
swarm-journal and worker-output paths. `swarmJournalFile` owns the journal artifact
name and derives it from the validated swarm/run directory. Worker outputs use `<root>/<swarmId>/<runId>/outputs/<workerInstance>`.
The local and published views derive from the same relative path; consumers must not
reconstruct that path or introduce a second output root. `RuntimeFilesystemMount`
owns host-to-container mounts. The layout does not create, read or delete files.

`FilesystemSwarmRemoveStore.deleteSwarmRuntime` remains the existing deletion owner
for the complete swarm tree, including worker outputs. File observers must collect
content before REMOVE. Clearing exporter composition consumes this projection directly; scenarios do not own
the output root. `RuntimeOutputDirectory` is its immutable projection for resolving
relative file names inside a single worker output directory.

**Forbidden:** environment discovery, file IO or lifecycle decisions in the layout;
consumer-local reconstruction of output paths; a second output-directory cleanup owner.

**Verification:** `RuntimeFilesystemLayoutTest`, `FilesystemSwarmRemoveStoreTest`.


## RESP-SWARM-FILE-JOURNAL

**F04 file slice:** `FileSwarmJournal` in swarm-controller remains the append owner.
`SwarmFileJournalQuery` in Orchestrator delegates run selection to
`SwarmJournalRunSelector` and reads through the `SwarmJournalFiles` port;
`FileSwarmJournalReader` implements file discovery, reading and decoding. Both file implementations consume `RuntimeFilesystemLayout` paths;
neither reconstructs the journal filename or run path. Swarm tree deletion remains
with `FilesystemSwarmRemoveStore`, not the reader or writer.

The query preserves existing selection: an explicit nonblank run wins, otherwise
use the registry's active run, otherwise the most recently modified directory.
A selected run with no journal does not cause a second selection. This is existing
file-query behavior, not a new recovery policy. The file reader preserves append
order, empty files, severity matching, and skipping malformed lines. Missing or
unreadable files return the existing absence result. Run identifiers now use the
same layout validation as the writer; invalid run paths are not read and retain
the endpoint's existing exception-to-500 mapping. No new HTTP error contract is added.

`SwarmJournalController` authorizes access and maps the file query to HTTP; it must
not discover runs on disk or read/decode journal files. Capture writes now follow
RESP-JOURNAL-WRITES below. Sink selection, severity normalization, Postgres
behavior and public response shapes are unchanged.

**Forbidden:** file-query state writes, independent path/default resolution,
reader-owned retention, changes to append/ACK semantics, or merging Hive and swarm
journal contracts.

**Verification:** `RuntimeFilesystemLayoutTest`, `FileSwarmJournalTest`,
`FileSwarmJournalReaderTest`, `SwarmFileJournalQueryTest`, `SwarmJournalControllerTest`,
`PostgresJournalStorageTest`, `OrchestratorAdminAuthTest` and existing swarm-tree
removal tests. SQL event reads follow RESP-JOURNAL-EVENT-QUERIES below; run lists
follow RESP-JOURNAL-RUN-QUERIES. Archive writes and retention follow RESP-JOURNAL-WRITES.


## RESP-JOURNAL-EVENT-QUERIES

**F04 event-read slice:** `common/journal-postgres` owns the `JournalEventQueries`
port and `JournalPageResponse`/`JournalCursor` projection in its `api` package.
`PostgresJournalEventQueries` owns event SELECTs, live/archive row decoding and
cursor construction. `JournalEventRowMapper` is the sole SQL-event-to-timeline/page
mapper; it keeps the existing distinction that only paged entries expose eventId,
and the existing null timestamp handling for each projection. These are read-only
projections of journal storage, not a second event/state authority.

Orchestrator composes the adapter with its existing JdbcTemplate and ObjectMapper.
`SwarmJournalRunSelector` is the single owner of explicit/registry/observed run
precedence for both file and SQL reads. Each query supplies only its adapter observation.
`SwarmStoredJournalQuery` owns pinned-read precedence and the existing
registered-vs-unknown swarm absence semantics. It delegates all event SQL
to the port. The pin endpoint also uses this same run resolver. Hive queries retain
explicit optional swarm/run filters and do not inherit swarm run selection.
Controllers authorize and normalize HTTP parameters, invoke the query/port, and
map results; they no longer decode SQL event rows or calculate next cursors.

Preserve: newest-first `(ts,id)` pages with limit+1 lookahead; oldest-first swarm
timelines; filters; pinned capture precedence; empty archived reads vs absent live
reads for unknown swarms; existing best-effort lookup failures and JSON-map parsing;
HTTP statuses, payload fields, and live/archive write behavior. This extraction
adds no fallback or adapter-selection policy. `JournalPageResponse` and its cursor
move internally without adding a second wire shape or changing serialization.

Run listing/summary merging follows RESP-JOURNAL-RUN-QUERIES below.
Metadata/pinning writes and retention follow RESP-JOURNAL-WRITES. They remain
separate capabilities from the read ports.

**Forbidden:** lifecycle/registry state, authorization, run-selection policy or
writes in the SQL reader; SQL/row mapping in event query consumers; new retention
or pinning policy; merging Hive and swarm semantics into one state machine.

**Verification:** JournalEventRowMapperTest, PostgresJournalEventQueriesFailureTest,
PostgresJournalEventQueriesTest (real Postgres), SwarmJournalRunSelectorTest,
SwarmStoredJournalQueryTest, existing PostgresJournalStorageTest, HTTP mapping/auth
tests and RepositoryImportBoundaryTest. Deployed E2E is separate.


## RESP-JOURNAL-RUN-QUERIES

**F04 run-list slice:** `JournalRunQueries` in `common/journal-postgres/api` exposes
per-swarm and deployment-wide run lists and the existing metadata summary read.
`PostgresJournalRunQueries` owns their SQL. `JournalRunRowMapper` owns summary row
mapping and persisted tags decoding. `JournalRunSummaries` owns the read-only merge
of live and pinned summaries by `(swarmId,runId)`; it must not alter stored state.
`SwarmRunSummary` and the smaller `JournalRunSummary` retain their existing JSON
fields as named read projections, moved from controller-nested types to the API.
The smaller projection is derived from the merged summary, never independently merged.

Orchestrator authorizes and normalizes list requests. SwarmStoredJournalQuery
retains the per-swarm empty/unknown distinction using SwarmStore; the SQL module
never accesses the registry. The metadata update endpoint consumes the same summary
reader after its existing write, eliminating a second mapper without changing writes.

Preserve the existing details: per-swarm reads live before pinned; global reads
pinned before live; pinnedOnly ignores afterTs and preserves SQL NULLS LAST order;
afterTs filters only live events before aggregation; the SQL live limit precedes
the global merge/final limit. Merged ordering retains the existing reversed
nullsLast comparator (null lastTs first). Pinned firstTs/metadata win when present;
only a newer live lastTs (or a null pinned lastTs) triggers replacement, using the
larger entry count. These rules describe inherited behavior, not new fallback policy.

**Forbidden:** summary state writes, lifecycle decisions, SQL in list consumers,
consumer-local tag parsing/summary merging, or changes to pinning/retention policy.
Metadata writes and run identity lookup, capture/pinning writes and retention follow
RESP-JOURNAL-WRITES; controller contract bags have been removed. No migration or wire change.

**Verification:** pure merge and mapping tests; real PostgreSQL listing tests for
pinned/live overlap, shared run IDs across swarms, null timestamps, afterTs/limits,
pinnedOnly and metadata-only summaries; existing journal/auth/file regression tests.


## RESP-JOURNAL-WRITES

**F04 implemented and reviewed:** `common/journal-postgres`
owns separate metadata, capture and retention ports. `JournalRunMetadata` owns both
startup registration and operator edits; `PostgresJournalRunMetadata` is their sole
SQL writer. Startup registration retains its best-effort semantics and scenario-id
projection from the Orchestrator template via `JournalRunRegistration`. Operator updates retain metadata/event/
capture identity lookup precedence, ambiguity handling, tag trimming/deduplication
and limits, null-body clearing, and the shared `JournalRunQueries` response projection.
No new transaction, identity rule or recovery path is introduced.

`JournalCaptures`/`PostgresJournalCaptures` own mode parsing, capture creation,
archive copying and capture-stat refresh. The request/response records move out of
REST without wire changes. `SwarmJournalPinning` resolves the selected run through
the existing shared selector before calling the capture port; REST only authorizes
and maps success, absence, mode conflict and storage errors. Preserve default/invalid
mode => SLIM, FULL/SLIM/ERRORS_ONLY behavior, repeated pin idempotency, existing
mode-conflict response, statement order and existing nontransactional semantics.
These inherited policies are not new compatibility or fallback mechanisms.

`JournalRetention`/`PostgresJournalRetention` own partition naming, creation,
batched default-partition rehoming and retention deletion. Orchestrator's `JournalRetentionSchedule`
only calls the port. `JournalRetentionSettings` owns effective bounds;
Spring composition binds the existing environment/property defaults once. Preserve
UTC day calculations, phase order, cutoff comparisons, exception/logging policy,
partition name recognition and pinned archive survival. No change to retention
algorithm or concurrent pin/retention semantics is included.

**Append/query/retention separation:** existing `BufferedPostgresJournalWriter` is
the sole journal_event INSERT/buffering/backpressure implementation. HiveJournal
and SwarmJournal retain distinct producer contracts and project their own events
into that writer; query ports are read-only projections. Only retention performs
partition/default-row deletion and only captures write pinned archives/statistics.
Metadata registration and edits share one SQL owner, updating distinct fields.
File journal reads/writes/deletion continue to use RESP-RUNTIME-FILESYSTEM-LAYOUT;
exporter directory behavior is not reopened. Database bootstrap stays with existing
Flyway migrations; this extraction introduces no schema or migration change.

**Forbidden:** journal SQL in REST or lifecycle consumers; capture state or outcomes
constructed by controllers; another tag normalizer, partition naming/retention owner,
or a shared Hive/swarm lifecycle state machine. No module/bean identity tests.

**Acceptance:** existing append/read/pin regressions plus real PostgreSQL tests for
metadata registration/edit/clearing and ambiguity, all capture modes/conflicts/repeat,
retention cutoffs, default rows and archive survival; behavior tests for normalization,
run selection and failure mapping; repository import rules and targeted module suites.


## RESP-WORK-DELIVERY

**Owner:** `work-config` owns `WorkDelivery`, its modes, validation, default and
`WorkDeliveryEnvironment` projection. See the approved [delivery contract](work-plane-boundaries.md#12-delayed-work-delivery).
`WorkIoType` declares the adapter's delivery capability; Artemis alone currently
supports DELAYED. Scenario validation and direct transport calls consult that same declaration.

**Consumers:** `WorkConfigurationParser` aggregates the neutral parser with adapter
settings. Controller worker composition exports the validated policy. SDK startup
binding delegates scalar parsing to the same owner and retains the immutable result
in `WorkIoBindings`. Candidate validation compares against that startup value before
accepted worker state changes. `WorkOutputRegistry` passes it alongside each result
through the existing `WorkOutput` port. `ArtemisWorkOutput` alone converts it to a
native scheduled delivery timestamp. `work-api` exposes that shared type; it does
not define another delivery DTO or serialize intent into WorkItem.

**Forbidden:** duplicated delivery defaults/parsers, inherited per-hop delivery headers,
worker timers, second publication paths, unsupported-mode fallback, or changes to
input admission/ACK and failure consumption.


## RESP-WORK-AUTH-HTTP-HEADERS

**Current module(s):** `common/worker-sdk`.

AuthHttpHeaders owns replacement of a single HTTP authentication header. AuthRuntime
supplies the already-resolved header name and value. Replacement removes all
case variants before writing the requested name, so an outgoing request has one
effective credential header. There is no second profile validator or token source.

**Forbidden:** acquire credentials, discover or validate profiles, modify unrelated
headers, construct HTTP requests or log credential values.

**Required effect:** Existing case variants cannot retain stale credentials;
nonmatching headers and the caller's source map retain their existing behavior.

**Verification entrypoints:** `AuthHttpHeadersTest`, `AuthRuntimeTest`,
`HttpSequenceSecondPassAuthTest`.

**Migration status:** Header mutation moves out of the mixed AuthRuntime owner;
credential generation and non-HTTP application remain with existing owners.

## RESP-PROCESSOR-HTTP-REQUEST-LOG

**Current module(s):** `processor-service`.

HttpRequestDebugLog owns processor request DEBUG projection and emission.
HttpProtocolHandler delegates request diagnostics before sending the original
headers. It delegates typed credential/session header projection to HttpHeaderRedactor
and retains noncredential header diagnostics.

**Forbidden:** mutate transport headers, acquire or apply credentials, decide
request validity, or implement collected runtime-log redaction.

**Required effect:** No Authorization, Proxy-Authorization, Cookie or Set-Cookie value reaches
per-header or aggregate processor request DEBUG arguments; outbound headers are
unchanged. Arbitrary URL/body redaction is not claimed by this header boundary.

**Verification entrypoints:** `HttpRequestDebugLogTest`, `HttpAuthSecondPassSecurityTest`.

**Migration status:** Request diagnostic responsibility extracted from
HttpProtocolHandler; broader HTTP result and transport behavior unchanged.

## RESP-HTTP-SEQUENCE-REQUEST-RENDERING

**Current module(s):** `http-sequence-service`.

HttpSequenceRequestRenderer owns step request-context assembly and rendering,
then delegates credential application to the supplied AuthRuntime. The runner
retains ordered execution, retry and runtime lifetime.

**Forbidden:** discover profiles, own runtime resources, parse templates,
execute transport calls or decide retry policy.

**Required effect:** Existing rendered method/path/body/headers and step context
reach the executor with the canonical auth application behavior.

**Verification entrypoints:** `HttpSequenceRequestRendererTest`,
`HttpSequenceRunnerTest`, `HttpSequenceSecondPassAuthTest`.

**Migration status:** Existing renderCall responsibility extracted from the
mixed runner before applying journey resource scoping.

## RESP-WORK-AUTH-RESOURCES

**Current module(s):** `common/worker-sdk`.

AuthRuntimeResources owns factory-created TokenStore/HttpClient allocation and
cleanup; injected resources are borrowed. AuthRuntime delegates resource lifetime
to this owner. Construction failures release acquired resources. Close releases
all owned resources even when an earlier close fails, retaining interruption and
failure information. RedisTokenStore remains owner of its client/connection pair
and shuts down its client if connection creation fails.

**Forbidden:** discover or validate profiles, apply credentials, acquire tokens,
change Redis records or refresh leases, or close borrowed test/integration resources.

**Required effect:** Completing a request/journey releases its runtime's connections
without deleting cached credentials or interfering with another runtime's resources.

**Verification entrypoints:** `AuthRuntimeLifecycleTest`, `RedisTokenStoreTest`,
`HttpSequenceSecondPassAuthTest`.

**Migration status:** Resource ownership extracted from mixed AuthRuntime as part
of the inherited Redis-retention fix. Runtime factory API behavior is preserved.


## RESP-MCP-WORKFLOW

**Current module(s):** `pockethive-mcp-service`.

ScenarioWorkflow owns scenario authoring transitions and their revision/generation
preconditions. Upload tickets bind to the prepared workflow revision, generated
file-set digest and capability fingerprint. Validation/publication evidence may
advance only that bound generation; an intervening update invalidates completion.

**Consumers:** ScenarioWorkflowToolExecutor, BundleToolExecutor and the upload lifecycle.

**Forbidden:** perform archive upload, remote owner calls or persistence.

**Required effect:** Older owner evidence cannot validate or publish newer authored state.

**Verification entrypoints:** ScenarioWorkflow and workflow upload lifecycle regressions.

## RESP-MCP-UPLOAD-LIFECYCLE

**Current module(s):** `pockethive-mcp-service`.

CoordinationWorkflowUploadLifecycle applies verified owner evidence through
ScenarioWorkflow's bound-generation preconditions and atomically persists the
result against the observed revision. It never substitutes the current generation
for the identity captured when an upload ticket was prepared.

**Forbidden:** own authoring transitions, upload archives or perform publication.

**Required effect:** A stale callback fails explicitly without overwriting newer state.

**Verification entrypoints:** Workflow upload lifecycle and stale-generation regressions.

## RESP-MCP-UPLOAD-COORDINATION

**Current module(s):** `pockethive-mcp-service`.

BundleUploadCoordinator owns ticket and archive integrity, owner-request
coordination and recording actual owner results. It carries immutable workflow
bindings to the canonical lifecycle and checks them before irreversible owner
publication. If an owner effect succeeds but workflow synchronization fails,
that divergence must be explicit rather than reporting a synchronized success.
Local ticket/attempt mutation, snapshot and persistence form one serialized step.
Owner and spool IO stay outside that step; their completion re-resolves canonical
state by identity. A rollback must not detach another operation from its stored
terminal result. If recording an owner result fails, report an unresolved outcome
with the attempt identity and preserve the state needed for restart recovery. Startup recovery
also examines retained publication attempts after obsolete tickets have been
retired by schema migration. Possible owner writes become AMBIGUOUS through the
canonical attempt state machine and remain reconcilable without replay. Interrupted
pre-owner RECEIVING/VERIFIED attempts become FAILED. PREPARED and terminal history
remain unchanged under existing retention; migration never invents generation
binding or reauthorizes retired workflow tickets.

**Forbidden:** implement scenario authoring transitions or owner bundle validators.

**Required effect:** Ticket evidence stays associated with its prepared generation.

**Verification entrypoints:** BundleUploadCoordinator and workflow upload regressions.

## RESP-MCP-COORDINATION-STATE

**Current module(s):** `pockethive-mcp-service`.

CoordinationStateRepository and its atomic adapter own snapshot persistence and
revision compare-and-save. They must reject a stale expected revision so that a
callback cannot overwrite a newer workflow snapshot.

**Forbidden:** decide authoring transitions or invent owner validation/publication evidence.

**Required effect:** Concurrent workflow updates are retained when stale completion is rejected.

**Verification entrypoints:** Atomic coordination repository concurrency regressions.

## RESP-HTTP-DIAGNOSTIC-HEADERS

**Current module(s):** `common/observability-core`.

HttpHeaderRedactor owns the pure diagnostic projection of HTTP header maps.
Both processor request logs and HTTP Sequence stored captures consume this policy.
Authorization, Proxy-Authorization, Cookie and Set-Cookie values are replaced
completely, case-insensitively. Scalar and multi-value shapes, key spelling and
safe value order are preserved in independent snapshots.

**Forbidden:** mutate transport headers, resolve credentials, execute IO, validate
profiles or claim redaction of arbitrary URLs, bodies or unstructured error text.

**Required effect:** Standard credential and session headers cannot reach either
diagnostic sink while sent requests and received responses remain unchanged.

**Verification entrypoints:** `HttpHeaderRedactorTest`, `HttpRequestDebugLogTest`,
`HttpSequenceDebugCaptureTest`.

## RESP-WORK-REDIS-DEBUG-CAPTURE

**Current module(s):** `common/redis-adapter`.

RedisDebugCaptureStore owns expiring diagnostic value writes and the lifetime of
its lazily allocated Redis client and single shared connection. It consumes
canonical RedisConnectionSettings and delegates client realization to
RedisConnections (RESP-REDIS-ADAPTER). Writes and close are serialized; close is
idempotent and attempts connection and client cleanup even if one fails. A failed
connection attempt releases acquired resources. No capture allocates resources
until an actual write; a closed owner cannot allocate again. Write failure remains
best effort and does not fail the worker journey.

**Forbidden:** select captures, project payloads, define service-specific keys,
resolve connection defaults, manage token/lease records or route business output.

**Required effect:** Capture resources remain bounded across worker threads and
are released by worker shutdown without deleting captured records before TTL.

**Verification entrypoints:** `RedisDebugCaptureStoreTest`, `HttpSequenceDebugCaptureTest`.

## RESP-HTTP-SEQUENCE-DEBUG-CAPTURE

**Current module(s):** `http-sequence-service`.

HttpSequenceDebugCapture owns the existing capture key and JSON projection,
including configured request/response inclusion and body truncation. Header
projection delegates to HttpHeaderRedactor. HttpSequenceRunner retains capture
selection and journey budgets, delegates expiring storage to
RESP-WORK-REDIS-DEBUG-CAPTURE in redis-adapter and
closes it. HttpSequenceWorkerImpl owns runner and pooled HTTP client shutdown;
both resources are attempted even if one close fails.

**Forbidden:** open Redis connections, implement another header redactor, change
auth schemas or profile resolution, or change debug capture defaults.

**Required effect:** Capture shape, selection and TTL remain compatible, standard
credential/session headers are redacted, and worker-owned resources have a close path.

**Verification entrypoints:** `HttpSequenceDebugCaptureTest`, `RedisDebugCaptureStoreTest`.

## RESP-REDIS-ADAPTER

**Current implementation transfer:** F01, common/redis-adapter.

RedisConnections is the single settings-to-client realization owner. Redis list
read/write, token storage, expiring diagnostics and sequences execute inside this
module. Only validated redis-config settings enter it; raw Lettuce clients and
command callbacks are internal. Existing token/sequence ports remain canonical.
List writers now have an explicit close operation owned by their SDK factory or
interceptor; closing never changes message publication, routing or retry policy.
TokenStore.listDueRefreshes lists candidates, not leases; claimRefresh alone claims.

SDK RedisSequenceConfiguration composes application-owned sequence instances and
validated updates; no process-global selection remains. Formatting semantics,
sequence key construction, token claims, diagnostic best-effort writes and list
push-then-trim ordering are preserved. Rejected configuration is not applied.

**Forbidden:** SDK/templating/service Lettuce imports; duplicate connection parsers;
implicit global Redis selection; converting diagnostic failure to business failure.

**Verification:** adapter operation/lifetime tests and SDK/HTTP Sequence integration;
RepositoryImportBoundaryTest rejects direct vendor imports outside the adapter.

Redis resource shutdown is terminal: the SDK writer and sequence owners allow
concurrent operations while open, wait for operations already inside their API
before releasing clients, and reject subsequent operations without creating clients.
This does not drain the worker executor or change stop/ACK/redelivery semantics.
An accepted WorkItem reaching Redis only after this owner has closed receives the
existing caller's operation-error handling. Cleanup still attempts all cached resources.

## RESP-DOCKER-RUNTIME

**Current modules:** `common/manager-sdk`, `common/docker-client`, `orchestrator-service`,
`swarm-controller-service`.

`DockerRuntimeClient` owns raw Docker inventory, inspect, log retrieval and explicit
force-container/service removal operations. `DockerRuntimeResource` is its read-only
inventory projection; `DockerRuntimeKind` identifies the Docker resource kind.
`DockerRuntimeAdapter` maps this projection to existing Orchestrator ports and
selects the already-resolved compute mode. It does not issue Docker commands.

**Forbidden:** decide cleanup eligibility, approvals, lifecycle completion or change
compute selection. Removal exceptions propagate unchanged; command completion is
not a new domain-level verification of absence. Inspect retains the application
ObjectMapper configuration and existing REST response shape. `ComputeRuntimeDebugPort`
and its read-only `RuntimeInspection`, `RuntimeInspectionState`, `RuntimeMountInspection`
values live in manager-sdk. `DockerInspectMapper` alone interprets Docker inspect
fields, including existing alias precedence and scalar values. No raw Docker inspect
map leaves docker-client. `RuntimeInspectResponseMapper` in Orchestrator owns HTTP
response fields and source redaction; target eligibility remains in RuntimeDebugService.
The mount projection records whether propagation was reported, preserving the existing
container field versus service omission. Existing container RW inversion is preserved
as diagnostic behavior; correcting it is not part of this extraction.

**Connection, compute and naming ownership:** `DockerEngine` owns one application-scoped connection
and construction of compute/runtime implementations. `DockerConnections` owns SDK
configuration and client realization. Orchestrator retains environment-based daemon
configuration and AUTO manager detection; Controller retains explicit host/socket
selection and requires a concrete mode. No new probing or selection fallback is added.
`ComputeHost` exposes network resolution/image pull without container or SDK types;
service lifecycle consumers receive this port and `ComputeAdapter` rather than raw clients.
`DockerControllerEnvironment` owns Docker-specific controller ENV and socket-mount
encoding. `DockerRuntimeNames` owns the existing stack-name rule; all four consumers
use its result, including status metadata and Docker labels. Existing trimming at
caller boundaries stays unchanged. Docker SDK/model/implementation imports are forbidden
outside docker-client (legacy E2E remains explicitly excluded). The uncalled
DockerWorkloadProvisioner/WorkloadProvisioner path is removed.

Lifecycle decisions, service-drain behavior, cleanup approvals/postconditions, image
repository resolution and CP transport ownership remain at their existing owners.
ClickHouse ENV and journal layout are transferred by F05/F04; worker freshness remains
deferred to F08. Implementation/verification progress is recorded in the plan.


## RESP-CLICKHOUSE-INSERT

**Module:** `common/sink-clickhouse`.

**F05 implementation (reviewed):** `ClickHouseJsonEachRowTransport` owns
HTTP client construction, INSERT URL encoding, JSONEachRow framing, Basic auth,
timeouts and the 2xx success condition. `ClickHouseInsert` is its prepared-operation
port: resolve the destination once before draining a flush, then send each batch.
`ClickHouseConnectionSettings` is a read-only view implemented by the existing
transaction and metrics properties; it has no defaults or independent state.
`ClickHouseInsertException` owns bounded failure-body presentation. Metrics retains
its existing diagnostic prefix and truncation marker.

`ClickHouseMetricsSink` retains metrics projection, label validation, bounded queue,
flush timing and requeue policy. `ClickHouseTxOutcomeSink` retains transaction
serialization, its own bounded queue/flush policy and best-effort shutdown flush.
The transaction sink starts its flush clock at zero; metrics starts at construction.
Transaction batch/interval/capacity clamps remain local existing behavior; metrics
properties retain their validation. Neither policy is silently unified.

**Forbidden:** consumer construction of ClickHouse HTTP requests, URLs, credentials
or INSERT statements; transport-owned domain events, buffers or retry scheduling.

**Required effect:** identical URL, UTF-8 body, auth and timeout settings; rejected
HTTP batches remain queued under the existing sink policy. Preparing an invalid
URI fails before draining. The transport adds no retries or configuration defaults.

**Verification:** transport request/response tests, both sink behavior tests,
launch environment tests; deployed DA-3 is the persistence acceptance path.

## RESP-CLICKHOUSE-ENVIRONMENT

**Module:** `common/sink-clickhouse`.

**F05 implementation (reviewed):** `ClickHouseSinkEnvironment` owns the
transaction sink ENV names and export from `ClickHouseSinkProperties`, including
endpoint/table/credentials/timeouts/batching/capacity. ContainerLifecycleManager
and SwarmWorkerSpecFactory apply this projection at their existing launch step.
`ClickHouseMetricsEnvironment` separately owns the metrics field mapping, exposing
runtime and controller-inheritance projections over the same properties. Configured
metrics entries overwrite the destination; blank credentials are omitted. These
existing rules deliberately differ from transaction-sink apply-missing semantics.
ControlPlaneContainerEnvironmentFactory consumes both projections without mapping
ClickHouse fields itself.

**Required effect:** unconfigured properties export nothing; configured values are
trimmed and blank values omitted. An existing key, even blank or null, wins. The
codec mutates only missing sink entries in the supplied environment; no defaults
or validation are added. Property classes remain the configuration authorities.
Spring binds the existing ENV names directly; service YAML must not duplicate
ClickHouse defaults or field aliases. Nested Orchestrator and Controller metrics
constructors explicitly name their ClickHouse parameter `clickhouse` via Spring's
`@Name`, preserving the public property prefix independently of Java camel-case.
Controller metrics binding is a separate `SwarmControllerMetricsProperties` unit.
Direct sink properties and full nested service configurations are tested separately,
including source precedence, absent values and existing validation. No new parser,
configuration defaults or compatibility path is added.

**Forbidden:** service-local sink ENV mapping or independent effective settings.

**Verification:** codec value/precedence tests, full nested service bootstrap tests
with actual application YAML and original ENV names, and both launch-path behavior
tests. Deployed DA-3 proves persisted outcomes; execution evidence lives in F05 of
`docs/inProgress/functional-module-boundaries.md`.

## RESP-TCP-MOCK-NOTIFICATIONS

**Current module(s):** `tcp-mock-server`.

NotificationService owns the mock UI's global, in-memory notification feed: IDs,
creation time, newest-first retention, unread state and clear/read operations.
NotificationController maps the existing `/api/notifications` HTTP surface and
delegates. Notification is a read-only response projection; NotificationRequest
is the existing request shape. No consumer may mutate the stored state.

Preserve the active controller semantics: IDs start at 1 and are not reset by
clear; creation uses Instant.now; retain the newest 100 entries; missing IDs on
mark-read succeed; mark-all-read and clear succeed on an empty feed. The accepted
`persistent` field remains ignored. The feed remains global, without username
filtering, persistence or new validation. JSON fields and HTTP statuses do not change.
The unused former per-user NotificationService/model are replaced, not retained
as a second owner. The UI's browser-local notifications are presentation state,
not an alternate backend store. Existing weakly consistent concurrent iteration
and retention operations are not redesigned by this extraction.

**Forbidden:** controller-owned collection/ID/read state, transport or user/auth
policy in the service, or writable response aliases to stored state.

**Verification entrypoints:** NotificationServiceTest for ordering, retention,
read/clear state, IDs and detached projections; NotificationControllerTest for
existing JSON shape and HTTP return values through the controller and real service.

## RESP-TCP-MOCK-WORKSPACES

**Current module(s):** `tcp-mock-server`.

WorkspaceService owns the global, in-memory mock UI workspace catalogue.
WorkspaceController maps `/api/workspaces` and delegates all state changes.
Workspace and WorkspaceRequest carry the existing wire fields; service copies
mutable boundary values on entry/exit, so they cannot mutate stored state.
The unused former member/user-aware service/model are replaced, not merged into
the active behaviour. Browser workspace state is a presentation cache.

Preserve the active controller semantics: initial default workspace, timestamp
IDs prefixed `ws-`, create owner `current-user`, HashMap iteration/storage, and
no validation or uniqueness repair. Delete rejects only the path ID `default`
with HTTP 400; missing other IDs return 200. Update is an upsert keyed by the
path ID and preserves the body ID (even if different), name, owner and shared
flag; it also permits replacing the default entry. These are existing policies,
not new recommendations. No permissions/membership, persistence, timestamp
metadata or concurrency redesign is introduced. HTTP fields/statuses stay unchanged.

Approved follow-up correctness fix: Workspace provides a no-argument constructor
for Jackson field binding. The inherited PUT decode failure is corrected without
changing field names, catalogue policy or adding required-field validation.
Missing strings remain null and missing shared remains false.

**Forbidden:** controller-owned catalogue, independent ID/default/deletion policy
in Java consumers, or writable aliases to stored state.

**Remaining F07 debt:** `static/workspace.js` still repeats default workspace data
on load failure and blocks default deletion locally. That is an existing UI policy
copy, not proof of end-to-end SSOT completion. Removing its fallback and consuming
owner-derived policy requires a separate UI/contract slice; this backend extraction
does not change it.

**Verification entrypoints:** WorkspaceServiceTest for catalogue transitions and
isolation; WorkspaceControllerTest for existing wire fields and response codes.
