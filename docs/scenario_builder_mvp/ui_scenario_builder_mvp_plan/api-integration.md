
# API integration

## Scenario Manager (authoritative for scenarios)
- `GET /scenarios` list
- `GET /scenarios/{id}`
- `POST /scenarios` create
- `PUT /scenarios/{id}` update

## Orchestrator (apply flow)
- `POST /orchestrator/run` — body: `{ scenarioId, runPrefix }`
  - Orchestrator then performs: create swarms -> send Plan (`sig.config-update.{swarmId}`) -> `sig.swarm-start.{swarmId}`

Notes:
- Request/response schemas match the minimal Plan contract for blocks.
