# Acceptance replacement coverage

N0 inventory and current N1/N2 evidence, 2026-09-15. This ledger is a requirements checklist, not executable
routing/configuration and not an assertion of equivalence. Sources: the six frozen
feature files under `e2e-tests/src/test/resources/features`, current REST/lifecycle,
worker, auth and networking contracts. No legacy implementation is reused.

Each row must receive concrete assertion/test and execution evidence before N3.
A new assertion may cover several old cases, but all observable requirements remain
accounted for. Rejected or obsolete expectations need an explicit agreed disposition.
OPEN means not yet demonstrated by the new system, including rows with a named target.
PASS means the stated row behavior has execution evidence; it does not close other rows or N3.

| ID | Required observable behavior | Source feature/case | New coverage / prerequisite | Status |
| --- | --- | --- | --- | --- |
| SM-1 | Ingress reports platform availability; preserve CONTROL connectivity coverage through an explicit owner test. | deployment-smoke: services healthy | PlatformSmokeAcceptanceIT through public ingress; SpringRabbitBrokerTest CONTROL transport on isolated broker, with SpringRabbitControlDeliveryTest policy checks | PASS |
| SM-2 | Fresh deployment has no implicit default swarm. | deployment-smoke: default swarm absent | Smoke on explicitly fresh dedicated target; do not assume all targets empty | OPEN |
| SC-1 | Authored generator rate appears in retrieved template. | scenario-defaults: rate limit | ScenarioReadAcceptanceIT.preservesAuthoredSchedulerRate: explicit numeric 7.5 through ingress | PASS |
| SC-2 | Authored templating interceptor appears in template. | scenario-defaults: templating | ScenarioReadAcceptanceIT.preservesTemplatingConfiguration: full authored templating object through ingress | PASS |
| SC-3 | Per-worker history policy survives template read. | history-policy: authoring | ScenarioReadAcceptanceIT.preservesEveryWorkersHistoryPolicy: exact four-role map with FULL/LATEST_ONLY through ingress | PASS |
| WK-1 | Runtime history policies match authoring and real traffic succeeds. | history-policy: runtime | WorkerRuntimeAcceptanceIT: authored policies match fresh runtime observations; actual processor LATEST_ONLY results contain one step at index zero with the correct executing identity. Rabbit/Artemis pass; SDK component tests cover FULL and policy transitions. | PASS |
| WK-2 | Processor result headers belong to step history, not global headers. | workitem-headers | WorkerRuntimeAcceptanceIT: canonical processor step headers, no global leakage, exact producing instance; Rabbit/Artemis pass | PASS |
| SW-1 | Create/start/process/stop/remove succeeds; expected workers present; canonical confirmations correlated; resource provisioning/removal has coverage. | swarm-lifecycle: golden path | HttpLifecycleAcceptanceIT on both adapters plus explicit Rabbit/Artemis broker, CONTROL codec/emitter, lifecycle replay and target-state owner evidence below | PASS |
| SW-2 | Stop-before-start and repeated start after target state are accepted correctly. | swarm-lifecycle: idempotent target state | TargetStateLifecycleAcceptanceIT passed on Rabbit and Artemis; STOP before START, repeated STOP/START, distinct request keys and operation IDs, same run and expected states. Exact-key replay is not asserted. | PASS |
| WK-3 | Templated generation produces expected processed response. | swarm-lifecycle: templated generator | TemplatingAcceptanceIT: interceptor output consumed by the generator message template, exact generated request body/headers and successful HTTP response in the same captured WorkItem; two profiles on Rabbit/Artemis | PASS |
| NW-1 | HTTP reaches SUT through selected proxy; runtime config and binding match; binding removed. | swarm-lifecycle: HTTP proxy | HttpProxyAcceptanceIT: explicit bundle SUT/profile, canonical binding, current processor config, successful HTTP proxy URLs, binding404 after verified REMOVE; Rabbit/Artemis pass | PASS |
| NW-2 | HTTPS reaches SUT through selected proxy with matching runtime config; binding removed. | swarm-lifecycle: HTTPS proxy | HttpProxyAcceptanceIT HTTPS: actual HTTPS response, explicit sslVerify=false in runtime, authored/binding/result addresses and verified binding removal; Rabbit/Artemis | PASS |
| NW-3 | TCPS reaches SUT through selected proxy with successful result; binding removed. | swarm-lifecycle: TCPS proxy | TcpsProxyAcceptanceIT: canonical TCP result and exact echo body, TLS scheme and runtime settings, authored/binding/result addresses, verified binding removal; Rabbit/Artemis | PASS |
| NW-4 | Valid binding applied; invalid candidate rejected without losing previous binding; explicit clear removes it. | swarm-lifecycle: HAProxy NFS | Dedicated network acceptance target supporting NFS topology | OPEN |
| SC-4 | Scenario variables resolve into generated traffic/template rendering. | swarm-lifecycle: variables | TemplatingAcceptanceIT: explicit amber/violet create profiles, global + SUT values, exact typed JSON and eval/header results from actual generated traffic; Rabbit/Artemis | PASS |
| NW-5 | Delayed TCP response produces processor timeout/error. | swarm-lifecycle: TCP timeout | TcpTimeoutAcceptanceIT: paired delayed-response control (8s timeout/5s delay) and error case (500ms timeout), owned run/processor alert through journal, empty result tap for explicit window, verified removal; Rabbit/Artemis | PASS |
| WK-4 | Explicit runtime config matches each worker; full status includes config/runtime metadata, delta omits heavy config. | swarm-lifecycle: explicit defaults | WorkerConfigurationAcceptanceIT baseline on Rabbit/Artemis; fresh config/runtime for every worker. WorkerStatusContractTest proves full/config/runtime → delta without config → full; SwarmControllerStatusPublisherTest proves controller runtime through codec | PASS |
| WK-5 | Explicit overrides, including generator I/O, reach all workers. | swarm-lifecycle: overrides | WorkerConfigurationAcceptanceIT overrides on Rabbit/Artemis: scheduler, adapter tuning, generator message, moderator mode/rate, processor URL/thread count and postprocessor flag; fresh config and successful traffic | PASS |
| DA-1 | Redis dataset flows through request builder and processor. | swarm-lifecycle: dataset traffic | RedisDatasetAcceptanceIT: two isolated records, FULL Generator/Request Builder/Processor history; Rabbit and Artemis verified | PASS |
| DA-2 | Dataset values are fully rendered in requests/payloads. | swarm-lifecycle: dataset payloads | Exact seeded JSON survives Generator and rendered HttpRequestEnvelope body; rendered header and HTTP result asserted on both adapters | PASS |
| DA-3 | Enabling tx outcome sink writes matching swarm outcomes to ClickHouse. | swarm-lifecycle: tx outcomes | TxOutcomeAcceptanceIT: persisted rows match captured trace IDs, swarm/sink identity, call ID, status, success and duration; Rabbit and Artemis verified through Grafana ingress | PASS |
| DA-4 | Five-customer WebAuth Redis fixture produces expected TCP activity. | swarm-lifecycle: WebAuth loop | WebAuthLoopAcceptanceIT: five owned customers, exact TCP XML/response and ordered RED/BAL/TOP/RED per customer; Rabbit and Artemis, seven-key verified cleanup | PASS |
| SW-3 | Scenario plan drives intended lifecycle transitions. | swarm-lifecycle: plan demo | ScenarioPlanAcceptanceIT on Rabbit/Artemis: fresh baseline/rate/pause/resume/final-stop worker snapshots, actual HTTP before pause and after resume, five ordered plan steps and completion in owned-run journal; CREATE/START/REMOVE only from the test. | PASS |
| EX-1 | 20 transactions form two clearing files. | swarm-lifecycle: clearing export | Exact finalized file content under canonical swarm/run/worker runtime directory | PASS |
| EX-2 | Structured config applied; 20 transactions form two XML files. | swarm-lifecycle: structured export | Applied config/schema; exact XML IDs, amounts, counts and totals | PASS |
| EX-3 | Streaming config applied; time window finalizes one file containing 20 transactions. | swarm-lifecycle: streaming export | Assert config and actual finalized output | OPEN |
| AU-1 | Orchestrator and Scenario Manager reject unauthenticated access. | auth-access: protected APIs | AuthReadAcceptanceIT: /api/swarms and canonical /api/templates, anonymous 401 and authenticated 200 | PASS |
| AU-2 | Capability, workspace/raw-config, CP schema/journal and network read surfaces reject unauthenticated access. | auth-access: additional APIs | AuthReadAcceptanceIT: nine additional protected read routes, anonymous 401 and authenticated 200 | PASS |
| AU-3 | Viewer has no runnable templates and cannot create swarm. | auth-access: viewer | ViewerAcceptanceIT: exact PocketHive VIEW, empty runnable list, CREATE403 and admin registry404 | PASS |
| AU-4 | Folder runner sees/runs only allowed scenarios, cannot run outside folder. | auth-access: scoped runner | ScopedRunnerAcceptanceIT: exact VIEW + RUN-folder grants; admin verifies fixtures, runner catalogue stays in scope, allowed CREATE succeeds, outside CREATE403; verified cleanup | PASS |
| AU-5 | Runner can read deployment-view capability/workspace/schema/journal/network endpoints. | auth-access: runner reads | ScopedRunnerAcceptanceIT.readsDeploymentViewApis: all six public deployment read endpoints200 with verified scoped runner | PASS |
| AU-6 | Viewer reads scenario list/detail/raw through ingress. | auth-access: Scenario Manager reads | ViewerAcceptanceIT: selected scenario in list, matching detail id and nonempty raw through ingress | PASS |
| AU-7 | Runtime materialization grants, folder write/delete grants and deployment-wide scenario create/delete grants are enforced. | auth-access: runtime/workspace/create | ScenarioMutationAuthorizationAcceptanceIT: deployed folder/scenario CRUD and denials on Rabbit/Artemis. ScenarioManagerAuthFilterTest: isolated owner-component runtime materialization, matching RUN and denied VIEW/outside RUN with filesystem postconditions. | PASS |
| AU-8 | Viewer reads shared network/SUT config and cannot write it. | auth-access: shared config | NetworkAccessAcceptanceIT: viewer GET200, same-content text PUT403, byte-for-byte unchanged raw for network profiles and SUT environments | PASS |
| AU-9 | Admin provisions bundle runner; profile/catalogue expose exact grant; only named bundle runs. | auth-access: bundle runner | BundleAuthorizationAcceptanceIT: provisions exact VIEW + RUN-bundle actor, verifies profile and sole runnable template, accepts owned CREATE, rejects sibling and outside-folder CREATE; Rabbit/Artemis; verified user revocation/deactivation | PASS |
| AU-10 | Folder ALL actor manages swarm; RUN-only actor cannot stop it. | auth-access: folder admin | FolderAuthorizationAcceptanceIT: independent RUN-bundle and ALL-folder actors, same runner-created RUNNING swarm, RUN STOP403 with unchanged state, folder START/STOP/REMOVE with canonical success; Rabbit/Artemis. Earlier scoped RUN-folder denial retained. | PASS |
| AU-11 | Folder admin denied deployment refresh/reset; deployment admin refresh accepted. | auth-access: deployment admin | DeploymentAuthorizationAcceptanceIT: folder refresh/reset403 preserve owned swarm/run/state; deployment refresh202 with explicit REFRESH receipt. No allowed RESET. Rabbit/Artemis. | PASS |
| AU-12 | Swarm-scoped manager/config/journal/pin/tap access, network conflict and deployment-only journal metadata grants hold. | auth-access: swarm admin | SwarmAuthorizationAcceptanceIT on Rabbit/Artemis: canonical CONFIG_UPDATE, journal/pin readback, metadata grants, tap read/close grants, missing-SUT network409 and verified cleanup. Pins remain as documented history. | PASS |
| AU-13 | Runner cannot change manual network override; viewer can read it. | auth-access: manual override | NetworkAccessAcceptanceIT: verified runner PUT403, viewer GET200, full manual override status unchanged | PASS |
| FW-1 | Assertion failure after create still removes exact owned swarm; cleanup failure remains visible. | New framework requirement | SwarmResourceTest and FailureCleanupAcceptanceIT passed (framework + Rabbit + Artemis); includes write failures at CREATE/START/STOP/REMOVE and combined test/cleanup/report failures | PASS |
| FW-2 | Wrong operation identity, terminal failure, timeout and missing configuration fail explicitly. | New framework requirement | Identity/failure/config and full-body HTTP checks plus OperationAwaiterBudgetTest: receipt/configured caps, remaining budget, bounded poll; TapResourceTest: missing samples timeout with closure. 93 framework tests pass. | PASS |

