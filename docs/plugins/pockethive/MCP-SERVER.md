# PocketHive MCP Server — Migration & Enhancement Spec

## Status
`IMPLEMENTED BASELINE / REFERENCE`

## Overview

The MCP server lives in the PocketHive repo at
`tools/pockethive-mcp/server.mjs`. This spec covers:

1. Maintaining the migrated server at `tools/pockethive-mcp/`
2. Decoupling from the bundles repo (BUNDLES_ROOT becomes configurable)
3. New tools for context switching and environment management
4. Publishing as `@pockethive/mcp-server` npm package
5. Adding HTTP/SSE transport alongside existing stdio
6. Removing shell/devops/log-scraping responsibilities from the MCP surface
7. Removing general GitHub issue tools from the PocketHive MCP surface
8. Exposing deterministic workflow state/configuration without embedding an LLM

## Source location

```
Current:  PocketHive/tools/pockethive-mcp/server.mjs
```

## What changes

### REPO_ROOT -> BUNDLES_ROOT

Currently `REPO_ROOT` is derived from the server file's own location and
assumed to be the bundles repo root. After migration the server lives in
the PocketHive repo, so bundles are elsewhere.

```javascript
// Before
const REPO_ROOT = resolve(__dirname, '../..');  // always bundles repo

// After
const BUNDLES_ROOT = requireEnv('BUNDLES_ROOT');      // injected by plugin
const POCKETHIVE_ROOT = resolve(__dirname, '../..');  // now the PH repo itself
```

All tools that reference `REPO_ROOT` for bundle operations switch to
`BUNDLES_ROOT`. All tools that reference `POCKETHIVE_ROOT` for validation
use the server's own location.

### .env loading removed

The server no longer reads a `.env` file on startup. All config comes
from `process.env` injected by the IDE plugin at spawn time. When run
standalone (CLI / Docker), users set env vars directly.

A `--env-file` CLI flag is supported for standalone use:

```bash
node server.mjs --env-file /path/to/.env
```

The `--env-file` flag is a standalone convenience only. IDE plugin mode never
reads `.env` files.

### No shell tools

The MCP server must not execute shell commands or spawn local dev tools. It is
not responsible for Docker, Compose, Maven, npm, Git, local stack lifecycle, or
container log access.

Allowed integrations are PocketHive-owned HTTP APIs, RabbitMQ management APIs,
Grafana's provisioned ClickHouse datasource API, WireMock/TCP mock admin APIs,
and guarded bundle file access.
If PocketHive exposes a structured log API, the MCP may use that API. Direct
Docker/container logs and direct Loki queries are out of scope for this phase.
Loki may be considered later only behind a PocketHive-owned API.

### Dual transport

```javascript
// stdio (default — IDE plugin mode)
if (!process.env.PH_MCP_HTTP_PORT) {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

// HTTP/SSE (team/Docker mode)
if (process.env.PH_MCP_HTTP_PORT) {
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: () => crypto.randomUUID(),
  });
  await server.connect(transport);
  // Node HTTP server exposes /mcp on PH_MCP_HTTP_PORT.
}
```

### package.json

```json
{
  "name": "@pockethive/mcp-server",
  "version": "0.15.33",
  "description": "PocketHive MCP server — full lifecycle tools for scenario authoring and swarm management",
  "bin": { "pockethive-mcp": "./server.mjs" },
  "files": ["server.mjs", "start.cjs"],
  "publishConfig": { "access": "public" },
  "scripts": {
    "start": "node server.mjs",
    "start:http": "PH_MCP_HTTP_PORT=3100 node server.mjs"
  }
}
```

Version tracks PocketHive releases (currently `0.15.33`).

Phase 1.5 registers the `evidence-summary` MCP App as an inline resource at
`ui://pockethive/evidence-summary-v1.html`. Phase 2 also registers the
workflow evidence report resource at
`ui://pockethive/workflow-evidence-v1.html`. Broader MCP Apps remain future
platform work.

## Tool Surface

Existing tools are migrated only when they fit the no-shell responsibility
boundary.

Each tool must have a contract entry matching `TOOL-CONTRACTS.md` before it is
implemented or changed.

