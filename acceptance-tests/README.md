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


The `workers` group uses `targets/local-workers-artemis.properties` or
`targets/local-workers-rabbit.properties` on a stack already selecting that adapter.
The new four-worker fixtures exercise FULL and LATEST_ONLY policies.
Each case creates its own swarm and captures real HTTP results. The history case
compares authored policies with fresh, instance-identified runtime observations of
all workers in the current run and checks that the processor's LATEST_ONLY result actually
contains one step reindexed to zero. The header case asserts processor status/success/duration
in the current step and absence of processor step-header keys from global headers.
These cover scenario-selected LATEST_ONLY retention, configuration/traffic and header placement;
they do not claim postprocessor throughput or CONTROL full/delta wire-shape coverage.
Cleanup remains the same explicit tap close and verified swarm REMOVE as lifecycle.


The `templating` group uses `targets/local-templating-artemis.properties` or
`targets/local-templating-rabbit.properties`. Two independent cases explicitly select
the amber/violet variables profiles in the matching new bundle. Each captures three
processed results with FULL history and verifies the generated canonical HTTP request:
exact interceptor-rendered JSON, numeric/boolean eval results, SUT-scoped customer and
rendered request headers, alongside the successful processor response and identities.
No template evaluator or variable resolver lives in the test framework. The existing
read-only SUT mapping and the same tap/swarm cleanup owners are used.


`worker-config` reuses the `local-workers-{artemis,rabbit}.properties` targets for
WK-4 baseline configuration. `worker-overrides` uses
`local-worker-overrides-{artemis,rabbit}.properties` for WK-5. Select the group and
target explicitly on a stack already using the matching adapter. Each test compares
all four workers' authored fields, including generator scheduler and adapter tuning,
against fresh runtime configuration for the exact current instances/run. Runtime-only
settings remain owned by the product. Concrete expected processor URLs test rendering;
no template or topology resolver lives here. Both cases capture three successful HTTP
results and require the existing tap close, verified REMOVE and registry404.

Full/delta CP shape is covered at its producers by WorkerStatusContractTest in
worker-sdk (real emitter and canonical codec) and SwarmControllerStatusPublisherTest
(controller metadata). Public worker state is a merged read projection, so the ingress
suite does not claim to distinguish the CP messages that produced it.


HTTP through the managed proxy (NW-1), with explicit scenario-local SUT and profile:

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-http-proxy-artemis.properties http-proxy
./run-acceptance-tests.sh acceptance-tests/targets/local-http-proxy-rabbit.properties http-proxy
```

Select the target matching the running WORK adapter. These cases use the dedicated
`acceptance-http-proxy` SUT, compare authored/binding/runtime/result addresses, and
verify binding404 after canonical swarm REMOVE. They reuse existing lifecycle and
tap cleanup; no direct proxy administration or broker connection is involved.


Network extension (NW-2/NW-3/NW-5), each on the explicitly selected WORK adapter:

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-https-proxy-artemis.properties https-proxy
./run-acceptance-tests.sh acceptance-tests/targets/local-tcps-proxy-artemis.properties tcps-proxy
./run-acceptance-tests.sh acceptance-tests/targets/local-tcp-delayed-artemis.properties tcp-delayed
./run-acceptance-tests.sh acceptance-tests/targets/local-tcp-timeout-artemis.properties tcp-timeout
```

Equivalent `-rabbit.properties` files select independent Rabbit fixtures. Proxy cases
verify TLS scheme, runtime settings, real responses and binding cleanup. NW-5 requires
both the successful delayed-response control and the shorter-timeout error case.
The timeout targets explicitly select the existing slow-response mapping and local
TCP mock Basic credentials. Reads use `/tcp-mock/` at ingress; mappings and journals
are never reset or rewritten. Errors are read from the owned swarm/run journal;
the processor output tap stays empty for the explicit quiet window after the error.


