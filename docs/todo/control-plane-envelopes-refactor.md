# Control‑Plane Envelopes Refactor Plan (Signals / Confirmations / Status / Journal)

> Status: **design / NFF big‑bang**  
> Scope: unify signal, confirmation and status envelopes and simplify journal projections. No backwards compatibility required.

Goal: introduce a single, consistent control‑plane envelope model used by signals (commands), outcomes (ready/error) and status messages, so that Journal and Hive UI can project timelines directly from message fields without ad‑hoc copying or payload parsing. This refactor is allowed to be a one‑off “big bang” (no compatibility shims), but must keep routing utilities and high‑level contracts conceptually aligned with `ARCHITECTURE.md`.

---

## 1. Contracts & Docs

- [x] Extend existing specs in `docs/spec`:
  - Update `docs/spec/asyncapi.yaml` channels and schemas, and `docs/spec/control-events.schema.json`, so they describe the canonical routing families and envelope shapes below.

  - **Core fields (always present)**

    | Field       | Type              | Required | Description                                                                                          |
    |------------|-------------------|----------|------------------------------------------------------------------------------------------------------|
    | `timestamp`| string            | Yes      | RFC‑3339 time when the message was emitted by its origin.                                           |
    | `version`  | string            | Yes      | Schema version of the envelope and its structured `data` section for this control‑plane message. Bump only for incompatible changes. |
    | `kind`     | string            | Yes      | Coarse category of the message: one of `signal`, `outcome`, `event`, `metric`. All routing/consumers should branch on this field first. |
    | `type`     | string            | Yes      | Concrete name within the `kind` category. For `kind=signal`/`outcome` this is the command name (`swarm-start`, `config-update`, …); for `kind=event` the current spec covers `alert`; for `kind=metric` the current spec covers `status-full` and `status-delta`. |
    | `origin`   | string            | Yes      | Logical emitter identity (e.g. `orchestrator-1`, `swarm-controller:aaa-marshal-…`, `processor:bee-1`, `hive-ui`). Never blank. |
    | `scope`    | object            | Yes      | `{ swarmId, role, instance }` describing the entity the message is about.                           |
    | `scope.swarmId` | string\|null | Yes      | Swarm the message relates to (may be `null` for global or cross‑swarm events).                      |
    | `scope.role`    | string\|null | Yes      | Role of the **subject** of the message; free‑form logical role id. Core roles include `orchestrator`, `swarm-controller`, `generator`, `moderator`, `processor`, `postprocessor`, `trigger`, but plugins may introduce additional roles. May be `null` for scopes that intentionally describe “all roles” or a cross‑role aggregate. The envelope schema must **not** hardcode an enum for this field. |
    | `scope.instance`| string\|null | Yes      | Logical instance identifier of the **subject** of the message (the controller/worker/orchestrator instance the message is about). May be `null` for scopes that intentionally describe “all instances” of a role. This may or may not be the same as the `origin` instance that emitted it. |
    | `correlationId`  | string\|null | Yes     | Correlation token used to join related messages. For `kind=signal` / `kind=outcome`, this field **must** be non‑empty and identical across the command signal and its outcomes. For other kinds (`event`, `metric`) it is either `null` or used only for explicitly documented higher‑level correlations. |
    | `idempotencyKey` | string\|null | Yes     | Stable identifier reused across retries of the same logical operation. For externally initiated `kind=signal` / `kind=outcome` messages this field **should** be non‑empty; for purely internal, non‑retriable messages it may be `null`. For non‑command kinds (`event`, `metric`) this field is typically `null`. |

    Relationship to routing keys (new prefixes):

    - Control‑plane **signals** use the `signal.*` prefix. The canonical pattern after the refactor is:  
      `signal.<commandType>.<swarmId>.<role>.<instance>` where:
      - `<commandType>` is the envelope `type` for `kind = signal` (for example `swarm-start`, `swarm-stop`, `swarm-remove`, `config-update`, `status-request`).
      - `<swarmId>.<role>.<instance>` are the semantic target and must match `scope.swarmId` / `scope.role` / `scope.instance` on the signal. For fan‑out signals the routing key may still use wildcards (for example `ALL`), while `scope` on outcomes will carry concrete values. **Lifecycle commands addressed at the swarm‑controller (`swarm-template`, `swarm-plan`, `swarm-start`, `swarm-stop`, `swarm-remove`) MUST use a concrete controller instance in the `<instance>` segment once a controller exists; using `ALL` for these commands is forbidden in the new model. Fan‑out across multiple controllers, if ever needed, must be implemented by sending separate instance‑specific commands, not by relying on `ALL`.**

    - Control‑plane **events** (everything that is not a command signal) use the `event.*` prefix. The canonical pattern is:  
      `event.<category>.<name>.<swarmId>.<role>.<instance>` where:
      - `<category>` differentiates major event families such as `outcome`, `metric`, `alert` (for example `event.outcome.*`, `event.metric.*`, `event.alert.alert.*`).
      - `<name>` is normally the envelope `type` within that family (for example `status-full`, `status-delta` for metrics, or the command name such as `swarm-start` / `config-update` for outcomes).
      - `<swarmId>.<role>.<instance>` are the semantic subject and must match `scope.swarmId` / `scope.role` / `scope.instance`, normalised so that `ALL` or wildcard segments are represented as `null`.

    - For **command outcomes** (`kind = outcome`), the routing key uses the `event.outcome.*` family:  
      `event.outcome.<commandType>.<swarmId>.<role>.<instance>`. Here `<commandType>` matches the originating signal’s `type` (command name), `scope` describes the concrete subject that actually processed the command, and `correlationId` / `idempotencyKey` join the outcome back to the original `signal.*` message.

  - **Structured sections**

    | Section / Field      | Type          | Applies to       | Description                                                                                                      |
    |----------------------|---------------|------------------|------------------------------------------------------------------------------------------------------------------|
    | `data`               | object\|null  | all kinds        | Structured payload for the message. For each (`kind`, `type`) combination, the AsyncAPI / JSON Schema specs in `docs/spec` MUST define the required shape of `data` (required fields + optional extension fields). Targeting is never carried inside `data`; targeting is described only by `scope` and the routing key. All additional, best‑effort metadata for that message also lives under `data`. |

  - **Known `data` schemas for existing messages (today)**

    The tables below describe the canonical `data` shapes for the message kinds/types covered by the current specs in `docs/spec/asyncapi.yaml` / `docs/spec/control-events.schema.json`.

    **Control metrics (`kind = metric`)**

    | `type`         | `data` field       | Required | Description                                                                                     |
    |----------------|--------------------|----------|-------------------------------------------------------------------------------------------------|
    | `status-full`  | `enabled`          | Yes      | Boolean. Indicates whether this component is currently allowed to run workloads for its scope. |
    |                | `startedAt`        | Yes      | RFC‑3339 timestamp when this component started processing workloads for its scope (or when the current process was started). |
    |                | `tps`              | Yes      | Integer ≥ 0. Global throughput sample for the reporting interval.                              |
    |                | `io`               | No       | Object describing IO bindings and queue health for this component. Canonical shape: `{ work: { queues, queueStats }, control: { queues } }`, where `queues` mirrors the per-exchange IO bindings (`in` / `out` / `routes`) and `queueStats` is a map keyed by queue name (`depth`, `consumers`, `oldestAgeSec?`). Only emitted in `status-full`. |
    |                | `context`          | No       | Freeform object carrying role‑specific status context. For swarm‑controller, `context` should carry worker/traffic details such as `swarmStatus`, `swarmDiagnostics`, `scenario`, and any aggregated counts (`desired`, `healthy`, `running`, `enabled`). For orchestrator, `context` should carry at least `swarmCount` and `computeAdapter`. |
    | `status-delta` | `enabled`          | Yes      | Boolean. Same semantics as in `status-full`; used to signal enablement changes without resending full status snapshots. |
    |                | `tps`              | Yes      | Integer ≥ 0. Throughput sample for the interval since the last status event.                   |
    |                | `context`          | No       | Same semantics as in `status-full`, but only for fields that change frequently (for example recent `swarmStatus`, rolling diagnostics). `data.io` and `data.startedAt` must be omitted from deltas. |

    **Control events (`kind = event`)**

    | `type`      | `data` field  | Required | Description                                                                                     |
    |------------|---------------|----------|-------------------------------------------------------------------------------------------------|
    | `alert`    | `level`       | Yes      | String enum: `info`, `warn`, `error`.                                                          |
    |            | `code`        | Yes      | Short, stable alert code (suitable for filtering and dashboards). Recommended codes include `worker.runtime-error`, `controller.runtime-error`, `io.out-of-data`, `io.backpressure`, `io.downstream-error`, `generator.limit-reached`, etc. |
    |            | `message`     | Yes      | Human‑readable alert message.                                                                  |
    |            | `errorType`   | No       | For runtime errors: Java/worker exception class name (for example `NullPointerException`, `TimeoutException`). |
    |            | `errorDetail` | No       | For runtime errors: best‑effort detail string (for example root cause message or a truncated stack trace snippet). |
    |            | `logRef`      | No       | Opaque string that lets the UI link directly to detailed logs or traces for this alert (for example a Loki query, trace id, or log correlation id). Producers should treat this as a pointer, not embed full stack traces in the alert. |
    |            | `context`     | No       | Object carrying type‑specific structured context. For IO / “out of data” alerts, recommended keys include: `backend` (for example `redis`, `csv`, `kafka`), `resourceId` (dataset id, file path, key prefix, etc.), `loopMode` (`loop`/`no-loop`), and optional limit info such as `limitKind` (`maxMessages`, `maxTime`, `none`) and `limitValue` (numeric/string). For other alert codes, `context` can carry whatever structured fields a producer and UI agree on. |

    **Command signals and outcomes (`kind = signal` / `kind = outcome`)**

    *Commands in the new model always use `kind = signal`, `type = <commandName>` and the `signal.<type>.<swarmId>.<role>.<instance>` routing family. Outcomes always use `kind = outcome`, `type = <commandName>` and the `event.outcome.<type>.<swarmId>.<role>.<instance>` family. The tables below summarise current commands and their `data`/args usage so we can standardise them in the refactor.*

    **Command signals (`kind = signal`) — purpose and targeting (today)**

    | `type`           | Purpose / effect                                           | Typical routing key (refactored)             | Target subject (conceptual `scope`)                                    |
    |------------------|------------------------------------------------------------|-----------------------------------------------|------------------------------------------------------------------------|
    | `swarm-template` | Apply swarm template (bees, images, wiring, config, SUT). | `signal.swarm-template.<swarmId>.swarm-controller.<instance>` | Swarm controller instance for `<swarmId>`. |
    | `swarm-plan`     | Push resolved scenario plan timeline to controller.       | `signal.swarm-plan.<swarmId>.swarm-controller.<instance>`     | Swarm controller instance for `<swarmId>`. |
    | `swarm-start`    | Start workloads inside a running controller.              | `signal.swarm-start.<swarmId>.swarm-controller.<instance>`    | Swarm controller instance for `<swarmId>`. |
    | `swarm-stop`     | Stop workloads (non‑destructive).                         | `signal.swarm-stop.<swarmId>.swarm-controller.<instance>`     | Swarm controller instance for `<swarmId>`. |
    | `swarm-remove`   | Tear down queues and controller runtime.                  | `signal.swarm-remove.<swarmId>.swarm-controller.<instance>`   | Swarm controller instance for `<swarmId>`. |
    | `config-update`  | Apply config patch / enablement to one or more components.| `signal.config-update.<swarmId>.<role>.<instance>`            | Target component(s) addressed by routing key segments (supports ALL wildcards where fan-out is intentional). |
    | `status-request` | Ask a component to emit an explicit status snapshot.      | `signal.status-request.<swarmId>.<role>.<instance>`           | Target component(s) addressed by routing key segments (supports ALL wildcards where fan-out is intentional). |

    **Command signals (`kind = signal`) — current `data` / args**

    | `type`           | `data` / args field | Required | Description                                                                                                      |
    |------------------|---------------------|----------|------------------------------------------------------------------------------------------------------------------|
    | `swarm-template` | `data`             | Yes      | Entire swarm template/plan as a `SwarmPlan` object (id, bees, traffic policy, sutId, etc.), converted to a JSON object. Shape is defined by the swarm model (`SwarmPlan`); the control envelope does not add extra fields. |
    | `swarm-plan`     | `data`             | Yes      | Resolved scenario plan timeline as a JSON object. Shape is defined by scenario manager contracts; control‑plane treats it as opaque. |
    | `swarm-start`    | —                  | No       | No command‑level `data` today; semantics come from `type`, `scope`/routing, `correlationId` and `idempotencyKey`. |
    | `swarm-stop`     | —                  | No       | Same as `swarm-start`; no structured `data`.                                                                     |
    | `swarm-remove`   | —                  | No       | Same as `swarm-start`; no structured `data`.                                                                     |
    | `config-update`  | `data`             | Yes      | Config payload for the target component(s). Targeting is carried exclusively by the envelope `scope` and routing key. The `data` object carries the config patch and enablement flags. Exact shape is defined in worker/manager config docs. |
    | `status-request` | —                  | No       | No command‑level `data` today; the response is a `status-full` metric event instead of a confirmation outcome.   |

    **Command outcomes (`kind = outcome`) — current payloads**

    For **outcome** messages (`kind = outcome`, `type = <command>`), outcomes use a single `CommandOutcomePayload` envelope shape; the table below captures the field-level mapping from the legacy confirmation shape.

    | Field (today)   | Planned location                | Description                                                                                             |
    |-----------------|---------------------------------|---------------------------------------------------------------------------------------------------------|
    | `state.status`  | `data.status`                  | High‑level status after processing the command (for example `Ready`, `Running`, `Stopped`, `Failed`).  |
    | `state.enabled` | — (removed)                    | Removed in the new model. Enablement lives in a single place: `data.enabled` on config‑update outcomes and `data.enabled` in status metrics; there is no generic `state.enabled` field. |
    | `state.details` | `data.context`                 | Structured post‑command state details (for example `workloads.enabled`, scenario changes, worker info), to be defined per command type. No separate `controllerEnabled` field is kept. |
    | `phase`         | — (removed or mapped to alert) | Error phase will not be carried as a generic outcome field. If needed for debugging, producers include it in alert `data.context.phase` for the corresponding `event.alert.alert` message. |
    | `code`          | — (replaced by alert `data.code`)   | Command outcomes no longer carry their own error/result code; runtime and IO errors are expressed via `event.alert.alert` with `data.code`.                                              |
    | `message`       | — (replaced by alert `data.message`) | Human‑readable error/message text for failures is carried by `event.alert.alert.data.message` rather than command outcome envelopes.                                                      |
    | `retryable`     | `data.retryable`               | Whether this command attempt is safe to retry. Only set (and documented) for commands where retry semantics are defined (for example swarm create/start/stop/remove).               |
    | `details`       | — (folded into `data.context`) | Catch‑all details on confirmations are removed. Any structured context that needs to survive goes into `data.context` on the outcome and/or the corresponding `event.alert.alert`.       |

  - Keep routing keys and channels in AsyncAPI as the single source of truth for where these envelopes flow, and update them to use the new `signal.*` / `event.*` patterns described above.
