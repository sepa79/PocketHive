# Separate Controller RESOLVED gate review — 2026-09-11

Verdict: **changes requested**. Scope: 2e5b691e plus the uncommitted Controller gate.
No implementation fixes, commits or deployment during review.

## CG-R1 — P1: Redis output environment bypasses the validated candidate

WorkerWorkConfigurationAdapter.compose overlays bee.env at line 91, but its final
projections include connections, CSV, scheduler and Redis dataset only. The new parser
call at lines 113–119 validates the bootstrap map without incorporating Redis output
write/target environment values. Returning that map together with the unchanged frozen
environment does not establish one effective validated configuration.

Reproduced with explicit valid SCHEDULER input and complete REDIS output, config declaring
pushDirection=RPUSH and bee.env.POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION=INVALID:

- Controller compose accepts; bootstrap outputs.redis.pushDirection is RPUSH.
- Spring lookup of the returned worker environment gives INVALID.
- The same canonical provider rejects that effective value: outputs.redis.pushDirection,
  Must be one of [LPUSH, RPUSH].

With override LPUSH, composition again accepts but bootstrap retains RPUSH while startup
receives LPUSH. RedisOutputProperties binds this property and validates it using
RedisConfigurationParser.parseRedisWriteSettings; invalid startup values therefore fail
at the worker rather than at Controller planning. A valid differing value still violates
startup/bootstrap agreement. A later bootstrap signal cannot repair a failed startup.

The existing overlay predates this patch. This is a gap in the newly claimed complete
effective-candidate gate, not a new parser defect. The deferred broad bee.env authoring
parity does not cover this concrete selected Redis field flowing to the container. It
violates the current acceptance requirement to validate the candidate passed to the worker.

Resolve supported output overrides once through an adapter-owned projection, then use
that result for bootstrap and environment, or explicitly reject competing output setting
overrides. Keep field rules in Redis providers; do not add local Controller validation.
Cover invalid and valid differing overrides (including Spring aliases), state retention
and actual emitted bootstrap/container environment agreement.

## Responsibility / source evidence

| Responsibility | Inspected flow and verdict |
|---|---|
| RESP-CONTROLLER-WORK-CONFIGURATION | Startup supplies canonical parser from CurrentWorkConfigurationProviders into the existing adapter. Final RESOLVED invocation precedes return. Injection/delegation supported, but effective Redis output projection incomplete (CG-R1). |
| RESP-WORK-CONFIGURATION-PARSER | Existing selected provider ports aggregate settings/outer IO decisions; no Controller field validator or alternate inventory introduced. Problems/deferred paths reject. |
| RESP-WORK-CONNECTION-ENVIRONMENT | Freezes composed env; Redis projection rewrites connection fields only. This owner does not incorporate output pushDirection/targets and should not acquire that responsibility. |
| RESP-CONTROLLER-WORKER-PLAN / RESP-WORK-STATE | Factory propagates rejection. SwarmRuntimeCore.prepare finishes planning before template/readiness/state writes, topology and provisioning. Covered errors retain accepted state; the probe candidate never triggers that rejection. |
| RESP-WORK-REDIS-OUTPUT-SETTINGS | RedisConfigurationParser owns write/target rules; RedisOutputProperties validates bound startup fields with it. Source review finds this real consuming path distinct from Controller's bootstrap-only gate. |

Repository searches followed parser construction/validate calls, candidate/environment
exports, Redis output property consumers and all Controller result/planning call sites.
The new gate does not duplicate field semantics. The conflicting environment/bootstrap
values remain two active representations deciding effective selected output behavior.
No new source scanner or bean-identity check was used.

## Six passes

| Pass | Result |
|---|---|
| Plan outcome | Blocked by CG-R1; missing/unknown/unselected bootstrap settings now reject correctly, but effective configuration agreement remains incomplete. |
| Style / boundaries | New behavior stays in the existing configuration adapter with injected neutral parser; headers/contracts updated. No new production type or mixed lifecycle owner. |
| Conciseness | Small existing-parser delegation is appropriate. Closing the missing projection must reuse Redis field ownership rather than grow local rules. |
| Security | No new auth/ingress surface or secret diagnostics. Malformed effective output settings still pass Controller acceptance (CG-R1). No live security claim. |
| Libraries | Existing composition module reused; no external library addition. |
| Readability / maintainability | Wiring and early rejection are clear, but “fully validated” must refer to effective settings, not just the bootstrap half of the result. |

## Verification

Independently reran **81 tests**, zero failures/errors/skips, including factory, lifecycle,
canonical parser, connection, adapter, topology and import boundaries. Log:
`/tmp/ph-gate-review-tests.log`. git diff --check passed.
Additional compiled Java probe calls real Controller composition and canonical parser,
with Spring property lookup on returned env; source `/tmp/ph-gate-review/GateProbe.java`.
It reproduces both INVALID rejection gap and LPUSH/RPUSH divergence without network or
private-method access. No deployed worker or broker tested. Package/docs results remain
implementation evidence, not repeated review checks. Full B02 remains unaccepted.
