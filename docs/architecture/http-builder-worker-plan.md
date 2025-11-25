# HTTP Builder Worker — Plan

**Goal**
- Add a generic HTTP Builder worker that takes `callId` + context from Rabbit, resolves a disk-backed template, and emits an HTTP request envelope for the existing processor (base URL, retries, etc. stay in the processor).

**Scope**
- Input: Rabbit (standard work item).
- Output: Rabbit (HTTP-style envelope compatible with the current processor).
- Templates: loaded from disk once at startup (baked-in defaults with optional override via mounted directory).
- Selection: `callId` (and optional `serviceId`) chosen upstream (e.g. by DataProvider) using weighted rules.
- Auth: explicitly out of scope for this iteration (no token service yet).

---

## Design Outline

- Template files
  - Single file per call (`serviceId` + `callId`) describing the full HTTP envelope, not just SOAP:
    - `method` (`GET`/`POST`/…)
    - `pathTemplate` (templated, relative path; processor still owns `baseUrl`)
    - `headersTemplate` (templated map)
    - `bodyTemplate` (templated string; SOAP XML, JSON, or any text)
  - Format: JSON or YAML (pick one and stick to it).
  - Layout: `/<root>/<serviceId>/<callId>.http.json` (or `.yaml`).

- Runtime behaviour
  - On startup:
    - [x] Resolve template roots (base path + optional override path).
    - [x] Scan filesystem and load all templates into memory (`(serviceId, callId) -> Template`).
    - [x] Fail startup if templates are malformed (no fallbacks).
  - On each message:
    - [x] Read `serviceId` and `callId` from WorkItem (headers or payload).
    - [x] Look up the preloaded template; if missing → configurable behaviour (pass-through or drop).
    - [x] Build a templating context from `WorkItem` (`workItem`, `payload`, `headers`, etc.).
    - [x] Render `pathTemplate`, `headersTemplate`, and `bodyTemplate` using existing Pebble+SpEL integration.
    - [x] Construct an HTTP envelope WorkItem and emit to the processor queue.

- Integration points
  - Upstream workers (e.g. DataProvider):
    - [x] Produce `serviceId` (optional) + `callId` + payload and forward as WorkItem to HTTP Builder.
    - [x] Handle weighted call selection per customer via config (templating-based in `redis-dataset-demo`).
  - Processor:
    - [x] Continue to own `baseUrl`, timeouts, and retries; simply append the rendered path.
  - Status / observability:
    - [x] Emit error metrics/status (`errorCount`, `errorTps`, templateRoot/serviceId, passThrough flag).
    - [x] Tag requests with `x-ph-service` and rely on existing tracing via processor.

---

## Task Checklist

### 1. Contracts & configuration

- [x] Define the template file schema for a single HTTP call (fields + types + required/optional).
- [x] Decide on the on-disk layout for templates (`serviceId`/`callId` tree and root path config).
- [x] Extend `generator` scenario capabilities to carry `serviceId` + `callId` where needed (via headers/templating and the `http-builder` capability).
- [x] Define worker-level config for:
  - [x] Template root(s) (baked-in vs override).
  - [x] Default `serviceId` if upstream doesn’t supply one.
  - [x] Behaviour on missing template (`passThroughOnMissingTemplate`).

### 2. Worker implementation

- [x] Introduce the HTTP Builder worker service (new module and `@PocketHiveWorker` bean).
- [x] Add a worker config class for HTTP Builder (template roots, defaults, behaviour flags).
- [x] Implement template loader:
  - [x] Scan template roots.
  - [x] Parse template files into an in-memory map with validation.
- [x] Implement main `onMessage` logic:
  - [x] Extract `serviceId`/`callId` and validate.
  - [x] Render path, headers, and body via the shared `TemplateRenderer`/SpEL.
  - [x] Build and emit the HTTP WorkItem.
- [x] Add status publishing (error count and error TPS, plus config snapshot).

### 3. Integration with existing pipeline

- [x] Update scenarios that should use HTTP Builder instead of embedding SOAP/HTTP in DataProvider/Generator (`redis-dataset-demo`).
- [x] Adjust DataProvider configs to:
  - [x] Pick `callId` per message (weighted, via templating) and add it to the WorkItem (`x-ph-call-id`).
  - [x] Forward payload untouched (so templates have full context).
- [x] Wire HTTP Builder between DataProviders and Processor in swarm templates.

### 4. Tooling & validation

- [x] Extend the `scenario-templating-check` tool (or add a sibling tool) to:
  - [x] Validate that all `callId`s referenced in scenarios exist in the loaded template set.
  - [x] Render each template once with a dummy WorkItem to catch templating/SpEL errors.
- [x] Add a simple CLI to list available `(serviceId, callId)` pairs and their source file (for debugging).

### 5. Tests

- [x] Unit tests for template loading (happy path).
- [x] Unit tests for `onMessage`:
  - [x] Successful resolution and rendering for a simple call.
  - [x] Missing `callId` → clear error / no output (given `passThroughOnMissingTemplate=false`).
  - [x] Missing template for known `callId` → clear error (no fallback) and either pass-through or drop depending on config.
- [x] Integration-style test wiring DataProvider → HTTP Builder → Processor via the e2e harness and the `redis-dataset-demo` scenario (end-to-end pipeline with WireMock in the stack).

### 6. Documentation

- [x] Add a new section to `docs/ARCHITECTURE.md` describing the HTTP Builder role and its place in the swarm.
- [x] Document the template file format and directory layout (with examples) in `http-builder-service/README.md`.
- [x] Document how to mount custom template directories via Docker and how overrides work.
- [ ] (Optional) Draft requirements for a future Postman→template import tool (out of scope for this iteration).
