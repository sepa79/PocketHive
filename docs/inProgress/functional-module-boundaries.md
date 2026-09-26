# Functional module boundaries — next refactors

## Integrated Tim delivery — 25 September 2026

Tim’s delivered scope: F06 worker authentication, F09 MCP application/projection
boundaries, and F07 TCP workspace catalogue correctness. Baseline: PR #523 head
`474912ef8f817b750ec259bad719fbf6f207e57b`, including #522 ancestry.
Source branch: `fix/f06-f07-f09-release`, commit `ed9ceacf`.
Integrated locally by `7308aee2`; the combined delivery lives on `codex/f07-ph-ui`.

F07 retains the single-user KISS foundation: server-owned default/deletion policy,
confirmed mutation results, visible errors and explicit reload. Workspace selection
is presentation context only: no mapping/traffic isolation, access
control or scenario/swarm link. The user subsequently required durable workspace
metadata; the catalogue now uses atomic file persistence in the existing data volume.
Administration explicitly selects NATIVE credentials or POCKETHIVE identity by
configuration. Standalone use requires no auth-service; PocketHive Compose selects
its shared login. Provider failures never switch authentication or affect TCP traffic.
New workspace owners retain provider-qualified identity across restarts and provider
changes; ownership does not implement tenancy. MCP persistence is outside this change.
See [contract](../tcp-mock/legacy-workspaces.md).

Native TCP upgrade code, tests and qualification progress remain preserved in
`feat/tcp-mock-upgrade`; they are deferred from this release candidate. This branch
retains the established TCP runtime and UI style; Compose selects PocketHive login.

Tim’s component, browser and review evidence is recorded in
[his handoff](../ci/evidence/2026-09-25-f06-f07-f09/README.md). Integrated verification
is separate from that source snapshot; test evidence does not imply release approval.


Status (2026-09-25): PR #520 integrates F01/F03 from #521, F04/F05 from #522,
and the reviewed #523 delivery from `codex/worker-inputs`. The latter includes
F02 local inputs, F07 producer-owned Scenario Manager contracts, and selected F09
processor and TCP mock boundaries. TCP mapping persistence and the equal-priority
restart fix are implemented, tested and accepted in separate review.

Tim’s F06/F07-TCP/F09-MCP delivery (`ed9ceacf`) is now integrated into
`codex/f07-ph-ui`. The current table includes our subsequent PH UI and Scenario
Manager repairs. F08 observation/freshness ownership and the separately approved degraded-control
behavior are implemented and reviewed (2026-09-26; verification below). Remaining lifecycle correctness and promotion of TCP runtime mappings
into PocketHive scenarios remain separate follow-ups. Historical
baseline analysis and intermediate verification remain below and in Git history.

## Current remaining work

This is the current backlog on `codex/f07-ph-ui`, based on PR #520 and integrated
with Tim’s release branch. Completed slice sections
below retain implementation evidence; their older test counts and intermediate
"remaining" statements are historical, not current gates. This table and the latest
closeout below define the remaining scope.

| ID / area | Current status and next action |
| --- | --- |
| F01–F05 | Closed in the selected scope: Redis, local inputs, Docker, journal/filesystem and ClickHouse. Do not repeat these extractions. |
| F06 — Tim | Implemented and integrated: AuthProfileLoader, AuthCredentialApplication and OAuth2TokenProvider own profile discovery/preparation, credential application and ordinary token acquisition. Existing signed OAuth and coordination owners remain. Loader consumes our ScenarioBundleLayout. |
| F07 — completed contracts | Scenario Manager producer contracts closed; do not repeat this extraction. Remaining work is split into F07-TCP and F07-UI below. |
| F07-TCP — Tim | Implemented and integrated: server-owned workspace policy, durable catalogue, confirmed UI mutations and explicit NATIVE/POCKETHIVE administration. Workspace selection does not isolate mappings or traffic. Tim’s acceptance evidence is linked below; it is not a deployment check of this integrated tree. |
| F07-UI — Zbigniew | Implemented: main PH UI uses backend access projections and explicit network modes; approved endpoints and UI consumers are complete. Browser/deployed acceptance remains unperformed. Broader model sharing requires evidence. Excludes TCP mock UI. |
| F08 | Implemented and reviewed, including the journal-failure correction. SwarmReadinessTracker owns heartbeat time, observed enablement and freshness; metrics and worker-list projections consume it. Separately approved degraded-control behavior and its UI fix are also reviewed; verification below. |
| F09 | Processor/TCP extraction closed. MCP caller/client-interaction and knowledge projection boundaries integrated from Tim. SM bundle/access/authoring APIs, shared layout and S1–S3/S9–S10 repairs implemented; integrated review findings are disposed below: TCP permission duplication fixed; large-file fingerprint memory explicitly accepted as a limitation. No outstanding implementation finding in this slice. |
| Separate correctness | Orchestrator reset/registry/recovery and orphan-removal outcomes follow `orchestrator-correctness.md`. Processor transport replacement/failure/shutdown lifecycle is also deferred; extraction did not repair it. |
| Separate TCP debt/features | Mock scenario-state reset/persistence/null semantics, public nested DTOs and promotion of runtime mappings into PH scenarios are outside the closed TCP slice. Workspace UI work belongs to F07-TCP (Tim). |

This backlog does not authorize behavior or public-contract changes. Remaining
extractions preserve existing behavior; raise concrete conflicting semantics
or contract changes for review when found. Full-Swarm qualification is not implied by
unit/component evidence.

## Objective and rules

Each functional responsibility has one implementation owner and a supported API.
Consumers pass intent or consume resolved values/projections; they do not reconstruct
paths, resource names, defaults, validation or outcomes. A module may contain several
focused classes. Neither one giant class nor an interface per class proves SSOT.

Keep domain policy with its domain owner and technology mechanics with its adapter.
Use ports when a consumer needs a capability independent of that implementation.
Do not create a generic infrastructure framework, DTO bag, discovery mechanism or
new adapter selection policy. Extend existing modules before inventing another owner.

Extraction preserves behavior. If implementations disagree, record the exact inputs,
outputs and effects and obtain a behavior decision before consolidation. Do not hide
fixes, migrations, compatibility aliases, retries or changed defaults in a move.
Rabbit/Artemis ACK on work admission is intentional: processing failures are reported,
not redelivered. Do not reopen it. Existing approved CP ENV limitations remain accepted.

Input/output is broader than WorkPlane: Rabbit and Artemis implement WORK transport;
Redis, CSV and scheduler inputs do not thereby become inter-worker topology owners.
Generator business logic remains in its service. CSV and scheduler are input-only.

## Already completed — do not redo

- Rabbit technology API and WorkPlane extraction; Artemis adapter, explicit global
  WORK selection and delayed delivery through the existing publication path (A1–A5).
- Neutral transport contracts already exist in `common/work-api`. In particular,
  `transport/WorkOutput.publish(WorkItem, WorkDelivery)` does not take WorkerDefinition.
- Redis settings/parser ownership exists in `common/redis-config`; reuse it, do not
  create a competing parser while extracting transport/client operations.
- Request-template format parsing has its owner in `request-templates`; filesystem
  loading is a separate concern in `request-template-files`.
- Worker executing identity and scenario history-policy propagation were repaired;
  FULL/LATEST_ONLY remain, DISABLED was removed. These are not pending refactor tasks.
- Exporters now require swarm-scoped runtime output directories, including updates.
  Remaining journal layout work must reuse the existing filesystem owner.
- Independent acceptance framework and final N3 review are complete. Latest large-Swarm
  evidence covers 57/57 cases across the full run and two corrected image-test reruns.
  Rabbit WORK has prior local evidence; it was not rerun on the remote Swarm.
  See [coverage](../ci/acceptance-coverage.md) and [report](artemis-swarm-full-acceptance.md).

N4 legacy E2E deletion still requires manual confirmation. A6 full 3DS, APATA/App mock,
output selector/splitter and CloseLook are separate work. The Dev stack was removed
through HiveForge on 2026-09-22 after testing; future live verification needs a deployment.

## Revalidated ownership map

This refresh traces the named current source paths and searches production imports.
It is not an exhaustive new review of all services. Confirmed extraction seams are
separated from historical findings that still require revalidation.

| Area | Current evidence | Next boundary |
| --- | --- | --- |
| Redis | All five paths use `common/redis-adapter`; settings remain in `redis-config` | F01 implemented; see Redis extraction evidence |
| Work integration | F02 removed the unused snapshot-typed `WorkInput.update`; SDK factories retain WorkerDefinition within composition; local mechanics consume settings/neutral policy contracts | Retain SDK composition and accepted-state ownership; do not move unused abstractions into work-api |
| Sequences | SDK `RedisSequenceConfiguration` owns application-scoped instances; no global sequence client | Implemented with F01 |
| Docker | Client construction, compute selection mechanics and runtime operations use `common/docker-client`; both services consume compute/host ports; stack naming has one implementation | F03 implemented; applications retain lifecycle decisions and cleanup postconditions |
| Journal/files | Shared file paths; query, metadata, capture and retention ports implemented; 111 focused tests green | F04 implemented and reviewed; Hive and swarm producer contracts remain distinct |
| ClickHouse | Both sinks use `ClickHouseJsonEachRowTransport`; shared ENV projections and property-owned defaults replace service copies | F05 implemented, tested and reviewed; separate domain/buffering policies preserved |
| Worker auth | AuthRuntime delegates discovery/preparation to AuthProfileLoader, application to AuthCredentialApplication and ordinary acquisition to OAuth2TokenProvider; signed acquisition and TokenStore retain their separate owners | F06 integrated from Tim; shared bundle layout is consumed by the loader |
| Freshness | `SwarmReadinessTracker.STATUS_TTL_MS` and `SwarmWorkerStatusHandler.WORKER_STATUS_STALE_AFTER_MS` remain separate 15s definitions | First decide whether they describe the same fact; then one owner/projection for that fact |
| UI network projection | `ui-v2/src/lib/networkProxy.ts` maps every unknown mode to DIRECT | Separate contract/behavior decision; do not silently change acceptance during extraction |

