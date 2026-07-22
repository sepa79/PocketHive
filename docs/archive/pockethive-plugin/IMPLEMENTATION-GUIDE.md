# Implementation Guide

## Status
`IMPLEMENTED BASELINE / ARCHIVED`

## Purpose

Fills the concrete implementation gaps not covered by the spec docs.
An agent must read this alongside AGENT-RULES.md before writing code.

---

## 1. MCP client — TypeScript (VS Code)

### Dependencies

```json
// vscode-pockethive/package.json
"dependencies": {
  "@modelcontextprotocol/sdk": "^1.25.1"
}
```

### Connecting over stdio

```typescript
// src/mcp/client.ts
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { ChildProcess } from 'node:child_process';

export class McpClient {
  private client: Client;
  private transport: StdioClientTransport;

  constructor(child: ChildProcess) {
    this.transport = new StdioClientTransport({
      // StdioClientTransport wraps an existing process's stdio streams
      stdin: child.stdin!,
      stdout: child.stdout!,
    });
    this.client = new Client(
      { name: 'pockethive-vscode', version: '1.0.0' },
      { capabilities: {} }
    );
  }

  async connect(): Promise<void> {
    await this.client.connect(this.transport);
  }

  async callTool(name: string, args: Record<string, unknown> = {}): Promise<unknown> {
    const result = await this.client.callTool({ name, arguments: args });
    if (result.isError) {
      throw new Error(String(result.content?.[0]?.text ?? 'MCP tool error'));
    }
    const text = result.content?.[0]?.text;
    if (typeof text === 'string') {
      try { return JSON.parse(text); } catch { return text; }
    }
    return result.content;
  }

  async close(): Promise<void> {
    await this.client.close();
  }
}
```

### Typed tool wrappers (src/mcp/tools.ts)

```typescript
// Partial example — add one wrapper per MCP tool
export class McpTools {
  constructor(private client: McpClient) {}

  async bundleList(): Promise<{ bundles: BundleSummary[] }> {
    return this.client.callTool('bundle.list') as Promise<{ bundles: BundleSummary[] }>;
  }

  async bundleValidate(bundle: string): Promise<{ jobId: string }> {
    return this.client.callTool('bundle.validate', { bundle }) as Promise<{ jobId: string }>;
  }

  async bundleValidateResult(jobId: string): Promise<ValidationResult> {
    return this.client.callTool('bundle.validate.result', { jobId }) as Promise<ValidationResult>;
  }

  async scenarioDeploy(bundle: string): Promise<{ deployed: boolean }> {
    return this.client.callTool('scenario.deploy', { bundle }) as Promise<{ deployed: boolean }>;
  }

  async swarmList(): Promise<SwarmSummary[]> {
    return this.client.callTool('swarm.list') as Promise<SwarmSummary[]>;
  }

  async swarmCreate(swarmId: string, templateId: string, sutId?: string, variablesProfileId?: string) {
    return this.client.callTool('swarm.create', { swarmId, templateId, sutId, variablesProfileId });
  }

  async swarmStart(swarmId: string) {
    return this.client.callTool('swarm.start', { swarmId });
  }

  async swarmStop(swarmId: string) {
    return this.client.callTool('swarm.stop', { swarmId });
  }

  async swarmRemove(swarmId: string) {
    return this.client.callTool('swarm.remove', { swarmId });
  }

  async debugQueues(swarmId?: string) {
    return this.client.callTool('debug.queues', swarmId ? { swarmId } : {});
  }

  async debugTap(swarmId: string, role: string, direction: 'IN' | 'OUT', ioName = 'in', maxItems = 10) {
    return this.client.callTool('debug.tap', { swarmId, role, direction, ioName, maxItems });
  }

  async debugJournal(swarmId: string, limit = 20) {
    return this.client.callTool('debug.journal', { swarmId, limit });
  }

  async healthCheck() {
    return this.client.callTool('health.check');
  }

  async contextGet() {
    return this.client.callTool('context.get');
  }

  async contextSetBundlesRoot(path: string) {
    return this.client.callTool('context.set-bundles-root', { path });
  }

  async envList() {
    return this.client.callTool('env.list');
  }

  async envSwitch(profile: string): Promise<{ switched: boolean; requiresRestart?: boolean }> {
    return this.client.callTool('env.switch', { profile }) as Promise<{ switched: boolean; requiresRestart?: boolean }>;
  }
}
```

---

## 2. MCP server manager (src/mcp/manager.ts)

The IDE adapter may spawn the MCP server process. This does not relax the MCP
tool boundary: MCP tools still must not execute shell commands, run local dev
tools, or read container logs. The spawned process is the product integration
server itself, not a general command runner.

