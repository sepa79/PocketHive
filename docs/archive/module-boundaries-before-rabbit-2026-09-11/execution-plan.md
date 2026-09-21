> Archived 2026-09-11. Historical evidence only; not implementation instructions.

# Enforced module boundaries — Work Plane first

Status: active plan; B01 accepted, B02 incomplete and unaccepted. Execution clarification
2026-09-11: complete responsibility/module boundaries are the unit of delivery. Existing
Controller, Scenario, topology-name and candidate-gate reviews remain scoped evidence.
Owner: PocketHive architecture work on `refactor/control-plane-critical-restart`.
Current inspected checkpoint: `0dbddaee`.
Scoped implementation/review results: [B02 evidence](boundary-design/b02/README.md).
Memory maintenance is separate; its remaining gate is recorded in
[the memory register](../../ai/HIVEMIND_MEMORY_STATUS.md).

## Current execution contract

This section owns the current order and bounded B02 worklist. Dated reports below and
in the evidence directory describe their recorded revisions, not additional instructions.
Their former next steps, model assignments and pending-review statements do not override
this section. Historical scoped acceptance remains evidence within its original scope.
The former Terra/Sol execution assignment is withdrawn; no automatic subagent dispatch
or review/fix loop is authorized. Review remains a separately requested task.

### Unit of delivery: one responsibility behind one module API

The existing SSOT and module-boundary requirements are acceptance conditions, not a
later cleanup objective. This clarification preserves phase scope and accepted work;
it does not authorize starting B03–B07 or adding new ports merely to wrap classes.

For each transferred responsibility, the implementation handoff must identify:

- the one owning module and its supported public API;
- all actual consumers, including startup, diagnostics, projections and cleanup where
  they consume that same responsibility;
- the old formulas, helpers, constructors and direct calls removed in that transfer;
- the exact dependency/import restriction enforcing the new boundary, including which
  internal implementation types must no longer be public consumer entrypoints;
- behavior showing that consumers use the owner's effective result, including
  non-default names/settings and rejected changes before effects where applicable.

Identify consumers from actual uses of names, paths, settings and effects, not only
references to a known helper. A projection consumes the resolved result; rebuilding it
from the same ingredients is another implementation. A port or namespace alone does
not establish ownership, and selecting the same concrete resolver independently is not
proof that a configured capability was consumed.

Close the responsibility across its consumers before accepting its transfer. Small
implementation commits remain possible; they are progress, not partial SSOT acceptance.
Do not defer an active competing owner to simplification or a later phase. A different,
untransferred responsibility retains its named owner and is not part of this closure.

Use the existing Maven dependency rules and sole permitted import test for mechanical
restrictions; do not add custom source scanners or bean-identity tests. A deployable
service may assemble both Work and Control Plane adapters. Enforce restrictions on its
application/core modules and permitted imports, not by banning infrastructure from the
entire deployable artifact that must contain those adapters. Any additional module split
needed outside the frozen B02 scope must be proposed explicitly, not silently implemented.

### Frozen B02 scope

B02 closes configuration boundaries: neutral IO selection/candidate and patch orchestration,
existing Rabbit/Redis/CSV/scheduler settings providers, and all affected startup,
Scenario, Controller and runtime configuration consumers. Existing request-template
parsing/file-boundary transfers retain their V05 acceptance obligation.

The candidate covers only `inputs`/`outputs`, with AUTHORING/RESOLVED modes. Concrete
settings and field rules belong to adapter/config modules behind neutral parser and
mutation-policy ports. Standard Spring flattening remains the runtime input boundary.
Rabbit AUTHORING contains selection and optional tuning, never physical destinations
or deferred placeholders for them. Controller materialization consumes resolved topology;
it must not become a second name or field-rule owner.

B05a/B06a/B07a are historical labels for the Rabbit/Redis/local configuration parts of
B02, not later prerequisites or separate phases. B03a names the already implemented
narrow mutation-provider composition and pre-acceptance validation integration within
B02. It does not transfer accepted-state ownership or start full B03 extraction.

Excluded from new B02 work: `privateConfig`, reset and execution/timeout/confirm/drain
mechanics; runtime state-machine extraction; topology provisioning/observation/cleanup;
broker replacement/Artemis; SEL-R1; unrelated service, UI or tooling cleanup. Previously
accepted reset/rate/timing/enablement transfers remain scoped evidence; their presence
does not authorize expanding this candidate. New issues enter the normal backlog unless
a concrete failure of a B02 gate makes them necessary. Any proposed scope addition must
name that failure and receive an explicit plan amendment before implementation.

### B02 transfers and completion conditions

Architecture owns port definitions and responsibility contracts. The rows below own
execution scope. A missing contract is a named preparation task, not permission to embed
its implementation in a consumer. Proposed extractions are targets, not delivered owners.

| Transfer | Owner and port boundary | Consumers | Old paths to remove | Completion condition |
|---|---|---|---|---|
| Neutral configuration and adapter providers | `WorkConfigurationParser` / `WorkPatchPolicy`; `WorkInputSettingsParser`, `WorkOutputSettingsParser`, `WorkInputMutationPolicy`, `WorkOutputMutationPolicy`; field owners in `rabbit-config`, `redis-config`, `work-local-config` | Worker SDK, Scenario validation/capabilities, Controller configuration flow | Adapter-specific parsing in neutral modules; duplicate selection/field validators and mutation descriptors | Exactly one selected provider, shared field decisions, strict AUTHORING/RESOLVED behavior; all affected consumers migrated (V04) |
| Controller configuration composition | `WorkerWorkConfigurationPort` consumed by `SwarmWorkerSpecFactory`, implemented by `WorkerWorkConfigurationAdapter` and wired by `WorkerWorkConfigurationComposition`; extraction and candidate gate have scoped review evidence; existing codecs/parsers retain field ownership | Worker planning and environment/bootstrap production | Concrete provider construction and adapter-specific composition in the mixed spec factory; repeated mapping/validation | Consumer receives the configured capability; one effective environment/bootstrap result, complete validation before plan acceptance/effects; no new topology/name owner |
| Scenario AUTHORING integration | `WorkConfigurationParser` delegates through its existing parser ports; Scenario owns only authoring projection and bundle references | `ScenarioBundleValidator`, `WorkConfigurationFindings`, capability projections | Concrete parser construction and local adapter-validation assembly in Scenario consumers | Rabbit tuning-only authoring works without physical topology; diagnostics come from canonical validation; bundle-reference checks preserved |
| Startup and runtime validation integration (historical B03a) | Existing worker state owner consumes neutral parser and mutation registry; adapters supply startup settings through their existing configuration boundary | Startup binders, worker composition, `WorkerControlPlaneRuntime` | Bypasses of candidate validation; duplicate settings rules and accepted-configuration authorities | Invalid candidates fail before logging/conversion/effects/state/ACK; accepted state remains unchanged on rejection; no worker-state ownership transfer |
| Existing request-template transfer | `RequestTemplateParser` owns decoded semantics; `request-template-files` owns file access through the existing loading boundary | Scenario, template loading, Request Builder, HTTP Sequence, offline diagnostics | Previous template validators/parsers and direct loading bypasses in migrated consumers | V05 semantics and failure classification preserved; historical open findings reconciled against recorded review evidence |

