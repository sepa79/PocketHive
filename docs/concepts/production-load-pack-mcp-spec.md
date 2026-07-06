# Production Load Pack MCP Spec

Status: draft  
Scope: MCP only  
Audience: stakeholders, novice users, maintainers, AI agents

## 1. Goal And Bounds

Goal: run a repeatable group of background scenarios to create production-like noise. MCP coordinates the pack. Orchestrator still owns create/start/stop/remove lifecycle.

Hard bounds:

- Orchestrator lifecycle unchanged; MCP calls Orchestrator REST for lifecycle.
- Scenario Manager unchanged; MCP reads `/api/templates` for scenario registry.
- Scenario bundles stay SSOT.
- No direct AMQP lifecycle, UI, Python, hidden fallback, guessed config, or raw `swarmId`.

Non-goals: UI, Orchestrator change, Scenario Manager change, new lifecycle model, direct AMQP lifecycle.

## 2. Startup

`start-mcp.sh`: no required args; Docker + detached by default; `--node` for local Node; `--foreground` for attached. It must not require local scenario bundle dir. Dependency failures return clear `code`, `message`, `fix`.

## 3. Config

```text
scenarios/
  bundles/<scenarioId>/
  collections/<collectionId>/
    collection.yaml
    load-profiles/<loadProfileId>.yaml
    runner-profiles/<runnerProfileId>.yaml
```

```yaml
# collection.yaml
version: 1
id: production-load-pack
name: Production Load Pack
defaultRunnerProfileId: local
allowedLoadProfiles: [production-noise]
allowedRunnerProfiles: [local]
---
# load-profiles/production-noise.yaml
version: 1
id: production-noise
scenarios:
  - scenarioId: db-query-postgres-smoke
    scenarioAlias: db-query
    sutId: postgres-local
    variablesProfileId: local
---
# runner-profiles/local.yaml
version: 1
id: local
maxParallelRequests: 1
healthWaitTimeoutMs: 300000
operationWaitTimeoutMs: 300000
failureMode: CONTINUE_AND_REPORT
```

Rules:

- IDs use lowercase slug chars: `a-z`, `0-9`, `-`.
- `collection.id`, profile `id`, `scenarioAlias`: max `32`; visible ID budget: `collectionId + loadProfileId + scenarioAlias <= 65`; final `swarmId <= 80`.
- `allowedLoadProfiles` / `allowedRunnerProfiles` are allowlists, not auto-run lists.
- MCP runs only requested `loadProfileId`; no load profile default.
- MCP may use valid `defaultRunnerProfileId` when `runnerProfileId` omitted.
- profile file `id` must match requested/listed ID.
- `scenarioAlias` unique per load profile.
- `sutId` and `variablesProfileId` required for MVP.
- runner settings control MCP wait/parallelism only.
- `failureMode`: `FAIL_FAST` or `CONTINUE_AND_REPORT`.

Mutating tool input:

```json
{
  "collectionId": "production-load-pack",
  "loadProfileId": "production-noise",
  "runnerProfileId": "local"
}
```

## 4. Generated Swarm ID

Format:

```text
<collectionId>-<loadProfileId>-<scenarioAlias>-<hash12>
```

Example:

```text
production-load-pack-production-noise-db-query-a1b2c3d4e5f6
```

`hash12` = first 12 SHA-256 hex chars of:

```text
<collectionId>|<loadProfileId>|<scenarioId>|<scenarioAlias>|<sutId>|<variablesProfileId>
```

No normal truncation. Overlong result rejects config.

## 5. MCP Tools

| Tool | Purpose |
| --- | --- |
| `mcp.health` | Check mode, dependencies, Orchestrator URL, Scenario Manager URL, Rabbit if needed. |
| `scenario.registry` | Read `/api/templates`; show runnable/defunct scenarios. |
| `scenario.inspect` | Diagnose one scenario/bundle: registry state, `defunctReason`, SUT IDs, variable profiles, resolve result, fixes. |
| `scenario.variables` | Read/write `variables.yaml` via Scenario Manager. |
| `scenario.sut` | List/read/write bundle SUTs via Scenario Manager. |
| `pack.validate-config` | Local YAML only. No remote calls. No mutation. |
| `pack.validate` | Check profiles, scenario IDs, SUT IDs, variable profile IDs. No mutation. |
| `pack.preflight` | Validate, resolve generated IDs, show exact plan. No mutation. |
| `pack.create` | Create missing swarms. |
| `pack.start` | Create missing, wait healthy, start. |
| `pack.stop` | Stop pack swarms. |
| `pack.remove` | Remove pack swarms. Explicit only. |
| `pack.status` | Show config, resolved IDs, swarm state, last manifest. |

