# Moderator Service

Filters or rewrites generator output before it reaches the processor.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Moderator workers read queue and swarm settings from `pockethive.worker`:

```yaml
pockethive:
  worker:
    swarm-id: default
    queues:
      gen: ph.${pockethive.worker.swarm-id}.gen
      mod: ph.${pockethive.worker.swarm-id}.mod
```

Legacy `ph.*` properties remain temporarily supported but are deprecated.

