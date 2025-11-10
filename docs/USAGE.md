# Usage Guide

## WebSocket Proxy (UI ↔ RabbitMQ)
- The UI does not connect directly to `localhost:15674`. Instead, nginx proxies `/ws` to `rabbitmq:15674/ws` to avoid CORS issues.
- Accessing the UI from a remote host is supported through this same-origin proxy.
- When serving over HTTPS the app automatically uses `wss://`.

## Healthchecks
- `rabbitmq`: built-in healthcheck via `rabbitmq-diagnostics ping`.
- `ui`: HTTP `GET /healthz` returns `200 ok` (nginx); Compose healthcheck pings it every 10s.

When the stack starts only the Orchestrator (Queen) is running. New swarms are created and started from the Hive view as needed.

## Scenario Manager API
- nginx proxies `/scenario-manager/*` to the Scenario Manager service.
- The service also exposes port `1081` on the host for direct API access.
- Ensure the `scenario-manager` container is running and healthy before calling it.

Example listings:

```bash
curl -s http://localhost:1081/scenarios
curl -s http://localhost:8088/scenario-manager/scenarios
```

Manual checks:
- UI health: `curl -s http://localhost:8088/healthz` → `ok`.
- RabbitMQ management UI: `http://localhost:15672`.

## UI Panels
- **Backgrounds**: selector for Bees / Network / Old; only the active background renders.
- **Buzz**: logs STOMP traffic with IN, OUT and Other views and lists current binds and URLs in a Config tab.
- **System Logs**: shows system and user actions such as connect/disconnect and edits of credentials.
- **Hive**: lists live components grouped by swarm with per-swarm start/stop controls and an interactive topology tab.
- **Nectar**: metric dropdown (TPS, latency, hops) and points input to adjust chart history.

## UI Controls
- **View tabs** switch between Hive, Buzz and Nectar panels.
- **Menu (☰)** links to README, Buzz bindings, changelog and API docs.
- **WebSocket eye** connects or disconnects from RabbitMQ.
- **Monolith button** broadcasts a global `status-request` signal.
- **Buzz view** displays IN, OUT and Other logs with a Config tab and Topic Sniffer.
- **Hive view** provides per-swarm start/stop controls, topology, and settings drawers with confirmable config updates.

## Swarm launch
- Open the Hive view and choose **Create Swarm**.
- Enter a swarm ID and select a scenario. The modal fetches scenario summaries from
  `/scenario-manager/scenarios` and loads the chosen scenario's JSON from
  `/scenario-manager/scenarios/{id}`.
- Submit to create the swarm, then start it with the play button next to its entry.

### Queue Guard scenarios
- **Enablement:** The controller feature flag `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_FEATURES_BUFFER_GUARD_ENABLED`
  defaults to `true`, providing a global kill switch. Guard logic still runs only when the selected scenario sets
  `trafficPolicy.bufferGuard.enabled: true`, so non-guarded swarms behave as before even with the flag on.
- **Reference templates:**
  - `local-rest-two-moderators` – deterministic WireMock delay, guard watches the `moderator-a-out` queue to keep a steady bracket.
  - `local-rest-two-moderators-randomized` – generator targets `/api/guarded-random`, WireMock cycles through multiple latency slots, and guard reacts to bursty downstream pressure.
- **Launch checklist:**
  1. Create/start a swarm from one of the guard scenarios (UI modal or `/api/swarms/{id}/create` + `/start`).
  2. Confirm the guard queue is exposed via metrics (`ph_swarm_queue_depth{queue="ph.<swarm>.moderator-a-out"}`) and logs report the guard state (`io.pockethive.swarmcontroller.guard` logger).
  3. Monitor guard gauges (`ph_swarm_buffer_guard_depth`, `*_target`, `*_rate_per_sec`, `*_state`) in Grafana or scrape Prometheus directly.
- **Tuning tips:** Use `targetDepth` as the desired steady level, keep `minDepth`/`maxDepth` wide enough to avoid thrash, and start with adjustment percentages between 5‑20%. Set `backpressure.queueAlias` to the queue immediately downstream of the guard if you want automatic slowdown when processors fall behind.

### Scenario and swarm API
- Create swarms via the Orchestrator REST API: `POST /api/swarms/{swarmId}/create` with JSON such as:

  ```json
  {
    "templateId": "rest",
    "idempotencyKey": "create-rest-001"
  }
  ```

  The Orchestrator fetches the requested template from `scenario-manager-service`, expands it into a `SwarmPlan`, boots a Swarm Controller runtime, and tracks progress internally—no `sig.swarm-create` message is published by clients.
- Subscribe to the control-plane confirmations to follow the lifecycle:
  - `ev.ready.swarm-create.<swarmId>.orchestrator.ALL` — emitted by the Orchestrator after the controller handshake completes.
  - `ev.ready.swarm-template.<swarmId>.swarm-controller.<controllerInstance>` — emitted by the Swarm Controller once the plan is applied and bees are provisioned (idle by default).
  - `ev.ready.swarm-start.<swarmId>.swarm-controller.<controllerInstance>` — emitted after issuing a start; indicates workloads are enabled and running.
- Start execution with `POST /api/swarms/{swarmId}/start` (body: `{ "idempotencyKey": "start-rest-001" }`). The Orchestrator sends `sig.swarm-start.<swarmId>.swarm-controller.ALL` on your behalf and you can reuse the same event subscriptions above to detect readiness or handle the matching `ev.error.*` topics if something fails.

## Troubleshooting
- **WebSocket errors**: ensure UI health is `ok`, RabbitMQ is running and Web-STOMP is enabled; check browser network logs for `/ws`.
- **Authentication**: RabbitMQ blocks remote logins for the built-in `guest` user; use the proxy or create a non-guest user.
- **UI access**: ensure port `8088` is free or adjust mapping in `docker-compose.yml`.