## Dispositions still required

- SW-1 CONTROL wire shape, physical bindings, target-state no-rebroadcast and exact-key
  replay now have named owner evidence in the SM-1/SW-1 section below. Deployed lifecycle
  evidence remains separate; no native broker client was added to acceptance-tests.
- Redis, TCP mapping/journal, ClickHouse and export inspection need supported fixture
  boundaries. This inventory does not approve backend-port access or orphan cleanup.
- Auth feature cases combine multiple endpoints. AU rows are checklists for those
  sub-assertions, not permission to replace a whole row with one status check.
- Existing green Cucumber reports are historical reference only. N3 requires current
  new evidence, including Rabbit and Artemis for common WORK behavior.

## Current execution evidence (N1 and first N2 slice)

2026-09-15: all 38 framework component tests passed, including full-body HTTP deadlines
and cancellation, request/receipt/operation identity, evidence-write failures and cleanup
failure reporting. Earlier three import-boundary cases passed; the current added test
introduces no production imports. Maven Enforcer passed with each runner invocation.

| Deployed case | Rabbit WORK | Artemis WORK |
| --- | --- | --- |
| HttpLifecycleAcceptanceIT | PASS, 16.95 s | PASS, 15.66 s |
| FailureCleanupAcceptanceIT | PASS, 14.59 s | PASS, 14.84 s |
| TargetStateLifecycleAcceptanceIT | PASS, 18.56 s | PASS, 17.78 s |

All six runs used public ingress. Every REMOVE succeeded with no remaining resources
or errors and a verified registry 404: 15 removed resources per Rabbit swarm, 16 per
Artemis swarm. These counts describe the observed artifacts, not framework expectations.
The final public swarm list was empty. Three pre-existing exited legacy containers were
not removed; restarting Orchestrator cleared their stale in-memory registry entries.
The local deployment was restored to ARTEMIS after the explicit Rabbit test switch.

Logs: `/tmp/acceptance-n1-rabbit.log` (first two cases),
`/tmp/acceptance-n2-rabbit-target-state.log` (new case),
`/tmp/acceptance-n1-n2-artemis.log` (all three cases).

Evidence directories under `acceptance-tests/target/runs/`:

| Case / adapter | Directory |
| --- | --- |
| HTTP / Rabbit | `http-lifecycle-3dc28ee4-12f2-4c14-9199-9eb9a3287d30` |
| Failure cleanup / Rabbit | `failure-cleanup-f2a1e4d1-2b94-4f6f-84d8-2d2fdf0ef455` |
| Target state / Rabbit | `target-state-lifecycle-93a26588-ba84-44a0-a6cc-d2ba415edf91` |
| HTTP / Artemis | `http-lifecycle-b668a63b-afea-4424-987a-0994349c78e0` |
| Failure cleanup / Artemis | `failure-cleanup-8468d8e0-ab2e-4b7b-8dce-115dc55032b1` |
| Target state / Artemis | `target-state-lifecycle-cfa812ca-5b89-4a73-a766-49c547424219` |

The HTTP case observes the processor result, not terminal postprocessor throughput.
N1 execution is complete; remaining N2 rows, full replacement acceptance and legacy
removal remain open. The target-state test passed its separate review without new findings.

## Scenario authoring slice — 2026-09-16

SC-1/SC-2/SC-3 passed in three independent read-only tests via public ingress. New
fixture `acceptance-scenario-authoring` declares a numeric rate of 7.5, an exact
templating string and different history policies for four workers. These are authored
values, not tests of implicit defaults. No worker/swarm is launched; runtime history
behavior remains WK-1 and is not claimed here. No legacy implementation was reused.

The `scenarios` target needs only ingress, actor, request timeout, report directory
and scenarioId. TargetLoader remains the sole resolver; ApiRun shares authenticated
HTTP/evidence lifetime with lifecycle composition. 44 framework tests passed, including
strict target separation, auth rejection and HTTP close despite report failure.
The three existing import-boundary cases also passed.

Live log: `/tmp/acceptance-scenario-live.log` (3/3, 0.466 s). Evidence under
`acceptance-tests/target/runs/`:

- `scenario-rate-05508a6d-84df-49b7-a9d0-1845a82bd580`
- `scenario-templating-3abc81bc-fc89-423d-81b5-96f0819053a5`
- `scenario-history-7bb40c1d-bdb0-4094-a5bb-2a83b975fb35`

Scenario authoring passed separate review without findings; committed as `71205fcf`. Remaining N2 coverage and N3/N4 stay open.

Regression after API scope extraction: `local-artemis.properties lifecycle` passed
3/3. All REMOVE operations succeeded with 16 removed resources, zero remaining/errors
and verified 404. Log: `/tmp/acceptance-scenario-lifecycle-regression.log`. Artifact
directories under `acceptance-tests/target/runs/`:

- `target-state-lifecycle-d94751b8-01ee-461f-910d-527f2ffbc032`
- `http-lifecycle-7cb3e502-1943-42f1-b3a0-ee039b4cff85`
- `failure-cleanup-279c19ec-8e36-415d-bdae-87ae7d705fa6`

Rabbit was not re-run in this authoring slice; previous N1/N2 evidence remains above.

Controlled negative run: a temporary target selected a nonexistent scenario id.
All three scenario cases failed with HTTP 404, zero skips; runner exit code 1 is the
expected result. Log: `/tmp/acceptance-scenario-missing-fixture.log`. The successful run is recorded in `/tmp/acceptance-scenario-live.log` above; a later
review rerun also passed 44 framework and 3 scenario tests.


## Auth read slice — 2026-09-16

AU-1/AU-2 passed through ingress in `AuthReadAcceptanceIT` (13 parameterized cases).
Each case verifies anonymous 401 and authenticated 200 with local-admin. Nine AU-2
routes cover capabilities, workspace list, scenario raw, shared network/SUT raw,
control schema, hive journal, network bindings and proxies. AU-1 covers swarms and
the canonical Scenario Manager `/api/templates`; the frozen legacy test requested
`/templates`, which is not the current controller mapping. Additional scenario list
and detail reads are included. No viewer/runner grant claim is made (AU-3–AU-13 open).

Text endpoints explicitly request text/plain. The initial JSON-only client produced
406 for scenario raw and selected the JSON `/{id}` mapping (404) for shared raw paths.
PocketHiveHttp now supports an explicit Accept value through the same bounded request
implementation; a framework test reproduces rejection of JSON and acceptance of text.
No product behavior, security policy, stack configuration or legacy code changed.

Final log: `/tmp/acceptance-auth-read-final.log` — 45 framework tests and 13 deployed
cases passed, zero errors/failures/skips. Each evidence directory named in that log
contains anonymous-response.json and authenticated-response.json (no request tokens).
Import boundary log: `/tmp/acceptance-auth-import-boundaries.log` — 3 passed.
The auth-read slice passed separate review without findings and was committed as `2ce87f7a`; no new lifecycle run was needed for these
read-only endpoints. N2 remains open, and N3/N4 are unchanged.


