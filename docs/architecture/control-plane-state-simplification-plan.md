# Control‑Plane `state` Simplification Plan

> Goal: make `state` a small, human‑readable summary string and move all
> behavioural / structured details into `data.*`. Remove all fallbacks that
> interpret nested `state` objects.

## 1. Contract decision

- `state` in control‑plane envelopes (`ready.*`, `error.*`, `status‑full`,
  `status‑delta`, etc.) is a **single summary string**, e.g.:
  - `"Running"`, `"Stopped"`, `"Ready"`, `"Failed"`, `"Unknown"`, `"Degraded"`.
- All structured information (flags, metrics, diagnostics, plans, guards, SUT,
  etc.) lives under `data.*` only.
- No new code is allowed to rely on `state.*` for behaviour.

## 2. Tighten core types

- **StatusEnvelopeBuilder**
  - Change the `state` field from `Object` to `String` (or an internal enum
    rendered as string).
  - Ensure all callers provide a short summary string only.

- **ControlPlaneEmitter / ConfirmationPayloadFactory / worker runtime**
  - Restrict `state` constructor parameters to `String`.
  - `Objects.requireNonNull(state, "state")` must now refer to a summary
    string, not a structured object.
  - If a caller tries to pass a non‑string state, fail fast in code or tests.

## 3. Migrate producers

- **Swarm‑controller (manager SDK)**
  - In `SwarmSignalListener` / `SwarmRuntimeCore`:
    - Replace complex `state` payloads with simple lifecycle summaries:
      - `"Ready"`, `"Running"`, `"Stopped"`, `"Removed"`, `"Degraded"`, `"Unknown"`.
    - Any data currently placed under `state.details.*` must be moved to
      `data.*` with explicit keys (e.g. `data.workloadsEnabled`,
      `data.controllerEnabled`, `data.swarmDiagnostics`, `data.scenario`).

- **Worker SDK**
  - In `WorkerStatusPublisher` / `WorkerControlPlaneRuntime`:
    - Emit a simple `state` string describing worker status
      (e.g. `"Running"`, `"Idle"`, `"Failed"`).
    - Push diagnostics / metrics into `data.*`.

## 4. Update consumers

- **Orchestrator / Manager SDK**
  - Do not read `state.*` for lifecycle, enablement, or routing decisions.
  - Use top‑level `data.swarmStatus`, `data.workloadsEnabled`,
    `data.controllerEnabled`, and other `data.*` fields exclusively.
  - The recent change to `ControllerStatusListener` already ignores
    `state.workloads.enabled` and `state.controller.enabled`; this plan
    formalises that and extends it across the codebase.

- **UI / tools**
  - If any code still inspects `state.*` for more than display, migrate it to
    read `data.*`.
  - It is fine for UI to display `state` as a small badge / label alongside
    more detailed `data.*` fields.

## 5. Tests & fixtures

- Update tests that assert nested `state` structure:
  - `swarm-controller-service` SwarmSignalListener tests.
  - Orchestrator SwarmEventFlow / SwarmSignalListener tests.
  - Control‑plane core JSON fixtures that currently contain `state.details`.

- New tests:
  - In control‑plane core:
    - Build a status / ready / error envelope and assert:
      - `state` is a non‑empty string.
      - No nested `state.details` is emitted.
  - In swarm‑controller tests:
    - Assert that all behavioural fields (swarm status, enablement, scenario,
      guard diagnostics) are present under `data.*` and **not** duplicated in
      `state`.

## 6. Documentation

- Update:
  - `docs/ARCHITECTURE.md`
  - `docs/rules/control-plane-rules.md`
  - `docs/ORCHESTRATOR-REST.md`
  - Any other documents that show `state` as an object.

- New description:
  - `state` — short textual summary of the component/swarm status, intended for
    humans and generic tooling.
  - `data` — structured, component‑specific fields used for behaviour and
    detailed diagnostics.

- Mark previous usage of `state.details.*` as **deprecated / removed** and
  point to the corresponding `data.*` fields.

## 7. Roll‑out notes

- This is a **contract change** inside PocketHive, but all services in this repo
  are versioned together, so we can change producers and consumers in one
  branch.
- External consumers of controller / worker status events should be migrated to
  `data.*` before we rely solely on `state` as a string. If we need a
  transition period, we can:
  - Continue emitting both `state` (string) and `data.*` fields.
  - Stop adding any new data to `state`.

