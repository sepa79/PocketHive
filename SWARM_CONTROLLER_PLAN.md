# SwarmController Integration Plan

## Phase 1 – Control channel handshake
- Subscribe to the `ph.control` exchange and declare `ph.control.herald.<instance>`.
- Emit `ev.ready.herald.<instance>` on startup.
- Queen responds with `sig.swarm-start.<swarmId>` carrying the SwarmPlan.

## Phase 2 – Plan expansion and queue provisioning
- Parse the SwarmPlan to resolve bee roles and queue suffixes.
- Declare `ph.<swarmId>.hive` and bind all `work.in/out` queues.

## Phase 3 – Bee lifecycle management
- Launch bee containers using the images from the plan.
- Fan-out `sig.config-update.<role>.<instance>` and `sig.status-request.<role>.<instance>` to individual bees as needed.

## Phase 4 – Swarm shutdown
- Handle `sig.swarm-stop.<swarmId>` by stopping bees and deleting queues.

## Phase 5 – Observability and UI integration
- Tag metrics with `swarm_id`, `service`, and `instance` and emit traceable logs.
- Surface controller status in the UI and wire create/start/stop controls.
- Extend integration tests to cover swarm start/stop through SwarmController.
