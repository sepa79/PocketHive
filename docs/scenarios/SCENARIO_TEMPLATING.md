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
- `#randLong(min, max)` – random long, inclusive (pass numbers as strings to avoid parser limits).
- `#uuid()` – random UUID string.
- `#md5_hex(value)` – MD5 hash in hex.
- `#sha256_hex(value)` – SHA‑256 hash in hex.
- `#base64_encode(value)` / `#base64_decode(value)` – Base64 helpers.
- `#hmac_sha256_hex(key, value)` – HMAC‑SHA256 in hex.
- `#regex_match(input, pattern)` – boolean.
- `#regex_extract(input, pattern, group)` – string (empty if no match).
- `#json_path(payload, path)` – JSONPath extractor; returns a string.
- `#date_format(instant, pattern)` – format `now` or provided Instant.
- `#sequence(key, mode, format)` – Redis-backed sequence generator.
- `#sequenceWith(key, mode, format, startOffset, maxSequence)` – sequence with explicit start/max.
- `#resetSequence(key)` – deletes the Redis counter; returns `true` when removed.

Example – weighted call selection:

```yaml
headers:
  x-ph-call-id: |
    {{ eval("#randInt(0,99) < 40 ? 'redis-balance' : (#randInt(0,99) < 80 ? 'redis-topup' : 'redis-auth')") }}
```

## Redis-backed sequences

Use Redis to generate deterministic, shared sequences across workers and swarms:

```yaml
body: |
  {
    "txnId": "{{ eval(\"#sequence('my-key', 'numeric', '%010d')\") }}"
  }
```

### Parameters

1. **key** – Redis key for the counter.
2. **mode** – character set (case-insensitive):
   - `alpha`, `alpha_lower`
   - `numeric`
   - `alphanum`, `alphanum_lower`
   - `binary`
   - `hex`, `hex_lower`
3. **format** – printf-style format string with tokens:
   - `%S` uppercase alphabet (or mode-specific uppercase set)
   - `%s` lowercase alphabet (or mode-specific lowercase set)
   - `%d` digits
   - prefix width with `0` for zero-padding (e.g., `%06d`)

Sequences advance like an odometer: the **rightmost** token changes fastest.
When the maximum is reached, values wrap within the computed range.

### Sequence behavior

- **Wrapping**: `(value - 1) % max + 1` keeps values in range `1..max`.
- **Thread safety**: Redis `INCR` is atomic across threads and processes.
- **Persistence**: sequences persist across swarm restarts; delete `ph:seq:<key>` to reset.

### Format strings

Format strings use printf-style tokens:

- `%S` – uppercase alphabetic/alphanumeric characters
- `%s` – lowercase alphabetic/alphanumeric characters
- `%d` – numeric digits
- `%0Nd` – zero-padded numeric (N = width)
- `%NS` / `%Ns` – fixed-width alphabetic (N = width)

Examples:

| Format | Mode | Output Examples |
| --- | --- | --- |
| `%010d` | numeric | `0000000001`, `0000000002`, `0000000123` |
| `%6S` | alpha | `AAAAAA`, `AAAAAB`, `AAAAAC` |
| `%4s%2d` | alpha | `aaaa01`, `aaaa02`, `aaab01` |
| `%8S` | alphanum | `AAAAAAAA`, `AAAAAAAB`, `0000000A` |
| `%4S` | hex | `0000`, `0001`, `FFFF` |
| `TXN-%06d` | numeric | `TXN-000001`, `TXN-000002` |

Modes:

| Mode | Characters | Use Case |
| --- | --- | --- |
| `alpha` | A-Z | Uppercase reference codes |
| `alpha_lower` | a-z | Lowercase reference codes |
| `numeric` | 0-9 | Transaction IDs, counters |
| `alphanum` | A-Z, 0-9 | Mixed identifiers |
| `alphanum_lower` | a-z, 0-9 | Lowercase mixed identifiers |
| `binary` | 0-1 | Binary flags |
| `hex` | 0-9, A-F | Hexadecimal identifiers |
| `hex_lower` | 0-9, a-f | Lowercase hex identifiers |

### Custom start/max

```yaml
body: |
  {
    "id": "{{ eval(\"#sequenceWith('my-key', 'numeric', '%06d', 1000, 999999)\") }}"
  }
```

### Reset

```yaml
headers:
  x-reset: "{{ eval(\"#resetSequence('my-key')\") }}"
```

### Redis configuration

Configure Redis in the scenario `config` block:

```yaml
config:
  redis:
    enabled: true
    host: redis
    port: 6379
    username: myuser
    password: mypass
    ssl: false
```

In `application.yml`, use `pockethive.worker.config.redis.*`.

### Examples

HTTP transaction payload:

```yaml
body: |
  {% set now = eval('now') %}
  {
    "transactionId": "{{ eval("#sequence('payment-txn-seq', 'numeric', '%012d')") }}",
    "referenceId": "{{ eval("#sequence('payment-ref-seq', 'alphanum', '%8S')") }}",
    "amount": 100.00,
    "timestamp": "{{ eval("#date_format(now, 'yyyy-MM-dd''T''HH:mm:ssZ')") }}"
  }
```

TCP message payload:

```yaml
body: |
  MSG{{ eval("#sequence('tcp-msg-seq', 'numeric', '%08d')") }}|DATA|END
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
  "templating": { "reseed": true }
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
