# Independent acceptance framework

User-approved direction: a new `acceptance-tests` Maven module, Java 21 and JUnit 5.
It is independent of the frozen `e2e-tests`; temporary coexistence ends only after
replacement acceptance. These are the current implementation responsibility records.
They do not claim complete replacement or a passing deployment run.

## Boundaries and composition

The runner selects an explicit target file and test group. `TargetLoader` resolves
that file once; no ENV/default precedence chain. A JUnit test composes a transport,
service clients, operation observer and scoped resources. Java try-with-resources
closes taps before swarms and preserves cleanup failures as suppressed exceptions.
JUnit reports the original failure and cleanup failures; `RunEvidence` records
operation/capture evidence per test. No DI container or Cucumber state store.

Only canonical product model/codec modules, Jackson and the JDK are runtime dependencies.
JUnit is test-scoped. Neither the legacy test artifact nor broker/service implementation
artifacts are dependencies. API projections without an exported canonical DTO use
Jackson trees; they do not introduce new wire DTOs, validators or domain state machines.
The module does not implement Rabbit/Artemis transport or resource naming.

## RESP-ACCEPTANCE-TARGET

**Owner:** `TargetLoader`; `ApiTarget` is the shared immutable ingress/actor/request/report
projection. `AcceptanceTarget` adds lifecycle limits and WorkFixture; `ScenarioTarget`
adds only the authored scenario id. ViewerTarget adds the explicit cleanup actor,
scenario/SUT and operation limits. RunnerTarget adds explicit folder and denied fixture.
ProxyTarget adds an explicit network profile and endpoint to the shared lifecycle target.
TcpTimeoutTarget adds explicit mock credentials/mapping and a quiet window.
NetworkAccessTarget holds only API and viewer/runner identity settings.
ProvisionedAuthTarget adds explicit fixture/scope and actor provisioning settings;
SwarmAuthorizationTarget adds a logical TapSelection to that same auth projection.
RedisFixtureTarget adds an explicit Redis Commander connection id via `loadRedisFixture`.
A read-only smoke suite selects `loadApi`, requiring only the shared ApiTarget fields.
The calling suite explicitly selects `load`, `loadScenario`, `loadViewer`, `loadRunner`,
`loadProxy`, `loadTcpTimeout`, `loadNetworkAccess`, `loadProvisionedAuth` or
`loadSwarmAuthorization`; the resolver never infers a group from fixture names.
**Effect:** one explicit file supplies ingress, actor, time limits and named fixture;
missing, unknown or invalid settings fail before any side effects. Required key sets
are scoped to the selected target kind; common fields are parsed once. `selectedFile`
reads the explicit runner property. Paths resolve
relative to that file. **Must not:** consult legacy configuration, infer adapter,
or embed defaults in individual clients/tests.

## RESP-ACCEPTANCE-HTTP

**Owner:** `PocketHiveHttp` sends bounded requests to the selected ingress only;
`ApiSurface` owns the public service prefixes (`/orchestrator`, `/scenario-manager`,
`/auth-service`, `/network-proxy-manager`, `/tcp-mock`, `/redis`, `/grafana`) and projects rooted service-relative links, including operationUrl.
`ApiSurface.pathSegment` encodes opaque identifiers once; clients do not add product ID grammar.
`ApiResponse` holds HTTP status/body, `ApiException` identifies an unexpected status.
**Effect:** preserve expected denial responses as data, reject off-origin operation
links and redirects, propagate interruption and request failures. The HTTP budget
covers response headers and the complete body; timeout/interruption cancels the
in-flight exchange before returning control to resource cleanup. **Must not:**
infer domain success, log credentials, retry mutations or use direct backend ports.
Callers can explicitly select the Accept media type for text/raw endpoints; existing
JSON callers retain application/json. Both use the same bounded request implementation.

## RESP-ACCEPTANCE-API

**Owners:** `GrafanaTxOutcomesApi` maps scoped persisted-outcome reads through Grafana (DA-3 section); `AuthApi` handles dev login and the canonical current-user profile; `AuthAdminApi` maps user/grant administration; `SwarmApi` maps swarm REST requests/readbacks;
`ScenarioApi` maps scenario CRUD and bundle template/SUT content; `ScenarioFolderApi`
maps folder CRUD. `SwarmManagementApi` maps manager/component configuration dispatch.
`NetworkBindingApi` maps canonical binding reads and synchronous bind/clear requests;
mutation responses remain data for the caller, including rejection. It also requires
absence after removal. The NW-4 suite exercises these mutations only on its owned swarm. `SwarmJournalApi` maps timeline, run, pin and metadata endpoints; `TcpMockApi`
reads the explicitly selected existing mock mapping and its request journal (DA-4).
These are distinct endpoint families.
**Effect:** use canonical auth/create/control/operation/state contracts and exact
public paths. `ControlReceipts` alone checks the acknowledgement idempotency key for
SwarmApi and SwarmManagementApi. The latter also checks the manager dispatch wrapper
against its requested swarm/controller. `ControlReceiptMismatchException` reports
a mismatch and retains the canonical receipt for bounded observation/cleanup; it is
never a successful result.
**Must not:** decide convergence, own cleanup, create wire DTO copies, add scenario-ID validation,
or call old E2E clients. Dev login is explicit in the local target; no token fallback.

## RESP-ACCEPTANCE-OPERATIONS

**Owner:** `OperationAwaiter`; `Deadline` only provides a monotonic bounded wait budget.
**Effect:** follow the accepted operation, verify swarm/correlation/idempotency/type,
return its terminal result, fail promptly for terminal failure when success is required.
Request timeouts and polling fit the remaining budget. **Must not:** terminalize an
operation, infer success from worker status, retry commands or reset elapsed budgets
inside a wait. Late/foreign operation evidence cannot satisfy the requested operation.

## RESP-ACCEPTANCE-RESOURCES

