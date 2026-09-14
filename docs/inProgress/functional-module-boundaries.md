# Functional module boundaries — continuation and repair plan

Status: source analysis and implementation proposal, 2026-09-14. Implementation not started.
Baseline: `0bfa378c` plus the user-approved removal of legacy Rabbit binding cleanup.
This continues the existing modularity/SSOT requirement; it does not introduce a new architecture programme.

Delivery priority, clarified 2026-09-14: first close
[Rabbit SSOT and WorkPlane isolation](work-plane-module-boundaries.md) with Rabbit and a stateful
test adapter. [Artemis and delayed publish for 3DS](../todo/work-plane-artemis-3ds.md) follow separately.
Other refactors and service correctness findings are separate PRs. The sequence proposed below
applies only when that work is selected; it does not add prerequisites to Rabbit isolation or Artemis/3DS.

## Outcome and constraints

Each functionality has one named implementation owner, an explicit supported API, and consumers
that pass intent or consume the owner's result. A separate Maven directory, Java interface,
shared DTO, or responsibility header alone does not establish this boundary.

Preserve behavior during extraction. No compatibility aliases, migration hooks, fallback chains,
new automatic adapter selection, or silent default changes. Where current implementations disagree,
write down the disagreement and resolve the intended contract before merging them; do not select
one by convenience. Existing user-approved behavior decisions override historical audit recommendations.

Rabbit AUTO ACK on dispatch/submission, swallowed processing failures, disabled input behavior,
executor-rejection handling and inactive publisher confirms remain as agreed. Legacy binding cleanup
was explicitly ordered removed and must not be reintroduced elsewhere.

Input/output is broader than Work Plane. Rabbit and a future Artemis may connect workers; Redis,
CSV and scheduler/generator sources do not become Work Plane merely by implementing an input/output
capability. CSV and scheduler are input-only. Generator business behavior remains in generator-service.
Do not implement Artemis as part of this repair plan.

## Separate correctness track

The 2026-09-14 Orchestrator path review does not expand this extraction plan into a repair of
Orchestrator behavior. [Orchestrator correctness](orchestrator-correctness.md) owns O1/O2 evidence
acceptance fixes and the separately pending reset/registry/lifecycle design. O5 journal, O6 metadata
resolution, O7 Docker and behavior-preserving O8 configuration ownership stay here. O4 permits moving
existing workflows out of HTTP; changes to their chronology/outcomes require the correctness track.
O1/O2 fixes await their separate review; that correctness track does not select the next WorkPlane PR's scope.
Closing this plan proves owner/API/cutover/deletion, not that all Orchestrator behavior is correct.

## Evidence and confidence

Inspected all 44 root Maven module declarations, production import distribution for Rabbit, Redis,
Docker, HTTP, SQL and files, existing boundary checks, and the concrete call paths below. UI contracts,
normalization and authorization were also inspected. This is an ownership analysis with targeted
source tracing, not an exhaustive review of every method or a new live failure reproduction.

`RepositoryImportBoundaryTest` passes on this tree. That demonstrates the current restrictions,
not complete modularity: its Redis allowlist includes worker-sdk and templating; Docker includes
both controller services. The ClickHouse rule detects a vendor client, but the duplicate HTTP-based
implementation in postprocessor uses the JDK and passes it.

The previous normal E2E passed 39 scenarios / 463 steps on 2026-09-11. It is a behavioral baseline,
not proof of boundary ownership; it also missed the browser schema/STOMP failure. After legacy
binding removal, 30 focused topology/lifecycle tests passed. No full E2E was rerun for this analysis.
HiveMind is not available in this session; this document is the durable current plan.

Confirmed competing implementations of the *same* rule below are SSOT blockers under AGENTS.md.
A wrong module dependency, broad class, or unverified area is identified separately; it is not
invented evidence of duplicate domain state. Historical findings are not accepted without rechecking.

## Current functionality map