## Viewer slice — 2026-09-16

AU-3/AU-6: ViewerAcceptanceIT verifies exact PocketHive deployment VIEW, visible
fixture in scenario list, matching detail id, nonempty raw, empty runnable templates,
and CREATE403 with registry404 read before/after by the explicit cleanup admin.
The supplied target uses local-viewer, local-admin and acceptance-http-artemis.
AuthApi reads the canonical profile DTO. Grants for other products are separate.
No user or policy changes are made. A rejected CREATE does not trigger removal.

SwarmResource accepts an explicit requester while retaining its observation/removal
API. Framework tests exercise unexpected accepted CREATE followed by assertion failure
and verified removal by the owner, plus rejected CREATE without removal. OperationLimits
is a read-only projection so these tests do not require capture settings; existing lifecycle
WaitLimits projects the same operation values. TargetLoader remains the single resolver.

Viewer execution: `/tmp/acceptance-viewer-final.log`, 49 framework tests and 3 deployed
cases passed, zero skips. Viewer slice passed separate review and was committed as `a133b554`.
N2 beyond these rows and N3/N4 remain open; scoped runner is next.

Lifecycle regression after the resource/limits change passed on Artemis (3/3):
`/tmp/acceptance-viewer-lifecycle-regression.log`. Each REMOVE was SUCCEEDED with
16 removed resources, zero remaining/errors and verified registry404. Three import
boundary cases passed: `/tmp/acceptance-viewer-import-boundaries.log`. Rabbit was
not rerun in this slice. Unexpected viewer acceptance cleanup was exercised with
framework HTTP fixtures; the deployed viewer correctly received403.


## Scoped runner slice — 2026-09-16

AU-4/AU-5 passed: local-runner has exactly PocketHive VIEW on deployment and RUN on
folder demo. New independent fixture demo/acceptance-runner-artemis is runnable;
acceptance/http-artemis is outside scope. Admin catalogue must contain both with
matching inside/outside folders; runner catalogue must contain the allowed fixture,
exclude the denied fixture and expose only its allowed folder subtree. Allowed CREATE
succeeds with matching runId; outside CREATE returns POST403 with admin registry404.
The admin's existing SwarmResource owns observation and verified removal. No account
or grant mutation, frozen legacy reuse, automatic adapter switch or new cleanup owner.

The second test checks capabilities, workspaces, CP schema, hive journal, network
bindings and proxies through ingress. Shared ActorAssertions compares exact PocketHive
grant sets (order-independent), identity and active state; viewer delegates to it.

`/tmp/acceptance-runner-final.log`: 53 framework tests, 2 runner tests passed (13.56 s).
Allowed swarm REMOVE SUCCEEDED,16 removed resources,0 remaining/errors,registry404;
evidence directory runner-cleanup-11915bb6-75f0-4d14-9075-5ef16f90acb0. Final public swarm
list was empty. `/tmp/acceptance-runner-viewer-regression.log`: viewer3/3 passed.
`/tmp/acceptance-runner-import-boundaries.log`: import checks3/3 passed. Zero skips.

Stack remained Artemis; only Scenario Manager reload was requested to load the new
fixture. This auth slice proves CREATE authorization, not worker START or processing;
RUN-only STOP denial remains AU-10. No Rabbit rerun. Scoped runner passed separate review and was committed as `5e9e80e5`.
Remaining N2 requirements and N3/N4 stay open. Actor provisioning requirements need a
supported cleanup contract: the current admin API lists/upserts users and replaces
grants but exposes no delete operation; do not silently retain generated users.


## Shared network authorization slice — 2026-09-16

AU-8/AU-13 passed through ingress: 2 raw config pairs and 1 manual override pair.
Viewer and runner identities/grants are explicit and checked using ActorAssertions.
PUT replays observed settings, requiring403 even when the value is unchanged. Raw
readback must match byte-for-byte; manual status must match as JSON including appliedAt.
This does not claim that a changed configuration was applied or that every possible
write payload was exercised. No admin writes, rollback, users or swarms are created.

NetworkAccessTarget contains no unrelated scenario/lifecycle/capture settings.
PocketHiveHttp adds explicit UTF-8 text bodies through its single bounded exchange;
existing JSON requests retain their encoding. Framework regression checks exact bytes,
headers, preserved403 and off-origin rejection. No wire DTO, service dependency or
second HTTP client was introduced. Shared raw fixture content must be nonempty.

`/tmp/acceptance-network-access.log`: 55 framework tests and3 deployed cases pass,
zero failures/errors/skips (live cases0.494 s). Each evidence directory records before,
write-denial and after, with actor profiles and no request tokens.
Import verification: `/tmp/acceptance-network-import-boundaries.log`.
New slice is uncommitted and awaits separate review. Remaining N2 and N3/N4 stay open.


### RUN-only STOP (AU-10 partial)

The third auth-runner case creates an independent allowed-folder swarm as RUN,
starts it as the explicit admin, requires STOP403 from RUN, and checks the same
run/workload intent/RUNNING state with no active operation. Admin STOP then succeeds
and normal resource cleanup verifies REMOVE SUCCEEDED and registry404.
SwarmResource owns both actors' command receipts;403 does not discard acquired
ownership. Framework regressions cover denied STOP followed by admin STOP/removal,
unexpected accepted STOP followed by assertion failure/cleanup, and unknown500
preventing blind removal.

Evidence: `/tmp/acceptance-runner-stop.log` — 58 framework + 3 deployed runner tests,
zero failures/errors/skips; `/tmp/acceptance-runner-stop-imports.log` — 3 import tests.
Both created swarms report REMOVE SUCCEEDED,16 removed/0 remaining/0 errors and404.
WORK was Artemis; no Rabbit rerun. New work awaits separate review.
AU-10 remains partial: folder ALL and isolated actor provisioning are not covered.
AU-8/AU-13 passed separate review and are committed as `5c9ea201`.


Review correction: restrict rejected-command clearing to STOP403. REMOVE403 no longer
causes close to reissue REMOVE; cleanup remains explicitly unverified. The new
rejectedExplicitRemovalIsNotRetriedByClose regression failed before the fix on an
unexpected second POST and passes after it. Evidence: `/tmp/acceptance-remove403-red.log`
and `/tmp/acceptance-remove403-fixed.log` (59 framework +3 deployed runner, no skips).
AU-10 remains partial; correction awaits separate review, no commit.


### Framework review corrections F1–F3 — 2026-09-16

ScenarioApi delegates ID validity to the product and uses shared path-segment encoding.
TapResource preserves the exact raw samples selected for assertions, including partial
results after timeout/decode failure. Explicit DebugTapService close now reports adapter
failure as HTTP 500; registry absence alone cannot turn that failure into successful cleanup.

`/tmp/acceptance-framework-fixes-focused.log`: scenario encoding 3/3, capture 5/5,
DebugTapService 5/5. `/tmp/acceptance-framework-fixes-lifecycle.log`: full framework 65/65
and deployed Artemis lifecycle 3/3, no skips. The adapter-close failure response is verified
through the real controller with MockMvc; the running stack was not rebuilt for that
product error path. Rabbit was not rerun. No additional coverage rows or N3/N4 are closed.


## Worker runtime WK-1/WK-2 — initial execution before identity repair

Two independent `workers` cases use new four-role fixtures for Rabbit and Artemis.
The history case compares all authored history policies with non-stale public worker
observations for the same run. The header case checks canonical processor status,
success and duration in the step, and absence of step-header keys from global headers.
Both use the existing swarm/tap ownership and removal verification. Shared HTTP
assertions now also match the producer instance to the processor reported by the API;
this strengthens the existing HTTP lifecycle case and currently exposes the same defect.

Artemis first execution: 66 framework tests pass; both deployed cases fail because
`ph.step.service=processor` is paired with the generator's `ph.step.instance`.
Log: `/tmp/acceptance-workers-artemis.log`. Both failed tests removed their swarms
successfully. History policy comparison itself passed for all four workers.

The saved payloads also reveal three retained steps although the processor's reported
policy is DISABLED. Source trace confirms two configuration paths: accepted raw config
reported in status versus startup PocketHiveWorkerProperties read during invocation.
Current source additionally confirms message headers override configured executing
identity. These are product defects, not adapter-specific naming problems or test
configuration defaults. Follow-ups are recorded under F02 in
`docs/inProgress/functional-module-boundaries.md`; product code remains unchanged.
No WK row or full replacement gate is declared passed by these failing tests.


Rabbit execution with aggregated assertions: 66 framework tests pass; both deployed
cases fail only on producing-instance identity. Successful HTTP response/body and step
header placement assertions execute despite that failure and pass. Log:
`/tmp/acceptance-workers-rabbit.log`. Both adapters retain three steps for the processor
configured DISABLED; this additional finding comes from saved canonical capture evidence
and the source trace, not a currently asserted step-count condition.

All four test swarms were removed with SUCCEEDED, no remaining resources/errors and
registry404: 20 removed resources per Artemis swarm, 18 per Rabbit swarm. These counts
are observed evidence, never fixture-specific cleanup rules. Evidence directories:

- Artemis: worker-history-c87a2d95-49ae-4ba3-a49a-84284411cde9,
  worker-headers-eef6d084-11ef-43fc-b0f6-b6f0cc1d1c42.
- Rabbit: worker-history-0350d283-ebcc-4a49-9ec3-843fb8573485,
  worker-headers-adb5f4da-3c0b-40af-82fb-ed4bea024145.

The local WorkPlane was temporarily switched through an external compose override,
then restored to the repository's Artemis configuration. Final public swarm list is
empty. No product code, checked-in deployment config or frozen legacy code changed.
The final assertion aggregation ran on Rabbit; Artemis above is the preceding run.
No full-reactor run or fresh import scan: dependency/import boundaries are unchanged.
This uncommitted slice awaits separate review and product defect resolution.


