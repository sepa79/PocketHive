# Functional module boundaries — current status

Updated 2026-09-26, branch `codex/f07-ph-ui`, including Tim's integrated delivery.
**Selected F01–F09 implementation is complete.** This is a closeout checklist and
separate follow-up backlog, not authorization for another extraction or behavior change.

## Completed scope

| ID | Delivered ownership / API |
| --- | --- |
| F01 | Shared Redis adapter for inputs, output, token store, capture and sequences; settings remain in redis-config. |
| F02 | Local CSV/scheduler input mechanics and neutral input contracts. |
| F03 | Docker client/compute/host API and one stack-name owner. |
| F04 | Runtime filesystem layout and journal query, metadata, capture and retention ports. |
| F05 | Shared ClickHouse transport, configuration and environment projections. |
| F06 — Tim | Worker auth profile preparation, credential application and OAuth acquisition; shared ScenarioBundleLayout. |
| F07 — PH UI | Backend-owned access projections and explicit network modes; UI no longer reconstructs grant policy or falls back to DIRECT. |
| F07 — TCP / Tim | Server-owned workspace policy, durable catalogue, confirmed UI mutations and explicit NATIVE/POCKETHIVE administration. Workspace selection does not isolate traffic or link to swarms. |
| F08 | One owner for worker heartbeat, enablement and freshness; metrics and UI worker lists are projections. Approved degraded STOP/config behavior and UI STOP availability are implemented. |
| F09 | Processor/TCP mechanics, MCP boundaries, SM bundle/access/authoring APIs and shared layout. S1–S3/S9–S10 repairs included. |

Approved follow-up implemented: Processor TCP/ISO transports now acquire one coherent
configuration generation per request, retain in-flight generations through replacement,
and preserve accepted state after failed construction. Worker/HTTP bean shutdown releases
owned transports and clients after active requests finish. ACK and retry policy is unchanged.

Final consolidation: SwarmAccessService owns checks and their list projection;
SwarmLifecycle directly declares scenario/guard capabilities while retaining its core
port; three unused guard types are removed. No lifecycle or HTTP policy changed.

## Remaining delivery gates

- Commit/publish the current reviewed changes on explicit user instruction: consolidation,
  TCP acceptance authentication, N4 removal, Redis intake, catalogue discovery/removal
  and Processor transport lifecycle, with their documentation and verification evidence.
- Manually check the integrated PH UI and TCP workspace UI. API/component tests do not
  prove browser behavior. Tim's source-branch browser evidence remains separate.
- Stabilize the local WSL clock before relying on time-sensitive local acceptance.
  User selected environment repair, not a PH timestamp-semantics change.

No further F01–F09 extraction is required by this plan. Full-Swarm qualification of
this integrated tree is not implied by earlier remote or current local results.

## Separate follow-ups — not blockers added to this refactor

| Area | Remaining scope | Owner / decision |
| --- | --- | --- |
| Orchestrator catalogue/removal | RESET/full-status discovery and identity-conflict errors are implemented. Approved catalogue-only deletion is implemented with verified compute absence and operation/identity guards. Files, queues, network bindings and general recovery/retries remain deferred. | Zbigniew; [separate plan](orchestrator-correctness.md). |
| TCP Mock | Remaining scenario-state reset/persistence/null semantics, public nested DTO cleanup and promoting runtime mappings into PH scenarios. Native TCP upgrade remains on its separate branch. | Tim. Do not reopen completed mapping/workspace persistence. |
| Resource diagnostics/cleanup | Ownership manifests, complete discovery and orphan cleanup; current diagnostics only inspect the declared resource list. | Separate design; [runtime debug plan](runtime-debug-mcp-cleanup-spec.md). |
| Large-file fingerprint | Current fingerprint reads complete file contents; streaming was explicitly deferred. | Accepted limitation, not a new release blocker. |

3DS/APATA/App mock, output selector/splitter and CloseLook are separate feature work.
Redis input SEL-R1 is fixed: STOP invalidates the current intake batch, including
a quick START; the next tick uses current settings. Already popped items still dispatch.

Legacy E2E deletion N4 is now authorized and implemented; see [E2E closeout](e2e-test-system.md).

## Verification scope

Use the [coverage matrix](../ci/acceptance-coverage.md) for lifecycle and
transport requirements. Browser behavior and cross-node deployment need their own checks.

## Preserved boundaries

One authoritative owner per responsibility; focused implementation classes may remain
behind one functional API. Do not recreate alternate parsers, resource names, paths,
permission rules or state machines. Ports serve actual consumer boundaries, not a
mandatory interface-per-class pattern. Preserve admission ACK: processing errors are
reported, never silently redelivered. No implicit compatibility, fallback or migration.

## Implementation references

### F01 — Redis, one PR closing the shared technology responsibility

Completed; [Redis extraction evidence](redis-adapter-extraction.md).

### F03 — Docker/compute

Completed; [compute boundary](../architecture/runtime-responsibilities.md).

### Processor transport lifecycle — approved fix, 2026-09-26

The user approved the three behavior corrections separately from the completed
SSOT extraction. TCP/ISO now acquire configuration and transport together through
a lease. Retired generations close after their last lease; failed construction
never publishes a replacement. NONE retains per-request release and PER_THREAD
retains per-thread reuse. The old configure/currentConfig/acquire sequence is gone.
HTTP tracks all eager/lazy clients, rolls back partial construction and defers
shutdown cleanup until admitted executions finish. Worker destruction closes its
TCP/ISO handlers; Spring owns HTTP client destruction. No broker ACK or retry changes.

Review passes: approved scope/observable lifetime effects; responsibility headers
and separate generation value owner; one acquire path replaces split state reads;
TLS/auth policy unchanged; standard AutoCloseable and existing Apache client APIs,
no dependencies; explicit short admission locks with request IO outside them.
Repository search confirms the existing HTTP owner and TCP factory/runtime remain
the production owners; each protocol retains its separate runtime instance.
