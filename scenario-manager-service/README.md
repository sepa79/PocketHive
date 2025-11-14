# Scenario Manager Service

Manages simulation scenarios over a REST API.

See the [architecture reference](../docs/ARCHITECTURE.md) for endpoint and signal details.

## Scenario schema

Scenarios describe swarms in YAML (see [`scenarios/`](scenarios/)). Each bee can declare overrides by embedding a
`pockethive.worker.config` block inside its `config`, for example:

```yaml
config:
  pockethive:
    worker:
      config:
        ratePerSec: 12
        message:
          path: /api/guarded
          body: warmup
```

The Scenario Manager merges those maps into `SwarmPlan.bees[*].config`, and the Swarm Controller immediately emits the
corresponding `config-update` signals when the swarm starts. No environment variables are required for logical worker
settings—the scenario (or the service defaults under `pockethive.worker.*`) is the single source of truth.
