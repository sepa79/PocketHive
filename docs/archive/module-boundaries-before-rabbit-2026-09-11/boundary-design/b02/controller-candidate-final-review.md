# Separate CG-R1 correction review — 2026-09-11

Verdict: **accepted within the Controller candidate-gate scope; CG-R1 closed**.
No new actionable findings in the correction. Base 2e5b691e plus the uncommitted gate
and Redis output projection. Full B02/startup reconciliation remains open.

## Responsibility and consuming-path evidence

RESP-WORK-REDIS-OUTPUT-SETTINGS: RedisOutputEnvironment owns the raw candidate with
scalar overrides, environment export and resolved bootstrap mapping. It preserves original
non-string types and unknown route fields for canonical validation. RedisConfigurationParser
retains field/route/write semantics. Connection fields are taken from the existing resolved
connection projection, not independently recomputed. Controller's active field/indexed
Redis output export helpers are removed.

RESP-CONTROLLER-WORK-CONFIGURATION: startup injects the output projection into the existing
adapter. Declaration preflight rejects route-list overrides; raw scalar values enter the
candidate before export/freeze. Final Spring lookup feeds output resolution, followed by
neutral RESOLVED validation on the returned bootstrap map. The same output candidate
supplies effective startup values. No post-freeze environment mutation is introduced.

RESP-WORK-CONNECTION-ENVIRONMENT: SpringConnectionEnvironment exposes property-tree
presence through Spring's canonical names; it does not implement Redis route policy.
Indexed/dotted/root overrides reject; route config and declared placeholders retain their
single owner. The route restriction is explicit in the amended contract and tests.

RESP-CONTROLLER-WORKER-PLAN / RESP-WORK-STATE: failure propagates through factory planning
before accepted-context changes and provisioning. The extended lifecycle test retains
accepted workers/SUT/readiness after invalid output overrides. The real emitted Redis
bootstrap and container environment agree for LPUSH and defaultList.

Repository searches inspected RedisOutputEnvironment construction, parseRedisOutputSettings,
old scalar/indexed export methods and all Controller candidate/result calls. One active
output projection found. SwarmWorkerSpecFactory retains an older unused generic indexed
helper (no callers); it is inherited dead code, not a second active output authority.
The current import test remains the only source-scanning test.

## Six passes

| Pass | Result |
|---|---|
| Plan outcome | Pass: original INVALID and LPUSH/RPUSH reproductions now reject/agree respectively. Gate accepts the effective selected output candidate. |
| Style / boundaries | Pass: new owner is a separate file with responsibility header; Controller delegates and loses field mapping. Spring helper supplies binding mechanics only. |
| Conciseness | Pass: existing parser/connection owners reused; no second validation implementation or new provider inventory. Route override rejection avoids an implicit list merge. |
| Security | No changed auth/ingress; invalid settings reject before new runtime effects. Diagnostics identify fields without exposing secrets. No deployed-security claim. |
| Libraries | Pass: existing Java/Spring and configuration modules; no external dependency added by correction. |
| Readability / maintainability | Pass: explicit candidate/export/resolve flow and documented override precedence; bootstrap mapping is a projection of canonical settings. |

## Verification and limits

Independently reran **85 tests**, zero failures/errors/skips. Log:
`/tmp/ph-cgr1-review-tests.log`. Includes adapter owner, aliases/placeholders, unsupported
route overrides, original scalar-type preservation, source immutability, lifecycle
retention and actual emitted bootstrap/container agreement. git diff --check passed.

Repeated the original compiled composition probe against corrected code:
`INVALID rejected: outputs.redis.pushDirection: Must be one of [LPUSH, RPUSH].`
`accepted: bootstrap=LPUSH, startup=LPUSH, startup problems=[]`
Source: `/tmp/ph-gate-review/FixedGateProbe.java` (original failing probe preserved).
No source/test implementation changes, commits, pushes, direct service-port checks or
live broker deployment in review. Package/docs builds remain prior implementation evidence.
This does not accept all B02 producers or arbitrary worker environment sources.