- [ ] Optionally add a short overview section (or paragraph) under `docs/ARCHITECTURE.md` or a small `docs/control-plane/README.md` that points to the JSON Schema / AsyncAPI specs as the canonical definition of envelopes, with one or two concrete examples.
- [ ] Update `docs/correlation-vs-idempotency.md` to reference where correlation and idempotency live in the updated payload schemas and how they flow signal → outcome → status/journal.

---

## 2. Core Model Changes (Shared Libraries)

- [x] Introduce a shared `ControlScope` (or equivalent) in `common/topology-core`:
  - Encapsulate `{ swarmId, role, instance }`.
  - Used consistently by signals, outcomes and status instead of mixing raw swarmId/role/instance fields vs `ConfirmationScope`.
- [x] Replace `ReadyConfirmation` / `ErrorConfirmation` with a single `CommandOutcome` record:
  - Fields: `timestamp`, `version`, `kind`, `type`, `origin`, `scope`, `correlationId`, `idempotencyKey`, `data`, matching the canonical envelope meta defined in §1.
  - Shape of `data` is defined per (`kind`,`type`) in `docs/spec/asyncapi.yaml` (for example command‑specific outcome payloads), with a clear split between required fields (such as `status` and `retryable` where applicable) and optional extension fields in `data.context`.
  - Route all outcomes through the `event.outcome.*` family: `event.outcome.<type>.<swarmId>.<role>.<instance>` instead of the legacy ready/error confirmation topics.
