# Scenario Config SSOT Review Follow-up Plan

Date: 2026-06-25

## Context

The direct bee config migration build passed. This follow-up captures the review
findings that still matter for Bee Config SSOT: scenario `template.bees[].config`,
runtime status config, capability config paths, and runtime `config-update` must
describe the same public worker config shape.

## Status update 2026-07-01

Runtime bee identity and public runtime default removal are implemented on
`runtime-bee-identity-followup`.

- `9b803fbf` documents runtime bee identity verification.
- `39d48132` removes worker SDK/application public config defaults.
- `8d9ff730` adds explicit scenario runtime config validation and migrates active
  repo scenarios.
- `c49d5b2c` requires explicit worker runtime config in service binders and adds
  focused Scenario Manager/trigger validation coverage.
- Local gates reported for this phase: targeted Scenario Manager/trigger tests,
  `git diff --check`, `./build-hive.sh`, and full local E2E through
  `./start-e2e-tests.sh --target local-swarm --group all`.

The follow-up is functionally complete for repo-owned scenarios. Active repo
scenarios are migrated, Scenario Manager rejects IO-specific config subblocks
whose explicit selector is missing or does not match the IO manifest, and
Scenario Manager validates literal `config[].options` values from capability
manifests, including unknown `inputs.type` / `outputs.type` selectors.
External scenario migration guidance/tooling now lives in
`docs/ai/SCENARIO_CONFIG_MIGRATION_GUIDE.md` and
`tools/scenario-config-migrate`.

### Post-review remediation queue 2026-07-01

Review after `ae567337` reopened the NFF gate. Fix these one at a time, with a
focused review/gate after each fix:

