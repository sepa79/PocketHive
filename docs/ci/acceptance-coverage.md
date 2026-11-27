# Acceptance replacement coverage

This checklist maps the behaviors retained from the retired Cucumber suite.
Use the [acceptance contracts](../architecture/acceptance-tests.md) and
[runner guide](../USAGE.md#independent-acceptance-framework) to select the current tests and
targets. Verify shared WORK behavior on RabbitMQ and Artemis. Test outputs
belong in the system that runs or tracks the tests.

| ID | Required observable behavior | Original feature or requirement |
| --- | --- | --- |
| SM-1 | Ingress reports platform availability; preserve CONTROL connectivity coverage through an explicit owner test. | deployment-smoke: services healthy |
| SM-2 | Fresh deployment has no implicit default swarm. | deployment-smoke: default swarm absent |
| SC-1 | Authored generator rate appears in retrieved template. | scenario-defaults: rate limit |
| SC-2 | Authored templating interceptor appears in template. | scenario-defaults: templating |
| SC-3 | Per-worker history policy survives template read. | history-policy: authoring |
| WK-1 | Runtime history policies match authoring and real traffic succeeds. | history-policy: runtime |
| WK-2 | Processor result headers belong to step history, not global headers. | workitem-headers |
| SW-1 | Create/start/process/stop/remove succeeds; expected workers present; canonical confirmations correlated; resource provisioning/removal has coverage. | swarm-lifecycle: golden path |
| SW-2 | Stop-before-start and repeated start after target state are accepted correctly. | swarm-lifecycle: idempotent target state |
| WK-3 | Templated generation produces expected processed response. | swarm-lifecycle: templated generator |
| NW-1 | HTTP reaches SUT through selected proxy; runtime config and binding match; binding removed. | swarm-lifecycle: HTTP proxy |
| NW-2 | HTTPS reaches SUT through selected proxy with matching runtime config; binding removed. | swarm-lifecycle: HTTPS proxy |
| NW-3 | TCPS reaches SUT through selected proxy with successful result; binding removed. | swarm-lifecycle: TCPS proxy |
| NW-4 | Valid binding applied; invalid candidate rejected without losing previous binding; explicit clear removes it. | swarm-lifecycle: HAProxy NFS |
| SC-4 | Scenario variables resolve into generated traffic/template rendering. | swarm-lifecycle: variables |
| NW-5 | Delayed TCP response produces processor timeout/error. | swarm-lifecycle: TCP timeout |
| WK-4 | Explicit runtime config matches each worker; full status includes config/runtime metadata, delta omits heavy config. | swarm-lifecycle: explicit defaults |
| WK-5 | Explicit overrides, including generator I/O, reach all workers. | swarm-lifecycle: overrides |
| DA-1 | Redis dataset flows through request builder and processor. | swarm-lifecycle: dataset traffic |
| DA-2 | Dataset values are fully rendered in requests/payloads. | swarm-lifecycle: dataset payloads |
| DA-3 | Enabling tx outcome sink writes matching swarm outcomes to ClickHouse. | swarm-lifecycle: tx outcomes |
| DA-4 | Five-customer WebAuth Redis fixture produces expected TCP activity. | swarm-lifecycle: WebAuth loop |
| SW-3 | Scenario plan drives intended lifecycle transitions. | swarm-lifecycle: plan demo |
| EX-1 | 20 transactions form two clearing files. | swarm-lifecycle: clearing export |
| EX-2 | Structured config applied; 20 transactions form two XML files. | swarm-lifecycle: structured export |
| EX-3 | Streaming config applied; time window finalizes one file containing 20 transactions. | swarm-lifecycle: streaming export |
| AU-1 | Orchestrator and Scenario Manager reject unauthenticated access. | auth-access: protected APIs |
| AU-2 | Capability, workspace/raw-config, CP schema/journal and network read surfaces reject unauthenticated access. | auth-access: additional APIs |
| AU-3 | Viewer has no runnable templates and cannot create swarm. | auth-access: viewer |
| AU-4 | Folder runner sees/runs only allowed scenarios, cannot run outside folder. | auth-access: scoped runner |
| AU-5 | Runner can read deployment-view capability/workspace/schema/journal/network endpoints. | auth-access: runner reads |
| AU-6 | Viewer reads scenario list/detail/raw through ingress. | auth-access: Scenario Manager reads |
| AU-7 | Runtime materialization grants, folder write/delete grants and deployment-wide scenario create/delete grants are enforced. | auth-access: runtime/workspace/create |
| AU-8 | Viewer reads shared network/SUT config and cannot write it. | auth-access: shared config |
| AU-9 | Admin provisions bundle runner; profile/catalogue expose exact grant; only named bundle runs. | auth-access: bundle runner |
| AU-10 | Folder ALL actor manages swarm; RUN-only actor cannot stop it. | auth-access: folder admin |
| AU-11 | Folder admin denied deployment refresh/reset; deployment admin refresh accepted. | auth-access: deployment admin |
| AU-12 | Swarm-scoped manager/config/journal/pin/tap access, network conflict and deployment-only journal metadata grants hold. | auth-access: swarm admin |
| AU-13 | Runner cannot change manual network override; viewer can read it. | auth-access: manual override |
| FW-1 | Assertion failure after create still removes exact owned swarm; cleanup failure remains visible. | New framework requirement |
| FW-2 | Wrong operation identity, terminal failure, timeout and missing configuration fail explicitly. | New framework requirement |
| WP-1 | Delayed Work results arrive no earlier than configured; ordinary traffic and ACK semantics remain; scheduled resources can be removed. | A5 transport extension |
