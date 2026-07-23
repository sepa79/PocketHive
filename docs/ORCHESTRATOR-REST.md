# PocketHive — Orchestrator REST API

**Base:** `/api` • MIME: `application/json` • Auth: Bearer JWT (RBAC enforced)

## Idempotency & correlation
Client sends **`idempotencyKey`** (UUID v4) per new logical action and reuses it when retrying that action. The Orchestrator creates one **`correlationId`** for the accepted operation and returns that same operation identity for duplicates:
```json
{
  "correlationId": "uuid-v4",
  "idempotencyKey": "uuid-v4",
  "operationUrl": "/api/swarms/<swarmId>/operations/<correlationId>",
  "outcomeTopic": "event.outcome.<command>.<swarmId>.orchestrator.<instance>",
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
    "runId": "run-20260722-123455Z",
    "runtimeIntent": "PRESENT",
    "workloadIntent": "RUNNING",
    "controllerState": "READY",
    "workloadState": "RUNNING",
    "health": "HEALTHY",
    "runtimeResourceState": "PRESENT",
    "observedAt": "2026-07-22T12:00:00Z",
    "observationStale": false,
    "activeOperation": null,
    "observation": null,
    "templateId": "baseline-demo",
    "controllerImage": "ghcr.io/pockethive/swarm-controller:1.2.3",
    "bees": [
      { "instance": "demo-generator-1", "role": "generator", "image": "ghcr.io/pockethive/generator:1.2.3" },
      { "instance": "demo-moderator-1", "role": "moderator", "image": "ghcr.io/pockethive/moderator:1.2.3" }
    ]
  }
]
```

> Swarms are sorted lexicographically by `id` for deterministic UI rendering.
> Bee summaries are keyed by runtime `instance`; do not collapse multiple bees
> with the same `role`.

### 2.2 Fetch swarm
`GET /api/swarms/{swarmId}`

**Response (200)** — Orchestrator projection joining owned intent/operation state with the cached Controller observation. The raw Controller envelope is retained under `observation`; it is evidence, not the swarm state authority.
```json
{
  "id": "demo",
  "runId": "run-20260722-123455Z",
  "runtimeIntent": "PRESENT",
  "workloadIntent": "RUNNING",
  "controllerState": "READY",
  "workloadState": "RUNNING",
  "health": "HEALTHY",
  "runtimeResourceState": "PRESENT",
  "observedAt": "2026-07-22T12:34:56Z",
  "observationStale": false,
  "activeOperation": null,
  "observation": {
    "receivedAt": "2026-07-22T12:34:56Z",
    "staleAfterSec": 30,
    "envelope": {
      "timestamp": "2026-07-22T12:34:55Z",
      "version": "2",
      "kind": "metric",
      "type": "status-full",
      "origin": "swarm-controller-instance",
      "scope": {
        "swarmId": "demo",
        "role": "swarm-controller",
        "instance": "demo-marshal-bee-1234"
      },
      "correlationId": null,
      "idempotencyKey": null,
      "runtime": {
        "templateId": "baseline-demo",
        "runId": "run-20260722-123455Z",
        "containerId": null,
        "image": "ghcr.io/pockethive/swarm-controller:1.2.3",
        "stackName": null
      },
      "data": {
        "config": {},
        "startedAt": "2026-07-22T12:00:00Z",
        "io": {},
        "ioState": {},
        "context": {
          "controllerState": "READY",
          "workloadState": "RUNNING",
          "health": "HEALTHY",
          "startupReady": true,
          "startupArtifactSha256": "sha256-hex",
          "watermarkAt": "2026-07-22T12:34:55Z",
          "expectedWorkers": [
            { "swarmId": "demo", "role": "generator", "instance": "demo-generator-1" }
          ],
          "workers": [
            {
              "role": "generator",
              "instance": "demo-generator-1",
              "enabled": true,
              "tps": 10,
              "lastSeenAt": "2026-07-22T12:34:55Z",
              "stale": false,
              "ioState": {
                "work": {
                  "input": "ok",
                  "output": "ok"
                }
              },
              "runtime": {
                "templateId": "baseline-demo",
                "runId": "run-20260722-123455Z",
                "containerId": null,
                "image": "ghcr.io/pockethive/generator:1.2.3",
                "stackName": null
              },
              "config": {
                "inputs": {
                  "type": "SCHEDULER",
                  "ratePerSecond": 10
                }
              }
            }
          ]
        }
      }
    }
  }
}
```

