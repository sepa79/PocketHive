# Changelog

All notable changes to this project will be documented in this file.

## [0.3.1] - 2025-08-29
Timestamp: 2025-08-29T10:50:00Z

- UI: README available in Menu (`/readme.html`) with in‑app markdown render.
- UI: Fix README images (logo + flow) — copy `docs/` to image, path rewriting, and Nginx alias for `/ui/*`.
- UI: Remove white backgrounds under images; transparent flow diagram background for dark UI.
- UI: Background selector moved under Menu; add HAL‑style background options icon next to WS health.
- UI: WS health icon opens connection menu.
- Docs: Simplified flow diagram (components, queues, direction, SUT) and README tweaks.

## [0.3.0] - 2025-08-29
Timestamp: 2025-08-29T10:24:00Z

- UI: New "Network" background and 3‑mode selector (Bees / Network / Old).
- UI: Auto‑apply controls for Network; performance pause for inactive backgrounds.
- UI: HAL status eyes with center‑pulse animations and colorblind‑friendly contrast.
- UI: Unified typography; improved dark dropdown styling and focus visibility.
- UI: Removed duplicate top bars; simplified background UI logic.
- UI: Nginx WS resolver fix so UI starts without RabbitMQ present.
- Docs: Updated README with new visuals and notes.

## [0.2.3] - 2025-08-29
Timestamp: 2025-08-29T02:20:00Z

- UI: Refactor Events connection to use StompJS (same as Generator) instead of a custom STOMP client.
- UI: Improve diagnostics around connect/handshake and socket lifecycle; clearer syslog entries.
- UI: Normalize CRLF handling in STOMP frame parsing (pre-refactor) to avoid missed CONNECTED frames.
- Docs: Update README with authentication notes (RabbitMQ `guest` remote restriction), recommended non‑guest user setup, and troubleshooting.

## [0.2.2] - 2025-08-29
Timestamp: 2025-08-29T01:24:02Z

- Docs: Add AsyncAPI spec (`ui/spec/asyncapi.yaml`) describing control/traffic contracts.
- UI: Add API Docs page (`/docs.html`) and group header actions into a ☰ Menu dropdown.
- UI: Fix changelog page showing duplicated header (remove extra static H1).
- Config: Align exchanges across UI/backends — `ph.hive` (traffic), `ph.control` (control).

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
