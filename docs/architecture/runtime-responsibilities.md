# Current runtime responsibility records

Status: B01 adoption accepted in separate review, 2026-09-08.
RV2 descriptions/headers agree with the inspected source. Current review evidence:
`docs/inProgress/boundary-design/b01/rv2-correction-review.md`.
RV1's wiring-test requirement remains superseded by the human boundary-verification
policy in `docs/REVIEW_RULES.md`. Implemented B02 transfers and their individual review
status are tracked in `docs/inProgress/boundary-design/b02/README.md`;
remaining B02–B07 work is still open.
This is the canonical current-owner record for the 157 production files in the B01
adoption scope and the B02 transfers recorded below. The [boundary design](work-plane-boundaries.md) owns target module
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

## RESP-RABBIT-CONNECTION

**Current module(s):** `common/rabbit-config` owns the immutable
`RabbitConnectionSettings` contract and `RabbitConnectionEnvironment` encoding.
The contract covers the existing container connection fields: host, port, username,
password and virtualHost. All are required; port is 1–65535. Values, including
credentials, are preserved exactly. The settings' text representation hides credentials.

`RabbitConnectionConfiguration` in control-plane-spring is a Spring bootstrap decoder
explicitly imported by Orchestrator and Controller. It binds the shared record from
`spring.rabbitmq` without constructing a defaulted RabbitProperties object. Validation
belongs to the record; the decoder does not reimplement it. Spring still owns client
construction and its other transport options. This transfer covers the existing five-field
container export, not TLS/address-list propagation or Work delivery policy.

ContainerLifecycleManager and SwarmLifecycleManager/SwarmWorkerSpecFactory receive the
immutable settings. ControlPlaneContainerEnvironmentFactory composes the shared encoder's
result with participant settings; it no longer validates or encodes Rabbit fields.

**Forbidden:** Spring or Rabbit client imports in rabbit-config; service-local connection
validation/environment encoding; credentials in settings text; topology or delivery policy
in this connection contract.

**Implemented effect:** Base connection settings reject missing/blank fields and
out-of-range ports at construction. The shared encoder preserves those validated values
in the participant environment before later composition. Parsing/export opens no connection.

SwarmWorkerSpecFactory validates the five final connection fields after `bee.env`
composition through RESP-WORK-CONNECTION-ENVIRONMENT. RabbitConnectionEnvironment
decodes property text and delegates constraints to RabbitConnectionSettings. Final
environment validation leaves the checked snapshot unchanged; encoding remains the
base-container projection. The worker planner must not implement a second validator
or silently replace an invalid override with the base value. Other Rabbit transport
options, full Work candidate validation and configuration sources beyond bee.env
remain B02 work; this five-field contract does not cover TLS or address-list settings.

**Verification entrypoints:** `RabbitConnectionSettingsTest`,
`RabbitConnectionEnvironmentTest`, `RabbitConnectionConfigurationTest`, existing
`ControlPlaneContainerEnvironmentFactoryTest`, `ContainerLifecycleManagerTest`,
`SwarmLifecycleManagerTest`, `SwarmWorkerSpecFactoryTest` and the single import test.

**Migration status:** B02 connection export implemented and behaviorally verified, pending
separate review. Complete Work settings/candidate parsing,
upstream sample defaults and other Spring transport options remain outside this transfer.

## RESP-WORK-REDIS-ROUTES

**Implemented transfer (B02), pending separate review:** `common/work-config` owns decoded Redis route declarations
and their parsing/validation through RedisConfigurationParser. RedisRouteDefinition is the
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
`docs/inProgress/boundary-design/b02/redis-routes-transfer.md` records behavior tests and limits.
IO/connection/execution records, output target/connection constraints,
full candidate validation and all producer migration remain required before full B02
acceptance. This sub-transfer does not certify the whole configuration contract.

## RESP-REDIS-CONNECTION-SETTINGS

RedisConfigurationParser in `io.pockethive.work.config.redis` owns decoded Redis connection validation and
partial-update merging; RedisConnectionSettings is its immutable resolved value.
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
full token/sequence/capture authoring remains open. Redis client/URI construction stays in the existing
adapters pending B06. Existing sequence bootstrap defaults, global sequence ownership,
token/sequence scope composition and producer migration remain open B02/B06 work.

