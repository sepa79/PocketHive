# Build-Ready Gap Resolutions

Status: resolved decisions / archived

Concrete decisions for gaps identified during pre-build review.
Implementors must read this alongside AGENT-RULES.md before writing code.

---

## Gap 6 — ValidationCache file-hash implementation

`ValidationCache.isStale()` uses a `fileHash` field. The hash is computed
as the `mtime` (last-modified timestamp in ms) of `scenario.yaml` in the
bundle directory. This is fast, requires no crypto, and correctly detects
any file save.

```typescript
// src/validationCache.ts
import * as fs from 'node:fs';
import * as path from 'node:path';

function bundleHash(bundlesRoot: string, bundleId: string): string {
  const scenarioPath = path.join(bundlesRoot, bundleId, 'scenario.yaml');
  try {
    return String(fs.statSync(scenarioPath).mtimeMs);
  } catch {
    return '0';
  }
}
```

Call `bundleHash(bundlesRoot, bundleId)` when reading and writing cache
entries. Pass `getBundlesDir()` (from MCP context) as `bundlesRoot`.

---

## Gap 7 — OperationTracker integration points

Wrap these specific commands with `operationTracker.register(label)`:

| Command | Label |
|---|---|
| `validateBundle(id)` | `Validation: ${id}` |
| `validateAll()` | `Validation: all` |
| `deployBundle(id)` | `Deploy: ${id}` |
| `deployAll()` | `Deploy: all` |
| `swarm.create` MCP call | `Create swarm: ${swarmId}` |
| `swarm.start` MCP call | `Start swarm: ${swarmId}` |

Do NOT wrap read-only calls (`swarm.list`, `bundle.list`, `health.check`).
The tracker is checked only in `switchEnvironment()` before restarting the
MCP server. Its purpose is to warn the user, not to block the switch.

---

## Gap 8 — Plugin → Webview push data contract

The extension host pushes data to the webview using these message types.
All messages are sent via `panel.webview.postMessage(msg)`.

```typescript
// Plugin → Webview message types (complete list)

// Initial config — sent once on panel open, before any data
{ type: 'config', payload: {
    baseUrl: string,       // e.g. http://localhost:8088
    swarmId?: string,      // for swarm-scoped panels
    tapId?: string,        // for tap viewer
    bundleId?: string,     // for bundle detail
    route: string,         // initial React route, e.g. /hive/my-swarm/view
    stompUrl: string,      // derived from baseUrl by resolveStompUrl()
  }
}

// Data push — used for periodic refresh (swarm list, queue depths)
{ type: 'data', key: 'swarms', payload: SwarmSummary[] }
{ type: 'data', key: 'queues', payload: QueueEntry[] }
{ type: 'data', key: 'journal', payload: JournalEntry[] }

// MCP tool response — reply to a { type:'mcp' } request from the webview
{ type: 'mcp-response', id: string, payload?: unknown, error?: string }

// API response — reply to a { type:'api' } request from the webview
{ type: 'api-response', id: string, status: number, body: string, headers: Record<string,string> }
```

The webview reads these in `window.__phPluginMessage` (registered in
`pluginBridge.ts`). The `config` message must be sent after
`onDidReceiveMessage` is registered, not before.

---

## Gap 9 — STOMP connection in plugin mode

**Decision: webview connects directly to the STOMP endpoint.**

VS Code webviews can make outbound WebSocket connections to any URL that
the extension host has declared in `package.json` under
`contributes.configuration` → `pockethive.environments[*].baseUrl`.
There is no CORS restriction on WebSocket connections from webviews.

The `baseUrl` is injected via the `config` postMessage. The webview calls
`resolveStompUrl(baseUrl)` (from `pluginBridge.ts`) to derive the STOMP URL
and connects directly. The extension host does NOT proxy STOMP.

This matches the existing `ui-v2` STOMP connection pattern — no changes
needed to `stompGateway.ts`.

