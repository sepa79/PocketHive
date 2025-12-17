# Java Guidelines — PocketHive

Scope: contributions to PocketHive Java services (Java 21, Spring Boot).

Goals:
- Keep service boundaries clean (ports/adapters, no cross-module leakage).
- Keep runtime behaviour deterministic (idempotency, timeouts, retries).
- Keep configuration explicit (no implicit fallbacks, no “optional core state”).

Authoritative references (SSOT):
- Architecture/layering: `docs/ARCHITECTURE.md`
- Correlation vs idempotency: `docs/correlation-vs-idempotency.md`
- Control-plane testing strategy: `docs/ci/control-plane-testing.md`

## 1) Service boundaries (PocketHive SRP)

- Do not blend concerns across modules (orchestrator, swarm-controller, generator, moderator, processor, postprocessor, trigger).
- Domain logic depends on ports; concrete adapters live in infra (DIP). Prefer small interfaces (ISP).
- Extend behaviour by adding new handlers/components; avoid modifying shared contracts “just for this case” (OCP).

## 2) Configuration (explicit, validated, no fallback chains)

- **No cascading defaults**: do not implement `a.orElse(b).orElse(c)` style property chains.
- Prefer:
  - `@ConfigurationProperties` + `@Validated` and fail-fast on missing/invalid config.
  - Single, explicit “effective config” calculation in one place when combining sources is unavoidable.
- Avoid “hidden defaults” inside domain code; defaults belong at the edges (config parsing) or in a single explicit builder.

## 3) Optionals (don’t use for core state)

- Do not use `Optional` for:
  - enablement flags,
  - mode selection,
  - required configuration.
- Preferred alternatives:
  - `enum` for mode (`AUTO/ON/OFF`, `TYPE_A/TYPE_B`), not “optional boolean/string”.
  - explicit config object with required fields + validation.
  - explicit “source” fields when you must represent override/default (without fallback chains).

## 4) Contracts & DTOs (SSOT, no duplicate parsers)

- Do not maintain multiple independent validators/parsers for the same on-wire format.
- Keep on-wire DTOs independent of Lombok implementation details (do not expose Lombok-specific types in JSON contracts).
- If a public contract changes, update the relevant doc first (SSOT) and keep code aligned to it.

## 5) Correlation, idempotency, retries (must be intentional)

- Propagate `correlationId` end-to-end per `docs/correlation-vs-idempotency.md`.
- Idempotency keys:
  - define once (what identifies “the same operation”) and enforce consistently.
  - never reuse correlation IDs as idempotency keys unless the spec explicitly says so.
- Timeouts/retries:
  - always bounded and observable (logs/metrics).
  - retries must be safe under at-least-once delivery (idempotent handlers).

## 6) Error handling (predictable edges)

- Throw domain exceptions from domain code; translate at the boundary (REST controller, message handler).
- Avoid swallowing exceptions unless it is explicitly “best-effort” and still observable.
- Prefer structured error responses/log events rather than ad-hoc strings.

## 7) Logging & metrics (baseline)

- Do not log secrets, tokens, or large payloads by default.
- Ensure logs include correlation context for cross-service debugging.
- Prefer stable, searchable keys (avoid “free-form blobs”).

## 8) Tests (by layer)

- Follow `docs/ci/control-plane-testing.md` patterns:
  - unit tests for pure logic,
  - slice tests for Spring wiring where it matters,
  - integration tests with Testcontainers/WireMock for IO boundaries.
- When changing contracts/merge semantics/idempotency behaviour, add/adjust tests that prevent regressions.

## 9) PR hygiene (Java)

- Java 21 everywhere.
- Keep changes scoped; do not “clean up” unrelated code in the same PR.
- Update docs when behaviour/contracts change (link the SSOT doc in the PR).
