# Architecture

## Goal
PocketHive delivers modular Java microservices and a React UI that together handle event processing and moderation.

## System context
Services communicate over HTTP and AMQP. Each service exposes APIs and consumes messages while remaining independent.

## Orchestration hierarchy
PocketHive coordinates work through a layered control plane:

- **Queen** – global scheduler that creates and stops swarms.
- **Herald** – per‑swarm controller started by the Queen; it provisions message queues and launches worker containers.
- **Bees** – the worker services inside each swarm.

### Swarm bootstrap
When a new swarm is requested, the Queen spawns a Herald. The Herald declares the required exchanges and queues, then starts the bee containers defined by the swarm template.

## Multi-Region & Queue Adapters
PocketHive will support swarms running in multiple geolocations. Each swarm connects to a region-local broker while the Queen coordinates them through the control plane. Regions remain isolated from each other’s traffic yet share common naming and signalling conventions.

To enable broker diversity, messaging will flow through pluggable queue adapters. The core interface will expose publish and consume operations and minimal lifecycle hooks. Implementations for AMQP, Kafka, SQS and others can plug in without altering domain code.

### Adding a new driver
1. Implement the adapter interface for the target broker.
2. Provide configuration mapping and wiring inside the swarm controller.
3. Register the driver with the Queen so swarms may select it at launch time.

This feature is planned and not yet implemented.

## Layers
Every service follows a hexagonal layout:
- **api** – inbound ports and DTOs.
- **app** – use cases and orchestration.
- **domain** – business rules, framework‑free.
- **infra** – adapters for persistence, messaging and external systems.

## Boundaries
Cross‑service calls go through API or message contracts only. Shared libraries live outside the domain to keep it pure.

## Testing strategy
- JUnit 5 for unit and integration tests.
- Cucumber for behaviour specs when features require it.
- ArchUnit guards package boundaries.

## Security
Principle of least privilege, encrypted transport, no secrets in code or logs.

## Versioning
Semantic Versioning with per‑service change logs.
