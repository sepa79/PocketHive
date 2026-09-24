# Functional module boundaries — next refactors

Status: F01 implemented in `9a12dd50`; F03 implemented in `3116364c`,
reviewed on 2026-09-23 on `codex/redis-adapter`. Prepared for review and integration
alongside PR #520. This work follows PR #519. Historical
baseline analysis remains in Git history.

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
| Worker auth | AuthRuntime now delegates preparation/validation to AuthProfilePreparation; OAuth/signature work changed these paths in PR #517 | Re-trace current authoring/runtime/token flow before alleging duplicate validation or extracting worker-auth |
| Freshness | `SwarmReadinessTracker.STATUS_TTL_MS` and `SwarmWorkerStatusHandler.WORKER_STATUS_STALE_AFTER_MS` remain separate 15s definitions | First decide whether they describe the same fact; then one owner/projection for that fact |
| UI network projection | `ui-v2/src/lib/networkProxy.ts` maps every unknown mode to DIRECT | Separate contract/behavior decision; do not silently change acceptance during extraction |

Older Scenario contract copies, metadata resolution, UI grants, MCP readiness,
HTTP client construction and TCP-mock persistence findings are audit leads, not
newly confirmed defects. Recheck their current owners and actual callers before
adding an implementation task. In particular, do not reuse the old MCP/auth class
inventory or old test counts as current evidence.

## Delivery order and stable task IDs

Keep F identifiers for existing references. Execution order is **F01 → F03 → F04
→ F05**, then individually selected F02/F06/F07/F09 slices. F08 and correctness
work need behavior decisions first. This ordering does not authorize parallel
changes across those areas or require every area to be finished for the next PR.

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

The remaining startup audit findings are plan-only under F04, F05 and F08 below,
as explicitly requested. They are not prerequisites for completing this F03 slice.

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

**Event reads reviewed, uncommitted:** Hive/swarm/live/archive event SELECTs,
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

**Run lists reviewed, uncommitted:** list SQL, summary/tag mapping and live/pinned
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

**Implemented, pending separate review.** Base: F07 commit `02b97665`.
Re-tracing confirmed duplicate ownership:
HttpProtocolHandler, TcpProtocolHandler and Iso8583ProtocolHandler each implement
applyExecutionMode against the same per-worker AtomicLong. All three callers now
use one ProcessorPacer owning both state and waiting; the old methods and externally
writable counter are removed. First-call delay, shared slots, rate/mode updates,
interruption and reported pacing duration are preserved. Configuration stays
with ProcessorWorkerConfig. See RESP-PROCESSOR-PACING for exact semantics.

This is a local processor responsibility, not a universal rate limiter. Moderator
shaping and scheduler quotas differ and remain separate. HTTP client construction,
TCP transport lifetime, Scenario Manager/MCP/TCP-mock boundaries remain to audit;
this slice does not close F09 as a whole. No TLS/security or ACK behavior change.

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
tests passed. No full repository reactor or deployed E2E was repeated.

### F08 and separate correctness work

Readiness, freshness, reset, registration, orphan cleanup and lifecycle outcomes
are domain facts. Define their distinct meanings, writers and postconditions before
changing them. Do not equate thresholds or merge state machines by convenience.
[Orchestrator correctness](orchestrator-correctness.md) remains a separate track;
its historical O1/O2 review status must be checked against current code/evidence
before selecting further fixes. This refresh does not accept or reopen that work.

Startup audit follow-up for F08: worker status observations write timestamps in both
SwarmReadinessTracker and SwarmWorkersAggregator, then independently calculate health
and stale with separate 15s thresholds. Establish one observation/freshness owner
and derived metrics/worker-list projections in the dedicated state refactor; do not
change lifecycle timing during F03.

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
F01, F03, F04 and F05 were subsequently authorized explicitly by the user; other entries remain plans.
