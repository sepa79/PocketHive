# Manager SDK & Guard/Scenario Extraction — Plan

> Status: **in progress**.  
> Core Manager SDK pieces are implemented; remaining items are documentation/alignment and incremental improvements.

This document sketches how to extract a reusable **Manager SDK** from the current
Swarm Controller implementation, and how to move guards and scenarios into
`common` so they are reusable and swappable across different controller
implementations.

The goal is for future controllers to look similar in size and complexity to
workers: small apps built on top of a shared SDK, with clear ports and adapters.

---

## 0. High-level task tracking

- [x] Create `common/manager-sdk` module (pom + empty packages)
- [x] Introduce core abstractions:
  - [x] `ManagerLifecycle`, `ManagerStatus`, `ManagerMetrics`, `QueueStats`
  - [x] Port interfaces: `WorkTopologyPort`, `WorkloadPort`, `ControlPlanePort`, `QueueStatsPort`, `MetricsPort`, `Clock`
- [x] Move core runtime:
  - [x] Extract `ManagerRuntimeCore` from `SwarmRuntimeCore`
  - [x] Rewire `SwarmLifecycleManager` to use `ManagerRuntimeCore` via ports (readiness/queue stats/enablement wiring; provisioning to be migrated incrementally)
- [ ] Move readiness & config fan-out:
  - [x] Relocate `SwarmReadinessTracker` → `ReadinessTracker` in manager SDK
  - [x] Relocate `SwarmConfigFanout` → `ConfigFanout` in manager SDK
  - [x] Adjust Swarm Controller to use the shared implementations
- [ ] Extract BufferGuard into common:
  - [x] Move `BufferGuardSettings` into manager SDK
  - [x] Move `BufferGuardController` into manager SDK and replace Rabbit/Micrometer with ports
  - [x] Adapt Swarm Controller to wire BufferGuard via ports and support multiple guard instances per swarm
- [x] Introduce Scenario engine abstraction:
  - [x] Define `Scenario`, `ManagerRuntimeView`, `ScenarioContext`
  - [x] Provide a simple `ScenarioEngine` in manager SDK
  - [x] Wire a placeholder scenario from Swarm Controller to validate (heartbeat-driven, no-op `NoopScenario`)
- [x] Clean up Swarm Controller:
  - [x] Shrink `SwarmLifecycleManager` to wiring + composition
  - [x] Ensure no guard/scenario logic remains in core SC classes
- [ ] Documentation & alignment:
  - [ ] Align `swarm-controller-refactor.md` with this plan
  - [ ] Update architecture docs once the Manager SDK is in place

## 1. New module layout (Manager SDK in `common`)

Introduce a new module:

- `common/manager-sdk`
  - `src/main/java/io/pockethive/manager/runtime/...`
  - `src/main/java/io/pockethive/manager/guard/...`
  - `src/main/java/io/pockethive/manager/scenario/...`
  - `src/main/java/io/pockethive/manager/ports/...`

Constraints:

- No Spring, no Rabbit, no Docker.
- Only pure Java, the shared control-plane types, and small port interfaces.

---

## 2. Core runtime

### 2.1 Packages

- `io.pockethive.manager.runtime`
- `io.pockethive.manager.control` (thin façade over topology-core, if needed)
- `io.pockethive.manager.metrics`

### 2.2 API & runtime

Extract from the current Swarm Controller:

- `ManagerLifecycle`
  - Generalised form of `SwarmLifecycle`:
    - `void prepare(String planJson)`
    - `void start(String planJson)`
    - `void stop()`
    - `void remove()`
    - `void updateHeartbeat(String role, String instance)`
    - `void updateEnabled(String role, String instance, boolean flag)`
    - `boolean isReadyForWork()`
    - `ManagerStatus getStatus()`
    - `ManagerMetrics getMetrics()`
    - `void setManagerEnabled(boolean enabled)`
    - `void setWorkEnabled(boolean enabled)` (swarm-wide enable/disable)

