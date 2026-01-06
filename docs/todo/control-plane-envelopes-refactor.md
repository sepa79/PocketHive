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
    | `scope.swarmId` | string     | Yes      | Swarm the message relates to. Use the literal `ALL` for cross‑swarm or global fan‑out; never `null`. |
    | `scope.role`    | string     | Yes      | Role of the **subject** of the message; free‑form logical role id. Core roles include `orchestrator`, `swarm-controller`, `generator`, `moderator`, `processor`, `postprocessor`, `trigger`, but plugins may introduce additional roles. Use the literal `ALL` for cross‑role or fan‑out scopes; never `null`. The envelope schema must **not** hardcode an enum for this field. |
    | `scope.instance`| string     | Yes      | Logical instance identifier of the **subject** of the message (the controller/worker/orchestrator instance the message is about). Use the literal `ALL` for fan‑out across instances; never `null`. This may or may not be the same as the `origin` instance that emitted it. |
    | `correlationId`  | string\|null | Yes     | Correlation token used to join related messages. For `kind=signal` / `kind=outcome`, this field **must** be non‑empty and identical across the command signal and its outcomes. For other kinds (`event`, `metric`) it is either `null` or used only for explicitly documented higher‑level correlations. |
    | `idempotencyKey` | string\|null | Yes     | Stable identifier reused across retries of the same logical operation. For externally initiated `kind=signal` / `kind=outcome` messages this field **should** be non‑empty; for purely internal, non‑retriable messages it may be `null`. For non‑command kinds (`event`, `metric`) this field is typically `null`. |

    Relationship to routing keys (new prefixes):

    - Control‑plane **signals** use the `signal.*` prefix. The canonical pattern after the refactor is:  
      `signal.<commandType>.<swarmId>.<role>.<instance>` where:
      - `<commandType>` is the envelope `type` for `kind = signal` (for example `swarm-start`, `swarm-stop`, `swarm-remove`, `config-update`, `status-request`).
      - `<swarmId>.<role>.<instance>` are the semantic target and must match `scope.swarmId` / `scope.role` / `scope.instance` on the signal. For fan‑out signals the routing key may still use wildcards (for example `ALL`), while `scope` on outcomes will carry concrete values. **Lifecycle commands addressed at the swarm‑controller (`swarm-template`, `swarm-plan`, `swarm-start`, `swarm-stop`, `swarm-remove`) MUST use a concrete controller instance in the `<instance>` segment once a controller exists; using `ALL` for these commands is forbidden in the new model. Fan‑out across multiple controllers, if ever needed, must be implemented by sending separate instance‑specific commands, not by relying on `ALL`.**

    - Control‑plane **events** (everything that is not a command signal) use the `event.*` prefix. The canonical pattern is:  
      `event.<category>.<name>.<swarmId>.<role>.<instance>` where:
      - `<category>` differentiates major event families such as `outcome`, `metric`, `alert` (for example `event.outcome.*`, `event.metric.*`, `event.alert.alert.*`). The double `alert` is intentional: `category=alert` and `name=alert` for the single alert event type.
      - `<name>` is normally the envelope `type` within that family (for example `status-full`, `status-delta` for metrics, or the command name such as `swarm-start` / `config-update` for outcomes).
      - `<swarmId>.<role>.<instance>` are the semantic subject and must match `scope.swarmId` / `scope.role` / `scope.instance`, normalised so that fan‑out uses the literal `ALL` in both the routing key and `scope` (no `null` placeholders).

    - For **command outcomes** (`kind = outcome`), the routing key uses the `event.outcome.*` family:  
      `event.outcome.<commandType>.<swarmId>.<role>.<instance>`. Here `<commandType>` matches the originating signal’s `type` (command name), `scope` describes the concrete subject that actually processed the command, and `correlationId` / `idempotencyKey` join the outcome back to the original `signal.*` message.

  - **Structured sections**

    | Section / Field      | Type          | Applies to       | Description                                                                                                      |
    |----------------------|---------------|------------------|------------------------------------------------------------------------------------------------------------------|
    | `data`               | object        | all kinds        | Structured payload for the message. On-wire producers always emit an object; commands without args send `{}` and outcomes must include at least `data.status`. For each (`kind`, `type`) combination, the AsyncAPI / JSON Schema specs in `docs/spec` MUST define the required shape of `data` (required fields + optional extension fields). Targeting is never carried inside `data`; targeting is described only by `scope` and the routing key. All additional, best‑effort metadata for that message also lives under `data`. |

  - **Known `data` schemas for existing messages (today)**

    The tables below describe the canonical `data` shapes for the message kinds/types covered by the current specs in `docs/spec/asyncapi.yaml` / `docs/spec/control-events.schema.json`.

    **Control metrics (`kind = metric`)**

    | `type`         | `data` field       | Required | Description                                                                                     |
    |----------------|--------------------|----------|-------------------------------------------------------------------------------------------------|
    | `status-full`  | `enabled`          | Yes      | Boolean. Indicates whether this component is currently allowed to run workloads for its scope. |
    |                | `startedAt`        | Yes      | RFC‑3339 timestamp when this component started processing workloads for its scope (or when the current process was started). |
    |                | `tps`              | No       | Integer ≥ 0. Throughput sample for the reporting interval. **Workers should emit this**; managers (Orchestrator / Swarm Controller) may omit. |
    |                | `config`           | Yes      | Snapshot of the effective configuration for this scope (role/instance). Must not include secrets. |
    |                | `io`               | Yes      | Object describing IO bindings and queue health. **Workers** should include both planes (`io.work` + `io.control`); **managers** are control‑plane‑only and should include only `io.control` (no `io.work`). `queueStats` is optional and applies only to the work plane. Present only in `status-full`. |
    |                | `ioState`          | Yes      | Coarse IO health summary for workload/local IO only (for example `ioState.work`, `ioState.filesystem`). **Workers** should include `ioState.work` plus any local IO; **managers** include only local IO if applicable. `ioState` does not represent control‑plane health. |
    |                | `context`          | No       | Freeform role‑specific context. For swarm‑controller, `context` carries swarm aggregates (e.g. `swarmStatus`, `totals`, `watermark`, `maxStalenessSec`, scenario progress) and includes `context.workers[]` **only in `status-full`**. For orchestrator, `context` carries at least `swarmCount`; `computeAdapter` is effectively static and belongs in `status-full` (not `status-delta`). |
    | `status-delta` | `enabled`          | Yes      | Boolean. Same semantics as in `status-full`; used to signal enablement changes without resending full status snapshots. |
    |                | `tps`              | No       | Integer ≥ 0. Throughput sample for the interval since the last status event. **Workers should emit this**; managers may omit. |
    |                | `ioState`          | Yes      | Coarse IO health summary (see §6). Same rules as `status-full`: workload/local IO only; managers omit `work`. |
    |                | `context`          | No       | Same semantics as in `status-full`, but only for fields that change frequently (for example recent `swarmStatus`, rolling diagnostics). `data.config`, `data.io`, and `data.startedAt` must be omitted from deltas. |

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
    | `swarm-start`    | —                  | No       | No command‑level args; semantics come from `type`, `scope`/routing, `correlationId` and `idempotencyKey`. On-wire producers still send an empty `data: {}` to keep envelopes schema‑compliant. |
    | `swarm-stop`     | —                  | No       | Same as `swarm-start` (no args); on-wire producers still send an empty `data: {}`.                               |
    | `swarm-remove`   | —                  | No       | Same as `swarm-start` (no args); on-wire producers still send an empty `data: {}`.                               |
    | `config-update`  | `data`             | Yes      | Config payload for the target component(s). Targeting is carried exclusively by the envelope `scope` and routing key. The `data` object carries the config patch and enablement flags. Exact shape is defined in worker/manager config docs. |
    | `status-request` | —                  | No       | No command‑level args; the response is a `status-full` metric event instead of a confirmation outcome. On-wire producers still send an empty `data: {}`. |

    **Command outcomes (`kind = outcome`) — current payloads**

    For **outcome** messages (`kind = outcome`, `type = <command>`), outcomes use a single `CommandOutcomePayload` envelope shape; the table below captures the field-level mapping from the legacy confirmation shape.

    | Field (today)   | Planned location                | Description                                                                                             |
    |-----------------|---------------------------------|---------------------------------------------------------------------------------------------------------|
    | `state.status`  | `data.status`                  | High‑level status after processing the command (for example `Ready`, `Running`, `Stopped`, `Removed`, `Failed`, `Applied`, `NotReady`).  |
    | `state.enabled` | — (removed)                    | Removed in the new model. Enablement lives in a single place: `data.enabled` on config‑update outcomes and `data.enabled` in status metrics; there is no generic `state.enabled` field. |
    | `state.details` | `data.context`                 | Structured post‑command state details (for example `workloads.enabled`, scenario changes, worker info), to be defined per command type. No separate `controllerEnabled` field is kept. |
    | `phase`         | — (removed or mapped to alert) | Error phase will not be carried as a generic outcome field. If needed for debugging, producers include it in alert `data.context.phase` for the corresponding `event.alert.alert` message. |
    | `code`          | — (replaced by alert `data.code`)   | Command outcomes no longer carry their own error/result code; runtime and IO errors are expressed via `event.alert.alert` with `data.code`.                                              |
    | `message`       | — (replaced by alert `data.message`) | Human‑readable error/message text for failures is carried by `event.alert.alert.data.message` rather than command outcome envelopes.                                                      |
    | `retryable`     | `data.retryable`               | Whether this **failed** command attempt is safe to retry. Only set on error outcomes for commands where retry semantics are defined (for example swarm create/start/stop/remove).    |
    | `details`       | — (folded into `data.context`) | Catch‑all details on confirmations are removed. Any structured context that needs to survive goes into `data.context` on the outcome and/or the corresponding `event.alert.alert`.       |

    **Initialization + readiness gates (`swarm-start`, `swarm-stop`, `config-update`)**

    - Initialization is satisfied after the controller has successfully processed both
      `swarm-template` and `swarm-plan` for the swarm.
    - Readiness is defined as: `isReadyForWork == true` AND `hasPendingConfigUpdates == false`.
    - Commands allowed before initialization: `swarm-template`, `swarm-plan`, `status-request`,
      and `swarm-remove` (abort).
    - `swarm-start` is rejected unless initialization + readiness are satisfied. A rejected
      `swarm-start` emits an outcome with `data.status = "NotReady"` and a `data.context`
      payload that captures the gating flags (for example `initialized=false`, `ready=false`,
      `pendingConfigUpdates=true`).
    - `swarm-stop` and controller-targeted `config-update` are rejected unless initialization
      + readiness are satisfied and the swarm is already `RUNNING`. Rejections use the same
      `NotReady` outcome pattern; no side effects occur when rejected.

  - Keep routing keys and channels in AsyncAPI as the single source of truth for where these envelopes flow, and update them to use the new `signal.*` / `event.*` patterns described above.
