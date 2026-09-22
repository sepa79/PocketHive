# Functional module boundaries — next refactors

Status: refreshed 2026-09-22 against `18987afc`, after Artemis and acceptance closeout.
This is the next-work plan, not additional implementation scope for PR #519.
The user authorized refreshing and publishing this plan; implementation starts only
when the next slice is selected. Historical analysis remains in Git history.

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
| Redis | Lettuce in SDK `RedisDataSetWorkInput`, `RedisPushSupport`, `RedisTokenStore`, `RedisDebugCaptureStore`, and templating `RedisSequenceGenerator` | One Redis technology owner, existing settings owner, domain-specific operation ports |
| Work integration | SDK `input/WorkInput.update` still takes `WorkerControlPlaneRuntime.WorkerStateSnapshot`; SDK factories take WorkerDefinition; neutral output transport already exists | Move only contracts necessary for the selected consumer; retain SDK composition and accepted-state ownership |
| Sequences | `ConfiguredRedisSequenceAccess` calls `RedisSequenceGenerator.getDefaultInstance()` | Explicit SequenceAccess composition and owned client lifetime |
| Docker | Raw Docker imports in both service configurations, `DockerRuntimeAdapter`, `SwarmLifecycleManager`, `DockerWorkloadProvisioner`, and `docker-client` | Existing docker-client owns client realization, inventory and operations; applications retain lifecycle decisions |
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

### F01 — Redis, delivered in bounded complete paths

Start with an inventory of connection settings, client lifetime, keys/list names,
serialization, timeouts and operations for all five consumers above. Include debug
capture; it was missing from the older four-consumer plan. Distinguish Redis
mechanics from token identity, dataset selection, trace identity and sequence policy.

1. **F01a: first complete path — debug capture.** Establish `common/redis-adapter`
   as client/connection mechanics owner. `HttpSequenceRunner` currently constructs
   RedisDebugCaptureStore directly; introduce only the narrow expiring-write API
   needed by this path. Reuse redis-config directly; do not move its
   parser just to obtain a new module name. Preserve capture encoding, limits,
   expiry, lazy initialization, best-effort diagnostic failure and close behavior.
   Delete the replaced SDK client implementation. HttpSequenceRunner retains
   capture selection/keys/payload and consumes the adapter API. This diagnostic
   capture is distinct from WorkPlane debug taps.
2. **F01b: dataset input and output.** Route read/write operations through the
   adapter. Reuse neutral Work transport contracts. If input extraction requires
   an update view, define only the immutable adapter-facing values it consumes;
   do not copy the entire mutable runtime snapshot or WorkerDefinition. Dataset
   selection and output target rules each retain their current canonical owner.
3. **F01c: token storage and sequences.** Move Redis storage implementations behind
   TokenStore and SequenceAccess, preserving atomic claims, expiry, increment/reset
   and errors. Inject sequence access explicitly; remove hidden global client
   configuration. Keep OAuth refresh policy and token-key identity with auth owners.
4. **Final closure.** Only redis-adapter imports Lettuce in production. Remove the
   SDK/templating exceptions and all replaced constructors/helpers. Redis-config
   may remain the single pure settings module; absorbing it is optional and must
   be an atomic move, never a second parser or compatibility artifact.

During F01a–c, explicitly list unmigrated consumers and their non-overlapping
responsibilities. No migrated responsibility may retain an alternative active
implementation. Do not claim Redis-wide closure until all five paths are cut over.

Gates per slice: non-default host/port/database/auth/TLS settings reach the actual
client unchanged; failure and shutdown release owned resources; relevant list,
capture, token or sequence operations retain observable behavior. Use owner tests
and affected ingress acceptance groups (debug taps, DA-1/2/4 and auth as applicable),
not bean-registration tests. F01a requires HTTP Sequence diagnostic-store
verification; successful WorkPlane taps alone do not exercise this Redis path.
Keep the deferred Redis STOP/update/START race as a
separate behavior decision; do not fix it incidentally.

Before F01a implementation, trace HttpSequenceRunner capture callers and the exact connection
contract, specify the minimal adapter API, and record baseline behavior tests. This
is the first concrete next task; a broad SDK rewrite is not a prerequisite.

### F03 — Docker/compute

Extend docker-client. Move client construction and raw inventory/inspect/removal
mechanics behind neutral specs/results; no raw Docker client/models escape the API.
Preserve explicit compute modes. Service coordinators retain when/why to provision,
operation state and convergence. Transfer one complete operation path per PR.

Gate: selected consumers use the API, bypass constructors/imports disappear, and
create/use/inspect/remove behavior is verified. Orphan removal postconditions and
AUTO-selection disagreements require separate decisions; moving code cannot silently
change success semantics or bypass the existing governed cleanup boundary.

### F04 — Runtime filesystem and journal

Extend RuntimeFilesystemLayout for journal artifact paths; remove reader/writer
path reconstruction. Then extract journal append/query/retention capabilities,
using journal-postgres for SQL and a named file implementation. Keep CP and swarm
journal contracts distinct; do not create one global journal state machine.

Gate: read/write/export/delete use identical resolved paths; invalid identifiers
follow one owner contract; REST maps requests and delegates; retention has one writer.
Do not reopen the completed exporter-directory fix.

### F05 — ClickHouse

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

Plan review: this update removes stale prerequisites, preserves existing owners,
adds the omitted Redis capture consumer and splits implementation from behavioral
redesign. No production code, public contract, dependencies or deployment changed.
Implementation is not authorized merely because this plan ships in PR #519.