Each implementation handoff must enumerate actual affected source paths and consumers
for its row, identify the owner/header/port, and show deletion of the prior path in the
same transfer. Use repository-wide searches to find remaining consumers. A moved class,
provider catalogue or passing parser suite alone cannot close a row. Do not defer duplicate
owners or consumer bypasses to the later simplification task.

### Authorized follow-up — 2026-09-10

Human authorization extends this slice to the named shared topology name-resolution owner
and port (RESP-WORK-RESOURCE-NAMES), Rabbit AUTHORING/RESOLVED separation and Scenario's
injected neutral validation path. This is the narrow B04 naming prerequisite; provisioning,
observation and cleanup state machines remain excluded. Remove replaced formulas and local
validators in the same transfer; test failure before effects and environment/bootstrap parity.
The previous blanket exclusion of topology migration does not exclude this named slice.

### Remaining order and B02 exit

1. **Prepare the concrete B02 closure list.** Inspect remaining startup, runtime and
   request-template consumers against the transfer table above. Record owner module,
   public API, existing bypass, deletion and exact enforcement location for each gap.
   Put this evidence in the existing B02 record, not a new competing roadmap. The output
   is a bounded implementation list with source references, not another broad audit or
   a list of classes to extract. Distinguish already delivered work from unresolved gaps.
2. **Close the listed B02 responsibilities.** Migrate all consumers of each affected
   configuration/parsing rule, remove old entrypoints and apply its boundary restriction
   in the same transfer. Complete V04/V05 evidence. Existing Controller/Scenario/name and
   full RESOLVED gate work is reused; it is not reimplemented. See the
   [topology correction review](boundary-design/b02/topology-tap-final-review.md) and
   [candidate gate review](boundary-design/b02/controller-candidate-final-review.md).
3. **Separate whole-B02 review.** Require all rows, real consuming paths, matching
   architecture/headers, enforced boundaries and behavioral evidence. Scoped review
   acceptance does not imply full B02 acceptance.
4. **Simplify accepted B02**, with separate review. Remaining duplicate authorities or
   live bypasses are acceptance defects, not simplification tasks.
5. Continue B03–B07 using the same complete-responsibility gate below. Phase order and
   separate review/simplification gates remain; no automatic execution is authorized.

### What remains in B03 and later phases

The architecture deletion ledger owns detailed targets. This table distinguishes the
remaining responsibilities from the configuration work already delivered in B02.

| Phase | Responsibility to close across consumers | Boundary required for acceptance |
|---|---|---|
| B03 | Worker accepted state, IO coordination and runtime decisions | One runtime state owner; adapters/control integration consume its API without independent state mutation. |
| B04 | Work resource provisioning, observation and removal | Named resource owners and adapters; Controller, Orchestrator and diagnostics consume their APIs/results instead of client calls or reconstructed identities. Existing name-resolution owner is reused. |
| B05 | Rabbit Work delivery and publishing | Rabbit Work implementation owns its client/container/delivery behavior; application cores cannot use Rabbit clients directly. Existing B02 settings and B01 Control/Work isolation are reused. |
| B06 | Redis dataset/output/sequence/token/capture operations | Assigned Redis operation owners behind their APIs; clients and globals removed from consumers. B02 configuration providers/projections remain the configuration SSOT. |
| B07 | Local input execution and worker packaging | Scheduler/CSV execution and worker business cores use their assigned APIs; SDK composition cannot retain alternate implementations. |
| C01–C03 | Remaining Control Plane, operations/files and service/client projections | Apply the same owner/API/deletion/enforcement gate after Work acceptance; these are distinct responsibilities, not exemptions for an unfinished B transfer. |

One technology namespace is not permission to mix Work/Control policy, state and
configuration authority. Public APIs expose the needed capability; consumers must not
receive a raw client or universal administration handle that reopens the boundary.

Artemis and [SEL-R1](boundary-design/b02/known-issues.md) remain separate deferred work.
The completed extraction does not authorize an automatic review or expansion beyond this worklist.

## Outcome and scope

Application code in Orchestrator, Controller, and workers must use narrow capabilities
without a compile-time dependency on RabbitMQ, Redis, Docker, JDBC, or their Spring
infrastructure wrappers. Only the corresponding infrastructure adapters may use those
clients. Separate startup/composition modules select explicit adapters and settings.

Each material fact also has one owner: configuration parsing/validation, effective
names and paths, accepted observations, state transitions, and verified outcomes.
Removing imports alone does not establish SSOT. Consumers must not reconstruct resource
names, copy validators, or turn attempted actions into successful outcomes.

This is the single execution plan for this architecture stream. Begin with Work Plane,
then apply the same boundary discipline to Control Plane and remaining service concerns.
The Controller extraction was subsequently authorized explicitly. Further runtime migrations
remain governed by the bounded worklist and existing contract/review rules.

Existing runtime contracts remain authoritative until their relevant contract-first change
is reviewed. Proposed module names below are planning names, not already delivered APIs.
The previous sink-splitting phase remains accepted within its scope; its inherited debt
does not make those extractions regressions.

## Ownership and dependency direction

