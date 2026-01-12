
# Apply Flow (current implementation)

1) UI -> Orchestrator: `POST /api/swarms/{swarmId}/create` with `{ "templateId": "<scenarioId>", "idempotencyKey": "..." }`.
2) Orchestrator fetches Scenario JSON from Scenario Manager: `GET /scenarios/{id}`.
3) Orchestrator prepares runtime assets: `POST /scenarios/{id}/runtime` with `{ "swarmId": "<swarmId>" }`.
4) Orchestrator launches the swarm controller and registers plan data for the swarm.
5) Orchestrator sends lifecycle signals (template/plan/start) to the controller as needed.

Notes:
- `swarmId` is provided by the caller; there is no `runPrefix` allocation in code.
- Scenario Manager does not expose `/apply` or `/runs` in the current implementation.
