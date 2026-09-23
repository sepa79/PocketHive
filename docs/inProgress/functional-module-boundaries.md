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
| Work integration | SDK `input/WorkInput.update` still takes `WorkerControlPlaneRuntime.WorkerStateSnapshot`; SDK factories take WorkerDefinition; neutral output transport already exists | Move only contracts necessary for the selected consumer; retain SDK composition and accepted-state ownership |
| Sequences | SDK `RedisSequenceConfiguration` owns application-scoped instances; no global sequence client | Implemented with F01 |
| Docker | Client construction, compute selection mechanics and runtime operations use `common/docker-client`; both services consume compute/host ports; stack naming has one implementation | F03 implemented; applications retain lifecycle decisions and cleanup postconditions |
| Journal/files | `SwarmJournalController` and `FileSwarmJournal` each assemble `journal.ndjson`; RuntimeFilesystemLayout already owns swarm/run directories | Extend the layout owner; journal API owns storage operations, REST delegates |
| ClickHouse | `ClickHouseMetricsSink` and `ClickHouseTxOutcomeSink` each construct HTTP clients and INSERT queries | Existing sink-clickhouse owns transport mechanics, separate explicit domain/buffering policies |
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

Confirmed on the startup/read paths: FileSwarmJournal and SwarmJournalController
both own `journal.ndjson`; the reader also reconstructs the run directory. Fix in
this dedicated refactor, not F03.

Extend RuntimeFilesystemLayout for journal artifact paths; remove reader/writer
path reconstruction. Then extract journal append/query/retention capabilities,
using journal-postgres for SQL and a named file implementation. Keep CP and swarm
journal contracts distinct; do not create one global journal state machine.

Gate: read/write/export/delete use identical resolved paths; invalid identifiers
follow one owner contract; REST maps requests and delegates; retention has one writer.
Do not reopen the completed exporter-directory fix.

### F05 — ClickHouse

Add the confirmed duplicate ENV export in ContainerLifecycleManager and
SwarmWorkerSpecFactory to this dedicated refactor. One sink-owned codec must export
endpoint/table/credentials/timeouts/batching; both launch paths consume it, retaining
the current precedence and values. This is plan-only during F03.

Move repeated HTTP/auth/INSERT construction into sink-clickhouse. Metrics and
transaction event construction stay with their domain owners. Their flush,
buffering and failure policies remain explicitly distinct, not unified defaults.

Gate: no consumer builds ClickHouse URLs/queries/credentials; exact requests and
queue-full/flush-error behavior covered; DA-3 still proves persisted outcomes.
Vendor-import restrictions alone cannot detect duplicated JDK HTTP implementations.

### F02 / F06 / F07 / F09 — selected follow-up slices

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
F01 and F03 were subsequently authorized explicitly by the user; the other entries remain plans.
