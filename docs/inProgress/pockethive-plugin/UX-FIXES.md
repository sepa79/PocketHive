# UX Fixes — Risk-Based Heuristic Review

## Status
`IN PROGRESS`

## Overview

15 UX issues identified via rapid testing heuristics (Visibility,
Error Prevention, Recovery, Efficiency, Consistency). Fixes are
ordered by risk: HIGH first, then MEDIUM, then LOW.

---

## Fix 1 — Remove swarm must not be an inline button (HIGH)

Remove is destructive. It tears down queues and runtime state.
It must never sit at the same visual weight as Start and Stop.

### Before
```
[▶ Start][■ Stop][✕ Remove]   <- all inline, same weight
```

### After
```
[▶ Start][■ Stop]             <- inline only
right-click → Remove...       <- context menu only, triggers confirm
```

### Confirm modal

```
+------------------------------------------+
| Remove swarm '<swarm-id>'?               |
|                                          |
| This will tear down all containers,      |
| queues, and runtime state. This cannot   |
| be undone.                               |
|                                          |
|              [Cancel]  [Remove]          |
+------------------------------------------+
```

### package.json menus change

Remove `pockethive.removeSwarm` from `view/item/context` inline group.
Move to navigation group (context menu only):

```json
{ "command": "pockethive.removeSwarm",
  "when": "view == pockethive.hive && viewItem =~ /^swarm/",
  "group": "navigation@3" }
```

---

## Fix 2 — Warn before environment switch cancels in-flight operations (HIGH)

Before switching environment, check for active operations and warn.

```typescript
// src/commands.ts
export async function switchEnvironment(
  envName: string,
  context: vscode.ExtensionContext
): Promise<void> {
  // Check for in-flight operations
  const active = operationTracker.getActive();
  if (active.length > 0) {
    const list = active.map(op => `  • ${op}`).join('\n');
    const choice = await vscode.window.showWarningMessage(
      `Switch to '${envName}'? Active operations will be cancelled:\n${list}`,
      { modal: true },
      'Switch anyway'
    );
    if (choice !== 'Switch anyway') return;
    operationTracker.cancelAll();
  }

  const config = vscode.workspace.getConfiguration('pockethive');
  await config.update('activeEnvironment', envName,
    vscode.ConfigurationTarget.Global);
  await mcpManager.restart(context);

  // Toast notification so user knows an agent or action changed env
  vscode.window.showInformationMessage(
    `PocketHive: Switched to '${envName}'`
  );
  refreshAllProviders();
  updateStatusBar();
}
```

### OperationTracker (src/operationTracker.ts)

```typescript
export class OperationTracker {
  private active = new Set<string>();

  register(label: string): () => void {
    this.active.add(label);
    return () => this.active.delete(label);
  }

  getActive(): string[] { return [...this.active]; }
  cancelAll(): void { this.active.clear(); }
}

export const operationTracker = new OperationTracker();
```

Usage — wrap any long-running MCP call:

```typescript
const done = operationTracker.register(`Validation: ${bundle}`);
try {
  await mcpTools.bundleValidate(bundle);
} finally {
  done();
}
```

---

## Fix 3 — Warn before deploying unvalidated bundle (HIGH)

```typescript
// src/commands.ts
export async function deployBundle(bundleId: string): Promise<void> {
  const status = validationCache.get(bundleId);

  if (!status || status.result === 'failed') {
    const msg = status?.result === 'failed'
      ? `'${bundleId}' failed validation. Deploying may break running swarms.`
      : `'${bundleId}' has not been validated.`;

    const choice = await vscode.window.showWarningMessage(
      msg,
      { modal: true },
      'Validate first',
      'Deploy anyway'
    );
    if (!choice || choice === 'Validate first') {
      await validateBundle(bundleId);
      return;
    }
  }

  const done = operationTracker.register(`Deploy: ${bundleId}`);
  try {
    await mcpTools.scenarioDeploy(bundleId);
    vscode.window.showInformationMessage(
      `PocketHive: '${bundleId}' deployed.`
    );
    scenarioProvider.refresh();
  } finally {
    done();
  }
}
```

