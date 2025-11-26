# Scenario Contract

This document defines the **authoring‑time YAML contract** for scenarios.
It is the authoritative reference for `scenario-manager-service` and
for Orchestrator clients that want to create swarms.

The runtime representation is `io.pockethive.scenarios.Scenario`, which
wraps a `SwarmTemplate` and optional `TrafficPolicy`.

---

## Top‑level structure

```yaml
id: local-rest
name: Local REST - Simple REST Swarm
description: Uses local images with a 50 msg/s generator.
template:
  image: swarm-controller:latest
  bees: [ ... ]
trafficPolicy:
  # optional, see traffic-shaping docs
```

- `id` (string, required) – unique scenario id; used in URLs and UI.
- `name` (string, required) – human‑friendly name.
- `description` (string, optional) – short description.
- `template` (object, required) – maps directly to `SwarmTemplate`:
  - `image` (string, required) – swarm‑controller image name.
  - `bees` (array, required) – list of Bee definitions.
- `trafficPolicy` (object, optional) – controls guards and traffic
  shaping; see `docs/traffic-shaping.md`.

Unknown top‑level fields are ignored by the Swarm model but should be
avoided to keep scenarios portable.

## Bees

Each **bee** defines one worker role in the swarm.

```yaml
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      work:
        out: genQ
      config: { ... }
    - role: processor
      image: processor:latest
      work:
        in: genQ
        out: finalQ
      config: { ... }
```

Bee fields (see `common/swarm-model/src/main/java/io/pockethive/swarm/model/Bee.java`):

- `role` (string, required)
  - Logical role name, e.g. `generator`, `processor`, `moderator`,
    `postprocessor`, `http-builder`.
- `image` (string, required)
  - Container image name (logical); Orchestrator will prefix it with the
    configured repository, e.g. `generator:latest` →
    `ghcr.io/ORG/pockethive/generator:latest`.
- `work` (object, required)
  - `in` (string, optional) – inbound queue suffix.
  - `out` (string, optional) – outbound queue suffix.
  - These suffixes are resolved to full queue names by the Swarm
    Controller using the swarm id and shared naming rules.
- `env` (map<string,string>, optional)
  - Raw environment variables to pass into the container.
- `config` (map<string,object>, optional)
  - Structured config for this role; becomes `SwarmPlan.bees[*].config`
    and is fanned out to the worker as a `config-update` signal during
    bootstrap.

> **Rule:** every bee must have a `work` section, even if both `in` and
> `out` are null. The Swarm model treats `work` as non‑null.

## Worker configuration shape

Inside `config` we follow a common convention:

```yaml
config:
  inputs:
    type: SCHEDULER            # or REDIS_DATASET, RABBITMQ, etc.
    scheduler:
      ratePerSec: 50
  outputs:
    type: RABBITMQ             # or NOOP, etc. (subject to IO plan)
  worker:
    # worker‑specific settings
    message:
      bodyType: HTTP
      path: /test
      method: POST
      body: '{"event":"local-rest"}'
      headers:
        content-type: application/json
```

This mirrors how workers bind properties:

- `inputs` – IO configuration for the inbound side.
  - `type` – input type enum (e.g. `SCHEDULER`, `REDIS_DATASET`).
  - `<typeKey>` – type‑specific config section
    (e.g. `scheduler`, `redis`).
- `outputs` – IO configuration for the outbound side.
  - `type` – output type enum (e.g. `RABBITMQ`, `NOOP`).
  - `<typeKey>` – type‑specific config.
- `worker` – logical worker config.
  - Keys under this section are specific to each role and are documented
    in the worker SDK and capability manifests.

The **capabilities** files under
`scenario-manager-service/capabilities/*.latest.yaml` are the
authoritative list of user‑tunable fields per worker and IO type. Each
entry maps a UI field to a path in `config`.

### IO configuration examples

**Scheduler generator (ticks only):**

```yaml
config:
  inputs:
    type: SCHEDULER
    scheduler:
      ratePerSec: 50
      maxMessages: 0        # 0 = infinite, >0 = finite run
  outputs:
    type: RABBITMQ
  worker:
    message:
      bodyType: SIMPLE
      body: 'tick'
```

**Redis dataset consumer:**

```yaml
config:
  inputs:
    type: REDIS_DATASET
    redis:
      host: redis
      port: 6379
      listName: ph:dataset:custa
      ratePerSec: 5
  outputs:
    type: RABBITMQ
  worker:
    message:
      bodyType: HTTP
      path: /api/demo
      method: POST
      body: '{{ payload }}'
      headers:
        content-type: application/json
```

## Docker volumes

To mount host directories into bee containers from a scenario, use
`config.docker.volumes`:

```yaml
config:
  docker:
    volumes:
      - /opt/pockethive/http-templates:/app/http-templates:ro
      - /opt/pockethive/scenario-overrides:/app/scenarios:ro
  worker:
    # normal worker config...
```

- `volumes` is a list of Docker bind specs:
  - `hostPath:containerPath[:mode]` where `mode` is `ro` or `rw`.
- Swarm Controller passes these to the underlying Docker client when
  provisioning containers.

## Traffic policy (buffer guard)

The optional `trafficPolicy` section is used by Swarm Controller guards,
especially the buffer guard. Its schema is documented in
`docs/traffic-shaping.md` and validated by the guard engine.

At a high level it allows you to:

- Choose which queues to monitor (by suffix).
- Define target depths and hysteresis ranges.
- Configure rate adjustments for generators/moderators.
- Optionally enable prefill and downstream backpressure.

Including `trafficPolicy` in a scenario is how you enable these
behaviours for a swarm.

## Validation & tooling

To validate scenarios and templates **without** starting a swarm, use
the CLI tool under `tools/scenario-templating-check`.

Examples:

```bash
# Check generator templating only
tools/scenario-templating-check/run.sh \
  --scenario scenario-manager-service/scenarios/e2e/templated-rest.yaml

# Check generator + HTTP Builder templates referenced from a scenario
tools/scenario-templating-check/run.sh \
  --check-http-templates \
  --scenario scenario-manager-service/scenarios/e2e/redis-dataset-demo.yaml
```

The tool will:

- Parse the scenario and build a template context with a sample WorkItem.
- Render generator templates via Pebble+SpEL and report errors.
- For HTTP Builder:
  - Load HTTP templates from the configured root.
  - Check that every `x-ph-call-id` used in the scenario has a matching
    template.
  - Render each template once with a dummy WorkItem to catch errors.

## Backwards compatibility

- Scenario Manager ignores unknown fields but workers and Swarm
  Controller expect config to adhere to their property bindings.
- The **NFF** rule applies: avoid multiple keys for the same concept and
  do not rely on fallback chains. Use the config structure described
  here and in capability manifests.