**Owners:** `AuthUserResource` owns only a uniquely provisioned test user, verifies absence before creation,
then revokes/deactivates and verifies its final state through Auth Service (see the provisioning section).
An unconfirmed user creation plus an absent readback remains unresolved; it cannot
prove that a timed-out request will not finish later.
`ScenarioResource` and `ScenarioFolderResource` own new test identifiers and verify
their deletion through the respective public APIs; unknown creation plus an absent
readback remains an error, not proof that a late mutation is impossible.
`SwarmResource` retains an individual test's acquisition receipt, tracks lifecycle
and CONFIG_UPDATE commands, and closes that exact swarm through canonical remove. `AcquisitionState` describes only the
handle's ownership certainty, not the product lifecycle.
`RedisDatasetResources` composes a dataset's dependent scenario/list handles and
uses SwarmResource's read-only cleanup permission; see the DA-1/DA-2 section.
**Effect:** register the handle before create; cleanup works after assertion failure,
waits for accepted remove, checks its published evidence and absence from the API.
Unconfirmed acquisition or a still-active operation is reported, not guessed around.
A previously accepted remove is followed rather than reissued. `ControlCommand` is
only the checked dispatch callback used by this owner. A mismatched acknowledgement
is retained and observed under the original wait budget, with the same swarm/run
checks and removal postconditions. The mismatch remains the primary failure even
when that observation or cleanup also fails. Failed cleanup remains
visible and does not mask the first error. **Must not:** own product state, synthesize
resource names, invoke reset/orphan cleanup, or delete by prefix.

## RESP-ACCEPTANCE-CAPTURE

**Owners:** `DebugTapApi` maps tap HTTP operations; `TapResource` owns one acquired tap,
its bounded sample wait and close. `TapSelection` is the immutable logical request;
WorkFixture.tap() is only a read-only projection into it. **Effect:** use that target and decode each
sample with `WorkItemJsonCodec`; close before swarm removal. Each wait records numbered
raw samples selected for its returned WorkItems, under a unique capture artifact prefix.
These remain available after API ring eviction, later decode failure or timeout; the
latest tap snapshot remains a separate diagnostic projection. **Must not:** configure
native broker clients, steal worker deliveries or branch on fixture names.

## RESP-ACCEPTANCE-EVIDENCE

**Owner:** `RunEvidence` writes per-test operation and sample artifacts.
**Effect:** record operation identity, canonical results and captured WorkItems;
JUnit remains the test outcome/exception reporter. **Must not:** log authentication
responses/headers, reconstruct domain outcomes or create a custom audit engine.
`RunEvidence` is test-scoped and must be closed after the test resources. It retains
file-write errors and throws them on close, so a broken report destination cannot
interrupt operation observation or removal. JUnit reports this error as primary or
suppressed alongside an existing test/cleanup failure; write errors are never ignored.
The target explicitly selects the evidence directory through TargetLoader. Supplied local
targets store runs in `acceptance-tests/runs`, outside Maven build output, so `clean`
does not discard acceptance evidence. This is an example configuration, not a resolver
default or a second path owner. Archive selected evidence before manual removal; JUnit
reports under `target` remain disposable build output.

## RESP-ACCEPTANCE-RUN

**Owner:** test-scoped `LiveRun` composes lifecycle clients and handles on `ApiRun`.
Its scenario field is the read-only authoring response already fetched during setup;
assertions may compare it with runtime observations without resolving configuration.
**Effect:** load or receive an already resolved lifecycle target, require its scenario and supply fresh swarm/tap
handles. Closing LiveRun delegates to ApiRun after owned resources are closed.
**Must not:** own authentication/HTTP lifetime, share mutable state or implement
lifecycle/capture behavior.

## RESP-ACCEPTANCE-API-RUN

**Owner:** test-scoped `ApiRun` owns authenticated HTTP and evidence lifetime.
**Effect:** receive resolved ApiTarget, log in through AuthApi once and make that
session available to suite composition. Close evidence and always close HTTP,
also after setup failure; preserve primary and suppressed failures.
**Must not:** resolve target files, require a scenario/swarm/SUT, implement lifecycle,
or infer which test group is running. Read-only scenario tests use this scope directly.

## Verification

Framework component tests use a test-owned JDK HTTP stub, not PocketHive backend ports.
They check operation failure/timeout/identity, API boundary behavior and resource cleanup
including suppressed failures. Deployed `*AcceptanceIT` tests are selected explicitly
through the new runner; plain Maven tests exercise the framework without a live stack.
The existing repository import check covers this module. No additional source scanner.
See the [coverage ledger](../ci/acceptance-coverage.md) for replacement acceptance;
N1 alone cannot retire the old suite.

## Auth read acceptance slice

`AuthReadAcceptanceIT` owns the explicit GET assertion matrix for AU-1/AU-2.
Each parameterized case uses the existing ScenarioTarget (common API settings plus
scenarioId) and ApiRun. The selected actor must be allowed to read every listed API.
Each route must return 200 with that actor and 401 without credentials; both responses
are retained as evidence. Scenario IDs are encoded as URI path segments. The matrix
uses ApiSurface for ingress prefixes and PocketHiveHttp for requests; it introduces
no alternate HTTP client, login, configuration resolver or authorization calculator.
It creates no resources and makes no claims about scoped grants or write denial.
The group requires Scenario Manager, Orchestrator, Network Proxy Manager and the
Postgres-backed hive journal; unavailable routes fail explicitly.


## Viewer acceptance slice

`ViewerAcceptanceIT` verifies AU-3/AU-6. TargetLoader.loadViewer owns the explicit
viewer username, cleanupUsername, scenarioId, sutId and operation/request/poll limits.
ViewerTarget and OperationLimits are immutable projections; WaitLimits exposes its
operation-only projection for existing lifecycle consumers. Capture settings are not
required by viewer tests. AuthApi also reads the canonical authenticated user profile.
Each viewer test requires the exact PocketHive deployment-wide VIEW grant before testing access.

