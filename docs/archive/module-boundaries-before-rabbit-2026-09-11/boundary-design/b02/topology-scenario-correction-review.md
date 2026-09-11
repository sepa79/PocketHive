# Separate TS-R1–TS-R3 correction review — 2026-09-10

Verdict: **changes requested**. TS-R1 and TS-R3 are supported as corrected;
TS-R2's listed callers are corrected, but resource-name SSOT still has an active
alternative owner. Scope: ee424015 plus current uncommitted correction/transfer code.
No production fixes, commits, deployments or direct service-port checks in this review.

## TS-R2a — P1 / CRITICAL SSOT blocker: debug tap reconstructs Work destinations

`orchestrator-service/.../app/DebugTapService.java:178–179` still builds exchange and
routing key from swarmId and suffix. The service neither receives WorkResourceNamesPort
nor consumes its resolved projection. `create` uses these values in an actual AMQP
binding and exposes them in its response.

The new owner can supply `selected.hive / selected.final` to Orchestrator/Controller,
but a tap for the same swarm/output binds to `ph.sw1.hive / ph.sw1.final`. Depending on
broker resources, declaration fails or the tap listens to a different resource. This
was reproduced through the real public service create method with mocked infrastructure;
probe source is `/tmp/ph-ts-review/TapProbe.java`. No private-method invocation or network.

This copy predates the correction and was missed by the previous review. It is not a
new regression, nor a request to perform full B04 extraction. Nevertheless it computes
the same Work identity as PrefixedWorkResourceNames; deferring broad B04 work does not
establish distinct, non-overlapping ownership. AGENTS.md's two-authority rule blocks
acceptance. Have this consumer use the configured naming port or the actual resolved
Work-binding projection; remove the local formula and test the resulting AMQP binding
against non-default resolved topology. Do not declare the duplicate an allowed owner.

## Responsibility evidence

| Responsibility | Actual call paths and verdict |
|---|---|
| RESP-CONTROLLER-WORK-CONFIGURATION | Factory declaration preflight → adapter → WorkSelectorEnvironmentPolicy through raw Spring lookup; both selector directions and aliases reject before external context lookup. Compose repeats declaration validation, then materializes/exports before connection freeze. TS-R1 corrected. |
| RESP-WORK-RABBIT-SETTINGS | Adapter checks containsKey; only absence supplies Map.of. Bootstrap requires an object and delegates AUTHORING/RESOLVED field semantics to Rabbit providers. Explicit null rejected in both directions. TS-R3 corrected. |
| RESP-WORK-RESOURCE-NAMES | Startup compositions choose PrefixedWorkResourceNames; Controller forwards its injected port into provisioning, guard, stats, runtime bindings and cleanup. Orchestrator uses the port for root settings and ownership manifests. Traffic/static environment helpers no longer select a resolver; missing root settings fail. DebugTapService retains the duplicate formula above. |
| RESP-WORK-STATE / RESP-CONTROLLER-WORKER-PLAN | prepare builds candidate worker plans before accepted-state writes/provisioning. New lifecycle tests start a valid plan, reject selectors/null tuning and retain workers, SUT, RUNNING/readiness, without Rabbit/container creation effects. |
| RESP-SCENARIO-VALIDATE | Neutral injected parser/provider delegation from preceding transfer retained; Scenario repository/validation and import boundary regression suites pass. Complete bee.env authoring and complete Controller candidate gate remain outside this correction. |

Searches covered production Java constructions of PrefixedWorkResourceNames, all
WorkResourceNamesPort and queueName/hiveExchange consumers, removed helper names, and
independent ph/swarm/hive concatenations. ControlPlaneTestFixtures is test fixture code,
not a production owner. DebugTapService is an active production consumer. The prior
transfer document's claim of a sole queue-concatenation owner is therefore unsupported.

The correction evidence also overstates one ordering detail: null tuning is rejected
inside compose, **after** controlNetwork.get in SwarmWorkerSpecFactory.plan. Its new
factory test asserts rejection, not zero context calls. The selector test does assert
zero context calls. Null rejection before provisioning/state effects is supported and
satisfies TS-R3; rejection before external context lookup is not claimed by this review.

## Six passes

| Pass | Result |
|---|---|
| Plan outcome | TS-R1/TS-R3 closed within scope; SSOT acceptance blocked by TS-R2a. |
| Style / boundaries | Separate new types with responsibility headers. Legacy large classes receive wiring rather than new domain owners. Remaining tap copy violates the transferred naming boundary. |
| Conciseness | Removed facades/default reconstruction simplify the flow. One remaining formula must be removed; no extra abstraction or full lifecycle extraction needed to close it. |
| Security | No new auth/ingress or secret-bearing diagnostics. Selector override rejection strengthens the configuration boundary. Tap mismatch is resource correctness; no live security validation claimed. |
| Libraries | Existing Java/Spring/providers reused; corrections introduce no external library. |
| Readability / maintainability | Port dependencies now explicit. Documentation overstates sole ownership and null/context ordering; these claims must match actual consumers and tests. |

## Verification

Independently reran all **169 selected tests**, zero failures/errors/skips. Log:
`/tmp/ph-ts-correction-review.log`. Source review additionally followed constructor
forwarding and actual resource-name calls; green tests alone did not establish ownership.
The independent debug-tap probe reproduced the remaining mismatch. git diff --check passed.
Full package/docs build results belong to the implementation evidence and were not rerun
in this review. No live Rabbit delivery, deployed worker or full B02 acceptance claimed.
