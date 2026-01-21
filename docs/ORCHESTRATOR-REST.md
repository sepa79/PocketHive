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

## 2. Swarm discovery

### 2.1 List swarms
`GET /api/swarms`

**Response (200)**
```json
[
  {
    "id": "demo",
    "status": "RUNNING",
    "health": "HEALTHY",
    "heartbeat": "2024-03-15T12:00:00Z",
    "workEnabled": true,
    "controllerEnabled": true,
    "templateId": "baseline-demo",
    "controllerImage": "ghcr.io/pockethive/swarm-controller:1.2.3",
    "bees": [
      { "role": "generator", "image": "ghcr.io/pockethive/generator:1.2.3" },
      { "role": "moderator", "image": "ghcr.io/pockethive/moderator:1.2.3" }
    ]
  }
]
```

> Swarms are sorted lexicographically by `id` for deterministic UI rendering.

### 2.2 Fetch swarm
`GET /api/swarms/{swarmId}`

**Response (200)** — same shape as the list entry above. Returns `404` when the swarm id is unknown.

### 2.3 Swarm journal (timeline)
`GET /api/swarms/{swarmId}/journal`

Returns the swarm-level journal entries as a JSON array (chronological order).

Notes:
- This is a non-paginated “timeline” endpoint intended for UI/debug use.
- When `pockethive.journal.sink=postgres`, entries are read from Postgres.
- When `pockethive.journal.sink=file`, entries are read from `/app/scenarios-runtime/{swarmId}/{runId}/journal.ndjson` (this path is expected to be bind-mounted from the host runtime root).

**Response (200)**
```json
[
  {
    "timestamp": "2025-01-01T12:34:56Z",
    "swarmId": "demo",
    "severity": "INFO",
    "direction": "IN",
    "kind": "signal",
    "type": "swarm-start",
    "origin": "orchestrator",
    "scope": { "swarmId": "demo", "role": "swarm-controller", "instance": "abc123" },
    "correlationId": "uuid-v4",
    "idempotencyKey": "uuid-v4",
    "routingKey": "signal.swarm-start.demo.swarm-controller.abc123",
    "data": {},
    "raw": {},
    "extra": {}
  }
]
```

### 2.4 Swarm journal (paginated, Postgres)
`GET /api/swarms/{swarmId}/journal/page`

Availability: requires `pockethive.journal.sink=postgres`. Otherwise returns `501 Not Implemented`.

**Query params**
- `limit` (optional, default `200`, max `1000`)
- `correlationId` (optional)
- `runId` (optional; when omitted, uses the active runId for the swarm if available, else latest recorded run)
- `beforeTs` + `beforeId` (optional cursor pair; use the `nextCursor` from the previous response)

**Response (200)** — newest-first (descending by `(ts,id)`)
```json
{
  "items": [ { "eventId": 123, "timestamp": "2025-01-01T12:34:56Z", "swarmId": "demo", "...": "..." } ],
  "nextCursor": { "ts": "2025-01-01T12:00:00Z", "id": 42 },
  "hasMore": true
}
```

### 2.5 Hive journal (paginated, Postgres)
`GET /api/journal/hive/page`

Availability: requires `pockethive.journal.sink=postgres`. Otherwise returns `501 Not Implemented`.

**Query params**
- `limit` (optional, default `200`, max `1000`)
- `swarmId` (optional)
- `runId` (optional; useful when `swarmId` is reused across runs)
- `correlationId` (optional)
- `beforeTs` + `beforeId` (optional cursor pair)

**Response (200)** — same page shape as swarm pagination.

### 2.6 Swarm journal runs (Postgres)
`GET /api/swarms/{swarmId}/journal/runs`

Availability: requires `pockethive.journal.sink=postgres`. Otherwise returns `501 Not Implemented`.

Lists known journal runs for a swarm id (new swarm with same id = new run).

### 2.6.1 Swarm journal runs (global, Postgres)
`GET /api/journal/swarm/runs`

Availability: requires `pockethive.journal.sink=postgres`. Otherwise returns `501 Not Implemented`.

Lists known swarm journal runs across all swarms (newest-first).

**Query params**
- `limit` (optional, default `500`, max `5000`)
- `pinned` (optional; when `true`, returns only pinned runs)

**Response (200)**
```json
[
  {
    "swarmId": "demo",
    "runId": "uuid-v4",
    "firstTs": "2025-01-01T12:00:00Z",
    "lastTs": "2025-01-01T12:34:56Z",
    "entries": 1234,
    "pinned": false,
    "scenarioId": "baseline-demo",
    "testPlan": "release-14",
    "tags": ["quick-test", "good"],
    "description": "Notes about this run"
  }
]
```

### 2.6.2 Swarm run metadata update (Postgres)
`POST /api/journal/swarm/runs/{runId}/meta`

Availability: requires `pockethive.journal.sink=postgres`. Otherwise returns `501 Not Implemented`.

Sets/clears metadata used by the Journals index grouping and filtering (post-factum labeling without SQL).

