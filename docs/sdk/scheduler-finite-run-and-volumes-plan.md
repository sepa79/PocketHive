# Scheduler Finite-Run & Scenario-Driven Volumes — Plan

This file tracks two related enhancements:

- Finite-run support for the scheduler input (max messages, quota headers).
- Passing Docker volumes from scenarios into worker containers via Swarm Controller.

The goal is to keep workers container-agnostic while letting scenarios fully
control how long generators run and what host paths they see.

---

## 0. Task tracking

- [ ] Scheduler finite-run support:
  - [ ] Config shape & docs
  - [ ] Scheduler state & counters
  - [ ] Config update handling (including reset semantics)
  - [ ] Headers & status fields
  - [ ] Tests & an example scenario
- [ ] Scenario-driven Docker volumes:
  - [ ] Volume shape in swarm model / scenarios
  - [ ] Volume resolution in `SwarmRuntimeCore`
  - [ ] Workload port & Docker adapter extension
  - [ ] Tests (unit + smoke) and documentation

---

## 1. Scheduler finite-run support

### 1.1 Config shape & docs

- Add an optional finite-run knob under scheduler IO config:
  - `inputs.scheduler.maxMessages` (or `maxDispatches`) — integer ≥ 0.
- Keep `ratePerSec` semantics unchanged:
  - `ratePerSec` controls quota per tick.
  - `maxMessages` caps the *total* number of dispatched seeds for the lifetime
    of the current configuration.
- Update:
  - `SchedulerInputProperties` in worker SDK.
  - IO plans / docs:
    - `docs/sdk/worker-configurable-io-plan.md`
    - Any relevant README snippets for generator/trigger.

### 1.2 Scheduler state & counters

- Extend scheduler state to track:
  - `long dispatchedCount`
  - `DoubleSupplier rateSupplier` (already present)
  - Optional `LongSupplier maxMessagesSupplier` (or a simple `long maxMessages`
    read from `SchedulerInputProperties`).
  - Derived `boolean exhausted` flag.
- Behaviour:
  - Each successful dispatch increments `dispatchedCount`.
  - When `maxMessages > 0 && dispatchedCount >= maxMessages`,
    mark `exhausted=true`.
  - While `exhausted=true`, `planInvocations(now)` returns `quota=0`, but
    the state still honours control-plane updates (`enabled` toggles, config
    changes).
  - Scheduler does **not** mutate control-plane config; it only tracks its own
    exhaustion state.

### 1.3 Config update handling

- In the scheduler state listener (where we already apply raw config overrides
  for `inputs.scheduler.ratePerSec`):
  - Bind `inputs.scheduler.maxMessages` from `snapshot.rawConfig()`.
  - Optionally honour a `inputs.scheduler.reset` boolean:
    - Any update where `maxMessages` changes **or** `reset=true`:
      - Reset `dispatchedCount = 0`.
      - Clear `exhausted=false`.
    - `reset` is one-shot and ignored after applying.
- This keeps re-enabling explicit:
  - Operators or scenarios send a config-update with a new limit or `reset`
    when they want a fresh finite run.

### 1.4 Headers & status fields

- Headers:
  - When a finite limit is configured (`maxMessages > 0`), attach
    `x-ph-scheduler-remaining` to each generated seed:
    - `remaining = max(0, maxMessages - dispatchedCountAfterThisDispatch)`.
  - This is telemetry for downstream workers / interceptors (e.g. routing on
    “near end of batch”), not a control-plane signal.
- Status:
  - Extend generator/trigger status payload with scheduler info, for example:
    - `scheduler.ratePerSec`
    - `scheduler.maxMessages`
    - `scheduler.dispatched`
    - `scheduler.remaining`
    - `scheduler.exhausted` (boolean)
  - Ensure these live under `status.data` so they appear in the existing
    status dashboards.

### 1.5 Tests & example scenario

