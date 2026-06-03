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

`workflow_result.proof.validation` separates local structural proof from live
Scenario Manager proof:

```json
{
  "status": "fail",
  "latestLevel": "scenario-manager",
  "structural": { "status": "pass" },
  "scenarioManager": { "status": "fail", "authoritative": false }
}
```

If structural validation passes but Scenario Manager validation fails, inspect
runtime/auth availability before patching bundle files. Agents can continue to
report the structural proof with the recorded Scenario Manager gap.

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
