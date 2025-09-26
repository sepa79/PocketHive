# Control Plane Rules (Comprehensive)

**Exchange:** `ph.control` (topic)  
**Scope:** Normative rules for producing and consuming control-plane messages and status events.  
**Audience:** Orchestrator, Swarm Controller, Components, UI/observers.

---

## 1) Purpose & principles

- **Single control surface**: One unified control-signal shape for all actions.
- **Deterministic queue naming**: Control queues MUST follow `ph.control.<swarmId>.<role>.<instance>`; workload queues hang off the swarm work exchange as `ph.work.<swarmId>.<queueName>`.
- **Command → Confirmation**: Every control action yields **exactly one** `ev.ready.*` (success) or `ev.error.*` (error), correlated by IDs.
- **Aggregate-first**: Orchestrator consumes **swarm aggregates**; per-component status is for Controller and observability.
- **Always-on control**: Config & status always handled, even when workload is disabled.
- **Controller-level toggles**: Downstream services MUST inspect top-level `commandTarget` plus the `swarmId`/`role`/`instance` tuple on `sig.config-update.<swarm>.swarm-controller.<instance|ALL>`. `commandTarget=swarm` with the controller's swarm ID pauses/resumes all workloads it manages, while `commandTarget=instance` with `role=swarm-controller` and the controller instance ID pauses/resumes only the controller runtime. In both cases the control plane stays responsive.
- **Non-destructive default**: Stop ≠ Remove. Removal is explicit and terminal.

---

## 2) Actors & responsibilities

- **Orchestrator**
  - Only publisher of swarm **lifecycle** signals (template/start/stop/remove).
  - Launches Controller via runtime. On controller handshake emits **`ev.ready.swarm-create.<swarmId>.orchestrator.ALL`**.
  - Enforces idempotency, retries, timeouts, and RBAC.
  - Issues controller-level `sig.config-update.<swarmId>.swarm-controller.<instance>` with top-level `commandTarget` metadata to pause/resume **workloads** (`commandTarget=swarm`, controller fans out to bees) or only the **controller runtime** (`commandTarget=instance`, reconciliation paused). For fleet-wide bulk actions use `sig.config-update.ALL.swarm-controller.ALL` (`commandTarget=all`).
  - Tears down Controller after **`ev.ready.swarm-remove.<swarmId>.swarm-controller.<instance>`**.

- **Swarm Controller**
  - Applies `SwarmPlan`; provisions components; computes **aggregate** status.
  - Emits `ev.status-{full|delta}.<swarmId>.swarm-controller.<instance>` and confirmations for template/start/stop/remove/config.
  - Treats AMQP status as heartbeat; polls Actuator if stale.
  - On controller-level `config-update` toggles, inspects top-level `commandTarget` plus the scope tuple: `commandTarget=swarm` with the local swarm ID → stay reachable and **fan the `enabled` flag out to every managed bee** via `sig.config-update.<swarmId>.ALL.ALL`; `commandTarget=instance` with `role=swarm-controller`/local instance → pause/resume its own reconciliation/runtime only. Emits `ev.status-delta.<swarmId>.swarm-controller.<instance>` showing `state.details.workloads.enabled` or `state.details.controller.enabled` accordingly.

- **Components**
  - Emit their own `ev.status-{full|delta}.<swarmId>.<role>.<instance>`.
  - Apply `sig.config-update.<swarmId>.<role>.<instance>` to **workload** only (control-plane stays live) and also bind to `sig.*.<swarmId>.<role>.ALL` plus `sig.*.<swarmId>.ALL.ALL` for swarm broadcasts.

- **UI/Observers**  
  - **Read-only** AMQP (STOMP) recommended; all **writes** via Orchestrator REST.  
  - Subscribe to `ev.ready.*`, `ev.error.*`, and controller aggregates.

---

## 3) Routing keys (topics)

