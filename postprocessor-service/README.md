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

Operators can opt-in to an expanded status payload that includes per-hop details and processor call
metadata by setting `pockethive.control-plane.worker.postprocessor.publish-all-metrics` to `true`.
The flag defaults to `false` so existing deployments continue emitting the lightweight summary.

```yaml
pockethive:
  control-plane:
    worker:
      postprocessor:
        enabled: true
        publish-all-metrics: true
```

When enabled, the worker publishes hop duration lists, hop metadata, and processor call statistics
alongside the usual summary metrics so the runtime can emit a full snapshot for observability tooling.

