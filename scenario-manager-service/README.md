# Scenario Manager Service

Manages simulation scenarios over a REST API.

See the [architecture reference](../docs/ARCHITECTURE.md) for endpoint and signal details.

## Scenario schema

Scenarios describe swarms in YAML (see [`scenarios/`](scenarios/)). Each entry can declare worker overrides under
`workers.<role>.config`, for example:

```yaml
workers:
  generator:
    config:
      ratePerSec: 12
      message:
        path: /api/guarded
        body: warmup
  processor:
    config:
      baseUrl: http://wiremock:8080
      timeoutMillis: 2500
```

The Scenario Manager merges those maps into `SwarmPlan.bees[*].config`, and the Swarm Controller immediately emits the
corresponding `config-update` signals when the swarm starts. No environment variables are required for logical worker
settings—the scenario (or the service defaults under `pockethive.workers.<role>.*`) is the single source of truth.
