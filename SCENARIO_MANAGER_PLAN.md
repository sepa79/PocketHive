# Scenario Manager Plan

## API behaviour
- `GET /scenarios` – return lightweight list of `{id, name}` summaries for UI selection.
- `GET /scenarios/{id}` – return full scenario definition; honour `Accept: application/yaml` for YAML.
- `POST /scenarios` – create a new scenario definition.
- `PUT /scenarios/{id}` – replace an existing scenario.
- `DELETE /scenarios/{id}` – remove a scenario.

## Implementation Plan
- [x] Add `list()` method to `ScenarioService` returning `List<ScenarioSummary>` sorted by name.
- [x] Expose `GET /scenarios` in `ScenarioController` delegating to the service method.
- [x] Update `SwarmCreateModal` in the UI to query `/scenario-manager/scenarios` for dropdown options and keep `/scenario-manager/scenarios/{id}` for fetching details.
- [x] Adjust `SwarmCreateModal.test.tsx` to stub the new endpoint and verify it is requested.
