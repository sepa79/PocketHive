# B01 RV2 correction review and acceptance

Date: 2026-09-08. Verdict: **no new findings in the correction scope; RV2 accepted; B01 accepted**.
B02 is the next implementation slice and has not started. No commit, push or deployment.

## Scope and continuity

This is the requested separate review of three corrected responsibility records and
four JavaDoc headers, applying the user's current boundary/testing policy. It is not
another full repository audit or an implementation-goal review/fix loop.

Base: `e0d378711b6447f8bb3e0f8bbf787f9b06c2b5bb` plus the uncommitted B01 change.
The 157-file production scope defined in `responsibility-adoption.md` now hashes to
`0648efbf33cec6c478d9ce3337cbb6803ae099ff1c100b3d27341c2c23b22f8a`.
Replacing the four corrected files in the digest input with their pre-RV2 copies
from `/tmp/rv2-header-before` reproduces the previous separate-review digest exactly:
`a9c4b265cc402070dc62a8405b366a409cd8057c02464a5616b537070665bd35`.
The exact diffs contain five JavaDoc line replacements, with no runtime changes.
This identifies the declared production scope, not every dirty repository file.

The earlier 51-row source review remains evidence for unchanged B01 responsibilities.
Its R1/R2 runtime corrections were accepted separately. The scanner-expansion remedy
and RV1's bean-selection-test requirement were superseded by explicit human decisions;
neither is described here as a fixed runtime defect. The test cleanup and subsequent
policy clarification are accounted for below. This review closes the remaining RV2
mismatch and supplies source evidence for the former RV1 composition concern.

## RV2 source evidence

Paths below are repo-relative. Each row refers to the unchanged stable ID/anchor in
`docs/architecture/runtime-responsibilities.md`; current ownership remains distinct
from target B02–B07 placement.

| Responsibility | Inspected owner, header and actual consumers | Alternative owners, effects and verdict |
| --- | --- | --- |
| RESP-CP-JSON-CONFIG | `common/observability-core/.../ControlPlaneJson.java:11` correctly declares non-wire projection configuration. Controller `RabbitConfig.objectMapper` calls it; `SwarmControllerControlPlaneConfiguration.swarmControllerControlPlanePublisher` passes it to `JournalControlPlanePublisher`, whose journal entries are projections. `common/control-plane-core/.../codec/ControlPlaneCodec.java:create` builds its own mapper and validates/encodes the wire envelope. | Followed the publisher delegate to `AmqpControlPlanePublisher`; the projection mapper does not replace its codec. Inspected Orchestrator `JacksonConfiguration` as a separate application mapper, and WorkItemJsonCodec as a different wire contract. No new competing control-wire owner introduced by RV2. **Supported.** |
| RESP-DB-QUERY-WORK | `DbQueryWorkerImpl` constructs `DbQueryRunner` with `DbQueryTemplateLoader`, `NamedSqlParser` and the injected `DbStatementExecutor`. `DbQueryRunner.bindSql` uses `DbValueResolver.resolve` to produce ordered `BoundSql`; `executeWithRetry` invokes the executor. The corrected resolver header names payload/headers/vars paths and declared-type conversion. | `JdbcDbStatementExecutor` is the production port implementation and owns connections/prepared statements. The runner retains template loading, retry and result mapping; those have not moved in this correction. Inspected source/root conversion and executor calls positively; DB production search contains no TemplateRenderer/Pebble/SpEL/render call. **Supported.** |
| RESP-WORK-MESSAGE-TEMPLATE | `MessageTemplateRenderer.render` constructs context and maps body/path/method/headers through its injected TemplateRenderer. Inspected Generator and Request Builder constructors/calls and `tools/scenario-templating-check/.../ScenarioTemplateValidator` diagnostic rendering. SDK `templatingInterceptor` supplies TemplateRenderer directly to `TemplatingInterceptor`, whose `intercept` appends a Work step and proceeds. Both corrected headers match those paths. | Interceptor does not delegate to MessageTemplateRenderer. Payload/context/default handling remains separate; the record explicitly preserves that B02 debt. PebbleTemplateRenderer remains the common engine implementation, not a newly copied evaluator. No transport connection is opened by either field/body mapping class. **Supported for the correction; existing context duplication is not accepted as resolved.** |

