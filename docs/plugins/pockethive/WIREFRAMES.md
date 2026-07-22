# Plugin Wireframes

## Status
`IMPLEMENTED BASELINE / REFERENCE`

## Design language

Extracted from `ui-v2/src/styles.css`. All plugin webviews use the same
tokens so the IDE panels feel like a natural extension of the web UI.

### Colour tokens

```
Background:       #05070b
Panel:            rgba(255,255,255,0.04)
Panel alt:        rgba(255,255,255,0.03)
Border:           rgba(255,255,255,0.12)
Border subtle:    rgba(255,255,255,0.08)
Muted text:       rgba(255,255,255,0.65)
Accent (cyan):    rgba(51,225,255,0.75)   #33e1ff
Brand amber:      #ffc107
OK green:         #00ff66
Warn cyan:        #00ccff
Alert red:        #ff0033
Missing grey:     #5b6679
```

### Typography

```
Font:             Inter, system-ui, Segoe UI, Roboto, sans-serif
H1:               18px, weight 700
H2:               14px, weight 700
Body:             13px
Small / label:    11-12px
Mono:             ui-monospace, SFMono-Regular, Menlo, Consolas
```

### Component patterns

```
Card:             border-radius 14px, border 1px solid rgba(255,255,255,0.12)
                  background rgba(255,255,255,0.04), padding 14px 16px

Button primary:   border-radius 10px, border 1px solid rgba(255,255,255,0.12)
                  background rgba(255,255,255,0.03), padding 6px 12px, 12px font

Button danger:    border-color rgba(255,95,95,0.35), bg rgba(255,95,95,0.12)

Pill OK:          border rgba(0,220,140,0.35), bg rgba(0,220,140,0.12)
Pill Bad:         border rgba(255,95,95,0.35), bg rgba(255,95,95,0.12)
Pill Info:        border rgba(51,225,255,0.35), bg rgba(51,225,255,0.12)
Pill Warn:        border rgba(255,193,7,0.4),  bg rgba(255,193,7,0.12)

Active item:      border-color rgba(51,225,255,0.5)
                  box-shadow 0 0 0 2px rgba(51,225,255,0.12)

Input:            border-radius 10px, border 1px solid rgba(255,255,255,0.12)
                  background rgba(255,255,255,0.03), padding 8px 10px
```

### HAL eye status indicator

Animated status dot used throughout. States:

```
ok      -- green  #00ff66  slow pulse animation
warn    -- cyan   #00ccff  modem blink animation
alert   -- red    #ff0033  fast pulse animation
missing -- grey   #5b6679  no animation
```

Mini variant (12px) used inline in tree view rows.
Full variant (28px) used in environment and swarm cards.

---

## 1. Activity bar icon + panel container (VS Code)

```
+--+
|  |  <- VS Code activity bar
|🐝|  <- PocketHive icon (hive.svg, amber on active)
|  |
+--+

Side panel (280px, #05070b bg):
+------------------------------------+
| Pocket[white]Hive[amber]    [⚙][?] |  <- 40px topbar, brand + icons
|------------------------------------|
| [● HIVE] [ SCENARIOS] [ JOURNAL] [ SETTINGS] |  <- tab strip
|------------------------------------|
|                                    |
|  (active tab content)              |
|                                    |
+------------------------------------+

Tab strip detail:
  Active tab:   bg rgba(51,225,255,0.14), border rgba(51,225,255,0.24)
  Inactive tab: bg transparent, color rgba(255,255,255,0.55)
  Border-radius: 12px, padding 10px 16px
```

---

## 2. Hive tab — environments + swarm list