```typescript
import * as vscode from 'vscode';
import { spawn, ChildProcess } from 'node:child_process';
import { McpClient } from './client';
import { buildMcpEnv } from '../config';

export class McpServerManager {
  private child: ChildProcess | null = null;
  private _client: McpClient | null = null;
  private restartAttempts = 0;
  private readonly maxRestarts = 3;
  private readonly _onStatusChange = new vscode.EventEmitter<McpStatus>();
  readonly onStatusChange = this._onStatusChange.event;

  async start(context: vscode.ExtensionContext): Promise<void> {
    const serverPath = resolveServerPath();
    const env = await buildMcpEnv(context);

    this._onStatusChange.fire('starting');
    this.child = spawn('node', [serverPath], {
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    this.child.stderr?.on('data', (data) => {
      outputChannel.appendLine(`[MCP] ${data.toString().trim()}`);
    });

    this.child.on('exit', (code) => {
      outputChannel.appendLine(`[MCP] Server exited with code ${code}`);
      this._onStatusChange.fire('stopped');
      this.scheduleRestart(context);
    });

    this._client = new McpClient(this.child);
    await this._client.connect();
    this.restartAttempts = 0;
    this._onStatusChange.fire('running');
  }

  async restart(context: vscode.ExtensionContext): Promise<void> {
    await this.stop();
    await new Promise(r => setTimeout(r, 500));
    await this.start(context);
  }

  async stop(): Promise<void> {
    if (this._client) { await this._client.close().catch(() => {}); this._client = null; }
    if (this.child) { this.child.kill(); this.child = null; }
    this._onStatusChange.fire('stopped');
  }

  get client(): McpClient | null { return this._client; }
  isRunning(): boolean { return this.child !== null && this._client !== null; }

  private scheduleRestart(context: vscode.ExtensionContext): void {
    if (this.restartAttempts >= this.maxRestarts) {
      vscode.window.showErrorMessage(
        'PocketHive MCP server failed to start after 3 attempts.',
        'Retry'
      ).then(choice => { if (choice === 'Retry') { this.restartAttempts = 0; this.start(context); } });
      return;
    }
    const delayMs = [2000, 4000, 8000][this.restartAttempts] ?? 8000;
    this.restartAttempts++;
    setTimeout(() => this.start(context), delayMs);
  }
}

export type McpStatus = 'starting' | 'running' | 'stopped' | 'error';

function resolveServerPath(): string {
  const override = vscode.workspace.getConfiguration('pockethive').get<string>('mcpServerPath');
  if (override && override.trim().length > 0) return override.trim();
  // Fall back to globally installed npm package
  const { execSync } = require('node:child_process');
  try {
    const root = execSync('npm root -g', { encoding: 'utf8' }).trim();
    return `${root}/@pockethive/mcp-server/server.mjs`;
  } catch {
    throw new Error('PocketHive MCP server not found. Run: npm install -g @pockethive/mcp-server');
  }
}
```

---

## 3. env.switch — requiresRestart signal

`env.switch` in `server.mjs` returns `{ switched: true, requiresRestart: true }` when
the environment change requires the server to be restarted with new env vars (which is
always the case since env vars are read at spawn time).

The plugin detects this and restarts:

```typescript
// In commands.ts
async function switchEnvironment(envName: string, context: vscode.ExtensionContext): Promise<void> {
  // 1. Update plugin settings first
  const config = vscode.workspace.getConfiguration('pockethive');
  await config.update('activeEnvironment', envName, vscode.ConfigurationTarget.Global);

  // 2. Call env.switch on the current server (updates its in-memory state)
  const result = await mcpManager.client?.callTool('env.switch', { profile: envName }) as any;

  // 3. If requiresRestart, respawn with new env vars
  if (result?.requiresRestart) {
    await mcpManager.restart(context);
  }

  // 4. Refresh all views
  refreshAllProviders();
  updateStatusBar();
}
```

---

## 4. Webview postMessage bridge — pluginApiFetch

The webview cannot call PocketHive APIs directly (CORS, auth). All HTTP
calls go through the extension host via postMessage.

### In ui-v2 (plugin mode shim)

