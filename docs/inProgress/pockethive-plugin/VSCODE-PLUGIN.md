# VS Code Extension — Spec

## Status
`IN PROGRESS`

## Overview

Enhancement of the existing `vscode-pockethive` extension (v0.0.7).
The extension already has Hive, Buzz, Journal, Scenario, and Settings
tree views plus a custom scenario editor. This spec adds:

- MCP server lifecycle management (spawn/connect/restart)
- Environment manager with multi-environment switching
- Bundles folder management with multi-root support
- Webview panels for topology, queue monitor, debug tap viewer
- Enhanced tree views with live status indicators
- Full wiring of all actions through the MCP server

## Extension activation

```typescript
// package.json activationEvents
"activationEvents": [
  "onStartupFinished"   // activate on IDE open, not on first command
]
```

On activation:
1. Read settings — active environment + active bundles folder
2. Locate MCP server binary (`@pockethive/mcp-server` from npm or local path)
3. Spawn MCP server child process with env vars from settings
4. Connect MCP client over stdio
5. Register all tree view providers
6. Register all webview panel commands
7. Show status bar item with environment name + health dot
8. Run `health.check` — update status bar

## Directory structure

```
vscode-pockethive/
  src/
    extension.ts              <- activation, registration
    config.ts                 <- settings read/write (enhanced)
    commands.ts               <- all command handlers (enhanced)
    types.ts                  <- shared types
    format.ts                 <- formatting helpers
    output.ts                 <- output channel
    pickers.ts                <- quick-pick helpers
    preview.ts                <- JSON preview
    scenarioPreview.ts        <- scenario HTML preview
    help.ts                   <- help content
    filters.ts                <- filter state
    filterState.ts
    constants.ts
    api.ts                    <- direct HTTP (legacy, being replaced by MCP)
    mcp/
      client.ts               <- MCP client wrapper
      manager.ts              <- server lifecycle (spawn/restart/connect)
      tools.ts                <- typed wrappers for all MCP tool calls
    providers/
      hiveProvider.ts         <- swarm tree view (enhanced)
      scenarioProvider.ts     <- bundle tree view (enhanced)
      journalProvider.ts      <- journal tree view (enhanced)
      buzzProvider.ts         <- buzz/control-plane tree view
      settingsProvider.ts     <- environment + bundles settings tree (enhanced)
      environmentProvider.ts  <- new: environment list tree view
    webviews/
      swarmDetailPanel.ts     <- swarm topology + worker detail (ui-v2)
      bundleDetailPanel.ts    <- bundle pipeline + file tree (ui-v2)
      queueMonitorPanel.ts    <- live queue depths (ui-v2)
      tapViewerPanel.ts       <- debug tap viewer (ui-v2)
      WebviewPanel.ts         <- base class for all webview panels
    editors/
      scenarioEditor.ts       <- existing custom editor (unchanged)
    fs/
      scenarioFileSystemProvider.ts  <- existing (unchanged)
  resources/
    hive.svg
    dist-plugin/              <- built ui-v2 assets (gitignored, built at package time)
  package.json
  tsconfig.json
```

## Settings schema additions

Added to `contributes.configuration` in `package.json`:

```json
"pockethive.environments": {
  "type": "array",
  "default": [],
  "markdownDescription": "Named PocketHive environments. Each entry has `name`, `baseUrl`, and optional overrides.",
  "items": {
    "type": "object",
    "required": ["name", "baseUrl"],
    "properties": {
      "name":        { "type": "string" },
      "baseUrl":     { "type": "string" },
      "rabbitUser":  { "type": "string", "default": "guest" },
      "tcpMockUrl":  { "type": "string" },
      "wiremockUrl": { "type": "string" }
    }
  }
},
"pockethive.activeEnvironment": {
  "type": "string",
  "default": "",
  "description": "Name of the active environment."
},
"pockethive.bundlesFolders": {
  "type": "array",
  "default": [],
  "description": "Paths to scenario bundle folders.",
  "items": { "type": "string" }
},
"pockethive.activeBundlesFolder": {
  "type": "string",
  "default": "",
  "description": "Active bundles folder path."
},
"pockethive.pockethiveRoot": {
  "type": "string",
  "default": "",
  "description": "Path to PocketHive repo checkout (for offline bundle validation)."
},
"pockethive.mcpTransport": {
  "type": "string",
  "enum": ["stdio", "http"],
  "default": "stdio",
  "description": "MCP server transport. stdio = spawn locally. http = connect to running server."
},
"pockethive.mcpHttpUrl": {
  "type": "string",
  "default": "",
  "description": "MCP server URL when mcpTransport is http."
},
"pockethive.mcpServerPath": {
  "type": "string",
  "default": "",
  "description": "Override path to pockethive-mcp server.mjs. Leave blank to use installed npm package."
}
```