SwarmResource remains the sole acquisition/cleanup owner. Its create overload accepts
an explicit requesting SwarmApi; its existing API remains the observer/removal actor.
Both paths use the same receipt/state handling. Viewer CREATE denial must be403 and
the admin readback must remain404. An unexpected accepted CREATE remains owned and
is removed by the configured admin even when the denial assertion fails. No alternate
cleanup registry, token fallback or direct broker verification is introduced.


## Scoped runner acceptance slice

RunnerTarget is a read-only projection resolved by TargetLoader.loadRunner: explicit
runner/cleanup actors, allowed folder, allowed and denied scenario ids, SUT and operation
limits. ScopedRunnerAcceptanceIT verifies exact VIEW-deployment and RUN-folder grants,
catalogue restriction, accepted allowed CREATE and denied outside-folder CREATE, plus
six deployment read APIs. The catalogue case only creates; existing SwarmResource observes and
removes via the explicit admin. The additional STOP-denial case is described below;
this suite does not claim message processing.
ActorAssertions owns shared test profile expectations; AuthApi remains the only profile
transport/decoder. Existing viewer assertions delegate to it with unchanged expectations.
The new fixture under demo is independent of the frozen legacy suite and uses Artemis.


## Network access acceptance slice

NetworkAccessTarget is resolved only by TargetLoader.loadNetworkAccess and contains
common API settings for the viewer plus runnerUsername/runnerFolder. No scenario,
SUT, broker or operation/capture settings are required. NetworkAccessAcceptanceIT uses
ApiRun and ActorAssertions for explicit identities and exact PocketHive grants.
AU-8 covers two raw configuration read/PUT-denial pairs as viewer. AU-13 covers viewer
read and runner PUT denial for manual override. Each PUT replays the observed settings;
expected403 and unchanged readback are both required. This is authorization coverage,
not proof of applying changed network settings. No admin mutation or rollback is used.

PocketHiveHttp owns explicit text/plain request encoding as well as JSON. Both use the
same bounded exchange; text is sent as UTF-8 bytes without JSON quoting. The manual
override request is the public status projection with response-only appliedAt removed;
no local DTO or policy parser is introduced. Endpoint-specific values remain test data.


## RUN-only STOP acceptance slice

ScopedRunnerAcceptanceIT additionally covers the RUN-only denial half of AU-10.
Each case creates its own allowed-folder swarm, starts it through the configured
admin, checks canonical RUNNING state, then requires runner STOP403 and unchanged
run/workload intent/state with no active operation. The admin subsequently performs
STOP successfully. This does not cover folder ALL or provision new actors.

SwarmResource remains the sole lifecycle receipt and cleanup owner. An explicit
requester overload for STOP uses the same command tracking as the default actor.
A STOP dispatch403 clears only that rejected pending command; ownership of an acquired
swarm remains intact for admin removal. REMOVE403 retains the removal attempt, so
close reports unverified cleanup without sending another REMOVE. Unexpected accepted STOP is observed before
cleanup even when the denial assertion fails. Unknown dispatch outcomes still block
cleanup with an explicit error. No second lifecycle helper or grant mutation is added.


## Worker runtime acceptance slice

WorkerRuntimeAcceptanceIT covers WK-1/WK-2 with an explicitly selected four-worker
work fixture. Each test owns its swarm and tap through LiveRun, SwarmResource and
TapResource. Worker history assertions compare authored policies with fresh
observation.workers[].config from the public SwarmStateView. Instance identifies each
runtime worker; this fixture explicitly requires one worker per authored role. Every
observed worker must match the current run, be non-stale and report its configuration.
SwarmApi supports the remaining observation budget through the same HTTP/state decoder.
Deadline bounds waiting for complete observations; no native CP subscriber is introduced.

HttpWorkAssertions owns shared test assertions for captured successful HTTP results
and swarm/processor identity, using canonical WorkItem and HttpResultEnvelope. It never
constructs outcomes. The header case checks canonical OutcomeHeaders and keeps all
observed processor step-header keys out of global headers. The history case also requires
the captured processor result to contain exactly one step at index zero, as selected by
the fixture's LATEST_ONLY policy. This verifies retention, not only configuration echo.
FULL and policy-update/reset/rejection behavior are covered through the SDK control-to-invocation
component test. These tests do not assert full/delta CP wire shape.
No product config resolver, wire DTO, lifecycle or broker cleanup implementation is added.


## Templating and scenario variables acceptance slice

TemplatingAcceptanceIT owns WK-3/SC-4 assertions over two explicit variable profiles
in independent Rabbit/Artemis fixtures. LiveRun passes the selected profile to the
canonical SwarmCreateRequest; it does not resolve variables. Existing SwarmResource
and TapResource own lifecycle, sample capture, evidence and cleanup. The test uses
FULL history to read the generated HttpRequestEnvelope and processed HttpResultEnvelope
from the same captured WorkItem, checks concrete rendered JSON/header values, and
verifies the producing workers against the current swarm/run. Expected values are
fixed test examples, not another templating engine or effective-configuration resolver.
The existing SUT mapping is read-only; there is no direct broker/SUT management access.


## RESP-ACCEPTANCE-WORKERS

WorkerObservations owns bounded waiting for a complete, fresh, current-run worker
configuration projection from the public SwarmStateView. A caller may additionally
require a phase predicate over that verified state and worker snapshot; it is evaluated
only after the shared freshness/identity/config checks. Each phase has one bounded wait
and a named evidence snapshot. Existing callers require RUNNING. It verifies the fixture's
one-instance-per-role expectation against bees and observation.workers. WK-1 and
WorkerConfigurationAcceptanceIT share this observer; it never merges CP messages,
resolves configuration or decides lifecycle success.

