# PocketHive Orchestrator Debug MCP Server

This is a small [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server that lets an MCP‑enabled client
talk directly to:

- the PocketHive Orchestrator REST API, and
- the RabbitMQ control‑plane exchange (control messages `sig.*` / `ev.*` on `ph.control`).

It is designed for **debugging** and **inspection**, not for production use.

## 1. Prerequisites

- Node.js 18+ available on your `PATH`
- This repo checked out and built with Maven as usual
- RabbitMQ / Orchestrator running (e.g. via `docker-compose.yml`)

From the repo root:

```bash
npm install
```

This will pull `@modelcontextprotocol/sdk`, `amqplib`, and `zod`.

## 2. Configuration

The server reads the same environment variables you already use in E2E tests / tooling:

- `ORCHESTRATOR_BASE_URL` (default: `http://localhost:8088/orchestrator`)
- `RABBITMQ_HOST` (default: `localhost`)
- `RABBITMQ_PORT` (default: `5672`)
- `RABBITMQ_DEFAULT_USER` (default: `guest`)
- `RABBITMQ_DEFAULT_PASS` (default: `guest`)
- `RABBITMQ_VHOST` (default: `/`)
- `POCKETHIVE_CONTROL_PLANE_EXCHANGE` (default: `ph.control`)

If you run PocketHive via the provided `docker-compose.yml` and port‑forward RabbitMQ / Orchestrator to localhost,
the defaults should be correct.

## 3. Running the MCP server

From the repo root:

```bash
node tools/mcp-orchestrator-debug/server.mjs
```

The process will:

- speak MCP over **stdio** (as expected by MCP clients)
- auto‑discover the MCP protocol once a client connects

You usually do **not** talk to this directly; instead you configure your MCP client (editor / CLI) to spawn it.

## 4. Registering in an MCP client

Exact configuration depends on the client; conceptually you want:

- **transport**: `stdio`
- **command**: `node`
- **args**: `["tools/mcp-orchestrator-debug/server.mjs"]`
- **working directory**: the PocketHive repo root
- **environment**: set `ORCHESTRATOR_BASE_URL`, `RABBITMQ_*`, `POCKETHIVE_CONTROL_PLANE_EXCHANGE` as needed

For example, in a JSON‑based MCP client config:

```json
{
  "servers": {
    "pockethive-orchestrator-debug": {
      "command": "node",
      "args": ["tools/mcp-orchestrator-debug/server.mjs"],
      "env": {
        "ORCHESTRATOR_BASE_URL": "http://localhost:8088/orchestrator",
        "RABBITMQ_HOST": "localhost",
        "RABBITMQ_PORT": "5672",
        "RABBITMQ_DEFAULT_USER": "guest",
        "RABBITMQ_DEFAULT_PASS": "guest",
        "RABBITMQ_VHOST": "/",
        "POCKETHIVE_CONTROL_PLANE_EXCHANGE": "ph.control"
      }
    }
  }
}
```

Consult your MCP client documentation for the exact config format; the important part is to run the script with Node
and pass the environment through.

## 5. Exposed tools

Once connected, the server exposes these MCP tools:

- `orchestrator.list-swarms`
  - No input.
  - Calls `GET {ORCHESTRATOR_BASE_URL}/api/swarms`.
  - Returns `structuredContent.swarms` (array) plus a pretty‑printed JSON text block.

- `orchestrator.get-swarm`
  - Input:
    - `swarmId: string`
  - Calls `GET {ORCHESTRATOR_BASE_URL}/api/swarms/{swarmId}`.
  - Returns `structuredContent.swarm` plus text.

- `control.start-recording`
  - Input (optional):
    - `routingKeyPattern?: string` — simple `*`/`#` style glob applied to the routing key (defaults to all).
  - Connects to RabbitMQ using `RABBITMQ_*` and `POCKETHIVE_CONTROL_PLANE_EXCHANGE`.
  - Asserts the control exchange and an exclusive auto‑delete queue.
  - Binds `sig.#` and `ev.#` to the queue and starts consuming.
  - Each message is recorded as:
    - `routingKey`, `body` (UTF‑8 text), `headers` and `timestamp`.

- `control.stop-recording`
  - Stops the consumer, closes the channel/connection.
  - Does **not** clear the in‑memory buffer; it only stops new messages from being recorded.

- `control.get-recorded`
  - No input.
  - Returns `structuredContent.messages` with the current in‑memory recording:
    - Each entry: `{ routingKey, body, headers, timestamp }`.
  - Also returns a text block with the same array pretty‑printed.

You can use these tools to answer questions like:

- “Did the `config-update` for this generator instance actually go out?”
- “Which `status-full` events were emitted for swarm `foo`?”
- “What exactly was in the `ready.swarm-remove` confirmation payload?”

Because everything is in memory, restart the server (or call `control.start-recording` again) to clear the buffer. 

