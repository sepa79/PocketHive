# Legacy PocketHive plugin design

## Status

`SUPERSEDED BY THE JAVA MCP AND HTML VS CODE COMPANION`

The documents in this directory describe the removed Node/stdio MCP and the
former multi-Tree-View IDE design. They are retained only as migration history
for this branch and must not be used as current configuration, implementation,
or operational guidance.

Current sources are:

- `docs/mcp/README.md` for MCP connection and agent usage;
- `docs/todo/pockethive-mcp-java-migration.md` for the implemented architecture
  and acceptance record;
- `docs/architecture/AUTH_SERVICE_API_SPEC.md` for OAuth;
- `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` for bundle ownership; and
- `vscode-pockethive/README.md` for the VS Code extension.

There is no supported `tools/pockethive-mcp`, stdio mode, local MCP process,
bundle-root setting, transport selector, dotted tool alias, or direct backend
configuration after cutover. Historical details remain available from Git
history.