WorkerConfigurationAcceptanceIT owns WK-4/WK-5 comparisons of explicitly authored
fields against runtime configuration and runtime metadata. Image identity is checked
against the Docker adapter's resolved launch-image label from the public runtime
inventory, matched by swarm, run, role and instance with exactly one runtime.
The inventory is independent of worker status; `bees.image` is not used because
it projects the same status metadata. Authored short image names are not compared
directly with resolved references, and the test does not implement image resolution.
This checks launch/status consistency, not independently the prefix resolver itself.
Fixed expected baseUrl
examples test rendering without reproducing the resolver. Existing workers fixtures
provide baseline configuration; worker-overrides fixtures explicitly select different
values and adapter tuning. Both capture successful HTTP results and use the existing
SwarmResource/TapResource cleanup. The tap closes immediately after sample capture;
worker observation, assertions and STOP run after closure using the captured values.
Full/delta wire shape belongs to component tests
of WorkerControlPlaneRuntime with ControlPlaneEmitter and ControlPlaneCodec; it
cannot be inferred from the merged API projection. No new runtime authority is added.


## HTTP proxy acceptance slice (NW-1)

`HttpProxyAcceptanceIT` composes the existing lifecycle/capture/worker observers.
`TargetLoader.loadProxy` requires a network profile and endpoint in addition to the
ordinary work target, with no defaults or adapter inference. LiveRun accepts this
resolved lifecycle target and passes an explicit PROXIED mode/profile to the canonical
SwarmCreateRequest. Separate Rabbit/Artemis scenario bundles author the SUT proxy URL
and upstream URL. These addresses stay in the SUT fixture; the target selects IDs only.

ScenarioApi reads the bundle SUT using canonical SutEnvironment. NetworkBindingApi
uses canonical NetworkBinding through public ingress; there is no test wire DTO,
network resolver, direct proxy admin client or alternate binding writer. Assertions
compare the explicit authored endpoint with the binding, fresh processor config with
clientBaseUrl, and successful HttpResultEnvelope baseUrl/URL authority with that same
binding. URI parsing only inspects explicit fixture/result addresses; it does not
supply ports, defaults or build effective endpoints. Samples belong to the owned
swarm and observed processor. The tap closes immediately after sample capture.

SwarmResource performs STOP/REMOVE through the existing owner; NW-1 then explicitly
requires GET binding404. The suite never clears bindings itself, changes shared
profiles/manual overrides, or claims native HAProxy/Toxiproxy absence from an API read.
Successful HTTP through the published proxy address proves the active route; the
canonical binding404 verifies its removal at the supported API boundary.


## Network acceptance extension (NW-2/NW-3/NW-5)

WorkFixture replaces the HTTP-only name for the same immutable scenario/SUT/tap/expected
response projection, now consumed by HTTP and TCP suites. TargetLoader remains the only
parser; ProxyTarget selects profile/endpoint, TcpTimeoutTarget adds explicit mock
credentials, mapping id and quiet window. No legacy alias is retained.
ProxyAssertions owns read-only source/binding comparisons extracted from NW-1 and shared
by HTTP/HTTPS/TCPS. It does not resolve endpoints. HTTPS and TCPS verify the TLS scheme,
authored/runtime TLS configuration and actual protocol-specific result DTOs.

TcpMockApi reads the selected existing mapping through `/tcp-mock/api/mappings` at the
same ingress, with explicit Basic credentials (no authentication fallback). PocketHiveHttp
still owns all network IO, complete-body deadlines and same-origin checks. No mock mapping
or shared request journal is mutated. The slow-response fixture has a paired successful
request with a longer read timeout and a timeout case with a shorter read timeout.

SwarmJournalApi reads the selected swarm/run via the existing journal REST timeline;
RuntimeErrorObservations waits for the owned processor's canonical alert projection.
No second CP receiver, event merge/state machine or duplicated wire DTO. Error evidence
must match scope, run and runtime.exception; the published alert currently reports the
wrapper exception, so the test does not claim it exposes the nested SocketTimeoutException.
The selected mock's explicit delay, successful control case and shorter runtime timeout
provide the scenario context. TapResource.requireEmptyFor owns the bounded negative
capture check; TargetLoader validates TTL against START + error wait + quiet window + final bounded read.
Tap closes before config observation/STOP, and all swarms use canonical verified REMOVE.

## Provisioned authorization acceptance (AU-9/AU-10/AU-11)

AuthAdminApi maps the existing public Auth Service user/grant endpoints using the
canonical auth-contracts DTOs. AuthUserResource owns only a test-created UUID/name:
it verifies absence before provisioning, installs explicit grants, and cleans up by
revoking grants and deactivating that same identity. Auth has no user-delete API;
the inactive, grant-free record remains until the environment's normal reset.
Cleanup verifies the stored projection and a rejected DEV login, even after partial
setup. It never modifies configured users or claims that deactivation deleted a row.
Unconfirmed creation is inspected by exact UUID/name before cleanup; failed readback
or cleanup is a test failure, never an assumed success.

ProvisionedAuthTarget selects the explicit allowed scenario, another scenario in the
same folder, a scenario outside it, SUT, folder and bundle scope. TargetLoader is the
only parser. Catalogue assertions compare these explicit facts with the product's
bundlePath/folderPath projections, without reconstructing effective authorization.
AuthFixture composes admin observation/cleanup, unique test users and the existing
SwarmResource/OperationAwaiter. Existing API and operation owners are reused.

BundleAuthorizationAcceptanceIT proves exact PH_BUNDLE RUN profile/catalogue, allowed
CREATE and same-folder/outside-folder denials. FolderAuthorizationAcceptanceIT proves
PH_FOLDER ALL can manage a runner-created swarm while that runner's STOP is denied;
both identities have independently verified grants. DeploymentAuthorizationAcceptanceIT
proves folder ALL cannot refresh/reset and deployment ALL can refresh. No successful
RESET is sent. Responses and unchanged owned-swarm identity/state are recorded.
These tests exercise authorization, not a new implementation of lifecycle or reset.

