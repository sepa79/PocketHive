# B02 settings and authoring execution

Status: implementation started, not accepted. B01 was committed as `eb681ee7` on
2026-09-08 after separate review. This task does not run an automatic acceptance review.
Transfers through Redis connection parsing and CONN-R1 were committed on human request
as `63597068` on 2026-09-09. The connection environment transfer below follows that checkpoint.
The namespace move renamed WorkConfigurationParser to RedisConfigurationParser.
The latest connection-composition slice extends the environment encoder into
RedisConnectionEnvironmentCodec and adds WorkConnectionEnvironmentResolver; it is
implemented, tested and accepted within its scope by the reviews below. Earlier evidence retains the names
used at its recorded revision; current ownership is documented in architecture.
The separate correction review accepts FENV-R2 diagnostic masking within scope.
The follow-up FENV-R1 correction composes and freezes the complete environment before
binding. Its separate review below accepts the correction within the connection-composition
scope; committed on human request as `7ac51535` on 2026-09-09.
Separate review found RATE-R1 in the subsequent input-rate transfer: the Controller
BufferGuard decoder was omitted. The separate correction review below closes RATE-R1
and accepts the input-rate transfer within its stated scope; remaining B02
settings/candidate parsing is still open.
Checkpoint `e17dee90` commits that reviewed transfer on human approval. The following
input timing/limit transfer's TIM-R1 correction and the subsequent scheduler-reset
transfer passed separate review on 2026-09-10: no new findings, 130 tests passed.
TIM-R1 is closed; timing/limit and reset parsing are accepted within their stated scope.
Full B02 remains open. After full B02 acceptance, the execution plan requires
a separate simplification task across the completed phase before B03.

Separate [review on 2026-09-08](review-2026-09-08.md) found HIGH R1: template loading
lost the auth failure classification used by worker error handling. The
[R1 correction](r1-correction.md) is implemented and behaviorally verified, pending
separate review. The remaining B02 work is still open.

The accepted scope is B02/V04/V05 in `docs/architecture/work-plane-boundaries.md`.
Implementation proceeds by complete responsibilities, migrating consumers and existing
behavior tests together and removing old owners in each step:

1. `work-config`: move IO selection types and consolidate live mutation classification
   and update validation in WorkPatchPolicy. SDK applies it; capabilities projects it.
2. RequestTemplateParser owns decoded template shape/protocol/auth validation;
   request-template-files owns filesystem loading. Runtime and Scenario Manager consume
   the parser; bundle references remain Scenario Manager's concern.
3. Typed IO/connection/execution settings, shared parsing for startup/authoring/runtime,
   canonical candidate validation and all configuration producers migrate together.
   Remove duplicate Redis/settings validators and update shared Rabbit environment export.

These steps do not waive any B02 requirement. Completion and V04/V05 acceptance require
all three; no first-step test result is a claim that all settings have been consolidated.
Boundary verification uses documentation, the single import test and separate review
with source evidence. Unit tests move with behavior; component tests cover useful flows.

Before revision: `eb681ee7` (clean working tree).
Before command:

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=LiveIoConfigUpdateGuardTest,WorkIOConfigBinderTest,CapabilityCatalogueServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Log: `/tmp/b02-config-before.log`. Subsequent evidence records each migrated owner and
actual test results, without claiming review acceptance or runtime deployment.

## Implemented transfers

| Responsibility | Current owner and consumers | Removed implementation |
|---|---|---|
| IO patch policy and selection values | `common/work-config`: WorkPatchPolicy, WorkerInputType, WorkerOutputType; SDK runtime/binders/factories and Scenario Manager capability catalogue consume them | SDK LiveIoConfigUpdateGuard and IO enums; scenario-validation-contracts LiveIoConfigMutability |
| Decoded request-template semantics | `common/request-templates`: RequestTemplateParser; workers load it through the file adapter; Scenario Manager delegates through RequestTemplateFindings | ScenarioBundleValidator.validateRequestTemplateShape and inline authRef shape/conversion; offline ScenarioTemplateValidator.parseHttpTemplate |
| Template filesystem loading | `common/request-template-files`: TemplateLoader and LoadedTemplate; request-builder, HTTP-sequence and offline diagnostics | Old request-templates TemplateLoader; offline loader enumeration/decoding |
| Rabbit connection container export | `common/rabbit-config`: immutable RabbitConnectionSettings and RabbitConnectionEnvironment; shared Spring decoder imported by both launching services | ControlPlaneContainerEnvironmentFactory.populateRabbitEnv/requireRabbitPort; RabbitProperties in lifecycle/spec consumers |

The architecture records are RESP-WORK-PATCH-POLICY, RESP-WORK-IO-CONFIG,
RESP-REQUEST-TEMPLATE-PARSE and RESP-SCENARIO-VALIDATE in
[runtime responsibilities](../../../architecture/runtime-responsibilities.md).
Headers refer to those records. The existing import test includes work-config and
request-templates; existing Maven Enforcer rules prohibit infrastructure there and
prohibit request-template-files in core modules. No new boundary scanner was added.

All old Java imports and API consumers were migrated, including test fixtures and the
offline diagnostic. WorkPatchPolicy no longer needs SDK WorkerDefinition. Binders obtain
the selected settings key from the shared enum. This does not transfer their remaining
settings validation or the adapters' raw update parsing.

## Behavioral changes and preservation

- Existing live-mutability decisions are preserved: endpoint/adapter changes require
  rematerialization; operational values retain their constraints; Redis listName changes
  require a disabled worker already using a single source. Missing policy construction
  inputs now fail explicitly.
- Runtime templates now require explicit serviceId, callId and protocol, matching the
  existing authoring requirement. HTTP requires method/pathTemplate. HTTP-sequence test
  fixtures were updated to declare serviceId; no serviceId fallback remains in the loader.
- Missing template roots, duplicate template identities and duplicate YAML/JSON keys
  now fail explicitly. Unknown runtime fields fail. The documented HTTP/TCP schemaRef
  authoring hint remains allowed; ISO8583 retains its distinct typed schema reference.
- AuthRef/AuthApplyAs remain the auth value owners. RequestTemplateFindings maps shared
  parser problems to existing bundle diagnostic codes/paths and leaves profile existence
  to the bundle validator. The different DB query template loader/API was not changed.

## Executed verification

The before revision is `eb681ee7`. The initial after revision is that commit plus the
uncommitted B02 changes before the R1 correction. [Verification output](verification.txt) preserves the result summaries;
[source fingerprint](source-changes.sha256) records every changed Java/POM source,
including deletions and new modules. It covers code/test/dependency changes, not this
evidence document or generated build output.

| Check | Result |
|---|---|
| Before: LiveIoConfigUpdateGuardTest, WorkIOConfigBinderTest, CapabilityCatalogueServiceTest | 56 passed |
| Before: request-builder TemplateLoaderTest | 7 passed |
| Before: ScenarioRepositoryValidationTest | 1 passed |
| After: focused suite below | 216 passed; no failures, errors or skips |
| Whole root reactor `./mvnw -B -ntp -DskipTests package` | Passed; production and test compilation, tests skipped |
| Documentation `npm --prefix docs-site run build` | Passed after correcting the historical audit's moved-source link |
| `git diff --check` | Passed |

After command:

