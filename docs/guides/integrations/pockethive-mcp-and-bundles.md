---
title: Connect PocketHive MCP and manage bundles
pagination_label: Connect MCP and bundles
---

# Connect PocketHive MCP and manage bundles

| Reader context | Details |
| --- | --- |
| Audience | Scenario authors, operators, AI agents, and IDE users |
| Prerequisites | A PocketHive source checkout, a running environment, and an approved bundles root |
| Expected outcome | Connect MCP, create and validate one guarded bundle, run it, and verify cleanup |
| Last verified PocketHive version | PocketHive `v0.15.35` |

PocketHive has one MCP implementation: `tools/pockethive-mcp/`. The configured
server ID `pockethive-bundles` names that server; it is not a second product.
`BUNDLES_ROOT` guards an external directory whose immediate children are
scenario bundle folders.

## Current versus target

| Area | Current in `v0.15.35` | Target; not current |
| --- | --- | --- |
| Distribution | Repository-local setup | Published public npm package |
| Transports | stdio and Streamable HTTP | Packaged setup without a PocketHive checkout |
| Tools | Guarded authoring, validation, deployment, lifecycle, evidence, diagnostics, and cleanup tools returned by `tools/list` | Additional tools only after registration, tests, and versioning |
| MCP Apps | Two read-only evidence widgets in compatible clients | Broader dashboards, forms, and mutation-oriented apps |
| IDE support | Partial VS Code integration; no supported IntelliJ integration | Qualified IDE packages and complete command coverage |

Do not use `npm install -g @pockethive/mcp-server`; the public package is not
available for this release. Treat older plugin documents as plans unless the
feature appears in the current server's `tools/list` response.

## Before you connect

You need:

- Node.js 18 or newer;
- a PocketHive checkout and environment reachable through its official ingress;
- an identity authorized for the intended operation;
- a bundles directory with one folder per bundle and a `scenario.yaml` in each;
- explicit approval before file, remote, or destructive changes.

| Label | Effect |
| --- | --- |
| **READ** | Inspect files, configuration, or runtime state |
| **SESSION** | Change only in-memory MCP state when the tool contract explicitly says so |
| **FILE WRITE** | Change files under an allowed bundles root |
| **REMOTE WRITE** | Store content or change a PocketHive environment |
| **DIAGNOSTIC SIDE EFFECT** | Temporarily create runtime resources for a focused diagnostic check; confirm cleanup |
| **DESTRUCTIVE** | Remove runtime or persisted state |

These labels describe effects; they do not grant authorization.

## Connect with Streamable HTTP

From the PocketHive repository root, set the environment explicitly.

Windows PowerShell:

```powershell
$env:POCKETHIVE_BASE_URL = "http://localhost:8088"
$env:POCKETHIVE_AUTH_USERNAME = "local-admin" # local Compose only
$env:POCKETHIVE_ROOT = (Resolve-Path ".").Path
$env:BUNDLES_ROOT = "C:\work\scenario-bundles-root"
New-Item -ItemType Directory -Force -Path $env:BUNDLES_ROOT | Out-Null
$env:PH_BUNDLES_ROOTS = ConvertTo-Json -Compress -InputObject @($env:BUNDLES_ROOT)

npm.cmd run mcp:setup
npm.cmd run mcp:doctor -- --no-config
npm.cmd run mcp:start:http
```

Linux or macOS:

```bash
export POCKETHIVE_BASE_URL="http://localhost:8088"
export POCKETHIVE_AUTH_USERNAME="local-admin" # local Compose only
export POCKETHIVE_ROOT="$(pwd)"
export BUNDLES_ROOT="/work/scenario-bundles-root"
mkdir -p "$BUNDLES_ROOT"
export PH_BUNDLES_ROOTS="$(node -p 'JSON.stringify([process.env.BUNDLES_ROOT])')"

npm run mcp:setup
npm run mcp:doctor -- --no-config
npm run mcp:start:http
```

Keep the server terminal open and connect the client to:

```text
http://localhost:3100/mcp
```

Minimal client configuration:

```json
{
  "servers": {
    "pockethive-bundles": {
      "type": "http",
      "url": "http://localhost:3100/mcp"
    }
  }
}
```

