# PocketHive Requirements

## Purpose
PocketHive is a portable transaction swarm: a set of small, composable services and a UI that generate, moderate, process and observe message workflows over RabbitMQ.

## Overall System Requirements
- Provide a Docker Compose environment orchestrating RabbitMQ, UI, and services.
- Components communicate via swarm-scoped workload exchanges with queues named `ph.work.<swarmId>.<queueName>`.
- Services read a `POCKETHIVE_CONTROL_PLANE_SWARM_ID` env (default `default`) to derive these names.
- Control-plane messaging flows through the `ph.control` exchange using the routing families defined in `docs/ARCHITECTURE.md` and `docs/spec/asyncapi.yaml` (signals `signal.*`, outcomes `event.outcome.*`, metrics `event.metric.*`, alerts `event.alert.{type}.*`).
- Each service exposes its presence and health through periodic `event.metric.status-delta` events and responds to `signal.status-request`.
- The UI connects to RabbitMQ over same-origin Web-STOMP at `/ws`.
- Services propagate an `x-ph-trace` header to carry trace IDs and hop timing between components.

## Component Requirements

### UI
- Connects to RabbitMQ using Web-STOMP and reuses a shared client across modules.
- Buzz panel logs STOMP traffic in separate IN, OUT, and Other tabs and lists current binds and URLs in a Config tab:
  - OUT lists each publish with the routing key and a pretty-printed JSON body.
  - IN captures all subscription messages and shows a concise summary of the payload.
  - Other captures UI events and diagnostics.
- Hive page lists active components and shows queue stats, allowing `config.update` messages from a detail drawer.
- Provides view tabs and a link to the WireMock admin console.

### Generator Service
- Produces HTTP-like transaction messages at a configurable rate per second.
- Publishes generated messages to the `ph.work.<swarmId>` exchange routed to the `ph.work.<swarmId>.generator` queue.
- Processes control messages to enable/disable generation, adjust rate, issue single requests, and respond to `status-request`.
- Control messages can also update request method, path, headers, and body at runtime.
- Emits `status-delta` every 5 s and `status-full` on startup or when requested.

### Moderator Service
- Consumes messages from the generator queue and forwards them to the moderated queue when enabled.
- Handles `config-update` messages to toggle forwarding and answers `status-request`.
- Sends periodic `status-delta` and startup `status-full` events describing queue flow.

### Processor Service
- Consumes moderated messages, logs each one, and relays it to the system under test using the configured base URL combined with the message's path.
- Forwards the SUT's response (status, headers, body) to the final queue without modifying it.
- Supports enable/disable via control messages and replies to `status-request`.
- Control messages may override the downstream `baseUrl` without requiring a restart.
- Emits periodic `status-delta` and startup `status-full` events.

### PostProcessor Service
- Consumes final-queue messages and records hop and total latency metrics.
- Pushes metrics to the control exchange and increments error counters when flagged.
- Accepts control messages to enable/disable processing and sends `status-full` on start.

### Log Aggregator Service
- Consumes log events from RabbitMQ and batches them to Loki over HTTP.
- Batch size, flush interval, and retry strategy are configurable via environment variables.
