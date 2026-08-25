# PocketHive MCP

PocketHive MCP is the agent-facing facade for one PocketHive environment. The
canonical server is the Java 21 `pockethive-mcp-service`; the removed Node
server and stdio transport are not supported compatibility paths.

## Connect

For the complete local stack, companion installation, sign-in, and verification
journey, follow the canonical
[local MCP and VS Code quick start](../USAGE.md#local-mcp-and-vs-code-quick-start).
This section owns the MCP connection contract and agent-client configuration.

Clients connect through the PocketHive public ingress:

- local development: `http://localhost:8088/mcp`;
- deployed environment: `https://<environment>/mcp`.

The endpoint is exact. Clients must not probe another scheme, port, path, or
transport. OAuth protected-resource and authorization-server metadata describe
the standard authorization-code flow with PKCE S256. The first-party VS Code
companion is pre-registered; other conforming MCP clients use the advertised
RFC 7591 dynamic-registration endpoint. No client product receives a private
authentication path. Tokens are scoped, audience-bound, and must never be
forwarded to PocketHive owner services.

For issuer `https://<environment>/auth-service`, clients discover authorization
metadata at the RFC 8414 path
`https://<environment>/.well-known/oauth-authorization-server/auth-service`.
The ingress transparently routes that request to the one Auth Service metadata
owner. A request for a well-known path must never fall through to the UI SPA.

Interactive clients use one consented, renewable environment session rather
than a browser grant per tool. They declare a bounded subset of discover, read,
operate, author, and publish; Auth Service limits the granted token to the
principal's existing PocketHive permissions and never includes cleanup. Access
tokens remain short-lived, refresh tokens rotate, and commands do not initiate
browser auth. Registration alone is neither authentication nor a support
claim; the client still completes browser authorization and the relevant
capability conformance checks.

Dynamic client registration has one bounded inactivity lifetime. Successful
use renews an active registration; inactive registrations expire after a
duration configured to be strictly longer than refresh-token lifetime. This
keeps long-running native MCP clients seamless without making abandoned client
registrations permanent.

Both OAuth protected-resource discovery and authorization-server discovery
publish that same interactive scope set. Governed cleanup is intentionally not
advertised to direct MCP clients. This keeps discovery, RFC 7591 registration,
authorization, and refresh on one canonical contract for any conforming MCP
client, without product-specific client handling.

For a local build and targeted redeploy:

```bash
./build-hive.sh --quick --service auth-service --service pockethive-mcp
```

For HiveForge, deploy the same published `pockethive-mcp` and `auth-service`
images through the governed workflow in `docs/HIVEFORGE.md`. Configure each
agent or IDE profile with the public MCP URL; neither `build-hive.sh` nor the VS
Code extension writes user MCP-client configuration.

Use the client's native Streamable HTTP configuration and OAuth support; an
NPM proxy is neither required nor supported. For example, VS Code/Copilot uses
`{"type":"http","url":"http://localhost:8088/mcp"}` inside its `servers`
map, while Amazon Q uses the same object inside `mcpServers`. On the first
connection each conforming client follows protected-resource discovery,
authorization-server discovery, dynamic public-client registration, PKCE, and
browser consent. PocketHive contains no Codex-, Amazon Q-, Copilot-, or
companion-specific branch in that flow.

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

`pockethive://capabilities/current` publishes `qaAnswerCaptureModes` so a client
or agent can select `MCP_FORM`, `AGENT_MEDIATED`, or `COMPACT_REVIEW` explicitly
before collecting an answer. Capability discovery never causes the server to
switch modes.

The ordered target catalogue and response contracts are bound once from the
validated `pockethive.mcp.environment-health.targets` configuration. Probe
connect and read timeouts use the required positive ISO-8601 duration
`PH_MCP_ENVIRONMENT_HEALTH_PROBE_TIMEOUT`; no application-layer default or
alternate target is permitted.

The server publishes one immutable complete tool list. Invocation still enforces
the required scope from the canonical descriptor, while catalogue resources may
present a principal-scoped projection. Connected, versioned skills cover every
tool. Owner services remain authoritative for live state and operations.
HiveGate remains authoritative for approval, execution tickets, and governed
evidence. HiveMind is optional agent-host memory and is not an MCP dependency.

Each canonical tool descriptor owns both its closed input schema and its output
schema. Successful Java values are converted once to JSON-native structured
content and validated against that descriptor before the SDK returns them.
Known application, workflow, upload, and owner refusals return
`CallToolResult(isError=true)` with a stable `code` and safe `message`.
Unexpected failures remain protocol failures with a correlation ID and no
internal detail in the client response. Clients must not parse server log text
or retry a mutating tool merely because a protocol result was lost.

The QA authoring skill asks for explicit requirements. It does not infer
missing answers. It supports three explicitly selected capture modes over the
same canonical server-owned topics:

- `scenario_workflow_answer` uses visible native MCP form elicitation and fails
  `ELICITATION_CAPABILITY_REQUIRED` when that capability is unavailable;
- `scenario_workflow_question` returns the exact question, current revision,
  question ID, response schema, and digest for a chat-mediated interview. The
  agent presents that question unchanged, waits for new user input, and then
  calls `scenario_workflow_answer_submit` with the explicit response and
  unchanged evidence;
- `scenario_workflow_review_prepare` validates and renders one deterministic
  all-topic candidate brief from a named, SHA-256-digested source. The agent
  presents the returned review unchanged and waits for explicit acceptance.
  `scenario_workflow_review_submit` then verifies the same revision, review
  contract, source, answer set, and digest before recording every topic in one
  atomic workflow mutation.

The compact review is the normal choice for a complete narrative; the guided
question modes remain available when the user chooses them or material gaps
remain. The server never switches modes automatically. A rejected review,
declined/cancelled form, incomplete answer set, or stale/tampered evidence
causes no mutation. One agent session may hold multiple isolated workflows,
while deployment, swarm, diagnostics, and other direct tools remain
independently callable without wizard state.

`swarm_wait_ready` performs one non-blocking observation of the canonical
Orchestrator swarm projection. It reports ready only when the top-level
`controllerState` is `READY`, `workloadState` is `STOPPED`,
`observationStale` is false, and `observation.startupReady` is true. Its
`totals.desired` and `totals.healthy` values are derived respectively from
`observation.expectedWorkers` and fresh (`stale=false`) entries in
`observation.workers`. Clients own their explicit finite polling policy. The
MCP does not parse a second raw control-plane envelope or infer readiness from
health alone.

Before the first Controller observation, the canonical Orchestrator projection
has `controllerState=PROVISIONING`, `workloadState=UNAVAILABLE`,
`observationStale=true`, and an empty `observation` object. `swarm_wait_ready` maps only
that exact pre-observation state to `ready=false` with zero desired and healthy
workers. Missing, malformed, or contradictory owner fields still fail
explicitly with `SWARM_STATUS_INVALID`.

Temporary message inspection uses the Orchestrator-owned debug-tap contract
without changing its meaning. `debug_tap` requires the exact swarm, role,
direction, I/O name, positive item cap, and positive `ttlSeconds` on every
call. `debug_tap_read` accepts an optional non-negative integer `drain`: omit
it to read up to the tap's item cap, use `0` for metadata only, or use a
positive count to drain at most that many samples. A Boolean drain value is
invalid and never maps to an owner default. Clients close each tap after use.

Runtime diagnosis starts with `runtime_assess_swarm`. Orchestrator alone compares
the registered swarm and run, cached control-plane state, exact ownership
manifest, labelled compute inventory, and exact RabbitMQ topology. The result is
`CONSISTENT`, `DRIFTED`, or `INCOMPLETE` with typed checks and differences.
`runtime_diff_swarm_runtime`, `runtime_control_plane_status`, and
`runtime_manifest_validate` remain discoverable compatibility names over that
same owner response. The companion presents the canonical assessment once.

`scenario_contracts_get` accepts no selectors and returns only the authoring
contract and fingerprint. `scenario_capabilities_get` accepts an empty input for
the characterised complete read, or exactly one of `all=true`, `imageName`, and
`imageDigest`; unsupported, false, or conflicting selectors fail before an
owner call. Component config preview resolves the exact currently observed
worker and performs only the documented deterministic shallow merge. Update
continues to use the existing Orchestrator endpoint and sends only its owner DTO.
The capability result schema preserves Scenario Manager's existing response:
the complete `all=true` read is an array, while an exact image-name or digest
read is one manifest object.

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
2. streams the bounded ZIP through `/mcp/uploads/<ticket>` using exactly one
   upload authentication mode: the existing OAuth Bearer session, or the
   ticket's short-lived `PocketHive-Upload-Capability` header;
3. receives Scenario Manager validation evidence plus the exact descriptor
   `scenarioId` and `scenarioName`;
4. asks the user for explicit `CREATE` or `REPLACE` intent; and
5. uploads the identical bytes with a publication ticket.

The MCP compares manifests and SHA-256 digests before calling Scenario Manager.
Invalid, incomplete, stale, or mismatched input fails explicitly. There is no
create/replace fallback, server-side Git checkout, bundle-root scan, or archive
execution.

The prepare result returns the opaque upload capability exactly once. It
authorises only one binary `PUT` to that exact ticket before its expiry; it is
not an MCP access token and cannot call tools or another upload ticket. The MCP
persists only its SHA-256 digest. Capability values are never accepted in URLs,
logs, or archive content. Supplying both Bearer and capability authentication
is ambiguous and fails explicitly. Clients that cannot stream ZIP bytes with
one of these two modes fail `CLIENT_CAPABILITY_REQUIRED`; inline/base64 archive
arguments are not supported. An upload-authentication `401` advertises only the
`PocketHiveUploadCapability` challenge; it does not expose or request a reusable
credential.

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
