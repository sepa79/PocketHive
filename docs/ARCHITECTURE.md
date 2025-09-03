# Architecture

## Goal
PocketHive delivers modular Java microservices and a React UI that together handle event processing and moderation.

## System context
Services communicate over HTTP and AMQP. Each service exposes APIs and consumes messages while remaining independent.

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
