# PocketHive

![PocketHive logo](pockethive-logo-readme.svg)

**PocketHive** is a portable transaction swarm: compact, composable components that let you generate, moderate, process, and test workloads with clear boundaries and durable queues.

## Stack & Ports

- `rabbitmq` (with Web-STOMP): 5672 (AMQP), 15672 (Mgmt UI), 15674 (Web-STOMP, internal only)
- `ui` (nginx static site): 8088 → serves UI, proxies WebSocket at `/ws` to RabbitMQ
- `generator`, `moderator`, `processor`: Spring Boot services using AMQP

## Quick Start

Prereqs: Docker and Docker Compose.

1) Build and start

```
docker compose up -d --build
```

2) Open the UI

- UI: http://localhost:8088
- Click "Connect". The UI connects to RabbitMQ via same-origin WebSocket `ws://localhost:8088/ws` using StompJS (same client as the built‑in Generator).

3) RabbitMQ Management (optional)

- http://localhost:15672 (guest / guest)
- Web-STOMP plugin is enabled in the RabbitMQ image.

## WebSocket Proxy (UI ←→ RabbitMQ)

- The UI does not connect directly to `localhost:15674`. Instead, nginx proxies `/ws` → `rabbitmq:15674/ws`.
- This avoids cross-origin/origin/CORS issues and works when the UI is accessed from a remote host.

Relevant files:

- `ui/nginx.conf` — reverse proxy for `/ws` and `/healthz`
- `docker-compose.yml` — mounts nginx config and exposes port 8088; adds healthcheck for UI
- `ui/assets/js/app.js` — defaults WS URL to same-origin `/ws`, includes system logs and health ping, and uses StompJS for Events handling.
- Backgrounds:
  - `ui/swarm/css/pockethive-fx.css` + `ui/swarm/js/fx.core.js` — new default FX background
  - `ui/swarm/css/pockethive-bees.css` + `ui/swarm/js/bees.core.js` — Swarm Bees background with options
  - `ui/swarm/js/swarm-ui.js` — unified modal to configure FX/Swarm (open via “Swarm Options”)

## Healthchecks

- `rabbitmq`: built-in healthcheck via `rabbitmq-diagnostics ping`
- `ui`: HTTP `GET /healthz` returns `200 ok` (nginx). Compose healthcheck pings it every 10s.

Manual checks:

- UI health: `curl -s http://localhost:8088/healthz` → `ok`
- Mgmt UI: visit `http://localhost:15672`

## UI Panels

- Event Log: shows application events/messages from STOMP subscriptions.
- System Logs: shows system and user actions:
  - Connect/Disconnect clicks, edits of URL/username/password (password length only)
  - WebSocket lifecycle (connecting URL, CONNECTED, subscriptions, errors, close)
  - UI health transitions based on `/healthz`
- Browser Generator: standalone page at `/generator/` to publish messages via Web‑STOMP with configurable rate and payload.

## Troubleshooting

- WebSocket error in UI:
  - Ensure UI health shows "healthy" (see System Logs) and `/healthz` returns `ok`.
  - Verify RabbitMQ is healthy and Web-STOMP is enabled (Mgmt UI → Plugins).
  - Check browser devtools → Network → WS for the `/ws` handshake (should be 101 Switching Protocols).
  - If serving the UI over HTTPS, the app will use `wss://…/ws` automatically; ensure any reverse proxy forwards upgrades.
  - Avoid manually pointing to `ws://localhost:15674/ws` unless you expose that port and handle origins.

- Authentication / `guest` user:
  - RabbitMQ blocks remote logins for the built‑in `guest` user by default. If you access the UI from a remote host, either use the UI’s same‑origin `/ws` proxy (recommended) or create a non‑guest user.
  - Easiest: set defaults in Compose for RabbitMQ:

    ```yaml
    rabbitmq:
      environment:
        RABBITMQ_DEFAULT_USER: phuser
        RABBITMQ_DEFAULT_PASS: phpass
    ```

  - Then set the same credentials in services or via env:

    ```yaml
    generator:
      environment:
        RABBITMQ_USER: phuser
        RABBITMQ_PASS: phpass
    moderator:
      environment:
        RABBITMQ_USER: phuser
        RABBITMQ_PASS: phpass
    processor:
      environment:
        RABBITMQ_USER: phuser
        RABBITMQ_PASS: phpass
    ```

  - Alternative (dev only): relax `guest` loopback restriction via `rabbitmq.conf` mount.

- Cannot access UI: ensure port 8088 is free or adjust the mapping in `docker-compose.yml`.

## Development Notes

- Static UI is served from `ui/`. Changes to HTML/CSS/JS are picked up on reload.
- Nginx config lives in `ui/nginx.conf` and is mounted into the `ui` container. After changing it, restart just the UI:

```
docker compose up -d --build ui
```

- Services use environment `RABBITMQ_HOST=rabbitmq` inside the Compose network.


---

PocketHive · portable transaction · swarm
