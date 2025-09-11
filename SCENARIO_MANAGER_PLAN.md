# Scenario Manager Plan

## API behaviour
- `GET /scenarios/names` – return lightweight list of scenario identifiers for UI selection.
- `GET /scenarios/{id}` – return full scenario definition; honour `Accept: application/yaml` for YAML.
- `POST /scenarios` – create a new scenario definition.
- `PUT /scenarios/{id}` – replace an existing scenario.
- `DELETE /scenarios/{id}` – remove a scenario.

## Implementation Plan
- [x] Add `listScenarioNames()` method to `ScenarioService` returning `List<String>` or `List<ScenarioSummary>`.
- [x] Expose `GET /scenarios/names` in `ScenarioController` delegating to the service method.
- [x] Update `SwarmCreateModal` in the UI to query `/scenario-manager/scenarios/names` for dropdown options and keep `/scenario-manager/scenarios/{id}` for fetching details.
- [x] Adjust `SwarmCreateModal.test.tsx` to stub the new endpoint and verify it is requested.
