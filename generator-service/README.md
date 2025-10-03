# Generator Service

Generates swarm traffic by publishing messages to the hive exchange.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Generator defaults now live under the `pockethive` namespace:

```yaml
pockethive:
  worker:
    swarm-id: default
    queues:
      gen: ph.${pockethive.worker.swarm-id}.gen
      mod: ph.${pockethive.worker.swarm-id}.mod
  generator:
    rate-per-sec: 0
    message:
      path: /api/test
      method: POST
```

Legacy `ph.*` keys continue to bind via deprecated aliases to ease migration, but new deployments should prefer the `pockethive.*` properties.

