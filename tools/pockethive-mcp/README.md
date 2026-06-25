# PocketHive MCP Server

Agent-facing MCP tools for PocketHive scenario authoring, deployment, runtime
verification, and evidence reporting.

This server is intentionally not a shell or devops wrapper. It talks to
PocketHive APIs, reads and writes guarded bundle files under `BUNDLES_ROOT`,
and returns structured evidence for agents and IDEs.

## 10-Minute Quickstart

From the PocketHive repo root:

```bash
npm run mcp:setup
```

Point the server at a running PocketHive stack and a separate scenario-bundles
checkout:

```bash
export POCKETHIVE_BASE_URL=http://localhost:8088
export POCKETHIVE_AUTH_USERNAME=local-admin
export POCKETHIVE_ROOT=/absolute/path/to/PocketHive
export BUNDLES_ROOT=/absolute/path/to/pockethive-scenario-bundles
export PH_BUNDLES_ROOTS='["/absolute/path/to/pockethive-scenario-bundles"]'
```

Run the doctor:

```bash
npm run mcp:doctor -- --no-config
```

Start stdio MCP:

```bash
npm run mcp:start
```

Start Streamable HTTP MCP for IDEs and assistants:

```bash
npm run mcp:start:http
```

Assistant configs should connect to:

```text
http://localhost:3100/mcp
```

Runtime debug tools are exposed through this same PocketHive MCP surface. Do not
start a separate runtime-debug MCP for normal product use.

## Agent Setup Map

Use this decision table when configuring an agent or IDE:

| Need | Use |
|---|---|
| Normal agent/IDE MCP access | `tools/pockethive-mcp` |
| Streamable HTTP endpoint | `http://localhost:3100/mcp` via `npm run mcp:start:http` |
| Stdio endpoint | `npm run mcp:start` |
| Runtime cleanup/log/version tools | `tools/pockethive-mcp` |
| Low-level terminal diagnostics only | `tools/mcp-orchestrator-debug/client.mjs` |

## Tool Name Contract

Default MCP tool names use underscores. Agent/client docs and examples must use
the names returned by `tools/list`, for example:

```text
scenario_list
scenario_get
scenario_deploy
swarm_list
swarm_create
swarm_wait_ready
swarm_get
swarm_start
swarm_stop
swarm_remove
debug_journal
component_config_preview
component_config_update
runtime_control_plane_status
runtime_inspect_worker
runtime_manifest_validate
```

Names with dots or hyphens, such as `swarm.create`,
`component.config-preview`, or `runtime.cleanup.plan`, are legacy/conceptual
names only. They are exposed only when `PH_MCP_TOOL_NAME_MODE=legacy` or
`PH_MCP_TOOL_NAME_MODE=both` is set. Do not use those names in normal agent
instructions or client integrations.

## Core Environment

| Variable | Required | Purpose |
|---|---:|---|
| `POCKETHIVE_BASE_URL` | yes | PocketHive ingress/API root, usually `http://localhost:8088` |
| `POCKETHIVE_AUTH_USERNAME` | local dev | Local authenticated user, usually `local-admin` |
| `POCKETHIVE_ROOT` | yes for validation | PocketHive repo checkout |
| `BUNDLES_ROOT` | yes for authoring | Separate scenario-bundles checkout |
| `PH_BUNDLES_ROOTS` | recommended | JSON array of configured bundle roots |
| `PH_WORKFLOW_SOURCE_ROOTS` | optional | Extra source roots for JMeter/Postman/OpenAPI/k6 inputs |
| `WIREMOCK_BASE_URL` | optional | Override WireMock admin URL; otherwise derived from `POCKETHIVE_BASE_URL` host on port `8080` |
| `TCP_MOCK_BASE_URL` | optional | Override TCP mock admin URL; otherwise derived from `POCKETHIVE_BASE_URL` host on port `8083` |

Use explicit values. Do not rely on hidden fallbacks for real environments.

When `POCKETHIVE_AUTH_USERNAME` is used for local dev auth, the MCP caches the
derived bearer token and refreshes it once after a `401` from PocketHive-owned
APIs. Explicit `POCKETHIVE_AUTH_TOKEN` values are never refreshed by the MCP.

## Canonical Agent Workflow

For new scenario work, agents should follow this loop:

```text
workflow_start
workflow_update
workflow_result
workflow_generate
workflow_validate
workflow_deploy_start
workflow_deploy_resume
workflow_verify_start
workflow_verify_resume
workflow_report
workflow_evidence_render
```

