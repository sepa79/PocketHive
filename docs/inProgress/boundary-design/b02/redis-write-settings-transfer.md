# B02 Redis write settings

Implemented on 2026-09-08 after the user requested plan continuation. Scope: HEAD
`eb681ee7` plus existing uncommitted B02 changes. The
[separate review](redis-write-settings-review-2026-09-08.md) supports this scoped transfer;
complete B02, V04 acceptance and B03 remain open.

Owner: [RESP-WORK-REDIS-WRITE-SETTINGS](../../../architecture/runtime-responsibilities.md#resp-work-redis-write-settings).
WorkConfigurationParser now owns sourceStep, pushDirection and maxLen decoding,
normalization and validation. RedisWriteSettings is its immutable resolved product;
RedisWriteSettingsValidation exposes no usable settings when errors or deferred paths
remain. RedisPayloadSource and RedisPushDirection replace RedisPushSupport's nested
enums; they do not decode raw configuration or perform IO.

Startup properties retain raw scalar types until shared validation. RedisWorkOutput
validates merged write settings before replacing its active PushRequest. Enabled
RedisUploaderInterceptor uses the same parser. RedisPushSupport receives resolved
settings and still owns payload selection and actual list writes. Removed the old
nested enum decoders and the three local maxLen validators in properties/output/capture.
Scenario Manager projects shared native-output authoring findings and delegates these
fields' required/type/option/range checks instead of producing competing catalogue errors.

All three fields remain required. Enums accept typed values or trimmed case-insensitive
text. maxLen accepts exact finite 32-bit integers of -1 or greater, including integer
property text; fractional values and overflow fail without coercion. Existing -1/0
unbounded behavior and positive-limit trimming remain unchanged. AUTHORING defers symbolic
fields; RESOLVED rejects them. No defaults, library, source scanner, YAML loader or
template preparser were added.

Executed verification:

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=RedisWriteSettingsTest,RedisOutputTargetsTest,RedisDatasetSelectionTest,WorkPatchPolicyTest,RedisSourcesParsingTest,WorkConfigurationParserTest,WorkConfigurationFindingsTest,RedisConfigurationValidationComponentTest,WorkIOConfigBinderTest,RedisDataSetWorkInputTest,WorkerControlPlaneRuntimeTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -B -ntp -pl scenario-manager-service -am \
  -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

275 distinct cases verified across the initial run and focused rerun. The initial run
passed 274; one API assertion still expected the old maxLen diagnostic text. Updated
that assertion to the canonical parser message; all 89 ScenarioControllerTest cases
then passed. No production change was required by this failure. Logs:
`/tmp/b02-redis-write-tests.log`, `/tmp/b02-redis-write-scenario-tests.log`.

New checks cover enum normalization, missing/malformed fields, precise integer bounds,
symbolic authoring/resolved differences and raw Spring type preservation. Adapter tests
exercise FIRST/LAST payload choice, direction/limit propagation and keeping the previous
request on invalid updates. Existing capture/source/route/control-update tests and the
single repository import test also ran. No wiring-identity tests were added.

Whole-reactor `./mvnw -B -ntp -DskipTests package` passed (production and test compilation;
tests skipped). `npm --prefix docs-site run build` passed after replacing new Markdown
links into the unpublished inProgress tree with repository path references.
`git diff --check` passed. Build logs: `/tmp/b02-redis-write-package.log` and
`/tmp/b02-redis-write-docs.log`.
These are local unit/component/build checks, not deployed-stack acceptance.

Repository-wide owner search covered SourceStep/PushDirection, sourceStep/pushDirection,
maxLen and local range validators. Evidence: `/tmp/b02-redis-write-owners-before.txt`
and `/tmp/b02-redis-write-owners-after.txt`. Current production matches for
parseRedisWriteSettings/validateRedisWriteSettings are the shared parser and its consumers;
RedisPushSupport.SourceStep/PushDirection and requireMaxLen/validateMaxLen are absent.
Clearing-export RecordSourceStep is a distinct record-selection contract (including
previous/index selection); it does not own Redis payload write settings.

Remaining: shared Redis connection settings including token/sequence consumers, full
capture authoring, other typed IO/execution settings, complete candidate validation before
accepted Control Plane state/ack, producer migration and original empty YAML shape
preservation. Capture enablement and BEFORE/AFTER phase remain distinct interceptor
policy. [SEL-R1](known-issues.md) remains open and user-deferred. No automatic acceptance
review, commit or deployment was performed.