Returns `404` only when the swarm id is unknown. Before a fresh Controller observation exists, the owned intent remains available and observed axes are explicitly `UNKNOWN`; absence of status is never treated as absence of the swarm.

`observation.envelope.data.context.workers[].instance` is the runtime worker identity for component
selection. `role` is the required routing segment for component actions, but
clients must not join or deduplicate workers by `role`. Runtime worker payloads
must not expose or require a second `beeId` identity.

### 2.2.1 Fetch operation

`GET /api/swarms/{swarmId}/operations/{correlationId}`

Returns the Orchestrator-owned operation independently of RabbitMQ delivery or UI subscription timing.

```json
{
  "swarmId": "demo",
  "type": "START",
  "target": { "role": "swarm-controller", "instance": "demo-marshal-bee-1234" },
  "correlationId": "uuid-v4",
  "idempotencyKey": "uuid-v4",
  "state": "SUCCEEDED",
  "createdAt": "2026-07-22T12:30:08Z",
  "dispatchedAt": "2026-07-22T12:30:09Z",
  "deadlineAt": "2026-07-22T12:33:08Z",
  "completedAt": "2026-07-22T12:30:11Z",
  "terminalResult": {
    "status": "Succeeded",
    "retryable": false,
    "context": {
      "target": { "role": "swarm-controller", "instance": "demo-marshal-bee-1234" },
      "requestedWorkloadState": "RUNNING",
      "observedWorkloadState": "RUNNING",
      "nonConvergedWorkers": []
    }
  }
}
```

Operation types are `CREATE`, `START`, `STOP`, `REMOVE` and `CONFIG_UPDATE`. States are `ACCEPTED`, `DISPATCHED`, `SUCCEEDED`, `REJECTED`, `FAILED` and `TIMED_OUT`. `terminalResult` is `null` until terminal. Returns `404` when either the swarm operation or correlation id is unknown.

### 2.3 Swarm journal (timeline)
`GET /api/swarms/{swarmId}/journal`

Returns the swarm-level journal entries as a JSON array (chronological order).

