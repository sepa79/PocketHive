# System Under Test (SUT) & Environments — Plan

> Status: **to be reviewed**  
> Scope: Scenario Manager, Orchestrator, Swarm Controller, UI, Worker SDK (templating)
>
> Review note: related SUT editor plan exists in `docs/toBeReviewed/scenario-sut-editor-plan.md`
> and should be reviewed together with this document for consistency.

This plan introduces **System Under Test (SUT) environments** as first‑class
objects that scenarios and swarms can bind to. The goal is to stop hard‑coding
target URLs in scenarios, and instead route traffic via a small, explicit SUT
model.

---

## 1. Problem statement

- Today scenarios and workers usually talk to:
  - a single hard‑coded endpoint (e.g. WireMock), or
  - ad‑hoc URLs embedded in templates / worker configs.
- Real systems typically expose **many endpoints per environment**:
  - multiple HTTP APIs, ISO/TCP listeners, admin UIs, feature‑specific LBs, etc.
- Different environments (dev, UAT, prod‑like, team sandboxes) are **subsets**
  of the full production platform and often mix **real + mocked** services.
- We want PocketHive to model:
  - *what* functionality an environment offers (capabilities),
  - *where* to send traffic for a given functionality (endpoints),
  - *which* environment a swarm should target,
  - without baking any of this into worker code.

---

## 2. SUT core concepts (v1)

The initial version is intentionally small and HTTP‑centric; non‑HTTP protocols
can extend the same shapes later.

- **SutEnvironment**
  - `id` — stable identifier (e.g. `wiremock-local`, `paytech-reltest`)
  - `name` — human label
  - `type` — free text: `sandbox`, `dev`, `team-dev`, `uat`, `prodlike`, …
  - `endpoints` — map of **named endpoints** in this environment

- **SutEndpoint** (v1: HTTP‑first)
  - `id` — key in the environment’s `endpoints` map (e.g. `accounts-api`)
  - `kind` — short string describing protocol / shape: `HTTP`, `ISO8583`, `MQ`, …
  - HTTP fields:
    - `baseUrl` — `https://reltest.example.com/accounts`
    - optional `defaultHeaders`, `authProfileId` (future)

Workers will *not* guess any of this. They will be told:

- which `sutId` the swarm bound to,
- which `targetEndpointId` (e.g. `accounts-api`) a given chain should use.

Templating will then use:

- `sut.id`, `sut.type`
- `sut.endpoints["accounts-api"].baseUrl`

---

## 3. High‑level flow

1. **Scenario Manager** hosts a small registry of `SutEnvironment` objects
   (backed by YAML on disk, same as scenarios/capabilities).
2. **UI**:
   - lists available SUT environments;
   - in the *Create swarm* modal, lets the user choose a SUT env per swarm.
3. **Orchestrator**:
   - records `sutId` in `Swarm` state;
   - resolves the SUT environment via Scenario Manager on **create swarm**;
   - applies SUT‑aware config templating, e.g.
     `baseUrl: "{{ sut.endpoints['public-api'].baseUrl }}/v1"`; and
   - passes the resolved config and `sutId` to the Swarm Controller via {@link SwarmPlan}.
4. **Swarm Controller**:
   - keeps the resolved `SutEnvironment` in its runtime context (for diagnostics and future use);
   - does not perform additional SUT templating for the `sut.endpoints[...]` pattern
     (that is owned by the Orchestrator).
5. **Workers / templating**:
   - see a plain, fully‑resolved `baseUrl` in their typed config and remain unaware of SUT details.

---

## 4. Incremental implementation plan

### 4.1 Documentation & contracts

- [x] Add this file as the **authoritative SUT plan** in `docs/architecture` (link from `docs/index.md`).
- [ ] Sketch minimal JSON/YAML contract for:
  - [ ] `SutEnvironment`
  - [ ] `SutEndpoint` (HTTP only)
- [x] Decide how `sutId` flows through:
  - [x] Scenario definitions → `sutId` chosen in UI.
  - [x] Orchestrator create API → `sutId` on `SwarmCreateRequest`.
  - [x] Swarm template/runtime state → `sutId` and `SutEnvironment` on `SwarmPlan`.
  - [x] Worker config → `baseUrl` resolved via
        `baseUrl: "{{ sut.endpoints['<id>'].baseUrl }}..."` at create time.

### 4.2 Scenario Manager — SUT registry (v1)

- [x] Add `scenario-manager-service/sut-environments.yaml` with a few examples:
  - [x] `wiremock-local` (existing local WireMock)
  - [x] one “real” demo env (e.g. `demo-http-sut`)
- [x] Implement a simple SUT registry:
  - [x] Load YAML (single file or directory of files) on startup
  - [x] `GET /sut-environments`
  - [x] `GET /sut-environments/{id}`
- [x] Wire into existing test infrastructure (e.g. load sample SUTs in e2e tests).

### 4.3 UI — choose SUT when creating a swarm

- [x] Extend UI API client to fetch `/sut-environments`.
- [x] In *Create Swarm* modal:
  - [x] Add a *System under test* dropdown listing SUT envs (id + name + type).
  - [x] Include chosen `sutId` in the `create swarm` request payload.
- [x] Show SUT env on swarm detail panel (read‑only, for now).

