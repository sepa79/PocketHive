# Templating Guide: Basics

| Reader context | Details |
| --- | --- |
| Audience | Scenario authors building dynamic payloads and request envelopes |
| Prerequisites | Basic scenario YAML knowledge and the [scenario variables contract](../scenarios/SCENARIO_VARIABLES.md) |
| Expected outcome | A small, validated template using the current Pebble and constrained SpEL surface |
| Last verified source | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

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

### Runtime fields and current gate

A future create request must supply both:

- `sutId` (required when `sut`-scoped variables exist)
- `variablesProfileId` (required when any variables exist)

The following object documents the relevant Orchestrator request fields; it is
not a command to submit against the current candidate:

```json
{
  "templateId": "variables-demo",
  "idempotencyKey": "uuid-v4",
  "autoPullImages": false,
  "sutId": "sut-A",
  "variablesProfileId": "france",
  "networkMode": "DIRECT",
  "networkProfileId": null
}
```

At the exact tested source, **Connectivity** fails on
`swarm-lifecycle.schema.json#/$defs/RuntimeMetadata`. Stop before creating this
swarm. Once a corrected candidate passes that preflight, use a unique swarm ID
and the version-matched [customer lifecycle](operators/swarm-lifecycle.md),
retain the returned correlation/operation evidence, and remove and verify the
disposable swarm. `tools/mcp-orchestrator-debug/` is maintainer diagnostics,
not a customer execution path.

## 3. SpEL helper: `eval(...)`

For logic that is too complex for plain Pebble, use constrained
Spring Expression Language:

```yaml
headers:
  x-ph-call-id: "{{ eval(\"#randInt(0,99) < 40 ? 'redis-balance' : 'redis-topup'\") }}"
```

The `eval` helper is backed by `SpelTemplateEvaluator` and runs in a
restricted `StandardEvaluationContext` with type references, method and
constructor resolution, and bean lookup disabled.

### Root variables

- `payload` - current payload. Depending on the rendering boundary, valid JSON
  is available as a parsed object/map or as its raw JSON string.
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
- `#json_path(payload, path)` - JSON Pointer extractor; accepts the current
  payload as either a parsed object/map or a JSON string and returns a string.
- `#date_format(instant, pattern)` - format `now` or provided Instant.
- `#datetime_offset(offset, pattern)` - format the current UTC time after an
  explicit offset such as `+2d`, `-1month`, or `3h`, using a Java date/time
  pattern.
- `#sequence(key, mode, format)` - Redis-backed sequence generator.
- `#sequenceWith(key, mode, format, startOffset, maxSequence)` -
  sequence with explicit start/max.
- `#resetSequence(key)` - destructive one-shot helper that deletes the shared
  Redis counter and returns `true` when removed. Never place it in a repeating
  runtime template; use the guarded reset procedure in the
  [advanced guide](templating-advanced.md#reset-destructive-one-shot-only).

Example - weighted call selection using the current Pebble helper (do not wrap
it in `eval(...)`):

```yaml
headers:
  x-ph-call-id: "{{ pickWeighted('redis-balance', 40, 'redis-topup', 40, 'redis-auth', 20) }}"
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
    "customerId": "{{ eval(\"#json_path(payload, '/customerId')\") }}"
  }
```

Common JSON Pointer patterns:

- Root object field: `/customerId`
- Nested field: `/customer/code`
- Array element: `/items/0/id`

Use the `payload` root variable here. Direct method calls such as
`workItem.payload()` are intentionally disabled in the restricted SpEL
context; `#json_path` accepts both parsed JSON objects/maps and raw JSON
strings.

## 5. Safety baseline

- Keep templates declarative and compact.
- Keep `eval(...)` expressions small and testable.
- Use `vars.*` for environment/profile differences instead of duplicating
  templates.
- Validate templates before long e2e/perf runs.

## Troubleshooting

Check variable resolution against the [scenario variables contract](../scenarios/SCENARIO_VARIABLES.md). For runtime rendering evidence, use the canonical [observability and troubleshooting guide](operators/observability-troubleshooting.md).

## Next step

Continue with [Templating Guide: Advanced](templating-advanced.md) for current sequence helpers, request templates, and validation tooling.
