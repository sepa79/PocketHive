# Scenario Bundle Authoring Playbook

Step-by-step guide for AI contributors creating PocketHive scenario bundles.

## Task: Create a new scenario bundle

### Step 1: Gather requirements

Before writing any YAML, confirm:

1. **Target system** — What API/service is being tested? (HTTP REST, SOAP/XML, TCP, ISO-8583, etc.)
2. **Data source** — Where does test data come from? (CSV file, Redis list, scheduler)
3. **Pipeline stages** — Which workers are needed? (generator → [moderator →] [request-builder →] processor → postprocessor)
4. **SUT endpoints** — What base URLs are needed? (define as SUT endpoints, never hard-code)
5. **Rate/volume** — What throughput is expected? (ratePerSec, maxMessages, threadCount)
6. **Template complexity** — Does the request need Pebble/SpEL templating?

### Step 2: Create bundle directory

```
bundles/<scenario-id>/
  scenario.yaml
  templates/<serviceId>/<callId>.yaml   # if using request-builder
  datasets/<data-file>.csv              # if using CSV input
  sut/<sut-id>/sut.yaml                 # SUT endpoint definitions
  variables.yaml                        # if parameterized
  README.md
```

The `scenario.yaml` `id` field MUST match the folder name exactly.

### Step 3: Write scenario.yaml

> **CRITICAL — work section syntax**: `in` and `out` are always `map<string,string>`.
> The key is the port id (`in` or `out`), the value is the queue suffix.
> Never use the short string form (`out: proc`) — it is rejected at runtime.

```yaml
# CORRECT
work:
  out:
    out: build      # port id → queue suffix

# WRONG — will fail at runtime
work:
  out: build
```

### Bee fields quick reference

| Field | Required | Notes |
|---|---|---|
| `role` | yes | Logical role: `generator`, `processor`, `moderator`, `request-builder`, `postprocessor`, `http-sequence`, `clearing-export` |
| `image` | yes | e.g. `generator:latest` |
| `work` | yes | Map form. Use `{}` for no IO. |
| `config` | no | Worker config fanned out via control plane |
| `id` | no | Stable identifier. Required when `topology` is declared. Used as `instanceId` in `plan.bees` when multiple bees share a role. |
| `env` | no | Raw env vars injected into the container. Use for secrets or JVM flags not covered by `config`. |
| `ports` | no | Logical IO ports for `topology` edges (UI only, no runtime effect). |

### config.outputs (explicit output routing)

By default all workers output to RabbitMQ. Override with `config.outputs`:

```yaml
# Route to Redis instead of RabbitMQ
config:
  outputs:
    type: REDIS
    redis:
      host: redis
      port: 6379
      sourceStep: FIRST     # FIRST = generator payload, LAST = processor response
      pushDirection: RPUSH
      routes:
        - header: x-ph-redis-list
          headerMatch: '^ph:dataset:custA$'
          list: ph:dataset:custA-out
      defaultList: ph:dataset:fallback

# Discard output (terminal worker with side-effects only)
config:
  outputs:
    type: NOOP
```

`outputs.type` options: `RABBITMQ` (default), `REDIS`, `NOOP`.

#### Minimal HTTP pipeline (scheduler → processor → postprocessor)

```yaml
id: my-scenario
name: My Scenario
description: Brief description
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 10
            maxMessages: 0        # 0 = infinite
        worker:
          message:
            bodyType: HTTP
            path: /api/test
            method: POST
            body: '{"key": "value"}'
            headers:
              content-type: application/json
      work:
        out:
          out: proc               # source bee — no 'in'

    - role: processor
      image: processor:latest
      config:
        baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
      work:
        in:
          in: proc
        out:
          out: post

    - role: postprocessor
      image: postprocessor:latest
      config:
        worker:
          postprocessor:
            publish-all-metrics: true
      work:
        in:
          in: post                # terminal bee — no 'out'
```

