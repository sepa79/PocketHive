# Agent Rules — PocketHive IDE Plugin

## Status
`IN PROGRESS`

## Purpose

Rules for AI agents (Amazon Q, Copilot, Cursor, Codex) tasked with
implementing the PocketHive IDE plugin. Read this before writing any code.

---

## 1. Authoritative sources — always read these first

Before implementing anything, read the relevant source files. Do not
infer behaviour from docs alone — the Java/Kotlin/TypeScript source is
the ground truth.

| What you need to know | Where to read |
|---|---|
| MCP server tools and config | `tools/pockethive-mcp/server.mjs` |
| MCP server config fields | `HttpSequenceWorkerConfig.java`, `PostProcessorWorkerConfig.java`, `ProcessorWorkerConfig.java` |
| Scenario contract | `docs/scenarios/SCENARIO_CONTRACT.md` |
| Worker capabilities | `scenario-manager-service/capabilities/*.latest.yaml` |
| Postprocessor metrics | `postprocessor-service/src/.../PostProcessorWorkerImpl.java` |
| ClickHouse schema | `clickhouse/init/02-ph-tx-outcome-v2.sql` |
| Existing VS Code extension | `vscode-pockethive/src/extension.ts`, `config.ts`, `commands.ts` |
| ui-v2 components to reuse | `ui-v2/src/pages/`, `ui-v2/src/lib/`, `ui-v2/src/styles.css` |
| Architecture | `docs/ARCHITECTURE.md` |
| Plugin spec | `docs/inProgress/pockethive-plugin/` (this folder) |

---

## 2. What NOT to do

- **Do not invent config fields** that do not exist in the Java source records.
  `PostProcessorWorkerConfig` has exactly three fields. `resultRules`,
  `assertions`, `successRegex` do not exist — do not add them to scenario YAML.

- **Do not use `.env` files** for plugin config. All config goes through
  `vscode.workspace.getConfiguration('pockethive')` (VS Code) or
  `PersistentStateComponent` (IntelliJ). Secrets go through `context.secrets`
  or `PasswordSafe`. See `CONFIG.md`.

- **Do not call PocketHive APIs directly** from new plugin code. All
  operations go through MCP tools. The existing direct API calls in
  `commands.ts` are legacy and being migrated.

- **Do not modify PocketHive runtime services** (orchestrator, scenario-manager,
  processor, postprocessor, etc.). The plugin is a consumer only.

- **Do not add new PocketHive API endpoints** to support the plugin. Use
  what exists.

- **Do not duplicate ui-v2 React components**. Webview panels embed the
  built `ui-v2` output. Do not rewrite topology views, journal pages, or
  tap viewers from scratch.

- **Do not hardcode environment URLs**. All URLs come from the active
  environment in plugin settings.

- **Do not store secrets in `settings.json` or `pockethive.xml`**. Auth
  tokens and passwords go to OS keychain only.

---

## 3. MCP server rules

### Config delivery
The MCP server receives all config as environment variables injected at
spawn time. It does not read `.env` files when running inside a plugin.
The plugin constructs the env map from its settings store before spawning.

### Adding new tools
New tools follow the existing `reg(name, desc, schema, handler)` pattern
in `server.mjs`. Every tool must:
- Have a clear, single-sentence description
- Use `z.string()`, `z.number()`, `z.boolean()`, `z.enum()` for inputs
- Return a plain JSON-serialisable object
- Handle errors by throwing — the `reg` wrapper catches and formats them

Tools that should render interactive UIs in AI chat use
`registerAppTool()` instead of `reg()`. See `MCP-APPS.md` for the
registration pattern. App-capable tools fall back to plain JSON text
when the connecting client does not support MCP Apps.

### context.* tools
`context.get`, `context.set-bundles-root`, `context.list-bundles-roots`
update the in-memory `BUNDLES_ROOT` variable. They do not restart the
server. The plugin must also update its settings store so the change
persists across restarts.

### env.* tools
`env.switch` updates the active environment name in the plugin settings
and signals the plugin to restart the MCP server with new env vars.
The server itself cannot restart itself — it signals the plugin via a
special response field `{ requiresRestart: true }` which the plugin
detects and acts on.

---

## 4. VS Code extension rules

### Activation
Use `onStartupFinished` — activate on IDE open, not on first command.
This ensures the MCP server is running before the user needs it.

### MCP client
Use the `@modelcontextprotocol/sdk` client package. Connect over stdio
pipes to the spawned child process. Reconnect automatically on crash
with exponential backoff (2s, 4s, 8s, max 3 attempts).