- [x] Refine `ControlSignal`:
  - Align structural shape with `CommandOutcome`:
    - Add `timestamp`, `version`, `kind`, `type`, `origin` and a `scope()` accessor (or embedded scope) so the in‑memory model matches the canonical envelope fields.
    - Ensure `correlationId` / `idempotencyKey` semantics stay identical to §1.
  - Keep `data` as the canonical command payload, mapped directly into the envelope `data` section for `kind = signal`.
- [x] Update `StatusEnvelopeBuilder` (or replace with a `StatusMessage` record + builder) to:
  - Emit `timestamp`, `version`, `kind`, `type`, `origin`, `scope`, `correlationId`, `idempotencyKey`, `data` fields consistent with the canonical envelope (no bespoke status‑only fields outside `data`).
  - Preserve existing queue/metrics fields (`queues`, `queueStats`, `totals`) but route them via `data` with a documented schema for `kind=metric`,`type=status-full` / `status-delta`.
  - Switch control‑plane routing to the new prefixes:
    - Signals published on `signal.<type>.<swarmId>.<role>.<instance>`.
    - Status metrics on `event.metric.status-full.<swarmId>.<role>.<instance>` / `event.metric.status-delta.<swarmId>.<role>.<instance>`.
    - Alerts on `event.alert.alert.<swarmId>.<role>.<instance>`.

