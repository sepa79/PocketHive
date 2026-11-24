# Control-Plane Architecture Simplification Plan

> Status: completed
> Delivered: redundant test machinery removed; in-scope production sinks separated; runtime findings re-reviewed

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
- Refactor `SwarmRuntimeCore` after the two control-plane listeners. `SwarmLifecycleSteps` is excluded from this plan
  by explicit human decision and remains unchanged; it is not a completion dependency for the production refactor.

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
  assertions, and environment diagnostics. This test fixture was inventoried but is explicitly out of scope.

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

- [x] Record the complete responsibility inventory and the canonical owner expected after each extraction.
- [x] Order sinks by dependency and risk, starting with the smallest boundary that establishes the extraction pattern.
- [x] Define a characterization and verification gate for each sink before editing it.

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
- [x] Extract worker error-counter observation to `SwarmWorkerErrorJournal`; remove its mutable counter baseline and
  diagnostic journal construction from the listener. Focused tests preserve increase, unchanged-counter, reset, and
  missing-counter behavior.
- [x] Extract accepted worker-alert journaling and config-error evidence application to `SwarmWorkerAlertHandler`.
  Keep envelope decoding, routing-scope checks, and pending-command terminalization at their existing boundary owners.
- [x] Extract accepted worker-status application to `SwarmWorkerStatusHandler`. It preserves the existing update
  order and applies heartbeat, full-snapshot freshness, read-only projections, error observation, enabled state, and
  readiness through the existing lifecycle owner. The listener retains decoding and reacts only to the returned
  startup-ready transition before evaluating its still-legacy publication/convergence workflows.
- [x] Delete the obsolete swarm-wide `SwarmIoStateAggregator`. The active worker-level `data.ioState.work` projection
  remains owned by `SwarmWorkersAggregator` and is still published in `data.context.workers[].ioState`; no controller
  `ioState.work` aggregate is reintroduced.
- [x] Pass `./mvnw -pl :swarm-controller-service -am test` after the complete observation extraction: all 12 reactor
  modules passed and the Swarm Controller module passed 122 tests. Repository search finds one caller that applies
  accepted worker status to lifecycle state and one owner of the three worker projections; `git diff --check` passes.
- [x] Extract health-transition state and journal construction to `SwarmHealthJournal`. Preserve the initial
  observation baseline, workload-enable suppression window, degraded/recovered edge semantics, severity, scope, and
  metric evidence in focused component tests; remove the mutable health-journal state from the listener.
- [x] Extract full/delta controller status construction, worker/diagnostic projection, health derivation, and
  control-route publication to `SwarmControllerStatusPublisher`. Keep mutable network configuration and publication
  trigger state outside the publisher so it remains a projection/publication owner.
- [x] Extract network-context ownership to `SwarmControllerNetworkContext`, guard-to-contract mapping to
  `BufferGuardTrafficPolicyMapper`, and startup/post-lifecycle full-status triggers to `SwarmStatusFullCoordinator`.
  The coordinator uses an injected clock and preserves fresh-snapshot, five-second timeout, replacement, and
  startup-at-most-once behavior.
- [x] Move status-shape characterization to focused publisher tests, including worker `ioState`, health states,
  traffic policy, network context, queue-refresh failure, and the exact canonical controller-route list. Add focused
  tests for network-context validation, guard projection, startup-ready emission, freshness waiting, timeout, and
  pending-trigger replacement.
- [x] Extract START/STOP execution and pending convergence to `SwarmLifecycleCommandHandler`. The handler preserves
  the 30-second bound, fresh matching worker requirement, exact non-converged target evidence, already-achieved
  behavior, config-error failure path, and post-lifecycle full-status trigger while `SwarmLifecycle` remains the
  owner of readiness, expected workers, workload state, and transitions.
- [x] Extract the shared command-readiness observation to `SwarmCommandReadiness` and canonical lifecycle/config
  result construction to `SwarmControllerResultPublisher`; remove both responsibilities and the pending lifecycle
  state from the listener. The listener now only dispatches START/STOP and triggers convergence reevaluation from
  accepted status events and its scheduled transport hook; accepted config-error alerts are delegated to the same
  pending-command owner.
- [x] Extract config command admission and application to `SwarmConfigUpdateHandler`; remove enabled-state, scenario,
  network-context, buffer-guard, rejection, and result workflow code from the listener. Delete the previously
  accumulated config `details`, which had no consumer and did not affect the result contract.
- [x] Extract partial buffer-guard normalization to `BufferGuardConfigOverrideMapper`. Focused tests preserve explicit
  disable, every supported nested override, retention of omitted effective settings, and rejection of runtime guard
  queue-identity changes.
