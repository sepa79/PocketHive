# PocketHive MCP Apps

## Status
`PHASE 1.5 SPIKE + FUTURE PLATFORM`

## Overview

MCP Apps can extend the PocketHive MCP server so that AI agents can render
interactive dashboards, forms, and live monitors directly inside the chat
conversation — not just text.

`@modelcontextprotocol/ext-apps` is published, so the package-exists blocker is
gone. Target client support still needs to be verified per client.

The early scope is deliberately small: read-only evidence widgets that
visualise existing MCP tool output. `evidence_summary` renders runtime swarm
evidence, and `workflow_evidence_render` renders the agent-managed workflow
evidence/report. The broader dashboard/form platform remains future work.

**Potential clients:** Claude, Claude Desktop, VS Code GitHub Copilot, Goose,
Postman, MCPJam. Confirm support before building against any client.

---

## How it fits the existing architecture

```
+------------------------------------------------------------------+
|  AI Chat (Claude, Copilot, Amazon Q, etc.)                       |
|  User asks: "show evidence for swarm-x"                          |
|  Agent calls: evidence_summary                                   |
|  Host fetches: ui://pockethive/evidence-summary                  |
|  Renders: read-only evidence widget in sandboxed iframe          |
+------------------------------------------------------------------+
                         same tools
+------------------------------------------------------------------+
|  tools/pockethive-mcp/server.mjs                                 |
|  registerAppTool() wraps existing reg() tools                    |
|  registerAppResource() serves bundled HTML                       |
+------------------------------------------------------------------+
                         same design tokens
+------------------------------------------------------------------+
|  tools/pockethive-mcp/apps/                                      |
|  Vite-bundled HTML/CSS/TypeScript MCP App components             |
|  Shares design tokens with ui-v2 (CSS variables only)           |
+------------------------------------------------------------------+
```

The MCP server must use explicit capability negotiation before returning App
resources. JSON-only clients receive JSON tool responses. App-capable clients
receive the same tool result plus the declared UI resource.

---

## Project structure

Phase 1.5 adds only evidence widgets. Other app folders below are future
platform candidates.

```
tools/pockethive-mcp/
  server.mjs                    <- existing MCP server
  apps/                         <- new: MCP App UIs
    package.json
    vite.config.ts
    tsconfig.json
    shared/
      tokens.css                <- design tokens (shared with ui-v2)
      components.ts             <- shared vanilla TS helpers
    evidence-summary/           <- Phase 1.5
      mcp-app.html
      src/mcp-app.ts
    swarm-dashboard/            <- future
      mcp-app.html
      src/mcp-app.ts
    bundle-explorer/
      mcp-app.html
      src/mcp-app.ts
    queue-monitor/
      mcp-app.html
      src/mcp-app.ts
    health-dashboard/
      mcp-app.html
      src/mcp-app.ts
    create-swarm-form/
      mcp-app.html
      src/mcp-app.ts
    journal-viewer/
      mcp-app.html
      src/mcp-app.ts
    tap-viewer/
      mcp-app.html
      src/mcp-app.ts
  dist/
    apps/                       <- built HTML files (gitignored)
```

---

## Dependencies

```json
// tools/pockethive-mcp/apps/package.json
{
  "type": "module",
  "scripts": {
    "build": "node build-all.mjs",
    "build:watch": "node build-all.mjs --watch"
  },
  "dependencies": {
    "@modelcontextprotocol/ext-apps": "^1.7.1",
    "@modelcontextprotocol/sdk": "^1.29.0"
  },
  "devDependencies": {
    "typescript": "~5.8.3",
    "vite": "^7.0.0",
    "vite-plugin-singlefile": "^2.0.0"
  }
}
```

```javascript
// tools/pockethive-mcp/apps/build-all.mjs
// Builds each app as a self-contained single HTML file
import { build } from 'vite';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const apps = [
  'evidence-summary'
];

for (const app of apps) {
  await build({
    configFile: resolve(__dirname, 'vite.config.ts'),
    build: {
      outDir: resolve(__dirname, '../dist/apps'),
      rollupOptions: { input: resolve(__dirname, app, 'mcp-app.html') },
    },
  });
  console.log(`Built: ${app}`);
}
```

