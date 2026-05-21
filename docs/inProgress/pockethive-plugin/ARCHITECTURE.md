# PocketHive IDE Plugin — Architecture

## Status
`IN PROGRESS`

## Three-layer model

```
+------------------------------------------------------------------+
|  UI Surfaces                                                     |
|                                                                  |
|  vscode-pockethive (.vsix)      intellij-pockethive (.zip)      |
|  TypeScript adapter             Kotlin adapter                   |
|  - Tree view providers          - Tool windows (Swing)          |
|  - Webview panel managers       - JCEF webview wrappers         |
|  - MCP server spawn/connect     - MCP server spawn/connect      |
|  - VS Code settings API         - PersistentStateComponent      |
|  - context.secrets (keychain)   - PasswordSafe (keychain)       |
|  Transport: stdio               Transport: stdio                 |
+------------------+-------------------------------+---------------+
                   |                               |
+------------------v-------------------------------v---------------+
|  AI Chat Clients (Claude, Copilot, Amazon Q, Cursor, Goose)     |
|  Current: JSON MCP tools over HTTP/SSE                          |
|  Phase 1.5: evidence.summary MCP App where supported            |
+------------------+-------------------------------+---------------+
                   |                               |
                   +---------------+---------------+
                                   |
+----------------------------------v-------------------------------+
|  tools/pockethive-mcp  (Node.js MCP server)                     |
|                                                                  |
|  Current tools:  reg() - JSON text responses                    |
|  Phase 1.5 App:  evidence.summary + evidence-summary resource   |
|                                                                  |
|  Published as: @pockethive/mcp-server (npm)                     |
+----------------------------------+-------------------------------+
                                   |
                          HTTP REST + STOMP
                                   |
+----------------------------------v-------------------------------+
|  PocketHive Stack                                               |
|  Orchestrator · Scenario Manager · RabbitMQ · Prometheus        |
|  WireMock · TCP Mock · ClickHouse                               |
|  Future option: PocketHive-provided logs backed by Loki          |
|  (local Docker Compose or remote NFT server)                    |
+------------------------------------------------------------------+
```

## Three UI surfaces

The plugin architecture provides three distinct UI surfaces:

| Surface | Transport | Rich UI mechanism | When to use |
|---|---|---|---|
| VS Code plugin | stdio | Webview panels (postMessage) | IDE-integrated views |
| IntelliJ plugin | stdio | JCEF panels (CefMessageRouter) | IDE-integrated views |
| AI chat (Claude, Copilot, etc.) | HTTP/SSE | JSON MCP tools | Conversational diagnostics |
| AI chat with MCP Apps support | HTTP/SSE | Evidence summary MCP App | Phase 1.5 evidence visualisation |
| AI chat with broader MCP Apps | HTTP/SSE | Dashboards/forms | Future platform |

MCP Apps start with a narrow Phase 1.5 spike: a read-only evidence summary
widget for App-capable HTTP/SSE clients. In stdio mode (IDE plugins), rich UI
is provided by IDE webview panels.

Response shape is selected by explicit client capability negotiation. This is
not a fallback chain: a JSON-only client gets the JSON response form, and an
App-capable client gets the same tool result plus the declared UI resource.

## MCP transport modes

### stdio (IDE plugin mode)

The IDE plugin spawns the MCP server as a child process. Tools return
JSON text. MCP Apps are not available in stdio mode — the IDE webview
panels serve the same purpose.

### HTTP/SSE - Streamable HTTP (AI chat mode)

The MCP server runs as an HTTP server. AI chat clients (Claude, Copilot,
Cursor) connect over HTTP/SSE. Phase 1-3 return JSON MCP tool responses.
Phase 1.5 may serve `ui://pockethive/evidence-summary` for clients that
explicitly support MCP Apps.

```
IDE plugin  -->  stdio  -->  pockethive-mcp (child process)
AI chat     -->  HTTP/SSE  -->  pockethive-mcp (HTTP server)
```

The same server binary supports both modes. `PH_MCP_HTTP_PORT` env var
enables HTTP mode.

## Webview strategy

`ui-v2/` is a Vite/React app. A plugin build mode produces a self-contained
bundle that can be loaded in IDE webviews.

```
ui-v2/vite.config.ts
  PLUGIN_MODE=true build
    -> dist-plugin/
         index.html
         assets/
           index-[hash].js
           index-[hash].css
```

The plugin embeds `dist-plugin/` as static assets. Webview panels load
`index.html` and communicate with the extension host via `postMessage`.
The extension host proxies REST calls. STOMP uses the explicit connection
strategy in `BUILD-READY-GAPS.md`.

IntelliJ JCEF renders the same `index.html`. The Kotlin adapter provides
the same `postMessage` bridge via `CefMessageRouter`.

### postMessage bridge contract

