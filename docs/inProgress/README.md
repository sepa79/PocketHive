# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

- [Functional module boundaries](functional-module-boundaries.md) — current source analysis
  and proposed repair order for the existing modularity/SSOT requirement. Covers Redis,
  worker I/O/runtime, Docker, journal/filesystem, ClickHouse, auth/templates, service contracts,
  lifecycle projections and residual service/tool boundaries. Implementation awaits plan review.
- [One Rabbit module](work-plane-module-boundaries.md) — Rabbit-specific transfer and remaining gates.
  Java resources, transport, configuration and physical naming now use `common/rabbit-adapter`.
  Remaining gates: legacy-cleanup deletion review and browser schema/STOMP validation;
  test fixtures and legacy debug tooling remain explicitly deferred.
  [Boundary design](../architecture/work-plane-boundaries.md) defines ownership;
  the plan defines order and completion. Former B02–B07/C01–C03 instructions and evidence
  are archived and are not implementation prerequisites. Artemis and Redis SEL-R1 remain deferred.

- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- `docs/inProgress/runtime-debug-mcp-cleanup-spec.md` — implementation exists; production HiveGate registration remains.
- Current PocketHive MCP/IDE reference documentation lives in `docs/mcp/README.md` and `vscode-pockethive/README.md`; the former plugin design pack is archived.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
