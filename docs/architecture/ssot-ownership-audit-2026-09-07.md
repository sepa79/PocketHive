# Repository SSOT ownership audit — 2026-09-07

Status: **SSOT approval blocked; eight additional finding groups beyond the RabbitMQ audit.**

Scope clarification: this status concerns repository-wide SSOT, not acceptance of the
sink-splitting refactor. A [baseline-to-closeout comparison](sink-refactor-finding-provenance-2026-09-07.md)
confirmed that all eight findings existed before that phase. They are inherited debt;
their presence is not evidence that the sink refactor failed or introduced these problems.

This is an audit of the current working tree on `refactor/control-plane-critical-restart`
(HEAD `5e68eec4`), including its existing uncommitted changes. It is not an implementation
completion report. No production code, deployment, commit, or push was performed for this audit.

## Result and evidence standard

The problem extends beyond RabbitMQ: resource-removal outcomes, journal paths, Redis
configuration, request-template validation, service contracts, client normalization,
worker freshness, and authorization predicates have competing implementations.

Five groups have executable examples of inconsistent behavior. Three are confirmed
ownership duplication without a reproduced production failure. All eight are **CRITICAL
SSOT review findings under AGENTS.md §1**, which explicitly makes two active authorities
an approval blocker. This severity is an architectural gate, not a claim that each issue
has caused an outage or constitutes an exploitable security vulnerability.

| ID | Responsibility | Evidence | Practical consequence |
|---|---|---|---|
| SSOT-01 | Resource absence / cleanup success | Reproduced with mocked infrastructure | `REMOVED` while inventory still contains the resource |
| SSOT-02 | Swarm run filesystem paths | Reproduced with synthetic local journal files | File journal reader can cross the requested swarm's directory |
| SSOT-03 | Redis input/output configuration semantics | Input disagreement reproduced; output duplication traced | Configuration accepted by properties is rejected by the runtime adapter |
| SSOT-04 | Request-template shape validation | Both implementations exercised | Runtime accepts a template the authoring validator rejects |
| SSOT-05 | Scenario Manager REST contracts | Active producer/client definitions and usages traced | Three wire shapes must be maintained independently |
| SSOT-06 | UI interpretation of network mode and workload state | Reproduced from actual TypeScript modules | Invalid mode becomes `DIRECT`; unsupported state aliases are accepted |
| SSOT-07 | Worker heartbeat freshness | Independent clocks, registries, TTL constants, and callers traced | Status projection independently computes the owner's freshness fact |
| SSOT-08 | PocketHive grant/scope policy | Active Java and handwritten TypeScript predicates traced | Server authorization and UI action availability maintain the same policy separately |

The earlier [RabbitMQ ownership audit](rabbitmq-ownership-audit-2026-09-07.md) remains
applicable. These findings neither undo nor broaden the narrowly verified CP-N01 fix.

## Scope and method

Repository-wide filename/text searches covered production Java, TypeScript/TSX, JavaScript
tooling, contracts, and configuration references. The source candidate inventory contained
1,511 paths, including tests and resource files. This number is a search inventory, **not**
a claim that every file received a line-by-line review. Build outputs, dependencies, and
archived documentation were excluded from source candidate searches; Git history and
archived plans were separately inspected for provenance and scope decisions.

Manual tracing followed candidate owners into active producers, callers, adapters, and
projections across `common/`, Orchestrator, Swarm Controller, Scenario Manager, workers,
network proxy/auth code, MCP adapters, UI, shared client packages, extension, and tooling.
The deepest checks were at boundaries where two implementations could decide the same fact.
Infrastructure declarations and Rabbit configuration are detailed in the preceding audit.

This is a repository-wide SSOT scan with targeted behavioral verification, not a complete
security audit, all-adapter certification, load test, or proof that no further duplicate
owners exist. Similar names and equivalent adapter interfaces were not sufficient findings.

## SSOT-01 — Cleanup constructs success without the absence owner

**CRITICAL; behavioral contradiction reproduced.**

- [RuntimeReconciliationService](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/runtime/RuntimeReconciliationService.java),
  `executeCandidate`, lines 564–598: direct orphan cleanup invokes a void removal port and
  immediately constructs `RuntimeCleanupStatus.REMOVED`.