**Query params**
- `runId` (optional; when omitted, uses the active runId for the swarm if available)
- `severity` (optional; exact match, one of `ERROR`, `WARN`, `INFO`)

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
- `severity` (optional; exact match, one of `ERROR`, `WARN`, `INFO`)
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
  "routingKey": "ph.demo.post",
  "queue": "ph.debug.demo.postprocessor.ab12cd34",
  "maxItems": 1,
  "ttlSeconds": 60,
  "createdAt": "2025-01-01T12:34:56Z",
  "lastReadAt": "2025-01-01T12:34:56Z",
  "samples": []
}
```

### 2.9 Runtime Cleanup Reconciliation

Runtime cleanup is an operator workflow for stale PocketHive-owned Docker and
RabbitMQ resources. The Orchestrator is the authority because it owns swarm desired
state, the swarm registry, AMQP topology access, and runtime ownership manifests.
MCP clients must call this API instead of deleting Docker/RabbitMQ resources
directly in production.

Orchestrator also owns Docker/Swarm runtime debug for PocketHive-managed worker
and swarm-controller manager runtimes. MCP clients must call the runtime debug
API for Docker/Swarm list, inspect, logs, and version reads instead of using the
Docker socket directly.

Cleanup is always:

```text
plan -> execute
```

Registered swarms are cleanup candidates only through `LIFECYCLE_REMOVE_SWARM`.
This candidate invokes the same canonical filesystem-backed `REMOVE` operation;
it is not a second deletion protocol. `REMOVE` sets workload intent to `STOPPED`
and the Controller converges disablement before cleanup, so a separate stop is
not required. Any non-terminal lifecycle operation blocks a new remove operation.
Cleanup execution reports this candidate as `DISPATCHED` with the canonical
`correlationId` and `operationUrl`; it does not report `REMOVED` before the
filesystem result completes that operation. All direct Docker and RabbitMQ
candidates belonging to a registered swarm remain blocked.
There is no registered-swarm override that bypasses operation ownership or
manufactures success without verified cleanup evidence.

Unregistered labeled Docker resources are treated as orphan cleanup candidates
only inside the requested `swarmId`/`runId` scope. They still require
`pockethive.managed=true` and all required PocketHive labels. RabbitMQ cleanup
still requires an ownership manifest and never deletes by prefix.

RabbitMQ cleanup is allowed only for exact queue/exchange names recorded in the
runtime ownership manifest or control queue names derived inside Orchestrator
from exact PocketHive runtime labels using the shared control-plane topology
descriptors. Derived worker control queues obey the same `includeRunning` gate as
their worker runtime object, so default cleanup plans do not target a running
worker's control queue. Prefix guessing, Docker prune-style operations, and
implicit cleanup fallbacks are forbidden. In production, the mutating execute
operation must be registered behind HiveGate or an equivalent governed control
plane for policy, human approval when required, and evidence.

#### 2.9.1 Runtime debug capabilities
`GET /api/runtime/debug/capabilities`

Returns the scoped runtime debug/cleanup contract understood by this
Orchestrator. PocketHive MCP clients use this endpoint before runtime debug or
cleanup tool execution so stale Orchestrator deployments fail only those runtime
tools, without impacting existing scenario, workflow, or swarm lifecycle tools.

**Response (200)**
```json
{
  "runtimeDebugContractVersion": "4",
  "cleanupContractVersion": "3",
  "runtimeDebugReadsBackedByOrchestrator": true,
  "cleanupPlanHasExecutionRisk": true,
  "cleanupPlanUsesApprovalFields": false,
  "cleanupExecuteRequiresCandidateSetHash": true,
  "rabbitTopologyExactByDefault": true,
  "cleanupSupportsRegisteredStateOverride": true
}
```

#### 2.9.2 Runtime resources
`POST /api/runtime/debug/resources/list`

Lists PocketHive-managed worker and swarm-controller manager runtimes for one
swarm from Orchestrator-owned Docker/Swarm inventory.

The compute adapter is not client-controlled. Orchestrator uses its internally
resolved adapter and reports it in the response as read-only runtime context.

**Request**
```json
{
  "swarmId": "demo",
  "runId": "optional",
  "includeManagers": true
}
```

**Response (200)**
```json
{
  "computeAdapter": "DOCKER_SINGLE",
  "swarmId": "demo",
  "runId": "run-1",
  "counts": { "workers": 1, "managers": 1, "blocked": 0 },
  "workers": [],
  "managers": [],
  "blocked": []
}
```

#### 2.9.3 Runtime target logs
`POST /api/runtime/debug/resources/logs`

Reads bounded, redacted Docker container logs or Swarm service logs for one
label-gated `worker` or `manager` runtime. The target must be identified by
`runtimeId`, `instance`, or `role`; ambiguous targets are rejected.

**Request**
```json
{
  "swarmId": "demo",
  "runId": "optional",
  "resourceKind": "manager",
  "instance": "controller-1",
  "tailLines": 200,
  "since": "2026-06-18T12:00:00Z"
}
```

**Response (200)**
```json
{
  "target": {
    "runtimeId": "abc",
    "runtimeType": "container",
    "resourceKind": "manager",
    "role": "swarm-controller",
    "instance": "controller-1"
  },
  "tailLines": 200,
  "redacted": true,
  "lineCount": 12,
  "logs": "..."
}
```

#### 2.9.4 Runtime target version
`POST /api/runtime/debug/resources/version`

Returns the version from the exact runtime image/labels used to create the
worker or manager. Deployment-wide service versions are not used.

#### 2.9.5 Runtime target inspect
`POST /api/runtime/debug/resources/inspect`

Returns a bounded inspect summary for one worker or manager runtime. Raw bind
host paths and environment variables are not returned.

#### 2.9.6 Runtime ownership manifest
`POST /api/runtime/debug/manifest`

Returns the canonical runtime ownership manifest selected by exact `swarmId`
and optional `runId`. Orchestrator owns manifest storage, selection and JSON
shape; clients must not read, locate or parse manifest files themselves. A
missing manifest returns `404 Not Found`.

**Request**
```json
{
  "swarmId": "demo",
  "runId": "optional"
}
```

**Response (200)** is the canonical `RuntimeOwnershipManifest` object.

#### 2.9.7 Rabbit topology snapshot
`POST /api/runtime/debug/rabbit/topology`

Reads exact RabbitMQ topology for one PocketHive swarm through Orchestrator.
The response is based on the runtime ownership manifest plus control queues
derived inside Orchestrator from exact worker labels and shared control-plane
topology descriptors. It does not consume queues, publish messages, scan by
prefix, or expose a RabbitMQ management fallback in the MCP.

**Request**
```json
{
  "swarmId": "demo",
  "runId": "optional"
}
```

**Response (200)**
```json
{
  "computeAdapter": "DOCKER_SINGLE",
  "swarmId": "demo",
  "runId": "run-1",
  "manifest": { "available": true },
  "rabbit": { "available": true },
  "exactOnly": true,
  "queues": [
    {
      "name": "ph.control.demo.processor.demo-processor-1",
      "present": true,
      "messages": 0,
      "consumers": 1
    }
  ],
  "exchanges": [
    { "name": "ph.demo.hive", "present": true }
  ],
  "unmanagedDiagnostics": []
}
```

When the ownership manifest is missing, the response is still exact-only and
returns no Rabbit resources instead of guessing by prefix.

#### 2.9.7 Plan cleanup
`POST /api/runtime/cleanup/plan`

**Request**
```json
{
  "swarmId": "demo",
  "runId": "optional",
  "includeRunning": false,
  "includeRabbit": true
}
```

**Response (200)**
```json
{
  "computeAdapter": "DOCKER_SINGLE",
  "swarmId": "demo",
  "runId": "run-1",
  "includeRunning": false,
  "includeRabbit": true,
  "candidateSetHash": "sha256:...",
  "executionRisk": "standard",
  "candidates": [
    {
      "candidateId": "docker:container:abc",
      "action": "DELETE_DOCKER_CONTAINER",
      "resourceId": "abc",
      "resourceType": "container",
      "resourceKind": "worker",
      "role": "processor",
      "instance": "demo-processor-1",
      "state": "exited",
      "image": "ghcr.io/pockethive/processor:1.2.3",
      "reason": "stopped PocketHive runtime resource"
    }
  ],
  "blocked": [
    {
      "candidateId": "rabbit:queue:ph.demo.final",
      "action": "DELETE_RABBIT_QUEUE",
      "resourceId": "ph.demo.final",
      "reason": "active swarm shared RabbitMQ resource is protected"
    }
  ]
}
```

#### 2.9.8 Execute cleanup
`POST /api/runtime/cleanup/execute`

Recomputes the plan, verifies the candidate hash and idempotency key, then
executes only the selected candidate ids. This endpoint does not approve itself;
production access is governed by HiveGate policy outside Orchestrator.

**Request**
```json
{
  "swarmId": "demo",
  "runId": "run-1",
  "includeRunning": false,
  "includeRabbit": true,
  "candidateSetHash": "sha256:...",
  "candidateIds": ["docker:container:abc"],
  "idempotencyKey": "uuid-v4",
  "reason": "remove stale stopped runtime",
  "actor": "operator"
}
```

**Response (200)**
```json
{
  "idempotent": false,
  "evidence": {
    "computeAdapter": "DOCKER_SINGLE",
    "idempotencyKey": "uuid-v4",
    "candidateSetHash": "sha256:...",
    "resultByCandidate": [
      {
        "candidateId": "docker:container:abc",
        "status": "REMOVED"
      }
    ]
  }
}
```

#### 2.8.2 Read tap
`GET /api/debug/taps/{tapId}`

	Query params:
	- `drain` (optional) — max messages to drain from the tap queue before returning (defaults to `maxItems`; `0` means metadata-only, no consume).

**Response (200)** — same shape as create response, with `samples` populated.

#### 2.8.3 Close tap
`DELETE /api/debug/taps/{tapId}`

Deletes the tap queue and returns the last known tap state.

## 3.0 Create swarm
`POST /api/swarms/{swarmId}/create`

**Behavior**
- Launch Controller runtime for `{swarmId}` (no AMQP signal).
- Emit **`event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>`** only after Controller state is `READY`, workload observation is `STOPPED`, every expected worker is fresh and bootstrap-acknowledged, and the reported startup artifact digest matches the launch record.
- On failure, emit **`event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>`** with `data.status=Failed` and an accompanying `event.alert.{type}` if applicable.
- Requires a `templateId` referencing the scenario template to instantiate.

**Request**
```json
{
  "templateId": "scenario-id",
  "idempotencyKey": "uuid-v4",
  "autoPullImages": true,
  "sutId": "optional; bundle-local SUT id",
  "variablesProfileId": "optional; required when variables.yaml defines profiles",
  "networkMode": "DIRECT",
  "networkProfileId": null,
  "notes": "optional"
}
```

`networkMode` is required and must be `DIRECT` or `PROXIED`; it is never inferred. `PROXIED` also requires explicit `sutId` and `networkProfileId`. `DIRECT` requires `networkProfileId` to be `null`.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "operationUrl": "/api/swarms/<swarmId>/operations/<correlationId>",
  "outcomeTopic": "event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>",
  "timeoutMs": 300000
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

**Signal:** `signal.swarm-start.<swarmId>.swarm-controller.<controllerInstance>` → internal **result:** `event.result.swarm-start.<swarmId>.swarm-controller.<controllerInstance>` → public **outcome:** `event.outcome.swarm-start.<swarmId>.orchestrator.<orchestratorInstance>`.

Clients watch the Orchestrator outcome and correlate it by the returned `correlationId`. `data.status` is terminal and may be `Succeeded`, `Rejected`, `Failed` or `TimedOut`; the topic is therefore not a success-only channel. Alerts are diagnostic and never replace the terminal outcome.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "operationUrl": "/api/swarms/<swarmId>/operations/<correlationId>",
  "outcomeTopic": "event.outcome.swarm-start.<swarmId>.orchestrator.<orchestratorInstance>",
  "timeoutMs": 180000
}
```

