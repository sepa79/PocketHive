# B01 responsibility/header adoption

Date: 2026-09-08. Status: **delivered for separate review**.
User authorization: apply the new development/review rules now, before further work.

## Scope and resulting artifacts

Source base: `e0d378711b6447f8bb3e0f8bbf787f9b06c2b5bb`, with the existing uncommitted
B01 implementation and corrections. Scope is the 157 existing/new production Java
files returned at task start by `git diff --name-only --diff-filter=AM --
'*src/main/java/*.java'` plus `git ls-files --others --exclude-standard --
'*src/main/java/*.java'`. Deleted old owners are not header targets.

- `docs/architecture/runtime-responsibilities.md` contains 51 current-owner records,
  linked from the architecture hub and target boundary design. They identify current
  behavior, delegates/projections, forbidden actions, required effects and pending slices.
- All 157 scoped files now reference an existing record ID/anchor in `Contract`.
  Added 56 missing headers, retaining class documentation where present. Corrected
  the description of SpelFunctions: clock/random helpers are not pure functions.
- Java changes in this task are comments only. For each file, reversing the exact
  recorded comment edits reproduced the pre-task source text. No ownership implementation,
  public wire contract, configuration, scanner or test behavior was changed.
- The earlier single import scanner and standard Maven/ArchUnit checks remain as-is.
  Temporary edit assignments were not installed as a policy or validation generator.

The SHA-256 of the sorted adoption scope (UTF-8 relative path, NUL, binary SHA-256 of
each final source file) is `a9c4b265cc402070dc62a8405b366a409cd8057c02464a5616b537070665bd35`.
This identifies those 157 files, not the entire dirty repository or a deployed image.

## Implementation evidence supplied to review

These are inspected paths and search results supporting the record/header edits;
they are not an independent SSOT approval. Exact record IDs link the evidence to
the authoritative descriptions rather than creating another ownership table.

