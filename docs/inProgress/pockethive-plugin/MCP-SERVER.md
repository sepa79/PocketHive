# PocketHive MCP Server — Migration & Enhancement Spec

## Status
`IN PROGRESS`

## Overview

The MCP server currently lives in the bundles repo at
`tools/mcp-server/server.mjs`. This spec covers:

1. Migration into the PocketHive repo at `tools/pockethive-mcp/`
2. Decoupling from the bundles repo (BUNDLES_ROOT becomes configurable)
3. New tools for context switching and environment management
4. Publishing as `@pockethive/mcp-server` npm package
5. Adding HTTP/SSE transport alongside existing stdio

## Source location

```
Current:  <bundles-repo>/tools/mcp-server/server.mjs
Target:   PocketHiveClean/tools/pockethive-mcp/server.mjs
```

## What changes

### REPO_ROOT -> BUNDLES_ROOT

Currently `REPO_ROOT` is derived from the server file's own location and
assumed to be the bundles repo root. After migration the server lives in
the PocketHive repo, so bundles are elsewhere.

```javascript
// Before
const REPO_ROOT = resolve(__dirname, '../..');  // always bundles repo

// After
const BUNDLES_ROOT = process.env.BUNDLES_ROOT || '';  // injected by plugin
const POCKETHIVE_ROOT = resolve(__dirname, '../..');  // now the PH repo itself
```

All tools that reference `REPO_ROOT` for bundle operations switch to
`BUNDLES_ROOT`. All tools that reference `POCKETHIVE_ROOT` for validation
use the server's own location.

### .env loading removed

The server no longer reads a `.env` file on startup. All config comes
from `process.env` injected by the IDE plugin at spawn time. When run
standalone (CLI / Docker), users set env vars directly.

A `--env-file` CLI flag is supported for standalone use:

```bash
node server.mjs --env-file /path/to/.env
```

### Dual transport

```javascript
// stdio (default — IDE plugin mode)
if (!process.env.PH_MCP_HTTP_PORT) {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

// HTTP/SSE (team/Docker mode)
if (process.env.PH_MCP_HTTP_PORT) {
  const transport = new HttpSseServerTransport({
    port: parseInt(process.env.PH_MCP_HTTP_PORT),
  });
  await server.connect(transport);
}
```

### package.json

```json
{
  "name": "@pockethive/mcp-server",
  "version": "0.15.15",
  "description": "PocketHive MCP server — full lifecycle tools for scenario authoring and swarm management",
  "bin": { "pockethive-mcp": "./server.mjs" },
  "files": ["server.mjs", "start.cjs", "dev-tools/", "dist/apps/"],
  "publishConfig": { "access": "public" },
  "scripts": {
    "build:apps": "cd apps && npm install && npm run build",
    "start": "node server.mjs",
    "start:http": "PH_MCP_HTTP_PORT=3100 node server.mjs"
  }
}
```

Version tracks PocketHive releases (currently `0.15.15`).

The npm package includes the pre-built MCP App HTML files under `dist/apps/`
so that HTTP-mode consumers get the interactive UIs without a separate build
step. The `apps/` source directory is not published — only the built output.

## Existing tools (unchanged behaviour)

All tools from the current `server.mjs` are migrated as-is:

### Bundle management
- `bundle.list` — lists bundles in `BUNDLES_ROOT/bundles/`
- `bundle.read` — reads a file from a bundle
- `bundle.validate` — async offline validation via scenario-templating-check
- `bundle.validate.result` — polls validation job result

### Scenario lifecycle
- `scenario.sync` — docker cp to running scenario-manager (local only)
- `scenario.deploy` — HTTP zip upload (local + remote)
- `scenario.list` — lists loaded scenarios
- `scenario.get` — gets a specific scenario

### Swarm lifecycle
- `swarm.list`, `swarm.get`, `swarm.create`, `swarm.start`
- `swarm.wait-ready`, `swarm.stop`, `swarm.remove`

### Debugging
- `debug.queues`, `debug.tap`, `debug.tap.read`, `debug.tap.close`
- `debug.journal`, `debug.docker-logs`, `debug.config-update`
- `debug.prometheus`

### Mock servers
- `mock.wiremock.list/add/reset/requests/unmatched`
- `mock.tcp.list/add/reset/requests/unmatched/scenarios`
- `mock.tcp.reset-scenarios/enable/disable/update`

### Dev tools
- `docker.execute`, `docker.compose`
- `git.execute`, `git.status`, `git.diff`
- `maven.execute`, `npm.execute`, `tools.check`
- `docs.refresh`, `paths.check`

### Health
- `health.check`

### GitHub
- `github.list_issues`, `github.get_issue`, `github.create_issue`
- `github.update_issue`, `github.add_issue_comment`, `github.search_issues`

### Environment (existing, enhanced)
- `env.list` — enhanced to return structured environment objects
- `env.switch` — switches active environment, takes effect on next MCP server spawn

## New tools

### context.get

Returns the current active configuration context. AI agents should call
this at the start of any session to understand what they are working with.