#### With request-builder (CSV → request-builder → processor → postprocessor)

```yaml
id: my-csv-scenario
name: My CSV Scenario
description: CSV-driven with request templates
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      config:
        inputs:
          type: CSV_DATASET
          csv:
            filePath: /app/scenario/datasets/testdata.csv
            ratePerSec: 5
            rotate: true
            skipHeader: true
        worker:
          message:
            bodyType: SIMPLE
            body: |
              {
                "field1": "{{ payloadAsJson.col1 }}",
                "field2": "{{ payloadAsJson.col2 }}"
              }
            headers:
              x-ph-call-id: my-call
              x-ph-service-id: my-service
      work:
        out:
          out: build

    - role: request-builder
      image: request-builder:latest
      config:
        worker:
          templateRoot: /app/scenario/templates
          serviceId: my-service
      work:
        in:
          in: build
        out:
          out: proc

    - role: processor
      image: processor:latest
      config:
        baseUrl: "{{ sut.endpoints['my-service'].baseUrl }}"
      work:
        in:
          in: proc
        out:
          out: post

    - role: postprocessor
      image: postprocessor:latest
      config:
        worker:
          postprocessor:
            publish-all-metrics: true
      work:
        in:
          in: post
```

### Step 4: Write request templates (if using request-builder)

Place under `templates/<serviceId>/<callId>.yaml`. The `callId` must match the `x-ph-call-id` header set by the generator.

**HTTP template:**
```yaml
serviceId: my-service
callId: my-call
protocol: HTTP          # required — always include
method: POST
pathTemplate: "/api/v1/resource"
bodyTemplate: |
  {
    "id": "{{ eval('#uuid()') }}",
    "data": "{{ payloadAsJson.field1 }}",
    "timestamp": "{{ eval(\"#date_format(now, 'yyyy-MM-dd''T''HH:mm:ss')\") }}"
  }
headersTemplate:
  Content-Type: application/json
```

**TCP template:**
```yaml
serviceId: my-service
callId: my-tcp-call
protocol: TCP           # required — always include
behavior: REQUEST_RESPONSE
transport: socket
endTag: "</Response>"
bodyTemplate: |
  <Request>{{ payloadAsJson.data }}</Request>
headersTemplate:
  Content-Type: application/xml
```

**ISO-8583 template** (via request-builder with FIELD_LIST_XML adapter):
```yaml
serviceId: my-service
callId: my-iso-call
protocol: ISO8583
wireProfileId: MC_2BYTE_LEN_BIN_BITMAP   # 2-byte length-prefixed binary
payloadAdapter: FIELD_LIST_XML
bodyTemplate: |
  <iso8583 mti="0100">
    <field num="2" value="{{ payloadAsJson.pan }}"/>
    <field num="4" value="{{ payloadAsJson.amount }}"/>
  </iso8583>
headersTemplate:
  x-flow: my-flow
```

> **ISO-8583 + TCP mock**: The TCP mock server uses newline delimiters by default.
> ISO-8583 uses binary length-prefix framing — the mock cannot respond correctly to
> ISO-8583 wire-format messages. Use a real ISO-8583 simulator or a custom TCP mock
> mapping that speaks the same wire profile. See `docs/ai/MOCK_SERVER_GUIDE.md`.

### Step 5: Create SUT definition

`sut/<sut-id>/sut.yaml`:

```yaml
id: local-wiremock
name: Local WireMock
type: sandbox
endpoints:
  my-service:
    kind: HTTP
    baseUrl: http://wiremock:8080
```

> **`sut.endpoints[...]` resolution scope**: The Orchestrator resolves
> `{{ sut.endpoints['id'].baseUrl }}` expressions **only** in `config.baseUrl`
> on the processor bee. It does NOT resolve them in nested config fields like
> `config.worker.auth[].tokenUrl`. Use hardcoded URLs or `vars.*` for those.

