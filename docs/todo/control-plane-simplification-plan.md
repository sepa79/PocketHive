# Control-Plane Architecture Simplification Plan

> Status: active
> Direction: remove redundant test machinery, then eliminate mixed-responsibility control-plane and lifecycle implementation units before re-reviewing runtime findings

## Goal

Reduce the amount of control-plane code and the number of responsibilities that must be understood together.
Prefer deletion and direct ownership over new abstraction layers. Preserve runtime behavior during structural
refactors, then review the simplified architecture from scratch.

## Decisions

- Remove the E2E-only control-plane parser, schema/routing audit, message-family coverage, AMQP capture, and
  operation-expectation registry. Functional E2E scenarios through the official ingress remain the system gate.
- Existing duplicate definitions that currently agree are outside this refactor queue by explicit human decision.
  They are not considered resolved, must not be expanded, and will be reassessed after the kitchen sinks are gone.
- Do not carry E2E-01 through E2E-05 or CP-01 through CP-06 forward as an implementation queue. Perform a new
  repository-wide review after simplification and create findings from the resulting tree.
- Structural passes do not change public contracts, routing, lifecycle postconditions, or runtime behavior.
- Use no compatibility layer, fallback, parallel old/new execution path, or temporary second owner.

## Simplification rules

- Extract or delete one responsibility at a time.
- An extraction must remove that responsibility from the original sink in the same change.
- Do not introduce facade chains or one-method wrappers that only move code without clarifying ownership.
- Prefer a net reduction in code. Any increase must be justified by a removed mixed responsibility and focused tests.
- Keep listeners transport-only and controllers HTTP-only at the end of their refactor.
- Characterize behavior before moving it and run unchanged functional tests after each completed sink.
- Execute one sink at a time. Do not mix opportunistic CP fixes into behavior-preserving extraction passes.

## Phase 1 — remove the E2E-only parser/audit

- [x] Inventory the parser/audit and all exclusive callers, configuration, dependencies, and tests.
- [x] Delete the global audit hook, parser, AMQP capture, family allowlist, expectation registry, audit scope, and
  their dedicated tests.
- [x] Remove audit-only Maven dependencies, test-JAR publication, and launcher properties.
- [x] Remove operation-expectation registration from lifecycle steps without changing outcome assertions.
- [x] Delete the redundant functional `ControlPlaneEventParser` wrapper; keep the subscriber as a minimal adapter
  that delegates decoding and validation directly to `ControlPlaneCodec`.
- [x] Pass E2E module tests and repository-wide searches for removed owners.
- [x] Pass `git diff --check` and review the resulting deletion against the engineering rules.

Verification: `./mvnw -pl :e2e-tests -am test` passed all eight reactor modules; the E2E module retained nine
focused tests. The change removes 1,149 lines while adding 35 lines outside this plan document.

Gate: no E2E-owned control-envelope parser, schema validator, routing validator, message-family audit, or audit
subscription remains; functional Cucumber scenarios and their supported clients still compile and run normally.

## Phase 2 — inventory mixed control-plane/lifecycle implementation units

The inventory must classify responsibilities rather than use line count as the verdict. Initial confirmed sinks:

- Orchestrator `SwarmSignalListener` — transport, result acceptance, CREATE observation, config convergence,
  removal, timeout, terminal context, and journaling.
- Swarm Controller `SwarmSignalListener` — transport, lifecycle commands, Worker status acceptance, readiness,
  terminal results, status projection, and journaling.
- `SwarmRuntimeCore` — runtime state, provisioning, topology/work bindings, and projections.
- E2E `SwarmLifecycleSteps` — unrelated API actions, scenario state, discovery, lifecycle assertions, traffic
  assertions, and environment diagnostics.

Candidates requiring responsibility review include `WorkerControlPlaneRuntime`, Orchestrator `SwarmController`,
and other control-plane/lifecycle files triggered by the engineering-rules size and collaborator thresholds.