- [x] Add a short overview under `docs/control-plane/README.md` that points to the JSON Schema / AsyncAPI specs as the canonical definition of envelopes, with concrete examples.
- [x] Update `docs/correlation-vs-idempotency.md` to reference where correlation and idempotency live in the updated payload schemas and how they flow signal → outcome → status/journal.

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

- [x] Define a minimal standard for IO state at control‑plane level:
  - Inputs: `ok | out-of-data | backpressure | upstream-error | unknown`.
  - Outputs: `ok | blocked | throttled | downstream-error | unknown`.
- [x] Wire IO semantics into:
  - Worker status snapshots (per worker).
  - Swarm‑level status aggregates (derived from worker metrics).
  - Relevant `event.alert.alert` instances when an error or readiness condition is directly tied to IO conditions (for example stop due to out‑of‑data).
- [x] Document how IO state should be interpreted in debugging (e.g. journal, Hive UI tooltips, docs).
  - `data.io` is a **topology/metrics snapshot** (queues/routes + optional per-queue depth), required and present only in `status-full`.
  - `data.config` is a **configuration snapshot** (effective config for the scope), required and present only in `status-full`.
  - `data.ioState` is a **coarse aggregate** intended for fast debugging and alerting, required in both `status-full` and `status-delta`. It does not represent control-plane (AMQP) health; it represents workload/local IO such as `ioState.work` and `ioState.filesystem`.
  - For `role=swarm-controller`, `data.context` is the canonical place for **swarm aggregates**:
    - `status-delta`: small aggregate + progress (no worker list).
    - `status-full`: full aggregate snapshot including per-worker list (e.g. `data.context.workers`).
  - `out-of-data` is *not* inferred from queue depth; it is a logical “source exhausted” condition and should be emitted by inputs/generators via `ioState` + (optionally) an `event.alert.alert` with `code=io.out-of-data` and `data.context.dataset` when known.

