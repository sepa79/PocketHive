# Control-Plane Post-Simplification Review

> Status: active repair queue
> Reviewed tree: `refactor/control-plane-critical-restart` after `50d456ee`
> Source plan: `docs/todo/control-plane-simplification-plan.md`

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

### CP-N01 — CRITICAL — Orchestrator control topology has two active owners

`ManagerControlPlaneAutoConfiguration` declares topology from
`OrchestratorControlPlaneTopologyDescriptor`, while Orchestrator `RabbitConfig` independently declares the
same queues and bindings with raw routing strings. `OrchestratorControlPlaneConfig` also reconstructs the
queue names from a second pair of prefixes. The defaults currently agree, but configuration can make the
two authorities diverge.

Repair direction: select one topology descriptor and declarable factory as SSOT, derive listener queue names
from that descriptor, and remove the duplicate Rabbit declarations and queue-prefix resolver. This changes
the public runtime configuration surface and therefore requires the protected-config approval before editing.

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

## Order for the next repair plan

1. CP-N01 because two active topology authorities are an SSOT blocker and the work overlaps future Work Plane
   broker abstraction.
2. CP-N05 because broker-neutral Work Plane provisioning and cleanup depend on this boundary.
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

The two consecutive complete official-ingress E2E runs remain the final simplification-plan gate after the
last deferred `SwarmLifecycleSteps` extraction.
