# Scenario bundle diagnostics

This document defines how Scenario Manager exposes **all discovered scenario bundles**
to the UI, including broken or unusable ones.

Related docs:
- `docs/scenarios/SCENARIO_CONTRACT.md`
- `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md`

---

## Goal

Scenario Manager scans the configured scenarios root on startup and reload.

The UI must be able to see:

- bundles that are valid and usable,
- bundles that were parsed but are not usable,
- bundles that exist on disk but could not be parsed correctly.

The UI must not need a second endpoint to explain why a bundle is missing.

---

## Bundle catalog endpoint

`GET /api/templates` returns the full discovered bundle catalog.

This endpoint is not limited to selectable templates. It is the canonical
list of bundle entries found on disk.

### Response shape

Each item has this shape:

| Field | Type | Meaning |
|---|---|---|
| `bundleKey` | string | Stable unique identifier for the discovered bundle entry. Derived from the bundle path on disk. |
| `bundlePath` | string | Bundle path relative to the scenarios root. |
| `folderPath` | string \| null | Parent folder path relative to the scenarios root. `null` for root entries. |
| `id` | string \| null | Parsed scenario id when available. `null` when the bundle could not be parsed into a valid scenario id. |
| `name` | string | Human-readable display name. Uses `scenario.name` when available, otherwise falls back to the bundle name/path. |
| `description` | string \| null | Parsed scenario description when available. |
| `controllerImage` | string \| null | Parsed `template.image` when available. |
| `bees` | array | Parsed bee role/image pairs when available. Empty for malformed bundles or bundles without a usable template. |
| `defunct` | boolean | `true` when the bundle exists on disk but cannot be used to create a swarm. |
| `defunctReason` | string \| null | Human-readable reason when `defunct=true`, otherwise `null`. |

### Selection rule

The backend does **not** return a `selectable` flag.

UI rule:

- bundle entries may still be clicked/selected in the UI to inspect details
- swarm creation is allowed if and only if `defunct === false`

### Create guardrail

Any create-swarm client must treat `GET /api/templates` as the source of truth for bundle runnability.

- do not assume that a known `scenario.id` is runnable by itself
- verify that the matching bundle entry exists and is not `defunct`
- do not bypass this check by calling orchestrator flows directly from tooling

---

## Authoring contract endpoint

`GET /api/authoring-contract` returns the Scenario Manager authoring contract
used by editors and MCP tools.

The response is read-only and versioned. It combines:

- supported Scenario Manager endpoints for authoring and validation,
- scenario bundle file layout rules,
- variable/SUT/auth/traffic-policy authoring hints,
- capability manifest summaries,
- template catalog metadata,
- a deterministic `fingerprint`.

The fingerprint changes when contract-visible capabilities/templates change.
Long-lived clients may cache the full response for a session and only refresh it
when explicitly asked to check or when the fingerprint changes.

`GET /api/authoring-contract/fingerprint` returns the lightweight fingerprint
view for clients that want to check whether their cached contract is stale.

Example response:

```json
{
  "contractVersion": "scenario-authoring.v1",
  "fingerprint": "sha256:...",
  "source": "scenario-manager",
  "endpoints": {
    "templates": "/api/templates",
    "capabilities": "/api/capabilities",
    "validateBundle": "/validation/scenario-bundles",
    "validateExistingBundle": "/validation/scenario-bundles/existing?bundleKey={bundleKey}"
  },
  "cache": {
    "sessionCacheable": true,
    "refreshWhenFingerprintChanges": true
  }
}
```

---

## Structured validation findings

Validation endpoints return findings in a machine-readable shape:

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

`severity` is one of:

- `error` — bundle should not be deployed or used for swarm creation.
- `warning` — bundle can be saved, but authoring should review the issue.
- `info` — explanatory context only.

Clients must key automation on `code` and `path`, not on the human-readable
`message` text.

---

## Dry-run validation endpoints

### Validate an uploaded bundle without importing it

`POST /validation/scenario-bundles` consumes `application/zip` and returns
`application/json`.

This endpoint unpacks and validates the bundle in temporary storage only. It
does not create, replace, move, or delete any scenario bundle.

Response:

