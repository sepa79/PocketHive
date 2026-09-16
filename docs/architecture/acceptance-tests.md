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
projection. `AcceptanceTarget` adds lifecycle limits and HTTP fixture; `ScenarioTarget`
adds only the authored scenario id. ViewerTarget adds the explicit cleanup actor,
scenario/SUT and operation limits. The calling suite explicitly selects `load` or
`loadScenario` or `loadViewer`; the resolver never infers a group from fixture names.
**Effect:** one explicit file supplies ingress, actor, time limits and named fixture;
missing, unknown or invalid settings fail before any side effects. Required key sets
are scoped to the selected target kind; common fields are parsed once. `selectedFile`
reads the explicit runner property. Paths resolve
relative to that file. **Must not:** consult legacy configuration, infer adapter,
or embed defaults in individual clients/tests.

## RESP-ACCEPTANCE-HTTP

**Owner:** `PocketHiveHttp` sends bounded requests to the selected ingress only;
`ApiSurface` owns the public service prefixes (`/orchestrator`, `/scenario-manager`,
`/auth-service`, `/network-proxy-manager`) and projects rooted service-relative links, including operationUrl.
`ApiResponse` holds HTTP status/body, `ApiException` identifies an unexpected status.
**Effect:** preserve expected denial responses as data, reject off-origin operation
links and redirects, propagate interruption and request failures. The HTTP budget
covers response headers and the complete body; timeout/interruption cancels the
in-flight exchange before returning control to resource cleanup. **Must not:**
infer domain success, log credentials, retry mutations or use direct backend ports.
Callers can explicitly select the Accept media type for text/raw endpoints; existing
JSON callers retain application/json. Both use the same bounded request implementation.

## RESP-ACCEPTANCE-API

**Owners:** `AuthApi` handles dev login and the canonical current-user profile; `SwarmApi` maps swarm REST requests/readbacks;
`ScenarioApi` reads the required scenario. These are distinct endpoint families.
**Effect:** use canonical auth/create/control/operation/state contracts and exact
public paths. `SwarmApi` also matches the returned idempotency key with the submitted
request. `ControlReceiptMismatchException` reports a mismatch and retains the
canonical receipt for bounded observation/cleanup; it is never a successful result.
**Must not:** decide convergence, own cleanup, create wire DTO copies,
or call old E2E clients. Dev login is explicit in the local target; no token fallback.

## RESP-ACCEPTANCE-OPERATIONS

**Owner:** `OperationAwaiter`; `Deadline` only provides a monotonic bounded wait budget.
**Effect:** follow the accepted operation, verify swarm/correlation/idempotency/type,
return its terminal result, fail promptly for terminal failure when success is required.
Request timeouts and polling fit the remaining budget. **Must not:** terminalize an
operation, infer success from worker status, retry commands or reset elapsed budgets
inside a wait. Late/foreign operation evidence cannot satisfy the requested operation.

## RESP-ACCEPTANCE-RESOURCES

**Owner:** `SwarmResource` retains an individual test's acquisition receipt and closes
that exact swarm through canonical remove. `AcquisitionState` describes only the
handle's ownership certainty, not the product lifecycle.
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
its bounded sample wait and close. **Effect:** use a logical target and decode each
sample with `WorkItemJsonCodec`; close before swarm removal. **Must not:** configure
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

## RESP-ACCEPTANCE-RUN

**Owner:** test-scoped `LiveRun` composes lifecycle clients and handles on `ApiRun`.
**Effect:** load a lifecycle target, require its scenario and supply fresh swarm/tap
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