If the STOMP connection fails (stack unreachable), the webview shows the
existing `ConnectivityIndicator` degraded state. No special plugin-mode
handling is needed.

---

## Gap 12 — Settings migration for existing extension users

This is an explicit compatibility feature, not an implicit fallback. On
activation, if `pockethive.hiveUrls` exists and `pockethive.environments` is
empty, show a migration prompt. Only migrate after the user accepts.

```typescript
// src/config.ts — call from activate() before McpServerManager.start()
export async function migrateSettingsIfNeeded(): Promise<void> {
  const config = vscode.workspace.getConfiguration('pockethive');
  const legacyUrls = config.get<string[]>('hiveUrls') ?? [];
  const environments = config.get<Environment[]>('environments') ?? [];

  if (legacyUrls.length > 0 && environments.length === 0) {
    const migrated: Environment[] = legacyUrls.map((url, i) => ({
      name: i === 0 ? 'local' : `env-${i}`,
      baseUrl: url,
      rabbitUser: 'guest',
      tcpMockUrl: '',
      wiremockUrl: '',
    }));
    const activeUrl = config.get<string>('activeHiveUrl') ?? '';
    const activeIdx = legacyUrls.indexOf(activeUrl);
    const activeName = activeIdx >= 0 ? migrated[activeIdx].name : migrated[0]?.name ?? '';

    const choice = await vscode.window.showInformationMessage(
      'PocketHive can migrate your old hiveUrls setting to named environments.',
      'Migrate',
      'Not now'
    );
    if (choice !== 'Migrate') {
      return;
    }

    await config.update('environments', migrated, vscode.ConfigurationTarget.Global);
    await config.update('activeEnvironment', activeName, vscode.ConfigurationTarget.Global);
    // Leave legacy keys in place - do not delete them (non-destructive migration)
  }
}
```

---

## Gap 13 — bundle.scaffold YAML content

`bundle.scaffold` in `server.mjs` uses the `yaml` npm package to serialise the
scaffold object. The MCP server package must declare `yaml` as its own
dependency. If it is not available, startup or tool registration must fail with
a clear missing-dependency error.

The scaffold content for each pattern is defined directly in the tool
handler as a plain JS object matching the canonical scenario contract.
No separate template files are needed.

---

## Gap 14 — MCP Apps availability and early scope

`@modelcontextprotocol/ext-apps` is published, so package availability is no
longer the blocker. Target client support still must be verified before relying
on App rendering for a workflow.

**Decision**: Add a Phase 1.5 spike for one read-only App-backed tool:
`evidence.summary`. The JSON tool result is canonical. App-capable clients may
render `ui://pockethive/evidence-summary` from that same result.

Do not build the broader MCP Apps dashboard/form platform in Phase 1.5. Future
Apps in `MCP-APPS.md` remain candidates after the evidence widget proves the
transport, packaging, and client support.

Implementation rules:

- Keep one canonical `evidence.summary` handler.
- Add explicit capability negotiation around the response presentation.
- Do not fork evidence logic by client type.
- Do not let the widget call PocketHive APIs directly.
- Do not add write-capable App tools in this phase.

---

## Gap 15 — IntelliJ McpServerManager singleton

`McpServerManager` must be registered as an **application-level service**
in `plugin.xml`, not instantiated directly. The status bar widget and all
actions obtain it via `McpServerManager.getInstance()`.

```xml
<!-- META-INF/plugin.xml -->
<applicationService
    serviceImplementation="io.pockethive.plugin.mcp.McpServerManager" />
```

```kotlin
// McpServerManager.kt
@Service(Service.Level.APP)
class McpServerManager : Disposable {
    companion object {
        fun getInstance(): McpServerManager =
            ApplicationManager.getApplication().getService(McpServerManager::class.java)
    }
    // ...
    override fun dispose() { stop() }
}
```

Remove the `project: Project` constructor parameter — application-level
services are not project-scoped. Use `ProjectManager.getInstance().openProjects`
if a project reference is needed for notifications.
