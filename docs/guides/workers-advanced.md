# Workers Guide: Advanced

| Reader context | Details |
| --- | --- |
| Audience | Experienced scenario authors and PocketHive operators |
| Prerequisites | Completion of [Workers Guide: Basics](workers-basics.md) and control-plane familiarity |
| Expected outcome | A bounded multi-worker flow with observable runtime configuration |
| Last verified PocketHive version | PocketHive `v0.15.35` |

This guide focuses on production patterns, control-plane behavior, and
how to model larger traffic flows with minimal worker duplication.

## 1. Pick the right worker shape

Use dedicated roles only when they add a clear boundary:

- `generator` for synthetic payload generation.
- `request-builder` for template-based request envelope building.
- `processor` for protocol execution (HTTP/TCP).
- `http-sequence` for step-by-step multi-call journeys in one worker.

Prefer `http-sequence` when one logical transaction requires multiple
ordered REST calls with data flowing between steps (for example,
onboarding chains). This reduces container count and queue hops.

HTTP Sequence keeps a required worker `baseUrl`. Only steps that target a
different authority need an override:

```yaml
config:
  baseUrl: "{{ sut.endpoints['identity'].baseUrl }}"
  steps:
    - { id: login, callId: login }
    - id: account
      callId: account
      sutEndpointId: accounts
    - id: audit
      callId: audit
      baseUrl: http://audit-sandbox:9080/audit
```

Use `sutEndpointId` for a named endpoint in the bound SUT environment. Use
step `baseUrl` only for an explicit literal HTTP(S) target. Do not set both.
An invalid override fails without retrying against worker `baseUrl`.

## 2. Control-plane config updates

All workers should be treated as runtime-configurable components.
Common operational loop:

1. Start swarm with baseline config.
2. Observe queue depth and throughput.
3. Send `signal.config-update` for rate/feature tuning.
4. Validate status snapshots and effective config.

References:

- `docs/ORCHESTRATOR-REST.md`
- `docs/control-plane/worker-guide.md`
- `docs/correlation-vs-idempotency.md`

## 3. Continuous onboarding + parallel usage

Recommended pattern for long-running load growth:

1. Onboarding swarm writes newly created identities/cards/accounts to
   Redis lists.
2. Usage swarms read those lists through `REDIS_DATASET` and generate
   live traffic against the same SUT.
3. Follow-up swarms can recycle records into other lists (`topup`,
   `active`, `suspended`) through output interceptors.

This pattern keeps producer and consumer lifecycles independent while
sharing data through Redis as the handoff layer.

Reference: `docs/scenarios/SCENARIO_PATTERNS.md`.

## 4. Debugging without bloating WorkItems

For `http-sequence`, keep `WorkItem.steps[]` compact by default:

- Always keep metadata and extracted fields.
- Store full request/response bodies in optional debug capture with
  limits (size, sampling, TTL).

Operational options:

- short-lived debug tap queues for ad-hoc diagnosis,
- Redis-backed capture keys with TTL,
- durable queue + DLQ as a follow-up topology decision.

Reference: `docs/archive/http-sequence-worker.md`.

## 5. Hardening checklist

- Keep queue aliases explicit and non-overloaded.
- Keep worker roles single-purpose (SRP by module).
- Propagate `correlationId` end-to-end.
- Cap retries and backoff windows per step.
- Bound debug payload retention (`maxBodyBytes`, TTL).
- Validate scenario bundles in CI before merge.

## 6. Migration direction

For HTTP request construction, prefer `request-builder` as the canonical
builder role. Keep `http-sequence` focused on journey orchestration, not
on replacing low-level protocol handlers.

## Troubleshooting

Use the canonical [observability and troubleshooting guide](operators/observability-troubleshooting.md) for runtime evidence, and check control-plane behavior against the [worker guide](../control-plane/worker-guide.md).

## Next step

Apply these boundaries with the reusable flows in [Scenario Patterns](../scenarios/SCENARIO_PATTERNS.md), then validate the bundle with the [authoring and test tools](integrations/authoring-and-test-tools.md).