---

## Fix 4 — Empty states for unconfigured plugin (HIGH)

Each tree view must show a helpful empty state when not configured.

### Hive provider — no environments

```typescript
// src/providers/hiveProvider.ts
getChildren(): HiveNode[] {
  const envs = getEnvironments();
  if (envs.length === 0) {
    return [{
      kind: 'empty',
      message: 'No environment configured.',
      action: { command: 'pockethive.addEnvironment', label: '+ Add environment' }
    }];
  }
  // ... normal children
}

getTreeItem(node: HiveNode): vscode.TreeItem {
  if (node.kind === 'empty') {
    const item = new vscode.TreeItem(node.message);
    item.description = node.action.label;
    item.command = { command: node.action.command, title: node.action.label };
    item.iconPath = new vscode.ThemeIcon('info');
    return item;
  }
  // ... normal items
}
```

### Scenario provider — no bundles folder

Same pattern with message:
`'No bundles folder configured. Click to add one.'`
and command `pockethive.addBundlesFolder`.

### Wireframe — empty Hive tab

```
+------------------------------------+
|                                    |
|   🐝                               |
|                                    |
|   No environment configured        |
|   Add a PocketHive stack to get    |
|   started.                         |
|                                    |
|   + Add environment                |  <- clickable tree item
|                                    |
+------------------------------------+
```

### Wireframe — empty Scenarios tab

```
+------------------------------------+
|                                    |
|   📁                               |
|                                    |
|   No bundles folder configured     |
|   Point to a folder containing     |
|   your scenario bundles.           |
|                                    |
|   + Add bundles folder             |  <- clickable tree item
|                                    |
+------------------------------------+
```

---

## Fix 5 — Restructure Hive tab: swarms scoped under active environment (MEDIUM)

Environments and swarms are separate concerns. Swarms belong to the
active environment. The redesigned Hive tab makes this explicit.

### Revised tree structure

```
ENVIRONMENT                    [Switch ▾]
  [●green] local  CONNECTED
  ─────────────────────────────────────
  SWARMS on local                [+ New]
  [●green] <swarm-a>  RUNNING
    N bees · <template-id>
    [▶ Start][■ Stop]  [View][Journal][Queues]
  [◐cyan]  <swarm-b>  READY
  [○grey]  <swarm-c>  STOPPED
  ─────────────────────────────────────
  [Manage environments...]
```

The `[Switch ▾]` dropdown lists all configured environments.
Selecting one updates `activeEnvironment` and restarts MCP.
`[Manage environments...]` navigates to the Settings tab.

### Why this is better

- Swarms are visually scoped to the active environment
- Switching environment is a single click on the dropdown
- The environment section no longer takes up card-sized space
- Users with many environments don't scroll past them to reach swarms

---

## Fix 6 — Persist validation cache across sessions (MEDIUM)

### ValidationCache (src/validationCache.ts)

```typescript
import * as fs from 'node:fs';
import * as path from 'node:path';

interface CacheEntry {
  bundleId: string;
  lastValidated: string;   // ISO timestamp
  result: 'passed' | 'failed';
  errors: string[];
  fileHash: string;        // hash of scenario.yaml mtime for staleness
}

export class ValidationCache {
  private cache = new Map<string, CacheEntry>();
  private cacheFile: string;

  constructor(bundlesRoot: string) {
    this.cacheFile = path.join(bundlesRoot, '.pockethive-cache.json');
    this.load();
  }

  get(bundleId: string): CacheEntry | undefined {
    return this.cache.get(bundleId);
  }

  set(bundleId: string, result: 'passed' | 'failed',
      errors: string[], fileHash: string): void {
    this.cache.set(bundleId, {
      bundleId,
      lastValidated: new Date().toISOString(),
      result,
      errors,
      fileHash,
    });
    this.save();
  }

  isStale(bundleId: string, currentHash: string): boolean {
    const entry = this.cache.get(bundleId);
    if (!entry) return true;
    if (entry.fileHash !== currentHash) return true;
    const age = Date.now() - new Date(entry.lastValidated).getTime();
    return age > 24 * 60 * 60 * 1000;  // stale after 24h
  }

  private load(): void {
    try {
      if (fs.existsSync(this.cacheFile)) {
        const data = JSON.parse(fs.readFileSync(this.cacheFile, 'utf8'));
        for (const entry of data) this.cache.set(entry.bundleId, entry);
      }
    } catch { /* ignore corrupt cache */ }
  }

  private save(): void {
    try {
      fs.writeFileSync(this.cacheFile,
        JSON.stringify([...this.cache.values()], null, 2), 'utf8');
    } catch { /* ignore write errors */ }
  }
}
```