Selected Scenario contract copies, processor HTTP client ownership and TCP mapping
persistence were resolved by the completed slices below. Remaining metadata, UI
grants, MCP readiness and broader service findings are audit leads: recheck their
current owners and callers before adding implementation work. Do not reuse old
MCP/auth inventories or test counts as current evidence.

## Delivery order and stable task IDs

Keep F identifiers for existing references. The selected F01–F09 slices are now
implemented; do not restart the historical delivery sequence below. The latest
review and verification closeout governs the current delivery gate. Deferred reset,
recovery, transport lifecycle and TCP features remain separate work.

### F01 — Redis, one PR closing the shared technology responsibility

Implemented in `9a12dd50`; [extraction status and evidence](redis-adapter-extraction.md).
The requirements below describe the completed extraction, not a fresh task.

Deliver the Redis extraction as **one PR covering all five consumers**. The steps
below are implementation checkpoints within that PR, not independently mergeable
transfers. Introducing an adapter for capture alone does not establish SSOT while
other consumers still construct RedisURI and RedisClient themselves.

Start with an inventory of connection settings, client lifetime, keys/list names,
serialization, timeouts and operations in RedisDataSetWorkInput, RedisPushSupport,
RedisTokenStore, RedisDebugCaptureStore and RedisSequenceGenerator. Record current
behavior tests before moving implementations. Distinguish Redis mechanics from
token identity, dataset selection, trace identity and sequence policy.

1. **Shared mechanics and API.** Establish `common/redis-adapter` as the sole owner
   of connection realization and Redis client operations for all five consumers.
   Reuse redis-config as the sole pure settings/parser module; do not add fields,
   defaults or a second parser. Keep raw Lettuce clients, commands and arbitrary
   client callbacks internal. Expose only the capabilities required by consumers.
   Shared ownership does not imply one shared connection or identical lifetimes:
   preserve each consumer's existing eager/lazy initialization and operation policy.
2. **Diagnostic capture.** HttpSequenceRunner consumes an expiring-write capability
   instead of constructing the SDK RedisDebugCaptureStore. Keep capture selection,
   keys and payload with HttpSequenceRunner. Preserve encoding, expiry and best-effort
   failure behavior. This diagnostic capture is distinct from WorkPlane debug taps.
3. **Dataset input and output.** Move Redis operations into the adapter and reuse
   neutral Work transport contracts. If input extraction needs an update view,
   expose only immutable adapter-facing values, not the complete runtime snapshot
   or WorkerDefinition. Dataset selection and output target rules each retain their
   existing canonical owner; SDK composition delegates through the new API.
4. **Token storage and sequences.** Move Redis implementations behind TokenStore
   and SequenceAccess, preserving atomic claims, expiry, increment/reset and errors.
   Inject sequence access explicitly and remove hidden global client configuration.
   OAuth refresh policy and token-key identity remain with their auth owners.
5. **Atomic cutover and closure.** All five paths use the adapter before the PR
   merges. Delete replaced implementations and client constructors/helpers; remove
   SDK/templating Lettuce import exceptions. No production Lettuce imports remain
   outside redis-adapter, and there is only one settings-to-connection implementation.
   Do not publish a partially migrated consumer as a completed SSOT transfer.

Configuration gates use the fields actually present in RedisConnectionSettings:
non-default host, port, username, password and ssl reach client construction unchanged.
Database selection is not an existing field and must not be introduced in this
extraction. Preserve currently effective behavior without adding a database option.

Failure gates distinguish stages and consumers instead of requiring every failure
to close the connection. For RedisDebugCaptureStore specifically:

- failed connection initialization releases partially created owned resources;
- an ordinary RuntimeException during an established connection's diagnostic write
  returns false and retains the connection; do not add close/reconnect or retry;
- close marks the store closed and attempts connection/client release, preserving
  existing cleanup-error and interrupt handling; subsequent store calls return false.

Record equivalent initialization/operation/shutdown expectations separately for
input, output, token store and sequences before extraction. Existing differences
are not permission to unify error policy. If consolidation requires a behavioral
change, resolve it separately before implementing that part.

Use owner tests for non-default settings, operation results and resource lifetime,
plus affected integration/official-ingress acceptance (DA-1/2/4, token/auth and
sequence paths as applicable). HTTP Sequence diagnostic-store verification is
required: successful WorkPlane taps alone do not exercise this Redis path.
Keep the deferred Redis STOP/update/START race as a separate behavior decision.

The initial inventory and API review are complete; their implementation and
verification are recorded in the [Redis extraction report](redis-adapter-extraction.md).

### F03 — Docker/compute

