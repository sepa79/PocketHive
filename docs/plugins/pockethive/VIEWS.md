# Plugin Views & Panels — Spec

## Status
`IMPLEMENTED BASELINE / REFERENCE`

## View inventory

| View | Type | IDE | Data source | Refresh |
|---|---|---|---|---|
| Hive | Tree view | Both | `swarm.list` via MCP | Manual + auto 5s |
| Scenarios | Tree view | Both | `bundle.list` via MCP | Manual |
| Journal | Tree view | Both | `debug.journal` via MCP | Manual + auto |
| Settings | Tree view | Both | Plugin settings | On settings change |
| Swarm Detail | Webview panel | Both | ui-v2 SwarmViewPage | STOMP live |
| Bundle Detail | Webview panel | Both | scenario.yaml + SM API | On open |
| Queue Monitor | Webview panel | Both | `debug.queues` via MCP | Auto 3s |
| Tap Viewer | Webview panel | Both | ui-v2 DebugTapViewerPage | On tap create |
| Status bar | Widget | Both | `health.check` via MCP | On env switch + 30s |

---

## Hive tree view

### Purpose
Primary operational view. Shows environments and swarms. Entry point for
all swarm lifecycle actions.

### Data sources
- Environment list: plugin settings (`pockethive.environments`)
- Swarm list: `swarm.list` MCP tool -> `GET /orchestrator/api/swarms`
- Health status: `health.check` MCP tool

### Tree structure

```
ENVIRONMENT                    [Switch ▾]
  [●green] local  CONNECTED  (active)
  ─────────────────────────────────────────────
  SWARMS on local                [+ New]
    [●green] <swarm-id-a>    RUNNING
      N bees · template: <template-id>
      inline: [▶ Start][■ Stop][View][Journal][Queues]
      context menu: Start | Stop | Remove... | Clone
    [◐cyan]  <swarm-id-b>    READY
    [○grey]  <swarm-id-c>    STOPPED
  ─────────────────────────────────────────────
  [Manage environments...]

Empty state (no environments):
  🐝  No environment configured
       + Add environment
```

### Interactions

| Action | Trigger | MCP tool |
|---|---|---|
| Start swarm | Inline button | `swarm.start` |
| Stop swarm | Inline button | `swarm.stop` |
| Remove swarm | Context menu only — with confirm modal | `swarm.remove` |
| Create swarm | [+ New] button | `swarm.create` |
| View topology | Inline [View] button | Opens SwarmDetailPanel |
| Open journal | Inline [Journal] button | Opens JournalPanel pre-filtered |
| Open queue monitor | Inline [Queues] button | Opens QueueMonitorPanel |
| Switch environment | [Switch ▾] dropdown | Updates settings, restarts MCP, shows toast |
| Add environment | [Manage environments...] | Navigates to Settings tab |

### Refresh strategy
- Auto-refresh swarm list every 5 seconds when view is visible
- HAL eye status updates immediately on swarm action
- Environment health dots update every 30 seconds

---

## Scenarios tree view

### Purpose
Bundle management. Shows all bundles in the active bundles folder with
validation status. Entry point for bundle authoring actions.

### Data sources
- Bundle list: `bundle.list` MCP tool (reads local filesystem)
- Validation status: cached from last `bundle.validate` run
- Deployed status: `scenario.list` MCP tool (compares local vs deployed)

### Tree structure

```
BUNDLES FOLDER
  [folder] <bundles-repo-name>  ACTIVE
    /path/to/bundles/
  [folder] <other-bundles-repo>
  + Add folder

BUNDLES              [+ New] [Validate all] [Deploy all]
  [check green]  <bundle-a>    CSV · TCP · N bees  DEPLOYED
    Validated 2h ago
    context menu: Validate | Deploy | Open | Create swarm | Open in explorer
  [warn amber]   <bundle-b>    CSV · HTTP · N bees  NOT VALIDATED
  [cross red]    <bundle-c>    ISO8583 · TCP · N bees  VALIDATION FAILED
    [error detail] FAIL: <message>

Empty state (no bundles folder):
  📁  No bundles folder configured
       + Add bundles folder
```

### Interactions