```bash
./mvnw -B -ntp \
  -pl common/worker-sdk,trigger-service,request-builder-service,http-sequence-service,scenario-manager-service,tools/scenario-templating-check -am \
  -Dtest=WorkPatchPolicyTest,WorkIOConfigBinderTest,WorkerControlPlaneRuntimeTest,CapabilityCatalogueServiceTest,RequestTemplateParserTest,TemplateLoaderTest,RequestTemplateFindingsTest,RequestBuilderWorkerImplTest,HttpSequenceRunnerTest,ScenarioRepositoryValidationTest,ScenarioControllerTest,RepositoryImportBoundaryTest,WorkControlCompositionTest,RateSchedulePolicyTest,TriggerSchedulePolicyTest,TriggerSchedulerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Fifteen patch-policy tests and seven loader tests moved with their owners. New behavioral
cases cover pure parsing/auth errors, diagnostic projection, missing roots and duplicate
template identities. The other tests exercise existing consumers, config-update behavior,
scenario validation, trigger scheduling and non-Rabbit/NONE startup. No tests were added
to assert module/bean choice or private factory identity. These are local tests, with no
deployed-stack or ingress acceptance claim. V05 has implementation evidence for separate
review; V04 and B02 as a whole remain incomplete.

## Remaining B02 work

The independent Rabbit connection/container export transfer is implemented and verified
in [its execution evidence](rabbit-connection-transfer.md).
The [separate Rabbit review](rabbit-connection-review-2026-09-08.md) found no new runtime
regression, but requires RB-R1/RB-R2 architecture/header corrections before acceptance.
Those corrections now distinguish base export from the open final-environment gate and
give lifecycle/worker planning their own current-owner records and primary header links.
Implementation verification is separate from acceptance review.
It covers the existing five-field export and both launch paths. This is a subdivision
of B02, not acceptance or a change to B02's final requirements.

The first WorkConfigurationParser sub-transfer, [Redis route parsing](redis-routes-transfer.md),
is implemented and tested: startup, native output, capture and Scenario Manager share
its route rules; the old route DTO, mapper and semantic validators were removed.
AUTHORING reports deferred symbolic constraints as warnings; RESOLVED rejects unrendered
values. This covers route declarations only, not complete Redis or Work configuration.
The [separate Redis review](redis-routes-review-2026-09-08.md) accepts RB-R1/RB-R2
corrections, but requires RR-R1–RR-R3 fixes for symbolic-route ownership, startup scalar
coercion and deferred co-constraints before accepting this route transfer.
Those [corrections are implemented](redis-routes-corrections.md) and accepted by the
[separate correction review](redis-routes-corrections-review-2026-09-08.md), which reran
162 behavior/import tests. It also reproduced inherited loss of nested optional YAML
route fields during startup binding. The [narrowed correction](yaml-decoding-correction.md)
retains only the binder fix for unrepresentable descendants. The global YAML loader was
withdrawn by human decision; empty `{}`/`[]` fields remain open for the target B02 decoder.
Complete startup/raw parity and B02 are not accepted. Earlier review findings are preserved.

The [Redis dataset source transfer](redis-sources-transfer.md) consolidates source-entry
names/weights and collection validation in work-config. Startup binding, raw input updates
and Scenario Manager consume it. Source-mode selection is covered by the subsequent
transfer below; the remaining settings stay open.
The [separate source review](redis-sources-review-2026-09-08.md) requires RS-R1/RS-R2:
the new explicit-null behavior can clear valid sources before candidate validation,
and NUL-only names escape canonical field diagnostics after trimming. The ownership
transfer is supported by source evidence. Both [corrections are implemented](redis-sources-transfer.md#rs-r1rs-r2-corrections):
explicit null is rejected and names are validated after trimming. The
[separate selection review](redis-selection-review-2026-09-08.md) accepts both corrections;
the remaining B02 work stays open and earlier findings are preserved.

The [dataset source-mode transfer](redis-selection-transfer.md) now consolidates the
single-list/multi-source choice across startup, input, Scenario Manager and requested-name/prior-mode
checks in WorkPatchPolicy. Raw input changes validate their resulting selection before
mutation and no longer implicitly clear the other mode. The separate review requires
SEL-R1 HIGH: an in-progress tick retains the previous list after STOP/update/START.
The user deferred SEL-R1 as a [known issue](known-issues.md) on 2026-09-08 and explicitly
authorized continuing the plan; changing the list is not a normal workflow operation.
This transfer does not complete the Control Plane candidate/acknowledgement gate.

The [Redis output-target transfer](redis-output-target-transfer.md) consolidates destination
type/normalization/presence rules across startup, native output, diagnostic capture and
Scenario Manager. Raw updates validate the merged targets before replacing the active
request. Message-time target templates remain supported. Separate review is pending.

The [Redis write-settings transfer](redis-write-settings-transfer.md) consolidates
sourceStep, pushDirection and maxLen parsing in work-config. Startup, native output,
enabled diagnostic capture and native-output authoring consume it. RedisPushSupport
receives immutable resolved settings; its nested enum decoders and the consumers'
maxLen validators were removed. The [separate write-settings review](redis-write-settings-review-2026-09-08.md)
found no new blockers in this scoped transfer and reran 210 tests. Full capture authoring
remains in the unfinished settings/producer migration.

### Redis connection transfer — 2026-09-09

Implemented; review found CONN-R1 (HIGH), fixed and behavior-tested below; separate fix review pending. Owner: RESP-REDIS-CONNECTION-SETTINGS in
`docs/architecture/runtime-responsibilities.md`. WorkConfigurationParser owns host,
port, username/password and SSL parsing/merging. RedisConnectionSettings replaces
the push/sequence nested ConnectionConfig types. RedisConnectionProperties replaces
three copies of binding fields. Dataset/output/uploader, sequence configuration,
token store and HTTP-sequence capture consume resolved settings; Scenario Manager
delegates selected IO connection diagnostics and their catalogue checks.

Removed local port/SSL/credential decoders, sequence port clamping/invalid-value
substitution and token-store host/port defaults. Password whitespace/explicit empty
passwords are preserved; username requires an explicit password. The generator cache
uses complete immutable settings rather than a partial string/auth-hash key.
Existing sequence defaults/global state, token/sequence scope composition, full
capture/token/sequence authoring and all producer/candidate gates remain open.

Before: `eb681ee7` plus B02 through the write-settings review; 231 selected tests passed.
After: 241 distinct tests passed across the focused run/reruns (same ten suites plus
RedisConnectionSettingsTest and RedisSequenceConfigurationTest). Corrections were an
Object getter assertion, migrated exception/message expectations and quoting a YAML
`yes` fixture so it remains text. No new scanner or permanent wiring tests were added.
Commands used `./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service,http-sequence-service -am`
with the selected test classes recorded in `/tmp/b02-connection-before-tests.log`,
`/tmp/b02-connection-tests.log`, `/tmp/b02-connection-scenario-tests.log` and
`/tmp/b02-connection-binder-tests.log`. New tests cover parsing/credentials, raw binding,
failed sequence-update retention and authoring findings. Redis token IO was not exercised.
Repository-wide search: `RedisConnectionSettings|parseRedisConnection|mergeRedisConnection|validateRedisConnection|connectionSettings`,
plus old ConnectionConfig/port/SSL decoders; `/tmp/b02-connection-owners.txt` lists consumers.
RedisURI/client construction remains in existing adapters for B06. Root `-DskipTests package`
and `npm --prefix docs-site run build` passed; logs `/tmp/b02-connection-package.log` and
`/tmp/b02-connection-docs.log`. `git diff --check` passed. Relative to this step's starting
tree: production Java -241 lines, tests +147; no extra third-party dependency.
SEL-R1 stays user-deferred. No commit, deployment or automatic review.

#### Separate connection review — 2026-09-09

Scope: uncommitted connection transfer above, on `eb681ee7` plus prior B02 slices;
contract RESP-REDIS-CONNECTION-SETTINGS. **CONN-R1 — HIGH:** the newly throwing
`RedisSequenceConfiguration.configureFromWorkerConfig` (lines 26–33) runs after
`WorkerControlPlaneRuntime` writes typed/raw config and enablement (lines 454–456).
Through `runtime.handle` and the canonical signal codec/routing, start with disabled,
rate=1, Redis port=6379; send enabled=true, rate=99, redis.port=0. One failure event is
emitted, but enabled=true, rate=99 and port=0 remain in worker state, while the sequence
connection stays at 6379. A subsequent rate=4 patch fails again against persisted port=0.
The catch path also notifies state listeners. Rejecting the command therefore does not
retain the accepted state and can activate work after reporting failure.

Attribution: late application order predates this step; the new exception path exposes
it for invalid Redis settings (the old implementation clamped/substituted them).
Keep rejection; parse the complete candidate before changing any accepted worker state
or sequence configuration, then apply the resolved result. The existing sequence unit
test proves only generator-config retention, not command atomicity. Reproduction:
`/tmp/b02-connection-review/RuntimeProbe.java` and `runtime-probe.log`; temporary probe
reuses the existing runtime test fixture and sends real decoded control signals in process.

SSOT search: repository-wide `RedisConnection|connectionSettings|mergeRedisConnection|parseRedisConnection|RedisURI|clampPort`
and Redis host/port/credential/SSL names, constants and generic export helpers; inspected
SDK binders → shared parser → dataset/output/uploader, sequence, auth token and HTTP capture
consumers, plus ScenarioBundleValidator → WorkConfigurationFindings → AUTHORING parser.
Headers reference the shared owner; client construction remains in existing adapters.
Search results: `/tmp/b02-connection-review-owners.txt` and `b02-connection-review-config.txt`.
Inherited producer gap at review time (addressed by the later environment transfer below):
SwarmWorkerSpecFactory.putEnvIfPresent trims/drops
blank passwords. Its migration and sequence defaults/global scope remain unfinished;
the shared parser's credential guarantee is not yet an end-to-end deployment guarantee.

Six passes: plan **blocked** by CONN-R1; style/header and boundary direction **supported
for this extraction** (existing mixed adapter files remain debt); conciseness **supported**
by removal of local decoders and shared carrier/value; security **supported at the parser**
(redacted diagnostics, exact password), with producer/global-scope gaps above; library
**supported** (only existing internal work-config dependency); readability **blocked** by
the caller's misleading failure/state semantics, despite clear typed connection values.

Verification: **241/241 tests passed** with the same selected suites using `clean test`,
including the single repository import test; `/tmp/b02-connection-review-clean-tests.log`.
The initial run hit stale compiled HTTP classes (`Unresolved compilation problems`);
clean compilation resolved all nine errors without source changes. An in-process Spring
system-environment binding probe passed for all three properties types, including empty
and whitespace passwords; `/tmp/b02-connection-review/EnvironmentProbe.java` and
`environment-probe.log`. No Redis network/deployed acceptance was exercised. No production
fix, permanent test, commit or deployment. SEL-R1 remains user-deferred; B02 remains open.

#### CONN-R1 fix — 2026-09-09

WorkerControlPlaneRuntime now prepares typed/private configuration before invoking
RedisSequenceConfiguration, which validates before applying the connection. Private/raw/
typed state, enablement and reseeding are changed only after those checks pass. The fix
reorders the existing flow and adds a local private-config candidate; no new parser or
production type. Invalid Redis settings retain the accepted state exposed to listeners.
One regression test in WorkerControlPlaneRuntimeTest covers rejection, private config,
reseeding, unchanged connection and a successful subsequent patch. It failed before the
fix (`expected false but was true`), then passed with all **36** selected tests:
WorkerControlPlaneRuntimeTest, RedisSequenceConfigurationTest, RedisConnectionSettingsTest
and RepositoryImportBoundaryTest. Command: `./mvnw -B -ntp -pl common/worker-sdk -am`
with those `-Dtest` selectors and `-Dsurefire.failIfNoSpecifiedTests=false test`;
logs `/tmp/b02-conn-r1-before.log` and `/tmp/b02-conn-r1-tests.log`. `git diff --check`
passed. No automatic review, commit or deployed acceptance; remaining B02 gaps are unchanged.

### Redis connection environment transfer — 2026-09-09

RedisConnectionEnvironment in work-config owns the input/output connection environment
mapping under RESP-REDIS-CONNECTION-SETTINGS. SwarmWorkerSpecFactory delegates to the
shared parser and encoder; its ten local connection exports were removed. Passwords keep
whitespace and explicit empty values; only null credentials are omitted. Port and SSL
use canonical text. Missing/invalid declared connection fields fail before returning a
worker plan. Other Work fields, adapter selection, final bee.env overrides and separate
sequence/token/capture scopes remain unfinished B02 work.

Before: `63597068`, 86 focused tests passed. The new credential regression also ran
against SwarmWorkerSpecFactory from that commit and failed: `" secret "` became `"secret"`.
After: 90 focused tests passed, plus ScenarioRepositoryValidationTest covering packaged
scenario bundles. The existing binder password test now consumes generated environment
values for both IO directions and verifies whitespace/empty passwords. Two lifecycle
fixtures now declare required `ssl: false`; production samples already declare it.
Commands: `./mvnw -B -ntp -pl common/worker-sdk,swarm-controller-service -am` with
RedisConnectionSettingsTest, RedisConnectionEnvironmentTest, WorkIOConfigBinderTest,
SwarmWorkerSpecFactoryTest, SwarmLifecycleManagerTest and RepositoryImportBoundaryTest;
`-Dtest=ScenarioRepositoryValidationTest` in the Scenario Manager reactor. Logs:
`/tmp/b02-redis-export-{before,tests,scenarios}.log`; before reproduction in
`/tmp/b02-redis-export-baseline/result.log`. Repository-wide environment-name search
identified the one launching producer; Docker environment conversion preserves the text.
Only an existing internal work-config dependency was added. No automatic review or
deployment; the new transfer is uncommitted and does not accept B02.
Root `./mvnw -B -ntp -DskipTests package` and docs-site build passed; the Controller
boot JAR contains work-config with the new encoder. Logs: `/tmp/b02-redis-export-package.log`
and `/tmp/b02-redis-export-docs.log`. `git diff --check` passed. The final credential-only
fixture rerun passed all six worker-plan tests (`/tmp/b02-redis-export-final-fixture.log`).

#### Separate environment-export review — 2026-09-09

Scope: uncommitted Redis environment transfer against `63597068`. **Blocked by ENV-R1
(HIGH): a rejected worker plan leaves partial swarm state and a later start skips
provisioning.** SwarmWorkerSpecFactory's new connection validation (lines 139–140,
177–178) runs inside SwarmRuntimeCore.prepare after topology declaration and runtime
state installation; earlier workers have already been registered (lines 121–146).
The reproduced two-worker plan has a valid first worker and `outputs.redis.port: 0`
on the second. Prepare throws WorkConfigurationException with one registered worker
and zero created containers. Start with the corrected plan (`port: 6379`) sees that
registration, skips prepare (lines 102–110), and exposes RUNNING with zero containers.
The same probe with the factory from `63597068` creates both containers: the ordering
defect predates this change, but the new Redis rejection path exposes it. Keep strict
validation; prepare all worker/config candidates before mutating topology, readiness,
fanout or accepted runtime state. Rejection must permit a real corrected retry.

Evidence: public SwarmLifecycleManager prepare/start methods, existing test fixture,
mocked compute adapter; no deployed or ingress outcome claimed. Probe and before/after
logs: `/tmp/b02-redis-export-review/{LifecycleProbe.java,lifecycle-before.log,lifecycle-after.log}`.
All **91** selected tests passed in the worker-sdk, Controller and Scenario Manager
reactor, including the existing import scanner; selectors are the six classes above
plus ScenarioRepositoryValidationTest. Log: `/tmp/b02-redis-export-review-tests.log`.

SSOT evidence: RESP-REDIS-CONNECTION-SETTINGS and RESP-CONTROLLER-WORKER-PLAN match
the new encoder/factory headers. Repository-wide producer/parser search found the
factory delegating connection parsing and environment encoding to work-config;
WorkIOConfigBinder consumes the exported names, and Docker conversion preserves
credential text. Remaining IO policy exports and separate connection scopes retain
their documented B02 status. A before/after probe also confirmed that final bee.env
overrides can still bypass declared-block validation; this is an existing, explicitly
unfinished B02 gate (`/tmp/b02-redis-export-review/override-{before,after}.log`).

Six passes: plan outcome blocked by ENV-R1; style and headers consistent; conciseness
and readability acceptable for the small shared encoder; security checked credential
preservation and validation errors without finding a new disclosure; library check
found only an existing internal dependency. Changed tests exercise value transfer and
rejection, not bean/module selection. No production fix or commit in this review.

#### ENV-R1 fix — 2026-09-09

SwarmRuntimeCore now prepares all worker specs/bootstrap candidates and checks their
identities in a local SwarmRuntimeState before replacing accepted state or requesting
topology/bootstrap/provisioning changes. The existing lifecycle owner and validators
remain; production change is +20/-14 lines, with no new types or dependencies.
RESP-CONTROLLER-CONTROL and the core header document the preparation ordering.
Repository-wide searches for SwarmRuntimeState, registerWorker, registerBootstrapConfig
and workersPlanned confirmed the core is the preparation caller; factory/state/fanout
retain their distinct planning, identity validation and bootstrap responsibilities.

One regression in SwarmLifecycleManagerTest failed before the fix (a phantom generator
remained), then verified rejection without effects, creation of both workers on corrected
start, and preservation of accepted identities/context/readiness/bootstrap/workload state
when a subsequent plan is rejected. All **43** selected tests passed: SwarmLifecycleManagerTest,
SwarmRuntimeCoreScenarioEngineTest, SwarmWorkerSpecFactoryTest, SwarmRuntimeStateTest,
RedisConnectionSettingsTest, RedisConnectionEnvironmentTest and RepositoryImportBoundaryTest.
Command: `./mvnw -B -ntp -pl swarm-controller-service -am` with those `-Dtest` selectors
and `-Dsurefire.failIfNoSpecifiedTests=false test`. Logs: `/tmp/b02-env-r1-{before,tests}.log`.
ENV-R1 is fixed; `git diff --check` passed. This addresses worker planning/identity
rejection, not rollback after infrastructure errors. No automatic review, commit or
deployed acceptance; remaining B02 gaps and the deferred SEL-R1 are unchanged.

### Configuration namespaces — 2026-09-09

Moved 18 production types and their eight existing test classes within work-config:
`.redis` owns Redis settings and RedisConfigurationParser; `.environment` contains
RedisConnectionEnvironmentEncoder; `.policy` contains WorkPatchPolicy. Shared IO enums,
validation mode/problems/exception stay in the root package. Updated 20 Java consumer
files plus architecture records/headers. The parser moved with the settings, preserving
package-private validated-value constructors. No compatibility facade, new parser,
dependency, behavior test or import-scanning rule was added. Complete Work candidate
validation must delegate Redis rules to this owner; it is still unfinished B02 work.

Before: all 67 work-config tests passed. After: the same 67 tests passed under their new
packages; 170 consumer/import tests passed, with one pre-existing gated Redis integration
placeholder skipped. Commands: `./mvnw -B -ntp -pl common/work-config -am clean test`,
and the worker-sdk, Scenario Manager and Controller reactor with the selected existing
Redis, runtime, binder, capability, scenario, lifecycle and import-test classes.
Logs: `/tmp/b02-config-namespaces-{before,config-tests,tests}.log`. Source search found
no old parser/encoder names or moved root-package imports in active Java. No B02
acceptance, automatic review, commit or deployment follows from the namespace move.
Root `./mvnw -B -ntp -DskipTests package` passed (including consumer test compilation),
log `/tmp/b02-config-namespaces-package.log`. The work-config JAR contains the new
namespaces and only the five shared root types, with no old classes. `git diff --check` passed.

#### Separate namespace and ENV-R1 review — 2026-09-09

**No findings in this slice.** Reviewed the uncommitted moves/consumers and ENV-R1
correction against `63597068`. Direct file diffs show the existing parser, settings,
policy and their tests changed only packages, imports, class names and header wording;
the environment encoder and lifecycle correction were inspected separately.

- Redis settings: headers and RESP-REDIS-CONNECTION-SETTINGS / RESP-WORK-REDIS-ROUTES,
  SOURCES, SELECTION, TARGETS and WRITE-SETTINGS agree. RedisConfigurationParser remains
  the decoded-settings owner; SDK properties/runtime and Scenario Manager findings
  delegate to it. RedisDatasetSource retains its documented field-decoding role.
  Source and `javap` confirm connection/write/compiled-route constructors stay
  package-private beside their parser. The module has no production dependencies.
- Environment: SwarmWorkerSpecFactory → parser → RedisConnectionEnvironmentEncoder
  retains canonical fields and exact password text; real Spring environment binding
  is exercised by WorkIOConfigBinderTest. No client or state operation in the encoder.
- Patch policy: WorkerControlPlaneRuntime delegates validation to WorkPatchPolicy;
  CapabilityCatalogueService reads its catalogue. RESP-WORK-PATCH-POLICY and headers
  agree; the move does not add another validator or state writer.
- ENV-R1: SwarmRuntimeCore validates all worker/identity candidates before replacing
  state or requesting topology/bootstrap/provisioning effects. The existing regression
  verifies initial rejection, corrected provisioning and preservation of accepted state.

Repository-wide searches covered old qualified names, construction/parsing call sites,
environment names and patch catalogues; inspected results contain no competing owner
for the migrated responsibilities. Temporary diffs/search: `/tmp/b02-config-namespaces-review-*`.
All **170** focused tests passed with no skips in the worker-sdk, Scenario Manager and
Controller reactor (including all eight work-config classes and the sole import test);
log `/tmp/b02-config-namespaces-review-tests.log`. `git diff --check` passed.
Six passes: plan outcome supported for this slice; style/header alignment supported;
conciseness and maintainability improved by names matching roles without new layers;
security preserves credential handling/access; library check found no new dependency.
The namespace migration and ENV-R1 correction are accepted within this scope. Full B02
candidate/bee.env validation, decoding gaps and B03–B07 physical adapter separation
remain open; SEL-R1 remains deferred. No deployed acceptance or commit.

The remaining WorkConfigurationParser transfer must cover immutable IO/connection/execution settings, AUTHORING/RESOLVED
validation and deferred symbolic constraints, bootstrap decoding, full candidate checks
before accepted state, removal of the other duplicate Redis validators, and producer migration.
The current adapters still contain raw parsing and can ignore invalid updates; this
transfer has not fixed those defects. Input-local enabled fields and existing default
settings also remain. Rabbit export no longer belongs to Control Plane, but upstream
sample defaults and transport options beyond its five-field contract remain outside
that transfer. Retained template payload-context/default duplication remains open.

No B02 acceptance, B03 start, deployment or whole-repository SSOT verdict follows from
these implemented transfers. Separate review is a human-triggered task under the accepted
workflow; this implementation task has not run an automatic review/fix loop.

### Final connection overrides — 2026-09-09

Scope: `63597068` plus the accepted uncommitted namespace/ENV-R1 changes above.
[RESP-WORK-CONNECTION-ENVIRONMENT](../../../architecture/runtime-responsibilities.md#resp-work-connection-environment)
owns connection composition after bee.env. SwarmWorkerSpecFactory supplies Spring's
SystemEnvironmentPropertySource lookup and consumes the resolved environment/bootstrap pair.
RabbitConnectionSettings and RedisConfigurationParser retain the field constraints;
RedisConnectionEnvironmentCodec owns both projections. work-config gains only the existing
internal rabbit-config dependency. No new module, external library or scanner was added.

Before: the new worker-plan cases produced four failures and one error: invalid Rabbit/
Redis overrides passed, valid Redis overrides were absent from bootstrap, and a valid
override could not repair an invalid declared port. After: invalid explicit values fail
before a worker plan is returned; accepted Redis values reach both startup and bootstrap.
Empty passwords remain explicit and source maps are unchanged. The existing ENV-R1
lifecycle regression covers planning rejection before accepted state/infrastructure effects.

Verification: **179 selected tests passed**, including resolver/codec units, worker planning,
real Spring binding of both projections, lifecycle rejection and the single import test.
Full root `./mvnw -B -ntp -DskipTests package` passed. Logs:
`/tmp/b02-final-connections-before.log`, `/tmp/b02-final-connections-tests.log`,
`/tmp/b02-final-connections-package.log`. Repository-wide Java search for the five Rabbit/
Redis environment fields and resolver/codec calls locates production mapping in the shared
owners and the single worker-plan consumer; binding remains a bootstrap concern.

Remaining: full IO selection/settings/execution candidate validation, other Redis scopes,
Rabbit TLS/address-list settings and later property sources. This is connection composition
only; B02 and user-deferred SEL-R1 remain open. No automatic review, commit or deployment.

#### Separate final-connection review — 2026-09-09

**Blocked by two HIGH findings.** Scope: final bee.env connection composition on
`63597068` plus the previously accepted uncommitted namespace/ENV-R1 changes.

- **FENV-R1 — planner lookup differs from worker binding.** SwarmWorkerSpecFactory:88–90
  supplies SystemEnvironmentPropertySource.getProperty, while worker startup uses
  Binder.get(environment). With base VIRTUAL_HOST=/base and bee.env
  SPRING_RABBITMQ_VIRTUALHOST="", the resolver accepts /base; the actual Binder rejects
  the empty vhost. With /other, Binder selects /other while the resolved canonical export
  still says /base. A password ${EMPTY}, with EMPTY="" in that same environment, also
  passes planning and fails binding. These are the five covered fields and the same
  source, not excluded TLS options/later property sources. Use the actual bootstrap
  property-resolution semantics before canonical validation. This leaves the earlier
  final-environment gap open; it is not a new regression for the reproduced Rabbit cases.
- **FENV-R2 — new disclosure of environment-only Redis passwords.** Resolver:50–54 copies
  the accepted password into public bootstrap config. ConfigFanout sends it to
  WorkerControlPlaneRuntime, whose publicConfigFrom removes only privateConfig;
  ControlPlaneNotifier logs the raw map and status-full includes it. A synthetic password
  supplied only through POCKETHIVE_OUTPUTS_REDIS_PASSWORD was absent from status before
  this projection, and present in status and INFO logs afterward. Existing raw-config
  logging lacked redaction; this change newly exposes environment-only secrets through it.
  Preserve adapter credentials while giving status/log consumers a single redacted projection.

Ownership evidence: RESP-WORK-CONNECTION-ENVIRONMENT → resolver/result → worker planner;
RESP-RABBIT-CONNECTION → RabbitConnectionEnvironment/RabbitConnectionSettings, with
RabbitConnectionConfiguration as startup decoder. Field constraints are shared, but FENV-R1
violates effective-value parity. RESP-REDIS-CONNECTION-SETTINGS → parser/codec → SDK
properties, input/output adapters and Scenario Manager; no second field validator found.
RESP-CONTROLLER-CONTROL → prepare validates candidates before state/topology/fanout/provisioning;
this ordering works for detected errors, but cannot prevent FENV-R1. Headers and file
separation follow the assigned ownership; redacted result.toString does not protect the
public bootstrap map after it leaves that result (FENV-R2).

Repository-wide Java searches covered connection environment names, parser/merge/constructor
calls, Binder/SystemEnvironmentPropertySource and status/log consumers. Search output:
`/tmp/b02-connection-review-search.txt`, `/tmp/b02-final-connection-review/redis-owners.txt`.
**115 selected tests passed**, including connection units, actual binding, worker planning,
lifecycle and the single import test; `/tmp/b02-final-connection-review/tests.log`.
Two temporary in-process probes reproduced the findings using real Binder and the existing
worker runtime/control codec/status builder: `binding-probe.log`, `secret-probe.log` in
the same temporary directory. No permanent tests, production fixes or deployment added.
`git diff --check` passed. Six passes: plan outcome blocked by FENV-R1; style/file separation
supported; conciseness/readability require one effective lookup path; security blocked by
FENV-R2; libraries supported (only existing rabbit-config dependency). B02 is not accepted.

#### FENV-R1/FENV-R2 corrections — 2026-09-09

SwarmWorkerSpecFactory now supplies Binder with Spring's property-name mapping and
PropertySourcesPlaceholdersResolver over the composed environment. Empty VIRTUALHOST
and empty expanded password fail before plan return; valid aliases/placeholders reach
startup and the accepted projection. The binder component fixture now uses Binder.get
and the same input shape as production (declared Redis fields remain in raw config until
resolution). Conflicting uppercase/dotted Rabbit port entries follow actual Spring
precedence; the earlier getProperty-based expectation was incorrect.

WorkConfigurationRedactor in `.config.projection` owns diagnostic masking of the exact
password field through decoded maps/lists. Worker status and configuration logs consume
it; logging moved from ControlPlaneNotifier into WorkerConfigurationLog. Raw accepted
state, adapter listener snapshots and appliedConfigSha256 retain original values.
No external dependency, source scanner or additional state/configuration owner was added.

Before: the two new worker-plan cases and runtime secret regression failed (three failures).
After: **133 selected tests passed**, including real Spring binding, runtime INFO/DEBUG
logging/status, preserved raw state/digest, redactor map/list/diff behavior, lifecycle and
the sole import test. Root `./mvnw -B -ntp -DskipTests package` and `git diff --check` passed.
Logs: `/tmp/b02-fenv-fix-before.log`, `/tmp/b02-fenv-fix-tests.log`,
`/tmp/b02-fenv-fix-package.log`. The redactor is an exact field policy, not a general secret
classifier. Remaining B02 scope and deferred SEL-R1 are unchanged. No automatic review,
commit or deployment; these corrections await separate review.

#### Separate FENV correction review — 2026-09-09

**Blocked by remaining HIGH FENV-R1.** Scope: the corrections above on `63597068` plus
uncommitted work. The original alias and direct-placeholder regressions now pass.
SwarmWorkerSpecFactory:91–95 builds Binder over a copy of the environment before
WorkConnectionEnvironmentResolver:29–35 adds Redis connection exports. With bee.env
`SPRING_RABBITMQ_PASSWORD=${POCKETHIVE_OUTPUTS_REDIS_PASSWORD}` and declared
`outputs.redis.password=""`, production planning accepts the unresolved Rabbit password.
The emitted environment contains the empty Redis password; real worker Binder resolves
the Rabbit password to empty and rejects startup. The temporary production-factory probe
prints `PLAN ACCEPT unresolvedRabbitPassword=true redisPasswordEmpty=true`, then
`BOOT REJECT spring.rabbitmq.password must not be null or blank`. This remains FENV-R1,
not deferred SEL-R1 or an excluded later property source. Validate the complete emitted
environment with worker semantics before returning the plan, including unresolved references.

**FENV-R2 accepted within scope.** WorkConfigurationRedactor owns the exact decoded
password-field projection; WorkerConfigurationLog and both runtime status config paths
consume it. Raw accepted state, adapter snapshots and digest retain original values.
The repeated secret probe reports `AFTER statusContainsEnvSecret=false`; runtime tests
also verify INFO/DEBUG masking. This does not certify arbitrary secret names or deployment.

SSOT/header evidence: RESP-WORK-CONNECTION-ENVIRONMENT → resolver/codec → worker planner
still fails effective-value parity with RabbitConnectionConfiguration; prepare cannot
reject an error missed by planning. RESP-WORK-CONFIGURATION-DIAGNOSTICS → shared redactor
→ log/status projections agrees with headers. Repository-wide owner search found no
second Work configuration masker; env-log, auth-error and text-log sanitizers have distinct
inputs/policies. Search: `/tmp/b02-fenv-correction-review-owners.txt`.

**133 selected tests passed**: redactor, resolver, real Spring IO/Rabbit binding, worker
runtime/spec/lifecycle and the sole import test. Log: `/tmp/b02-fenv-correction-review-tests.log`.
Probe sources/results: `/tmp/b02-fenv-correction-review/`; `git diff --check` passed.
Six passes: plan blocked by FENV-R1; style/header separation supported; conciseness supported
(shared projection and extracted log owner); security supported for FENV-R2; libraries
supported (existing Spring/JDK facilities); readability supported, with composition order
remaining the correctness blocker. No production changes, permanent tests or deployment
in this review. Do not accept the complete slice/B02; SEL-R1 remains explicitly deferred.

#### FENV-R1 complete environment correction — 2026-09-09

Human-approved correction to the remaining finding above, on `63597068` plus uncommitted
work. RESP-WORK-CONNECTION-ENVIRONMENT now specifies: compose both Redis directions,
freeze the complete environment, bind/validate, project bootstrap, return that unchanged
environment. Rabbit validation no longer re-exports values after binding. Source strings
and placeholders remain in the emitted snapshot; bootstrap holds accepted Redis settings.

SwarmWorkerSpecFactory delegates Spring behavior to the 47-line SpringConnectionEnvironment
in its config package. Raw lookup obtains explicit overrides without placeholder expansion;
the final lookup factory receives the complete snapshot and uses strict Spring expansion.
Missing/cyclic references fail with a property name and no credential-bearing cause.
RedisConnectionEnvironmentCodec encodes candidates before validation; raw non-text types
remain available to the canonical parser, preventing text export from legitimizing invalid
declarations. Valid explicit overrides still replace invalid base values. No custom parser,
retry/fallback loop, new dependency or scanner was introduced.

Before: four new regression cases failed in the existing worker-plan suite. After:
**140 selected tests passed**, covering empty Rabbit password via declared Redis password,
successful references against the final snapshot, alias/override precedence, raw types,
bootstrap/startup parity, strict-reference errors, lifecycle rejection and existing
status/log masking. Strict-reference cases moved to the extracted Spring adapter's unit
test; no duplicate factory cases remain. Root `./mvnw -B -ntp -DskipTests package` and
`git diff --check` passed. Logs: `/tmp/b02-fenv-freeze-before.log`,
`/tmp/b02-fenv-freeze-tests.log`, `/tmp/b02-fenv-freeze-package.log`.

Owner/consumer search: `/tmp/b02-fenv-freeze-owners.txt`. Connection field constraints stay
in RabbitConnectionSettings/RedisConfigurationParser; the codec owns mapping, the resolver
owns composition and SpringConnectionEnvironment supplies framework lookup semantics.
Implementation handoff only: await separate review, with full B02/SEL-R1 scope unchanged.
No automatic review, commit or deployment was performed.

#### Separate complete-environment review — 2026-09-09

**No findings in this correction; FENV-R1 accepted within scope.** Revision: `63597068`
plus the uncommitted complete-environment correction. This closes the reported planning/
startup mismatch; it does not accept full B02, other settings/scopes or deployment.

RESP-WORK-CONNECTION-ENVIRONMENT: factory → resolver composes both directions → immutable
snapshot → SpringConnectionEnvironment final lookup → shared validators → unchanged spec
environment and accepted Redis bootstrap. The raw lookup performs no expansion. Headers
and architecture agree. prepare finishes every candidate before accepted state/topology/
fanout/provisioning. Both selected compute adapters copy the environment; Docker serialization
does not re-resolve connection fields (Swarm retains its existing template-delimiter escaping).

RESP-RABBIT-CONNECTION and RESP-REDIS-CONNECTION-SETTINGS retain field constraints in
RabbitConnectionSettings/RedisConfigurationParser. Codec numeric formatting only changes
the wire representation; raw types remain available for validation. Worker startup uses
RabbitConnectionConfiguration and Work IO binders/RedisConnectionProperties. Repository-wide
searches found no second owner introduced for these responsibilities; other Redis scopes
remain separate B02 work. Evidence: `/tmp/b02-fenv-freeze-review-owners.txt` and
`/tmp/b02-fenv-freeze-review-diagnostics.txt`.

**140 selected tests passed again**, including the reported negative, alias/override
precedence, strict-reference errors without secrets, lifecycle and prior log/status masking.
Log: `/tmp/b02-fenv-freeze-review-tests.log`. A temporary producer uses the production
planner; a separate consumer process uses worker Binder and Work IO binders. All eight
variants agree for Rabbit/input/output, including cross-direction references, dotted
overrides, escaped Rabbit placeholders, integral decimal numbers and explicit empty values.
Both classpaths use Boot 3.5.14/Core 6.2.18. Sources/results: `/tmp/b02-fenv-freeze-review/`.
`git diff --check` passed. No permanent tests or production fixes added by this review.

Six passes supported within scope: plan outcome verified; style/header separation aligned;
conciseness uses one composition and frozen result; security errors omit values/causes and
existing diagnostic masking passes; libraries use existing Spring/JDK; readability exposes
raw versus final binding and keeps Spring outside work-config. No deployed check performed.
FENV-R2 remains accepted in its reviewed scope; SEL-R1 remains explicitly deferred.

#### Common input rate — 2026-09-09

Base: clean `7ac51535`, committed on human request after the separate connection reviews.
[RESP-WORK-INPUT-RATE](../../../architecture/runtime-responsibilities.md#resp-work-input-rate)
was documented before implementation. InputRateParser/InputRateValidation in `work-config.input`
own the shared setting. WorkConfigurationExpressions extracts the existing Redis deferral
check unchanged, with both parsers consuming it. No new library, scanner or template parser.

Removed rate constraints from three startup properties, three runtime inputs, WorkPatchPolicy
and SchedulingState. Properties preserve decoded Object values for canonical parsing;
`ratePerSec()` is the typed accessor, and all callers were migrated. ScenarioBundleValidator
maps the selected field to WorkConfigurationFindings and skips its generic required/type/range
checks for that field. SchedulingState remains a projection, with shape consistency only.

Observable changes: all three inputs accept numeric text and reject explicit null; absent
patch fields remain unchanged. CSV validates the rate before applying file/other overrides.
Errors carry a field path without raw input/cause. Authoring reports exactly one error or
one deferred warning per selected rate, retaining resolved-only acceptance of expressions.

133 selected tests passed: parser valid/invalid/deferred behavior, patch null rejection,
startup binding (including raw booleans), runtime CSV rejection without partial file changes,
existing Redis/scheduler/trigger behavior, real-catalogue scenario validation for all three
inputs, Redis expression regressions and the single import test. Full root package and
`git diff --check` passed. Logs: `/tmp/b02-input-rate-tests.log`,
`/tmp/b02-input-rate-package.log`. The additional ScenarioServiceTest suite passed
12 tests (`/tmp/b02-input-rate-scenario-service.log`): 145 tests in total.
Owner/consumer searches:
`/tmp/b02-input-rate-owners.txt`, `/tmp/b02-input-rate-all-consumers.txt`; repository-wide Java
searches for rate paths, numeric conversions, range checks and accessor callers.
Moderator's mode/SINE rate controls processing rather than input intake and remains outside
this transfer. Catalogue descriptors are presentation metadata, not the selected rate validator.

Implementation handoff only, no automatic review or deployment. Full startup/raw shape parity,
complete candidate acceptance, timing/limits, other B02 settings and SEL-R1 remain open.

#### Separate input-rate review — 2026-09-09

Scope: `7ac51535` plus the uncommitted input-rate transfer, including new/untracked files.
**RATE-R1 CRITICAL — incomplete SSOT transfer.** BufferGuardCoordinator in Controller still
parses the same `inputs.scheduler.ratePerSec` / `inputs.redis.ratePerSec` through
extractRatePerSec (301–348), substitutes missing/invalid values with adjustment.minRatePerSec
(283), and repairs non-finite/negative values through clampRate (351–357). This competes
with InputRateParser's required finite/nonnegative contract. BufferGuard's adjustment bounds
are its own domain behavior; decoding/accepting the source input rate is not.

Reproduction through public configureFromTemplate with production JSON decoding, guard
resolution and currentSettings: for both selected inputs, -1, "fast", "NaN" and null are
rejected by InputRateParser but result in active=true, initialRate=1.0, lastProblem=null
in BufferGuard. "3.0" is accepted as 3.0 by both. Temporary executable and output:
`/tmp/b02-input-rate-review/BufferGuardRateProbe.java`,
`/tmp/b02-input-rate-review/buffer-guard.txt`. No network/client effect is exercised.
BufferGuard source is unchanged from `7ac51535`; this is inherited duplication omitted
from the current migration, not a newly introduced fallback regression.

Required correction: remove BufferGuard's rate decoder and consume the shared resolved
rate validation before applying the guard's own adjustment bounds. Invalid selected input
rates must not become an active guard with a substituted minimum. Keep any explicitly
supported non-rate input behavior separate. Update the responsibility record/header and
verify this actual configuration effect. Do not accept this transfer until RATE-R1 is fixed.

| Responsibility | Independently inspected evidence / verdict |
|---|---|
| RESP-WORK-INPUT-RATE | Parser/report headers and architecture agree for the migrated callers. WorkInputRegistryInitializer → WorkInputConfigBinder → selected properties validate; runtime inputs and WorkPatchPolicy delegate. ScenarioBundleValidator → WorkConfigurationFindings projects canonical errors/deferred paths, bypassing generic catalogue rate checks. **Violated globally by the Controller alternative owner above.** |
| Work expression deferral | WorkConfigurationExpressions is extracted unchanged from RedisConfigurationParser; both parsers delegate. It neither renders nor validates template syntax. Existing Redis tests and new input AUTHORING/RESOLVED negatives pass. |
| RESP-WORK-SCHEDULE-CONTRACT | Only production SchedulingState constructors are SchedulerWorkInput bootstrap and update projection, both consuming the validated accessor. Removed numeric validation has no production bypass found; configured-state shape checks remain. |

Repository searches: `rg -n 'ratePerSec|rate-per-sec|rate_per_sec|ratePerSecond'` over all
Java production modules (`/tmp/b02-input-rate-review-consumers.txt`), plus TS/TSX/MJS
consumers, numeric helper/conversion searches, input-property callers and all SchedulingState
constructors. Inspected candidates include BufferGuard, worker property/runtime paths,
ScenarioBundleValidator generic validators, SwarmWorkerSpecFactory environment mapping,
and Moderator/Processor processing limits (distinct settings, outside this input contract).
Remaining full producer/candidate and empty-YAML shape work stays B02 debt, not certified here.

**145 selected tests passed again**, including the single repository import test;
log `/tmp/b02-input-rate-review-tests.log`. `git diff --check` passes. The previous full
package result remains implementation evidence; this review did not rerun packaging or
deploy the stack. Temporary behavioral probe adds the contradiction not covered by that suite.

Six passes: plan/SSOT acceptance blocked by RATE-R1; changed type headers and file separation
supported; extracting common parsing removes local copies but misses one consumer; canonical
errors omit raw values/causes, while the inherited guard still logs malformed text; existing
JDK/Spring libraries suffice; call paths are readable but the all-consumer claim is incomplete.
No other finding established in this scope. SEL-R1 remains explicitly deferred; full B02
and the current rate transfer remain unaccepted. Review changes only documentation/evidence.

#### RATE-R1 correction — 2026-09-09

Human-requested correction on `7ac51535` plus the uncommitted input-rate transfer.
Architecture updated first: RESP-WORK-INPUT-RATE names BufferGuard as a consumer;
RESP-CONTROLLER-BUFFER-GUARD records current integration and remaining traffic-policy debt.
The Coordinator header matches those records. Source-rate parsing/validation moves to the
existing InputRateParser; its prior numeric decoder, OptionalDouble fallback and non-finite
repair are removed. Guard-specific adjustment bounds remain separate. The local input enum
is replaced with WorkerInputType, including its settings keys in emitted patches. Role/input
mapping is cleared on reconfiguration to avoid retaining a previous controlled input.
Production correction: one file, 26 additions / 86 deletions; no new runtime type/dependency.

Before: BufferGuardCoordinatorTest failed its two selected-input rejection cases because
invalid configuration left the guard active. After: **52 tests passed**, including that
suite, canonical parser/patch policy, the single import test, SwarmLifecycleManagerTest
and the traffic-policy projection test. Invalid/null/non-finite/symbolic/missing rates now
leave active=false, empty settings and a reported WorkConfigurationException, also after
a valid prior configuration. Numeric text is accepted; zero/high valid values still obey
guard bounds. Existing bounds-only behavior for non-controlled inputs remains separate.
Logs: `/tmp/b02-rate-r1-before.log`, `/tmp/b02-rate-r1-tests.log`.
Repository-wide consumer search: `/tmp/b02-rate-r1-consumers.txt`; Controller no longer has
extractRatePerSec, Double.parseDouble/doubleValue conversion or source-rate finite checks.
`git diff --check` passes. The affected Maven reactor compiled production and test sources;
no broader root package rerun or deployed check was needed for this consumer correction.

Implementation handoff: correction awaits separate review; full B02 and deferred SEL-R1
remain open. No automatic review/fix loop, commit or deployment.

#### Separate RATE-R1 correction review — 2026-09-09

Scope: `7ac51535` plus the uncommitted input-rate transfer; this follow-up independently
reviews the Controller correction and its new behavioral test. **No findings in this
correction. RATE-R1 is closed; the scoped input-rate transfer is accepted.** The original
145-test review above remains evidence for the unchanged extraction consumers.

| Responsibility | Source evidence and verdict |
|---|---|
| RESP-WORK-INPUT-RATE | BufferGuardCoordinator.configuredInputRate delegates raw selected settings to InputRateParser before guard bounds. The local decoder/default/non-finite repair is gone. Parser, consuming header and architecture agree. Repository-wide production Java search for `ratePerSec\|rate-per-sec\|rate_per_sec\|ratePerSecond` is recorded in `/tmp/b02-rate-r1-review-owners.txt`; inspected SDK inputs, policy, authoring, Controller and Manager SDK candidates. Moderator/Processor processing limits and guard adjustment bounds are distinct settings. No competing source-rate decoder found. |
| RESP-CONTROLLER-BUFFER-GUARD | SwarmLifecycleManager.prepare → Controller coordinator → shared parser → Manager SDK configuration. Manager SDK owns running guards and feedback state; QueueStatsPort supplies samples; Controller sends computed updates through ControlPlanePublisher with shared routing/signals. Invalid configuration clears Manager SDK settings and reports failure. Role mapping is rebuilt; WorkerInputType supplies patch keys. Runtime traffic-policy overrides preserve the accepted initial rate. No new direct infrastructure access. |

**52 tests passed again**: BufferGuardCoordinatorTest, SwarmLifecycleManagerTest,
BufferGuardTrafficPolicyMapperTest, InputRateParserTest, WorkPatchPolicyTest and the
single RepositoryImportBoundaryTest. Command:
`./mvnw -B -ntp -pl swarm-controller-service -am -Dtest=BufferGuardCoordinatorTest,SwarmLifecycleManagerTest,BufferGuardTrafficPolicyMapperTest,InputRateParserTest,WorkPatchPolicyTest,RepositoryImportBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`.
Log: `/tmp/b02-rate-r1-review-tests.log`.

A temporary public-API probe uses real Controller/Manager SDK coordinators and metrics,
with queue statistics and CP publication replaced at their ports. Its immediate ticks
verify Scheduler → invalid → Redis → Rabbit transitions: correct rate patches, stopped
guard/removed metrics after rejection, no restart on disable/re-enable until valid
configuration, and no stale rate patch in bounds-only mode. A second invalid guard
rejects the entire replacement without starting the first. All five check groups
passed; `/tmp/b02-rate-r1-review/BufferGuardTransitionProbe.java` and `transitions.txt`.
No permanent test/scanner was added during review.

Six passes: plan closes the missed consumer; style/header ownership agrees; conciseness
removes the duplicate decoder and local enum; security rejects malformed rates without
logging their raw value; libraries reuse existing contracts/JDK; maintainability keeps
source validation separate from guard bounds. Remaining traffic-policy parsing and
broader Controller separation are inherited debt, not certified by this correction.

`git diff --check` passes. No deployed broker/worker check or full package rerun in this
review; port-level assertions do not establish delivered CP updates. The transition probe
does not simulate an already in-flight queue read during stop. Full B02, timing/limits,
complete candidate acceptance and explicitly deferred SEL-R1 remain open. Review changes
only documentation/evidence; no production fix, commit or deployment.

#### Input timing and limits — 2026-09-09

Baseline: `e17dee90`, committing the accepted input-rate transfer on human approval.
RESP-WORK-INPUT-SCHEDULE now owns integer timing/limit declarations in work-config:
InputScheduleField defines fields/ranges; InputScheduleParser validates exact integer
values and InputScheduleValidation carries accepted values or errors/deferred paths.
It uses JDK BigDecimal.longValueExact, with no new dependency or expression parser.

Consumers migrated together: Scheduler/Redis/CSV startup properties preserve raw values
until canonical validation; SchedulerWorkInputBuilder and dataset inputs consume validated
timing without local clamping; SchedulerWorkInput and WorkPatchPolicy delegate maxMessages;
WorkConfigurationFindings projects authoring results and ScenarioBundleValidator bypasses
generic catalogue checks for those selected fields. Rate and limit updates are parsed
before either setting is changed. Remaining counter/quota arithmetic is execution behavior.

The existing omission defaults are retained centrally (implementation assumption after
the optional preference question received no answer): Scheduler/Redis delay=0ms and
tick=1000ms, Scheduler maxPendingTicks=1. Explicit null/invalid declarations are rejected;
CSV timing and scheduler maxMessages remain required. Numeric text and Number values
share exact integer rules; maxMessages retains the full long range. Duration bounds cover
both seconds conversion and the scheduler's nanosecond clock, without silent overflow or
saturation. The existing maxPendingTicks setting still has no execution consumer; this
slice does not claim to implement a backlog limit.

Before: **120 tests passed**, log `/tmp/b02-input-timing-before.log`. A temporary public
WorkInputConfigBinder probe compiled the prior SchedulerInputProperties from `e17dee90`
and compared it with current code: initialDelayMs=-1, tickIntervalMs=99 and maxPendingTicks=0
were all accepted before and all rejected afterward. Probe/output:
`/tmp/b02-input-timing-probe/TimingProbe.java`, `/tmp/b02-input-timing-probe/results.txt`.

After: **143 tests passed**, including canonical integer/expression/default boundaries,
real startup binding, finite-run limit reset and rejection without partial rate changes,
scenario diagnostics, existing CSV/Redis/Trigger/runtime behavior and the single import
test. InputRateValidationComponentTest was renamed/extended to
InputNumericSettingsValidationComponentTest to reuse its real bundle fixture.
Command: `./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service,trigger-service -am -Dtest=InputScheduleParserTest,WorkIOConfigBinderTest,WorkPatchPolicyTest,InputNumericSettingsValidationComponentTest,SchedulerWorkInputTest,CsvDataSetWorkInputTest,RedisDataSetWorkInputTest,RateSchedulePolicyTest,TriggerSchedulePolicyTest,TriggerSchedulerIntegrationTest,WorkerControlPlaneRuntimeTest,RepositoryImportBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`.
Log: `/tmp/b02-input-timing-tests.log`. Full root `./mvnw -B -ntp -DskipTests package`
passed after the final duration bounds; log `/tmp/b02-input-timing-package.log`.

Owner searches cover production Java and non-Java callers/environment spellings;
`/tmp/b02-input-timing-owners-before.txt`, `/tmp/b02-input-timing-owners-after.txt` and
`/tmp/b02-input-timing-accessors.txt`. SwarmWorkerSpecFactory still exports raw settings;
it does not contain a competing timing/limit decoder. Complete producer/candidate
validation, other settings/defaults, reset decoding and input enablement remain B02 work.
No full startup-shape/bee.env acceptance or deployed-stack delivery claim follows here.
`git diff --check` passes. No additional scanner, wiring tests, automatic review, deployment
or second commit. Hand off this uncommitted transfer for separate review; B02 and SEL-R1
remain open, and B03 has not started.

#### Separate input timing/limit review — 2026-09-09

Scope: uncommitted timing/limit transfer on `e17dee90`. **MEDIUM TIM-R1 — not accepted:**
SchedulerWorkInput.tick calls SchedulerInputProperties.maxMessages inside its dispatch
loop. The accessor now runs InputScheduleParser on every message, constructing a
BigDecimal and validation result from configuration already validated at startup/update.
Previously getMaxMessages read the accepted long. This moves boundary parsing onto the
per-message execution path, contrary to AGENTS.md's normalize-once rule, and adds avoidable
allocation/GC pressure. Keep the canonical parser at binding/update boundaries and let
execution read the accepted typed limit; no new parser or generic caching framework is needed.

A temporary public-API probe binds the same Long.MAX_VALUE text through the real
WorkInputConfigBinder before/after, then measures the limit accessor with ThreadMXBean
after 100,000 warm-up calls. Observed allocation: **0 versus about 352 bytes per call**
over 100,000 measured calls. This is an isolated accessor measurement, not a throughput
benchmark. Evidence: `/tmp/b02-input-timing-review/TimingBoundaryProbe.java` and
`/tmp/b02-input-timing-review/boundaries.txt`; prior properties compiled from `e17dee90`.

| Responsibility | Source evidence and verdict |
|---|---|
| RESP-WORK-INPUT-SCHEDULE | InputScheduleField/Parser/Validation own ranges, omission policy and exact numeric decoding. Repository-wide timing/limit spelling search (`/tmp/b02-input-timing-review-owners.txt`) followed SDK, policy, Scenario Manager and raw environment producers; no competing schedule decoder found. Separate journal/guard timers are different settings. TIM-R1 concerns repeated use of that owner, not duplicate implementations. |
| RESP-WORK-IO-CONFIG | WorkInputConfigBinder retains raw values through Spring and delegates configured validation. Scheduler/Redis/CSV property headers agree with architecture, but Scheduler's typed accessor reparses instead of retaining its accepted value. |
| RESP-WORK-PATCH-POLICY | WorkPatchPolicy delegates live maxMessages checks to the canonical parser; Scheduler parses rate and limit before applying either. Existing live-mutability rules, counter reset and exact long limits are preserved in behavioral tests. |
| RESP-SCENARIO-VALIDATE | ScenarioBundleValidator bypasses generic catalogue checks for the selected canonical fields; WorkConfigurationFindings only projects errors/deferred paths from their owner. No separate numeric rule is introduced. |

**143 tests passed again**, using the implementation command above; log
`/tmp/b02-input-timing-review-tests.log`. No new permanent test or scanner was added.
The same temporary probe also uses YamlPropertySourceLoader and the real binder:
null, lists and 99ms are rejected now; omitted tick keeps the documented default.
An empty YAML map (`tick-interval-ms: {}`) is accepted both before and after because
flattening drops it. This is inherited startup-shape debt within the already-open full
candidate/producer work, not a new regression or evidence of complete shape validation.

Six passes: plan/SSOT ownership is aligned within this slice; style and conciseness find
TIM-R1's repeated parsing; security finds no new raw-value exposure in canonical errors
(full producer/candidate acceptance remains unverified); standard JDK/Spring libraries
suffice; readability/maintainability requires keeping accepted typed state separate from
boundary decoding without introducing a framework. Headers, imports, constructors and
consuming paths add no infrastructure owner or IO effect. No further finding established.
The prior full package pass remains implementation evidence; no deployment was performed.
Review changes only documentation/memory. TIM-R1, full B02 and deferred SEL-R1 stay open.

#### TIM-R1 correction — 2026-09-09

Human-requested correction on `e17dee90` plus the uncommitted timing transfer.
SchedulerWorkInput now owns one accepted runtime `volatile long maxMessages`, initialized
from startup properties. The existing update boundary parses rate and limit before
replacing that value and resetting the counter. Ticks read the long; the raw startup
declaration is no longer rewritten on live updates. Canonical parsing remains in
InputScheduleParser. Architecture and the consuming header describe this ownership.
Production correction is confined to SchedulerWorkInput; no new class or dependency.
Repository consumer search: `/tmp/b02-tim-r1-fix/limit-consumers.txt`.

**90 tests passed**, covering parser, binding, patch policy, Scheduler and Trigger.
The existing finite-run test now checks dispatch count and remaining-message headers,
including limit changes, rejection of null/fractions/overflow without partial rate
changes, omitted-limit updates, exact Long.MAX_VALUE and unlimited runs. No new test class.
Command: `./mvnw -B -ntp -pl common/worker-sdk,trigger-service -am -Dtest=InputScheduleParserTest,WorkIOConfigBinderTest,WorkPatchPolicyTest,SchedulerWorkInputTest,RateSchedulePolicyTest,TriggerSchedulePolicyTest,TriggerSchedulerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`.
Log: `/tmp/b02-tim-r1-fix-tests.log`.

A temporary public-API allocation probe compares the saved pre-correction input with
current code, using unlimited maxMessages="0", a fixed seed, a counting WorkerRuntime
and a fixed-quota policy. After 1,000,000 warm-up dispatches, another 1,000,000 dispatches
allocate **112.112 bytes/message before, 0 after** in the measured calling thread.
This isolates the scheduler loop; it is not a whole-worker throughput claim. Probe and
results: `/tmp/b02-tim-r1-fix/RuntimeAllocationProbe.java`, `allocations.txt`.
`git diff --check` passes. The correction awaits separate review; full B02 and deferred
SEL-R1 remain open. No self-review loop, full package rerun, deployment or commit.

#### Scheduler reset contract — 2026-09-09

Continue B02 on `e17dee90` plus the uncommitted timing/TIM-R1 correction, whose separate
review remains pending. RESP-WORK-SCHEDULER-RESET assigns one strict boolean parser to
work-config. WorkPatchPolicy and SchedulerWorkInput consume it; Scenario Manager projects
its authoring diagnostics and bypasses the selected reset field's generic catalogue check.
The runtime string decoder and policy's local boolean check are removed. All requested
scheduler controls are parsed before rate, limit or counter mutation. Missing reset means
no request; false preserves the counter, true resets it. Null/text/number/structure errors
are explicit; AUTHORING defers expressions, RESOLVED rejects them. No startup reset
property or change to command replay/lifecycle semantics is introduced.

Owner/consumer searches: `/tmp/b02-scheduler-reset-owners-before.txt` and
`/tmp/b02-scheduler-reset-owners-after.txt`. Controller `scenario.reset`, CP's empty-config
reset and the TCP mock reset response have separate responsibilities. Existing capability
metadata already declares reset boolean; no valid producer needed migration.

Before: **86 selected tests passed**, `/tmp/b02-reset-before.log`. A separately selected
REST test already failed (expected four findings, received three): the accepted input-rate
contract allows numeric text, while its old assertion still rejected "1.0". The test now
expects the remaining maxMessages/reset/body failures and canonical integer wording.
Before log: `/tmp/b02-reset-controller-before.log`.

After: **92 tests passed**: the new parser's two behavioral tests; existing policy,
scheduler, binding, Trigger integration, the selected REST case and the single import
test; seven real-bundle component cases. The numeric component fixture was renamed to
InputSettingsValidationComponentTest and extended, preserving its rate/timing coverage.
Scheduler tests now include null/text/symbolic invalid reset alongside new rate/limit:
rejection retains prior dispatch behavior; false/omission preserve exhaustion, true
restarts the accepted finite run, and long/unlimited limits still work.
Command: `./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service,trigger-service -am '-Dtest=SchedulerResetParserTest,WorkPatchPolicyTest,SchedulerWorkInputTest,InputSettingsValidationComponentTest,WorkIOConfigBinderTest,TriggerSchedulerIntegrationTest,ScenarioControllerTest#bundleValidationRejectsSelectedIoConfigTypeMismatches,RepositoryImportBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false test`.
Log: `/tmp/b02-reset-tests.log`; `git diff --check` passes. No new scanner, wiring test,
library, self-review, root package rerun, deployment or commit. This transfer and TIM-R1
await separate review. Remaining B02 includes complete typed settings/candidate validation,
producer/startup-shape parity and input-local enablement removal; SEL-R1 remains deferred.

