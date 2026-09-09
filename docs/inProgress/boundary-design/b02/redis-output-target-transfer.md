# B02 Redis output destination settings

Implemented after the user deferred [SEL-R1](known-issues.md) and requested plan continuation.
Scope: HEAD `eb681ee7` plus the existing uncommitted B02 work. Separate review pending;
this does not accept complete B02 or start B03.

Owner: [RESP-WORK-REDIS-TARGETS](../../../architecture/runtime-responsibilities.md#resp-work-redis-targets).
WorkConfigurationParser owns destination shape, normalization and the requirement for
at least one configured route/defaultList/targetListTemplate. RedisOutputTargetsValidation
is its immutable report; invalid/deferred reports expose no partial usable targets.
Route validation remains in the same parser; previously compiled immutable routes can
be reused when an update changes another field.

Removed the independent target-presence predicates in RedisOutputProperties,
RedisUploaderInterceptor and ScenarioBundleValidator, plus properties/raw conversion
of target numbers into strings. Startup retains Object values until shared validation.
RedisWorkOutput and enabled diagnostic capture construct requests from resolved reports.
Scenario Manager projects findings and delegates the selected target fields' type checks.
Native output validates merged targets before replacing its request; removing the last
target or supplying a malformed target keeps the previous request. The existing listener
logging policy and the incomplete Control Plane candidate/acknowledgement gate remain open.

defaultList is a configured destination: AUTHORING can defer an expression, RESOLVED
requires rendering. targetListTemplate is intentionally evaluated per message and is
retained as normalized text in both modes. RedisPushSupport continues to own route matching,
template rendering, explicit destination precedence and Redis writes. A configured
template can still resolve empty for an individual message; output reports failure then.
No renderer, template preparser, source scanner or dependency was added.

Verification covers pure target rules, deferred/static/message phases, actual Spring
type preservation, source/route regressions, rejecting a destination-less request,
invalid update retention, reuse of unchanged compiled routes and scenario diagnostics.
Existing uploader template tests continue to verify message-derived Redis destinations.

Executed commands:

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=RedisOutputTargetsTest,RedisDatasetSelectionTest,WorkPatchPolicyTest,RedisSourcesParsingTest,WorkConfigurationParserTest,WorkConfigurationFindingsTest,RedisConfigurationValidationComponentTest,WorkIOConfigBinderTest,RedisDataSetWorkInputTest,WorkerControlPlaneRuntimeTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -B -ntp -pl scenario-manager-service -am \
  -Dtest=RedisConfigurationValidationComponentTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Results: 267 distinct test cases verified across the initial run and focused rerun.
The initial run passed 266 and failed one new fixture because it referenced an undefined
scenario variable. Replaced that fixture expression with a constant expression; all 17
cases in the affected component suite then passed. No production fix was needed for
that failure. Logs: `/tmp/b02-output-target-tests.log`,
`/tmp/b02-output-target-scenario-tests.log`. The run includes the existing import test.
Root `./mvnw -B -ntp -DskipTests package`, `npm --prefix docs-site run build` and
`git diff --check` passed. Build logs: `/tmp/b02-output-target-package.log`,
`/tmp/b02-output-target-docs.log`.
These are local unit/component/build checks, not deployed-stack acceptance.

Repository-wide owner search used defaultList/targetListTemplate and target-presence
messages/predicates. Before/after output: `/tmp/b02-output-target-owners-before.txt`,
`/tmp/b02-output-target-owners-after.txt`. Remaining RedisPushSupport checks select a
destination for one message; SwarmWorkerSpecFactory exports values and remains in the
broader producer migration. Neither implements the transferred declaration validator.

Remaining: other IO/connection/execution fields, complete candidate validation before
accepted Control Plane state/ack, producer migration and original empty YAML shape
preservation. SEL-R1 remains open and deferred by the user. No automatic review or commit.
