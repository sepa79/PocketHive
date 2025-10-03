# Log Aggregator Service

Collects service logs and forwards them to Loki for storage.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Loki and queue settings are exposed via `pockethive` keys:

```yaml
pockethive:
  logs:
    queue: ph.logs.agg
  loki:
    url: http://loki:3100
    flush-interval-ms: 1000
```

Existing `ph.*` properties are still wired through deprecated aliases during the migration window.

