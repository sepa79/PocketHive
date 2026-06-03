# PocketHive Plugin Developer Setup

## Status
`IN PROGRESS`

This document defines the setup experience for developers working on
`pockethive-mcp`, HiveMind-backed agent workflows, and the VS Code plugin.
For the shortest command-oriented path, start with
`tools/pockethive-mcp/README.md`.
For assistant-specific client configuration, see
`AI-ASSISTANT-SETUP.md`.

## Setup Goals

A new developer should be able to:

1. Install local dependencies.
2. Start or connect to a PocketHive stack through normal developer tooling.
3. Run `pockethive-mcp` against that stack.
4. Launch the VS Code plugin against the local MCP server.
5. Point HiveMind at the shared team memory service.
6. Run one doctor command that explains missing setup clearly.

The setup must stay explicit. Do not silently create local HiveMind stores,
switch endpoints, or start Docker/build tools from MCP.

Scenario bundles should be authored in a separate scenario-bundles repository,
not inside the PocketHive product repository. The in-repo `scenarios/bundles`
folder is acceptable only as a legacy/example fallback for smoke tests and
backwards-compatible local development. New team work should configure
`BUNDLES_ROOT` to an external checkout.

## Recommended Developer Profiles

| Profile | Purpose | PocketHive stack | HiveMind |
|---|---|---|---|
| `local` | Daily development | Local stack from `build-hive.sh` / Compose | Shared remote, if available |
| `remote-nft` | Team NFT environment | Remote PocketHive URL | Shared remote |
| `offline-docs` | Docs/spec work only | Not required | Shared remote, if available |

Profiles are configured in IDE settings or the developer's MCP client config.
They are not guessed by the MCP server.

## Required Local Tools

| Tool | Why |
|---|---|
| Node.js LTS | `tools/pockethive-mcp` and `vscode-pockethive` |
| npm | Dependency install and package scripts |
| Java 21 | PocketHive services |
| Docker/Compose | Local PocketHive stack, outside MCP |
| Scenario bundles repo checkout | Active `BUNDLES_ROOT` for authoring |
| VS Code | Extension development |
| GitHub MCP client config | Issue workflows, outside PocketHive MCP |
| HiveMind MCP client config | Shared agent memory |

## Environment Values

The IDE plugin passes these values to `pockethive-mcp` at process spawn:

| Variable | Required | Source |
|---|---|---|
| `POCKETHIVE_BASE_URL` | Yes | Active environment setting |
| `POCKETHIVE_ROOT` | Yes for validation | Repo path setting |
| `BUNDLES_ROOT` | Yes for bundle tools | Active bundles folder setting; should point to separate scenario-bundles repo |
| `RABBITMQ_DEFAULT_USER` | No | Active environment setting |
| `RABBITMQ_DEFAULT_PASS` | No | OS keychain |
| `TCP_MOCK_BASE_URL` | No | Active environment setting |
| `WIREMOCK_BASE_URL` | No | Active environment setting |
| `PH_BUNDLES_ROOTS` | No | Configured bundle roots |
| `PH_WORKFLOW_PROFILES_PATH` | No | Optional team `workflow-profiles.json`; invalid files fail closed |
| `PH_WORKFLOW_PERSISTENCE` | No | `local` by default; set `memory` to disable local workflow-session persistence |
| `PH_WORKFLOW_STORE_PATH` | No | Optional explicit local JSON workflow-session store |
| `PH_MCP_TOOL_NAME_MODE` | No | `underscore` by default for GitHub Copilot/OmniMCP compatibility; `legacy` or `both` only for explicit migration testing |
| `HIVEMIND_MCP_URL` / `HIVEMIND_BASE_URL` | No | Optional HiveMind MCP endpoint for workflow enrichment only |

GitHub tokens are not passed to `pockethive-mcp`. Configure a separate GitHub
MCP server with a fine-grained issue-only token.

## HiveMind Remote Setup

HiveMind is a shared team memory service, not a repo-local cache. Configure it
in the developer's global MCP client configuration.

Recommended values:

```text
HIVEMIND_BASE_URL=https://<team-hivemind-host>
HIVEMIND_PROJECT_ID=pockethive
HIVEMIND_WORKSPACE_PATH=<developer-local-pocket-hive-path>
```

`HIVEMIND_WORKSPACE_PATH` is intentionally developer-local. It should point to
the developer's checkout, while the project id remains `pockethive` for shared
team context.

If HiveMind is not configured, agents should say so and continue without
durable memory. They must not create `.hivemind/` in this repo.

## GitHub Issue MCP Setup

Use a separate GitHub MCP server for issue work. Restrict its token at the
GitHub token or GitHub App level:

