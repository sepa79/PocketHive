# PocketHive — ARCHITECTURE

> **Status:** Authoritative architecture specification (reference for agents).  
> **Scope:** Universal runtime (Docker Compose or Kubernetes).  
> **Compatibility:** Control‑plane names remain as in the repo; this file is the single source of truth.

---

## 1. Overview

PocketHive orchestrates message-driven swarms of components (generators, processors, post‑processors, triggers, etc.) coordinated by an **Orchestrator** and a per‑swarm **Swarm Controller**. Communication is over **AMQP** (RabbitMQ). **Health** and **readiness** are inferred from **AMQP status** events; controllers and the orchestrator cannot reach component Actuator endpoints and rely exclusively on control-plane heartbeats.

**Design principles**

- **Single source of truth** for desired state: **Orchestrator**.
- **Aggregate state** per swarm: **Swarm Controller**.
- **Per‑component state**: emitted by **components themselves**, consumed by the **Controller**, **not** by the Orchestrator in steady state.
- **Control plane always on**: status and config are accepted even when workloads are disabled.
- **Scoped controller toggles**: Operators set top-level `commandTarget` metadata together with explicit `swarmId`/`role`/`instance` fields on controller config updates. `commandTarget=swarm` with the swarm identifier toggles every workload the controller manages, while `commandTarget=instance` with `role=swarm-controller` and the controller's instance ID pauses/resumes only the controller runtime. In both cases `enabled` governs work processing while the control plane stays reachable.
- **Non‑destructive defaults**: failures never auto‑delete resources; Stop ≠ Remove.
- **Deterministic ordering** derived from queue I/O topology, not hard‑coded by role.
- **Command → Confirmation pattern**: Every control signal results in **exactly one**
  **success** (`ev.ready.*`) **or** **error** (`ev.error.*`), correlated via `correlationId` and `idempotencyKey`.

---

## 2. Roles (Managers vs. Workers)

PocketHive splits the control plane into **managers** (orchestrator + swarm controllers) and **workers** (generators, moderators, processors, post-processors, triggers, etc.). Managers shape desired state and publish control signals; workers execute workloads and echo health back through the same exchange.

### 2.1 Managers

#### Orchestrator (Queen)
- Owns the **desired state** and lifecycle intents per swarm (`SwarmPlan`).
- Launches a **Swarm Controller** for a new swarm (runtime) and, after the controller handshake, emits **`ev.ready.swarm-create.<swarmId>.orchestrator.ALL`**.
- Publishes swarm-scoped lifecycle commands with routing keys such as `sig.swarm-template.<swarmId>.swarm-controller.ALL` and `sig.swarm-start.<swarmId>.swarm-controller.ALL`.
- Issues **controller config updates** with top-level `commandTarget` metadata: `sig.config-update.<swarmId>.swarm-controller.<instance>` toggles a single controller, while `sig.config-update.ALL.swarm-controller.ALL` broadcasts a fleet-wide enable/disable intent (`commandTarget=all`).
- **Monitors** swarms to **Ready / Running**, marks **Failed** on timeout/error, and **never auto‑deletes** resources.
- Consumes **only swarm-level aggregates** and lifecycle confirmations, keeping fan-in small.

#### Swarm Controller (Marshal)
- Applies the plan locally; **provisions** components; maintains the **aggregate** swarm view.
- Declares the control queue `ph.control.<swarmId>.swarm-controller.<instance>` and binds it to `sig.*.<swarmId>.swarm-controller.<instance>`, `sig.*.<swarmId>.swarm-controller.ALL`, and `sig.*.ALL.swarm-controller.ALL` so it receives per-instance, per-swarm, and global manager broadcasts.
- Emits **swarm-level** lifecycle confirmations (`ev.ready.swarm-start.<swarmId>.swarm-controller.<instance>`, etc.) and periodic status events.
- Treats AMQP `ev.status-{delta|full}` as the **sole heartbeat source**; if a component goes silent it issues `sig.status-request` and marks the component stale if no response arrives.
- When targeted by `sig.config-update.<swarmId>.swarm-controller.<instance>` it inspects the top-level `commandTarget` together with the `swarmId`/`role`/`instance` tuple: `commandTarget=swarm` with the controller's swarm ID → keep the control plane alive and **fan the new workload state out to every bee** via `sig.config-update.<swarmId>.ALL.ALL`; `commandTarget=instance` with `role=swarm-controller` and the local instance ID → pause/resume only its reconciliation loops while emitting controller-specific state (`state.controller.enabled`).
- Control plane stays enabled even when workloads are paused; start/stop/remove/status/config are always honored.