### 6.1 Topology‑First: Logical Topology vs IO Adapter Config vs Runtime Bindings

Goal: give UI a stable “what to draw” graph that does **not** depend on transport details (Rabbit vs Redis vs CSV),
while still exposing enough runtime detail to debug work-plane delivery and backpressure.

**A) Logical Topology (scenario SSOT; UI drawing contract)**

- Stored in scenario templates (see `docs/scenarios/SCENARIO_CONTRACT.md`), not in status messages.
- Describes roles/ports/edges only; does **not** encode:
  - queue names, routing keys, exchanges,
  - IO adapter types (Redis/CSV/HTTP),
  - concrete runtime instanceIds (there can be many instances per role).

Because scenarios can already contain multiple bees with the same `role` (e.g. `local-rest-two-moderators`,
`local-rest-with-multi-generators`), `role` is **not** a stable join key for UI. The stable join key must be a per-bee
authoring-time identifier.

Proposed split of responsibilities:
- `template.bees[]` is the **SSOT for nodes** (identity + role + optional UI metadata + optional port declarations).
- `topology` is the **SSOT for edges** (graph connections), referencing bees by `beeId` and ports by `port`.

Minimal proposed shape (example YAML fragment embedded in a scenario template):

```yaml
template:
  bees:
    - id: genA
      role: generator
      ui:
        label: "Generator A"
      ports:
        - id: out
          direction: out
      # image/config/work/env as today
    - id: modA
      role: moderator
      ui:
        label: "Moderator A"
      ports:
        - { id: in, direction: in }
        - { id: out, direction: out }

topology:
  version: 1
  edges:
    - id: e1
      from: { beeId: genA, port: out }
      to:   { beeId: modA, port: in }
```