| Functionality | Current owner and concrete source evidence | Assessment / target |
| --- | --- | --- |
| Rabbit technology | `common/rabbit-adapter`, `RabbitResourceNames`, `RabbitConnectionEnvironment`, `SpringRabbitResources`, `SpringRabbitListeners`; SDK and CP bridges use public API | Technology boundary established in inspected Java paths. Keep owner; do not restart extraction. Known exclusions and gates below. |
| Work values/configuration | `work-api`, `work-config`, `CurrentWorkConfigurationProviders` | Preserve canonical WorkItem, parser and mutation ports. Provider catalogue belongs to composition, not core or every consumer. |
| Worker execution and I/O | `worker-sdk`: `WorkInput`, `WorkOutput`, factories, `DefaultWorkerRuntime`, `WorkerControlPlaneRuntime`, concrete input/output implementations | API is not independently usable: input update exposes nested runtime state; output/factories expose WorkerDefinition. Cut these specific dependencies before moving concrete I/O. |
| Redis | `redis-config` now owns settings; Lettuce in `RedisDataSetWorkInput`, `RedisPushSupport`, `RedisTokenStore`, `RedisSequenceGenerator` | Settings consolidation exists. Connection realization/client lifecycle and operations remain scattered. Target one `redis-adapter` owner with supported public API. |
| CSV and scheduler | `work-local-config` owns settings/rate/schedule parsing; `CsvDataSetWorkInput` and `SchedulerWorkInput` execute inside SDK | Preserve existing rules; move local input execution behind neutral input API into a local-input owner. Do not invent CSV output or Work Plane resources. |
| Template rendering and sequences | `templating-api` / `templating`; `ConfiguredRedisSequenceAccess` calls global `RedisSequenceGenerator` | Renderer remains in templating. Redis sequence storage/client belongs to Redis; renderer consumes SequenceAccess. Explicit composition replaces hidden global configuration/lifecycle. |
| Request templates | `RequestTemplateParser` in `request-templates`; `TemplateLoader` in `request-template-files`; Scenario validator delegates to parser | Previously duplicated required-field parsing is now consolidated. Keep these owners. File decoding/provenance and pure format rules are distinct responsibilities, not duplicate parsers. |
| Worker/SUT auth | values and TokenStore in `auth-contracts` under old `worker.sdk.auth` namespace; `AuthRuntime` in SDK; Scenario `validateAuthProfileStorage` | Same refreshable-type/storage constraints exist in authoring and runtime. Separate worker-auth ownership from product login; one profile parser/validator, runtime coordination and storage port. |
| Docker/compute | `docker-client`, two service `DockerConfiguration`s, `DockerRuntimeAdapter`, `SwarmLifecycleManager` | Raw clients/model types and client construction escape module. Runtime adapter directly inspects/removes/lists. Target existing docker-client as technology owner; use existing ComputeAdapter and explicit inventory/removal capabilities. |
| Lifecycle/readiness/cleanup | manager-sdk engine; service lifecycle coordinators; `SwarmReadinessTracker`, `SwarmWorkersAggregator`, `RuntimeReconciliationService` | Application ownership stays in services. Freshness is independently calculated; orphan compute cleanup declares REMOVED after void deletion. Consolidate each fact/postcondition, not all lifecycle state into one singleton. |
| Runtime files and journal | `control-plane-filesystem`, `journal-postgres`; `FileSwarmJournal`, `SwarmJournalController`, `PostgresHiveJournal`, `PostgresSwarmJournal` | Runtime run path exists, but journal reader rebuilds it; file leaf repeated. REST controllers perform SQL/file queries and mutations. Explicit journal read/write/retention APIs and one layout owner needed. |
| ClickHouse and metrics | `sink-clickhouse/metrics/ClickHouseMetricsSink`; `postprocessor/ClickHouseTxOutcomeSink` | Both implement HTTP client, INSERT URL/query, credentials and batch delivery. One ClickHouse mechanics owner; metric vs transaction contracts and their distinct buffering policies remain explicit. |
| Scenario authoring/API | scenario-manager owns bundles/materialization/variables; ScenarioManagerClient duplicates producer records | Preserve service ownership; share or generate canonical runtime request/response contracts. Scenario validator keeps file/reference checks and projects owner diagnostics. |
| Product auth and UI | `auth-service`, `auth-client`, `PocketHiveGrantChecks`; UI `auth.ts` copies grant/scope predicates | Server is authorization owner. UI should consume effective permissions/capabilities (or generated policy), not maintain an independent predicate. This is SSOT work, not added adversarial hardening. |
| Network control | network-proxy-manager owns binding operations, HaproxyConfigClient/ToxiproxyHttpClient behind its API; `ui-v2/networkProxy.ts` coerces unknown mode to DIRECT | Keep this functional service; canonical contract/client and exact projection in UI. No generic module for every use of HTTP. |
| Worker business capabilities | generator, moderator, processor, request-builder, http-sequence, db-query, clearing-export, trigger, postprocessor | Keep business ownership local. Processor constructs HTTP pools/TLS in worker class; extract that infrastructure concern. DB statement executor and clearing sink already provide local seams: harden them rather than mechanically move every class into common. |
| MCP | OrchestratorToolExecutor and ScenarioManagerToolExecutor delegate to OwnerApiPort; BundleUploadCoordinator calls owner.validate; SwarmReadinessObserver computes ready | Core forwarding path is real. Keep MCP upload coordination distinct from Scenario publication state. Readiness predicate must be an explicit owner projection, not an independently evolving definition. |
| TCP mock and test tooling | MessageTypeRegistry, StateManager, RequestStore; AdminController vs MessageMappingController | Runtime/transport/mapping concerns exist but endpoints independently choose memory-only vs file-backed deletion. Put operation semantics behind a mapping service, retaining separately specified endpoint behavior. Legacy/E2E exclusions remain explicit. |