- [x] Apply the verified filesystem startup artifact through `SwarmControllerStartupInitializer`.
- [x] Move control-plane collaborator construction to `SwarmControllerControlPlaneConfiguration` and runtime metadata
  resolution to `SwarmControllerRuntimeMetadata`. Preserve the journaled publisher, one-minute/256-entry duplicate
  cache, controller identity, topology settings, startup timestamp, and initial status behavior. The listener now
  receives every workflow owner ready to dispatch and no longer constructs publishers, emitters, projections,
  readiness queries, or command handlers.
- [x] Run `./build-hive.sh --quick`; the canonical rebuild/redeploy completed and every required base service reached
  healthy state. The first official-ingress run then exposed a deterministic dynamic-controller startup failure:
  Spring could not choose between the production and test-clock constructors of `SwarmHealthJournal`.
- [x] Move initial status publication and periodic convergence triggers to
  `SwarmControllerControlPlaneScheduler`.
- [x] Resolve the convergence regression exposed by the first post-review E2E run. Controller logs showed accepted
  post-START worker snapshots whose wall-clock timestamps moved backwards by about 0.53 seconds, so timestamp-based
  freshness incorrectly classified current observations as older than the command. `SwarmReadinessTracker` now owns
  a monotonic observation revision used by START/STOP and delayed full-status convergence. Each revision is stored
  atomically with the validated `enabled` value, so a delta or rejected payload cannot masquerade as fresh full-status
  evidence. Wall time remains limited to heartbeat age and metrics; command and coordinator deadlines use monotonic
  `System.nanoTime`.
- [x] Resolve the final-review lifecycle admission finding. `SwarmLifecycleCommandHandler` now serializes command
  admission, mutation, convergence, and alert failure through one owner; a pending command rejects another START or
  STOP before lifecycle mutation or an already-achieved shortcut. Two focused overlap regressions pass, and a clean
  `./mvnw -pl :swarm-controller-service -am clean test` passes all 12 reactor modules with 142 Swarm Controller tests.
- [x] Resolve the remaining final-review readiness findings. Both convergence queries now consume one synchronized,
  deeply immutable worker-instance snapshot, and the status-request port is the standalone
  `WorkerStatusRequestCallback` production type instead of a public nested interface. Focused readiness tests and the
  complete 12-module dependency reactor pass with 142 Swarm Controller tests.
- [x] Resolve the final-review config and terminal-result findings. Config overrides now distinguish an omitted field
  from an invalid value and fail explicitly when a requested buffer-guard change has no scenario-owned guard to
  update. Config and lifecycle mutation catches end before terminal success publication, so a downstream publication
  or post-result status failure cannot be translated into a second `FAILED` result. Four focused regressions pass;
  the complete 12-module dependency reactor passes with 146 Swarm Controller tests and no failures, errors, or skips.

The listener is now the control-plane boundary adapter: it validates ingress, decodes accepted envelopes, establishes
diagnostic context, applies routing scope, and dispatches transport triggers to the owning components. Its focused
component reactor, canonical rebuild/redeploy, dynamic controller wiring, complete unchanged official-ingress E2E
gate, and post-run cleanup check pass after the follow-up repairs. The Swarm Controller listener sink is complete and
ready for final review before the next extraction.

Next sink: `SwarmRuntimeCore` (1,299 lines before extraction). Its retained responsibility is the one runtime
lifecycle state machine. Mixed responsibilities to remove are work-binding projection, plan/topology analysis,
worker-spec and environment construction, infrastructure provisioning/removal, queue-stat collection, worker status
requests, scenario-engine integration, and local runtime/scenario journaling. Extract one complete cluster at a time;
do not create a second runtime-state owner.

Progress on `SwarmRuntimeCore`:

- [x] Extract the read-only `bindings.work` projection to `SwarmWorkBindingsProjector`. The core now supplies only
  the current plan and an immutable instances-by-role snapshot; the projector owns exact edge, endpoint, selector,
  exchange, queue, and routing-key mapping through canonical traffic settings.
- [x] Add focused projection coverage for the documented multi-port/selector shape, the pre-prepare empty shape, and
  explicit rejection of ambiguous runtime role mapping. Targeted projector/status tests pass 9 of 9. The complete
  12-module Swarm Controller dependency reactor passes with 149 tests and no failures, errors, or skips;
  `SwarmRuntimeCore` is reduced from 1,299 to 1,179 lines and repository search finds one bindings projector.
