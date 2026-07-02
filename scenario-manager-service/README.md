# Scenario Manager Service

Manages simulation scenarios over a REST API.

See the [architecture reference](../docs/ARCHITECTURE.md) for endpoint and signal details.

## Scenario schema

Scenarios describe swarms in YAML (see [`scenarios/`](scenarios/)). Each bee declares worker overrides directly inside
its `config`, for example:

```yaml
config:
  ratePerSec: 12
  message:
    path: /api/guarded
    body: warmup
```

The Scenario Manager passes those maps into `SwarmPlan.bees[*].config`, and the Swarm Controller immediately emits the
corresponding `config-update` signals when the swarm starts. No environment variables are required for logical scenario
settings. Service defaults under `pockethive.worker.*` remain runtime application defaults for locally launched workers,
not a scenario YAML shape.
