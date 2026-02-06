# Scenario Patterns & Examples

This guide provides concrete scenarios you can copy and adapt.
Each example links back to the contract in `SCENARIO_CONTRACT.md`.

## 1. Simple local REST swarm

Authoritative example: `scenarios/**/local-rest/scenario.yaml` (in this repo: `scenarios/e2e/local-rest/scenario.yaml`)

Highlights:

- Minimal HTTP pipeline: **generator → moderator → processor → postprocessor**.
- Scheduler‑driven generator with `inputs.scheduler.ratePerSec: 50`.
- Processor pointing at WireMock (`worker.baseUrl`), postprocessor publishing metrics.
- Custom queue suffixes via `work.in` / `work.out` port maps (see the scenario file for exact values).

## 2. Redis dataset demo

Authoritative example: `scenarios/**/redis-dataset-demo/scenario.yaml` (in this repo: `scenarios/e2e/redis-dataset-demo/scenario.yaml`)

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

Source: `scenarios/**/local-rest-two-moderators/scenario.yaml`
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

Primary example: `scenarios/**/redis-dataset-demo/scenario.yaml` (in this repo: `scenarios/e2e/redis-dataset-demo/scenario.yaml`)
combined with the HTTP templates under
`scenarios/**/redis-dataset-demo/templates/http/default`.

Pattern:

- Generators or Redis‑based providers emit JSON payloads and set
  `x-ph-service-id` / `x-ph-call-id` headers.
- HTTP Builder worker transforms these into HTTP envelopes using
  templates stored inside the scenario bundle under
  `/app/scenario/templates/http`.
- Scenario Manager materialises the bundle into a per-swarm runtime
  directory and Swarm Controller mounts it into all bees, so you only
  need to point `templateRoot` at the scenario path:

  ```yaml
  config:
    worker:
      serviceId: default
      templateRoot: /app/scenario/templates/http
      passThroughOnMissingTemplate: false
  ```

Templates themselves can use Pebble + SpEL (see `SCENARIO_TEMPLATING.md`)
to pull fields from payload and headers.