```
+------------------------------------+
| Pocket[white]Hive[amber]    [⚙][?] |
|------------------------------------|
| [● HIVE] [ SCENARIOS] [ JOURNAL]  |
|------------------------------------|
|                                    |
| ENVIRONMENTS              [+ Add]  |  <- 10px caps label
|                                    |
| +--------------------------------+ |
| | [●green] local       CONNECTED | |  <- hal-eye ok, cyan active border
| |  http://localhost:8088         | |
| |  [Use][Edit][🔑][Delete]       | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [○grey]  remote-env            | |  <- hal-eye missing
| |  http://<host>:8088            | |
| |  [Use][Edit][🔑][Delete]       | |
| +--------------------------------+ |
| [+ Add environment]                |
|                                    |
|------------------------------------|
|                                    |
| ENVIRONMENT               [Switch ▾] |
| +--------------------------------+ |
| | [●green] local  CONNECTED      | |  <- active env, cyan border
| +--------------------------------+ |
|                                    |
| SWARMS on local           [+ New]  |
|                                    |
| +--------------------------------+ |
| | [●green] <swarm-a>  RUNNING    | |
| |  N bees · <template-id>        | |
| |  [▶ Start][■ Stop]             | |  <- Remove moved to context menu
| |  [View][Journal][Queues]       | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [◐cyan]  <swarm-b>  READY      | |
| |  N bees · <template-id>        | |
| |  [▶ Start][■ Stop]             | |
| |  [View][Journal][Queues]       | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [○grey]  <swarm-c>  STOPPED    | |
| |  N bees · <template-id>        | |
| |  [▶ Start][■ Stop]             | |
| |  [View][Journal][Queues]       | |
| +--------------------------------+ |
|                                    |
| [Manage environments...]           |
|                                    |
+------------------------------------+

Swarm card states:
  RUNNING:  hal-eye ok (green glow)
  READY:    hal-eye warn (cyan modem blink)
  STOPPED:  hal-eye missing (grey, no animation)
  FAILED:   hal-eye alert (red fast pulse)

Remove swarm: context menu only (right-click) — never an inline button.
Confirm modal required before remove executes.

Empty state (no environments configured):
+------------------------------------+
|                                    |
|   🐝                               |
|   No environment configured        |
|   Add a PocketHive stack to get    |
|   started.                         |
|   + Add environment                |
|                                    |
+------------------------------------+
```

---

## 3. Scenarios tab — bundle explorer

```
+------------------------------------+
| Pocket[white]Hive[amber]    [⚙][?] |
|------------------------------------|
| [ HIVE] [● SCENARIOS] [ JOURNAL]  |
|------------------------------------|
|                                    |
| BUNDLES FOLDER            [+ Add]  |
|                                    |
| +--------------------------------+ |
| | [📁] my-bundles-repo    ACTIVE | |  <- cyan active border
| |  /path/to/bundles/             | |
| |  [Use][Open][Delete]           | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [📁] other-bundles-repo        | |
| |  /path/to/other-bundles/       | |
| |  [Use][Open][Delete]           | |
| +--------------------------------+ |
|                                    |
|------------------------------------|
|                                    |
| BUNDLES          [+ New][Validate all][Deploy all] |
|                                    |
| +--------------------------------+ |
| | [✓green] bundle-a              | |  <- validation passed
| |  CSV · TCP · 4 bees  DEPLOYED  | |
| |  [Validate][Deploy][Open]      | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [✓green] bundle-b              | |
| |  CSV · HTTP · 3 bees DEPLOYED  | |
| |  [Validate][Deploy][Open]      | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [⚠amber] bundle-c              | |  <- not yet validated
| |  ISO8583 · TCP · 5 bees        | |
| |  [Validate][Deploy][Open]      | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [✕red]   bundle-d              | |  <- validation failed
| |  Redis · HTTP · 6 bees         | |
| |  [Validate][Deploy][Open]      | |
| |  > FAIL: Unrecognized field... | |  <- error child node, 11px mono
| +--------------------------------+ |
|                                    |
+------------------------------------+

Validation icons:
  $(check)   green  -- last validation passed (tooltip: "Validated Xh ago")
  $(warning) amber  -- never validated, stale (>24h), or files changed
  $(error)   red    -- last validation failed
  $(sync~spin)      -- validation running

Protocol chips: pill-info style, 11px
  CSV · TCP · ISO8583 · Redis · HTTP

Empty state (no bundles folder configured):
+------------------------------------+
|                                    |
|   📁                               |
|   No bundles folder configured     |
|   Point to a folder containing     |
|   your scenario bundles.           |
|   + Add bundles folder             |
|                                    |
+------------------------------------+
```

---

## 4. Journal tab

