# Changelog

All notable changes to this project will be documented in this file.

## [0.2.5] - 2025-08-31
Timestamp: 2025-08-31T01:50:27Z

- Wrap Hive, Swarm and Swarmlets icons in hexagon borders and place a radar icon beside the monolith status button.

## [0.2.4] - 2025-08-31
Timestamp: 2025-08-31T01:32:04Z

- Replace Swarm view with Hive featuring All, Swarm, and Swarmlets tabs and bee-themed icons; remove web generator link.

## [0.2.3] - 2025-08-29
Timestamp: 2025-08-29T02:20:00Z

- Refactor Events connection to use StompJS (same as Generator) instead of a custom STOMP client.
- Improve diagnostics around connect/handshake and socket lifecycle; clearer syslog entries.
- Normalize CRLF handling in STOMP frame parsing (pre-refactor) to avoid missed CONNECTED frames.

## [0.2.1] - 2025-08-29
Timestamp: 2025-08-29T01:03:45Z

- UI: Add browser generator integration under `/generator/` and visible UI/WS health icons.
- UI: Share connection settings (URL/login/pass/vhost) with generator via localStorage.
- UI: Add changelog viewer (`/changelog.html`) and expose current `VERSION` and `CHANGELOG.md` via Nginx.
- UI: Load global config from `/config.json` (STOMP path/vhost, subscriptions) for ready‑to‑run defaults.
- Backend: Make topology names overridable via `PH_*` env; fix `@RabbitListener` to use property placeholders (compilation error resolved).
- Compose: Start UI immediately; other services wait for RabbitMQ health.
- Generator: Add UUID v4 polyfill for wider browser compatibility.

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