The Orchestrator listener is the first sink. Its extraction order is based on dependency direction, not method size:

1. Orchestrator status publication — independent scheduled output; extracted to `OrchestratorStatusPublisher`.
2. CREATE/config observation convergence — remove the current listener-to-listener coupling with
   `ControllerStatusListener` and give pending observation state one owner.
3. Filesystem removal convergence and cleanup — isolate filesystem evidence, postcondition verification, and
   registry/runtime cleanup from AMQP ingress.
4. Generic executor-result acceptance, expiration, and outcome publication — leave the listener with decode,
   context establishment, and typed dispatch only.

Each extraction must retain its existing focused tests. The affected Maven reactor is the per-extraction gate;
`build-hive.sh` and official-ingress E2E are required when the complete sink reaches its transport-only boundary.

- [ ] Record the complete responsibility inventory and the canonical owner expected after each extraction.
- [ ] Order sinks by dependency and risk, starting with the smallest boundary that establishes the extraction pattern.
- [ ] Define a characterization and verification gate for each sink before editing it.

## Phase 3 — eliminate sinks one at a time

Progress on Orchestrator `SwarmSignalListener`:

- [x] Move periodic delta and requested full-status publication to `OrchestratorStatusPublisher`.
- [x] Remove status scheduling, topology materialisation, emitter state, and compatibility methods from the listener.
- [x] Make `ControlPlaneSyncService` invoke the status owner directly.
- [x] Add focused full/delta status tests and pass the complete Orchestrator dependency reactor.
- [x] Extract CREATE/config observation convergence to `SwarmOperationObservationHandler`, remove transport-key
  retention from pending config state, and remove the listener-to-listener dependency.
- [x] Extract filesystem removal convergence and cleanup to `SwarmRemovalConvergenceHandler`; move filesystem
  evidence polling, postcondition verification, network/controller teardown, registry/runtime cleanup, durable
  terminal evidence, and REMOVE outcome publication out of the listener.
- [x] Move the five existing removal characterization cases to focused component tests and add unreadable-evidence
  coverage without changing removal semantics.
- [x] Extract executor-result acceptance, timeout construction, expiration scheduling, observation cleanup, and
  missed-outcome publication to `SwarmOperationTerminalHandler`.
- [x] Reduce `SwarmSignalListener` to the AMQP adapter: parse routing, decode once, dispatch by envelope kind, and
  journal invalid ingress. Move result and timeout characterization to focused component tests.

Current verification evidence:

- Focused Orchestrator component tests pass: listener transport dispatch, status publication, CREATE/config
  observation, generic terminal handling, and REMOVE convergence are covered by their owning implementation units.
- `./mvnw -pl :orchestrator-service -am test` passed the complete 17-module dependency reactor; the Orchestrator
  module passed 182 tests.
- `./build-hive.sh --quick` rebuilt and redeployed the local stack; all required services reported healthy.
- The first complete official-ingress `./start-e2e-tests.sh --target local-swarm` run passed 38 of 39 scenarios. The
  final scenario received a controller-produced `swarm-stop` `FAILED` result after the controller did not observe
  fresh disabled snapshots from three workers within its 30-second convergence window. All four worker
  config-update acknowledgements arrived immediately.
- The exact final scenario then passed in an isolated diagnostic replay. A clean full A/B check rebuilt the stack
  from the unchanged base commit `18d8cfbc` and passed 39 of 39 scenarios and 463 of 463 steps. The stack was then
  rebuilt from this refactor without changing the working tree, and the same full gate also passed 39 of 39
  scenarios and 463 of 463 steps; the formerly failing final STOP converged in about two seconds.