Add `.pockethive-cache.json` to the bundles repo `.gitignore`.

Validation status tooltip now shows:
`"Validated 2h ago"` or `"Validated — files changed since last run"`

---

## Fix 7 — Journal tab pre-selects swarm from Hive context (MEDIUM)

When navigating to Journal from a swarm action, pass the swarmId.

```typescript
// src/commands.ts
export async function openJournal(swarmId?: string): Promise<void> {
  // Switch to Journal tab
  await vscode.commands.executeCommand(
    'pockethive.journal.focus'
  );
  // Pre-select the swarm in the journal provider
  if (swarmId) {
    journalProvider.setSwarmId(swarmId);
    journalProvider.refresh();
  }
}
```

```typescript
// src/providers/journalProvider.ts
export class JournalProvider implements vscode.TreeDataProvider<JournalNode> {
  private swarmId: string | null = null;

  setSwarmId(id: string): void {
    this.swarmId = id;
  }
  // getChildren uses this.swarmId for the debug.journal call
}
```

Journal tab wireframe updated — swarm dropdown pre-populated:

```
SWARM  [<swarm-id> ▾]   <- pre-selected from Hive context
```

---

## Fix 8 — Tap TTL countdown with Extend button (MEDIUM)

### Updated tap viewer header

```
+----------------------------------------------------------------------+
| Debug Tap: <swarm-id> / <role> OUT                   [↺] [✕ Close]  |
|----------------------------------------------------------------------|
| tap-<id>                                                             |
| [████████████████░░░░] 87s  [Extend]   <- progress bar + extend btn |
|   turns amber at 30s, red at 10s                                     |
```

### TapViewerPanel — TTL countdown logic

```typescript
// src/webviews/tapViewerPanel.ts
private ttlInterval: NodeJS.Timeout | null = null;
private tapCreatedAt = 0;
private readonly TAP_TTL_SEC = 120;

private startTtlCountdown(): void {
  this.tapCreatedAt = Date.now();
  this.ttlInterval = setInterval(() => {
    const elapsed = (Date.now() - this.tapCreatedAt) / 1000;
    const remaining = Math.max(0, this.TAP_TTL_SEC - elapsed);
    const pct = (remaining / this.TAP_TTL_SEC) * 100;

    this.panel.webview.postMessage({
      type: 'ttl-update',
      remaining: Math.round(remaining),
      pct,
      urgent: remaining < 30,
      critical: remaining < 10,
    });

    if (remaining <= 0) {
      clearInterval(this.ttlInterval!);
      this.panel.webview.postMessage({ type: 'ttl-expired' });
    }
  }, 1000);
}

async extendTap(): Promise<void> {
  // Close existing tap and create a new one with fresh TTL
  if (this.tapId) {
    await mcpTools.debugTapClose(this.tapId);
  }
  const result = await mcpTools.debugTap(
    this.swarmId, this.role, this.direction
  ) as any;
  this.tapId = result.tapId;
  this.tapCreatedAt = Date.now();
  // Existing samples are preserved in the webview state
}
```

---

## Fix 9 — Create swarm button shows target environment (MEDIUM)

### Bundle Detail panel header

```
+----------------------------------------------------------------------+
| Bundle: <bundle-id>    [Validate] [Deploy] [Create swarm on: local ▾]|
```

The dropdown lists all configured environments. Selecting one sets
the target environment for swarm creation without changing the active
environment globally.