| Boundary | Owns | Must not own |
|---|---|---|
| Work API / neutral configuration | Work envelope in `work-api`; selection, parser/mutation ports and candidate orchestration in `work-config` | Concrete adapter settings/parsers/codecs, broker connections, service lifecycle |
| Adapter configuration | Concrete settings, field parsing/validation and codecs in the matching config module, exposed through neutral ports | Application orchestration, topology naming, duplicate client-side validators |
| Work runtime | Work delivery/execution policy and use-case orchestration through ports | Rabbit/Redis/Docker imports, infrastructure naming, topology declaration |
| Worker integration contracts | Infrastructure-free identity, configuration commands, status and lifecycle capabilities required by Work consumers | Control transport, Spring configuration beans, copied Control contracts |
| Work topology owner | Effective logical-to-physical resource mapping and desired topology for Work | Control Plane topology or Orchestrator operation state |
| Rabbit Work adapter | Rabbit implementation of its assigned Work ports, client/container configuration and resource observations | Control Plane behavior or independently chosen names/defaults |
| Redis adapter | Its explicit dataset/sink capabilities and Redis client operations | Service orchestration or a second settings validator |
| Application cores | Their domain state, workflows, and postconditions through assigned capabilities | Direct infrastructure clients or hidden alternative implementations |
| Startup/composition | Explicit wiring of cores, adapters, and resolved settings | Domain decisions, fallback selection, resource-name reconstruction |

Use capability-sized ports, such as publishing, consuming, topology provisioning, or
resource observation. Do not expose a universal broker administration port to every caller.
A worker must not gain queue deletion just because it can publish work. Avoid leaking
Rabbit `Channel`/`Queue`, Lettuce connections, Spring AMQP objects, or arbitrary client
callbacks through port signatures. Technology-specific settings may remain explicit in
their canonical adapter contract without leaking client library types into consumers.

The compile dependency direction is core -> contracts/ports <- adapter, with startup
depending on the selected components. Audit transitive dependencies as well as direct
POM entries. A core build must fail when code imports a forbidden client. Standard
dependency checks reject forbidden edges; separate source review checks implementation
references, service-local provisioning and exposed infrastructure capabilities.

Build isolation and runtime composition isolation are separate acceptance conditions.
The boundary review and relevant behavioral evidence below must establish that adapters cannot change another
plane's effective configuration or delivery policy through global Spring customizers,
bean discovery, shared mutable factories, or implicit default selection.

Separate repositories are optional packaging after these boundaries are proven. If used,
consumers depend on versioned artifacts; they must not need coordinated source edits for
an ordinary consumer change. Package names alone are not enforcement. These are build
and review constraints, not a security sandbox against code that can modify the build itself.

## Goal execution and separate review

Human decision (2026-09-07): design and implementation goals contain the work, required
tests/checks and evidence collection. They do not contain a self-review pass or an
automatic review/fix/review loop. Apply this separation to all goals in this plan.

- A goal ends with a concrete result, validation evidence and stated limitations, handed
  off as **do review** (awaiting review). Finishing execution does not approve the slice
  or satisfy its acceptance gate. Incomplete work or missing required checks stay explicit.
- Review is a separate task on the finished result, using `docs/REVIEW_RULES.md` and the
  slice's acceptance conditions. Run the six review passes there, not again inside the
  implementation goal. The goal does not automatically launch a review task.
- A correction task addresses the reported findings and runs the relevant verification.
  It returns the changes and evidence for review without automatically repeating a full
  self-review. Any follow-up review is a separate task.
- Keep test execution, negative architecture checks, before/after evidence and existing
  contract/approval requirements in the work. Only a separate review can record acceptance;
  dependent slices remain subject to the acceptance gates below.

## Execution steps

### Separate simplification after each phase

Human decision (2026-09-09): after completing and separately accepting each implementation
phase (B02, B03, etc., then C01–C03), run a distinct simplification task before starting
the next phase. This works on the completed phase as a whole, not after every small
transfer. It does not replace implementation or acceptance review.

Remove obsolete code, repeated delegation and unnecessary intermediate types; simplify
data flow and public surfaces while preserving canonical owners, explicit failure,
port boundaries, observability and accepted behavior. Adjust existing behavioral tests
with the code; do not add wiring tests, scanners, fallback paths or dependencies merely
for cleanup. Line count is evidence, not a target that overrides clarity or correctness.
Record the concrete deletions/simplifications and relevant verification, then hand off
for separate review under the same rules. Do not reopen deferred issues implicitly.

First scheduled simplification: after full B02 acceptance. TIM-R1 correction and reset
parsing passed scoped review on 2026-09-10; full B02 remains open.

### 1. Prepare documentation, review gates, and project memory

- [x] Commit the existing CP-N01 repair and audit evidence as `19d56091`.
- [x] Publish this one active plan and archive competing sequencing/design instructions.
- [x] Retain living contracts and carry all unresolved findings into the register below.
- [x] Repair navigation and links; remove legacy Node/stdio plugin instructions from active docs.
- [x] Add explicit plan, style, conciseness, security, library, and maintainability review passes.
- [x] Inspect the actual global HiveMind MCP catalogue and review PocketHive memory against code.
- [x] Supersede obsolete journal directions, retain unresolved risks and the test-fixture exclusion.
- [ ] Correct/retire stale learning advice through supported lifecycle tools; verify fresh startup output.
- [x] Record unavailable maintenance capabilities as blockers, not successful cleanup.

Step 1 evidence: 31 documents moved with an archive/disposition manifest; no living
contract was replaced by the plan. Thirteen journal records were superseded/corrected
or resolved with explicit reasons. All 23 returned learnings were reviewed; 14 received
correction comments but remain active because the connected MCP 0.5.4 lacks learning
lifecycle mutation. Fresh entry search confirms the retired entries are no longer open.
Read `docs/ai/HIVEMIND_MEMORY_STATUS.md` for exact IDs, limits, and current interpretations.
The user confirmed the missing HiveMind lifecycle endpoints are still under development
and will announce availability. That memory gate is explicitly waiting; documentation
preparation is complete. Fresh verification with an explicit session `plan_ref` selects
this plan in the project brief.
The six review passes are in `docs/REVIEW_RULES.md`; HiveMind ruleset v6 points to that owner.
Validation: `npm run build` in `docs-site` passed after correcting archive-only links
and a source-file link that the site tried to bundle. Archive path/link checks found no
missing destinations or remaining active references to the old paths; `git diff --check`
passed. No application/runtime tests were rerun for these documentation-only changes.

Preparation review:

| Pass | Result |
|---|---|
| Plan outcome | Owners, dependency direction, atomic migration/deletion, explicit negative acceptance cases, and excluded scope are defined. Concrete port/module design remains step 2. |
| Style | Navigation and status conventions updated; current contracts retained; no production implementation types changed. |
| Conciseness | One execution plan replaces competing sequences; archived content has one historical copy and a disposition manifest. |
| Security | No runtime data cleanup, deployment, secret output, direct backing-store edits, or project deletion. Old permissions are not reused. |
| Libraries | No dependencies added. |
| Maintainability | Unresolved findings, provenance, archive reasons, and missing memory capabilities remain explicit; no false completion claim. |