Implemented in `3116364c`, after Redis extraction commit `9a12dd50`.
Ownership is defined by [RESP-DOCKER-RUNTIME](../architecture/runtime-responsibilities.md#resp-docker-runtime).

- `DockerEngine` owns connection lifetime and compute/runtime construction;
  `DockerConnections` realizes SDK configuration. Orchestrator and Controller receive
  `ComputeAdapter` and `ComputeHost`; neither constructs raw SDK clients.
- `DockerRuntimeClient` owns inventory, inspect, logs and explicit force removal.
  `DockerInspectMapper` interprets Docker fields and returns manager-sdk's neutral
  `RuntimeInspection`. Orchestrator retains response construction/redaction and
  cleanup eligibility, approvals and verified removal postconditions.
- `DockerControllerEnvironment` owns Docker ENV/socket encoding. `DockerRuntimeNames`
  replaces all four stack-name formulas used by manager launch, worker launch,
  controller status and Docker stack labels.
- The uncalled `DockerWorkloadProvisioner`/`WorkloadProvisioner` path was removed.
  Existing import restrictions now reject raw Docker SDK and concrete operation
  implementation imports from both services; legacy E2E keeps its existing exception.

Preserved behavior: Orchestrator AUTO manager detection, Controller concrete-mode
selection, connection precedence, lifecycle stop/remove and service-drain policy,
force-removal exceptions, inspect aliases/nulls/redaction and historical RW diagnostic
calculation. Naming still yields `ph-` plus the lowercased swarm ID; caller-side
trimming remains unchanged. No ACK, retry, cleanup approval or lifecycle timing change.

Verification on 2026-09-23: **186 tests passed, zero failures/errors/skips** across
focused Docker/runtime/lifecycle/environment/architecture tests and the existing
Orchestrator creation and Controller lifecycle integration tests (four integration
cases). All affected reactor modules compiled, including tests. Commands:

```sh
mvn -q -pl orchestrator-service,swarm-controller-service -am test -Dtest=Docker*Test,Runtime*Test,ContainerLifecycleManagerTest,SwarmLifecycleManagerTest,SwarmWorkerSpecFactoryTest,SwarmControllerRuntimeMetadataTest,ControlPlaneContainerEnvironmentFactoryTest,RepositoryImportBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q -pl orchestrator-service,swarm-controller-service -am test -Dtest=DockerSingleNodeComputeAdapterTest,DockerSwarmServiceComputeAdapterTest,RepositoryImportBoundaryTest,SwarmCreationMock1E2ETest,SwarmLifecycleManagerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Counts include each final test case once across both runs. Adapter effects are verified
against Docker command doubles; component integrations exercise service wiring and
control-plane collaboration. No fresh deployed Docker/Swarm acceptance was run for
F03. Separate source review on 2026-09-23 found no actionable issues in the complete
change set, including connection/compute composition, naming, diagnostics and removal.
All six review passes were recorded in HiveMind; deployed acceptance was not rerun.

The startup audit findings assigned to F04/F05 were subsequently implemented.
F08 observation/freshness work remains open and was not part of F03.

Gate: raw Docker imports allowed only in docker-client/test fixtures; both services use the same client
construction owner. Removal-result semantics retain the separately approved contract. Keep explicit human approval
at the caller boundary; no new approval flow and no live cleanup during this refactor.

### F04 — Runtime filesystem and journal

**First F04 slice reviewed and committed** as `a651d468`, branch `codex/journal-filesystem`,
based on `7c6c402d` after all four PR #521 CI checks passed. The complete file-read
path now leaves REST through SwarmFileJournalQuery → SwarmJournalFiles →
FileSwarmJournalReader. RuntimeFilesystemLayout owns the journal artifact path
used by both reader and writer.
See `RESP-SWARM-FILE-JOURNAL` in the runtime responsibility records for the preserved
selection/error behavior and the remaining ownership debt.

Pre-extraction baseline: FileSwarmJournal and SwarmJournalController both owned
`journal.ndjson`; the reader also reconstructed the run directory.

Validation: 45 tests passed, zero failures/errors/skips, including file read/write,
run selection, HTTP mapping, removal isolation, Postgres storage/pinning, authorization
and RepositoryImportBoundaryTest. Test log: `/tmp/ph-f04-tests.log` (local evidence).
No deployed E2E was repeated for this slice. Invalid file-query run identifiers now
follow the existing layout validation; no new HTTP response contract was introduced.

**Event reads implemented and reviewed in `1aa5e3d1`:** Hive/swarm/live/archive event SELECTs,
row mapping and cursor construction now use JournalEventQueries in journal-postgres.
SwarmJournalRunSelector is the sole explicit/active/observed run selector for file
and stored reads, including pinning. SwarmStoredJournalQuery retains archive
precedence and registry-aware empty/absent results. JournalPageResponse and its
cursor moved to the shared API with unchanged JSON fields; the old DTO was removed.
See RESP-JOURNAL-EVENT-QUERIES; no wire or write-policy change.

Validation: 58 tests passed, zero failures/errors/skips, including real PostgreSQL
live/archive paging with equal timestamps, filters, mapping, storage lookup failures,
run selection, authorization, prior file behavior and import boundaries.
Command: `./mvnw -B -ntp -pl orchestrator-service,swarm-controller-service -am test`
with the focused journal/filesystem/auth/import test selection; local log
`/tmp/ph-f04-sql-tests.log`. No deployed E2E or full reactor repeat in this slice.

**Run lists implemented and reviewed in `1aa5e3d1`:** list SQL, summary/tag mapping and live/pinned
merge now use JournalRunQueries; ordering/filter/limit semantics are preserved. The
metadata update response uses the same summary reader. See RESP-JOURNAL-RUN-QUERIES.

Validation: 69 focused tests passed, zero failures/errors/skips, including real
PostgreSQL run merging, swarm isolation, afterTs aggregation, null-date ordering,
metadata-only summaries, prior event/file behavior and import boundaries.
Log: `/tmp/ph-f04-runs-tests.log`. No deployed E2E or full-suite run.

**F04 implementation complete and reviewed:**
metadata registration/operator edits, capture/pinning and retention now use the
separate ports and adapters in RESP-JOURNAL-WRITES. REST contains no journal SQL,
archive outcome construction or nested request/response records. Lifecycle startup
projects template metadata through JournalRunRegistration; the scheduled trigger
calls JournalRetention. Removed JournalRunMetadataWriter/JournalPartitionManager
and their SQL implementations from Orchestrator. One shared adapter owns each write.

Existing BufferedPostgresJournalWriter still owns event INSERT/buffering for the
separate Hive/swarm producer contracts. File append/read/remove paths use the shared
RuntimeFilesystemLayout; the completed exporter-directory fix was not reopened.
All existing policies, mode defaults, HTTP payloads and nontransactional statement
ordering remain unchanged. No database/schema or migration change.

Validation: **111 tests, zero failures/errors/skips**, including real PostgreSQL
metadata registration/edit/clearing/ambiguity, all pin modes and repeat/conflict,
retention cutoffs and pinned archive survival, HTTP error/authorization mapping,
producer append/durable behavior, file queries/removal, run/cursor projections,
ContainerLifecycleManager and RepositoryImportBoundaryTest. Command:
`./mvnw -B -ntp -pl orchestrator-service,swarm-controller-service -am test`
with `-Dtest='*Journal*Test,PinModeTest,RuntimeFilesystemLayoutTest,FilesystemSwarmRemoveStoreTest,RepositoryImportBoundaryTest,OrchestratorAdminAuthTest,ContainerLifecycleManagerTest'`
and `-Dsurefire.failIfNoSpecifiedTests=false`.
Log: `/tmp/ph-f04-complete-tests.log`. Full reactor/deployed E2E not repeated.
Final whole-F04 review: no blocking findings. Full Orchestrator/Swarm Controller
and dependency tests passed with `AUTH_OPENSSL_TEST_EXECUTABLE=/usr/bin/openssl`:
1806 tests, 1802 passed, 4 skipped for missing explicit Redis fixture configuration,
zero failures/errors. Log: `/tmp/ph-f04-review-configured-tests.log`.
No deployed stack/Swarm E2E was repeated.

Gate: read/write/export/delete use identical resolved paths; invalid identifiers
follow one owner contract; REST maps requests and delegates; retention has one writer.
Do not reopen the completed exporter-directory fix.

### F05 — ClickHouse

**F05 implementation, verification and separate review complete** on `codex/journal-filesystem`, after
F04 commit `1aa5e3d1`. Ownership is recorded in RESP-CLICKHOUSE-INSERT and
RESP-CLICKHOUSE-ENVIRONMENT in the runtime responsibility records.

- `ClickHouseJsonEachRowTransport` owns HTTP client construction, INSERT URI,
  UTF-8 JSONEachRow framing, authentication, timeout use and HTTP success checking.
  Both sinks consume a prepared `ClickHouseInsert`; the old HTTP implementations
  are deleted. Existing properties expose a read-only connection view without a
  second configuration/defaults owner.
- `ClickHouseSinkEnvironment` replaces transaction ENV mapping in
  ContainerLifecycleManager and SwarmWorkerSpecFactory. Existing keys (including
  blank/null) retain precedence. `ClickHouseMetricsEnvironment` replaces both
  copies of metrics fields in ControlPlaneContainerEnvironmentFactory, preserving
  runtime/controller prefixes and metrics overwrite semantics.
- Bootstrap slice: service YAML no longer repeats ClickHouse defaults or ENV
  aliases. Existing property classes remain the only defaults/validation owners.
  Spring's `@Name("clickhouse")` fixes constructor binding for nested metrics;
  Controller metrics settings are extracted into their own implementation unit.
  Full service binding tests cover the original ENV names, all settings, source
  precedence, omitted defaults and rejected invalid metrics configuration.
- Metrics and transaction event construction, their separate clocks, buffering,
  validation/clamping, requeue and shutdown behavior remain with their existing
  policy owners. Tests verify full buffers, partial flush failures, invalid URI
  preparation before draining, failure diagnostics and exact requests.
- The existing import gate now forbids JDK HTTP clients in postprocessor production
  code. Repository searches found no other Java production JSONEachRow request or
  ClickHouse ENV field builder outside `sink-clickhouse`. Historical storage tools
  and deployment config are not runtime consumers of this Java API.

Verification (2026-09-23): affected reactor
`AUTH_OPENSSL_TEST_EXECUTABLE=/usr/bin/openssl ./mvnw -B -ntp -pl orchestrator-service,swarm-controller-service,postprocessor-service -am test`
passed: **1853 tests, 1849 passed, 4 skipped, no failures/errors**
(`/tmp/ph-f05-bootstrap-reactor-final.log`). Skips are the existing externally
configured Redis fixtures. Transport/sink behavior, both launch consumers, full
service binding and RepositoryImportBoundaryTest ran. The 17 bootstrap tests also
passed separately (`/tmp/ph-f05-bootstrap-binding.log`). The earlier YAML-removal
failure was traced to constructor `clickHouse` being bound as `click-house`;
`@Name("clickhouse")` now preserves the canonical property path without aliases.
Deployed acceptance: rebuilt the local stack from this worktree through
`COMPOSE_PROJECT_NAME=pockethive-redis ./build-hive.sh --quick` (existing data kept;
no swarms were active). `./run-acceptance-tests.sh
acceptance-tests/targets/local-tx-outcome-artemis.properties tx-outcome` passed
DA-3 on Artemis through the public ingress/Grafana: no rows with sink NONE,
then matching trace/call IDs, status, success and duration after enabling
CLICKHOUSE_V2 by config-update. The normal stop/remove lifecycle completed and
public list-swarms returned empty. Evidence:
`acceptance-tests/runs/tx-outcome-a51fa251-47dd-4d43-a4c0-c462cac25bc3/`;
logs `/tmp/ph-f05-local-deploy.log` and `/tmp/ph-f05-da3.log`.
The acceptance invocation ran 388 tests including dependencies/framework tests
and one deployed DA-3, all passing. No remote Swarm or Rabbit deployment repeated.
Final separate whole-F05 review: no actionable findings. 115 focused tests passed,
zero failures/errors/skips (`/tmp/ph-f05-complete-review.log`). Review traced both
sink paths, startup/launch ENV precedence and repository-wide alternative owners;
checked the existing DA-3 artifacts without repeating deployment. Ready for commit.

Gate: no consumer builds ClickHouse URLs/queries/credentials; exact requests and
queue-full/flush-error behavior covered; DA-3 still proves persisted outcomes.
Vendor-import restrictions alone cannot detect duplicated JDK HTTP implementations.

### F02 / F06 / F07 / F09 — selected follow-up slices

Current work: `codex/worker-inputs`, based on F04/F05 commit `2d763660` (PR #522).
User selected smaller follow-ups before F06: F02, then bounded F07/F09 changes;
F08 state semantics require separate decisions. No F06 auth code was changed.

**F02 selected extraction implemented and reviewed.**
`common/work-local` now owns CSV loading/formatting/cursor (`CsvDatasetCursor`),
scheduler rate quota (`RateSchedulePolicy`) and runtime rate/max/reset projection,
finite-run count and derived diagnostics (`SchedulerRunState`). Canonical settings
and field parsers retain their existing owners. The old SDK implementations are
removed; SDK keeps worker lifecycle, scheduling clock, snapshot projection, seed
metadata and dispatch. CSV intake pacing remains in the SDK coordinator; its
interval-scaled arithmetic is distinct from the scheduler's existing per-tick quota.
No timing reinterpretation, ACK or wire change is included.

Repository tracing found no implementations or callers of WorkInput.update(snapshot).
That unused method was removed; WorkInput retains only lifecycle methods. Factory
WorkerDefinition arguments stay inside SDK composition and are not needed by the
extracted owners. No unused neutral interface or compatibility copy was added.

The CSV review P3 is corrected: documentation states the caller's serialization
requirement, without claiming stop waits for an in-flight tick. Runtime behavior
was not changed for that finding.

Verification: clean affected reactor through worker-sdk and trigger-service,
83 selected tests, zero failures/errors/skips (`/tmp/ph-f02-local-inputs-clean.log`).
Coverage includes CSV format/charset/EOF/rotation/reload, rejected settings,
fractional quota, finite/unlimited/long limits, reset, disabled/re-enabled state,
seed/dispatch/result failures, trigger behavior, other existing inputs and the
repository import gate. Separate previous CSV review ran 30 tests successfully
(`/tmp/ph-f02-csv-review.log`). Separate complete F02 review found no actionable
issues; its fresh 83 tests passed (`/tmp/ph-f02-scheduler-review.log`). No full
reactor or deployed E2E repeated.

- **F02 local input/SDK:** CSV and scheduler execution behind minimal input
  contracts; preserve cursor/EOF/rotation/rate/reset semantics. SDK keeps execution,
  admission and accepted-state ownership, with composition separated from mechanics.
- **F06 worker auth/rendering:** revalidate current signed/ordinary OAuth flows,
  authoring versus effective-value validation and token coordination after PR #517.
  Extract only confirmed ownership leaks. Keep product login separate from SUT auth;
  keep rendering in templating and SequenceAccess in templating-api.
- **F07 service contracts/UI:** identify producer-owned contracts and independent
  policy copies. Share/generate contracts where appropriate; UI consumes owner
  projections. Do not invent a universal DTO module or replace intentional boundary
  validation merely because similar field names occur in two services.
- **F09 service-local boundaries:** re-trace Scenario Manager, processor HTTP,
  MCP and TCP mock entrypoints. Preserve useful existing local ports, including
  DbStatementExecutor and ClearingExportSink. HTTP-library reuse alone is not
  evidence that unrelated functional clients should share an owner.

### F07 — first producer-contract slice

**Implemented and reviewed; committed in `02b97665`.** Base: F02 commit `b0f92340`.
Re-tracing confirmed exact copies of RuntimeRequest,
ScenarioRuntimeResponse and VariablesResolveResponse in Scenario Manager and its
Orchestrator client. Producer-owned records now live in `common/scenario-api`,
consumed by both boundaries; all local wire copies are removed. Endpoint paths,
JSON fields, null handling, required runtimeDir checks and auth/error behavior
are preserved.

Template metadata and ScenarioPlan are deliberate partial views, not evidence for
merging the full authoring model into Orchestrator. The redundant intermediate
template response record is removed; the existing application projection is decoded directly;
unknown-field tolerance remains local to that projection. ResolvedVariables remains
a named local normalized view of the shared wire response. UI grant/network-mode
policies and broader scenario model sharing remain outside this first slice.

Verification: affected reactor through Scenario Manager and Orchestrator compiled
cleanly after the moves. Final 112 selected tests passed, zero failures/errors/skips
(`/tmp/ph-f07-scenario-contract-final.log`; clean build:
`/tmp/ph-f07-scenario-contract-clean.log`). Tests consume serialized producer-contract
values through the actual HTTP client and cover request fields, nested variables,
warnings, request context, existing empty-collection projection, rejected null
metadata/missing runtime directory, HTTP errors and auth retry. Existing producer
controller/variables/materializer suites and the repository import gate also pass.
Separate F07 review found no actionable findings and reran 112 tests successfully
(`/tmp/ph-f07-review.log`). No deployed acceptance or full repository reactor was repeated.

### F09 — processor pacing slice

**Implemented and reviewed; committed in `476f8dc5`.** Base: F07 commit `02b97665`.
Re-tracing confirmed duplicate ownership:
HttpProtocolHandler, TcpProtocolHandler and Iso8583ProtocolHandler each implement
applyExecutionMode against the same per-worker AtomicLong. All three callers now
use one ProcessorPacer owning both state and waiting; the old methods and externally
writable counter are removed. First-call delay, shared slots, rate/mode updates,
interruption and reported pacing duration are preserved. Configuration stays
with ProcessorWorkerConfig. See RESP-PROCESSOR-PACING for exact semantics.

This is a local processor responsibility, not a universal rate limiter. Moderator
shaping and scheduler quotas differ and remain separate. HTTP client ownership,
TCP transport mechanics and the selected TCP mock boundaries were subsequently
completed below. Scenario Manager/MCP and the explicitly deferred transport lifecycle
repairs remain open. No TLS/security or ACK behavior change.

All constructor call sites were traced: the worker supplies the same non-null
pacer to all handlers, and the existing HTTP test supplies its own pacer. Old
AtomicLong constructors are removed rather than retained as compatibility paths;
handlers cannot create private schedules when a dependency is absent. Production
uses System.nanoTime/Thread.sleep; a package-private clock/wait seam permits
behavior tests without real delays. No new dependency or import exemption is needed.

Verification: **67 tests passed, zero failures/errors/skips**, including all 64
processor tests and 3 repository import tests (`/tmp/ph-f09-processor-pacing.log`).
The 11 pacing cases cover initial/queued/idle reservations, mode/rate updates,
fractional waits and reported durations, interruption, concurrent reservations and
per-worker isolation. Existing HTTP/TCP/ISO8583 result/error and logging/security
tests passed. Separate pacing review reran 67 tests successfully
(`/tmp/ph-f09-pacing-review.log`) with no actionable findings. No full repository
reactor or deployed E2E was repeated.

### F09 — processor HTTP client slice

**Implemented and reviewed; committed in `f6e55c31`.** Base: pacing commit `476f8dc5`.
ApacheProcessorHttpClient now owns HTTP pool/TLS client construction, selection and
status capacity behind ProcessorHttpClient. ProcessorConfiguration injects that API;
WorkerImpl no longer imports Apache clients or constructs them, and the handler no
longer selects a client. Old raw-client constructors and helpers are removed.
Request/response callbacks remain on the same Apache execution path. Architecture
owner: RESP-PROCESSOR-HTTP-CLIENT.

No generic cross-service HTTP framework, configuration/default changes, new shutdown
hooks or TLS/ACK changes. HTTP Sequence ownership and TCP transport lifetime remain
separate. TLS acceptance/rejection is tested through the real owner, reflective
proxy checks are replaced by actual proxy requests, and processor response/error
coverage is retained through the port. The API is intentionally local and Apache
HTTP-specific; it accepts a response decoder but does not expose raw clients.

Verification: **88 tests passed, zero failures/errors/skips**
(`/tmp/ph-f09-processor-http-final.log`). This includes 23 owner cases for request/
response effects, decoder errors, GLOBAL/PER_THREAD/NONE and keepAlive precedence,
thread isolation, configured capacity, verified/unverified TLS and actual system
proxy routing. Existing processor/pacing/transport/security suites plus the 3 import
checks pass. Worker status consumes the owner's capacity projection. No new module,
artifact dependency or import exemption was introduced; this local package boundary
is documented and reviewed in source, not enforced by a new scanner.
Separate HTTP review reran 88 tests successfully (`/tmp/ph-f09-http-review.log`)
with no actionable findings. No full repository reactor or deployed E2E was repeated.
This does not close F09.

### F09 — TCP/ISO8583 runtime slice

**Implemented and reviewed; committed in `a29bae54`.** Base: HTTP commit `f6e55c31`.
TcpTransportRuntime owns configuration/reload and selection, TcpPerThreadTransports
owns each generation's lazy per-thread resources, and TcpTransportLease owns scoped
release. Both handlers delegate through this API with separate runtime instances.
Retry/result scopes and existing update/close order are preserved. The unused
TcpTransportPool and string/global-pool factory helpers are removed after repository
caller search; the remaining factory is package-private. See RESP-PROCESSOR-TCP-RUNTIME.

Existing non-atomic replacement, failed-construction state and lack of shutdown
cleanup are deliberately outside this extraction; no lifecycle repair is implied.
Socket/NIO/Netty IO implementations, protocol framing and auth remain unchanged.
The active factory's existing config-based selection/fallback behavior is preserved,
not expanded or reinterpreted in this extraction.

Verification: **96 tests passed, zero failures/errors/skips**
(`/tmp/ph-f09-tcp-runtime.log`): 93 processor tests plus 3 repository import checks.
Eight owner tests exercise request/config propagation, GLOBAL/NONE/PER_THREAD reuse,
per-thread and protocol isolation, replacement release, close failures, missing
configuration and caller-controlled retry through one lease. Existing handler and
real HTTP/TCP/ISO8583 transport tests remain green. The existing ProcessorTest fake
transport injection fixture was adapted to the new owner; owner tests themselves
exercise the API without inspecting private state. No new module/dependency/import
exception or scanner was added. No full repository reactor or deployed E2E repeated.
This completes the selected pool-mechanics transfer, not all F09 or lifetime repair.

### F08 and separate correctness work

Readiness, freshness, reset, registration, orphan cleanup and lifecycle outcomes
are domain facts. Define their distinct meanings, writers and postconditions before
changing them. Do not equate thresholds or merge state machines by convenience.
[Orchestrator correctness](orchestrator-correctness.md) remains a separate track;
its historical O1/O2 review status must be checked against current code/evidence
before selecting further fixes. This refresh does not accept or reopen that work.

F08 implementation (2026-09-25, after `7f9a0271`): `SwarmReadinessTracker`
owns heartbeat time, observed enablement and the single freshness rule. Its immutable
`WorkerObservation` projection passes through the existing lifecycle API to the
worker-list aggregator. The aggregator retains only reported presentation data;
its second timestamp, enabled copy and freshness threshold are removed. Readiness,
healthy/running metrics and worker-list stale use the same owner's freshness rule.
The handler serializes worker-list reads against applying accepted status events.
A reset observation owner no longer exposes orphaned presentation entries.

The contract is [RESP-SWARM-OBSERVATION](../architecture/runtime-responsibilities.md#resp-swarm-observation).
Ready still means the expected ready instances have fresh heartbeats; healthy
means fresh heartbeat; running metrics mean healthy plus enabled. Full-status
revisions remain the separate evidence required for post-command convergence.
The journal's 15-second startup warning grace is separately named, not treated as
heartbeat freshness. Controller wire Health and journal degraded/recovered labels
retain their current, distinct projection semantics; they consume canonical counts.
This extraction alone did not change START/STOP/config acceptance, timeout policy,
reset/recovery or envelopes. The separately approved command-admission change below
supersedes the earlier admission behavior.

Verification: Controller suite discovered 226 tests, 224 passed and two existing
`@RabbitAvailable` integration cases skipped because Rabbit was unavailable;
`/tmp/ph-f08-controller.log`. One additional handler flow test then passed with the
three existing handler tests (`/tmp/ph-f08-handler.log`): 225 distinct tests executed.
Coverage includes exact 15-second boundary, expiry/recovery, missing heartbeat,
reset projection, enabled delta versus full-status command evidence, and actual
handler full/delta flow retaining config. `git diff --check` passes. No deployment
or load test was performed in that run; the subsequent review and correction are recorded below.

Plan/style/conciseness/security/library/readability implementation checks: reuse
one existing owner and lifecycle port, one immutable projection type, no dependencies,
no security/wire changes. Repository searches for heartbeat timestamps, stale TTLs,
worker aggregators and their callers confirm the worker-list copy is removed.
Orchestrator controller-status receive time observes a different hop and stays separate.
Its reset/recovery track and Processor transport lifetime repairs remain deferred.

F08 review correction (2026-09-26): commit the reported enabled/full-status
observation before appending the worker-error journal entry. Journal failure still
propagates, but can no longer expose a fresh heartbeat with the previous enabled
value. Four regression cases cover full/delta and both enablement transitions;
full-status revision semantics are preserved. Focused owner, projection, handler,
listener, error-journal and lifecycle-command tests: 46 passed, none skipped
(`/tmp/ph-f08-journal-fix-tests.log`). Full deployment was not repeated.


## Required completion evidence for every PR

1. Name the responsibility, existing owners/callers and supported API before edits.
2. Trace a concrete entrypoint through parse/resolve, effect and readback. Consumers
   receive resolved values; the owner alone constructs configuration/paths/names.
3. Cut over every caller in the selected path and delete the replaced code. Make
   bypasses inaccessible where possible; no raw clients or arbitrary client callbacks.
4. Tighten existing dependency/import restrictions as ownership transfers. No new
   source-scanning framework; checks complement review rather than proving semantics.
5. Verify non-default config, rejected-candidate state preservation and real effects
   through the appropriate owner/integration/official-ingress tests. Preserve ACK.
6. Review actual call paths and search the whole repository for competing owners.
   Record all six required review passes and explicit unverified/deferred scope.

Original plan review (PR #519): that update removed stale prerequisites, preserved existing owners,
added the omitted Redis capture consumer and split implementation from behavioral
redesign. That plan-only update changed no production code, public contract, dependencies or deployment.
F01–F05 and the selected F07/F09 slices were subsequently authorized and implemented.
Only the current backlog above describes outstanding work.

### F09 — TCP mock notification slice

**Implemented and reviewed; committed in `a5daf11c`.** Base: `a29bae54`.
Transfer the active global notification feed from NotificationController into
NotificationService; replace its uncalled per-user implementation and model.
Repository-wide Java search found no consumers of that old service/model.
Preserve the existing HTTP contract, retention, ignored persistent flag and
ID/read behavior; expose detached response projections. See
RESP-TCP-MOCK-NOTIFICATIONS. No workspace, mapping, mock protocol or security change.
Verification: all 10 TCP mock tests passed, including 6 feed behavior tests and
2 controller/JSON tests using the real service (`/tmp/ph-f09-notifications.log`).
Coverage: creation/order/time, retention, missing/repeated reads, clear without ID
reset, ignored persistent input, null fields, detached projections and unchanged
response fields/statuses. Controller tests call Java methods and serialize values;
they do not claim deployed HTTP/security acceptance. No security config changed.
The existing repository import gate also passed (3 tests,
`/tmp/ph-f09-notifications-imports.log`). No deployment or full reactor repeated.
Later TCP sections close the agreed mock scope; broader F09 remains open.

### F09 — TCP mock workspace slice

**Implemented and reviewed; committed in `08efc686`.** Base: `a5daf11c`.
Extract the active global catalogue from WorkspaceController into WorkspaceService;
replace the unused user/member-aware implementation and model. Preserve existing
upsert/body-ID mismatch, default handling, generated IDs and wire shape. Repository
Java reference search finds no consumers of the unused implementation. See
RESP-TCP-MOCK-WORKSPACES. No permissions, persistence or concurrency repair.
Verification: 21 tests passed with zero failures/errors/skips
(`/tmp/ph-f09-workspaces.log`): 8 new workspace tests, 10 existing TCP mock tests
and 3 existing import checks. Tests exercise default creation/protection, generated
IDs/owner, missing deletion, upsert/path-versus-body-ID behaviour, null fields and
input/output snapshot isolation. Controller tests use direct calls/serialization,
not deployed HTTP. No full reactor/deployment was repeated.
Remaining F07-TCP (Tim): static/workspace.js repeats default data on load failure
and blocks default deletion; mutation-response handling also needs attention if the
workspace feature is retained. The attempted F07 browser changes were discarded.
Consider removal of this feature, which currently does not partition mappings or
traffic. No end-to-end SSOT completion is claimed. Broader F09 remains open.

Workspace review follow-up: the user approved fixing the inherited PUT JSON decode
failure. Workspace now supports Jackson field binding via a no-argument constructor.
Two new tests begin with JSON (complete and omitted fields), update through the real
controller/service and verify stored values, response serialization and isolation.
Both failed with InvalidDefinitionException before the fix
(`/tmp/ph-workspace-json-red.log`); all 23 selected tests pass after it
(`/tmp/ph-workspace-json-green.log`). No new field validation, HTTP fields or
catalogue policy. These remain mapper/controller tests, not deployed HTTP checks.

### F09 — TCP mock mapping and execution closure

**Implemented, reviewed and integrated into PR #520 through #523.** Extraction
commits: `7e75105e`, `d45bcc13`, `ebf9b9a5`; persistence/housekeeping: `474912ef`.
The integration commit is `60c039a5`.

Current ownership:
- MappingAuthoringParser decodes JSON/YAML; MappingAuthoringService coordinates
  sequential authoring through the registry. StubMappingConverter owns admin/file
  conversion and reverse export. HTTP controllers delegate to application owners.
- MessageTypeRegistry is the sole catalogue mutation owner. MappingPersistence
  delegates snapshot IO to MappingFileStore; StartupMappingSource delegates fresh
  initialization to FileBasedMappingLoader. There is no old per-id write path.
- MappingExecutor serves text, binary and manual execution. TextRequestProcessor,
  admin services and diagnostic/web projections own their respective boundaries.
- DocumentationReader and WireMockImporter close their streams. Unused
  AdvancedTemplateEngine, AdvancedMatcher and PaymentLogicEngine were removed.

See RESP-TCP-MOCK-MAPPING-FILES, RESP-TCP-MOCK-MAPPING-AUTHORING,
RESP-TCP-MOCK-STUB-CONVERSION and RESP-TCP-MOCK-EXECUTION for canonical contracts.
Earlier per-file writes, suppressed storage errors and different startup/write roots
were intermediate behavior, superseded by the approved durability change below.
They are neither current implementation nor outstanding extraction tasks.

### TCP mock runtime mapping persistence — closed

The runtime retains its complete catalogue at `/app/data/mapping-catalogue.json`.
Fresh instances initialize from defaults and startup files; an existing snapshot,
including an empty array, is authoritative. Authoring, admin and imports persist
through one serialized registry mutation before publishing accepted in-memory state.
Save failures retain accepted configuration; corrupt saved state fails startup.
Sequential bulk imports retain earlier successful entries after a later failure.

Catalogue order resolves equal-priority ties and survives edits and restart.
Replacing an id keeps its position; new ids append. Existing Compose/HiveForge
mounts retain `/app/data`; one instance owns each data root. Diagnostic counters
are not guaranteed durable after each request. No legacy per-file migration or
promotion into PocketHive scenarios is provided.

Mapping clear is durable and does not reset the separate mock scenario state or
request journals. Scenario-state reset/persistence/null semantics and public nested
DTO cleanup remain distinct deferred work; they do not reopen mapping persistence.

Final evidence:
- All 74 TCP tests passed in separate review, including runtime import/edit/delete/
  empty-state restoration, storage failures and ordered response selection.
- The ordering regression failed before its fix; eight independent JVM reloads
  selected the same response after it.
- Three repository import-boundary tests passed. The module's Mockito subclass
  maker/reflection accessor also permits the full TCP suite without dynamic attach.
- After integration into #520: 74 TCP tests and 160 focused input/processor/scenario/
  import-boundary tests passed. No fresh full-reactor or deployed acceptance claim.

Evidence logs: `/tmp/ph-tcp-order-review.log`, `/tmp/pr520-merge-tcp.log`,
`/tmp/pr520-merge-tests.log`. Intermediate test counts and superseded implementation
steps remain in Git history; they are not additional pending work or acceptance gates.

### F07 ownership split — 2026-09-25

User decision: TCP mock changes belong to Tim; the remaining main UI work belongs
to Zbigniew. F07-TCP and F07-UI are independent delivery scopes. The uncommitted
workspace browser implementation, tests and CI changes on codex/f07-ui-projections
were reverted; only the plan update remained there. No workspace feature removal has
been implemented. Evaluate that removal in Tim's TCP scope instead of assuming
that the current UI-only workspace needs to be developed further.

F07-UI retains the grant/scope interpretation audit against PocketHiveGrantChecks
and network response projection audit against NetworkMode/NetworkBinding. It does
not include TCP mock workspace policies. Existing broader ownership assignments,
including processor transport lifecycle assigned to Zbigniew, remain unchanged.

### F07-UI — network response projection

Active worktree: `/home/sepa/PocketHive-ph-ui`, branch `codex/f07-ph-ui`, based on
PR #520 `c39bbdc4`. TCP changes are excluded. User approved rejecting unknown
network modes rather than displaying DIRECT. Extract the binding response decoder
from networkProxy.ts; both Hive and Proxy consume it. NetworkBinding/NetworkMode
in common/swarm-model remain the producers' contract. Decode failures reach the
existing error UI; a failed or missing binding must not be presented as DIRECT.
Verify valid modes are preserved and invalid/missing modes fail explicitly.
Grant projection remains the next scope: first establish whether existing APIs
expose decisions rather than introduce another client-side permission policy.

Network slice verification: all 50 UI tests pass (11 files), including 19 new
decoder/badge cases; production TypeScript/Vite build passes. Build retains its
large-chunk advisory. No deployment/browser smoke test or Java changes.
Grant trace: AuthController returns raw user grants; SwarmController returns
SwarmStateView and performs private read/run/manage scope checks. No permission
projection was found on these consumer paths. Proposed first additive endpoint
GET /api/access/swarms is documented in ORCHESTRATOR-REST.md; approval and
implementation are recorded below. Broader grant projections remain open.

### F07-UI — swarm permission projection (implemented and reviewed)

User explicitly approved GET /api/access/swarms. SwarmAccessService delegates to
existing OrchestratorAuthorization; SwarmTemplateScopeResolver is the single owner
of extracted template-scope enrichment and descriptor lookup. Lifecycle controller
and visible-swarm projection use that access owner. Auth-disabled behavior remains
unchanged; missing metadata does not create permissions from unrelated grants.
Projection contains sorted visible swarm IDs and canRun/canManage, with no-store.

HivePage now consumes the projection through useSwarmCatalogue/swarmAccessApi.
Its scenario-catalogue permission lookup and fallback to any ALL grant are removed.
Missing entries, loading and request/decoding failures disable actions; superseded
loads cannot restore old permissions and projections are tied to the current caller.
Creation/global navigation, Scenario Manager bundle/folder controls and auth-admin
grant interpretations remain open F07-UI work. TCP belongs to Tim and is untouched.

Verification: focused Java access/resolver/controller/import tests and full UI tests
plus production build; logs /tmp/ph-access-java.log, /tmp/ph-access-ui.log and
/tmp/ph-access-ui-build.log. No deployed/browser acceptance.

F07 review fixes: projection moved to `/api/access/swarms` (public ingress
`/orchestrator/api/access/swarms`) so `/api/swarms/access` still reads the swarm
whose ID is access. The user authorized fixing both review findings. Background
polls join an in-flight catalogue request; explicit refresh may supersede it.
Cleanup invalidates outstanding work, and stale completion cannot release a newer
request slot. Four hook regressions exercise controlled async responses with React
hook primitives stubbed; MVC regression registers both controller mappings and
checks that the access-named swarm remains readable. These are not browser/E2E tests.
Verification: 63 UI tests, production UI build, 22 focused Java tests (including
three import-boundary tests) pass. Logs: /tmp/ph-f07-fixes-ui.log,
/tmp/ph-f07-fixes-build.log, /tmp/ph-f07-fixes-java.log.
Separate review accepted both fixes with no new findings; reran 13 focused UI
and 19 Java tests successfully (/tmp/ph-f07-fixes-review-ui.log and
/tmp/ph-f07-fixes-review-java.log). Remaining F07-UI work is unchanged above.

### F07-UI — scenario catalogue permissions (reviewed)

Base efa01e35. Confirmed `/api/templates` already filters run permissions and
`/scenarios/bundles/workspaces` already filters read permissions. Remove redundant
UI filtering rather than add a second run/read projection. Bundle edit permission
uses GET /api/access/bundles, explicitly approved by the user and documented in
SCENARIO_MANAGER_BUNDLE_REST.md. Global navigation,
reload/upload controls and auth admin remain outside this slice.

Implemented: shared ScenarioCatalogueAccess preserves existing catalogue visibility;
BundleAccessProjection delegates edit decisions to existing authorization. UI consumes
these decisions and uses one existing template parser. Removed UI bundle/folder scope
matching and its unused AuthContext wrappers. Refresh retains the selected bundle
while access is disabled during loading/errors; caller change and modal close invalidate
observations and pending responses. Backend mutations still recheck permissions.

Evidence: 112 focused Java tests and all 76 UI tests pass; production UI build passes.
Logs: /tmp/ph-scenario-java.log, /tmp/ph-scenario-ui-tests.log,
/tmp/ph-scenario-ui-build.log. Hook tests observe async state writes with stubbed React
primitives; no browser/deployed acceptance. Repository searches found the removed
scope matchers have no remaining consumers in active UI; the archived legacy UI retains
its separate template parser and is outside this change. Global navigation, reload/upload
and auth-admin rules remain open; TCP is untouched.

Separate review: no new blocking findings in the approved slice. Reran 115 Java
tests (including three import-boundary tests) and 14 focused UI tests successfully.
Logs: /tmp/ph-scenario-review-java.log and /tmp/ph-scenario-review-ui.log.
Known S9 duplicate-scenario-ID catalogue behavior remains deferred.

### F07-UI — navigation and toolbar decisions (implemented; review fixes completed)

Base 60467c4a. User authorized continuing this slice. Source audit confirmed:
Auth /me exposes identity/grants but no decisions; UI repeats PH-any VIEW/RUN/ALL,
PH-any RUN/ALL and Auth admin interpretation. Scenario toolbar uses PH-any ALL even
though reload requires deployment ALL and upload requires management of the actual
upload folder. Proposed concrete endpoints are documented in AUTH_SERVICE_API_SPEC.md
and SCENARIO_MANAGER_BUNDLE_REST.md; the user explicitly approved both endpoints.

Implementation sequence: centralize unchanged Java permission sets already repeated
by Orchestrator, Scenario Manager and Network Proxy Manager, then reuse them for the
Auth projection; reuse AuthGrantChecks for admin. Extract scenario operation permission
checks shared by command boundaries and projection, deriving upload target from its
existing owner. Add focused controllers/DTOs. UI uses caller-bound observations with
explicit loading/errors, preserving login success if a projection fails and allowing
retry. Remove obsolete browser grant predicates and AuthContext helpers. No TCP changes.
Verification: exact old/new decision equivalence for scoped/global grants, auth-only
admin, denied/error paths, caller changes, controls for reload/upload and admin; relevant
Java/UI tests, production UI build and existing repository import check.

Implemented on 60467c4a: AuthAccessController/AuthAccessProjection provide global
navigation/admin observations; ScenarioOperationAccess is shared by reload/upload
commands and ScenarioOperationsAccessController. PocketHivePermissionSets replaces
three identical service-local definitions. Upload authorization gets its folder from
ScenarioBundleOrganizationService.uploadFolder(), which also supplies publication.
Existing policies, null-caller handling and auth/session contracts are unchanged.

UI AuthContext consumes useAccessObservation instead of calculating grants. The same
observation hook serves scenario toolbar permissions. Caller/token changes immediately
hide old decisions; request generations reject late completion. Loading and failure
have explicit notices/retry; projection failure does not invalidate authentication.
Removed remaining browser grant predicates and unused hasGrant/hasPermission wrappers.
UsersPage still displays and edits grant data; it does not decide authorization from it.
This closes the previously open navigation/Create/toolbar/auth-admin implementation
slice, pending separate review. TCP and the known S9 catalogue identity defect remain
outside this work.

Verification: 161 focused Java tests across the two runs (including three repository
import checks), all 93 UI tests and production UI build pass. Logs:
/tmp/ph-global-java.log, /tmp/ph-global-filters.log, /tmp/ph-global-ui-tests.log,
/tmp/ph-global-ui-build.log. UI observation tests use controlled React hook primitives,
not browser rendering; no deployed/browser acceptance was performed. No new dependencies,
no commit or push in this implementation turn.

F07 global-access review fix: extracted useAdminUsersLoader from UsersPage. Permission
loading/error for the same caller ID and token no longer reloads the user list or
resets the selected account/editing draft after saving one's own profile/grants.
Explicit Reload keeps its selected-account argument; session changes and confirmed
denial invalidate the loaded list. Cancelled requests cannot publish form updates.
Initial load errors expose Retry, and a changed caller does not see the previous
caller's editor while the new list is loading. Permission gates remain unchanged.
Verification: 99 UI tests and production build pass; six loader regressions use
controlled hook primitives (not browser rendering). Logs:
/tmp/ph-user-refresh-tests.log and /tmp/ph-user-refresh-build.log. Backend unchanged
since the previous passing tests. Awaiting separate review of the fix.

### F09-SM — bundle download owner (reviewed)

User started F09 Scenario Manager after F07 commit 9ea4ab6f. Revalidated historical
S6: ScenarioController.downloadBundle and ScenarioBundleWorkspaceService.download
still independently walk files and construct ZIPs. First bounded slice extracts
one ScenarioBundleZipExporter and ScenarioBundleDownloadService, removes both old
implementations and keeps HTTP mapping with the controller. Current contract and
synchronization remain unchanged; record RESP-SCENARIO-BUNDLE-DOWNLOAD owns the scope.
This is the already-planned behavior-preserving S6 transfer, not a new wire contract.
S1/S2/S3/S9 correctness, S4 worker auth, S5 metadata and S7 layout are not bundled in.
Verification: archive bytes/entries, nested/binary/empty-directory handling, both
filename conventions, missing target errors, existing controller and import tests.

Implemented: both HTTP download paths delegate to ScenarioBundleDownloadService and
one ScenarioBundleZipExporter; removed filesystem/ZIP work from ScenarioController
and the workspace export method. One controller mapper builds attachment headers.
Preserved by-ID unlocked vs by-key synchronized behavior, existing error mapping,
filename conventions and binary content. No atomic snapshot guarantee is added.
Verification: 109 tests pass including 97 existing controller tests, six new ZIP/target
behavior tests, workspace/logging tests and three repository import checks. Log:
/tmp/ph-f09-sm-download.log. Whole-repository production-Java search finds ZIP output
construction only in ScenarioBundleZipExporter. No deployed E2E run; no commit/push.
Next bounded F09-SM candidates: revalidate S7 bundle layout consumers, then S5 authoring
metadata ownership; correctness fixes and F06 remain distinct work.

Separate S6 review: no actionable findings; reran 109 tests successfully
(/tmp/ph-f09-sm-review.log). Existing concurrent-edit/snapshot limitations remain
unchanged; no deployed E2E qualification is claimed.

### F09-SM / F07-UI — simplify functional APIs (reviewed)

User approved correcting excessive fragmentation before further extraction. Replace
separate publication/download entrypoints with ScenarioBundleService; keep substantive
editing and ZIP helpers internal. Consolidate catalogue/operation access and bundle
projection into ScenarioAccessService, with one HTTP controller for both approved
Scenario Manager access endpoints. All approved access endpoints already have UI
consumers; finish by exercising the consolidated composition and existing UI tests.
No new routes, payloads or permission policy. Do not expand into S5/S7/S9 correctness
or TCP. Acceptance: existing upload/download/edit/validation and access behavior,
no old service consumers, unchanged HTTP contracts and passing boundary tests.

Implemented: ScenarioBundleService replaces publication/download services and is the
only application consumer of the four package-private authoring helpers. Its existing
publication workflow and download selection remain in the service; ZIP encoding stays
in one exporter. ScenarioController now has seven collaborators instead of thirteen.
ScenarioAccessService replaces ScenarioCatalogueAccess, ScenarioOperationAccess and
BundleAccessProjection; ScenarioAccessController replaces two single-route controllers.
Earlier entries above describe the implementation history; these are the current owners.

Verification: 156 Scenario Manager tests pass, including seven full Spring HTTP/auth
access cases; 99 UI tests and production build pass. The access tests request JSON
explicitly, like UI clients (Scenario Manager also supports YAML negotiation). Existing
ZIP contents/names/errors, editing, upload, validation and authorization regressions
remain green. No deployment/browser E2E, commit or push in this slice. Logs:
/tmp/ph-consolidated-java.log, /tmp/ph-consolidated-ui-tests.log,
/tmp/ph-consolidated-ui-build.log. Separate review completed below.

RepositoryImportBoundaryTest also passes (3 tests), run explicitly in control-plane-core;
log /tmp/ph-consolidated-boundaries.log. Whole-repository Java search finds no former
publication/download/access service consumers; only ScenarioBundleService consumes
the internal authoring helpers. git diff --check passes.

Separate review of the consolidation: no actionable findings. Re-ran 156 Scenario
Manager tests plus 3 import-boundary tests; log /tmp/ph-bundle-access-review.log.
Checked endpoint-to-owner call paths, former-owner removal, preserved locks/error
mapping and canonical permission decisions. No deployed/browser E2E claimed.

### F09-SM — S7 layout and S5 authoring metadata (implemented; reviewed in integrated delivery)

Base 33626518. User authorized S7 followed by S5. Reuse ScenarioBundleLayout for
resolved bundle directories and the default worker mount; wire existing callers
without changing their containment or auth lookup policy. S5 moves authoring
assembly out of REST and derives metadata from contract owners. No per-field
services. The user subsequently approved the required protocolVersion response correction.
Acceptance: preserved editing, template path validation, worker auth lookup and
Orchestrator mount behavior; authoring API projects parser/type metadata; existing
wire shape, catalogue filter and fingerprint behavior stay unchanged apart from any
explicitly approved field-list correction. S1/S2/S3/S9/S10 remain out of scope.

S7 implemented: ScenarioBundleLayout owns SUT/template/schema paths and CONTAINER_ROOT.
Content/SUT services and validator consume resolved directory paths; Orchestrator
uses the mount constant; AuthProfileLoader consumes the same default mount and canonical
auth-profile filename. Its existing property/env override and ancestor-search order
are preserved. No new filesystem policy or fallback is introduced. Repository-wide
production Java search finds /app/scenario only in the layout owner and no remaining
resolve("sut"|"templates"|"schemas") literals.

S5 implemented: ScenarioAuthoringService owns assembly and the existing
run-filtered catalogue projection; REST delegates. RequestTemplateParser owns ordered
required-field lists used by both parsing and authoring. VariablesDocument owns the
version used by validation and authoring; enum values are projected from their types.
The user approved protocolVersion in the required-field response. Scenario owns
field metadata; Jackson introspection projects existing NotBlank fields and required
template presence into the response, with no manually maintained required-field list.
Scenario field-name constants bind template/trafficPolicy/plan annotations and their
metadata. Validation execution remains unchanged. No changes to S9 filter or S10 fingerprint.

Verification: S7 run passed 150 tests across SM, worker auth, Orchestrator and the
import boundary (/tmp/ph-s7-layout-tests.log). Subsequent S5 run passed 126 tests
including parser/authoring behavior and HTTP regressions (/tmp/ph-s5-metadata-tests.log);
these sets overlap. git diff --check passes. No deployment, review, commit or push.

S5 completion verification: 127 tests passed in the latest run, including the public
required-field response, missing/malformed/incompatible protocol validation, bundle
publication/runtime regressions, HTTP parser metadata behavior and 3 import-boundary
checks. Log: /tmp/ph-s5-complete-tests.log. Previous S7 worker/Orchestrator evidence
remains applicable; the completion changes only descriptor metadata and projection.
No deployed E2E, commit or push. Separate review pending.

### F09-SM — S9/S10 repair (implemented; separate review passed)

User authorized fixing bundle catalogue identity and incomplete authoring fingerprint.
Read/run catalogue filtering now uses existing findBundleAccess(bundleKey), including
malformed bundles without a scenario ID. The full authoring response (excluding its
fingerprint) drives the digest through deterministic JSON with sorted object keys.
Regression tests cover duplicate IDs with distinct folder grants, missing bundle
access, malformed bundles, metadata changes and stable repeated responses/map order.
No new endpoints or grant matching rules. This supersedes earlier S9/S10 deferrals
in the historical slices above; S1/S2/S3 remain outside this change.

Verification: 148 tests passed (145 Scenario Manager tests plus 3 repository import
boundary tests), zero failures/errors/skips. Log: /tmp/ph-s9-s10-tests.log. No deployed
E2E or UI rerun. git diff --check passed. No commit created.

### F09-SM — S1/S2/S3 (implemented; review corrections completed)

Implement CREATE target collision rejection without replacement; validate-existing
reads current files; reload derives defunct from complete canonical validation.
Acceptance: preserve occupied CREATE target, allow normal CREATE/REPLACE, reject
changed/multiple descriptors consistently, recover after fixing an invalid descriptor,
and match catalogue/runtime/validation outcomes including warnings and catalogue
restrictions. No concurrency snapshot or runtime layout redesign.

Implementation: CREATE uses createDirectory (no clear); only REPLACE clears existing
contents. validate-existing no longer receives a cached Scenario/defunct reason.
Reload projects defunct from full BundleValidationResult.ok; descriptor prerequisites
are now private to the validator. Removed the obsolete cached-defunct finding helper.
This supersedes the earlier S1/S2/S3 deferrals in this worktree.

Verification: 7 regression failures reproduced before the repair (/tmp/ph-s123-red.log).
Final selected reactor run: 243 tests, zero failures/errors/skips (/tmp/ph-s123-tests.log),
including 239 Scenario Manager tests and repository import checks. Covers occupied
CREATE (including HTTP 409 and content preservation), normal CREATE/REPLACE, current
protocol, recovery from stale parse errors, nested descriptors, invalid bundle extras,
missing Work settings and warning-only AUTHORING acceptance. Existing fixtures for
healthy worker scenarios now declare required inputs/outputs; six old tests had
relied on the narrower catalogue check. git diff --check passed.
No deployed E2E, atomic snapshot for concurrent external edits, commit or push.

S3 review correction implemented: one bundle UTF-8 text read helper converts only
CharacterCodingException into canonical BUNDLE_INVALID findings. Invalid template,
variables or schema text cannot abort reload/init; no alternative decoder or broad
IO suppression was introduced. Regression RED: three encoding cases errored before
the fix (/tmp/ph-encoding-red.log). GREEN: 246 selected reactor tests passed with
zero failures/errors/skips (/tmp/ph-encoding-tests.log). Tests exercise validation,
reload, new ScenarioService initialization and preservation of existing runtime
contents when materialization rejects the broken bundle. Subsequently reviewed in the
integrated delivery and committed in `7f9a0271`.


### Integrated verification — 2026-09-25

Merged Tim’s `ed9ceacf` into `codex/f07-ph-ui` as `7308aee2`, retaining our PH UI
access projections and uncommitted SM repairs. AuthProfileLoader consumes the shared
ScenarioBundleLayout; it remains the sole profile-discovery owner. Browser session
storage/parsing is owned by authSession.ts, with PH access decisions still supplied
by backend projections. MCP projection/transport separation preserves the current
remote-HTTP properties and PR #520 catalogue content.

Conflict-resolution checks covered ownership/plan consistency, implementation
boundaries, absence of new policy or libraries, current auth/scope contracts, and
readability of the combined paths. Corrected the MVC test composition to include
ScenarioAuthoringService and updated Tim’s golden catalogue fingerprint to the
already-changed PR #520 descriptions. These are integration checks, not a new
independent acceptance review of every change in either branch.

Verification: 2,269 Java tests passed, zero remaining failures/errors/skips, counting
the latest result per test class across the main run and focused completions. Scope:
SM, Orchestrator, Processor, MCP, TCP mock, Request Builder, HTTP Sequence and their
selected reactor dependencies (including Worker SDK and the import-boundary gate).
Real disposable Redis and OpenSSL fixtures were used for auth/sequence cases.
101 PH UI tests, both normal/plugin builds, five TCP browser-module test files,
Compose parsing and the HiveForge contract check passed. No deployed E2E/browser
acceptance was run against this integrated tree.

Local logs: `/tmp/ph-tim-merge-java-complete.log`,
`/tmp/ph-tim-merge-services.log`, `/tmp/ph-tim-merge-final-services.log`,
`/tmp/ph-tim-merge-http-redis.log`. Earlier failures remain in their logs; subsequent
focused completions resolve them without rerunning already-passing suites.

### Integrated review disposition — 2026-09-25

- Large-file fingerprint memory use: explicitly accepted by the user as a current
  limitation, not a blocker for this PR. Full validation on startup/reload reads
  each file into heap while computing its digest. The review reproduced OOM with
  a 128 MiB data file and a 64 MiB heap; these values are reproduction conditions,
  not a supported size boundary. Large files are not planned for immediate use.
  Streaming the identical digest is deferred; no code or validation changes made.
- TCP permission-set duplication fixed: TcpMockAuthFilter now consumes
  PocketHivePermissionSets.READ/MANAGE; its local READ/WRITE definitions are removed.
  HTTP method selection, global scope and authentication behavior are unchanged.
  Five AdministrationAuthenticationTest/WorkspaceControllerTest cases pass, with no
  failures/errors/skips. Repository search finds these combinations only in the
  shared owner; git diff --check passes. The large-file limitation above stays deferred.


### Approved degraded-control behavior (2026-09-26)

Implemented after F08 review. Separate review found the UI STOP availability gap;
the correction passed follow-up review on 2026-09-26. The user separately approved the
behavior change discussed in [command admission](../architecture/runtime-responsibilities.md#resp-swarm-command-admission).
STOP ignores stale telemetry and pending bootstrap acknowledgements after initialization;
new STOP requests resend disable and require new evidence even with STOPPED cached intent.
STOP supersedes pending START through both Orchestrator reservation and Controller handling.
START is settled as FAILED; STOP has its own identity and disabled-evidence check.
Missing/never-ready expected runtime workers remain in the non-converged list.
Controller config admission drops only the heartbeat-readiness requirement, retaining
initialization, pending-bootstrap and workload-state rules. Worker-targeted speed changes
already use the separate worker config path. In-flight config operations retain their
own results/timeouts. A config error cannot cancel pending STOP; lifecycle failure is
now decided by the command owner instead of also being written during config-error parsing.

Validation: 275 Orchestrator tests and 232 Controller tests passed, two existing
RabbitAvailable Controller integration tests skipped (509 discovered).
`/tmp/ph-control-full-tests.log`; no deployment/load qualification performed.
Tests cover ordered concurrent lifecycle dispatch, exact request replay, START supersession,
late START result rejection after termination, stale/pending-bootstrap STOP, missing worker
timeout, preserved strict START and config state gates. `git diff --check` passes.

Scope/ownership checks: existing operation/readiness owners and lifecycle service reused;
no new dependency, wire field, terminal enum or endpoint. Canonical REST/architecture
no-op and lifecycle-conflict rules updated to the approved behavior. Reset/recovery,
transport lifecycle and cross-restart operation persistence remain separate work.


### Final F08 / degraded-control review — 2026-09-26

No outstanding finding after review of the UI correction. `SwarmLifecycleButtons`
is the sole owner of the extracted lifecycle button presentation; HivePage supplies
backend access projections and existing operation feedback. Pending START permits
Stop after HTTP acceptance, while pending STOP and HTTP dispatch block repeat actions.
Existing permission checks remain. Outcome correlation and the polling callback's
current-correlation check keep superseded START responses from overwriting STOP.

Review passes: requested behavior is available through the UI; the component has a
bounded rendering responsibility and a contract header; extraction replaces the old
inline controls; no new authorization policy, network surface or library; existing
feedback and backend operation owners are reused. F08 observation and command-admission
owners were traced in the preceding review; no new duplicate owner was found.

Deployment/browser/load qualification is not implied by component tests or builds.
No deployment was performed for this closeout. Reset/recovery, orphan cleanup and
Processor transport replacement/shutdown remain deferred, as does the accepted
large-file fingerprint memory limitation.

Final verification of this integrated worktree (2026-09-26): full affected reactor
`AUTH_OPENSSL_TEST_EXECUTABLE=/usr/bin/openssl ./mvnw -B -ntp -pl orchestrator-service,swarm-controller-service -am test`
passed: 1,954 discovered, 1,948 passed, zero failures/errors, six skipped. This includes
Scenario Manager and reactor dependencies, Worker SDK, import boundaries and real
Testcontainers-backed Orchestrator tests. Four Redis-fixture cases were skipped
(RedisListIntegrationTest, RedisSequenceConfigurationTest, OAuth2HttpSignatureRedisTest,
OAuth2SecondPassWireTest), plus the two existing RabbitAvailable Controller cases.
Log: `/tmp/ph-f08-final-reactor.log`. No claim that skipped cases were executed.

All 106 PH UI tests passed. Normal and VS Code plugin production builds passed;
ESLint passed for the new lifecycle component, its tests and the feedback regression.
The build reports the existing large-bundle warning. `git diff --check` is clean.
This completes source/component/build verification for the current patch; it does
not replace deployment/browser/load acceptance. Prior integrated MCP/TCP/Processor
verification remains the dated evidence above; these services were not changed by
this F08/control/UI patch and were not rerun here.