```typescript
// src/webviews/bundleDetailPanel.ts
// postMessage to webview includes available environments
this.sendConfig({
  bundleId,
  baseUrl,
  environments: getEnvironments().map(e => e.name),
  activeEnvironment: getActiveEnvironment()?.name ?? '',
});
```

The webview renders the dropdown and sends back the selected environment
name with the create-swarm postMessage:

```
{ type: 'mcp', tool: 'swarm.create',
  args: { swarmId, templateId, targetEnv: 'nft-remote' } }
```

The extension host checks if `targetEnv` differs from the active
environment and temporarily switches before creating the swarm.

---

## Fix 10 — Collapse Advanced section in Settings tab (MEDIUM)

Settings tree view restructured with collapsible Advanced section.

### Revised Settings tree structure

```
ENVIRONMENTS              [+ Add]   <- always expanded
  [●] local  ACTIVE  CONNECTED
    [Use][Edit][🔑][Delete]
  <remote-env>
    [Use][Edit][🔑][Delete]

BUNDLES FOLDERS           [+ Add]   <- always expanded
  [📁] <bundles-repo>  ACTIVE
    [Use][Open][Delete]

▸ Advanced                          <- collapsed by default
  PocketHive Root
    /path/to/PocketHive/  [Browse]
  MCP Server
    [● stdio ○ http]  Running pid N
    [Restart][Logs]
```

```typescript
// src/providers/settingsProvider.ts
getTreeItem(node: SettingsNode): vscode.TreeItem {
  if (node.kind === 'advanced-header') {
    const item = new vscode.TreeItem('Advanced',
      this.advancedExpanded
        ? vscode.TreeItemCollapsibleState.Expanded
        : vscode.TreeItemCollapsibleState.Collapsed
    );
    item.iconPath = new vscode.ThemeIcon('gear');
    return item;
  }
  // ...
}
```

---

## Fix 11 — Add Queues button to swarm cards (MEDIUM)

Swarm cards in the Hive tab get an inline `[Queues]` button alongside
Start and Stop. This is the fastest path to queue depth data.

### Revised swarm card inline actions

```
[▶ Start][■ Stop]  [View][Journal][Queues]
```

`[View]` opens SwarmDetailPanel.
`[Journal]` opens JournalPanel pre-filtered to this swarm (Fix 7).
`[Queues]` opens QueueMonitorPanel.

### package.json menus addition

```json
{ "command": "pockethive.openQueueMonitor",
  "when": "view == pockethive.hive && viewItem =~ /^swarm/",
  "group": "inline@3" }
```

---

## Fix 12 — Add SETTINGS tab to VS Code panel (LOW)

The VS Code panel tab strip gains a fourth tab for consistency
with IntelliJ.

```
[● HIVE] [ SCENARIOS] [ JOURNAL] [ SETTINGS]
```

The Settings tab renders the existing `settingsProvider` tree view.
No new provider needed — just register it as a fourth tab in the
`viewsContainers` contribution.

```json
// package.json contributes.views.pockethive
{ "id": "pockethive.settings", "name": "Settings" }
```

---

## Fix 13 — Validate all / Deploy all need confirmation + progress (LOW)

### Validate all

```typescript
export async function validateAll(): Promise<void> {
  const bundles = await mcpTools.bundleList();
  const choice = await vscode.window.showWarningMessage(
    `Validate all ${bundles.bundles.length} bundles?`,
    { modal: true }, 'Validate all'
  );
  if (choice !== 'Validate all') return;

  await vscode.window.withProgress({
    location: vscode.ProgressLocation.Notification,
    title: 'PocketHive: Validating bundles',
    cancellable: true,
  }, async (progress, token) => {
    for (let i = 0; i < bundles.bundles.length; i++) {
      if (token.isCancellationRequested) break;
      const b = bundles.bundles[i];
      progress.report({
        message: `${b.name} (${i + 1}/${bundles.bundles.length})`,
        increment: 100 / bundles.bundles.length,
      });
      await validateBundle(b.name);
    }
  });
  scenarioProvider.refresh();
}
```

### Deploy all — same pattern with stronger warning