For a client that owns a stdio process, use `npm.cmd run mcp:start` on Windows
or `npm run mcp:start` elsewhere, with the same environment. Do not use the
workstation-specific `start-mcp.bat` as customer configuration.

### Confirm setup succeeded

| Check | Success means | It does not prove |
| --- | --- | --- |
| `mcp:setup` | Repository-local server dependencies installed successfully. | PocketHive APIs are reachable. |
| `mcp:doctor -- --no-config` | The server loads with the explicit environment and reports its resolved context and tools. | Every dependency is healthy or the identity has every grant. |
| `mcp:start:http` | The process stays running and listens on the expected HTTP endpoint. | PocketHive authentication succeeds. |
| `context_get {}` | The returned `baseUrl` and bundles root are the intended targets. | Remote writes are authorized; still obtain approval. |

## Verify the connection

The examples below are MCP tool calls, not terminal commands. Current tool
names use underscores.

```text
context_get {}
health_check {}
bundle_list {}
scenario_list {}
swarm_list {}
```

Before any write, verify that `context_get` shows the intended `baseUrl` and
bundles root. `health_check` must reach the required dependencies; an
unconfigured metrics dependency can be unhealthy while the core API remains
available. Record existing scenarios and swarms so later cleanup targets the
correct objects.

## Create and validate one bundle

The following flow keeps files under `BUNDLES_ROOT` and validates without
storing the scenario:

```text
wizard_start {"intent":"Create a local REST smoke scenario that sends POST /api/test to WireMock at one request per second.","bundleId":"customer-demo","protocol":"REST","target":"wiremock-local","endpoint":{"method":"POST","path":"/api/test"},"requestBody":"{\"event\":\"customer-demo\"}","ratePerSec":1}
wizard_summary {"sessionId":"<sessionId>"}
wizard_complete {"sessionId":"<sessionId>"}
bundle_validate {"bundle":"customer-demo","validator":"scenario-manager-dry-run"}
bundle_validate_result {"jobId":"<jobId>"}
```

1. `wizard_start` and `wizard_summary` change only session state.
2. Review the summary and answer any returned human checkpoint before continuing.
3. `wizard_complete` writes the guarded bundle; inspect its diff.
4. Poll the same validation job until it finishes successfully with no blocking diagnostics.

Dry-run validation proves contract acceptance at that time. It does not deploy
the bundle or prove runtime behavior. Do not switch to an uploading validator
to bypass a failed result.

## Deploy, run, and clean up

Continue only after validation and explicit approval for the target environment:

Deploy, create, and wait for readiness:

```text
scenario_deploy {"bundle":"customer-demo"}
swarm_create {"swarmId":"customer-demo-run","templateId":"customer-demo","sutId":"wiremock-local"}
swarm_wait_ready {"swarmId":"customer-demo-run","timeoutSec":80}
swarm_get {"swarmId":"customer-demo-run"}
swarm_start {"swarmId":"customer-demo-run"}
```

After Start, repeat these reads until the correlated outcome exists and a
fresh aggregate shows `RUNNING` with the planned workers enabled:

```text
swarm_get {"swarmId":"customer-demo-run"}
debug_journal {"swarmId":"customer-demo-run","limit":50}
```

Then stop:

```text
swarm_stop {"swarmId":"customer-demo-run"}
```

Repeat these reads until the correlated Stop outcome exists and the fresh
aggregate shows `STOPPED` with the planned workers disabled:

```text
swarm_get {"swarmId":"customer-demo-run"}
debug_journal {"swarmId":"customer-demo-run","limit":50}
```

Only then remove and verify absence:

```text
swarm_remove {"swarmId":"customer-demo-run"}
debug_journal {"swarmId":"customer-demo-run","limit":50}
swarm_list {}
```

Repeat `debug_journal` and `swarm_list` until the correlated `Removed` outcome
exists and a fresh list no longer contains the same swarm ID.

| Phase | Required evidence |
| --- | --- |
| Deploy | Scenario Manager accepts the intended bundle and identity |
| Create | The exact swarm reaches `Ready` with `healthy >= desired` |
| Start or stop | The correlated outcome is present and a fresh aggregate shows worker convergence |
| Remove | A correlated `Removed` outcome exists and the swarm is absent from the fresh list |