## Scenario and swarm authorization (AU-7/AU-12)

ScenarioApi maps scenario CRUD; ScenarioFolderApi maps folder CRUD. ScenarioResource
and ScenarioFolderResource own only newly requested, unique test identifiers. They
check absence before create, retain ownership before dispatch, and verify deletion by
readback even after partial setup. They never delete repository fixtures or remove by
prefix. AuthFixture and AuthUserResource remain the sole actor composition/lifetime
owners. Scenario mutation tests clone an API projection under a fresh ID; they do not
copy a product DTO, validate a scenario themselves or implement filesystem cleanup.

Runtime materialization is tested at its Scenario Manager owner with MockMvc and
isolated temporary storage: VIEW and outside-folder RUN denials must have no
filesystem effect; matching RUN must copy the authored file byte for byte. The public runtime endpoint clears the entire swarm root
and has no independent DELETE/readback API. Do not call it on an active deployed swarm
or add a test cleanup path that manufactures another swarm to reclaim its directory.
This is explicitly component evidence, not a deployed positive runtime request.

SwarmManagementApi maps manager/component config commands to canonical ControlResponse;
ControlReceipts owns acknowledgement-key validation shared with SwarmApi. SwarmResource
retains these CONFIG_UPDATE commands just like lifecycle commands, so cleanup observes
pending operations instead of racing them. A definitive403 rejects that configuration
dispatch without creating an operation and leaves the owned swarm available for admin
removal; an unknown dispatch still blocks cleanup explicitly. OperationAwaiter still owns bounded reads;
no test outcome/state merger is added. SwarmJournalApi maps run/pin/metadata calls;
assertions use the exact owned swarm/run and read back stored pin/metadata effects.
Journal pins/metadata have no public deletion API; the test records its retained archive
ID explicitly. They are historical journal artifacts, not orphan worker resources.

TapSelection is the immutable logical tap request; WorkFixture exposes a read-only
projection into it. TargetLoader alone parses the additional explicit tap parameters
for SwarmAuthorizationTarget. TapResource owns creation/closure, including authorization
tests; its ID accessor exposes the acquired handle without transferring ownership.
AU-12 distinguishes READ (viewer/runner allowed) from MANAGE (runner close denied).
A separate actor with no grants proves denied tap reads. All actor grants are verified.
Independent management fixtures have an explicit processor URL and can run without a
bound SUT; network update409 then tests the documented missing-SUT conflict, not a
fabricated invalid profile. No successful reset, raw CP, broker or database access.


## Scenario timeline acceptance (SW-3)

ScenarioPlanAcceptanceIT owns explicit phase assertions for a new three-worker fixture:
initial rate, changed rate, generator disabled, generator enabled again, then all
workers stopped by the plan. CREATE and the initial START use SwarmResource; the test
sends no manual STOP or config update. It observes the product's plan rather than
implementing a scheduler. Real HTTP output is captured before the pause and after the
resume, using the existing tap and result assertions, with each tap closed immediately.

WorkerObservations remains the sole fresh/current-run worker projection observer. It
accepts a phase predicate over the verified swarm state and worker snapshot; one Deadline covers each wait. Existing callers retain their RUNNING assertion.
The scenario suite records each successful phase and reads the exact swarm/run journal
through SwarmJournalApi: five ordered completed steps, no failed plan step, one completed
plan and the current controller identity. Journal dispatch completion alone is not
proof of a worker effect; the phase observations provide that separate evidence.
Product execution/progress belongs to TimelineScenario/SwarmScenarioCoordinator and
SwarmRuntimeCore; the test does not consume/merge raw CP or calculate lifecycle outcomes.
Explicit Rabbit/Artemis bundles and existing target format; no new parser or defaults.

## Platform smoke (SM-1)

`PlatformSmokeAcceptanceIT` reads the existing UI `/healthz` and Orchestrator/Scenario
Manager actuator health routes through `ApiSurface` and the selected public ingress.
It asserts HTTP 200 plus the endpoint-owned `ok`/`UP` responses and records each
response. It uses ApiRun and has no lifecycle fixture, broker client, health catalogue
or independent health aggregator. CONTROL delivery and topology are verified in
owner component tests; public health alone is not evidence of physical bindings.
SM-2 requires a separately declared fresh deployment and is not implied by this smoke.

SM-1/SW-1 broker evidence lives in `rabbit-adapter`'s `SpringRabbitBrokerTest`: an
isolated Testcontainers broker exercises RabbitResources/Publisher/Receiver and the
canonical WORK resolver. It verifies CONTROL transport delivery, physical WORK
bindings (including healing), idempotent ensure, removal/absence and another swarm's
continued delivery. This is component evidence, not access to a deployed backend port.
CONTROL envelope semantics stay with ControlPlaneCodec/emitter tests; lifecycle replay
stays with OperationDispatchService/SwarmOperationCoordinator and target-state handling
with SwarmLifecycleCommandHandler. No new production responsibility is introduced.

## Redis fixture preparation (DA prerequisite)

**RESP-ACCEPTANCE-REDIS-FIXTURE:** `RedisCommanderApi` maps the deployed Redis
Commander HTTP interface under `/redis` through the existing PocketHiveHttp. The
connection id is required target data, never constructed from a hostname or discovered
by selecting the first connection. It supports only single-key observation, creating
a one-item list and deleting that exact key. No console command API, native Redis
client, glob deletion, flush, connection administration or alternate backend route.
`RedisFixtureTarget` holds ApiTarget and the explicit connection id; TargetLoader
remains the resolver. This target verifies the fixture boundary, not a WORK adapter.

