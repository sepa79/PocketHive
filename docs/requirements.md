# PocketHive Requirements

## Purpose
PocketHive is a portable transaction swarm: a set of small, composable services and a UI that generate, moderate, process and observe message workflows over RabbitMQ.

## Overall System Requirements
- Provide a Docker Compose environment orchestrating RabbitMQ, UI, and services.
- Components communicate via the `ph.hive` exchange and durable queues.
- Control-plane messaging flows through the `ph.control` exchange using `sig.*` routing keys for commands and `ev.*` for status or metrics.
- Each service exposes its presence and health through periodic `status-delta` events and responds to `status-request` signals.
- The UI connects to RabbitMQ over same-origin Web-STOMP at `/ws`.
- Services propagate an `x-ph-trace` header to carry trace IDs and hop timing between components.

## Component Requirements

### UI
- Connects to RabbitMQ using Web-STOMP and provides Connect/Disconnect controls.
- Logs STOMP traffic in the Buzz panel with separate OUT and IN views:
  - OUT lists each publish with the routing key and a pretty-printed JSON body.
  - IN captures all subscription messages and shows a concise summary of the payload.
- Offers Start/Stop generator control and a broadcast button that emits a global `status-request`.
- Provides view tabs, log filters, metric selectors, and graph controls for user interaction.
- Shows a WireMock request journal via a toolbar dropdown, fetching `/__admin/requests` and listing method, URL, and status.

### Generator Service
- Produces HTTP-like transaction messages at a configurable rate per second.
- Publishes generated messages to the `ph.hive` exchange routed to the `ph.gen` queue.
- Processes control messages to enable/disable generation, adjust rate, issue single requests, and respond to `status-request`.
- Control messages can also update request method, path, headers, and body at runtime.
- Emits `status-delta` every 5 s and `status-full` on startup or when requested.

### Moderator Service
- Consumes messages from the generator queue and forwards them to the moderated queue when enabled.
- Handles `config-update` messages to toggle forwarding and answers `status-request`.
- Sends periodic `status-delta` and startup `status-full` events describing queue flow.

### Processor Service
- Consumes moderated messages, appends observability context, and forwards to the final queue.
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