### 3.1 Pattern overview
- **Signals:** `sig.<signal>.<swarm>.<role>.<instance>`
  - `signal` — command verb (`swarm-template`, `swarm-start`, `swarm-stop`, `swarm-remove`, `config-update`, `status-request`, ...).
  - `swarm` — specific swarm id or `ALL` for orchestrator broadcasts.
  - `role` — target role (e.g., `swarm-controller`, `generator`, `moderator`, `processor`, `postprocessor`, `trigger`, `orchestrator`) or `ALL` for swarm-wide fan-outs.
  - `instance` — unique instance identifier or `ALL` for role-wide commands.
- **Events & confirmations:** `ev.<event>.<swarm>.<role>.<instance>` with the same segment semantics. Event names include `ready.<command>`, `error.<command>`, `status-delta`, `status-full`, `lifecycle`, `metric`, and `alert`.

### 3.2 Control signals (publishers → consumers)
- `sig.swarm-template.<swarmId>.swarm-controller.ALL` — Orchestrator → Controller.
- `sig.swarm-start.<swarmId>.swarm-controller.ALL` — Orchestrator → Controller.
- `sig.swarm-stop.<swarmId>.swarm-controller.ALL` — Orchestrator → Controller *(non-destructive)*.
- `sig.swarm-remove.<swarmId>.swarm-controller.ALL` — Orchestrator → Controller *(delete resources)*.
- `sig.config-update.<swarmId>.swarm-controller.<instance>` — Orchestrator → Controller *(inspect `commandTarget` to decide swarm vs. controller runtime toggles)*.
- `sig.config-update.ALL.swarm-controller.ALL` — Orchestrator → All controllers *(bulk workload enable/disable)*.
- `sig.config-update.<swarmId>.ALL.ALL` — Controller → Swarm members *(broadcast workload toggles within the swarm).* 
- `sig.status-request.<swarmId>.<role>.<instance>` — Orchestrator/Controller → Component *(emit `status-full` now).* 

### 3.3 Confirmations & errors (emitters → observers)
- **Success (`ev.ready.*`):**
  - `ev.ready.swarm-create.<swarmId>.orchestrator.ALL` — Orchestrator → Observers.
  - `ev.ready.swarm-template.<swarmId>.swarm-controller.<instance>` — Controller → Observers.
  - `ev.ready.swarm-start.<swarmId>.swarm-controller.<instance>` — Controller → Observers.
  - `ev.ready.swarm-stop.<swarmId>.swarm-controller.<instance>` — Controller → Observers.
  - `ev.ready.swarm-remove.<swarmId>.swarm-controller.<instance>` — Controller → Observers.
  - `ev.ready.config-update.<swarmId>.<role>.<instance>` — Controller → Observers.
- **Error (`ev.error.*`):** mirror the success routes with `error` in place of `ready`.

### 3.4 Status streams & bootstrap
- **Aggregate (Controller):** `ev.status-{full|delta}.<swarmId>.swarm-controller.<instance>`.
- **Per-component:** `ev.status-{full|delta}.<swarmId>.<role>.<instance>`.
- **Bootstrap:** `ev.ready.swarm-controller.<swarmId>.swarm-controller.<instance>` (controller just came up).

> Controller status events MUST surface both `state.details.workloads.enabled` and `state.details.controller.enabled` whenever they change so observers can correlate with the corresponding `scope` flow.

---

## 4) Message envelopes

### 4.1 Control signals (publishers MUST include)
- `signal` *(string)* — command identifier (`swarm-start`, `config-update`, ...)
- `correlationId` *(uuid)* — **new per attempt**
- `idempotencyKey` *(uuid)* — **stable across retries** of the same action
- Scope: include whichever of `swarmId`, `role`, `instance` apply
- Optional `args` object with command-specific parameters (workload-specific patches stay in `args`/`patch`)
- Top-level `commandTarget` *(required)* guides routing in concert with the explicit `swarmId`/`role`/`instance` fields. `commandTarget=swarm` with a swarm identifier signals workload fan-out; `commandTarget=instance` with `role=swarm-controller` and the controller instance pauses/resumes the controller runtime. Other workloads typically use `commandTarget=instance` with their own role/instance values.