### 2.2 Workers (Bees)
- Declare their own control queues on startup using the `ph.control.<swarmId>.<role>.<instance>` naming pattern and bind only to their swarm: `sig.*.<swarmId>.<role>.<instance>`, `sig.*.<swarmId>.<role>.ALL`, and `sig.*.<swarmId>.ALL.ALL`.
- Consume workloads from queues named `ph.work.<swarmId>.<queueName>` that hang off the swarm's shared work exchange.
- **Do not bind** to `sig.*.ALL...` routes so that only their controller can command them; orchestrator broadcasts always transit through the controller first.
- Emit **their own** status streams (`ev.status-{full|delta}.<swarmId>.<role>.<instance>`) and respond to manager `sig.status-request` heartbeats.
- Apply `sig.config-update.<swarmId>.<role>.<instance>` (`enabled: true|false`) to control **workload** only while keeping control listeners responsive.

---

## 3. Exchanges & routing (wire contract)

> The AsyncAPI defines a **single swarm-aware control signal shape** and unified confirmations/events.

### 3.1 Routing key patterns
- **Signals:** `sig.<signal>.<swarm>.<role>.<instance>`
  - `signal` — command verb (`swarm-template`, `swarm-start`, `config-update`, `status-request`, ...)
  - `swarm` — concrete swarm identifier; use `ALL` for orchestrator broadcasts.
  - `role` — target role (`swarm-controller`, `generator`, ...); use `ALL` when fanning out to every role in the swarm.
  - `instance` — specific instance id or `ALL` for role-wide commands.
- **Confirmations & events:** `ev.<event>.<swarm>.<role>.<instance>` with the same swarm/role/instance semantics. Event names include `ready.<command>`, `error.<command>`, `status-delta`, `status-full`, `lifecycle`, `metric`, and `alert`.

### 3.2 Required bindings

| Actor | Declared control queue | Required bindings |
|---|---|---|
| **Swarm controller** | `ph.control.<swarmId>.swarm-controller.<instance>` | `sig.*.<swarmId>.swarm-controller.<instance>`, `sig.*.<swarmId>.swarm-controller.ALL`, `sig.*.ALL.swarm-controller.ALL` |
| **Generator / Moderator / Processor / Post-processor / Trigger** | `ph.control.<swarmId>.<role>.<instance>` | `sig.*.<swarmId>.<role>.<instance>`, `sig.*.<swarmId>.<role>.ALL`, `sig.*.<swarmId>.ALL.ALL` |
| **Observers (read-only)** | n/a | Subscribe to `ev.ready.*`, `ev.error.*`, `ev.status-*.*` as needed |

> Workers intentionally **do not** bind to `sig.*.ALL...` routes; the controller remains their single command authority. Managers MAY bind to the global pattern to receive orchestrator broadcasts.

### 3.3 Control signals (managers publish)
- `sig.swarm-template.<swarmId>.swarm-controller.ALL` — send/replace `SwarmPlan` (components start `enabled=false`).
- `sig.swarm-start.<swarmId>.swarm-controller.ALL` — start workloads in dependency order.
- `sig.swarm-stop.<swarmId>.swarm-controller.ALL` — **non-destructive stop** (disable workloads, keep resources).
- `sig.swarm-remove.<swarmId>.swarm-controller.ALL` — explicit deprovision/delete.
- `sig.config-update.<swarmId>.swarm-controller.<instance>` — toggle a specific controller (`commandTarget=instance`).
- `sig.config-update.<swarmId>.ALL.ALL` — controller-to-bee broadcast within a swarm (`commandTarget=swarm`).
- `sig.config-update.ALL.swarm-controller.ALL` — orchestrator broadcast to every controller (`commandTarget=all`).
- `sig.status-request.<swarmId>.<role>.<instance>` — request an immediate `status-full` from a worker.

**`ControlSignal` essentials:** `correlationId` *(uuid, new per attempt)*, `idempotencyKey` *(uuid, stable across retries)*, `swarmId`/`role`/`instance` segments mirroring the routing key, `commandTarget` (`all|swarm|role|instance`), and an `args` object for command-specific data (patch payloads remain unchanged).

