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


The `auth-viewer` group uses `targets/local-viewer.properties`. It verifies the exact
PocketHive VIEW grant, scenario list/detail/raw access, an empty runnable-template
list and CREATE403 followed by admin registry404. The target explicitly supplies
viewer and cleanup usernames, scenario/SUT and operation limits; it requires no tap
or capture settings. The supplied fixture is Artemis. If a regression accepts CREATE,
SwarmResource observes and removes the owned swarm using the cleanup actor.
It never switches credentials automatically or recreates users. Other products' grants
are outside the PocketHive grant assertion. Both actors' profiles are recorded without tokens.


`auth-runner` uses `targets/local-runner.properties`: local-runner has deployment VIEW
and RUN on demo; local-admin owns observation/cleanup. The suite requires both named
scenarios in the admin runnable catalogue, checks their folders, filters the runner's
catalogue, creates the allowed fixture and rejects the outside-folder fixture. Existing
SwarmResource verifies removal. Another case checks six deployment read APIs.
The new demo/acceptance-runner-artemis fixture must be loaded by Scenario Manager;
the stack must already use Artemis. No accounts/grants are changed and no legacy
fixtures are used. A third case creates its own swarm, starts it as admin, verifies
RUN-only STOP403 with unchanged RUNNING state, then verifies admin STOP and removal.
It does not assert traffic or folder ALL access. PocketHive grant assertions are shared
with viewer tests.


`auth-network` uses `targets/local-network-access.properties`; only ingress, actor
settings, request timeout and evidence directory are required. The two shared raw
configuration files must contain replayable content. Viewer reads and attempts to PUT
that same content; runner attempts to PUT the observed manual override settings.
All three writes must return403 and readback must remain unchanged. Manual override
appliedAt is response-only and is omitted from PUT. Exact PocketHive grants are checked.
These tests verify authorization even for a same-value write, not changed-setting
application or network effects. They create no swarms, users or broker resources.
