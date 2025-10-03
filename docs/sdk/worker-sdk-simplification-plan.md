# PocketHive Worker SDK Simplification Plan

## Context
Current worker services (generator, moderator, processor, postprocessor, trigger, etc.) embed substantial infrastructure code alongside their business logic. Each service directly manages Rabbit listeners, control-plane wiring, configuration updates, status heartbeats, observability propagation, and metrics. This makes onboarding difficult and violates the goal of exposing only a simple `generate()` API for generators and an `onMessage(WorkMessage)` API for other workers.

## Objectives
- Present workers as thin business components: a generator exposes a single `generate()` method; all other workers expose an `onMessage(WorkMessage)` method that returns the outbound payload.
- Hide message transport, control-plane, observability, and metrics boilerplate behind an enriched Worker SDK runtime.
- Provide a staged roadmap so implementation can proceed incrementally without breaking existing deployments.

## Success Criteria
- New workers require <100 lines and touch no messaging/control APIs directly.
- Control-plane semantics (ready/error/status) remain intact and reusable across worker types.
- Migrated services (generator, moderator, processor) compile with only the Worker SDK API plus business logic.

## Guardrails
- Obey existing architecture contracts (`docs/ARCHITECTURE.md`, control-plane docs) and avoid changing routing/topics without prior doc updates.
- Preserve observability headers, correlation IDs, and idempotency behaviour.
- Legacy worker implementations can be replaced outright; no backward compatibility layer is required.

## Stage Roadmap

### Stage 0 – API & contract alignment
- Draft Worker SDK API design (`docs/sdk/worker-sdk-stage0-design.md`) detailing `GeneratorWorker`, `MessageWorker`, `WorkMessage`, and `WorkerContext` contracts.
- Validate assumptions with architecture docs and update any affected public contracts.
- Identify configuration schema strategy (records/POJOs) for control-plane config updates.

### Stage 1 – Worker runtime skeleton
- ✅ Introduce the runtime primitives described in [`docs/sdk/worker-sdk-stage1-runtime.md`](worker-sdk-stage1-runtime.md), including worker discovery, runtime dispatch, and context creation.
- Provide unified `WorkMessage` abstraction (headers, body, metadata) and helper builders (JSON, binary, text).
- Introduce `WorkerDescriptor` annotation/bean describing role, queues, and worker type (generator vs message).

### Stage 2 – Control-plane integration
- ✅ Extract existing `WorkerControlPlane` usage into a reusable `ControlPlaneRuntime` that handles signal dispatch, config parsing, and ready/error emission.
- ✅ Supply a `StatusPublisher` helper that tracks TPS, routes, and custom data while exposing `emitFull/emitDelta` hooks.
- ✅ Ensure duplicate-signal guard, self-filter, and routing resolution move into the runtime layer.

### Stage 3 – Observability & extras
- ✅ Fold MDC/ObservabilityContext handling into runtime interceptors so workers never manipulate MDC directly.
- ✅ Expose optional hooks for metrics, logging, or message interceptors without leaking core infrastructure.
- ✅ Provide testing utilities in `worker-sdk-testing` for unit/integration tests with the new abstractions.

### Stage 4 – Core worker rollout
- ✅ Replace generator with a `GeneratorWorker.generate()` implementation that delegates rate control to the runtime (0.11.0).
- ✅ Rebuild moderator and processor as `MessageWorker.onMessage()` components returning outbound `WorkMessage` objects (0.11.0).
- ✅ Add regression tests ensuring control-plane behaviour (config success/error, status updates) matches expectations in the new runtime.

### Stage 5 – Adoption & cleanup
- ✅ Reimplement the postprocessor directly against the new SDK (0.11.3); legacy wiring removed along with dedicated tests.
- ⬜ Reimplement trigger and any remaining workers on the SDK.
- ⬜ Delete legacy infrastructure code paths immediately after each service migrates, since compatibility layers are unnecessary.
- ⬜ Update developer documentation and starter templates to showcase the simplified API.
- ⚠️ Known gap: migrated workers are not auto-started on boot—the control-plane still needs to send an enable signal; backlog item remains open to restore automatic start-up.

## Deliverables
- Worker SDK design doc (Stage 0).
- New runtime components and abstractions in `common/worker-sdk` (Stages 1–3).
- Migrated services with accompanying tests (Stage 4).
- Final documentation updates, migration guide, and removal of legacy paths (Stage 5).

## Risks & Mitigations
- **Contract drift:** Keep architecture/control-plane docs updated before code changes.
- **Behaviour regression:** Introduce integration tests per worker during migration to catch differences in control-plane emissions and message formatting.
- **Adoption friction:** Provide code-mod examples and optional compatibility shims so teams can migrate gradually.

## Open Questions
- How should configuration payloads map onto worker-specific config records (Jackson module vs manual conversion)?
- Do we need async/streaming variants of `onMessage` for long-running work?
- Should WorkMessage expose typed payload views (JSON tree vs typed DTO) or remain byte-oriented with helpers?