- `ManagerRuntimeCore`
  - Extracted from `SwarmRuntimeCore`, but parameterised:
    - Plan model: accepts a `Plan` type (for now, this will be `SwarmPlan`
      from `swarm-model`, but the core lives in `common` and depends only on
      the model types, not on Spring).
    - Uses ports for all side effects:
      - `WorkTopologyPort`
      - `WorkloadPort`
      - `ControlPlanePort`
      - `QueueStatsPort`
      - `MetricsPort` (or an adapter-friendly metrics interface)
    - Owns:
      - Plan preparation and queue naming
      - Container/process lifecycle per role
      - Readiness tracking
      - Config bootstrap and fan-out
      - Queue stats snapshotting

- `ReadinessTracker`
  - Current `SwarmReadinessTracker`, moved to `manager-sdk`:
    - Tracks expected workers per role, ready instances, heartbeats, enabled
      flags.
    - Uses a `StatusRequestCallback` port to request status when missing or
      stale.

- `ConfigFanout`
  - Current `SwarmConfigFanout`, moved to `manager-sdk`:
    - Builds `config-update` envelopes using the shared control-plane types
      (`ControlSignal`, `CommandTarget`, `ConfirmationScope`, etc.).
    - Publishes via a `ControlPlanePort`.

- DTOs / metrics
  - `ManagerStatus` (RUNNING, STOPPED, FAILED, REMOVED)
  - `ManagerMetrics` (ready counts, heartbeat recency, etc.)
  - `QueueStats` (depth, consumers, optional oldest age)

---

## 3. Ports (interfaces)

Define ports in `io.pockethive.manager.ports` — these are implemented by
applications such as the Swarm Controller.

- `WorkTopologyPort`
  - `String declareWorkExchange()`
  - `void declareWorkQueues(String workExchange, java.util.Set<String> suffixes)`
  - `void deleteWorkQueues(java.util.Set<String> suffixes)`
  - `void deleteWorkExchange()`

- `WorkloadPort`
  - `String startWorker(String image, String name, java.util.Map<String,String> env)`
  - `void stopWorker(String containerId)`

- `ControlPlanePort`
  - `void publishSignal(String routingKey, String payload)`
  - `void publishEvent(String routingKey, String payload)`

- `QueueStatsPort`
  - `io.pockethive.manager.runtime.QueueStats getQueueStats(String queueName)`

- `MetricsPort` (optional, adapter for Micrometer/Prometheus)
  - For example:
    - `void updateQueueMetrics(String queueName, QueueStats stats)`
    - plus simple gauge/counter helpers if needed.

- `Clock` (optional, for deterministic testing)
  - `long currentTimeMillis()`
  - `java.time.Instant now()`

The existing Swarm Controller implements these using Rabbit (AmqpAdmin),
Docker, and Micrometer.

---

## 4. Guard API and BufferGuard in common

Guards are reusable policy modules that sit *on top* of the Manager runtime.

### 4.1 Packages

- `io.pockethive.manager.guard`
- `io.pockethive.manager.guard.buffer`

### 4.2 Core interfaces

- `Guard`
  - `void start()`
  - `void stop()`
  - `void pause()`
  - `void resume()`

- `GuardConfig`
  - Marker/base interface for guard settings.

### 4.3 BufferGuard in common

Move logic from `swarm-controller-service` into `common`:

- `BufferGuardSettings`
  - Same structure as current `BufferGuardSettings`, but no Rabbit or
    Micrometer types.
  - Only primitive and configuration types.

- `BufferGuardController implements Guard`
  - Current state machine and rate-adjustment logic, rewritten to use ports:
    - `QueueStatsPort` or a minimal `QueueDepthProvider`:
      - `QueueStats getQueueStats(String queueName)`
    - `GuardMetricsPort` abstraction for gauges:
      - e.g. `DoubleGauge depthGauge(String name, Tags tags)`, or a simple
        `update(GuardMetricsSnapshot)` callback.
    - Keeps:
      - prefill logic
      - moving average depth
      - backpressure handling
      - rate clamp and step rules

- `BufferGuardCoordinator`
  - Lives in `manager-sdk`:
    - Accepts:
      - a `ManagerRuntimeCore` (or a smaller interface providing config fan-out
        and status),
      - `QueueStatsPort`,
      - `GuardMetricsPort`,
      - a `BufferGuardSettings` derived from the plan.
    - Resolves initial rate from an explicit `ratePerSec` in the plan/config
      (no guessing).
    - Creates and manages a `BufferGuardController`.
    - Reacts to enable/disable/remove events from the Manager runtime.