```
+------------------------------------+
| Pocket[white]Hive[amber]    [⚙][?] |
|------------------------------------|
| [ HIVE] [ SCENARIOS] [● JOURNAL]  |
|------------------------------------|
|                                    |
| SWARM  [<swarm-id> ▾]     [↺][→]  |  <- pre-selected from Hive context
|                                    |
| +--------------------------------+ |
| | [⚠] template-invalid           | |  <- journalIssueBoxWarn style
| |  generator · 14:32:01          | |
| |  "Unrecognized field protocol" | |
| |  [Open full journal]           | |
| +--------------------------------+ |
|                                    |
| 14:32:05  swarm-start      [✓]    |
| 14:32:04  config-update    [✓]    |
| 14:32:03  swarm-template   [✓]    |
| 14:32:01  template-invalid [⚠]    |  <- amber
| 14:32:00  swarm-create     [✓]    |
|                                    |
| [Load more]                        |
|                                    |
+------------------------------------+

Row colours:
  ok:    rgba(255,255,255,0.85)
  warn:  #ffd27a
  error: #ffb4b4
  info:  #9ee7ff

Issue box:
  warn:  border rgba(255,193,7,0.28), bg rgba(255,193,7,0.08)
  error: border rgba(255,95,95,0.3),  bg rgba(255,95,95,0.08)
```

---

## 5. Settings tab

```
+------------------------------------+
| Pocket[white]Hive[amber]    [⚙]   |
|------------------------------------|
| SETTINGS                           |
|------------------------------------|
|                                    |
| ENVIRONMENTS              [+ Add]  |
|                                    |
| +--------------------------------+ |
| | [●green] local        ACTIVE   | |  <- cyan border, hal-eye ok
| |  http://localhost:8088         | |
| |  [Use][Edit][🔑 Set token]     | |
| |  [Delete]                      | |
| +--------------------------------+ |
| +--------------------------------+ |
| | [○grey]  remote-env            | |
| |  http://<host>:8088            | |
| |  [Use][Edit][🔑 Set token]     | |
| |  [Delete]                      | |
| +--------------------------------+ |
|                                    |
| BUNDLES FOLDERS           [+ Add]  |
|                                    |
| +--------------------------------+ |
| | [📁] my-bundles-repo    ACTIVE | |  <- cyan border
| |  /path/to/bundles/             | |
| |  [Use][Open in explorer][Del]  | |
| +--------------------------------+ |
|                                    |
| ▸ Advanced                         |  <- collapsed by default
|   PocketHive Root                  |
|     /path/to/PocketHive/  [Browse] |
|   MCP Server                       |
|     [● stdio ○ http]  Running      |
|     pid <N>  [Restart][Logs]       |
|                                    |
+------------------------------------+

MCP status dot: hal-eye mini
  green  = running
  red    = crashed / failed to start
  grey   = not started / no config
```

---

## 6. Add / Edit environment modal

```
+----------------------------------------------------+
| Add Environment                           [✕ Close] |
|----------------------------------------------------|
|                                                    |
| Name                                               |
| +------------------------------------------------+ |
| | my-environment                                 | |
| +------------------------------------------------+ |
|                                                    |
| Base URL                                           |
| +------------------------------------------------+ |
| | http://localhost:8088                          | |
| +------------------------------------------------+ |
|                                                    |
| Auth Token                    [🔑 Set in keychain] |
| +------------------------------------------------+ |
| | ••••••••••••  (stored securely)                | |
| +------------------------------------------------+ |
| Note: token stored in OS keychain, not settings    |
|                                                    |
| [▾ Advanced]                                       |
|                                                    |
|   RabbitMQ user    RabbitMQ password               |
|   +------------+  +----------------------------+   |
|   | guest      |  | ••••••  [Set]              |   |
|   +------------+  +----------------------------+   |
|                                                    |
|   TCP Mock URL override  (blank = auto-derive)     |
|   +------------------------------------------------+|
|   |                                               ||
|   +------------------------------------------------+|
|                                                    |
|   WireMock URL override                            |
|   +------------------------------------------------+|
|   |                                               ||
|   +------------------------------------------------+|
|                                                    |
| +------------------------------------------------+ |
| | [Test connection]    [●green] Connected        | |  <- inline health
| +------------------------------------------------+ |
|                                                    |
|                          [Cancel]  [Save]          |
+----------------------------------------------------+

Modal:
  border-radius: 16px
  border: 1px solid rgba(255,255,255,0.12)
  background: rgba(11,15,22,0.98)
  width: min(560px, 96vw)

Test connection result:
  Connected:    hal-eye ok green + "Connected"
  Unreachable:  hal-eye alert red + "Unreachable: <error>"
  Checking:     spinner + "Checking..."
```

