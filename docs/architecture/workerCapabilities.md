# Worker Capability Catalogue (Simplified, Scenario‑Manager–Centric)

This spec defines how PocketHive exposes worker **capabilities** without embedding any capability code in workers.  
**Only the Scenario Manager (SM)** holds and serves capability data, loaded from files and matched to **container images**.

---

## 1) Goals

- **Single source of truth:** Scenario Manager owns file-based capabilities and loads them
  into an in-memory catalogue at startup.
- **No worker code:** Bees do not emit capabilities; they stay dumb runtime artifacts.
- **Easy authoring:** Hive UI and Scenario Editor fetch capabilities from SM alongside swarm templates.
- **Deterministic authoring:** Scenario validation resolves template image references against
  the loaded capability manifests.

---

## 2) Scope & Ownership

- **Owner:** Scenario Manager (the service and its build pipeline).
- **Consumers:** Hive UI, Scenario Editor, Scenario Manager validation, PocketHive MCP,
  and optional CLI clients.
- **Out of scope:** Workers/Bees do not publish, embed, or negotiate capabilities.

---

## 3) Capability Catalogue (Files → In-Memory Index)

### 3.1 File format (per image)
One JSON (or YAML) **manifest per image**:

```json
{
  "schemaVersion": "1.0",
  "capabilitiesVersion": "3",
  "image": {
    "name": "ghcr.io/pockethive/generator",
    "tag": "1.12.0",
    "digest": "sha256:abcd..."   // optional but preferred if known
  },
  "role": "generator",
  "config": [
    { "name": "inputs.type", "type": "string", "liveMutable": false, "required": true,
      "options": ["SCHEDULER", "REDIS_DATASET"] },
    { "name": "rate", "type": "integer", "liveMutable": true, "required": true, "min": 1, "max": 100000,
      "ui": {"step": 10, "unit": "msg/s"} },
    { "name": "payloadTemplate", "type": "string", "liveMutable": true, "required": true, "multiline": true }
  ],
  "actions": [
    { "id": "warmup", "label": "Warm Up", "params": [] },
    { "id": "flush", "label": "Flush Buffers", "params": [] }
  ],
  "panels": [
    { "id": "wiremockMetrics" },
    { "id": "rateGraph" }
  ]
}
```

**Notes**
- Manifests are indexed by canonical `image.name` and, when declared, by `image.digest`.
  Scenario template validation matches canonical image names; the capabilities API also
  supports direct digest lookup.
- `config`/`actions` are **semantic contracts**, not URLs; UI builds forms and buttons from them.
- `config[].type` has one canonical vocabulary only: `string`, `boolean`, `number`,
  `integer`, `json`. Do not use aliases such as `text` or `int`; capability
  manifest loading fails on unsupported type values.
- `config[].liveMutable` is required and must be explicit. `true` means the field can be offered by
  runtime config editing; `false` means the field is authoring/startup configuration only. Runtime
  enforcement may narrow an offered field further: `inputs.redis.listName` is shown by the generic
  editor but may be changed only for an already-disabled, single-source Redis dataset worker. The UI
  must block submission unless swarm status is explicitly `STOPPED`; MCP agents must establish the same
  precondition with `swarm_get` rather than inferring completion from an accepted Stop request.
  For `inputs.*` / `outputs.*`, `true` is allowed only on operational live IO fields documented in
  `docs/ARCHITECTURE.md`. Except for the documented disabled-only Redis single-source `listName`, IO
  wiring fields such as selectors, endpoints, source lists, credentials, and output routes must be
  `liveMutable: false`.
- Bundled capability manifests must not publish `config[].default` values for public scenario config.
  Runtime behaviour must come from explicitly provided scenario configuration, not from capability
  manifest defaults or worker fallback defaults.
- `config` may include IO selector fields such as `inputs.type` / `outputs.type`. IO-specific
  knobs (e.g. scheduler vs Redis dataset vs Redis output) are modelled as separate manifests with
  `ui.ioType` + `ui.ioScope` set (`io.scheduler.latest.yaml`, `io.redis-dataset.latest.yaml`,
  `io.redis-output.latest.yaml`) and merged in the UI when selected `inputs.type`/`outputs.type`
  matches both values.
- `panels` are optional hints for rich UI; fallback renderer uses `config`/`actions` only.

### 3.2 Loading and validation

- Scenario Manager loads the individual JSON/YAML manifests from its configured
  `capabilities` directory into an in-memory catalogue.
- Loading rejects malformed manifests, unsupported config types, invalid live-mutability
  declarations, and duplicate image-name or digest keys.
- The new catalogue replaces the previous in-memory state only after every discovered
  manifest has loaded and validated successfully.
- Scenario bundle validation reports a missing manifest when a template references an
  image that the catalogue cannot resolve.

---

## 4) Scenario Manager API

UI already calls SM for swarm templates; it should fetch capabilities in the same pass.

### 4.1 Endpoints
- `GET /api/templates`  
  Returns authoring templates (as today), including each component’s image reference.

- `GET /api/capabilities`  
  - **Query:** `imageDigest` or `imageName`.
  - **Bulk mode:** `?all=true` to return the entire catalogue for local caching.
  - **Response:** the manifest(s) matching the provided image(s).

- `GET /api/authoring-contract`
  Returns the current authoring contract, capability summaries, templates, and a
  deterministic fingerprint.

- `GET /api/authoring-contract/fingerprint`
  Returns the lightweight contract fingerprint used to decide whether a cached
  authoring contract must be refreshed.

---

## 5) Authoring & UI Behaviour

- On project load (or template change), UI:
  1) `GET /api/templates`
  2) Extracts the **images** referenced in the template
  3) `GET /api/capabilities?all=true` and matches manifests to those images
- UI renders:
  - **Generic forms** from `config`
  - **Action buttons** from `actions`
  - **Rich panels** only when `panels.id` matches a registered UI module; otherwise ignore panels
- If an image in the template has **no matching manifest**, Scenario Manager bundle
  validation reports the missing capability and the authoring client must surface that
  validation result.

---

## 6) Orchestrator & Runtime

- Bees do **not** emit manifests at boot. Runtime remains simpler and deterministic.

---

## 7) Versioning & Governance

- **Schema:** `schemaVersion` changes only when the manifest structure changes.
- **Capabilities:** `capabilitiesVersion` must bump on any user-visible change to `config`, `actions`, or `panels`.
- **Provenance:** Manifests may declare an image digest. Scenario Manager can resolve
  capabilities by that digest or by canonical image name.

---

## 8) Integrity

- Manifest loading fails explicitly when any discovered file is invalid or when image-name
  or digest keys collide.
- Scenario validation checks template image references against the loaded catalogue.
- `/api/authoring-contract` computes a deterministic fingerprint from the current
  capability summaries and templates. Clients can compare it through
  `/api/authoring-contract/fingerprint` before refreshing cached authoring data.
- No component-side drift reporting is required in this Scenario-Manager-centric model.

---

## 9) Security & Trust

- Capability manifests are trusted deployment inputs owned by Scenario Manager and are
  validated before becoming active.
- Capability and authoring-contract API access is subject to Scenario Manager
  authorization.
- Image digests may be used where immutable image identity is required.

---
