# N3 local replacement assessment — 2026-09-18

Scope: `codex/artemis-work-plane`, reviewed baseline `51227a32`, plus the DA-3
correction described below. This is a dated assessment, not another coverage ledger.
[The coverage matrix](acceptance-coverage.md) remains the current requirement/status
owner; the repository execution plan (`docs/inProgress/e2e-test-system.md`) owns N3/N4 sequencing.

The local requirement/assertion comparison is complete. One missing transition was
found: DA-3 tested a preconfigured ClickHouse sink, while the old observable behavior
included enabling it through runtime configuration. That gap is corrected and its
execution evidence is listed below. Its separate source review subsequently passed
without findings (53 focused tests); the correction was committed in `6e3b0aa4`.
NW-4 still lacks cross-node Swarm/NFS execution. Final N3 acceptance and N4 deletion
remain open; no legacy code was removed and no remote stack was deployed.

## Requirements compared with actual assertions

Read all six frozen feature files and the relevant legacy steps to identify observable
requirements. Reviewed every replacement suite and the delegated API, operation,
resource and assertion paths. No legacy implementation was copied. The 41 matrix
rows include two new framework requirements; they are not 41 deployed test methods.

| Matrix rows | Inspected assertions and disposition | Execution basis |
| --- | --- | --- |
| SM-1 | `PlatformSmokeAcceptanceIT`: public UI health and both service health responses. `SpringRabbitBrokerTest` separately proves actual CONTROL delivery on an isolated broker. | Retained current smoke run; owner tests rerun. |
| SM-2 | `FreshDeploymentAcceptanceIT`: explicit deployment identity, actual deployment-wide admin profile, empty canonical registry. Freshness is established by the dedicated provisioning record, not inferred from an empty list. | Preserved successful invocation, isolated project/volume/network creation and teardown records. |
| SC-1..3 | `ScenarioReadAcceptanceIT`: numeric 7.5 rate, full templating object, exact four-role history-policy map. | Fresh public-ingress replay. |
| WK-1..2 | `WorkerRuntimeAcceptanceIT`: each authored policy versus fresh worker state; actual LATEST_ONLY processor result with one step at index zero, executing identity and step-only result headers. `WorkerHistoryPolicyTest` supplies FULL and runtime transition cases. | Fresh Rabbit/Artemis replay and owner tests. |
| WK-3, SC-4 | `TemplatingAcceptanceIT`: amber/violet profiles, global/SUT/profile values, exact typed request JSON and headers, successful HTTP result from that request. | Both profiles replayed on each adapter. |
| WK-4..5 | `WorkerConfigurationAcceptanceIT`: all authored worker settings, generator I/O/message, moderator settings, processor URL/thread count, postprocessor flags, runtime identity and real work. Real status publisher/codec tests establish full/config/runtime versus delta omission. | Baseline and overrides replayed on both adapters; status owner tests rerun. |
| SW-1..2 | `HttpLifecycleAcceptanceIT`, `TargetStateLifecycleAcceptanceIT`: current run/controller/workers, actual result, expected workload transitions, distinct repeated requests, verified REMOVE. Exact-key replay, native provisioning/removal and CONTROL wire semantics have named owner tests. | Current retained Artemis lifecycle evidence, fresh Rabbit replay and owner tests. |
| SW-3 | `ScenarioPlanAcceptanceIT`: baseline/rate/pause/resume/final-stop observations, traffic before/after pause, five ordered journal steps and plan completion for the owned run. Test issues CREATE/START/REMOVE, not the plan's STOP. | Fresh Rabbit/Artemis replay. |
| NW-1..3 | HTTP/HTTPS/TCPS suites compare authored endpoints, canonical binding, applied runtime config and actual response; verified removal includes binding absence. | Fresh Rabbit/Artemis replay. |
| NW-4 | `NetworkBindingRecoveryAcceptanceIT`: real work before/after rejected apply, full previous binding retained, bounded apply timeout, explicit clear and absence. | Complete retained local Rabbit/Artemis runs; cross-node Swarm/NFS remains unverified. |
| NW-5 | `TcpTimeoutAcceptanceIT`: paired successful 8s read timeout against a 5s delayed response, then 500ms timeout with owned processor/runtime alert and empty result tap. The wrapper alert does not assert the root exception text. | Both positive control and error case replayed on both adapters. |
| DA-1..2 | `RedisDatasetAcceptanceIT`: two isolated seeded records, disabled workers before seeding, exact Generator/Request Builder/Processor FULL history, rendered request/header and successful response. | Preserved successful Rabbit/Artemis logs plus audited record/operation/key-cleanup summaries. |
| DA-3 | `TxOutcomeAcceptanceIT`: NONE with real traffic and no persisted rows, runtime CONFIG_UPDATE targeting the same postprocessor, applied CLICKHOUSE_V2, new captured traces matched to stored swarm/sink/call ID/status/success/duration. | Fresh Rabbit/Artemis execution after the correction. |
| DA-4 | `WebAuthLoopAcceptanceIT`: five isolated customers, exact TCP XML/response, ordered RED/BAL/TOP/RED journal per customer, distinct request identities and scoped seven-key cleanup. The earlier sourceList RED/GREEN remains documented in the ledger. | Preserved successful Rabbit/Artemis logs and customer/journal/cleanup summary. |
| EX-1..3 | `ClearingExportAcceptanceIT`: exact 20 records, text/XML counts/content/totals and runtime settings; streaming checks every observed finalized output against the 15s window before STOP, below the 100-record limit, and preserves content afterward. Canonical runtime layout owns paths. | Preserved successful logs on both adapters; corrected streaming run and timeline also retained in full. |
| AU-1..2 | `AuthReadAcceptanceIT`: authenticated 200 and anonymous 401 on every declared protected route. | Fresh read-only replay. |
| AU-3..6, AU-8, AU-13 | Viewer, scoped runner and network suites verify exact actor grants, allowed reads/create, denied mutations, absence/unchanged state after denial. | Fresh replay; these access decisions do not depend on the WORK adapter. |
| AU-7, AU-9..12 | Provisioned actor suites verify exact grants, bundle/folder boundaries, same-swarm RUN/ALL differences, refresh/reset denial, canonical config receipts, journal metadata/pin readback and tap access. `ScenarioManagerAuthFilterTest` covers runtime materialization with actual filesystem postconditions. | Fresh Rabbit/Artemis replay; isolated authorization owner test rerun. |
| FW-1..2 | `FailureCleanupAcceptanceIT` and framework tests: primary assertion preserved, verified cleanup; ambiguous acquisition/dispatch, receipt mismatch, failed cleanup, terminal failure, bounded timeout and report-write failure remain visible. | Current Artemis and fresh Rabbit deployed evidence; complete framework tests. |

