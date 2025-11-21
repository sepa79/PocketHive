# Swarm Controller Refactor Findings & Proposal

## Summary of issues
- **Responsibilities concentrated in one manager class.** `SwarmLifecycleManager` wires AMQP topology, Docker lifecycle, readiness tracking, BufferGuard policy, metrics registration, and config fan-out in a single component, which makes reuse by the orchestrator or Worker SDK difficult and tightly couples control logic to infrastructure choices.【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java†L82-L166】
- **Control loops are not modular.** BufferGuard tuning and the status/config dispatch loop sit directly inside the lifecycle manager, preventing multiple guards from running or being reused by other services. There is no abstraction for additional guards or schedulable policies beyond the single built-in controller.【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java†L100-L131】
- **Scenario/state handling is ad hoc.** The controller stores the active template and readiness bookkeeping internally, but there is no explicit Scenario/Plan engine that could be shared with the orchestrator for multi-step timelines (e.g., time-based config updates).【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java†L107-L114】
- **Single implementation of the lifecycle contract.** `SwarmLifecycle` is a clean interface, yet only `SwarmLifecycleManager` implements it; adapters (RabbitMQ, Docker, metrics) are not separated from the lifecycle orchestration, making it hard to swap transports or to embed the lifecycle into a different runtime (e.g., Worker SDK sidecars).【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycle.java†L9-L189】【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java†L82-L166】
- **Limited reuse of control-plane contracts.** The architecture doc defines manager roles and control-plane routing, but the controller runtime re-derives routing and declarations locally rather than consuming a reusable SDK that could also be leveraged by the orchestrator and Worker SDK for consistent binding and confirmations.【F:docs/ARCHITECTURE.md†L13-L136】

## Proposed architecture for reusable libraries and configurable loops

### 1) Control-plane SDK (shared between Orchestrator, Swarm Controller, Worker SDK)
- Provide common definitions for control-plane routing, signal envelopes, confirmation helpers, and correlation/idempotency handling aligned with the authoritative contract.【F:docs/ARCHITECTURE.md†L13-L136】
- Offer Spring (and plain Java) modules that expose message listener wiring, acknowledgement emitters, and health/status publishers so each runtime can declaratively register handlers without rebuilding queues/bindings logic.
- Include reusable metrics/observability helpers for correlation IDs and status TTL tracking so managers and workers report consistent telemetry.

### 2) Swarm runtime core library
- Extract `SwarmLifecycle` orchestration into a transport-agnostic core that coordinates lifecycle phases (`prepare`, `start`, `stop`, `remove`), desired/actual state reconciliation, and readiness gating. Infrastructure adapters (RabbitMQ admin, container runtime, metrics sink) become pluggable ports rather than inlined fields.【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycle.java†L18-L189】
- Define ports such as `ControlPlaneClient`, `WorkloadProvisioner`, `TopologyProvisioner`, `StatusStore`, and `ConfigBroadcaster`. Concrete implementations live in small adapters (RabbitMQ, Docker, Prometheus) that can be reused by the orchestrator when it needs the same bindings or by Worker SDK extensions running embedded controllers.
- Model desired state with a dedicated `SwarmRuntimeContext` that stores the active `SwarmPlan`, readiness map, enablement flags, pending config queues, and the derived **resource set** for that swarm (declared queues/bindings, hive exchange, container ids). `remove()` then tears down only resources registered in this context so queues and containers cannot be orphaned across template changes or controller restarts.【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java†L94-L114】
- When provisioning workers, route all container environment wiring (including IO bindings and any future extra mounts) through this context + ports so the controller always has a canonical view of “what exists” for a given swarm.

### 3) Guard/Policy engine
- Introduce a schedulable policy engine that can run multiple guard instances (BufferGuard, backpressure guard, custom scenario checks) with a shared clock/timer abstraction. Guards receive queue metrics and status snapshots from the Swarm runtime core and emit config deltas via the control-plane SDK.
- BufferGuard’s tuning parameters move into a reusable `GuardSpec` so other services can import it; the Swarm Controller composes several guards without modifying the main lifecycle class.【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java†L100-L131】