#### Separate TIM-R1 and reset review — 2026-09-10

Scope: `e17dee90` plus the uncommitted TIM-R1 correction and scheduler-reset transfer.
**No findings in this scope. TIM-R1 is closed; scoped timing/limit and reset transfers
are accepted.** Prior timing review evidence remains applicable to the unchanged parser;
this review checks the correction and subsequent reset integration independently.

| Responsibility | Source evidence and verdict |
|---|---|
| RESP-WORK-INPUT-SCHEDULE / RESP-WORK-SCHEDULE-INPUT | SchedulerWorkInput initializes the accepted volatile long once; tick, per-message headers and diagnostics read that value. Live limit changes are decoded before assignment/reset. The only production maxMessages accessor call is construction; no live setter caller remains. TIM-R1 is corrected without a parallel mutable startup limit. |
| RESP-WORK-SCHEDULER-RESET | SchedulerResetParser owns bool acceptance and uses WorkConfigurationExpressions for authoring/resolved modes; validation results expose no accepted flag on error/defer. Runtime invokes it only for a declared field, before changing rate/limit/count. Boolean false and omission do not request a reset. |
| RESP-WORK-PATCH-POLICY | Selected operational fields delegate to the canonical reset/limit parsers before CP configuration merge/state writes. The allowlist and bootstrap limitations retain their documented scope; no local boolean decoder remains. |
| RESP-SCENARIO-VALIDATE | Selected reset/timing fields bypass generic catalogue semantic checks; WorkConfigurationFindings projects canonical errors/deferred paths. Capability metadata already specifies boolean. The REST assertion correction follows accepted numeric-text behavior and does not weaken reset validation. |