## Evidence assessment

No tracked raw run archive was found in the 29 available local/cached remote refs
checked with `git ls-tree`; the other local PocketHive worktree has no acceptance
module. This is a check of available refs, not a claim about un-fetched remote data.
The earlier deleted `target/runs` JSON has not been reconstructed.

For Redis, WebAuth, exports and SM-2, preserved full successful invocation logs plus
the earlier concrete audit/provisioning records support the inspected assertions.
Those groups were not rerun solely because their raw JSON was lost. The historical
DA-3 logs establish preconfigured persistence only; the missing transition needed a
new execution. Earlier groups whose ledger entries lacked accessible supporting
records were replayed through the canonical runner on the public local ingress.

Evidence roots (local, ignored by Git):

- `acceptance-tests/runs/n3-audit-20260918`: current logs, invocation manifests listing
  exact run directories, archived JUnit reports and final registry observation.
- `acceptance-tests/runs/historical-partial-20260918`: preserved logs/summaries and
  SM-2 provisioning/teardown records. The name deliberately identifies incompleteness.
- `acceptance-tests/runs/n3-readiness-20260918`: prior current smoke/lifecycle/streaming
  report archives and evidence-retention verification.
- NW-4 runs `network-binding-recovery-3d7f1d27-1a7b-40a3-b465-e1a8f0c54cd2`
  (Artemis) and `network-binding-recovery-dc3295e7-2b28-4cde-a0ad-7ef68557e063` (Rabbit).

Each invocation log identifies the tests actually executed. A report archive can
also contain older XML files; its directory size or XML count is not the run count.
The replay bookkeeping lives only under `/tmp`; it is not another repository runner,
source scanner, evidence format or authority for product outcomes.

Current independent framework build: **178 tests, zero failures/errors/skips** after
DA-3 (177 at the reviewed baseline), with a nine-module reactor that excludes legacy,
production services and broker adapters. Separately selected owner tests: **126
passing, zero skips** in `owner-tests.log`:

| Owner coverage | Executed tests |
| --- | ---: |
| Rabbit physical CONTROL/WORK and resource/transport policies | 21 |
| Artemis embedded broker and topology | 12 |
| CONTROL codec/emitter/publisher | 25 |
| Existing repository import boundary test | 3 |
| SDK history transitions and worker status contracts | 16 |
| Scenario Manager authorization/materialization | 21 |
| Operation dispatch/replay and coordinator | 11 |
| Controller target-state handling and status publisher | 17 |

