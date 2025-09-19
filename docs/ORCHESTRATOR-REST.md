# PocketHive — Orchestrator REST API

**Base:** `/api` • MIME: `application/json` • Auth: Bearer JWT (RBAC enforced)

## Idempotency & correlation
Client sends **`idempotencyKey`** (UUID v4) per new action (reuse on retry). Server generates **`correlationId`** per attempt and returns:
```json
{
  "correlationId": "uuid-v4",
  "idempotencyKey": "uuid-v4",
  "watch": { "successTopic": "...", "errorTopic": "..." },
  "timeoutMs": 180000
}
```

## 3.0 Create swarm
`POST /api/swarms/{swarmId}/create`

**Behavior**
- Launch Controller runtime for `{swarmId}` (no AMQP signal).
- On controller handshake `ev.ready.swarm-controller.<instance>`, emit **`ev.ready.swarm-create.<swarmId>`** (echo ids).
- On failure, emit **`ev.error.swarm-create.<swarmId>`**.
- Requires a `templateId` referencing the scenario template to instantiate.

**Request**
```json
{
  "templateId": "scenario-id",
  "idempotencyKey": "uuid-v4",
  "notes": "optional"
}
```

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "ev.ready.swarm-create.<swarmId>",
    "errorTopic":   "ev.error.swarm-create.<swarmId>"
  },
  "timeoutMs": 120000
}
```

## 3.1 Start swarm
`POST /api/swarms/{swarmId}/start`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "notes": "optional" }
```

**Signal:** `sig.swarm-start.<swarmId>` → **Success:** `ev.ready.swarm-start.<swarmId>` → **Error:** `ev.error.swarm-start.<swarmId>`

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "ev.ready.swarm-start.<swarmId>",
    "errorTopic":   "ev.error.swarm-start.<swarmId>"
  },
  "timeoutMs": 180000
}
```

## 3.2 Stop swarm (non-destructive)
`POST /api/swarms/{swarmId}/stop`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "notes": "optional" }
```

**Signal:** `sig.swarm-stop.<swarmId>` → **Success:** `ev.ready.swarm-stop.<swarmId>` → **Error:** `ev.error.swarm-stop.<swarmId>`

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "ev.ready.swarm-stop.<swarmId>",
    "errorTopic":   "ev.error.swarm-stop.<swarmId>"
  },
  "timeoutMs": 90000
}
```

## 3.3 Remove swarm (explicit delete)
`POST /api/swarms/{swarmId}/remove`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "notes": "optional" }
```

**Signal:** `sig.swarm-remove.<swarmId>` → **Success:** `ev.ready.swarm-remove.<swarmId>` → **Error:** `ev.error.swarm-remove.<swarmId>`  
**Post‑success:** tear down the Controller runtime for this swarm.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "ev.ready.swarm-remove.<swarmId>",
    "errorTopic":   "ev.error.swarm-remove.<swarmId>"
  },
  "timeoutMs": 180000
}
```

## 3.4 Apply swarm template
`POST /api/swarms/{swarmId}/template`

**Request**
```json
{
  "idempotencyKey": "uuid-v4",
  "swarmPlan": { /* SwarmPlan; components typically enabled=false */ },
  "notes": "optional"
}
```

**Signal:** `sig.swarm-template.<swarmId>` → **Success:** `ev.ready.swarm-template.<swarmId>` → **Error:** `ev.error.swarm-template.<swarmId>`

**Response (202)** — same envelope.

## 4. Components

### 4.1 Update config
`POST /api/components/{role}/{instance}/config`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "patch": { "enabled": true }, "notes": "optional" }
```

**Signal:** `sig.config-update.<role>.<instance>` → **Success:** `ev.ready.config-update.<role>.<instance>` → **Error:** `ev.error.config-update.<role>.<instance>`

**Response (202)** — same envelope.

### 4.2 Status request
`POST /api/components/{role}/{instance}/status-request`

**Request**
```json
{ "idempotencyKey": "uuid-v4" }
```

**Signal:** `sig.status-request.<role>.<instance>` → component emits `ev.status-full.<role>.<instance>` (no `ev.ready.*`).

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": { "infoTopic": "ev.status-full.<role>.<instance>" },
  "timeoutMs": 10000
}
```

### 4.3 Swarm controller enable/disable (bulk)
`POST /api/controllers/config`

**Behavior**
- Publishes **`sig.config-update.swarm-controller.{instance}`** per targeted controller with `patch: { "enabled": true|false }`.
- If **`targets` omitted or empty**, apply to **all registered controllers** (fan-out driven by the Orchestrator's live registry).
- Controllers must keep their control plane session alive and **fan out** the `enabled` change to every managed bee.

**Request**
```json
{
  "idempotencyKey": "uuid-v4",
  "enabled": false,
  "targets": ["swarm-controller.alpha", "swarm-controller.bravo"],
  "notes": "optional"
}
```

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "targets": [
    {
      "instance": "swarm-controller.alpha",
      "successTopic": "ev.ready.config-update.swarm-controller.alpha",
      "errorTopic": "ev.error.config-update.swarm-controller.alpha"
    },
    {
      "instance": "swarm-controller.bravo",
      "successTopic": "ev.ready.config-update.swarm-controller.bravo",
      "errorTopic": "ev.error.config-update.swarm-controller.bravo"
    }
  ],
  "timeoutMs": 60000
}
```

**Notes**
- The Orchestrator reuses the same `correlationId` for every fan-out signal so confirmations map cleanly to the bulk action.
- Controllers acknowledge even when already at the requested `enabled` state (idempotent success).
