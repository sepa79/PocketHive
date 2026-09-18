---
title: Connect PocketHive MCP and manage bundles
pagination_label: Connect MCP and bundles
---

# Connect PocketHive MCP and manage bundles

| Reader context | Details |
| --- | --- |
| Audience | Scenario authors, operators, AI agents, and IDE users |
| Prerequisites | A PocketHive source checkout, a running environment, and an approved bundles root |
| Expected outcome | Connect through client-owned stdio, create and validate one guarded bundle, exercise its lifecycle, and recognize the current removal-evidence limitation |
| Candidate source tested | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

PocketHive has one MCP implementation: `tools/pockethive-mcp/`. The configured
server ID `pockethive-bundles` names that server; it is not a second product.
`BUNDLES_ROOT` guards an external directory whose immediate children are
scenario bundle folders.

## Current versus target

| Area | Current at tested lifecycle rewrite source (`0524165e`) | Target; not current |
| --- | --- | --- |
| Distribution | Repository-local setup | Published public npm package |
| Transports | Client-owned stdio is the customer-qualified path. A Streamable HTTP implementation exists but is not customer-qualified. | Authenticated, explicitly bound HTTP transport and packaged setup without a PocketHive checkout |
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

## Connect with client-owned stdio

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
```

Configure the MCP client to start the server as a child process with the same
environment. Use absolute paths in client configuration. This Windows example
uses the common `servers` shape; if the client uses `mcpServers`, keep the
server body unchanged:

```json
{
  "servers": {
    "pockethive-bundles": {
      "type": "stdio",
      "command": "node",
      "args": ["C:\\work\\PocketHive\\tools\\pockethive-mcp\\start.cjs"],
      "env": {
        "POCKETHIVE_BASE_URL": "http://localhost:8088",
        "POCKETHIVE_AUTH_USERNAME": "local-admin",
        "POCKETHIVE_ROOT": "C:\\work\\PocketHive",
        "BUNDLES_ROOT": "C:\\work\\scenario-bundles-root",
        "PH_BUNDLES_ROOTS": "[\"C:\\\\work\\\\scenario-bundles-root\"]"
      }
    }
  }
}
```

Replace the example paths with the exact paths checked by `mcp:doctor`. On
Linux or macOS, point `args` at the absolute `tools/pockethive-mcp/start.cjs`
path and use the matching absolute bundle root. `npm.cmd run mcp:start` on
Windows and `npm run mcp:start` elsewhere launch the same stdio entry point,
but the MCP client must own that process. Do not use the workstation-specific
`start-mcp.bat` as customer configuration.

:::danger Streamable HTTP is not a customer connection path

At the tested source, `mcp:start:http` listens beyond the loopback interface
and does not authenticate inbound MCP clients. `POCKETHIVE_AUTH_USERNAME` and
`POCKETHIVE_AUTH_TOKEN` authenticate calls from MCP to PocketHive; they do not
protect the MCP endpoint. Do not run the HTTP transport on a shared host or an
untrusted network. A VM is isolation for testing, not an authentication
control for a customer deployment.

:::

### Confirm setup succeeded

| Check | Success means | It does not prove |
| --- | --- | --- |
| `mcp:setup` | Repository-local server dependencies installed successfully. | PocketHive APIs are reachable. |
| `mcp:doctor -- --no-config` | The server loads with the explicit environment and reports its resolved context and tools. | Every dependency is healthy or the identity has every grant. |
| Client-owned `mcp:start` | The client initializes the stdio child process and can list tools. | PocketHive authentication succeeds. |
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

## Generate and dry-run validate only

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

In this candidate, the generated `FLOW_DOCUMENT.md` is descriptive output and
can still contain a repository-only contract path, backend-only
`/api/capabilities`, legacy dotted tool names, or validation submission without
result polling. Do not execute it as-is. Use this guide's
`/scenario-manager/api/capabilities?all=true`, underscore tool names, and
`bundle_validate_result` polling contract; the generator remains an
implementation gap.

If a bundle contains `mock-config/wiremock`, direct `scenario_deploy` and UI
upload/Create do not load those mappings. Use `workflow_deploy_start`, then
advance one bounded step at a time with `workflow_deploy_resume`. When a
response first returns `phase: "create"`, mock loading is complete but Create
has not yet been issued. Inspect `evidence.mockConfig.wiremock.loaded` at that
point and continue only when it equals the number of supported mapping files;
otherwise stop before Create. After that gate passes, resume through terminal
completion. Explicitly loading every supported mapping is the only equivalent.

## Direct swarm tools for a bundle without mock configuration

This section is a separate reference path, not a continuation of the
`customer-demo` WireMock example above. Do not deploy `customer-demo` with
these tools: its mapping would not be loaded. Continue here only for a
validated bundle with no `mock-config` directory, an explicitly
preprovisioned SUT, and explicit variables-profile and network choices that
match that bundle and environment. Replace every `REPLACE_WITH_...` value
before submitting a call. These are unresolved placeholders, not rejected
sentinel values; never submit them literally.

Deploy, create, and wait for readiness:

```text
scenario_deploy {"bundle":"REPLACE_WITH_VALIDATED_NO_MOCK_BUNDLE_ID"}
swarm_create {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID","templateId":"REPLACE_WITH_VALIDATED_NO_MOCK_BUNDLE_ID","sutId":"REPLACE_WITH_PREPROVISIONED_SUT_ID","variablesProfileId":"REPLACE_WITH_DECLARED_VARIABLES_PROFILE_ID","networkMode":"DIRECT"}
swarm_wait_ready {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID","timeoutSec":80}
swarm_get {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID"}
swarm_start {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID"}
```

That concrete example uses `DIRECT` networking, so it must not include a
`networkProfileId`. If the scenario has no variables profile, omit
`variablesProfileId` instead of inventing one. For `PROXIED` networking, change
`networkMode` to `PROXIED` and add the explicitly configured
`networkProfileId`; both that profile and `sutId` are required.

Retain the `swarm_create` response, including its correlation and operation
metadata. The current Journal tool does not expose a correlated Create outcome.
The direct `swarm_*` tool surface also has no operation-read tool. Its strongest
direct-tool Create evidence is therefore `swarm_wait_ready` reaching
`controllerState=READY` and `workloadState=STOPPED`, followed by a fresh
`swarm_get` with `observationStale=false` and the complete expected worker set,
not request acceptance alone. This proves fresh current state but does not
supply the canonical public terminal outcome.

After Start, repeat these reads until the same correlation appears in Journal
and a fresh aggregate shows workload state `RUNNING` with the planned workers
enabled:

```text
swarm_get {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID"}
debug_journal {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID","limit":50}
```

The correlated Journal entry may be the routed signal or the controller's
internal `kind=result` with `data.status=Succeeded`; neither is the
Orchestrator's public terminal outcome. Record exactly which layer is present.

Then stop:

```text
swarm_stop {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID"}
```

Repeat these reads until the Stop correlation appears and the fresh aggregate
shows workload state `STOPPED` with the planned workers disabled. Again, do
not relabel a signal or internal result as the public terminal outcome:

```text
swarm_get {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID"}
debug_journal {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID","limit":50}
```

Only then remove and verify absence:

```text
swarm_remove {"swarmId":"REPLACE_WITH_UNIQUE_SWARM_ID"}
swarm_list {}
```

Retain the `swarm_remove` response and repeat `swarm_list` until a fresh list
no longer contains the same swarm ID. After registry removal,
`debug_journal` returns `404` for that swarm, so it cannot supply the canonical
correlated `Removed` outcome.

| Phase | Required evidence |
| --- | --- |
| Deploy | Scenario Manager accepts the intended bundle and identity |
| Create | The receipt plus `controllerState=READY`, `workloadState=STOPPED`, `observationStale=false`, and the complete expected worker set proves fresh current state; the public terminal operation is not readable through direct `swarm_*` tools |
| Start or stop | The receipt, exact Journal layer actually observed, and fresh aggregate prove dispatch/executor evidence plus convergence; the public terminal operation is not readable through direct `swarm_*` tools |
| Remove | The receipt plus fresh-list absence proves cleanup state; the public terminal operation is not readable through direct `swarm_*` tools |

An accepted request is not completion evidence. The direct `swarm_*` path can
exercise and observe the lifecycle, but it cannot independently provide strict
canonical completion evidence because it cannot read the returned operation
URL. The resumable workflow path below is different: `workflow_deploy_*`
follows and requires successful Create and Start operation records, and
`workflow_verify_*` follows and requires the successful Stop operation record
before settlement. It still does not perform or prove Remove. When a direct
path lacks required proof, preserve the response, swarm ID, correlations,
exact Journal layers, and timestamps and escalate rather than reusing the ID
or claiming a fully evidenced lifecycle.
Apply the canonical
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

This is an alternative authoring and deployment path, not a continuation of
the earlier wizard session, and it does not automatically adopt the bundle
written by `wizard_complete`. To exercise the `customer-demo` WireMock example
at runtime, start a new workflow that authors and deploys it within that same
workflow. After a deploy resume first returns `phase: "create"`, require
`evidence.mockConfig.wiremock.loaded == 1` for its one generated mapping before
the next resume can issue Create. A different count is a stop condition.

Generation and reporting write files; deployment and verification can change
the remote environment; verification can create temporary diagnostic
resources. A resumable state is evidence, not permission. At every resume,
confirm the environment, target, returned questions, and allowed action.

For exact arguments and the current registered set, use the server's
`tools/list` response.
For implementation-level details, use `tools/pockethive-mcp/README.md` from
the same source checkout; do not substitute the mutable `main` copy.

## Troubleshooting

| Symptom | Recovery |
| --- | --- |
| Wrong root or base URL | Stop, set explicit environment values, restart, and recheck `context_get`. |
| Guarded-path error | Use an approved root; never select or move data automatically. |
| Client cannot initialize stdio | Re-run `mcp:doctor -- --no-config`, verify the absolute `start.cjs` path and environment in the client, then restart that client-owned process. |
| Expected tool is missing | Use its underscore-safe name and verify `tools/list`; do not assume a planned tool exists. |
| PocketHive returns `401` or `403` | Confirm the intended identity and grants; do not use `local-admin` outside local Compose. |
| Validation or readiness remains pending | Poll the same job or swarm, then inspect structured diagnostics and Journal evidence. |
| Removal is ambiguous | Preserve the removal receipt, swarm ID, timestamps, and fresh-list result. Do not claim canonical completion or reuse the ID when the correlated outcome is unavailable. |

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
