# Scenario Contract

This document defines the **authoring‑time YAML contract** for scenarios.
It is the authoritative reference for `scenario-manager-service` and
for Orchestrator clients that want to create swarms.

The runtime representation is `io.pockethive.scenarios.Scenario`, which
wraps a `SwarmTemplate` and optional `TrafficPolicy`.

---

## Top‑level structure

```yaml
protocolVersion: "2.0.0"
id: local-rest
name: Local REST - Simple REST Swarm
description: Uses local images with a 50 msg/s generator.
template:
  image: swarm-controller:latest
  bees: [ ... ]
topology:
  version: 1
  edges: [ ... ]
trafficPolicy:
  # optional, see traffic-shaping docs
```

- `protocolVersion` (SemVer string, required) – scenario authoring protocol
  version. Scenario Manager currently supports major version `2`. A missing,
  malformed, or different-major version is rejected before deeper bundle
  validation. There is no implicit version for legacy files.
- `id` (string, required) – unique scenario id; used in URLs and UI.
- `name` (string, required) – human‑friendly name.
- `description` (string, optional) – short description.
- `template` (object, required) – maps directly to `SwarmTemplate`:
  - `image` (string, required) – swarm‑controller image name.
  - `bees` (array, required) – list of Bee definitions.
- `topology` (object, optional) – logical graph for UI and scenario tooling.
  - `version` (number, required) – topology schema version (currently `1`).
  - `edges` (array, required) – list of logical edges.
- `trafficPolicy` (object, optional) – controls guards and traffic
  shaping; see `docs/architecture/traffic-shaping.md`.

Unknown top‑level fields are ignored by the Swarm model but should be
avoided to keep scenarios portable.

## Bees

Each **bee** defines one scenario worker declaration in the swarm. `role` is the
unique scenario node key. Runtime worker identity is still the materialised
`instance` exposed in `status-full.data.context.workers[]`; live runtime
mutation addresses `role + instance`.

```yaml
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      work:
        out:
          out: genQ
      config: { ... }
    - role: processor
      image: processor:latest
      work:
        in:
          in: genQ
        out:
          out: finalQ
      config: { ... }
```

Bee fields (see `common/swarm-model/src/main/java/io/pockethive/swarm/model/Bee.java`):

- `role` (string, required)
  - Unique scenario node key, e.g. `generator`, `processor`, `moderator-a`,
    `gen-oauth-client`, `request-builder`, `http-sequence`.
  - `role` must be unique within one `scenario.yaml`.
  - `role` is the control-plane routing segment for the materialised worker.
  - Worker type/capability is resolved from `image`, not from a second id field.
  - Legacy note: older bundles may still reference `http-builder`.
- `image` (string, required)
  - Container image name (logical); Orchestrator will prefix it with the
    configured repository, e.g. `generator:latest` →
    `ghcr.io/ORG/pockethive/generator:latest`.
- `work` (object, required)
  - `in` (`map<string,string>`, optional) – inbound queue suffixes keyed by input port id.
  - `out` (`map<string,string>`, optional) – outbound queue suffixes keyed by output port id.
  - These suffixes are resolved to full queue names by the Swarm Controller
    using the swarm id and shared naming rules.
- `env` (`map<string,string>`, optional)
  - Raw environment variables to pass into the container.
- `config` (`map<string,object>`, optional)
  - Structured config for this role; becomes `SwarmPlan.bees[*].config`
    and is fanned out to the worker as a `config-update` signal during
    bootstrap.
- `ports` (array, optional)
  - Logical IO ports for `topology` (not used for runtime wiring).
  - Each port: `id` (string, required), `direction` (`in` or `out`, required).

> **Rule:** every bee must have a `work` section, even if both `in` and
> `out` are null. The Swarm model treats `work` as non‑null.

## Topology (logical graph)

`topology` is the SSOT for the logical graph that UI and scenario tooling use
to draw edges. It does not replace `work` queue suffixes and is not a runtime
binding list. Runtime bindings are emitted by swarm-controller in
`status-full.data.context.bindings`.

Runtime worker identity is assigned by Swarm Controller as `instance`. Scenario
Manager validates authoring identity separately: `template.bees[].role` is
required, unique, and must be referenced by topology endpoints.
`template.bees[].id` and `topology.edges[].from|to.beeId` are not valid fields.

```yaml
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      work:
        out:
          out: genQ
      ports:
        - { id: out, direction: out }
    - role: moderator
      image: moderator:latest
      work:
        in:
          in: genQ
        out:
          out: procQ
      ports:
        - { id: in, direction: in }
        - { id: out, direction: out }

topology:
  version: 1
  edges:
    - id: e1
      from: { role: generator, port: out }
      to:   { role: moderator, port: in }
```

`work` port keys must match the `ports` ids when `topology` is present. For
single-input/output bees, use the standard `in` and `out` port ids.

Edge fields:
- `id` (string, required) – stable edge id within the template.
- `from` (object, required) – `{ role, port }`; `role` must reference an
  existing `template.bees[].role`.
