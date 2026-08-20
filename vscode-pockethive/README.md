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
owner responses. Hive restores context-valid Start/Stop and guarded Remove,
opens authoritative swarm details and Debug with the exact swarm, supports
exact Start all and Stop all batch actions when eligible swarms are visible,
and shows one authoritative collapsible run history at a time. Selecting a run
opens Journal with its exact swarm and run IDs. Buzz and Journal request ten
recent events for the narrow Side Bar and offer local search, time, kind, and
severity filters. Scenarios are searchable compact disclosures that can open
the deployed summary, deployed `scenario.yaml`, and an exact deployed schema or
template path in read-only previews. Debug keeps the canonical actions grouped
as Runtime, Messaging, Definition, and Maintenance.

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

The base companion session (`pockethive:mcp:discover` and
`pockethive:mcp:read`) renews on demand when it has no more than 60 seconds to
live. Renewal is single-flight: concurrent tool calls share one refresh, the
Auth Service rotates the opaque refresh token, and the extension validates a
candidate MCP connection before replacing the current connection. The current
workspace, active tab, and last good owner data remain visible while renewal is
in progress, so renewal does not flash, strobe, or return the user to the
Environments page. A failed refresh or candidate connection leaves the user in
the workspace with one explicit session message and a retry action.

Privileged tool scopes use separate short-lived sessions and receive no refresh
token. The Account menu shows the verified principal and offers the action that
matches the current state: **Sign in**, **Retry**, or **Sign out**. Sign-out
revokes both current base-session tokens, clears the matching Secret Storage
record, closes the MCP transport, and returns to Environments only after local
session teardown. It does not claim a remote logout if revocation could not be
confirmed.

Browser sign-in and consent are rendered by Auth Service with a responsive
PocketHive theme, the canonical logo, explicit client/resource/scope context,
and accessible form controls. Styling does not change OAuth request fields,
PKCE, exact redirect/resource validation, or consent behavior.

Read-only scenario inspection follows the MCP contract exactly. The extension
does not infer schema or template paths from bundle content and does not edit
the deployed copy. Git remains the source of truth for authoring.

The Scenarios tab accepts a directory from an accessible Git worktree. It
packages only the exact committed tree, including safe mixed files such as
shell, SQL, YAML/YML, JSON, CSV, and Markdown. Validation and publication use
the MCP ticket upload flow. `CREATE` or `REPLACE` is always an explicit user
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

The Activity Bar uses a 24 px-optimized PocketHive hexagon silhouette. The
shared header on both companion pages uses the full-colour mark and colours the
`Hive` suffix with a generated canonical brand token. Its compact heading and
subtitle rhythm follows the source logo. All three derivatives come
deterministically from `ui-v2/public/logo.svg`; run
`npm run assets:check` to detect drift.

For the server and agent contract, see `docs/mcp/README.md` and
`docs/todo/pockethive-mcp-java-migration.md`.
