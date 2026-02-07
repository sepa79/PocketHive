# Scenario Bundle Runtime — Plan

> Status: in progress  
> Scope: Scenario Manager, Swarm Controller, Request Builder, Hive UI (no changes to worker JSON contracts).

This plan introduces a **directory‑based scenario bundle** that keeps all
scenario assets together (scenario YAML, SUTs, HTTP templates, datasets),
then materialises a **per‑swarm runtime directory** that is mounted into all
workers.

The goal is to make scenarios easy to move around (download/upload/zip) while
keeping volumes explicit and plan‑driven (NFF).

---

## 1. Goals & constraints

- **Bundle as SSOT**  
  A scenario bundle is the single source of truth for:
  - Scenario YAML (`scenario.yaml` or similar).
  - Protocol templates (HTTP/TCP/ISO/SOAP/etc).
  - SUT environment overrides (optional).
  - Any datasets (Redis, files, etc.).

- **Per‑swarm runtime dir**  
  When a swarm is started from a scenario, Scenario Manager:
  - creates a runtime directory for that swarm under a configured root, and  
  - copies (or symlinks) the bundle contents there.

- **Volume wiring is explicit**  
  - Swarm Controller passes a shared host path into bees via
    `config.docker.volumes`.  
- Worker configs refer only to container paths (e.g. `/app/scenario/http-templates`);
    there are no implicit host defaults or fallbacks.

- **Download/upload supported**  
  - Users can download a scenario bundle (zip) from Hive.  
  - Users can upload a bundle zip to import or update a scenario.

---

## 2. Bundle layout (authoring‑time)

Target layout for a bundle on disk or in a zip:

```text
my-scenario/
  scenario.yaml            # Scenario (template + plan)
  templates/               # Protocol templates (YAML/JSON)
    http/default/*.yaml
    tcp/*.yaml
    iso/*.yaml
    soap/*.yaml
  sut/                     # Optional SUT overrides for this scenario
    sut-environments.yaml
  datasets/                # Optional data for inputs (redis, files, ...)
    redis/*.json
  docs/                    # Optional human docs
    notes.md
```

Notes:

- Scenarios remain YAML documents matching the existing `Scenario` contract;
  no schema changes here.  
- HTTP templates are already supported as `.yaml` or `.json`.  
- SUT environments can already be loaded from a directory of `*.yaml` files.

---

## 3. Runtime root & per‑swarm directories

Introduce a shared **runtime root** on the host:

- Host path (provided via env):  
  `POCKETHIVE_SCENARIOS_RUNTIME_ROOT` (e.g. `/opt/pockethive/scenarios-runtime`).
- Container destination (fixed):  
  `/app/scenarios-runtime`.
- Mounted into:
  - Scenario Manager (read/write at `/app/scenarios-runtime`).  
  - Swarm Controller (read at `/app/scenarios-runtime`).  
  - Bees via `config.docker.volumes` when a scenario demands it.

When starting a swarm from a scenario:

- Scenario Manager materialises the bundle into:
  - `${runtime-root}/${swarmId}` (or `${runtime-root}/${swarmId}-${runId}` if needed).
- Workers see the bundle under a consistent container path, for example:
  - Host: `${runtime-root}/${swarmId}`  
  - Container: `/app/scenario`

---

## 4. Scenario Manager changes

- [x] **Runtime root configuration**
  - [x] Runtime root destination is hardcoded to `/app/scenarios-runtime`; the host bind-mount source is provided via `POCKETHIVE_SCENARIOS_RUNTIME_ROOT`.

- [x] **Bundle location & registration**
  - [x] Define where bundles live on disk (`scenarios/**` at repo root;
        configurable via the `scenarios.dir` property for runtime).
  - [x] Map `scenarioId` → bundle path; document the layout.

- [x] **Materialise per‑swarm runtime dir**
  - [x] On swarm creation/start from scenario:
    - [x] Create `${runtime-root}/${swarmId}`.  
    - [x] Copy (or symlink, where allowed) bundle contents into that dir.
  - [x] Ensure idempotent behaviour (re‑starting the same swarm reuses or refreshes
        the same runtime dir without leaking stale files).

