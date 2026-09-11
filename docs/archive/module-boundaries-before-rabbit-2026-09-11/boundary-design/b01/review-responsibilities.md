# B01 separate review after responsibility adoption

Date: 2026-09-08. Historical verdict: **changes required; B01 was not accepted**.
Current disposition: [separate correction review](rv2-correction-review.md) accepts RV2
and B01. Findings and execution notes below retain their original checkpoint context.
This is a separate review task, not an execution-goal self-review or a repair loop.

Correction status: RV2 descriptions/headers corrected on 2026-09-08 at the user's
request; see the implementation note below. RV1's wiring-test requirement was later
superseded by the human boundary-verification policy; see the final policy note.
The findings, line
references and fingerprint below describe the reviewed snapshot, not the corrected
headers; no independent acceptance of the RV2 correction or B01 is claimed.

## Scope

Reviewed base `e0d378711b6447f8bb3e0f8bbf787f9b06c2b5bb` plus the uncommitted B01
extraction, subsequent corrections, single import test and responsibility adoption.
At review time, the 157-file adoption scope had SHA-256
`a9c4b265cc402070dc62a8405b366a409cd8057c02464a5616b537070665bd35`
using the algorithm in `responsibility-adoption.md`. This does not identify every
uncommitted repository file. The 51 current records and target B01 gates were read
separately; later B02–B07/C-stage ownership is not claimed as implemented.

## Findings

### RV1 — historical HIGH, requirement superseded: V03 can pass with production wiring disabled

Disposition: the user rejected tests of module/bean usage as ownership verification.
The required test expansion below is withdrawn, not technically fixed or evidence of
B01 acceptance. Source/import/dependency review obligations still apply; the negative
experiment is retained as historical evidence of the removed tests' limits.

`WorkControlFactoryPolicyTest` lines 23–39 constructs both factories and the Work
customizer itself. It loads Common CP configuration, but bypasses the SDK bean
selection that the test is supposed to help protect. The main
`WorkControlCompositionTest` exercises scheduler/CSV/Redis plus NONE; it does not
exercise the Rabbit Work branch with the production-selected Work customizer.

`TriggerSchedulerIntegrationTest` line 34 explicitly constructs
`new TriggerSchedulePolicy()`. It proves delivery of intermediate revisions to that
object, but not selection of the service policy over `RateSchedulePolicy` through
`TriggerSchedulingConfiguration` and the SDK. Repository search found no test that
loads that configuration or asserts this production policy selection.
`ApplicationBeeNameTest` loads only its own properties configuration; the older SDK
queue-resolution test checks IO definitions, not these policies.

Negative experiment, with both changes applied together:

1. Remove `@Configuration(proxyBeanMethods = false)` from
   `trigger-service/src/main/java/io/pockethive/trigger/TriggerSchedulingConfiguration.java`.
   Component scanning no longer registers the service policy through that class.
2. Change only the SDK `virtualThreadRabbitContainerCustomizer` bean condition from
   input type `RABBITMQ` to an unmatched probe value. Normal Rabbit Work startup can
   no longer select that customizer.
3. Run `WorkControlCompositionTest`, `WorkControlFactoryPolicyTest`,
   `TriggerSchedulePolicyTest`, and `TriggerSchedulerIntegrationTest` in the SDK/Trigger
   reactor: **15 tests passed**, zero failures/errors/skips, `BUILD SUCCESS`.

The experiment demonstrates a verification hole, not a claim that the unmodified
application currently fails. Both source files were restored byte-for-byte in a
`finally` block; the 157-file fingerprint above was rechecked. Restored production
classes were recompiled and the broader 77-test selection passed again.

Original requested correction (now withdrawn): extend the existing composition tests to obtain factories,
customizers and the Trigger policy from the actual application/SDK configuration.
Exercise the Rabbit Work branch with distinct CP/Work policies and their lifecycle,
and assert Trigger selection and interval/single-request behavior through the selected
input. Removing either production registration must fail its relevant test. Keep
the useful intermediate-revision test. No additional architecture scanner is needed.

