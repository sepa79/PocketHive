# SwarmController Integration Plan

1. **Implement swarm signal listener**
   - Subscribe to the `ph.control` exchange for `sig.swarm-start.<swarmId>` and `sig.swarm-stop.<swarmId>`.
   - Filter messages so the controller acts only on its `Topology.SWARM_ID`.
2. **Resolve membership**
   - Query Herald or another registry to obtain the list of bees in the swarm.
   - Cache memberships and update on new swarm member events.
3. **Fan-out per-bee commands**
   - Emit `sig.config-update.<role>.<instance>` or `sig.status-request.<role>.<instance>` for each resolved bee.
4. **Metrics and logging**
   - Tag metrics with `swarm_id`, `service`, and `instance`.
   - Emit traceable logs for received and dispatched signals.
5. **UI and service integration**
   - Surface SwarmController status in the UI and wire create/start/stop controls.
   - Drop swarm-level listeners from other services once they rely on the controller.
   - Extend integration tests to cover swarm start/stop through SwarmController.