### Rabbit closure status

Production Rabbit/Spring-AMQP imports were found only in rabbit-adapter (excluding test tooling).
Physical naming, CP/Work connection decoding/export and resource mechanics have named owners.
The two external string references to RabbitTransportAutoConfiguration are Spring ordering metadata,
not broker-operation bypasses. Work/CP bridges may remain outside the module if they only translate
owned domain contracts and invoke its API.

Aggregate review, including the legacy-cleanup deletion, ran on 2026-09-14. Its three findings
were corrected; residual gate is separate review of those corrections. UI canonical schema
loading and actual STOMP were repaired and verified through public ingress on 2026-09-14;
see the Rabbit plan latest verification. E2E/test-fixture naming and legacy Node debug clients remain explicitly
deferred for replacement, not secretly included as completed migrations. Accepted indirect CP ENV
overrides remain an accepted limitation. No claim of repository-wide Rabbit closure including those exclusions.

### Revalidation of the older audit

- Redis old duplicate semantic-validation finding is not copied forward unchanged: properties and
  runtime now delegate to RedisConfigurationParser; transport/client extraction is the remaining task.
- Request-template required-field disagreement is no longer the old loader-vs-Scenario implementation:
  both call RequestTemplateParser. Do not reimplement it.
- Cleanup finding is narrower now: Rabbit delete verifies absence inside its adapter. Compute orphan
  deletion still returns straight into REMOVED in RuntimeReconciliationService (368–401); the lifecycle
  path uses RuntimeRemovalPostconditionVerifier. Repair the compute path, do not claim Rabbit lacks verification.
- Journal paths, duplicate Scenario wire records, worker freshness and UI grant/mode interpretations
  remain evidenced in current code. UI workload guard now checks STOPPED directly; do not resurrect
  historical aliases removed elsewhere.

## Repair sequence — each slice closes a real path

The order below is proposed implementation order, not permission to work on everything at once.
Every slice ends with consumer cutover, deletion of replaced logic, tighter build restrictions and
behavior evidence. API names below describe responsibilities; do not create empty packages in advance.

### F01 — Redis owner, with the minimum I/O contract cut required for extraction

Evidence:
- `RedisDataSetWorkInput.LettuceRedisClientFactory.buildUri`, `RedisPushSupport.LettuceRedisWriterFactory`,
  `RedisTokenStore` constructor and `RedisSequenceGenerator` constructor repeat connection-to-client realization.
- `WorkInput.update` takes `WorkerControlPlaneRuntime.WorkerStateSnapshot`; `WorkOutput.publish` and
  both factory interfaces take WorkerDefinition. Moving adapters wholesale today would depend on SDK runtime.

Actions:
1. Move only adapter-facing contracts needed by these consumers into existing neutral Work API/config
   owners. Supply an immutable input update projection, dispatch callback and minimal output context.
   WorkerDefinition's bean/reflection metadata and the mutable state writer stay in runtime. Do not copy
   all of WorkerStateSnapshot to create another state owner. No new generic plugin/discovery framework.
2. Absorb redis-config's existing canonical implementation into `common/redis-adapter` with explicit
   `io.pockethive.redis.api` surface and internal config/client/operations packages. No second parser.
