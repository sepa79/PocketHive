# Postprocessor Service

Aggregates final responses and emits metrics for analysis.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Post-processor deployments resolve routing exclusively from `pockethive.inputs.rabbit` /
`pockethive.outputs.rabbit`. Provide the final queue/exchange bindings (for example `pockethive.inputs.rabbit.queue`
and `pockethive.outputs.rabbit.exchange`) in configuration and consult the
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
        forward-to-output: false
```

When enabled, the worker still enriches the status snapshot with hop durations, hop metadata, and
processor call statistics. Per-item Prometheus metrics are intentionally not emitted (even when
`publish-all-metrics: true`) to avoid flooding the control-plane metrics pipeline.

For per-transaction analytics, use the ClickHouse sink mode described below.

### Forwarding mode

Set `forward-to-output: true` to pass the original `WorkItem` downstream (for example to a dedicated
analytics sink worker). With the default `false`, postprocessor remains terminal and does not emit
downstream messages.

### ClickHouse sink mode

Set `write-tx-outcome-to-click-house: true` to persist transaction outcomes directly from postprocessor.
The worker projects `x-ph-call-id`, `x-ph-processor-*`, `x-ph-business-*`, and `x-ph-dim-*` headers into
ClickHouse `JSONEachRow` inserts.

```yaml
pockethive:
  control-plane:
    worker:
      postprocessor:
        write-tx-outcome-to-click-house: true
        drop-tx-outcome-without-call-id: true
  sink:
    clickhouse:
      endpoint: http://clickhouse:8123
      table: ph_tx_outcome_v1
      # Performance knobs (batching). These are optional.
      batch-size: 200
      flush-interval-ms: 200
      max-buffered-events: 50000
```

The writer batches inserts in-memory and flushes either when `batch-size` is reached or when
`flush-interval-ms` elapses. If the buffer reaches `max-buffered-events`, the worker reports the
condition via status (`txOutcomeBufferFull`) and treats it as a sink failure.