```typescript
// ui-v2/src/lib/pluginBridge.ts  (new file, only used in PLUGIN_MODE)
type PendingRequest = { resolve: (r: Response) => void; reject: (e: Error) => void };
const pending = new Map<string, PendingRequest>();
let seq = 0;

// Called by the extension host when an API response arrives
window.__phPluginMessage = (msg: unknown) => {
  if (!msg || typeof msg !== 'object') return;
  const { type, id, status, body, headers } = msg as any;
  if (type !== 'api-response') return;
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  p.resolve(new Response(body, { status, headers }));
};

export async function pluginApiFetch(path: string, init?: RequestInit): Promise<Response> {
  const id = `req-${++seq}`;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    window.parent.postMessage({
      type: 'api',
      id,
      method: init?.method ?? 'GET',
      path,
      body: init?.body ?? null,
      headers: Object.fromEntries(new Headers(init?.headers ?? {}).entries()),
    }, '*');
    // Timeout after 30s
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error(`API request timed out: ${path}`));
      }
    }, 30000);
  });
}
```

### In the extension host (WebviewPanel base class)

```typescript
// src/webviews/WebviewPanel.ts
protected handleMessage(msg: unknown): void {
  if (!msg || typeof msg !== 'object') return;
  const m = msg as any;

  if (m.type === 'api') {
    const baseUrl = getActiveBaseUrl();
    fetch(baseUrl + m.path, {
      method: m.method,
      headers: m.headers,
      body: m.body ?? undefined,
    })
      .then(async res => {
        const body = await res.text();
        this.panel.webview.postMessage({
          type: 'api-response',
          id: m.id,
          status: res.status,
          body,
          headers: Object.fromEntries(res.headers.entries()),
        });
      })
      .catch(err => {
        this.panel.webview.postMessage({
          type: 'api-response',
          id: m.id,
          status: 500,
          body: err.message,
          headers: {},
        });
      });
  }

  if (m.type === 'mcp') {
    mcpManager.client?.callTool(m.tool, m.args ?? {})
      .then(result => {
        this.panel.webview.postMessage({ type: 'mcp-response', id: m.id, payload: result });
      })
      .catch(err => {
        this.panel.webview.postMessage({ type: 'mcp-response', id: m.id, error: err.message });
      });
  }
}
```

---

## 5. VS Code package.json — menus wiring for context values

The tree view context values defined in VSCODE-PLUGIN.md must be wired
in `package.json` `contributes.menus`:

```json
"menus": {
  "view/title": [
    { "command": "pockethive.addEnvironment",      "when": "view == pockethive.hive",     "group": "navigation" },
    { "command": "pockethive.refreshHive",          "when": "view == pockethive.hive",     "group": "navigation" },
    { "command": "pockethive.startAllSwarms",       "when": "view == pockethive.hive",     "group": "navigation" },
    { "command": "pockethive.stopAllSwarms",        "when": "view == pockethive.hive",     "group": "navigation" },
    { "command": "pockethive.addBundlesFolder",     "when": "view == pockethive.scenario", "group": "navigation" },
    { "command": "pockethive.refreshScenario",      "when": "view == pockethive.scenario", "group": "navigation" },
    { "command": "pockethive.refreshJournal",       "when": "view == pockethive.journal",  "group": "navigation" }
  ],
  "view/item/context": [
    { "command": "pockethive.setActiveEnvironment", "when": "view == pockethive.hive && viewItem == environment",       "group": "inline" },
    { "command": "pockethive.editEnvironment",      "when": "view == pockethive.hive && viewItem =~ /^environment/",   "group": "inline" },
    { "command": "pockethive.setEnvironmentToken",  "when": "view == pockethive.hive && viewItem =~ /^environment/",   "group": "inline" },
    { "command": "pockethive.removeEnvironment",    "when": "view == pockethive.hive && viewItem =~ /^environment/",   "group": "inline" },
    { "command": "pockethive.startSwarm",            "when": "view == pockethive.hive && viewItem =~ /^swarm/", "group": "inline@1" },
    { "command": "pockethive.stopSwarm",             "when": "view == pockethive.hive && viewItem =~ /^swarm/", "group": "inline@2" },
    { "command": "pockethive.openSwarmDetail",       "when": "view == pockethive.hive && viewItem =~ /^swarm/", "group": "inline@3" },
    { "command": "pockethive.openJournal",           "when": "view == pockethive.hive && viewItem =~ /^swarm/", "group": "inline@4" },
    { "command": "pockethive.openQueueMonitor",      "when": "view == pockethive.hive && viewItem =~ /^swarm/", "group": "inline@5" },
    { "command": "pockethive.removeSwarm",           "when": "view == pockethive.hive && viewItem =~ /^swarm/", "group": "navigation@1" },
    { "command": "pockethive.validateBundle",       "when": "view == pockethive.scenario && viewItem =~ /^bundle/",    "group": "inline" },
    { "command": "pockethive.deployBundle",         "when": "view == pockethive.scenario && viewItem =~ /^bundle/",    "group": "inline" },
    { "command": "pockethive.openBundleDetail",     "when": "view == pockethive.scenario && viewItem =~ /^bundle/",    "group": "navigation" },
    { "command": "pockethive.setActiveBundlesFolder","when": "view == pockethive.scenario && viewItem == bundlesFolder","group": "inline" }
  ]
}
```

