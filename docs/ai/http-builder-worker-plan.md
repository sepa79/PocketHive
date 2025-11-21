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
    - [ ] Resolve template roots (base path + optional override path).
    - [ ] Scan filesystem and load all templates into memory (`(serviceId, callId) -> Template`).
    - [ ] Fail startup if templates are malformed (no fallbacks).
  - On each message:
    - [ ] Read `serviceId` and `callId` from WorkItem (headers or payload).
    - [ ] Look up the preloaded template; if missing → error (no fallback).
    - [ ] Build a templating context from `WorkItem` (`workItem`, `payload`, `headers`, etc.).
    - [ ] Render `pathTemplate`, `headersTemplate`, and `bodyTemplate` using existing Pebble+SpEL integration.
    - [ ] Construct an HTTP envelope WorkItem and emit to the processor queue.

- Integration points
  - Upstream workers (e.g. DataProvider):
    - [ ] Produce `serviceId` + `callId` + payload and forward as WorkItem to HTTP Builder.
    - [ ] Handle weighted call selection per customer via config.
  - Processor:
    - [ ] Continue to own `baseUrl`, timeouts, and retries; simply append the rendered path.
  - Status / observability:
    - [ ] Emit per-`callId` metrics/status (counts, last error, last success timestamp).
    - [ ] Tag requests with `x-ph-service-id` / `x-ph-call-id` headers for tracing.

---

## Task Checklist

### 1. Contracts & configuration

- [ ] Define the template file schema for a single HTTP call (fields + types + required/optional).
- [ ] Decide on the on-disk layout for templates (`serviceId`/`callId` tree and root path config).
- [ ] Extend `generator`/`data-provider` scenario capabilities to carry `serviceId` + `callId` where needed.
- [ ] Define worker-level config for:
  - [ ] Template root(s) (baked-in vs override).
  - [ ] Default `serviceId` if upstream doesn’t supply one.

### 2. Worker implementation

- [ ] Introduce the HTTP Builder worker service (new module and `@PocketHiveWorker` bean).
- [ ] Add a worker config class for HTTP Builder (template roots, defaults, etc.).
- [ ] Implement template loader:
  - [ ] Scan template roots.
  - [ ] Parse template files into an in-memory map with validation.
- [ ] Implement main `onMessage` logic:
  - [ ] Extract `serviceId`/`callId` and validate.
  - [ ] Render path, headers, and body via the shared `TemplateRenderer`/SpEL.
  - [ ] Build and emit the HTTP WorkItem.
- [ ] Add status publishing (per-call stats and error details).

### 3. Integration with existing pipeline

- [ ] Update scenarios that should use HTTP Builder instead of embedding SOAP/HTTP in DataProvider/Generator.
- [ ] Adjust DataProvider configs to:
  - [ ] Pick `callId` per message (weighted) and add it to the WorkItem.
  - [ ] Forward payload untouched (so templates have full context).
- [ ] Wire HTTP Builder between DataProviders and Processor in swarm templates.

### 4. Tooling & validation

- [ ] Extend the `scenario-templating-check` tool (or add a sibling tool) to:
  - [ ] Validate that all `callId`s referenced in scenarios exist in the loaded template set.
  - [ ] Render each template once with a dummy WorkItem to catch templating/SpEL errors.
- [ ] Add a simple CLI to list available `(serviceId, callId)` pairs and their source file (for debugging).

### 5. Tests

- [ ] Unit tests for template loading (happy path + invalid files).
- [ ] Unit tests for `onMessage`:
  - [ ] Successful resolution and rendering for a simple call.
  - [ ] Missing `callId` → clear error / no output.
  - [ ] Missing template for known `callId` → clear error (no fallback).
- [ ] Integration-style test wiring DataProvider → HTTP Builder → Processor using testcontainers/WireMock (optional but preferred).

### 6. Documentation

- [ ] Add a new section to `docs/ARCHITECTURE.md` describing the HTTP Builder role and its place in the swarm.
- [ ] Document the template file format and directory layout (with examples).
- [ ] Document how to mount custom template directories via Docker and how overrides work.
- [ ] (Optional) Draft requirements for a future Postman→template import tool (out of scope for this iteration).