```json
{
  "ok": false,
  "source": "UPLOADED_ZIP",
  "scenarioId": "webauth-demo",
  "bundleKey": null,
  "bundlePath": null,
  "summary": {
    "errors": 1,
    "warnings": 0,
    "infos": 0
  },
  "findings": [
    {
      "category": "capabilities",
      "code": "CAPABILITY_MANIFEST_MISSING",
      "severity": "error",
      "path": "scenario.yaml:template",
      "message": "No capability manifest found for image 'example:latest' (controller).",
      "fix": "Install the matching capability manifest or update the image reference."
    }
  ]
}
```

### Validate an existing bundle by bundle key

`POST /validation/scenario-bundles/existing?bundleKey={bundleKey}` validates the
catalog entry identified by `bundleKey`. Use this for malformed bundles,
duplicate-id bundles, or bundles whose `scenario.id` is not safe to address.

Initial checks include:

- HTTP template files under `templates/http/**`,
- required HTTP template fields: `protocol`, `serviceId`, `callId`, `method`,
  `pathTemplate`,
- duplicate HTTP template `callId` values,
- `x-ph-call-id` references in `scenario.yaml` that have no matching template.

Future renderer-backed checks may extend this endpoint, but clients should treat
the `/validation/scenario-bundles*` endpoints as the canonical bundle validation
surface now.

---

## Defunct semantics

A bundle is `defunct` when it exists on disk but must not be usable for swarm creation.

Examples:

- malformed `scenario.yaml`
- missing required `id`
- missing `template`
- missing controller or bee image
- missing capability manifest for a referenced image
- duplicate scenario id across multiple bundles

The important rule is:

- if the bundle is present on disk but not usable, it is still returned by `GET /api/templates`
  with `defunct=true` and a reason

---

## Duplicate ids

Duplicate ids are handled as a bundle-level conflict.

If two or more discovered bundles resolve to the same scenario `id`:

- all conflicting bundles are returned,
- all conflicting bundles are marked `defunct=true`,
- all conflicting bundles expose a `defunctReason` that explains the duplicate-id conflict
  and identifies the other conflicting bundle path(s)

No conflicting bundle is considered selectable.

### Quarantine interaction

`scenarios/quarantine/` is a special bundle folder.

If a bundle is moved under `quarantine/`:

- it is still returned by `GET /api/templates`
- it is still validated and can still be `defunct` for its own bundle problems
- it is treated as non-runnable
- it is ignored for duplicate-id conflict detection against non-quarantined bundles

This allows an operator to isolate a broken or conflicting bundle without deleting it.

---

## Malformed bundles

If a bundle cannot be parsed into a usable `Scenario` object:

- it is still returned by `GET /api/templates`
- `id` may be `null`
- `name` falls back to the bundle folder name or relative bundle path
- `controllerImage` is `null`
- `bees` is `[]`
- `defunct=true`
- `defunctReason` contains the parse/validation exception message in a user-visible form

Malformed bundles must not break reload for healthy bundles.

---

## UI step 1

Initial UI behaviour:

- show all bundle entries returned by `GET /api/templates`
- show `defunct` entries as greyed-out but still clickable for inspection
- show a clear `DEFUNCT` warning badge
- show `defunctReason` inline
- do not allow creating a swarm from defunct entries
- in the Scenarios admin view, allow bundle-level actions for `defunct` entries:
  `Move to quarantine`, `Download bundle`, `Delete bundle`
- do not require a valid `scenario.id` for those bundle-level actions

## Bundle-addressed admin actions

Some bundle operations cannot rely on `scenario.id`, because malformed or duplicate-id bundles
may not have a usable id.

For those cases, admin actions operate by `bundleKey` / bundle path identity rather than by scenario id.

This applies to:

- move bundle to another folder
- move bundle to `quarantine/`
- download bundle
- delete bundle

### Current action policy

Bundle-level admin actions are intentionally split from any future repair/edit flow.

- bundles with missing `id` or conflicting duplicate `id` are still fully inspectable
- those bundles support `Move to quarantine`, `Download bundle`, and `Delete bundle`
- those bundles do **not** support repair editing in this step

The reason is simple:

- read-side identity is `bundleKey`
- malformed or duplicate-id bundles do not have a stable write-side `scenario.id`
- adding edit routes on `scenario.id` for those cases would reintroduce an inconsistent contract

Future repair editing, if needed, must also be bundle-addressed rather than id-addressed.