### Tree view providers
All providers extend `vscode.TreeDataProvider<T>`. Refresh by firing
`EventEmitter`. Do not poll on a timer inside providers — use the MCP
server's data and refresh on explicit user action or on swarm state
change events.

### Webview panels
- One panel class per view (SwarmDetailPanel, BundleDetailPanel, etc.)
- Extend a shared `WebviewPanel` base class
- Load `dist-plugin/index.html` as the webview content
- Send initial config via `postMessage` after `onDidReceiveMessage` is
  registered
- Proxy all API calls — webviews cannot call PocketHive directly
- Dispose the panel on close and clean up any MCP resources (e.g. debug taps)

### Settings
Read with `vscode.workspace.getConfiguration('pockethive').get<T>(key)`.
Write with `config.update(key, value, vscode.ConfigurationTarget.Global)`.
Never write to workspace-scoped settings for environments or bundles folders
— these are user-level, not project-level.

### Commands
All new commands use MCP tools via `mcpClient.callTool(name, args)`.
Wrap every command in try/catch and show errors via
`vscode.window.showErrorMessage`.

---

## 5. IntelliJ plugin rules

### Settings persistence
Use `PersistentStateComponent<State>` with `@State(storages = [Storage("pockethive.xml")])`.
The `State` data class must use `var` fields (not `val`) for Jackson
deserialisation to work correctly.

### Secrets
Use `PasswordSafe.instance` with `CredentialAttributes` keyed as
`"PocketHive/<envName>/<secretName>"`. Never store tokens in the `State`
data class.

### MCP server spawn
Use `ProcessBuilder`. Always call `pb.environment().putAll(System.getenv())`
before adding PocketHive-specific vars so the Node.js process inherits
the system PATH (needed to find `node`).

### JCEF webviews
Use `JBCefBrowser`. Register a `CefMessageRouter` for the postMessage
bridge. Load `dist-plugin/index.html` from the plugin's resource directory.
The resource path is resolved via `PluginManagerCore.getPlugin(pluginId)
?.pluginPath?.resolve("dist-plugin/index.html")`.

### Tool windows
Use `SimpleToolWindowPanel` as the container. Register via `plugin.xml`
`<toolWindow>` extension point. Do not use deprecated `ToolWindowFactory`
APIs — use the `factoryClass` attribute pointing to a class implementing
`ToolWindowFactory`.

### Actions
Register in `plugin.xml` `<actions>` block. Use `AnAction` subclasses.
Call MCP tools via `McpClient` from a background thread
(`ApplicationManager.getApplication().executeOnPooledThread { ... }`).
Update UI on the EDT via `ApplicationManager.getApplication().invokeLater { ... }`.

---

## 6. ui-v2 plugin build mode

Add to `ui-v2/vite.config.ts`:

```typescript
const pluginMode = process.env.PLUGIN_MODE === 'true';

export default defineConfig({
  base: pluginMode ? './' : '/',   // relative paths for webview loading
  define: {
    __PLUGIN_MODE__: pluginMode,
  },
  build: pluginMode ? {
    outDir: '../vscode-pockethive/resources/dist-plugin',
    emptyOutDir: true,
  } : undefined,
});
```

In the React app, detect plugin mode and use `postMessage` instead of
direct `fetch` for API calls:

```typescript
const isPluginMode = typeof __PLUGIN_MODE__ !== 'undefined' && __PLUGIN_MODE__;

async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  if (isPluginMode) {
    return pluginApiFetch(path, init);  // postMessage bridge
  }
  return fetch(path, init);
}
```

---

## 7. Workflow for implementing a new feature

1. **Read the spec** — check `VIEWS.md`, `WIREFRAMES.md`, `ARCHITECTURE.md`
2. **Read the source** — find the relevant Java/TypeScript files listed in §1
3. **Check what MCP tools exist** — run `bundle.list` or read `server.mjs`
   to confirm the tool you need exists before writing code that calls it
4. **Implement MCP tool first** (if needed) — add to `server.mjs`, test
   with `integration-test.mjs`
5. **Implement VS Code side** — provider or webview panel
6. **Implement IntelliJ side** — tool window or JCEF panel reusing same assets
7. **Update `README.md`** in this folder if the spec changes

---

## 8. Testing approach

### MCP server
Run `node tools/pockethive-mcp/integration-test.mjs` against a live local
stack. Tests call tools directly and assert on responses.

### VS Code extension
Use `@vscode/test-electron` for integration tests. Unit test providers
and config helpers with Jest/Vitest — mock `vscode` module.