`RedisListResource` owns a generated UUID key and its acquisition/cleanup lifetime.
It verifies absence before mutation or an explicit producer reservation (DA-4), retains uncertain writes, and verifies absence
after deletion. A pre-existing key must never be deleted; a failed/uncertain write
followed by absence remains unconfirmed. Successfully acquired lists may disappear
when consumed. Close never masks the original test failure. RunEvidence records
requests' responses and exact key identity without credentials. The Redis response's
`items.value` is a UI display representation, not the canonical dataset payload;
fixture observation asserts key/type/length, with actual payload assertions reserved
for the downstream WorkItem in DA-1/DA-2.

`RedisFixtureAcceptanceIT` verifies this boundary through public ingress, including
cleanup after a deliberate assertion failure and preservation of a second owned key.
It does not mark DA-1/DA-2/DA-4 complete: those still require worker traffic.

## Redis dataset acceptance (DA-1/DA-2)

RedisDataTarget composes the existing lifecycle target and explicit Redis Commander
connection id; TargetLoader resolves both. RedisDatasetAcceptanceIT uses LiveRun and
existing RedisListResource/ScenarioResource/SwarmResource/TapResource scopes. It creates
two UUID lists and an owned scenario derived from a new independently authored bundle.
Only that scenario's two input source keys change. Template/SUT text is transferred
through ScenarioApi's existing public content endpoints, unchanged and read back.
ScenarioApi remains a thin HTTP mapper; it does not clone bundles or resolve paths.
LiveRun composes the existing Redis client without another HTTP/configuration owner.

CREATE uses empty sources and disabled workers. The test waits for fresh STOPPED
observations with every worker disabled, seeds and reads back both records, opens its
processor tap, then sends START. Seeding does not consume the tap lifetime. This keeps Redis seed readback free of competing consumers and
prevents finite input from being processed during bootstrap before capture. It asserts both seeded payloads, the canonical Request Builder
HttpRequestEnvelope and successful processor HttpResultEnvelope from captured FULL
history. Distinct customer/nonce values bind observations to this test's input. No
Redis display-value parser or production transformation is reimplemented. It stops
and removes its swarm before deleting the owned bundle/lists. RedisDatasetResources
owns this dependency lifetime only; it delegates acquisition/deletion to the existing
handles. SwarmResource.permitsDependentCleanup projects its existing acquisition
state: only never-requested, definitively rejected CREATE, or verified removal permits
dependent cleanup. Unconfirmed CREATE or unresolved removal retains the scenario and
both list identifiers in retained-dataset-resources evidence and the reported failure.
No second lifecycle state, automatic retry or orphan cleanup is introduced.
These acceptance assertions use RESP-ACCEPTANCE-WORKERS and the existing resource
owners; Request Builder/Redis/Processor behavior stays with product modules.

## Transaction outcome persistence acceptance (DA-3)

TxOutcomeTarget composes the lifecycle target and explicit Grafana credentials,
datasource UID and outcome table. TargetLoader remains the configuration owner.
ApiSurface owns the existing public /grafana prefix; PocketHiveHttp extends its
existing Basic-auth support to bounded JSON POST without a second HTTP transport.
GrafanaTxOutcomesApi sends a swarm-scoped SELECT through /grafana/api/ds/query and
maps the returned table frames to read-only JSON rows. It checks nested query errors
and frame shape; it does not derive transaction success or duplicate TxOutcomeEvent.
The storage schema remains clickhouse/init/02-ph-tx-outcome-v2.sql; TxOutcomeProjector
and ClickHouseTxOutcomeSink remain the production projection/write owners.

TxOutcomeAcceptanceIT starts an independently authored scheduler/HTTP fixture with
NONE configured on its postprocessor. After observing successful HTTP traffic with
that runtime setting and no stored outcomes, it enables CLICKHOUSE_V2 through the
public component-config API. SwarmManagementApi maps the explicit component role
and instance; SwarmResource uses the existing CONFIG_UPDATE receipt/operation path.
The suite verifies the operation target and fresh applied worker configuration, then
captures new successful processor WorkItems and their trace IDs and awaits matching
persisted rows for the owned swarm and sink instance. Status,
success, call ID and duration are checked against concrete captured result/header
values. Fresh worker observations confirm the configured sink. No success is inferred
from postprocessor counters. Queries and waits are bounded; database rows are retained
as normal telemetry under the existing table TTL, with no acceptance DELETE or TRUNCATE.
The same suite runs on explicit Rabbit and Artemis targets. NW-4 remains deferred.

## Five-customer Redis WebAuth loop acceptance (DA-4)

WebAuthLoopAcceptanceIT authors an isolated five-customer RED -> shared BAL ->
shared TOP -> customer RED loop. RedisListResource owns each generated key;
reserveForProducer checks absence before granting a swarm permission to create an
initially empty intermediate list. Seeded lists retain their existing write checks.
RedisDatasetResources accepts a list of handles and retains every dependency until
SwarmResource permits cleanup; it adds no lifecycle authority. All cleanup failures
remain visible and all independent handles are closed.

WebAuthTarget composes explicit lifecycle, Redis connection and TCP mock credentials;
TargetLoader remains their sole resolver. TcpMockApi extends its read-only scope to
the public request journal with a bounded request budget. No shared journal clear or
mapping mutation is permitted. The suite matches exact authored XML requests with a
fresh nonce, customer/account/amount and RED/BAL/TOP stage, requires successful TCP
results and observes RED/BAL/TOP/RED in timestamp order for each of five customers.
Each request also exposes the actual Redis input header x-ph-redis-list as the
fixture-only sourceList XML attribute. Expected source keys come from the owned
resource handles: customer RED, shared BAL, shared TOP, then the same customer RED.
A correct customer/stage payload arriving from another customer's list must fail.
Test-owned request strings are fixture expectations, not a WebAuth protocol parser or
production renderer. Existing Request Builder and RedisPushSupport remain runtime
rendering/routing owners. The suite uses one round-robin generator across seven lists;
weighted multi-generator scheduling is not a DA-4 acceptance condition.