- [RuntimeRemovalPostconditionVerifier](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/runtime/RuntimeRemovalPostconditionVerifier.java),
  `verifyAbsent`, line 29: independently observes compute/Rabbit resources and refuses
  success when a resource is still present or observation fails.
- [SwarmRemovalConvergenceHandler](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/app/SwarmRemovalConvergenceHandler.java)
  uses that verifier for the canonical swarm removal flow.

These are legitimately different workflows: registered-swarm cleanup dispatches lifecycle
REMOVE and returns `DISPATCHED`; orphan cleanup is direct. The violation is the different
meaning of successful removal of the same resource, not the existence of two workflows.
Architecture §5.1.1 explicitly forbids treating a delete invocation as observed absence.

Offline reproduction used `plan` and `execute`, a fully labeled unregistered worker, a
mock removal port, and an inventory that continued to return that worker. Comparing the
result with the existing verifier produced:

```text
CLEANUP status=REMOVED verified=false
remaining=[RemoveResource[type=WORKER_RUNTIME, id=worker-1]]
```

The existing `RuntimeReconciliationServiceTest.exactUnregisteredLabeledRuntimeCanStillBeRemovedSynchronously`
also expects `REMOVED` with a fixed inventory mock and verifies the removal invocation.
It does not establish absence. Thus a passing test can preserve this false-success behavior.

Required ownership: one reusable absence evaluator must determine success for both
workflows; action dispatch, observation failure, and verified absence must remain distinct.

## SSOT-02 — File journal reader bypasses the shared run-path contract

**CRITICAL; cross-swarm file read reproduced on synthetic data.**

- [RuntimeFilesystemLayout](../../common/control-plane-filesystem/src/main/java/io/pockethive/controlplane/filesystem/RuntimeFilesystemLayout.java),
  `swarmRunDirectory`, line 38, validates `runId` as a single segment.
- [FileSwarmJournal](../../swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/FileSwarmJournal.java),
  lines 46–48, uses that resolver when writing.
- [SwarmJournalController](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/app/SwarmJournalController.java),
  lines 370–384 and 415–422, resolves only the swarm root, accepts an explicitly supplied
  `runId` after trimming, and builds `dir.resolve(runId).resolve("journal.ndjson")` itself.

With the file backend selected, a request scoped to swarm `allowed` with
`runId=../other/run-2` reads `other/run-2/journal.ndjson`. The controller authorizes `allowed`;
the requested run path is not passed through the shared segment validator. Absolute paths
are another way to bypass the swarm directory. The fixed filename remains `journal.ndjson`.

An offline invocation of the public controller method, with a mocked authorization port
and temporary synthetic files, returned:

```text
JOURNAL status=200 OK body=[{marker=other-swarm-synthetic-data}]
canonicalResolverRejected=true
```

This proves controller/path behavior, not an end-to-end authentication exploit. The default
journal sink is Postgres; the demonstrated issue is conditional on the selectable file path.
No real journal, credentials, service port, or running environment was accessed by the probe.

Required ownership: the reader and writer must obtain the complete journal path from the
same filesystem contract. Existing swarm-ID sanitizers that delegate to `requireSegment`
are not themselves duplicate validators.

## SSOT-03 — Redis semantics are implemented in authoring, properties, and runtime

**CRITICAL; one input inconsistency reproduced, wider duplicated validation traced.**

- [RedisDataSetInputProperties](../../common/worker-sdk/src/main/java/io/pockethive/worker/sdk/config/RedisDataSetInputProperties.java),
  `validateConfigured`, line 147: host/port/SSL/strategy/rate and source-mode/weight rules.
- [RedisDataSetWorkInput](../../common/worker-sdk/src/main/java/io/pockethive/worker/sdk/input/redis/RedisDataSetWorkInput.java),
  `validateConfiguration`, line 548: repeats those rules and additionally rejects duplicate
  source list names. Runtime config updates also have their own map parsing and checks.
- [ScenarioBundleValidator](../../scenario-manager-service/src/main/java/io/pockethive/scenarios/validation/ScenarioBundleValidator.java),
  lines 1258–1359: dispatches to another Redis source-mode, duplicate-name, and weight validator.