### 4.2 Confirmations (emitters MUST include)
- Echo **`correlationId`** and **`idempotencyKey`** from the initiating control signal (or from the runtime op for create).
- `signal`, `result` (`success`|`error`), `scope` (`swarmId`/`role`/`instance`), `ts`
- Success MAY include `state` (`Ready|Running|Stopped|Removed`), structured command metadata, and `notes`. Config confirmations MUST mirror `commandTarget` at the top level, rely on the envelope `scope`, and include `state.enabled` plus the relevant enablement details (`state.details.workloads.enabled` for swarm fan-outs, `state.details.controller.enabled` for controller runtime toggles) so observers can rely on the semantics without duplicated identifiers.
- Error MUST include `code`, `message`; MAY include `phase`, `retryable`, `details`

### 4.3 Status & bootstrap
- SHOULD include `messageId` and `timestamp`
- MAY include `correlationId` when directly answering `sig.status-request`

---

## 5) Idempotency & retries

- Delivery is **at-least-once**. Receivers MUST tolerate replays; the swarm controller no longer performs automatic
  deduplication.
- Consumers MAY use `idempotencyKey` to guard side-effects locally, but the shared controller simply processes each attempt and
  emits a fresh confirmation.
- UI **retries** must reuse **the same `idempotencyKey`**; Orchestrator creates a new `correlationId` per attempt.
- Suggested retention: ≥ duration of the longest user retry horizon (e.g., 24h).

---

## 6) Heartbeats & freshness

- **Controller aggregates:** `status-delta` on change + at least every **10s** (watermark), optional periodic `status-full`.
- **Components:** `status-full` on startup and upon `status-request`; `status-delta` on change.
- If a component is **stale** beyond TTL, Controller **polls Actuator** before asserting Ready/Running.

---

## 7) Ordering & semantics

- **Start order:** producers → transformers → consumers (topological).  
- **Stop order:** exact reverse.  
- Cycles → choose a stable order + publish a **warning** with the heuristic used.
- `sig.swarm-stop.<swarmId>.swarm-controller.ALL` disables workload but **preserves** resources.
- `sig.swarm-remove.<swarmId>.swarm-controller.ALL` **deletes** resources; on success, Orchestrator removes the Controller.

---

## 8) Security, RBAC, and AMQP ACLs

- UI **publishing** is disallowed in production. UI should have **read-only** AMQP creds.  
- Orchestrator is the **only publisher** for swarm lifecycle signals.  
- Enforce RBAC: who may start/stop/remove/template/config.  
- Rate-limit commands per user/tenant; protect against floods.  
- Audit every accepted action with `idempotencyKey`, `correlationId`, identity, and scope.

---

## 9) Mapping (action → confirmation)

| Action | Success confirmation | Error confirmation |
|---|---|---|
| **Create (runtime)** | REST *(Orchestrator)* | REST *(Orchestrator)* |
| `sig.swarm-template.<swarmId>.swarm-controller.ALL` | `ev.ready.swarm-template.<swarmId>.swarm-controller.<instance>` | `ev.error.swarm-template.<swarmId>.swarm-controller.<instance>` |
| `sig.swarm-start.<swarmId>.swarm-controller.ALL` | `ev.ready.swarm-start.<swarmId>.swarm-controller.<instance>` | `ev.error.swarm-start.<swarmId>.swarm-controller.<instance>` |
| `sig.swarm-stop.<swarmId>.swarm-controller.ALL` | `ev.ready.swarm-stop.<swarmId>.swarm-controller.<instance>` | `ev.error.swarm-stop.<swarmId>.swarm-controller.<instance>` |
| `sig.swarm-remove.<swarmId>.swarm-controller.ALL` | `ev.ready.swarm-remove.<swarmId>.swarm-controller.<instance>` | `ev.error.swarm-remove.<swarmId>.swarm-controller.<instance>` |
| `sig.config-update.<swarmId>.<role>.<instance>` | `ev.ready.config-update.<swarmId>.<role>.<instance>` | `ev.error.config-update.<swarmId>.<role>.<instance>` |