Fresh deployed acceptance: **67 successful test executions across 30 explicit runner
invocations**, zero failures/errors/skips. These include 16 read-only checks, both
WORK adapters for shared behavior, DA-3 after the correction, and an additional
Artemis AU-12 regression for the generalized component-config method. The generated
`execution-summary.json` indexes 94 new run directories and
157 canonical successful operations, including
40 REMOVE results with nonempty removed resources and empty remaining/errors.
The suites also verify public absence after REMOVE. Artemis WORK was restored after
an empty registry check; the final public registry is `[]` (`final-swarms.json`).

For DA-3, each adapter records the successful postprocessor CONFIG_UPDATE, the initial
empty outcome read with NONE, fresh CLICKHOUSE_V2 worker config, captured traffic
before/after the update and matching persisted rows. Exact run IDs are in
`tx-outcome-{artemis,rabbit}.json`; the five CREATE/START/CONFIG_UPDATE/STOP/REMOVE
operations all succeeded on each adapter. No ClickHouse rows were deleted.

A local fixture-refresh command encountered the existing read-only capabilities
mount (`scenario-sync.log`). No build-script or mount change was made: scenario files
were already bind-mounted, and the supported debug CLI reloaded them through the
public ingress (`scenario-reload.json`). This operational limitation is separate from
test results and does not add a framework fallback.

## Responsibility and SSOT assessment

The following records refer to the existing
[acceptance architecture](../architecture/acceptance-tests.md); headers were compared
with the named sections and actual delegated effects. The new tests remain independent
of the frozen E2E implementation.

| Contract / owner | Inspected call path and effects; alternative-owner assessment | Evidence and limit |
| --- | --- | --- |
| RESP-ACCEPTANCE-TARGET / `TargetLoader` | Runner supplies file and group; specialized target loaders compose typed settings through this parser. No suite resolves environment, detects a broker or selects a fallback. | Missing/invalid target tests and explicit per-adapter deployed targets. |
| RESP-ACCEPTANCE-HTTP/API / `PocketHiveHttp`, `ApiSurface`, small endpoint clients | `ApiRun` constructs one bounded HTTP transport; API clients map public routes and canonical DTOs. Grafana/Redis/TCP clients use public ingress. `ControlReceipts` owns key validation; no wire DTO or transport copy. | Framework HTTP/API tests, ingress runs and Enforcer dependency bans. DA-3 changes the existing component-config mapper; no second mapper. |
| RESP-ACCEPTANCE-OPERATIONS / `OperationAwaiter`, `Deadline` | `SwarmResource` submits once and observes canonical operation identity/type/terminal state with one bounded budget. Neither calculates domain convergence nor reconstructs success from worker status. | Identity, failure, deadline and stale/foreign-operation component tests; deployed operations. |
| RESP-ACCEPTANCE-RESOURCES / resource handles | Each handle owns acquisition certainty and cleanup of its exact resource. Swarm REMOVE requires canonical removed/remaining/errors and public absence. Dataset cleanup depends on the swarm handle's verified permission. | Success/failure/ambiguous-dispatch tests and deployed cleanup. Handles do not own the product state machine or orphan cleanup. |
| RESP-ACCEPTANCE-CAPTURE / `DebugTapApi`, `TapResource` | Logical public tap API and canonical WorkItem codec; DELETE plus readback closes the tap. No broker client, physical destination or competing queue consumer. | Captured real results and timeout/close/failure component tests. |
| RESP-ACCEPTANCE-WORKERS / `WorkerObservations` | Reads the canonical swarm projection, checks owned run/instances/freshness and applies phase assertions. No CP message merge, config normalization or state mutation. | Worker, timeline and network suites; SDK/controller owner tests cover actual full/delta emission. |
| RESP-ACCEPTANCE-EVIDENCE/RUN / `RunEvidence`, `ApiRun`, `LiveRun`, `AuthFixture` | Composition and per-test evidence; HTTP lifetime remains in ApiRun. AuthFixture supplies explicit grant inputs and actor resource handles, not authorization decisions. Evidence failure does not discard accepted receipts. | Framework evidence/lifetime/failure tests. New DA-3 composition exposes the existing management API. |
| RESP-ACCEPTANCE-REDIS-FIXTURE and DA-3/DA-4 APIs | Public Redis Commander seeds/observes only owned keys; Grafana performs scoped read-only SELECT; TCP mock reads known mapping/journal. Production dataset, projection and sink owners retain interpretation/write behavior. | Data acceptance proofs and API error/shape tests; no native Redis/ClickHouse client or telemetry deletion. |
| RESP-ACCEPTANCE-EXPORT-FILES / `ExportFiles`, `StreamingExportObservation` | `RuntimeFilesystemLayout` supplies the path; ExportFiles observes regular output files. The concurrent observer timestamps immutable snapshots; lifecycle and evidence writes remain with the caller. | File/symlink and controlled-clock/cancellation/report-failure tests plus deployed text/XML/streaming evidence. Remote filesystem access remains deferred. |