3. Move Redis input/output implementation, shared push mechanics, token-store implementation and Redis
   sequence implementation to the owner. Keep token identity/claim contracts in worker-auth and the
   SequenceAccess contract in templating-api. These ports must not depend on worker-sdk.
4. Existing worker composition wires adapters to runtime; templating receives SequenceAccess explicitly.
   Delete replaced implementations/old config artifact after all consumers cut over. No raw Lettuce
   connection, command object or client callback in public API.

Gate: only redis-adapter imports Lettuce in production; Scenario/worker/update paths consume the same
settings; actual clients receive identical effective connection fields; list read/write, token claims,
sequence increment/reset and shutdown preserve current results. Missing adapters fail explicitly.
Test representative non-default config and runtime operations, not just bean registration.
The deferred Redis SEL-R1 STOP/update/START race remains a separate behavior decision, not silently fixed.

### F02 — Local input and worker runtime boundaries

Use the neutral adapter contracts from F01. Bring CSV settings + parsing + file reading/cursor execution
and scheduler settings + input execution under `common/work-local-input` (separate packages/owners),
absorbing work-local-config. The scheduler/rate policy already in work-config is referenced, not copied.
Keep generator's message creation in generator-service. Migrate factories and remove SDK copies.

Make worker-sdk the execution/state/dispatch owner plus integration composition, not storage, Redis,
auth refresh or filesystem parsing. Separate composition package from runtime package. Concrete adapter
factories must not receive the entire WorkerControlPlaneRuntime. Separate worker control transport
bridges from accepted-state mutation and status projection; one writer per accepted worker state.

Gate: concrete local inputs build without SDK implementation/Control Plane internals; CSV rotate/EOF,
rate/cursor updates, scheduler timing/reset and invalid-candidate state preservation retain behavior.
Rabbit SDK bridges are checked against the same boundary without reopening Rabbit settlement.

### F03 — Docker technology owner

Extend existing docker-client; do not create another Docker library. Move both client configurations,
Docker runtime inventory/inspection/removal and compute-adapter construction into it. Public operations
use neutral specs/results; DockerClient and Docker model types do not escape. Preserve distinct explicit
compute modes and current connection settings; AUTO selection behavior needs a separately stated decision
if its current implementations disagree—do not silently introduce a fallback while consolidating.

ContainerLifecycleManager/SwarmLifecycleManager retain *when/why* to provision/remove, runtime manifests
and lifecycle state. Compose through ComputeAdapter and explicit diagnostic ports. The known orphan
cleanup success defect is recorded in the correctness track; moving this code must not silently change it.

Gate: raw Docker imports allowed only in docker-client/test fixtures; both services use the same client
construction owner. Removal-result semantics retain the separately approved contract. Keep HiveGate approval
at its existing boundary; no new approval flow and no live governed cleanup during this refactor.

### F04 — Journal/storage paths and queries

Extend RuntimeFilesystemLayout with canonical journal artifact projection; both writer and reader use it.
`SwarmJournalController.readJournalEntries` currently uses `swarmRoot().resolve(runId).resolve("journal.ndjson")`,
while FileSwarmJournal uses swarmRunDirectory then repeats the leaf. Remove local path/sanitizer rules.

Create a journal API owner for append/query/run metadata/retention; extend existing journal-postgres for
SQL implementation and keep a file implementation behind the corresponding ports. Move SQL/file access
out of JournalController and SwarmJournalController. CP and swarm journal events remain distinct projections
of their domain owners; do not merge their event state machines or make one global writer for all journals.

Gate: writer/reader/export/delete agree on paths and record contracts; HTTP only maps requests/results;
invalid IDs are handled through one layout contract; retention/pinning are not separately mutated in REST.

### F05 — ClickHouse mechanics under the existing sink module

Move transaction sink transport into sink-clickhouse. Consolidate the repeated INSERT URI, HTTP auth,
client construction and response handling shared with metrics sink. Keep TxOutcomeEvent construction
with postprocessor and metric sample construction with observability. Different buffering/flush/failure
policies are not interchangeable defaults: retain them as explicit separate policies in the owner.

Gate: no ClickHouse query/credential/URL construction in postprocessor or other consumers; use metrics
and transaction fixtures to assert exact wire requests and queue/full/flush-failure behavior. Existing
vendor-import checks alone are insufficient for JDK HTTP implementations.