**Request**
```json
{
  "testPlan": "optional; null/blank clears",
  "description": "optional; null/blank clears",
  "tags": ["optional", "labels"]
}
```

**Response (200)** — returns the updated run summary for convenience.

### 2.7 Swarm journal pin (archive, Postgres)
`POST /api/swarms/{swarmId}/journal/pin`

Availability: requires `pockethive.journal.sink=postgres`. Otherwise returns `501 Not Implemented`.

Pins a swarm journal run into an archive so it can be kept beyond time-based retention.

**Request**
```json
{
  "runId": "optional; when omitted, pins the active/latest run",
  "mode": "FULL|SLIM|ERRORS_ONLY",
  "name": "optional label"
}
```

**Response (200)**
```json
{
  "captureId": "uuid",
  "swarmId": "demo",
  "runId": "run-1",
  "mode": "SLIM",
  "inserted": 1234,
  "entries": 1234
}
```

### 2.8 Debug taps (UI V2)
Debug taps mirror data-plane messages without touching worker code. The orchestrator creates a
temporary queue bound to the swarm's hive exchange and buffers samples for UI inspection.

#### 2.8.1 Create tap
`POST /api/debug/taps`

**Request**
```json
{
  "swarmId": "demo",
  "role": "postprocessor",
  "direction": "OUT",
  "ioName": "out",
  "maxItems": 1,
  "ttlSeconds": 60
}
```

**Response (200)**
```json
{
  "tapId": "uuid",
  "swarmId": "demo",
  "role": "postprocessor",
  "direction": "OUT",
  "ioName": "out",
  "exchange": "ph.demo.hive",
  "routingKey": "ph.work.demo.post",
  "queue": "ph.debug.demo.postprocessor.ab12cd34",
  "maxItems": 1,
  "ttlSeconds": 60,
  "createdAt": "2025-01-01T12:34:56Z",
  "lastReadAt": "2025-01-01T12:34:56Z",
  "samples": []
}
```

#### 2.8.2 Read tap
`GET /api/debug/taps/{tapId}`

Query params:
- `drain` (optional) — max messages to drain from the tap queue before returning (defaults to `maxItems`).

**Response (200)** — same shape as create response, with `samples` populated.

#### 2.8.3 Close tap
`DELETE /api/debug/taps/{tapId}`

Deletes the tap queue and returns the last known tap state.

## 3.0 Create swarm
`POST /api/swarms/{swarmId}/create`

**Behavior**
- Launch Controller runtime for `{swarmId}` (no AMQP signal).
- After the first controller `event.metric.status-full.<swarmId>.swarm-controller.<controllerInstance>`, emit **`event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>`** (echo ids).
- On failure, emit **`event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>`** with `data.status=Failed` and an accompanying `event.alert.{type}` if applicable.
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
    "successTopic": "event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>",
    "errorTopic":   "event.alert.{type}.<swarmId>.orchestrator.<orchestratorInstance>"
  },
  "timeoutMs": 120000
}
```

**Response (409)** — when a swarm with `{swarmId}` already exists and the `idempotencyKey` is new.
```json
{
  "message": "Swarm '<swarmId>' already exists"
}
```

> Retries that reuse the original `idempotencyKey` continue to return the 202 response with the stored correlation id even after the swarm has been provisioned.

## 3.1 Start swarm
`POST /api/swarms/{swarmId}/start`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "notes": "optional" }
```

**Signal:** `signal.swarm-start.<swarmId>.swarm-controller.<controllerInstance>` → **Outcome:** `event.outcome.swarm-start.<swarmId>.swarm-controller.<controllerInstance>` (check `data.status`) → **Alerts:** `event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>`

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "event.outcome.swarm-start.<swarmId>.swarm-controller.<controllerInstance>",
    "errorTopic":   "event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>"
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

**Signal:** `signal.swarm-stop.<swarmId>.swarm-controller.<controllerInstance>` → **Outcome:** `event.outcome.swarm-stop.<swarmId>.swarm-controller.<controllerInstance>` (check `data.status`) → **Alerts:** `event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>`

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "event.outcome.swarm-stop.<swarmId>.swarm-controller.<controllerInstance>",
    "errorTopic":   "event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>"
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

