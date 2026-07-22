# Network Proxy Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/network-proxy-plan.md`

## Goal

Track only work that remains after the delivered shared-per-SUT Network Proxy V1.

## Runtime reliability

- [ ] Replace cross-node NFS `inotify` reliance with an explicit HAProxy reload mechanism.
- [ ] Add a swarm/NFS regression proving that a newly applied binding creates the expected HAProxy listener and route before generator traffic starts.

## Scenario Plan integration

- [ ] Define the `network-profile` Scenario Plan step in the canonical plan contract before implementation.
- [ ] Implement controller-side execution and journal/status outcomes for the step.
- [ ] Document ordering and authoring rules.

## Isolation upgrade

- [ ] Design per-swarm DNS/gateway isolation.
- [ ] Add per-swarm binding namespaces and blast-radius containment.
- [ ] Support concurrent swarms targeting one SUT with different active profiles.

## Explicitly not carried forward

The archived plan mentioned a Swarm Controller runtime profile client. V1 intentionally keeps runtime apply/clear authority in Orchestrator and Network Proxy Manager. Reintroducing a controller client requires a new architecture decision; it is not an implicit missing implementation.