Secrets (auth tokens, passwords) are stored via `context.secrets`, never
in `settings.json`.

## MCP manager (src/mcp/manager.ts)

Manages the MCP server child process lifecycle.

```typescript
class McpServerManager {
  private child: ChildProcess | null = null;
  private client: McpClient | null = null;
  private restartAttempts = 0;

  async start(env: NodeJS.ProcessEnv): Promise<void>
  async restart(): Promise<void>   // kill + start with current settings
  async stop(): Promise<void>
  isRunning(): boolean
  getClient(): McpClient | null

  // Events
  onStatusChange: Event<'starting' | 'running' | 'stopped' | 'error'>
}
```

Auto-restart on crash: up to 3 attempts with 2s/4s/8s backoff.
After 3 failures, shows error notification with "Restart" action button.

## Commands (additions to existing)

```typescript
// Environment management
'pockethive.addEnvironment'          // opens Add Environment quick input flow
'pockethive.editEnvironment'         // opens Edit Environment for selected
'pockethive.removeEnvironment'       // removes selected environment
'pockethive.setActiveEnvironment'    // switches active environment + restarts MCP
'pockethive.setEnvironmentToken'     // stores auth token in context.secrets

// Bundles folder management
'pockethive.addBundlesFolder'        // file picker -> adds to bundlesFolders
'pockethive.removeBundlesFolder'     // removes selected folder
'pockethive.setActiveBundlesFolder'  // switches active folder + restarts MCP

// Bundle actions
'pockethive.validateBundle'          // runs bundle.validate via MCP
'pockethive.deployBundle'            // runs scenario.deploy via MCP
'pockethive.newBundle'               // scaffold wizard -> creates bundle files

// Webview panels
'pockethive.openSwarmDetail'         // opens SwarmDetailPanel for swarm
'pockethive.openBundleDetail'        // opens BundleDetailPanel for bundle
'pockethive.openQueueMonitor'        // opens QueueMonitorPanel for swarm
'pockethive.openTapViewer'           // opens TapViewerPanel for tap

// MCP server
'pockethive.restartMcpServer'        // manual restart
'pockethive.showMcpLogs'             // shows MCP server output channel
```

## Tree views

### Hive view (enhanced)

```
ENVIRONMENT                    [Switch ▾]
  [●green] local  CONNECTED  (active)
  ─────────────────────────────────────────────
  SWARMS on local                [+ New]
  [●green] <swarm-id-a>    RUNNING   N bees
    [▶ Start][■ Stop]  [View][Journal][Queues]
    context menu: Remove... | Clone
  [◐cyan]  <swarm-id-b>    READY     N bees
  [○grey]  <swarm-id-c>    STOPPED   N bees
  ─────────────────────────────────────────────
  [Manage environments...]

Empty state (no environments):
  🐝  No environment configured
       + Add environment
```

Remove is context menu only — never inline. Confirm modal required.

Tree item context values:
- `swarmRunning` — inline: Stop, View, Journal, Queues; menu: Remove
- `swarmReady`   — inline: Start, View, Journal, Queues; menu: Remove
- `swarmStopped` — inline: Start, View, Journal, Queues; menu: Remove

### Scenario view (enhanced)

```
BUNDLES FOLDER
  [folder icon] <bundles-repo-name>  ACTIVE
  [folder icon] <other-bundles-repo>
  + Add folder

BUNDLES                              [+ New] [Validate all] [Deploy all]
  [check green]  <bundle-a>    CSV · TCP  (Validated 2h ago)
    context menu: Validate | Deploy | Open | Create Swarm
  [warn amber]   <bundle-b>    CSV · HTTP  (not validated)
  [cross red]    <bundle-c>    ISO8583 · TCP  (validation failed)

Empty state (no bundles folder):
  📁  No bundles folder configured
       + Add bundles folder
```