```
// Plugin -> Webview
{ type: 'config', payload: { baseUrl, swarmId, ... } }
{ type: 'data', key: 'swarms', payload: [...] }
{ type: 'data', key: 'snapshot', payload: {...} }

// Webview -> Plugin
{ type: 'api', method: 'GET', path: '/orchestrator/api/swarms' }
{ type: 'api', method: 'POST', path: '/orchestrator/api/swarms/x/start', body: {...} }
{ type: 'mcp', tool: 'debug.tap', args: { swarmId, role } }
{ type: 'navigate', view: 'journal', swarmId: 'x' }
```

## Config delivery to MCP server

The MCP server reads all config from `process.env`. The IDE plugin
constructs the env object from its own settings store and passes it
at spawn time. No .env file is read by the server when running inside
a plugin.

```typescript
// VS Code — constructing env for spawn
function buildMcpEnv(settings: PocketHiveSettings): NodeJS.ProcessEnv {
  const env = settings.activeEnvironment();
  const rabbitPass = await context.secrets.get(`ph.env.${env.name}.rabbitPass`);
  return {
    ...process.env,
    POCKETHIVE_BASE_URL:   env.baseUrl,
    POCKETHIVE_ROOT:       settings.pockethiveRoot,
    BUNDLES_ROOT:          settings.activeBundlesFolder,
    RABBITMQ_DEFAULT_USER: env.rabbitUser ?? 'guest',
    RABBITMQ_DEFAULT_PASS: rabbitPass ?? 'guest',
    ...(env.tcpMockUrl  ? { TCP_MOCK_BASE_URL:  env.tcpMockUrl  } : {}),
    ...(env.wiremockUrl ? { WIREMOCK_BASE_URL:  env.wiremockUrl } : {}),
  };
}
```

```kotlin
// IntelliJ — constructing env for ProcessBuilder
fun buildMcpEnv(settings: PocketHiveSettings): Map<String, String> {
    val env = settings.activeEnvironment() ?: return emptyMap()
    val rabbitPass = PasswordSafe.instance.getPassword(credAttr(env.name, "rabbitPass")) ?: "guest"
    return mapOf(
        "POCKETHIVE_BASE_URL"   to env.baseUrl,
        "POCKETHIVE_ROOT"       to settings.state.pockethiveRoot,
        "BUNDLES_ROOT"          to settings.state.activeBundlesFolder,
        "RABBITMQ_DEFAULT_USER" to (env.rabbitUser.ifBlank { "guest" }),
        "RABBITMQ_DEFAULT_PASS" to rabbitPass,
    ) + (if (env.tcpMockUrl.isNotBlank())  mapOf("TCP_MOCK_BASE_URL"  to env.tcpMockUrl)  else emptyMap())
      + (if (env.wiremockUrl.isNotBlank()) mapOf("WIREMOCK_BASE_URL"  to env.wiremockUrl) else emptyMap())
}
```

## Environment switching — hot reload

When the user switches environment or active bundles folder, the plugin
kills the existing MCP server child process and respawns with new env vars.
This takes ~1 second and is transparent to the user.

```
user clicks "Use" on nft-remote environment
  -> settings.activeEnvironment = "nft-remote"
  -> mcpManager.restart()
       -> kill existing child process
       -> build new env from settings
       -> spawn new child process
       -> reconnect MCP client
       -> refresh all tree views
  -> status bar shows "PocketHive: nft-remote"
```

The MCP client uses a reconnect strategy with exponential backoff so
transient spawn delays do not surface as errors to the user.

## STOMP connection (webviews)

The topology and swarm view webviews use STOMP over WebSocket to receive
live control-plane events. In plugin mode, the webview connects directly
to the PocketHive stack's STOMP endpoint (same as the web UI).

The `baseUrl` is injected into the webview via the postMessage config
message on panel open. The webview derives the STOMP URL from `baseUrl`.

```
baseUrl: http://localhost:8088
STOMP:   ws://localhost:8088/stomp/websocket
```

## Logs And Evidence

The plugin does not read Docker/container logs and the MCP server does not run
shell log commands. Current diagnostics should use PocketHive-provided runtime
evidence: swarm status, journal, queues, debug taps, Prometheus metrics, mock
request history, and dataset checks.

If PocketHive later exposes structured logs through a product API, the MCP may
add a tool for that API. Loki is a possible backend for that future feature, but
direct Loki access is not part of the current plugin design.

## Process isolation

Each IDE window has its own MCP server child process. If the user has
multiple IDE windows open against different environments, each window
manages its own server instance independently.

## Failure modes

| Failure | Detection | Recovery |
|---|---|---|
| MCP server crashes | Child process `exit` event | Auto-restart with backoff (max 3 attempts) |
| Stack unreachable | `health.check` on activation | Show error in status bar, retry button |
| Node.js not found | spawn error | Show install prompt with link |
| Port conflict (HTTP mode) | Connection refused | Show config error, suggest stdio mode |
| Env switch fails | Spawn error | Revert to previous environment, show error |
