Status: draft / design

# HTTP Sequence worker (`http-sequence`) — Journey runner for multi-call REST flows

## Goal
Support “transaction-style” performance tests and workloads where a single logical job requires a **fixed sequence**
of REST calls (e.g. onboarding: create customer → create account → issue card → …) with data flowing step-to-step,
without needing a 10–20 container swarm (generator + request-builder + processor × N).

The core idea: **one WorkItem = one journey**, executed sequentially inside a single worker instance.

## Non-goals (v1)
- Arbitrary scripting (Groovy / user code execution).
- Unbounded loops (no `while`; any repetition must be explicit and bounded).
- Duplicating HTTP template definitions or templating engines (SSOT remains on-disk templates + worker-sdk renderer).

## Inputs / outputs (concept)
`http-sequence` is a normal worker and can be used with existing IOs:

- Input: `SCHEDULER` (continuous onboarding) or `REDIS_DATASET` (per-customer/per-tenant journeys).
- Output: typically `RABBITMQ` (optional), plus an optional **debug capture** output.

Recommended pattern for “onboarding non-stop + concurrent usage swarms”:

1) Onboarding swarm runs `http-sequence` at a controlled pace (scheduler or per-tenant dataset).
2) On success it emits a compact “created identities” record and routes it into Redis via `RedisUploaderInterceptor`.
3) Usage swarms consume those Redis lists via `REDIS_DATASET` and generate “real usage” traffic; their own outputs can
   recycle records into other lists (e.g. `topup`, `active`, `suspended`) using the same Redis uploader.

See the Redis dataset pattern in `docs/scenarios/SCENARIO_PATTERNS.md`.

## SSOT: reuse the existing HTTP templates
`http-sequence` must reuse the same template model as `request-builder`:

- Disk-backed templates under `templateRoot`, keyed by `(serviceId, callId)`.
- Template fields: `method`, `pathTemplate`, `headersTemplate`, `bodyTemplate`.
- Rendering: `TemplateRenderer` + constrained `eval(...)` (see `docs/scenarios/SCENARIO_TEMPLATING.md`).

This keeps call definitions in one place and avoids an “alternate DSL” for the same behavior.

## Execution model
Per inbound WorkItem (“journey”):

1) Build a mutable `context` map (starts from the inbound payload/headers and accumulates step outputs).
2) For each step:
   - render the call template using the standard context (`payload`, `headers`, `workItem`, `vars`) plus `context`
     (name TBD, e.g. `ctx`).
   - execute the HTTP call with the same semantics as `processor-service` (baseUrl, timeouts, sslVerify, connection reuse).
   - record step metadata into WorkItem history (small, always-on).
   - apply **extracts** into `context` for the next step.
3) Emit the final WorkItem (optional) and/or rely on interceptors (e.g. Redis uploader) to publish derived records.

### Concurrency & pacing
Concurrency is at the journey level: max in-flight journeys per instance.
Each journey’s steps remain sequential to preserve data dependencies and to model SUT behavior accurately.

For high scale, run multiple `http-sequence` instances and use:
- scheduler rate limiting (for “steady onboarding”)
- or Redis lists per tenant/customer to control fairness and multi-tenant mixing

## Extracts and transforms (no scripting)
Onboarding flows usually need many “response → next request” mappings.
The recommended approach is declarative mapping backed by the existing templating/runtime helpers.

### Extracts (from response)
Each step can define extract rules, for example:

- From JSON body (JSON Pointer semantics): `/customerId`, `/account/id`, `/cards/0/token`
- From response headers: `location`, `x-request-id`
- From status code: `2xx/4xx/5xx` classification or exact value

Extract target is `ctx.*` and/or a merged payload map used by later templates.

### Transforms (compute values)
Transforms use Pebble templates (and optionally `eval(...)`) to compute fields:

- ids / derived keys: `{{ eval("#uuid()") }}`
- JSON stitching: `{{ ctx.customerId }}-{{ ctx.accountId }}`
- conditional logic: `{{ eval("ctx.segment == 'vip' ? 'A' : 'B'") }}`

Avoid introducing Groovy. If the current helper set is insufficient, prefer adding a small, safe helper
(e.g. `#xpath(...)` for XML) rather than enabling arbitrary code execution.

