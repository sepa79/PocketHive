# Trigger Service

Executes scheduled side effects such as shell commands or HTTP calls.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Trigger scheduling and payload settings have moved under `pockethive.trigger`:

```yaml
pockethive:
  worker:
    swarm-id: default
    queues:
      gen: ph.${pockethive.worker.swarm-id}.gen
      mod: ph.${pockethive.worker.swarm-id}.mod
      final: ph.${pockethive.worker.swarm-id}.final
  trigger:
    interval-ms: 60000
    action-type: shell
    command: echo hello
```

Deprecated `ph.trigger.*` keys map to the new namespace for compatibility.

