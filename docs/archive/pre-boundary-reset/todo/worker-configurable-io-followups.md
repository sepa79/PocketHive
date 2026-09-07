> Status: superseded / archived on 2026-09-07.
> Original path: `docs/todo/worker-configurable-io-followups.md`.
> Factory/registry/config contract decisions now belong to the boundary design, not an independent IO queue.
> Current execution plan: `docs/inProgress/work-plane-module-boundaries.md`.
> Original text below is historical; unchecked work is not an active instruction and is not marked fixed.

# Worker Configurable IO Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/worker-configurable-io-plan.md`

- [ ] Decide whether SDK-level `WorkInputFactory` and `WorkOutputFactory` abstractions are still needed after the current registry-based implementation.
- [ ] If retained, define one explicit `IoConfig` contract and wire it only through adapters.
- [ ] Complete capability exposure for processor IO selectors where still missing.
- [ ] Add focused unit and official-ingress E2E coverage for newly introduced IO selection behavior.

Do not implement these items implicitly as compatibility paths for the separate control-plane-owned IO proposal.