```
Tool: context.get
Input: (none)
Output: {
  bundlesRoot: string,          // active BUNDLES_ROOT path
  bundlesRootName: string,      // display name (last path segment)
  pockethiveRoot: string,       // PocketHive repo root
  activeEnvironment: string,    // active environment name
  baseUrl: string,              // active POCKETHIVE_BASE_URL
  mcpVersion: string,           // server version
  platform: string              // win32 / linux / darwin
}
```

### context.set-bundles-root

Switches the active bundles root. Takes effect immediately — no server
restart needed. The server updates its in-memory `BUNDLES_ROOT` variable.

```
Tool: context.set-bundles-root
Input: { path: string }
Output: {
  switched: true,
  path: string,
  bundleCount: number           // number of bundles found at new root
}
```

Note: this only affects the current server process. The IDE plugin also
updates its settings so the new root persists across restarts.

### context.list-bundles-roots

Lists all configured bundles roots from the IDE plugin settings.
The server receives these as a JSON array in `PH_BUNDLES_ROOTS` env var.

```
Tool: context.list-bundles-roots
Input: (none)
Output: {
  roots: [{ path: string, name: string, active: boolean }],
  active: string
}
```

### env.add

Creates a new named environment profile. Stored in the IDE plugin settings.
The server signals the plugin to persist the new profile.

```
Tool: env.add
Input: {
  name: string,
  baseUrl: string,
  rabbitUser?: string,
  tcpMockUrl?: string,
  wiremockUrl?: string
}
Output: { added: true, name: string }
```

### env.remove

Removes a named environment profile.

```
Tool: env.remove
Input: { name: string }
Output: { removed: true, name: string }
```

### env.current

Returns the active environment details (without secrets).

```
Tool: env.current
Input: (none)
Output: {
  name: string,
  baseUrl: string,
  rabbitUser: string,
  tcpMockUrl: string,    // resolved (auto-derived if not set)
  wiremockUrl: string,   // resolved (auto-derived if not set)
  hasAuthToken: boolean  // true if token stored in keychain
}
```

### bundle.diff

Shows the diff between the local bundle files and the version currently
deployed to the Scenario Manager.

```
Tool: bundle.diff
Input: { bundle: string }
Output: {
  bundle: string,
  localFiles: string[],
  deployedFiles: string[],
  added: string[],
  removed: string[],
  modified: string[]    // files that differ (by content hash)
}
```

## Environment variables reference

All variables injected by the IDE plugin at spawn time:

| Variable | Source | Description |
|---|---|---|
| `POCKETHIVE_BASE_URL` | active environment | Reverse proxy root URL |
| `BUNDLES_ROOT` | active bundles folder | Path to bundles directory |
| `POCKETHIVE_ROOT` | plugin setting | Path to PocketHive repo checkout |
| `RABBITMQ_DEFAULT_USER` | active environment | RabbitMQ username |
| `RABBITMQ_DEFAULT_PASS` | keychain | RabbitMQ password |
| `GITHUB_TOKEN` | keychain | GitHub PAT for issue tools |
| `GITHUB_REPO` | plugin setting | Target repo (default: sepa79/PocketHive) |
| `TCP_MOCK_BASE_URL` | active environment (optional) | Override TCP mock admin URL |
| `WIREMOCK_BASE_URL` | active environment (optional) | Override WireMock admin URL |
| `PH_BUNDLES_ROOTS` | plugin settings | JSON array of all configured bundle roots |
| `PH_MCP_HTTP_PORT` | plugin setting (HTTP mode) | Port for HTTP/SSE transport |

## File structure

```
tools/pockethive-mcp/
  server.mjs              <- main server (migrated + enhanced)
  start.cjs               <- CommonJS entry point for bin
  package.json            <- @pockethive/mcp-server
  package-lock.json
  dev-tools/
    executor.cjs          <- cross-platform shell executor (WSL detection)
    docker-tool.cjs
    git-tool.cjs
    maven-tool.cjs
    npm-tool.cjs
  apps/                   <- MCP App source (not published to npm)
    package.json
    vite.config.ts
    build-all.mjs
    shared/               <- design tokens, HAL eye CSS, shared helpers
    swarm-dashboard/
    bundle-explorer/
    queue-monitor/
    health-dashboard/
    create-swarm-form/
    journal-viewer/
    tap-viewer/
  dist/
    apps/                 <- built MCP App HTML files (published to npm)
  Dockerfile              <- for HTTP/SSE standalone deployment
  README.md
```

## Dockerfile (HTTP/SSE mode)

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY server.mjs start.cjs ./
COPY dev-tools/ dev-tools/
COPY dist/apps/ dist/apps/
ENV PH_MCP_HTTP_PORT=3100
EXPOSE 3100
CMD ["node", "server.mjs"]
```

## Bundles repo after migration

The bundles repo `mcp.json` changes from pointing at the local server file
to the installed npm package:

```json
{
  "mcpServers": {
    "pockethive-bundles": {
      "command": "npx",
      "args": ["@pockethive/mcp-server"],
      "env": {
        "BUNDLES_ROOT": "${workspaceFolder}",
        "POCKETHIVE_BASE_URL": "http://localhost:8088"
      }
    }
  }
}
```

Users run `npm install -g @pockethive/mcp-server` once. The bundles repo
becomes purely scenario content with no Node.js tooling of its own.