Swarm Controller side:

- Parses guard config from `SwarmPlan.trafficPolicy.bufferGuard`.
- Maps it to `BufferGuardSettings`.
- Provides Rabbit-backed `QueueStatsPort` and Micrometer-backed `GuardMetricsPort`.
- Wires the coordinator into its lifecycle (on start/stop/remove).

---

## 5. Scenario engine in common

Scenarios are reusable, higher-level policies that orchestrate swarms based on
queue state, metrics, or other signals.

### 5.1 Packages

- `io.pockethive.manager.scenario`
- `io.pockethive.manager.scenario.plan`

### 5.2 Core types

- `Scenario`
  - `void onTick(ManagerRuntimeView view, ScenarioContext ctx)`
  - Could later support event-based hooks, but start with a simple tick-based
    API.

- `ManagerRuntimeView`
  - Read-only view over:
    - current plan
    - queue stats
    - readiness / metrics
    - traffic policy

- `ScenarioContext`
  - Methods to:
    - emit `config-update` via `ConfigFanout`
    - request status
    - mark manager failed
    - log diagnostics

Application side (`swarm-controller-service`):

- `ScenarioEngine`
  - Holds a list of `Scenario` instances.
  - Invoked on a scheduler (reusing or complementing existing guard
    scheduling).
  - Provides a `ManagerRuntimeView`/`ScenarioContext` backed by the
    `ManagerRuntimeCore` and ports.

---

## 6. Interaction with topology-core / control-plane

We already have shared control-plane types in:

- `common/topology-core/src/main/java/io/pockethive/control/...`
  - `ControlSignal`
  - `CommandTarget`
  - `CommandState`
  - `ConfirmationScope`
  - `ConfirmationSupport`

The Manager SDK should:

- Use these as the canonical representation of commands and confirmations.
- Let `ConfigFanout` and future diagnostic helpers build envelopes and
  confirmations using them.
- Keep all control-plane wiring and conventions in one place.

---

## 7. Migration path

High-level migration steps:

1. **Introduce `common/manager-sdk`**
   - Create the module and basic package structure.
   - Add minimal `ManagerLifecycle`, `ManagerStatus`, `QueueStats`, and the
     port interfaces.

2. **Move core runtime logic**
   - Extract `ManagerRuntimeCore` logic from `SwarmRuntimeCore` into the new
     module.
   - Keep `SwarmRuntimeCore` as a thin shell or remove it in favour of direct
     `ManagerRuntimeCore` usage from `SwarmLifecycleManager`.

3. **Move `ReadinessTracker` and `ConfigFanout`**
   - Relocate these classes into the Manager SDK with no Spring imports.
   - Adjust Swarm Controller to use them via the new module.

4. **Extract BufferGuard into common**
   - Move `BufferGuardSettings` and `BufferGuardController` into
     `io.pockethive.manager.guard.buffer`.
   - Introduce a `BufferGuardCoordinator` in the SDK that works with generic
     ports, not Rabbit or Micrometer.
   - Adapt Swarm Controller to wire the guard using adapters for
     `QueueStatsPort` and metrics.

5. **Introduce basic Scenario engine abstraction**
   - Define `Scenario`, `ManagerRuntimeView`, `ScenarioContext`.
   - Implement a minimal `ScenarioEngine` in the Manager SDK.
   - Wire it into the Swarm Controller with a placeholder scenario to validate
     the wiring.

6. **Tighten Swarm Controller**
   - Reduce `SwarmLifecycleManager` to:
     - Spring wiring of ports and adapters.
     - Guard and Scenario configuration.
     - REST/control-plane entry points.
   - Ensure no SC-specific logic leaks back into the Manager SDK.

7. **Documentation & cleanup**
   - Update architecture docs to reference the Manager SDK structure.
   - Remove any remaining duplicated helpers or heuristics that NFF forbids.

This leaves `common/manager-sdk` as the reusable “Manager SDK” (runtime + guards
 + scenarios + ports), and keeps `swarm-controller-service` as a relatively
small, composable Swarm Controller built on top of it.
