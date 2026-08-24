# PocketHive VS Code extension

The extension is a narrow HTML Side Bar companion for PocketHive MCP. It does
not spawn a local MCP process, read direct PocketHive service URLs, or persist
Scenario Bundle roots.

## Use

1. Open the PocketHive Activity Bar view.
2. Add an environment name and exact public MCP URL, such as
   `http://localhost:8088/mcp` for the explicit local-loopback profile.
3. Choose **Connect**. The extension validates the endpoint, opens the
   authorization-code flow with PKCE S256, then tests the expected MCP server.
4. Choose **Save & open** only after authentication and connection testing both
   succeed.
5. Use the Hive, Buzz, Journal, Scenarios, and Debug tabs for that environment.

The workspace presents compact bounded rows and empty states rather than raw
owner responses. Hive groups each swarm into one lifecycle-accented operational
surface, keeps its status beside its identity, and exposes run history through a
full-width disclosure owned by that swarm. Workers own a full-width disclosure
row above the swarm actions; expanding it inserts one inline row per exact bee,
with Inspect and Logs actions, and never opens an overlapping menu. It restores
context-valid Start/Stop,
enables guarded Remove only for a fresh ready stopped swarm, renders the exact
Orchestrator bee summaries as a worker list, opens Debug with the exact swarm,
and opens the exact swarm in PocketHive Web UI. A worker Inspect or Logs action
resolves that exact bee instance through `runtime_list_workers` before invoking
the matching diagnostic tool; no runtime identifier is inferred. It supports exact Start all and
Stop all batch actions when eligible swarms are visible, and shows one
authoritative collapsible run history at a time.
Selecting a run opens Journal with its exact swarm and run IDs. Buzz and Journal
request ten
recent events for the narrow Side Bar and offer local search, time, kind, and
severity filters; time, kind, and severity remain collapsed until requested.
Scenarios separates `Deployed` and `Repository` views. Deployed scenarios are
searchable compact disclosures with a collapsed exact-folder filter. Overview
presents Description, Controller, and Bees as full-width rows; Files renders the
deployed bundle as a nested hierarchy and is the single place to open exact
read-only file previews. Repository discovery reads only committed `HEAD` from
trusted Git workspace folders, groups candidates by repository, and reuses the
existing validation/publication flow without parsing or executing bundle files.
Each candidate is bound to its discovered commit; withdrawn workspace trust or
a changed `HEAD` fails explicitly and requires a refresh. Repository cards are
collapsed by default and every card can return to that compact state.
Debug presents one target-first diagnostic workspace: exact worker discovery
and selection, compact Logs/Inspect/Version tabs with adjacent evidence,
a two-column swarm-tool matrix, and a visibly plan-only Cleanup plan. Logs are
the selected container's bounded Docker stdout/stderr. Inspect is the bounded
Orchestrator inspect projection also used by `ui-v2`. Version is the runtime
image and label projection returned by Orchestrator: it prefers the
`pockethive.version` label and otherwise uses the image tag, while preserving
the image and immutable digest. Cleanup execution is shown locked because it
requires HiveGate approval and is not called directly by the extension.

Every workspace uses the same icon-led tab and action language plus one fixed,
expandable environment rail. The rail owns the environment identity, uses the
canonical PocketHive hexagon, and consumes
`pockethive://environment/health` for PocketHive UI, Orchestrator, Scenario
Manager, Network Proxy Manager, WireMock, TCP Mock, and Grafana. The extension
does not probe those services itself. The account icon opens an anchored overlay
for the verified principal and current session action; it does not reserve
drawer space. Known service IDs have explicit local icons; an unknown service ID
uses a neutral globe without inferring its type from a name or endpoint.

Each owner-data field is bounded independently, so an oversized response becomes
an explicit field error without replacing navigation, profiles, or connection
state. Journal requires an exact swarm selection. Its top tabs support arrow,
Home, and End keys and keep the selected tab visible at narrow Side Bar widths.

Profiles are stored in VS Code global state, the active profile is
workspace-local, and OAuth session material is stored through VS Code Secret
Storage. A saved profile is never displayed as connected until it is
revalidated. If VS Code reconstructs the Side Bar webview, the extension host
reattaches only the newest view and restores its current model; disposal of an
older view cannot detach or freeze its replacement.

The companion authenticates once per environment and requests the exact set of
capabilities its UI exposes: discover, read, operate, author, and publish. Auth
Service narrows that request to the signed-in principal's existing grants before
consent; cleanup is never requested. The resulting exact scope set and rotating
refresh token are stored only in VS Code Secret Storage. No tab or command opens
a command-specific authorization flow.

The session schedules renewal before its 15-minute access token expires and
also checks on demand before every MCP action. Its rotating refresh grant
defaults to 30 days, subject to the environment's Auth Service policy. Renewal
is single-flight:
concurrent actions share one refresh, Auth Service rotates the opaque refresh
token, and the extension validates a candidate MCP connection before replacing
the current connection. The current workspace, active tab, and last good owner
data remain visible while renewal is in progress, so renewal does not flash,
strobe, or return the user to the Environments page. Transient refresh failures
retain the grant for a later bounded retry; definitive refresh rejection leaves
one explicit sign-in action and never launches the browser from a command.