Published MCP tool names use underscores. Names with dots or hyphens are
legacy/conceptual aliases and must not be used in normal agent or plugin
integrations unless `PH_MCP_TOOL_NAME_MODE=legacy` or `both` is set.

### Bundle management
- `bundle_list` — lists bundles in `BUNDLES_ROOT/bundles/`
- `bundle_read` — reads a file from a bundle
- `bundle_scaffold` — quick-pick bundle scaffold for IDE users
- `bundle_validate` — async validation through Scenario Manager; never a shell command
- `bundle_validate_result` — polls validation job result
- `bundle_diff` — preview generated/session changes before export

### Wizard authoring
- `wizard_start` — starts a novice bundle design session; no file writes
- `wizard_answer` — records one answer and returns the next required question
- `wizard_summary` — previews the generated plan; no file writes
- `wizard_complete` — creates a new bundle and runs generation sanity checks

### Agent-managed workflows
- `workflow_start`, `workflow_source_read`, `workflow_update`,
  `workflow_status`, `workflow_preview`, `workflow_generate`,
  `workflow_validate`, `workflow_deploy`, `workflow_verify`,
  `workflow_patch`, and `workflow_report`
  provide the deterministic control surface for external-agent test conversion
  workflows. The MCP owns state, gates, generated artifacts, official API calls,
  and evidence. The external agent owns source interpretation and debug/fix
  choices.
- `workflow_result` is the compact agent-facing handoff shape for the same
  workflow: verdict, phase, diagnosis, next action, proof summary, and refs.
  `workflow_status`, `workflow_report`, `workflow_evidence_render`, and
  workflow traces include the same `agent` object so agents can stay on one
  interpretation path and drill into full evidence only when needed.
- Runtime verification has two explicit proof modes. `accept-partial` is for
  fast debug loops and accepts partial reports while preserving gaps. Production
  and live acceptance use `proofMode=strict` with `includeTapSample=true`; this
  fails missing/partial production proof for queue drain, request handling,
  flow order, configured auth, mutating payload body assertions, and runtime
  payload trace.
- `workflow_config_get`, `workflow_config_validate`, and `workflow_list` are
  read-only plugin-facing tools for configuration and status display. Plugins
  may render remaining `nextQuestions`, but must not answer them or call
  mutating workflow tools.

Workflow registration lives in `tools/pockethive-mcp/workflow-tools.mjs`; the
main `server.mjs` injects shared PocketHive helpers into that module. This keeps
the workflow surface maintainable without creating duplicate bundle-generation
or validation logic.

#### Agent fast path

Agents should use `workflow_result` as the first read after every workflow
mutation. The fast loop is:

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

When `workflow_result.nextAction.tool` is present, call that tool next unless
the user changes the goal. Use `workflow_status` only when the compact result
points to missing fields, role checks, or evidence details that need inspection.
Use `workflow_evidence_render` for human handoff, not as the agent's primary
decision surface.

The MCP App evidence widgets include a local light/dark mode toggle for human
readability. The toggle only changes widget presentation and does not mutate
workflow state, answer questions, call PocketHive APIs, or change the canonical
JSON result.

For deployment and runtime proof, `workflow_result` prefers resumable lifecycle
tools. `workflow_deploy_status` and `workflow_verify_status` are read-only;
`workflow_deploy_resume` and `workflow_verify_resume` advance one bounded step
and return `nextPollAfterMs` when another poll is needed. Agents should use that
value as the retry interval instead of blocking a tool call with thread sleeps.

If Scenario Manager dry-run or deploy returns PocketHive API auth failures, the
workflow records `WORKFLOW_ENV_AUTH_FAILED` and points
`workflow_result.nextAction.tool` at `env_status`. That is an environment/auth
remediation path, not a generated-bundle patch path.

Validation proof is Scenario Manager-owned.
`workflow_result.proof.validation.status` shows the latest Scenario Manager
validation attempt and `workflow_result.proof.validation.scenarioManager` shows
the dry-run proof. If Scenario Manager validation cannot complete, agents should
treat that as a runtime/auth/Scenario Manager gap before editing generated
bundle files.

Production proof uses:

```json
{
  "proofMode": "strict",
  "includeTapSample": true
}
```

