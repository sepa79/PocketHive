# In-Progress Plans

This directory contains only work that is actively being implemented or is waiting on a concrete delivery gate.

## Current plans

- `docs/inProgress/work-plane-module-boundaries.md` — primary architecture stream: documentation/memory preparation, then enforced Work Plane boundaries, Control Plane, and remaining production sinks.
  Concrete design: `docs/architecture/work-plane-boundaries.md`; design and review evidence: `docs/inProgress/boundary-design/`.
  B01 R1/R2 are accepted; custom scanner approach withdrawn by user. Removal and mandatory review rules: `docs/inProgress/boundary-design/b01/scanner-removal.md`.
  Current responsibility/header adoption: 157 B01 production files linked to 51 current records. [Separate correction review](boundary-design/b01/rv2-correction-review.md) accepts RV2 and B01; RV1's wiring-test requirement remains superseded by the human boundary policy. B01 committed as `eb681ee7`. [B02 execution](boundary-design/b02/README.md) has implemented patch-policy, request-template, Rabbit connection export, Redis route, dataset-source, source-mode, output-target and write-settings transfers; remaining typed Work settings and candidate validation are open.

- `docs/inProgress/processor-iso8583-v1-v2-plan.md` — active ISO8583 processor delivery and remaining V2 work.
- `docs/inProgress/runtime-debug-mcp-cleanup-spec.md` — implementation exists; production HiveGate registration remains.
- Current PocketHive MCP/IDE reference documentation lives in `docs/mcp/README.md` and `vscode-pockethive/README.md`; the former plugin design pack is archived.

Completed delivery plans belong in `docs/archive/`. Future work belongs in `docs/todo/`. Every active plan should state its remaining gate explicitly rather than relying only on this directory name.
