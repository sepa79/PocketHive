# Control‑Plane Contract Enforcement (AsyncAPI + JSON Schema)

> Status: **critical / design**  
> Scope: enforce `docs/spec/*` as SSOT via dedicated tests + CI gates. No runtime validation by default.

## Problem

We have a canonical contract for control-plane envelopes and routing in:

- `docs/spec/asyncapi.yaml`
- `docs/spec/control-events.schema.json`

Yet implementations can drift (extra fields, wrong nesting, consumers relying on undocumented shapes, etc.).
Relying on “tolerant parsing” in runtimes hides breakage and makes UI/debugging unreliable.

## Goal

Make it impossible (or at least very hard) to merge a change that produces or expects a payload that violates
`docs/spec/*`, without adding invasive runtime checks.

## Non-goals

- Validating every message at runtime in production (too invasive / too costly).
- Backward compatibility shims (unless explicitly required by a task).

## Enforcement strategy

### 1) Contract test suite (schema-driven)

Create a dedicated test suite that loads `docs/spec/control-events.schema.json` and validates:

- **Golden fixtures**: curated JSON payloads stored under `common/control-plane-core/src/test/resources/...`
  (or a new `docs/spec/fixtures/` folder if preferred).
- **Generated payloads**: payloads produced by builders/emitters in code (e.g. `StatusEnvelopeBuilder`,
  `ControlPlaneEmitter`, `ControlSignals`, etc.).

This makes producers contract-compliant by construction.

### 2) Producer-side unit tests (per service/module)

For each producer (Swarm Controller, Orchestrator, Worker SDK runtime):

- Assert the emitted JSON validates against the schema.
- Add semantic assertions that the schema cannot express well, e.g.:
  - `status-delta` does not include snapshot-only sections (e.g. `io`).
  - `status-delta` carries only the intended “minimal delta” fields (definition TBD).
  - `correlationId` and `idempotencyKey` invariants for `kind=signal` and `kind=outcome`.

### 3) Consumer-side tests (tolerant runtime, strict expectations)

Keep runtime parsing tolerant, but enforce expectations via tests:

- Feed consumers valid envelopes (from fixtures) and verify correct projection/state updates.
- Feed invalid envelopes and verify behavior (ignore/log/reject), depending on component requirements.

### 4) E2E capture audit (blocking)

Add a control-plane traffic capture step that runs across the full E2E suite:

- Start a RabbitMQ listener (CP only: `signal.#` / `event.#` on `ph.control`) before E2E runs.
- Capture all emitted control-plane payloads (routing key + headers + body).
- Validate every captured payload against `docs/spec/control-events.schema.json`.
- Fail on any non-conforming payload; no field-value checks beyond schema conformance.
- Enforce “status-delta minimalism” in the schema itself (tighten schema as needed).

Implementation preference: Java-based validator inside the E2E harness (blocking in CI).

### 5) CI gate

In CI, add a mandatory stage that runs:

- Schema validation tests (the contract test suite).
- Module tests for core producers/consumers (at least: `common/control-plane-core`, `common/worker-sdk`,
  `swarm-controller-service`, `orchestrator-service`, `ui`).

Optionally:

- Validate `docs/spec/asyncapi.yaml` with an AsyncAPI validator (as a separate step).

## Open decisions

- **Validator implementation**: Java-based (recommended for Maven modules) vs Node-based (fast, but adds tooling coupling).
- **Fixture location**: keep under module tests vs central `docs/spec/fixtures/` folder.
- **“Delta minimalism”**: formalize what “delta” is allowed to contain (likely needs explicit doc + tests).