All tools return: `status`, `packRunId`, `summary`, `items[]`, `errors[]`, `fixes[]`. Status is `ok`, `rejected`, or `failed`.

Scenario tools wrap existing Scenario Manager endpoints only: `/api/templates`, `/scenarios/{scenarioId}/suts`, `/scenarios/{scenarioId}/variables`, `/scenarios/{scenarioId}/variables/resolve`, and raw SUT read/write routes.

## 6. Validation

`pack.validate` rejects before mutation when selected profiles are missing/not allowed, profile `id` mismatches requested ID, `scenarioId` is absent/defunct in `/api/templates`, `sutId` does not exist for scenario, or `variablesProfileId` does not resolve for `(scenarioId, sutId)`. Failures include enough context for `scenario.inspect`, `scenario.variables`, or `scenario.sut` to fix.

Failure shape:

```json
{
  "code": "PACK_SUT_NOT_FOUND",
  "path": "loadProfile.scenarios[0].sutId",
  "message": "SUT 'postgres-local' was not found for scenario 'db-query-postgres-smoke'.",
  "fix": "Choose a listed SUT or add the missing bundle SUT."
}
```

## 7. Agentic And Runtime Safety

Flow: `Intent -> Validate -> Preflight -> Execute -> Observe -> Record -> Report`.

Rules: AI states intent before mutation; MCP shows exact plan before action; MCP observes Orchestrator outcomes; unknown outcome retries use same idempotency key; MCP never swaps scenario, SUT, profile, runner, or adapter; MCP never auto-removes failed swarms; remove shows affected swarms first.

Each mutating call creates `packRunId`. Logs, manifests, idempotency keys, and status output reference it.

Manifest path:

```text
tools/mcp-orchestrator-debug/pack-runs/<packRunId>.json
```

Manifest item:

```json
{
  "scenarioId": "db-query-postgres-smoke",
  "scenarioAlias": "db-query",
  "swarmId": "production-load-pack-production-noise-db-query-a1b2c3d4e5f6",
  "status": "STARTED",
  "correlationIds": ["..."],
  "idempotencyKeys": ["..."]
}
```

Runtime safety: no secrets in manifests; no success unless Orchestrator proves success; one active mutation per `collectionId + loadProfileId`; lock conflict returns `PACK_LOCKED`; partial failure never reports success; `CONTINUE_AND_REPORT` continues then reports failed items; immutable plan before action; bounded concurrency from runner profile; REST/outcome waits have timeouts; stable idempotency keys per pack run; no unbounded `Promise.all`.

## 8. Design

SOLID split: `PackConfigLoader`, `PackConfigValidator`, `PackPlanner`, `PackRunner`, `OrchestratorLifecycleClient`, `ScenarioCatalogClient`, `ScenarioRepairClient`, `PackRunStore`, `PackReporter`. Domain depends on interfaces. HTTP/file code stays at edges.

## 9. TDD

Must test:

- Config: valid load, invalid YAML, missing fields, raw `swarmId`, duplicate alias, ID limits, visible budget, deterministic `swarmId`, `swarmId <= 80`.
- Startup: no local scenario bundle dir needed, `start-mcp.sh` defaults to Docker detached, `--node` works, dependency failure gives clear fix.
- Validation: missing/disallowed profiles, missing/defunct scenario before Orchestrator call, bad `sutId` / `variablesProfileId`, registry unavailable.
- Scenario tools: inspect explains defunct/missing IDs; variables/SUT tools read, validate, write, and return clear fixes.
- Preflight: exact plan returned, generated IDs shown, no mutation.
- Runner: `maxParallelRequests`, `FAIL_FAST`, `CONTINUE_AND_REPORT`, timeout behavior.
- Outcomes: unknown outcome never becomes success; success/failure manifests written.
- Tools/HTTP: common result shape, `PACK_LOCKED`, partial failure reporting, Orchestrator calls use generated `swarmId` and idempotency keys, `/api/templates` guard before create.

## 10. Acceptance

- tools exist: `mcp.health`, `scenario.registry`, `scenario.inspect`, `scenario.variables`, `scenario.sut`, `pack.validate-config`, `pack.validate`, `pack.preflight`, `pack.create`, `pack.start`, `pack.stop`, `pack.remove`, `pack.status`.
- `start-mcp.sh` works with no options.
- Orchestrator and Scenario Manager unchanged.
- validation proves profiles, scenarios, SUT IDs, variables profile IDs before mutation.
- all mutating calls expose `packRunId`.
- local runner waits 5 minutes.
- pack runs write manifests.
- tests cover config, validation, runner, MCP tools, HTTP client behavior.
