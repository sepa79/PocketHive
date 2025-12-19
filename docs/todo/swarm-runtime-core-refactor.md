# SwarmRuntimeCore Refactor (Swarm Controller Runtime)

> Status: **critical / design**  
> Scope: break up `SwarmRuntimeCore` (1000+ LOC) into testable, single-purpose components.

## Problem

`swarm-controller-service/.../SwarmRuntimeCore.java` has grown into a large, multi-responsibility class.
This creates:

- hard-to-reason-about flows (bootstrap, lifecycle, reconciliation, control-plane IO, journaling hooks, etc.)
- poor testability (requires broad integration contexts for small behavior changes)
- high regression risk when making control-plane or lifecycle changes

## Goal

Reduce complexity and regression risk by:

- splitting responsibilities into cohesive units (SRP)
- introducing narrow interfaces where it clarifies boundaries
- adding focused tests per responsibility (unit-level where possible, integration only where required)

## Constraints / guardrails

- Keep behavior stable unless explicitly changed by a task.
- No compatibility shims unless required.
- Prefer incremental refactor steps that are reviewable and reversible.

## Proposed decomposition (example)

1) **Control-plane command handling**
   - Receiving and dispatching signals (`config-update`, `status-request`, lifecycle signals).
   - Correlation/idempotency management (where applicable).

2) **Workload provisioning**
   - Container lifecycle: create/start/stop/remove + health checks.
   - Volume/env wiring and adapter-specific behavior.

3) **Topology & routing**
   - Work/control queue declarations and routing (delegated to shared utilities).

4) **Scenario plan timeline / reconciliation loop**
   - State machine for “plan steps” (start/stop/reset).
   - Reconciliation timers and orchestration of per-role instances.

5) **Status emission & projection**
   - Snapshot vs delta emission and payload shaping.
   - Ensure adherence to `docs/spec/*` (validated by contract tests).

6) **Journal integration**
   - Publishing events to journal sinks (file/postgres) via a port/interface.

## Suggested refactor milestones

### Milestone A — Extract internal services (no behavior change)

- Extract “private helper clusters” into package-private classes, still called by `SwarmRuntimeCore`.
- Introduce small DTOs for internal state where it reduces parameter lists.

### Milestone B — Add focused tests

- Add unit tests for extracted services with minimal mocking.
- Keep an integration test that covers the end-to-end startup path.

### Milestone C — Contract-driven status cleanup (separate follow-up)

This milestone is intentionally separate because it changes behavior:

- Define what “minimal delta” means for `status-delta` and enforce it via schema+semantic tests.
- Remove duplicated or redundant status fields (once validated by consumers).

## Known follow-ups (out of scope for Milestone A/B)

- Fix “delta sends full config” and other status duplication issues.
- Reduce or eliminate duplicated projections in UI/state stores (once payloads are corrected).