Gate: one active architecture sequence, usable documentation build, traceable archive,
and verified current memory or an explicit list of remaining stale records/tool limitations.
Do not report all memory clean while known stale active learning records remain retrievable.

### 2. Review the concrete Work Plane boundary design

Design: [Work Plane boundaries](architecture-design.md).
Design and review evidence (former scan outputs are archived):
`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/`. B01–B07 and C01–C03 in that design are the concrete
slices for the execution steps here; V01–V12 are their acceptance obligations.

Step 2 evidence (2026-09-07): the design assigns artifacts/namespaces, exact port owners,
all nine worker-core dependencies, shared prerequisites, B01–B07/C01–C03 deletion and
consumer groups, and V01–V12 checks. The corrected generated inventory assigns 212 current source
files and records their reference candidates plus 117 additional candidates across 1,861
source/config files. The 46-artifact target graph is acyclic; target core/client and
worker/admin-capability reachability checks passed. All four input and three output
variants map to concrete slices/checks, with 12 pair acceptance cases in the design.
Source/consumer paths and slice/check references were verified; docs-site build passed.
The earlier self-review in `docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/README.md` is historical and
does not establish acceptance. Corrections to the three separate-review findings are now
prepared: full V03 prerequisites move to B01; queue statistics, availability and manager
projection are specified for B04; the graph gate rejects service adapters, with permanent
direct/transitive negative fixtures. The correction evidence is in that same evidence file.
Separate review accepted R1–R3; the remaining LOW evidence wording was corrected.
B01 execution is delivered for independent review: [implementation and evidence](boundary-design/b01/README.md).
The clean reactor passed 1197 tests (one pre-existing skip); the isolated API/core check
and all-consumer packaging also passed. Work/CP composition and architecture negative
fixtures are part of that evidence, not a self-review. Next task: separate B01 review.
B01 may start only after design acceptance, its before-state evidence and applicable
contract/approval gates; no B/C migration slice is marked complete.

- Inventory every current publisher, consumer, config binder/patch parser, topology writer,
  name resolver, queue observer, cleanup executor, and diagnostic consumer across the repo.
- Assign each responsibility to one owner and map every consumer to its allowed ports.
- Define module artifacts and a permitted dependency graph, including Spring composition.
- Identify the minimum shared-contract and worker-integration extraction required before
  Work migration. Today `worker-sdk` depends on `control-plane-core` and
  `control-plane-spring`, and even `control-plane-core` brings `spring-rabbit`.
  The `templating` dependency also brings Lettuce. Name every such transitive path,
  its destination owner and deletion step; excluding only direct SDK clients is insufficient.
- Populate the coverage matrix below with exact artifacts/types, all active consumers,
  ordered slice IDs and executable acceptance checks. Resolve the prerequisites before
  selecting the first Work responsibility; do not choose a convenient example in isolation.
- Keep Work and Control capabilities separate even if their adapters share a Rabbit driver.
- Define explicit delivery, acknowledgement, failure, timeout, idempotency, shutdown,
  reconfiguration, and observed-removal contracts where applicable.
- Review whether the design actually prevents the audit examples from recurring; update
  authoritative contracts before implementing any changed public behavior.

Gate: every affected responsibility has exactly one owner; every forbidden edge has a
concrete enforcement mechanism and negative acceptance case. Each matrix row has a
reviewed disposition and every required slice has specified baseline and composition
checks. No migration starts with an unresolved ownership choice, dependency on unfinished
step 5 work, or a generic all-powerful adapter API.

### 3. Establish build boundaries and migrate the first complete responsibility

- Capture the first slice's before-state evidence using the verification protocol below.
- First extract the minimum infrastructure-free shared contracts and worker integration
  identified in step 2, introducing the contract/core/adapter/composition artifacts and
  dependency checks. Move existing canonical definitions with all their consumers; do not
  create Work copies of Control contracts. Keep transport implementations in adapters and
  select them only in composition. This prerequisite does not migrate the full CP lifecycle.
- Remove Work settings decisions from Control composition and isolate cross-plane factory
  customization as prerequisites for each affected Work slice (including RAB-03/RAB-07).
  An integration dependency needed for a Work gate cannot be postponed to step 5.
- Migrate one complete Work responsibility with all active consumers in the same slice.
- Remove its previous implementations and dependency paths in that slice.
- Prove a deliberately forbidden infrastructure import/edge fails the normal build.
- Exercise the adapter contract, mandatory Work + Control composition checks, and an
  official-ingress vertical flow using explicit settings. Compare against the before-state.

Gate: the first responsibility is usable through its port, the old owner is absent,
the build rejects bypasses, and composed behavior passes the assigned checks. Evidence
identifies the prerequisite extractions and intended behavioral changes. Do not leave
competing old/new execution paths between slices.

### 4. Complete Work Plane ownership and close its inherited findings

- Complete every step 3–4 row in the coverage matrix, including all supported input/output
  variants, supporting Redis uses, topology, naming, observations, diagnostics, cleanup,
  and configuration updates. Apply the before/after protocol to every slice.
- Make Scenario Manager consume the canonical adapter configuration validation rather than
  independently defining transport rules. Keep bundle-reference validation in its own owner.
- Integrate equivalent canonical contracts into UI/tooling consumers without local defaults.
- Move only required responsibilities out of existing sinks; do not perform unrelated
  class splitting or mix in a broker replacement.

Gate: the Work application/worker cores and their integration contracts compile without
infrastructure clients, including transitive CP/templating paths. Every matrix row required
for steps 3–4 has passing evidence; all migrated owners pass repository-wide SSOT searches, adversarial
contract cases, relevant module tests, composition isolation and official-ingress acceptance.
A single vertical flow cannot close Work Plane. Remaining whole-service CP/infrastructure
separation belongs to step 5 and must not be reported complete here. Declarations of success
require the owning postconditions; no unresolved prerequisite may be relabelled as deferred.

### 5. Apply the boundary model to Control Plane and other infrastructure

- Complete Control Plane contracts/core, transport adapters, and service composition using
  the shared boundaries already extracted in step 3; do not introduce replacement owners.
- Remove remaining service-local Rabbit topology/configuration ownership and UI/tool copies.
- Use the same method for compute, filesystem, journal/database, network proxy, and auth
  policy consumers, preserving one state owner and explicitly derived projections.
- Resolve operation chronology and cleanup postconditions with their domain owners.

