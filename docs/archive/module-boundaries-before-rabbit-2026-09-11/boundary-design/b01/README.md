# B01 execution evidence

Status: R1/R2 accepted; the user withdrew the heuristic scanner approach.
The later single import-test decision is in `scanner-removal.md`. Current adoption
of architecture IDs and headers is recorded in `responsibility-adoption.md`:
157 production files, 51 current records. The [separate correction review](rv2-correction-review.md)
accepts RV2 and B01. RV1's wiring-test requirement remains superseded by the human
boundary-verification policy. B02 settings/authoring is next and has not started.
Removed artificial factory/bean-identity cases; see the policy implementation note
in `review-responsibilities.md`. The latest human-approved focused selection contains 8 cases
(import rules, one Scheduler/NONE startup regression, sequence behavior and scheduling
regressions); see its pragmatic test reduction note. Test names/counts below are historical execution evidence.
Removal and mandatory review requirements are recorded in `scanner-removal.md`.
R3's proposed scanner expansion is superseded; the separate correction review records
the bounded ownership acceptance and remaining migration scope. Reports and commands below preserve historical execution evidence.

Implementation/test execution is separate from review. Acceptance comes from the linked
separate review; no self-review, commit, push or deployment is claimed here.

Scope: B01 shared prerequisites and Work/Control composition isolation from
`docs/architecture/work-plane-boundaries.md`. The user authorized continuation after
separate design review accepted R1–R3 and the LOW evidence wording was corrected.

## Before

Source checkpoint: `e0d378711b6447f8bb3e0f8bbf787f9b06c2b5bb`; production was unchanged
before the baseline run. `before-tests.json` records its command and log digest:
967 tests, zero failures/errors, one pre-existing skipped Redis placeholder, BUILD SUCCESS.
The first count (752) omitted the WARNING-labelled SDK summary; the corrected report
includes its 215 tests and one skip. This is test evidence, not a certified stable revision.
`before-source-inventory.json` and `before-module-graph.json` preserve the design checkpoint.

Two pre-change composition tests failed for the intended reasons: CP worker declarations
required `pockethive.outputs.rabbit.exchange` for Scheduler/NONE, and CP customization
replaced the sentinel Work error handler. Both now pass. The startup fixture was completed
with the existing required `scheduler.maxMessages=0` once the first blocker was removed.

## Implemented differences

| Responsibility | Sole current owner / result |
|---|---|
| Worker annotation, capabilities, WorkItem/codec/schema/transport DTOs, worker context/status contracts | `common/work-api`, `io.pockethive.work.api`; public nested contracts/builders extracted; old SDK definitions deleted |
| Context, hop, status-envelope and identity utilities | `common/observability-core`; original packages retained; exporters/Spring remain in `observability` |
| Auth/token values, `TokenStore`, storage/refresh profile values | `common/auth-contracts`; request-templates no longer depends on worker-sdk |
| Rendering, syntax and sequence contracts | `common/templating-api`; the existing Pebble/SpEL implementation receives `SequenceAccess`; engine errors cross the boundary as `TemplateRenderingException` |
| CP AMQP publisher | `common/control-plane-spring`; CP core no longer has Spring AMQP/exporter dependencies; its AMQP-specific tests follow the publisher |
| CP listener policy and declarations | Dedicated `controlPlaneRabbitListenerContainerFactory`; all CP listeners select it; CP worker composition/declarable factory reads no Work queue/exchange settings |
| Work listener policy and Rabbit destination | Work-only executor customizer owns shutdown; CP customization cannot replace Work errors/executor; Rabbit output captures immutable exchange/routing/delivery settings |
| IO implementation selection | Exactly one matching factory; missing/duplicate inputs and outputs fail startup; only selected adapter factories participate; generic NONE factory cannot silently disappear behind an unrelated factory |
| Scheduler business policy | Work API `ScheduledInvocationPolicy` and read-only `SchedulingState`; generic rate policy in SDK; Trigger boot supplies its interval/single-request policy without role-based factory selection |
| Test fixture ownership | `common/work-test-fixtures`, consumed with test scope only; no fixture implementation remains in SDK main sources |

ControlPlaneTestFixtures constructs Spring properties/descriptors, so its test-only
artifact explicitly depends on CP Spring as well as CP core and Work API. The target
graph records this. CP Spring's wire-shape test consumes the existing CP core schema
fixture through a test-scoped test JAR; no validator was copied.

## Validation

`after-tests.json` records the final commands, result totals and log digests.

- Clean tests covering all nine worker services plus Scenario Manager, Controller and
  Orchestrator: **1197 tests, zero failures/errors, one pre-existing skip**.
- `WorkControlCompositionTest`: 9 cases against actual SDK composition. Scheduler/CSV/
  Redis dataset + NONE start with CP declarations and no Rabbit Work settings; selected
  inputs actually enter their running state; missing/duplicate adapter wiring fails;
  stopping Work preserves CP factory/publisher availability.
- `WorkControlFactoryPolicyTest`: different CP/Work error handlers and executors;
  changes and executor shutdown are checked in both directions.
- `SequencePortRenderingTest`: arguments/default start/max/reset reach the injected
  owner; renderer instances select independent ports; syntax validation executes no
  sequence effects; explicitly disabled rendering fails through the API exception.
- Rate/Trigger policy tests preserve fractional carry-over, disable/reset, interval,
  single-request and revision behavior, including a negative monotonic clock origin.
- Existing CP-N01 topology ownership tests remain in the reactor.
- Maven packaging compiles all repository production/test consumers, including product
  MCP, E2E fixtures and scenario-templating-check. Packaging skips execution of suites
  outside the selected runtime test reactor.
- Source/POM policy: `python3 tools/check-work-boundaries.py`. Permanent negative
  fixtures (11 tests) use the same gate; they include direct and transitive clients, service
  adapters, command capabilities, context lookup, local resource naming, diagnostic
  exchange assertions, fixture scope, deleted owners, missing scans, nested contracts
  and disabling Enforcer. The external-transitive case also runs real Maven Enforcer
  against a temporary reactor and requires its specific banned-dependency failure.
- Compiled ArchUnit checks import every recorded core/API owner and reject generic
  client signatures and context lookup. Enforcer runs at `validate`, transitively,
  for the five migrated core/API modules. Parent exclusions are a checked generated
  projection of `tools/work-boundaries.json`, not a second policy owner.
- The design graph's four tests (13 negative dependency fixtures), documentation build
  and `git diff --check` are recorded alongside the execution checks.

The final standalone API/core check was rerun after making the public Jackson
dependency explicit and ordering test dependencies after production dependencies.

Effective compile dependency trees for migrated modules are under `dependencies/`.
The policy records exact remaining driver source/import pairs and diagnostic provisioning
calls; future slices remove those entries as ownership moves. No wildcard common/infra
exemption is used.

## Remaining planned work and limits

B02 still owns configuration parsing/patch consolidation, including Redis output raw
updates and the input-local enabled fields. B03 owns migration of the current sole CP
worker-state owner and intake/completion/lifecycle semantics. Existing Redis sequence
configuration/generator globals remain behind `ConfiguredRedisSequenceAccess` until B06;
this slice does not claim their removal. Worker/service packaging and remaining direct
client paths follow B04–B07/C01–C03 in the accepted design.

The composition evidence covers in-process startup, policy and executor ownership; it
is not a deployed-stack/official-ingress acceptance run. No swarm was deployed and no
HiveMind learning was marked fixed from these local checks. `SwarmLifecycleSteps` was
not extended. Separate review is the next task; B02 must not start on the basis of a
self-review or an automatic review/fix loop.