- [RedisOutputProperties](../../common/worker-sdk/src/main/java/io/pockethive/worker/sdk/config/RedisOutputProperties.java),
  [RedisWorkOutput](../../common/worker-sdk/src/main/java/io/pockethive/worker/sdk/output/RedisWorkOutput.java),
  and `ScenarioBundleValidator` lines 1360–1445 similarly own overlapping output target/route
  rules. Delegation from the runtime output to `RedisPushSupport` does not remove the
  separate authoring implementation of route semantics.

Probe input: valid required connection/settings fields, round-robin strategy, and two
sources both named `same`, each with weight 1. The properties validator accepts it; the
runtime validation method rejects it before Redis client creation:

```text
REDIS properties=accepted
runtime=IllegalStateException: Redis dataset sources must not contain duplicates
```

The runtime method was invoked reflectively as an isolated unit probe; no scheduler,
Redis connection, or message flow was started. This is a disagreement between validation
owners, not a claim that Scenario Manager would approve this particular duplicate-list input.

Required ownership: one transport configuration contract/parser/semantic validator, used
by binding, authoring validation, and runtime patch handling. Template-aware authoring
can defer unresolved values, but must delegate the rules for resolved literal values.

## SSOT-04 — Request-template authoring and loading disagree on required fields

**CRITICAL; the same document was evaluated by both implementations.**

- [ScenarioBundleValidator](../../scenario-manager-service/src/main/java/io/pockethive/scenarios/validation/ScenarioBundleValidator.java),
  `validateRequestTemplateShape`, lines 1747–1795, independently requires protocol,
  serviceId, callId, and HTTP method/pathTemplate.
- [TemplateLoader](../../common/request-templates/src/main/java/io/pockethive/requesttemplates/TemplateLoader.java),
  `parseTemplate`, lines 76–119, substitutes its supplied `defaultServiceId` for absent
  serviceId and constructs an HTTP definition with absent method/pathTemplate.

Exact temporary input:

```yaml
protocol: HTTP
callId: example
```

`TemplateLoader.load(root, "default-service")` accepted it and returned an
`HttpTemplateDefinition(serviceId=default-service, method=null, pathTemplate=null, ...)`.
An isolated invocation of the actual authoring shape validator returned three ERROR
findings, for missing `serviceId`, `method`, and `pathTemplate`.

The probe used the private authoring method reflectively, not the entire bundle pipeline.
It establishes incompatible shape semantics; it does not assert that the resulting
incomplete HTTP request can successfully execute. The shared DTO alone does not provide
SSOT when validation and defaulting are implemented separately.

Required ownership: the request-template library must own parsing and shape validation;
Scenario Manager should translate its diagnostics and add bundle-reference checks.

## SSOT-05 — Scenario Manager and its client hand-maintain three wire shapes

**CRITICAL under the SSOT rule; structural duplication, no wire failure reproduced.**

Producer-owned files:

- [RuntimeRequest](../../scenario-manager-service/src/main/java/io/pockethive/scenarios/RuntimeRequest.java)
- [ScenarioRuntimeResponse](../../scenario-manager-service/src/main/java/io/pockethive/scenarios/ScenarioRuntimeResponse.java)
- [VariablesResolveResponse](../../scenario-manager-service/src/main/java/io/pockethive/scenarios/VariablesResolveResponse.java)

[ScenarioManagerClient](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/infra/scenario/ScenarioManagerClient.java)
declares independent matching records at lines 277, 280, and 287. They are active:
materialization constructs/deserializes them at lines 88–91 and variable resolution
deserializes its copy at line 155. Producer endpoints in `ScenarioController` use their
own records at lines 445–455 and 768–776.

These are copies of the same request/response contract, without generation or a shared
contract type. Moving producer records into separate files satisfied implementation-unit
separation while retaining duplicate authority. The client's smaller, named
`ScenarioTemplateResponse` catalogue view was not counted as another full-contract copy.

Required ownership: a canonical service contract artifact or generated clients. A caller
may map that contract into its own domain model after decoding.

## SSOT-06 — UI replaces canonical values with its own normalization rules

**CRITICAL; two disagreements reproduced from current TypeScript sources.**

- [networkProxy.ts](../../ui-v2/src/lib/networkProxy.ts), lines 3, 76–78 and 98–115,
  hand-defines NetworkMode and maps every value except exact `PROXIED` to `DIRECT`.
  `normalizeBindings` applies this to both requested and effective mode.
