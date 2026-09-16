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
| SC-1 | Authored generator rate appears in retrieved template. | scenario-defaults: rate limit | Scenario API test with explicit fixture expectation | OPEN |
| SC-2 | Authored templating interceptor appears in template. | scenario-defaults: templating | Scenario API test | OPEN |
| SC-3 | Per-worker history policy survives template read. | history-policy: authoring | Scenario API test, all configured workers/policies | OPEN |
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
| AU-1 | Orchestrator and Scenario Manager reject unauthenticated access. | auth-access: protected APIs | Auth group | OPEN |
| AU-2 | Capability, workspace/raw-config, CP schema/journal and network read surfaces reject unauthenticated access. | auth-access: additional APIs | Auth endpoint matrix through ingress | OPEN |
| AU-3 | Viewer has no runnable templates and cannot create swarm. | auth-access: viewer | Auth group | OPEN |
| AU-4 | Folder runner sees/runs only allowed scenarios, cannot run outside folder. | auth-access: scoped runner | Auth fixture and shared new swarm cleanup | OPEN |
| AU-5 | Runner can read deployment-view capability/workspace/schema/journal/network endpoints. | auth-access: runner reads | Auth endpoint matrix | OPEN |
| AU-6 | Viewer reads scenario list/detail/raw through ingress. | auth-access: Scenario Manager reads | Auth endpoint matrix | OPEN |
| AU-7 | Runtime materialization grants, folder write/delete grants and deployment-wide scenario create/delete grants are enforced. | auth-access: runtime/workspace/create | Independent actors and cleanup of all created artifacts | OPEN |
| AU-8 | Viewer reads shared network/SUT config and cannot write it. | auth-access: shared config | Auth group; rejection before mutation | OPEN |
| AU-9 | Admin provisions bundle runner; profile/catalogue expose exact grant; only named bundle runs. | auth-access: bundle runner | New user fixture with cleanup | OPEN |
| AU-10 | Folder ALL actor manages swarm; RUN-only actor cannot stop it. | auth-access: folder admin | Self-contained actor provisioning; no previous-test dependency | OPEN |
| AU-11 | Folder admin denied deployment refresh/reset; deployment admin refresh accepted. | auth-access: deployment admin | API authorization test; no new reset behavior | OPEN |
| AU-12 | Swarm-scoped manager/config/journal/pin/tap access, network conflict and deployment-only journal metadata grants hold. | auth-access: swarm admin | Endpoint matrix includes tap read/close denial and allowed close; canonical operations | OPEN |
| AU-13 | Runner cannot change manual network override; viewer can read it. | auth-access: manual override | Auth/network API matrix | OPEN |
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
removal remain open. The new target-state test awaits a separate review.