| Action | Trigger | MCP tool |
|---|---|---|
| Validate bundle | Context menu / button | `bundle.validate` + `bundle.validate.result` |
| Deploy bundle | Context menu / button | `scenario.deploy` |
| Open bundle | Context menu | Opens BundleDetailPanel webview |
| Create swarm from bundle | Context menu | `swarm.create` with templateId = bundle name |
| New bundle | [+ New] | Opens scaffold wizard |
| Switch folder | Click folder row | Updates settings, restarts MCP |
| Add folder | [+ Add] | File picker |

### Validation status icons
- `$(check)` green — last validation passed (tooltip: "Validated Xh ago")
- `$(warning)` amber — never validated, stale (>24h), or files changed since last run
- `$(error)` red — last validation failed
- `$(sync~spin)` — validation in progress

Validation results are persisted to `.pockethive-cache.json` in the
bundles root (gitignored) so status survives IDE restarts.

---

## Journal tree view

### Purpose
Shows control-plane events for a selected swarm. Quick triage view —
full journal opens in a webview.

### Data sources
- `debug.journal { swarmId, limit: 20 }` MCP tool

### Tree structure

```
SWARM  [<swarm-id> ▾]   <- pre-selected when opened from Hive context
```

[warn] template-invalid  14:32:01  generator
  "<validation error detail>"
[ok]   swarm-start       14:32:05
[ok]   config-update     14:32:04  generator
[ok]   swarm-template    14:32:03
[ok]   swarm-create      14:32:00

[Load more]
```

### Interactions

| Action | Trigger |
|---|---|
| Switch swarm | Dropdown picker |
| Refresh | [↺] button |
| Open full journal | Button -> opens JournalPage webview |
| Show event detail | Click row -> expands JSON payload |

---

## Settings tree view

### Purpose
Quick summary of current config. Links to full settings UI.

### Tree structure

```
ENVIRONMENTS                              [+ Add]
  [check cyan] local  ACTIVE  CONNECTED
    http://localhost:8088
    [Use] [Edit] [Set token] [Delete]
  <remote-env>
    http://<host>:8088
    [Use] [Edit] [Set token] [Delete]

BUNDLES FOLDERS                           [+ Add]
  [check cyan] <bundles-repo-name>  ACTIVE
    /path/to/bundles/
    [Use] [Open in explorer] [Delete]
  <other-bundles-repo>
    [Use] [Open in explorer] [Delete]

[Advanced ▸]                               <- collapsed by default
  PocketHive Root
    /path/to/PocketHive/  [Browse]
  MCP Server
    [●green] Running  pid <N>  stdio
    [Restart] [Diagnostics]
```

---

## Swarm Detail webview panel

### Purpose
Full swarm runtime view. Workers + live topology diagram. Equivalent to
`ui-v2/SwarmViewPage`.

### Data sources
- Scenario definition: `GET /scenario-manager/scenarios/{templateId}`
- Worker snapshots: STOMP `ph.control.#` subscription
- Queue stats: embedded in STOMP status-full events

### Layout

```
+------------------------------------------------------------------+
| Swarm: <swarm-id>                      [Back] [Journal] [Refresh]|
+------------------------------------------------------------------+
|                                                                  |
| +---------------------------+  +--------------------------------+|
| | WORKERS              N    |  | TOPOLOGY                       ||
| |                           |  |                                ||
| | [worker cards — see       |  | [ReactFlow diagram — see       ||
| |  WIREFRAMES.md]           |  |  WIREFRAMES.md]                ||
| |                           |  |                                ||
| +---------------------------+  +--------------------------------+|
|                                                                  |
+------------------------------------------------------------------+
```

### Interactions
- Click worker card -> highlights node in topology
- Click topology node -> selects worker card
- [Tap OUT] / [Tap IN] -> creates debug tap, opens TapViewerPanel
- [Journal] -> opens journal filtered to this swarm
- Drag nodes -> persists positions in sessionStorage

---

## Bundle Detail webview panel

### Purpose
Bundle overview. Static pipeline diagram from `scenario.yaml` + file tree
+ YAML preview. No swarm needed.

### Data sources
- `bundle.read { bundle, file: "scenario.yaml" }` MCP tool
- File list: `bundle.list` MCP tool (hasTemplates, hasDatasets, hasSut etc.)

### Layout