```typescript
// tools/pockethive-mcp/apps/vite.config.ts
import { defineConfig } from 'vite';
import { viteSingleFile } from 'vite-plugin-singlefile';

export default defineConfig({
  plugins: [viteSingleFile()],
  build: { outDir: '../dist/apps', emptyOutDir: false },
});
```

---

## Server integration

In `server.mjs`, replace `reg()` calls for App-capable tools with
`registerAppTool()` from `@modelcontextprotocol/ext-apps/server`.
Non-App tools keep using `reg()` unchanged.

```javascript
// tools/pockethive-mcp/server.mjs (additions)
import {
  registerAppTool,
  registerAppResource,
  RESOURCE_MIME_TYPE,
} from '@modelcontextprotocol/ext-apps/server';
import { readFileSync, existsSync } from 'node:fs';

const APPS_DIST = resolve(__dirname, 'dist/apps');

function appHtml(name) {
  const path = resolve(APPS_DIST, `${name}.html`);
  if (!existsSync(path)) return null;
  return readFileSync(path, 'utf-8');
}

function registerApp(name, toolName, desc, schema, handler) {
  const resourceUri = `ui://pockethive/${name}`;

  registerAppTool(server, toolName, {
    title: toolName,
    description: desc,
    inputSchema: schema,
    _meta: { ui: { resourceUri } },
  }, handler);

  registerAppResource(server, resourceUri, resourceUri,
    { mimeType: RESOURCE_MIME_TYPE },
    async () => {
      const html = appHtml(name);
      if (!html) throw new Error(`App not built: ${name}. Run npm run build in apps/`);
      return { contents: [{ uri: resourceUri, mimeType: RESOURCE_MIME_TYPE, text: html }] };
    }
  );
}
```

---

## Phase 1.5 App — Evidence Summary

**Trigger:** User asks "show evidence for swarm X", "did this run prove the
scenario works?", "what evidence is missing?"

**Tool:** `evidence_summary`

**Scope:** read-only. The tool returns the canonical evidence model. The widget
only renders that model.

**UI:**

```
+------------------------------------------------------------------+
| Evidence Report: <swarm-id>                              PARTIAL  |
|------------------------------------------------------------------|
| Queues drained                  PASS     work queues are empty    |
| Requests handled                PASS     6/6 expected calls       |
| Payloads valid                  PASS     WireMock body matchers   |
| Data passed between steps       PASS     extract -> later request |
| Step flow                       PASS     ordered timestamps       |
| Auth flow                       PASS     token + bearer calls     |
| Auth expiry / refresh           PASS     repeated token refresh   |
| Redis data / token state        PASS     token + debug captures   |
|------------------------------------------------------------------|
| Flow timeline                                                     |
| 1 start -> 2 profile -> 3 validate -> 4 session -> 5 confirm ...  |
|------------------------------------------------------------------|
| Missing evidence / risks                                           |
| - swarm-controller control queue has one retained control signal   |
+------------------------------------------------------------------+
```

**Rules:**

- No writes.
- No direct runtime calls from the widget.
- No shell, Docker logs, or direct Loki.
- All values come from the `evidence_summary` result.
- JSON output remains canonical for non-App clients.
- The report must distinguish full proof, partial proof, and not-applicable
  claims instead of implying evidence that was not collected.

---

## Phase 2 App — Workflow Evidence Report

**Trigger:** User asks "show the workflow evidence", "show the chat report",
"what questions/gates/evidence are left?", or "prove this generated bundle is
ready".

**Tool:** `workflow_evidence_render`

**Scope:** read-only. The tool returns the canonical workflow status/evidence
model and attaches `ui://pockethive/workflow-evidence-v1.html`. The widget only
renders that model.

**UI:**