```typescript
const choice = await vscode.window.showWarningMessage(
  `Deploy all ${count} bundles to '${activeEnv}'? ` +
  `This will overwrite deployed scenarios on the active environment.`,
  { modal: true }, 'Deploy all'
);
```

---

## Fix 14 — MCP operation timeout with user notification (LOW)

All MCP tool calls are wrapped with a 30s timeout. On timeout, show
an actionable notification rather than silently failing.

```typescript
// src/mcp/client.ts
async callTool(name: string, args = {}, timeoutMs = 30000): Promise<unknown> {
  const timeoutPromise = new Promise<never>((_, reject) =>
    setTimeout(() => reject(new Error(`timeout:${name}`)), timeoutMs)
  );
  try {
    return await Promise.race([
      this._callTool(name, args),
      timeoutPromise,
    ]);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (msg.startsWith('timeout:')) {
      vscode.window.showWarningMessage(
        `PocketHive: '${name}' timed out. The MCP server may have restarted.`,
        'Check status'
      ).then(choice => {
        if (choice === 'Check status') {
          hiveProvider.refresh();
        }
      });
    }
    throw err;
  }
}
```

---

## Fix 15 — Toast notification when agent switches context (LOW)

When an AI agent calls `env.switch` or `context.set-bundles-root`,
the plugin shows a toast with an Undo option.

```typescript
// src/mcp/tools.ts — intercept context-changing tools
async envSwitch(profile: string): Promise<unknown> {
  const previous = getActiveEnvironment()?.name;
  const result = await this.client.callTool('env.switch', { profile });

  // Show toast with undo
  vscode.window.showInformationMessage(
    `🐝 PocketHive: Agent switched to '${profile}'`,
    'Undo'
  ).then(async choice => {
    if (choice === 'Undo' && previous) {
      await switchEnvironment(previous, extensionContext);
    }
  });

  return result;
}

async contextSetBundlesRoot(path: string): Promise<unknown> {
  const previous = getActiveBundlesFolder();
  const result = await this.client.callTool(
    'context.set-bundles-root', { path }
  );

  vscode.window.showInformationMessage(
    `🐝 PocketHive: Agent switched bundles folder to '${path}'`,
    'Undo'
  ).then(async choice => {
    if (choice === 'Undo' && previous) {
      await this.client.callTool('context.set-bundles-root',
        { path: previous });
      scenarioProvider.refresh();
    }
  });

  return result;
}
```

---

## Summary — cross-reference status

All changes below have been applied to the target docs.

| Doc | Change | Status |
|---|---|---|
| WIREFRAMES.md | Fix 1: remove inline Remove button from swarm cards | ✅ Already reflected |
| WIREFRAMES.md | Fix 4: add empty state wireframes for Hive + Scenarios tabs | ✅ Already reflected |
| WIREFRAMES.md | Fix 5: restructure Hive tab — env dropdown + scoped swarms | ✅ Already reflected |
| WIREFRAMES.md | Fix 8: tap viewer TTL progress bar | ✅ Already reflected |
| WIREFRAMES.md | Fix 9: bundle detail Create swarm button shows env | ✅ Already reflected |
| WIREFRAMES.md | Fix 10: Settings tab Advanced collapsed section | ✅ Already reflected |
| WIREFRAMES.md | Fix 11: swarm cards add Queues inline button | ✅ Already reflected |
| WIREFRAMES.md | Fix 12: VS Code tab strip adds SETTINGS tab | ✅ Already reflected |
| VIEWS.md | Fix 5: update Hive tree structure | ✅ Already reflected |
| VIEWS.md | Fix 7: journal pre-selects swarm | ✅ Already reflected |
| VIEWS.md | Fix 11: swarm card interactions table | ✅ Already reflected |
| VSCODE-PLUGIN.md | Fix 1: remove removeSwarm from inline menus | ✅ Applied |
| VSCODE-PLUGIN.md | Fix 12: add settings tab to viewsContainers | ✅ Already reflected |
| AGENT-RULES.md | Fix 15: agent context switches trigger toasts | ✅ Applied |
