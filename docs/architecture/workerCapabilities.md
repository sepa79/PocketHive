# Worker Capability Catalogue (Simplified, Scenario‑Manager–Centric)

This spec defines how PocketHive exposes worker **capabilities** without embedding any capability code in workers.  
**Only the Scenario Manager (SM)** holds and serves capability data, loaded from files and matched to **container images**.

---

## 1) Goals

- **Single source of truth:** Scenario Manager owns file-based capabilities per worker, and on runtime builds a catalogue of it in memory.
- **No worker code:** Bees do not emit capabilities; they stay dumb runtime artifacts.
- **Easy authoring:** Hive UI and Scenario Editor fetch capabilities from SM alongside swarm templates.
- **Deterministic execution:** Plans reference images; SM resolves images → capabilities.

---

## 2) Scope & Ownership

- **Owner:** Scenario Manager (the service and its build pipeline).
- **Consumers:** Hive UI + Scenario Editor (authoring), Orchestrator (validation), optional CLI.
- **Out of scope:** Workers/Bees do not publish, embed, or negotiate capabilities.

---

## 3) Capability Catalogue (Files → Bundle)

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
    { "name": "inputs.type", "type": "string", "default": "SCHEDULER",
      "options": ["SCHEDULER", "REDIS_DATASET"] },
    { "name": "rate", "type": "int", "default": 100, "min": 1, "max": 100000,
      "ui": {"step": 10, "unit": "msg/s"} },
    { "name": "payloadTemplate", "type": "string", "default": "", "multiline": true }
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
- `image` is the **match key** at runtime (prefer `digest` > `name+tag`).
- `config`/`actions` are **semantic contracts**, not URLs; UI builds forms and buttons from them.
- `config` may include IO selector fields such as `inputs.type` / `outputs.type`. IO-specific
  knobs (e.g. scheduler vs Redis dataset vs Redis output) are modelled as separate manifests with
  `ui.ioType` + `ui.ioScope` set (`io.scheduler.latest.yaml`, `io.redis-dataset.latest.yaml`,
  `io.redis-output.latest.yaml`) and merged in the UI when selected `inputs.type`/`outputs.type`
  matches both values.
- `panels` are optional hints for rich UI; fallback renderer uses `config`/`actions` only.

### 3.2 Bundle
- During SM build, all per-image manifests are **validated** and **packed** into:
  - `contracts/capabilities/catalogue-v{N}.json` (signed; versioned)
- CI fails if:
  - a listed template image lacks a manifest, or
  - schema validation fails.

---

## 4) Scenario Manager API

UI already calls SM for swarm templates; it should fetch capabilities in the same pass.

### 4.1 Endpoints
- `GET /api/templates`  
  Returns authoring templates (as today), including each component’s image reference.

- `GET /api/capabilities`  
  - **Query:** `imageDigest` (preferred) or `imageName` + `tag`  
  - **Bulk mode:** `?all=true` to return the entire catalogue for local caching.
  - **Response:** the manifest(s) matching the provided image(s).

- `GET /api/capabilities/catalogue`  
  Returns the signed `catalogue-v{N}.json` and metadata: `{ catalogueVersion, schemaVersion, publishedAt }`.

*(Exact paths can be adapted to current SM routing; the contract is “templates + capabilities served by SM”.)*

---

## 5) Authoring & UI Behaviour

- On project load (or template change), UI:
  1) `GET /api/templates`
  2) Extracts the **images** referenced in the template
  3) `GET /api/capabilities?images=...` (or `?all=true` if caching)
- UI renders:
  - **Generic forms** from `config`
  - **Action buttons** from `actions`
  - **Rich panels** only when `panels.id` matches a registered UI module; otherwise ignore panels
- If an image in the template has **no matching manifest**, UI:
  - warns the author,
  - blocks publishing (soft or hard per environment setting).

---

## 6) Orchestrator & Runtime

- Bees do **not** emit manifests at boot. Runtime remains simpler and deterministic.

---

## 7) Versioning & Governance

- **Schema:** `schemaVersion` changes only when the manifest structure changes.
- **Capabilities:** `capabilitiesVersion` must bump on any user-visible change to `config`, `actions`, or `panels`.
- **Catalogue:** `catalogueVersion` increments when SM publishes a new bundle.
- **Provenance:** Prefer image **digests** in templates; if tags are used, SM resolves them to digests when possible.

---

## 8) Drift & Integrity

- **Build-time guardrails (preferred):**
  - SM build checks that **every** image referenced by shipped templates has a manifest.
  - Optional: SM resolves tags → digests and writes them back to the catalogue for immutability.
- **Runtime observability (lightweight):**
  - SM exposes `/api/capabilities/catalogue` with `publishedAt` and `catalogueVersion` so UIs can cache/compare.
- **No component-side drift reporting** is required in this simplified model.

---

## 9) Security & Trust

- Sign the catalogue artifact; verify signature on SM start.
- Optionally pin images by **digest** in templates to prevent “tag drift.”

---
