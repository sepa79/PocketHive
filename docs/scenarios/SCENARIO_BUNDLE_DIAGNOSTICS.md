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