After each mutating workflow tool, read `workflow_result` first. It is the
compact handoff surface for phase, verdict, next action, claim matrix, and
proof summary. Use `workflow_status` only when `workflow_result` points to
missing fields, role checks, validation issues, or detailed evidence.

`workflow_result.proof.validation` records Scenario Manager validation as the
canonical static bundle proof:

```json
{
  "status": "fail",
  "latestLevel": "scenario-manager",
  "scenarioManager": { "status": "fail", "authoritative": false }
}
```

If Scenario Manager validation cannot complete, inspect runtime/auth
availability before patching bundle files. Generated bundle sanity checks are
not workflow validation evidence.

Scenario Manager `401` responses are reported as `WORKFLOW_ENV_AUTH_FAILED`,
with `workflow_result.nextAction.tool=env_status`. This means the active MCP
environment or auth profile needs attention; generated bundle files are not the
first fix target.

Slow machines or live stacks should use the resumable forms:

```text
workflow_deploy_start -> workflow_deploy_status -> workflow_deploy_resume
workflow_verify_start -> workflow_verify_status -> workflow_verify_resume
```

`workflow_deploy_status` and `workflow_verify_status` are read-only. The
`resume` tools advance one bounded lifecycle step and return
`nextPollAfterMs`; agents should call resume after that interval instead of
sleeping inside a long-running tool call.

The read-only MCP App widgets for `evidence_summary` and
`workflow_evidence_render` include a local light/dark mode toggle. The toggle
changes only widget presentation; the JSON MCP result remains canonical.

## Strict Runtime Proof

Production/live verification should use:

```json
{
  "proofMode": "strict",
  "includeTapSample": true
}
```

## Runtime Debug And Cleanup

The PocketHive MCP exposes label-gated worker/manager runtime diagnostics and
governed runtime cleanup:

```text
runtime_cleanup_plan
runtime_tail_worker_logs
runtime_get_worker_version
runtime_list_workers
runtime_inspect_worker
runtime_diff_swarm_runtime
runtime_control_plane_status
runtime_rabbit_topology_snapshot
runtime_swarm_timeline
runtime_manifest_validate
runtime_cleanup_execute
```

These default names use underscores for client compatibility. See the Tool Name
Contract above for legacy dotted/hyphenated name handling.

Cleanup is always `plan -> execute`. Cleanup tools delegate to Orchestrator's
`/api/runtime/cleanup/*` reconciliation API so swarm registry, Docker runtime
state, RabbitMQ topology, idempotency, and evidence stay in one authority path.
If Orchestrator HTTP is unavailable, cleanup tools fail closed instead of running
a local cleanup fallback. Register `runtime_cleanup_execute` behind HiveGate for
real policy, approval when required, and governed execution evidence.
Registered pre-run, stopped, and failed swarms are cleanup candidates only
through Orchestrator lifecycle removal. Running/removing registered swarms are
blocked by default, and the plan or execute error includes the required lifecycle
action. Rare break-glass cleanup can set `overrideRegisteredSwarmState=true` on
both plan and execute; the override is hash-bound, high-risk, and still uses only
`LIFECYCLE_REMOVE_SWARM`.

Runtime debug and cleanup tools first read `/api/runtime/debug/capabilities`.
Docker/Swarm list, logs, version, inspect, and exact Rabbit topology tools
delegate to Orchestrator's `/api/runtime/debug/*` API. If Orchestrator HTTP is
unavailable or the runtime debug contract is incompatible, only the runtime
tools fail closed; existing scenario, workflow, and swarm MCP tools are not
disabled. The MCP does not use a local Docker socket or Rabbit topology fallback
for runtime debug/cleanup.

Registered swarm-controller containers/services are removed through the
Orchestrator lifecycle action. Orphaned swarm-controller and worker
containers/services can be removed as labeled Docker cleanup candidates. RabbitMQ
queues/exchanges are eligible only when they appear in the exact runtime
ownership manifest; missing manifests block RabbitMQ cleanup. Worker control
queues derived from exact labels obey the same `includeRunning` gate as the
worker runtime object.

Worker and swarm-controller manager logs are bounded and redacted by
Orchestrator before returning to the caller. Version reports use the runtime
image/labels that Orchestrator or Swarm Controller used to create the runtime
object; deployment-wide `POCKETHIVE_VERSION` is not used as a runtime-version
source.

Additional read-only debug tools explain drift across the swarm registry,
runtime ownership manifest, Docker/Swarm state, RabbitMQ topology, and journal:

- `runtime_list_workers` lists label-gated manager/worker runtimes.
- `runtime_inspect_worker` returns a bounded inspect summary for one worker or
  manager.
- `runtime_diff_swarm_runtime` compares expected, registered, live, and cleanup
  views.
