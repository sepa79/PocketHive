# Data and State Patterns

Patterns for managing test data, state, and coordination across pipeline phases.

## CSV Datasets

Place CSV files under `datasets/`, reference as `/app/scenario/datasets/<file>.csv`.

```yaml
inputs:
  type: CSV_DATASET
  csv:
    filePath: /app/scenario/datasets/cards.csv
    ratePerSec: 5
    rotate: true        # true = replay infinitely; false = one-shot
    skipHeader: true    # skip first row if it contains column names
    startupDelaySeconds: 3  # wait before starting (useful for multi-phase)
```

Access columns in the generator body template using `payloadAsJson.<columnName>`:

```yaml
body: |
  {
    "pan": "{{ payloadAsJson.pan }}",
    "amount": "{{ payloadAsJson.amount }}"
  }
```

CSV column names come from the header row (when `skipHeader: true`).

---

## Redis Datasets

Use Redis lists for inter-phase coordination and closed-loop recycling.

### Single-source input

```yaml
inputs:
  type: REDIS_DATASET
  redis:
    host: redis
    port: 6379
    listName: "ph:dataset:my-list"
    ratePerSec: 10
```

### Multi-source weighted input

```yaml
inputs:
  type: REDIS_DATASET
  redis:
    host: redis
    port: 6379
    sources:
      - listName: "ph:dataset:high-value"
        weight: 3
      - listName: "ph:dataset:low-value"
        weight: 1
    pickStrategy: WEIGHTED_RANDOM
    ratePerSec: 20
```

> Never mix `listName` and `sources` — use exactly one.

### Pushing results to Redis (two approaches)

**Option A: `redisUploader` interceptor on postprocessor** (most common)

Use when you want to push WorkItem data to Redis after postprocessing:

```yaml
- role: postprocessor
  image: postprocessor:latest
  work:
    in:
      in: post
  config:
    worker:
      interceptors:
        redisUploader:
          enabled: true
          host: redis
          port: 6379
          phase: AFTER          # BEFORE or AFTER postprocessing
          sourceStep: FIRST     # FIRST = generator payload, LAST = processor response
          pushDirection: RPUSH
          routes:               # optional — first match wins
            - header: x-ph-redis-list
              headerMatch: '^ph:dataset:custA$'  # regex on header value
              list: ph:dataset:custA-out
            - match: '"status":"approved"'       # regex on payload body
              list: ph:dataset:approved
          targetListTemplate: "ph:dataset:{{ payloadAsJson.customerId }}"  # Pebble template, used if no route matches
          fallbackList: "ph:dataset:output-list"  # static fallback if template renders blank
```

Target list resolution order:
1. First matching `routes` entry (header regex OR payload body regex)
2. `targetListTemplate` rendered against the WorkItem
3. `fallbackList` / `defaultList`

**Option B: `outputs.type: REDIS` on any worker** (bypasses postprocessor)

Use when you want to route WorkItems directly to Redis from any worker,
skipping the postprocessor entirely:

```yaml
- role: processor
  image: processor:latest
  work:
    in:
      in: proc
    # no 'out' needed when outputs.type is REDIS or NOOP
  config:
    baseUrl: "{{ sut.endpoints['api'].baseUrl }}"
    outputs:
      type: REDIS
      redis:
        host: redis
        port: 6379
        sourceStep: FIRST
        pushDirection: RPUSH
        routes:
          - header: x-ph-redis-list
            headerMatch: '^ph:dataset:custA$'
            list: ph:dataset:custA-processed
        defaultList: ph:dataset:fallback
```

`outputs.type` options: `RABBITMQ` (default), `REDIS`, `NOOP`.

### Closed-loop recycling

Route processed records back to the input list so they are consumed again:

```yaml
interceptors:
  redisUploader:
    enabled: true
    host: redis
    port: 6379
    phase: AFTER
    sourceStep: FIRST
    pushDirection: RPUSH
    fallbackList: "ph:dataset:my-list"   # same list the generator reads from
```

---

## Multi-phase Coordination

### In-swarm sequencing with startupDelaySeconds

Use `startupDelaySeconds` to stagger phases within a single swarm:

```yaml
# Phase 1: setup (starts immediately)
- role: setupGenerator
  config:
    inputs:
      type: CSV_DATASET
      csv:
        rotate: false           # one-shot — stops after all rows
        startupDelaySeconds: 0

# Phase 2: load (starts after setup has had time to populate Redis)
- role: loadGenerator
  config:
    inputs:
      type: REDIS_DATASET
      redis:
        listName: "ph:dataset:setup-output"
        ratePerSec: 50
    worker:
      message:
        bodyType: SIMPLE
        body: "{{ payload }}"
        headers:
          x-ph-call-id: load-call
```

