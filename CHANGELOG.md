# Changelog

All notable changes to this project will be documented in this file.

## [0.4.21] - 2025-09-01
Timestamp: 2025-09-01T21:05:54Z

- Observability: integrate Loki and Promtail logging stack with a dedicated log-aggregator service, RabbitMQ logback appender, and Grafana dashboards; services wait for the log-aggregator and route AMQP logs to console.
- UI: refactor Hive panels into modular components with shared controls and a common status renderer; panels auto-request and render initial status, display generator rate and queue bindings/config, open controls in modals, toggle visibility, and provide tabbed system logs.
- Control: announce inbound queues and publishing topics, use explicit control topics and dedicated per-service control queues; actions request status and status blocks expand with queue topology.
- Processor: declare final queue binding and forward moderated messages to the postprocessor.
- TPS: measure throughput using elapsed intervals across Generator, Moderator, and Processor.
- Docker: add Wiremock service for NFT tests with journaling disabled.

## [0.4.20] - 2025-08-30
Timestamp: 2025-08-30T23:39:40Z

- UI: Reduce monolith icon depth to a quarter of its width for accurate proportions.

## [0.4.19] - 2025-08-30
Timestamp: 2025-08-30T23:33:46Z

- UI: Widen monolith status icon's front face for clearer orientation.

## [0.4.18] - 2025-08-30
Timestamp: 2025-08-30T23:16:22Z

- UI: Correct isometric angle of monolith status icon.

## [0.4.17] - 2025-08-30
Timestamp: 2025-08-30T23:11:47Z

- UI: Remove ground from monolith status icon and add subtle pulsing glow with hover effect.

## [0.4.16] - 2025-08-30
Timestamp: 2025-08-30T22:58:51Z

- UI: Narrow the monolith status icon and anchor it to the ground.

## [0.4.15] - 2025-08-30
Timestamp: 2025-08-30T22:50:15Z

- UI: Swap Update Status button for Space Odyssey monolith icon with hover glow.

## [0.4.14] - 2025-08-30
Timestamp: 2025-08-30T22:25:29Z

- UI: Allow separate line limits for Control and System Logs with 10–500 range.

## [0.4.13] - 2025-08-30
Timestamp: 2025-08-30T22:20:47Z

- UI: Align TPS dropdown buttons with header menu style.

## [0.4.12] - 2025-08-30
Timestamp: 2025-08-30T22:12:26Z

- UI: Style TPS metric dropdown and options with shared menu dropdown theme.

## [0.4.11] - 2025-08-30
Timestamp: 2025-08-30T22:02:56Z

- UI: Match TPS dropdown menu styling to header Menu theme without bold text.

## [0.4.10] - 2025-08-30
Timestamp: 2025-08-30T21:48:20Z

- UI: Raise chart metric dropdown above graphs so it remains clickable.

## [0.4.9] - 2025-08-30
Timestamp: 2025-08-30T21:42:34Z

- UI: Replace metric selection with menu-style dropdown using menu button theme.

## [0.4.8] - 2025-08-30
Timestamp: 2025-08-30T21:32:51Z

- UI: Style chart metric dropdown to match the menu dropdown.

## [0.4.7] - 2025-08-30
Timestamp: 2025-08-30T21:14:45Z

- UI: Align log and system log line selectors in their header rows.
- UI: Theme chart metric selector to match the menu button.

## [0.4.6] - 2025-08-30
Timestamp: 2025-08-30T21:04:56Z

- UI: Add themed line limit controls for control and system logs (default 600 lines).
- UI: Style metric selection dropdown to match cyan button theme.

## [0.4.5] - 2025-08-30
Timestamp: 2025-08-30T20:55:01Z

- UI: Restore menu to original header position and remove logo hover spin.

## [0.4.4] - 2025-08-30
Timestamp: 2025-08-30T20:43:37Z

- UI: Remove animated network background, leaving a static dark backdrop.

## [0.4.3] - 2025-08-30
Timestamp: 2025-08-30T17:06:56Z

- UI: Move Menu beneath the spacestation logo and spin the logo when hovered.

## [0.4.2] - 2025-08-30
Timestamp: 2025-08-30T16:58:33Z

- UI: Replace heavy canvas network background with lightweight SVG animation to reduce GPU usage.

## [0.4.1] - 2025-08-29
Timestamp: 2025-08-29T14:39:23Z

- Auth: Default all components to RabbitMQ `guest/guest`; remove custom user creation script (`rabbitmq/add-user.sh`) and related Compose envs.
- Compose: Stop setting `RABBITMQ_DEFAULT_USER/PASS`; services rely on defaults and `RABBITMQ_HOST` only.
- UI: Defaults and placeholders use `guest/guest` (config.json, app.js, generator page, legacy controls).
- Control-plane: Switch routing key kinds to kebab-case — `status-delta`, `status-full` (generator, moderator, processor, docs, spec, schema).
- Docs: README, control-plane rules, AsyncAPI, and JSON schema updated to kebab-case and guest defaults.
- RabbitMQ image: Do not copy `add-user.sh`. Note: `definitions.json` remains minimal; if it causes issues, consider removing it from the image.

## [0.4.0] - 2025-08-29
Timestamp: 2025-08-29T11:05:00Z

- UI: Added top-level tabs (Control, Swarm); Control hosts existing charts/logs.
- UI: New Swarm View — auto-discovers components from Control messages, draws nodes and links (Generator → Moderator → Processor → SUT), with hold time and Clear & Restart.
- UI: Moved background selector into Menu; background options accessible via HAL-style icon near WS health.
- UI: WS health icon opens connection menu; connection fields collapsed into dropdown.
- UI: README available in app; fixed image paths and transparency; served docs/ and /ui alias in Nginx.
- Docs: Simplified flow diagram with SUT and explicit data directions.

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