```
+------------------------------------------------------------------+
| Workflow Evidence: wf-123                              VALIDATED  |
|------------------------------------------------------------------|
| Bundle       google-smoke              Profile   Novice Builder  |
| Questions    0                         Evidence gaps  runtime    |
|------------------------------------------------------------------|
| Claim matrix                                                     |
| bundle.exists        SATISFIED       generated bundle exists      |
| validation.passed    SATISFIED       local bundle check passed    |
| runtime.deployed     NOT-RUN         deploy not requested         |
|------------------------------------------------------------------|
| Role review                                                     |
| Three Amigos Review: complete                                    |
| architect: pass | pockethive-sme: pass | tester: pass            |
|------------------------------------------------------------------|
| Lifecycle operations                                             |
| op-deploy-... deploy succeeded complete                          |
|   wait-ready: 2 attempts | start: WORKFLOW_DEPLOY_STARTED         |
|------------------------------------------------------------------|
| Remaining questions / evidence gaps                              |
| - runtime.verified: run workflow_verify or mark not-run           |
+------------------------------------------------------------------+
```

**Rules:**

- No writes.
- No direct runtime calls from the widget.
- No question answering from the widget.
- No shell, Docker logs, or direct service-port checks.
- All values come from the `workflow_evidence_render` result.
- `workflow_status` remains the data/status tool; `workflow_evidence_render`
  is the render tool that attaches the MCP App resource.

---

## Future App 1 — Swarm Dashboard

**Trigger:** User asks "show me swarms", "what's running", "swarm status"

**Tool:** `swarm.list` (existing, wrapped with App UI)

**UI:**

```
+--------------------------------------------------+
| 🐝 PocketHive Swarms                    [↺]      |
| Environment: local  http://localhost:8088         |
|--------------------------------------------------|
| ● <swarm-a>    RUNNING   N bees   tps: 10        |
|   [■ Stop]  [📊 Queues]  [📓 Journal]            |
|                                                  |
| ◐ <swarm-b>    READY     N bees                  |
|   [▶ Start]  [📊 Queues]  [📓 Journal]           |
|                                                  |
| ○ <swarm-c>    STOPPED   N bees                  |
|   [▶ Start]  [✕ Remove → Confirm? [Yes] [No]]   |
|                                                  |
| [+ Create swarm]                                 |
+--------------------------------------------------+
```

Remove uses a two-click inline confirm pattern — `confirm()` is blocked
in sandboxed iframes. First click shows `[Confirm? [Yes] [No]]` inline.
Auto-cancels after 5s if not confirmed.

**Server registration:**

```javascript
registerApp('swarm-dashboard', 'swarm.list',
  'List all swarms with live status. Returns an interactive dashboard.',
  {},
  async () => {
    const swarms = await httpJson('/api/swarms');
    return { content: [{ type: 'text', text: JSON.stringify(swarms) }] };
  }
);
```

**UI logic (src/mcp-app.ts):**

```typescript
import { App } from '@modelcontextprotocol/ext-apps';

const app = new App({ name: 'Swarm Dashboard', version: '1.0.0' });
app.connect();

let pendingRemove: string | null = null;
let pendingRemoveTimer: ReturnType<typeof setTimeout> | null = null;

app.ontoolresult = (result) => {
  const swarms = JSON.parse(result.content[0].text);
  renderSwarms(swarms);
  checkConnectivity();
};

async function startSwarm(swarmId: string) {
  setSwarmBusy(swarmId, true);
  await app.callServerTool({ name: 'swarm.start', arguments: { swarmId } });
  await refresh();
}

async function stopSwarm(swarmId: string) {
  setSwarmBusy(swarmId, true);
  await app.callServerTool({ name: 'swarm.stop', arguments: { swarmId } });
  await refresh();
}

// Two-click inline confirm — confirm() is blocked in sandboxed iframes
function initiateRemove(swarmId: string) {
  if (pendingRemove === swarmId) {
    // Second click — confirmed
    if (pendingRemoveTimer) clearTimeout(pendingRemoveTimer);
    pendingRemove = null;
    doRemove(swarmId);
  } else {
    // First click — show inline confirm state
    pendingRemove = swarmId;
    renderSwarmRemoveConfirm(swarmId);
    pendingRemoveTimer = setTimeout(() => {
      pendingRemove = null;
      renderSwarms(lastSwarms); // revert UI
    }, 5000);
  }
}

async function doRemove(swarmId: string) {
  setSwarmBusy(swarmId, true);
  await app.callServerTool({ name: 'swarm.remove', arguments: { swarmId } });
  await refresh();
}

async function refresh() {
  const result = await app.callServerTool({ name: 'swarm.list', arguments: {} });
  renderSwarms(JSON.parse(result.content[0].text));
}

async function checkConnectivity() {
  try {
    await app.callServerTool({ name: 'health.check', arguments: {} });
    hideOfflineBanner();
  } catch {
    showOfflineBanner('PocketHive stack is unreachable. Check your environment.');
  }
}
```

