---
slug: /
---

# Start Here

PocketHive is a RabbitMQ-centric load and behavior simulator that runs **swarms**
of small worker services ("bees") to generate traffic, shape it, execute calls
against a SUT, and emit telemetry.

## What you do in practice

1. Run the local stack (`./build-hive.sh`).
2. Create a swarm from a scenario.
3. Start it and observe queues/metrics/journal.
4. Iterate by editing scenario bundles and applying plan/config updates.

## Choose your path

- UI-first: use Hive at `http://localhost:8088` (recommended for first run).
- CLI-first: use `tools/mcp-orchestrator-debug/client.mjs` for create/start/inspect.

## Where to look for answers

- Running locally: `docs/USAGE.md`
- Orchestrator REST: `docs/ORCHESTRATOR-REST.md`
- Scenarios overview: `docs/scenarios/README.md`
- Scenario contract: `docs/scenarios/SCENARIO_CONTRACT.md`
- Scenario patterns: `docs/scenarios/SCENARIO_PATTERNS.md`
- Workers basics: `docs/guides/workers-basics.md`
- Templating basics: `docs/guides/templating-basics.md`

Next: `docs/guides/onboarding/quickstart-15min.md`
