# Observability

## Buzz Panel

The Buzz panel aggregates REST and STOMP activity from both the Hive services and the UI.

1. Open the **Buzz** view in the UI header.
2. Use the **Source** and **Channel** dropdowns to filter entries.
3. Adjust the **Limit** field to control how many recent messages are retained.

Each entry displays its timestamp, origin, channel and payload to help operators trace system interactions.

## Metrics Pushgateway

PocketHive services now export Micrometer metrics to a Prometheus Pushgateway instead of exposing `/actuator/prometheus`.

- **Service wiring.** Each bee binds the standard Micrometer properties (`management.prometheus.metrics.export.pushgateway.*`) so deployments can supply the gateway URL, swarm id (`...job`), and bee name (`...grouping-key.instance`) directly via configuration or environment variables. The Docker Compose profile continues to inject these variables for local services.
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

## Postprocessor `publishAllMetrics`

The postprocessor still emits aggregated latency and error metrics to Prometheus, but the high-frequency per-item (`publishAllMetrics=true`) metrics are currently disabled to avoid flooding the control plane and dashboards. A dedicated event-time sink (Influx/Loki) will be added in a future change; until then, `publishAllMetrics` only affects internal aggregation and no longer pushes per-message samples to Prometheus or status payloads.

## Buffer Guard runbook

1. **Confirm prerequisites**
   - Controller feature flag `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_FEATURES_BUFFER_GUARD_ENABLED` must be `true` (default).
   - The active scenario must define `trafficPolicy.bufferGuard.enabled: true` and point `queueAlias` at the queue you want to regulate (e.g., `moderator-a-out`).
2. **Watch the guard gauges**
   - Depth: `ph_swarm_buffer_guard_depth{swarm="<id>"}` — moving average of the guarded queue.
   - Target/min/max: `ph_swarm_buffer_guard_target` (constant) and the queue depth gauges `ph_swarm_queue_depth` for min/max context.
   - Rate override: `ph_swarm_buffer_guard_rate_per_sec` — the last rate pushed to the producer role.
   - State: `ph_swarm_buffer_guard_state` — numeric code (0=disabled, 1=steady, 2=prefill, 3=filling, 4=draining, 5=backpressure).
   - Dashboards should plot depth vs. target and rate vs. time for each guarded swarm (use `queue` and `swarm` labels).
3. **Inspect logs**
   - Set `io.pockethive.swarmcontroller.guard` to `INFO` (already defaulted in `logback-spring.xml`) to view lines such as:
     - `Buffer guard [moderator-a-out] state -> DRAINING (...)`
     - `Buffer guard [moderator-a-out] adjusting generator rate 40.0 -> 28.5 (...)`
   - Suppress noisy `[CTRL]` logs by keeping the root `io.pockethive` logger at `WARN`.
4. **Runbook actions**
   - **Target drifting low/high**: adjust `targetDepth` and the `[minDepth, maxDepth]` window in the scenario; redeploy/restart the swarm.
   - **Guard thrashing**: widen the min/max bracket or reduce `adjust.maxIncreasePct` / `maxDecreasePct`.
   - **Backpressure events**: verify the downstream queue alias and thresholds (`highDepth`, `recoveryDepth`, `moderatorReductionPct`). If backpressure toggles constantly, raise the thresholds or add more capacity downstream.
   - **No guard activity**: check the scenario config was applied (UI component detail shows `trafficPolicy.bufferGuard`) and ensure the guarded queue name resolves via `properties.queueName(queueAlias)`; the controller logs a warning if the alias is invalid.
5. **Grafana panels**
   - Create a template variable for `swarm` and `queue`.
   - Panel 1: Line chart showing `ph_swarm_buffer_guard_depth` vs. `ph_swarm_buffer_guard_target`.
   - Panel 2: Rate overrides (`ph_swarm_buffer_guard_rate_per_sec`) alongside the current generator rate (from worker metrics) to spot divergence.
   - Panel 3: State transitions using `ph_swarm_buffer_guard_state` (use thresholds/legend for state labels).