Preparation clones the newly authored acceptance fixture through Scenario Manager,
substitutes only owned list identifiers and writes its template/SUT through public
APIs. Both producer-only keys are reserved before CREATE; the five source lists are
seeded only while workers are confirmed disabled. The producer-only keys may remain absent until the loop writes
into them. The swarm is removed before deleting any dataset key or scenario.

## Export observation in the swarm runtime directory (EX-1..3)

Human direction, 2026-09-18: output belongs inside the swarm's own directory. The
previous proposal for a separate exportHostRoot/exportWorkerRoot and a new bind mount
is withdrawn. Reuse the existing shared runtime filesystem mount.

Implemented path ownership: RuntimeFilesystemLayout owns the local and published
worker-output directory below the existing swarm/run directory:

```text
<runtime-root>/<swarmId>/<runId>/outputs/<workerInstance>/
```

Human direction, 2026-09-18: this is mandatory for every exporter, including after
config-update. ClearingExportStorageConfiguration derives the directory from runtime
identity and the existing mount, then supplies it to LocalDirectoryClearingExportSink.
localTargetDir is removed from worker configuration and capabilities. RuntimeOutputDirectory
is an immutable path projection; file names and manifest paths must stay within it.
Run and worker identity prevent unrelated runs/exporters sharing filenames.

Test observation will use the same layout projected onto the explicitly selected
host-visible runtime root. It must not reconstruct the layout, discover container
internals or infer a remote-host path. No separate mount or export root is introduced.
The output observer is implemented. EX-1 now has Rabbit/Artemis fixtures and an acceptance suite; deployed verification is tracked in the coverage ledger. EX-2 structured XML is implemented; deployed evidence is in the coverage ledger. EX-3 remains planned.

Discovery: ClearingExportWorkerImpl returns no output WorkItem. Its batch writer
publishes file lifecycle metadata/counters, not file bytes. The optional JSONL export
manifest is also metadata, not content proof. Orchestrator/MCP has no finalized-file
download interface. No new product API is proposed merely for E2E observation.

Cleanup remains owned by the existing swarm lifecycle. FilesystemSwarmRemoveStore
already deletes the whole swarm runtime directory through RuntimeFilesystemLayout.
Therefore tests must validate finalized file contents and save evidence BEFORE REMOVE;
they must not add another output-directory deleter or try to inspect files after the
swarm directory has been removed. Verify cleanup through the canonical REMOVE result.

### Implementation sequence

1. DONE (code and component verification): integrate mandatory output location with
   the existing layout and exporter composition. Deployed verification remains pending.
2. Add explicit local output observation for the selected runtime root. Keep normal
   lifecycle, fresh worker/config and journal access through ingress. The earlier
   ingress-only rule question applies to direct file observation; the user's location
   decision does not authorize container inspection or remote-host access.
3. Independently author Rabbit/Artemis fixtures with exactly20 distinct input records.
   EX-1: two finalized text files of ten records; verify exact record union, headers
   and footers. EX-2: applied structured config/schema and two XML files of ten records,
   checked with standard XML parsing and concrete expected values/totals.
4. EX-3: set record limit above20 and require one file finalized by the time window,
   containing all20 records and footer BEFORE STOP, so shutdown cannot stand in for
   the time trigger. For every mode verify no temporary files remain at completion.
5. Save observed content as test evidence, remove the swarm normally, then verify
   canonical cleanup. Run both WORK adapters before marking EX rows PASS. Remote
   Swarm/NFS work remains deferred NW-4; no remote filesystem workaround is implied.

### RESP-ACCEPTANCE-EXPORT-FILES

`ExportFiles` reads UTF-8 finalized output and names of pending temporary files for
one observed swarm/run/worker identity. It obtains its directory exclusively through
RuntimeFilesystemLayout, using an explicitly selected existing local runtime root.
`ExportFilesSnapshot` is a read-only observation, not a completion/outcome calculation.
The temporary suffix comes from the observed exporter configuration. Pending files
are not opened: they may be renamed during observation. Snapshots are not atomic;
tests poll with the existing Deadline and decide content/count postconditions.
Missing worker output directories mean no output yet; a missing/inaccessible selected
runtime root is an error. Finalized files must be regular files; symlinks are rejected.
No directory creation/deletion, mount discovery, container access or lifecycle logic
belongs to this observer. Save snapshots through RunEvidence before normal REMOVE.
`ExportTarget` extends lifecycle target settings with the explicit local runtime root
and Redis connection for twenty distinct owned input records.

EX-1 preparation uses twenty UUID Redis lists, each seeded once through the existing
RedisListResource, and one owned scenario guarded by RedisDatasetResources. This reuses
canonical acquisition/cleanup without adding a bulk-write or deletion authority.
The fixture routes Generator → Clearing Export through the selected Work adapter.
Assertions compare two files of ten exact nonce-tagged records, headers and trailers,
with no pending files; duplicates cannot replace missing records. Results are checked
before STOP and once more after STOP, then normal REMOVE releases the runtime tree.

EX-2 reuses the EX-1 lifecycle/data/file-observation flow. Its independently authored
JSON clearing schema is read and copied through Scenario Manager's existing schema
API into the owned scenario, then read back for equality before CREATE. Runtime
observations must show structured mode, the explicit schema id/version/root and
batching settings before any input is seeded. Twenty inputs contain distinct IDs
(including XML special characters) and amounts1..20. Standard JDK XML parsing checks
two documents, ten records each, exact IDs/amounts, header marker and per-file trailer
counts/totals. Expectations come from fixture inputs, not the product renderer.
The observer remains format-neutral; cleanup and storage ownership are unchanged.

