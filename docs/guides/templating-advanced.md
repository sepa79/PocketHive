# Templating Guide: Advanced

This guide covers advanced patterns for deterministic IDs, weighted
routing, request templates, and validation workflow.

## 1. Redis-backed sequences

Use Redis to generate deterministic, shared sequences across workers and
swarms:

```yaml
body: |
  {
    "txnId": "{{ eval(\"#sequence('my-key', 'numeric', '%010d')\") }}"
  }
```

### Parameters

1. **key** - Redis key for the counter.
2. **mode** - character set (case-insensitive):
   - `alpha`, `alpha_lower`
   - `numeric`
   - `alphanum`, `alphanum_lower`
   - `binary`
   - `hex`, `hex_lower`
3. **format** - printf-style format string with tokens:
   - `%S` uppercase alphabet (or mode-specific uppercase set)
   - `%s` lowercase alphabet (or mode-specific lowercase set)
   - `%d` digits
   - prefix width with `0` for zero-padding (for example `%06d`)

Sequences advance like an odometer: the rightmost token changes
fastest. After max value, sequence wraps in range.

### Sequence behavior

- **Wrapping**: `(value - 1) % max + 1` keeps values in range `1..max`.
- **Thread safety**: Redis `INCR` is atomic across threads/processes.
- **Persistence**: counters persist across swarm restarts; delete
  `ph:seq:<key>` to reset.

### Format strings

- `%S` - uppercase alphabetic/alphanumeric
- `%s` - lowercase alphabetic/alphanumeric
- `%d` - numeric digits
- `%0Nd` - zero-padded numeric (N = width)
- `%NS` / `%Ns` - fixed-width alphabetic (N = width)

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

Configure Redis in scenario `config`:

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

In `application.yml` use `pockethive.worker.config.redis.*`.

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

TCP payload:

```yaml
body: |
  MSG{{ eval("#sequence('tcp-msg-seq', 'numeric', '%08d')") }}|DATA|END
```

## 2. Proposed weighted helpers (Pebble)

This is a proposal for shorthand weighted selection without chained
`#randInt(...)` calls.

### `pickWeighted(...)`

Returns one of provided values based on weights:

```yaml
headers:
  x-ph-call-id: "{{ pickWeighted('redis-auth', 50, 'redis-balance', 30, 'redis-topup', 20) }}"
```

Rules:

- arguments are `(value, weight)` pairs,
- `weight >= 0`,
- odd argument count / negative weight / total weight `0` -> render
  failure,
- selection is intentionally non-deterministic across restarts.

### `pickWeightedSeeded(label, seed, ...)`

Deterministic pseudo-random stream per label:

```yaml
headers:
  x-ph-call-id: "{{ pickWeightedSeeded('callId', 'demo-seed-001', 'redis-auth', 50, 'redis-balance', 30, 'redis-topup', 20) }}"
```

Rules:

- `label` separates independent streams in one worker,
- seed applies on first call per label in a worker instance,
- stream can be reset by config-update.

#### `reseed` via `config-update` (proposal)

```json
{
  "templating": { "reseed": true }
}
```

## 3. Request templates (`request-builder`)

`request-builder` loads templates from YAML/JSON under `templateRoot`
(for example `scenarios/e2e/redis-dataset-demo/templates/http/default/*.yaml`).

Each template may contain Pebble in `pathTemplate`, `method`,
`bodyTemplate`, and header values.

YAML is recommended for multiline bodies.

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

At runtime:

- `serviceId` + `callId` resolve template file,
- templates are rendered from upstream WorkItem context,
- `schemaRef` is authoring-only metadata used by UI editors.

## 4. Scenario templating check tool

Validate template syntax and call coverage:

```bash
tools/scenario-templating-check/run.sh \
  --scenario scenarios/e2e/redis-dataset-demo/scenario.yaml

tools/scenario-templating-check/run.sh \
  --check-http-templates \
  --scenario scenarios/e2e/redis-dataset-demo/scenario.yaml
```

Behavior:

- parses scenario YAML,
- renders generator templates with sample WorkItem,
- with `--check-http-templates`:
  - loads request templates,
  - finds `x-ph-call-id` usages,
  - verifies template exists per callId,
  - renders each template once.

The tool exits non-zero on failures.

## 5. Safety constraints

- SpEL runs in constrained context: no type references, bean lookups,
  or reflection.
- Only registered helpers are callable.
- Keep heavy logic in workers or templates, not deeply nested
  expressions.
