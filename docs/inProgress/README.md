# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

- `docs/inProgress/managed-test-data-lifecycle-generic-spec.md` — managed Dataset
  manager-review specification for an Orchestrator-owned bounded module using
  PostgreSQL durability, common/SDK integration, continuous supply/refresh,
  worker-local high-throughput consumption, Redis compatibility, security,
  qualification profiles, and MCP proof without a new core application
  container.
- `docs/inProgress/managed-test-data-assurance-strategy.md` — companion Rapid
  Software Testing strategy covering risk heuristics, independent oracles,
  charters, corner cases, debriefs, confidence grades, and release evidence.
- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- `docs/inProgress/runtime-debug-mcp-cleanup-spec.md` — implementation exists; production HiveGate registration remains.
- Current PocketHive MCP/IDE reference documentation lives in `docs/plugins/pockethive/`.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