## 3.2 Stop swarm (non-destructive)
`POST /api/swarms/{swarmId}/stop`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "notes": "optional" }
```

**Signal:** `signal.swarm-stop.<swarmId>.swarm-controller.<controllerInstance>` → internal **result:** `event.result.swarm-stop.<swarmId>.swarm-controller.<controllerInstance>` → public **outcome:** `event.outcome.swarm-stop.<swarmId>.orchestrator.<orchestratorInstance>`.

Completion requires fresh post-dispatch status from every expected worker with `enabled=false`. Dispatch acceptance is not completion.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "operationUrl": "/api/swarms/<swarmId>/operations/<correlationId>",
  "outcomeTopic": "event.outcome.swarm-stop.<swarmId>.orchestrator.<orchestratorInstance>",
  "timeoutMs": 90000
}
```

## 3.3 Remove swarm (explicit delete)
`POST /api/swarms/{swarmId}/remove`

**Request**
```json
{ "idempotencyKey": "uuid-v4", "notes": "optional" }
```

The Orchestrator first creates the immutable filesystem request under `<runtime-root>/<swarmId>/operations/remove/<correlationId>/request.json`. `signal.swarm-remove.<swarmId>.swarm-controller.<controllerInstance>` is only a repeatable wake-up. The Controller writes the matching `pockethive/swarm-remove-result/v2` `result.json`, whose `targetResources` are action evidence rather than an absence claim. The Orchestrator then removes the Controller runtime, verifies every compute and RabbitMQ target through the canonical observation ports, deletes the runtime directory and registry entry, and synchronously persists terminal audit evidence with the captured `runId`. Only after those postconditions pass may it publish `event.outcome.swarm-remove.<swarmId>.orchestrator.<orchestratorInstance>`. Missing or partial evidence is failure/timeout, never success.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "operationUrl": "/api/swarms/<swarmId>/operations/<correlationId>",
  "outcomeTopic": "event.outcome.swarm-remove.<swarmId>.orchestrator.<orchestratorInstance>",
  "timeoutMs": 180000
}
```

## 4. Components

### 4.1 Update config
`POST /api/components/{role}/{instance}/config`

**Request**
```json
{
  "idempotencyKey": "uuid-v4",
  "patch": { "enabled": true },
  "swarmId": "required",
  "notes": "optional"
}
```

**Signal:** `signal.config-update.<swarmId>.<role>.<instance>` → internal target **result:** `event.result.config-update.<swarmId>.<role>.<instance>` → public **outcome:** `event.outcome.config-update.<swarmId>.orchestrator.<orchestratorInstance>`.

For UI-originated edits, resolve `{role}/{instance}` from the selected runtime
worker in `status-full.data.context.workers[]`. `instance` is the runtime worker
identity; `role` alone is not a stable target.

**Response (202)**
```json
{
  "correlationId": "…",
  "idempotencyKey": "…",
  "operationUrl": "/api/swarms/<swarmId>/operations/<correlationId>",
  "outcomeTopic": "event.outcome.config-update.<swarmId>.orchestrator.<orchestratorInstance>",
  "timeoutMs": 60000
}
```

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
