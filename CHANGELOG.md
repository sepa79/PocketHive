# Changelog

All notable changes to this project will be documented in this file.

## [0.2.0] - 2025-08-29
Timestamp: 2025-08-29T00:18:36Z

- UI: Add Nginx reverse proxy for WebSocket at `/ws` to RabbitMQ Web‑STOMP (`rabbitmq:15674/ws`).
- UI: Default WebSocket URL to same‑origin (`ws(s)://<host>/ws`) to avoid CORS/origin issues.
- UI: Add System Logs panel to capture system events and user actions (connect/disconnect, field edits, WS lifecycle).
- UI: Periodic health ping to `/healthz` with status transitions logged.
- UI: Add `/healthz` endpoint in Nginx and Docker healthcheck for `ui` service.
- Docs: Update README with stack, quick start, proxy, healthchecks, troubleshooting, and development notes.

## [0.1.1] - 2025-08-29
Timestamp: 2025-08-28T23:51:09Z

- Initial UI wiring and assets.

## [0.1.0] - 2025-08-29
Timestamp: 2025-08-28T23:07:54Z

- Initial repository setup.
