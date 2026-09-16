# Independent PocketHive acceptance tests

New Java 21 / JUnit 5 framework. No dependency on the frozen legacy E2E suite.
Runtime support lives in `src/main/java/io/pockethive/acceptance`; framework component
tests and deployed acceptance tests are separate under `src/test/java`.

- `config`: the only target-file resolver, with explicit settings and no ENV fallback.
- `api`: bounded JDK HTTP and focused clients of the official public API.
- `operations`: canonical operation identity, terminal results and wait budgets.
- `resources`: exact per-test ownership and verified cleanup through normal remove.
- `capture`: public debug taps and canonical WorkItem codec.
- `evidence`: per-test operation/capture artifacts. JUnit reports primary and suppressed cleanup errors.

See [usage](../docs/USAGE.md#independent-acceptance-framework) for commands and setup,
[responsibilities](../docs/architecture/acceptance-tests.md) for boundaries, and the
[coverage ledger](../docs/ci/acceptance-coverage.md) for remaining replacement requirements.

Lifecycle coverage: HTTP processing, failure-after-create cleanup and commands at their target state. The HTTP fixture
contains generator → processor → postprocessor; capture observes the processor result.
The test verifies processed HTTP responses, canonical lifecycle results and removal.
It does not claim to have measured postprocessor throughput or CP wire/binding coverage.
New fixtures are under `scenarios/acceptance`; target files select their exact IDs.
They use the existing read-only WireMock `/api/test` SUT mapping. Tests neither modify
that mapping nor contact a backend management port. Each lifecycle test creates a unique swarm.

The independent `scenarios` group uses `targets/local-scenarios.properties` and reads
`acceptance-scenario-authoring` through ingress: scheduler rate, templating content
and all per-worker history policies. It creates no swarm and requires no SUT or running
WORK broker. Its target has only common API settings and scenarioId; lifecycle settings
are rejected. `ApiRun` owns shared authenticated HTTP/evidence lifetime; `LiveRun`
adds lifecycle composition only.

Target selection does not change the stack's configured WorkPlane. The deployment must
already use the requested fixture's adapter. The framework neither switches brokers nor
restarts shared services. Plain Maven tests never run deployed acceptance tests; the new
runner selects deployed tests explicitly. The old suite remains untouched until N3.

The `auth-read` group uses the same scenario target shape. It checks 13 explicit
read routes: 401 without credentials and 200 for the selected actor (local-admin in
the supplied target). It requires the selected scenario, the public Scenario Manager,
Orchestrator and Network Proxy Manager APIs, and the Postgres-backed hive journal.
It performs no mutations and does not assert viewer/runner permissions. Each case
records the two API responses without request credentials or authentication responses.
