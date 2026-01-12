# Scenario Builder — Extracted Notes

This is a distilled set of ideas from the archived Scenario Builder MVP docs.
It captures what still makes sense today and avoids repeating superseded plans.

## Still-valid ideas
- Keep control-plane contracts single-source and avoid duplicated schema definitions.
- Treat Scenario Manager as the source of truth for scenario content; Orchestrator reads and applies.
- Deliver in small phases: contract/schema -> backend CRUD -> UI -> wiring -> hardening.
- Prioritize contract conformance tests and end-to-end flows early (mocked first, then real).
- Preserve observability tags (runId, planId, swarmId) across services for traceability.
- Plan for scenario versioning and run history storage (scenario + version + run + run_event in Postgres).
- Watch for schema drift between UI and backend; keep shared validation artifacts.

## Current references
- Editor status and gaps: `docs/scenarios/SCENARIO_EDITOR_STATUS.md`
- Scenario authoring overview: `docs/scenarios/README.md`