RedisConnectionEnvironmentCodec in `io.pockethive.work.config.environment` owns encoding connection candidates for
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
**Remaining B02:** connection bootstrap defaults/scope composition, full typed IO/candidate acceptance, producer
migration and original empty-YAML shape preservation. SEL-R1 stays user-deferred.
**Verification:** RedisWriteSettingsTest, WorkIOConfigBinderTest, RedisWorkOutputTest,
RedisUploaderInterceptorTest and RedisConfigurationValidationComponentTest.
Implementation evidence: `docs/inProgress/boundary-design/b02/redis-write-settings-transfer.md`.
Separate `redis-write-settings-review-2026-09-08.md` in that directory supports this
scoped transfer; full B02 remains open.

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
**Remaining B02:** connection bootstrap defaults/scope composition, full candidate validation before
Control Plane state/ack, producer migration and original empty YAML shape preservation.
**Verification:** RedisOutputTargetsTest, WorkIOConfigBinderTest, RedisWorkOutputTest,
RedisUploaderInterceptorTest and RedisConfigurationValidationComponentTest.
Implementation is followed by separate review under the active workflow.

## RESP-WORK-REDIS-SOURCES

**B02 transfer:** `common/work-config` owns Redis dataset source entries and collection
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

**Remaining B02:** connection bootstrap defaults/scope composition and rate settings,
full candidate validation and original empty-YAML shape preservation are not transferred
by this source-list change. Their existing duplicate decisions remain open debt.

