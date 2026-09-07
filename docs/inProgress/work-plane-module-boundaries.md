# Enforced module boundaries — Work Plane first

Status: active; step 1 documentation prepared, memory cleanup partially blocked by missing lifecycle tools.
Plan review: four planning gaps addressed on 2026-09-07; concrete design review in step 2 remains required.
Owner: PocketHive architecture work on `refactor/control-plane-critical-restart`.
Checkpoint: `19d56091` (2026-09-07), committed before this plan and documentation cleanup.

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
The current turn authorizes the checkpoint, plan, documentation/review-rule preparation,
and PocketHive memory cleanup. It does not require starting the production migrations below.

Existing runtime contracts remain authoritative until their relevant contract-first change
is reviewed. Proposed module names below are planning names, not already delivered APIs.
The previous sink-splitting phase remains accepted within its scope; its inherited debt
does not make those extractions regressions.

## Ownership and dependency direction

| Boundary | Owns | Must not own |
|---|---|---|
| Work contracts/configuration | Work envelope, adapter-specific settings contracts, canonical parsing/validation and explicit patch semantics | Broker connections, service lifecycle, duplicated client-side validators |
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
POM entries. A core build must fail when code imports a forbidden client. Architecture
tests must additionally reject forbidden module edges, implementation references,
service-local provisioning, and newly exposed infrastructure capabilities.

Build isolation and runtime composition isolation are separate acceptance conditions.
The mandatory composition checks below must prove that adapters cannot change another
plane's effective configuration or delivery policy through global Spring customizers,
bean discovery, shared mutable factories, or implicit default selection.

Separate repositories are optional packaging after these boundaries are proven. If used,
consumers depend on versioned artifacts; they must not need coordinated source edits for
an ordinary consumer change. Package names alone are not enforcement. These are build
and review constraints, not a security sandbox against code that can modify the build itself.

## Execution steps

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
The current input/output enum owners are `common/worker-sdk/.../config/WorkerInputType.java`
and `WorkerOutputType.java`; contract changes update their authoritative definitions first.
All rows below are pending concrete design and implementation. Step 2 must replace the
responsibility-level owners with exact artifacts/types and add slice IDs, consumer lists,
commands/test IDs and evidence links. Extend the inventory for additional active paths
found by repository-wide searches; do not treat this initial list as exhaustive discovery.

| Variant / responsibility | Planned owner | Required stage | Minimum acceptance evidence |
|---|---|---|---|
| Worker identity, config/status/lifecycle integration | Canonical integration contracts; Control adapter implements its side | 3, prerequisite | No transitive infrastructure types in Work cores; existing config/status flow works with CP present |
| Input `RABBITMQ` | Rabbit Work consumer adapter; Work runtime owns execution policy | 3–4 | Effective listener settings, delivery/acknowledgement, errors/redelivery, shutdown and reconfiguration |
| Input `REDIS_DATASET` | Redis dataset adapter; canonical dataset settings | 3–4 | Dataset source validation, ordering/exhaustion, configured rate and explicit failure; no Rabbit Work settings required |
| Input `CSV_DATASET` | CSV/filesystem input adapter; canonical dataset/path settings | 3–4 | Parsing, ordering/exhaustion, configured rate, invalid data/path failure and lifecycle |
| Input `SCHEDULER` | Scheduler input adapter; Work runtime owns execution | 3–4 | Configured triggering, stop/reconfigure behavior and no post-stop work beyond the declared shutdown contract |
| Output `RABBITMQ` | Rabbit Work publisher adapter | 3–4 | Destination, persistence, supported confirmation/timeout semantics and explicit publish failure |
| Output `REDIS` | Redis output adapter; canonical sink contract | 3–4 | Target/list semantics, serialization, failure and close behavior; include `RedisPushSupport` consumers |
| Output `NONE` | Explicit no-output implementation | 3–4 | No downstream publish or output-only connection/settings requirement; worker execution/status still work |
| Redis sequences used by templating | Sequence capability owner and Redis sequence adapter | 3–4 | Existing sequence/concurrency semantics and explicit connection configuration; no Lettuce leak through templating |
| Redis auth token storage used by workers | Auth token-store capability owner and its Redis adapter | 3–4 boundary extraction; broader auth policy in 5 | Preserve token scope, expiry and atomic operations where contracted; no client leak into Work/auth consumer cores |
| Redis debug capture in HTTP sequence workers | Diagnostic capture owner and Redis capture adapter | 3–4 | Capture scope, configured retention and explicit failure semantics; HTTP runner does not own a Redis client |
| Work topology, naming, observation, diagnostics and removal | Work topology owner, scoped adapters and owner-derived projections | 3–4 | Provision/listen/status/tap/remove agree for non-default names; verify removal rather than attempted deletion |
| Work settings authoring, patches, UI and tools | Canonical Work/adapter validators; generated or owner-derived consumers | 3–4 | Authoring/runtime decisions agree; no copied normalization/defaults; invalid patches have canonical outcomes |
| Remaining CP, compute, journal/database, network proxy and auth policies | Their canonical domain owners and scoped infrastructure adapters | 5 | Complete service-core isolation and corresponding state/postcondition checks; enumerate concrete slices in step 2 |

