# Scenario Plan — Architecture (Concise)

> Status: **to be reviewed**.  
> Scenario engine direction is stable, but this doc requires review before archival.
>
> Review note: SUT plan exists on a branch and should be reviewed against this document for consistency.

### Status tracker (v1 engine)
- [x] Foundations (YAML load, time normalisation, required stepId)
- [x] Scheduler/dispatcher with `(planId, stepId)` idempotency and retries
- [x] Readiness / all-or-nothing start
- [x] Ack tracking & outcome (per-step states, success/fail)
- [x] Observability (per-step status/latency metrics, structured logs)
- [x] API: submit/start/status endpoints
- [ ] Per-step types beyond `config-update`
- [ ] Environment profiles / bindings
- [ ] Config-update standardisation (out of scope for this branch):
  - Inventory current producers/consumers and payload shapes per role.
  - Define a versioned envelope + per-role schema with explicit merge semantics and idempotency keys.
  - Add validation at ingress and migrate handlers to the contract.

## Scope & Principles
- A single **Scenario Plan** drives the **Swarm Manager**, which schedules and sends **`config-update`** messages to targeted bees.
- **All-or-nothing readiness**: the scenario starts only when **all declared bees** are ready; otherwise fail fast.
- **No payload validation** here (deferred to authoring tools like Hive UI / Scenario Manager).
- **Step types**: keep `type` (default `config-update`) to allow `await` later; in v1 only `config-update` is executed.
- **Two config forms**: environment variables for boot defaults; `config-update` messages for runtime overrides.
- **Strictness**: **no guessing** and **no synthesized fields**. If any required field is missing or malformed, the scenario **fails immediately**.

## Data Model (summary)
- **Top-level**: `id`, `version`.
- **`template`**: controller image & default `instanceId`.
- **`bees[]`**: `role`, `instanceId`, `image`, optional `env`, `work`.
- **`plan.bees[]`**: `instanceId` → `steps[]`.
- **`step` (required fields)**: `stepId`, `name`, `time` (accept `15s/2m/1h`; normalize internally to ISO-8601), `type` (defaults to `config-update`), `config` (opaque).
- **No auto-generation**: `stepId` **must be provided** for every step; absence is a hard error.

## Desired Behaviour (Swarm Manager)
1. **Ingest & Normalize**
   - Parse YAML; normalize time strings to ISO-8601; verify all required fields exist (including `stepId`).
   - Any missing/invalid field ⇒ **abort** with a clear error.
2. **Topology Provisioning**
   - Ensure all declared bees exist with given `image`, `env`, `work`.
   - Wait until **all** are Ready within a timeout; else **fail** the run (no partial execution).
3. **Timeline Build**-
   - For each bee, sort steps by `time` (stable by provided `stepId`).
   - Compute absolute due times as `T0 + Δ`, where `T0` is scenario start.
   - When multiple bees have steps scheduled around the same moment, **ordering comes from the
     timestamps themselves** (e.g. `60.0s` → `60.1s` → `60.2s`). The engine does **not** invent
     ordering; you must express sequences explicitly (e.g. “stop processor at `60.0s`, reconfigure
     WireMock at `60.1s`, start processor at `60.2s`).
4. **Scheduling & Dispatch**
   - At due time, send **`config-update`** to the target `instanceId` with the step’s `config`.
   - Use **at-least-once** delivery with bounded retries/backoff.
   - **Idempotency key**: `(planId, stepId)`; receivers treat duplicates as no-ops.
5. **Acks & Outcome**
   - Track per-step states: `published` → `acked` (success) or `failed` (after retries/timeout).
   - Scenario **succeeds** iff all steps ack; otherwise **failed**.
6. **Observability**
   - Emit metrics/events: per-step status, publish→ack latency, retries, final summary.

## Non-Goals (v1)
- No late joiners/backfills.  
- No conditional flows or `await/emit` semantics.  
- No schema validation of `config`.  
- No dynamic scaling.

## Reliability & Ops
- **Retries**: exponential backoff; max attempts per step (configurable).
- **Timeouts**: per-bee readiness timeout; per-step ack timeout.
- **Abort**: admin can abort the scenario; remaining scheduled steps become `skipped`.

## Security & Safety
- Only explicit **`instanceId`** targets receive updates.
- Optional **dry-run**: parse/normalize/schedule without dispatching.

## Implementation Plan (Lean)

**Phase 1 — Foundations**
- YAML loader; time normalization; strict field validation (including `stepId`).  
- In-memory plan graph (Bees, Steps, Timeline).

**Phase 2 — Runner**
- Scheduler (due-time queue).  
- Dispatcher (publish `config-update` with `(planId, stepId)`).  
- Ack tracker (correlate and measure latency).

**Phase 3 — Reliability & Lifecycle**
- Retry/backoff; step failure states.  
- Scenario start/abort/finalize; end-of-run summary.

**Phase 4 — Observability**
- Metrics (published/acked/failed/skipped; latencies).  
- Structured logs & events.

**Phase 5 — Thin API/CLI**
- Submit/Start (returns `runId`).  
- Status (read-only).

---

## Environment Profiles (Future Work)

Goal: make scenarios environment-agnostic by treating target systems as named, typed
“environments” that provide values for a small set of parameters.

- [ ] Define an **Environment manifest**:
  - `id`, `name`, `tags[]` (e.g. `http-demo`, `soap`, `perf`).
  - `values` map for parameters (e.g. `processorBaseUrl`, `soapEndpoint`).
- [ ] Let scenarios declare **environment requirements**:
  - `requiresEnvironment.tags[]` to express compatible env types.
  - `parameters` section listing required keys and simple types (string/url/int).
- [ ] Add a small, explicit **binding step** in Scenario Manager:
  - On run: `effectiveTemplate = scenarioTemplate ⊕ environment.values`.
  - Only allow `${paramName}` (or an overlay mapping) in scenario config; no free-form templating here.
  - Fail fast if any required parameter or tag is missing (NFF, no guessing).
- [ ] Optional overlay model (no `${...}` in scenarios):
  - Environments may declare overlays such as:
    - `role: processor`, `path: worker.baseUrl`, `valueFrom: processorBaseUrl`.
  - Scenario Manager applies overlays when materialising the template.
- [ ] UI / UX:
  - Surface “required parameters” for a scenario and available environments that satisfy them.
  - Allow selecting an environment when starting a swarm; show the resolved values for review.

This keeps scenarios clean and reusable, lets us maintain a library of environments,
and preserves strictness: configuration remains explicit and strongly typed, with no
implicit fallbacks.

### Reference YAML (as agreed)

```yaml
id: mock-1-with-defaults
version: 1
template:
  image: pockethive-swarm-controller:latest
  instanceId: marshal-bee

bees:
  - role: generator
    instanceId: seeder-bee
    image: pockethive-generator:latest
    config:
      ratePerSec: 5
    work:
      out:
        out: gen

  - role: moderator
    instanceId: guardian-bee
    image: pockethive-moderator:latest
    work:
      in:
        in: gen
      out:
        out: mod

  - role: processor
    instanceId: worker-bee
    image: pockethive-processor:latest
    work:
      in:
        in: mod
      out:
        out: final

  - role: postprocessor
    instanceId: forager-bee
    image: pockethive-postprocessor:latest
    work:
      in:
        in: final

plan:
  bees:
    - instanceId: seeder-bee
      steps:
        - stepId: s1
          name: Change rate to 10/s
          time: PT15S
          type: config-update
          config:
            ratePerSec: "10"
```