---

## Future App 2 — Bundle Explorer

**Trigger:** User asks "show me bundles", "list scenarios", "what bundles do I have"

**Tool:** `bundle.list` (existing, wrapped)

**UI:**

```
+--------------------------------------------------+
| 📁 Bundles                    [+ New] [↺]        |
| /path/to/bundles                                 |
|--------------------------------------------------|
| ✓ bundle-a    CSV · TCP · 4 bees   DEPLOYED      |
|   Validated 2h ago                               |
|   [Validate]  [Deploy]  [▶ Create swarm]         |
|                                                  |
| ⚠ bundle-b    CSV · HTTP           NOT VALIDATED |
|   [Validate]  [Deploy ⚠]                        |
|                                                  |
| ✕ bundle-c    ISO8583 · TCP        FAILED        |
|   FAIL: Unrecognized field "protocol"            |
|   [Validate]  [View error ▾]                     |
+--------------------------------------------------+
```

Clicking **Deploy ⚠** on an unvalidated bundle shows an inline warning
before calling `scenario.deploy`. Clicking **Create swarm** opens the
Create Swarm Form App inline. Clicking **+ New** triggers the bundle
creation wizard flow defined in
[BUNDLE-WIZARD.md](../../docs/inProgress/pockethive-plugin/BUNDLE-WIZARD.md)
— the agent follows the risk-ordered decision tree to scaffold a new bundle
via MCP session tools.

---

## Future App 3 — Queue Monitor

**Trigger:** User asks "show queue depths", "is there a backlog", "monitor queues for <swarm>"

**Tool:** `debug.queues` (existing, wrapped)

**UI:**

```
+--------------------------------------------------+
| 📊 Queue Monitor                        [■ Stop] |
| Swarm: <swarm-id>   Auto-refresh: 3s            |
|--------------------------------------------------|
| QUEUE              DEPTH  CONSUMERS  HEALTH      |
| ph.swarm.build         0          1    ●         |
| ph.swarm.proc          2          1    ●         |
| ph.swarm.post          0          1    ●         |
|                                                  |
| [sparkline — last 60s, one line per queue]       |
+--------------------------------------------------+
```

**Live updates:** The UI calls `debug.queues` every 3s via
`app.callServerTool()`. No server push needed — the bidirectional
call pattern handles it.

```typescript
// Auto-refresh loop
async function startAutoRefresh(swarmId: string) {
  while (autoRefreshEnabled) {
    const result = await app.callServerTool({
      name: 'debug.queues',
      arguments: { swarmId },
    });
    renderQueues(JSON.parse(result.content[0].text));
    await sleep(3000);
  }
}
```

---

## Future App 4 — Health Dashboard

**Trigger:** User asks "is the stack healthy", "check health", "what's the status"

**Tool:** `health.check` (existing, wrapped)

**UI:**