---

## 3. Emitter / Factory Refactor

- [x] Rework `ControlPlaneEmitter` in `common/control-plane-core` to:
  - Build `CommandOutcome` instead of `ReadyConfirmation` / `ErrorConfirmation`.
  - Fill outcome `data` consistently for all successes/failures (worker, swarm‑controller, orchestrator):
    - Always set `data.status` on outcomes.
    - Set `data.retryable` explicitly for commands that define retry semantics (for example swarm create/start/stop/remove).
    - Use per‑command `data.context` for structured state (enablement, scenario changes, worker metadata).
  - Emit `event.alert.alert` messages (with `data.code`, `data.message`, `data.context`) alongside error outcomes when a failure should surface as a runtime alert (for example worker runtime exceptions, bootstrap failures, IO/data exhaustion).
- [x] Provide helper APIs:
  - Centralise exception → outcome + alert mapping in `ControlPlaneEmitter.emitException(…)` and `Alerts.fromException(…)` / `Alerts.error(…)`.
  - Provide IO/runtime alert factories in `Alerts` (for example `Alerts.ioOutOfData(…)`) with stable `code` and structured `context` (plus optional `logRef` for deep links to stack traces).
- [x] Introduce explicit factory/builder methods for each supported (`kind`,`type`) combination so producers cannot bypass the canonical envelope shape:
  - For signals: `ControlSignals.*` (`configUpdate`, `swarmStart`, `swarmStop`, …).
  - For outcomes: `CommandOutcomes.*` (canonical `kind=outcome` success/failure builders) used by `ControlPlaneEmitter` and service emitters.
  - For metrics: `observability/StatusEnvelopeBuilder` as the canonical builder for `kind=metric` (`status-full`, `status-delta`).
  - For alerts: `Alerts.*` as the canonical builder for `kind=event,type=alert`.
  - All callers MUST use these canonical factories/builders instead of instantiating envelopes directly. Any new control‑plane input/output (signals, outcomes, status, alerts, metrics) must be introduced by extending these APIs and the corresponding (`kind`,`type`) schemas, not by ad‑hoc JSON construction.