- `to` (object, required) – `{ role, port }`; `role` must reference an existing
  `template.bees[].role`.
- `selector` (object, optional) – edge selection hint for multi‑IO policies,
  e.g. `{ policy: predicate, expr: "payload.priority >= 50" }`.

Multi‑IO:
- Model multiple inputs/outputs as multiple `ports` and multiple `edges`.
- Do not infer edges from `work`; the topology is the only graph.

## Worker configuration shape

Inside `config` we follow a common convention:

```yaml
config:
  inputs:
    type: SCHEDULER            # or REDIS_DATASET, RABBITMQ, etc.
    scheduler:
      ratePerSec: 50
  outputs:
    type: RABBITMQ             # or REDIS / NOOP, etc.
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
  - `type` – output type enum (e.g. `RABBITMQ`, `REDIS`, `NOOP`).
  - `<typeKey>` – type‑specific config.
- Role-specific worker fields live directly under `config`.
  - These keys are documented in the worker SDK and capability manifests.

The **capabilities** files under `scenario-manager-service/capabilities/` are
the authoritative list of user-tunable fields per worker and IO type.
Capability lookup uses the canonical image name without registry, namespace,
tag, or digest, so `processor:0.15`, `processor:latest`, and
`registry.example.lan:5000/pockethive/processor:dev-*` all resolve the
`processor` manifest. Capability manifest `image.tag` is optional compatibility
metadata, not a deployment tag; runtime image tags come from the scenario or
deployment environment. Each entry maps a UI field to a path in `config`.

### IO configuration examples

**Scheduler generator (ticks only):**

```yaml
config:
  inputs:
    type: SCHEDULER
    scheduler:
      ratePerSec: 50
      maxMessages: 0        # 0 = infinite, >0 = finite run; Java long technical range
  outputs:
    type: RABBITMQ
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
  message:
    bodyType: HTTP
    path: /api/demo
    method: POST
    body: '{{ payload }}'
    headers:
      content-type: application/json
```

**Redis dataset consumer (multi-list):**

```yaml
config:
  inputs:
    type: REDIS_DATASET
    redis:
      host: redis
      port: 6379
      sources:
        - listName: webauth.TOP.custA
          weight: 1
        - listName: webauth.RED.custA
          weight: 3
        - listName: webauth.RED.custB
          weight: 2
      pickStrategy: WEIGHTED_RANDOM   # ROUND_ROBIN | WEIGHTED_RANDOM
      ratePerSec: 20
```

**Redis output routing:**

```yaml
config:
  outputs:
    type: REDIS
    redis:
      host: redis
      port: 6379
      sourceStep: FIRST
      pushDirection: RPUSH
      routes:
        - header: x-ph-redis-list
          headerMatch: '^webauth\\.TOP\\.custA$'
          list: webauth.RED.custA
      defaultList: ph:dataset:other
```

## Docker volumes

To mount host directories into bee containers from a scenario, use
`config.docker.volumes`:

```yaml
config:
  docker:
    volumes:
      - /opt/pockethive/scenarios-runtime/<swarmId>:/app/scenario:ro
  # normal worker config...
```

- `volumes` is a list of Docker bind specs:
  - `hostPath:containerPath[:mode]` where `mode` is `ro` or `rw`.
- Swarm Controller passes these to the underlying Docker client when
  provisioning containers.
- The preferred model is to mount the per‑swarm runtime bundle at
  `/app/scenario` so workers can resolve templates/datasets from a single root.

## Traffic policy (buffer guard)

The optional `trafficPolicy` section is used by Swarm Controller guards,
especially the buffer guard. Its schema is documented in
`docs/architecture/traffic-shaping.md` and validated by the guard engine.

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
  --scenario scenarios/e2e/templated-rest/scenario.yaml

# Check generator + Request Builder templates referenced from a scenario
tools/scenario-templating-check/run.sh \
  --check-http-templates \
  --scenario scenarios/e2e/redis-dataset-demo/scenario.yaml
```

The tool will:

- Parse the scenario and build a template context with a sample WorkItem.
- Render generator templates via Pebble+SpEL and report errors.
- For Request Builder:
  - Load HTTP templates from the configured root.
  - Check that every `x-ph-call-id` used in the scenario has a matching
    template.
  - Render each template once with a dummy WorkItem to catch errors.

Scenario contract validators must not treat runtime identity as an authoring
validation rule. They must validate scenario authoring identity through unique
`template.bees[].role` values and topology endpoint `role` references. Runtime
`instance` values are owned by Swarm Controller materialisation.

## System Under Test (SUT) environments

Scenarios themselves do not embed full environment details, but swarms
may be bound to a **System Under Test (SUT)** chosen at create time.
The contract for SUT environments lives in
`common/swarm-model/src/main/java/io/pockethive/swarm/model/{SutEnvironment,SutEndpoint}.java`
and is represented on disk as YAML under
`scenario-manager-service/sut/sut-environments*.yaml`.

### SUT environment YAML