With tap samples enabled, `workflow_verify` and `evidence_summary` interpret
the tap payload as internal step-flow evidence and compare it with both the
scenario plan and WireMock request journal. Agents should read
`workflow_result.proof` first, then drill into `evidence.tapFlow` or the full
report only when a tap-flow gap is reported.

Debug proof may use `proofMode=accept-partial` to keep iteration fast, but the
result must still surface recorded gaps through `workflow_result.diagnosis`,
`workflow_result.proof`, and the full evidence report.

For local dev authentication, `POCKETHIVE_AUTH_USERNAME` produces a cached
bearer token. PocketHive-owned API calls refresh that username-derived token
once after a `401`. Explicit `POCKETHIVE_AUTH_TOKEN` values are treated as
caller-owned and are not refreshed by the MCP.

### Scenario Manager contracts
- `scenario_contracts_get` — reads Scenario Manager capability/template/scenario
  contract context for wizard and authoring tools
- `scenario_capabilities_get` — reads worker capability manifests from
  Scenario Manager `/api/capabilities`
- `scenario_templates_catalog` — reads Scenario Manager `/api/templates`,
  including defunct bundle status

`scenario_contracts_get` reads Scenario Manager `/api/authoring-contract` on the
first call and caches it for the MCP server process. Later calls reuse the
cached contract unless the caller passes `forceRefresh: true`, or passes
`checkFingerprint: true` and `/api/authoring-contract/fingerprint` reports a
changed fingerprint.

`bundle_validate` supports two explicit validators:

- `scenario-manager-dry-run` — validates a zip through
  `POST /validation/scenario-bundles`; no Scenario Manager writes
- `scenario-manager-upload` — validates by uploading/replacing the bundle
  through Scenario Manager. This has Scenario Manager write side effects.

### Scenario lifecycle
- `scenario_deploy` — HTTP zip upload (local + remote)
- `scenario_list` — lists loaded scenarios
- `scenario_get` — gets a specific scenario

### Swarm lifecycle
- `swarm_list`, `swarm_get`, `swarm_create`, `swarm_start`
- `swarm_wait_ready`, `swarm_stop`, `swarm_remove`
- `workflow_deploy_start`, `workflow_deploy_status`, `workflow_deploy_resume`
  and `workflow_verify_start`, `workflow_verify_status`,
  `workflow_verify_resume` for slow machines where scenario
  startup or runtime settlement may take several minutes. These tools persist a
  lifecycle operation id and advance in short, repeatable calls instead of
  relying on one long MCP timeout.

### Real-time component control
- `component_config_preview` — reads the current runtime config and returns the
  merge plan without sending an update.
- `component_config_update` — sends a targeted runtime config-update through
  Orchestrator `POST /api/components/{role}/{instance}/config`.

For `inputs.redis.listName`, agents must first call `swarm_get` and verify that
the returned swarm status is explicitly `STOPPED`. An accepted `swarm_stop`
request is not completion evidence. Running, transitional, unknown, or stale
state must block the call; agents must not infer or bypass this rule.

This is the same write path used by the web UI. Before sending the update, the
MCP tool reads the latest exact `status-full` config for the target component.
It first checks Orchestrator journal evidence and, when worker config snapshots
are not journaled, binds to the control-plane status stream and requests a fresh
status refresh before proceeding. It then deep-merges the requested `patch` into
that current config and sends the merged config to Orchestrator. This prevents
sparse updates from accidentally dropping existing config fields.

It is useful for debug and live tuning, for example changing a generator input
rate:

```json
{
  "swarmId": "webauth-loop-redis-5-customers-...",
  "role": "generator",
  "instanceId": "generator-...",
  "patch": {
    "inputs": {
      "redis": {
        "ratePerSec": 10
      }
    }
  }
}
```

If the current config cannot be read for the exact `role` and `instanceId`, the
tool fails with `CURRENT_CONFIG_UNAVAILABLE` instead of sending a partial patch.
Use `component_config_preview` when an agent or human wants to inspect the
planned merge first; it uses the same read and merge logic but does not publish
a config update.
The returned Orchestrator `202 Accepted` response and watch topics are dispatch
evidence only; agents should follow with `debug_journal`, status-full snapshots,
queue depth, or metrics to prove the component applied the update.

