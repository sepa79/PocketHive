# PocketHive MCP

PocketHive MCP is the agent-facing facade for one PocketHive environment. The
canonical server is the Java 21 `pockethive-mcp-service`; the removed Node
server and stdio transport are not supported compatibility paths.

## Connect

Clients connect through the PocketHive public ingress:

- local development: `http://localhost:8088/mcp`;
- deployed environment: `https://<environment>/mcp`.

The endpoint is exact. Clients must not probe another scheme, port, path, or
transport. OAuth protected-resource and authorization-server metadata describe
the pre-registered authorization-code flow with PKCE S256. Tokens are scoped,
audience-bound, and must never be forwarded to PocketHive owner services.

For a local build and targeted redeploy:

```bash
./build-hive.sh --quick --service auth-service --service pockethive-mcp
```

For HiveForge, deploy the same published `pockethive-mcp` and `auth-service`
images through the governed workflow in `docs/HIVEFORGE.md`. Configure each
agent or IDE profile with the public MCP URL; neither `build-hive.sh` nor the VS
Code extension writes user MCP-client configuration.

## Agent contract

After `initialize`, read these resources before operating PocketHive:

- `pockethive://knowledge/overview`;
- `pockethive://capabilities/current`;
- `pockethive://tools/catalogue`;
- `pockethive://skills/catalogue`.

The server publishes one immutable, scope-filtered tool list and connected,
versioned skills for every tool. Owner services remain authoritative for live
state and operations. HiveGate remains authoritative for approval, execution
tickets, and governed evidence. HiveMind is optional agent-host memory and is
not an MCP dependency.

The QA authoring skill asks for explicit requirements. It does not infer
missing answers. One agent session may hold multiple isolated workflows, while
deployment, swarm, diagnostics, and other direct tools remain independently
callable without wizard state.

## Scenario Bundles

Git is the source of truth for editable bundles and their history. The client
packages one exact committed bundle directory, preserving every safe regular
file such as `.yaml`, `.yml`, `.sql`, `.sh`, and `.md`. It then:

1. requests a validation upload ticket;
2. streams the bounded ZIP through `/mcp/uploads/<ticket>`;
3. receives Scenario Manager validation evidence;
4. asks the user for explicit `CREATE` or `REPLACE` intent; and
5. uploads the identical bytes with a publication ticket.

The MCP compares manifests and SHA-256 digests before calling Scenario Manager.
Invalid, incomplete, stale, or mismatched input fails explicitly. There is no
create/replace fallback, server-side Git checkout, bundle-root scan, or archive
execution.

## Owning documents

- Java migration, tool, workflow, security, and acceptance specification:
  `docs/todo/pockethive-mcp-java-migration.md`
- Node tool disposition ledger: `docs/mcp/NODE_TOOL_MIGRATION_LEDGER.md`
- Auth and OAuth contract: `docs/architecture/AUTH_SERVICE_API_SPEC.md`
- Scenario Manager bundle contract:
  `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md`
- VS Code client and package usage: `vscode-pockethive/README.md`
