
# Scenario→Controller Mapping (for Scenario Manager)

For each Track:
1) Materialize N instances:
   - swarmId = `{runPrefix}.{templateRef}.{index}` (index = 01..N)
2) Emit signals via Controller (topic `ph.control`):
   - Create: `sig.swarm-create.{swarmId}` with SwarmPlan (roles, processor bindings)
   - Start: `sig.swarm-start.{swarmId}`
   - Stop:  `sig.swarm-stop.{swarmId}`
3) For blocks:
   - Ramp/Hold/Spike/Pause: translate into generator rate change commands to Marshal
     - e.g., `sig.gen-rate.{swarmId}` payload `{ ratePerSec, durationSec, profile:"ramp|hold|spike|pause", ... }`
   - Signal: `sig.user.{name}` Published once with payload and `runPrefix` for scoping
   - WaitFor: local gate; subscribe to `sig.user.{name}` and resume on receipt (timeout => track error)
4) Teardown:
   - After track completes: `sig.swarm-stop.{swarmId}` and optionally `sig.swarm-destroy.{swarmId}`

Notes:
- All emitted signals MUST include `runId` and `runPrefix` in payload for correlation.
- Scenario Manager owns scheduling and alignment of block boundaries wall-clock wise.