### 4.4 Orchestrator — store & pass SUT binding

- [x] Extend `Swarm` domain object to store `sutId`.
- [x] Adjust `SwarmController#create` REST handler:
  - [x] Accept optional `sutId`.
  - [x] Attach it to `Swarm` on creation.
- [x] When launching the swarm‑controller:
  - [x] Include `sutId` in the template metadata / initial config payload
        (SwarmPlan) sent to the controller bee.

### 4.5 Swarm Controller — inject SUT into workers

- [x] Extend `SwarmRuntimeCore` state with optional `sutId` and `SutEnvironment` (for diagnostics).
- [x] Keep support for `config.sut.targetEndpointId` via `enrichConfigWithSut(...)` for
      scenarios that explicitly opt into that pattern.
- [x] Do **not** perform additional templating for the `sut.endpoints[...]` form; that is resolved
      in the Orchestrator before the plan reaches the controller.

### 4.6 Workers & templating — first usage

- [ ] Update HTTP‑builder / Processor / Generator templates and docs to consistently show the
      `baseUrl: "{{ sut.endpoints['<id>'].baseUrl }}..."` pattern where SUTs are involved.
- [x] Migrate e2e scenarios away from hard‑coded URLs and onto SUT envs:
  - [x] `templated-rest.yaml` — processor now relies on injected SUT baseUrl instead of a literal.
  - [x] `redis-dataset-demo.yaml` — processor now relies on injected SUT baseUrl instead of a literal.

---

## 6. SUT visibility & editing in Hive UI (next steps)

> Goal: make SUTs first‑class objects on the Hive canvas and provide a small
> editor/viewer so operators can see and tweak SUT definitions without leaving
> PocketHive. This will eventually replace the ad‑hoc “Services” group.

### 6.1 SUT registry APIs (Scenario Manager)

- [x] `GET /sut-environments` → list of environments (id, name, type, endpoints summary).
- [x] `GET /sut-environments/{id}` → full environment definition.
- [x] (v1) `GET /sut-environments/raw` and `PUT /sut-environments/raw` for whole‑file YAML editing from the UI.
- [ ] (Later) fine‑grained `PUT/POST/DELETE /sut-environments` for per‑env editing.

### 6.2 UI: SUT picker & “active environments”

- [ ] Introduce a `SutEnvironmentContext` (or extend an existing context) that:
  - [ ] Fetches `/sut-environments` on startup.
  - [ ] Exposes `{ envs, activeIds, setActiveIds }`.
  - [ ] Persists `activeIds` in `localStorage` / cookie under a single key so SUT selection
        survives reloads.
- [ ] Replace the current “Services” cluster on the Hive page with a **Systems under test**
      panel:
  - [ ] Render one card per SUT env (name, type, endpoint count).
  - [ ] Provide a simple toggle “Show on hive map” that flips membership in `activeIds`.
  - [ ] WireMock becomes just one SUT entry defined in `sut-environments.yaml`.

### 6.3 UI: Rendering SUTs on the Hive graph

- [ ] For each active SUT env, create a synthetic component via `upsertSyntheticComponent`:
  - [ ] `id = sut.id`, `name = sut.name`, `role = 'sut'`, `queues = []`.
- [ ] Replace WireMock‑specific heuristics in `buildTopology` with SUT‑aware wiring:
  - [ ] For each worker component, if `config.baseUrl` starts with any
        `sut.endpoints[*].baseUrl` (no fallbacks), add an edge
        `{ from: workerId, to: sutId, queue: 'sut' }`.
  - [ ] Only draw edges/nodes for SUT ids present in `activeIds`.
- [ ] Ensure existing edge types (swarm‑control, work queues) are unchanged.

### 6.4 UI: SUT viewer (read‑only, then editable)

- [x] Add a “Systems under test” view:
  - [x] Left column: list envs (name, type, endpoint count).
  - [x] Right panel: details for the selected env:
    - [x] Endpoints table (id, kind, baseUrl).
    - [x] JSON dump of the selected env for quick inspection.
- [x] Link from Swarm Controller detail panel:
  - [x] When a SC reports a `sutId`, show a “View SUT” / link to `/sut?sutId=...`.
- [x] Phase 2 (v1): enable editing via raw YAML:
  - [x] Swap read‑only YAML block for a Monaco editor over the full `sut-environments.yaml`.
  - [x] Save via `PUT /sut-environments/raw` with backend validation.

---

## 5. Future extensions (not for v1)

These are intentionally deferred; this plan only reserves naming/shape so they
fit later without breaking v1 contracts.

- **Capabilities & tags**
  - `SutCapability { domain, group, name, labels[] }`
  - Env advertises a set of capabilities; endpoints implement one or more.
  - Scenarios declare `sutRequirements` as capabilities/tags instead of raw ids.

- **Real vs mock vs external**
  - Per‑endpoint `mode: REAL | MOCK | EXTERNAL`.
  - Useful for team envs where only some services are truly present.

- **Non‑HTTP protocols**
  - Extend `SutEndpoint` with shapes for ISO8583/TCP, MQ, Kafka, etc.
  - Workers that own such protocols read only the fields they understand.

- **UI support**
  - Group SUTs by domain/platform and type.
  - Filter environments that satisfy scenario `sutRequirements`.