```
+--------------------------------------------------+
| 🐝 PocketHive Health                    [↺]      |
| local  http://localhost:8088                     |
|--------------------------------------------------|
| orchestrator      ● UP                           |
| scenario-manager  ● UP                           |
| rabbitmq          ● UP                           |
| prometheus        ● UP                           |
| wiremock          ● UP                           |
| tcp-mock          ◐ DEGRADED                     |
|                                                  |
| Ref synced: 2h ago  [Refresh docs]               |
+--------------------------------------------------+
```

---

## Future App 5 — Create Swarm Form

**Trigger:** User asks "create a swarm", "run bundle-a", "start a load test"

**Tool:** `swarm.create` (new App-wrapped variant)

**UI:**

```
+--------------------------------------------------+
| ➕ Create Swarm                                   |
|--------------------------------------------------|
| Template    [Select scenario...        ▾]        |
| Swarm ID    [auto-generated             ]        |
| Environment [local                     ▾]        |
| SUT         [(none)                    ▾]        |
| Variables   [(none)                    ▾]        |
|                                                  |
| ┌──────────────────────────────────────────────┐ |
| │ ⚙ Creating...  [████████░░░░] Waiting ready  │ |
| └──────────────────────────────────────────────┘ |
|                                                  |
| [Cancel]                    [Create + Start]     |
+--------------------------------------------------+
```

**Multi-step workflow in the UI:**

```typescript
async function createAndStart() {
  setProgress('Creating swarm...');
  await app.callServerTool({ name: 'swarm.create', arguments: {
    swarmId, templateId, sutId, variablesProfileId
  }});

  setProgress('Waiting for workers to be ready...');
  const ready = await app.callServerTool({ name: 'swarm.wait-ready', arguments: {
    swarmId, timeoutSec: 60
  }});

  if (JSON.parse(ready.content[0].text).ready) {
    setProgress('Starting...');
    await app.callServerTool({ name: 'swarm.start', arguments: { swarmId } });
    setSuccess(`Swarm '${swarmId}' is running.`);
  } else {
    setWarning('Swarm created but not yet ready. Start it manually.');
  }
}
```

---

## Future App 6 — Journal Viewer

**Trigger:** User asks "show journal for <swarm>", "what happened", "any errors"

**Tool:** `debug.journal` (existing, wrapped)

**UI:**

```
+--------------------------------------------------+
| 📓 Journal — <swarm-id>              [↺] [More]  |
|--------------------------------------------------|
| ⚠ 14:32:01  template-invalid  generator          |
|   "Unrecognized field protocol"                  |
|   [▾ Show detail]                                |
|                                                  |
| ✓ 14:32:05  swarm-start                          |
| ✓ 14:32:04  config-update       generator        |
| ✓ 14:32:03  swarm-template                       |
| ✓ 14:32:00  swarm-create                         |
|                                                  |
| [Load more]                                      |
+--------------------------------------------------+
```

Clicking **Show detail** expands the raw JSON payload inline.
Clicking **Load more** calls `debug.journal` with an offset.

---

## Future App 7 — Debug Tap Viewer

**Trigger:** User asks "tap the processor output", "show me what's flowing through"

**Tool:** `debug.tap` (existing, wrapped)

**UI:**

```
+--------------------------------------------------+
| 🔍 Debug Tap — <swarm> / <role> OUT              |
| [████████████████░░░░] 87s  [Extend]             |
|--------------------------------------------------|
| SAMPLES                    | SELECTED SAMPLE     |
| [14:32:01] WorkItem #1     | Headers:            |
| [14:32:02] WorkItem #2 *   |   x-ph-call-id: ... |
| [14:32:03] WorkItem #3     |   status: 200       |
|                            | Steps:              |
| [Refresh] [Clear]          |   [▾ 0] payload     |
|                            |   [▾ 1] response    |
+--------------------------------------------------+
```

TTL countdown with Extend button (calls `debug.tap.close` +
`debug.tap` to recreate). Panel close calls `debug.tap.close`.

---

## Shared design tokens