- [NetworkBinding](../../common/swarm-model/src/main/java/io/pockethive/swarm/model/NetworkBinding.java),
  lines 23–24 and its `requireMode`, requires explicit non-null modes. Architecture
  §5.1.1 specifically prohibits null-to-DIRECT and invalid-value recovery.
- [runtimeConfigGuard.ts](../../ui-v2/src/lib/runtimeConfigGuard.ts), line 11,
  trims and uppercases workload state instead of using the strict
  [generated lifecycle parser](../../packages/swarm-lifecycle-contract/index.cjs).

These are active paths: `ProxyPage` lines 184–186 and `HivePage` line 705 normalize
bindings; `HivePage` line 869 uses the workload predicate when gating Redis list changes.

Actual source modules were transpiled with the installed TypeScript compiler and invoked
offline alongside the shared parser:

```text
NETWORK null -> DIRECT
NETWORK "BROKEN" -> DIRECT
NETWORK "PROXIED" -> PROXIED
WORKLOAD "Stopped" guard=true contract=REJECTED
WORKLOAD " STOPPED " guard=true contract=REJECTED
WORKLOAD "STOPPED" guard=true contract=STOPPED
```

This proves invented UI state and permissive gating, not an actual backend network-mode
change or a bypass of server-side configuration enforcement.

Required ownership: consume canonical parsed/generated contracts and expose invalid data
as invalid. A display projection must not choose a network mode or invent compatibility aliases.

## SSOT-07 — Worker status projection independently owns freshness policy

**CRITICAL under the SSOT rule; duplicate observation policy, no outage reproduced.**

- [SwarmReadinessTracker](../../swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmReadinessTracker.java)
  owns a heartbeat registry, timestamps, and `STATUS_TTL_MS = 15_000` (line 24).
  Its metrics and readiness evaluate heartbeat age at lines 154 and 188.
- [SwarmWorkerStatusHandler](../../swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmWorkerStatusHandler.java)
  declares another 15-second constant at line 19 and constructs a separate aggregator.
- [SwarmWorkersAggregator](../../swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmWorkersAggregator.java)
  independently validates `enabled`, timestamps raw status receipt, stores worker state,
  and computes `stale` from its own clock/map at lines 28–69 and 86.

The handler updates lifecycle heartbeat and the aggregator separately, then updates the
lifecycle enabled/snapshot observation. The aggregate is therefore not simply a serialization
of the lifecycle owner's observation. Equal TTL literals do not make one policy owner;
independent timestamps and validation allow the projections to disagree at boundaries.

The finding is limited to duplicated worker observation/freshness semantics. It does not
claim that `SwarmWorkersAggregator` implements another full lifecycle state machine, or
that expected worker state and observed worker state must be the same data structure.

Required ownership: the core supplies accepted timestamped observations and their
freshness policy/result; the status aggregate projects those observations and adds only
presentation/diagnostic fields with explicitly distinct ownership.

## SSOT-08 — UI reimplements the server's grant and resource-scope predicate

**CRITICAL under the SSOT rule; structural policy duplication, no authorization bypass proved.**

- [PocketHiveGrantChecks](../../common/auth-contracts/src/main/java/io/pockethive/auth/contract/PocketHiveGrantChecks.java)
  owns permission membership and DEPLOYMENT/FOLDER/BUNDLE scope matching in Java.
- [auth.ts](../../ui-v2/src/lib/auth.ts), lines 201–244, independently implements the same
  product, permission, folder-prefix, bundle, and global-selector checks.
- [authContracts.ts](../../ui-v2/src/lib/authContracts.ts) hand-maintains the related product,
  resource, and permission constants; this is not the generated lifecycle package.

Server callers include Orchestrator and Network Proxy authorization; UI `authContext.tsx`
lines 161–164 derives access/action capabilities using its local predicate. This is a
second implementation of the same policy despite being used for UI visibility.

Normal valid-input predicates currently appear aligned. The UI does not grant server
permissions, and this audit does not claim an access-control bypass here. The problem is
the independent policy maintenance and absence of derivation from one policy owner.

Required ownership: server-derived resource capabilities, or one canonical policy
definition generating both consumers. Service-specific required permission sets may
remain different where their operations have explicitly different authorization needs.

## Checked cases not promoted to duplicate-owner findings