---

## 4. Producers & Consumers (Services)

- [x] Worker SDK (`common/worker-sdk`):
  - Update `ControlPlaneNotifier.emitConfigReady/emitConfigError` to build `CommandOutcome` via the new `ControlPlaneEmitter`.
  - When possible, populate:
    - `origin` (worker role/instance).
    - `error` (for config errors and runtime exceptions).
    - `io.inputs` / `io.outputs` (e.g. out‑of‑data vs throttled) when that knowledge exists.
- [x] Swarm‑controller:
  - Switch `SwarmSignalListener.emitSuccess/emitError` to build and publish `CommandOutcome`.
  - Stop embedding confirmation JSON as opaque strings in journal `details`; rely on envelope fields (meta + `data.*`) for projection, using outcomes for command status and alerts/metrics for errors and IO.
- [x] Orchestrator:
  - Update swarm‑create/start/stop/timesout emission paths to use `CommandOutcome`.
  - Update `SwarmSignalListener` in orchestrator to consume `CommandOutcome` instead of `ReadyConfirmation`/`ErrorConfirmation` when updating swarm registry state.
- [x] Any remaining tests/e2e helpers that depend on the old confirmation types or JSON shapes must be updated to parse the new envelopes and assert fields explicitly.

---

## 5. Journal & UI Simplification

