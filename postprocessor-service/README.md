# Postprocessor Service

Aggregates final responses and emits metrics for analysis.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Post-processor deployments resolve routing exclusively from `pockethive.inputs.rabbit` /
`pockethive.outputs.rabbit`. Provide the final queue/exchange bindings (for example `pockethive.inputs.rabbit.queue`
and `pockethive.outputs.rabbit.exchange`) in configuration and consult the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) plus the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for reference YAML.

### Metrics

The postprocessor currently emits aggregate Micrometer metrics for the completed pipeline:

- `ph_hop_latency_ms`
- `ph_total_latency_ms`
- `ph_hops`
- `ph_errors_total`
- `ph_processor_latency_ms`
- `ph_processor_calls_total`
- `ph_processor_calls_success_total`
- `ph_processor_success_ratio`
- `ph_processor_latency_avg_ms`

These metrics are tagged with `ph_role`, `ph_instance`, and `ph_swarm` and are exported through the
standard PocketHive ClickHouse metrics sink described in [Observability](../docs/observability.md).

There is no supported `publish-all-metrics` config flag in the current postprocessor contract. The
old per-transaction gauges such as `ph_transaction_hop_duration_ms`,
`ph_transaction_total_latency_ms`, and `ph_transaction_processor_*` are not emitted by the current
worker path. Transaction-level Grafana analysis should use the ClickHouse tx-outcomes sink described
in [Observability](../docs/observability.md#postprocessor-clickhouse-tx-outcomes-sink).

DB-only scenarios can reuse the ClickHouse tx-outcome sink by emitting the shared outcome headers
(`x-ph-call-id`, `x-ph-processor-*`, `x-ph-business-*`, and `x-ph-dim-*`) on the step consumed by
postprocessor. `db-query-service` maps successful DB executions into those headers, so existing
ClickHouse dashboards can plot DB query latency via `processorDurationMs` and group by `callId`,
`businessCode`, or entries in the `dimensions` map.
