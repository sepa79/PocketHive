# PocketHive Orchestrator Debug MCP Server

This is a small [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server that lets an MCP‑enabled client
talk directly to:

- the PocketHive Orchestrator REST API, and
- the RabbitMQ control‑plane exchange (control messages `signal.*` / `event.*` on `ph.control`).

It is designed for **debugging** and **inspection**, not for production use.

It is additive to the existing debug CLI in `tools/mcp-orchestrator-debug/client.mjs` and the
Rabbit recorder in `tools/mcp-orchestrator-debug/rabbit-recorder.mjs`. It does not replace those
tools or change PocketHive runtime behavior.

## 0. Relationship to the existing debug tooling

This folder now contains three layers:

- `client.mjs`
  - Existing shell-oriented debug CLI for orchestrator, queue, and journal inspection.
- `rabbit-recorder.mjs`
  - Existing RabbitMQ control-plane recorder that writes JSONL entries to disk.
- `server.mjs`
  - Thin MCP stdio wrapper that reuses the existing CLI and recorder so MCP clients can call them.

Use the MCP server when you want editor/agent integration. Use the CLI directly when you want the
full command surface from the terminal.

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

The server also writes a local feedback/event log to:

- `tools/mcp-orchestrator-debug/session-log.jsonl`

## 3. Running the MCP server

From the repo root:

```bash
node tools/mcp-orchestrator-debug/server.mjs
```

The process will:

- speak MCP over **stdio** (as expected by MCP clients)
- auto‑discover the MCP protocol once a client connects
- reuse the existing `client.mjs` and `rabbit-recorder.mjs` scripts under the same repo folder

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

Once connected, the server exposes a focused subset of the existing debug capabilities:

Important guardrail:

- direct swarm creation must not bypass Scenario Manager bundle diagnostics
- verify the selected entry through `GET /api/templates` first
- a bundle marked `defunct=true` must be treated as non-runnable even if raw id-based REST calls still succeed

- `orchestrator.list-swarms`
  - No input.
  - Calls `GET {ORCHESTRATOR_BASE_URL}/api/swarms`.
  - Returns `structuredContent.swarms` (array) plus a pretty‑printed JSON text block.

- `orchestrator.get-swarm`
  - Input:
    - `swarmId: string`
  - Calls `GET {ORCHESTRATOR_BASE_URL}/api/swarms/{swarmId}`.
  - Returns `structuredContent.swarm` plus text.

- `scenario.reload-scenarios`
  - No input.
  - Calls `POST {SCENARIO_MANAGER_BASE_URL}/scenarios/reload`.
  - Returns the reload acknowledgement from Scenario Manager plus text.

- `control.start-recording`
  - Input (optional):
    - `routingKeyPattern?: string` — simple `*`/`#` style glob applied to the routing key (defaults to all).
  - Connects to RabbitMQ using `RABBITMQ_*` and `POCKETHIVE_CONTROL_PLANE_EXCHANGE`.
  - Asserts the control exchange and an exclusive auto‑delete queue.
  - Binds `signal.#` and `event.#` to the queue and starts consuming.
  - Clears any previous `control-recording.jsonl` file before starting a new recording session.
  - Each message is recorded as:
    - `routingKey`, `body` (UTF‑8 text), `headers` and `timestamp`.

- `control.stop-recording`
  - Stops the consumer, closes the channel/connection.
  - Does **not** delete the buffered recording file; it only stops new messages from being recorded.

- `control.get-recorded`
  - No input.
  - Returns `structuredContent.messages` with the current file-backed recording from
    `tools/mcp-orchestrator-debug/control-recording.jsonl`:
    - Each entry: `{ routingKey, body, headers, timestamp }`.
  - Also returns a text block with the same array pretty‑printed.

- `feedback.submit`
  - Input:
    - `relatedEventId: string`
    - `intent: string`
    - `outcomeUnderstanding: string`
    - `blockerType: string`
    - `proposedNextAction: string`
    - `suggestedImprovements[]`
  - Stores structured AI feedback in `tools/mcp-orchestrator-debug/session-log.jsonl`.

- `feedback.summary`
  - No input.
  - Returns a small summary for the current MCP server session:
    - tool calls by status
    - feedback event count
    - most used tools
    - most common validation codes / blocker types / suggestion types

Every tool result now also includes `structuredContent.toolEvent` with:

- `eventId`
- `sessionId`
- `toolName`
- `resultStatus`
- `summary`
- `validation[]`
- `nextHint`
- `feedbackRequired`
- `timestamp`

That `eventId` can be passed back into `feedback.submit` as `relatedEventId`.

You can use these tools to answer questions like:

- “Did the `config-update` for this generator instance actually go out?”
- “Which `status-full` events were emitted for swarm `foo`?”
- “What exactly was in the `ready.swarm-remove` confirmation payload?”

To clear the buffered recording, call `control.start-recording` again or delete
`tools/mcp-orchestrator-debug/control-recording.jsonl`.

## 6. What this does not change

- Existing CLI commands in `client.mjs` remain supported and unchanged.
- Existing recorder usage via `node tools/mcp-orchestrator-debug/rabbit-recorder.mjs` remains supported.
- PocketHive services, swarm behavior, and control-plane contracts are unchanged.

## 7. Validation

The MCP helper logic is covered by:

```bash
npx vitest run tools/mcp-orchestrator-debug/server-utils.test.mjs
```

If you add new MCP tools, keep the README aligned with the exposed tool names and whether the
implementation is file-backed or process-local.