## Executing-worker identity repair — 2026-09-16

Current result supersedes the identity failures above: configured ControlPlaneIdentity
is the sole executing swarm/instance source in DefaultWorkerContextFactory; Spring
supplies workerControlPlaneIdentity. Header precedence and identity-less constructors
were removed. Existing step authors, origin headers and trace context are preserved.
History-policy resolution/retention is unchanged and explicitly deferred by the user.
WK-1 proves the authored/accepted-config projection plus real HTTP traffic, not retention.

Regression: `/tmp/worker-identity-before.log` reproduced 5 failures in 6 SDK cases;
`/tmp/worker-identity-after.log` passes 19 SDK/context/composition cases. Deployed logs
`/tmp/worker-identity-artemis.log` and `/tmp/worker-identity-rabbit.log` each pass 66
framework cases and 2 worker cases, 0 skips. Exact evidence under acceptance-tests/target/runs:

- Artemis: worker-history-571d84d9-debc-4325-a300-4aa4b9befa9c,
  worker-headers-ef03b091-0dba-447b-942a-95913965f784.
- Rabbit: worker-history-decc570d-18b0-4e4c-a6d9-cf3d09de5acd,
  worker-headers-135f1960-2fe7-4cf2-a934-370f6179f279.

All four REMOVE operations succeeded with empty remainingResources/errors and
registry404; removed-resource counts were 20/20 on Artemis and 18/18 on Rabbit.
The stack was rebuilt from current source, temporarily switched using an external
override, then restored to Artemis. Final public swarm list is empty.
The rebuild exposed documentation-publication issues: the coverage page is now in
the explicit Docusaurus include list and its literal `/{id}` path is marked as code.
Java/image packaging and the corrected UI build passed; no full-reactor test run.
Dependency/import boundaries are unchanged. Existing lifecycle was not separately
rerun; its shared producer assertion is exercised by these worker cases.
N3/N4 and the deferred history-policy finding remain open. No commit or review verdict.


## Removal of redundant history policy — 2026-09-16

User approved removal of DISABLED only. FULL and LATEST_ONLY retain their existing
behavior; configuration selection and the deferred scenario/runtime mismatch are
unchanged. Fixtures previously declaring DISABLED now explicitly declare LATEST_ONLY.
The historical execution evidence above predates this two-policy contract.

Verification: `/tmp/history-two-policies-tests.log` passes WorkItem/codec 13 and
framework 66; `/tmp/history-two-policies-sdk.log` passes 7 SDK/context/composition
cases. `/tmp/history-two-policies-authoring.log` passes 3 ScenarioReadAcceptanceIT
cases through ingress after scenario reload, including the two-policy four-role map
(evidence: scenario-history-2218eae0-01f3-4af5-9b89-adc84b3a9305). No skips.
No new worker-image rebuild or deployed worker/lifecycle rerun for this enum removal;
the earlier Rabbit/Artemis runtime evidence remains dated to the preceding slice.


## Scenario-selected runtime history — 2026-09-16

The user reopened the deferred wiring defect: runtime must honor scenario
config.historyPolicy. WorkerRuntimeConfiguration parses the complete merged candidate;
WorkerState stores the raw map and its parsed policy together. DefaultWorkerContextFactory
captures the accepted policy at invocation start. The separate service property and
bean/role policy resolver are removed. WorkItem FULL/LATEST_ONLY operations and transport
ACK behavior are unchanged. Unknown/nontext policy values are rejected before state
acceptance; omission preserves an existing policy through merge, and explicit worker
config reset restores the absent-field FULL default.

WorkerHistoryPolicyTest drives canonical CP commands through real runtime/state/context
and WorkerInvocation: actual retained steps, FULL/LATEST_ONLY updates, partial patch,
disable/re-enable, reset, in-progress invocation snapshot and rejection preserving
accepted raw/typed config, enablement and listener-visible state. The existing rejection
notification can repeat the previous snapshot; it never exposes the rejected candidate.
WorkerRuntimeConfigurationTest covers immutable raw/policy consistency and invalid
input at the parser boundary. The pre-fix regression produced 10 failures in 11 cases
(`/tmp/history-runtime-before.log`). Focused SDK checks passed 78 cases; the full selected
reactor passed worker-sdk 270, framework 66, Work API 21 and dependent module tests,
including RepositoryImportBoundaryTest 3 (`/tmp/history-runtime-modules.log`).

Full local rebuild succeeded with build-hive.sh --quick (`/tmp/history-runtime-build.log`).
The strengthened worker acceptance case asserts one retained processor result step at
index zero for the authored LATEST_ONLY policy. It does not calculate production retention
rules or resolve effective configuration. Both deployed workers cases passed with no skips
on each adapter (`/tmp/history-runtime-artemis.log`, `/tmp/history-runtime-rabbit.log`).
Evidence directories under acceptance-tests/target/runs:

- Artemis: worker-history-f5749c1c-12a2-4a61-945e-bfa24377f94a,
  worker-headers-4b68d1bb-9efc-4d4c-b46b-a2ef90d88452.
- Rabbit: worker-history-ee419bc3-50bb-47ad-9976-ac72a56ac9ba,
  worker-headers-0f02cfb8-4055-4815-838f-12fb92267130.

Each run captured three one-step results; all four REMOVE operations SUCCEEDED with
empty remainingResources/errors and verified registry404. Removed-resource counts
were 20/20 for Artemis and 18/18 for Rabbit, recorded evidence rather than test constants.
Rabbit was selected only by the external local override; the base Artemis stack was
restored afterwards. Final public swarm list is empty. This closes the scenario/runtime
history wiring defect; N3/N4 and the unrelated deferred service refactors remain open.
FULL and dynamic policy changes are proven by SDK component tests; the deployed slice
proves scenario-selected LATEST_ONLY. No full root-reactor test or separate review verdict
is claimed. No commit or push.


## Templating and selected variables WK-3/SC-4 — 2026-09-17

New `templating` group and explicit `acceptance-templating-rabbit` / `acceptance-templating-artemis`
fixtures use two independent profile cases, amber and violet. Every case creates its own
swarm, captures three distinct processor results, and verifies the last generator step
as canonical HttpRequestEnvelope alongside the successful HttpResultEnvelope. The test
checks exact rendered JSON (including string/int/bool values, +1 and conditional eval),
request headers, POST/path and executing generator/processor identities. The interceptor
produces the source field; the generator message template combines it with profile and
SUT variables. Expected values are fixed examples; no runtime expression evaluator or
variables resolver is reimplemented in acceptance code. This verifies generated request
content and successful processing, not an independent SUT-side body journal.

LiveRun accepts an explicit variablesProfileId through its existing canonical create
factory. TargetLoader and the HTTP, capture, evidence, operation and removal owners
are unchanged. The fixture uses the existing read-only WireMock mapping; tests neither
edit SUT state nor connect to backend management ports. The frozen legacy framework
and production implementation were not changed by this test slice.

Verification: 66 framework tests pass, and TemplatingAcceptanceIT passes 2/2 on Artemis
and 2/2 on Rabbit, zero failures/errors/skips. Logs: `/tmp/acceptance-templating-components.log`,
`/tmp/acceptance-templating-artemis.log`, `/tmp/acceptance-templating-rabbit.log`.
The local stack was rebuilt from current source with build-hive.sh --quick
(`/tmp/acceptance-templating-build.log`). Rabbit was selected by an external local compose
override only; the repository deployment configuration remains Artemis. The base
Artemis Orchestrator was restored and its public swarm list is empty
(`/tmp/acceptance-templating-restore-artemis.log`).

Evidence under acceptance-tests/target/runs:

- Artemis amber: templating-amber-6965018d-9e0b-46ae-995b-e3806c734461.
- Artemis violet: templating-violet-00d94d48-3e08-4135-b414-c9d423b59426.
- Rabbit amber: templating-amber-1a17105e-34b0-4a40-b9c9-ad818db2cecc.
- Rabbit violet: templating-violet-4bcfccb9-867a-4bf5-b1e6-c6dc63f66ac7.

All four REMOVE operations were SUCCEEDED with empty remainingResources/errors and
verified registry404. Recorded removed counts were 16 per Artemis run and 15 per Rabbit
run; these remain execution evidence, never adapter constants in the test. Each run
retains its fixture, expected profile/body, three actual samples and canonical operations.
No full root-reactor test or new import-boundary scan: no dependency/import permission
changed. Full/delta status, overrides, variables validation/multi-SUT matrix and N3/N4
are not claimed by this slice. Separate WK-3/SC-4 review on 2026-09-17 found no findings;
66 framework tests and persisted evidence rechecked, without repeating deployed E2E; no commit.


## Worker configuration and overrides WK-4/WK-5 — 2026-09-17

WorkerConfigurationAcceptanceIT adds two explicitly selected groups: worker-config
on the existing workers fixtures, and worker-overrides on independent four-worker
fixtures with their own SUT files. Both run unchanged on explicit Rabbit/Artemis targets.
The test compares authored fields with fresh observation.workers[].config, requires
current runId and instance membership in bees, and checks template/image/container/stack
metadata. Runtime-added defaults/addresses are not reconstructed. BaseUrl assertions
are concrete expected rendering examples. Each case additionally captures three real
successful HTTP results. This proves reported configuration and successful traffic;
it does not measure throughput, broker persistence or each tuning option's external effect.

WorkerObservations now owns the common bounded observation wait shared with WK-1;
its former local implementation is removed. No second config resolver, CP receiver,
wire model, operation-success calculator or cleanup owner is introduced.
WorkerStatusContractTest exercises each worker role through real WorkerControlPlaneRuntime,
ControlPlaneEmitter and ControlPlaneCodec: full includes accepted config/runtime,
delta omits config, next full still includes the accepted config. Controller metadata
is asserted at SwarmControllerStatusPublisherTest through its canonical codec. This
wire evidence is component-level; the API view merges statuses and cannot prove it alone.