| Records | Search and inspected path | Observation / limit |
|---|---|---|
| RESP-WORK-WIRE, RESP-WORK-ITEM | Repository-wide `rg -n 'WorkItemJsonCodec' --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'`; `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/transport/rabbit/RabbitWorkItemConverter.java`, `common/work-api/src/main/java/io/pockethive/work/api/WorkItemJsonCodec.java` and `WorkItemSchemaValidator.java` | Converter delegates encode/decode to the codec; the package-private validator loads the packaged schema. WorkItem payload convenience JSON conversion is a different scope. Redis capture does not use the envelope codec. No claim that this symbol search excludes differently named codecs. |
| RESP-WORK-CAPABILITY, RESP-WORK-COMPOSITION, RESP-WORK-ADAPTER-SELECTION | `WorkerDefinitionDiscovery.discover`, SDK `workerRegistry`/`templatingRenderer`, and `WorkInputRegistryInitializer`/`WorkOutputRegistryInitializer` in `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/` | Discovery uses declaration/IO binders; the input/output initializers enforce exactly one matching factory in their distinct directions. The renderer receives SequenceAccess. Startup/patch parsing is still B02 work. |
| RESP-TEMPLATE-RENDER, RESP-TEMPLATE-SEQUENCE, RESP-WORK-REDIS-PUSH | Repository-wide `rg -l 'SequenceAccess|RedisSequenceGenerator' --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'`; `common/templating/src/main/java/io/pockethive/templating/SequenceFunctions.java`, `ConfiguredRedisSequenceAccess.java`, SDK `config/RedisSequenceConfiguration.java` and `runtime/RedisPushSupport.java`/`RedisUploaderInterceptor.java` | Expression arguments reach the port; the configured adapter reaches the existing generator. Global configuration and convenience constructors that construct an adapter remain. Their availability is not proof of a live bypass: selected composition/reachability still needs review. |
| RESP-WORK-STATE, RESP-WORK-STATUS, RESP-WORK-CONTEXT | `rg -n 'state.updateConfig\(|mutateStatusData\(' common --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'`; SDK `runtime/WorkerControlPlaneRuntime.java`, `WorkerState.java`, `WorkerStatusPublisher.java`, `DefaultWorkerContextFactory.java` | The inspected accepted-config update is in WorkerControlPlaneRuntime; WorkerStatusPublisher mutates separately scoped contributions. State and snapshots remain in SDK. This does not certify canonical patch parsing or atomic revision semantics for B02/B03. |
| RESP-WORK-SCHEDULE-CONTRACT, RESP-WORK-SCHEDULE-INPUT, RESP-WORK-RATE-POLICY, RESP-TRIGGER-POLICY | Repository-wide `rg -l 'ScheduledInvocationPolicy' --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'`; SDK `input/SchedulerWorkInput.java`/`RateSchedulePolicy.java`; `trigger-service/src/main/java/io/pockethive/trigger/TriggerSchedulePolicy.java` | Scheduler delivers state revisions and asks the selected policy for quota. Rate and trigger policies own different scheduling semantics; trigger owns pending single-request state. Raw scheduling overrides still await B02. |
| RESP-WORK-INVOCATION, RESP-WORK-RABBIT-TRANSPORT | SDK `runtime/DefaultWorkerRuntime.dispatch`, `input/rabbit/RabbitWorkInputFactory.create`, `transport/rabbit/RabbitMessageWorkerAdapter.dispatchSynchronously` and its builder | The normal factory supplies WorkerRuntime dispatch and an empty result-publisher callback; runtime publishes through WorkOutputRegistry. Other callback/direct-publication construction paths exist. No blanket single-publication/settlement acceptance is inferred. |
| RESP-CP-COMPOSITION, RESP-CP-LISTENER-POLICY, RESP-CP-DECLARATIONS, RESP-CP-PUBLISH, RESP-WORK-RABBIT-POLICY | `common/control-plane-spring/src/main/java/io/pockethive/controlplane/spring/` configuration/customizer/declaration/publisher classes and SDK `autoconfigure/VirtualThreadRabbitContainerCustomizer.java` | Headers retain the dedicated CP factory versus Work executor distinction and descriptor-only CP declarations. The focused composition/policy tests were rerun; deployed behavior was not. |
| RESP-CONTROLLER-CONTROL, RESP-ORCHESTRATOR-INGRESS | `swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmLifecycleManager.java` and `SwarmSignalListener.java`; Orchestrator `app/ControllerStatusListener.java` and `SwarmSignalListener.java` | SwarmLifecycleManager composes infrastructure and delegates state to SwarmRuntimeCore; it is not a second lifecycle state owner. Listeners dispatch to existing command/observation owners. Current records preserve these distinctions without claiming all listener/composition debt is removed. |
| Worker service records; RESP-SCENARIO-VALIDATE | Processor `ProcessorWorkerImpl`/protocol handlers/`ResponseBuilder`; Clearing `ClearingExportWorkerImpl`/`StructuredRecordProjector`/`ClearingExportFileAssembler`; DB and HTTP-sequence worker/runner pairs; Scenario `ScenarioBundleValidator`; `tools/scenario-templating-check/src/main/java/io/pockethive/tools/ScenarioTemplateValidator.java` | Read roles/call sites distinguish worker coordination, protocol/file effects and value projections. Scenario syntax/diagnostic callers use disabled sequences; offline diagnostics do not own bundle acceptance. B01 primarily relocated imports in these consumers; this task does not certify their full internal SSOT. |

For the remaining value/context/metrics/auth records, source descriptions, moved
contracts and existing test entrypoints were linked. Their forbidden actions and
absence of differently named competitors still require the per-responsibility review
evidence prescribed by the workflow. No record is accepted merely because it exists.

## Fresh verification

```bash
./mvnw -B -ntp -pl common/worker-sdk,trigger-service -am \
  -Dtest=RepositoryImportBoundaryTest,WorkItemJsonCodecTest,WorkControlCompositionTest,WorkControlFactoryPolicyTest,SequencePortRenderingTest,RateSchedulePolicyTest,TriggerSchedulePolicyTest,TriggerSchedulerIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -B -ntp -DskipTests package
npm --prefix docs-site run build
git diff --check
```

- Focused tests: **28 passed**, zero failures/errors/skips across the eight named
  test classes. Includes the repository import scanner and the actual SDK composition.
- Root package: all repository production/test compilation and packaging passed;
  tests outside the focused selection were not rerun.
- Documentation build passed. All 157 explicit header references resolve to the
  51 record anchors; the comment-only reversal check passed for every scoped source.
- Logs: `/tmp/b01-responsibility-tests.log`, `/tmp/b01-responsibility-package.log`,
  `/tmp/b01-responsibility-docs.log`.

## Handoff and remaining work

Use `docs/ai/RESPONSIBILITY_WORKFLOW.md` for subsequent development and the separate
review. Review must inspect each affected record/header/call path and supply its own
evidence. The records deliberately expose current mixed auth, Redis, configuration
and service implementations; documenting them does not waive violations or permit
new behavior in mixed files. The existing B02–B07/C-stage gates remain in force.

This applies the workflow to B01's current production change scope, not all 967
production files. No full repository ownership acceptance, runtime deployment,
B01 approval, B02 start, commit or push is claimed.
