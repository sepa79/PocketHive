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

The VS Code companion uses one consented, renewable environment session rather
than a browser grant per tool. It requests discover, read, operate, author, and
publish once; Auth Service limits the granted token to the principal's existing
PocketHive permissions and never includes cleanup. Access tokens remain
short-lived, refresh tokens rotate, and commands do not initiate browser auth.

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

Authenticated IDE clients may also read
`pockethive://environment/health`. It is the canonical bounded projection of
the services reached through this environment's declared PocketHive ingress.
Each row contains the stable service ID, display name, public endpoint, exact
`HEALTHY` or `UNAVAILABLE` state, and observation time. The aggregate state is
`HEALTHY`, `DEGRADED`, or `UNAVAILABLE`. A failed target remains an explicit
failed row; the MCP never probes another host, port, path, or adapter.

The first projection contains PocketHive UI, Orchestrator, Scenario Manager,
Network Proxy Manager, WireMock, TCP Mock, and Grafana. Their checks use only
the single configured UI ingress and its explicit public routes. The MCP owns
normalisation of those observations, while each service still owns its health
response. IDE clients must not duplicate these probes or infer health from
ordinary tool results.

The ordered target catalogue and response contracts are bound once from the
validated `pockethive.mcp.environment-health.targets` configuration. Probe
connect and read timeouts use the required positive ISO-8601 duration
`PH_MCP_ENVIRONMENT_HEALTH_PROBE_TIMEOUT`; no application-layer default or
alternate target is permitted.

The server publishes one immutable, scope-filtered tool list and connected,
versioned skills for every tool. Owner services remain authoritative for live
state and operations. HiveGate remains authoritative for approval, execution
tickets, and governed evidence. HiveMind is optional agent-host memory and is
not an MCP dependency.

The QA authoring skill asks for explicit requirements. It does not infer
missing answers. One agent session may hold multiple isolated workflows, while
deployment, swarm, diagnostics, and other direct tools remain independently
callable without wizard state.

`swarm_wait_ready` performs one non-blocking observation of the canonical
Orchestrator swarm projection. It reports ready only when the top-level
`controllerState` is `READY`, `workloadState` is `STOPPED`,
`observationStale` is false, and `observation.startupReady` is true. Its
`totals.desired` and `totals.healthy` values are derived respectively from
`observation.expectedWorkers` and fresh (`stale=false`) entries in
`observation.workers`. Clients own their explicit finite polling policy. The
MCP does not parse a second raw control-plane envelope or infer readiness from
health alone.

Read-only scenario inspection also stays on the MCP surface. IDE clients may
inspect deployed bundle catalogue metadata, bundle trees, individual deployed
files, and bundle-local SUT descriptors through MCP tools backed by Scenario
Manager. The deployed `scenario.yaml`, schema, and template preview tools
return preview text, while bundle-workspace file inspection returns the
structured workspace file payload. IDE clients must not call Scenario Manager
workspace endpoints directly from the client.

## Scenario Bundles

Git is the source of truth for editable bundles and their history. The client
packages one exact committed bundle directory, preserving every safe regular
file such as `.yaml`, `.yml`, `.sql`, `.sh`, and `.md`. It then:

1. requests a validation upload ticket;
2. streams the bounded ZIP through `/mcp/uploads/<ticket>`;
3. receives Scenario Manager validation evidence plus the exact descriptor
   `scenarioId` and `scenarioName`;
4. asks the user for explicit `CREATE` or `REPLACE` intent; and
5. uploads the identical bytes with a publication ticket.

The MCP compares manifests and SHA-256 digests before calling Scenario Manager.
Invalid, incomplete, stale, or mismatched input fails explicitly. There is no
create/replace fallback, server-side Git checkout, bundle-root scan, or archive
execution.

IDE repository views may use the validation receipt's owner-reported identity
for their publication confirmation. A conflicting `scenarioId` requires one
explicit choice: `REPLACE`, or edit and commit a renamed source bundle before
new validation. The MCP and IDE never rewrite retained ZIP bytes or silently
suffix an identity.

## Owning documents

- Java migration, tool, workflow, security, and acceptance specification:
  `docs/todo/pockethive-mcp-java-migration.md`
- Node tool disposition ledger: `docs/mcp/NODE_TOOL_MIGRATION_LEDGER.md`
- Auth and OAuth contract: `docs/architecture/AUTH_SERVICE_API_SPEC.md`
- Scenario Manager bundle contract:
  `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md`
- VS Code client and package usage: `vscode-pockethive/README.md`
