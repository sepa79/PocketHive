# PocketHive Evidence Taxonomy

## Status
`IN PROGRESS`

This document defines which evidence sources an agent may use when proving that
a scenario bundle or swarm behaves correctly.

## Core Rule

Evidence must come from PocketHive product APIs, mock/dataset admin APIs, or
guarded MCP tool output. Do not read Docker logs, container logs, local files
outside the active bundle, or query Loki directly. Runtime log evidence must go
through PocketHive's bounded Orchestrator runtime debug API.

Loki is a future backend option only. If PocketHive later exposes structured
logs through a product-owned API, the MCP may use that API and should report it
as `pockethive_logs`.

## Evidence Sources

| Source | MCP tool | Proves | Does not prove |
|---|---|---|---|
| Swarm state | `swarm.get`, `swarm.list` | Swarm exists, lifecycle state, template binding | Payload correctness |
| Journal | `debug.journal` | Lifecycle events, worker events, reported runtime errors | Full payload content |
| Queue depths | `debug.queues` | Backlog, drain state, message movement pressure | Request success |
| Tap samples | `debug.tap`, `debug.tap.read`, `debug.tap.close` | Representative WorkItem payload flow through a role/edge | Complete population coverage |
| ClickHouse metrics | `metrics_query` | Throughput, latency, counters, success/error metrics through whitelisted ClickHouse summary shapes | Root cause of a failure by itself |
| WireMock requests | `mock.wiremock.requests`, `mock.wiremock.unmatched` | HTTP SUT-double calls and unmatched traffic | Real SUT behaviour |
| TCP mock requests | `mock.tcp.requests`, `mock.tcp.unmatched` | TCP SUT-double payloads and scenarios | Real SUT behaviour |
| Dataset check | `dataset.check` | Dataset exists and has expected shape/count | Runtime consumption success |
| Runtime logs | `runtime_tail_worker_logs` | Bounded worker/manager log tail through Orchestrator runtime debug | Durable log retention or direct Docker/Loki access |
| Structured logs | Future `debug.logs` or equivalent | Product-owned structured log events if PocketHive exposes them | Anything from container logs directly |
| Evidence summary | `evidence.summary` | Aggregated read-only view of available and missing evidence | New evidence beyond its source tools |

## Evidence Levels

| Level | Required for | Minimum evidence |
|---|---|---|
| Generated | Generated bundle | generation sanity result and contract source metadata |
| Deployable | Imported scenario | `bundle.validate`, `scenario.deploy`, `scenario.get` |
| Runnable | Started swarm | `swarm.create`, `swarm.start`, `swarm.get`, `debug.journal` |
| Flow-proven | End-to-end data path | Runnable evidence plus `debug.tap` and mock/dataset evidence |
| Performance-proven | Load/performance claim | Flow-proven evidence plus ClickHouse throughput/latency metrics from `metrics_query` |

## Evidence Payload Rules

- Store evidence summaries in `CHANGELOG.md`.
- Include exact tool names and timestamps where available.
- Include IDs: bundle, scenario, swarm, template, dataset, mock stub.
- Sanitise tap samples and request bodies before writing docs.
- Truncate long payloads and state what was truncated.
- Never invent missing values. If a tool did not return a value, say so.
- Do not use screenshots as primary evidence when structured tool output exists.

## Suggested CHANGELOG Evidence Block

```yaml
evidence:
  bundle:
    generationSanity: "<wizard/generation sanity summary>"
    validate: "<bundle.validate summary>"
  runtime:
    swarmId: "<swarm id>"
    journal: "<debug.journal summary>"
    queues: "<debug.queues summary>"
    tapSample: "<debug.tap sample summary>"
  sutDouble:
    wiremockRequests: "<mock.wiremock.requests summary>"
    tcpRequests: "<mock.tcp.requests summary>"
  metrics:
    throughput: "<metrics_query tx-outcomes-summary or processor-runtime-summary result>"
    latency: "<metrics_query tx-outcomes-summary or processor-runtime-summary result>"
  logs:
    runtimeTail: "<runtime_tail_worker_logs bounded excerpt, only when relevant>"
    pockethiveLogs: "<only if PocketHive exposes structured logs>"
```