Execution:

- Framework: 66/66, `/tmp/worker-config-framework.log` (also each runner invocation).
- WorkerStatusContractTest: 4/4; SwarmControllerStatusPublisherTest: 6/6,
  `/tmp/worker-config-component.log`.
- Artemis: baseline 1/1 and overrides 1/1, `/tmp/worker-config-artemis.log` and
  `/tmp/worker-overrides-artemis.log`.
- Rabbit: baseline 1/1 plus WK-1/WK-2 regression 2/2,
  `/tmp/worker-config-rabbit.log`; overrides 1/1, `/tmp/worker-overrides-rabbit.log`.
- No failures/errors/skips in the final runs. The initial override fixture lacked its
  bundle-local SUT and CREATE returned400; the fixture was completed before these runs.
  No product behavior changed to make the tests pass.

Run evidence under acceptance-tests/target/runs:

- Artemis baseline: worker-config-1ecddd21-36db-48f8-b33c-ec72b2efe22e.
- Artemis overrides: worker-overrides-de09957a-f584-4f7d-9c5e-95f36c439051.
- Rabbit baseline: worker-config-0861c873-893b-40ee-b275-d126c9e09161.
- Rabbit overrides: worker-overrides-4a165294-c356-40ca-95ab-e47b394f0c05.
- Rabbit history regression: worker-history-e8bae35e-0817-455f-95a0-d2736a655e2f.
- Rabbit header regression: worker-headers-041d1c07-4a2e-42ba-9138-46345702a14c.

All six runs retain three raw samples and CREATE/START/STOP/REMOVE SUCCEEDED evidence.
Each REMOVE has empty remainingResources/errors and the suite verifies registry404.
The stopped local stack was started with its existing current-source images; only
fixtures/tests/docs changed in this slice. The temporary Rabbit WORK override was
outside the repository; base Artemis was restored and the public swarm list is empty
(`/tmp/worker-config-restore-artemis.log`).

No full root reactor or import-boundary rerun: no dependency/import permission changed.
Production/legacy implementations are unchanged by this slice. Independent review is
pending, no commit. Networking, data/export, remaining authorization/lifecycle and N3/N4
remain open; this does not assert complete replacement of the frozen framework.


### WK-4/WK-5 review F1 fix — tap lifetime

WorkerConfigurationAcceptanceIT now closes its tap immediately after obtaining the
samples. Worker observation, assertions and STOP run after verified tap closure;
captured WorkItems remain available for comparison. No TTL increase, tolerated404,
product behavior or cleanup-owner change.

Verification: framework66/66 and its dependent module tests pass
(`/tmp/wk45-tap-fix-tests.log`). The review's expiry reproduction now passes both
baseline and overrides using the unchanged acceptance methods against a disposable
scripted ingress replaying recorded Rabbit evidence. Each of START, configuration
read and STOP is delayed2.4s with operation/request3s and tapTTL4s: tap closes at2.6s,
the full flow finishes at7.8s, assertions pass and swarm cleanup remains verified.
The probe also asserts DELETE occurs before configuration read and STOP, and GET
after DELETE returns404. Evidence: `/tmp/Wk45TapLifetimeProbe.java` and
`/tmp/wk45-tap-fix-repro.log`. These are isolated regression checks, not new deployed
E2E runs. The shared stack was not changed. No commit; fix awaits separate review.


## N2 execution evidence: HTTP proxy NW-1 (2026-09-17)

Previous worker/history/templating slices and the tap-lifetime correction are committed
as `a3cdf0d5`. NW-1 is a subsequent uncommitted slice ready for separate review.

- Framework: `./mvnw -pl acceptance-tests -am test` — 72/72 framework tests passed,
  dependent modules green. Six added cases cover explicit proxy target requirements,
  canonical binding decoding/encoded public path, rejection of 200/401/500 as absence,
  and bundle SUT read with no fallback after404. Log: `/tmp/nw1-framework-tests.log`.
- Artemis: `./run-acceptance-tests.sh acceptance-tests/targets/local-http-proxy-artemis.properties http-proxy`
  — 1/1 deployed case passed, log `/tmp/nw1-artemis.log`;
  run `acceptance-tests/target/runs/http-proxy-d96ac0b8-9ffc-43ce-9181-85d9fce264e3`.
- Rabbit: `./run-acceptance-tests.sh acceptance-tests/targets/local-http-proxy-rabbit.properties http-proxy`
  — 1/1 deployed case passed, log `/tmp/nw1-rabbit.log`;
  run `acceptance-tests/target/runs/http-proxy-93269ca2-3ccc-48a4-b479-e562f97a519e`.

Each run records the scenario and bundle SUT, the binding before/after lifecycle,
fresh worker configuration and three distinct raw WorkItems. Authored endpoint,
binding.clientBaseUrl, processor.config.baseUrl and HttpResultEnvelope.request.baseUrl
agree; actual request URL is `http://haproxy:18090/api/test`, with HTTP200 and the exact
expected response. Binding profile is explicitly passthrough, effective mode PROXIED,
and upstream matches the fixture. All eight CREATE/START/STOP/REMOVE operations
SUCCEEDED with matching owned identities; REMOVE had nonempty removedResources and
empty remainingResources/errors, followed by registry404 and explicit binding404.
The tap closed directly after capture, before configuration observation and STOP.

Local Artemis WORK was restored; public swarm list is empty. No product/legacy E2E
changes, native broker connections, proxy management-port checks or new dependencies.
The deletion claim is canonical API absence; native proxy-state verification belongs
to the product's owner. NW-2–NW-5 and other open rows/N3/N4 remain open. Next: NW-2
HTTPS through the selected proxy. Git diff whitespace check passed.


## N2 network extension — NW-2/NW-3/NW-5 (2026-09-17)

NW-1 received separate six-pass review with no findings (`/tmp/nw1-review.md`), fresh
72 framework tests and reread execution evidence before this extension. The new
extension remains uncommitted and requires its own review.

Framework: 78 tests pass, including Basic-auth ingress restriction/no credential
fallback, explicit journal run selection, required TCP mock configuration and total
tap lifetime, selected mock mapping, final negative-capture snapshot and read-failure
propagation. All dependent reactor modules passed. Existing production artifacts and
local stack were used; no product, security/compose or frozen E2E changes.

