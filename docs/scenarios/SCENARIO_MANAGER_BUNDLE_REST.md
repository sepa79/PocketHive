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
- PocketHive MCP migration: `docs/todo/pockethive-mcp-java-migration.md`

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
- The selected bundle is a directory containing canonical `scenario.yaml`.

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
- `validate-existing`

Repair editing is intentionally out of scope for bundles whose `scenario.id` is missing
or not uniquely addressable.

---

## Validation endpoints

Validation endpoints are side-effect-free unless explicitly documented
otherwise. They return structured findings so editor and MCP clients can show
actionable repair guidance without parsing free-text errors.

Finding shape:

```json
{
  "category": "templates",
  "code": "TEMPLATE_CALL_ID_MISSING",
  "severity": "error",
  "path": "scenario.yaml:plan",
  "message": "Scenario references HTTP callId 'login' but no matching template exists.",
  "fix": "Add templates/http/<service>/login.yaml or update the x-ph-call-id reference."
}
```

### Dry-run validate an uploaded bundle

`POST /validation/scenario-bundles` → request `application/zip`, response
`application/json`

- Unpacks and validates in temporary storage.
- Does not import, replace, move, delete, or persist the bundle.
- Validates the scenario descriptor, capability references, `variables.yaml`,
  bundle-local `sut/**/sut.yaml`, and template call references that Scenario
  Manager can inspect.
- Compiles inline Pebble and `eval` syntax with the canonical
  `common/templating` engine also used by worker runtime.

Scenario Manager is the single public and authoritative bundle validator.
MCP, UI, CLI, CI, and agent acceptance tests must call these Scenario Manager
validation endpoints and must not maintain independent bundle validators. The
shared `common/templating` module is a runtime compiler dependency used by the
one Scenario Manager validation pipeline; it is not a second bundle-validation
surface.

Response:

```json
{
  "ok": true,
  "source": "uploaded-zip",
  "validation": {
    "scenarioProtocolVersion": "2.0.0",
    "supportedScenarioProtocolVersion": "2.0.0",
    "scenarioManagerVersion": "0.15.35",
    "artifactDigest": "sha256:..."
  },
  "scenarioId": "webauth-demo",
  "scenarioName": "WebAuth demo",
  "bundleKey": null,
  "bundlePath": null,
  "summary": {
    "errors": 0,
    "warnings": 0
  },
  "findings": []
}
```

`validation` is required evidence. It identifies the scenario protocol declared
by the submitted descriptor, the protocol supported by the validator, the
Scenario Manager release that performed validation, and a deterministic
SHA-256 digest of the validated bundle contents (sorted relative paths plus
file bytes). ZIP container metadata therefore does not change the digest.

`scenarioId` and `scenarioName` are the exact descriptor identity parsed by
Scenario Manager. Clients must use these owner-reported values when presenting
publication intent; they must not parse or infer scenario identity independently.

### Validate an existing bundle by bundle key

`POST /validation/scenario-bundles/existing?bundleKey={bundleKey}` → response
`application/json`

- Validates a catalog entry from `GET /api/templates`.
- Works for malformed bundles and duplicate-id bundles that cannot be safely
  addressed by `scenarioId`.
- Uses the same `BundleValidationResult` response shape as uploaded-bundle
  dry-run validation.

---

## Bundle publication endpoints

These are the canonical mutation endpoints for publishing a complete validated
Scenario Bundle. Callers choose one operation explicitly. Scenario Manager does
not try the other operation when the selected operation fails.

### Create a bundle

`POST /scenarios/bundles` → request `application/zip`, response
`application/json`

- Validates the complete uploaded ZIP with the same canonical validation
  pipeline as `POST /validation/scenario-bundles`.
- Requires the descriptor's scenario ID not to exist in the discovered
  catalogue.
- Stores the validated bundle under the current `bundles/` catalogue folder.
- Returns `201 Created` with the loaded `Scenario` on success.
- Returns `400 Bad Request` with `BundleValidationResult` when bundle validation
  fails, including a duplicate-ID failure.
- Never changes an existing bundle and never falls back to replace.

### Replace a bundle

`PUT /scenarios/{id}/bundle` → request `application/zip`, response
`application/json`

- Validates the complete uploaded ZIP with expected scenario ID `{id}`.
- Requires the validated descriptor ID to equal `{id}`.
- Replaces the current deployed contents for that scenario ID and reloads the
  catalogue.
- Returns `200 OK` with the reloaded `Scenario` on success.
- Returns `400 Bad Request` with `BundleValidationResult` when validation or ID
  matching fails.
- Never creates under a different ID and never falls back to create.

Replace is currently last-write-wins. There is no expected-version, ETag, or
canonical-digest precondition. A caller must not present a preflight read as an
atomic concurrency guarantee or automatically retry an ambiguous replace.

### Regular-file preservation

For validation, create, and replace, the validated bundle root is the directory
containing the accepted `scenario.yaml`. A single wrapper directory in the ZIP
is therefore removed from the deployed path. Under that validated root,
Scenario Manager:

- preserves every accepted regular file regardless of extension;
- preserves each regular file's relative path and exact bytes;
- creates required directories;
- does not execute scripts, SQL, Compose files, or other uploaded content; and
- does not promise to preserve ZIP timestamps, ownership, directory entries, or
  POSIX mode bits.

Clients that need source traceability must compare the canonical
`validation.artifactDigest`, which is calculated from sorted relative paths and
file bytes. ZIP container metadata and POSIX mode are outside that digest.

The input ZIP remains subject to Scenario Manager's archive safety and bundle
validation rules. A gateway or MCP may enforce stricter transport limits before
calling Scenario Manager, but it must not create a second semantic bundle
validator.

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

- Returns canonical bundle-local SUTs only: `sut/<sutId>/sut.yaml` must parse and `sut.yaml.id` must match `{sutId}`.

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