- [x] Extract worker-spec and environment construction to `SwarmWorkerSpecFactory`, with immutable
  `PlannedSwarmWorker` carrying the matching bootstrap config. The core no longer owns Rabbit, Docker-network,
  ClickHouse, filesystem-mount, adapter-environment, volume, or SUT-mapping details; it registers and provisions the
  resolved plans. `SwarmLifecycleManager` now passes its existing canonical `WorkerSettings` instead of the core
  deriving the same settings again.
- [x] Extract plan-derived queue suffix and runnable-worker selection to `SwarmRuntimePlanAnalyzer`. Review showed
  that the previous dependency-order calculation had no production consumer: removal reversed the list and then
  ignored it, while compute adapters already remove workers in reverse provisioning order. Delete that algorithm,
  its context/state fields, and its tests rather than establishing a new owner for dead behavior. Focused coverage
  preserves queue collection, blank filtering, and workers without a runtime image.
- [x] Keep runtime journal projection in `SwarmRuntimeJournal` and infrastructure effects
  in `SwarmRuntimeInfrastructure`. `SwarmQueueStatsCollector` supplies immutable snapshots.
- [x] Extract worker status-request publication to `SwarmWorkerStatusRequestPublisher` behind the existing callback
  boundary. The lifecycle state machine no longer constructs routing, scopes, envelopes, diagnostic identifiers, or
  publishes control-plane messages; repository search finds one production owner of status-request publication.
Scenario construction and execution belong to `SwarmScenarioCoordinator`. It delegates
commands through `ScenarioLifecyclePort`.

The production `SwarmRuntimeCore` kitchen-sink refactor is complete. The remaining class is the lifecycle coordinator:
it owns lifecycle state and tick permission and delegates infrastructure effects, projections, journaling, readiness,
queue observation, worker specification, status-request publication, and scenario execution to focused components.

After integrating the pending MCP hardening branch, the final production sink inventory also includes Scenario
Manager `ScenarioService` (2,169 lines before extraction). It currently owns the discovered bundle catalogue and
scenario index, descriptor CRUD, generic bundle-workspace editing, variables resolution, bundle-local SUT editing,
ZIP import/replace/validation, and runtime-directory materialisation. It also contains public nested response and
configuration types. Preserve the filesystem and REST contracts while extracting in dependency order:

1. Move public nested contract/configuration types to one production type per file without changing their JSON shape.
2. Extract generic bundle workspace and folder mutation; the catalogue remains the only owner of bundle identity and
   is explicitly reloaded after successful mutation.
3. Extract variables and bundle-local SUT workflows behind their existing canonical validator.
4. Extract uploaded-ZIP validation/publication and runtime-directory materialisation without adding another bundle
   validator, path resolver, or scenario index.
5. Retain one filesystem-backed scenario catalogue/repository owner for discovery, duplicate/quarantine state,
   availability, access projection, and descriptor lifecycle.

`ScenarioService` owns the catalogue. Workspace, authoring, bundle publication and
runtime materialisation have separate owners and share `ScenarioBundleValidator`.

The remaining oversized HTTP organization belongs to the post-simplification repair queue. The unchanged
official-ingress E2E suite is the final production-sink gate. The E2E `SwarmLifecycleSteps` fixture was not modified
and is explicitly excluded from this plan by human decision.

Every in-scope production sink was handled through the same completed loop: focused characterization, one explicit
owner with a responsibility header, deletion from the original sink, repository-wide owner search, affected-reactor
verification, canonical rebuild/deploy, unchanged official-ingress E2E, and review before the next extraction.

## Phase 4 — review the simplified architecture

- [x] Repeat the repository-wide control-plane review against `AGENTS.md`, `docs/ENGINEERING_RULES.md`,
  `docs/REVIEW_RULES.md`, and `docs/ai/REVIEW_CHECKLIST.md`.
- [x] Reassess former CP-01 through CP-06 against the new owners; do not assume they survived or were fixed.
- [x] Record only findings reproducible in the simplified tree.
- [x] Order remaining fixes by severity and dependency.
- [x] Run two consecutive complete official-ingress local-swarm E2E gates before final approval.

The fresh findings and their repair order live in
Git history. They replace the abandoned numbered findings queue;
they do not reopen completed behavior-preserving sink extractions.

## Completion criteria

- The E2E-only parser/audit subsystem is deleted.
- Every in-scope production control-plane/lifecycle kitchen sink has one coherent responsibility or has been removed.
- No extraction introduced a second owner, compatibility path, fallback, or hidden behavior change.
- The post-refactor review, not the abandoned findings plan, is the source of the remaining repair queue.
- Decisions, verification evidence, and explicitly deferred risks are recorded in HiveMind.
