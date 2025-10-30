# Postprocessor Service

Aggregates final responses and emits metrics for analysis.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Post-processor deployments resolve routing exclusively from `pockethive.control-plane.*` properties. Provide the hive
traffic exchange (`pockethive.control-plane.traffic-exchange`) and queue aliases (for example
`pockethive.control-plane.queues.final`) in configuration and consult the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) plus the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for reference YAML.

### Detailed metrics mode

Operators can opt-in to an expanded metrics stream by setting
`pockethive.control-plane.worker.postprocessor.publish-all-metrics` to `true`. The flag defaults to
`false` so existing deployments continue emitting the lightweight summary only.

```yaml
pockethive:
  control-plane:
    worker:
      postprocessor:
        enabled: true
        publish-all-metrics: true
```

When enabled, the worker still enriches the status snapshot with hop durations, hop metadata, and
processor call statistics. In addition, every processed message is written to dedicated Prometheus
gauges (for example `ph_transaction_hop_duration_ms`, `ph_transaction_total_latency_ms`, and
`ph_transaction_processor_*`) so operators can plot high-resolution timelines directly from scraped
metrics.