Deploy on amber/red shows warning modal. Validate all / Deploy all
show confirmation + cancellable progress notification.

Validation status icons:
- `$(check)` green — last validation passed (tooltip: "Validated Xh ago")
- `$(warning)` amber — never validated, stale (>24h), or files changed
- `$(error)` red — last validation failed

### Settings view (enhanced)

```
ENVIRONMENTS                              [+ Add]
  [check] local  ACTIVE
    [Use] [Edit] [Set token] [Delete]
  <remote-env>
    [Use] [Edit] [Set token] [Delete]

BUNDLES FOLDERS                           [+ Add]
  [check] <bundles-repo-name>  ACTIVE
    [Use] [Open] [Delete]
  <other-bundles-repo>
    [Use] [Open] [Delete]

[Advanced ▸]                               <- collapsed by default
  PocketHive Root  [Browse]
  MCP Server  [●green] Running  [Restart][Logs]
```

## Webview panels

### SwarmDetailPanel

Opens as a tab in the editor area. Renders `ui-v2/SwarmViewPage`.

```typescript
class SwarmDetailPanel extends WebviewPanel {
  static open(swarmId: string, context: vscode.ExtensionContext): void

  // Sends config to webview on open
  private sendConfig(): void {
    this.panel.webview.postMessage({
      type: 'config',
      payload: { baseUrl, swarmId, stompUrl }
    });
  }

  // Proxies API calls from webview to PocketHive stack
  private handleMessage(msg: WebviewMessage): void {
    if (msg.type === 'api') {
      const result = await fetch(baseUrl + msg.path, { method: msg.method, body: msg.body });
      this.panel.webview.postMessage({ type: 'api-response', id: msg.id, payload: result });
    }
    if (msg.type === 'mcp') {
      const result = await mcpClient.callTool(msg.tool, msg.args);
      this.panel.webview.postMessage({ type: 'mcp-response', id: msg.id, payload: result });
    }
  }
}
```

### BundleDetailPanel

Opens as a tab. Renders static pipeline diagram from `scenario.yaml` +
file tree + YAML preview.

### QueueMonitorPanel

Opens as a tab. Polls `debug.queues` every 3s. Renders queue depth table
and sparkline chart.

### TapViewerPanel

Opens as a tab. Renders `ui-v2/DebugTapViewerPage`. Receives tapId from
the command that opens it.

## Status bar item

```
[🐝 PocketHive: local  ●]
```

- Clicking opens the Settings tree view
- `●` is coloured: green=connected, amber=degraded, red=error, grey=not started
- Shows environment name
- Tooltip shows full baseUrl + MCP server status

## Configuration change listener

```typescript
vscode.workspace.onDidChangeConfiguration(event => {
  if (event.affectsConfiguration('pockethive.activeEnvironment') ||
      event.affectsConfiguration('pockethive.activeBundlesFolder') ||
      event.affectsConfiguration('pockethive.pockethiveRoot')) {
    mcpManager.restart();
    refreshAllProviders();
    updateStatusBar();
  }
  if (event.affectsConfiguration('pockethive.environments') ||
      event.affectsConfiguration('pockethive.bundlesFolders')) {
    refreshAllProviders();
  }
});
```

## Build and packaging

```json
// package.json scripts
"scripts": {
  "build":           "tsc -p ./",
  "build:webviews":  "cd ../ui-v2 && PLUGIN_MODE=true vite build --outDir ../vscode-pockethive/resources/dist-plugin",
  "watch":           "tsc -watch -p ./",
  "package":         "npm run build:webviews && vsce package",
  "vscode:prepublish": "npm run build:webviews && npm run build"
}
```

The `dist-plugin/` directory is built from `ui-v2` at package time and
embedded in the `.vsix`. It is gitignored in the source tree.

## Migration from existing extension

The existing extension (v0.0.7) calls PocketHive APIs directly in
`commands.ts`. The migration path:

1. Add MCP manager alongside existing direct API calls
2. New commands use MCP tools exclusively
3. Existing commands are progressively migrated to MCP
4. Direct API calls in `api.ts` are kept as fallback during transition
5. v1.0.0 release removes all direct API calls — everything goes through MCP

This allows incremental delivery without breaking existing functionality.
