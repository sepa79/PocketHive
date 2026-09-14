# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

Current delivery: [Rabbit SSOT and complete WorkPlane isolation](work-plane-module-boundaries.md),
verified with Rabbit and a stateful test adapter. [Artemis and delayed publish for 3DS](../todo/work-plane-artemis-3ds.md)
follow in a separate PR. Other refactors and service correctness findings remain separate PRs.

- [Orchestrator correctness](orchestrator-correctness.md) — separate behavior fixes: O1/O2 evidence
  identity acceptance implemented (74 tests), awaiting review; reset/registry/lifecycle design remains pending.
  Does not expand the behavior-preserving SSOT extraction scope.

- [Functional module boundaries](functional-module-boundaries.md) — current source analysis
  and proposed repair order for the existing modularity/SSOT requirement. Covers Redis,
  worker I/O/runtime, Docker, journal/filesystem, ClickHouse, auth/templates, service contracts,
  lifecycle projections and residual service/tool boundaries. Implementation awaits plan review.
- [Rabbit SSOT and WorkPlane isolation](work-plane-module-boundaries.md) — technology transfer exists;
  R1–R6 close selected Work configuration/topology/resources/transport/observations/cleanup across
  SDK, Swarm Controller and Orchestrator, then remove alternatives and hand off for aggregate review.
  Verification includes a small stateful fake and normal Rabbit E2E. Plan prepared; closure not implemented.
  [Boundary design](../architecture/work-plane-boundaries.md) defines ownership. Delayed-publish API
  design and Artemis belong to the later PR; retired B02–B07 instructions are not prerequisites.
  Existing legacy-tool/test-fixture exclusions and deferred Redis SEL-R1 remain explicit in the plan.

- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- `docs/inProgress/runtime-debug-mcp-cleanup-spec.md` — implementation exists; production HiveGate registration remains.
- Current PocketHive MCP/IDE reference documentation lives in `docs/mcp/README.md` and `vscode-pockethive/README.md`; the former plugin design pack is archived.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
