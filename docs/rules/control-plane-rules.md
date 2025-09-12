# Control Plane Rules

**Exchange:** `ph.control`  
**Audience:** agents and services producing/consuming control messages & status events.

---

## 1) Scope and actors

- **Orchestrator** — source of desired state and swarm lifecycle commands. Consumes **swarm-level aggregates** and confirmations.
- **Swarm Controller** — applies the plan, provisions components, emits **swarm aggregates**, and confirms commands.
- **Components** — expose HTTP Actuator, emit their own **status** events, apply **config updates**. Components **do not** emit `ev.ready.*`.

The **control plane is always on**. A component with `enabled=false` must still accept config and status requests and publish status.

---

## 2) Routing keys (topics)

### 2.1 Control (signals) — unified shape
Publisher → Consumer
- `sig.swarm-create.<swarmId>` — Orchestrator → Controller
- `sig.swarm-template.<swarmId>` — Orchestrator → Controller
- `sig.swarm-start.<swarmId>` — Orchestrator → Controller
- `sig.swarm-stop.<swarmId>` — Orchestrator → Controller *(non-destructive)*
- `sig.swarm-remove.<swarmId>` — Orchestrator → Controller *(delete resources)*
- `sig.config-update.<role>.<instance>` — Orchestrator → Component
- `sig.status-request.<role>.<instance>` — Orchestrator/Controller → Component *(emit `status-full` now)*

**Signal payload (excerpt):**
- `correlationId` *(uuid)* — new **per attempt**
- `idempotencyKey` *(uuid)* — **stable across retries** of the same action
- `swarmId` / `role` / `instance` / optional `args` as required

> All control signals MUST carry `correlationId` and `idempotencyKey`.

### 2.2 Command confirmations (events)
Emitter → Consumer
- **Success:**  
  - `ev.ready.swarm-template.<swarmId>` — Controller → Orchestrator  
  - `ev.ready.swarm-start.<swarmId>` — Controller → Orchestrator  
  - `ev.ready.swarm-stop.<swarmId>` — Controller → Orchestrator  
  - `ev.ready.config-update.<role>.<instance>` — Controller → Orchestrator
- **Error:**  
  - `ev.error.swarm-create.<swarmId>` — Orchestrator → Orchestrator/Observers  
  - `ev.error.swarm-template.<swarmId>` — Controller → Orchestrator  
  - `ev.error.swarm-start.<swarmId>` — Controller → Orchestrator  
  - `ev.error.swarm-stop.<swarmId>` — Controller → Orchestrator  
  - `ev.error.swarm-remove.<swarmId>` — Controller → Orchestrator  
  - `ev.error.config-update.<role>.<instance>` — Controller → Orchestrator

**Confirmation payloads MUST echo:**

**Orchestrator action on remove:** upon receiving `ev.ready.swarm-remove.<swarmId>`, tear down the Swarm Controller (stop/delete pod/container) and finalize the operation. `correlationId`, `idempotencyKey`, `signal`, `scope`, `result` (`success` or `error`).

### 2.3 Lifecycle milestones (events)
- `ev.swarm-created.<swarmId>` — informational
- `ev.ready.swarm-remove.<swarmId>` — success confirmation for remove

> Milestones represent state transitions and MAY NOT carry correlation/idempotency. UIs should drive spinners off **confirmations** and display milestones as state changes.

### 2.4 Status streams (events)
- **Swarm aggregate (from Controller):**  
  `ev.status-full.swarm-controller.<instance>` and `ev.status-delta.swarm-controller.<instance>`
- **Per-component status:**  
  `ev.status-full.<role>.<instance>` and `ev.status-delta.<role>.<instance>`
- **Controller bootstrap handshake:**  
  `ev.ready.swarm-controller.<instance>` (control plane up)

---

## 3) Heartbeats and freshness

- AMQP `status-{delta|full}` events are treated as **heartbeats**.
- **Controller** emits aggregate `status-delta` on change **and** at least every **10s** (watermark). It may emit `status-full` on demand or at longer cadence.
- **Components** emit:
  - `status-full` on startup and upon `sig.status-request`
  - `status-delta` on state changes
