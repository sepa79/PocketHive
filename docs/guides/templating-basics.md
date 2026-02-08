# Templating Guide: Basics

PocketHive uses **Pebble** templates with a constrained **SpEL**
(`eval`) helper for dynamic payloads, headers and request envelopes.

All templating is executed by `TemplateRenderer` / `PebbleTemplateRenderer`
in worker SDK runtime paths, so behavior is consistent across Generator,
interceptors, and `request-builder`.

## 1. Template context

When a template is rendered, the context contains at least:

- `payload` - current step payload. Parsed as Map when valid JSON,
  otherwise string.
- `headers` - map of global WorkItem headers (top-level).
- `workItem` - full `WorkItem` object (steps, step headers, payloads).
- `vars` - resolved Scenario Variables map (when the scenario bundle
  provides `variables.yaml` and the swarm is created with a
  `variablesProfileId`).

Generators and interceptors may add more fields, but these four are the
baseline contract.

Example:

```yaml
body: |
  {
    "raw": "{{ payload }}",
    "messageId": "{{ workItem.messageId }}"
  }
```

## 2. Scenario Variables (`vars.*`)

If a scenario bundle contains `variables.yaml` (see
`docs/scenarios/SCENARIO_VARIABLES.md`), Orchestrator resolves a flat
`vars` map at create-swarm time and injects it into each bee config
under `config.vars`.

Workers propagate `config.vars` into the WorkItem header `vars`, so
templates can reference:

- Pebble: `{{ vars.customerId }}`, `{{ vars.loopCount }}`
- SpEL through Pebble `eval(...)`: `{{ eval("vars.loopCount + 1") }}`

### Example bundle

See `scenarios/e2e/variables-demo/`:

- `scenario.yaml` uses `vars.*` in body + `eval(...)`
- `variables.yaml` defines two profiles (`default`, `france`) and a
  SUT-scoped `customerId`
- `sut/` defines `sut-A` and `sut-B` (bundle-local SSOT)

### How to run

Create the swarm with both:

- `sutId` (required when `sut`-scoped variables exist)
- `variablesProfileId` (required when any variables exist)

Via Orchestrator REST (`docs/ORCHESTRATOR-REST.md`):

```json
{
  "templateId": "variables-demo",
  "idempotencyKey": "uuid-v4",
  "sutId": "sut-A",
  "variablesProfileId": "france"
}
```

If you run the local stack via `build-hive.sh`, you can also create it
via the debug CLI:

```bash
node tools/mcp-orchestrator-debug/client.mjs create-swarm <swarmId> variables-demo --sutId sut-A --variablesProfileId france
```

## 3. SpEL helper: `eval(...)`

For logic that is too complex for plain Pebble, use constrained
Spring Expression Language:

```yaml
headers:
  x-ph-call-id: "{{ eval(\"#randInt(0,99) < 40 ? 'redis-balance' : 'redis-topup'\") }}"
```

The `eval` helper is backed by `SpelTemplateEvaluator` and runs in a
restricted `SimpleEvaluationContext`.

### Root variables

- `payload` - raw payload string.
- `headers` - map of global WorkItem headers (top-level).
- `workItem` - full `WorkItem` instance (steps, step headers,
  payloads).

### Available functions

The following SpEL functions are available (via `#name(...)`):

- `#randInt(min, max)` - random integer, inclusive.
- `#randLong(min, max)` - random long, inclusive (pass numbers as
  strings to avoid parser limits).
- `#uuid()` - random UUID string.
- `#md5_hex(value)` - MD5 hash in hex.
- `#sha256_hex(value)` - SHA-256 hash in hex.
- `#base64_encode(value)` / `#base64_decode(value)` - Base64 helpers.
- `#hmac_sha256_hex(key, value)` - HMAC-SHA256 in hex.
- `#regex_match(input, pattern)` - boolean.
- `#regex_extract(input, pattern, group)` - string (empty if no match).
- `#json_path(payload, path)` - JSONPath extractor; returns a string.
- `#date_format(instant, pattern)` - format `now` or provided Instant.
- `#sequence(key, mode, format)` - Redis-backed sequence generator.
- `#sequenceWith(key, mode, format, startOffset, maxSequence)` -
  sequence with explicit start/max.
- `#resetSequence(key)` - deletes the Redis counter; returns `true`
  when removed.

Example - weighted call selection using plain SpEL:

```yaml
headers:
  x-ph-call-id: |
    {{ eval("#randInt(0,99) < 40 ? 'redis-balance' : (#randInt(0,99) < 80 ? 'redis-topup' : 'redis-auth')") }}
```

## 4. JSON field access

When `payload` is valid JSON, it is parsed as a map. Use direct
property access:

```yaml
body: |
  {
    "customerId": "{{ payload.customerId }}",
    "nested": "{{ payload.customer.code }}"
  }
```

For complex selectors or raw-string payload usage, use `#json_path`
with JSON Pointer syntax (RFC 6901):

```yaml
body: |
  {
    "customerId": "{{ eval(\"#json_path(workItem.payload(), '/customerId')\") }}"
  }
```

Common JSON Pointer patterns:

- Root object field: `/customerId`
- Nested field: `/customer/code`
- Array element: `/items/0/id`

Note: `#json_path` expects a JSON string as first argument, so use
`workItem.payload()` instead of `payload`.

## 5. Safety baseline

- Keep templates declarative and compact.
- Keep `eval(...)` expressions small and testable.
- Use `vars.*` for environment/profile differences instead of duplicating
  templates.
- Validate templates before long e2e/perf runs.
