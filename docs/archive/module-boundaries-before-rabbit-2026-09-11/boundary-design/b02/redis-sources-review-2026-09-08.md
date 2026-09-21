# B02 Redis dataset sources — separate review

Verdict: **changes required** (RS-R1 HIGH, RS-R2 MEDIUM). Reviewed the uncommitted
source-list transfer on `refactor/control-plane-critical-restart`, HEAD `eb681ee7`,
after the route/binder corrections. This does not reopen accepted earlier transfers
or accept complete B02. No production fixes or permanent tests were added by this review.

## Findings

### RS-R1 — HIGH: newly accepted null clears valid sources before candidate validation

WorkConfigurationParser.validateRedisSources (lines 37–38) maps explicit null to an empty
list. RedisDataSetWorkInput.applyRawConfigOverrides (lines 374–378) applies it to existing
multi-source properties, leaving sources empty and listName null. Other fields in the
same update are applied as well. The next tick's ensureReadyForTick rejects this mode
and does not consume data. The previous raw parseSources explicitly rejected null
before any property mutation; the null delta therefore introduces another failing path.

Reproduction with valid bound source `red`, first raw config `{inputs: {redis:
{sources: null, ratePerSec: 3}}}`: WorkPatchPolicy accepts the bootstrap update, the
adapter applies it, then properties are `sources=[]`, `listName=null`, `rate=3` and
validateConfigured fails. ConfigKeyCanonicalizer preserves nested nulls; ConfigMerger
retains them; WorkerControlPlaneRuntime publishes state before invoking this listener.
For subsequent established raw config, WorkPatchPolicy normally blocks source changes;
the demonstrated entry is the first raw snapshot, with previousRaw empty.

Full candidate validation and the analogous existing `sources: []` gap are inherited
and already deferred. That does not make the new null-triggered loss safe to accept.
Retain explicit-null rejection until clearing sources can be validated together with
the resulting source mode, or validate the complete candidate before any mutation.
The implementation record acknowledges the changed null semantics but has no gate
preventing the demonstrated invalid state.

### RS-R2 — MEDIUM: name normalization can escape the validation report

RedisDatasetSource.decodeName (lines 27–31) checks blankness before trim. For a decoded
NUL-only string (`"\u0000"`, YAML `"\0"`), isBlank is false but trim produces empty text.
The constructor can consequently expose an empty listName. In WorkConfigurationParser,
the first scalar check passes, then the second constructor call (line 88) throws outside
the diagnostic catch. validateRedisSources and the actual ScenarioBundleValidator.validate
both throw IllegalArgumentException instead of returning the required field finding.

The existing-bundle HTTP controller maps this exception to 404; ZIP validation catches
it at the outer upload level, losing the precise source-field path and accumulated
findings. This is a new failure in the shared decoder, not the known empty-YAML-object
gap. Normalize first and validate the resulting name through the same canonical helper.

## Ownership and source evidence

| Responsibility | Owner/header, consumers and effects | Result |
|---|---|---|
| RESP-WORK-REDIS-SOURCES | RedisDatasetSource owns scalar decoding; WorkConfigurationParser owns collection/duplicate/symbolic rules; RedisSourcesValidation exposes immutable results; RedisDatasetPickStrategy only names strategies. Constructors/helpers perform no infrastructure IO. | Ownership transfer supported; RS-R2 violates the normalized-value/report contract. |
| RESP-WORK-IO-CONFIG | WorkerDefinitionDiscovery → WorkInputConfigBinder → immutable source constructor/property setter → parser. Shared WorkConfigBindHandler uses Spring binding metadata, not a new YAML loader. | Valid/nonnumeric/nested/unknown/duplicate startup cases pass; high-priority lists override malformed lower-priority lists in the temporary probe. Empty YAML shape loss remains deferred. |
| RESP-WORK-REDIS-DATASET | Factory → input → state listener → shared parser → properties; tick owns ordered/weighted selection and Redis pop. The production connection is created by the Lettuce factory at runtime readiness, not by work-config. | Existing ordering/exhaustion behavior tests pass; RS-R1 changes failure/state behavior. |
| RESP-SCENARIO-VALIDATE | ScenarioBundleValidator delegates selected sources to WorkConfigurationFindings; the latter projects shared reports without validating sources itself. Generic catalogue presence and remaining mode checks stay outside this transfer. | No remaining local source-entry/weight/duplicate validator found; real bundle reproduction confirms RS-R2. |

Repository-wide searches covered Java, TypeScript/TSX, JavaScript/MJS and Python:
`RedisDatasetSource`, old `RedisDataSetInputProperties.Source`, `parseSources`,
`validateRedisDatasetSources`, source/weight/listName diagnostics, duplicate-list logic,
and sources/weight/pickStrategy references in UI/tools. Inspected the remaining
WorkPatchPolicy.hasConfiguredSources (existing mode/mutability check), capability YAML
(presence/options), property/runtime mode checks and weightedIndex (sampling).
They do not replace source-entry validation; their outstanding settings/mode duplication
remains B02 debt. The old source DTO and three entry-validation implementations are gone.

## Verification and six review passes

- Re-ran the 11 suites listed in redis-sources-transfer.md: **200 tests passed**, zero
  failures/errors/skips. Log: `/tmp/b02-redis-sources-review-tests.log`. Includes the
  existing repository import test; it is not evidence of complete behavioral isolation.
- Temporary Java probes compiled against actual reactor classes: scalar normalization,
  actual ScenarioBundleValidator, Spring source precedence, and bootstrap policy/raw
  input application. Sources/output: `/tmp/b02-redis-source-review/` (`probe.txt`,
  `scenario-probe.txt`, `null-update-probe.txt`). No network/deployed-stack tests.
- Before comparison: `git show eb681ee7:common/worker-sdk/src/main/java/io/pockethive/worker/sdk/input/redis/RedisDataSetWorkInput.java`
  and RedisDataSetInputProperties plus ScenarioBundleValidator at the same revision.
- Plan: blocked by RS-R1; the null behavior expansion depends on the deferred candidate gate.
  Style: separate types/headers align with current architecture. Conciseness: old
  validators removed; no new framework needed. Security: malformed-name handling fails
  under RS-R2; no new credentials, authorization surface or infrastructure access.
  Libraries: no new dependency, compiler parameter metadata enables constructor binding.
  Readability/maintainability: explicit ownership is clearer, but normalization must
  preserve its stated invariant (RS-R2), and null must not imply safe clearing (RS-R1).
- `git diff --check` passed. No commit, deployment or fixes in this review task.
