# Workers Guide: Basics

| Reader context | Details |
| --- | --- |
| Audience | Scenario authors configuring built-in PocketHive workers |
| Prerequisites | Basic scenario YAML knowledge and the [scenario contract](../scenarios/SCENARIO_CONTRACT.md) |
| Expected outcome | A small worker chain with explicit roles, queues, and configuration |
| Last verified source | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

This guide explains how to use built-in PocketHive workers in scenarios.

## 1. Worker model

Each bee in `scenario.yaml` defines:

- `role` - logical worker role (`generator`, `moderator`, `processor`, `postprocessor`, `trigger`, `request-builder`, `http-sequence`).
- `image` - container image tag.
- `work` - queue aliases (`in` and `out`) used by the swarm work exchange.
- `config` - worker runtime configuration (`worker`, `inputs`, `outputs`, optional `docker`).

Use the version-matched [scenario contract](../scenarios/SCENARIO_CONTRACT.md).

## 2. Minimal HTTP flow

Typical baseline chain:

1. `generator` builds payloads.
2. `request-builder` maps payload + call ID to concrete request envelope.
3. `processor` executes HTTP/TCP call against the SUT.
4. `postprocessor` aggregates/exports metrics and optional enrichments.

Minimal example:

```yaml
template:
  bees:
    - role: generator
      image: generator:latest
      work:
        out:
          out: req
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 5
        worker:
          message:
            bodyType: SIMPLE
            body: '{"customerCode":"custA"}'
    - role: request-builder
      image: request-builder:latest
      work:
        in:
          in: req
        out:
          out: proc
      config:
        templateRoot: /app/templates/http
        serviceId: default
    - role: processor
      image: processor:latest
      work:
        in:
          in: proc
        out:
          out: post
      config:
        baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
```

## 3. Inputs and outputs

Workers are IO-configurable through:

- `config.inputs.*`
- `config.outputs.*`

Common patterns:

- `SCHEDULER` for fixed-rate generation.
- `REDIS_DATASET` for shared, cross-swarm datasets.
- `RABBITMQ` queue aliases for chaining workers.

Use the current [worker capability catalogue](../architecture/workerCapabilities.md)
for per-role inputs and outputs, the
[scenario contract](../scenarios/SCENARIO_CONTRACT.md) for bundle structure,
and [Scenario Patterns](../scenarios/SCENARIO_PATTERNS.md) for supported
composition examples. Archived plans and TODOs are historical, not runtime
contracts.

## 4. Runtime lifecycle boundary

At the exact tested source, the required UI **Connectivity** preflight fails on
`swarm-lifecycle.schema.json#/$defs/RuntimeMetadata`. Stop before **New swarm**,
**Create**, or **Start**; this worker guide does not override that customer
lifecycle gate.

For a future corrected candidate, the completion flow per swarm is:

1. Create swarm from scenario template.
2. Start swarm.
3. Observe `status-full` and queue depth.
4. Update config via control-plane when needed (rate, enablement, etc.).
5. Stop and remove swarm.

References:

- [Customer swarm lifecycle](operators/swarm-lifecycle.md)
- `docs/ORCHESTRATOR-REST.md`
- `docs/ARCHITECTURE.md`

## 5. Practical checklist

- Keep `work` aliases explicit and simple.
- Keep templating in templates, not in Java code.
- Keep worker responsibilities narrow (SRP by role).
- Use Scenario Variables for environment/profile-level differences.
- Prefer `request-builder` and `http-sequence` over duplicating per-call workers.

## Troubleshooting

Check the scenario against the [canonical scenario contract](../scenarios/SCENARIO_CONTRACT.md). For runtime and queue symptoms, use the [observability and troubleshooting guide](operators/observability-troubleshooting.md).

## Next step

Continue with [Workers Guide: Advanced](workers-advanced.md) for control-plane and multi-swarm patterns.