### Debugging
- `debug_queues`, `debug_tap`, `debug_tap_read`, `debug_tap_close`
- `debug_journal`, `debug_config_update` compatibility alias
- `metrics_query`
- `runtime_tail_worker_logs`
- `evidence_summary` — read-only aggregate evidence model for one swarm
- `workflow_result` — compact read-only agent handoff for one workflow
- `workflow_evidence_render` — read-only MCP App render tool for workflow
  evidence, claim matrix, role checks, lifecycle operations, and gaps
- PocketHive-provided log tools may be added later only if backed by
  PocketHive APIs. Direct Docker logs and direct Loki queries are out of scope.

### Mock servers
- `mock_wiremock_list`, `mock_wiremock_add`, `mock_wiremock_reset`,
  `mock_wiremock_requests`, `mock_wiremock_unmatched`
- `mock_tcp_list`, `mock_tcp_add`, `mock_tcp_reset`, `mock_tcp_requests`,
  `mock_tcp_unmatched`, `mock_tcp_scenarios`, `mock_tcp_reset_scenarios`,
  `mock_tcp_enable`, `mock_tcp_disable`, `mock_tcp_update`

### Removed shell/dev tools
- `docker.execute`, `docker.compose`
- `git.execute`, `git.status`, `git.diff`
- `maven.execute`, `npm.execute`, `tools.check`
- `docs.refresh`, `paths.check`
- `stack.start`, `stack.stop`, `stack.rebuild`
- `bundle.commit`
- `debug.shell`, `debug.docker-logs`
- `github.list_issues`, `github.get_issue`, `github.create_issue`
- `github.update_issue`, `github.add_issue_comment`, `github.search_issues`

### Health
- `health_check`

### GitHub issues
General GitHub issue access is out of scope for PocketHive MCP. Use a separate
GitHub MCP server configured with a fine-grained issue-only token.

A future PocketHive-specific helper may be considered only if it exports
PocketHive evidence into a structured issue payload, for example
`issue.export-evidence`. It must not become a general GitHub client.

### Environment (existing, enhanced)
- `env_list` — enhanced to return structured environment objects
- `env_switch` — switches active environment, takes effect on next MCP server spawn

## New tools

### context_get

Returns the current active configuration context. AI agents should call
this at the start of any session to understand what they are working with.

```
Tool: context_get
Input: (none)
Output: {
  bundlesRoot: string,          // active BUNDLES_ROOT path
  bundlesRootName: string,      // display name (last path segment)
  pockethiveRoot: string,       // PocketHive repo root
  activeEnvironment: string,    // active environment name
  baseUrl: string,              // active POCKETHIVE_BASE_URL
  hasAuthToken: boolean,         // token presence only; never returns token
  mcpVersion: string,           // server version
  platform: string              // win32 / linux / darwin
}
```

### context_set_bundles_root

Switches the active bundles root. Takes effect immediately — no server
restart needed. The server updates its in-memory `BUNDLES_ROOT` variable.

```
Tool: context_set_bundles_root
Input: { path: string }
Output: {
  switched: true,
  path: string,
  bundleCount: number           // number of bundles found at new root
}
```

Note: this only affects the current server process. The IDE plugin also
updates its settings so the new root persists across restarts.

### context_list_bundles_roots

Lists all configured bundles roots from the IDE plugin settings.
The server receives these as a JSON array in `PH_BUNDLES_ROOTS` env var.

```
Tool: context_list_bundles_roots
Input: (none)
Output: {
  roots: [{ path: string, name: string, active: boolean }],
  active: string
}
```

### env_add

Creates a new named environment profile. Stored in the IDE plugin settings.
The server signals the plugin to persist the new profile.

```
Tool: env_add
Input: {
  name: string,
  baseUrl: string,
  rabbitUser?: string,
  tcpMockUrl?: string,
  wiremockUrl?: string
}
Output: { added: true, name: string }
```

### env_remove

Removes a named environment profile.

```
Tool: env_remove
Input: { name: string }
Output: { removed: true, name: string }
```

