# Observability

## Buzz Panel

The Buzz panel aggregates REST and STOMP activity from both the Hive services and the UI.

1. Open the **Buzz** view in the UI header.
2. Use the **Source** and **Channel** dropdowns to filter entries.
3. Adjust the **Limit** field to control how many recent messages are retained.

Each entry displays its timestamp, origin, channel and payload to help operators trace system interactions.

## Runtime Logs

PocketHive does not run a central Loki/Log Aggregator pipeline. Services write
logs to container stdout/stderr, and product-owned tools read them on demand
through Orchestrator's runtime debug API:

- UI runtime inspector: bounded `Logs` action for workers and managers.
- PocketHive MCP: `runtime_tail_worker_logs`, backed by Orchestrator.
- REST: `POST /api/runtime/debug/resources/logs` as documented in
  `docs/ORCHESTRATOR-REST.md`.

The runtime debug path returns finite, redacted Docker/Swarm log reads. It is for
debugging current runtime state, not durable log retention.

## Product Metrics (ClickHouse)

PocketHive product metrics use the explicit metrics adapter and are stored in
ClickHouse. Runtime targets must declare one adapter (`CLICKHOUSE` for active
metrics, or an explicit disabled/test state for targets that intentionally do
  not publish metrics). Do not add a secondary metrics backend as a silent fallback.

- **Storage.** Aggregate service metrics are written to `ph_metrics_samples`.
  The table stores `eventTime`, `swarmId`, `runId`, `role`, `instance`,
  `metricName`, `metricKind`, `statistic`, `value`, `unit`, and bounded
  `labels`. The ClickHouse schema enforces a 30-day TTL.
- **Service wiring.** Active runtimes set `POCKETHIVE_METRICS_ADAPTER=CLICKHOUSE`
  together with explicit ClickHouse settings:
  `POCKETHIVE_METRICS_CLICKHOUSE_ENDPOINT`,
  `POCKETHIVE_METRICS_CLICKHOUSE_TABLE`, credentials when required, batching,
  timeout, buffer, and label-bound settings. Identity fields
  (`POCKETHIVE_METRICS_SWARM_ID`, `POCKETHIVE_METRICS_RUN_ID`,
  `POCKETHIVE_METRICS_ROLE`, `POCKETHIVE_METRICS_INSTANCE`) must be explicit for
  swarm-scoped components.
- **Semantics.** Counters are stored as cumulative samples; dashboards compute
  rates from the difference between the first and last sample in each time
  bucket. Timers store `COUNT` and `SUM` samples where applicable; gauges store
  current `VALUE` snapshots.
- **Bounds.** Label count, key length, and value length are rejected at the
  ClickHouse metrics sink boundary. Rejected samples are skipped individually
  and logged so one bad meter does not stop a full publish cycle.

## Queue depth snapshots

Status events now include an optional `queueStats` object keyed by queue name. Each entry surfaces the latest broker snapshot for that queue:

- `depth` — current message count in the queue.
- `consumers` — number of active consumers bound to the queue.
- `oldestAgeSec` (optional) — age, in seconds, of the oldest message when the broker exposes it.

Operators can correlate these numbers with the `queues` topology block to understand which bindings map to a growing backlog. Empty or unavailable stats simply omit the `queueStats` object.

## Postprocessor ClickHouse tx-outcomes sink

Per-transaction postprocessor observability relies on the ClickHouse
tx-outcomes sink. Transaction-level analysis (latency tails, success ratios,
error mixes, segment drill-downs) should be done from `ph_tx_outcome_v2`.
Aggregated service metrics for runtime dashboards are stored in
`ph_metrics_samples`. The bundled Grafana transaction dashboards target
`ph_tx_outcome_v2` only. The legacy `ph_tx_outcome_v1` table is no longer
created for fresh ClickHouse volumes; when it exists in an old volume, treat it
as read-only historical source data for migration and benchmark work.

For wide time ranges, prefer the long-term RTT dashboard. It requires selecting one `swarmId` first, uses hourly-or-coarser buckets, and avoids high-cardinality `swarm/callId/businessCode` time-series fan-out except for bounded drill-down tables.

## Buffer Guard runbook

1. **Confirm prerequisites**
   - Controller feature flag `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_FEATURES_BUFFER_GUARD_ENABLED` must be `true` (default).
   - The active scenario must define `trafficPolicy.bufferGuard.enabled: true` and point `queueAlias` at the queue you want to regulate (e.g., `moderator-a-out`).
2. **Watch the guard gauges**
   - Depth: `metricName = 'ph_swarm_buffer_guard_depth'` in
     `ph_metrics_samples` — moving average of the guarded queue.
   - Target/min/max: `ph_swarm_buffer_guard_target` and
     `ph_swarm_queue_depth` for queue-depth context.
   - Rate override: `ph_swarm_buffer_guard_rate_per_sec` — the last rate pushed
     to the producer role.
   - State: `ph_swarm_buffer_guard_state` — numeric code (0=disabled, 1=steady,
     2=prefill, 3=filling, 4=draining, 5=backpressure).
   - Dashboards should plot depth vs. target and rate vs. time for each guarded
     swarm using `swarmId` and `labels['queue']`.
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
