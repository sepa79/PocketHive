# Observability

## Buzz Panel

The Buzz panel aggregates REST and STOMP activity from both the Hive services and the UI.

1. Open the **Buzz** view in the UI header.
2. Use the **Source** and **Channel** dropdowns to filter entries.
3. Adjust the **Limit** field to control how many recent messages are retained.

Each entry displays its timestamp, origin, channel and payload to help operators trace system interactions.

## Metrics Pushgateway

PocketHive services now export Micrometer metrics to a Prometheus Pushgateway instead of exposing `/actuator/prometheus`.

- **Service wiring.** Every bee receives the gateway URL (`MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL`) from the swarm controller and publishes under the swarm id (`MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB`) and bee name (`MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_GROUPING_KEY_INSTANCE`) labels. The Docker Compose profile also injects these variables for locally run services.
- **Prometheus scrape.** The bundled Prometheus instance scrapes only the Pushgateway (`pushgateway:9091`) with `honor_labels: true`, so dashboards continue to use the swarm/bee labels that workers provide.
- **Lifecycle hygiene.** During shutdown each service deletes its Pushgateway metrics (job + instance grouping key) via `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_SHUTDOWN_OPERATION=DELETE`. Expect the series to vanish within one scrape interval after a container terminates.
- **Retention runbook.** If a worker crashes without executing its shutdown hook, stale metrics remain. Operators can:
  1. Inspect the Pushgateway UI (`http://pushgateway:9091`) to confirm lingering groups.
  2. Manually delete metrics with `curl -X DELETE http://pushgateway:9091/metrics/job/<swarm>/instance/<bee-name>`.
  3. Restart the swarm or offending bee to force a fresh push if the worker is still active.
- **Alerting considerations.** Dashboards and alerts should filter on the swarm/bee tags rather than the old Prometheus scrape job names. Treat the absence of a bee's metrics as a potential failure once the Pushgateway retention window (default 15s scrape interval) elapses.

## Queue depth snapshots

Status events now include an optional `queueStats` object keyed by queue name. Each entry surfaces the latest broker snapshot for that queue:

- `depth` — current message count in the queue.
- `consumers` — number of active consumers bound to the queue.
- `oldestAgeSec` (optional) — age, in seconds, of the oldest message when the broker exposes it.

Operators can correlate these numbers with the `queues` topology block to understand which bindings map to a growing backlog. Empty or unavailable stats simply omit the `queueStats` object.