`auth-provisioned` uses `targets/local-auth-provisioned-{artemis,rabbit}.properties`
with an existing Auth administrator who also has deployment ALL. It independently
provisions unique bundle RUN and folder ALL actors, checks profile/catalogue/CREATE
scope, RUN-only STOP denial, folder lifecycle management, and deployment refresh/reset
authorization. Only deployment refresh is allowed; successful RESET is never invoked.
The target explicitly names one allowed bundle/scenario, a same-folder sibling and
an outside-folder fixture. These fixtures must exist in the admin catalogue.

Each test revokes and deactivates its users, verifies stored inactive/empty-grant state
and requires login401. Auth has no user-delete API: inactive records remain in its
store until normal environment reset. Existing users are never edited. Swarm cleanup
uses the existing verified REMOVE. All calls use public ingress and no raw tokens
are recorded. Separate groups below cover scenario workspace mutations and the
swarm management endpoint matrix (AU-7/AU-12).


`auth-scenario-mutations` uses the same explicit `local-auth-provisioned-{adapter}`
targets. Two cases create/delete their own folders and scenarios, verify scoped
denials and read back absence after removal. Scenario content is cloned from the
selected API projection under a new ID; repository fixtures are never edited.

`auth-swarm-management` uses `local-auth-management-{adapter}.properties` and the
matching new management bundle. Three provisioned actors exercise manager/controller
configuration, journal/pin, deployment-only metadata, tap read/close authorization,
and the missing-SUT network conflict. Both configuration commands use the existing
operation owner. The fixture explicitly supports CREATE without a bound SUT; no
network resolver, broker client or alternative cleanup is introduced.

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-auth-provisioned-artemis.properties auth-scenario-mutations
./run-acceptance-tests.sh acceptance-tests/targets/local-auth-management-artemis.properties auth-swarm-management
```

Use the corresponding `-rabbit.properties` targets when the deployment selects Rabbit.
All users are revoked/deactivated; folders, scenarios, taps and swarms have verified
cleanup. Journal pins and metadata intentionally remain as historical records because
there is no public delete/unpin API; evidence records the retained capture ID.
Runtime materialization authorization is covered separately by
ScenarioManagerAuthFilterTest with an isolated temporary root. The runtime endpoint
clears a swarm's startup directory and has no independent cleanup API, so these
acceptance groups do not invoke it on a running swarm or claim deployed runtime coverage.


`scenario-plan` uses `local-plan-{artemis,rabbit}.properties`. New independent bundles
schedule workload enablement, generator rate2→7, generator pause/resume and final workload
stop. The test sends only CREATE, initial START and cleanup REMOVE. Fresh worker snapshots
prove each phase; two short taps prove HTTP processing before pause and after resume.
The exact owned swarm/run journal must contain the five ordered completed steps and
one completed plan without plan errors. No throughput benchmark is inferred from a rate
setting. Timeline offsets are explicit in the bundle; the test does not schedule actions.

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-plan-artemis.properties scenario-plan
./run-acceptance-tests.sh acceptance-tests/targets/local-plan-rabbit.properties scenario-plan
```

Select only the target matching the deployment's WORK adapter. The final STOPPED state
must come from the plan; the test never sends STOP to satisfy it. Existing resource
owners close taps and verify REMOVE/registry absence, including on an assertion failure.

Platform availability through public ingress (read only, no empty-stack assumption):

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-smoke.properties smoke
```

This checks UI, Orchestrator and Scenario Manager health. CONTROL transport evidence
is recorded separately in the coverage ledger; SM-2 needs a fresh dedicated deployment.

Redis fixture boundary through the public Redis Commander ingress:

```bash
./run-acceptance-tests.sh acceptance-tests/targets/local-redis-fixture.properties redis-fixture
```

The target explicitly selects a Redis Commander connection id. The test creates
two unique one-item lists, checks cleanup after assertion failure and preserves
the second list until its own close. It never flushes Redis or deletes by prefix.
This proves fixture preparation/cleanup, not yet DA worker pipeline coverage.
