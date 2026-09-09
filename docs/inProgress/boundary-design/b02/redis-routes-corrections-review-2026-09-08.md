# B02 Redis route corrections review — 2026-09-08

Verdict: **RR-R1–RR-R3 corrections accepted within their scope; no new defect found
in the correction delta.** One inherited bootstrap decoding gap was reproduced below.
This is not acceptance of complete startup/raw configuration parity or full B02.
No production code, permanent test, commit or deployment was added by this review.

## Scope and verification

Reviewed `eb681ee7` plus the uncommitted correction snapshot in
[redis-routes-corrections.sha256](redis-routes-corrections.sha256). Comparing it with
redis-routes-source-changes.sha256 isolates ten changed/new/deleted Java paths; no
dependency changes. All 120 cumulative Java/POM fingerprints match source/deletions.
Earlier B02 transfers were not reviewed again.

Independently reran the correction's focused suites: **162 tests passed**, with no
failures, errors or skips. Log: `/tmp/b02-rr-correction-review-tests.log`.

```bash
./mvnw -B -ntp -pl common/worker-sdk,scenario-manager-service -am \
  -Dtest=WorkConfigurationParserTest,WorkConfigurationFindingsTest,RedisRouteValidationComponentTest,WorkIOConfigBinderTest,RedisWorkOutputTest,RedisUploaderInterceptorTest,ScenarioControllerTest,ScenarioRepositoryValidationTest,RepositoryImportBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The existing repository import test and inherited Maven dependency enforcement pass.
Prior clean root package/docs build evidence was read, not rerun. `git diff --check`
passes. Temporary probes use public parser/binder/validator APIs, no Redis/network
effects or direct deployed service ports.

## Findings and disposition

| Previous finding | Independently checked result |
|---|---|
| RR-R1: competing symbolic route classification | Selected Redis routes bypass generic capability type checking and reach WorkConfigurationFindings. Target presence consumes the same RedisRoutesValidation. Real bundle validation accepts both expression forms with one deferred warning and no competing error. Empty/concrete-invalid routes retain their errors. |
| RR-R2: scalar conversion before validation | Actual Spring binding rejects numeric match/header/headerMatch/list through the canonical parser. Additional YamlPropertySourceLoader probes reject numeric and boolean list values, preserve textual `123` and significant regex whitespace. Uppercase indexed system-environment fields bind a valid header route. |
| RR-R3: premature conditional requirement | Symbolic header with a payload matcher defers; an empty resolved header passes and a nonblank resolved header without headerMatch fails. With no payload matcher or headerMatch, the route still fails authoring validation. |

**Inherited MEDIUM gap — nested optional YAML fields can disappear during startup
binding.** For an otherwise valid route with `match: ' ok '` and `list: out`, the real
YamlPropertySourceLoader + WorkOutputConfigBinder accepts `header: {nested: wrong}`
as an absent header. The canonical parser rejects that original object-valued header.
`header: []` and `header: {}` also disappear. A malformed restriction can therefore
be lost before validation, leaving a payload-only route.

This predates these corrections: the original RedisOutputProperties from
`git show eb681ee7:common/worker-sdk/src/main/java/io/pockethive/worker/sdk/config/RedisOutputProperties.java`,
compiled under a distinct name and exercised through the same real binder/YAML input,
also accepts all three forms. It is not evidence that the Object-field correction
introduced this behavior. Nor does Object prevent information loss that happens before
the declaration reaches the parser. Preserve this gap in the remaining B02 bootstrap
decoding work; full raw/startup parity cannot be accepted until it is handled.

Reproduction sources and outputs: `/tmp/pockethive-rr-correction-review/`, specifically
BootstrapProbe.java, OptionalFieldProbe.java, BeforeOptionalFieldProbe.java and their
bootstrap-result.txt, optional-field-result.txt, before-optional-field-result.txt outputs.
These are temporary behavior probes, not repository scanners or permanent test suites.

## Responsibility and SSOT evidence

Contract: [RESP-WORK-REDIS-ROUTES](../../../architecture/runtime-responsibilities.md#resp-work-redis-routes).

| Responsibility / owner | Source and consuming path checked | Verdict / limit |
|---|---|---|
| Decoded route semantics: WorkConfigurationParser | RedisOutputProperties.setRoutes, RedisWorkOutput and RedisUploaderInterceptor call the same RESOLVED parser; ScenarioBundleValidator → WorkConfigurationFindings calls AUTHORING. Map and RedisRouteDefinition inputs share text/regex/co-constraint checks. | RR corrections supported. No competing route semantic validator found. The inherited upstream YAML loss above is still open. |
| Validation result: RedisRoutesValidation | Parser constructs the report; it suppresses partial compiled lists whenever any error/deferred path exists. Scenario target presence consumes isEmpty; runtime parse throws canonical errors. | Read-only projection, no second expression recognizer or route parser. Invalid/deferred results are distinct from known-empty results. |
| Scenario diagnostics: WorkConfigurationFindings | Maps parser problems/deferred paths into existing finding codes/severities and returns the original report. Generic catalogue required-field presence remains separate. | Header and implementation agree; no route regex, type or conditional semantics in the projection. |
| Runtime matching: RedisPushSupport | Consumes compiled patterns, selects the first matching route and performs existing destination selection/write behavior. | Distinct from declaration validation; unchanged by these corrections. No network/filesystem operation was added to work-config. |

Repository-wide searches covered production/test Java plus JS/TS/Python candidates:

```bash
rg -n 'headerMatch|header-match|RedisRouteDefinition|RedisRoutesValidation|WorkConfigurationValidation' --glob '*.java' --glob '*.ts' --glob '*.js' --glob '*.py'
rg -n 'parseRedisRoutes|validateRedisRoutes|hasConfiguredJsonCollection|Redis output route|Redis route field' --glob '*.java' --glob '*.ts' --glob '*.js' --glob '*.py'
```

Inspected parser/declaration/report headers, startup binder/properties, output and capture
consumers, Scenario capability type/presence paths and actual capability metadata.
WorkConfigurationValidation has no remaining source consumer. hasConfiguredJsonCollection
is now used only for dataset sources, an explicitly unmigrated responsibility. Generic
route option/range constraints are absent in the current catalogue. No alternative
active owner was found for the corrected decoded route decisions; this is not a
repository-wide SSOT verdict for other settings or infrastructure.

## Six review passes

| Pass | Result and evidence |
|---|---|
| Plan outcome | Pass for RR-R1–RR-R3: callers consume the canonical decision and scalar types survive to it. Complete bootstrap/candidate migration remains required; inherited YAML loss is recorded above. |
| Style | Pass: declaration, parser, immutable result and scenario projection have separate files and aligned responsibility headers. Scenario removes route decisions instead of adding another validator. |
| Conciseness | Pass: one report removes caller reinterpretation; one text check handles both decoded representations. No extra scanner or parallel validator. |
| Security | No new finding: parser performs no IO or expression execution; invalid concrete types/regexes still fail. Input loss before parsing is the inherited gap above. Deployed authorization/security was not reviewed. |
| Libraries | Pass: no dependency changes; existing Java regex and Spring binding are used. |
| Readability / maintainability | Pass: raw Object values are confined to the unvalidated declaration, compiled runtime types remain explicit, and report semantics prevent partial success. Additional behavior tests cover concrete failures rather than implementation identity. |

Recommendation: retain the RR corrections and continue B02. Complete bootstrap decoding,
candidate validation, other Redis/settings owners and final bee.env composition remain
open. No full route parity, B02/V04/V05 acceptance or B03 start follows from this review.
