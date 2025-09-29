
# PocketHive Scenario Builder – MVP Pack

Generated: 2025-09-29T08:31:49.747073Z

Contents:
- `scenario-schema-v0.json` — JSON Schema for Scenario definitions (MVP).
- `openapi-scenario-manager.yaml` — REST surface for Scenario Manager (authoritative).
- `example-scenario.json` — A worked example (two coordinated swarms).
- `ui-checklist.md` — Phased tasks for the UI.
- `component-tree.md` — React component map.
- `scenario-to-controller.md` — Mapping from Scenario blocks to Controller signals.

Implementation notes:
- Use `runPrefix` to allow parallel runs; Scenario Manager derives `swarmId` as `{runPrefix}.{templateRef}.{index}`.
- Processor auth is placeholder in MVP; future: token refresh and header injection.
- Dataset providers modeled as `redis`, `csv`, or `inline` (dev-only).