Repository-wide reset/limit consumer search across Java and non-Java sources:
`/tmp/b02-reset-review-owners.txt`. Inspected parser, policy, SDK builder/input/properties,
CP update-to-listener path, Scenario Manager projector/catalogue and their tests.
Controller scenario reset, CP empty-config reset and TCP mock response resets have
different ownership. No competing reset parser or new infrastructure effect found.

**130 tests passed**, including the handoff suites plus InputScheduleParserTest and
WorkerControlPlaneRuntimeTest. Command:
`./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service,trigger-service -am '-Dtest=SchedulerResetParserTest,InputScheduleParserTest,WorkPatchPolicyTest,SchedulerWorkInputTest,InputSettingsValidationComponentTest,WorkIOConfigBinderTest,TriggerSchedulerIntegrationTest,WorkerControlPlaneRuntimeTest,ScenarioControllerTest#bundleValidationRejectsSelectedIoConfigTypeMismatches,RepositoryImportBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false test`.
Log: `/tmp/b02-reset-review-tests.log`.

A fresh temporary public-API probe uses real SchedulerWorkInput/RateSchedulePolicy with
CP snapshots supplied through the registered listener and a counting WorkerRuntime.
Limit changes, stop/start, disable/re-enable, false/true/omitted reset and rejection
without changing the exhausted count pass. A fixed-seed unlimited loop allocates
**0 bytes/message** across 1,000,000 dispatches after the same warm-up count in the
measured calling thread. Source/result: `/tmp/b02-reset-review/SchedulerReviewProbe.java`,
`result.txt`. This is not whole-worker throughput or deployed acceptance. Prior /tmp
probe files are no longer present; their historical baseline was not rerun.

Six passes: plan outcomes and owner deletion supported; headers/file responsibilities
aligned; no new library or fallback; errors omit raw values; accepted state replaces
per-message decoding. Parser/report repetition may be considered in the separately
scheduled phase simplification, without removing validation or separate ownership.
`git diff --check` passes. No permanent test/scanner, production edit, commit or deployment.
Full candidate/startup-shape validation and input-local enablement remain B02 work;
existing reset replay/concurrency behavior is not certified by this parsing transfer.
Full B02 and user-deferred SEL-R1 remain open; simplification follows full phase acceptance.