### IntelliJ plugin
Use `IntelliJPlatformPlugin` Gradle test tasks. Unit test Kotlin classes
with JUnit 5. Integration tests use the `intellij-platform-gradle-plugin`
test framework.

### Webviews
Test React components with Vitest + Testing Library. The existing
`ui-v2` test setup (`vitest.config.ts`) applies — add plugin-mode
specific tests alongside existing ones.

---

## 9. Phasing — what to build in order

### Phase 1 — MCP migration (do this first)

1. Copy `tools/mcp-server/` to `tools/pockethive-mcp/`
2. Replace `REPO_ROOT` with `BUNDLES_ROOT` throughout `server.mjs`
3. Remove `.env` file loading from server startup
4. Add `--env-file` CLI flag for standalone use
5. Add `context.get`, `context.set-bundles-root`, `context.list-bundles-roots`
6. Enhance `env.list`, `env.switch`, add `env.current`, `env.add`, `env.remove`
7. Add HTTP/SSE transport (guarded by `PH_MCP_HTTP_PORT` env var)
8. Update `package.json` — name `@pockethive/mcp-server`, add `bin` entry
9. Add `Dockerfile` for standalone deployment
10. Update bundles repo `mcp.json` to reference npm package

### Phase 2 — VS Code enhancement

1. Add settings schema additions to `package.json` (environments, bundlesFolders, etc.)
2. Implement `src/mcp/manager.ts` — spawn, restart, connect
3. Implement `src/mcp/client.ts` — typed tool call wrappers
4. Enhance `src/config.ts` — read/write new settings, secrets via `context.secrets`
5. Enhance `src/providers/settingsProvider.ts` — environments + bundles + MCP status
6. Enhance `src/providers/hiveProvider.ts` — environments section + hal-eye status
7. Enhance `src/providers/scenarioProvider.ts` — bundles folder section + validation status
8. Add `src/webviews/WebviewPanel.ts` — base class
9. Add `src/webviews/swarmDetailPanel.ts`
10. Add `src/webviews/bundleDetailPanel.ts`
11. Add `src/webviews/queueMonitorPanel.ts`
12. Add `src/webviews/tapViewerPanel.ts`
13. Add status bar item
14. Wire all new commands in `extension.ts`
15. Add `build:webviews` npm script

### Phase 3 — IntelliJ plugin

1. Scaffold Gradle project with `intellij-platform-gradle-plugin`
2. Implement `PocketHiveSettings` — `PersistentStateComponent`
3. Implement `PocketHiveCredentials` — `PasswordSafe` wrappers
4. Implement `McpServerManager` — `ProcessBuilder` spawn/restart
5. Implement `McpClient` — JSON-RPC over stdio
6. Implement `PocketHiveToolWindowFactory` + four tab panels
7. Implement `PocketHiveConfigurable` — settings page
8. Implement JCEF webview panels (reuse Phase 2 `dist-plugin/`)
9. Implement `PocketHiveStatusBarWidget`
10. Register all actions in `plugin.xml`

### Phase 4 — Authoring session MCP

Implement the session-based scenario authoring tools from
`docs/concepts/pockethive-scenario-builder-mcp-plugin-spec.md`:
- `session.start`, `session.close`, `session.discard`
- `create_scenario`, `create_rest_pipeline`, `add_bee`, `connect_bees`
- `set_bee_config`, `add_plan_stage`, `add_http_template`
- `validate_session`, `preview_diff`, `generate_bundle`, `export_bundle`
- `get_action_log`, `get_validation_log`, `submit_ai_feedback_report`

The AI chat conversation strategy for bundle creation is defined in
[BUNDLE-WIZARD.md](./BUNDLE-WIZARD.md). It specifies the risk-ordered
decision tree, adaptive skip logic, and conversation heuristics that
agents follow when calling the session tools above.

---

## 10. Key constraints summary

| Constraint | Rule |
|---|---|
| Config storage | IDE settings API only — no .env files |
| Secrets | OS keychain only — never settings.json |
| API calls | Via MCP tools only — no direct HTTP from new plugin code |
| UI components | Reuse ui-v2 via webview — no reimplementation |
| PH services | Read-only consumer — no new endpoints, no service changes |
| Scenario YAML | Only use fields that exist in Java source records |
| MCP tools | Only call tools that exist in server.mjs |
| Thread safety | IntelliJ UI updates on EDT, background work on pooled thread |
| Agent context switches | `env.switch` and `context.set-bundles-root` trigger toast notifications with Undo (see UX-FIXES.md Fix 15) |
