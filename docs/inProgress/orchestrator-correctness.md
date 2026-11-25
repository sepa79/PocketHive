# Orchestrator correctness — separate from SSOT extraction

Status updated 2026-09-26: O1/O2, O3 catalogue discovery, UNKNOWN workload intent
and approved catalogue-only deletion are implemented and reviewed. General recovery
and disk/queue/network cleanup remain deferred.
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

## Failed preparation and explicit removal — 2026-09-22

The user separately requested the startup/cleanup fix after release qualification
found controllers that exited on incompatible worker plans and could not answer REMOVE.
The scoped fix retains the Controller after applying a verified plan fails, reports
failure through its existing lifecycle owner, and rejects workload commands. Matching
instance/run/template/digest evidence fails CREATE promptly; normal explicit REMOVE
cleans partial resources through the existing filesystem handshake and postconditions.
See [the CREATE contract](../ORCHESTRATOR-REST.md#30-create-swarm).

Artifact verification and process/bootstrap failures still abort startup. Dead-process
general recovery and operation reconciliation across restarts remain separate work.
The later O3 catalogue decision below supersedes the original reset exclusion.
The `startup-failure` acceptance group reproduces the incompatible-fixture case through
public ingress; the matching-target `lifecycle` group verifies the normal path.

## Separate design work — no implementation authorization from O1/O2

- **O3 catalogue discovery — implemented, 2026-09-26 user decision.** RESET clears the
  local catalogue and ordinary full statuses rebuild entries, including after restart.
  Discovery verifies live compute identity and the existing startup artifact; no ownership
  manifest or invented runtime ID. Unknown deltas request full status. Conflicting controller
  or run identities preserve current entries and emit an operator-visible ERROR with both
  identities. Active operation recovery across process restarts is not included.
- **Manual stale-entry removal — implemented and verified.** The approved
  catalogue-only endpoint verifies compute absence and deletes the current entry without
  waiting for Controller. Disk, queue and network-binding cleanup remain deferred. General orphan recovery/retries
  remain deferred. STALE alone never authorizes automatic deletion.
- **Broader CREATE and lifecycle design.** Beyond the scoped failed-preparation fix above,
  specify state ownership, side-effect ordering, failure cleanup and terminalization before
  behavior changes. Moving the existing workflow out of HTTP belongs
  to the SSOT plan only when its behavior remains unchanged.
- **Orphan compute removal success / invalid HTTP timeouts.** The path review and F03 identified
  behavior changes needed here; keep their contract decisions separate from moving implementations.

## Verification scope

`ControllerObservationHandlerTest` exercises admission of Controller observations.
`ExecutorResultIngressTest` exercises operation identity and pending evidence.

## Review correction: unavailable workload intent (approved API addition)

Add `UNKNOWN` to the canonical `WorkloadIntent` view enum. Discovery initializes
this value because status reports observation, not the last accepted command.
Fresh CREATE keeps STOPPED; accepted START/STOP sets RUNNING/STOPPED; REMOVE sets
STOPPED as required by runtimeIntent=ABSENT. Diagnostics report unavailable intent
as INCOMPLETE, never invent a STOPPED expectation or hide observed failure/health.
UI and generated lifecycle projections accept UNKNOWN. START/STOP result
`requestedWorkloadState` remains restricted to RUNNING/STOPPED (UNKNOWN is not a command).
Approved explicitly by the user on 2026-09-26 under AGENTS.md §3.

Status application now runs inside SwarmStore.updateIfCurrent, using the exact admitted
entry. RESET, registration and removal use that same monitor; discovery IO, journal
publication and operation observations remain outside the critical section. An entry
replacement rejects the old update and reports the new identity conflict or requests
full status when the entry is absent/same-identity rediscovered.

## Manual catalogue removal — approved 2026-09-26

Tracing REMOVE confirmed that its terminal contract requires Controller evidence
and verification of all targeted resources. The startup artifact describes the
plan, but is not a complete history of worker instances and their CONTROL queues.
Do not manufacture a successful Controller result or claim complete cleanup from
a stale status snapshot.

Approved first slice: `DELETE /api/swarms/{swarmId}/catalogue-entry`, specified in
[the REST contract](../ORCHESTRATOR-REST.md#331-catalogue-only-removal).
Verify compute absence, reject active operations, and remove the exact current
catalogue entry. Reuse canonical authorization, inventory, catalogue and journal
owners. Preserve normal REMOVE. Files, queues and network bindings stay untouched;
associated cleanup remains deferred rather than partially reported as success.
The user explicitly approved this endpoint and catalogue-only scope on 2026-09-26.