EX-3 uses the same owned data/lifecycle/observer flow with a streaming text fixture:
twenty records, maxRecordsPerFile100, streamingWindowMs15000 and flushIntervalMs900000.
Applied configuration is checked before seeding. The test requires exactly one finalized
file with all twenty records and trailer, no pending files, before issuing STOP.
A read-only observer starts before the blocking START call and samples concurrently
until finalized output has no pending files. Each snapshot carries monotonic elapsed
time from before START. After START settles, the caller writes the timeline and checks
that no sampled finalized file appeared before the configured window, and completion
preceded the ordinary flush interval. Late START confirmation cannot replace the
recorded observation time. This is a conservative bound with polling resolution,
not an exact first-record timestamp. Existing Deadline owns the observation budget.
The observer performs no lifecycle calls or evidence writes; it is cancelled and joined
before resource cleanup on every exit. After joining, the caller always passes an
immutable copy of the collected timeline to RunEvidence, including on START failure,
read failure, timeout or interruption. No background evidence writes occur. Existing
RunEvidence owns write-error deferral so the original failure survives cleanup.
Content is checked again after STOP.


## Fresh deployment smoke (SM-2)

FreshDeploymentAcceptanceIT runs only under the explicit `fresh-deployment` tag and
requires a FreshDeploymentTarget with a deploymentId in addition to ApiTarget fields.
This is the operator's declaration of a newly provisioned dedicated environment,
not a freshness inference from an empty API result. Ordinary API targets are rejected.
The deployment operator records creation evidence separately; the test performs no
reset, refresh, deployment or resource cleanup. It requires the selected actor's exact
DEPLOYMENT/GLOBAL ALL grant through ActorAssertions before reading SwarmApi.list, so
an authorization-filtered empty list cannot pass as an empty deployment. The canonical
Orchestrator list must be empty; the response and declared target are saved as evidence.
Run platform `smoke` alongside it on the same fresh target using its API-only settings.

For local evidence use the canonical docker-compose.yml with the test-only
acceptance-tests/deployments/fresh-local.compose.yml overlay: a unique Compose project,
private network and new project-scoped volumes, a new explicit host runtime root, and
only a separate UI ingress published. Reuse built images; no rebuild is needed.
The overlay changes isolation settings only, not startup policy or product behavior.
Launch ui, auth-service, orchestrator and artemis plus their declared dependencies.
After evidence collection, remove only this owned project and its named volumes.
The local procedure runs in a subshell; its project/runtime environment never replaces
the operator's prior shell settings, on success or failure. The established local
Artemis stack remains running. NW-4 stays deferred.


## Binding recovery acceptance (NW-4)

User-approved local execution comes first; cross-node Swarm/NFS remains a later
execution of the same behavioral test. Local success does not prove remote filesystem
propagation. The product apply/rollback contract remains ARCHITECTURE section 2.6.

`BindingRecoveryTarget` is a read-only projection of ProxyTarget plus the explicit
minimum rejection duration. TargetLoader alone resolves it. The target declares an
HTTP timeout longer than that bound to observe the server's failure response, rather
than treating a client timeout as rejection. Supplied targets reuse the independently
authored HTTP proxy fixtures for the selected WORK adapter; they do not switch brokers.
The minimum is a test expectation for that deployment, not a product apply-timeout default.

`NetworkBindingRecoveryAcceptanceIT` creates a normal PROXIED swarm through LiveRun
and SwarmResource. The Orchestrator resolves SUT addresses and applies the valid binding.
ProxyAssertions checks the public binding against the authored SUT; captured successful
HTTP results prove traffic uses its client address. The test closes that tap and stops
the swarm before submitting an invalid candidate through NetworkBindingApi.

The candidate reuses the canonical observed binding/profile and SUT identity, with only
one selected endpoint's clientAuthority replaced by the explicit malformed fixture
`invalid:-1`. The test does not resolve production resource names or reproduce the
HAProxy renderer/port offset. This input currently reaches HAProxy candidate validation;
a fast parsing/authorization/conflict error must not masquerade as the apply failure.
The test requires HTTP500 after the configured minimum duration, and exact equality
of the original and subsequent binding (including appliedAt). It records request,
response, monotonic duration and both observations through RunEvidence. The bound
and source trace distinguish this case from an immediate HTTP400; the test does not
read native configuration files or claim inspection of the applied digest.

A new tap opened after rejection observes successful HTTP results after the same swarm
restarts, with disjoint message IDs, the same producing worker identity and the same
proxy request address. After STOP, the test explicitly POSTs clear for its own swarm
and requires HTTP200 plus GET404. SwarmResource then performs normal verified REMOVE;
it remains the sole cleanup owner on both success and test-body failure. The test adds
no synthetic binding registry, standalone binding resource, native client, global
cleanup or fallback. Existing Orchestrator REMOVE also clears a surviving binding.

Framework tests cover canonical request transport and preserve rejection as data;
focused assertions reject successful/too-early mutation responses and changed retained
bindings. The deployed test proves actual proxy processing before and after rejection.
A later Swarm run must independently establish NPM/HAProxy placement on separate hosts
and the shared NFS runtime; the API-only test does not infer placement from node count.


## Delayed Work delivery acceptance (A5)

`DelayedDeliveryAcceptanceIT` uses the ordinary `LiveRun`, HTTP clients, lifecycle
resources and diagnostic tap. The scenario owns the delay value; the test reads it
from the selected fixture. Existing observability hops supply generator completion
and processor admission timestamps, avoiding a false pass caused by slow sample
polling. It verifies successful HTTP results and swarm removal through public ingress.
It does not reimplement broker scheduling, name resolution or delivery validation.
Embedded broker and Controller/SDK flow tests own not-before delivery, immediate
traffic bypass, scheduled-resource removal and startup-only policy rejection.
This transport slice does not assert full 3DS correctness or load capacity.