Affected records: RESP-WORK-COMPOSITION, RESP-CP-LISTENER-POLICY,
RESP-WORK-RABBIT-POLICY, RESP-WORK-SCHEDULE-INPUT, RESP-TRIGGER-POLICY.
Required gate: full V03 in `docs/architecture/work-plane-boundaries.md` §6–7.

### RV2 — MEDIUM: current responsibility records describe nonexistent collaborators

Three concrete disagreements were found in the new canonical map:

| Record / location in `docs/architecture/runtime-responsibilities.md` | Actual source path and consequence |
|---|---|
| RESP-CP-JSON-CONFIG, line 210: ControlPlaneCodec consumes the helper's mapper | `common/control-plane-core/.../codec/ControlPlaneCodec.java:create` constructs its own JsonMapper. `ControlPlaneJson.mapper()` is used by Controller `RabbitConfig` and `JournalControlPlanePublisher` composition for non-wire projections. The helper's original JavaDoc explicitly says non-wire; its new Responsibility line misleadingly says canonical control serialization. Changing this helper will not change the wire codec. |
| RESP-DB-QUERY-WORK, line 722: TemplateRenderer resolves expressions | `DbQueryWorkerImpl` constructs `DbQueryRunner(mapper, loader, NamedSqlParser, executor)`. The runner creates `DbValueResolver`, binds named SQL parameters and calls `DbStatementExecutor`. There is no TemplateRenderer/Pebble/SpEL/render call in DB query production sources. This record invents an engine dependency and hides the actual parameter-resolution path. |
| RESP-WORK-MESSAGE-TEMPLATE, line 448: TemplatingInterceptor applies MessageTemplateRenderer | `TemplatingInterceptor.intercept` directly calls TemplateRenderer on a body template, using its own context construction. MessageTemplateRenderer separately maps body/path/method/headers and is called by Generator, Request Builder and the offline diagnostic. These are not the documented delegation chain. Their retained context/parsing differences must remain visible for B02, not be described as already delegated. |

These are introduced documentation/header defects, not evidence of new runtime
duplication. In particular the non-wire mapper and wire codec have distinct scopes;
do not merge them merely to make the incorrect record true. Correct the current
records and affected header wording from the real call paths, retaining the original
contract owners and recording the limited scope of the existing tests.

## Search and call-path evidence

Repository searches used `rg` over Java sources with
`--glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'`.
Test coverage searches used `--glob '*Test.java' --glob '!**/target/**'`.
The named candidates below were inspected; a symbol search alone is not proof that
differently named implementations do not exist.

| Search family | Patterns / inspected alternatives |
|---|---|
| W — Work wire and values | `WorkItemEnvelope\|WorkItemStepEnvelope\|WorkItemJsonCodec\|WorkItemSchemaValidator\|payloadEncoding`; request/result constructors; RabbitWorkItemConverter versus WorkItem payload convenience conversion. Candidate log `/tmp/b01-review-wire-candidates.txt`. |
| T — templates and sequences | `TemplateRenderer\|SequenceAccess\|RedisSequenceGenerator\|MessageTemplateRenderer`; `new PebbleTemplateRenderer\|new RedisPushSupport\|new RedisUploaderInterceptor`; SDK bean construction, Processor's `@Autowired` constructor, Redis output/capture constructors, Scenario validation and offline diagnostics. Candidate log `/tmp/b01-review-template-candidates.txt`. |
| S — state, selection and auth | `WorkerState\|ScheduledInvocationPolicy\|WorkInputFactory\|WorkOutputFactory\|TokenStore\|AuthTokenKeys`; `state.updateConfig\(\|mutateStatusData\(`; input/output initializers, scheduler callback, rate/Trigger policies, RedisTokenStore and shared token-key validation. Candidate log `/tmp/b01-review-state-candidates.txt`. |
| C — control, context and policy | `ControlPlaneJson\|StatusEnvelopeBuilder\|@RabbitListener\|setErrorHandler\|setTaskExecutor\|ControlPlaneCodec`; all four production Rabbit listeners, CP configuration/customizer, Work customizer, publisher, codec, Controller mapper consumers, status projection and reserved-key filtering. Candidate log `/tmp/b01-review-control-candidates.txt`. |
| G — gate coverage | `TriggerSchedulingConfiguration\|triggerSchedulePolicy\|rateSchedulePolicy\|VirtualThreadRabbitContainerCustomizer\|ApplicationContextRunner\|SpringBootTest`; inspected named composition tests and older IO/properties tests. RV1 includes a negative experiment, not just an empty search. |

