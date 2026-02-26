# Workers Guide: Basics

This guide explains how to use built-in PocketHive workers in scenarios.

## 1. Worker model

Each bee in `scenario.yaml` defines:

- `role` - logical worker role (`generator`, `moderator`, `processor`, `postprocessor`, `trigger`, `request-builder`, `http-sequence`).
- `image` - container image tag.
- `work` - queue aliases (`in` and `out`) used by the swarm work exchange.
- `config` - worker runtime configuration (`worker`, `inputs`, `outputs`, optional `docker`).

Reference: `docs/scenarios/SCENARIO_CONTRACT.md`.

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
        templateRoot: /app/http-templates
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

Reference: `docs/toBeReviewed/worker-configurable-io-plan.md`.

## 4. Runtime lifecycle

The normal flow per swarm:

1. Create swarm from scenario template.
2. Start swarm.
3. Observe `status-full` and queue depth.
4. Update config via control-plane when needed (rate, enablement, etc.).
5. Stop and remove swarm.

References:

- `docs/ORCHESTRATOR-REST.md`
- `docs/ARCHITECTURE.md`

## 5. Practical checklist

- Keep `work` aliases explicit and simple.
- Keep templating in templates, not in Java code.
- Keep worker responsibilities narrow (SRP by role).
- Use Scenario Variables for environment/profile-level differences.
- Prefer `request-builder` and `http-sequence` over duplicating per-call workers.
