# B02 Redis write-settings review — 2026-09-08

Verdict: no new blocking findings in the sourceStep/pushDirection/maxLen transfer.
The scoped validation ownership transfer is supported by source and behavior evidence.
This does not accept full B02/V04 or the other unreviewed transfers.

Scope: HEAD `eb681ee7` plus uncommitted B02 work; reviewed the latest write-settings
change documented in [implementation evidence](redis-write-settings-transfer.md).
Adjacent target/source/connection code was inspected to distinguish retained behavior.
No production code, committed tests, dependencies or deployment were changed by review.

## Responsibility evidence

Contract: RESP-WORK-REDIS-WRITE-SETTINGS in
`docs/architecture/runtime-responsibilities.md`. WorkConfigurationParser and the four
Redis write-settings value/report/enum files have matching responsibility headers.

| Path | Inspected owner, behavior and effects | Verdict |
|---|---|---|
| Raw settings → resolved value | WorkConfigurationParser owns required values, enum normalization, exact integer/range checks and AUTHORING/RESOLVED handling. RedisWriteSettings has a package-private constructor; RedisWriteSettingsValidation hides settings on errors/deferrals. The parser performs no environment read, rendering or Redis operation. | Supported |
| Startup → output | WorkerDefinitionDiscovery/WorkOutputRegistryInitializer use WorkOutputConfigBinder; RedisOutputProperties retains raw scalar types until validation. RedisWorkOutputFactory constructs the output from those properties. RedisWorkOutput.fromProperties consumes the same parser. | Supported |
| Raw output update → publication | RedisWorkOutput validates merged settings and targets before replacing its AtomicReference. publish captures one PushRequest. Invalid updates preserve the prior request; the existing catch/log behavior remains. RedisPushSupport selects FIRST/LAST payload and passes direction/maxLen to its writer. | Supported for adapter-local behavior; full CP candidate gate remains open |
| Enabled diagnostic capture | RedisUploaderInterceptor delegates these three fields to the parser before invoking its chain/write flow. Enablement and BEFORE/AFTER remain separate policy. WorkerInvocation catches Exception; the new IllegalArgumentException subtype remains on its error path. | Supported; full capture authoring remains open |
| Native-output authoring | ScenarioBundleValidator delegates the three fields through WorkConfigurationFindings. Its generic required/type/options/range passes skip exactly these selected Redis-output fields under the same selection/subblock predicate used by the delegated validation. Findings retain canonical paths; deferred settings are warnings, not runtime acceptance. | Supported |

Actual Redis effects remain in RedisPushSupport.LettuceRedisWriterFactory: connection
construction, LPUSH/RPUSH and LTRIM. The write-settings change replaces enum references
and request accessors; it does not change connection caching or trimming behavior.
The writer's positive-limit condition is execution of resolved policy, not a competing
raw-config validator. Existing public nested connection/writer/request types remain
declared B02/B06 debt; the two setting enums were removed from that container.

## Alternative-owner search

Repository-wide searches covered `sourceStep|pushDirection|maxLen|SourceStep|PushDirection`
in Java/TypeScript/JavaScript and the corresponding JSON/YAML declarations. Outputs:
`/tmp/b02-redis-write-review-owners.txt`, `/tmp/b02-redis-write-review-contracts.txt`.
Inspected consumers and old validator deletions, not only search counts.

- No other active Java decoder/validator for these selected write settings remains.
- SwarmWorkerSpecFactory exports raw values; producer normalization is still unfinished.
- The Redis capability manifest retains manually declared UI defaults/options/range
  metadata. Selected authoring no longer uses it as a second validator for these fields;
  generated producer/metadata consolidation is not accepted by this review.
- Clearing-export RecordSourceStep selects clearing records/business-code payloads with
  its distinct previous/index semantics. UI/debug maxLen matches are display lengths.
- Remaining requireInt methods in the output/uploader validate connection ports;
  uploader requireEnum now serves only the distinct capture phase.

## Six review passes

| Pass | Result and evidence |
|---|---|
| Plan | Scoped transfer removes active validators from all four declared consumers; unfinished connection, producer and candidate work stays explicit. No full B02 acceptance follows. |
| Style | New setting types have individual files and accurate headers; affected parsing is extracted from RedisPushSupport. Remaining mixed runtime/connection code is identified above. |
| Conciseness | One parser handles raw values and typed products; consumers delegate. No extra parser framework or boundary scanner was added. |
| Security | New errors report field paths and permitted values without echoing raw settings. No secret, credential, authorization, network-target or routing policy change was found. |
| Libraries | JDK BigDecimal supports exact integer checks; no dependency added. |
| Readability/maintainability | Resolved settings make request contents explicit. Raw Object fields are confined to binding/parsing; the small enum/value/report files preserve one owner without duplicating validation. |

## Executed verification and limits

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=RedisWriteSettingsTest,RedisOutputTargetsTest,WorkIOConfigBinderTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,WorkerControlPlaneRuntimeTest,RedisConfigurationValidationComponentTest,ScenarioControllerTest,WorkConfigurationFindingsTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

210 tests passed, zero failures/errors/skips. Log: `/tmp/b02-redis-write-review-tests.log`.
The existing single import test is included. A temporary in-process EnvironmentProbe
also used SystemEnvironmentPropertySource and WorkOutputConfigBinder with the actual
POCKETHIVE_OUTPUTS_REDIS_* names: trimmed lowercase enum values resolve to FIRST/RPUSH;
maxLen -1/4 succeeds and 4.5 fails at pockethive.outputs.redis.maxLen. Probe source/result:
`/tmp/b02-redis-write-review/EnvironmentProbe.java` and `environment-probe.log` there.
No additional permanent tests were added. `git diff --check` passed.

No deployed Redis, end-to-end producer normalization, complete CP candidate/acknowledgement
gate, original empty-YAML shape preservation or full capture authoring acceptance was
tested or claimed. Those remain B02 work. [SEL-R1](known-issues.md) remains open and
user-deferred; this review neither reopens it as a blocker nor claims it fixed.