## Step config sketch (MVP)
The step contract is intentionally simple and matches what a future Ninja Bee `http.request` step type would need.
Illustrative YAML:

```yaml
worker:
  steps:
    - id: createCustomer
      callId: CreateCustomer
      retry:
        maxAttempts: 5
        initialBackoffMs: 100
        backoffMultiplier: 2.0
        maxBackoffMs: 2000
        on: ["exception", "5xx", "429"]
      extracts:
        - fromJsonPointer: /id
          to: customerId
          required: true
    - id: createAccount
      callId: CreateAccount
      continueOnNon2xx: false
```

Notes:
- `retry.on[]` tokens supported by MVP: `exception`, `non2xx`, `4xx`, `5xx`, or an exact status code like `429`.
- Response headers are treated case-insensitively in `fromHeader`.

## Debugging large response bodies without bloating WorkItems
Full response bodies are valuable for debugging, but making them default step payloads is not viable at scale.
The design separates “always-on metadata” from “optional capture”.

### Always-on (stored in `WorkItem.steps[]`)
Store only small fields per step:

- `callId` / `serviceId`
- status code / outcome
- duration (ms)
- retry attempts
- response size (bytes)
- content type
- `sha256` of the response body (so you can correlate with a captured blob)
- extracted fields (the data needed by later steps)

In the MVP implementation:

- step payload remains the **current journey context** (JSON map) so downstream templates and workers can keep reading `payload.*`.
- step metadata is stored in **step headers** (e.g. `x-ph-http-seq-status`, `x-ph-http-seq-attempts`, `x-ph-http-seq-debug-ref`).

### Optional capture modes (side-channel)
Capture the full response body as a separate “debug event” with strict limits and sampling.

Proposed config (names TBD; keep stable once implemented):

```yaml
config:
  worker:
    debugCapture:
      mode: "ERROR_ONLY"     # NONE | ERROR_ONLY | SAMPLE | ALWAYS
      samplePct: 0.1         # only for SAMPLE; 0.0..1.0
      maxBodyBytes: 262144   # hard cap per response body
      maxJourneyBytes: 1048576
      includeHeaders: true
      includeRequest: false  # default false (requests may include secrets)
      bodyPreviewBytes: 4096 # optionally also store a small preview in steps[]
      redisTtlSeconds: 120   # TTL for full-body captures stored in Redis (key is written to step meta as debugRef)
```

`debugCapture` is intended to be updatable at runtime via `signal.config-update` (same behavior as other worker config
updates; no restart required).

Example `config-update` payload (illustrative):

```json
{
  "debugCapture": {
    "mode": "ERROR_ONLY",
    "samplePct": 0.01,
    "maxBodyBytes": 65536,
    "bodyPreviewBytes": 2048
  }
}
```

### Where captured bodies go (practical options)
Two complementary options:

1) **Debug taps (recommended for ad-hoc investigation)**
   - `http-sequence` publishes debug events to a dedicated work output (e.g. `work.out.debug`).
   - Operators use the Orchestrator Debug Tap API to create an **exclusive, auto-delete** tap queue with:
     - `x-message-ttl`
     - `x-max-length`
   - This gives “recent samples” debugging without permanently storing bodies.

   See `orchestrator-service` `DebugTapService` for current queue semantics.

2) **Durable capture queue with TTL + DLQ (requires work-topology support)**
   - Declare a per-swarm durable queue with Rabbit arguments:
     - `x-message-ttl` (retention)
     - `x-max-length-bytes` + `x-overflow=reject-publish` (bounded memory)
     - `x-dead-letter-exchange` / `x-dead-letter-routing-key` (DLQ)
   - This requires extending swarm-controller’s work topology declaration to support queue arguments per port/suffix.

Option (2) is intentionally a follow-up because it touches topology/contract surfaces.

### Current implementation note (MVP)
The initial implementation stores full captures in **Redis** (TTL key/value) and writes the Redis key as `debugRef`
into the step metadata. This avoids adding multi-output wiring or queue-topology changes for the MVP.

## Open questions
- Naming and contract placement for `debugCapture` and per-step mapping schema (keep SSOT and avoid duplicate parsers).
- Response parsing helpers: JSON is covered; do we need a safe `#xpath(...)` helper for SOAP/XML onboarding APIs?
- Failure semantics: per-step retry/backoff, and “poison journey” handling (drop vs retry vs route to error output).