Constraints:
- `template.bees[].id` and `topology.edges[].id` are stable identifiers within the template (UI uses them as keys).
- `template.bees[].ports[]` is optional; if omitted, UI assumes a default port set or uses `work.in/work.out` conventions.
- `topology.edges[].from.beeId` / `.to.beeId` must reference an existing `template.bees[].id`.

**B) IO Adapter Config (runtime behaviour; per-module configuration)**

- Lives in worker config (`status-full.data.config` for worker scope).
- Can include adapter types and settings (CSV/Redis/HTTP/etc). This is **not** a graph and must not try to replace topology.

**C) Runtime Bindings (materialisation; “what is actually wired on this swarm right now”)**

- Emitted by Swarm Controller in `status-full` only, so UI can map logical edges/ports to work-plane routing:
  - which exchange is used for work traffic,
  - which routing keys are produced/consumed for each logical connection,
  - optional queue names and queue stats where they exist.
- This is intentionally separate from the logical topology because it is environment- and swarm-specific.

Minimal proposed shape (inside SC `status-full.data.context`):

```json
{
  "bindings": {
    "work": {
      "exchange": "ph.<swarm>.traffic",
      "edges": [
        {
          "edgeId": "e1",
          "from": { "role": "generator", "instance": "gen-1", "routingKey": "ph.<swarm>.gen" },
          "to":   { "role": "moderator", "instance": "mod-1", "queue": "ph.<swarm>.mod" }
        }
      ]
    }
  }
}
```

Notes:
- `bindings` should be **best-effort** and may be partial (e.g. workers not ready yet).
- This is the place where “Rabbit out is not a queue” is modelled correctly:
  - producer side: `routingKey` (+ exchange),
  - consumer side: `queue` (and optionally `queueStats` reported separately under `data.io.work.queueStats`).

**D) UI join strategy**

- UI obtains `template + topology` via Scenario Manager REST (SSOT).
- UI uses SC `status-full` for:
  - `workers[]` (per-instance status snapshots),
  - a stable mapping from runtime instances to authoring nodes (`workers[].beeId` or equivalent),
  - `bindings` (runtime materialisation),
  - queue stats (from workers / SC snapshots where available).

