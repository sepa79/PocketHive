# B02 Redis route review corrections — 2026-09-08

Status: RR-R1–RR-R3 accepted by the [separate correction review](redis-routes-corrections-review-2026-09-08.md).
The inherited YAML gap has a [narrowed binder fix](yaml-decoding-correction.md);
empty `{}`/`[]` fields and full B02 remain open. Scope: `eb681ee7` plus the uncommitted B02 work
identified by redis-routes-source-changes.sha256 and these corrections.

Contract: [RESP-WORK-REDIS-ROUTES](../../../architecture/runtime-responsibilities.md#resp-work-redis-routes).
The [review](redis-routes-review-2026-09-08.md) records the before behavior and findings.

## Corrections and removed decisions

- **RR-R1:** ScenarioBundleValidator delegates the selected Redis routes field's type
  check to WorkConfigurationParser. Its target-presence check consumes the same parser
  result; it no longer calls hasConfiguredJsonCollection for routes. That helper remains
  only on the still-unmigrated dataset-source path. No expression detector was added or
  copied. WorkConfigurationFindings projects diagnostics and returns the original report.
- **RR-R2:** RedisRouteDefinition carries original Object field values to the parser.
  Spring therefore cannot convert a numeric route field to String before validation.
  Map and declaration inputs pass through the same canonical text-type check. The typed
  compiled RedisRoute remains the runtime projection. Existing standard binding still
  rejects unknown properties; the properties class has no second field-type validator.
- **RR-R3:** The parser classifies the header before evaluating the conditional
  headerMatch requirement. With a payload matcher, a symbolic header may disappear,
  so the requirement defers. With neither payload matcher nor headerMatch, the route
  remains certainly invalid. Concrete errors still fail and RESOLVED still rejects
  unrendered values.

RedisRoutesValidation replaces WorkConfigurationValidation. It exposes a complete
compiled route list only when no errors/deferred constraints remain, and reports known
empty configuration separately from invalid/deferred configuration through isEmpty.
This removes caller-side reinterpretation of raw route shape and prevents a mixed list
from exposing only its valid subset. The architecture record and affected headers are
aligned with these roles. No dependency or additional source-scanning test was introduced.

## Verification

**162 tests passed**, no failures/errors/skips. The existing 150 cases remain, with
12 additional behavior cases: two parser/report cases, five startup decoding cases and
five component cases through actual bundle validation and the repository capability
catalogue. Projection tests were adapted to the returned canonical report.

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=WorkConfigurationParserTest,WorkConfigurationFindingsTest,RedisRouteValidationComponentTest,WorkIOConfigBinderTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The first expanded run used clean test and exposed a missing checked-exception declaration
in the new component fixture setup. After correcting that test compilation error, the
command above passed. Log: `/tmp/b02-rr-fix-tests.log`.

| Reproduced case | Current result |
|---|---|
| Whole route expression with `{{ ... }}` or `{% ... %}`, no other target | Bundle accepted for authoring with one deferred warning; no type/target error |
| Payload matcher plus header `{{ '' }}`, no headerMatch | Bundle accepted for authoring with one deferred warning; resolved empty header accepted, resolved nonblank header rejected |
| Numeric match/header/headerMatch/list through actual Spring binding | Rejected by canonical WorkConfigurationException; no string coercion |
| Textual list name `"123"` | Preserved and accepted |
| Concrete non-list routes | Canonical list-type error retained |
| Known empty routes and no target/default | Missing-target error retained |
| Valid first route followed by invalid or deferred route | No partial compiled route list exposed |

The original review's ScenarioRoutesProbe was rerun unchanged against current classes.
Its current bundle results for the conditional header and block array changed from
ok=false to ok=true with only the deferred warning. Numeric raw/scenario fields remain
rejected as intended. Output: `/tmp/pockethive-b02-redis-review/scenario-after-fixes.txt`.
All checks use local public parser/binder/validator APIs; no Redis connection or deployed
service port is involved.

[Source fingerprint](redis-routes-corrections.sha256) records 120 cumulative Java/POM
paths, including deletion of the formerly untracked WorkConfigurationValidation source.
The reviewed fingerprint is preserved. The whole reactor passed
`./mvnw -B -ntp -DskipTests clean package`, including production/test compilation and
packaging (tests skipped by that command). The clean build also removes obsolete compiled
classes. `npm --prefix docs-site run build` and `git diff --check` passed. Logs:
`/tmp/b02-rr-fix-package.log`, `/tmp/b02-rr-fix-docs.log`.

Remaining scope is unchanged: full IO/settings/candidate validation, other Redis rules,
all configuration producers including final bee.env composition, input-local defaults/
enablement and retained template context debt. No full-B02 acceptance, automatic review
loop, commit or deployment follows from this correction task.