### 3.4 Confirmations (events)
- **Success (`ev.ready.*`):**
  - `ev.ready.swarm-create.<swarmId>.orchestrator.ALL` — emitted by the Orchestrator after controller handshake.
  - `ev.ready.swarm-template.<swarmId>.swarm-controller.<instance>` — Controller.
  - `ev.ready.swarm-start.<swarmId>.swarm-controller.<instance>` — Controller.
  - `ev.ready.swarm-stop.<swarmId>.swarm-controller.<instance>` — Controller.
  - `ev.ready.swarm-remove.<swarmId>.swarm-controller.<instance>` — Controller.
  - `ev.ready.config-update.<swarmId>.<role>.<instance>` — Controller, echoing the final target scope.
- **Error (`ev.error.*`):** mirror the success topics with `error` in place of `ready`.

> Controller config confirmations mirror the top-level `commandTarget`, keep the authoritative scope in the confirmation envelope, and surface enablement data via `state.enabled` plus `state.details.workloads.enabled` or `state.details.controller.enabled` so observers can distinguish workload and controller toggles without duplicated scope fields.

### 3.5 Status & telemetry streams
- **Swarm aggregates (Controller):** `ev.status-full.<swarmId>.swarm-controller.<instance>` and `ev.status-delta.<swarmId>.swarm-controller.<instance>`.
- **Per-component status:** `ev.status-full.<swarmId>.<role>.<instance>` and `ev.status-delta.<swarmId>.<role>.<instance>`.
- **Controller bootstrap handshake:** `ev.ready.swarm-controller.<swarmId>.swarm-controller.<instance>`.
- **Lifecycle milestones:** `ev.lifecycle.<swarmId>.<role>.<instance>`.
- **Metrics & alerts:** `ev.metric.<swarmId>.<role>.<instance>` / `ev.alert.<swarmId>.<role>.<instance>`.

---

## 4. Health & heartbeat model

- **AMQP `status-{delta|full}` events are the only heartbeat source.**
- If **no AMQP status** arrives within a **TTL** for a component included in the aggregate, the Controller **issues `sig.status-request`** and marks the component **Degraded/Unknown** if no response arrives in time.
- Every **swarm aggregate** carries a **watermark timestamp** and **max-staleness**; if stale or incomplete, the Controller emits **Degraded/Unknown**.

---

## 5. Lifecycle & states

### 5.1 Swarm lifecycle (Orchestrator view)
```
New → Creating → Ready → Starting → Running
                     ↘ Failed ↙        → Stopping → Stopped → Removing → Removed
```
- **Creating:** Controller launched; success signalled by **`ev.ready.swarm-create`**.
- **Ready:** plan applied; all desired components reporting Healthy via AMQP status events with `enabled=false`.
- **Failed:** an error or timeout occurred; **resources are preserved** for debugging.

### 5.2 Component lifecycle (aggregate perspective)
```
New → Provisioning → Healthy(enabled=false) → Starting → Running(enabled=true)
                                               ↘ Failed ↙               → Stopping → Stopped
```
> Per‑component transitions are **emitted by components**; the Controller **aggregates** only.

---

## 6. Dependency ordering (queue I/O graph)

Construct a directed graph where **A → B** if **A produces** to a queue that **B consumes**.

- **Create/Start order:** producers → transformers → consumers (topological order).
- **Stop order:** reverse of start order.
- Cycles/ambiguity → choose a stable order and emit a **warning** event with the heuristic used.

---

## 7. Sequences

> Rendering note: Mermaid messages avoid semicolons to prevent parser hiccups.

### 7.1 Create → Template (no auto‑start)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller
  participant RT as Runtime (Docker/K8s)

  QN->>RT: Launch Controller for <swarmId>
  RT-->>QN: Controller container up
  MSH-->>QN: ev.ready.swarm-controller.<swarmId>.swarm-controller.<instance>
  QN-->>QN: ev.ready.swarm-create.<swarmId>.orchestrator.ALL

  QN->>MSH: sig.swarm-template.<swarmId>.swarm-controller.ALL (SwarmPlan, all enabled=false)
  MSH->>MSH: Provision component containers and processes
  MSH-->>QN: ev.ready.swarm-template.<swarmId>.swarm-controller.<instance>
