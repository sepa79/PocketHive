# Quickstart (15 min)

Goal: run the stack, create a swarm from an existing scenario, and start it.

## 1. Prerequisites

- Docker
- Java 21

## 2. Start the local stack

From repo root:

```bash
./build-hive.sh --quick
```

Open:

- UI: `http://localhost:8088`
- RabbitMQ: `http://localhost:15672` (via proxy also: `http://localhost:8088/rabbitmq/`)

Health checks:

```bash
curl -s http://localhost:8088/healthz
```

## 3. Create and start a swarm (UI)

1. Open Hive view.
2. Click **Create Swarm**.
3. Pick a scenario (start with `local-rest`).
4. Create the swarm, then press play to start it.

## 4. Create and start a swarm (CLI)

If you prefer CLI:

```bash
node tools/mcp-orchestrator-debug/client.mjs list-scenarios
node tools/mcp-orchestrator-debug/client.mjs create-swarm demo local-rest
node tools/mcp-orchestrator-debug/client.mjs start-swarm demo
node tools/mcp-orchestrator-debug/client.mjs get-swarm demo
```

If the stack is not running, these commands fail with connection refused.

Next: `docs/guides/onboarding/first-scenario.md`