An accepted request is not completion evidence. Apply the canonical
[acceptance, dispatch, and convergence model](../concepts/system-workflows.md#read-the-evidence-in-layers).
If any state is ambiguous, stop mutations, inspect `swarm_get` and the Journal,
and keep the same swarm ID. Do not create or remove another target merely to
obtain a clean result.

`swarm_remove` does not delete the uploaded scenario or guarded source folder.
Remove a disposable remote scenario through **Scenarios** only after the swarm
is gone; delete guarded source files as a separate authoring decision.

## Resumable agent workflow

For a longer agent-guided task, start a resumable workflow and let
`workflow_result` identify the allowed next action. By default, workflow state
is persisted to `.pockethive-workflows.json` under the bundles root, so treat
workflow start and updates as **FILE WRITE**. Set
`PH_WORKFLOW_PERSISTENCE=memory` before starting the server only when ephemeral
state is intended.

```text
workflow_start {"sourceType":"plain-instructions","instructions":"Create a REST smoke test for POST /api/test with a WireMock double."}
workflow_result {"workflowId":"<workflowId>"}
```

```text
workflow_start → workflow_result
→ workflow_update / workflow_result as questions are answered
→ workflow_generate → workflow_result
→ workflow_validate → workflow_result
→ workflow_deploy_start → workflow_deploy_status / workflow_deploy_resume
→ workflow_verify_start → workflow_verify_status / workflow_verify_resume
→ workflow_report → workflow_evidence_render
```

Status tools are read-only; each Resume call advances one bounded step. Read
`workflow_result` after every mutating workflow call before choosing the next
action.

Generation and reporting write files; deployment and verification can change
the remote environment; verification can create temporary diagnostic
resources. A resumable state is evidence, not permission. At every resume,
confirm the environment, target, returned questions, and allowed action.

For exact arguments and the current registered set, use the server's
`tools/list` response and the
[PocketHive MCP implementation reference](https://github.com/sepa79/PocketHive/blob/main/tools/pockethive-mcp/README.md).

## Troubleshooting

| Symptom | Recovery |
| --- | --- |
| Wrong root or base URL | Stop, set explicit environment values, restart, and recheck `context_get`. |
| Guarded-path error | Use an approved root; never select or move data automatically. |
| Port `3100` is unavailable | Inspect the server terminal and `PH_MCP_HTTP_PORT`; use one explicit matching port. |
| Expected tool is missing | Use its underscore-safe name and verify `tools/list`; do not assume a planned tool exists. |
| PocketHive returns `401` or `403` | Confirm the intended identity and grants; do not use `local-admin` outside local Compose. |
| Validation or readiness remains pending | Poll the same job or swarm, then inspect structured diagnostics and Journal evidence. |
| Removal is ambiguous | Require the correlated outcome and fresh-list absence for the same swarm ID. |

`npm run mcp:start:http` pins port `3100`. To use another port, skip that
wrapper, set the value, run the server directly from the repository root, and
use the same port in the client URL:

```powershell
$env:PH_MCP_HTTP_PORT = "3200"
node .\tools\pockethive-mcp\server.mjs
```

```bash
PH_MCP_HTTP_PORT=3200 node tools/pockethive-mcp/server.mjs
```

## Permissions and safety

- Keep file writes under the explicit guarded root.
- Confirm environment, identity, and exact target before every remote write.
- Treat cleanup, raw logs, and tap sampling as governed operations.
- Sanitize endpoints, payloads, identifiers, and bundle names before sharing evidence.
- Call and review `runtime_cleanup_plan` before `runtime_cleanup_execute`.
- Treat `runtime_cleanup_execute` as destructive and place it behind the
  environment's approval or HiveGate control.
- Both cleanup tools delegate to official Orchestrator reconciliation and fail
  closed when the Orchestrator is unavailable; there is no local fallback.

## Next step

- Use the [15-minute UI quickstart](../onboarding/quickstart-15min.md) for the
  equivalent manual lifecycle.
- Use [system workflows](../concepts/system-workflows.md) for proof boundaries.
- Use [observability and troubleshooting](../operators/observability-troubleshooting.md)
  when a call does not converge.
- Use [authoring and test tools](authoring-and-test-tools.md) to choose another surface.