Gate: Control Plane and other migrated service cores cannot bypass their ports; ownership
is demonstrated by imports/dependencies, actual call paths, and verified outcomes together.
The same composition and before/after gates apply to these slices.

### 6. Finish remaining sinks and assess repository packaging

- Finish CP-N02–CP-N08 within the established boundaries; public contract bags get named types.
- Review any still-large class by responsibility, not line count alone.
- Decide whether independent repositories now provide useful ownership/release isolation.
- Run the final regression/architecture review and update the findings register from evidence.

`SwarmLifecycleSteps` remains explicitly excluded. Do not refactor it as an implicit
dependency of completing the production work. Any later change of that scope is explicit.

## Coverage matrix and slice assignment

This matrix tracks migration acceptance, not a second runtime capability catalogue.
The current input/output enum owners are `common/work-config/.../config/WorkerInputType.java`
and `WorkerOutputType.java`; contract changes update their authoritative definitions first.
The matrix below references the concrete owners/slices and V-checks in the design; phase acceptance follows the current status above, while scoped evidence
is retained in the linked review records. The design owns the port/artifact definitions,
consumer/deletion ledger and commands. Extend the inventory for additional active paths
found by repository-wide searches; do not treat this initial list as exhaustive discovery.

| Variant / responsibility | Planned owner | Required stage | Minimum acceptance evidence |
|---|---|---|---|
| Worker identity, config/status/lifecycle integration | `WorkerStateCoordinator`, `work-runtime-spi`, `worker-control-adapter` | B01/B03, step 3 prerequisite | V01/V02/V03/V06: infrastructure-free API and one accepted state owner |
| Input `RABBITMQ` | `work-rabbit-adapter`; `DefaultWorkerRuntime` owns execution | B02/B03/B05 | V03/V06/V09: actual settings, settlement, errors and drain |
| Input `REDIS_DATASET` | `redis-adapter`; `WorkConfigurationParser` | B02/B03/B06 | V03/V04/V10: source/order/exhaustion/rate, no Rabbit Work settings |
| Input `CSV_DATASET` | `work-local-adapters`; `WorkConfigurationParser` | B02/B03/B07 | V03/V04/V11: parsing, paths, rate and lifecycle |
| Input `SCHEDULER` | `work-local-adapters`; explicit `ScheduledInvocationPolicy` | B01/B02/B03/B07 | B01 V03: exact selection and trigger policy parity; V06/V11: rate/reset/stop and role independence |
| Output `RABBITMQ` | `work-rabbit-adapter` implementing `WorkOutput` | B02/B03/B05 | V06/V09: one publish, destination/persistence and confirmation receipts |
| Output `REDIS` | `redis-adapter` implementing `WorkOutput`/`RedisUpload` | B02/B03/B06 | V04/V06/V10: one target resolver/writer including uploader consumers |
| Output `NONE` | Explicit no-output implementation in `work-runtime` | B03/B07 | V03/V06/V11: no downstream publish or output connection requirement |
| Redis sequences used by templating | `SequenceAccess` in `templating-api`; `redis-adapter` implementation | B01/B06 | V01/V10: sequence semantics and explicit configuration without client leakage |
| Redis auth token storage used by workers | `TokenStore` in `auth-contracts`; `redis-adapter` implementation | B01/B06; broader auth policy C03 | V01/V10: token scope/expiry/atomicity and no client leak |
| Redis debug capture in HTTP sequence workers | `DebugCaptureStore` in `work-api`; `redis-adapter` implementation | B06/B07 | V10/V12: scope/retention/failure; no Redis client in HTTP sequence core |
| Work topology, naming, observation, diagnostics and removal | `WorkTopologyResolver`, resource ports and `work-rabbit-adapter`; Controller QueueStatsPort/gauge projections | B04 | V07/V08/V12: same effective resources, measured statistics and explicit unavailability, guard/gauge behavior, verified absence |
| Work settings authoring, patches, UI and tools | `WorkConfigurationParser`, `WorkPatchPolicy`, `RequestTemplateParser` | B02/B04 | V04/V05/V07: shared decisions, canonical patches and projections |
| Remaining CP, compute, journal/database, network proxy and auth policies | Canonical domain owners and C-slice deletion ledger in design | C01/C02/C03, step 5 | CP/SSOT reproductions, generated-client checks and ingress evidence |

Test all supported input/output combinations used by current workers/scenarios and contract
boundaries affected by the slice. Record unsupported combinations and their canonical
rejection evidence; do not infer that the Cartesian product is supported. Each supported
variant needs acceptance evidence even if no existing end-to-end scenario exercises it.
Combine focused adapter/composition tests with representative official-ingress flows.
Removal of a supported variant requires an explicit contract/scope decision, not omission
from tests. Any proposed deferral must state the retained behavior, owner and destination
slice and prove it is not required by an earlier gate; required Work rows block step 4.

## Composition ownership review and behavioral evidence

