# B02 Redis route parsing and Rabbit review corrections — 2026-09-08

Status: implemented and tested, pending separate review. B02 remains open.
Scope: `eb681ee7` plus earlier uncommitted B02 transfers and the changes below.
This is execution evidence, not an automatic acceptance review.

## Rabbit review corrections

- **RB-R1:** RESP-RABBIT-CONNECTION now states the implemented base-settings validation
  and encoder effect. The `bee.env` overlay remains explicitly unvalidated. Its required
  final-candidate gate names WorkConfigurationParser/bootstrap decoding and the existing
  RabbitConnectionSettings field constraints; no second validator is prescribed.
- **RB-R2:** ContainerLifecycleManager links primarily to
  RESP-ORCHESTRATOR-CONTAINER-LIFECYCLE; SwarmWorkerSpecFactory links to
  RESP-CONTROLLER-WORKER-PLAN. Both consume RESP-RABBIT-CONNECTION separately. Current
  mixed responsibilities and their remaining migration gates are recorded accurately.

These corrections change architecture text and JavaDocs only. The inherited invalid
final-environment behavior reproduced in the [review](rabbit-connection-review-2026-09-08.md)
is still open; this task does not claim to fix it.

## Ownership transfer

Contract: [RESP-WORK-REDIS-ROUTES](../../../architecture/runtime-responsibilities.md#resp-work-redis-routes).
WorkConfigurationParser in dependency-free `work-config` owns decoded route shape,
co-constraints and regex compilation. RedisRouteDefinition is its immutable decoded
declaration; RedisRoute is a compiled read-only projection, constructible only inside
the owning package. WorkConfigurationValidation reports canonical problems and deferred
paths. Runtime matching, target precedence and writes remain RedisPushSupport behavior.

| Consumer | Current delegation | Removed local implementation |
|---|---|---|
| RedisOutputProperties / WorkOutputConfigBinder | Spring decodes RedisRouteDefinition; the shared parser validates routes. Standard NoUnboundElementsBindHandler rejects unknown selected-output properties. | Mutable nested Route, route copying/trimming and silent discard of unknown bound fields. |
| RedisWorkOutput | Shared parser for startup declarations and raw update routes. | Startup declaration-to-map mapper and dependency on RedisPushSupport.parseRoutes. |
| RedisUploaderInterceptor | Shared parser for capture routes. | Dependency on RedisPushSupport.parseRoutes. |
| RedisPushSupport | Reads compiled patterns and list names to match messages. | parseRoutes, invalidRoute and nested Route declaration. |
| ScenarioBundleValidator | WorkConfigurationFindings projects AUTHORING problems/warnings. | validateRedisOutputRoutes and its route-only validateRegex helper. |

WorkConfigurationFindings maps concrete errors to the existing scenario issue and
deferred paths to WORK_CONFIGURATION_DEFERRED warnings. ValidationIssue owns the new
diagnostic code; the [diagnostic contract](../../../scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md#structured-validation-findings)
was updated before the implementation. Warnings permit saving authored data but do not
accept it for runtime use.

Intentional behavior changes: startup now rejects invalid regexes and unknown selected
output properties; raw route fields no longer coerce numbers/objects to strings. Nonblank
route text retains significant whitespace at every consumer instead of startup-only
trimming. Unrendered configuration expressions are deferred in AUTHORING and rejected in
RESOLVED. This does not affect per-message `targetListTemplate` rendering, a separate field.

## Implementation searches

Repository searches covered Java, TypeScript, JavaScript and Python source, excluding
generated target output, for `parseRoutes`, `RedisOutputProperties.Route`,
`RedisPushSupport.Route`, `headerMatch`, `header-match` and route error messages.
Old route parser/DTO references are gone. Remaining route semantic rules and regex
compilation live in WorkConfigurationParser. SwarmWorkerSpecFactory's route-field map
encodes environment keys; it does not validate route semantics. Config producers and
templates remain inputs, not another semantic validator.

Other Redis source, connection, output target and execution validators still exist and
remain B02 work. This bounded transfer does not certify full Redis or Work SSOT.

## Verification

Before migration: existing binder, native output, uploader and scenario API suites passed
**130 tests** (`/tmp/b02-routes-before.log`). After migration: **150 tests passed**, zero
failures/errors/skips (`/tmp/b02-routes-verification.log`):

| Behavior | Cases |
|---|---:|
| Canonical routes: map/declaration parity, order and significant text; concrete errors; symbolic constraints; complete-list failure | 13 |
| Scenario error/deferred diagnostic projection | 2 |
| Existing startup IO binding plus invalid regex and unknown route field rejection | 28 |
| Existing Redis native-output/capture routing and invalid-update behavior | 15 |
| Existing scenario API validation | 89 |
| All repository scenario bundles | 1 |
| Existing single repository import test | 2 |

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=WorkConfigurationParserTest,WorkConfigurationFindingsTest,WorkIOConfigBinderTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The whole root reactor passed `./mvnw -B -ntp -DskipTests package` (production/test
compilation and packaging; tests skipped in this command). Documentation passed
`npm --prefix docs-site run build` after correcting an evidence link to the excluded
inProgress directory. `git diff --check` passed. Logs: `/tmp/b02-routes-package.log` and
`/tmp/b02-routes-docs.log`. Final source-only changes after the behavior/package checks
were JavaDoc headers on the mode/problem/exception types; runtime code is unchanged.

[Durable execution output](redis-routes-verification.txt) preserves result summaries;
[cumulative source fingerprint](redis-routes-source-changes.sha256) records 118 changed
Java/POM paths, including earlier B02 transfers, new files and deletions. Historical
fingerprints remain unchanged so a later review can isolate this transfer.

Tests exercise contract behavior and existing consumers. No new source scanner, wiring
identity test, dependency or deployed-service test was added. No direct service ports
were used. There is no deployed acceptance claim.

Remaining B02: complete immutable IO/connection/execution settings, other Redis rules,
validation of the complete candidate before accepted state, all bootstrap/configuration
producers including final `bee.env`, input-local enablement/default removal and retained
template context/default debt. No B02 acceptance, B03 start, commit or deployment follows.
