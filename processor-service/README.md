# Processor Service

Calls the system under test and forwards responses downstream.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Processor configuration uses the consolidated namespace:

```yaml
pockethive:
  worker:
    swarm-id: default
    queues:
      gen: ph.${pockethive.worker.swarm-id}.gen
      mod: ph.${pockethive.worker.swarm-id}.mod
      final: ph.${pockethive.worker.swarm-id}.final
  processor:
    base-url: http://wiremock:8080
```

Deprecated `ph.*` keys still bind for backward compatibility until the migration completes.

