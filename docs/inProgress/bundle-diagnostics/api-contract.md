# API Contract

All endpoints are served by `scenario-manager-service` and proxied via nginx at `/scenario-manager/`.

---

## Changed: `GET /scenarios`

Query param `includeDefunct` (default `false`) behaviour unchanged. Response shape gains two new fields on each item.

### Response (200)
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
    "defunctReason": "No capability manifest found for image 'io.pockethive/generator:0.15.11' (bee: generator). Check that this image version is installed."
  }
]
```

New fields:
- `defunct` (boolean, always present) — `true` if the bundle loaded but cannot be used to create a swarm
- `defunctReason` (string or null) — plain-English reason; null when `defunct` is false

---

## Changed: `GET /api/templates`

Used by `CreateSwarmModal`. Previously only returned available (non-defunct) scenarios. Now returns all scenarios including defunct, with reason.

> **Behaviour change note:** This endpoint previously filtered out defunct scenarios. It now includes them with `defunct: true`. The only current consumer is `CreateSwarmModal` in ui-v2, which is updated as part of this feature. If any other consumer is added in future it must handle the `defunct` flag. ui-v1 does not call this endpoint.

### Response (200)
```json
[
  {
    "id": "local-rest",
    "name": "Local REST",
    "folderPath": null,
    "description": "Simple local REST swarm",
    "controllerImage": "swarm-controller:latest",
    "bees": [
      { "role": "generator", "image": "generator:latest" }
    ],
    "defunct": false,
    "defunctReason": null
  },
  {
    "id": "ctap-iso8583-request-builder-demo",
    "name": "CTAP ISO8583",
    "folderPath": "bundles/ctap-iso8583-rbuilder-scenario",
    "description": null,
    "controllerImage": "swarm-controller:0.15.11",
    "bees": [
      { "role": "generator", "image": "generator:0.15.11" }
    ],
    "defunct": true,
    "defunctReason": "No capability manifest found for image 'io.pockethive/generator:0.15.11' (bee: generator). Check that this image version is installed."
  }
]
```

New fields: same as `/scenarios` above.

---

## New: `GET /scenarios/failures`

Returns bundles that could not be loaded at all during the last `reload()`. These bundles have no scenario id and cannot appear in the scenarios list.

### Response (200)
```json
[
  {
    "bundlePath": "bundles/my-broken-scenario",
    "reason": "Could not read scenario file: mapping values are not allowed here at line 5, column 8"
  },
  {
    "bundlePath": "bundles/old-duplicate",
    "reason": "Duplicate scenario id 'local-rest' — another bundle at 'bundles/local-rest' was loaded instead"
  }
]
```

Fields:
- `bundlePath` (string) — path relative to the scenarios root, using forward slashes
- `reason` (string) — plain-English reason

Returns empty array `[]` when all bundles loaded successfully. Never returns 4xx/5xx for normal operation.

---

## Unchanged endpoints

The following endpoints are not changed by this feature:

- `GET /scenarios/defunct` — still works, now also returns `defunct` and `defunctReason` fields
- `GET /scenarios/{id}` — unchanged
- `POST /scenarios/reload` — unchanged, still triggers a full reload; failures map is repopulated
- All bundle editing endpoints (`/variables`, `/suts`, `/templates`, etc.) — unchanged

---

## Backwards compatibility

### All API changes are additive

`GET /scenarios` and `GET /api/templates` gain two new fields — `defunct` and `defunctReason`. Existing consumers that do not read these fields are unaffected.

### Full consumer inventory

| Consumer | Endpoint | Impact |
|---|---|---|
| `ui-v2/src/pages/hive/CreateSwarmModal.tsx` | `GET /api/templates` | Updated as part of this feature |
| `ui/src/pages/hive/SwarmCreateModal.tsx` | `GET /api/templates` | **Requires one-line fix — see below** |
| `ui/src/lib/scenarioManagerApi.ts` | `GET /scenarios` | Safe — `normalizeSummary()` only reads `id`, `name`, `folderPath`, ignores new fields |
| `ui-v2/src/lib/scenariosApi.ts` | `GET /scenarios` | Updated as part of this feature |
| Orchestrator | `GET /scenarios/{id}`, `POST /scenarios/{id}/runtime` | Not changed |
| VSCode extension `vscode-pockethive/src/api.ts` | Generic fetch wrapper only | Does not call these endpoints directly — safe |

---

## ui-v1 SwarmCreateModal — required one-line fix

`ui/src/pages/hive/SwarmCreateModal.tsx` calls `GET /api/templates`. After the backend change, defunct templates appear in the response. The current `normalizeTemplate()` does not read `defunct`, so defunct entries would silently appear as selectable options in ui-v1's Create Swarm modal — a user could select one and get a confusing failure at the orchestrator.

**Fix:** Add one line to `normalizeTemplate()` in `ui/src/pages/hive/SwarmCreateModal.tsx`:

```typescript
function normalizeTemplate(entry: unknown): ScenarioTemplate | null {
  if (!entry || typeof entry !== 'object') return null
  const value = entry as Record<string, unknown>
  if (value.defunct === true) return null   // ← add this line
  // ... rest unchanged
}
```

This preserves existing ui-v1 behaviour exactly — defunct templates remain invisible in ui-v1, identical to today.

This fix must be included in the same PR as the backend change.