The following is review evidence linked to the current records, not another ownership
catalogue. All rows share the revision above and refer to the matching architecture
ID/header. `Supported` means the stated bounded observation is supported; it is not
approval of an entire legacy service. `Limited` identifies an area not fully verified
in this review. The findings and unverified gates prevent overall acceptance.

Abbreviations for source paths: `api` = `common/work-api/src/main/java/io/pockethive/work/api`;
`sdk` = `common/worker-sdk/src/main/java/io/pockethive/worker/sdk`;
`cp` = `common/control-plane-spring/src/main/java/io/pockethive/controlplane/spring`;
`obs` = `common/observability-core/src/main/java/io/pockethive/observability`.
Service class names refer to the corresponding service's production Java directory.

| Responsibility | Inspected owner / consumer path and alternative-owner evidence | Verification, verdict and limit |
|---|---|---|
| RESP-WORK-ITEM | W: api/WorkItemBuilder constructs WorkItem and explicit steps; envelope conversion is a separate codec concern. | WorkItemTest passed. Supported for construction/history; no claim of deep immutability of arbitrary nested header values. |
| RESP-WORK-WIRE | W: sdk/transport/rabbit/RabbitWorkItemConverter delegates to api/WorkItemJsonCodec and its packaged-schema validator. | WorkItemJsonCodecTest passed; one canonical envelope codec found on the inspected path. Payload convenience JSON is distinct. |
| RESP-WORK-HTTP-CONTRACT | W: shared request/result records; producer paths use their factories rather than service-local envelope constructors. | TransportEnvelopeDtosTest passed; limited to relocated value/field contracts, not HTTP execution. |
| RESP-WORK-TCP-CONTRACT | W: shared TCP records/factories; socket execution remains Processor infrastructure. | TransportEnvelopeDtosTest passed; no socket implementation moved into API. |
| RESP-WORK-ISO-CONTRACT | W: shared ISO records/schema reference; schema loading remains Request Builder. | TransportEnvelopeDtosTest passed; full ISO schema/transport validation not rerun. |
| RESP-WORK-CAPABILITY | S: SDK workerRegistry delegates to WorkerDefinitionDiscovery; binders resolve declared IO before registries select factories. | Composition tests passed; startup/patch parsing remains B02, not certified SSOT. |
| RESP-WORK-CONTEXT | S/C: DefaultWorkerContextFactory creates a WorkerState-backed view, not a second accepted-config store. | DefaultWorkerContextFactoryTest passed; mutable underlying typed configuration/atomic snapshots remain B03 concerns. |
| RESP-WORK-STATUS | S/C: WorkerStatusPublisher mutates contributions; WorkerControlPlaneRuntime filters RESERVED_STATUS_KEYS before root status construction. | WorkerStatusPublisherTest passed. Supported distinction between contributions and accepted control state. |
| RESP-AUTH-VALUES | S: moved profile/reference/material values are shared with AuthRuntime; product auth API has a different scope. | Compilation covered by fresh reactor; full profile validation behavior not rerun. Limited to relocation and ownership scope. |
| RESP-AUTH-TOKEN-STORE | S: TokenStore port is implemented by RedisTokenStore; both it and Scenario validation call AuthTokenKeys validation. | Source path supported; refresh-lease behavior not freshly exercised. B06 infrastructure/connection ownership remains open. |
| RESP-OBS-CONTEXT | C: WorkerObservabilityInterceptor adds a Hop, delegates MDC propagation to ObservabilityContextUtil and attaches the context to the result. | WorkerObservabilityInterceptorTest passed. Mutable context is not a second control-intent writer. |
| RESP-CP-JSON-CONFIG | C: codec creates its own mapper; helper feeds Controller non-wire consumers. | **Violated: RV2.** ControlPlaneCodecTest passes but does not verify the invented helper dependency. |
| RESP-CP-STATUS-ENVELOPE | C: StatusPayloadFactory/ControlPlaneEmitter and Controller status publisher use StatusEnvelopeBuilder; runtime supplies observations. | Source path supported; no fresh full status-builder suite in this selection. Terminal lifecycle success is outside this builder. |
| RESP-IDENTITY-BEE-NAME | C: BeeNameGenerator uses its name components and role map; it does not construct queue/exchange names. | Source inspection; utility behavior test not rerun. Supported bounded role, not configuration-default consolidation. |
| RESP-TEMPLATE-RENDER | T: PebbleTemplateRenderer owns engine calls; SpEL helpers use explicit functions and injected SequenceAccess. | SequencePortRenderingTest passed. BlockingTypeLocator is retained behavior, not proof of a complete sandbox. |
| RESP-TEMPLATE-SEQUENCE | T: SequenceFunctions calls injected port; configured adapter delegates to existing generator; DisabledSequenceAccess throws. | Selected/disabled SDK composition tests passed. Processor injected constructor and Redis output/capture pass the selected renderer. Convenience/global paths remain B06 debt. |
| RESP-CP-COMPOSITION | C: Common/Worker/Manager configurations use CP descriptors and declarations, with Work requirements removed. | Non-Rabbit/NONE composition and wire tests passed. CP declaration beans are nonempty; live broker behavior was not tested. |
| RESP-CP-LISTENER-POLICY | C/G: all four production listeners name the CP factory; poison customizer checks that name. | Customizer behavior supported; **actual joint policy composition unverified by gate: RV1**. |
| RESP-CP-DECLARATIONS | C: ControlPlaneTopologyDeclarableFactory maps descriptors; Work provisioning stays with Controller. | ControlTopologyOwnershipTest (CP-N01) and composition tests passed. No claim that B04 topology consolidation is complete. |
| RESP-CP-PUBLISH | C: AmqpControlPlanePublisher delegates serialization to ControlPlaneCodec then calls AMQP; it receives a canonical envelope, not pre-encoded bytes. | AmqpControlPlanePublisherTest/ControlPlaneWireShapeTest passed. Record wording “without re-encoding” must be read as no alternative codec, not no call to encode. |
| RESP-WORK-COMPOSITION | T/S/G: SDK passes selected renderer and uses exactly-one input/output initialization. | Missing/duplicate factory tests passed; **RV1 leaves Rabbit Work and Trigger production policy selection insufficiently protected**. |
| RESP-WORK-IO-CONFIG | S: discovery and registry initializers still bind config; raw runtime patches remain separate. | Supported as explicit current debt only. Startup/patch equivalence is unverified here and belongs to B02. |
| RESP-WORK-ADAPTER-SELECTION | S: input/output initializers filter supports(), require exactly one match, then create; direction scopes are distinct. | Actual SDK missing/duplicate/NONE tests passed. No ordering-based ambiguity resolution on these paths. |
| RESP-WORK-STATE | S: WorkerControlPlaneRuntime updates accepted config; status/counter callers mutate separate facts in WorkerState. | Limited source tracing. No fresh full state-machine suite; atomic revision/configuration consolidation remains B02/B03. |
| RESP-WORK-INVOCATION | S: DefaultWorkerRuntime → WorkerInvocation → WorkOutputRegistry; normal Rabbit factory supplies an empty alternate result callback. | Source tracing supports the normal path. Alternate direct-publish callbacks/async settlement remain unverified B05 behavior. |
| RESP-WORK-METRICS | C: WorkerMetricsInterceptor observes chain success/timing and returns the result; metrics do not write accepted config. | WorkerMetricsInterceptorTest passed. Its success means invocation-chain success, not verified downstream business completion. |
| RESP-WORK-MESSAGE-TEMPLATE | T: MessageTemplateRenderer is used by Generator, Request Builder and diagnostics; TemplatingInterceptor calls TemplateRenderer directly. | **Violated: RV2.** Retained context/default differences are visible, not a proven shared mapping path. |
| RESP-WORK-SCHEDULE-CONTRACT | S: SchedulingState validates availability/rate; update and plan are serialized by both policy implementations. | Rate/Trigger policy tests passed. A projection is not another accepted-config writer. |
| RESP-WORK-SCHEDULE-INPUT | S/G: scheduler callback delivers each revision under projectionLock, policy plans quota, runtime dispatches. | Intermediate true/false and disabled updates passed. **Production Trigger selection gap: RV1**; raw overrides remain B02. |
| RESP-WORK-RATE-POLICY | S: RateSchedulePolicy owns fractional carry and resets it on disabled updates. | RateSchedulePolicyTest passed. This does not independently revalidate every inherited tick-interval/rate combination. |
| RESP-TRIGGER-POLICY | S/G: TriggerSchedulePolicy retains pending request and interval state; configuration should select it. | Policy/revision behavior passed; **selection gate violated by RV1 mutation experiment**. |
| RESP-WORK-RABBIT-POLICY | C/G: VirtualThreadRabbitContainerCustomizer only changes named Work factory; destroy closes its own executor. | Direct policy test passed; **SDK registration is not covered by that test: RV1**. |
| RESP-WORK-RABBIT-TRANSPORT | W/S: RabbitWorkOutput snapshots exchange/key/delivery mode; converter delegates codec; input delegates runtime. | Source tracing supported destination/codec scopes. Settlement, confirmation and alternate callbacks remain B05, not accepted here. |
| RESP-WORK-CSV-INPUT | S: selected CsvDataSetWorkInput receives CSV settings and worker state; no Rabbit Work config required at construction. | Actual SDK CSV/NONE startup passed. File iteration/exhaustion not freshly exercised. |
| RESP-WORK-REDIS-DATASET | S: selected RedisDataSetWorkInput is distinct from token/sequence/push capabilities. | Actual SDK Redis/NONE startup passed without connecting to the dataset. Cursor/order/exhaustion remains limited evidence. |
| RESP-WORK-REDIS-PUSH | T/S: factory constructs shared support with selected renderer; output and diagnostic capture have separate policies. | Constructor reachability supported. Redis connection/parsing duplication remains B02/B06; no live list writes tested. |
| RESP-WORK-NONE-OUTPUT | S: NoopWorkOutput only drops/logs the selected result. | Actual SDK NONE compositions passed; no output connection or exchange needed. |
| RESP-WORK-AUTH-RUNTIME | S: AuthRuntime remains existing mixed file/profile/HTTP/store coordination; the moved values do not move its effects. | Limited to extraction/dependency scope. Full auth policy/SSOT acceptance is not supplied by this review. |
| RESP-GENERATOR-WORK | T/W: Generator constructs MessageTemplateRenderer with injected renderer; Rabbit metadata coupling remains visible. | Source call path supported; Generator behavior suite not rerun. B07 coupling remains. |
| RESP-MODERATOR-WORK | Imported API/header migration keeps business moderation in ModeratorWorkerImpl. | Limited: no fresh full moderation/state/effect audit or behavior suite. No broad service SSOT verdict. |
| RESP-REQUEST-BUILD | T/W: RequestBuilder uses MessageTemplateRenderer; schema registry/XML codec remain separate local collaborators. | Mapping path supported; full schema/auth/file-effect behavior not freshly exercised. |
| RESP-PROCESSOR-EXECUTE | T/W: @Autowired Processor constructor receives selected renderer; protocol handlers/ResponseBuilder remain service collaborators. | Injected constructor reachability supported; convenience constructors are not the inspected Spring path. Protocol execution not freshly tested. |
| RESP-HTTP-SEQUENCE-WORK | HttpSequenceWorkerImpl creates runner with template loader, call executor and target resolver; delegates run(). | Source supports current mixed composition. Full step/auth/capture ownership and runtime behavior remain limited. |
| RESP-DB-QUERY-WORK | Worker → runner → NamedSqlParser/DbValueResolver → DbStatementExecutor; no template engine. | **Violated: RV2.** Executor delegation verified from source; SQL/DB behavioral suite not rerun. |
| RESP-POSTPROCESSOR-WORK | Worker calls TxOutcomeProjector and existing sinks; metric state is a projection. | Limited: no fresh full sink/unknown-outcome audit. Existing fallback semantics are not approved by naming this record. |
| RESP-CLEARING-EXPORT | Worker/projector/assembler and batch writer have distinct mapping/rendering/persistence steps. | Limited to current role/import migration; full file-output/record-validation suite not rerun. |
| RESP-TRIGGER-WORK | TriggerWorkerImpl executes the dispatched action; policy test constructs a scheduler around mocked dispatch. | Action implementation is not newly accepted through the scheduler test. RV1 concerns policy selection, not a proven action regression. |
| RESP-ORCHESTRATOR-INGRESS | C: listeners explicitly select CP factory; codec/observation owners retain existing roles. | CP-N01 passed; no full Orchestrator convergence re-audit in this selection. Existing listener debt remains C-stage. |
| RESP-CONTROLLER-CONTROL | C: configuration composes journaled publisher; local runtime owner remains SwarmRuntimeCore, QueueStatsPort differs from meter projection. | CP-N01 source/ownership checks passed. B04 provisioning/stats and complete Controller workflow audit remain open. |
| RESP-SCENARIO-VALIDATE | T: ScenarioBundleValidator uses syntax API with disabled sequences; offline renderer is a diagnostic consumer. | Disabled sequence behavior passed. Full bundle validation/schema/parsing SSOT remains limited/B02. |
| RESP-TEST-WORK-FIXTURES | W/C: relocated fixture package is test-scoped; import rule excludes only fixture/E2E owners. | RepositoryImportBoundaryTest passed. Production artifact bans remain in Maven Enforcer, not duplicated into another scanner. |