### 4) Scenario engine
- Provide a reusable Scenario executor that can schedule config updates over time or in response to events (status, metrics). Scenarios are sequences of `ConfigPatch` steps with triggers (time offsets, event predicates) and can be played by either the Swarm Controller (per-swarm runtime) or orchestrator (fleet-level orchestration).
- Store scenarios separately from runtime state so they can be started/stopped independently and replayed; integrate with the control-plane SDK to publish the resulting `config-update` signals and confirmations.【F:docs/ARCHITECTURE.md†L37-L136】

### 5) Configurable controller main loop
- Replace the monolithic controller loop with a composition root that wires together: the Swarm runtime core, selected guards, optional Scenario runners, and adapters for RabbitMQ/Docker/metrics. The main loop becomes declarative (e.g., a `SwarmControllerApplication` that loads modules from configuration) and can be reused in Worker SDK or orchestrator test harnesses.
- Expose enablement hooks so `commandTarget=instance` toggles can pause guard execution and message handling without tearing down adapters, enabling richer testability and sidecar deployments.【F:swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycle.java†L192-L197】

### 6) Migration path
- First extract message contract helpers and routing utilities into the control-plane SDK and refactor the Swarm Controller to consume them, reducing duplicate declarations.
- Next, peel the lifecycle orchestration logic out of `SwarmLifecycleManager` into the Swarm runtime core and wrap the current RabbitMQ/Docker wiring in adapters, keeping behaviour intact while enabling orchestrator/worker reuse. As part of this step, introduce the explicit `SwarmRuntimeContext` and topology/resource tracking so queue/container removal is driven purely by the stored plan-derived resource set rather than ad hoc maps.
- Finally, layer the guard engine and scenario executor on top, enabling multiple guard instances and timed/even-driven scenarios without altering the main lifecycle orchestration logic.

> **Note on worker mounts:** Once the Swarm runtime core and `WorkloadProvisioner` port exist, extend the `SwarmPlan`/bee model with an optional per-worker `mounts` block (hostPath, containerPath, readOnly) and have the controller pass those through the Docker adapter when creating containers. Mount configuration must be plan-driven (no implicit defaults), and missing/invalid host paths should fail the swarm rather than being silently ignored. This keeps extra directories such as HTTP template roots or dataset folders under the same “plan is the only source of truth” rule as queues and images.

### Topology vs runtime IO configuration

- **Control plane + Rabbit topology are immutable per swarm.**  
  Queue names, the hive exchange, bindings, and the mapping of roles to `work.in/work.out` suffixes are derived from the `SwarmPlan` during `prepare()` and form the swarm’s resource set. They must not change via `config-update`; changing topology requires a new plan/swarm.
- **Runtime IO config can tune behaviour, not topology.**  
  Worker configs may change non-topology knobs at runtime (e.g. Redis host/list names, Redis rates, scheduler rates, HTTP template roots, header/body templates) via `config-update`. These do not create or remove queues and do not affect the resource set tracked by `SwarmRuntimeContext`.
- **Queue/exchange/routing fields are plan-only.**  
  Fields that identify Rabbit queues/exchanges/routing keys are seeded from the plan/env (e.g. `POCKETHIVE_INPUT_RABBIT_QUEUE`, `POCKETHIVE_OUTPUT_RABBIT_*`) and treated as read-only by workers and the config merger. Runtime patches must not be allowed to silently change them.
- **Input type is fixed for a running swarm.**  
  Whether a worker uses Rabbit, Redis, scheduler, or NOOP input/output is a deployment-time choice expressed in service configuration and the swarm plan. Swapping input type at runtime (e.g. Rabbit → Redis) is explicitly out of scope for this refactor and would require a dedicated, plan-driven feature in a later major version.

> A future iteration of the controller/UI could support interactive topology editing (dragging routes, changing queue wiring live), but that belongs in a later major version with explicit plan updates and matching safety guarantees, not as an ad hoc runtime mutation of the current resource set.