| Permission | Recommendation |
|---|---|
| Issues | Read/write if agents may create/update issues |
| Metadata | Read |
| Contents | None |
| Pull requests | None unless explicitly needed elsewhere |
| Actions/secrets/admin | None |

PocketHive MCP may later expose a narrow `issue.export-evidence` tool only if
it adds PocketHive-specific value, such as creating a preformatted evidence
bundle for a separate GitHub MCP to post. It should not implement general
`github.*` tools.

## Doctor Command

Install MCP server dependencies first:

```bash
npm run mcp:setup
```

Use the developer-facing doctor command before blaming the IDE. It runs as a
local Node script under `tools/pockethive-mcp/`; it is not exposed as an MCP
tool.

Default command:

```bash
npm run mcp:doctor
```

The doctor first loads the same JSON MCP config shape used by assistants. Search
order is:

1. `--config <path>` or `PH_MCP_CONFIG`
2. `<repo>/mcp.json`
3. `<repo>/.amazonq/mcp.json`

It reads the `pockethive-bundles` server by default. Override that with
`--server <id>` or `PH_MCP_SERVER_ID` if a client uses a different server id.
Environment variables from the shell override values loaded from the config.

The doctor intentionally fails if the effective `BUNDLES_ROOT` is missing or
points at a non-directory. This is separate from dependency setup: `mcp:setup`
can pass on a clean machine before the bundles repo exists, while `mcp:doctor`
passes only after the bundles checkout is configured.

To test environment-only setup, bypass config loading explicitly:

```bash
BUNDLES_ROOT=/path/to/pockethive-scenario-bundles \
PH_BUNDLES_ROOTS='["/path/to/pockethive-scenario-bundles"]' \
npm run mcp:doctor -- --no-config
```

To check a specific assistant config:

```bash
npm run mcp:doctor -- --config .amazonq/mcp.json
```

Suggested checks:

| Check | Failure message should include |
|---|---|
| Node.js version | Required version and installed version |
| MCP config source | Loaded config path, server id, malformed JSON, disabled server, missing URL, or missing command/args in stdio mode |
| MCP dependencies | Whether dependencies were current or installed from lockfile |
| MCP server startup | Command used and first startup error |
| MCP input schemas | Tool/schema name when an array schema is missing `items` |
| Bundle root | Path and whether bundles are found |
| Bundle roots list | Invalid `PH_BUNDLES_ROOTS` JSON or missing directories |
| Workflow source roots | Invalid `PH_WORKFLOW_SOURCE_ROOTS` JSON or missing directories |
| Workflow profiles | Invalid custom workflow profile config |
| Workflow persistence | Local JSON store path when persistence is enabled |

The doctor may run shell commands because it is developer tooling. The MCP
server must not expose doctor as an MCP tool.

## First-Run Flow

1. Developer installs dependencies.
2. Developer configures IDE plugin settings:
   - PocketHive repo path
   - bundles root from a separate scenario-bundles checkout
   - environment base URL
   - Rabbit credentials if needed
3. Developer configures global HiveMind MCP remote.
4. Developer configures external GitHub MCP with issue-only token if needed.
5. Developer runs doctor.
6. Developer launches the VS Code extension host.

## Verification Commands

From the PocketHive repo root:

```bash
npm run mcp:test
npm run mcp:workflow-acceptance
npm run mcp:agentic-evals
```

To verify against a running local PocketHive stack:

```bash
PH_WORKFLOW_ACCEPTANCE_LIVE=1 \
POCKETHIVE_BASE_URL=http://localhost:8088 \
POCKETHIVE_AUTH_USERNAME=local-admin \
npm --prefix tools/pockethive-mcp run acceptance:workflow:live
```

The live acceptance run creates a unique live workflow bundle/swarm, verifies
it with `proofMode=strict` and `includeTapSample=true`, then removes both the
swarm and uploaded Scenario Manager bundle in a teardown block. If the process
is interrupted, remove the created swarm through `swarm_remove` or:

```bash
POCKETHIVE_AUTH_USERNAME=local-admin \
node tools/mcp-orchestrator-debug/client.mjs remove-swarm <swarmId>
```

Remove the uploaded live bundle with:

```bash
TOKEN=$(curl -s \
  -H "content-type: application/json" \
  -d '{"username":"local-admin"}' \
  "http://localhost:8088/auth-service/api/auth/dev/login" | jq -r .accessToken)

curl -X DELETE \
  -H "Authorization: Bearer ${TOKEN}" \
  "http://localhost:8088/scenario-manager/scenarios/<bundleId>"
```

## Definition Of Ready

The setup is ready when a new developer can diagnose every missing dependency
or endpoint from one doctor output, without reading implementation code and
without the MCP server taking over local devops tasks.