## Verification and six review passes

Fresh behavior/composition selection:

```bash
./mvnw -B -ntp -pl common/worker-sdk,trigger-service,orchestrator-service -am \
  -Dtest=RepositoryImportBoundaryTest,ControlPlaneCodecTest,ControlPlaneWireShapeTest,ControlTopologyOwnershipTest,AmqpControlPlanePublisherTest,ControlPlaneRabbitPoisonMessageCustomizerTest,WorkControlCompositionTest,WorkControlFactoryPolicyTest,WorkItemTest,WorkItemJsonCodecTest,TransportEnvelopeDtosTest,SequencePortRenderingTest,RateSchedulePolicyTest,TriggerSchedulePolicyTest,TriggerSchedulerIntegrationTest,WorkerStatusPublisherTest,DefaultWorkerContextFactoryTest,WorkerMetricsInterceptorTest,WorkerObservabilityInterceptorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**77 tests passed**, zero failures/errors/skips. Repeated after restoring the negative
experiment to recompile restored classes: same result. Logs:
`/tmp/b01-separate-review-tests.log`, `/tmp/b01-review-v03-mutation.log`,
`/tmp/b01-separate-review-restored-tests.log`. Negative experiment used the same Maven
options with `-pl common/worker-sdk,trigger-service` and the four classes listed in RV1.
No service ports, deployment, external workload or live infrastructure was tested.
Prior 1202-test/package/docs results remain historical implementation evidence; they
are not reported as freshly rerun by this review.

| Required pass | Review result |
|---|---|
| Plan outcome | Extraction direction and staged B02–B07 work are retained. RV1 prevents claiming full B01 V03; module/package success alone cannot release B02. |
| Style/engineering | Stable IDs and current/target distinction are present. RV2 violates architecture/header/code agreement. Existing mixed files remain debt; import/comment moves do not waive the no-new-behavior rule. |
| Conciseness | Single 116-line import test is within the explicit human exception. Keep its inline rule table and standard Enforcer; correct existing composition tests instead of adding another checker. |
| Security | Reviewed moved wire/schema boundary, disabled sequence path and CP/Work error-policy scoping. No new runtime security regression established on those paths; full template sandbox, auth runtime and service security are not certified. |
| Library necessity | Existing Jackson/schema, Pebble/SpEL, JUnit/Spring test tools and Maven Enforcer serve these changes. Neither finding requires another library. |
| Readability/maintainability | Linking headers to records helps navigation, but invented delegation is harmful maintenance guidance (RV2). Concrete production selection assertions are needed for trustworthy V03 evidence (RV1). |

The import test checks declared imports in conventional Maven production directories;
its documented wildcard/FQ/reflection/comment limitations are accepted scope, not
new findings. No claim of repository-wide SSOT approval follows from its green result.

Only review evidence/status documentation was changed permanently in this task.
No corrective production/test changes, commit, push, B02 start or automatic repair
loop were performed.

## RV2 correction — implementation note, 2026-09-08

User scope at that time: correct finding 2 only. RV1 was still open; this note is implementation
evidence for the correction, not another review or B01 acceptance.

Corrected the existing RESP-CP-JSON-CONFIG, RESP-DB-QUERY-WORK and
RESP-WORK-MESSAGE-TEMPLATE records in `docs/architecture/runtime-responsibilities.md`.
Kept their stable IDs and actual implementations. Four matching header corrections:

- `common/observability-core/src/main/java/io/pockethive/observability/ControlPlaneJson.java`:
  non-wire projection mapper, with wire ownership explicitly excluded.
- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime/TemplatingInterceptor.java`:
  invocation body rendering and appended Work step through TemplateRenderer.