---

## 6. IntelliJ — minimum viable Gradle scaffold

```
intellij-pockethive/
  src/
    main/
      kotlin/io/pockethive/plugin/
        (source files)
      resources/
        META-INF/plugin.xml
        icons/hive.svg
  build.gradle.kts
  gradle.properties
  settings.gradle.kts
```

### settings.gradle.kts

```kotlin
rootProject.name = "pockethive-intellij"
```

### gradle.properties

```properties
pluginGroup=io.pockethive
pluginName=PocketHive
pluginVersion=1.0.0
pluginSinceBuild=231
pluginUntilBuild=251.*
platformType=IC
platformVersion=2023.1
```

### build.gradle.kts

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.2"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
}

intellij {
    pluginName = providers.gradleProperty("pluginName")
    version = providers.gradleProperty("platformVersion")
    type = providers.gradleProperty("platformType")
    plugins = listOf("com.intellij.java")
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    patchPluginXml {
        sinceBuild = providers.gradleProperty("pluginSinceBuild")
        untilBuild = providers.gradleProperty("pluginUntilBuild")
    }
    // Copy dist-plugin assets into the plugin sandbox
    prepareSandbox {
        from("../ui-v2/dist-plugin") {
            into("${intellij.pluginName.get()}/dist-plugin")
        }
    }
    signPlugin {
        certificateChain = System.getenv("CERTIFICATE_CHAIN") ?: ""
        privateKey = System.getenv("PRIVATE_KEY") ?: ""
        password = System.getenv("PRIVATE_KEY_PASSWORD") ?: ""
    }
    publishPlugin {
        token = System.getenv("PUBLISH_TOKEN") ?: ""
    }
}
```

---

## 7. MCP server — integration test scaffold

The `integration-test.mjs` file referenced in AGENT-RULES does not exist
yet. Create it at `tools/pockethive-mcp/integration-test.mjs`:

```javascript
#!/usr/bin/env node
// Basic smoke test — requires a running local PocketHive stack.
// Usage: node integration-test.mjs

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { spawn } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));

const child = spawn('node', [resolve(__dirname, 'server.mjs')], {
  env: {
    ...process.env,
    POCKETHIVE_BASE_URL: process.env.POCKETHIVE_BASE_URL ?? 'http://localhost:8088',
    BUNDLES_ROOT: process.env.BUNDLES_ROOT ?? resolve(__dirname, '../../scenarios'),
  },
  stdio: ['pipe', 'pipe', 'inherit'],
});

const transport = new StdioClientTransport({ stdin: child.stdin, stdout: child.stdout });
const client = new Client({ name: 'integration-test', version: '1.0.0' }, { capabilities: {} });
await client.connect(transport);

async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) throw new Error(`${name} failed: ${result.content?.[0]?.text}`);
  return JSON.parse(result.content?.[0]?.text ?? '{}');
}

// Tests
console.log('context.get:', await call('context.get'));
console.log('bundle.list:', await call('bundle.list'));
console.log('health.check:', await call('health.check'));
console.log('env.list:', await call('env.list'));