- Control-plane decoding and public response construction have identifiable shared owners
  (`ControlPlaneCodec`, `ControlResponseFactory`); reviewed callers delegate to them.
- Command-specific lifecycle handlers operate through the operation coordinator. Separate
  intent and observation models are required by the architecture; their mere coexistence
  is not a second state machine.
- Controller buffer-guard integration delegates its guard decisions to the manager SDK.
  The similarly named integration class is not sufficient evidence of a copied guard engine.
- Work-item transport conversion delegates canonical envelope decoding to `WorkItemJsonCodec`.
  Building a new work item from a Redis dataset record is a different responsibility.
- MCP bundle operations call Scenario Manager and map owner results into named projections;
  no second complete bundle validator was established in those inspected adapters.
- UI bundle-validation summary normalization is questionable fallback debt, but the
  inspected code retains the owner's `ok`; it was not counted as a second success decision.
- Socket/Netty transports are alternative implementations. Some local defaults deserve
  separate NFF review, but normal TCP handling supplies effective settings; duplicate
  method names/default literals alone did not establish another effective-config owner.

These are scoped observations, not certifications that the modules contain no other issues.

## Provenance: retained debt is not automatically a new regression

Git line history predates the latest structural phase for several demonstrated problems:

| Finding | Relevant history |
|---|---|
| SSOT-01 | Immediate `REMOVED` construction: `754ed84e2`, 2026-06-18 |
| SSOT-02 | Reader path assembly: `fa904d8fe`, 2025-12-15; partial shared-root integration: `922dc5b05`, 2026-07-23 |
| SSOT-03 | Properties validator: `045f23fb7`, 2026-07-02 |
| SSOT-04 | Loader service fallback: `a8f6f42b7`, 2026-02-07; required authoring fields already present June/July 2026 |
| SSOT-05 | Client records: December 2025 / February 2026; matching producer records existed before extraction `50d456ee` |
| SSOT-06 | Network coercion: `2b74cec9a`, 2026-03-09 |
| SSOT-07 | Handler constant moved in `5710bcddc`, 2026-09-01; its parent already had `MAX_STALENESS_MS` and the aggregator in `SwarmSignalListener` |
| SSOT-08 | UI scope predicate: `c40cc3b3b`, 2026-04-20 |

This is line provenance, not proof of the precise first commit where every pair began to
conflict. It is enough to reject attributing all these problems to the last week of edits.
It does not identify a stable rollback point or establish that the refactor achieved SSOT.

The [archived simplification plan](../archive/control-plane-simplification-plan.md),
Decisions, explicitly deferred existing agreeing duplicates by human decision and said
they were **not resolved** and required later reassessment. Thus structural completion
of that queue was never evidence that repository SSOT had been achieved. The behavioral
contradictions above require their own assessment; they cannot be justified as agreeing copies.

## Verification record and implications

Selected current production Java sources were recompiled with `javac -parameters` against
the existing reactor test dependencies and invoked in temporary harnesses. Java and Node
probes completed successfully and produced the contradictory results quoted above.
Reflection was used for the two isolated private validation methods and for configuring
the journal backend in the unit fixture. Infrastructure/authorization ports were mocked.
The UI probe used actual transpiled modules, with an unused UUID dependency stubbed.

Temporary evidence is in `/tmp/SsotOwnershipProbe.java`, `/tmp/SsotTemplateAuthoringProbe.java`,
`/tmp/ssot-ui-probe.cjs`, and their `/tmp/ssot-*-probe.log` outputs. Inputs, invocation scope,
and decisive results are preserved in this report because temporary paths are not durable
repository artifacts. No full-stack test was run; no service/backend ports were contacted.

A WorkPlane library rewrite would address only part of this list. It needs to own
transport configuration, validation, patch semantics, and execution contracts together;
Scenario Manager and workers must consume those contracts rather than copy their rules.
The resource-outcome, filesystem, control-observation, and client-policy findings would
remain separate work even after a correct WorkPlane rewrite.

Separate repositories can enforce build/dependency boundaries, but file movement alone
does not remove any of these competing authorities. Acceptance must demonstrate, for each
responsibility, one callable owner, deletion of the old implementations, and tests showing
that all consumers agree on valid/invalid inputs and verified postconditions. The examples
in this audit are concrete negative cases for that acceptance, rather than another claim
that class extraction or a green happy-path run establishes SSOT.
