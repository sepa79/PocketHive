# JMeter Conversion Guide

How to convert a JMeter `.jmx` test plan to a PocketHive scenario bundle.

## Concept mapping

| JMeter concept | PocketHive equivalent |
|---|---|
| Thread Group | generator + pipeline bees |
| HTTP Request Sampler | request-builder template |
| CSV Data Set Config | generator with `type: CSV_DATASET` |
| Constant Timer / Throughput Shaping | `ratePerSec` on generator or moderator |
| Ramp-up period | moderator with `type: sine` or `startupDelaySeconds` |
| Sequential Thread Groups | `startupDelaySeconds` or separate swarms |
| Parallel Thread Groups | multiple pipelines in one swarm |
| Response Assertion | `resultRules` in template |
| Regular Expression Extractor | multi-step WorkItem or Redis handoff |
| BeanShell / JSR223 | SpEL via `eval(...)` in Pebble templates |

---

## Thread Group → generator

```xml
<!-- JMeter -->
<ThreadGroup>
  <numThreads>10</numThreads>
  <loops>100</loops>
  <rampUp>30</rampUp>
</ThreadGroup>
```

```yaml
# PocketHive
- role: generator
  image: generator:latest
  config:
    inputs:
      type: SCHEDULER
      scheduler:
        ratePerSec: 10          # numThreads / rampUp ≈ initial rate
        maxMessages: 1000       # numThreads * loops
```

For ramp-up, add a moderator with sine wave:

```yaml
- role: moderator
  image: moderator:latest
  config:
    worker:
      mode:
        type: sine
        sine:
          minRatePerSec: 1
          maxRatePerSec: 50
          periodSeconds: 60     # rampUp * 2
  work:
    in:
      in: moderate
    out:
      out: proc
```

---

## HTTP Request Sampler → request-builder template

```xml
<!-- JMeter -->
<HTTPSamplerProxy>
  <stringProp name="HTTPSampler.domain">api.example.com</stringProp>
  <stringProp name="HTTPSampler.path">/api/v1/users/${userId}</stringProp>
  <stringProp name="HTTPSampler.method">POST</stringProp>
  <stringProp name="HTTPSampler.postBodyRaw">{"name": "${name}"}</stringProp>
</HTTPSamplerProxy>
```

```yaml
# PocketHive template: templates/myapi/create-user.yaml
serviceId: myapi
callId: create-user
protocol: HTTP
method: POST
pathTemplate: "/api/v1/users/{{ payloadAsJson.userId }}"
bodyTemplate: |
  {"name": "{{ payloadAsJson.name }}"}
headersTemplate:
  Content-Type: application/json
```

Generator header: `x-ph-call-id: create-user`

---

## CSV Data Set Config → CSV_DATASET generator

```xml
<!-- JMeter -->
<CSVDataSet>
  <stringProp name="filename">users.csv</stringProp>
  <stringProp name="variableNames">userId,name,email</stringProp>
  <boolProp name="recycle">true</boolProp>
  <boolProp name="ignoreFirstLine">true</boolProp>
</CSVDataSet>
```

```yaml
# PocketHive
inputs:
  type: CSV_DATASET
  csv:
    filePath: /app/scenario/datasets/users.csv
    rotate: true          # recycle=true
    skipHeader: true      # ignoreFirstLine=true
    ratePerSec: 10
```

Access columns: `{{ payloadAsJson.userId }}`, `{{ payloadAsJson.name }}`, etc.

---

## JMeter functions → SpEL

| JMeter | PocketHive SpEL |
|---|---|
| `${__UUID()}` | `{{ eval('#uuid()') }}` |
| `${__Random(1,100)}` | `{{ eval('#randInt(1, 100)') }}` |
| `${__time(yyyy-MM-dd)}` | `{{ eval("#date_format(now, 'yyyy-MM-dd')") }}` |
| `${__MD5(${var})}` | `{{ eval('#md5_hex(payload)') }}` |
| `${__base64Encode(${var})}` | `{{ eval('#base64_encode(payload)') }}` |
| `${__counter(TRUE)}` | `{{ eval('#sequence(\"my-counter\")') }}` |

---

## Response Assertion → resultRules

```xml
<!-- JMeter -->
<ResponseAssertion>
  <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
  <stringProp name="Assertion.test_type">8</stringProp>
  <stringProp name="Assertion.custom_message">Expected 200</stringProp>
</ResponseAssertion>
```

```yaml
# PocketHive template resultRules
resultRules:
  businessCode:
    source: RESPONSE_BODY
    pattern: '"result_code"\s*:\s*"([^"]+)"'
  successRegex: '^TRS0001$'
  dimensions:
    - name: operation
      source: REQUEST_BODY
      pattern: '"operation"\s*:\s*"([^"]+)"'
```

HTTP status codes are automatically tracked via `x-ph-processor-status` header.

---

## Timers → ratePerSec / moderator

| JMeter timer | PocketHive equivalent |
|---|---|
| Constant Timer (500ms) | `ratePerSec: 2` on generator (1000/500) |
| Uniform Random Timer | moderator `type: rate-per-sec` with varying rate |
| Throughput Shaping Timer | moderator `type: sine` or buffer guard |
| Synchronizing Timer | not directly supported — use `threadCount` cap on processor |

---

## Sequential Thread Groups → startupDelaySeconds

```xml
<!-- JMeter: setUp Thread Group then main Thread Group -->
```

```yaml
# PocketHive: stagger with startupDelaySeconds
- role: setupGenerator
  config:
    inputs:
      type: CSV_DATASET
      csv:
        rotate: false           # one-shot
        startupDelaySeconds: 0

- role: mainGenerator
  config:
    inputs:
      type: REDIS_DATASET
      redis:
        listName: "ph:dataset:setup-output"
        ratePerSec: 50
```

Or use two separate swarms: run setup swarm first, then start load swarm.

---

## Parallel Thread Groups → multiple pipelines

```yaml
# Two independent pipelines in one swarm
bees:
  # Pipeline A
  - role: generatorA
    work:
      out:
        out: build-a
  - role: processorA
    work:
      in:
        in: build-a
      out:
        out: post-a
  - role: postprocessorA
    work:
      in:
        in: post-a

  # Pipeline B
  - role: generatorB
    work:
      out:
        out: build-b
  - role: processorB
    work:
      in:
        in: build-b
      out:
        out: post-b
  - role: postprocessorB
    work:
      in:
        in: post-b
```

---

## Extractors → multi-step or Redis handoff

JMeter Regular Expression Extractors that pass values between samplers map to:

1. **Within one WorkItem** — the processor appends the response as a step; subsequent
   request-builder templates can access it via `payloadAsJson` on the latest step.
2. **Across phases** — use `redisUploader` interceptor to push extracted values to Redis,
   then a second generator reads them.

There is no direct equivalent of JMeter's variable extraction within a single pipeline hop.
Design the scenario so each WorkItem carries all needed data from the generator payload.

---

## Checklist for JMeter migrations

- [ ] Each Thread Group → one generator + pipeline
- [ ] CSV files copied to `datasets/`, paths updated to `/app/scenario/datasets/`
- [ ] `${var}` replaced with `{{ payloadAsJson.var }}`
- [ ] JMeter functions replaced with SpEL equivalents
- [ ] Assertions converted to `resultRules` in templates
- [ ] Timers converted to `ratePerSec` / moderator
- [ ] SUT base URL extracted to `sut/<id>/sut.yaml`
- [ ] `work:` sections use map form throughout
- [ ] Bundle validated with `bundle.validate`
