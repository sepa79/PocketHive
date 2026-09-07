# Control-Plane Post-Simplification Review

> Status: active repair queue
> Reviewed tree: `refactor/control-plane-critical-restart` after `50d456ee`
> Source plan: `docs/archive/control-plane-simplification-plan.md`

## Scope and method

This is the fresh review required by Phase 4 of the simplification plan. It does not carry the abandoned
CP-01 through CP-06 queue forward. The review re-ran repository-wide searches for control-plane routing,
codec use, terminal-result construction, outcome publication, large implementation units, collaborator
counts, public nested production types, and direct infrastructure access from HTTP and message boundaries.

The listener, runtime-core, and Scenario Manager extractions completed by the simplification plan remain
accepted. The findings below are independently reproducible in the simplified tree and form the next repair
queue. They are not implicit extensions of the behavior-preserving simplification commits.

## Accepted owners

- `ControlPlaneCodec` remains the sole production control-envelope JSON codec.
- `OperationOutcomePublisher` remains the sole public outcome publisher. Workflow-specific handlers may
  construct terminal evidence, but they all delegate publication and operation terminalization through the
  canonical Orchestrator owner.
- Orchestrator `SwarmSignalListener` is a transport boundary after extraction.
- Swarm Controller `SwarmSignalListener` is a transport boundary after extraction.
- `SwarmRuntimeCore` is the runtime lifecycle coordinator; infrastructure effects, projections, queue
  observation, journaling, status requests, and scenario execution have focused owners.
- `ScenarioService` is the sole filesystem-backed scenario catalogue owner. Workspace, variables, SUT,
  publication, validation, and runtime-materialisation workflows are delegated to focused owners.
- `ScenarioBundleValidator` is large but remains one coherent canonical validation owner. Its size triggers
  review but is not by itself a reason to create competing validators.

## Findings

### CP-N01 — RESOLVED — Orchestrator control topology has one owner

Resolved in the working tree on 2026-09-07; changes are not committed. The user
explicitly requested this fix, including the previously described removal of the
service-specific configuration surface.

`OrchestratorControlPlaneTopologyDescriptor` owns both queue names and all five
bindings. Its named `controllerStatusQueue` projection is also used by
`additionalQueues`, so declaration and listener lookup cannot maintain separate
status-queue definitions. `ManagerControlPlaneAutoConfiguration` and
`ControlPlaneTopologyDeclarableFactory` remain the only declaration path.

Orchestrator `RabbitConfig` and the service-local queue-name resolver are deleted.
`OrchestratorControlQueueConfiguration` exposes only descriptor-derived names;
`ControllerStatusListener` consumes the status name directly. Both independent
Orchestrator queue-prefix properties are removed. Strict application-property
binding rejects the removed property keys, and
`RemovedOrchestratorQueueEnvironmentGuard` rejects their former environment
variables, including empty values. Configuration types were extracted to separate
files while removing topology settings; other settings retain their behavior.

Regression gates:

- `OrchestratorControlTopologyTest` combines the production queue-name configuration
  and shared auto-configuration. It checks default and non-default prefixes,
  resolves the actual listener annotations, counts both queues and every binding,
  and checks both declaration-disable switches and invalid prefixes.
- `ControlTopologyOwnershipTest` scans compiled Orchestrator production classes
  and blocks local AMQP declarables/builders, declarable-factory dependencies, and
  alternative descriptor implementations. The only separate declaration owner is
  `DebugTapService`, for temporary Work Plane tap queues. A deliberately reintroduced
  queue-bean fixture must trigger the rule.
- Configuration binding and environment-guard tests reject the removed settings.

Verification: the Orchestrator Maven reactor passed; a subsequent canonical
`build-hive.sh` clean package passed all 17 reactor modules, including 193
Orchestrator tests, 70 Control Plane Core tests and 19 Control Plane Spring tests,
with zero failures/errors/skips in those modules. The docs/UI build initially
caught a link to a repo-only document; after correcting the reference, the
canonical targeted rebuild/redeploy completed. No Java behavior changed between
that clean test gate and deployment.

The official-ingress acceptance check at `http://localhost:8088` completed
CREATE, START, STOP and REMOVE for `cpn01-1788783840516`, polling each canonical
operation to `SUCCEEDED`. Controller observations were fresh and `READY` with
workload `STOPPED`, `RUNNING`, then `STOPPED`. The final swarm list confirmed removal.
This is a focused lifecycle acceptance check, not a rerun of the entire E2E pack.
Repository-wide owner searches and `git diff --check` passed. Other findings below
remain open.

### CP-N02 — HIGH — `ControllerStatusListener` still mutates the swarm projection

The listener decodes AMQP and also hydrates network metadata, writes full/delta status state, derives lifecycle
observation enums, requests missing baselines, and expires stale observations. This exceeds the transport
boundary even though terminal-operation convergence was already extracted.

Repair direction: extract one controller-status observation handler and one scheduled expiry owner. Keep the
listener limited to routing/codec/context/dispatch and transport failure policy.

### CP-N03 — HIGH — `WorkerControlPlaneRuntime` is a multi-workflow state sink

The type owns signal dispatch, config merging and application, state-listener registration, status projection
and publication, IO-state aggregation, out-of-data alerts, work-error reporting, and runtime metadata. It also
exposes the public nested `WorkerStateSnapshot` contract.