### Step 5b: Add a plan (optional — for automated ramp/stop)

Add a `plan` section alongside `template` to automate rate changes and lifecycle
events on a time-based schedule. See `docs/ai/SCENARIO_PLAN_GUIDE.md` for full details.

```yaml
plan:
  bees:
    - instanceId: generator      # matches the bee's role name
      steps:
        - stepId: ramp-up
          name: Ramp to 50/s after 1 minute
          time: PT1M
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 50

  swarm:
    - stepId: auto-stop
      name: Stop after 5 minutes
      time: PT5M
      type: stop
```

Step types: `config-update` (default), `start`, `stop`.
Time format: ISO-8601 duration (`PT15S`, `PT1M`, `PT1H30M`).

### Step 6: Add variables (if parameterized)

`variables.yaml` supports two scopes:
- `global` — same value for all SUTs
- `sut` — value differs per SUT environment (resolved by the `sutId` passed at swarm creation)

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
      uat:
        authUrl: "https://auth.uat.example.com/token"
    high-load:
      local-mock:
        authUrl: "http://wiremock:8080/auth/token"
      uat:
        authUrl: "https://auth.uat.example.com/token"
```

Reference in scenario.yaml or templates: `{{ vars.ratePerSec }}`, `{{ vars.authUrl }}`.

Pass at swarm creation: `swarm.create { variablesProfileId: "default", sutId: "local-mock" }`.

### Step 7: Validate (MCP tools — always use these)

```
# 1. Verify paths are OK
paths.check

# 2. Start validation (returns jobId immediately)
bundle.validate { bundle: "my-scenario" }

# 3. Poll every 10-15s until status=done (takes 20-60s)
bundle.validate.result { jobId: "<jobId>" }
```

**Known validator false positives** — treat these as PASS:
- `#sequenceWith` / `#sequence` — tries to connect to Redis at validation time.
  The validator runs outside Docker and cannot reach `redis:6379`. The template
  is correct; ignore this error and proceed.
- `protocol` field rejected — means the validator jar is stale. Rebuild it per
  the README. Always include `protocol` in templates regardless.

Never invoke `run.sh` or `validate.sh` directly.

### Step 8: Write README.md

Document:
- What the scenario tests
- Required SUT endpoints and their expected APIs
- Dataset format (if CSV)
- How to run (which `sutId`, `variablesProfileId` to use)
- Expected results/metrics to monitor
- Cleanup steps

### Step 9: Deploy and test

```
scenario.deploy { bundle: "my-scenario" }
scenario.get { scenarioId: "my-scenario" }   # verify loaded

swarm.create { swarmId: "my-test", templateId: "my-scenario", sutId: "local-wiremock" }

# Poll until ready — do NOT use swarm.wait-ready for long waits (MCP timeout risk)
# Instead poll swarm.get every 5s until totals.healthy == totals.desired
swarm.get { swarmId: "my-test" }   # check context.totals.healthy == context.totals.desired

swarm.start { swarmId: "my-test" }
```

## Checklist

- [ ] `scenario.yaml` `id` matches folder name
- [ ] All `work:` sections use map form (`out: { out: queue-name }`)
- [ ] Queue names chain correctly (`out` value → `in` value of next bee)
- [ ] Every `x-ph-call-id` has a matching template file
- [ ] `serviceId` in templates matches generator headers and request-builder config
- [ ] Every template includes `protocol: HTTP|TCP|ISO8583`
- [ ] SUT references (`sut.endpoints[...]`) only used in `config.baseUrl` on processor
- [ ] CSV `filePath` uses `/app/scenario/datasets/` prefix
- [ ] Template `templateRoot` uses `/app/scenario/templates`
- [ ] No hard-coded hostnames or credentials
- [ ] If `plan` is used: all `stepId` values are unique, `instanceId` matches a bee role, `time` is ISO-8601 duration
- [ ] README.md documents the bundle