```

### 7.2 Start whole swarm
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller
  participant CMP as Components

  QN->>MSH: sig.swarm-start.<swarmId>.swarm-controller.ALL
  MSH->>MSH: Enable components in derived dependency order
  CMP-->>MSH: ev.status-delta.<swarmId>.<role>.<instance> (enabled=true)
  MSH-->>QN: ev.ready.swarm-start.<swarmId>.swarm-controller.<instance>
```

### 7.3 Per‑component enable/disable (via config‑update)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller
  participant CMP as Component

  QN->>MSH: sig.config-update.<swarmId>.<role>.<instance> ({ enabled: true|false, ... })
  MSH->>CMP: Apply config (control plane always on)
  CMP-->>MSH: ev.status-delta.<swarmId>.<role>.<instance> (enabled reflected)
  MSH-->>QN: ev.ready.config-update.<swarmId>.<role>.<instance>
```

### 7.3b Bulk controller suspend/resume (`commandTarget=swarm`)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controllers
  participant CMP as Bees

  QN->>MSH: sig.config-update.ALL.swarm-controller.ALL ({ commandTarget: "swarm", swarmId: "<swarmId>", args: { data: { enabled: true|false } } })
  note right of QN: Fan-out per controller instance (shared correlationId)
  MSH->>CMP: Propagate enabled flag via sig.config-update.<swarmId>.ALL.ALL (control plane stays up)
  CMP-->>MSH: ev.status-delta.<swarmId>.<role>.<instance> (workloads.enabled reflected)
  MSH-->>QN: ev.ready.config-update.<swarmId>.ALL.ALL (scope.swarmId=<swarmId>, state.details.workloads.enabled reflected)
```

### 7.3c Controller runtime pause/resume (`commandTarget=instance`)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller

  QN->>MSH: sig.config-update.<swarmId>.swarm-controller.<instance> ({ commandTarget: "instance", role: "swarm-controller", instance: "<instance>", args: { data: { enabled: true|false } } })
  note right of QN: Runtime loops pause/resume, bees untouched
  MSH-->>QN: ev.ready.config-update.<swarmId>.swarm-controller.<instance> (scope.role=swarm-controller, scope.instance=<instance>, state.details.controller.enabled reflected)
  MSH-->>QN: ev.status-delta.<swarmId>.swarm-controller.<instance> (controller.enabled reflected)
```

### 7.4 Stop whole swarm (non‑destructive)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller
  participant CMP as Components

  QN->>MSH: sig.swarm-stop.<swarmId>.swarm-controller.ALL
  MSH->>MSH: Disable workload in reverse dependency order
  CMP-->>MSH: ev.status-delta.<swarmId>.<role>.<instance> (enabled=false)
  MSH-->>QN: ev.ready.swarm-stop.<swarmId>.swarm-controller.<instance>
```

### 7.5 Remove swarm (explicit delete)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller
  participant RT as Runtime

  QN->>MSH: sig.swarm-remove.<swarmId>.swarm-controller.ALL
  MSH->>MSH: Ensure Stopped and deprovision components
  MSH->>RT: Delete component resources
  MSH-->>QN: ev.ready.swarm-remove.<swarmId>.swarm-controller.<instance>
  QN->>RT: Remove Controller for <swarmId>
```

### 7.6 Failure during create/start (no deletion)
```mermaid
sequenceDiagram
  participant QN as Orchestrator
  participant MSH as Swarm Controller
  participant CMP as Components
  participant RT as Runtime

  QN->>RT: Launch Controller for <swarmId>
  alt Launch fails
    QN-->>QN: ev.error.swarm-create.<swarmId>.orchestrator.ALL (reason)
  else Controller up
    MSH-->>QN: ev.ready.swarm-controller.<swarmId>.swarm-controller.<instance>
    QN-->>QN: ev.ready.swarm-create.<swarmId>.orchestrator.ALL
    QN->>MSH: sig.swarm-template.<swarmId>.swarm-controller.ALL
    MSH->>RT: Provision components
    CMP-->>MSH: ev.status-delta.<role>.<instance> (health=DOWN) or no status within TTL
    MSH->>CMP: sig.status-request.<swarmId>.<role>.<instance>
    MSH-->>QN: ev.error.swarm-template.<swarmId>.swarm-controller.<instance> (aggregate Failed)
  end
