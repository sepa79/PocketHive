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
| SM-1 | Ingress reports platform availability; preserve CONTROL connectivity coverage through an explicit owner test. | deployment-smoke: services healthy | Smoke group; public health and a named CP component test | OPEN |
| SM-2 | Fresh deployment has no implicit default swarm. | deployment-smoke: default swarm absent | Smoke on explicitly fresh dedicated target; do not assume all targets empty | OPEN |
| SC-1 | Authored generator rate appears in retrieved template. | scenario-defaults: rate limit | ScenarioReadAcceptanceIT.preservesAuthoredSchedulerRate: explicit numeric 7.5 through ingress | PASS |
| SC-2 | Authored templating interceptor appears in template. | scenario-defaults: templating | ScenarioReadAcceptanceIT.preservesTemplatingConfiguration: full authored templating object through ingress | PASS |
| SC-3 | Per-worker history policy survives template read. | history-policy: authoring | ScenarioReadAcceptanceIT.preservesEveryWorkersHistoryPolicy: exact four-role map with FULL/LATEST_ONLY/DISABLED through ingress | PASS |
| WK-1 | Runtime history policies match authoring and real traffic succeeds. | history-policy: runtime | Worker configuration + message tests | OPEN |
| WK-2 | Processor result headers belong to step history, not global headers. | workitem-headers | Canonical WorkItem assertions after processing | OPEN |
| SW-1 | Create/start/process/stop/remove succeeds; expected workers present; canonical confirmations correlated; resource provisioning/removal has coverage. | swarm-lifecycle: golden path | HttpLifecycleAcceptanceIT passed on Rabbit and Artemis, including expected worker roles; topology/CP details remain open | PARTIAL |
| SW-2 | Stop-before-start and repeated start after target state are accepted correctly. | swarm-lifecycle: idempotent target state | TargetStateLifecycleAcceptanceIT passed on Rabbit and Artemis; STOP before START, repeated STOP/START, distinct request keys and operation IDs, same run and expected states. Exact-key replay is not asserted. | PASS |
| WK-3 | Templated generation produces expected processed response. | swarm-lifecycle: templated generator | New templating fixture | OPEN |
| NW-1 | HTTP reaches SUT through selected proxy; runtime config and binding match; binding removed. | swarm-lifecycle: HTTP proxy | Network group; proxy fixture | OPEN |
| NW-2 | HTTPS reaches SUT through selected proxy with matching runtime config; binding removed. | swarm-lifecycle: HTTPS proxy | Network group; TLS fixture | OPEN |
| NW-3 | TCPS reaches SUT through selected proxy with successful result; binding removed. | swarm-lifecycle: TCPS proxy | Network group; TCPS fixture | OPEN |
| NW-4 | Valid binding applied; invalid candidate rejected without losing previous binding; explicit clear removes it. | swarm-lifecycle: HAProxy NFS | Dedicated network acceptance target supporting NFS topology | OPEN |
| SC-4 | Scenario variables resolve into generated traffic/template rendering. | swarm-lifecycle: variables | New variables fixture and WorkItem assertions | OPEN |
| NW-5 | Delayed TCP response produces processor timeout/error. | swarm-lifecycle: TCP timeout | Supported isolated TCP setup/observation interface must be specified | OPEN |
| WK-4 | Explicit runtime config matches each worker; full status includes config/runtime metadata, delta omits heavy config. | swarm-lifecycle: explicit defaults | API worker config assertions + canonical CP contract coverage; preserve both | OPEN |
| WK-5 | Explicit overrides, including generator I/O, reach all workers. | swarm-lifecycle: overrides | New override fixture; worker configuration API evidence | OPEN |
| DA-1 | Redis dataset flows through request builder and processor. | swarm-lifecycle: dataset traffic | Supported Redis fixture setup/observation boundary required | OPEN |
| DA-2 | Dataset values are fully rendered in requests/payloads. | swarm-lifecycle: dataset payloads | Same boundary; assert values, not merely message arrival | OPEN |
| DA-3 | Enabling tx outcome sink writes matching swarm outcomes to ClickHouse. | swarm-lifecycle: tx outcomes | Supported ClickHouse observation required; errors must fail test | OPEN |
| DA-4 | Five-customer WebAuth Redis fixture produces expected TCP activity. | swarm-lifecycle: WebAuth loop | Isolated Redis/TCP data and supported interfaces | OPEN |
| SW-3 | Scenario plan drives intended lifecycle transitions. | swarm-lifecycle: plan demo | Scenario plan fixture and fresh operation/state evidence | OPEN |
| EX-1 | 20 transactions form two clearing files. | swarm-lifecycle: clearing export | Supported file/content observation interface required | OPEN |
| EX-2 | Structured config applied; 20 transactions form two XML files. | swarm-lifecycle: structured export | Assert config, file content/type/count | OPEN |
| EX-3 | Streaming config applied; time window finalizes one file containing 20 transactions. | swarm-lifecycle: streaming export | Assert config and actual finalized output | OPEN |
| AU-1 | Orchestrator and Scenario Manager reject unauthenticated access. | auth-access: protected APIs | AuthReadAcceptanceIT: /api/swarms and canonical /api/templates, anonymous 401 and authenticated 200 | PASS |
| AU-2 | Capability, workspace/raw-config, CP schema/journal and network read surfaces reject unauthenticated access. | auth-access: additional APIs | AuthReadAcceptanceIT: nine additional protected read routes, anonymous 401 and authenticated 200 | PASS |
| AU-3 | Viewer has no runnable templates and cannot create swarm. | auth-access: viewer | ViewerAcceptanceIT: exact PocketHive VIEW, empty runnable list, CREATE403 and admin registry404 | PASS |
| AU-4 | Folder runner sees/runs only allowed scenarios, cannot run outside folder. | auth-access: scoped runner | ScopedRunnerAcceptanceIT: exact VIEW + RUN-folder grants; admin verifies fixtures, runner catalogue stays in scope, allowed CREATE succeeds, outside CREATE403; verified cleanup | PASS |
| AU-5 | Runner can read deployment-view capability/workspace/schema/journal/network endpoints. | auth-access: runner reads | ScopedRunnerAcceptanceIT.readsDeploymentViewApis: all six public deployment read endpoints200 with verified scoped runner | PASS |
| AU-6 | Viewer reads scenario list/detail/raw through ingress. | auth-access: Scenario Manager reads | ViewerAcceptanceIT: selected scenario in list, matching detail id and nonempty raw through ingress | PASS |
| AU-7 | Runtime materialization grants, folder write/delete grants and deployment-wide scenario create/delete grants are enforced. | auth-access: runtime/workspace/create | Independent actors and cleanup of all created artifacts | OPEN |
| AU-8 | Viewer reads shared network/SUT config and cannot write it. | auth-access: shared config | NetworkAccessAcceptanceIT: viewer GET200, same-content text PUT403, byte-for-byte unchanged raw for network profiles and SUT environments | PASS |
| AU-9 | Admin provisions bundle runner; profile/catalogue expose exact grant; only named bundle runs. | auth-access: bundle runner | New user fixture with cleanup | OPEN |
| AU-10 | Folder ALL actor manages swarm; RUN-only actor cannot stop it. | auth-access: folder admin | Self-contained actor provisioning; no previous-test dependency | PARTIAL — RUN-only STOP denial passes; folder ALL remains OPEN |
| AU-11 | Folder admin denied deployment refresh/reset; deployment admin refresh accepted. | auth-access: deployment admin | API authorization test; no new reset behavior | OPEN |
| AU-12 | Swarm-scoped manager/config/journal/pin/tap access, network conflict and deployment-only journal metadata grants hold. | auth-access: swarm admin | Endpoint matrix includes tap read/close denial and allowed close; canonical operations | OPEN |
| AU-13 | Runner cannot change manual network override; viewer can read it. | auth-access: manual override | NetworkAccessAcceptanceIT: verified runner PUT403, viewer GET200, full manual override status unchanged | PASS |
| FW-1 | Assertion failure after create still removes exact owned swarm; cleanup failure remains visible. | New framework requirement | SwarmResourceTest and FailureCleanupAcceptanceIT passed (framework + Rabbit + Artemis); includes write failures at CREATE/START/STOP/REMOVE and combined test/cleanup/report failures | PASS |
| FW-2 | Wrong operation identity, terminal failure, timeout and missing configuration fail explicitly. | New framework requirement | Component request/receipt/operation identity, failure/config and full-body HTTP timeout/interruption tests passed; complete wait-budget matrix remains open | PARTIAL |

## Dispositions still required

- Public API alone does not prove CP wire shape, no re-broadcast at target state or physical bindings. Identify specific
  canonical owner tests; do not drop this coverage under the neutrality label.
- Exact-key lifecycle replay still needs named owner/integration evidence; the new target-state
  test intentionally issues new keys and proves those requests receive distinct operations.
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
406 for scenario raw and selected the JSON /{id} mapping (404) for shared raw paths.
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
