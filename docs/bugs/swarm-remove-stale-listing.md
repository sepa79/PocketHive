# Swarm remove is async and list view can show stale state briefly

**Area:** Orchestrator swarm lifecycle / UI polling  
**Status:** Open  
**Impact:** Operators may think `remove` failed because the swarm can remain visible for a few seconds after `POST /remove`.

---

## Problem

`POST /api/swarms/{swarmId}/remove` is asynchronous and correctly returns `202`.

The actual flow is:

1. Orchestrator sends `signal.swarm-remove...`
2. `swarm-controller` tears down workers / queues and emits `event.outcome.swarm-remove...`
3. Orchestrator receives that outcome
4. Orchestrator stops and removes the manager container
5. Orchestrator deletes the controller queue and removes the swarm from `SwarmStore`

During that window, `list-swarms` can still briefly show the swarm using the last controller snapshot, which makes the UI look inconsistent.

---

## Observed runtime evidence

Live smoke on `remove-smoke-1776443593`:

- `POST /remove` at `2026-04-17T16:33:25.285Z`
- `event.outcome.swarm-remove...` at `2026-04-17T16:33:29.652Z`
- `SwarmStore: READY -> REMOVING` at `2026-04-17T16:33:29.652Z`
- `SwarmStore: REMOVING -> REMOVED` at `2026-04-17T16:33:31.016Z`

Total remove time in this run: about `5.7s`.

Relevant code:

- [SwarmController.java](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/app/SwarmController.java)
- [SwarmSignalListener.java](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/app/SwarmSignalListener.java)
- [ContainerLifecycleManager.java](../../orchestrator-service/src/main/java/io/pockethive/orchestrator/app/ContainerLifecycleManager.java)
- [SwarmRuntimeCore.java](../../swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/runtime/SwarmRuntimeCore.java)
- [DockerContainerClient.java](../../common/docker-client/src/main/java/io/pockethive/docker/DockerContainerClient.java)

---

## Likely cause

The remove flow itself is working.

The confusing part is the transient state:

- the `swarm-controller` outcome arrives a few seconds after the request
- then the orchestrator still performs synchronous Docker teardown of the manager container
- during that time, list/status surfaces may still expose the last known controller snapshot

So this is primarily a **stale-listing / stale-status presentation bug**, not a missing remove signal.

---

## Follow-up direction

- Make `list-swarms` prefer `SwarmStore` lifecycle state when a swarm is already `REMOVING`.
- Ensure the UI can clearly represent `REMOVING` instead of continuing to show an old `STOPPED`/`READY` snapshot.
- Optionally measure whether `computeAdapter.stopManager(...)` is the dominant contributor to remove latency and expose that in logs/metrics.