---

## 7. Swarm detail webview panel

Renders `ui-v2/SwarmViewPage` embedded in a VS Code webview tab
or IntelliJ JCEF editor tab.

```
+----------------------------------------------------------------------+
| Swarm: <swarm-id>                    [Back] [Journal] [Queues] [↺]  |
|----------------------------------------------------------------------|
|                                                                      |
| +---------------------------+  +----------------------------------+  |
| | WORKERS              N    |  | TOPOLOGY                         |  |
| |                           |  |                                  |  |
| | +-------------------------+ |  |  +----------+                  |  |
| | | [●] generator  enabled  | |  |  |generator |---<queue>--->+  |  |
| | |  (<id>) seen Xs ago     | |  |  +----------+              |  |  |
| | |  tps N                  | |  |       |                    |  |  |
| | |  [Tap OUT]              | |  |  +----------+              |  |  |
| | +-------------------------+ |  |  |<worker>  |              |  |  |
| | +-------------------------+ |  |  +----------+              |  |  |
| | | [●] <worker>   enabled  | |  |       |<queue>             |  |  |
| | |  tps N                  | |  |  +----------+              |  |  |
| | |  [Tap IN][Tap OUT]      | |  |  |processor |              |  |  |
| | +-------------------------+ |  |  +----------+              |  |  |
| | +-------------------------+ |  |       |<queue>             |  |  |
| | | [●] processor  enabled  | |  |  +----------+              |  |  |
| | |  tps N                  | |  |  |postproc  |              |  |  |
| | |  [Tap IN][Tap OUT]      | |  |  +----------+              |  |  |
| | +-------------------------+ |  |                                  |  |
| | +-------------------------+ |  |  ---- empty queue (blue)        |  |
| | | [●] postprocessor       | |  |  ==== deep queue (red, thick)   |  |
| | |  tps N                  | |  |                                  |  |
| | |  [Tap IN]               | |  |  [Reset view]                    |  |
| | +-------------------------+ |  +----------------------------------+  |
| +---------------------------+                                         |
|                                                                      |
+----------------------------------------------------------------------+

Worker card selected state:
  border-color: rgba(51,225,255,0.5)
  box-shadow: 0 0 0 2px rgba(51,225,255,0.12)

Edge colours (from ui-v2 TopologyView):
  Empty queue:  stroke #66aaff, width 2px
  Deep queue:   stroke #ff6666, width scales with log(depth+1)

Node shapes (from capability manifests):
  generator:       square
  moderator:       triangle
  processor:       diamond
  postprocessor:   hexagon
  http-sequence:   square  #0ea5e9
  request-builder: pentagon
```

---

## 8. Bundle detail webview panel

```
+----------------------------------------------------------------------+
| Bundle: <bundle-id>   [Validate] [Deploy] [Create swarm on: local ▾] |
|----------------------------------------------------------------------|
|                                                                      |
| PIPELINE  (static — from scenario.yaml, no swarm needed)            |
|                                                                      |
| +------------------------------------------------------------------+ |
| |  +----------+  <q>  +-----------+  <q>  +----------+           | |
| |  |generator |------>|<worker>   |------>|processor |           | |
| |  |<input>   |       |<role>     |       |<protocol>|           | |
| |  +----------+       +-----------+       +----+-----+           | |
| |                                              | <q>              | |
| |                                         +----v-----+           | |
| |                                         |postproc  |           | |
| |                                         +----------+           | |
| +------------------------------------------------------------------+ |
|                                                                      |
| +---------------------------+  +----------------------------------+  |
| | FILES                     |  | PREVIEW                          |  |
| |                           |  |                                  |  |
| | scenario.yaml      [✓]   |  | id: <bundle-id>                  |  |
| | variables.yaml            |  | name: <Bundle Name>              |  |
| | templates/                |  | template:                        |  |
| |   <service>/              |  |   image: swarm-controller:latest |  |
| |     <call>.yaml           |  |   bees:                          |  |
| | datasets/                 |  |     - role: generator            |  |
| |   <data>.csv              |  |       ...                        |  |
| | sut/                      |  |                                  |  |
| |   <env>/                  |  | [Open in editor]                 |  |
| |     sut.yaml              |  |                                  |  |
| +---------------------------+  +----------------------------------+  |
|                                                                      |
+----------------------------------------------------------------------+

Validation result banner (shown after [Validate]):
  Pass: pill-ok green  "Validation passed (2.3s)"
  Fail: pill-bad red   "Validation failed — see details"
         + expandable error detail block, mono 11px
```

