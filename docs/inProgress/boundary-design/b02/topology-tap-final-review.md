# Separate TS-R2a final correction review — 2026-09-11

Verdict: **accepted within the TS correction scope; no new findings**.
TS-R2a is closed. Together with the preceding correction review supporting TS-R1 and
TS-R3 and the other TS-R2 consumers, this closes the recorded TS findings. Full B02,
complete Controller candidate validation and B04 lifecycle ownership remain open.
Reviewed ee424015 plus the uncommitted DebugTapService correction and its evidence.

## Responsibility and call-path evidence

RESP-WORK-RESOURCE-NAMES: Orchestrator startup's WorkResourceNamesConfiguration supplies
the sole selected implementation to both ContainerLifecycleManager and DebugTapService.
The latter has one required constructor dependency, no concrete construction or fallback.
resolveBinding calls forSwarm, exchangeName and queueName; create uses the resulting
TapBinding for the actual AMQP Binding and DebugTap response projection. The local source
exchange/routing-key formula is gone. Temporary tap queue identity, expiry and samples
remain the existing service's distinct concern, not a competing Work source-name owner.
The service header and runtime-responsibilities record describe that distinction.

Repository-wide production Java searches inspected concrete PrefixedWorkResourceNames
construction, port consumers, and ph/swarm/hive and queue-prefix concatenations. Concrete
selection remains in startup; the matching ControlPlaneTestFixtures code is explicitly
a test fixture. No remaining alternative source-name formula found in this scoped path.
This source evidence is distinct from import-test success.

DebugTapServiceTest exercises real create calls in both directions with non-default port
results and checks the actual AMQP binding exchange, routing key and destination, plus
response agreement. The default-name expiry/deletion test remains. Factory/manifest,
authorization, owner and import-boundary regression suites also pass.

Correction evidence now accurately states that null tuning fails during composition after
network context lookup but before returning a plan/provisioning; selector preflight is
the earlier rejection. Historical reviews remain unchanged as historical evidence.

## Six review passes

| Pass | Result |
|---|---|
| Plan outcome | Pass: identified duplicate removed and affected consumer demonstrably uses the port. |
| Style / boundaries | Pass for correction: responsibility/header matches actual delegation; no added production type or expanded lifecycle responsibility. Existing legacy nested tap/DTO structure is unchanged debt. |
| Conciseness | Pass: one required dependency replaces two local formulas; no compatibility overload or additional resolver. |
| Security | No changed ingress/auth contract or secret diagnostics. Existing admin authorization tests pass; no deployed security claim. |
| Libraries | Pass: existing topology contract, Java/Spring and test facilities; no new dependency. |
| Readability / maintainability | Pass: explicit source-name dependency, shared result drives effect and response; corrected ordering evidence. |

## Verification and limits

Independently reran 26 tests: zero failures/errors/skips. Log:
`/tmp/ph-tap-review-tests.log`. git diff --check passed. No production edits, commits,
pushes or deployments during review. No direct service-port or live broker test.
Package/docs results remain prior implementation evidence, not repeated review checks.
This accepts the bounded corrections, not all repository SSOT or full B02 completion.
