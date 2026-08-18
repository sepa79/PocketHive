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

Profiles are stored in VS Code global state, the active profile is
workspace-local, and OAuth session material is stored through VS Code Secret
Storage. A saved profile is never displayed as connected until it is
revalidated.

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
npm run mutation
npm run package
```

`npm run package` compiles, reruns all tests, validates the atomic cutover and
logo provenance, checks the VSIX allow-list, and creates
`pockethive-vscode-<version>.vsix`. The package must not contain source, tests,
Node modules, mutation reports, legacy Tree Views, or local MCP launch code.

The Activity Bar and header marks are deterministic derivatives of the
canonical `ui-v2/public/logo.svg`; run `npm run assets:check` to detect drift.

For the server and agent contract, see `docs/mcp/README.md` and
`docs/todo/pockethive-mcp-java-migration.md`.