Final execution: five cases per WORK adapter, ten passed through localhost:8088.
For each group/adapter the command was:

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-<group>-<adapter>.properties <group>
```

| Group | Adapter | Evidence directory |
| --- | --- | --- |
| http-proxy | artemis | `acceptance-tests/target/runs/http-proxy-c6ba341e-8ecf-490a-a2d4-cbc24fe88ac4` |
| https-proxy | artemis | `acceptance-tests/target/runs/https-proxy-bb05e44e-d8cc-48f0-9249-5d64404dbcdd` |
| tcps-proxy | artemis | `acceptance-tests/target/runs/tcps-proxy-7118633b-d168-47e2-9fd0-597815a28d41` |
| tcp-delayed | artemis | `acceptance-tests/target/runs/tcp-delayed-bb0bb2ee-fb79-427e-bf5d-55f7b1a7d9e6` |
| tcp-timeout | artemis | `acceptance-tests/target/runs/tcp-timeout-2bf9fc86-2d2c-4b6b-9ac0-b295b04cf034` |
| http-proxy | rabbit | `acceptance-tests/target/runs/http-proxy-ae58c1e5-4ab9-4117-8a5c-9338ba2d17fd` |
| https-proxy | rabbit | `acceptance-tests/target/runs/https-proxy-bc49b126-d6d6-4943-9e86-e9270fb0d2a5` |
| tcps-proxy | rabbit | `acceptance-tests/target/runs/tcps-proxy-41cfd29a-706d-4628-89e2-2cffc3cea0e6` |
| tcp-delayed | rabbit | `acceptance-tests/target/runs/tcp-delayed-91e5cbed-232f-46f0-84e4-37ebd31ca6c0` |
| tcp-timeout | rabbit | `acceptance-tests/target/runs/tcp-timeout-664d17a6-5d80-4062-82b9-aabd0ab5cfa2` |

Logs: `/tmp/network-<group>-<adapter>-final.log`; audited artifact summary:
`/tmp/network-evidence-artemis-rabbit.json`. Ten owned swarms, 40 correlated operations
SUCCEEDED, 20 distinct successful result samples (18 proxy samples and two delayed
TCP controls). Every REMOVE had nonempty removedResources, empty remaining/errors,
and registry404. All six proxy runs also have explicit binding404.

NW-2 uses local HTTPS stub with explicitly authored sslVerify=false; both the result
scheme/URL and fresh runtime configuration must match. NW-3 sends an explicit
canonical tcp.request via the generator's SIMPLE body, receives the exact echo body,
and checks tcps scheme/endpoint and explicit TLS setting against the owner binding.
No test TCP clients, native broker access, endpoint resolver or new dependencies.

NW-5 uses the existing slow-response mapping, read-only through `/tcp-mock/api/mappings`
with explicit Basic credentials. Both fixtures use the same SUT, message and transport
settings except readTimeoutMs (8000/500). The control receives the expected body after
at least5000ms; the short-timeout case has the owned processor/runtime run's work-phase
runtime.exception, a messageId, and no output in the2s quiet window after the alert.
The published alert exposes the wrapper IllegalStateException/Processor request failed;
this test does not claim that nested SocketTimeoutException is present in the API.
No mapping mutation or shared mock-journal reset; no error WorkItem or ACK change.

WorkFixture is the renamed common immutable journey fixture (no HttpFixture alias).
ProxyAssertions compares source/owner observations; TargetLoader alone reads settings.
TcpMockApi and SwarmJournalApi only read their endpoint families; PocketHiveHttp owns
Basic/Bearer transport, deadlines and origin restriction. TapResource owns positive and
negative capture, and the timeout target reserves START + error wait + quiet window +
final read in TTL. Taps close before config wait and STOP; removal stays SwarmResource.

Artemis WORK restored; public swarm list empty. Matrix now23 PASS,3 PARTIAL,15 OPEN
(18 rows to finish). NW-4 remains OPEN: local shared-volume HAProxy is not the required
cross-node NFS topology. N3/N4 and all other open rows remain unchanged. Next sizeable
slice can cover remaining auth requirements; NW-4 still needs a dedicated NFS target.


## Network package review — 2026-09-17

No findings in the separate review of NW-1/NW-2/NW-3/NW-5. All six passes from
docs/REVIEW_RULES.md passed: scope/acceptance matches the plan; responsibility headers
and implementation units agree; common proxy comparisons and capture reuse existing
owners; HTTP credentials stay same-origin and out of evidence; only existing/JDK
libraries are used; positive/negative paths and cleanup remain explicit.

Owner evidence: TargetLoader alone parses target settings; PocketHiveHttp alone sends
bounded HTTP; ScenarioApi/NetworkBindingApi/TcpMockApi/SwarmJournalApi only read public
projections; TapResource owns capture lifetime; RuntimeErrorObservations compares the
owned processor/run alert without reconstructing CP state. Repository-wide source
search and call-path review retain SwarmNetworkBindingService as endpoint resolver,
NetworkBindingService as binding writer, WorkerControlPlaneRuntime/Alerts as error
producer, and SwarmResource/OperationAwaiter as the existing acceptance operation and
cleanup consumers. No alternate transport resolver or domain outcome owner was added.

Fresh dependent reactor and 78 framework tests pass (`/tmp/network-review-tests.log`);
all ten previously executed final E2E artifacts rechecked (`/tmp/network-review-evidence.json`),
including 40 SUCCEEDED operations and verified REMOVE postconditions. E2E was not rerun
for this source review. The documented wrapper-alert limitation remains; NW-4 and N3/N4
are not claimed. NW-4 awaits Swarm deployment: user has a one-host and a four-host
environment; cross-node shared-storage behavior requires the latter.

## Provisioned auth and wait budgets — 2026-09-17

AU-9, AU-10 and AU-11 now have independent acceptance cases on both WORK adapters.
Explicit targets select allowed bundle, sibling and outside-folder scenarios; no paths
are inferred from scenario IDs. Canonical auth-contracts DTOs carry user/grant data.
AuthAdminApi maps public endpoints; AuthUserResource owns only test-created identities.
The existing catalogue comparisons were extracted into CatalogueAssertions and the
scoped-runner suite uses that same read/assertion owner. AuthFixture composes existing
ApiRun, SwarmResource and OperationAwaiter; there is no authorization evaluator or
alternate lifecycle/cleanup implementation. Product/security code is unchanged.

Per adapter: 3/3 cases, 8 SUCCEEDED operations, 3 verified swarm removals, five 403
denials (two CREATE, STOP, refresh, reset). Every owned user has readback confirming
empty grants and inactive status, and a rejected401 login: four users per adapter.
The Auth API has no DELETE; these eight inactive records remain, deliberately reported
as deactivation rather than deletion. No configured users were modified.

Evidence logs: /tmp/auth-provisioned-artemis.log, /tmp/auth-provisioned-rabbit.log.
The artifact audit is /tmp/auth-evidence-summary.json. Principal evidence directories
under acceptance-tests/target/runs (other actor directories are linked by their logs):

- Artemis: bundle-admin-34cca566-b3a5-40c8-964a-3a121e61236c,
  folder-admin-c2997303-d5d2-4ad1-9954-2d7380babcd1,
  folder-manager-f84be6d6-d7c0-4e0d-a6fa-09a46fc635f7,
  deployment-auth-3a043318-8f70-4153-be50-e923d0e8f6bb.
- Rabbit: bundle-admin-5b0b1f0e-9115-40a5-ad0f-aa4ad8ac073a,
  folder-admin-a55cd0e3-6c4b-4698-9613-d58a7a084fac,
  folder-manager-fd462502-9037-41fe-a487-9f800959eef0,
  deployment-auth-1f10a363-aaa1-457f-9c4e-b75107305f81.

FW-2 adds actual delayed HTTP responses to prove receipt and configured wait caps,
remaining budget after a pending response/poll, and no extra read after deadline.
Missing capture samples time out and still close the tap. User-resource cases prove
partial acquisition/grant failure cleanup, collision/foreign identity protection,
revocation failure with continued deactivation, authoritative readback and evidence
errors. No new wait implementation was added.

Final framework93/93 and dependent reactor pass in /tmp/auth-runner-regression.log;
the existing auth-runner regression passes3/3 after restoring base Artemis.
Earlier framework execution is /tmp/auth-framework-final.log (91 tests before the
last two ownership cases). No full product reactor or product rebuild was needed.
All deployed requests use official ingress; local WORK switching used only a temporary
external override. New auth/FW-2 implementation awaits separate review; not committed.

Ledger after that slice: 27 PASS, 1 PARTIAL (SW-1), 13 OPEN. Subsequent AU-7/AU-12
evidence and the current count follow below; N3/N4 remain open.


## Scenario and swarm authorization — 2026-09-17

AU-7: two deployed cases per adapter verify scoped folder CREATE/DELETE, viewer DELETE403,
deployment-wide scenario CREATE/DELETE and folder actor403. Every created folder/scenario
has verified deletion through its public API. Runtime materialization uses explicit
owner-component evidence from ScenarioManagerAuthFilterTest: real filter/materializer,
mocked auth-profile lookup, isolated runtime root, denied VIEW and outside-folder RUN
with no filesystem effect, matching RUN with byte-identical scenario output. This is
not a deployed positive runtime call. Its API clears swarmRoot and has no independent
cleanup endpoint; the suite does not invoke it on a running swarm.

AU-12: one deployed case per adapter verifies two CONFIG_UPDATE results, scoped journal
access, pin/readback, deployment-only metadata with unchanged readback after403, tap
read/close permission separation, and409 when changing network without a bound SUT.
The two management swarms produced12 SUCCEEDED operations including verified REMOVE
with no remaining resources/errors. Tap close requires DELETE200 followed by GET404
in the shared owner; it is asserted by the passing test, not a separate stored receipt.
Twelve provisioned users have inactive/empty-grant readbacks and login401. Their records
remain because Auth has no DELETE. No product/security behavior changed.

Evidence directories under acceptance-tests/target/runs:

| Adapter | Folder mutations | Scenario mutations | Swarm management admin |
| --- | --- | --- | --- |
| artemis | `folder-mutations-447a8933-a60c-472d-b78b-8c4475b94340` | `scenario-mutations-a1983ce9-cd72-482e-b685-978cfce0d3ba` | `swarm-auth-admin-d668ba7e-11a5-4a9b-98ce-1809827a39ca` |
| rabbit | `folder-mutations-8b7bb94c-7aa9-4c5d-8464-73f39b91fdb6` | `scenario-mutations-1b154019-bc46-4149-8567-90c338b5119e` | `swarm-auth-admin-387e495f-7ae6-4fed-a84f-0e6ccdac80fa` |

Journal pin/metadata have no public delete/unpin API. Retained captures (recorded under
each manager's retained-journal-pin.json and verified by pinned-run-readback.json):

- artemis: `c38410f3-a4c6-436d-aa96-9c008ab7c012`, run `d056c88d-1045-47fa-a528-3d196eb4cc68`.

- rabbit: `ba9ab357-555a-48d7-8a1d-976e70c2d4ba`, run `4bebacc6-8388-4542-acf9-bb63e39ab0ba`.

Logs: `/tmp/auth-scenario-{artemis,rabbit}.log` and
`/tmp/auth-management-{artemis,rabbit}-final.log`. Artifact audit:
/tmp/auth-next-evidence-summary.json. The first Artemis management attempt failed in
its new assertion because CREATE targets Orchestrator; the corrected assertion uses
the controller target from START. That failed attempt's swarm and three users also
have verified cleanup; it created no pin. It is not counted as a passing execution.

Final framework107/107, Scenario Manager auth owner21/21 and dependent reactors pass.
RepositoryImportBoundaryTest3/3 passes in /tmp/auth-next-imports.log.
Lifecycle regression3/3 on each adapter verifies the shared TapSelection projection,
including six actual HTTP result samples,26 SUCCEEDED operations and six removals.
Logs `/tmp/auth-next-http-{rabbit,artemis}.log`; audit /tmp/auth-next-lifecycle-summary.json.
Base Artemis restored, final public swarm list empty. No remote Swarm deployment.
This implementation and the preceding auth package await separate review and commit.

Ledger after AU-7/AU-12: **29 PASS, 1 PARTIAL (SW-1), 11 OPEN**:12 rows remained.
The next SW-3 execution is recorded below. SM-2 still requires a dedicated fresh target.
DA-1..4 and EX-1..3 still need supported preparation/observation boundaries. NW-4 awaits
Swarm/NFS deployment. N3 replacement acceptance and N4 legacy removal remain open.


## Auth/FW-2 review follow-up — 2026-09-17

Separate review found two P2 cleanup defects: unconfirmed user creation was treated as
resolved by one absent readback; CONFIG_UPDATE403 prevented admin removal by retaining
a receiptless pending command. Both fixed through the existing resource owners, with
three failing regression invocations before the fix and all109 framework tests green
afterwards. No other finding or competing SSOT owner in the pending auth package.
The six passes and per-owner evidence are in /tmp/auth-review-report.md; execution logs
/tmp/auth-review-red.log and /tmp/auth-review-green.log. Prior deployed artifacts were
reread (/tmp/auth-review-evidence.json), not rerun for these error branches. Subsequent
SW-3 is a new implementation slice, not covered by this review. No commit requested.


## Scenario timeline — SW-3 (2026-09-17)

New independent Rabbit/Artemis bundles each author five timeline steps. The acceptance
test sends CREATE and initial START; the plan then enables workload, sets generator
rate2→7, pauses it, resumes it and stops the entire workload. There is no test-issued
STOP/config-update. Each phase has its own fresh state/worker snapshot with matching
current run and instance identities. Two short taps capture3 successful HTTP responses
each, before pause and after resume. This proves processing continuity, not a throughput
measurement of7/s. The exact run journal has all five completed steps in order, one
completed plan and no plan error, all from the current controller. Journal dispatch
completion is corroborated by actual worker effects rather than treated as their proof.

| Adapter | Evidence directory under acceptance-tests/target/runs | Operations | HTTP samples |
| --- | --- | --- | --- |
| artemis | `scenario-plan-6362f501-374f-427e-b32d-d001b6b40e6f` | 3 SUCCEEDED (CREATE/START/REMOVE) | 6 |
| rabbit | `scenario-plan-322ee953-0e39-4b82-9256-6193a988a93a` | 3 SUCCEEDED (CREATE/START/REMOVE) | 6 |

Logs: `/tmp/plan-{artemis,rabbit}.log`. Audited snapshots, ordered journal and removal
postconditions: /tmp/plan-evidence-summary.json. Both owned swarms were removed with
nonempty removedResources and empty remainingResources/errors, followed by registry404.
Each tap closed through the existing DELETE200/GET404 path before the next phase.
The shared WorkerObservations gained a phase predicate over its already verified
projection, retaining one Deadline; no new state merger, configuration parser or
lifecycle implementation. Existing worker-config regression passes1/1 per adapter in
`/tmp/plan-worker-regression-{artemis,rabbit}.log`. All109 framework tests and dependent
reactors pass. Base Artemis restored and final public swarm list empty. No product
behavior, public contracts, legacy E2E or deployment manifests changed.

Current ledger: **30 PASS, 1 PARTIAL (SW-1), 10 OPEN**:11 rows remain. SW-1/SM-1 owner
evidence and dedicated fresh SM-2 precede the remaining data/export boundaries and
NW-4 Swarm/NFS. New SW-3 code awaits its separate review; N3/N4 remain open. No commit.

## Platform smoke and lifecycle owner evidence — SM-1/SW-1 (2026-09-17)

SM-1 public smoke passed on the current Artemis WORK / Rabbit CONTROL deployment.
`PlatformSmokeAcceptanceIT.ingressReportsPlatformAvailability` records HTTP200/`ok`
for UI `/healthz` and HTTP200/`UP` for both service actuator health routes, all via
localhost:8088. No service port or broker connection is used by this suite.
Evidence: `platform-smoke-ad2c63bb-b037-4494-a8c3-73dfd0aba1e6` under
acceptance-tests/target/runs; log `/tmp/acceptance-smoke.log`. All110 framework tests pass.
This does not assert that the deployment is fresh or empty (SM-2).

The remaining SW-1 requirements now have these explicit component proofs, alongside
the previously recorded Rabbit/Artemis public lifecycle runs:

| Requirement | Owner test and observable evidence | Tests passed |
| --- | --- | --- |
| Rabbit physical bindings, idempotent ensure, repair, scoped removal; CONTROL connectivity | `SpringRabbitBrokerTest`: dedicated Testcontainers broker, actual UTF8 persistent delivery through a CONTROL transport binding; WORK message delivery, unbind/ensure/redelivery, verified resource absence and continued delivery for another swarm. No deployment backend access. | 2 |
| Rabbit declaration/observation/removal and transport failure semantics | `RabbitWorkTopologyTest`, `SpringRabbitResourcesTest`, `SpringRabbitTransportTest`, `SpringRabbitControlDeliveryTest`: mock-based owner contracts; CONTROL settlement policy independent of WORK tuning. These are not physical broker evidence. | 19 |
| Artemis physical resource and transport behavior | `ArtemisWorkPlaneTest`: real embedded broker, repeated ensure, canonical payload delivery, selected swarm removal with other swarm retained; ACK/error/stop/restart cases. `ArtemisTopologyTest`: owner address/configuration rules. | 12 |
| CONTROL wire/routing integrity | `ControlPlaneCodecTest`: all envelope families through canonical codec, invalid payload/routing rejected. `ControlPlaneEmitterTest`: actual executor evidence through codec. `ControlPlanePublisherIntegrationTest`: selected exchange/routes using mock RabbitPublisher; not a connectivity claim. | 25 |
| Exact-key replay and correlated completion | `OperationDispatchServiceTest.exactKeyReplayDoesNotDispatchAgainBeforeOrAfterCompletion`: same operation/correlation, one execution before and after terminal success; `SwarmOperationCoordinatorTest`: exact identity completion, timeout and conflicting-command cases. Other existing dispatch failure tests also pass. | 11 |
| Target-state command does not rebroadcast | `SwarmLifecycleCommandHandlerTest.confirmsAlreadyAchievedStateWithoutRebroadcastingCommand`: RUNNING/START and STOPPED/STOP return success without calling either lifecycle mutation; existing fresh-convergence cases pass. | 11 |
| Import boundaries | `RepositoryImportBoundaryTest`, unchanged ownership rules | 3 |

Owner logs: `/tmp/acceptance-rabbit-owner.log` (21 tests),
`/tmp/acceptance-sw1-owners.log` (62 tests). All pass with zero skips. The broker test
uses the Rabbit module's existing Spring BOM to resolve its test-only Testcontainers
dependency. Production behavior and deployment configuration are unchanged.

Current ledger: **32 PASS, 0 PARTIAL, 9 OPEN**. Remaining: SM-2, NW-4, DA-1..4, EX-1..3.
SM-2 needs a fresh dedicated deployment; NW-4 needs Swarm/NFS; DA/EX need supported
preparation/observation interfaces. N3/N4 remain open, legacy E2E remains frozen.
This implementation (and prior SW-3) awaits separate review; no commit or push.

## Data fixture boundary — prerequisite only (2026-09-17)

Redis Commander already has a public ingress route. New RedisCommanderApi uses
its exact-key HTTP operations with a required connection id; no native Redis client,
console command executor, first-connection selection or wildcard deletion.
RedisListResource creates UUID keys and checks absence before mutation; uncertain
writes and failed deletion postconditions remain visible. The existing acquisition
state and shared HTTP/evidence owners are reused. One-item list payloads remain opaque;
Redis Commander's UI-escaped item display is not treated as canonical application data.
DA-1/DA-2 will assert actual downstream WorkItems.

118 framework tests pass, including collision/no mutation, cleanup after assertion,
failed write with observable effect, unknown write with absent readback, delete200
with remaining key, wrong-key response and an already-consumed list. The shared
ScriptedIngress adds explicit plain-text replies for the real API's `ok` body.
`RedisFixtureAcceptanceIT` passes against public localhost:8088: creates two isolated
lists, deliberately fails after the second acquisition, proves that cleanup removes
only that key, then removes the first. Both absent artifacts are present under
`redis-fixture-8e60fe1e-cc2b-4f25-a6b8-7f11458e99e0` in acceptance-tests/target/runs.
Log: `/tmp/acceptance-redis-fixture.log`. No swarm was started or deployment changed.

DA-3 discovery: existing public `/grafana/api/ds/query` with the provisioned ClickHouse
datasource returned nested status200 and count0 for a unique nonexistent swarm.
Artifact: `/tmp/acceptance-grafana-boundary.json`. This is only an observation-boundary
probe; no outcome-write behavior is claimed. Auth/connection selection must be explicit
in its eventual acceptance target. No transaction API exists in Orchestrator/MCP,
but adding one is unnecessary for this supported Grafana read path.

EX-1..3 still lack public finalized-file/content observation; the current sink writes
worker-local files. No container filesystem or direct DB port access is authorized.
Ledger remains **32 PASS / 9 OPEN**, including all DA rows. This new slice awaits review.

### 2026-09-18 — DA-1/DA-2 dataset pipeline

Both local WORK adapters pass RedisDatasetAcceptanceIT through public ingress.
Each run verifies two distinct customer/account/amount/nonce payloads, both Redis-input
and Generator history steps, the canonical Request Builder request body and rendered
header, and Processor HTTP200/body with the owned worker identities. Preparation waits
for fresh STOPPED/disabled worker observations before opening the tap and seeding lists;
START follows verified seed readback. This avoids processing finite input during CREATE
and prevents consumers racing seed readback. No product behavior changed.

| WORK adapter | Evidence directory under acceptance-tests/target/runs | Operations | Samples |
|---|---|---:|---:|
| Artemis | `redis-dataset-24006cc9-7bbe-40ff-9ff8-ed8c37d12d78` | 4 SUCCEEDED | 2 |
| Rabbit | `redis-dataset-b07565de-46d3-48c8-b2b8-0bb00f540b60` | 4 SUCCEEDED | 2 |

Final logs: `/tmp/redis-data-artemis-final.log`, `/tmp/redis-data-rabbit-final.log`.
Artifact audit: `/tmp/redis-data-evidence-summary.json`. Each run verifies REMOVE with
nonempty removedResources and empty remainingResources/errors, registry absence,
scenario404 and both Redis keys absent. Framework119 tests and dependent reactor pass.
The local rebuild also exposed unescaped brace expressions in this page; formatting
was corrected and the documentation/UI build passed. Base Artemis restored after tests.

Current ledger: **34 PASS, 0 PARTIAL, 7 OPEN**: DA-3, DA-4, EX-1..3, SM-2, NW-4.
NW-4 stays last. DA-3 can next verify postprocessor outcomes through the existing
Grafana/ClickHouse ingress. Legacy E2E deletion remains gated by replacement coverage.
Implementation awaits separate review; no commit or push in this slice.

### 2026-09-18 — DA cleanup review fixes

Seed/readback now precedes tap creation after confirmed disabled workers. Dependent
scenario/list cleanup uses the existing SwarmResource acquisition state and retains
identifiers on unresolved swarm cleanup; seven HTTP regression cases cover that behavior.
Framework126 tests pass; Artemis E2E `redis-dataset-7230bcc5-d264-4967-a580-f933d5bb77c2` verifies the final successful path and
all dependency cleanup. Log `/tmp/redis-cleanup-artemis.log`. Rabbit was not rerun in
this correction slice. Coverage remains34 PASS/7 OPEN; separate review pending.

### 2026-09-18 — DA-3 persisted transaction outcomes

TxOutcomeAcceptanceIT passes on Rabbit and Artemis. Each fresh swarm first has no
stored rows; the configured CLICKHOUSE_V2 postprocessor then writes rows matching
two actual captured HTTP results by trace ID, swarm ID and sink instance. Call ID,
HTTP status200, processorSuccess1 and exact duration are verified against captured
WorkItem headers/HttpResultEnvelope. Existing worker observations confirm configured
sink mode. No postprocessor counter is treated as proof of persistence.

| WORK adapter | Evidence directory under acceptance-tests/target/runs | Matched traces | Observed rows | Operations |
|---|---|---:|---:|---:|
| Artemis | `tx-outcome-15b9075a-a7a2-416c-b550-d2977f70c190` | 2 | 3 | 4 SUCCEEDED |
| Rabbit | `tx-outcome-d50e94fd-e231-4f27-9eae-95ed419cbf7a` | 2 | 3 | 4 SUCCEEDED |

Logs: `/tmp/tx-outcome-artemis.log`, `/tmp/tx-outcome-rabbit.log`.
Artifact audit: `/tmp/tx-outcome-evidence-summary.json`. The fixture produces continuous
traffic so additional rows are expected; the two selected traces each have exactly one
matching row. Both swarm removals have complete canonical resource evidence and registry
absence. Telemetry remains under the existing ClickHouse table TTL; no database cleanup
or direct service-port test access. Base Artemis restored; public swarm registry empty.

Framework132 tests pass, including Grafana nested-query errors, malformed/foreign frames,
empty results, scoped query transport, integer preservation and required target settings.
No product service, public contract or deployment manifest changed. New API code maps
only a read-only projection of the existing storage schema; production projection and
write ownership remain in postprocessor. DA-3 implementation awaits separate review.

Current ledger: **35 PASS, 0 PARTIAL, 6 OPEN**: DA-4, EX-1..3, SM-2, NW-4.
Next local candidate is DA-4 (five-customer Redis/TCP WebAuth), using the existing Redis
fixture and public TCP observation boundaries. NW-4 remains last.

### 2026-09-18 — DA-4 five-customer Redis WebAuth loop

New independently authored fixtures verify five customers through customer RED ->
shared BAL -> shared TOP -> customer RED. A fresh nonce and exact XML bind journal
observations to each seeded customer/account/amount. Every customer's first four
requests must follow RED/BAL/TOP/RED and carry the expected TCP response. Captured
processor results additionally match the owned worker and rendered request. The
shared TCP journal and mappings are never mutated. One round-robin generator drives
all seven sources; weighted multi-generator scheduling is outside this row's scope.

| WORK adapter | Evidence directory under acceptance-tests/target/runs | Customers completing loop | Captured results | Cleanup |
|---|---|---:|---:|---|
| Artemis | `webauth-loop-86782769-3c4d-4c92-9c0a-b26b8b6f3ad3` | 5 | 5 | 4 SUCCEEDED operations, scenario404, 7 absent Redis keys |
| Rabbit | `webauth-loop-3c81b290-7471-488f-8520-51fa0553833e` | 5 | 5 | 4 SUCCEEDED operations, scenario404, 7 absent Redis keys |

Framework **141 tests pass**, including seven-dependency retention on failed CREATE /
REMOVE, all-close attempts with suppressed failures, producer-list reservation and
collision behavior, journal shape, explicit settings and customer sequence assertions.
Logs: `/tmp/da4-unit.log`, `/tmp/da4-artemis.log`, `/tmp/da4-rabbit.log`.
Artifact audit: `/tmp/da4-evidence-summary.json`.

RedisDatasetResources now accepts a collection of the same resource handles; it
still consumes SwarmResource's cleanup permission and owns no second lifecycle.
RedisListResource can reserve an absent producer-only key; its existing exact-key
cleanup verifies absence. No product code, public product contract or deployment
manifest changed. DA-4 changes await separate review and remain uncommitted.

Current ledger: **36 PASS, 0 PARTIAL, 5 OPEN**: EX-1..3, SM-2, NW-4.
Next local slice: export observation and EX-1..3. SM-2 needs a fresh dedicated
installation; NW-4 remains last on remote Swarm/NFS.

### 2026-09-18 — DA-4 review fix: observe the actual customer return key

Fixed the P2 observation gap: both request templates expose sourceList directly from
x-ph-redis-list. Expected keys come from owned Redis handles for customer RED, shared
BAL, shared TOP and the same customer's RED return. No production behavior changed.
A regression for all five customers first failed on missing source-key expectations,
then passed; replacing only the return source with another customer's key is rejected
while keeping customer/stage/payload/response intact. Four focused assertions tests and
all142 framework tests pass. Both deployed DA-4 runs verify exact source keys and
complete cleanup (four successful operations, scenario404, seven absent Redis keys).

- artemis: `webauth-loop-1f7782b4-be9b-49a1-83fb-6d647b8f0672`
- rabbit: `webauth-loop-744c3d9a-63f1-43be-8861-9a64a68a25d7`

Logs: `/tmp/da4-source-red.log`, `/tmp/da4-source-green.log`,
`/tmp/da4-source-artemis.log`, `/tmp/da4-source-rabbit.log`.
Artifact audit: `/tmp/da4-source-evidence-summary.json`.
Coverage remains36 PASS/5 OPEN. Correction remains uncommitted for separate review.

### EX-1 — actual clearing files, 2026-09-18

Reviewed product output isolation committed as `bba0bcb9`. New acceptance code reads
through RuntimeFilesystemLayout from an explicitly configured existing host-visible
runtime root. ExportFiles observes finalized UTF-8 content and pending names, performs
no writes/cleanup and introduces no layout resolver. Twenty owned single-item Redis
lists reuse existing acquisition and cleanup handles. Generator routes to Clearing
Export through the selected Work adapter; lifecycle and data preparation use ingress.

Both runs proved two text files of ten exact, distinct nonce-tagged records, expected
headers/trailers, no pending files, identical valid content before and after STOP.
Evidence is saved before REMOVE; four successful lifecycle operations and complete
resource cleanup are recorded per adapter.

| WORK | Evidence under acceptance-tests/target/runs | Result | Cleanup |
|---|---|---|---|
| Artemis | `clearing-export-07b17784-750f-446e-be49-c6786d9781c0` | 2 files,20 exact records | REMOVE succeeded,20 Redis keys absent,scenario404 |
| Rabbit | `clearing-export-efd1b85e-2644-4172-8abc-d84311eaec5c` | 2 files,20 exact records | REMOVE succeeded,20 Redis keys absent,scenario404 |

148 framework tests pass; focused import-boundary checks pass. Logs:
`/tmp/export-ex1-artemis.log`, `/tmp/export-ex1-rabbit.log`, `/tmp/export-ex1-tests.log`.
The initial CREATE rejection was a fixture separator declaration: actual newline was
considered blank by capability validation; explicit literal `\n` fixed the fixture.
No product behavior change was needed for acceptance.

Local base restored to Artemis WORK / Rabbit CONTROL. New EX-1 changes are uncommitted
and await separate review. Matrix: **37 PASS, 0 PARTIAL, 4 OPEN**: EX-2,EX-3,SM-2,NW-4.

### EX-2 — structured XML output, 2026-09-18

Reviewed EX-1 committed as `b7552ea8`. EX-2 reuses its lifecycle, resource handles and
format-neutral ExportFiles observer. ScenarioApi maps existing schema GET/PUT routes;
no new product API or service dependency. The owned scenario receives an independently
authored JSON clearing schema through ingress with read-back equality before CREATE.
Fresh stopped/disabled workers report structured mode, schema id/version/root and
batch size10 before twenty inputs are seeded.

Both adapters produced two XML files, each containing ten records. Standard XML
parsing verified exact twenty distinct IDs (including `<&>`), amounts1..20, marker,
record counts and per-file totals. Content was verified before and after STOP.

| WORK | Evidence under acceptance-tests/target/runs | Cleanup |
|---|---|---|
| Artemis | `clearing-export-xml-636c41d4-d06c-456b-a332-2d8e85f17488` | 4 SUCCEEDED operations,20 Redis keys absent,scenario404 |
| Rabbit | `clearing-export-xml-c9575f78-f9a6-468e-b035-38dcdd287c9b` | 4 SUCCEEDED operations,20 Redis keys absent,scenario404 |

151 framework tests pass. New negative assertions reject corrupt amounts/totals/counts
and duplicate/unknown IDs; API tests verify JSON is not double-encoded and rejection
is propagated. Focused import-boundary tests pass. Logs: `/tmp/export-ex2-unit.log`,
`/tmp/export-ex2-artemis.log`, `/tmp/export-ex2-rabbit.log`. EX-1 unit assertions also
passed after sharing the flow; deployed EX-1 was not repeated.
Local Artemis WORK restored, public swarm registry empty. EX-2 remains uncommitted
for separate review. Matrix: **38 PASS, 0 PARTIAL, 3 OPEN**: EX-3,SM-2,NW-4.