1. [x] Require explicit processor IO selectors even when no IO-specific
   subblock is present. `processor.latest.yaml` still defaulted
   `inputs.type`/`outputs.type` to `RABBITMQ`.
   - 2026-07-01: Done by marking processor `inputs.type`/`outputs.type`
     required, adding Scenario Manager regression coverage for missing
     selectors, suppressing duplicate generic required findings when the
     IO-subblock-specific selector rule already reports the same field, and
     migrating repo-owned processor scenarios to explicit selectors.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest,ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`
     (`ScenarioControllerTest` 64/64, `ScenarioRepositoryValidationTest` 1/1),
     `node tools/scenario-config-migrate/cli.mjs check scenarios`, and
     working-tree `git diff --check`.
2. [x] Reclassify or remove remaining public runtime defaults in worker config
   records and capability manifests. Operational guardrails may stay, but public
   scenario config must not be silently filled.
   - 2026-07-01: Done by removing all bundled capability `config[].default`
     values, adding a bundled-capability regression test that rejects future
     config defaults, making `http-sequence`, `db-query`, and
     `clearing-export` required public config fail fast in worker config
     records, expanding the clearing-export capability manifest to cover the
     public config it already consumes, and migrating repo-owned clearing-export
     scenarios to explicit runtime values.
   - Review classification: processor HTTP/TCP timeout, pooling, and transport
     objects remain runtime guardrails; SDK IO property defaults remain outside
     capability-manifest defaults and should be reviewed separately before
     changing shared IO binding semantics.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=CapabilityCatalogueServiceTest,ScenarioControllerTest,ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl http-sequence-service,db-query-service,clearing-export-service -DskipITs -Dtest=HttpSequenceRunnerTest,DbQueryRunnerTest,ClearingExportWorkerConfigTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl clearing-export-service test`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios`,
     `rg -n "default:" scenario-manager-service/capabilities/*.yaml`, and
     working-tree `git diff --check`.
3. [x] Restore `git diff --check` by removing extra EOF blank lines from
   bundle-local `sut.yaml` files.
   - 2026-07-01: Done by removing the extra final blank line from 10
     bundle-local `sut.yaml` files.
   - Focused review/gates: `git diff --check`,
     `perl -0ne 'print "$ARGV\n" if /\n\n\z/' $(rg --files scenarios orchestrator-service/src/test/resources/scenarios scenario-manager-service | rg 'sut\.ya?ml$')`,
     and `node tools/scenario-config-migrate/cli.mjs check scenarios`.
4. [x] Remove dead SDK code left by default removal.
   - 2026-07-01: Done by removing unused typed-conversion/config-override
     helpers from `PocketHiveWorkerProperties`, removing the unused protected
     mapper accessor from `CanonicalWorkerProperties`, and renaming remaining
     SDK/processor binding messages from "worker defaults" to "worker config".
   - Focused review/gates:
     `rg -n "worker defaults|Worker defaults|Unable to bind worker defaults|Unable to convert worker defaults|hasConfigOverrides\\(|toConfig\\(|protected ObjectMapper objectMapper\\(" common/worker-sdk processor-service -g '*.java'`,
     `./mvnw -q -pl common/worker-sdk,processor-service -Dtest=MessageTemplateRendererTest,ProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     and working-tree `git diff --check`.
5. [x] Clean up formatting drift in `MessageTemplate`.
   - 2026-07-01: Done by replacing the tab-indented compact constructor and
     builder field indentation with the file's normal four-space style.
   - Covered by the same focused worker-sdk/processor test gate and
     `git diff --check`.

### Second post-review remediation queue 2026-07-01

Run these one at a time. After each fix, do a focused review and gate before
starting the next item.

1. [x] Align the DB Query blank credential contract. Runtime accepts explicitly
   present blank `connection.username` / `connection.password`, while Scenario
   Manager currently rejects blank strings for required manifest fields.
   - 2026-07-01: Done by adding manifest-level `allowBlank` for DB Query
     credentials and teaching the required-config validator to treat blank
     strings as present only when the canonical capability entry allows it.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest,CapabilityCatalogueServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios`,
     scoped `allowBlank` grep, and working-tree `git diff --check`.
2. [x] Make `clearing-export` reject unknown `mode` values explicitly instead
   of treating any non-`structured` value as template mode.
   - 2026-07-01: Done by validating normalized `mode` against explicit
     `template` / `structured` values before the template-vs-structured branch.
   - Focused review/gates:
     `./mvnw -q -pl clearing-export-service -DskipITs -Dtest=ClearingExportWorkerConfigTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl clearing-export-service test`, clearing-export capability
     `mode` options review, and working-tree `git diff --check`.
3. [x] Resolve or explicitly track selected SDK IO subconfig defaults. The
   capability manifests no longer publish `default:` values, but selected input
   and output property classes still instantiate default values through SDK
   binders.
   - 2026-07-01: Done by marking selected IO manifest config fields required,
     validating selected IO manifest requirements/options in Scenario Manager,
     removing SDK binder/factory fallback config instantiation, and making
     selected IO config classes validate explicit required fields after bind.
   - Repo-owned scenarios and SDK auto-config fixtures now carry explicit
     selected IO values. The migration tool reports missing selected IO required
     fields and only writes old-default-equivalent values where the target is
     safe to infer.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest,ScenarioRepositoryValidationTest,CapabilityCatalogueServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk test`,
     `./mvnw -q -pl trigger-service -DskipITs test`,
     `npm test --prefix tools/scenario-config-migrate`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
     scoped SDK fallback grep, and working-tree `git diff --check`.
4. [x] Remove the stale Redis dataset `sourcesJson` compatibility path. The
   canonical selected IO contract exposes `inputs.redis.listName` or
   `inputs.redis.sources[]`; keeping `sourcesJson` in runtime config creates a
   second non-manifest input shape for the same source list contract.
   - 2026-07-01: Done by removing `sourcesJson` from the SDK properties,
     bootstrap/runtime config-update handling, migrator source inference, and
     tests. Redis dataset input now exposes only `listName` and `sources[]`.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=WorkIOConfigBinderTest,RedisDataSetWorkInputTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk test`,
     `npm test --prefix tools/scenario-config-migrate`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
     scoped `sourcesJson` grep, and working-tree `git diff --check`.
5. [x] Clean stale public-config default wording in worker/capability docs. Some
   docs still show capability `config[].default` examples or Redis output
   comments like `sourceStep ... default LAST` after runtime/capability defaults
   were removed.
   - 2026-07-01: Done by updating Worker SDK examples to use
     `context.requireConfig(...)`, removing the stale primary Redis output
     `sourceStep` default comment, and changing the capability manifest example
     from `default` fields to explicit `required` fields.
   - Focused review/gates: scoped default-wording grep over SDK/capability docs
     and working-tree `git diff --check`.
6. [x] Make enabled Redis uploader interceptor config explicit. With
   `interceptors.redisUploader.enabled=true`, runtime still fills `port`,
   `phase`, `sourceStep`, `pushDirection`, `maxLen`, and silently no-ops when no
   Redis target is configured.
   - 2026-07-01: Done by making enabled uploader config require explicit
     `host`, `port`, `ssl`, `phase`, `sourceStep`, `pushDirection`, `maxLen`,
     and at least one target path (`routes`, `targetListTemplate`, or
     `defaultList`). The e2e scenario and SDK docs now include the explicit
     uploader fields.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk test`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
     scoped `redisUploader` review, and working-tree `git diff --check`.
7. [x] Remove Redis push enum/null fallbacks. Shared Redis push parsing still
   maps missing or unknown `sourceStep` to `LAST`, missing/unknown
   `pushDirection` to `RPUSH`, and null `PushRequest` enum fields to defaults.
   - 2026-07-01: Done by making `RedisPushSupport.SourceStep` and
     `PushDirection` parsing reject missing/unknown values, requiring non-null
     enum fields in `PushRequest`, and adding Redis output regression coverage.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=RedisWorkOutputTest,RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk test`, scoped Redis enum fallback grep,
     and working-tree `git diff --check`.
8. [x] Make native Redis output runtime-update scalar parsing explicit. Present
   `outputs.redis.port`, `outputs.redis.ssl`, or `outputs.redis.maxLen` values
   still fall back to the current value or `false` when malformed.
   - 2026-07-01: Done by parsing present `port`, `ssl`, and `maxLen` values
     strictly in `RedisWorkOutput` raw config updates. Malformed scalar updates
     are rejected as a whole and do not partially apply other fields.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=RedisWorkOutputTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk test`, scoped Redis scalar fallback grep,
     and working-tree `git diff --check`.
9. [x] Make output registry fail fast when no factory supports a selected
   non-`NONE` output transport. `WorkOutputRegistryInitializer` still falls
   back to `NoopWorkOutput` when no factory matches, which can hide a missing
   `RABBITMQ` / `REDIS` output adapter and silently drop worker results.
   - 2026-07-01: Done by removing the initializer-level `NoopWorkOutput`
     fallback and requiring a matching `WorkOutputFactory` for every selected
     output transport. `NONE` output remains handled through the explicit
     `NoopWorkOutputFactory` bean.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=WorkOutputRegistryInitializerTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk -Dtest=WorkerMetricsInterceptorTest,PocketHiveWorkerSdkAutoConfigurationQueueResolutionTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk test`, scoped output factory fallback
     grep, and working-tree `git diff --check`.
10. [x] Make Redis route parsing fail fast for malformed route entries.
    `RedisPushSupport.parseRoutes` still skips non-map routes, blank route
    targets, invalid regex patterns, and header routes without `headerMatch`,
    which can silently fall through to `targetListTemplate` / `defaultList`.
    - 2026-07-01: Done by making shared Redis route parsing reject malformed
      route lists/entries/patterns/criteria instead of skipping them, and by
      rejecting null typed `RedisOutputProperties.Route` entries before they can
      be dropped.
    - Focused review/gates:
      `./mvnw -q -pl common/worker-sdk -Dtest=RedisWorkOutputTest,RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
      `./mvnw -q -pl common/worker-sdk test`,
      `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
      scoped Redis route skip grep, and working-tree `git diff --check`.
11. [x] Make Redis dataset `sources[]` parsing fail fast for malformed source
    entries. Source lists still drop `null` / non-object entries and default a
    missing or malformed `weight` to `1.0`, which can hide an invalid selected
    Redis dataset input contract.
    - 2026-07-01: Done by rejecting null/non-object source entries, blank
      source `listName`, missing/non-numeric `weight`, and non-positive
      weights in both bound properties and runtime `inputs.redis.sources`
      parsing. Runtime source update parsing now happens before source-mode
      mutation and reports invalid config update diagnostics.
    - Focused review/gates:
      `./mvnw -q -pl common/worker-sdk -Dtest=RedisDataSetWorkInputTest,WorkIOConfigBinderTest -Dsurefire.failIfNoSpecifiedTests=false test`,
      `./mvnw -q -pl common/worker-sdk test`,
      `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
      scoped Redis dataset source fallback grep, and working-tree
      `git diff --check`.
12. [x] Make Redis dataset runtime-update scalar parsing explicit. Present
    `inputs.redis.host`, `port`, `ssl`, `pickStrategy`, or `ratePerSec` values
    are still partially ignored when blank, malformed, or unknown, which can
    leave a runtime patch looking accepted while preserving old values.
    - 2026-07-01: Done by parsing all present Redis dataset scalar runtime
      update fields before mutating properties, rejecting blank `host`,
      malformed `port`, malformed `ssl`, unknown `pickStrategy`, and malformed
      or negative `ratePerSec`. Runtime updates now also apply explicit `ssl`.
    - Focused review/gates:
      `./mvnw -q -pl common/worker-sdk -Dtest=RedisDataSetWorkInputTest,WorkIOConfigBinderTest -Dsurefire.failIfNoSpecifiedTests=false test`,
      `./mvnw -q -pl common/worker-sdk test`,
      `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
      scoped Redis dataset scalar fallback grep, and working-tree
      `git diff --check`.
13. [x] Make Redis uploader `enabled` parsing explicit. The interceptor still
    uses `Boolean.TRUE.equals(...)`, so a present malformed value like
    `enabled: "yes"` disables the uploader instead of failing the config.
    - 2026-07-01: Done by parsing a present `enabled` value through the same
      strict boolean parser used by other Redis uploader booleans. Missing
      `enabled` still means the interceptor is dormant; malformed present
      values now fail.
    - Focused review/gates:
      `./mvnw -q -pl common/worker-sdk -Dtest=RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
      `./mvnw -q -pl common/worker-sdk test`,
      `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
      scoped Redis uploader enabled fallback grep, and working-tree
      `git diff --check`.
14. [x] Remove dead Redis push integer fallback helper. `RedisPushSupport.asInt`
    is no longer used after strict Redis output/dataset parsing, but still
    encodes malformed-value-to-default behavior.
    - 2026-07-01: Done by deleting the unused helper.
    - Focused review/gates:
      `rg -n "RedisPushSupport\\.asInt|asInt\\(" common/worker-sdk/src/main/java common/worker-sdk/src/test/java`,
      `./mvnw -q -pl common/worker-sdk test`,
      `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
      and working-tree `git diff --check`.

### Third post-review remediation queue 2026-07-01

Review after the second queue found live IO mutation and numeric validation gaps:

1. [x] Block unsafe live `inputs.*` / `outputs.*` updates and document the
   contract in Architecture. IO wiring changes such as `inputs.type`,
   `outputs.type`, Redis host/port/SSL, Redis lists/sources, CSV file path, and
   Redis output routing require worker/swarm restart; safe live IO fields are
   limited to explicit operational controls such as `ratePerSec`,
   scheduler `maxMessages`, and scheduler `reset`.
   - 2026-07-01: Done by adding a worker-sdk live IO update guard before
     control-plane config merge/state persistence. Bootstrap full config remains
     allowed when previous raw config is empty, unchanged unsafe fields in a full
     form resend are allowed, and unsafe changed fields/reset are rejected before
     config merge/state persistence/config-ready emission. Runtime still uses
     the normal config-update error/status notification path for rejected
     updates. `docs/ARCHITECTURE.md` now warns that input/output wiring is
     unsafe for live mutation.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=LiveIoConfigUpdateGuardTest,WorkerControlPlaneRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl common/worker-sdk -Dtest=LiveIoConfigUpdateGuardTest,WorkerControlPlaneRuntimeTest,WorkIOConfigBinderTest,RedisDataSetWorkInputTest,RedisWorkOutputTest,RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     and working-tree `git diff --check`.
2. [x] Reject out-of-range selected Redis IO startup/runtime scalar values
   instead of clamping or accepting them.
   - 2026-07-01: Done by removing startup clamping from selected Redis dataset
     and Redis output properties, validating Redis ports as `1..65535`, Redis
     dataset `ratePerSec` as finite `>= 0`, and Redis output/uploader `maxLen`
     as `-1` or greater. Runtime Redis output/dataset/uploader scalar parsing now
     enforces the same ranges.
3. [x] Enforce capability manifest numeric `min`/`max` in Scenario Manager.
   - 2026-07-01: Done by adding manifest-backed numeric range validation for
     image-owned and explicitly selected IO config entries. Literal numeric
     values outside manifest ranges now produce validation findings; templated
     values remain deferred.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=WorkIOConfigBinderTest,RedisDataSetWorkInputTest,RedisWorkOutputTest,RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
     and working-tree `git diff --check`.

### Fourth post-review remediation queue 2026-07-01

Review after the live IO guard found remaining SSOT/runtime validation gaps.
Fix these one at a time, with a focused review/gate after each fix:

1. [x] Validate allowed operational live IO fields before config merge and
   config-ready. Unsafe IO fields are blocked, but currently allowed safe fields
   such as `inputs.scheduler.ratePerSec`, `inputs.scheduler.maxMessages`,
   `inputs.redis.ratePerSec`, and `inputs.csv.ratePerSec` can still be
   malformed or out of range and only fail later in state listeners.
   - 2026-07-01: Done by validating the allowed operational live IO fields in
     the worker-sdk guard before config merge/state persistence/config-ready.
     `ratePerSec` must be a finite number `>= 0`, scheduler `maxMessages` must be
     a non-negative Java `long` (`0..Long.MAX_VALUE` technical range), and
     scheduler `reset` must be a boolean. Invalid safe-field updates now emit config-update errors and
     leave the previous raw config intact.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=LiveIoConfigUpdateGuardTest,WorkerControlPlaneRuntimeTest,WorkIOConfigBinderTest,RedisDataSetWorkInputTest,RedisWorkOutputTest,RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`.
2. [x] Align clearing-export `recordBuildFailurePolicy` values between the
   capability manifest, runtime enum, scenarios, and tests. Scenario Manager
   currently allows `skip_record` / `warn_only`, while the worker accepts
   `silent_drop` / `journal_and_log_error` / `log_error` / `stop`.
   - 2026-07-01: Done by making the runtime enum accept only the manifest
     values `stop`, `skip_record`, and `warn_only`. `skip_record` skips the
     failed record without manual work-error journaling, `warn_only` logs a
     warning and continues, and `stop` keeps the existing manual work-error
     journal plus worker-stop behavior. Old runtime-only values are rejected.
   - Focused review/gates:
     `./mvnw -q -pl clearing-export-service -DskipITs -Dtest=ClearingExportWorkerConfigTest,ClearingExportWorkerImplTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl clearing-export-service test`, and scoped grep for stale
     `silent_drop` / `journal_and_log_error` / `log_error` values.
3. [x] Enforce capability manifest `config[].type` in Scenario Manager for
   image-owned and selected IO config. Required/options/min/max validation is
   present, but boolean/json/int/string type mismatches can still pass scenario
   validation and fail later in runtime.
   - 2026-07-01: Done by adding Scenario Manager type validation for active
     image-owned and selected IO capability config entries. Literal values now
     must match the canonical manifest types `string`, `boolean`, `number`,
     `integer`, or `json` (`object`/`array` values); templated strings remain
     deferred to runtime.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl scenario-manager-service -Dtest=CapabilityCatalogueServiceTest,ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`,
     and working-tree `git diff --check`.

### Fifth post-review remediation queue 2026-07-01

Review after the fourth queue found remaining capability-type SSOT gaps. Fix
these one at a time, with a focused review/gate after each fix:

1. [x] Align integer-only capability config fields with runtime integer
   semantics. Several manifests still declare ports, counts, queue depths, and
   timing/count scalars as `number`, while runtime models use `int`, `Integer`,
   or `long`; some raw Redis paths also truncate decimal `Number` values via
   `intValue()`.
   - 2026-07-01: Done by marking integer-only repository capability fields as
     `integer` for Redis ports/maxLen, scheduler maxMessages, CSV delays,
     HTTP sequence threadCount, and Swarm Controller buffer/depth/count/pct
     fields. Raw Redis dataset/output/uploader integer parsers now reject
     non-integral or out-of-range `Number` values instead of truncating.
   - Focused review/gates:
     `./mvnw -q -pl common/worker-sdk -Dtest=RedisDataSetWorkInputTest,RedisWorkOutputTest,RedisUploaderInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`.
2. [x] Remove capability manifest `type: text` pass-through in Scenario Manager.
   `text` was used by generator/trigger body fields, but the validator treated
   unknown capability types as pass-through.
   - 2026-07-01: Initially covered by string-equivalent validation, then
     superseded by canonical vocabulary enforcement. `text` is no longer an
     accepted manifest type; bundled manifests use `string`. Repository
     generator `message.body` now rejects object/array/non-string literals at
     validation time.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`.
3. [x] Avoid duplicate or noisy numeric findings after a field has already
   failed capability type validation. Range checks should not add a second
   finite-number finding for the same non-numeric literal/object value.
   - 2026-07-01: Done by sharing the capability type-mismatch check between
     type validation and numeric range validation. Templated strings remain
     deferred, while literal values with the wrong type now produce only the
     type diagnostic.
   - Focused review/gates:
     `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Tracking

- [x] Confirm the two-level contract: worker `status-full.data.config` is
  canonical; SC `context.workers[].config` is the UI projection.
- [x] Verify the SC aggregation code path uses `SwarmWorkersAggregator` and
  carries worker `data.config` into `context.workers[].config`.
- [x] Strengthen `SwarmWorkersAggregatorTest` to assert exact config projection
  and no synthetic `inputs.type` when the worker did not report it.
- [x] Run focused SC aggregation test:
  `./mvnw -pl swarm-controller-service -am -Dtest=SwarmWorkersAggregatorTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- [x] Fix MCP config-update so it sends only the requested patch, not
  `mergedConfig`.
- [x] Tighten capability validation to reject exact `worker` / `pockethive`
  roots.
- [x] Require explicit `inputs.type` / `outputs.type` whenever scenario config
  contains IO-specific subblocks such as `inputs.scheduler`, `inputs.redis`,
  `inputs.csv`, or `outputs.redis`.
  - 2026-07-01: Done in Scenario Manager by deriving IO subblock selector
    requirements from IO capability manifests (`ui.ioScope`, `ui.ioType`, and
    `config[].name`).
- [x] Compose Hive UI runtime config forms from the worker capability manifest
  plus matching IO manifests selected by explicit `inputs.type` / `outputs.type`.
- [x] Provide migration guidance/tooling for internal and external scenarios to
  add explicit IO selectors without guessing when multiple IO blocks exist.
  - 2026-07-01: Done by extending `tools/scenario-config-migrate` to derive IO
    selector requirements from capability manifests and to auto-add selectors
    only when exactly one known IO subblock exists for a scope. Ambiguous or
    mismatched selectors fail with manual guidance.
- [x] Provide runtime identity guidance/tooling for internal and external
  scenarios: authoring labels may exist for topology, but runtime worker
  identity is the materialised `instance`.
  - Done via architecture, REST, UI flow, and scenario contract documentation.
- [x] Add validation/tests that reject missing or unknown IO selectors and prove
  UI does not synthesize selectors from runtime metadata.
  - 2026-07-01: Done in Scenario Manager. Missing/mismatched IO subblock
    selectors are derived from IO manifests, and unknown literal selector values
    are rejected through generic capability `config[].options` validation.
    Templated values such as `{{ vars.txOutcomeSinkMode }}` are intentionally
    treated as dynamic inputs and remain covered by existing
    `variables.yaml`/template validation.
- [x] Superseded: do not make `template.bees[].id` required/unique.
  Scenario authoring now uses `template.bees[].role` as the only scenario node
  key, and Scenario Manager rejects `template.bees[].id` as legacy shape.
- [x] Define the canonical SC worker aggregate contract for
  `status-full.data.context.workers[]`, including runtime `instance`. The
  schema/docs contract is the SSOT; the Java DTO/record is the SC implementation
  projection and must be tested against that contract.
- [x] Supersede the temporary runtime `beeId` identity design: runtime
  `instance` is the SC/UI identity key; `role` remains a user-facing label /
  control-plane routing segment only.
  - [x] SC runtime state stores workers by runtime `instance`.
  - [x] SC does not assign `POCKETHIVE_BEE_ID` per materialised worker.
  - [x] SC aggregation publishes `context.workers[].instance`, not a separate
    runtime `beeId`.
  - [x] Worker status does not echo `data.context.beeId`.
  - [x] Hive UI resolves the selected scenario bee to the unique runtime worker
    by `role`, then sends live updates to that worker by `role + instance`;
    it does not expose a second runtime id selector.
- [x] Follow TDD order for the SC-side runtime identity fix: architecture
  contract updates, red tests, then implementation.
- [x] Final phase: remove public runtime config defaults from workers after
  explicit config migration, validation, and UI composition are complete.
  - Completed by `39d48132` and `c49d5b2c`; remaining defaults are operational
    settings or empty collection/null guards.

## Findings

### 1. MCP sends merged config as the config-update patch

Severity: High
Status: Done

Evidence:

- `tools/pockethive-mcp/server.mjs` computes `plan.mergedConfig` in
  `planLiveComponentConfigUpdate(...)`.
- `sendComponentConfigUpdate(...)` sends `patch: plan.mergedConfig` to
  `/api/components/{role}/{instance}/config`.
- Worker runtime already owns the merge in
  `WorkerControlPlaneRuntime.handleConfigUpdate(...)` through `ConfigMerger`.

Impact:

This keeps a second config merge implementation in MCP. It can turn stale or
derived status fields into an applied runtime config. The API argument is named
`patch`, but MCP sends a full merged config.

Fix:

- Keep MCP preview as an optional "what would the runtime see after merge" view.
- Send the original user patch to Orchestrator from `component.config-update`.
- Add MCP tests proving `component.config-update` dispatches only the requested
  patch and never `mergedConfig`.

### 2. Verify swarm-controller config projection used by UI

Severity: Medium
Status: Done

Evidence:

- Worker `status-full.data.config` is the canonical public worker config.
- Hive UI does not read worker status directly in the swarm detail flow. It reads
  the cached swarm-controller `status-full` through Orchestrator and uses
  `data.context.workers[]`.
- `docs/ARCHITECTURE.md` says `status-full.data.context` is freeform
  role-specific context, but also says `data.context.workers[]` entries must
  carry the last known public worker `status-full.data.config` as `config`.
- `SwarmWorkersAggregator.updateFromWorkerStatus(...)` receives worker
  `data.config`, stores it, and `snapshot()` emits it as worker entry `config`.
- `ui-v2/src/pages/HivePage.tsx` uses `runtimeWorker.config` from
  `data.context.workers[].config` as the current config for the edit modal.

Note:

The earlier review suspected synthetic `inputs.type` / `outputs.type`
augmentation in the aggregate config. The actual SC aggregator currently appears
to copy worker `data.config` directly; the suspicious augmentation path is in
`WorkerControlPlaneRuntime.collectSnapshot(...)` and does not appear to feed SC
`context.workers[]`.

This is a two-level contract:

- Worker level: `status-full.data.config` is canonical.
- Swarm-controller/UI level: `context.workers[].config` is a projection/cache of
  that canonical worker config so UI can avoid per-worker status fan-out.

Impact:

`context` can remain freeform, but once SC exposes a field named
`context.workers[].config` and UI uses it as current config, that specific field
must be a faithful projection of worker `status-full.data.config`. Current code
looks close to that target, but we need tests that lock the behavior.

Fix:

- Keep `context.workers[].config` as a carried copy/projection of canonical
  worker `status-full.data.config`.
- Add/strengthen `SwarmWorkersAggregatorTest` so it asserts exact config
  equality, including absence of synthetic `inputs.type` / `outputs.type` when
  the worker did not report them.
- Keep IO type metadata in non-config fields such as `ioState`, `input`, or
  `output`. Do not put IO selector metadata in `runtime`; `runtime` is
  infra-only and schema-restricted.
- If UI needs IO type as an editable config field, it must come from canonical
  worker config, not from SC synthesis, capability defaults, runtime metadata,
  or topology inference.

### 3. Capability validation misses exact legacy root names

Severity: Medium
Status: Done

Evidence:

- `CapabilityCatalogueService.validateConfigPath(...)` rejects `worker.*` and
  `pockethive.*`.
- It does not reject exact `worker` or exact `pockethive`.
- `ScenarioBundleValidator` already rejects top-level scenario config keys
  `worker` and `pockethive`.

Impact:

A future capability manifest could declare `name: worker` or `name: pockethive`
and drive a runtime patch that creates the same top-level legacy shape now
rejected in scenario YAML.

Fix:

- Reject exact `worker` and `pockethive` in capability `config[].name`.
- Apply the same exact-root rejection to `config[].when` paths.
- Add tests for exact-root and prefixed-root rejection.

### 4. Hive runtime config UI did not compose IO capability manifests

Severity: High
Status: Done

Evidence:

- `docs/architecture/workerCapabilities.md` says IO-specific knobs are separate
  manifests with `ui.ioType` + `ui.ioScope`, merged in UI when selected
  `inputs.type` / `outputs.type` matches.
- `docs/scenarios/SCENARIO_PLAN_GUIDE.md` says config-update forms must expose
  fields such as `inputs.scheduler.ratePerSec` by adding IO manifests.
- Before the fix, `ui-v2/src/pages/HivePage.tsx` resolved only the runtime
  worker image manifest and passed `manifest.config` directly into
  `ConfigUpdatePatchModal`.
- `generator.latest.yaml` exposes `inputs.type`; scheduler fields live in
  `io.scheduler.latest.yaml`, so generator RPS was not visible in Hive UI before
  capability composition was added.
- Manual UI smoke on `.50` with `local-rest` confirmed the original issue.

Impact:

Runtime config editing is not aligned with the capability contract. Users cannot
edit input-specific config through Hive UI, and adding fields to worker manifests
would duplicate the IO manifest SSOT.

Fix:

- Keep IO-specific fields only in IO manifests.
- Keep the capability composer in the UI layer:
  - the worker manifest resolved by image,
  - current canonical runtime config from `context.workers[].config`,
  - the capability catalogue indexed by `(ui.ioScope, ui.ioType)`.
- Append matching IO manifest entries only when the relevant selector is explicit
  in canonical runtime config:
  - `inputs.type=SCHEDULER` -> `ui.ioScope=INPUT`, `ui.ioType=SCHEDULER`
  - `outputs.type=REDIS` -> `ui.ioScope=OUTPUT`, `ui.ioType=REDIS`
- Do not infer IO type from the presence of `inputs.scheduler`, queue topology,
  role name, runtime defaults, or worker metadata.

### 5. Worker public config defaults should be retired last

Severity: Medium
Status: Done

Evidence:

- Capability defaults are authoring hints only; they must not drive runtime
  behavior.
- Active scenario examples have been migrated to explicit runtime config where
  required.
- `39d48132` removed worker SDK/application public config defaults.
- `c49d5b2c` removed remaining public worker config defaults in generator,
  moderator, processor, request-builder, and trigger binders, with focused
  startup/config tests.
- Full local E2E was reported green after the default-removal commit.

Impact:

Keeping runtime defaults forever weakens NFF and lets incomplete scenario config
appear valid. Removing them too early would create broad runtime breakage.

Fix:

- Worker default removal was executed after scenario YAML migration, Scenario
  Manager required-field validation, Hive UI capability composition, and local
  runtime config-update smoke coverage.
- Keep sparse `config-update` patches; sparse patches are allowed because the
  worker merges them into an already explicit runtime config.
- Worker startup/bind tests now fail clearly when required public config is
  missing, instead of applying service-local defaults.

### 6. Hive UI joins runtime workers by `role` instead of runtime instance

Severity: High
Status: Superseded by `runtime-worker-instance-identity-plan.md`

Evidence:

- `docs/ARCHITECTURE.md` now describes runtime worker aggregates with
  `instance`. The correction here is that runtime identity is the materialised
  worker `instance`, not a Scenario Manager `template.bees[].id`.
- `role` is not node identity. It is a user-facing/logical label and remains a
  control-plane routing segment together with runtime `instance`.
- Before the fix, `ui-v2/src/pages/HivePage.tsx` built
  `runtimeWorkersByRole` and used it to choose the current runtime
  worker/config for the selected scenario bee.
- Repo scenarios already contain repeated roles, for example multiple
  generators/moderators.
- `9b803fbf` documented the temporary runtime bee identity design; it is now
  superseded by the `instance`-only runtime identity plan.
- `docs/ARCHITECTURE.md`, `docs/ORCHESTRATOR-REST.md`,
  `docs/ui-v2/UI_V2_FLOW.md`, and `docs/scenarios/SCENARIO_CONTRACT.md` now
  document runtime `instance` and explicitly separate it from authoring labels.
- `ui-v2/src/pages/HivePage.tsx` must use explicit runtime `instance`
  selection.
- Swarm Controller tests must cover repeated-role worker aggregates and
  duplicate `instance` rejection.

Impact:

The UI can show and patch the wrong runtime worker when more than one bee has
the same `role`. This also makes `context.workers[].config` unsafe for config
editing because the config projection is selected through a non-identity field.

Fix:

- Contract: runtime `instance` is the stable runtime node identity for one
  materialised swarm run.
- `role` and `instance` are the component action address; `role` alone is not a
  stable target.
- Swarm Controller must carry `instance` into the aggregate snapshot as
  `status-full.data.context.workers[].instance` and must not add a second
  runtime identity.
- Worker status must not echo `data.context.beeId` and must not receive
  `POCKETHIVE_BEE_ID`.
- Hive UI must target editable runtime workers by explicit runtime `instance`
  only. Missing runtime `instance` disables runtime config editing with an
  explicit reason. Do not fallback to role.
- Audit and fix existing role-keyed runtime projections where they represent
  node identity rather than transport grouping. Known candidates include
  `SwarmRuntimeState`, binding materialisation, `computeStartOrder`, Orchestrator
  summary `beesFromWorkers`, and Hive UI runtime selection.

TDD sequence:

1. Architecture / contracts:
   - Update `docs/ARCHITECTURE.md` status-full aggregate notes so
     `data.context.workers[]` includes required `instance` for worker entries.
   - Define a canonical worker aggregate contract for
     `status-full.data.context.workers[]` instead of relying on an untyped
     free-form map for fields used by UI/runtime editing. The canonical
     definition lives in the documented schema/docs contract; the Java
     DTO/record is an implementation projection used by SC aggregation tests,
     not a second SSOT.
   - Update `docs/ORCHESTRATOR-REST.md` cached status-full examples.
   - Update `docs/ui-v2/UI_V2_FLOW.md`: current role match is invalid; target
     behavior is explicit `instance` selection only.
   - Update scenario docs/validation notes so `template.bees[].id` is not
     treated as the runtime identity gate in Scenario Manager validation.
2. Red tests:
   - No Scenario Manager validation test for missing or duplicate
     `template.bees[].id`; that would move runtime identity into the authoring
     validator.
   - SC runtime/planning test proves each worker uses explicit runtime
     `instance` and duplicate instances are rejected.
   - Worker SDK/control-plane status test proves `data.context.beeId` is not
     emitted in worker status context.
   - `SwarmWorkersAggregatorTest` covers two planned workers with the same
     `role`, different `instance` values, and different worker configs. The
     snapshot must contain two entries keyed by `instance` and preserve each
     config.
   - UI mapping test covers two scenario bees with the same `role`; selecting
     bee B must use worker B's config and runtime target, not the last worker
     stored for that role.
3. Implementation:
   - Do not enable required-id validation in Scenario Manager as part of this
     runtime identity fix.
   - Add migration instructions/tooling only where bundles need authoring graph
     labels; do not claim those labels are runtime identity.
   - Do not assign runtime bee id in SC or propagate `POCKETHIVE_BEE_ID`.
   - Store runtime workers by `instance` in SC runtime state.
   - Carry instance from SC runtime state into `context.workers[].instance`.
   - Do not emit or validate worker `data.context.beeId`.
   - Replace or explicitly classify role-keyed maps: role can group transport
     targets, but must not identify scenario nodes.
   - Replace `runtimeWorkersByRole` in Hive UI with explicit runtime
     `instance` selection.
   - Disable live edit explicitly until the UI holds an explicit runtime target
     selected from `status-full.data.context.workers[].instance`.

## Execution Order

1. [x] Fix/document the SC aggregate projection contract:
   `context.workers[].config` is not canonical by itself, but must faithfully
   carry worker canonical `status-full.data.config` for UI.
2. [x] Fix MCP config-update to send only the requested patch.
3. [x] Tighten capability manifest validation for exact legacy roots.
4. [x] Migrate active repo scenarios and docs to explicit IO selectors:
   - `inputs.scheduler` requires `inputs.type: SCHEDULER`
   - `inputs.redis` requires `inputs.type: REDIS_DATASET`
   - `inputs.csv` requires `inputs.type: CSV_DATASET`
   - `outputs.redis` requires `outputs.type: REDIS`
   - multiple IO subblocks without an explicit selector must fail migration.
   - External scenario guidance/tooling is covered by
     `tools/scenario-config-migrate` and
     `docs/ai/SCENARIO_CONFIG_MIGRATION_GUIDE.md`.
5. [x] Add Scenario Manager validation for missing/mismatched IO selectors and tests
   against active scenario bundles.
   - The IO-subblock rule now rejects missing or mismatched selectors and active
     repo bundle validation passes.
   - Generic capability `config[].options` validation now rejects unknown
     literal selector/enum values while allowing templated values to be checked
     through `variables.yaml`/template validation.
6. [x] Add Hive UI capability composition for runtime config edit forms, using only
   explicit selectors and IO manifests from Scenario Manager.
7. [x] Supersede runtime bee identity with instance-only runtime identity:
   - [x] architecture contract updates,
   - [x] SC tests for duplicate `instance` rejection and repeated roles,
   - [x] SC implementation that carries `instance` into
     `context.workers[].instance` without a second runtime id,
   - [x] worker runtime does not emit `data.context.beeId`,
   - [x] UI implementation that removes role joins and targets explicit
     `instance`.
8. [x] Smoke through Hive UI:
   create a runnable swarm with explicit `inputs.type=SCHEDULER`, start it,
   change generator `inputs.scheduler.ratePerSec`, confirm runtime config/TPS,
   then change it again.
   - 2026-06-26 local smoke: `smoke-ui-rate` from `local-rest-topology`.
   - Confirmed Hive UI capability edits on generator:
     `50 -> 120`, `120 -> 30`, and final `30 -> 120`.
   - Confirmed via Orchestrator status-full after control-plane refresh:
     `inputs.type=SCHEDULER`, `inputs.scheduler.ratePerSec=30` with `tps=30`,
     then `ratePerSec=120` with `tps=120`.
9. [x] Final phase: remove public runtime config defaults from workers and replace
   them with explicit startup/config validation.
   - Completed by `39d48132` and `c49d5b2c`.
10. [x] Run focused gates:
   - 2026-06-29 local focused verification passed:
     - `npm test --prefix tools/pockethive-mcp` (`82/82`),
     - `npm test --prefix ui-v2` (`3/3`),
     - focused Maven worker/scenario-manager/swarm-controller tests,
     - `ScenarioControllerTest` (`48/48`) as the current bundle validation
       regression suite,
     - `npm run lint --prefix ui-v2`,
     - `npm run build --prefix ui-v2` with the existing large chunk warning,
     - legacy config grep returned no matches.
   - 2026-07-01 default-removal verification:
     - `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest,ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `./mvnw -q -pl trigger-service -Dtest=TriggerWorkerImplTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `git diff --check`,
     - `./build-hive.sh`,
     - full local E2E reported green through
       `./start-e2e-tests.sh --target local-swarm --group all`.
   - 2026-07-01 IO selector/options verification:
     - `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest,ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`
       (`ScenarioControllerTest` 63/63, `ScenarioRepositoryValidationTest` 1/1),
     - `git diff --check`.
   - 2026-07-01 IO selector migration tooling verification:
     - `npm test --prefix tools/scenario-config-migrate` (`7/7`).
11. [x] Sixth post-review remediation:
   - [x] Capability manifests fail fast on unsupported `config[].type` values.
   - [x] Capability manifest `config[].type` matching is exact canonical
     matching only; case or whitespace aliases such as `INTEGER` and
     `" integer "` fail validation.
   - [x] Canonical capability config type vocabulary is documented as:
     `string`, `boolean`, `number`, `integer`, `json`.
   - [x] Bundled capability manifests use canonical `integer`/`string` instead
     of `int`/`text`.
   - [x] Scenario config migrator docs state that selected IO required fields may
     be filled only from hardcoded safe explicit values.
   - [x] Scenario config migrator docs now state the Redis output condition
     precisely: `routes` may be filled with `[]`, and `targetListTemplate` /
     `defaultList` may be filled with blank strings, only when the scenario
     already declares at least one Redis output target path.
   - [x] Scenario Manager validates selected Redis IO semantic runtime
     contracts: Redis dataset requires exactly one real source mode
     (`listName` or non-empty `sources[]`), Redis output requires at least one
     real target (`routes[]`, `targetListTemplate`, or `defaultList`), and
     `sources` / `routes` must be lists with well-formed entries before
     runtime.
   - [x] Hive runtime config patch UI rejects decimal values for `integer`
     fields before submitting the patch.
   - [x] Hive runtime config patch UI sends `""` for selected blank `string`
     fields when the capability manifest declares `allowBlank: true`; blank
     strings without `allowBlank` are still omitted.
   - [x] Selected IO `ratePerSec` has no upper bound in manifests/runtime
     validation; Scheduler/CSV/Redis dataset only require finite values `>= 0`.
     Scheduler `maxMessages` remains configurable with no business upper bound
     and must be an integer in the Java `long` technical range
     `0..Long.MAX_VALUE`.
   - [x] Scheduler and CSV selected IO local/binder/runtime paths fail fast on
     out-of-range values instead of clamping or silently masking invalid config.
   - [x] CSV dataset runtime initialization validates the full selected
     `inputs.csv` contract before loading the file or starting the scheduler
     executor.
   - [x] Scheduler `maxMessages` docs clarify that there is no business upper
     bound; the effective technical range is Java `long`
     `0..Long.MAX_VALUE`.
   - 2026-07-01 verification:
     - `./mvnw -q -pl scenario-manager-service -Dtest=CapabilityCatalogueServiceTest,ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `npm --prefix ui-v2 test -- ConfigUpdatePatchModalForm.test.ts capabilities.test.ts`,
     - `npm --prefix tools/scenario-config-migrate test` (`8/8`),
     - `npm --prefix ui-v2 run build`,
     - `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`
       (`48/48` OK, changed=0, findings=0),
     - `git diff --check`.
   - 2026-07-01 exact-canon/docs follow-up verification:
     - `./mvnw -q -pl scenario-manager-service -Dtest=CapabilityCatalogueServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `git diff --check`.
   - 2026-07-01 Redis IO semantic follow-up verification:
     - `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `./mvnw -q -pl scenario-manager-service -Dtest=CapabilityCatalogueServiceTest,ScenarioRepositoryValidationTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `node tools/scenario-config-migrate/cli.mjs check scenarios orchestrator-service/src/test/resources/scenarios --capabilities-dir scenario-manager-service/capabilities`
       (`48/48` OK, changed=0, findings=0),
     - `git diff --check`.
   - 2026-07-01 UI `allowBlank` patch follow-up verification:
     - `npm --prefix ui-v2 test -- ConfigUpdatePatchModalForm.test.ts`,
     - `npm --prefix ui-v2 run build`.
   - 2026-07-01 selected IO runtime range follow-up verification:
     - `./mvnw -q -pl common/worker-sdk -Dtest=WorkIOConfigBinderTest,LiveIoConfigUpdateGuardTest,RedisDataSetWorkInputTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `./mvnw -q -pl common/worker-sdk test`,
     - `./mvnw -q -pl scenario-manager-service -Dtest=ScenarioControllerTest,CapabilityCatalogueServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`.
   - 2026-07-01 CSV runtime init/maxMessages docs follow-up verification:
     - `./mvnw -q -pl common/worker-sdk -Dtest=CsvDataSetWorkInputTest,WorkIOConfigBinderTest,LiveIoConfigUpdateGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`,
     - `./mvnw -q -pl common/worker-sdk test`,
     - `git diff --check`.

```bash
npm test --prefix tools/pockethive-mcp
./mvnw -pl common/worker-sdk -am -Dtest=WorkerControlPlaneRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl scenario-manager-service -am -Dtest=CapabilityCatalogueServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl scenario-manager-service -am -Dtest=ScenarioControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl swarm-controller-service -am -Dtest=SwarmWorkersAggregatorTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl swarm-controller-service -am -Dtest=SwarmRuntimeCoreScenarioEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
npm test --prefix ui-v2
npm run lint --prefix ui-v2
npm run build --prefix ui-v2
rg -n "config\\.worker|config\\.pockethive|worker\\.message|name:\\s*worker\\.|name:\\s*pockethive\\." \
  scenarios scenario-manager-service/capabilities docs/scenarios tools/pockethive-mcp \
  -g '!**/target/**' -g '!**/node_modules/**' -g '!**/build/**'
```

## Done Criteria

- Worker `status-full.data.config` remains the canonical config field.
- SC `context.workers[].config` is a faithful UI projection of that canonical
  field and is not metadata-augmented.
- MCP has no client-side write merge for `component.config-update`.
- Capability manifests cannot reintroduce `worker` or `pockethive` config roots.
- Active scenario docs, repo scenarios, capability manifests, UI, and MCP all use
  the direct config shape only.
- Scenario config declares IO selectors explicitly whenever IO-specific config
  blocks are present.
- Hive UI exposes IO-specific fields such as `inputs.scheduler.ratePerSec` by
  composing worker capabilities with IO capabilities from Scenario Manager.
- Hive UI and SC do not synthesize `inputs.type` / `outputs.type`.
- Scenario Manager does not gate runtime identity on required/unique
  `template.bees[].id`.
- Runtime worker identity is `instance`, never `role`.
- `status-full.data.context.workers[]` exposes `instance` for worker entries
  and no second runtime id.
- Hive UI disables runtime config editing if explicit `instance` is unavailable
  instead of falling back to `role`.
- Worker runtime defaults for public config are removed only after migration,
  validation, and UI smoke pass.
