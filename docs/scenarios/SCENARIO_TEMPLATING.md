# Scenario Templating & Helpers

PocketHive uses **Pebble** templates with a constrained **SpEL**
(`eval`) helper for dynamic payloads, headers and HTTP envelopes.
This document explains the available helpers and patterns.

All templating is executed by `TemplateRenderer` / `PebbleTemplateRenderer`
in the worker SDK, so behaviour is consistent across Generator,
TemplateInterceptor, HTTP Builder, and other components.

## Template context

When a template is rendered, the context contains at least:

- `payload` – current step payload. Parsed as Map when valid JSON, otherwise string.
- `headers` – map of current step headers.
- `workItem` – full `WorkItem` object (steps, headers, payloads).

Generators and interceptors may add more fields, but these three are
always available.

Example:

```yaml
body: |
  {
    "raw": "{{ payload }}",
    "messageId": "{{ headers['message-id'] }}"
  }
```

## SpEL helper: `eval(...)`

For more complex logic we expose a constrained Spring Expression
Language helper:

```yaml
headers:
  x-ph-call-id: "{{ eval(\"#randInt(0,99) < 40 ? 'redis-balance' : 'redis-topup'\") }}"
```

The `eval` helper is backed by
`SpelTemplateEvaluator` in the worker SDK and uses a **restricted**
`SimpleEvaluationContext`. Only a small set of functions and root
variables is available.

### Root variables

- `payload` – raw payload string.
- `headers` – map of headers.
- `workItem` – full `WorkItem` instance (steps, payloads, headers).

### Functions

The following SpEL functions are available (via `#name(...)`):

- `#randInt(min, max)` – random integer, inclusive.
- `#uuid()` – random UUID string.
- `#md5_hex(value)` – MD5 hash in hex.
- `#sha256_hex(value)` – SHA‑256 hash in hex.
- `#base64_encode(value)` / `#base64_decode(value)` – Base64 helpers.
- `#hmac_sha256_hex(key, value)` – HMAC‑SHA256 in hex.
- `#regex_match(input, pattern)` – boolean.
- `#regex_extract(input, pattern, group)` – string (empty if no match).
- `#json_path(payload, path)` – JSONPath extractor; returns a string.
- `#date_format(instant, pattern)` – format `now` or provided Instant.

Example – weighted call selection:

```yaml
headers:
  x-ph-call-id: |
    {{ eval("#randInt(0,99) < 40 ? 'redis-balance' : (#randInt(0,99) < 80 ? 'redis-topup' : 'redis-auth')") }}
```

## Proposed: weighted selection helpers (Pebble)

This section proposes a shorthand for weighted random selection that avoids
multiple independent `#randInt(...)` calls.

### `pickWeighted(...)`

Returns one of the provided values based on weights:

```yaml
headers:
  x-ph-call-id: "{{ pickWeighted('redis-auth', 50, 'redis-balance', 30, 'redis-topup', 20) }}"
```

Rules:
- Arguments are `(value, weight)` pairs (varargs).
- `weight` is an integer `>= 0`; `0` disables an option without removing it (useful for `config-update` toggles).
- If the argument count is odd, any weight is negative, or the sum of weights is `0`, rendering fails.
- Selection is intentionally non-deterministic across worker restarts (no stable seed).

### `pickWeightedSeeded(label, seed, ...)`

Like `pickWeighted(...)`, but uses a deterministic pseudo-random sequence that repeats after restart:

```yaml
headers:
  x-ph-call-id: "{{ pickWeightedSeeded('callId', 'demo-seed-001', 'redis-auth', 50, 'redis-balance', 30, 'redis-topup', 20) }}"
```

Rules:
- `label` groups independent streams inside one worker (use different labels to avoid unrelated choices affecting each other).
- `seed` is only applied on the first invocation for a given `label` in a given worker instance; subsequent calls use the existing stream.
- To restart the sequence without restarting the container, send a `config-update` that triggers `reseed` (see below).

#### `reseed` via `config-update` (proposal)

To explicitly clear all seeded streams inside a worker instance:

```json
{
  "worker": {
    "templating": {
      "reseed": true
    }
  }
}
```

This resets all `pickWeightedSeeded(...)` streams in that worker so the next invocation re-applies its `seed`.

## JSON field access

When `payload` is valid JSON, it's automatically parsed as a Map. Use direct property access:

```yaml
body: |
  {
    "customerId": "{{ payload.customerId }}",
    "nested": "{{ payload.customer.code }}"
  }
```

For complex cases or when you need the raw string, use `#json_path` with JSON Pointer syntax (RFC 6901):

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

Note: `#json_path` expects a JSON **string** as first argument, so use `workItem.payload()` not `payload`.

## HTTP Builder templates

HTTP Builder loads templates from YAML or JSON files under the configured
`templateRoot` (for example `scenarios/bundles/redis-dataset-demo/http-templates/default/*.yaml`).
Each template can contain Pebble expressions in `pathTemplate`, `method`,
`bodyTemplate` and header values.

YAML is recommended for HTTP templates so multi-line bodies remain readable.

Example:

```yaml
serviceId: default
callId: redis-balance
pathTemplate: "/soap/{{ payload.customerCode }}/balance"
method: POST
headersTemplate:
  content-type: application/xml
  x-nonce: "{{ eval(\"#uuid()\") }}"
schemaRef: "schemas/redis-balance.schema.json#/body"
bodyTemplate: |
  <soapEnvelope>
    <!-- templated XML body -->
  </soapEnvelope>
```

When HTTP Builder runs:

- It resolves `serviceId`/`callId` to a template file.
- Renders the templates using the WorkItem context from upstream
  (often supplied by a Generator or Redis dataset provider).

`schemaRef` is an optional, authoring-only hint used by the Hive UI to render
a JSON Schema-backed editor for `bodyTemplate`. It is treated as an opaque string
and ignored by runtime workers.

## Scenario templating check tool

To validate that your templates are syntactically correct and that all
referenced HTTP templates exist, use the CLI tool:

```bash
# From repo root
tools/scenario-templating-check/run.sh \
  --scenario scenarios/bundles/redis-dataset-demo/scenario.yaml

tools/scenario-templating-check/run.sh \
  --check-http-templates \
  --scenario scenarios/bundles/redis-dataset-demo/scenario.yaml
```

Behaviour:

- Parses the scenario YAML.
- Locates the generator bee and renders its `worker.message` templates
  with a sample WorkItem.
- Optionally (`--check-http-templates`):
  - Loads HTTP Builder templates from the default root (or
    `--template-root`).
  - Finds all `x-ph-call-id` usages in the scenario.
  - Ensures there is a matching template for every callId.
  - Renders each HTTP template once with a dummy WorkItem.

The tool exits with a non‑zero status and prints diagnostics if
anything fails to render or any callId is missing.

## Safety & constraints

- SpEL is executed in a **constrained** context: no type references, no
  bean lookups, no reflection.
- Only the listed helpers are available; attempts to access anything
  else will fail.
- Keep templating logic small and focused; heavy test logic should live
  in workers or HTTP Builder templates, not SpEL expressions.
