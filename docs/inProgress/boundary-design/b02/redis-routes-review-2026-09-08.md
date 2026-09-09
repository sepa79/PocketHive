# B02 Redis route transfer review — 2026-09-08

Verdict: **Redis route transfer requires corrections**: one CRITICAL SSOT finding and
two MEDIUM contract/parity findings. RB-R1/RB-R2 documentation/header corrections are
accepted within their original scope. Full B02 remains open. No production fixes,
permanent tests, commits or deployment were made during this review.

## Scope and verification

Reviewed `eb681ee7` plus the latest uncommitted Redis route transfer and Rabbit review
corrections. Comparing rabbit-source-changes.sha256 with redis-routes-source-changes.sha256
isolates **21 changed/new Java paths**. All **118** cumulative Java/POM fingerprints match
current source/deletions. Earlier patch-policy, request-template and Rabbit runtime
transfers were not re-reviewed.

Reran the transfer's eight documented suites: **150 tests passed**, no failures/errors/skips.
Log: `/tmp/b02-redis-review-tests.log`. The single import test and the pure module's
inherited dependency enforcement pass. Prior root package/docs results were read, not
rerun; this review changes only evidence/status documentation. `git diff --check` passes.

Temporary probes live in `/tmp/pockethive-b02-redis-review/`. RedisRoutesProbe exercises
the real Spring binder, canonical parser and Pebble renderer with DisabledSequenceAccess.
ScenarioRoutesProbe exercises actual ScenarioBundleValidator.validate and capabilities.
It compares current code with the original RedisOutputProperties, RedisPushSupport and
ScenarioBundleValidator from `git show HEAD:<path>`, compiled under distinct names. Its
minimal bundle has no request templates, isolating it from the earlier template transfer.
No network/Redis writes, private-field wiring checks or direct service ports are involved.

## RR-R1 — CRITICAL (SSOT): symbolic route classification still has two active owners

WorkConfigurationParser.symbolic (`WorkConfigurationParser.java:124–138`) classifies
both `{{ ... }}` and `{% ... %}` as deferred authoring expressions. The consuming
ScenarioBundleValidator still independently decides whether the **same routes value**
is symbolic in hasCapabilityConfigTypeMismatch (`:1421–1428`) using its separate
containsTemplateExpression (`:1565–1569`), which recognizes only `{{ ... }}`.
The selected-output target-presence check also uses that local classification.

Reproduction with a valid minimal Redis-output bundle, an explicit defaultList, and
`routes: "{% if true %}[]{% endif %}"`:

- The canonical parser reports the route array as deferred, without errors.
- Bundle validation returns **ok=false** with both an ERROR that routes must be an
  object/array and a WORK_CONFIGURATION_DEFERRED WARNING at that same route path.
- Before this transfer the bundle also failed, but both route/type paths consistently
  rejected the expression. The new parser/report and the retained caller now disagree.

This is a newly introduced competing authority in the migrated route responsibility,
not a claim that literal bundle acceptance regressed. The architecture explicitly promises
deferred whole route arrays, and defines no separate, non-overlapping ownership for
these two symbolic-route decisions. The CRITICAL classification follows AGENTS.md SSOT.

Correction: make the consuming route/type/presence checks derive the symbolic-route
decision from the canonical owner/report. Do not add another expression recognizer or
copy the new marker conditions into ScenarioBundleValidator. Verify the complete bundle
result, including the route error/warning combination, through its public validator API.

## RR-R2 — MEDIUM: startup silently coerces route fields that runtime rejects

WorkOutputConfigBinder binds directly into RedisRouteDefinition's String fields before
RedisOutputProperties.setRoutes calls the shared parser (`WorkOutputConfigBinder.java:32`,
`RedisOutputProperties.java:92–94`). Standard Spring conversion has already erased the
original scalar type; NoUnboundElementsBindHandler checks unused names, not this conversion.

Reproduction using an actual MapConfigurationPropertySource with an Integer value for
`pockethive.outputs.redis.routes[0].list`:

| Input `list: 123`, with `match: '.*'` | Before | After |
|---|---|---|
| Startup binding | Accepted as text `123` | Accepted as text `123` |
| Raw runtime route parser | Accepted as text `123` | Rejected: field must be a string |
| Scenario bundle validation | Accepted | Rejected: field must be a string |

Strict raw rejection is intentional; failing to enforce it at startup creates the new
parity gap. It violates RESP-WORK-REDIS-ROUTES' explicit no-coercion requirement, not just
the still-open full-candidate gate. A startup path can accept settings that the canonical
raw contract rejects. Preserve field types until canonical validation; do not introduce
a second semantic validator in the properties class. Add a behavior case covering the
actual decoder and raw parser for the same typed input.