**Verification:** RedisSourcesParsingTest, WorkIOConfigBinderTest,
RedisDataSetWorkInputTest and RedisConfigurationValidationComponentTest.
Implementation evidence: `docs/inProgress/boundary-design/b02/redis-sources-transfer.md`;
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
**Remaining B02:** complete IO/candidate validation before accepted Control Plane state,
connection bootstrap defaults/scope composition, timing settings and empty-YAML shape preservation. This adapter-side selection
gate does not certify the full candidate or change Control Plane acknowledgement order.
**Verification:** RedisDatasetSelectionTest, WorkPatchPolicyTest, WorkIOConfigBinderTest,
RedisDataSetWorkInputTest and RedisConfigurationValidationComponentTest.
**Review status:** SEL-R1 HIGH remains open and is deferred by the user; it does not
block plan continuation. See `docs/inProgress/boundary-design/b02/known-issues.md`.

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
[review evidence](../inProgress/boundary-design/b02/README.md#separate-rate-r1-correction-review--2026-09-09).

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

TIM-R1 correction: SchedulerWorkInput resolves its initial maxMessages from startup
properties once, then owns the accepted runtime long. Valid updates replace that value
only after both mutable settings pass canonical validation; they do not rewrite the
startup limit declaration. Ticks and diagnostics consume the accepted value without
parsing configuration. This corrects the existing consumer, without adding a state layer.

**Forbidden:** local timing/limit decoders or range repair, accepting a rejected setting,
or presenting metadata validation as an implemented scheduling/backlog effect.
**Verification:** parser boundary/error tests, startup binding, scenario diagnostics and
finite-run scheduling behavior. Full candidate acceptance and other IO settings remain B02.

## RESP-WORK-SCHEDULER-RESET

**B02 transfer accepted within scope on 2026-09-10:** `common/work-config`, `input.SchedulerResetParser` owns
the `inputs.scheduler.reset` value contract. WorkPatchPolicy, SchedulerWorkInput and
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

**Current module(s):** `common/worker-sdk`.

B02 input enablement transfer: input properties no longer expose `enabled`, and Rabbit
input properties no longer expose the unused `autoStartup`. RESP-WORK-INPUT-LIFECYCLE-POLICY
rejects those declarations at raw update, authoring, worker binding and worker planning
boundaries. WorkerState supplies initial disabled state and accepted control updates;
input adapters hold read-only enablement projections from its snapshots. Input-local
properties must not seed desired state. Other typed settings/candidate work remains B02.

PocketHiveWorkerProperties holds bound worker settings; WorkOutputConfig is the selected output settings contract. Existing WorkInputConfigBinder/WorkOutputConfigBinder perform startup binding using selection keys from work-config.

WorkConfigBindHandler rejects unknown/unrepresentable fields for both directions;
type-specific settings validation remains with the owning parser/properties during B02.
Input rates delegate to RESP-WORK-INPUT-RATE; scheduled input timing/limits delegate to
RESP-WORK-INPUT-SCHEDULE. Their property holders retain raw bound values so numeric
coercion cannot bypass the canonical parser; runtime accessors expose validated numbers.
Complete CSV settings delegate to RESP-WORK-CSV-SETTINGS; its adapter consumes one
immutable validated snapshot rather than decoding properties during intake.

Discovery and adapter factories consume bound settings. WorkerControlPlaneRuntime delegates IO mutability decisions to WorkPatchPolicy. Complete runtime candidate parsing and IO adapter parsing still have separate paths pending the rest of B02.

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
Workers start disabled in WorkerState and input registration receives that state before
intake. Only accepted worker-level control enablement updates may enable intake;
input properties and container environment must not provide a second enablement flag.
ControlPlaneNotifier derives results and applied configuration digests from accepted raw
state. Configuration logs and external status views consume RESP-WORK-CONFIGURATION-DIAGNOSTICS;
redaction must not change the state, adapter view or digest.

For Redis connection updates, candidate validation under RESP-REDIS-CONNECTION-SETTINGS
precedes accepted-state writes and reseeding; rejection preserves the previous state
also exposed to listeners. Complete Work candidate validation remains B02 work.

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
Its rate is supplied from RESP-WORK-INPUT-RATE accepted settings; the projection does not
revalidate that setting.

SchedulerWorkInput delivers updates; RateSchedulePolicy and TriggerSchedulePolicy implement distinct rate versus trigger policies.

**Forbidden:** read Control Plane directly, execute work or make the projection a configuration writer.

**Required effect:** Ordered updates reach the policy without consuming quota; plan consumes quota using monotonic tick time.

**Verification entrypoints:** `RateSchedulePolicyTest`, `TriggerSchedulePolicyTest`.

**Migration status:** Current B01 port; updates and planning serialize in each policy.

## RESP-WORK-SCHEDULER-SETTINGS

**B02 implemented; awaiting separate review:** `work-config.scheduler.SchedulerSettingsParser` owns the
complete five-field scheduler settings contract. It delegates rate and integer constraints
and omission defaults to RESP-WORK-INPUT-RATE and RESP-WORK-INPUT-SCHEDULE. Required
ratePerSec/maxMessages and explicit null rejection remain unchanged. Unknown fields fail.
The optional reset command delegates RESP-WORK-SCHEDULER-RESET and is never retained in
the immutable settings value. AUTHORING defers expressions; RESOLVED requires valid values.
SDK raw properties and Scenario Manager consume this parser; scheduler construction takes
one immutable startup snapshot. Runtime rate/max/reset controls continue using their
canonical field parsers and the existing scheduling/counter policy, without mutating the
Spring property carrier. Complete Work candidate validation and Controller scheduler
startup export remain subsequent B02 work, not acceptance claims of this transfer.

**Forbidden:** duplicate numeric/default/reset rules, schedule work or own worker state.
**Verification:** parser behavior tests, existing Spring binder, authoring and scheduler
runtime tests; no new boundary scanner or wiring tests.

## RESP-WORK-SCHEDULE-INPUT

**Current module(s):** `common/worker-sdk`.

SchedulerWorkInput owns timed intake, finite-run count and dispatch; its factory/builder wire the selected policy and callbacks.

It projects WorkerControlPlaneRuntime snapshots, delivers each revision to the policy, and dispatches the returned quota through WorkerRuntime.
Source rates and timing/limits come from RESP-WORK-INPUT-RATE and RESP-WORK-INPUT-SCHEDULE.
Rate, maxMessages and declared reset flags are parsed before any setting is changed; a valid
changed maxMessages resets the finite-run counter. Its builder consumes validated timing
without local defaults or clamping. SchedulerWorkInput owns the accepted runtime limit
as a long initialized from startup properties; ticks never reparse its declaration.
Reset flags consume RESP-WORK-SCHEDULER-RESET; explicit true resets the existing counter.

**Forbidden:** reimplement trigger interval/single-request rules or select a policy by worker role.

**Required effect:** Each state revision reaches the policy before a subsequent tick; finite-run intake dispatches through WorkerRuntime.

**Verification entrypoints:** `WorkControlCompositionTest`, `TriggerSchedulerIntegrationTest`, `SchedulerWorkInputTest`.

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

## RESP-WORK-CSV-SETTINGS

**B02 implemented; awaiting separate review:** `work-config.csv.CsvDatasetParser` owns complete CSV settings
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
WorkPatchPolicy validates a supplied selected CSV candidate before accepted-state writes;
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

Consumes RESP-WORK-CSV-SETTINGS for one immutable resolved settings snapshot; bootstrap
and raw updates delegate parsing before replacement. Dataset file reads and cursor /
initialization failures stay here. A rate change does not reload the file; the existing
patch policy still requires rematerialization for CSV source/format/timing changes.

**Current module(s):** `common/worker-sdk`.

CsvDataSetWorkInput owns file-backed dataset iteration and intake lifecycle in the current SDK.

It consumes selected CSV settings, observes worker state and dispatches records through WorkerRuntime.
Timing/rate validation and the seconds-to-milliseconds conversion delegate to
RESP-WORK-INPUT-SCHEDULE and RESP-WORK-INPUT-RATE; intake does not repair invalid timing.

**Forbidden:** declare broker resources or own accepted worker configuration.

**Required effect:** Records are read in the configured order and exhaustion/stop is observed without broker provisioning.

**Verification entrypoints:** `CsvDataSetWorkInputTest`.

**Migration status:** Local adapter packaging B07; raw configuration/lifecycle consolidation B02/B03.

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

RedisPushSupport owns route/payload selection and Redis list write execution; RedisWorkOutput applies output policy, while RedisUploaderInterceptor applies diagnostic-capture policy.

Both consumers delegate the push operation; RedisWorkOutputFactory wires the selected output. Diagnostic capture and business output are distinct uses, not duplicate authority for one result.

**Forbidden:** turn capture into business output or independently reimplement the shared Redis push operation.

**Required effect:** The selected payload is pushed to the resolved list through shared support; capture and business output retain separate explicit policies.

**Verification entrypoints:** `RedisWorkOutputTest`, `RedisUploaderInterceptorTest`.

**Migration status:** Write settings and their enum decoding now belong to
RESP-WORK-REDIS-WRITE-SETTINGS; destination validation belongs to RESP-WORK-REDIS-TARGETS.
RedisPushSupport consumes their resolved products and RESP-REDIS-CONNECTION-SETTINGS.
Its nested ConnectionConfig was removed. Connection defaults, scope composition and
remaining nested writer/request contracts remain B02/B06 work; complete Redis settings
are not yet consolidated.

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

## RESP-ORCHESTRATOR-CONTAINER-LIFECYCLE

**Current module(s):** `orchestrator-service`.

ContainerLifecycleManager prepares controller container settings, invokes the configured
compute adapter, records the resulting Swarm runtime identity and ownership manifest,
pre-pulls requested images and removes controller compute/control queues. It consumes
RESP-RABBIT-CONNECTION through the participant environment factory, plus the existing
runtime filesystem mount, metrics and compute contracts. Swarm operation handlers invoke
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

**Current module(s):** `swarm-controller-service`.

SwarmWorkerSpecFactory maps a scenario Bee and SUT environment into PlannedSwarmWorker:
worker identity, container environment/volumes and bootstrap configuration. It consumes
the shared participant environment factory, RESP-RABBIT-CONNECTION,
RESP-REDIS-CONNECTION-SETTINGS and runtime filesystem mount; remaining Work settings
export, queue naming and SUT enrichment are still local.
SwarmRuntimeCore consumes the plan and owns lifecycle/state; compute executes the spec.

**Forbidden:** provision a worker, publish bootstrap configuration, mutate the source Bee
or runtime state, or independently validate/encode the shared Rabbit/Redis connection contracts.

**Required effect:** Planning returns the worker spec and its corresponding bootstrap
configuration without performing compute or broker operations. After applying bee.env,
it delegates Rabbit/Redis connection resolution to RESP-WORK-CONNECTION-ENVIRONMENT.
It consumes RESP-WORK-INPUT-LIFECYCLE-POLICY for raw configuration and the composed
environment; unsupported input controls fail before provisioning. CSV export no longer
produces an input-local enablement variable.
Final validation of other settings remains B02 work.

**Verification entrypoints:** `SwarmWorkerSpecFactoryTest`, `SwarmLifecycleManagerTest`.

**Migration status:** Current planning owner; complete Work configuration parsing/export and
final-candidate validation are B02, canonical Work naming B04. This record does not accept
those outstanding responsibilities as isolated or certify final environment validity.

## RESP-WORK-CONNECTION-ENVIRONMENT

**Current module:** `common/work-config`, `io.pockethive.work.config.environment`.

WorkConnectionEnvironmentResolver produces ResolvedWorkConnectionEnvironment from the
raw bootstrap configuration, composed container environment, unexpanded override lookup
and a factory for the final property lookup. The human-approved FENV-R1 correction composes
both Redis directions first, freezes the complete environment and only then expands/binds
and validates connections. SwarmWorkerSpecFactory delegates Spring lookup to
SpringConnectionEnvironment: raw lookup uses Binder without placeholder expansion; final
lookup uses Binder with strict Spring placeholder resolution over the supplied snapshot.
The same raw Spring lookup also supplies RESP-WORK-INPUT-LIFECYCLE-POLICY during worker
planning; SpringConnectionEnvironment does not decide which input fields are supported.
Names/precedence and successful expansion follow worker binding. Missing/cyclic references
fail planning with a property name, without exposing the input or exception cause.
No process properties are consulted, and no custom placeholder parser or retry loop exists.
The resolver validates the five Rabbit connection fields through RabbitConnectionEnvironment
and RabbitConnectionSettings. For each declared Redis IO block, selected Redis IO direction,
or direction with connection overrides, explicit unexpanded environment values replace
corresponding declared fields before encoding. Final text comes from the complete snapshot;
non-text declaration types stay intact for RedisConfigurationParser to validate.
Missing overrides retain declared fields; empty text is an explicit value, and invalid
overrides fail. A valid override may supply a missing field or correct an invalid base field.

The returned environment is exactly the frozen candidate checked by final binding; its
strings/placeholders are not rewritten after validation. Bootstrap projects the accepted
Redis values and must not restore the pre-override connection. Unrelated config fields and
environment entries are retained, and source maps are not mutated. Rejection yields no
worker spec; RESP-CONTROLLER-CONTROL validates all specs before infrastructure/state effects.

**Forbidden:** duplicate connection constraints, read process environment, construct clients,
choose adapters, mutate accepted runtime state or present this slice as full Work validation.

**Verification:** resolver/codec and SpringConnectionEnvironment unit tests, worker
environment binding and SwarmWorkerSpecFactory/SwarmLifecycleManager behavioral tests.

**Migration status:** B02 connection composition only. Other IO/execution settings,
additional Rabbit transport options, other Redis scopes and later property sources remain open.

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

ControlPlaneCodec decodes and the dedicated CP listener factory supplies transport policy. Operation owners retain desired state/terminalization.

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

**Migration status:** B01 isolates the CP factory. Existing listener handlers/core remain their owners; Work provisioning/stats extraction is B04 and composition cleanup is later work.

## RESP-SCENARIO-VALIDATE

**Current module(s):** `scenario-manager-service`, `tools/scenario-templating-check`.

ScenarioBundleValidator owns bundle acceptance checks; ScenarioTemplateValidator is an offline template-rendering diagnostic, not another bundle acceptance authority.

Both use the canonical template API with DisabledSequenceAccess; diagnostic rendering must not decide persisted scenario validity.

Request-template shape/auth/protocol checks delegate to RequestTemplateParser.
RequestTemplateFindings projects its problems into bundle findings; profile existence and
bundle visibility stay here. The offline diagnostic delegates file loading to
request-template-files. See RESP-REQUEST-TEMPLATE-PARSE for these transferred owners.
WorkConfigurationFindings projects canonical input rate/timing/limit and complete CSV
settings errors and deferred paths; ScenarioBundleValidator bypasses catalogue type/range/required validation for those
selected fields. Catalogue descriptions remain presentation metadata.

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

## RESP-WORK-PATCH-POLICY

**Current module:** `common/work-config`, `io.pockethive.work.config.policy`.

WorkPatchPolicy owns the live mutable/disabled-only IO field catalogue and validation
of proposed IO updates/reset against prior configuration and current enablement.
WorkerInputType and WorkerOutputType remain shared selection values in `io.pockethive.work.config`. The policy uses
those values and a worker name for diagnostics; it does not depend on SDK WorkerDefinition.

WorkerControlPlaneRuntime delegates before merging/publishing accepted configuration.
WorkPatchPolicy first consumes RESP-WORK-INPUT-LIFECYCLE-POLICY on proposed inputs,
including the first bootstrap update, before its existing patch classifications.
CapabilityCatalogueService reads the same field classifications for authoring metadata.
LiveIoConfigUpdateGuard and LiveIoConfigMutability have been removed; neither
SDK nor scenario-validation-contracts retains another implementation of these decisions.

**Forbidden:** mutate worker state, apply configuration to infrastructure, select clients,
read Spring/environment state, or claim full candidate validation from patch classification.

**Required effect:** Endpoint/adapter changes require rematerialization; operational fields
keep their existing value constraints. Redis single-source listName changes require a
disabled worker already in that mode. Rejected updates cannot reach the SDK merge path.

**Verification entrypoints:** migrated WorkPatchPolicyTest; existing capability and SDK
runtime tests. Boundary ownership is checked through the import test and separate review.

**Migration status:** Patch-policy transfer implemented, pending separate review. Complete settings/candidate parsing remains
the subsequent WorkConfigurationParser transfer; this policy does not replace that gate.

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