Repository-wide source searches (production Java, excluding tests and build output):

```bash
rg -n 'ControlPlaneJson|MessageTemplateRenderer|TemplatingInterceptor|DbValueResolver|new DbQueryRunner|new NamedSqlParser|implements DbStatementExecutor' --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'
rg -n 'class .*Template.*Renderer|class .*ValueResolver|class .*SqlParser|class .*Db.*Executor|implements TemplateRenderer|JsonMapper.builder\(' --glob '*.java' --glob '!**/src/test/**' --glob '!**/target/**'
rg -n 'TemplateRenderer|Pebble|Spel|render\(' db-query-service/src/main/java
```

The final search returned no matches. Named candidates and their actual delegated
calls were inspected; empty search results alone do not establish SSOT.

## B01 composition disposition under current policy

The following source paths were inspected directly, without creating wiring tests:

- CP Common configuration imports `ControlPlaneRabbitListenerConfiguration`, which
  creates the dedicated CP factory. CP poison customization checks only its canonical
  factory name. All four production CP listeners explicitly select that factory.
- SDK enables `VirtualThreadRabbitContainerCustomizer` for `RABBITMQ` input; its
  mutation targets only `rabbitListenerContainerFactory`, and destruction closes its
  own executor. Rabbit Work endpoints use the default factory through the registrar.
  These are distinct factory/policy paths even when they share a connection factory.
- Trigger's `Application` scans its package; `TriggerSchedulingConfiguration` supplies
  the service policy. SDK `rateSchedulePolicy` is conditional on no policy bean.
  `schedulerWorkInputFactory` passes the injected policy through SchedulerWorkInputFactory
  and its builder. Input/output registries reject zero or multiple matches before create.
- `WorkerControlPlaneAutoConfiguration.workerControlPlaneDeclarables` passes only the
  CP descriptor, CP exchange and identity to the declaration factory. It has no Work
  exchange/queue argument; worker additional queues are excluded by the CP factory.

These observations support B01 ownership/composition under
`docs/REVIEW_RULES.md#boundary-verification-and-test-value`. They do not claim live
broker delivery, settlement or shutdown convergence. Those runtime capabilities keep
their planned behavioral checks in the applicable later slices.

## Verification and six review passes

The latest retained B01 selection already passed **8 cases**, zero failures/errors/skips
in `/tmp/b01-pragmatic-tests.log`: one repository import scanner with its rule sanity
case, one Scheduler/NONE startup regression, two sequence cases and three scheduling
cases. Inspected that log; it was not rerun or represented as a new review execution.
The startup case proves Spring context/declaration construction, not CP responsiveness.

Existing MessageTemplateRendererTest, TemplatingInterceptorTest and DbQueryRunnerTest
cover the respective behavior; their named cases were inspected, not rerun. No new unit
is extracted by RV2, and the verified five-line JavaDoc delta requires no new behavior
test. Future extractions must carry their unit tests and useful component tests under
the clarified policy; eight cases are not a limit on later coverage.

| Required pass | Result in this correction review |
| --- | --- |
| Plan outcome | B01 prerequisites retain their accepted owners. RV2 corrects documentation to actual code; B02 retains configuration/context consolidation. No later slice is claimed complete. |
| Style/engineering | Four responsibility headers agree with the three current records. Exact comparison establishes comment-only changes. Existing mixed/nested types are not expanded or newly approved. |
| Conciseness | Corrected existing records and retained shared engine/codec boundaries. No new abstraction, scanner or ownership-test suite. |
| Security | No executable/configuration/permission delta. Wire validation stays with the codec; database connections stay with the executor. Full auth, template sandbox and service security are outside this correction. |
| Library necessity | No dependency changes; no additional library required. |
| Readability/maintainability | Removed fictitious delegation and made the two template call paths explicit, preserving the B02 debt instead of claiming consolidation. |

Acceptance is limited to B01's staged extraction/isolation and these corrected records.
It is not full repository SSOT acceptance, completion of B02–B07/C01–C03, or production
readiness certification. The old review reports retain their historical verdicts;
current plan/status pages link to this disposition.

Fresh review-artifact validation: `npm --prefix docs-site run build` and
`git diff --check` passed. Documentation log: `/tmp/b01-rv2-review-docs.log`.
Only review evidence and current status documentation changed in this review task.
