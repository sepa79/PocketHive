# SwarmController Service

SwarmController manages the lifecycle of a single swarm. It consumes swarm-level signals and fans out
existing per-bee control messages without introducing new routing patterns.

## Responsibilities
- Listen for `sig.swarm-start.<swarmId>` and `sig.swarm-stop.<swarmId>` events.
- Resolve swarm membership and emit `sig.config-update.<role>.<instance>` or
  `sig.status-request.<role>.<instance>` messages for each bee.
- Tag all emitted metrics with the `swarm_id` label and expose a friendly bee name alongside a UUID.

See [control-plane rules](../docs/rules/control-plane-rules.md) for canonical signal formats.
