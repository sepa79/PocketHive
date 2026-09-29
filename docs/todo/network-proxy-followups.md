# Network Proxy Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/network-proxy-plan.md`

## Goal

Track only work that remains after the delivered shared-per-SUT Network Proxy V1.

## Runtime reliability

- [x] Replace cross-node NFS `inotify` reliance with digest polling and an applied-digest handshake.
- [ ] Add a swarm/NFS regression proving that a newly applied binding creates the expected HAProxy listener and route before generator traffic starts.

## Scenario Plan integration

The proposed replacement is a separate
[SUT Plan owned by one SUT Controller](global-sut-mocks-spec.md#continuous-sut-plans).
It preserves scenario YAML and the existing proxy services. Review that ownership
change before implementation; the older swarm `network-profile` step is not an
independent delivery requirement.

- [ ] Review the SUT-scoped profile apply/readback and autonomous-plan contracts.
- [ ] Deliver the accepted controller integration and phase evidence through the
      existing Network Proxy Manager.

## Isolation upgrade

- [ ] Design per-swarm DNS/gateway isolation.
- [ ] Add per-swarm binding namespaces and blast-radius containment.
- [ ] Support concurrent swarms targeting one SUT with different active profiles.

## Explicitly not carried forward

The archived plan mentioned a Swarm Controller runtime profile client. V1 intentionally keeps runtime apply/clear authority in Orchestrator and Network Proxy Manager. The new proposal introduces a distinct SUT Controller, not that old Swarm Controller client. Its authority split requires the explicit architecture amendment identified in the specification; it is not an implicit missing implementation.
