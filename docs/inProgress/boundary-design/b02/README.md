# B02 settings and authoring execution

Status: implementation started, not accepted. B01 was committed as `eb681ee7` on
2026-09-08 after separate review. This task does not run an automatic acceptance review.

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
Inherited producer gap remains: SwarmWorkerSpecFactory.putEnvIfPresent still trims/drops
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