```
+------------------------------------------------------------------+
| Bundle: <bundle-id>           [Validate] [Deploy] [Create swarm] |
+------------------------------------------------------------------+
|                                                                  |
| PIPELINE (static from scenario.yaml)                             |
| [ReactFlow static diagram — no live data]                        |
|                                                                  |
| +---------------------------+  +--------------------------------+|
| | FILES                     |  | PREVIEW                        ||
| | scenario.yaml  [valid]    |  | (YAML content of selected file)||
| | templates/                |  |                                ||
| |   <service>/              |  |                                ||
| |     <call>.yaml           |  |                                ||
| | datasets/                 |  |                                ||
| |   <data>.csv              |  |                                ||
| | sut/                      |  |                                ||
| |   <env>/                  |  |                                ||
| |     sut.yaml              |  |                                ||
| +---------------------------+  +--------------------------------+|
|                                                                  |
+------------------------------------------------------------------+
```

### Interactions
- Click file in tree -> shows content in preview pane
- [Validate] -> runs `bundle.validate`, shows result inline
- [Deploy] -> runs `scenario.deploy`, shows result notification
- [Create swarm] -> opens swarm create dialog with templateId pre-filled

---

## Queue Monitor webview panel

### Purpose
Live queue depth monitoring for a running swarm.

### Data sources
- `debug.queues { swarmId }` MCP tool — polled every 3s

### Layout

```
+------------------------------------------------------------------+
| Queue Monitor: <swarm-id>              [Auto ●] [Filter] [↺]    |
+------------------------------------------------------------------+
|                                                                  |
| QUEUE                        DEPTH   CONSUMERS   AGE    HEALTH  |
| ph.<swarm-id>.<queue-a>          0        1       —       [ok]  |
| ph.<swarm-id>.<queue-b>          2        1       1s      [ok]  |
| ph.<swarm-id>.<queue-c>          0        1       —       [ok]  |
| ph.control.<swarm-id>.*          —        4       —       [ok]  |
|                                                                  |
| DEPTH OVER TIME (last 60s)                                       |
| [sparkline chart — <queue-a> / <queue-b> / <queue-c>]           |
|                                                                  |
+------------------------------------------------------------------+
```

### Health thresholds
- Green: depth = 0
- Amber: depth 1–10
- Red: depth > 10 or consumers = 0

---

## Tap Viewer webview panel

### Purpose
Inspect WorkItem payloads flowing through a worker queue in real time.
Equivalent to `ui-v2/DebugTapViewerPage`.

### Data sources
- `debug.tap` MCP tool (creates tap)
- `debug.tap.read` MCP tool (polls samples)
- `debug.tap.close` MCP tool (cleanup on panel close)

### Layout

```
+------------------------------------------------------------------+
| Debug Tap: <swarm-id> / <role> OUT                     [Close]  |
+------------------------------------------------------------------+
|                                                                  |
| +---------------------------+  +--------------------------------+|
| | SAMPLES              N    |  | SELECTED SAMPLE                ||
| |                           |  |                                ||
| | [14:32:01] WorkItem #1    |  | Headers:                       ||
| | [14:32:02] WorkItem #2 *  |  |   x-ph-call-id: <call-id>      ||
| | [14:32:03] WorkItem #3    |  |   x-ph-service-id: <service>   ||
| |                           |  |                                ||
| | [Refresh] [Clear]         |  | Steps:                         ||
| |                           |  |   [0] generator payload        ||
| |                           |  |   [1] <worker> output          ||
| |                           |  |   [2] processor response       ||
| |                           |  |                                ||
| +---------------------------+  +--------------------------------+|
|                                                                  |
+------------------------------------------------------------------+
```

### Lifecycle
- Panel open -> `debug.tap` creates tap with ttlSeconds=120
- TTL countdown shown as progress bar; turns amber <30s, red <10s
- [Extend] button appears at <30s: closes tap + recreates with fresh TTL
- Panel visible -> `debug.tap.read` polls every 2s
- Panel close -> `debug.tap.close` deletes tap
- Tap expires (120s) -> panel shows "Tap expired" with [Recreate] button

---

## Status bar widget

### VS Code
```
[🐝 PocketHive: <env-name>  ●]
```
- Click -> opens Settings tree view
- `●` colour: green=connected, amber=degraded, red=error, grey=stopped
- Tooltip: environment name, baseUrl, MCP status, last health check time

### IntelliJ
Same content, rendered as `StatusBarWidget` in the bottom status bar.