The Account menu shows the verified principal and offers the action that
matches the current state: **Sign in**, **Retry**, or **Sign out**. Sign-out
revokes both current companion-session tokens, clears the matching Secret Storage
record, closes the MCP transport, and returns to Environments only after local
session teardown. It does not claim a remote logout if revocation could not be
confirmed.

Browser sign-in and consent are rendered by Auth Service with a responsive
PocketHive theme, the canonical logo, explicit client/resource/scope context,
and accessible form controls. The loopback callback landing page shown after
browser approval, cancellation, or failure uses an exact build-generated data
URI of that canonical logo and the same PocketHive visual language. The OAuth
hand-off therefore remains one coherent product flow without a remote asset,
CSS-drawn substitute, or fallback. Styling does not change OAuth request
fields, PKCE, exact redirect/resource validation, or consent behavior.

Read-only scenario inspection follows the MCP contract exactly. The extension
does not infer schema or template paths from bundle content and does not edit
the deployed copy. Git remains the source of truth for authoring.

Repository Edit actions open the exact trusted worktree file through a `file:`
URI. The extension verifies that the file is writable before opening it and
fails with `REPOSITORY_SCENARIO_FILE_NOT_WRITABLE` when host ownership or mode
blocks saving; it never changes host permissions or opens a read-only Git object
as a fallback.

The Scenarios tab discovers canonical `scenarios/**/scenario.yaml` candidates
from each trusted workspace Git repository. Discovery is bounded, de-duplicates
multi-root folders in the same repository, reports non-Git or failed folders
explicitly, and sends opaque candidate IDs rather than filesystem paths to the
webview. The separate **Choose committed folder** action remains available.
Both paths package only the exact committed tree, including safe mixed files
such as shell, SQL, YAML/YML, JSON, CSV, and Markdown. Validation and publication
use the MCP ticket upload flow. `CREATE` or `REPLACE` is always an explicit user
decision; dirty workspace bytes and historical source require an explicit Git
commit selection outside the extension.

## Develop and verify

The extension remains TypeScript because that is the VS Code extension-host
platform boundary. Dependencies are pinned and locked.

```bash
cd vscode-pockethive
npm ci --ignore-scripts
npm test
npm run ui:check
npm run mutation
npm run package
```

`npm run ui:check` drives the complete local-loopback add/connect/open flow
through `http://localhost:8088/mcp`, including browser OAuth, live MCP reads,
exact lifecycle/history/debug messages, run-to-Journal navigation, event and
scenario filters, grouped diagnostics, narrow-width geometry, keyboard tabs,
accessibility analysis, responsive Auth Service pages, refresh-token rotation,
retired-token replay rejection, sign-out revocation, and screenshots.
It therefore requires the local PocketHive stack and a Playwright Chromium
installation.

`npm run package` compiles, reruns all tests, validates the atomic cutover and
logo provenance, checks the VSIX allow-list, and creates
`pockethive-vscode-<version>.vsix`. The package must not contain source, tests,
Node modules, mutation reports, legacy Tree Views, or local MCP launch code.

The workspace uses one compact mobile-width shell: `← Environments`, the five
top tabs, and one fixed footer that owns the exact environment name, service
health, PocketHive mark, and account menu. It deliberately has no global or
duplicated environment header. Hive rows retain their context-valid Start or Stop action;
historical runs only open their exact Journal evidence. Buzz and Journal keep
event summaries to one line, place time/kind/severity behind a collapsed Filters
control, and use an accessible searchable listbox for exact-swarm choices where
a target is required. A typed value outside the current bounded result fails
explicitly and is never sent as another target.

Web UI navigation resolves only the exact `pockethive-ui` endpoint supplied by
the MCP environment-health resource. Hive opens the exact swarm view, Journal
opens the exact selected swarm/run, and Buzz opens only its top-level page
because the Web UI does not expose a record-level Buzz route. The extension
never derives the Web UI endpoint from the configured MCP URL.

The Activity Bar uses a 24 px-optimized PocketHive hexagon silhouette. The
full-colour mark remains available for compact swarm identity, the generated
canonical Hive colour marks the active workspace tab, and the callback data URI
uses the exact canonical SVG bytes. All four derivatives come deterministically
from `ui-v2/public/logo.svg`; run `npm run assets:check` to detect drift.

Interface icons use the official VS Code Codicon font from the exact pinned
`@vscode/codicons` development package. The build copies the font, stylesheet,
and attribution into `resources/`; the webview loads those local packaged files
under its Content Security Policy, and `npm run assets:check` detects drift.

For the server and agent contract, see `docs/mcp/README.md` and
`docs/todo/pockethive-mcp-java-migration.md`.
