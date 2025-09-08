# Log Aggregator Service

Collects logs from all bees and forwards batches to Loki for storage.

## Responsibilities
- Consume log events from the `ph.logs` exchange.
- Enrich log entries with swarm context before shipping.
