# B02 Redis dataset source selection

Implemented on `refactor/control-plane-critical-restart`, HEAD `eb681ee7` plus the
earlier uncommitted B02 transfers. [Separate review](redis-selection-review-2026-09-08.md)
found SEL-R1 HIGH, now [deferred by the user](known-issues.md); B02 remains incomplete.
Owner: [RESP-WORK-REDIS-SELECTION](../../../../architecture/runtime-responsibilities.md#resp-work-redis-selection).

WorkConfigurationParser now decides exactly one source mode, delegating list entries
to its existing source parser and list-name decoding to RedisDatasetSource. The immutable
RedisDatasetSelectionValidation result exposes SINGLE/MULTIPLE, or UNRESOLVED with
problems/deferred paths. A known conflict remains invalid even if an entry weight is
symbolic; choices that depend on rendering remain deferred. No partial valid selection
escapes to runtime. Top-level numeric/object names are rejected as well as invalid entries.

Removed the mode predicates in RedisDataSetInputProperties, RedisDataSetWorkInput and
ScenarioBundleValidator, plus WorkPatchPolicy.hasConfiguredSources. Binders retain the
original listName type until validation. The properties holder stores the resolved name.
The input uses the parser's immutable selection projection for each tick's reads.
Scenario diagnostics project the shared report; selected listName/sources type checks
also delegate. Catalogue required-field metadata remains distinct.

Raw input updates merge the declared selection fields with current settings and parse
the resulting selection before changing any property. They no longer silently clear
sources when listName is supplied, or clear listName when sources are supplied. A mode
transition must explicitly clear the old mode. Null sources and a choice with neither
source fail before mutation, preserving existing sources and other settings.
WorkPatchPolicy retains its bootstrap/mutability rules and disabled-only restriction;
it delegates both requested single-list validity and prior mode to the same parser.
Its existing requirement for already-normalized patch text is preserved.

Behavior tests cover canonical single/multiple selection, missing/conflicting choices,
null and numeric names, symbolic co-constraints, real startup binding and scenario
diagnostics, explicit adapter transitions and preservation after an invalid update.
Existing runtime ordering/exhaustion and worker config-update tests run with the change.
No new dependency, source scanner, global YAML decoder or module-selection test.

Verification command:

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=RedisDatasetSelectionTest,WorkPatchPolicyTest,RedisSourcesParsingTest,WorkConfigurationParserTest,WorkConfigurationFindingsTest,RedisConfigurationValidationComponentTest,WorkIOConfigBinderTest,RedisDataSetWorkInputTest,WorkerControlPlaneRuntimeTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Verification results (2026-09-08): 258 tests passed, no failures/errors/skips,
including the existing repository import test. Root `./mvnw -B -ntp -DskipTests package`
and `npm --prefix docs-site run build` passed; `git diff --check` passed.
Logs: `/tmp/b02-redis-selection-tests.log`, `/tmp/b02-redis-selection-package.log`,
`/tmp/b02-redis-selection-docs.log`. These are local unit/component/build checks;
no deployed-stack verification or acceptance review was performed.

Repository-wide migration search: mode predicates/messages, hasConfiguredSources,
validateRedisDatasetSelection and decodeOptionalName (`/tmp/b02-redis-selection-owners.txt`).
The remaining predicates in WorkPatchPolicy enforce mutability using the canonical mode;
orderedSources/weightedIndex perform runtime ordering and sampling rather than validation.

Remaining: full candidate validation before accepted Control Plane state/acknowledgement,
complete typed IO/connection/execution settings, producer migration and original empty
YAML shape preservation. This adapter gate does not certify those requirements or B03.
RS-R1/RS-R2 corrections are accepted by the separate review; SEL-R1 remains open.