- `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/templating/MessageTemplateRenderer.java`:
  mapping MessageTemplate body/path/method/headers through its injected renderer.
- `db-query-service/src/main/java/io/pockethive/dbquery/DbValueResolver.java`:
  payload/headers/vars path lookup and declared parameter-type conversion.

Fresh source checks repeated repository-wide `rg -n 'ControlPlaneJson|MessageTemplateRenderer'`
with the Java/production exclusions listed above. Inspected codec `create()`, Controller
`RabbitConfig.objectMapper`, `SwarmControllerControlPlaneConfiguration` and
`runtime/JournalControlPlanePublisher`, both template callers and their consumers,
plus `DbQueryRunner.bindSql`/`DbValueResolver.resolveSource`. DB query search for
`TemplateRenderer|Pebble|Spel|render\(` returned no production matches; the positive
SQL parser/resolver/executor call path is now documented explicitly.

Exact comparison against four pre-task file copies confirmed only five specified
JavaDoc lines changed. No runtime behavior, imports, dependencies or test/scanner
implementation changed; runtime suites were not rerun for this documentation correction.
The historical review fingerprint and findings above are preserved as before evidence.

Validation completed: `npm --prefix docs-site run build` and `git diff --check`
passed. Documentation build log: `/tmp/b01-rv2-docs.log`.

