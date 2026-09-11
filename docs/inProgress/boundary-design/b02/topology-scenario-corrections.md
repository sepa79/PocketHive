# TS-R1–TS-R3 correction evidence — 2026-09-10

Status: implemented and tested; separate correction review pending. This does not
accept B02 or replace the historical [review](topology-scenario-review.md).

## Changed ownership and removed paths

- **TS-R1:** `WorkSelectorEnvironmentPolicy` in work-config rejects supplied input/output
  selector properties. Controller declaration preflight invokes it through raw Spring
  property lookup before external context resolution. Uppercase environment and dotted
  property aliases are covered. No selector override wins silently; config owns selection.
- **TS-R2:** `WorkResourceNamesPort` supplies swarm topology settings and resource names.
  `PrefixedWorkResourceNames` owns the formulas; only Controller and Orchestrator startup
  composition select the implementation. Controller forwards the injected port to guard,
  queue statistics, status bindings and cleanup, as well as configuration/provisioning.
  Orchestrator uses it for Controller traffic settings and resource ownership manifests.
  `SwarmControllerProperties.Traffic` holds raw settings; its name resolver and the static
  Control Plane environment/settings naming helpers were removed. The latter requires
  explicit resolved traffic settings and no longer reconstructs missing values.
- **TS-R3:** Controller tests field presence before passing Rabbit tuning to bootstrap.
  Omission becomes an empty map; explicit null reaches the boundary and fails. Bootstrap
  no longer converts null to omission. Rabbit parsers retain tuning semantics ownership.

## Behavioral evidence

`SwarmWorkerSpecFactoryTest` rejects selector overrides in both directions before network
context lookup. Null tuning in either direction is rejected during composition, after
network context lookup but before returning a plan or provisioning. `SwarmLifecycleManagerTest` starts an accepted plan and then
tries each invalid replacement; workers, accepted SUT, readiness and RUNNING state remain,
with no new container or Rabbit effects. Existing topology configuration tests compare
materialized bootstrap/environment destinations with provisioned Rabbit resources.

Guard, statistics, bindings, runtime cleanup and Orchestrator manifest tests use a naming
port returning non-default names and assert the resulting settings, queries, projections
or resource lists. `PrefixedWorkResourceNamesTest` covers swarm root settings;
`ControlPlaneContainerEnvironmentFactoryTest` rejects absent prefix/exchange rather than
reconstructing them. Scenario repository and neutral validation regressions still pass.

Repository-wide production searches found concrete naming construction only in the two
startup compositions. Removed helper names and properties/traffic queueName call sites
have no remaining production matches. The existing RepositoryImportBoundaryTest passes;
no additional scanner or bean-identity test was introduced.

## Verification

- 169 selected tests passed, zero failures/errors/skips, including all affected consumer
  suites, Scenario validation, Rabbit configuration and import-boundary checks.
- Full root Maven package with tests skipped passed (selected behavioral tests ran above).
- Documentation site build passed.
- Logs: `/tmp/ph-ts-fixes/final-tests.log`, `package.log`, `docs.log` (local evidence).
- No commit, push, deployment or direct service-port testing.

The complete Controller Work candidate gate and other B02/B03 work retain their existing
scope and status. These corrections do not claim full candidate validation or Rabbit
delivery effects. Review must follow actual consuming calls and removed paths, as required
by the transfer contract; implementation test success alone is not acceptance.

## TS-R2a follow-up — 2026-09-10

The [correction review](topology-scenario-correction-review.md) found the inherited
DebugTapService formula missed in the initial transfer. It is now removed: the service
receives WorkResourceNamesPort from the existing Orchestrator startup composition and
uses forSwarm, exchangeName and queueName for its source binding. Temporary tap queue
identity and lifecycle retain their existing owner. No new resolver or compatibility
constructor was added. The responsibility record and service header describe this role.

DebugTapServiceTest checks both IN and OUT with non-default resolved names, asserting
actual AMQP binding exchange/routing key/destination and matching response values. Its
default-name expiry/deletion regression remains. This handoff awaits separate review;
the historical review verdict is preserved.

TS-R2a verification: 26 tests passed (DebugTapService, OrchestratorAdminAuth,
ContainerLifecycleManager, PrefixedWorkResourceNames, ControlPlaneContainerEnvironmentFactory
and RepositoryImportBoundary). Log: `/tmp/ph-tap-fix-tests.log`. Production source search
for ph/swarm/hive concatenations now leaves only the separately scoped test fixture;
concrete naming selections remain in startup. git diff --check passed. No deployment.