### F06 — Worker auth and rendering

Extract worker auth profile parsing/validation and runtime auth coordination from SDK into a named
worker-auth owner. Split product identity/grants from SUT auth contracts now mixed in auth-contracts;
move packages with all callers, without old namespace aliases. Share refreshable-type/storage rules
currently repeated in AuthRuntime.validateProfile and ScenarioBundleValidator.validateAuthProfileStorage.
AuthTokenKeys remains the sole token-key owner; Redis implements TokenStore through F01.

Rendering stays in templating behind templating-api. Request-template parsing stays where already
consolidated. TemplateLoader may translate canonical auth failures; its existing `worker.sdk.auth`
import is a type housed in auth-contracts, not proof of a runtime SDK dependency.

Gate: same profile gives the same diagnostics before authoring/execution effects; refresh/claim/token
identity and rendering behavior preserved; loading templates cannot start a Redis client implicitly.

### F07 — Service contracts and UI projections

Consolidate the identical metadata resolver currently in SwarmController and
OrchestratorEndpointAuthorization (O6): both must consume one resolution of bundle/folder metadata,
with existing grant checks preserved. Consolidate ClickHouse ENV export in F05. For O8 HTTP timeouts,
move existing policy first; rejection of previously defaulted invalid settings requires a separate
correctness decision.

Move runtime request/response and variables response ownership from ScenarioManagerClient's nested
copies to canonical Scenario service contracts, reused or generated for clients. Verify product MCP
and network-profile clients against the same producer contracts; do not extract unrelated domain models
into a universal DTO bag. Service URL construction remains with each owning client, not individual callers.

UI consumes exact network/lifecycle values and server-projected effective permissions/capabilities.
Remove independent normalizeMode-to-DIRECT and grant/scope implementations once replaced. Repair delivery
of canonical referenced schemas to UI; do not copy `$defs` manually or disable validation.
This bootstrap subtask is implemented and browser-verified on 2026-09-14 (Rabbit plan latest verification);
do not repeat it when executing the rest of F07.

Gate: generated/shared contracts have a drift check; invalid values produce explicit diagnostics; actual
browser login → schema load → owner-projected STOMP subscription → status update is covered. Existing
39-scenario E2E green is insufficient for this browser path. No broad security hardening is included.

### F08 — Readiness, status and lifecycle facts

SwarmReadinessTracker checks heartbeat age against STATUS_TTL_MS; SwarmWorkerStatusHandler constructs
SwarmWorkersAggregator with a separate 15,000ms stale threshold and the latter calculates stale itself.
Define one worker presence/freshness owner with clock/threshold and have readiness/status consume its
projection. Define intentionally distinct concepts explicitly if readiness and display truly need them;
do not merely force numbers equal. This decision must precede changing behavior.

Separate lifecycle command coordination, infrastructure effects and outcome construction inside services.
Keep existing operation chronology/idempotency/wire contracts; preserve roles of manager-sdk's ScenarioEngine
and service convergence handlers. A coordinator and its delegating service bridge are not automatically
competing owners (e.g. inspect BufferGuard delegation before deleting either class).

Gate: an identical heartbeat/clock yields consistent freshness facts; duplicate/stale events do not create
another writer; terminal success comes from canonical postconditions. State ownership, not class count,
is the acceptance criterion.

### F09 — Service-local functional boundaries and residual consumers

Scenario Manager path review is recorded in
[the 2026-09-14 report](../architecture/scenario-manager-code-path-review-2026-09-14.md).
Its agreed follow-up separates correctness fixes from behavior-preserving extraction, and is
outside the Rabbit plan. S6/S7/S5 cover bundle export/layout/authoring metadata; S4 and S8 belong
to F06/F07. Findings remain open while the service-by-service review continues.

For processor, move HTTP pool/TLS/client construction out of ProcessorWorkerImpl into its infrastructure
owner; keep protocol dispatch, envelope/result contract and transport implementation distinct. Compare
HttpProtocolHandler and http-sequence ApacheHttpCallExecutor before sharing mechanics: a shared HTTP library
alone does not prove duplicated behavior. Auth-client, Toxiproxy client and SUT request execution remain
separate functional clients even though all use HTTP.