## RR-R3 — MEDIUM: AUTHORING evaluates a conditional rule before resolving its premise

WorkConfigurationParser checks `header != null && headerMatch == null` at `:70–72`
before classifying header as symbolic at `:74`. A symbolic header is therefore assumed
to remain nonblank, although its rendered value can remove the header constraint.

Reproduction: `{match: '.*', header: "{{ '' }}", list: out}` with no headerMatch.
AUTHORING and actual bundle validation reject it for missing headerMatch. Rendering
the header through the real Pebble renderer produces an empty string; RESOLVED accepts
the complete route with its payload matcher. The authoring result includes a deferred
header warning **and** an error whose premise is still unresolved.

The old bundle validator already rejected this case: this is **inherited behavior**, not
a new runtime regression. It nevertheless remains a gap in the new explicit AUTHORING
contract for the transferred route rules; it is not among the unrelated settings left
outside this transfer. Defer co-constraints whose truth depends on a symbolic value,
while retaining errors that are certain regardless of rendering. Test a case with a
valid payload matcher, so the route can become valid when its header renders blank.

## Responsibility and source evidence

| Responsibility / call path | Inspected evidence | Result |
|---|---|---|
| RESP-RABBIT-CONNECTION base export | Record now limits implemented guarantees to validated base values and names the final bee.env gate, shared field owner and required decoder/parser delegation. Runtime code unchanged in this correction. | RB-R1 accepted; inherited final-environment bypass remains open. |
| Lifecycle / worker planning | ContainerLifecycleManager and SwarmWorkerSpecFactory primary headers link to RESP-ORCHESTRATOR-CONTAINER-LIFECYCLE and RESP-CONTROLLER-WORKER-PLAN; records describe actual actions and remaining mixed debt. | RB-R2 accepted. |
| RESP-WORK-REDIS-ROUTES | Pure parser validates/compiles routes; immutable decoded declaration and package-constructed compiled projection have distinct roles. No IO imports/calls or new dependency. | Literal rules/order supported; RR-R3 authoring co-constraint gap. |
| Bootstrap → WorkOutputConfigBinder → RedisOutputProperties | Actual SDK bootstrap uses Binder.get(environment); output discovery/registration bind selected properties and RedisWorkOutput consumes them. Valid indexed system-environment header routes work; a typo fails with UnboundConfigurationPropertiesException. | RR-R2 original scalar types are lost before canonical validation. |
| Native output / capture → parser → RedisPushSupport | fromProperties, mergeWithRawConfig and enabled uploader resolveConfig delegate routes. Matching still uses Pattern.find, payload plus optional header, first route priority; writes/target template rendering stay in push support. | Old route DTO/map builder/parser removed; existing behavior suites pass. Invalid-update swallowing and complete state acceptance remain explicitly open B02 debt. |
| ScenarioBundleValidator → WorkConfigurationFindings → parser | Projection maps errors/deferred paths with shared ValidationIssue codes, but other caller checks independently decide symbolic route validity. | RR-R1 conflicts with the canonical report; RR-R3 reaches actual bundle acceptance. |

Repository-wide Java/TypeScript/JavaScript/Python searches covered `headerMatch`,
`header-match`, `parseRoutes`, Redis route types, route error messages and expression
recognizers, excluding generated target output. Old runtime/Scenario route validators
are gone. SwarmWorkerSpecFactory's field map is environment encoding, not route validation;
generic capability and target-presence paths were followed because name searches alone
would miss RR-R1. Other Redis connection/source/target validators remain declared B02 debt.

## Six review passes

| Pass | Result |
|---|---|
| Plan outcome | Extraction/deletion direction is followed, but claimed route authoring/startup parity is incomplete (RR-R1–RR-R3). Full B02 is correctly still open. |
| Style guide | New production types are separate with accurate local headers; mixed original files lose route behavior. Rabbit header corrections pass. |
| Conciseness | No new library or scanner. Retained caller classification must consume the canonical result rather than gain another special case. |
| Security | Parser opens no resources; regex syntax errors omit raw route content. Runtime regex execution remains existing behavior. Startup conversion can bypass declared input constraints (RR-R2). |
| Library necessity | Java collections/regex and existing Spring/Pebble facilities suffice. No production third-party dependency added. |
| Readability / maintainability | Decoded/compiled roles and remaining B02 work are explicit. Simultaneous deferred/error outcomes from competing decisions obscure the acceptance contract. |

Evidence artifacts: `probe-valid-env.txt`, `probe-typo.txt` and
`scenario-probe-result.txt` under the temporary probe directory; the decisive results
are reproduced above. No full repository SSOT certification or deployed acceptance.