## Human boundary-verification decision — 2026-09-08

The user rejected tests whose purpose is proving that a module/bean/implementation
is used. Canonical policy is now `docs/REVIEW_RULES.md#boundary-verification-and-test-value`;
the workflow, checklist, V03 design and execution plan reference that policy.
Module ownership uses import/dependency enforcement and source review against
architecture/header contracts. RV1's requested wiring-test expansion is superseded;
its earlier mutation experiment remains historical evidence, not a current blocker
or a claim of a repaired runtime defect. RV2 remains corrected pending separate review.
This task does not accept B01 or start B02.

Removed the two artificial factory test classes:
`WorkControlFactoryPolicyTest` and `ControlPlaneRabbitPoisonMessageCustomizerTest`.
Removed three cases from `WorkControlCompositionTest`: positive selected-port identity,
CP factory/publisher availability after Work stop, and manual factory error-handler
identity. Also removed its private-field, bean-count and implementation-class checks.
No replacement ownership/wiring tests or scanners were added.

Retained cases exercise startup without Rabbit Work settings with nonempty CP
declarations, missing/ambiguous configuration rejection and disabled sequence effects.
Trigger policy tests retain the concrete lost-single-request regression. The one
repository import scanner and Maven dependency rules remain unchanged. Production
implementation was not changed by this policy/test cleanup.