---

## 9. Queue monitor webview panel

```
+----------------------------------------------------------------------+
| Queue Monitor: <swarm-id>                [● Auto] [Filter] [↺]      |
|----------------------------------------------------------------------|
|                                                                      |
| QUEUE                          DEPTH  CONSUMERS  AGE     HEALTH     |
| -------------------------------------------------------------------- |
| ph.<swarm-id>.<queue-a>            0          1    —     [●green]   |
| ph.<swarm-id>.<queue-b>            2          1   1s     [●green]   |
| ph.<swarm-id>.<queue-c>            0          1    —     [●green]   |
| ph.control.<swarm-id>.*            —          4    —     [●green]   |
|                                                                      |
| DEPTH OVER TIME  (last 60s)                                          |
| +------------------------------------------------------------------+ |
| | 10 |                                                             | |
| |  8 |    .--.                                                     | |
| |  6 |   /    \                                                    | |
| |  4 |  /      `--------------------------------------------      | |
| |  2 | /                                                           | |
| |  0 |/                                                            | |
| |    +----+----+----+----+----+----+----+----+----+----+----+----+ | |
| |    0s                                                        60s | |
| |    -- <queue-a>   -- <queue-b>   -- <queue-c>                    | |
| +------------------------------------------------------------------+ |
|                                                                      |
+----------------------------------------------------------------------+

Health thresholds:
  depth = 0:     hal-eye ok    green
  depth 1-10:    hal-eye warn  cyan
  depth > 10:    hal-eye alert red
  consumers = 0: hal-eye alert red (regardless of depth)

Auto-refresh: polls debug.queues every 3s when toggle is on
Chart: Victory.js sparklines (already in ui-v2 dependencies)
```

---

## 10. Debug tap viewer webview panel

Renders `ui-v2/DebugTapViewerPage`.

```
+----------------------------------------------------------------------+
| Debug Tap: <swarm-id> / <role> OUT                   [↺] [✕ Close]  |
|----------------------------------------------------------------------|
|                                                                      |
| tap-<id>                                                             |
| [████████████████░░░░] 87s  [Extend]  <- amber <30s, red <10s       |
|                                                                      |
| +---------------------------+  +----------------------------------+  |
| | SAMPLES               N   |  | SELECTED SAMPLE                  |  |
| |                           |  |                                  |  |
| | [14:32:01] WorkItem #1    |  | Headers:                         |  |
| | [14:32:02] WorkItem #2 *  |  |   x-ph-call-id: <call-id>        |  |
| | [14:32:03] WorkItem #3    |  |   x-ph-processor-status: 200     |  |
| |                           |  |   x-ph-processor-success: true   |  |
| | [Refresh] [Clear]         |  |   x-ph-processor-duration-ms: 12 |  |
| |                           |  |                                  |  |
| |                           |  | Steps:                           |  |
| |                           |  |   [▾ 0] generator payload        |  |
| |                           |  |   [▾ 1] <worker> output          |  |
| |                           |  |   [▾ 2] processor response       |  |
| |                           |  |     { ... }                      |  |
| +---------------------------+  +----------------------------------+  |
|                                                                      |
| [Tap expired — click Recreate to open a new tap]                    |
+----------------------------------------------------------------------+

Tap lifecycle:
  Open:    debug.tap creates tap, ttlSeconds=120
  Polling: debug.tap.read every 2s while panel visible
  Close:   debug.tap.close called on panel dispose
  Expired: banner shown with [Recreate] button

Selected sample:
  border-color: rgba(51,225,255,0.5)
  background: rgba(51,225,255,0.08)
