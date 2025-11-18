# WorkItem Refactor & Worker Contract Simplification Plan

> Scope: replace `WorkMessage`/`WorkResult` with a simpler `WorkItem` API and a single return type, then extend it with step support.  
> Sources: align with `docs/ARCHITECTURE.md`, `docs/ORCHESTRATOR-REST.md`, and the pipeline/HTTP plans in `docs/sdk/`.

## Motivation

The current Worker SDK exposes two core types:

- `WorkMessage` — the payload + headers handed to workers.
- `WorkResult` — a tiny wrapper that distinguishes “message vs none”.

This split makes simple workers harder to read (e.g. `WorkResult.message(WorkMessage.json(...).build())`) and complicates the evolution toward `WorkItem` with steps. External teams building custom workers also need a clear, one-time breaking change rather than a series of small, confusing ones.

Goals:

- Simplify the worker contract to a single type (`WorkItem`) returned directly.
- Remove `WorkResult` from the public surface.
- Keep behaviour equivalent initially (single payload, no history), then add steps/history as a non-breaking enhancement.

## High-Level Stages

1. **Stage 1 — Core API Pivot (single breaking wave)**
2. **Stage 2 — Add Step API on `WorkItem` (non-breaking)**
3. **Stage 3 — Enable Real Step History (bounded)**

This plan focuses primarily on Stage 1 for now so downstream worker authors can adapt once.

## Stage 1 — Core API Pivot (Single Breaking Wave)

### 1.1 Introduce `WorkItem` and New Worker Contract

- [x] Create a new `WorkItem` type by adapting the existing `WorkMessage` implementation:
  - Keep fields and behaviour identical for now: body (bytes), headers (map), charset, `ObservabilityContext`.
  - Preserve existing builder semantics as an implementation detail (will be hidden behind friendlier methods later).
- [x] Change the core worker contract to return a `WorkItem` directly:
  - Update `PocketHiveWorkerFunction` to:
    ```java
    WorkItem onMessage(WorkItem in, WorkerContext context) throws Exception;
    ```
  - Define `null` as the explicit “no output” signal for now (simple for downstream teams).
- [x] Remove `WorkResult` from the public API:
  - Update runtime code (`WorkerInvocation`, `WorkOutput` implementations) to:
    - Call `onMessage(...)` and check for `null`.
    - Publish the returned `WorkItem` directly via the configured `WorkOutput` if non-null.
  - Keep `WorkResult` only as an internal/legacy type if absolutely necessary for a gradual transition, but avoid exposing it in public packages or examples.

### 1.2 Adapt Runtime & Outputs

- [x] Update `WorkerInvocation` and related runtime classes:
  - Replace any use of `WorkResult` with `WorkItem` / `null`.
  - Ensure interceptors and status publishing logic still wrap around the new call signature cleanly.
- [x] Update `WorkOutput` SPI and implementations:
  - Change `WorkOutput.publish(...)` to accept `WorkItem` directly (or a wrapper object if needed for future extensibility).
  - Update `RabbitWorkOutput` to:
    - Read body/headers directly from `WorkItem`.
    - Preserve current content-type/header behaviour.
- [x] Ensure `WorkInput` implementations still construct the inbound type correctly, now as `WorkItem`.

### 1.3 Migrate Built-in Workers & Tests

- [x] Update all workers in this repo to the new contract:
  - Replace imports of `WorkMessage` with `WorkItem`.
  - Replace `WorkResult.message(...)` / `WorkResult.none()` call sites with:
    - `return workItem;` (or a newly built item) for “publish”.
    - `return null;` for “no output”.
- [x] Update tests and fixtures:
  - Adapt any helpers/factories that previously used `WorkResult` or `WorkMessage` directly.
  - Ensure existing behavioural tests for generator/moderator/processor/postprocessor/trigger still pass with the new contract.

### 1.4 Docs & Migration Notes

- [x] Update `common/worker-sdk/README.md` and `docs/sdk/worker-sdk-quickstart.md`:
  - Show the new `PocketHiveWorkerFunction` signature with `WorkItem`.
  - Provide simple examples:
    - “Return the same item with an extra header.”
    - “Return a new item with JSON body.”
    - “Return `null` for no output.”
- [ ] Draft a short “migration cheat sheet” (in this file or a sibling doc) for external worker authors:
  - Old vs new signature.
  - How to replace `WorkResult.message(...)` and `WorkMessage` usage.
  - Link to updated examples.

## Stage 2 — Add Step API on WorkItem (Non-Breaking)

Once all workers (internal and external) are on the new contract, we can introduce the step API without breaking existing code.

- [x] Add read helpers to `WorkItem`:
  - `String payload()` for current step payload.
  - `Optional<String> previousPayload()` for one-step-back (initially empty).
  - `Iterable<WorkStep> steps()` returning a single synthetic step at first.
- [x] Add write helpers:
  - `WorkItem addStepPayload(String payload)` — adds/replaces the current step payload.
  - `WorkItem addStep(String payload, Map<String,Object> headers)` — adds a step with headers.
  - `WorkItem addStepHeader(String name, Object value)` — adds a header to the current step.
  - `WorkItem clearHistory()` — retains only the current step (no-op initially).
- [x] Introduce `HistoryPolicy`:
  - Configurable per worker/role/swarm with modes `FULL`, `LATEST_ONLY`, `DISABLED`.
  - Expose the default via `pockethive.worker.history-policy` in Scenario YAML / service config, defaulting to `FULL`.
  - In Stage 2, the public API is wired; enforcement can be tightened in Stage 3 when real step history limits are applied.

> Stage 2 is additive; no worker contract changes are required. Existing workers can ignore these new methods until they need step support.

## Stage 3 — Enable Real Step History (Bounded)

After Stage 2 is stable, we can upgrade `WorkItem` to hold real step history and honour `HistoryPolicy`.

- [x] Implement internal `WorkStep` structure and step list on `WorkItem`.
- [ ] Honour `HistoryPolicy` limits and bounds (max steps, optional size constraints).
- [ ] Ensure the HTTP processor, templating pipeline, and routing output use steps in a consistent way.

> Stage 3 ties directly into `docs/sdk/templated-generator-plan.md` and `docs/sdk/http-request-processor-plan.md`.