- After the refactor rerun, the official API reported no active swarms, both remaining Orchestrator queues had zero
  ready and unacknowledged messages, and the stack was healthy. The observed matrix is therefore baseline full:
  pass; refactor full: one fail followed by one pass; refactor isolated: pass. Treat the original failure as an open,
  non-reproduced convergence incident rather than proof of either a regression or an unrelated transient. Review
  the extraction for ordering, scheduling, and concurrency changes before declaring this sink complete or starting
  the next sink.

Next sink: Swarm Controller `SwarmSignalListener` (1,600 lines before extraction). Confirmed responsibilities are
AMQP ingress, signal dispatch, filesystem-backed REMOVE execution, START/STOP convergence, config application,
worker status and alert ingestion, controller status projection/publication, and health journaling. Extract in this
order so that state ownership remains in the existing `SwarmLifecycle`/runtime core:

1. Filesystem-backed REMOVE command handling — a dedicated handler validates the canonical request identity,
   invokes the lifecycle cleanup port, and persists exactly one canonical result. The listener only dispatches.
2. Worker status and alert observation — decode at the boundary, then delegate accepted observations to one handler
   that updates the lifecycle core and triggers convergence evaluation.
3. Controller status projection/publication and health journaling — derive read-only output from the lifecycle core;
   do not create another readiness or workload-state owner.
4. START/STOP convergence and config command workflows — retain the lifecycle core as the only owner of expected
   workers, readiness, workload observation, and transitions; leave the listener with transport dispatch only.

The first extraction gate is the existing REMOVE success/failure characterization moved to the new handler, listener
dispatch coverage, the complete Swarm Controller dependency reactor, and `git diff --check`. It does not alter public
contracts, routing, readiness, lifecycle convergence, or terminal postconditions.

Progress on Swarm Controller `SwarmSignalListener`:

- [x] Extract filesystem-backed REMOVE execution to `SwarmRemoveCommandHandler`; remove the filesystem store,
  request loading, identity validation, cleanup execution, and result construction from the listener.
- [x] Move REMOVE success/failure characterization to focused handler tests and add same-operation replay,
  conflicting-idempotency, and wrong-controller identity coverage.
- [x] Retain a listener boundary test proving that an accepted REMOVE signal is dispatched once without publishing
  an AMQP terminal result.
- [x] Pass `./mvnw -pl :swarm-controller-service -am test`: all 12 reactor modules passed and the Swarm Controller
  module passed 112 tests. Repository search finds one active filesystem REMOVE workflow owner, and
  `git diff --check` passes.

For every confirmed sink:

- [ ] Write or identify focused characterization tests for the responsibility being extracted.
- [ ] Extract one owner with an explicit `Responsibility`, `Must not`, and `Contract` header.
- [ ] Delete the extracted behavior and obsolete helpers from the sink.
- [ ] Verify that repository search finds one active owner for the moved responsibility.
- [ ] Pass focused tests, affected reactor tests, `./build-hive.sh`, and the unchanged official-ingress E2E gate.
- [ ] Review before starting the next extraction.

## Phase 4 — review the simplified architecture

- [ ] Repeat the repository-wide control-plane review against `AGENTS.md`, `docs/ENGINEERING_RULES.md`,
  `docs/REVIEW_RULES.md`, and `docs/ai/REVIEW_CHECKLIST.md`.
- [ ] Reassess former CP-01 through CP-06 against the new owners; do not assume they survived or were fixed.
- [ ] Record only findings reproducible in the simplified tree.
- [ ] Order remaining fixes by severity and dependency.
- [ ] Run two consecutive complete official-ingress local-swarm E2E gates before final approval.

## Completion criteria

- The E2E-only parser/audit subsystem is deleted.
- Every confirmed control-plane/lifecycle kitchen sink has one coherent responsibility or has been removed.
- No extraction introduced a second owner, compatibility path, fallback, or hidden behavior change.
- The post-refactor review, not the abandoned findings plan, is the source of the remaining repair queue.
- Decisions, verification evidence, and explicitly deferred risks are recorded in HiveMind.