```

---

## 11. Status bar widget

### VS Code (bottom status bar)

```
[🐝 PocketHive: <env-name>  ●]
 ^                            ^
 icon                         hal-eye mini dot

States:
  ● green  = MCP running + stack connected
  ● cyan   = MCP running + stack degraded
  ● red    = MCP crashed or stack unreachable
  ○ grey   = MCP not started / no config

Tooltip:
  PocketHive
  Environment: <env-name>
  Base URL:    <base-url>
  MCP:         Running (pid <N>, stdio)
  Last check:  <time> (<N>s ago)

Click: opens Settings tree view
```

### IntelliJ (bottom status bar widget)

Same content, rendered as `StatusBarWidget`.

```
🐝 PocketHive: <env-name> ●    <- right-aligned in status bar
```

---

## 12. IntelliJ tool window layout

```
+---------------------------------------------------------------------+
| IntelliJ IDEA                                                       |
|---------------------------------------------------------------------|
|                                              [PocketHive 🐝]  <- tool window tab (right panel)
|                                                                     |
| +---------------------------------------------------------------+   |
| | PocketHive                                      [⚙] [?] [↺]  |   |
| |---------------------------------------------------------------|   |
| | [HIVE] [SCENARIOS] [JOURNAL] [SETTINGS]                       |   |  <- tab strip
| |---------------------------------------------------------------|   |
| |                                                               |   |
| | ENVIRONMENTS                                                  |   |
| | [●green] local  http://localhost:8088  ACTIVE                 |   |
| | [○grey]  remote-env  http://<host>:8088                       |   |
| |                                                               |   |
| | SWARMS                                           [+ New]      |   |
| | [●green] <swarm-id-a>    RUNNING   N bees                     |   |
| |   [▶][■][✕]  [View topology]  [Journal]  [Queues]            |   |
| | [◐cyan]  <swarm-id-b>    READY     N bees                     |   |
| |   [▶][■][✕]  [View topology]  [Journal]  [Queues]            |   |
| |                                                               |   |
| +---------------------------------------------------------------+   |
|                                                                     |
+---------------------------------------------------------------------+

Topology and queue views open as separate editor tabs (JCEF panels),
not inside the tool window itself.
```

---

## 13. IntelliJ settings page

File → Settings → Tools → PocketHive

```
+----------------------------------------------------------------------+
| Settings                                                             |
| +------------------+  +------------------------------------------+  |
| | > Editor         |  | Tools > PocketHive                        |  |
| | > Plugins        |  |------------------------------------------|  |
| | v Tools          |  |                                           |  |
| |   > External     |  | Environments                   [+ Add]    |  |
| |   • PocketHive   |  | +--------------------------------------+  |  |
| |   > Terminal     |  | | Name       | Base URL     | Active  |  |  |
| | > Build          |  | |------------|--------------|---------|  |  |
| |                  |  | | local      | localhost:.. |   ●     |  |  |
| |                  |  | | remote-env | <host>:8088  |   ○     |  |  |
| |                  |  | +--------------------------------------+  |  |
| |                  |  | [Edit] [Delete] [Set token 🔑]           |  |  |
| |                  |  |                                           |  |  |
| |                  |  | Bundles Folders                [+ Add]    |  |  |
| |                  |  | +--------------------------------------+  |  |  |
| |                  |  | | /path/to/bundles/          ● active  |  |  |
| |                  |  | | /path/to/other-bundles/    ○         |  |  |
| |                  |  | +--------------------------------------+  |  |  |
| |                  |  | [Remove] [Set active]                     |  |  |
| |                  |  |                                           |  |  |
| |                  |  | PocketHive Root                           |  |  |
| |                  |  | +--------------------------------------+  |  |  |
| |                  |  | | /path/to/PocketHive/                 |  |  |
| |                  |  | +----------------------------------[📁]+  |  |  |
| |                  |  |                                           |  |  |
| |                  |  | MCP Server                                |  |  |
| |                  |  | Transport  (●) stdio  ( ) http            |  |  |
| |                  |  | HTTP URL   [                           ]  |  |  |
| |                  |  | Path override [                       ]  |  |  |
| |                  |  |                                           |  |  |
| |                  |  |                    [Cancel]  [Apply] [OK] |  |  |
| +------------------+  +------------------------------------------+  |
+----------------------------------------------------------------------+
```