await client.close();
child.kill();
console.log('\nAll integration tests passed.');
```

---

## 8. ui-v2 — STOMP URL derivation in plugin mode

The webview needs to derive the STOMP WebSocket URL from the `baseUrl`
injected via postMessage. Add this to `ui-v2/src/lib/config.ts`:

```typescript
export function resolveStompUrl(baseUrl: string): string {
  // http://host:8088 -> ws://host:8088/stomp/websocket
  // https://host     -> wss://host/stomp/websocket
  const url = new URL(baseUrl);
  const protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${url.host}/stomp/websocket`;
}
```

In plugin mode, the webview receives `baseUrl` via the config postMessage
and calls `resolveStompUrl(baseUrl)` to connect STOMP. The existing
`stompClient.ts` `setClient` function is called with the new client.

---

## 9. IntelliJ MCP client (Kotlin)

The IntelliJ plugin communicates with the MCP server over stdio using
JSON-RPC 2.0. There is no official Kotlin MCP SDK — implement a minimal
client directly.

### Dependency

```kotlin
// build.gradle.kts — already included via jackson-module-kotlin
implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
```

### McpClient.kt

```kotlin
package io.pockethive.plugin.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpClient(private val process: Process) {

    private val mapper = ObjectMapper().registerKotlinModule()
    private val seq = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, PendingCall>()
    private val writer = PrintWriter(process.outputStream, true)
    @Volatile private var running = true

    // Start reader thread on construction
    private val readerThread = Thread(::readLoop, "pockethive-mcp-reader").also {
        it.isDaemon = true
        it.start()
    }

    /** Send MCP initialize handshake. Call once after construction. */
    fun initialize() {
        sendRequest("initialize", mapper.createObjectNode().apply {
            putObject("clientInfo").apply {
                put("name", "pockethive-intellij")
                put("version", "1.0.0")
            }
            putObject("capabilities")
            put("protocolVersion", "2024-11-05")
        })
        // Send initialized notification (no id, no response expected)
        val notification = mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        writer.println(mapper.writeValueAsString(notification))
    }

    /**
     * Call an MCP tool and return the parsed result.
     * Blocks the calling thread up to [timeoutSec] seconds.
     */
    fun callTool(name: String, args: Map<String, Any?> = emptyMap(), timeoutSec: Long = 30): Any? {
        val params = mapper.createObjectNode().apply {
            put("name", name)
            set<JsonNode>("arguments", mapper.valueToTree(args))
        }
        val result = sendRequest("tools/call", params, timeoutSec)
        // MCP result shape: { content: [{ type: "text", text: "..." }], isError: false }
        val isError = result.path("isError").asBoolean(false)
        val text = result.path("content").firstOrNull()?.path("text")?.asText()
        if (isError) throw McpToolException(name, text ?: "unknown error")
        if (text == null) return null
        return try { mapper.readValue(text, Any::class.java) } catch (_: Exception) { text }
    }

    fun close() {
        running = false
        readerThread.interrupt()
        process.destroy()
    }

    private fun sendRequest(method: String, params: JsonNode, timeoutSec: Long = 30): JsonNode {
        val id = seq.getAndIncrement()
        val request = mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            set<JsonNode>("params", params)
        }
        val call = PendingCall()
        pending[id] = call
        writer.println(mapper.writeValueAsString(request))
        if (!call.latch.await(timeoutSec, TimeUnit.SECONDS)) {
            pending.remove(id)
            throw McpTimeoutException(method, timeoutSec)
        }
        val response = call.response ?: throw McpToolException(method, "null response")
        if (response.has("error")) {
            val msg = response.path("error").path("message").asText("MCP error")
            throw McpToolException(method, msg)
        }
        return response.path("result")
    }

    private fun readLoop() {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        while (running) {
            val line = try { reader.readLine() } catch (_: Exception) { break } ?: break
            if (line.isBlank()) continue
            try {
                val node = mapper.readTree(line)
                val id = node.path("id").asLong(-1)
                if (id >= 0) {
                    pending.remove(id)?.let { call ->
                        call.response = node
                        call.latch.countDown()
                    }
                }
            } catch (_: Exception) { /* ignore malformed lines */ }
        }
    }

    private class PendingCall {
        val latch = CountDownLatch(1)
        @Volatile var response: JsonNode? = null
    }
}

class McpToolException(tool: String, message: String) :
    RuntimeException("MCP tool '$tool' failed: $message")

class McpTimeoutException(method: String, timeoutSec: Long) :
    RuntimeException("MCP call '$method' timed out after ${timeoutSec}s")
```

### Usage in McpServerManager.kt

```kotlin
class McpServerManager {
    private var process: Process? = null
    private var _client: McpClient? = null
    val client: McpClient? get() = _client

    fun start(settings: PocketHiveSettings) {
        val serverPath = resolveServerPath(settings)
        val pb = ProcessBuilder("node", serverPath)
        pb.environment().apply {
            putAll(System.getenv())
            putAll(buildMcpEnv(settings))
        }
        pb.redirectErrorStream(false)  // keep stderr separate for logging
        val proc = pb.start()
        process = proc

        // Log stderr on a daemon thread
        Thread({
            proc.errorStream.bufferedReader().forEachLine { line ->
                McpOutputLog.append(line)
            }
        }, "pockethive-mcp-stderr").also { it.isDaemon = true; it.start() }

        val client = McpClient(proc)
        client.initialize()
        _client = client
    }

    fun restart(settings: PocketHiveSettings) {
        stop()
        Thread.sleep(500)
        start(settings)
    }

    fun stop() {
        _client?.close()
        _client = null
        process?.destroy()
        process = null
    }

    fun isRunning() = process?.isAlive == true && _client != null

    private fun resolveServerPath(settings: PocketHiveSettings): String {
        val override = settings.state.mcpServerPath.trim()
        if (override.isNotEmpty()) return override
        // Find globally installed npm package
        return try {
            val root = Runtime.getRuntime()
                .exec(arrayOf("npm", "root", "-g"))
                .inputStream.bufferedReader().readText().trim()
            "$root/@pockethive/mcp-server/server.mjs"
        } catch (e: Exception) {
            throw IllegalStateException(
                "PocketHive MCP server not found. Run: npm install -g @pockethive/mcp-server", e
            )
        }
    }
}
```

### Calling tools from actions (background thread)

```kotlin
// In any AnAction.actionPerformed
ApplicationManager.getApplication().executeOnPooledThread {
    try {
        val result = mcpManager.client?.callTool("swarm.list") as? List<*>
        ApplicationManager.getApplication().invokeLater {
            // update UI with result
        }
    } catch (e: McpToolException) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(e.message, "PocketHive")
        }
    }
}
```

---

## 10. Bundle scaffold wizard (pockethive.newBundle)

The IDE-native quick-pick wizard below is the **fast path** for users who
know what they want. For AI-assisted bundle creation in chat (where the
agent asks questions, infers intent, and handles ambiguity), see
[BUNDLE-WIZARD.md](../../plugins/pockethive/BUNDLE-WIZARD.md).

VS Code quick-pick multi-step flow. No webview needed — uses
`vscode.window.showInputBox` and `vscode.window.showQuickPick`.

```typescript
// src/commands.ts
export async function newBundle(): Promise<void> {
  const bundlesRoot = vscode.workspace.getConfiguration('pockethive')
    .get<string>('activeBundlesFolder');
  if (!bundlesRoot) {
    vscode.window.showErrorMessage('PocketHive: No bundles folder configured.');
    return;
  }

  // Step 1: bundle id
  const bundleId = await vscode.window.showInputBox({
    title: 'New Bundle (1/3)',
    prompt: 'Bundle ID — used as folder name and scenario id (e.g. my-service-load)',
    validateInput: v => /^[a-z0-9-]+$/.test(v.trim()) ? null : 'Use lowercase letters, numbers and hyphens only',
    ignoreFocusOut: true,
  });
  if (!bundleId) return;

  // Step 2: pipeline pattern
  const pattern = await vscode.window.showQuickPick([
    { label: 'REST — generator → processor → postprocessor',         value: 'rest-simple' },
    { label: 'REST with request-builder',                             value: 'rest-rbuilder' },
    { label: 'Sequence — generator → http-sequence → postprocessor', value: 'sequence' },
    { label: 'TCP — generator → processor (TCP) → postprocessor',    value: 'tcp-simple' },
    { label: 'Blank — scenario.yaml only',                           value: 'blank' },
  ], { title: 'New Bundle (2/3)', placeHolder: 'Choose pipeline pattern', ignoreFocusOut: true });
  if (!pattern) return;

  // Step 3: SUT type
  const sut = await vscode.window.showQuickPick([
    { label: 'WireMock (local)',  value: 'wiremock-local' },
    { label: 'TCP Mock (local)', value: 'tcp-mock-local' },
    { label: 'None',             value: 'none' },
  ], { title: 'New Bundle (3/3)', placeHolder: 'System Under Test', ignoreFocusOut: true });
  if (!sut) return;

  // Scaffold files via MCP tool
  // bundle.scaffold is a new MCP tool added in Phase 1 migration
  await mcpManager.client?.callTool('bundle.scaffold', {
    bundleId: bundleId.trim(),
    pattern: pattern.value,
    sutType: sut.value,
  });

  vscode.window.showInformationMessage(
    `PocketHive: Bundle '${bundleId}' created.`,
    'Open'
  ).then(choice => {
    if (choice === 'Open') {
      vscode.commands.executeCommand('pockethive.openBundleDetail', bundleId.trim());
    }
  });

  // Refresh scenario tree
  scenarioProvider.refresh();
}
```

### bundle.scaffold MCP tool (add to server.mjs)

```javascript
reg('bundle.scaffold', 'Scaffold a new bundle directory with scenario.yaml and optional templates', {
  bundleId: z.string().describe('Bundle id — used as folder name and scenario id'),
  pattern: z.enum(['rest-simple', 'rest-rbuilder', 'sequence', 'tcp-simple', 'blank']),
  sutType: z.enum(['wiremock-local', 'tcp-mock-local', 'none']).default('none'),
}, async ({ bundleId, pattern, sutType }) => {
  if (!BUNDLES_ROOT) throw new Error('BUNDLES_ROOT not set');
  const bundleDir = resolve(BUNDLES_ROOT, 'bundles', bundleId);
  if (existsSync(bundleDir)) throw new Error(`Bundle '${bundleId}' already exists`);

  mkdirSync(bundleDir, { recursive: true });

  const scenario = buildScaffoldScenario(bundleId, pattern, sutType);
  writeFileSync(resolve(bundleDir, 'scenario.yaml'), scenario, 'utf8');

  if (pattern !== 'blank') {
    mkdirSync(resolve(bundleDir, 'templates', 'default'), { recursive: true });
    writeFileSync(resolve(bundleDir, 'templates', 'default', 'call.yaml'),
      buildScaffoldTemplate(pattern), 'utf8');
  }
  if (sutType !== 'none') {
    mkdirSync(resolve(bundleDir, 'sut', sutType), { recursive: true });
    writeFileSync(resolve(bundleDir, 'sut', sutType, 'sut.yaml'),
      buildScaffoldSut(sutType), 'utf8');
  }

  return { created: true, bundleId, path: bundleDir };
});
```

---

## 11. Swarm create dialog (pockethive.createSwarm)

VS Code multi-step quick-pick. Fetches available templates from
`scenario.list` and SUT options from the MCP server.

```typescript
export async function createSwarm(): Promise<void> {
  // Step 1: pick template from deployed scenarios
  const scenarios = await mcpManager.client?.callTool('scenario.list') as any[];
  if (!scenarios?.length) {
    vscode.window.showErrorMessage('PocketHive: No scenarios deployed. Deploy a bundle first.');
    return;
  }
  const templatePick = await vscode.window.showQuickPick(
    scenarios.map(s => ({ label: s.id, description: s.name ?? '' })),
    { title: 'Create Swarm (1/3)', placeHolder: 'Select scenario template', ignoreFocusOut: true }
  );
  if (!templatePick) return;

  // Step 2: swarm id
  const swarmId = await vscode.window.showInputBox({
    title: 'Create Swarm (2/3)',
    prompt: 'Swarm ID — unique identifier for this run',
    value: `${templatePick.label}-${Date.now().toString(36)}`,
    validateInput: v => v.trim().length > 0 ? null : 'Swarm ID is required',
    ignoreFocusOut: true,
  });
  if (!swarmId) return;

  // Step 3: SUT (optional)
  const sutOptions = await mcpManager.client?.callTool('scenario.get',
    { scenarioId: templatePick.label }) as any;
  const sutIds: string[] = sutOptions?.suts?.map((s: any) => s.id) ?? [];
  let sutId: string | undefined;
  if (sutIds.length > 0) {
    const sutPick = await vscode.window.showQuickPick(
      [{ label: '(none)', value: '' }, ...sutIds.map(id => ({ label: id, value: id }))],
      { title: 'Create Swarm (3/3)', placeHolder: 'Select SUT environment (optional)', ignoreFocusOut: true }
    );
    if (!sutPick) return;
    sutId = sutPick.value || undefined;
  }

  // Create + wait-ready + start
  await mcpManager.client?.callTool('swarm.create', {
    swarmId: swarmId.trim(),
    templateId: templatePick.label,
    ...(sutId ? { sutId } : {}),
  });

  const ready = await mcpManager.client?.callTool('swarm.wait-ready',
    { swarmId: swarmId.trim(), timeoutSec: 60 }) as any;

  if (ready?.ready) {
    await mcpManager.client?.callTool('swarm.start', { swarmId: swarmId.trim() });
    vscode.window.showInformationMessage(`PocketHive: Swarm '${swarmId}' started.`);
  } else {
    vscode.window.showWarningMessage(
      `PocketHive: Swarm '${swarmId}' created but not yet ready. Start it manually.`
    );
  }

  hiveProvider.refresh();
}
```

---

## 12. Auth token input UX

Secure input stored in OS keychain via `context.secrets`. Never shown
in plain text after initial entry.

```typescript
export async function setEnvironmentToken(
  envName: string,
  context: vscode.ExtensionContext
): Promise<void> {
  const existing = await context.secrets.get(`ph.env.${envName}.authToken`);

  const token = await vscode.window.showInputBox({
    title: `Set auth token for '${envName}'`,
    prompt: 'Bearer token — stored securely in OS keychain, never in settings.json',
    password: true,                          // masks input
    value: existing ? '••••••••' : '',       // hint that one exists
    placeHolder: existing ? 'Leave blank to keep existing token' : 'ghp_... or Bearer ...',
    ignoreFocusOut: true,
  });

  if (token === undefined) return;           // user cancelled
  if (token === '' && existing) return;      // kept existing — no change
  if (token === '••••••••') return;          // unchanged placeholder

  if (token === '') {
    await context.secrets.delete(`ph.env.${envName}.authToken`);
    vscode.window.showInformationMessage(`PocketHive: Token removed for '${envName}'.`);
  } else {
    await context.secrets.store(`ph.env.${envName}.authToken`, token);
    vscode.window.showInformationMessage(`PocketHive: Token saved for '${envName}'.`);
  }

  // Restart MCP server so new token is picked up
  await mcpManager.restart(context);
}
```

For IntelliJ the equivalent uses `PasswordSafe` with a dialog:

```kotlin
fun setEnvironmentToken(envName: String, project: Project) {
    val existing = PocketHiveCredentials.getAuthToken(envName)
    val dialog = PasswordSafeDialog(
        project,
        title = "Set auth token for '$envName'",
        description = "Stored securely in OS keychain. Never written to pockethive.xml.",
        existingHint = if (existing != null) "Token already set — enter new value to replace" else null
    )
    if (!dialog.showAndGet()) return
    val token = dialog.password
    if (token.isNullOrBlank()) {
        PocketHiveCredentials.clearAuthToken(envName)
    } else {
        PocketHiveCredentials.setAuthToken(envName, token)
    }
    // Restart MCP server
    mcpServerManager.restart(PocketHiveSettings.getInstance())
}
```

---

## 13. Node.js not found — install prompt

```typescript
// In McpServerManager.start(), catch the spawn error:
catch (err: unknown) {
  const msg = err instanceof Error ? err.message : String(err);
  if (msg.includes('ENOENT') || msg.includes('not found')) {
    const choice = await vscode.window.showErrorMessage(
      'PocketHive: Node.js not found. The MCP server requires Node.js 20+.',
      'Download Node.js',
      'Set path manually'
    );
    if (choice === 'Download Node.js') {
      vscode.env.openExternal(vscode.Uri.parse('https://nodejs.org/en/download'));
    }
    if (choice === 'Set path manually') {
      vscode.commands.executeCommand('workbench.action.openSettings', 'pockethive.mcpServerPath');
    }
  } else {
    vscode.window.showErrorMessage(`PocketHive: MCP server failed to start — ${msg}`);
  }
}
```

For IntelliJ:

```kotlin
catch (e: IOException) {
    if (e.message?.contains("No such file") == true || e.message?.contains("error=2") == true) {
        val choice = Messages.showDialog(
            project,
            "Node.js not found. The PocketHive MCP server requires Node.js 20+.",
            "PocketHive — Node.js Required",
            arrayOf("Download Node.js", "Set server path", "Cancel"),
            0,
            Messages.getErrorIcon()
        )
        when (choice) {
            0 -> BrowserUtil.browse("https://nodejs.org/en/download")
            1 -> ShowSettingsUtil.getInstance().showSettingsDialog(project, PocketHiveConfigurable::class.java)
        }
    }
}
```

---

## 14. Journal webview — plugin-mode wiring

The `ui-v2/src/pages/journal/` pages use the same `apiFetch` pattern as
the rest of `ui-v2`. In plugin mode they automatically use `pluginApiFetch`
via the shim in §4 — no additional wiring needed for the journal pages
themselves.

The plugin opens the journal as a webview panel with a route parameter:

```typescript
// src/webviews/journalPanel.ts
export class JournalPanel extends WebviewPanel {
  static open(swarmId: string, context: vscode.ExtensionContext): void {
    const panel = new JournalPanel(context, `Journal: ${swarmId}`);
    // Send route config so ui-v2 renders the swarm journal page
    panel.sendConfig({ route: `/journal/swarms/${encodeURIComponent(swarmId)}` });
  }
}
```

The `ui-v2` app reads the `route` from the config postMessage and uses
`react-router-dom`'s `MemoryRouter` with `initialEntries` set to the
route — this avoids needing a real URL in the webview:

```typescript
// ui-v2/src/App.tsx — plugin mode branch
if (isPluginMode && pluginConfig?.route) {
  return (
    <MemoryRouter initialEntries={[pluginConfig.route]}>
      <Routes> ... </Routes>
    </MemoryRouter>
  );
}
```

---

## 15. Known gaps — Phase 4 only

These items remain out of scope until Phase 4 and an agent should not
attempt them without a separate spec:

| Gap | Where the spec lives |
|---|---|
| Authoring session MCP tools | `docs/concepts/pockethive-scenario-builder-mcp-plugin-spec.md` |
| Session UI panels in VS Code | Not yet designed — needs Phase 4 spec |
| Session UI panels in IntelliJ | Not yet designed — needs Phase 4 spec |