- [x] Swarm journal projection (in swarm‑controller + orchestrator):
  - For **signals**: derive `JournalEvent` directly from `ControlSignal`:
    - `timestamp`, `kind`, `type`, `scope`, `origin`, `data`, plus direction (IN/OUT) from routing key.
  - For **outcomes**: derive from `CommandOutcome`:
    - Use the typed `data` fields for command status (for example `data.status`, `data.retryable`) as defined in the updated schemas.
  - For **alerts**: project `event.alert.alert` alongside outcomes so the journal can surface error codes/messages (`data.code`, `data.message`, `data.context`, `logRef`).
  - For **metrics/status**: do not log every `status-*` tick; derive and record only state transitions (e.g. healthy → degraded → recovered) as local journal entries.
  - Avoid storing nested stringified payloads inside `details.payload`; store structured `data` instead.
- [x] Schema clean‑up:
  - Treat `actor` as redundant (it can be derived from `origin` + `scope` + routing direction). It may stay in the on‑disk JSON for now but should not be required by UI or new tooling.
- [x] Hive UI:
  - Simplify `SwarmJournalPage` to rely on:
    - `origin` + routing key for “from → to”.
    - Outcome `data.status` for status display.
    - Alert `data.code` / `data.message` and metric IO fields for surfacing worker/controller/orchestrator problems, “out of data”, etc.
  - Remove any UI‑side heuristics that try to reconstruct this information from `details` blobs.

---

## 6. Status & IO Semantics

- [ ] Define a minimal standard for IO state at control‑plane level:
  - Inputs: `ok | out-of-data | backpressure | upstream-error | unknown`.
  - Outputs: `ok | blocked | throttled | downstream-error | unknown`.
- [ ] Wire IO semantics into:
  - Worker status snapshots (per worker).
  - Swarm‑level status aggregates (derived from worker metrics).
  - Relevant `event.alert.alert` instances when an error or readiness condition is directly tied to IO conditions (for example stop due to out‑of‑data).
- [ ] Document how IO state should be interpreted in debugging (e.g. journal, Hive UI tooltips, docs).

---

## 7. Tests & Validation

- [ ] Update unit tests in:
  - `common/topology-core` (model types).
  - `common/control-plane-core` (emitters, routing).
  - `common/worker-sdk`, `swarm-controller-service`, `orchestrator-service`.
  - E2E helpers (`e2e-tests` control‑plane utilities) to parse and assert the new envelopes.
- [ ] Add targeted tests for:
  - A successful config‑update round‑trip (signal → outcome → journal).
  - A worker config parse/validation error surfaced as `CommandOutcome` + `event.alert.alert`.
  - A swarm lifecycle timeout / controller error surfaced as `CommandOutcome` + `event.alert.alert`.
- [ ] Verify that the journal JSON produced by SC and read by orchestrator matches the canonical envelope‑based projections and that Hive UI renders the simplified fields as expected.
- [ ] Maintain a short manual test plan (to be run before and after the refactor) that covers:
  - Swarm lifecycle happy path (create → template/plan → start → stop → remove) driven via Orchestrator REST, with confirmations and status events observed on the control exchange and in the swarm journal.
  - Idempotent retries for at least one lifecycle command (e.g. `swarm-start`) using the same `idempotencyKey`, verifying correlation and confirmation behaviour.
  - Controller and worker `config-update` success and failure, including how enablement changes and errors are surfaced in outcomes, status metrics and (after refactor) alerts.
  - Explicit `status-request` flows (`signal.status-request` → `event.metric.status-full`) exercised via REST, matching the documented topic patterns.
  - At least one runtime error and one IO exhaustion scenario, ensuring that today’s error surface is understood and can be mapped to `event.alert.alert` in the new model.