```css
/* tools/pockethive-mcp/apps/shared/tokens.css */
/* Matches ui-v2/src/styles.css exactly */
:root {
  --ph-bg:        #05070b;
  --ph-panel:     rgba(255,255,255,0.04);
  --ph-border:    rgba(255,255,255,0.12);
  --ph-muted:     rgba(255,255,255,0.65);
  --ph-accent:    #33e1ff;
  --ph-amber:     #ffc107;
  --ph-ok:        #00ff66;
  --ph-warn:      #00ccff;
  --ph-alert:     #ff0033;
  --ph-missing:   #5b6679;
  --ph-font:      Inter, system-ui, Segoe UI, Roboto, sans-serif;
  --ph-mono:      ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

body {
  margin: 0;
  background: var(--ph-bg);
  color: #fff;
  font-family: var(--ph-font);
  font-size: 13px;
}
```

---

## Capability Negotiation

The server checks whether the connecting client supports MCP Apps at
`ui/initialize`. JSON-only clients receive plain JSON tool responses.
App-capable clients receive the same canonical tool result plus a UI resource.

This means:
- Amazon Q (if it doesn't yet support MCP Apps) gets JSON text responses
- Claude, Copilot, Cursor get the interactive App UI
- Tool logic does not fork by client type

```javascript
// Capability negotiation belongs in the registration/transport layer.
// Tool handlers should stay canonical and client-neutral.
```

---

## Transport

MCP Apps require **HTTP/SSE or Streamable HTTP transport** — not stdio.
The MCP server must expose an HTTP endpoint for App-capable clients.

This aligns with the existing `PH_MCP_HTTP_PORT` env var in the server spec.
When running in HTTP mode, the server uses `StreamableHTTPServerTransport`
and serves App resources at `ui://pockethive/*`.

For IDE plugin stdio mode, MCP Apps are not available — the IDE webview
panels serve the same purpose. This is the correct split:

| Surface | Transport | Rich UI mechanism |
|---|---|---|
| AI chat (Claude, Copilot) | HTTP/SSE | MCP Apps (sandboxed iframe) |
| VS Code plugin | stdio | Webview panels (postMessage) |
| IntelliJ plugin | stdio | JCEF panels (CefMessageRouter) |

---

## Server integration

The first evidence widget is intentionally inline in `server.mjs`, so there is
no separate frontend build. The package exposes HTTP mode for App-capable
clients:

```json
"scripts": {
  "start":      "node server.mjs",
  "start:http": "PH_MCP_HTTP_PORT=3100 node server.mjs"
}
```

Phase 1.5 registers `ui://pockethive/evidence-summary-v1.html`; Phase 2 also
registers `ui://pockethive/workflow-evidence-v1.html`. Other app bundles are
not published until their phase is approved.

---

## Testing

### With basic-host (local, no Claude account needed)

```bash
# Terminal 1 — build and start MCP server in HTTP mode
cd tools/pockethive-mcp
npm run build:apps
PH_MCP_HTTP_PORT=3100 node server.mjs

# Terminal 2 — start basic-host test client
git clone https://github.com/modelcontextprotocol/ext-apps.git
cd ext-apps/examples/basic-host && npm install
SERVERS='["http://localhost:3100/mcp"]' npm start
# Open http://localhost:8080
```

### With Claude (requires paid plan + cloudflared)

```bash
# Expose local server
npx cloudflared tunnel --url http://localhost:3100

# Add the generated URL as a custom connector in Claude settings
# Then ask: "show evidence for swarm <swarm-id>"
```

### With VS Code GitHub Copilot

Add to `.vscode/mcp.json`:

```json
{
  "servers": {
    "pockethive-bundles": {
      "type": "http",
      "url": "http://localhost:3100/mcp",
      "disabled": false
    }
  }
}
```

---

## Cross-references applied

The following docs have been updated to reflect MCP Apps:

| Doc | Change applied |
|---|---|
| ARCHITECTURE.md | Phase 1.5 evidence widget described |
| MCP-SERVER.md | `evidence_summary` tool and Phase 1.5 App packaging described |
| AGENT-RULES.md | Phase 1.5 App constraints added |
| README.md | MCP-APPS.md listed as evidence-widget spike plus future platform |