### env_current

Returns the active environment details (without secrets).

```
Tool: env_current
Input: (none)
Output: {
  name: string,
  baseUrl: string,
  rabbitUser: string,
  tcpMockUrl: string,    // resolved (auto-derived if not set)
  wiremockUrl: string,   // resolved (auto-derived if not set)
  hasAuthToken: boolean  // true if token stored in keychain
}
```

### bundle_diff

Shows the diff between the local bundle files and the version currently
deployed to the Scenario Manager.

> **Note**: The Scenario Manager REST API does not expose an endpoint to
> list or download deployed bundle file contents. `bundle_diff` therefore
> compares local files against a local deploy manifest written by
> `scenario_deploy` at deploy time (`.pockethive-deploy-manifest.json`
> in the bundle directory). If no manifest exists, the tool reports all
> local files as undeployed. This is a known limitation — a future SM
> API endpoint (`GET /scenarios/{id}/files`) would enable true remote diff.

```
Tool: bundle_diff
Input: { bundle: string }
Output: {
  bundle: string,
  localFiles: string[],
  deployedFiles: string[],   // from local manifest, not SM API
  added: string[],
  removed: string[],
  modified: string[]         // files whose mtime is newer than last deploy
}
```

## Environment variables reference

All variables injected by the IDE plugin at spawn time:

| Variable | Source | Description |
|---|---|---|
| `POCKETHIVE_BASE_URL` | active environment | Reverse proxy root URL |
| `BUNDLES_ROOT` | active bundles folder | Path to bundles directory; should normally be a separate scenario-bundles repo |
| `POCKETHIVE_ROOT` | plugin setting | Path to PocketHive repo checkout |
| `RABBITMQ_DEFAULT_USER` | active environment | RabbitMQ username |
| `RABBITMQ_DEFAULT_PASS` | keychain | RabbitMQ password |
| `TCP_MOCK_BASE_URL` | active environment (optional) | Override TCP mock admin URL |
| `WIREMOCK_BASE_URL` | active environment (optional) | Override WireMock admin URL |
| `PH_BUNDLES_ROOTS` | plugin settings | JSON array of all configured bundle roots |
| `PH_MCP_HTTP_PORT` | plugin setting (HTTP mode) | Port for HTTP/SSE transport |

## File structure

```
tools/pockethive-mcp/
  server.mjs              <- main server (migrated + enhanced)
  start.cjs               <- CommonJS entry point for bin
  package.json            <- @pockethive/mcp-server
  package-lock.json
  apps/                   <- MCP App source (not published to npm)
    package.json
    vite.config.ts
    build-all.mjs
    shared/               <- design tokens and shared helpers
    evidence-summary/     <- Phase 1.5 App
    swarm-dashboard/      <- future
    bundle-explorer/      <- future
    queue-monitor/        <- future
    health-dashboard/     <- future
    create-swarm-form/    <- future
    journal-viewer/       <- future
    tap-viewer/           <- future
  dist/
    apps/                 <- built App HTML; Phase 1.5 publishes evidence-summary only
  Dockerfile              <- for HTTP/SSE standalone deployment
  README.md
```

## Dockerfile (HTTP/SSE mode)

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY server.mjs start.cjs ./
COPY dist/apps/ dist/apps/
ENV PH_MCP_HTTP_PORT=3100
EXPOSE 3100
CMD ["node", "server.mjs"]
```

## Bundles repo after migration

Scenario bundles should live in a separate repository from PocketHive product
code. The product repo can retain small examples and legacy smoke fixtures, but
authoring tools should treat an external `BUNDLES_ROOT` checkout as the normal
case.

The bundles repo `mcp.json` changes from pointing at the local server file
to the installed npm package:

```json
{
  "mcpServers": {
    "pockethive-bundles": {
      "command": "npx",
      "args": ["@pockethive/mcp-server"],
      "env": {
        "BUNDLES_ROOT": "${workspaceFolder}",
        "POCKETHIVE_BASE_URL": "http://localhost:8088"
      }
    }
  }
}
```

Users run `npm install -g @pockethive/mcp-server` once. The bundles repo
becomes purely scenario content with no Node.js tooling of its own.
