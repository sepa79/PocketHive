# Scenario Patterns & Examples

This guide provides concrete scenarios you can copy and adapt.
Each example links back to the contract in `SCENARIO_CONTRACT.md`.

## 1. Simple local REST swarm

Authoritative example: `scenario-manager-service/scenarios/e2e/local-rest.yaml`

Highlights:

- Minimal HTTP pipeline: **generator → moderator → processor → postprocessor**.
- Scheduler‑driven generator with `inputs.scheduler.ratePerSec: 50`.
- Processor pointing at WireMock (`worker.baseUrl`), postprocessor publishing metrics.
- Custom queue suffixes via `work.in` / `work.out` (see the scenario file for exact values).

## 2. Redis dataset demo

Authoritative example: `scenario-manager-service/scenarios/e2e/redis-dataset-demo.yaml`

Pattern:

- One **seeder** generator:
  - Input: `SCHEDULER` – ticks at a higher rate (e.g. 5 msg/s).
  - Generates simple JSON records with templated fields
    (`customerCode`, `accountNumber`, `cardNumber`, `nonce`).
  - Postprocessor with Redis uploader interceptor routes records into
    Redis lists based on `customerCode`.
- Two **consumer** generators (acting as data providers):
  - Input: `REDIS_DATASET` – one list per customer (`ph:dataset:custa`,
    `ph:dataset:custb`).
  - Output: `RABBITMQ` – sends HTTP envelopes built by HTTP Builder.
  - HTTP Builder selects callIds and uses on‑disk HTTP templates.

Use this scenario as a reference when you need:

- To split a logical dataset into multiple Redis lists (per customer,
  per segment, etc.).
- Multiple consumers with different Redis sources but the same downstream
  HTTP pipeline.

## 3. Guarded swarm (buffer guard)

Source: `scenario-manager-service/scenarios/e2e/local-rest-two-moderators.yaml`
and related examples.

Pattern:

- Generator → Moderator A → Moderator B → Processor → Postprocessor.
- A buffer guard watches the queue after Moderator A to keep depth within
  a desired band by adjusting Moderator A’s rate.

Traffic policy snippet (simplified):

```yaml
trafficPolicy:
  bufferGuard:
    enabled: true
    queues:
      - queueAlias: moderator-a-out
        targetDepth: 100
        minDepth: 50
        maxDepth: 150
        samplePeriod: PT5S
        movingAverageWindow: 5
        adjust:
          minRatePerSec: 1
          maxRatePerSec: 200
          maxIncreasePct: 20
          maxDecreasePct: 20
```

Use this pattern when you want Swarm Controller to automatically keep
upstream producers in a safe operating window.

## 4. HTTP Builder with on‑disk templates

Primary example: `scenario-manager-service/scenarios/e2e/redis-dataset-demo.yaml`
combined with the HTTP templates under
`http-builder-service/http-templates/default`.

Pattern:

- Generators or Redis‑based providers emit JSON payloads and set
  `x-ph-service-id` / `x-ph-call-id` headers.
- HTTP Builder worker transforms these into HTTP envelopes using
  templates stored on disk.
- For externalising templates (e.g. in `/opt/pockethive/http-templates`),
  mount them via `config.docker.volumes` and point `templateRoot` at the
  container path:

  ```yaml
  config:
    docker:
      volumes:
        - /opt/pockethive/http-templates:/app/http-templates:ro
    worker:
      serviceId: default
      templateRoot: /app/http-templates
      passThroughOnMissingTemplate: false
  ```

Templates themselves can use Pebble + SpEL (see `SCENARIO_TEMPLATING.md`)
to pull fields from payload and headers.