Validation command (first with `clean test` to remove stale deleted test classes,
then `test` after removing the positive selected-port case):

```bash
./mvnw -B -ntp -pl common/worker-sdk,trigger-service -am \
  -Dtest=RepositoryImportBoundaryTest,WorkControlCompositionTest,SequencePortRenderingTest,RateSchedulePolicyTest,TriggerSchedulePolicyTest,TriggerSchedulerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Logs: `/tmp/b01-boundary-policy-tests.log`, `/tmp/b01-boundary-policy-tests-final.log`,
`/tmp/b01-boundary-policy-docs.log`. No deployed checks or runtime-service calls were
introduced as a substitute for the withdrawn wiring-test gate.

Final validation: **17 tests passed**, zero failures/errors/skips; documentation
build and `git diff --check` passed. The clean run removed stale class files for
deleted tests. No new behavior tests were introduced by this cleanup.

## Pragmatic test reduction — 2026-09-08

The user approved reducing the preceding 17-case focused selection to these **8 cases**:

| Test class | Cases | Retained purpose |
| --- | ---: | --- |
| `RepositoryImportBoundaryTest` | 2 | Repository-wide forbidden imports and the existing rule-matching sanity check. |
| `WorkControlCompositionTest` | 1 | Scheduler/NONE starts with CP declarations without a fictitious Work exchange or input queue. |
| `SequencePortRenderingTest` | 2 | Sequence argument/default/reset behavior; syntax validation avoids effects while disabled rendering fails explicitly. |
| `RateSchedulePolicyTest` | 1 | Fractional quota and reset across disable/enable. |
| `TriggerSchedulePolicyTest` | 1 | Interval and pending single-request behavior across state revisions. |
| `TriggerSchedulerIntegrationTest` | 1 | A single-request true→false update between ticks still reaches dispatch. |

Removed the full-SDK disabled-sequence case, the CSV/Redis startup variants,
four missing/duplicate factory cases, renderer-instance selection and the separate
missing-trigger-config case. The single startup regression constructs a Spring
context and CP declarations; it does not verify a live broker or CP responsiveness.
Existing tests outside this agreed selection were not expanded or removed.

The preceding 17-test result remains historical evidence. Ownership and explicit
configuration requirements remain unchanged; removing a test does not remove its
production contract. No production changes, replacement tests, scanners or new
libraries were introduced. RV2 remains pending separate review; B01 is not accepted.

Validation uses the same six-class Maven command above. Current logs:
`/tmp/b01-pragmatic-tests.log`, `/tmp/b01-pragmatic-docs.log`.

Validation completed: **8 tests passed**, zero failures/errors/skips; documentation
build and `git diff --check` passed.