- [x] **Enrich SwarmPlan with volume + config paths**
- [x] For each Request Builder bee (and other workers as needed):
    - [x] Append a `config.docker.volumes` entry  
          `${runtime-root}/${swarmId}:/app/scenario:ro`.  
    - [x] Set `config.templateRoot: /app/scenario/http-templates`
          in the canonical Redis dataset demo bundle.
  - [ ] For bees that need datasets (e.g. Redis, file inputs), map their config to
        subpaths under `/app/scenario/datasets`.  
  - [ ] Optionally allow SUT environments per scenario by setting
        `pockethive.sut.environments-path: /app/scenario/sut` when desired.

---

## 5. Swarm Controller / workers

Most plumbing already exists; tasks here are mainly documentation and validation:

- [ ] **Volume semantics**
  - [ ] Verify Swarm Controller passes `config.docker.volumes` into `WorkerSpec.volumes`
        and that both `DockerSingleNodeComputeAdapter` and `DockerSwarmServiceComputeAdapter`
        honour them for workers.
  - [ ] Add a small smoke test (optional) that uses a temporary host dir, mounts it,
        and asserts that a file is visible inside the worker container (via logs or a
        simple “cat on start” pattern).

- [ ] **Scenario bundle awareness**
  - [ ] Document the expectation that workers read under `/app/scenario/**` when
        scenarios provide bundle content (no hard‑coded `/app/templates/http` etc.).

No worker JSON contracts change; this is all file‑system wiring.

---

## 6. Hive UI & APIs — download/upload

### 6.1 Scenario Manager APIs

- [x] **Bundle download**
  - [x] Add `GET /scenarios/{id}/bundle` that returns a zip of the bundle directory.

- [x] **Bundle upload**
  - [x] Add `POST /scenarios/bundles` to create a new scenario from an uploaded zip.  
  - [x] Add `PUT /scenarios/{id}/bundle` to replace/update an existing scenario’s bundle.
  - [x] Validate:
    - [x] Zip structure matches expected bundle layout.  
    - [x] `scenario.yaml` parses into a valid `Scenario`.  
    - [ ] Optional: run `scenario-templating-check` for HTTP templates/SUTs and fail fast
          on inconsistencies.

### 6.2 Hive UI

- [x] **Scenario list integration**
  - [x] Extend existing Scenario Manager UI (or add a new view) to list scenarios
        with actions:
        - [x] “Download bundle” → save zip.  
        - [x] “Upload bundle” → open file picker, POST to Scenario Manager.

- [x] **Optional: inline editing**
  - [x] Allow opening `scenario.yaml` in the existing Monaco‑style editor.  
  - [x] When user saves:
    - [x] POST updated `scenario.yaml` back into the bundle via Scenario Manager.
    - [ ] Optional: automatically regenerate the runtime dir or mark running swarms
          as needing restart.

---

## 7. Docs & tooling

- [ ] **Docs**
- [x] **Docs**
  - [x] Add a “Scenario bundles” section to `docs/scenarios/README.md` linking to this plan.  
  - [x] Describe bundle layout and recommended paths (`/app/scenario/...`) in
        `SCENARIO_PATTERNS.md` and `SCENARIO_TEMPLATING.md`.

- [ ] **Templating check tool**
  - [ ] Extend `tools/scenario-templating-check` with a `--bundle-root` mode that:
    - [ ] Takes a bundle directory.  
    - [ ] Validates `scenario.yaml`, HTTP templates, and SUT files together.  
    - [ ] Optionally runs the same checks Scenario Manager performs on upload.

---

## 8. Implementation order

1. **Config & docs**
   - [x] Introduce `pockethive.scenarios.runtime-root` and document it.
2. **Scenario Manager runtime dirs**
   - [x] Implement runtime dir materialisation and SwarmPlan volume/config path enrichment.
3. **Volume verification**
   - [x] Add/adjust tests to prove volumes are correctly mounted for workers.
4. **Download/upload APIs**
   - [x] Build Scenario Manager endpoints for bundle zips.
5. **Hive UI**
   - [x] Add bundle download/upload controls and wire them to the new APIs.
6. **Optional**
   - [x] Inline scenario editing in UI built on top of bundles.