Tracking / tasks:
- [ ] Extend `docs/scenarios/SCENARIO_CONTRACT.md` with `template.bees[].id`, `template.bees[].ports`, optional `template.bees[].ui`, and `topology.edges[]` + validation rules.
- [ ] Extend `Bee` (swarm-model) with `id` (or parallel field) and propagate it into runtime worker identity mapping.
- [ ] Add Scenario Manager REST to fetch topology (by template id/name + revision/hash).
- [ ] Emit SC `status-full.data.context.bindings` (work-plane materialisation) and include a stable scenario identifier for UI join.
- [ ] Update SC `workers[]` aggregate to include `beeId` for each runtime instance (so UI can join per-node when roles repeat).
- [ ] Update UI to draw from `topology.edges[]` + node metadata from `template.bees[]`, join runtime by `beeId`.

---

## 7. Tests & Validation

- [x] Update unit tests in:
  - `common/topology-core` (model types).
  - `common/control-plane-core` (emitters, routing).
  - `common/worker-sdk`, `swarm-controller-service`, `orchestrator-service`.
  - E2E helpers (`e2e-tests` control‑plane utilities) to parse and assert the new envelopes.
- [x] Add targeted tests for:
  - A successful config‑update round‑trip (signal → outcome → journal).
  - A worker config parse/validation error surfaced as `CommandOutcome` + `event.alert.alert`.
  - A swarm lifecycle timeout / controller error surfaced as `CommandOutcome` + `event.alert.alert`.
- [x] Verify that the journal JSON produced by SC and read by orchestrator matches the canonical envelope‑based projections and that Hive UI renders the simplified fields as expected (see `orchestrator-service/src/test/java/io/pockethive/orchestrator/app/SwarmControllerTest.java`).
- [x] Maintain a short manual test plan (to be run before and after the refactor) that covers:
  - Swarm lifecycle happy path (create → template/plan → start → stop → remove) driven via Orchestrator REST, with confirmations and status events observed on the control exchange and in the swarm journal.
  - Idempotent retries for at least one lifecycle command (e.g. `swarm-start`) using the same `idempotencyKey`, verifying correlation and confirmation behaviour.
  - Controller and worker `config-update` success and failure, including how enablement changes and errors are surfaced in outcomes, status metrics and (after refactor) alerts.
  - Explicit `status-request` flows (`signal.status-request` → `event.metric.status-full`) exercised via REST, matching the documented topic patterns.
  - At least one runtime error and one IO exhaustion scenario, ensuring that today’s error surface is understood and can be mapped to `event.alert.alert` in the new model.

### Manual test runbook (tight)

- Start stack: use `./build-hive.sh` (see `docs/USAGE.md` for current flags).
- Create a swarm in Hive UI and capture its `swarmId`.
- Validate command routing + outcomes:
  - Trigger `swarm-start` then `swarm-stop`.
  - For at least one command, repeat it with the same `idempotencyKey` (via the debug CLI) and confirm only one effective change happens while outcomes still correlate correctly.
- Validate status flows:
  - Trigger `status-request` and confirm a `event.metric.status-full` appears for the expected scope.
  - Confirm periodic `event.metric.status-delta` continues to update (TPS + enabled).
- Validate config-update success/failure:
  - Apply a known-good `config-update` to at least one worker; confirm an `event.outcome.config-update` is emitted and the worker status reflects the change.
  - Apply a known-bad `config-update` (type mismatch / invalid schema) and confirm:
    - `event.outcome.config-update` reports failure, and
    - `event.alert.alert` is emitted with stable `data.code` and structured `data.context` (plus `logRef` when available).
- Validate IO exhaustion surfacing:
  - Run a generator/input that can reach “out of data” and confirm:
    - worker emits `data.ioState.work.input=out-of-data` in status metrics, and
    - a single `event.alert.alert` with `code=io.out-of-data` is emitted on transition (no repeated alerts per tick).
- Validate journal/UI:
  - Open the swarm’s Journal view and confirm entries show direction + origin + kind/type, and row details show the raw control message (no escaped JSON blobs).
  - Confirm status tick spam is not present (only transitions and non-status signals/outcomes/alerts).