- Unit tests in worker SDK:
  - `SchedulerStates` / `SchedulerWorkInput`:
    - Dispatches up to `N` items and then stops.
    - Respects `enabled=false` regardless of `maxMessages`.
    - Resumes when a config-update with `reset=true` or a new `maxMessages`
      arrives.
  - Header behaviour:
    - Confirms `x-ph-scheduler-remaining` is present with the right count when
      finite-run is enabled.
- E2E / scenario:
  - Add (or extend) a scenario that:
    - Configures `inputs.scheduler.maxMessages` for generator.
    - Verifies that only `N` messages hit the downstream queue.
    - Issues a config-update with `reset=true` and confirms generation resumes.

---

## 2. Scenario-driven Docker volumes

### 2.1 Volume shape in swarm model / scenarios

- Use a simple representation under `Bee.config`, keeping `Bee` itself stable:
  - In YAML:
    ```yaml
    bees:
      - role: generator
        image: generator:latest
        work: {}
        config:
          docker:
            volumes:
              - "/host/path:/container/path:ro"
              - "named-vol:/container/cache"
    ```
  - This ends up in `Bee.config()` as:
    - `config.get("docker")` → `Map<String,Object>`
    - `docker.get("volumes")` → `List<String>`
- No changes to worker services: they remain unaware of Docker specifics.

### 2.2 Volume resolution in SwarmRuntimeCore

- In `swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/SwarmRuntimeCore.java`:
  - Extend the loop in `prepare(...)` where we start bees:
    - Currently:
      ```java
      String containerId = workloadProvisioner.createAndStart(bee.image(), beeName, env);
      ```
    - Add a helper to resolve volumes from `bee.config()`:
      ```java
      List<String> volumes = resolveVolumes(bee.config());
      String containerId = workloadProvisioner.createAndStart(bee.image(), beeName, env, volumes);
      ```
  - `resolveVolumes(Map<String,Object> config)`:
    - Navigates `config.get("docker")` → `docker.get("volumes")`.
    - Returns an immutable `List<String>` (empty if none).

### 2.3 Workload port & Docker adapter

- Extend `WorkloadProvisioner`:
  - Add a default method:
    ```java
    default String createAndStart(String image,
                                  String name,
                                  Map<String,String> env,
                                  java.util.List<String> volumes) {
      return createAndStart(image, name, env);
    }
    ```
- Implement volumes in `DockerWorkloadProvisioner`:
  - Override the 4-arg method:
    ```java
    @Override
    public String createAndStart(String image, String name,
                                 Map<String,String> env,
                                 List<String> volumes) {
      Objects.requireNonNull(volumes, "volumes");
      return docker.createAndStartContainer(
        image,
        env,
        name,
        hostConfig -> {
          if (!volumes.isEmpty()) {
            List<com.github.dockerjava.api.model.Bind> binds = volumes.stream()
              .filter(v -> v != null && !v.isBlank())
              .map(com.github.dockerjava.api.model.Bind::parse)
              .toList();
            hostConfig.withBinds(binds);
          }
          return hostConfig;
        });
    }
    ```
- This keeps the Docker-specific handling inside the infra adapter, with
  SwarmRuntimeCore only dealing with abstract volume strings.

### 2.4 Tests & documentation

- Tests:
  - Unit tests for `resolveVolumes(...)` using a synthetic `Bee.config` map.
  - Small test for `DockerWorkloadProvisioner` to ensure it passes binds
    into `DockerContainerClient.createAndStartContainer` correctly (can use a
    fake DockerContainerClient to capture `HostConfig`).
  - Optionally, an integration test that starts a swarm with a mounted volume
    and verifies files are visible inside a worker container (e.g., HTTP
    Builder loading templates from `/app/http-templates`).
- Docs:
  - Update `docs/architecture/manager-sdk-plan.md` and
    `docs/architecture/swarm-controller-refactor.md` to mention scenario-driven
    volumes as a supported behaviour.
  - Add a short “Using volumes in scenarios” section in
    `scenario-manager-service` docs or `docs/USAGE.md`, with a concrete YAML
    example.