- If a component’s status is **stale** beyond TTL, the Controller **polls HTTP Actuator** before asserting Ready/Running.

---

## 4) Ordering & semantics

- Dependency order is derived from the **queue I/O graph**: producers → transformers → consumers. Stop order is the reverse.
- `sig.swarm-stop` **disables workload** but preserves resources.  
  `sig.swarm-remove` **deletes** provisioned resources.
- The Orchestrator **consumes only swarm‑level aggregates** and confirmations, not per‑component updates.
- Components **do not** emit `ev.ready.*` (status is sufficient).

---

## 5) Idempotency & delivery

- Delivery is **at-least-once**. Do not rely on exactly-once semantics.
- Receivers MUST deduplicate control signals by `(swarmId, signal, idempotencyKey)` within a retention window.
- On duplicate: do not re-execute; **replay** the prior outcome and re-emit the same confirmation.
- Logs/metrics SHOULD index attempts by `correlationId` and user actions by `idempotencyKey`.

---

## 6) Minimal mapping (command → confirmation)

| Signal (topic) | Success confirmation | Error confirmation |
|---|---|---|
| `sig.swarm-create.<swarmId>` | `ev.swarm-created.<swarmId>` *(milestone only)* | `ev.error.swarm-create.<swarmId>` |
| `sig.swarm-template.<swarmId>` | `ev.ready.swarm-template.<swarmId>` | `ev.error.swarm-template.<swarmId>` |
| `sig.swarm-start.<swarmId>` | `ev.ready.swarm-start.<swarmId>` | `ev.error.swarm-start.<swarmId>` |
| `sig.swarm-stop.<swarmId>` | `ev.ready.swarm-stop.<swarmId>` | `ev.error.swarm-stop.<swarmId>` |
| `sig.swarm-remove.<swarmId>` | `ev.ready.swarm-remove.<swarmId>` | `ev.error.swarm-remove.<swarmId>` |
| `sig.config-update.<role>.<instance>` | `ev.ready.config-update.<role>.<instance>` | `ev.error.config-update.<role>.<instance>` |

> UIs should consider confirmations authoritative for completing user actions; milestones are informative transitions.

---

## 7) Envelopes (normative fields)

### 7.1 Control signals (publishers MUST include)
- `correlationId` *(uuid)* — new per attempt
- `idempotencyKey` *(uuid)* — stable across retries
- `messageId` *(uuid)* — unique per message
- `timestamp` *(ISO-8601 UTC)*
- `swarmId` / `role` / `instance` / optional `args`

### 7.2 Confirmations (emitters MUST include)
- Echo the **`correlationId`** and **`idempotencyKey`** from the signal
- `signal`, `result` (`success`|`error`), `scope` (`swarmId`/`role`/`instance`), and `ts`
- For errors, include `code`, `message`, optional `phase`, `retryable`, and `details`

### 7.3 Status & milestones
- SHOULD include `messageId` and `timestamp`
- MAY include `correlationId` when directly answering `sig.status-request`

---

## 8) Examples

**Control signal (swarm start)**
```json
{
  "signal": "swarm-start",
  "swarmId": "swarm-42",
  "idempotencyKey": "a1c3-1111-2222-9f",
  "correlationId": "attempt-001-aaaa-bbbb",
  "messageId": "msg-123",
  "timestamp": "2025-09-12T12:30:00Z"
}
```

**Confirmation (success)**
```json
{
  "result": "success",
  "signal": "swarm-start",
  "scope": {"swarmId": "swarm-42"},
  "state": "Running",
  "idempotencyKey": "a1c3-1111-2222-9f",
  "correlationId": "attempt-001-aaaa-bbbb",
  "ts": "2025-09-12T12:30:08Z"
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

## 9) Compliance

- Tools MUST validate routing keys and envelopes against `docs/spec/asyncapi.yaml`.
- Services MUST follow these rules to interoperate across operators and runtimes.
