# Scenario Manager — Bundle Editing REST (Scenario Bundles)

This document describes the **Scenario Manager** REST endpoints used by the UI/editor to read and edit
scenario bundle files that live under:

`scenarios/**/<scenarioId>/`

It intentionally covers only the **bundle editing surface** (variables + bundle-local SUTs).

Related docs:
- Scenario YAML contract: `docs/scenarios/SCENARIO_CONTRACT.md`
- Scenario Variables contract: `docs/scenarios/SCENARIO_VARIABLES.md`

---

## Base URL

The UI reaches Scenario Manager via the reverse proxy:

- `/scenario-manager/...` (nginx) → `scenario-manager-service`

The service itself exposes the routes under:

- `/scenarios/...`

---

## `variables.yaml`

File path in bundle:

- `scenarios/**/<scenarioId>/variables.yaml`

### Read variables

`GET /scenarios/{scenarioId}/variables` → `text/plain`

- `200` returns the raw `variables.yaml` content
- `404` when the file does not exist (scenario does not use variables)

### Write variables

`PUT /scenarios/{scenarioId}/variables` → request `text/plain`, response `application/json`

- Validates the YAML on write (strict schema + type checks).
  - Invalid schema/type mismatch/unknown keys → `400`
  - Incomplete coverage for `required: true` across `profile × sut` → **warning** (save allowed)

Response (200):
```json
{ "status": "ok", "warnings": ["..."] }
```

### Resolve variables (create-swarm / runtime)

`GET /scenarios/{scenarioId}/variables/resolve?profileId={profileId}&sutId={sutId}` → `application/json`

- Used by Orchestrator during `swarm-create` to compile a flat `vars` map for the chosen `(profileId, sutId)`.
- Missing required variables for the selected pair → `400` (hard error).

Response (200):
```json
{
  "profileId": "france",
  "sutId": "sut-A",
  "vars": { "loopCount": 10, "customerId": "123" },
  "warnings": ["..."]
}
```

---

## Bundle-local SUTs

Bundle layout:

`scenarios/**/<scenarioId>/sut/<sutId>/sut.yaml`

The JSON/YAML model is `io.pockethive.swarm.model.SutEnvironment`.

### List SUT ids in a bundle

`GET /scenarios/{scenarioId}/suts` → `application/json`

Response (200):
```json
["sut-A", "sut-B"]
```

### Read parsed SUT environment (JSON)

`GET /scenarios/{scenarioId}/suts/{sutId}` → `application/json`

- Validates that `sut.yaml.id` matches the directory name `{sutId}`.

### Read raw `sut.yaml`

`GET /scenarios/{scenarioId}/suts/{sutId}/raw` → `text/plain`

- `404` when the file does not exist.

### Write raw `sut.yaml` (create/update)

`PUT /scenarios/{scenarioId}/suts/{sutId}/raw` → request `text/plain`

- Parses the YAML as `SutEnvironment`.
- Requires `sut.yaml.id == {sutId}`.

Returns `204 No Content` on success.

### Delete a bundle-local SUT

`DELETE /scenarios/{scenarioId}/suts/{sutId}`

Deletes the directory `sut/<sutId>/` from the bundle.

Returns `204 No Content` on success.