### Separate swarms for distinct phases

For strict phase separation, use two swarms:
1. Run setup swarm (`rotate: false`) until it stops naturally
2. Start load swarm that reads from the Redis list populated by phase 1

Redis LPOP provides atomic exactly-once consumption — the load swarm will block
naturally when the list is empty.

---

## SUT Configuration

Every external URL must use `{{ sut.endpoints['id'].baseUrl }}` with a matching
`sut/<sut-id>/sut.yaml`. This allows the same scenario to target different environments.

```yaml
# sut/local-mock/sut.yaml
id: local-mock
name: Local Mock
type: sandbox
endpoints:
  api:
    kind: HTTP
    baseUrl: http://wiremock:8080
  auth:
    kind: HTTP
    baseUrl: http://wiremock:8080

# sut/uat/sut.yaml
id: uat
name: UAT Environment
type: uat
endpoints:
  api:
    kind: HTTP
    baseUrl: https://api.uat.example.com
  auth:
    kind: HTTP
    baseUrl: https://auth.uat.example.com
```

> **Scope limitation**: `sut.endpoints[...]` resolves only in `config.baseUrl` on the
> processor bee. Use hardcoded URLs or `vars.*` for auth `tokenUrl` and other nested fields.

---

## Variables

Use `variables.yaml` for values that differ between environments or load profiles.
Variables have two scopes:

- `global` — same value regardless of which SUT is selected
- `sut` — value differs per SUT environment (e.g. different auth URLs per env)

```yaml
version: 1
definitions:
  - name: ratePerSec
    scope: global
    type: int
    required: true
  - name: pcsCallId
    scope: global
    type: string
    required: true
  - name: authUrl
    scope: sut
    type: string
    required: true
  - name: clientId
    scope: sut
    type: string
    required: true

profiles:
  - id: default
    name: Default
  - id: high-load
    name: High Load

values:
  global:
    default:
      ratePerSec: 10
      pcsCallId: "pcs-auth"
    high-load:
      ratePerSec: 100
      pcsCallId: "pcs-auth"
  sut:
    default:
      local-mock:
        authUrl: "http://wiremock:8080/auth/token"
        clientId: "test-client"
      uat:
        authUrl: "https://auth.uat.example.com/token"
        clientId: "uat-client"
    high-load:
      local-mock:
        authUrl: "http://wiremock:8080/auth/token"
        clientId: "test-client"
      uat:
        authUrl: "https://auth.uat.example.com/token"
        clientId: "uat-client"
```

Reference in scenario.yaml or templates: `{{ vars.ratePerSec }}`, `{{ vars.authUrl }}`.

Pass at swarm creation: `swarm.create { variablesProfileId: "default", sutId: "local-mock" }`.

The runtime resolves `sut` scope variables by matching the `sutId` passed at swarm creation.
If a variable is `scope: sut` but no value is defined for the active SUT, the swarm will
fail to create with a missing variable error.

---

## JSON Schemas (schemaRef)

`schemaRef` in generator or template config is **UI-only** — workers ignore it at runtime.
Place JSON Schema files under `schemas/` for documentation and UI tooling only.

---

## Template Design Patterns

### Conditional fields (Pebble if)

```yaml
body: |
  {
    "pan": "{{ payloadAsJson.pan }}"
    {% if payloadAsJson.storeId %},
    "storeId": "{{ payloadAsJson.storeId }}"
    {% endif %}
  }
```

### Dynamic IDs

```yaml
body: |
  {
    "txId": "{{ eval('#uuid()') }}",
    "seq": "{{ eval('#sequenceWith(\"my-seq\", \"numeric\", \"%06d\", 1, 999999)') }}"
  }
```

> `#sequenceWith` requires Redis at runtime. The offline validator will fail with a
> Redis connection error — this is a known false positive. See `SCENARIO_BUNDLE_PLAYBOOK.md`.

### Date formatting

```yaml
"timestamp": "{{ eval(\"#date_format(now, 'yyyy-MM-dd''T''HH:mm:ss')\") }}"
```

### Auth token injection

```yaml
"Authorization": "Bearer {{ eval('#authToken(\"myapi:auth\")') }}"
```
