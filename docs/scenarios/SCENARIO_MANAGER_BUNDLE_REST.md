# Scenario Manager — Bundle Editing REST (Scenario Bundles)

This document describes the **Scenario Manager** REST endpoints used by the UI/editor to read and edit
scenario bundle files that live under:

`scenarios/**/<scenarioId>/`

It intentionally covers only the **bundle editing surface** (variables + bundle-local SUTs).

For bundle diagnostics and bundle-catalog semantics, see:

- `docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md`

Related docs:
- Scenario YAML contract: `docs/scenarios/SCENARIO_CONTRACT.md`
- Scenario Variables contract: `docs/scenarios/SCENARIO_VARIABLES.md`
- Bundle diagnostics contract: `docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md`

---

## Base URL

The UI reaches Scenario Manager via the reverse proxy:

- `/scenario-manager/...` (nginx) → `scenario-manager-service`

The service itself exposes the routes under:

- `/scenarios/...`

---

## Bundle-addressed admin operations

Some bundle operations must work even when `scenario.id` is missing or unusable.

Those routes use `bundleKey`, which is the stable bundle identity returned by `GET /api/templates`.

### Move bundle to folder

`POST /scenarios/bundles/move` → request `application/json`

Request body:
```json
{ "bundleKey": "tcp/tcp-echo-demo", "path": "quarantine" }
```

- Moves the whole bundle by its discovered bundle identity.
- Works for malformed bundles and duplicate-id bundles.
- `path=""` or `null` means root.

### Download bundle

`GET /scenarios/bundles/download?bundleKey={bundleKey}` → `application/zip`

- Returns a zip for the selected bundle.
- For descriptor-only bundles, the zip contains the descriptor file.

### Delete bundle

`DELETE /scenarios/bundles?bundleKey={bundleKey}`

- Deletes the selected bundle by its discovered bundle identity.
- Works even when `scenario.id` is missing or conflicting.

### Scope note

These bundle-addressed routes are the supported admin surface for malformed bundles
and duplicate-id bundles in the current step.

- `download`
- `move`
- `delete`

Repair editing is intentionally out of scope for bundles whose `scenario.id` is missing
or not uniquely addressable.

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
