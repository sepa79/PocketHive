# Scenario Plan — Architecture (Concise)

## Scope & Principles
- A single **Scenario Plan** drives the **Swarm Manager**, which schedules and sends **`config-update`** messages to targeted bees.
- **All-or-nothing readiness**: the scenario starts only when **all declared bees** are ready; otherwise fail fast.
- **No payload validation** here (deferred to Scenario Editor).
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
    env:
      POCKETHIVE_CONTROL_PLANE_WORKER_GENERATOR_RATE_PER_SEC: "5"
    work:
      out: gen

  - role: moderator
    instanceId: guardian-bee
    image: pockethive-moderator:latest
    work: { in: gen, out: mod }

  - role: processor
    instanceId: worker-bee
    image: pockethive-processor:latest
    work: { in: mod, out: final }

  - role: postprocessor
    instanceId: forager-bee
    image: pockethive-postprocessor:latest
    work: { in: final }

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