```yaml
# scenario-manager-service/sut-environments.yaml

- id: wiremock-local
  name: WireMock (local)
  type: sandbox
  endpoints:
    default:
      kind: HTTP
      baseUrl: http://wiremock:8080

- id: demo-http-sut
  name: Demo HTTP SUT
  type: dev
  endpoints:
    public-api:
      kind: HTTP
      baseUrl: https://demo.example.com/public
```

Shape:

- Root – list of environments.
- Environment (`SutEnvironment`):
  - `id` (string, required) – stable identifier; referenced as `sutId`
    when creating swarms.
  - `name` (string, required) – human‑readable name.
  - `type` (string, optional) – free‑text classification such as
    `sandbox`, `dev`, `uat`, `prodlike`.
  - `endpoints` (object, required) – map from endpoint id to endpoint
    details.
- Endpoint (`SutEndpoint`) inside `endpoints`:
  - map key – the only endpoint id (e.g. `default`, `public-api`); nested
    endpoint `id` fields are invalid.
  - `kind` (non-blank string, required) – short protocol label such as
    `HTTP`, `HTTPS`, `TCP`, or `TCPS`.
  - `baseUrl` (non-blank string, required) – URI or protocol-specific
    authority for this endpoint, e.g. `http://wiremock:8080`,
    `https://demo.example.com/public`, or `tcp-mock-server:9090`.
  - `upstreamBaseUrl` (non-blank string, optional) – upstream URI or
    authority used by proxied network bindings.

### Using SUTs from scenarios

When creating a swarm, the UI (or API client) may supply a `sutId`
alongside the `templateId`. Orchestrator will:

- fetch the `SutEnvironment` for that id from Scenario Manager; and
- apply SUT‑aware config templating for workers that opt in.

The recommended pattern in worker config is:

```yaml
config:
  # For HTTP workers (e.g. processor)
  baseUrl: "{{ sut.endpoints['default'].baseUrl }}/api"
```

At **create** time the Orchestrator resolves this expression using the
chosen SUT environment and writes a concrete `baseUrl` into the
`SwarmPlan`. Workers themselves only see the final URL, not the SUT
template.

> Rule: if you use `sut.endpoints[...]` in config, you must provide a
> `sutId` when creating the swarm. Missing SUTs or unknown endpoint ids
> are treated as hard errors (no fallback).

### Auth profiles and SUT context

Bundles may define `authProfiles.yaml` and activate profiles from request
templates with `authRef.profileId`. Auth profile string fields may reference
the selected SUT environment directly:

```yaml
profiles:
  webauth-oauth:
    type: OAUTH2_CLIENT_CREDENTIALS
    storage:
      mode: REDIS
      tokenKey: webauth.oauth
    tokenUrl: "{{ sut.endpoints['default'].baseUrl }}/oauth/token"
    clientId: webauth-client
```

At swarm creation time, when a `sutId` is supplied, Orchestrator injects the
selected SUT into the reserved private worker config path
`privateConfig.authProfile.sut` for auth-capable workers (`request-builder`,
`http-sequence`, and `processor`). Worker status, config-update preview, and
config-update evidence must not expose `privateConfig`. Auth runtime template
rendering exposes that private map as `sut`, alongside `vars`, `swarm`, and
`worker`.

The `sut` map contains only non-secret SUT metadata:

```yaml
privateConfig:
  authProfile:
    sut:
      id: wiremock-local
      name: wiremock-local
      type: sandbox
      endpoints:
        default:
          kind: HTTP
          baseUrl: http://wiremock:8080
          upstreamBaseUrl: http://backend:8080
```

> Rule: if an auth profile references `sut.*`, create the swarm with an
> explicit `sutId`. If no SUT context is available, auth profile rendering must
> fail explicitly rather than silently substituting another endpoint.

## Backwards compatibility

- Scenario Manager rejects non-canonical contract fields during bundle
  validation. In particular, nested `endpoints.<key>.id` fields are invalid;
  the map key is the only SUT endpoint identifier.
- The **NFF** rule applies: avoid multiple keys for the same concept and
  do not rely on fallback chains. Use the config structure described
  here and in capability manifests.

### Optional authoring helpers

Scenarios may include **authoring‑time‑only** metadata to help the Hive
UI render richer editors without changing worker behaviour.

Example for generator HTTP bodies:

```yaml
config:
  message:
    bodyType: HTTP
    schemaRef: "schemas/local-rest-body.schema.json#/body"
    body: |
      {
        "event": "local-rest-schema-demo",
        "message": "Hello from {{ swarmId }}",
        "correlationId": "{{ correlationId }}"
      }
```

- The same `schemaRef` hint is also supported in **Request Builder templates** (for example next to `bodyTemplate`).

- `schemaRef` is treated as an opaque string by Scenario Manager and
  workers; it is only used by the UI to locate a schema file inside the
  scenario bundle (for example under `schemas/`) and render a form for
  the body / bodyTemplate.
- The referenced schema remains **advisory** – workers only see and
  use the templated `body` / `bodyTemplate` strings when generating HTTP payloads.
