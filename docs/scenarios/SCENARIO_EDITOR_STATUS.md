# Scenario Editor — Current State vs Intended Behaviour

This document is the **single place** to track what the Hive **Scenarios** editor
currently does, what it *should* do (per the scenario contract), and what is
missing or buggy.

**Scope**
- UI: `ui/src/pages/ScenariosPage.tsx` (the “Scenarios” page)
- Shared config patch modal: `ui/src/components/ConfigUpdatePatchModal.tsx`
- Capability-driven config: `docs/architecture/workerCapabilities.md`
- Authoring contract: `docs/scenarios/SCENARIO_CONTRACT.md`
- Plan semantics: `docs/scenarios/SCENARIO_PLAN_GUIDE.md`

Out of scope: runtime worker behaviour, control-plane contracts, routing keys.

---

## 1) What the Scenario Editor does today

### 1.1 Scenario bundle operations
- Lists scenarios (bundles) from Scenario Manager.
- Downloads / uploads / replaces scenario bundle ZIPs.
- Creates a new scenario bundle (minimal metadata).

### 1.2 YAML (single source of truth)
- Loads and saves the raw `scenario.yaml` through Scenario Manager.
- Provides a Monaco-backed full-YAML editor.

### 1.3 Plan editor (timeline)
- Visual editing of `plan`:
  - swarm steps + per-bee timelines
  - step types: `config-update`, `start`, `stop`
  - “Edit config” per `config-update` step via the capability-driven modal
- Stores plan edits back into YAML.

Important implementation detail:
- The UI normalises time values to a “simple” format (`10s`, `5m`, `1h`) even if
  the YAML used `PT…` ISO-8601 durations.

### 1.4 Swarm template editor (basic)
- Visualises bees and queue wiring (`work.in`/`work.out`).
- Edits:
  - `template.image` (controller image)
  - `template.bees[].instanceId`, `role`, `image`, `work.in`, `work.out`
  - `config.inputs.type` selector (when options exist in capabilities)
- Offers “Edit config” for a bee (opens the capability-driven modal).

### 1.5 HTTP templates editor (bundle files)
- Lists HTTP template files under the bundle and allows editing/saving the YAML.
- Can create a new template file.
- “Edit body via schema” for HTTP templates (schema file chosen from bundle).

### 1.6 Schema-backed “body” helper (authoring aid)
- Supports a small schema-driven editor for:
  - generator message body (when a `schemaRef` exists in the scenario YAML)
  - HTTP template body (when `schemaRef` exists in the template YAML)
- Reads/writes schema files in the bundle.

---

## 2) What it should do (baseline expectations)

Baseline is “**YAML-as-SSOT**” with **safe round-tripping**:

1) **No data loss**
- Any UI action must preserve unrelated fields in YAML.
- “Edit config” must not drop unknown keys (for example `interceptors`, `docker.volumes`, custom maps).

2) **Contract coverage**
- The editor should allow manipulating the main scenario contract areas:
  - scenario metadata (`id`, `name`, `description`)
  - `template` (controller image, bees list)
  - `bees[].work`, `bees[].env`, `bees[].config` (including subtrees not covered by capabilities)
  - `trafficPolicy` (at least view + safe edit entry points)

3) **Capability-driven config as a convenience layer**
- Capabilities-driven UI should be an accelerator, but never the only way to edit important fields.
- Complex maps (JSON blobs, headers maps, interceptor configs) need a usable editor surface if they are expected to be edited often.

4) **Authoring-quality UX**
- Editor operations should be deterministic and predictable:
  - stable modal sizing
  - no surprise “normalisation” changes unless explicitly requested
  - clear scoping (template config vs plan patch vs runtime knobs)

---

## 3) Known correctness bugs (must-fix)

### 3.1 Data loss: bee template “Edit config” overwrites entire `config`
Current behaviour in the Swarm template editor:
- The capability modal produces a **patch** object containing only fields it knows about.
- Applying that result currently **replaces** the bee’s `config` with this patch.

Impact:
- Any `config` subtrees not represented by capabilities are silently dropped, e.g.:
  - `config.interceptors.*`
  - `config.docker.volumes`
  - any additional worker config the capabilities don’t model

Expected:
- Treat the modal output as a patch and **merge** it into the existing config
  (or keep the untouched original for unknown paths).

### 3.2 Plan time normalisation rewrites user intent
The UI normalises `PT…` durations into `Xs` strings.

Impact:
- “Noisy diffs” in YAML even when semantics are unchanged.
- Users lose the duration style they authored.

Expected:
- Either preserve the original representation, or make normalisation explicit
  (opt-in).

---

## 4) Gaps / missing features (high impact)

### 4.1 Interceptors are not editable in structured UI
The scenario contract supports `config.interceptors`, and scenarios rely on it
(for example Redis uploader).

Current state:
- Capability manifests do not expose `config.interceptors.*`.
- The UI therefore provides no structured way to view/edit interceptors.
- YAML edit is the only option.

Desired:
- A dedicated “Interceptors” section (even if it starts as a generic map editor),
  or capability coverage for interceptor configs if they are meant to be first-class.

### 4.2 Docker volumes and env vars are missing from structured UI
The contract supports:
- `bees[].env`
- `config.docker.volumes`

Current state:
- Not represented in capabilities, not editable in UI (except raw YAML).

Desired:
- Minimal, safe editors:
  - env as key/value list editor
  - volumes as list editor with basic validation (`host:container[:mode]`)

### 4.3 Editing complex maps is too coarse
Several important config fields are currently “blob edits”:
- `message.headers` is edited as a JSON textarea
- similar for other `json` / `text` fields in capabilities

Impact:
- Features like `pickWeighted(...)` UI are hard to surface because values live
  inside a JSON blob, not as a dedicated field.

Desired:
- A “map editor” for common maps (headers, properties, etc.) with:
  - per-key rows
  - type hints (string/number/bool/json)
  - optional helpers (e.g. weighted-choice widget when value matches `pickWeighted*`)

---

## 5) Documentation drift / unfinished plans to reconcile

These docs exist and contain editor-related plans or promises; they should be
treated as **inputs**, not as “current behaviour”:

- `docs/inProgress/scenario-sut-editor-plan.md`
  - Contains a React Flow + side-panel editor plan. Some ideas exist in the
    current editor, but the plan is not implemented as written.
- `docs/pockethive_scenario_builder_mvp/ui_scenario_builder_mvp_plan/*`
  - A micro-frontend “Scenario Builder” plan (tracks/blocks) that does not match
    the current `plan` editor implementation.
- `docs/inProgress/scenario-bundle-runtime-plan.md`
  - Tracks bundle upload/download + optional validation-on-upload; parts are done,
    but the optional validation and some bundle conventions remain open.

---

## 6) Recommended build plan (sequence)

1) **Safety first**
- Fix data loss in template config edits (merge patch vs replace).
- Add guardrails/tests for “unknown subtree preserved”.

2) **Make missing contract areas editable**
- Interceptors: read-only view + safe edit (even if raw YAML slice at first).
- Env + docker.volumes: simple editors.

3) **Make complex config usable**
- Introduce a generic map editor for JSON blobs (headers etc.).
- Layer the “weighted-choice” widget into that map editor.

4) **Reduce YAML churn**
- Preserve plan time representation or make normalisation opt-in.

---

## 7) Quick checklist for future work

- [ ] No UI action drops YAML subtrees.
- [ ] Interceptors visible/editable without raw YAML.
- [ ] Env + volumes supported.
- [ ] Map editor for headers/properties exists (unblocks weighted UI visibility).
- [ ] Plan time formatting does not rewrite user YAML unless requested.