For each affected slice, enforce imports/dependencies and review the actual application
composition against its architecture/header contracts. Follow
[the boundary-verification policy](../../REVIEW_RULES.md#boundary-verification-and-test-value).
Each extraction delivers the class/adapter with unit tests of its owned behavior;
move or adapt existing tests with it. Port contracts are exercised through their
implementations. Add component tests where real collaboration has meaningful behavior.
The eight retained B01 cases are a scoped cleanup result, not a limit on future behavior
coverage. Boundary verification uses documentation/header alignment, the single import
test and review rules with source evidence; do not grow tests around temporary wiring.
Do not add tests that mirror bootstrap code, inspect private factory identity or assert
that a module/bean is used. Wiring changes alone do not require tests or deployment.
Use relevant behavior tests for concrete failure cases and official-ingress acceptance
where the slice requires externally observable delivery/lifecycle evidence.

- Trace Work and Control listener/delivery settings to their owners and consumers.
  Review both directions for cross-plane mutation; test concrete failure behavior
  when affected, rather than factory field values or registration identity.
- Verify explicit ownership/scope of customizers and mutable client resources. In particular,
  `ControlPlaneRabbitPoisonMessageCustomizer` must not install CP error policy on Work
  factories. Sharing a low-level driver is allowed only with proven policy isolation.
- Start non-Rabbit Work input with output `NONE` and valid Control configuration, without
  Rabbit Work settings. Control may still use Rabbit; it must not demand a dummy Work
  exchange, bind Work configuration, or activate an unselected Work adapter.
- Verify missing/ambiguous required adapter wiring fails explicitly, without automatic
  alternative selection. Publish-only consumers must not receive provisioning/deletion
  capabilities, including through auto-configuration or service-locator access.
- Exercise startup, shutdown and reconfiguration when their behavior is affected.
  Record observable policy/effects and concrete regression evidence. Missing ownership
  review or a failing required behavioral case blocks the slice; absence of a
  module/bean-selection test does not.

## Inherited findings register

| Findings | Current disposition | Planned owner/slice |
|---|---|---|
| CP-N01 | Fixed and committed in `19d56091`; focused prior reactor and ingress evidence recorded | Preserve shared Orchestrator topology ownership |
| CP-N02–CP-N05, CP-N07 | Open production sinks | Extract affected responsibilities during steps 3–5; finish remainder in step 6 |
| CP-N06, CP-N08 | Open file/HTTP organization debt | Contract boundaries and final sink pass |
| CP-N09 | Open chronology defect, including local clock regression | Orchestrator operation owner in step 5; may be brought forward as a bounded reliability fix |
| SSOT-01, SSOT-02 | Open cleanup success and journal path defects | Absence evaluator and complete filesystem-path owner |
| SSOT-03, SSOT-04 | Open Redis/template semantic duplication | Canonical Work/adapter and request-template contracts |
| SSOT-05, SSOT-06 | Open wire copies and UI interpretation | Canonical service/generated client contracts |
| SSOT-07, SSOT-08 | Open freshness and policy duplication | Owner-derived observations/capabilities |
| RAB-01–RAB-07 | Open beyond repaired CP-N01 | Work slices first, then Control transport/configuration and projections |

Detailed dated evidence remains in `docs/architecture/ssot-ownership-audit-2026-09-07.md`,
`docs/architecture/rabbitmq-ownership-audit-2026-09-07.md`, and
`docs/architecture/sink-refactor-finding-provenance-2026-09-07.md`.
The previous CP repair report is archived as evidence, not another execution queue.
Archiving a plan does not resolve any defect. Severity is not erased by changing sequencing.

## Deferred proposals and documentation policy

The archive manifest at `docs/archive/pre-boundary-reset/README.md` records original paths,
why each document moved, and the disposition of unfinished work. Artemis, plugin hosts,
multi-worker containers, richer HTTP workers, simulation/dataset redesign, IntelliJ,
and historical MCP feedback concepts are not prerequisites for these boundaries.
Reassess them only against the accepted contracts after the relevant boundary exists.

Living architecture, API/schema, security, usage, testing, and current MCP/IDE references
remain active. Unrelated backlog remains future work, not an alternative execution order
for this architecture stream. Archived code examples and acceptance claims are historical.

## Review and verification

Apply the six review passes in `docs/REVIEW_RULES.md` in the separate review task before
accepting each slice, following "Goal execution and separate review" above. Include
negative cases from the audits: duplicate Redis sources, incomplete template fields,
unknown network modes, non-default/colliding prefixes, cross-swarm paths, delete attempts
with resources still present, and operation chronology across clock changes.

Human decision, 2026-09-09: keep implementation/review evidence concise while this
refactor is active. After final acceptance, delete its working evidence, scan reports,
fingerprints and verification logs instead of moving them to Archive. Keep the current
architecture/contracts, development/review rules and useful tests; move unresolved known
issues into the normal backlog before removing their temporary records.

Use current producer contracts and canonical parsers in tests. No replacement E2E parser,
parallel migration shim, or direct service-port acceptance shortcut. Tests must demonstrate
the effect required by the owner; invocation counts and green happy paths are insufficient.

### Before/after evidence required for every migration slice

Before editing production code, attach a reviewable evidence record to the slice:

1. Identify the exact before revision and any working-tree patch/digest, configuration
   (redacted secrets), scenario/input versions, test commands and environment/artifact
   versions. `19d56091` is a comparison checkpoint, not a certified stable baseline.
2. Run the affected behavior checks before migration and preserve results, including
   known failures. Add focused reproductions for the findings the slice promises to fix.
   A missing runnable baseline must be reported and resolved before implementation;
   unverified behavior must not be classified as an inherited failure.
3. List intended observable differences and their canonical contract/decision references
   before changing behavior. Preserve supported behavior outside that list. A baseline
   defect is not a contract to reproduce; each intended repair needs its own postcondition.
4. Run comparable checks after migration with the same controlled inputs/configuration,
   recording the exact after revision/patch and built/deployed artifacts. Explain required
   configuration or test changes so the comparison remains meaningful. Do not weaken an
   assertion to hide a difference. Include architecture rejection and composition evidence.
5. Classify each difference as an intended repair/change, a demonstrated inherited defect,
   a new regression, or unresolved. New regressions and unresolved differences block
   acceptance. A still-failing inherited defect outside the slice remains open and must
   not undermine its gate; a finding promised by the slice cannot remain failing.
6. Close each finding individually with its canonical owner, removed implementation paths,
   before reproduction, after postcondition and regression check. Record matrix-row status
   and remaining limitations. Moving files, calling an adapter, or obtaining a green
   unrelated suite is not closure evidence.

Keep evidence in a durable repository/CI artifact referenced from the plan or slice review;
temporary `/tmp` logs alone are insufficient for future verification. This protocol applies
to the shared prerequisites as well as Work, Control and other infrastructure migrations.
It does not require a production baseline run for this documentation-only amendment.

## Historical execution and review records

These dated records retain original evidence and decisions. Their next-action and status
wording applies only to the recorded checkpoint. Current scope/order is owned by
"Current execution contract" above; later decisions do not erase earlier evidence.

### Plan review corrections — 2026-09-07

| Review gap | Plan correction | Implementation status |
|---|---|---|
| Work gate depended on CP separation scheduled later | B01 includes full V03 prerequisites: CP/Work factory isolation, no CP Work binding and exact adapter selection with trigger policy; B03 consumes migrated state ports and B05 preserves isolation | Design accepted; B01 execution delivered for independent review |
| Import checks could pass while Spring changes another plane's policy | Source review of customizer/resource scope plus relevant behavior evidence, including non-Rabbit Work startup; no bean-selection tests | Pending review/migration |
| One Work flow could stand in for all variants | Concrete B-slices/V-checks plus 12-pair acceptance matrix in the design | Designed; implementation evidence pending |
| No repeatable before/after comparison per slice | Exact revisions/configurations, intended-delta list, preserved results and finding-specific closure protocol | Required before each migration |

Amendment review: plan outcome covers all four findings while keeping step 2 design review
mandatory; style follows the existing plan structure; conciseness keeps acceptance ownership
in this plan instead of another execution document; security requires scoped capabilities
and redacted evidence without changing authorization contracts; no libraries are added;
maintainability gains traceable row/slice/finding evidence. These corrections close planning
gaps only. They do not close inherited production findings or prove the runtime boundaries.

### B01 correction handoff — 2026-09-08

Separate review found three HIGH issues, recorded in
`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b01/review.md`. The requested corrections and test
evidence are delivered in `docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b01/fixes.md` and
`fixes-evidence.json`. Next: separate review of these corrections. No B01 acceptance,
commit or B02 start follows from execution tests alone.

### B01 correction review — 2026-09-08 (historical; automation remedy withdrawn below)

`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b01/correction-review.md` accepts R1/R2 and retains
R3 as HIGH: ZipFile(String) and Scanner(Path) passed both guards. Its then-proposed
JDK-policy expansion was subsequently withdrawn by the user decision below. B01 was not accepted;
B02 does not start. This review did not implement further fixes.

### User decision — standard tools, no custom heuristic scanners — 2026-09-08

The user explicitly requested complete removal of the custom scanner and confirmed
that off-the-shelf tooling may remain. The source/POM scanner, regex-based design
inventory generator, their tests, shared catch-all JSON policy and catch-all ArchUnit
test were deleted. Former generated snapshots are archived; do not recreate them as
an active semantic/ownership validation workflow. Maven Enforcer and existing focused
architecture/behavior tests remain. Root POM dependency bans are maintained directly.

R3's requested scanner/blacklist expansion is superseded by this decision, not fixed
or evidence that all IO is now mechanically blocked. Its underlying ownership/IO
requirement is a hard separate-review obligation in `docs/REVIEW_RULES.md`.
Next: separate review of the removal and boundary acceptance under those rules.
No B01 acceptance, commit or B02 start is implied. Evidence:
`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b01/scanner-removal.md`.

Final human clarification (2026-09-08): use one simple import scanner across all
Java modules, rather than retaining a ControlPlane-only behavior scanner.
`RepositoryImportBoundaryTest` replaces `ControlPlaneBoundaryArchitectureTest`;
the two non-scanning envelope assertions remain in `ControlPlaneEnvelopeContractTest`.
One inline module/import regex table owns source restrictions. No method-call
classification, parser, generated inventory or additional scanning framework is
authorized. Scope, current migration allowances and limitations are owned by
`docs/REVIEW_RULES.md#sole-source-scanning-test-exception`.

Responsibility/header follow-up (2026-09-08): apply
`docs/ai/RESPONSIBILITY_WORKFLOW.md` to subsequent ownership/boundary work and its
separate review. Architecture owns stable responsibility records; headers reference
them and describe actual code. Review supplies per-responsibility evidence. This
instruction does not claim that the full repository responsibility map or existing
headers have already been migrated, and does not accept B01 or start B02.

Applied to current B01 scope (2026-09-08):
`docs/architecture/runtime-responsibilities.md` now owns 51 current records, referenced
by all 157 production files in the adoption scope. The change is documentation/headers
only and preserves current behavior; remaining migration gaps are explicit. Evidence
and the separate-review handoff are in
`docs/archive/module-boundaries-before-rabbit-2026-09-11/boundary-design/b01/responsibility-adoption.md`. Subsequent work uses
the development workflow and review evidence requirements already linked above.


### B01 acceptance — 2026-09-08

The [separate RV2 correction review](boundary-design/b01/rv2-correction-review.md)
accepts the three corrected responsibility records and four headers. The 157-file
production comparison reproduces the earlier review fingerprint after restoring only
five JavaDoc lines. Current source evidence also confirms the former RV1 composition
paths under the human boundary-verification policy. B01 is accepted in its staged
scope; B02 settings/authoring is next and has not started. Earlier verdicts above are
historical. No commit, deployment or acceptance of later slices is implied.


### B02 first transfers — 2026-09-08

B01 is committed as `eb681ee7`. `work-config` now owns selected IO values and
WorkPatchPolicy, including the capability mutability catalogue. RequestTemplateParser
owns decoded request-template shape/auth/protocol semantics; request-template-files
owns file loading. SDK, Scenario Manager, Request Builder, HTTP Sequence and offline
diagnostics have migrated to those owners. Existing behavior tests moved with them.
See [B02 evidence](boundary-design/b02/README.md). B02 remains incomplete until typed
IO/connection/execution settings, full candidate validation, Redis parser consolidation,
Rabbit environment encoding and all corresponding producers are migrated.

### B02 Rabbit connection export — 2026-09-08

The existing five-field connection contract and environment encoder now live in
`rabbit-config`, without Spring/client dependencies. Both launching services receive
immutable settings from the shared bootstrap decoder; the CP environment factory
delegates encoding. The previous validator/encoder and service RabbitProperties
consumers were removed. [Execution evidence](boundary-design/b02/rabbit-connection-transfer.md)
records tests and limits. The next transfer remains WorkConfigurationParser and its
coupled settings/candidate/producer migration. Full B02 and separate review stay open.

### B02 Rabbit review corrections and Redis routes — 2026-09-08

RB-R1 now distinguishes validated base Rabbit export from the still-open validation
after `bee.env` composition. RB-R2 gives container lifecycle and worker planning their
own current-owner records and primary header references. The inherited final-environment
defect remains a required B02 gate.

RedisConfigurationParser now owns Redis route declarations/validation, with a compiled
read-only route projection for runtime selection. Startup properties, native Redis output,
uploader capture and Scenario Manager delegate to it; old route implementations were
deleted. [Execution evidence](boundary-design/b02/redis-routes-transfer.md) covers the
bounded transfer and its tests. Complete IO/settings/candidate parsing and producer
migration remain open; this does not accept B02 or start B03.

### B02 configuration namespaces — 2026-09-09

The existing work-config artifact now separates `.config.redis` (Redis parsing and
settings), `.config.environment` (environment encoders) and `.config.policy` (patch
policy); shared IO selection and validation reports remain in `.config`.
The Redis-only WorkConfigurationParser was renamed RedisConfigurationParser, and
RedisConnectionEnvironment became RedisConnectionEnvironmentEncoder. All consumers and
existing tests moved to the new names. Full Work candidate parsing remains a separate
B02 obligation and must consume the Redis owner. These namespace moves do not implement
the B03–B07 runtime/adapter artifacts. See [B02 evidence](boundary-design/b02/README.md).

### B02 final connection composition — 2026-09-09

WorkConnectionEnvironmentResolver now validates Rabbit/Redis IO connections after bee.env,
delegating field rules to the existing owners. RedisConnectionEnvironmentEncoder becomes
RedisConnectionEnvironmentCodec: accepted values are projected into both container
environment and bootstrap so bootstrap cannot restore the old connection. Standard Spring
property lookup stays in Controller bootstrap; work-config remains free of Spring/clients.
179 selected implementation tests and the full package build passed. Separate review
blocks this slice on FENV-R1 (lookup/binding disagreement) and FENV-R2 (environment-only
Redis passwords exposed in status/logs); see the latest B02 evidence.
Full IO/settings/execution candidates and remaining producer migration are next B02 work.
This does not start B03 or change the deferred SEL-R1 decision.

### B02 final connection review corrections — 2026-09-09

FENV-R1 now uses Spring Binder name/placeholder resolution during worker planning.
FENV-R2 adds one diagnostic password projection consumed by worker status and the
extracted configuration logger; raw adapter state and applied-config digests are preserved.
133 selected tests and the root package build passed. See the latest B02 evidence;
separate review of the corrections remains pending. Full B02 and SEL-R1 remain open.

### B02 common input rate — 2026-09-09

Checkpoint `7ac51535` commits the reviewed namespaces, connection environment and diagnostic
corrections. Continued B02 with RESP-WORK-INPUT-RATE: `work-config.input.InputRateParser`
replaces local scheduler/CSV/Redis input-rate validation in properties, runtime, patch
policy and authoring. SchedulingState projects accepted rates. Concrete startup, runtime
and authoring values share one rule; symbolic authoring remains deferred.

Implementation verification and limits: [B02 input-rate evidence](boundary-design/b02/README.md#common-input-rate--2026-09-09).
The [separate RATE-R1 correction review](boundary-design/b02/README.md#separate-rate-r1-correction-review--2026-09-09)
closes the missed BufferGuard consumer and accepts the scoped input-rate transfer: 52 tests
and five active-guard transition checks passed. Full settings/candidate acceptance, timing and limits,
other B02 work and deferred SEL-R1 remained open at that checkpoint; B03 had not started then.

### B02 input timing and limits — 2026-09-09

Human accepted the next step and checkpoint: `e17dee90` commits the reviewed input-rate
transfer and RATE-R1 correction. Continue with RESP-WORK-INPUT-SCHEDULE: one owner for
integer timing/limit parsing across startup properties, input consumers, patch policy
and Scenario Manager, including exact long range and CSV seconds conversion. Remove
local validators/clamping; preserve finite-run behavior and live-mutability policy.
Before-state selected suite: 120 tests passed (`/tmp/b02-input-timing-before.log`).
The existing maxPendingTicks property has no execution consumer; do not invent a queue
or backlog implementation in this configuration slice. Tests/evidence hand off to a
human-triggered separate review; full B02 and SEL-R1 remain open.

Execution delivered: [timing/limit evidence](boundary-design/b02/README.md#input-timing-and-limits--2026-09-09).
143 selected tests and the full root package build passed. Existing omission defaults
are now defined centrally; invalid values fail instead of being clamped. Exact long
limits and scheduler-representable durations are enforced. Separate review reran all 143
tests and found MEDIUM TIM-R1: Scheduler reparses maxMessages for every dispatched
message. Keep decoding at configuration boundaries and consume the accepted typed limit.
This slice remains unaccepted; see [review evidence](boundary-design/b02/README.md#separate-input-timinglimit-review--2026-09-09).

TIM-R1 correction implemented: Scheduler owns one accepted runtime long; startup/update
boundaries retain canonical parsing. 90 selected tests passed; an isolated scheduler-loop
probe measures 112.112 allocation bytes/message before and 0 after. Await separate review;
see [correction evidence](boundary-design/b02/README.md#tim-r1-correction--2026-09-09).

### B02 scheduler reset contract — 2026-09-09

The next complete parsing responsibility moves to SchedulerResetParser in work-config.
SDK runtime, patch policy and Scenario Manager share strict boolean validation and
symbolic-authoring handling. Remove local decoders; reject invalid reset before any
rate/limit/counter mutation. 92 tests pass; see [handoff evidence](boundary-design/b02/README.md#scheduler-reset-contract--2026-09-09).
This transfer and TIM-R1 correction await separate review; full B02 remains open.
The human-requested simplification task follows full phase acceptance as recorded above.

Separate review on 2026-09-10 accepts TIM-R1 correction and reset parsing within scope:
no findings, 130 tests passed, fresh scheduler transition/allocation probe passed.
TIM-R1 is closed; full B02 and deferred SEL-R1 remain open.
See [review evidence](boundary-design/b02/README.md#separate-tim-r1-and-reset-review--2026-09-10).

### B02 input enablement ownership — 2026-09-10

Human-requested checkpoint `8e99c673` commits the accepted input timing/limit and reset
transfers, including TIM-R1. The next slice removes input-local enablement settings and
the unused Rabbit autoStartup property. RESP-WORK-STATE remains the sole enablement
owner; Redis startup consumes its current snapshot. RESP-WORK-INPUT-LIFECYCLE-POLICY
owns rejection of removed controls at authoring, patch, startup and worker-planning
boundaries. Controller CSV export and affected test producers are migrated.

Implementation delivered, awaiting separate review: **173 selected tests and the full
root Maven package build pass**. Before-state suite: 159 tests passed. Evidence:
[input enablement handoff](boundary-design/b02/README.md#input-enablement-ownership--2026-09-10).
Remaining B02: complete typed settings/candidate validation, startup-shape and producer
parity (including bee.env authoring), and the other named B02 transfers. SEL-R1 remains
explicitly deferred. At that checkpoint B03 had not started; the current narrow B03a
status is recorded at the top of this plan. Simplification follows full B02 acceptance.

Separate review reran 173 tests and reproduced ENBL-R1 with real Spring YAML binding.
Correct the removed-field presence lookup and extend its existing behavior test before
accepting this slice; other B02 gates and SEL-R1 retain their existing scope.

ENBL-R1 correction delivered: Spring property/descendant presence replaces Object value
binding, with the existing binder test extended. 173 tests pass; await separate review.

Separate ENBL-R1 correction review accepted the input enablement transfer within scope.
Next implementation: complete CSV settings ownership in work-config, migrating startup,
runtime, Scenario Manager and Controller export together; no B03 work starts yet.