- `runtime_control_plane_status` summarizes Orchestrator-provided exact control
  queues and recent control events.
- `runtime_rabbit_topology_snapshot` reads Orchestrator-backed exact
  queues/exchanges for a concrete compute adapter.
- `runtime_swarm_timeline` builds an operator timeline from journal/runtime
  evidence.
- `runtime_manifest_validate` checks manifest drift against live runtime state.

These tools return explicit source availability instead of silently guessing.
They never expose raw environment variables or mutate runtime resources.
They are designed for zero scenario-path impact: no scenario queue consumers, no
message publishing, no worker exec/pause/resume, finite log reads only, and
RabbitMQ diagnostics use exact Orchestrator-owned metadata reads only.

Strict proof blocks on missing or failing production evidence:

- work queues drained
- expected WireMock requests handled
- observed HTTP flow order matches the scenario plan
- auth flow when the scenario config requires auth
- mutating request body assertions when mutating calls exist
- runtime payload trace from debug tap or Redis debug captures
- debug tap step-flow interpretation through `tap.flow`

`tapFlow` is derived from captured tap samples. It compares internal runtime
step flow with both the scenario plan and WireMock request journal. A healthy
proof has:

```json
{
  "tapFlow": {
    "extractable": true,
    "observed": ["GET /hello"],
    "matchedExpected": true,
    "agreesWithWireMock": true
  }
}
```

## WireMock Authoring

Generated WireMock mappings preserve explicit response body aliases
(`responseBody`, `jsonBody`, `body`, and nested `response.*` or `mock.*`
forms). For mutating HTTP methods, generated stubs include request body
assertions. When result rules are enabled and no mock response body is supplied,
the generator adds the inferred result field to the default mock body with a
value from `successCodes`.

## Acceptance Commands

Run local tests:

```bash
npm run mcp:test
```

Run agent workflow acceptance without a live stack:

```bash
npm run mcp:workflow-acceptance
```

Run agentic evals:

```bash
npm run mcp:agentic-evals
```

Run live workflow acceptance against a local PocketHive stack:

```bash
PH_WORKFLOW_ACCEPTANCE_LIVE=1 \
POCKETHIVE_BASE_URL=http://localhost:8088 \
POCKETHIVE_AUTH_USERNAME=local-admin \
npm --prefix tools/pockethive-mcp run acceptance:workflow:live
```

The live acceptance path creates a unique live bundle/swarm, verifies it with
strict tap proof, and removes both the swarm and uploaded Scenario Manager
bundle in a `finally` block. If a run is interrupted, remove matching test
swarms manually:

```bash
POCKETHIVE_AUTH_USERNAME=local-admin \
node tools/mcp-orchestrator-debug/client.mjs remove-swarm <swarmId>
```

Remove matching uploaded live bundles through Scenario Manager:

```bash
TOKEN=$(curl -s \
  -H "content-type: application/json" \
  -d '{"username":"local-admin"}' \
  "http://localhost:8088/auth-service/api/auth/dev/login" | jq -r .accessToken)

curl -X DELETE \
  -H "Authorization: Bearer ${TOKEN}" \
  "http://localhost:8088/scenario-manager/scenarios/<bundleId>"
```

## Troubleshooting

| Symptom | First check |
|---|---|
| MCP server will not start | `npm run mcp:doctor -- --no-config` |
| Assistant cannot see tools | Confirm it connects to `http://localhost:3100/mcp` and uses underscore tool names |
| Bundle path errors | Check `BUNDLES_ROOT` and `PH_BUNDLES_ROOTS` point to the same separate scenario-bundles checkout |
| Source file rejected | Add the source parent folder to `PH_WORKFLOW_SOURCE_ROOTS` |
| Live deploy fails with existing swarm | Use a unique swarm id or remove the old test swarm |
| Strict verify fails on `tap.flow` | Inspect `evidence.tapFlow` and the `tap.flow` claim gaps |
| WireMock admin calls fail | Confirm `WIREMOCK_BASE_URL` or the host-derived `http://<base-host>:8080` admin URL |

## More Docs

- `docs/inProgress/pockethive-plugin/MCP-SERVER.md` - server architecture and tool surface
- `docs/inProgress/pockethive-plugin/TOOL-CONTRACTS.md` - public tool contracts
- `docs/inProgress/pockethive-plugin/DEVELOPER-SETUP.md` - team setup and doctor behavior
- `docs/inProgress/pockethive-plugin/AI-ASSISTANT-SETUP.md` - assistant client configuration
- `docs/inProgress/pockethive-plugin/EVIDENCE.md` - evidence taxonomy