```

---

## 8. Timeouts & cadence (defaults)

> Applied unless stricter values exist in code or plan.

- **Provisioning timeout (per component):** 120s  
- **Ready timeout (swarm total):** 5m  
- **Start timeout (per component):** 60s  
- **Start timeout (swarm total):** 3m  
- **Graceful stop timeout (per component):** 30s, then force‑stop (report degraded)  
- **Controller heartbeats:** `ev.status-{delta|full}.swarm-controller.<instance>` on **state change** + **every 10s** (aggregate watermark).

---

## 9. Idempotency & delivery

- Control messages carry an **idempotency key** (UUID) and `correlationId`; delivery is **at‑least‑once**.
- The Swarm Controller now executes **every attempt**. It no longer caches outcomes, so callers must avoid reusing `idempotencyKey`
  values unless they intentionally want the command re-applied.
- Upstream components may still perform their own idempotency checks, but the controller simply emits a fresh confirmation for
  each attempt.

---

## 10. Observability & metrics

**Controller aggregates** include:
- `ts` (watermark), `swarmId`, and `{total, healthy, running, enabled}` counts.
- **Max staleness** and, when applicable, **Degraded/Unknown** reason.
- Recent **error summaries** (role/instance, reason, correlationId) for operator drill‑down.

**Orchestrator** surfaces:
- Provision/ready/start durations, failure counts by reason, current running/enabled counts, queue connection summaries.

---

## 11. Security & audit

- Only the **Orchestrator** issues swarm lifecycle signals; UI proxies via Orchestrator.
- All actions/events are stamped with `correlationId`; per‑swarm audit logs are retained.
- Controller subscribes/publishes strictly within its `{swarmId}` namespace.
- UI AMQP creds are **read‑only**; all writes via Orchestrator REST.

---

## 12. Command envelopes

### Control signal (`sig.*`)
```json
{
  "signal": "config-update",
  "correlationId": "uuid-from-orchestrator",
  "idempotencyKey": "uuid-reused-for-retries",
  "swarmId": "alpha",
  "role": "generator",
  "instance": "ALL",
  "commandTarget": "role",
  "args": {
    "enabled": true,
    "workloads": {
      "enabled": true
    }
  }
}
```

### Success (`ev.ready.*`)
```json
{
  "ts": "2025-09-12T12:34:56Z",
  "correlationId": "uuid-from-signal-or-runtime-op",
  "idempotencyKey": "uuid-from-signal",
  "signal": "swarm-create|swarm-start|swarm-stop|swarm-template|swarm-remove|config-update",
  "scope": {"swarmId":"...", "role":null, "instance":null},
  "commandTarget": "swarm|instance|role|all",
  "result": "success",
  "state": {
    "scope": {"swarmId":"...", "role":null, "instance":null},
    "enabled": true,
    "workloads": { "enabled": true },
    "controller": { "enabled": true }
  },
  "notes": "optional text"
}
```

### Error (`ev.error.*`)
```json
{
  "ts": "2025-09-12T12:34:56Z",
  "correlationId": "uuid-from-signal-or-runtime-op",
  "idempotencyKey": "uuid-from-signal",
  "signal": "swarm-create|swarm-start|swarm-stop|swarm-template|swarm-remove|config-update",
  "scope": {"swarmId":"...", "role":null, "instance":null},
  "commandTarget": "swarm|instance|role|all",
  "result": "error",
  "phase": "create|template|start|stop|remove|runtime",
  "code": "SHORT_CODE",
  "message": "Human-readable summary",
  "retryable": true,
  "details": {"context": "last logs, probe, etc."}
}
```

---

## 13. Changelog (normative changes)

- **Create flow:** no `sig.swarm-create`; success is **`ev.ready.swarm-create.<swarmId>.orchestrator.ALL`** emitted by the Orchestrator after controller handshake.
- **Removed milestones:** `ev.ready.swarm-template.<swarmId>` and `ev.ready.swarm-remove.<swarmId>` are replaced by **`ev.ready.swarm-template`** and **`ev.ready.swarm-remove`** confirmations respectively.  
- **Remove flow:** upon **`ev.ready.swarm-remove.<swarmId>`**, the Orchestrator **removes the Swarm Controller** runtime unit.
- **Unified envelopes:** all commands use `ControlSignal`; confirmations echo `correlationId` and `idempotencyKey`.
- **Signals updated:** drop legacy `type`/`version`/`messageId`/`timestamp` fields; payload is unified `ControlSignal` metadata (`signal`, scope fields, `commandTarget`, and optional `args`).
