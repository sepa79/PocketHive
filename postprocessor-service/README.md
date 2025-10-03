# Postprocessor Service

Aggregates final responses and emits metrics for analysis.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Postprocessor workers resolve queues from `pockethive.worker`:

```yaml
pockethive:
  worker:
    swarm-id: default
    queues:
      final: ph.${pockethive.worker.swarm-id}.final
```

Use the new keys for forward compatibility; `ph.*` remains as a deprecated alias.

