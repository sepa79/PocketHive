# Archived: Swarm Lifecycle + Health Model Plan

Status: superseded on 2026-07-22 by `docs/ARCHITECTURE.md` §5.

This plan correctly identified that the old `swarmStatus` / `swarmHealth` model mixed provisioning, workload and health facts. Its proposed remedy—a larger single lifecycle enum whose ownership moved from Orchestrator to Controller—was rejected during the lifecycle/control-plane redesign.

The canonical design uses independent, single-writer axes instead:

- Orchestrator-owned runtime and workload intent;
- Controller-projected controller and workload observation;
- health independent from lifecycle;
- adapter-observed runtime resources; and
- Orchestrator-owned operations with exact correlation and one public outcome.

Historical candidate states such as `CREATING_ENV`, `AWAITING_WORKERS`, `RUNNING`, `REMOVED` and `FAILED` must not be restored as one domain enum. UI progress labels may be derived projections, but they must never become a second contract or drive behavior.
