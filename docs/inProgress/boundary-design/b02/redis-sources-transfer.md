# B02 Redis dataset sources

Implementation on `refactor/control-plane-critical-restart`, after `eb681ee7` and the
earlier uncommitted B02 transfers. RS-R1/RS-R2 corrections implemented after separate
review; their acceptance and complete B02 remain open.

`common/work-config` owns decoded source names/weights (`RedisDatasetSource`) and list
shape, unknown fields, normalized duplicates and symbolic validation
(`WorkConfigurationParser`, `RedisSourcesValidation`). The shared pick-strategy enum
replaces the nested SDK enum; selection and Redis reads remain in RedisDataSetWorkInput.
The owning record is [RESP-WORK-REDIS-SOURCES](../../../architecture/runtime-responsibilities.md#resp-work-redis-sources).

Removed: SDK mutable Source/entry validator, RedisDataSetWorkInput.parseSources and its
duplicate entry-validation loop, ScenarioBundleValidator.validateRedisDatasetSources
and hasConfiguredJsonCollection. WorkConfigurationFindings projects both route and
source diagnostics through one mapping. Scenario source-mode checks run only after the
list is concrete and valid; they do not infer a mode from a failed/deferred list.

Startup binds original field types into the immutable source constructor, then delegates
collection validation to the parser. The work-config compiler retains parameter names
for constructor binding. Input and output binders share the existing 31-line handler,
renamed WorkConfigBindHandler; unknown/unrepresentable input properties now fail as well.
No global YAML loader, additional library or boundary scanner was introduced.

Names must be nonblank after trimming; weights must be finite positive
numbers or numeric property text. Previously accepted numeric names, unknown source
keys and nonnumeric authoring weights now fail. Explicit null source lists are rejected
by the shared parser in both modes. Absent fields are handled by the caller: no source
update in the adapter, an empty list for authoring source-mode checks. The initially
introduced null-to-empty behavior was withdrawn under the RS-R1 correction.

Behavior tests cover parsing, immutable results, canonical paths/errors, symbolic
constraints, real Spring YAML binding, scenario diagnostics, source order/exhaustion,
and rejection of an invalid raw list before applying another field. Source validation
cases moved out of the runtime test; the existing route component fixture now also
covers sources as RedisConfigurationValidationComponentTest.

Verification command (reactor was cleaned after the binder rename):

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=WorkConfigurationParserTest,RedisSourcesParsingTest,WorkConfigurationFindingsTest,RedisConfigurationValidationComponentTest,WorkIOConfigBinderTest,RedisDataSetWorkInputTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Initial verification: **200 tests passed**, no failures/errors/skips
(`/tmp/b02-redis-sources-tests.log`). Whole root reactor `./mvnw -B -ntp -DskipTests package`
passed, including production/test compilation (`/tmp/b02-redis-sources-package.log`);
tests were skipped in that packaging command. `npm --prefix docs-site run build` and
`git diff --check` passed (`/tmp/b02-redis-sources-docs.log`).

Repository-wide source searches for
parseRedisSources, source weight/listName/entry errors and duplicate listName identify
the shared owners and their delegates; output is `/tmp/b02-redis-sources-owners.txt`.
This is migration evidence, not an independent SSOT approval.

Remaining B02: source-mode ownership, connection/rate and other IO settings, full
candidate validation before accepted state, producer migration, final container env,
and original empty YAML `{}`/`[]` shape preservation. No B02 acceptance or B03 start.

## RS-R1/RS-R2 corrections

Human decision: retain rejection of explicit null and validate the normalized name.
Removed the parser's null-to-empty branch; null now receives its canonical list-shape
error. Scenario Manager passes an empty list only when the sources field is absent.
The adapter already parses declared sources before mutation, so rejected null cannot
clear bound sources or apply another field. RedisDatasetSource trims first, then checks
the result, keeping invalid names inside the parser's field-diagnostic path.

Existing tests now cover null in both parser modes, NUL-only names, actual scenario
diagnostics for YAML `"\0"`, and rejection of a null raw source list while retaining
the bound sources and rate. No new test file, dependency or decoder was added.
Full candidate validation and the inherited empty-list/YAML gaps remain open.

Correction verification: **204 tests passed**, no failures/errors/skips, using the
same focused command above (`/tmp/b02-redis-source-corrections-tests.log`).
`git diff --check` and `npm --prefix docs-site run build` passed
(`/tmp/b02-redis-source-corrections-docs.log`). This is implementation verification,
not acceptance review. The later [separate selection review](redis-selection-review-2026-09-08.md)
accepts RS-R1/RS-R2; its SEL-R1 finding concerns the subsequent source-mode transfer.
