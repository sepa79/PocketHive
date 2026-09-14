# One Rabbit module — migration plan

Status: Java technology transfer implemented; residual gates remain, rechecked 2026-09-14.
Startup/E2E checkpoint: `0bfa378c`; legacy binding cleanup was subsequently removed on explicit
user instruction, with 30 focused tests passing. Full normal E2E passed 39 scenarios on
2026-09-11 before that deletion. Browser schema/STOMP remains blocked; test fixtures and legacy
debug tooling retain explicit exclusions. Historical handoff notes below are not current test results.
The [functional module repair plan](functional-module-boundaries.md) owns sequencing beyond Rabbit;
this file retains the Rabbit-specific contract and exclusions.

## Current execution contract

Create `common/rabbit-adapter`, namespace `io.pockethive.rabbit`, as the sole RabbitMQ
technology owner. Work Plane and Control Plane both use its public API. Move and
consolidate existing implementations; do not add a parallel implementation alongside them.
The [boundary design](../architecture/work-plane-boundaries.md) owns the responsibility
split. This file owns execution order and completion conditions.

This replaces the former B02-first and B03–B07/C01–C03 sequence. Old phase exclusions
must not defer Rabbit configuration, naming, resource operations or actual settings
consumption outside this migration. Existing unrelated functionality is retained.
The task is complete only when both planes and other Rabbit consumers use the owner.
Smaller implementation steps are progress, not separate SSOT acceptance.

## Implementation order

1. **Map the existing Rabbit responsibility and specify its public API.** Follow actual
   configuration reads, name/routing construction, connection/client construction,
   declare/bind/inspect/delete/purge calls, publishing, consuming and settlement.
   Cover worker-sdk, control-plane-spring, Controller, Orchestrator, Scenario,
   diagnostics and operational tooling. Record a compact owner/consumer/deletion table
   here, from the current source. Separate domain routing decisions from broker mechanics.
   Define operation results and failure postconditions before moving their implementation.
2. **Move the owner into `common/rabbit-adapter`.** Consolidate settings/defaults/
   normalization/validation, physical resource naming and Rabbit operations. Reuse
   existing types and behavior where correct. Neither the old properties nor the new
   parsers win by name: preserve one implementation of each rule and remove its copies.
   One class per responsibility; no universal class or raw-client access through the API.
3. **Migrate Work Plane and Control Plane consumers.** They pass explicit, separate
   settings and domain intent. Include startup, Scenario validation, environment export,
   provisioning, transport, diagnostics and cleanup. Transfer existing Rabbit-specific
   implementations from services/SDK/control-plane-spring and absorb rabbit-config.
   Keep application state machines and wire semantics with their existing domain owners.
   Apply non-default settings to actual Rabbit operations, not just property carriers.
4. **Remove bypasses and enforce the boundary.** Delete replaced classes/helpers,
   duplicate rules and direct client paths. Remove the old rabbit-config artifact after
   its consumers move. Use existing Maven Enforcer rules and RepositoryImportBoundaryTest;
   dependency declarations alone do not prove ownership. Composition may assemble the
   module through its supported API, but must not configure raw Rabbit clients externally.
5. **Hand off for separate review.** Present a short current owner/deletion table,
   behavior results and remaining limits. Review is requested separately; no automatic
   agent assignment or review/fix loop. Simplification must not defer duplicate owners.

## Current owner/API transfer map

| Responsibility | Public API target | Existing consumers / implementation to remove |
| --- | --- | --- |
| Declare/bind/inspect/delete broker resources | `RabbitResources`, immutable queue/exchange/binding specifications and queue observation | `SwarmWorkTopologyManager`, `SwarmRuntimeInfrastructure`, `SwarmQueueStatsPortAdapter`, `SwarmControllerControlQueueVerifier`, `ContainerLifecycleManager`, `AmqpRabbitTopologyAdapter`, CP declarable factories and DebugTap |
| Encode/decode effective settings and names | Rabbit configuration/naming API | rabbit-config, SDK Rabbit properties, Controller exports, CP connection composition, shared physical-name resolver |
| Publish/receive and listener lifecycle | Rabbit publishing/subscription capabilities, broker-neutral message value | SDK Rabbit input/output/converter, CP publisher/listeners, Generator, DebugTap; raw templates/containers stay internal |

