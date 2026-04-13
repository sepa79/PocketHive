# Scenario Manager — Bundle Editing REST (Scenario Bundles)

This document describes the **Scenario Manager** REST endpoints used by the UI/editor to read and edit
scenario bundle files that live under:

`scenarios/**/<scenarioId>/`

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

## Bundle diagnostics

Scenario Manager validates every bundle at startup and on every reload. Bundles that fail
validation are marked **defunct** and excluded from the template picker. Bundles that cannot
be parsed at all are recorded as **load failures**.

### List scenarios (with defunct status)

`GET /scenarios` → `application/json`

Query params:
- `includeDefunct=false` (default) — returns only available scenarios
- `includeDefunct=true` — returns all scenarios including defunct

Each item in the response now includes:

| Field | Type | Description |
|---|---|---|
| `id` | string | Scenario id |
| `name` | string | Human-readable name |
| `folderPath` | string \| null | Folder path within the scenarios root |
| `defunct` | boolean | `true` if the bundle loaded but cannot be used to create a swarm |
| `defunctReason` | string \| null | Plain-English reason; `null` when `defunct` is `false` |

Example response (mixed):
```json
[
  {
    "id": "local-rest",
    "name": "Local REST",
    "folderPath": null,
    "defunct": false,
    "defunctReason": null
  },
  {
    "id": "ctap-iso8583-request-builder-demo",
    "name": "CTAP ISO8583 Request Builder Demo",
    "folderPath": "bundles/ctap-iso8583-rbuilder-scenario",
    "defunct": true,
    "defunctReason": "No capability manifest found for image 'io.pockethive/generator:0.15.11' (bee 'generator'). Check that this image version is installed."
  }
]
```

### List defunct scenarios only

`GET /scenarios/defunct` → `application/json`

Returns only defunct scenarios. Response shape is identical to `GET /scenarios` above,
including the `defunct` and `defunctReason` fields.

### List bundle load failures

`GET /scenarios/failures` → `application/json`

Returns bundles that could not be loaded at all during the last reload. These are distinct
from defunct scenarios — they have no scenario id and cannot appear in the scenarios list.

Returns `[]` when all bundles loaded successfully. Never returns 4xx/5xx for normal operation.

| Field | Type | Description |
|---|---|---|
| `bundlePath` | string | Path relative to the scenarios root, using forward slashes |
| `reason` | string | Plain-English reason |

Example response:
```json
[
  {
    "bundlePath": "bundles/my-broken-scenario",
    "reason": "Could not read scenario file: mapping values are not allowed here at line 5"
  },
  {
    "bundlePath": "bundles/old-duplicate",
    "reason": "Duplicate scenario id 'local-rest' — another bundle at 'bundles/local-rest' was loaded instead"
  }
]
```

### Templates endpoint (used by Create Swarm modal)

`GET /api/templates` → `application/json`

Returns **all** scenarios including defunct, with `defunct` and `defunctReason` fields.
The UI uses this to show defunct templates as greyed-out, non-selectable entries with a
tooltip explaining why they cannot be used.

> **Note:** Prior to this change, this endpoint only returned available (non-defunct)
> scenarios. Consumers that do not handle the `defunct` field should filter entries where
> `defunct === true` to preserve previous behaviour.

---

## Why a bundle may be defunct

| Reason | `defunctReason` contains | How to fix |
|---|---|---|
| Missing `id` field | `missing a required 'id' field` | Add `id:` to `scenario.yaml` |
| No `template:` block | `no swarm template defined` | Add a `template:` section |
| Controller has no `image:` | `Controller image is not defined` | Add `image:` under `template:` |
| Bee has no `image:` | `bee 'X' has no image defined` | Add `image:` to the bee |
| Image tag not in capability manifests | `No capability manifest found for image '...'` | Add a capability manifest YAML or update the scenario to use an installed tag |

## Why a bundle may fail to load entirely

| Reason | `reason` contains | How to fix |
|---|---|---|
| Malformed YAML or JSON | `Could not read scenario file:` + parse error location | Fix the syntax error at the reported line/column |
| Two bundles share the same `id` | `Duplicate scenario id` | Rename the `id` in one of the conflicting `scenario.yaml` files |

## Triggering a reload

After fixing a file on disk, call `POST /scenarios/reload` or restart the service to pick
up the changes. The UI Refresh button re-fetches the scenarios and failures lists but does
**not** trigger a server-side reload.

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
