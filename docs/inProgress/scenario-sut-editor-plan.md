# Scenario & SUT Editors — Plan

> **Scenario editor: superseded**  
> The Scenario editor portion of this plan is superseded by `docs/scenarios/SCENARIO_EDITOR_STATUS.md`.
> This file remains as an in-progress plan for the **SUT editor** and for historical context.

> Status: in progress  
> Scope: UI, Scenario Manager, docs (no changes to workers or control‑plane contracts)

This plan introduces two complementary editors:

- a **System Under Test (SUT) editor** to manage `SutEnvironment` / `SutEndpoint` objects, and  
- a **Scenario editor** to visualise and tweak swarm templates.

Both are **views over YAML**, not new sources of truth. Scenario and SUT files remain
in `scenarios/**` and `sut-environments*.yaml` and are still
intended to be edited by humans and AI directly when needed.

---

## 1. Design principles

- **YAML as SSOT**  
  All configuration continues to live in YAML files under the shared `scenarios` root and Scenario Manager.
  Editors always load → modify → write YAML; there is no extra DB or shadow model.

- **Monaco for text, React Flow for graphs**  
  - Monaco gives “VS Code‑like” editing: syntax highlighting, autocomplete, validation.  
  - React Flow gives a simple graphical view: nodes, edges, drag‑and‑drop.

- **Round‑tripping and diffs**  
  - Graphical actions are translated into **small, deterministic YAML patches** so Git diffs
    stay readable.  
  - At any time users can open the raw YAML in Monaco and see the exact file content.

- **KISS & NFF**  
  - No implicit behaviour; editors only manipulate explicit fields.  
  - If an action cannot be expressed as a small, clear YAML change, the editor must not offer it.

---

## 2. SUT Editor (environments + endpoints)

### 2.1 Goals

- Make it easy for support/devs to:
  - browse existing SUT environments,  
  - add / edit / clone environments and endpoints, and  
  - see how URLs are structured without reading raw YAML.

### 2.2 Backend model (already present)

- `common/swarm-model`:
  - `SutEnvironment { id, name, type, Map<String,SutEndpoint> endpoints }`
  - `SutEndpoint { id, kind, baseUrl }`
- `scenario-manager-service`:
  - YAML registry `sut-environments.yaml` (+ optional directory of files).  
  - `SutEnvironmentService` loads them; `SutEnvironmentController` exposes
    `GET /sut-environments` and `GET /sut-environments/{id}`.

### 2.3 Plan — SUT editor

- [ ] **Schema + validation**
  - [ ] Add `docs/scenarios/sut-environments.schema.json` describing:
    - env: `id`, `name`, `type`, `endpoints` map.  
    - endpoint: `id`, `kind`, `baseUrl`.
  - [ ] Wire schema into Monaco in UI and into VS Code via `yaml.schemas`.

- [ ] **Graphical view (React Flow)**
  - [ ] Render each `SutEnvironment` as a card node (name, id, type).  
  - [ ] Render each `SutEndpoint` as a child / sub‑node in the card (id, kind, baseUrl snippet).  
  - [ ] Support simple filters (by type, by substring match on id or baseUrl).

- [ ] **Editing flows**
  - [ ] “Add environment” dialog → appends a new entry into YAML.  
  - [ ] “Add endpoint” button on a card → adds a new endpoint in that env:
    - fields: `id`, `kind`, `baseUrl`.  
  - [ ] “Edit” opens side panel with Monaco bound to just that env’s YAML slice.  
  - [ ] “Clone environment” → copies env under a new `id`, preserving endpoints.

- [ ] **Persistence**
  - [ ] Scenario Manager: add a small “write” API (e.g. `PUT /sut-environments/{id}` and `POST /sut-environments`), writing back to `sut-environments.yaml` or a configured directory.  
  - [ ] UI: send edits as whole‑env payloads; the backend owns YAML formatting.

---

## 3. Scenario Editor (templates + SUT bindings)

### 3.1 Goals

- Give support/devs a visual way to:
  - see bees and their queues,  
  - attach SUT endpoints to workers, and  
  - tweak obvious knobs (rates, paths) without hand‑editing YAML.

### 3.2 Graph model

- **Nodes**
  - One node per bee in `template.bees[]`:
    - label: `role` (generator, moderator, processor, request-builder, postprocessor, …)  
    - icon hints for role category (input, processor, output).
  - Optional “SUT lane” showing the chosen `SutEnvironment` and its endpoints for this swarm.

- **Edges**
  - `work.out` → `work.in` port map wiring rendered as edges, just like the runtime topology.
  - Optional extra edge from endpoint node → worker node to indicate a SUT binding.

### 3.3 Plan — Scenario editor

- [ ] **Schema + validation**
  - [ ] Add `docs/scenarios/scenario.schema.json` covering:
    - root fields: `id`, `name`, `description`.  
    - `template.image`, `template.bees[].role/image/work/config`.  
    - `config.baseUrl`, `config.inputs/outputs`, and optional `sut` blocks.
  - [ ] Wire schema into Monaco in UI / VS Code.

- [ ] **Visual wiring (React Flow)**
  - [ ] Build a graph from YAML:
    - nodes from `template.bees`.  
    - edges from matching `work.out` and `work.in` port map suffixes.
  - [ ] Show SUT lane when a `sutId` is selected for the swarm:
    - env card + endpoints from SUT registry.

- [ ] **SUT binding via drag‑and‑drop**
  - [ ] Allow dragging an endpoint node (e.g. `default`) onto a HTTP‑aware worker (processor, HTTP Builder).  
  - [ ] On drop, patch the worker config:
    - set / update `baseUrl` to `{{ sut.endpoints['<endpointId>'].baseUrl }}` or
      `{{ sut.endpoints['<endpointId>'].baseUrl }}/api/...`.  
    - ensure swarm’s `sutId` is set if not already.
  - [ ] Draw a thin “binding” edge from endpoint → worker with tooltip indicating the template.

- [ ] **Per‑bee knobs**
  - [ ] For generator / Redis inputs:
    - expose the `ratePerSec` slider in the node sidebar, mapping straight to `inputs.scheduler.ratePerSec` or `inputs.redis.ratePerSec`.  
  - [ ] For processor:
    - allow switching between `THREAD_COUNT` and `RATE_PER_SEC`, and editing `ratePerSec` / `threadCount`.

- [ ] **Monaco integration**
  - [ ] Node click opens a side panel showing that bee’s config fragment in Monaco, still validated by the schema.  
  - [ ] A “View full YAML” toggle opens the whole scenario file for advanced edits.

- [ ] **Persistence & diffs**
  - [ ] Scenario Manager: add write API for scenarios (e.g. `PUT /scenarios/{id}/raw`).  
  - [ ] UI: send the updated YAML; backend writes back to the correct file.  
  - [ ] Keep YAML formatting stable where possible so Git diffs remain minimal.

---

## 4. Next steps

- [ ] Add both this plan and `sut-environments-plan.md` to `docs/index.md` so they are discoverable.  
- [ ] Implement schemas + VS Code wiring first (gives immediate value without UI work).  
- [ ] Introduce Monaco‑based text editing in UI for SUTs and scenarios.  
- [ ] Then layer React Flow‑based SUT and Scenario editors on top, starting with read‑only visualisation and moving to editing once the patching logic is well‑tested.