Repository searches included `controllerConfig`, `componentConfig`, `OperationAwaiter`,
`TargetLoader`, `RuntimeFilesystemLayout`, broker/legacy imports and the actual file,
network, process and environment entrypoints. Inspected alternatives are the frozen
legacy test system (explicit temporary coexistence), product contract/behavior owners,
or distinct endpoint/resource families. No additional active product authority was
identified in the reviewed replacement paths. Searches and dependency rules support
the traced source review; they are not claimed as proof from names alone.

## Six review passes on the baseline

- **Plan:** requirement-level comparison found the DA-3 runtime transition gap. It is
  corrected in the working tree, with independent review still due. Cross-node NW-4
  and final acceptance remain explicit gates; legacy deletion is not authorized yet.
- **Style:** bounded owners and responsibility headers match the architecture;
  canonical contracts and public ingress are used. No new source-policy scanner.
- **Conciseness:** suites reuse HTTP, observations, operation waits and scoped handles.
  Generalizing the existing component-config method removes the controller-only
  restriction without introducing a parallel command/cleanup abstraction.
- **Security:** exact actors and grant scopes are asserted, newly provisioned users
  are revoked/deactivated, and denied writes have readback checks. Tests mutate only
  their own fixtures/swarms; no broad cleanup, deployment reset or token-in-report path.
- **Libraries:** existing JDK HTTP, Jackson, JUnit and canonical product contracts
  suffice. No dependency added; broker-specific proof stays with adapter owner tests.
- **Readability/maintainability:** each suite states observable behavior and delegates
  mechanics to the named owner. The ledger distinguishes fresh raw evidence,
  historical logs and unverified environment behavior. The DA-3 change is implementation
  evidence for the next review, not a self-approved review/fix loop.

No additional blocking finding was identified in the baseline paths inspected for
local replacement. This does not claim exhaustive production-service review or
prove the deferred Swarm/NFS boundary. Next: separate review of DA-3 and this evidence
assessment, then NW-4 on the agreed Swarm target and final N3 acceptance before N4.


## Subsequent separate review and sequencing — 2026-09-18

The separate review of the DA-3 correction and this assessment found no actionable
issues or competing SSOT. It ran 53 focused API/resource/operation/capture tests,
all passing, and checked the retained Rabbit/Artemis DA-3 observations plus all
30 invocation logs supporting 67 successful live executions. It did not rerun
deployed E2E or the full reactor. Final N3 and cross-node NW-4 remain open.

The user then chose to resume Artemis/3DS and defer legacy deletion until their
manual tests and confirmation. Current sequencing is owned by the
repository execution plan (`docs/inProgress/e2e-test-system.md`); this dated assessment does not
authorize deleting the old framework.


## Closing execution evidence available — 2026-09-22

Cross-node NW-4 passed on the large Swarm (NPM mgr-2, HAProxy wrk-1).
The canonical ledger owns the result and evidence links. This closes the missing
execution condition; it does not retroactively turn this dated local assessment
into a review of the new deployment changes. Final N3 awaits separate review of
the closing evidence. N4 remains explicitly deferred pending manual confirmation.

## Final N3 review — 2026-09-22

N3 is closed. Reviewed the archived full-Swarm JUnit index (55 PASS, two image
assertion failures), both corrected WK-4/WK-5 reruns (one PASS each), cross-node
NW-4 and fresh SM-2 with successful HiveForge remove/deploy and scoped empty-root
cleanup evidence. Latest results cover all 57 distinct cases; this is not a claim
that the full suite was repeated after the test-only correction.

Reviewed the actual image path: Orchestrator resolves the reference; compute
adapters record the launch image; public runtime inventory exposes that label.
Worker status is compared against this independent projection, with exact run,
swarm, role and instance matching. No image resolver was added to the framework.

Six passes: plan acceptance satisfied; style/responsibility headers aligned;
concise boundary client using existing HTTP lifetime; authenticated public ingress
with owned-resource cleanup; no new libraries; explicit oracle and documented
limits. Repository search found no second acceptance runtime-inventory client or
image resolver. No blocking findings in this closing scope. This is not a new
exhaustive review of every production service on the branch. N4 remains deferred
until user manual testing and explicit confirmation. Raw artifacts remain local
in the directories named by the full-Swarm report.
