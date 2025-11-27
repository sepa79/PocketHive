# Swarm Stack Adapter & Per‑Swarm Grouping Plan

Status: [ ] Draft  [ ] In Progress  [ ] Done  
Owner: Manager SDK / Orchestrator / Swarm Controller

## 1. Goal

Run each PocketHive swarm as a **single Swarm deployment unit** (stack‑like grouping) instead of loosely related services/containers, while keeping the core `ComputeAdapter` abstraction portable and Swarm‑specific details well isolated.

Concretely:

- Group **Swarm Controller + worker bees** for a given `swarmId` under a single logical deployment (e.g. `ph-${swarmId.toLowerCase()}`).
- Make creation, update, and teardown **atomic** at the “swarm” level where Swarm supports it.
- Prepare a shape that can be reused for other runtimes (Kubernetes namespace, ECS service group, etc.).

## 2. Constraints & rules

- **NFF / KISS** still apply:
  - No implicit migration to stacks; must be explicitly enabled via config.
  - No auto‑magic discovery of managers; the Docker endpoint for Swarm remains explicit.
- **Manager SDK stays generic**:
  - `ComputeAdapter` remains service/container‑centric (manager + workers).
  - Stack/namespace semantics live in **Swarm‑specific adapters** or orchestration helpers, not in the core SDK.
- **Compatibility**:
  - Existing single‑Docker and Swarm‑service adapters remain available.
  - Existing scenarios, capabilities, and e2e tests must continue to work in service mode.

## 3. High‑level design

We introduce a Swarm‑specific “stack adapter” **on top of** the existing service‑level `ComputeAdapter`, not instead of it.

- A new adapter type in `ComputeAdapterType`:
  - `[ ]` `SWARM_STACK` — Swarm stack grouping (controller + bees).
- A Swarm‑specific adapter in `docker-client`:
  - `[ ]` `DockerSwarmStackAdapter` (or equivalent name):
    - Accepts a `ManagerSpec` + list of `WorkerSpec`s + `swarmId`.
    - Materialises a stack definition (internally using `docker service` APIs, not `docker stack deploy` CLI).
    - Uses labels and naming conventions to group services under a logical stack name `ph-${swarmId.toLowerCase()}`.
- Orchestrator / Swarm Controller orchestration:
  - `[ ]` For `SWARM_STACK`, orchestrator calls into a small “stack manager” that:
    - Creates the controller service.
    - Hands a **stack context** (stack name, network name, shared labels) to Swarm Controller for worker services.

## 4. Detailed tasks

### 4.1 Enum & configuration

- [ ] Extend `ComputeAdapterType` with `SWARM_STACK` (documented as Swarm‑only).
- [ ] Add config knobs:
  - Orchestrator:
    - `pockethive.control-plane.orchestrator.docker.compute-adapter=DOCKER_SINGLE|AUTO|SWARM_SERVICE|SWARM_STACK`.
  - Swarm Controller:
    - `pockethive.control-plane.swarm-controller.docker.compute-adapter=DOCKER_SINGLE|SWARM_SERVICE|SWARM_STACK`.
- [ ] Validation rules:
  - `SWARM_STACK` only allowed when Docker endpoint is a Swarm **manager** (same `docker info` check as `SWARM_SERVICE`).
  - `AUTO` remains “single vs. SWARM_SERVICE” only; `SWARM_STACK` must be explicit.

### 4.2 Swarm stack adapter (docker-client)

- [ ] Add `DockerSwarmStackAdapter` in `common/docker-client`:
  - Depends on `DockerClient` and control network supplier.
  - Responsibilities:
    - Given a `ManagerSpec` + `swarmId`:
      - Create a controller service with:
        - Name: `ph-${swarmId.toLowerCase()}-controller`.
        - Network: `ph-${swarmId.toLowerCase()}-net` (overlay, auto‑created if needed).
        - Labels:
          - `ph.swarmId=${swarmId}`
          - `ph.stack=ph-${swarmId.toLowerCase()}`
      - Track controller service id for teardown.
    - Given a topology (`swarmId`, list of `WorkerSpec`s):
      - Create worker services with:
        - Names: `ph-${swarmId.toLowerCase()}-${role}-${index}`.
        - Same network and labels as controller.
      - Track worker service ids per swarm.
    - Remove:
      - All worker services for a swarm.
      - Controller service for that swarm.
      - Optionally the overlay network if the adapter created it.
- [ ] Keep `DockerSwarmServiceComputeAdapter` as “services without explicit stack grouping” for scenarios where stacks are not desired.

### 4.3 Orchestrator integration

- [ ] Update `DockerConfiguration` (orchestrator) to resolve `SWARM_STACK`:
  - `SWARM_STACK` → `DockerSwarmStackAdapter`.
  - Use the same Swarm manager validation as `SWARM_SERVICE`.
- [ ] `ContainerLifecycleManager`:
  - For `SWARM_STACK`:
    - Use stack adapter to start the Swarm Controller (manager service inside the stack).
    - Expose the stack name and shared network name via environment for the Swarm Controller (e.g. `POCKETHIVE_SWARM_STACK_NAME`, `CONTROL_NETWORK`).
  - Ensure teardown (`removeSwarm`) delegates controller + worker removal to the stack adapter, not container ids.

### 4.4 Swarm Controller integration

- [ ] Swarm Controller `SwarmLifecycleManager`:
  - For `SWARM_STACK`:
    - Construct worker `WorkerSpec`s as today, but delegate to `DockerSwarmStackAdapter` rather than the service‑only adapter.
    - Ensure workers join the stack’s overlay network and carry the same labels.
- [ ] Keep BufferGuard and scenario engine logic unchanged; only the provisioning port changes.

### 4.5 UI & diagnostics

- [ ] Extend orchestrator status payloads:
  - Add `data.stackMode = "SWARM_STACK" | "SWARM_SERVICE" | "DOCKER_SINGLE"`.
  - For `SWARM_STACK`, also add `data.stackName = "ph-${swarmId.toLowerCase()}"`.
- [ ] Hive UI:
  - Orchestrator component details:
    - Show `Compute adapter` (already done) and `Stack mode` (if present).
  - Optionally, SC node details:
    - Show `Stack: ph-<swarm>` when in stack mode.

### 4.6 Tests & validation

- [ ] Unit tests for `DockerSwarmStackAdapter`:
  - Creates controller and worker services with expected names, labels, and networks.
  - Removes all services and network on teardown.
- [ ] Orchestrator tests:
  - `SWARM_STACK` resolution and failure when Swarm manager is not available.
  - `ContainerLifecycleManager` uses stack adapter for controller start/remove.
- [ ] Swarm Controller tests:
  - Worker services created under the stack grouping.
- [ ] e2e smoke scenario:
  - Deploy a swarm in `SWARM_STACK` mode and assert:
    - Controller and bees share the stack network.
    - Services are named and labelled with the stack id.

## 5. Notes & follow‑ups

- This plan deliberately keeps stack behaviour **Swarm‑specific**. The longer‑term idea is to mirror similar “deployment group” semantics in other adapters (Kubernetes namespaces, ECS service groups) while keeping the Manager SDK unaware of those details.
- Once `SWARM_STACK` is stable, we can consider de‑emphasising `SWARM_SERVICE` in docs in favour of the more structured stack mode, but both should remain available for now.