Resource API uses explicit immutable specifications. Queue inspection returns presence
and broker observations, never invented zeroes for malformed required counts. Deletion
returns normally only when absence is observed; broker failure or a surviving resource
fails explicitly. Application cleanup authority and operation state remain outside the
module. Resource declarations preserve durable/topic and binding intent from callers.
Transport and configuration signatures are specified from their actual consumers before
those transfers; this table does not claim those paths are migrated.

## Completion conditions

- Rabbit rules and operations have one implementation owner for both planes.
- Consumers use `io.pockethive.rabbit.api`; raw Rabbit/Spring-AMQP clients, admin handles,
  listener containers or callbacks exposing those objects do not escape that boundary.
- Shared module does not merge Work/Control connections, credentials, resources or policies.
- Configuration is validated once by its owner and the effective result is consumed by
  startup, exports and execution. Invalid values fail before the relevant effects.
- Non-default names, prefetch/concurrency and publishing settings have observable effects
  where supported. Missing/unsupported settings fail explicitly; no silently ignored knobs.
- Resource creation/observation/removal and delivery outcomes preserve the required
  postconditions. Attempting an operation is not proof of successful completion.
- Current wire/routing contracts, correlation, error classification and settlement behavior
  are preserved unless a specific documented behavior change is approved. Moving routing
  utilities does not authorize changing public routes or schemas.
- No active second owner remains in diagnostics or tooling. Non-Java consumers use a
  supported service API backed by the owner; any missing API is an explicit design item.
- Tests cover actual results: rejected candidates before effects, accepted-state preservation,
  effective settings, resource failures and both planes operating with isolated settings.
  Use existing behavioral tests and supported test interfaces, not bean-identity tests or
  a new source scanner. Deployment is not implicit in this task.

## Known starting defects and scope limits

At the starting revision Rabbit properties owned defaults/normalization while newly added
parsers implemented another set of rules. The current transfer removes that split. A source-compiled probe at `0dbddaee` accepted prefetch=0 through
properties validation and rejected it through the shared parser. Rabbit input factory
ignored its config argument; this predated the branch and is fixed in the current transfer.

Existing Redis/local/template delegations are retained; do not roll back the branch or
re-extract them as a prerequisite. Further technology modules and broad worker runtime
extraction are later work, to be planned after the Rabbit boundary closes. Input/output
is broader than inter-worker Work Plane; see the design for that distinction.

Artemis implementation is deferred. Redis SEL-R1 remains open and user-deferred: a resumed
in-flight tick can consume the old list after STOP/update/START. Other CP/state/security
issues are not silently closed by this migration. Existing authorization and cleanup
requirements remain effective; no production deployment, push or commit is authorized here.

## Current implementation handoff

`common/rabbit-adapter` now owns resource operations, publishing, polling and listener
mechanics. Controller/Orchestrator resource consumers use RabbitResources; CP declarations
project public specifications. Passive inspection distinguishes absence from broker failure;
deletion verifies absence. Queue age remains explicitly unavailable from passive inspection.

CP publication and Work output use RabbitPublisher, DebugTap uses RabbitReceiver. Work input
uses RabbitListeners with selected prefetch/concurrency/exclusive; its second result publisher
and SDK raw container customizers are deleted. Four CP receive paths register RabbitListenerBinding
values through the same implementation. CP retains its fatal contract-error classifier; Rabbit
owns container setup and ACK/NACK effects. Deleted CP factory/poison customizer and application
Rabbit annotations. No production raw Rabbit imports remain outside the module (E2E fixtures
retain their existing exception). Root Enforcer now rejects direct Rabbit dependencies outside
the module/fixtures; the existing import test enforces the corresponding source boundary.

rabbit-config is absorbed; properties delegate canonical normalization/validation/defaults.
Scalar rules are shared by parser/snapshots, and invalid exclusive/concurrency rejects before
registration. Spring connection decoding also moved into rabbit-adapter; services no longer
import their own Rabbit bootstrap configuration.