Test all supported input/output combinations used by current workers/scenarios and contract
boundaries affected by the slice. Record unsupported combinations and their canonical
rejection evidence; do not infer that the Cartesian product is supported. Each supported
variant needs acceptance evidence even if no existing end-to-end scenario exercises it.
Combine focused adapter/composition tests with representative official-ingress flows.
Removal of a supported variant requires an explicit contract/scope decision, not omission
from tests. Any proposed deferral must state the retained behavior, owner and destination
slice and prove it is not required by an earlier gate; required Work rows block step 4.

## Mandatory runtime composition checks

Run the relevant checks for each affected slice with Work and Control adapters loaded
together in the actual application composition. Isolated adapter mocks alone do not prove
composition isolation. Use focused Spring composition tests for effective wiring and
official-ingress acceptance for externally observable delivery/lifecycle behavior.

- Give Work and Control deliberately different valid listener/delivery settings. Inspect
  the effective factories/containers/templates and exercise relevant failure behavior.
  Change Work settings and prove Control policy is unchanged; repeat in the other direction.
- Verify explicit ownership/scope of customizers and mutable client resources. In particular,
  `ControlPlaneRabbitPoisonMessageCustomizer` must not install CP error policy on Work
  factories. Sharing a low-level driver is allowed only with proven policy isolation.
- Start non-Rabbit Work input with output `NONE` and valid Control configuration, without
  Rabbit Work settings. Control may still use Rabbit; it must not demand a dummy Work
  exchange, bind Work configuration, or activate an unselected Work adapter.
- Verify missing/ambiguous required adapter wiring fails explicitly, without automatic
  alternative selection. Publish-only consumers must not receive provisioning/deletion
  capabilities, including through auto-configuration or service-locator access.
- Exercise startup, shutdown and reconfiguration for the affected capabilities. Assert
  observable policy/effects, not only bean existence or invocation counts. A failing
  composition case blocks the slice even when dependency and architecture checks pass.

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

Apply the six review passes in `docs/REVIEW_RULES.md` before accepting each slice. Include
negative cases from the audits: duplicate Redis sources, incomplete template fields,
unknown network modes, non-default/colliding prefixes, cross-swarm paths, delete attempts
with resources still present, and operation chronology across clock changes.

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

### Plan review corrections — 2026-09-07

| Review gap | Plan correction | Implementation status |
|---|---|---|
| Work gate depended on CP separation scheduled later | Step 2 identifies transitive prerequisites; step 3 extracts canonical integration contracts and isolates composition before affected Work slices | Pending design/migration |
| Import checks could pass while Spring changes another plane's policy | Mandatory joint-composition acceptance, effective-policy checks and non-Rabbit Work startup case | Pending tests/migration |
| One Work flow could stand in for all variants | Coverage matrix, all supported variant/combination dispositions and row-specific evidence required by step 4 | Pending concrete slice assignments |
| No repeatable before/after comparison per slice | Exact revisions/configurations, intended-delta list, preserved results and finding-specific closure protocol | Required before each migration |

Amendment review: plan outcome covers all four findings while keeping step 2 design review
mandatory; style follows the existing plan structure; conciseness keeps acceptance ownership
in this plan instead of another execution document; security requires scoped capabilities
and redacted evidence without changing authorization contracts; no libraries are added;
maintainability gains traceable row/slice/finding evidence. These corrections close planning
gaps only. They do not close inherited production findings or prove the runtime boundaries.
