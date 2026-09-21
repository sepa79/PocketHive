# Orchestrator correctness — separate from SSOT extraction

Status: O1/O2 fixes implemented on 2026-09-14; ready for separate review.
The [path review](../architecture/orchestrator-code-path-review-2026-09-14.md) is the diagnostic baseline.
The [functional module plan](functional-module-boundaries.md) owns behavior-preserving SSOT transfers.
Neither plan's completion implies completion of the other.

## Authorized first slice: accept evidence only for its owner identity

1. **O1 — controller observations.** Extract the observation handling currently inside the
   transport listener. The handler accepts full/delta only for the registered swarm's controller
   instance and runId, before cache/network/health/timestamps or lifecycle callbacks change.
   Preserve accepted full/delta behavior, missing-baseline requests, stale threshold and ACK policy.
   Gate: mismatching full and delta leave the accepted snapshot, metadata and operation unchanged;
   matching traffic still updates and completes CREATE/config observation as before.
2. **O2 — executor results.** Use one exact operation identity (swarm, operation type, target,
   templateId/runId, correlationId, idempotencyKey) for both immediate completion and results waiting
   for observation. Match before pending insertion and when completing. Remove the completion path
   that substitutes the operation's own fields for the received evidence's fields.
   Gate: wrong identity never completes or poisons pending; valid delayed/duplicate/late results
   retain their existing behavior. Optional runtime image/container metadata is not identity.

This slice changes acceptance of mismatched evidence explicitly. It does not alter Rabbit delivery,
wire schemas, provisioning, normal lifecycle timing, reset, or recovery.

## Separate design work — no implementation authorization from this slice

- **O3 reset and registry/observation model.** Decide whether reset clears only observations or
  whether the endpoint should be removed. A registered runtime must not disappear as a side effect
  of refreshing diagnostics. Status must not recreate registrations. Define effects on active
  operations and accepted snapshots before changing REST §5.2. Gate: running runtimes remain
  controllable across the chosen reset behavior, with explicit operation/observation semantics.
- **CREATE and lifecycle design.** Specify state ownership, side-effect ordering, failure cleanup
  and terminalization before behavior changes. Moving the existing workflow out of HTTP belongs
  to the SSOT plan only when its behavior remains unchanged.
- **Orphan compute removal success / invalid HTTP timeouts.** The path review and F03 identified
  behavior changes needed here; keep their contract decisions separate from moving implementations.

## Handoff

O1/O2 verification: 74 tests passed (26 new regression cases and 48 existing checks), including
RepositoryImportBoundaryTest. `ControllerObservationHandlerTest` covers rejected full/delta,
unchanged snapshot/metadata, missing-baseline requests and valid CREATE completion.
`ExecutorResultIngressTest` covers every operation identity field on immediate/observation paths,
protection of valid pending evidence and duplicate handling; optional runtime diagnostics remain accepted.
The old incomplete `recordResult` signature and nested value-contract names were removed with all callers
updated. Codecs and listener ingress are real in the new integration cases; no direct service ports.
Log: `/tmp/orchestrator-identity-fixes-regression.log`. `git diff --check` passed. No deployment or full E2E
was performed; reset and other design changes were not implemented.

Hand off O1/O2 for separate review. Resume the selected SSOT slice after that handoff; do not automatically begin reset redesign or a service-wide rewrite.
HiveMind tools are unavailable in this session; current decisions remain in these repository plans.
