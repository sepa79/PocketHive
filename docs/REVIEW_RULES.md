# Review Rules

## Purpose

This document is the reviewer checklist for non-trivial PocketHive changes. It
is broader than the short project invariants stored in HiveMind.

Review in this order:

1. non-negotiable rules in `AGENTS.md`,
2. `docs/ENGINEERING_RULES.md`,
3. the authoritative architecture, REST, AsyncAPI, schema, and lifecycle docs,
4. the checks below.

A violation of a hard project invariant is blocking. A review-rule violation is
also blocking when it creates ambiguous ownership, hidden behavior, or a new
kitchen sink.

## 1. Scope and durable direction

Check:

- the change matches the requested scope,
- durable behavior is documented before implementation,
- public REST, event, schema, routing, and filesystem changes update their
  canonical contract first,
- unrelated cleanup has not obscured the change.

Reject when behavior exists only in code, a test fixture, or conversation.

## 2. File shape and responsibility

Check:

- one Java production type per file, except the narrow private nested-type
  exception,
- one TypeScript/React runtime module or component concern per file,
- one clear responsibility per file,
- required responsibility headers exist and remain accurate,
- listeners and controllers are thin boundaries,
- handlers, state machines, projections, repositories, and adapters are
  separated,
- existing kitchen-sink files are reduced rather than expanded.

Reject when:

- a file becomes or remains the convenient home for unrelated behavior,
- a public nested DTO/enum or `*Contracts` bag is introduced or expanded,
- transport, domain decisions, persistence, and response construction are mixed,
- a responsibility header is widened to avoid extraction.

## 3. Authority and SSOT

Search the whole repository for every affected responsibility.

Check that there is exactly one owner for:

- wire and filesystem contracts,
- parsing, validation, normalization, and mapping,
- routing and topology construction,
- effective configuration and path resolution,
- state transitions and operation terminalization,
- success postconditions and terminal evidence.

Two active authorities are a critical blocker unless architecture explicitly
defines non-overlapping ownership.

## 4. Control plane

Check:

- routing keys come from the shared routing utility,
- listener code decodes and dispatches but does not execute domain workflows,
- every signal/result/outcome/status uses the canonical codec,
- correlation, idempotency, concrete target, and `runId` are matched at every
  acceptance boundary,
- duplicate delivery continues existing execution or replays stored evidence,
- status-full and status-delta retain distinct documented semantics,
- only the canonical operation owner terminalizes and publishes public outcome.

Reject silent duplicate drops, stale-run acceptance, hand-built routing, or
terminal evidence assembled by multiple services.

## 5. Configuration and infrastructure boundaries

Check:

- required adapter and settings are explicit and validated,
- no fallback chain or compatibility heuristic was introduced,
- one resolver owns queue names, paths, exchange names, and effective settings,
- domain/application code depends on ports rather than Docker, RabbitMQ,
  filesystem, database, or Spring implementations.

## 6. Failure behavior and observability

Check:

- failure is explicit and does not claim an unverified effect,
- retries and timeouts are bounded and safe under at-least-once delivery,
- terminal evidence reports actual missing/non-converged resources,
- correlation context is preserved in logs and events,
- exceptions are translated at boundaries rather than swallowed.

## 7. Tests

Check:

- tests use official ingress/API paths where required,
- producer-derived wire payloads are validated by the canonical codec/schema,
- each extracted handler/state machine has focused tests,
- wrong target, wrong controller instance, stale `runId`, duplicate delivery,
  timeout, and late result paths are covered where relevant,
- test fixtures and E2E audits do not duplicate production validators or outcome
  calculators,
- test classes and step definitions are not kitchen sinks.

## 8. Verification

Check:

- commands cover the changed surface,
- contract checks and relevant module tests passed,
- integration/E2E evidence uses the supported environment boundary,
- unverified areas and existing failures are stated explicitly,
- `git diff --check` passes.

## Suggested severity

Critical/blocking:

- competing SSOT authorities,
- state or terminalization owned by multiple components,
- contract/routing divergence,
- stale identity accepted as current state.

High/blocking:

- new or expanded kitchen-sink class,
- domain behavior in transport/controller code,
- silent duplicate suppression,
- missing lifecycle postcondition evidence.

Medium unless impact raises severity:

- public contract bags and misplaced contract versions,
- oversized tests that obscure responsibility boundaries,
- missing responsibility header on materially changed runtime code.

## Review output

State at least:

- findings ordered by severity, or `no findings`,
- canonical owners checked and repository-wide searches performed,
- commands and tests run,
- unverified areas,
- merge recommendation and remaining risks.

## Relationship to HiveMind

HiveMind holds a short set of stable, enforceable project invariants. This file
holds the broader diagnostic checklist. Review findings worth preserving should
be recorded through `docs/ai/HIVEMIND_WORKFLOW.md`.