> UIs should use confirmations to complete user actions; `status-*` drives live progress indicators.

---

## 10) Topic patterns (subscription guidance)

- Confirmations: `ev.ready.*` and `ev.error.*` (filter by `<swarm>.<role>.<instance>` as needed).
- Controller aggregates: `ev.status-{full|delta}.<swarmId>.swarm-controller.*`
- Per-component panels: `ev.status-{full|delta}.<swarmId>.<role>.*` (filter in client)
- Bootstrap (internal): `ev.ready.swarm-controller.<swarmId>.swarm-controller.*`

---

## 11) Backward compatibility (changes)

- **Unified payloads:** all control signals use `ControlSignal`; confirmations echo ids.

---

## 12) Examples

**Control signal (swarm start)**
```json
{
  "signal": "swarm-start",
  "swarmId": "swarm-42",
  "role": "swarm-controller",
  "instance": "ALL",
  "correlationId": "attempt-001-aaaa-bbbb",
  "idempotencyKey": "a1c3-1111-2222-9f",
  "commandTarget": "swarm"
}
```

**Confirmation (success)**
```json
{
  "result": "success",
  "signal": "swarm-start",
  "scope": {"swarmId": "swarm-42", "role": "swarm-controller", "instance": "controller-1"},
  "state": {
    "status": "Running",
    "scope": {"swarmId": "swarm-42"},
    "enabled": true,
    "details": {
      "workloads": {"enabled": true}
    }
  },
  "idempotencyKey": "a1c3-1111-2222-9f",
  "correlationId": "attempt-001-aaaa-bbbb",
  "ts": "2025-09-12T12:30:08Z"
}
```

**Create success (Orchestrator-emitted)**
```json
{
  "result": "success",
  "signal": "swarm-create",
  "scope": {"swarmId": "swarm-42", "role": "orchestrator", "instance": "ALL"},
  "idempotencyKey": "create-7777-eeee",
  "correlationId": "attempt-001-cccc",
  "ts": "2025-09-12T12:01:02Z"
}
```

**Controller config success (`scope=swarm`)**
```json
{
  "result": "success",
  "signal": "config-update",
  "scope": {"role": "swarm-controller", "instance": "swarm-controller.alpha"},
  "state": {"enabled": false, "details": {"workloads": {"enabled": false}}},
  "idempotencyKey": "bulk-toggle-1234",
  "correlationId": "attempt-009-zzzz",
  "ts": "2025-09-12T12:05:00Z"
}
```

**Controller config success (`scope=controller`)**
```json
{
  "result": "success",
  "signal": "config-update",
  "scope": {"role": "swarm-controller", "instance": "swarm-controller.alpha"},
  "state": {"enabled": false, "details": {"controller": {"enabled": false}}},
  "idempotencyKey": "ctrl-toggle-5678",
  "correlationId": "attempt-010-yyyy",
  "ts": "2025-09-12T12:06:00Z"
}
```

**Controller aggregate (status-delta)**
```json
{
  "ts": "2025-09-12T12:34:56Z",
  "role": "swarm-controller",
  "instance": "ctrl-1",
  "kind": "status-delta",
  "state": "Running",
  "totals": {"desired": 8, "healthy": 8, "running": 8, "enabled": 8},
  "watermark": "2025-09-12T12:34:55Z",
  "maxStalenessSec": 10
}
```

---

## 13) Compliance & validation

- Validate envelopes and topics against `docs/spec/asyncapi.yaml`.  
- Automated tests should assert: one confirmation per command attempt; ids echo; replayed attempts emit fresh confirmations;
  staleness handling; ordering on start/stop.