**Signal:** `signal.swarm-remove.<swarmId>.swarm-controller.<controllerInstance>` → **Outcome:** `event.outcome.swarm-remove.<swarmId>.swarm-controller.<controllerInstance>` (check `data.status`) → **Alerts:** `event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>`  
**Post‑success:** tear down the Controller runtime for this swarm.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": {
    "successTopic": "event.outcome.swarm-remove.<swarmId>.swarm-controller.<controllerInstance>",
    "errorTopic":   "event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>"
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
  "swarmPlan": {
    "id": "demo",
    "bees": [
      {
        "role": "generator",
        "image": "ghcr.io/pockethive/generator:latest",
        "work": { "out": "gen-out" },
        "config": {
          "ratePerSec": 10,
          "message": { "path": "/api/guarded", "body": "warmup" }
        }
      },
      {
        "role": "processor",
        "image": "ghcr.io/pockethive/processor:latest",
        "work": { "in": "gen-out", "out": "final" },
        "config": {
          "baseUrl": "{{ sut.endpoints['default'].baseUrl }}",
          "timeoutMillis": 2500
        }
      }
    ]
  },
  "notes": "optional"
}
```

**Signal:** `signal.swarm-template.<swarmId>.swarm-controller.<controllerInstance>` → **Outcome:** `event.outcome.swarm-template.<swarmId>.swarm-controller.<controllerInstance>` (check `data.status`) → **Alerts:** `event.alert.{type}.<swarmId>.swarm-controller.<controllerInstance>`

**Response (202)** — same envelope.

## 4. Components

### 4.1 Update config
`POST /api/components/{role}/{instance}/config`

**Request**
```json
{
  "idempotencyKey": "uuid-v4",
  "patch": { "enabled": true },
  "swarmId": "optional; omit to use ALL",
  "notes": "optional"
}
```

**Signal:** `signal.config-update.<swarmId>.<role>.<instance>` → **Outcome:** `event.outcome.config-update.<swarmId>.<role>.<instance>` (check `data.status`) → **Alerts:** `event.alert.{type}.<swarmId>.<role>.<instance>`

**Response (202)** — same envelope.

### 4.2 Status request
`POST /api/components/{role}/{instance}/status-request`

**Request**
```json
{ "idempotencyKey": "uuid-v4" }
```

**Signal:** `signal.status-request.<swarmId>.<role>.<instance>` → component emits `event.metric.status-full.<swarmId>.<role>.<instance>` (no outcome).

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "watch": { "infoTopic": "event.metric.status-full.<swarmId>.<role>.<instance>" },
  "timeoutMs": 10000
}
```

### 4.3 Swarm manager enable/disable (fan-out)
`POST /api/swarm-managers/enabled`

**Behavior**
- Publishes `signal.config-update.<swarmId>.swarm-controller.<instance>` per registered controller with `data.enabled`.
- Controllers keep their control plane sessions alive even when workloads are disabled.
- The response lists each dispatch with watch topics for outcomes and alerts.

**Request**
```json
{
  "idempotencyKey": "uuid-v4",
  "enabled": false,
  "notes": "optional"
}
```

**Response (202)**
```json
{
  "dispatches": [
    {
      "swarm": "demo",
      "instanceId": "swarm-controller-demo-1",
      "reused": false,
      "response": {
        "correlationId": "…",
        "idempotencyKey": "…",
        "watch": {
          "successTopic": "event.outcome.config-update.<swarmId>.swarm-controller.<instance>",
          "errorTopic": "event.alert.{type}.<swarmId>.swarm-controller.<instance>"
        },
        "timeoutMs": 60000
      }
    }
  ]
}
```

#### 4.3.1 Single swarm enable/disable
`POST /api/swarm-managers/{swarmId}/enabled`

**Behavior**
- Same as the bulk endpoint, but targets a single swarm controller instance.
- Returns `404` if the swarm has not registered a controller instance.

**Request**
```json
{
  "idempotencyKey": "uuid-v4",
  "enabled": true,
  "notes": "optional"
}
```

**Response (202)** — same shape as the bulk fan-out response.

## 5. Control-plane sync (debug-only)
These endpoints are intended for local diagnostics and should be secured behind admin access or removed before exposing the orchestrator publicly.

### 5.1 Refresh control-plane status
`POST /api/control-plane/refresh`

**Behavior**
- Triggers `event.metric.status-full` broadcasts from the orchestrator.
- Publishes `signal.status-request` for known swarms (or all controllers if none are registered).
- Throttled if called more often than once every 2 seconds.

**Response (202)**
```json
{
  "mode": "REFRESH",
  "correlationId": "…",
  "idempotencyKey": "…",
  "signalsPublished": 3,
  "throttled": false,
  "issuedAt": "2025-01-01T12:00:00Z"
}
```

### 5.2 Reset control-plane state (debug-only)
`POST /api/control-plane/reset`

**Behavior**
- Clears the orchestrator registry before issuing the same sync flow as refresh.
- Intended for local recovery/testing only.

**Response (202)** — same shape as refresh, with `mode: "RESET"`.

### 5.3 Control-plane schema (UI bootstrap)
`GET /api/control-plane/schema/control-events`

**Behavior**
- Returns the raw `docs/spec/control-events.schema.json` payload.
- Supports `ETag` and `If-None-Match` for caching (5 minute max-age).
- Intended for UI bootstrap; no fallback source should be used if unavailable.
- Should be secured behind admin access or removed before exposing the orchestrator publicly.

**Response (200)**
```
Content-Type: application/schema+json;version="draft/2020-12"
ETag: "..."
Cache-Control: max-age=300
```

**Response (304)** — when `If-None-Match` matches the current `ETag`.

Outcome and metric payloads follow the envelope rules in `docs/ARCHITECTURE.md`.
