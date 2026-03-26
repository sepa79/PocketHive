# Swarm Lifecycle + Health Model Plan

## Scope
- Goal: replace the current coarse `swarmStatus` / `swarmHealth` interpretation with a clearer operator-facing model.
- The new model should make startup progress, blocking steps, and runtime failures understandable from Hive without immediate log inspection.
- This work is **spec-first**. Architecture docs and message specs must be aligned before implementation starts.

## Problem Summary
- Current swarm state is too coarse and mixes lifecycle with operational condition.
- Multiple critical startup failures currently collapse into `DEGRADED`, and some broken swarms can still appear as `READY` or `RUNNING`.
- Operators need to see:
  - which startup/runtime step the swarm is in,
  - how long it has been there,
  - whether the swarm is operationally healthy.

## Proposed Direction
- Keep one lifecycle field that reflects the real startup / runtime sequence.
- Keep one health field that reflects operational truth.
- Before the Swarm Controller exists, Orchestrator owns lifecycle progression.
- After the Swarm Controller has started and reported in, the Swarm Controller becomes the source of truth for lifecycle and health.
- If the Swarm Controller disappears, lifecycle becomes last-known and health degrades based on staleness / timeout rules.

## Target Model

### Lifecycle
- Lifecycle should be granular enough to reflect visible startup steps, not low-level technical internals.
- Candidate states:
  - `CREATING_ENV`
  - `LAUNCHING_CONTROLLER`
  - `BOOTING`
  - `APPLYING_TEMPLATE`
  - `APPLYING_PLAN`
  - `AWAITING_WORKERS`
  - `READY`
  - `STARTING`
  - `RUNNING`
  - `STOPPING`
  - `STOPPED`
  - `REMOVING`
  - `REMOVED`
  - `FAILED`

### Health
- Candidate values:
  - `OK`
  - `DEGRADED`
  - `FAIL`
  - `UNKNOWN`

### Semantics
- `OK`
  - expected state progression is happening normally
  - all required workers are present and fresh for the current lifecycle step
- `DEGRADED`
  - swarm is complete and running, but runtime errors are occurring
  - use only for still-operational swarms with active runtime issues
- `FAIL`
  - startup/provisioning failed
  - any required worker is missing
  - any required worker is stale
  - swarm is not operational
- `UNKNOWN`
  - reserved for truly insufficient/stale state information, not normal bootstrap

## Core Rules
- Normal bootstrap phases such as `CREATING_ENV` and `LAUNCHING_CONTROLLER` should report `health=OK`, not `UNKNOWN`.
- Missing or stale required workers are `FAIL`, not `DEGRADED`.
- `DEGRADED` should be reserved for runtime problems after the swarm has become operational.
- Lifecycle should represent sequence milestones, not internal implementation details.

## Time + Debugging Requirements
- Each lifecycle state should expose when it was entered.
- Hive should show lifecycle duration, for example:
  - `APPLYING_TEMPLATE · 8s`
  - `AWAITING_WORKERS · 34s`
- Timeout handling should be per lifecycle step, not one global timeout for all startup stages.

## Delivery Strategy

### Phase 1: Timer-based model
- Implement lifecycle entered-at timestamps.
- Add per-state timeout thresholds.
- Surface `lifecycle + elapsed + health` in Hive.
- Use static timeout policy first.

### Phase 2: Progress heuristics
- Add optional progress hints for slow stages, especially `AWAITING_WORKERS`.
- Candidate hints:
  - image pulling
  - container creation in progress
  - waiting for first worker status
- This phase should refine operator messaging, not replace lifecycle.

## Required Spec / Doc Changes
- [ ] Update [ARCHITECTURE.md](/home/sepa/PocketHive/docs/ARCHITECTURE.md)
  - redefine lifecycle ownership between Orchestrator and Swarm Controller
  - redefine health semantics
  - update health/heartbeat section
  - update lifecycle/state section
  - update UI projection notes

- [ ] Update [asyncapi.yaml](/home/sepa/PocketHive/docs/spec/asyncapi.yaml)
  - align status metric examples and field descriptions
  - define lifecycle + health fields for swarm-controller status payloads

- [ ] Update [control-events.schema.json](/home/sepa/PocketHive/docs/spec/control-events.schema.json)
  - add canonical schema for lifecycle and health fields in `status-full` / `status-delta`
  - remove ambiguity around current `swarmStatus` / `swarmHealth` meaning

## Suggested Implementation Plan
- [ ] Write and agree the new lifecycle + health contract in docs/spec first.
- [ ] Update architecture narrative to match the contract.
- [ ] Refactor Swarm Controller state classification to emit the new model.
- [ ] Refactor Orchestrator state handling to distinguish:
  - pre-controller lifecycle ownership
  - post-controller lifecycle mirroring
  - last-known state handling when controller disappears
- [ ] Update Hive/UI to display:
  - lifecycle
  - elapsed time in lifecycle
  - health
  - clearer fail/degraded presentation
- [ ] Reclassify existing crash repro scenarios against the new model.
- [ ] Add tests for:
  - template failure
  - worker-missing startup failure
  - runtime degraded case
  - controller disappearance / stale last-known state

## Repro Cases To Reuse
- `local-rest-invalid-volume-template`
  - expected classification: startup/provisioning failure
- `local-rest-worker-crash`
  - expected classification: startup failure due to missing worker
- `ctap-iso8583-request-builder-demo`
  - expected classification: runtime degraded or runtime fail depending on final agreed rule

## Open Questions
- [ ] Final lifecycle state names
- [ ] Whether `FAILED` remains a lifecycle state or becomes health-only
- [ ] Exact rule for controller disappearance:
  - last-known lifecycle + `UNKNOWN`
  - last-known lifecycle + `FAIL`
  - grace window then `FAIL`
- [ ] Exact threshold policy per lifecycle step
- [ ] Whether progress hints should be persisted in the control-plane contract or remain best-effort UI diagnostics
