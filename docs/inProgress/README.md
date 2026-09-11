# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

- `docs/inProgress/work-plane-module-boundaries.md` — primary architecture stream: documentation/memory preparation, then enforced Work Plane boundaries, Control Plane, and remaining production sinks.
  Concrete design: `docs/architecture/work-plane-boundaries.md`; design and review evidence: `docs/inProgress/boundary-design/`.
  B01 is accepted and committed as `eb681ee7`. B02 remains incomplete and unaccepted.
  Its [current execution contract](work-plane-module-boundaries.md#current-execution-contract)
  owns the frozen configuration/provider/consumer scope, deletion gates and phase order.
  B05a/B06a/B07a and narrow B03a are historical labels within B02; full runtime extraction
  remains B03. [B02 evidence](boundary-design/b02/README.md) preserves scoped results.
  Previous Terra/Sol assignments are withdrawn; separate review and phase simplification
  remain required. Artemis and SEL-R1 remain deferred.

- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- `docs/inProgress/runtime-debug-mcp-cleanup-spec.md` — implementation exists; production HiveGate registration remains.
- Current PocketHive MCP/IDE reference documentation lives in `docs/mcp/README.md` and `vscode-pockethive/README.md`; the former plugin design pack is archived.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