Repair direction: retain `WorkerStateStore` as the worker-state authority and extract config-command handling,
status projection/publication, work-error reporting, and state-observation subscriptions into focused owners.
Move `WorkerStateSnapshot` to its own file and update consumers to depend on the narrow capability they use.

### CP-N04 — HIGH — Orchestrator `SwarmController` owns application workflows

The REST controller has fifteen collaborators and directly executes CREATE preparation, scenario/SUT/variables
resolution, template rendering, startup-artifact persistence, network binding/rollback, runtime launch,
operation lookup, and state-view projection in addition to HTTP mapping and authorization.

Repair direction: extract a swarm-create application service, scenario plan materializer, and state-view
projector. Split lifecycle mutation and query controllers only where the resulting HTTP boundaries have
coherent endpoint families.

### CP-N05 — HIGH — `ContainerLifecycleManager` mixes infrastructure concerns

The type combines controller runtime provisioning/removal, image resolution and preload, environment assembly,
Rabbit cleanup, runtime manifest persistence, metrics configuration, and secret redaction.

Repair direction: separate controller runtime provisioning, container environment construction, image
resolution/preload, and ownership-manifest recording. Work-plane broker ownership must be isolated before an
Artemis adapter is introduced.

### CP-N06 — HIGH — Active public nested contract bags remain

Examples include `RuntimeCleanupContracts`, `RuntimeDebugContracts`, `RuntimeAssessmentContracts`,
`RuntimeCleanupPorts`, and `ComponentConfigContracts`, plus nested request/response records in several REST
controllers. These violate the one-production-type-per-file rule and make contracts depend on namespace-holder
classes.

Repair direction: move each externally consumed request, response, port, state, and enum to a named file in
the package that owns the contract. Do this with unchanged JSON shapes and producer/consumer tests.

### CP-N07 — HIGH — Journal HTTP controllers own SQL persistence

`JournalController` and `SwarmJournalController` map HTTP while directly executing JDBC queries, archive copy,
capture mutation, cursor handling, filesystem fallback, filtering, and response construction.

Repair direction: retain thin HTTP controllers and extract journal query/archive application services plus
repository adapters.

### CP-N08 — MEDIUM — Scenario HTTP surface remains oversized

`ScenarioController` is now transport-only in the sense that mutation and validation rules live in application
services, and its public nested DTOs were removed during this review. It still maps unrelated catalogue,
workspace, variables, SUT, publication, and runtime endpoint families through ten collaborators.

Repair direction: split the HTTP surface by those documented endpoint families without reintroducing business
logic or changing routes.

### CP-N09 — HIGH — executor wall-clock timestamps can invalidate lifecycle results

`SwarmOperationTerminalHandler` uses the executor-owned `CommandResult.timestamp` as the completion time of the
Orchestrator-owned operation. A local E2E replay observed the wall clock move backwards by about 1.7 seconds between
operation creation and receipt of an otherwise valid STOP result. `SwarmOperation` correctly rejected the resulting
chronology (`completedAt must not precede createdAt`), but the listener then dropped the only terminal evidence and
the operation timed out even though the controller and all workers had reached `STOPPED`.

Repair direction: stamp operation completion with the Orchestrator's coordination/receipt clock and retain the
executor timestamp as external evidence. Do not weaken the `SwarmOperation` chronology invariant or add a clock-skew
fallback.

## Order for the next repair plan

CP-N01 is resolved above. The remaining queue is:

1. CP-N05 because broker-neutral Work Plane provisioning and cleanup depend on this boundary.
2. CP-N09 because lifecycle completion must not depend on an executor wall clock.
3. CP-N02 and CP-N03 to finish message-listener/runtime separation.
4. CP-N04 and CP-N08 to restore thin HTTP boundaries.
5. CP-N06 contract-file separation alongside the owning workflow changes, not as a compatibility layer.
6. CP-N07 as a journal-specific refactor independent of Work Plane migration.

## Verification evidence

- `./mvnw -pl :scenario-manager-service -am clean test`: 11 reactor modules passed; Scenario Manager
  175 tests, zero failures/errors/skips.
- `./mvnw -pl :scenario-manager-service -am test` after extracting HTTP/validation contract types:
  11 reactor modules passed; Scenario Manager 175 tests, zero failures/errors/skips.
- Repository searches found one production `ControlPlaneCodec`, one `OperationOutcomePublisher`, the terminal
  result construction sites listed above, the duplicate topology declarations, and the remaining nested
  production contracts.
- One complete official-ingress run passed 39 of 39 scenarios, 463 of 463 steps, and 48 of 48 Maven tests. The next
  complete run exposed a stale status-full snapshot in the history-policy scenario. An isolated replay confirmed the
  expected policy values for all workers, then exposed CP-N09 during STOP; the orphaned test swarm was removed through
  the supported Orchestrator API after the timed-out operation released it.

`SwarmLifecycleSteps` is explicitly excluded from this work by human decision and remains unchanged. After the
CP-N09 diagnostic resource was removed through the supported API, two consecutive complete official-ingress runs
each passed 39 of 39 scenarios, 463 of 463 steps, and 48 of 48 Maven tests without a rebuild between them. The
simplification plan's final gate is complete; this document remains the active repair queue.
