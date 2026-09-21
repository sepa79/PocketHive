# Controller Work composition extraction — 2026-09-10

Status: implemented and tested; awaiting separate review. Scope is the existing Work
composition path behind a Controller-local port; no full candidate-gate activation,
AUTHORING change, B04 topology migration, lifecycle change or new configuration defaults.
Contracts: RESP-CONTROLLER-WORKER-PLAN and RESP-CONTROLLER-WORK-CONFIGURATION in
`docs/architecture/runtime-responsibilities.md`.

Before: `ee424015` plus the existing uncommitted B02 tree. Worker-plan source SHA256:
e341197d1ef1d9ba08685191c1bce0df9529f6c82817018f284047a2b49cbd1d.
Selected baseline: 57 cases, 56 passed; one lifecycle fixture omitted required Redis
ratePerSec and failed in RedisDatasetEnvironment.resolve before any extraction. The
fixture now supplies ratePerSec=10.0; no production parser/default changed. The corrected pre-extraction baseline passed all 57 cases.
Baseline command: `./mvnw -B -ntp -pl swarm-controller-service -am
-Dtest=SwarmWorkerSpecFactoryTest,SwarmLifecycleManagerTest,WorkConnectionEnvironmentResolverTest,SpringConnectionEnvironmentTest,RepositoryImportBoundaryTest
-Dsurefire.failIfNoSpecifiedTests=false
-DargLine=-javaagent:/home/sepa/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar test`.

Owner search covered repository Java references to SwarmWorkerSpecFactory, connection
resolver/result, Work parser ports, Rabbit bootstrap and IO environment names. Production
factory caller: SwarmLifecycleManager; runtime consumer: SwarmRuntimeCore.prepare. No
existing whole-Work composition port found. Existing connection resolver and per-adapter
codecs are reused, not widened into a second complete configuration authority.


## Delivered boundary

Paths below are relative to `swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/`:

- `runtime/WorkerWorkConfigurationPort.java`: required injected composition capability and
  canonical declaration preflight before external network lookup.
- `runtime/WorkerWorkConfigurationResult.java`: environment/bootstrap projection, immutable
  outer maps and redacted diagnostics; unchanged nested domain values remain borrowed read-only.
- `infra/configuration/WorkerWorkConfigurationAdapter.java`: extracted Work composition;
  delegates existing policy, settings parsers/codecs, connection resolver and Rabbit bootstrap.
- `config/WorkerWorkConfigurationComposition.java`: explicit startup construction of the
  implementation and collaborators.
- `runtime/SwarmWorkerSpecFactory.java`: consumes the port; removed Work provider construction,
  IO/environment composition and Rabbit materialization helpers. Identity, SUT, mounts,
  base Control Plane/ClickHouse/network environment and spec assembly remain here.
- `SwarmLifecycleManager.java`: receives and forwards the port; no adapter selection or
  lifecycle/state transition changes.

Existing `WorkConnectionEnvironmentResolver` retains connection composition ownership;
`SwarmControllerProperties` retains its current naming role pending B04. The extraction
adds no competing parser, validator, defaults, topology resolver or import scanner.

## Verification

Final targeted reactor run: **61 tests passed**, zero failures/errors/skips:
24 worker-plan, 26 lifecycle, 4 connection resolver, 2 Spring environment,
3 new composition behavior cases and 2 existing repository import-boundary cases.
Run the baseline command above with `WorkerWorkConfigurationAdapterTest` added to `-Dtest`.
All changed Controller production sources and upstream reactor dependencies compile in this run.
`npm --prefix docs-site run build` passed; `git diff --check` passed.

Added evidence covers repeated composition with independent overrides, unchanged source maps
after rejection, recovery on a subsequent valid candidate, redacted/immutable result environment,
and removed-control rejection before the external network supplier is invoked. Standalone
adapter composition also invokes the canonical declaration preflight. Fixtures explicitly
supply required rates and scheduler limits; no production fallback was added.

Repository-wide source searches locate production adapter construction only in startup
composition; worker factory consumers are the lifecycle manager and its component test.
Work helpers now exist only in the adapter. The existing import test enforces module imports,
not all intra-service port usage; it is not proof of full B02 architecture acceptance.

Full Work candidate validation, Rabbit AUTHORING/Scenario integration and B04 resource/name
ownership remain outside this extraction. No automatic review, commit or deployment performed.