Preserve and enforce existing DbStatementExecutor and ClearingExportSink seams; don't create a generic SQL
or filesystem module joining journal persistence, test queries and business exports. Keep request-builder
assembly, generator message creation, moderator policy, trigger work and postprocessor outcome construction
in their own services. Inspect API signatures and alternative owners per service before claiming closure.

Concrete residual consumer work:
- MCP: lifecycle/cleanup tools already forward through OwnerApiPort and bundle validation calls Scenario's
  owner port. Preserve that. SwarmReadinessObserver.status/Status.ready counts non-stale workers and combines
  READY/STOPPED/startupReady locally. Establish a canonical readiness result or shared predicate with the
  owner; first specify whether this is startup readiness or readiness to run, rather than assuming all
  uses of “ready” are the same. Keep upload tickets/QA workflow state separate from Scenario domain state.
- TCP mock: MessageTypeRegistry holds mappings and delegates scenario state to StateManager/ScenarioManager;
  RequestStore records observations. UnifiedTcpRequestHandler also coordinates validation, latency, metrics
  and recording. Move that pipeline behind a request-execution capability, leaving Netty framing/dispatch
  in the handler. MessageMappingController.removeMapping performs memory + file deletion while AdminController
  performs memory-only deletion; both swallow all exceptions. Define these as explicit operations in one
  mapping application service, preserve their documented difference if intentional, and use one failure
  contract. Do not assume endpoint names authorize changing persistence behavior.
- Existing debug-tool/E2E broker naming remains its separately selected replacement slice; this analysis
  does not authorize unrelated test-harness rewrites. Map fixture lifecycle and cleanup through product APIs
  when that slice is selected.

Gate: MCP tool handlers return owner results for domain facts; TCP REST/Netty boundaries delegate to
mapping/execution owners, with tests for persistence effects, scenario transition and response framing.
These inspected paths are evidence for the plan, not blanket acceptance of all 123 MCP or 62 mock classes.

## How the boundaries become enforceable

1. **API dependency direction first:** core/contracts cannot depend on runtime, concrete clients or
   composition. Adapters implement contract ports; composition depends on both. Minimal projections
   replace signatures exposing entire runtime objects. No parallel mutable configuration model.
2. **Actual encapsulation:** implementation types package-private where feasible; explicit `.api`
   surface and restricted internal imports where cross-package implementation needs public visibility.
   No public raw clients, arbitrary client callbacks or alternative constructors recreating defaults.
3. **Build restrictions updated with each move:** extend existing Maven Enforcer/import boundary test,
   remove worker-sdk/templating from Redis and both services from Docker allowlists on cutover. Keep
   core transitive restrictions. No new competing source-scan framework. Import checks are guardrails,
   not semantic proof; review HTTP/SQL/string construction against owner API too.
4. **One configuration path:** declaration → owner parser → accepted immutable settings → owner execution
   and projections. Scenario/ENV/property bindings call that owner. Validate candidate before effects;
   rejected updates leave accepted state intact. Shared constants without shared behavior do not suffice.
5. **Behavior evidence:** narrow owner tests with real edge cases, consumer integration through public
   contracts, relevant official-ingress E2E, browser checks where needed. Preserve explicit errors and
   existing ACK semantics. Do not repeat the whole test suite for documentation-only changes.
6. **Closure review:** trace at least one create/use/observe/remove or parse/apply/read path end to end;
   search the repository for alternative owners; delete old callers/helpers; verify restrictions now reject
   the former bypass. A slice with two active owners is unfinished, even if its new module tests pass.

## Suggested order within a separately selected refactor PR

O1/O2 are implemented in the separate correctness track and await review. When the Redis refactor is selected,
start F01 with the Redis connection/operations inventory and the *minimal* adapter-facing contract cut.
It has four concrete client-construction sites and already consolidated configuration to reuse.
Do not start the broad worker-runtime rewrite first. Finish Redis consumers and restrictions before claiming
Redis complete. F03/F05 are independent later technology transfers; F04/F07/F08 require their own domain
contract work. This plan does not authorize deploying, committing or silently resolving behavior disagreements.

Plan review: owner/cutover/deletion/gates specified; no new libraries required; boundaries and behavior
constraints preserved. Security scope remains existing contracts, with indirect CP ENV limitation accepted.
Maintainability goal is fewer active authorities and smaller public APIs, not fewer lines or more artifacts.
