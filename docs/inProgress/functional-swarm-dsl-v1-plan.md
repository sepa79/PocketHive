# Functional Swarm DSL v1

Status: in progress

## Scope

The v1 POC proves one HTTP invocation against a live swarm and the same invocation locally.
It supports only explicit, unauthenticated HTTP templates and unauthenticated Redis endpoints.
Templates using `authRef` fail explicitly; v1 does not silently omit or emulate authentication.
Redis credentials are outside the v1 contract and must not be inferred.

## Single sources of truth

- HTTP template: `common/request-templates` `HttpTemplateDefinition`.
- HTTP rendering: `common/request-templates` `HttpTemplateRenderer`.
- HTTP execution: `common/request-execution` `RequestExecutor`.
- RedisRPC request: `docs/spec/functional-swarm-rpc.schema.json` and
  `common/functional-swarm-contracts`.
- Remote ingress: `functional-swarm-ingress-service`.
- Remote reply publication and expiry: `functional-swarm-reply-service`.

## Request path

`FunctionalSwarmInvocation` is the public input for both modes.

1. Local mode renders the configured `HttpTemplateDefinition` and executes it through the
   shared `RequestExecutor`.
2. Remote mode serializes the versioned RPC request and waits on its generated reply list.
3. The ingress validates the RPC request and generated reply list, renders the same configured
   template, and sends the canonical HTTP request envelope to Processor.
4. The reply sink accepts only ingress-owned Functional Swarm transport headers. It maps either a
   canonical HTTP result or a canonical worker-failure event to one expiring RPC reply. A late
   response therefore cannot create a permanent reply-list leak.

## Failure path: shared worker error output

Functional Swarm must not add exception handling or Redis publishing to individual workers.
Template parsers/renderers, Request Builder, Processor, and future workers need the same failure
semantics. A worker exception is therefore a first-class output of the worker runtime, not a
terminal, worker-local Journal action.

```text
normal worker exception
        |
        v
shared Worker SDK -> canonical work.failure output
        |                                  |
        v                                  v
Journal consumer                    Functional Swarm reply consumer
fingerprint + deduplicate            validated request context -> RPC failure reply
```

### Canonical ownership

- The shared Worker SDK owns conversion of an uncaught worker exception and its input `WorkItem`
  into a versioned `work.failure` envelope. The envelope preserves worker identity, observability
  context, and the original top-level headers; its failure code/message must be safe for transport.
- A shared failure routing contract/utility owns the error exchange and routing. Workers must not
  hand-craft error routing keys or directly write to the Journal, Rabbit, or Redis on failure.
- Journal is a read-only projection of `work.failure`, not an alternate failure path. It owns the
  canonical fingerprint and flood/deduplication policy for Journal records and alerts.
- `functional-swarm-reply-service` is a second, independent `work.failure` consumer. It maps a
  failure with validated Functional Swarm request context into the versioned RPC failure reply and
  publishes it once with the configured TTL.

Journal deduplication applies only to Journal records/alerts. It must never suppress an individual
RPC failure reply: every caller that submitted a valid request needs a terminal response.

### Trust boundary

The ingress may add Functional Swarm transport headers only after the RPC request has parsed and
the generated reply list has matched the configured namespace. Failures after that point retain
trusted request context and are eligible for a Functional Swarm failure reply. A malformed or
untrusted raw Redis request has no trusted reply destination; it is emitted only to `work.failure`
and Journal, never answered through Redis.

### Required runtime wiring

- The Worker SDK and Swarm Controller must provide a named error side-output independent of normal
  `work.out` delivery. This is a shared infrastructure channel, not a per-worker flag and not a
  Functional-Swarm-specific adapter.
- The Functional Swarm scenario binds the shared failure output to both the Journal consumer and
  its reply consumer. These are independent subscriptions, not competing consumers of one queue.
- Normal successful items keep their existing topology; failures do not attempt to travel through
  Processor or any other normal output edge.

### Acceptance criteria

- A template/parser failure, Request Builder failure, and Processor transport failure each produce
  a client-visible terminal RPC failure before the caller timeout.
- Repeated identical failures are deduplicated/rate-limited only in Journal; every valid RPC
  invocation still receives its own failure reply.
- A malformed raw RPC request produces an auditable failure event but no Redis reply.
- A failure in the error-output path is explicit and never re-emitted into the same error output.

The POC has no MCP tool. A Java caller owns one explicit `FunctionalSwarmRemoteConfig`; it never
guesses a target, transport, template, timeout, or Redis list.