Verification: prior transport/configuration transfer passed 181 selected tests and 16 startup
checks. Current CP transfer passed 71 selected behavior, composition and boundary tests
(`/tmp/rabbit-cp-final-tests.log`). Tests
exercise actual listener containers against mocked broker clients, not live service ports.
Full reactor code/test compilation passed (`/tmp/rabbit-cp-all-testcompile.log`). No separate
review, broker deployment, commit or push.

The approved [resource-plane identity addition](../architecture/work-plane-boundaries.md#connection-split-prerequisite-resource-identity)
is implemented in lifecycle targets, cleanup candidates/results, debug snapshots and the
generated TypeScript lifecycle contract. The planner preserves equal names in CONTROL/WORK;
execution and absence verification use the explicit plane. UI projections display it and MCP
passes the owner's result through. Historical unscoped removal targets are rejected.

Control and Work now use separate connection instances. Control clients receive canonical
`spring.rabbitmq` settings; Work clients receive required `pockethive.rabbit.work` settings.
RabbitConnectionClients applies the five fields for either plane, and RabbitConnectionEnvironment
exports both through Controller/worker startup. Work resources, publishing, polling and
subscriptions use its connection; CP retains its own listener policy. Per-worker Work connection
overrides are rejected. Cleanup hashing includes the selected broker/vhost/principal identity.
No environment deployment was performed; operators must supply explicit Work settings.

Physical Java naming now belongs to RabbitResourceNames: Work prefixes/queues/exchanges,
Control participant/status queues and debug taps. Neutral ports remain in topology-core;
Control descriptors retain routing policy and use the naming port. Removed the old core
PrefixedWorkResourceNames owner, Controller prefix reconstruction and worker properties'
duplicate naming/routing catalog. Startup/status/provisioning/cleanup consume those owners.
ControlPlaneRouting remains the domain grammar owner. Operational non-Java paths remain open.

Work delivery preserves pre-extraction behavior: AUTO ACK on callback return, existing
async dispatch/error handling and STOP behavior, and submission-only output. The attempted
publisher-confirms activation, completion-driven settlement, disabled-admission requeue and
additional unsupported Work-option rejection were withdrawn on explicit user instruction.
Module/API extraction, canonical settings/names, plane separation and UI projection remain.

UI STOMP address projection is implemented through Orchestrator information API; legacy debug
tools are deferred as recorded below. Full TLS/address-list propagation remains outside the
five-field connection contract; Control address lists are rejected to preserve exact cleanup
identity. No complete Rabbit SSOT acceptance or aggregate review yet.

## Implementation reading list

The [accepted CP override limitation](../architecture/work-plane-boundaries.md#accepted-cp-override-limitation)
is outside further hardening and is not a completion blocker. Preserve the direct-key guard
and ordinary ENV support; do not reopen indirect startup overrides from historical reviews.

Read this plan, the linked boundary design, AGENTS.md and its applicable engineering,
contract and review references. Read current source for the operation being transferred.
Old phase reports are not prerequisites or instructions.

The superseded plan/design and 57 evidence files are in
[the archive](../archive/module-boundaries-before-rabbit-2026-09-11/README.md).
They preserve historical findings and uncommitted planning context, not current acceptance.
Keep new handoff evidence compact and current; do not recreate an accumulating phase diary.

Non-Java scope decision: legacy tools/mcp-orchestrator-debug/client.mjs and rabbit-recorder.mjs
(including their legacy MCP wrapper) are deferred for removal/replacement through product APIs;
do not spend migration work refactoring their broker clients. UI keeps Web-STOMP and consumes
a Rabbit-owned destination projection from Orchestrator /api/control-plane/info. No SSE migration.

## Behavior-preservation correction — 2026-09-11

User explicitly rejected any behavior change in this refactor. The previous aggregate
review's RAB-R01/R02/R03 repair sequence is withdrawn as implementation scope, not evidence
that all historical behavior is ideal. AUTO ACK, swallowed dispatch failures, executor
rejection synchronous processing, disabled-null return and inactive publisherConfirms are
restored. No shutdown/drain implementation was added. The latest additional Work TLS/address
rejection is also removed; earlier explicit connection separation remains intact.

Do not reactivate these changes from historical review notes or HiveMind entries. Any
future delivery/configuration behavior change requires a separate explicit task.
