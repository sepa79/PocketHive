# Usage Guide

## WebSocket Proxy (UI ↔ RabbitMQ)
- The UI does not connect directly to `localhost:15674`. Instead, nginx proxies `/ws` to `rabbitmq:15674/ws` to avoid CORS issues.
- Accessing the UI from a remote host is supported through this same-origin proxy.
- When serving over HTTPS the app automatically uses `wss://`.

## Healthchecks
- `rabbitmq`: built-in healthcheck via `rabbitmq-diagnostics ping`.
- `ui`: HTTP `GET /healthz` returns `200 ok` (nginx); Compose healthcheck pings it every 10s.

When the stack starts only the Orchestrator (Queen) is running. New swarms are created and started from the Hive view as needed.

## PocketHive MCP and VS Code

### Local MCP and VS Code quick start

This is the canonical first-time local setup. It starts the Java PocketHive MCP
inside the normal PocketHive stack, installs the PocketHive VS Code companion,
and connects both through the supported public ingress. The companion is a
PocketHive user interface; it is separate from VS Code's built-in MCP agent
configuration.

#### 1. Check requirements

Install:

- Docker with Docker Compose;
- Java 21;
- `curl` and a Bash-compatible shell; and
- VS Code 1.85 or later, Node.js, and npm when building the companion from
  source.

The `code` command is optional. Without it, install the generated VSIX through
VS Code's **Extensions: Install from VSIX...** command.

#### 2. Build and start PocketHive

From the repository root, run:

```bash
./build-hive.sh
```

This full build includes `auth-service` and the Java `pockethive-mcp` service.
It builds their JARs and images and deploys them with the rest of the local
Compose stack. Use `--quick` only for a later development rebuild where
skipping the Maven test phase is intentional.

Verify the supported public ingress, not a service container port:

```bash
curl -fsS http://localhost:8088/healthz
curl -fsS http://localhost:8088/.well-known/oauth-protected-resource
```

The first command returns `ok`. The second returns OAuth protected-resource
metadata whose `resource` is `http://localhost:8088/mcp`. A protected `/mcp`
request may return an authentication challenge before sign-in; that does not
mean the MCP is unavailable.

#### 3. Build and install the VS Code companion

From the repository root, run:

```bash
cd vscode-pockethive
./init.sh --install
```

The script installs the locked npm dependencies, builds and verifies the
extension, creates `pockethive-vscode-<version>.vsix`, and force-installs that
package when the `code` command is available. Force installation replaces an
older installed build even when it has the same version.

If `code` is unavailable, run `./init.sh --package`, then use **Extensions:
Install from VSIX...** and select the generated file. After either route, run
**Developer: Reload Window** in VS Code so the new extension host is active.

#### 4. Connect the companion

1. Open the PocketHive hexagon in the VS Code Activity Bar.
2. Add an environment named `Local PocketHive` with the exact MCP URL
   `http://localhost:8088/mcp`.
3. Choose **Connect**. Complete the PocketHive browser sign-in and consent.
   The default local DEV administrator username is `local-admin`.
4. Confirm that **Authenticated** and **Connection test** both succeed.
5. Choose **Save & open**.

The workspace should show the Hive, Buzz, Journal, Scenarios, and Debug tabs,
with the local environment reported as connected. Profiles are stored locally,
while OAuth material is stored through VS Code Secret Storage.

#### 5. Configure an MCP agent client when required

The companion profile above does not configure VS Code/Copilot, Amazon Q,
Codex, Cursor, or Windsurf as agent clients. Configure each required client
separately with the same exact Streamable HTTP URL. Ready repository examples
are available in `.vscode/mcp.json`, `.amazonq/mcp.json.dist`, `mcp.json`,
`.cursor/mcp.json`, and `.windsurf/mcp.json`.

Use native Streamable HTTP and OAuth support. Do not configure a Java service
port, the removed Node server, stdio, an npm proxy, or a fallback endpoint. See
the [PocketHive MCP connection contract](mcp/README.md#connect) for client
configuration and authentication behaviour.

#### Troubleshooting

- If `healthz` fails, inspect `docker compose ps` and rerun `./build-hive.sh`;
  do not switch the client to a backend service port.
- If the PocketHive Activity Bar still shows an older interface, reinstall with
  `./init.sh --install` and run **Developer: Reload Window**.
- If connection validation reports a loopback error, use the exact local URL
  `http://localhost:8088/mcp`; do not use an unspecified host or a container
  hostname.
- If authentication has expired or was declined, use the explicit **Sign in**
  action. Ordinary tab and swarm commands must not open a separate browser
  authorization flow.
- If an agent client retained an OAuth registration across a local restart, it
  can re-authorize with that same client ID. Do not clear or recreate the client
  configuration merely because Auth Service restarted; active dynamic client
  registrations are retained in the `pockethive-auth-state` volume.
- The first upgrade from the earlier in-memory registry cannot reconstruct a
  client ID that was issued before durable state existed. Remove and re-add that
  MCP server once so the client performs dynamic registration; later Auth
  Service restarts retain the replacement registration.

Scenario Bundle source remains in Git. From the Scenarios tab select a committed
bundle directory; the extension uploads the exact committed regular files for
Scenario Manager validation, then requires an explicit `CREATE` or `REPLACE`.
See `docs/mcp/README.md` for the agent contract and publication flow.

## Docker Swarm mode (manager-only)
- Swarm mode requires a **Docker Swarm manager**. Workers cannot create services, so the Orchestrator must connect to a manager node.
- If you deploy the Orchestrator inside Swarm, schedule it on a manager node and mount the manager’s Docker socket.
- If you target a remote engine, point `DOCKER_HOST` at a manager endpoint that has Swarm control available.

## Journal (Swarm vs Hive)

PocketHive exposes two related timelines:

- **Swarm journal**: events tied to a single swarm run.
- **Hive journal**: a Hive-level timeline (Orchestrator) that can be filtered by `swarmId`/`runId`.

### Storage backends (`POCKETHIVE_JOURNAL_SINK`)

The journal backend is selected via `pockethive.journal.sink` (env: `POCKETHIVE_JOURNAL_SINK`) on the **orchestrator** container. The Orchestrator propagates this value to the swarm-controller containers it launches.

- `postgres` (recommended; default in `docker-compose.yml`)
  - Enables paginated APIs + runs + pin + Hive journal.
  - Requires Postgres connection (`SPRING_DATASOURCE_*`) to be configured.
- `file` (explicit lightweight mode)
  - Disables Postgres-only APIs (they return `501 Not Implemented`).
  - Swarm journal is read from `journal.ndjson` under the runtime root (see below).

### Runtime filesystem roots

File-backed swarm journals live under:

`$POCKETHIVE_RUNTIME_FILESYSTEM_ROOT/<swarmId>/<runId>/journal.ndjson`

In the default stack this is a bind mount:

- Host: `/opt/pockethive/scenarios-runtime`
- Containers: `/app/scenarios-runtime`

Filesystem controller startup uses the same bind mount with two explicit settings whose meanings do not overlap:

- `POCKETHIVE_RUNTIME_FILESYSTEM_ROOT` is the absolute local path used by every process performing file IO (`/app/scenarios-runtime` in the default stack);
- `POCKETHIVE_SCENARIOS_RUNTIME_ROOT` is only the absolute host source passed to the runtime adapter when it creates a bind mount.

PocketHive MCP does not read this filesystem. Runtime manifest diagnostics go through the Orchestrator runtime-debug API, so storage and parsing remain under one owner.

There are no separate startup read/write root settings. The Orchestrator, Scenario Manager and Controller all use the shared runtime-filesystem contract and resolver. A controller receives the exact artifact path and SHA-256; it does not fall back to RabbitMQ or another file. Isolated test harnesses set `POCKETHIVE_RUNTIME_FILESYSTEM_ROOT` to their absolute temporary root.

### How to enable file mode locally

In `docker-compose.yml` under `orchestrator.environment`, set:

- `POCKETHIVE_JOURNAL_SINK: file`

Then rebuild/redeploy the stack via `./build-hive.sh` (or `docker compose down && docker compose up -d`).

### UI behavior

- The Hive UI’s mini-journal on a swarm card can switch between:
  - **Swarm**: per-swarm journal entries
  - **Hive**: Hive journal filtered by `swarmId`
- Paginated Swarm and Hive journal views require the `postgres` backend.
- In explicit `file` mode, Postgres-only requests return `501 Not Implemented`;
  the UI reports that error and does not switch to another journal API.

## Grafana (metrics + journal annotations)

- Grafana UI: `http://localhost:8088/grafana/` (user/pass: `pockethive` / `pockethive`).
- Dashboards:
  - `PocketHive Journal` (`uid=pockethive-journal`) — Postgres-backed timeline + annotations (WARN/ERROR, lifecycle outcomes, journal backpressure).
  - `Pipeline observability` (`uid=pockethive-pipeline`) — ClickHouse service metrics from `ph_metrics_samples` with Journal annotations overlaid.
  - `PocketHive Pipeline Deep Dive` (`uid=pockethive-pipeline-detailed`) — ClickHouse runtime metrics and transaction drill-downs.
  - ClickHouse transaction dashboards use `ph_tx_outcome_v2`.

## ClickHouse tx_outcome v1 -> v2 migration

Fresh ClickHouse volumes create only `ph_tx_outcome_v2`. Existing volumes may still contain the legacy `ph_tx_outcome_v1` table.

The local ClickHouse service uses a small entrypoint wrapper that runs the official ClickHouse entrypoint, waits for ClickHouse readiness, and then runs the v1 -> v2 migration inside the same container. On startup it:

- exits successfully when `ph_tx_outcome_v1` does not exist
- migrates all `ph_tx_outcome_v1` rows into `ph_tx_outcome_v2` when v1 exists and v2 is empty
- drops `ph_tx_outcome_v1` after a successful full migration
- fails the ClickHouse container startup instead of appending into a non-empty v2 table, to avoid duplicate historical rows

For manual migration or recovery, use the local wrapper:

```bash
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh
```

For date-bounded migration:

```bash
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --from 2026-02-01 --to 2026-02-22
```

For a full rebuild of v2 from v1:

```bash
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --truncate-v2
```

To manually drop v1 after a successful full-table migration:

```bash
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --drop-source-after-migration
```

The wrapper copies and runs the underlying migration script inside the running `clickhouse` docker compose service:

- script: `clickhouse/migrate-tx-outcome-v1-to-v2.sh`
- expected runtime: inside the running ClickHouse container
- required tooling: `bash` and `clickhouse-client` only

Manual operator flow:

1. Upload the script into a path already mounted into the ClickHouse container.
2. Open a shell in the running ClickHouse container.
   This can be done either with `docker exec` on the node that hosts the task, or via Portainer `Exec Console`.
3. Run the script from inside that shell.

Examples:

```bash
bash /mounted/path/migrate-tx-outcome-v1-to-v2.sh
```

```bash
bash /mounted/path/migrate-tx-outcome-v1-to-v2.sh --from 2026-02-01 --to 2026-02-22
```

```bash
bash /mounted/path/migrate-tx-outcome-v1-to-v2.sh --truncate-v2
```

Operational behavior:

- the script checks that `ph_tx_outcome_v1` exists
- it creates `ph_tx_outcome_v2` if missing, using the repo v2 schema
- it fails if `ph_tx_outcome_v2` is already non-empty, unless `--truncate-v2` is passed explicitly
- it migrates day by day
- after each day it compares `v1` and `v2` row counts and exits non-zero on mismatch

## Scenario Manager API
- nginx proxies `/scenario-manager/*` to the Scenario Manager service.
- The service also exposes port `1081` on the host for direct API access.
- Ensure the `scenario-manager` container is running and healthy before calling it.

### Scenario image tags
- Scenario images must include a tag or digest unless scenario-manager is configured with `POCKETHIVE_IMAGES_DEFAULT_TAG` (see `docker-compose.yml`).
- When `POCKETHIVE_IMAGES_DEFAULT_TAG` is configured, scenario-manager applies that tag to image references without digests, including references that already include a tag. Digest-pinned image references are preserved.
- Capability manifest lookup uses the canonical image name without registry, namespace, tag, or digest; runtime image references are not rewritten.

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
  2. Confirm the guard queue is exposed via ClickHouse metrics (`ph_swarm_queue_depth` in `ph_metrics_samples`, filtered by `swarmId` and `labels['queue']`) and logs report the guard state (`io.pockethive.swarmcontroller.guard` logger).
  3. Monitor guard gauges (`ph_swarm_buffer_guard_depth`, `*_target`, `*_rate_per_sec`, `*_state`) in Grafana.
- **Tuning tips:** Use `targetDepth` as the desired steady level, keep `minDepth`/`maxDepth` wide enough to avoid thrash, and start with adjustment percentages between 5‑20%. Set `backpressure.queueAlias` to the queue immediately downstream of the guard if you want automatic slowdown when processors fall behind.

#### Guard configuration cheat‑sheet

| Field | Description | Suggested Values |
|-------|-------------|------------------|
| `queueAlias` | Queue suffix to monitor (resolves via `traffic.queuePrefix`) | e.g. `moderator-a-out` |
| `targetDepth` | Desired steady depth | Pick a midpoint the queue should hover around |
| `minDepth` / `maxDepth` | Hysteresis bounds; guard only clamps when average depth crosses these | ~±20–30% around the target |
| `samplePeriod` | How often the controller samples Rabbit (duration string) | 3–5 s for most swarms |
| `movingAverageWindow` | Number of samples to average | 5–10 to smooth noise |
| `adjust.maxIncreasePct` / `maxDecreasePct` | Max percentage per decision when filling/draining | Start with 5–15% |
| `adjust.minRatePerSec` / `maxRatePerSec` | Hard bounds on the generator/moderator rate | Match the safe operating range for the producer |
| `prefill.enabled` | When `true`, temporarily raises the target to pre-load the queue | `false` unless you need to warm up before a spike |
| `prefill.lookahead` | Duration to stay in prefill mode | e.g. `30s`, `2m`; after this the guard returns to steady mode |
| `prefill.liftPct` | Percentage to bump the target while prefill is active | 10–30% |
| `backpressure.queueAlias` | Downstream queue to watch for high watermark events | `proc-out`, etc. |
| `backpressure.highDepth` / `recoveryDepth` | Depth thresholds that enter/exit backpressure mode | Pick based on downstream capacity |
| `backpressure.moderatorReductionPct` | How much to trim moderators when backpressure fires | 15–30% |

> **Prefill usage:** When `prefill.enabled = true`, the guard enters a temporary **prefill** state for `lookahead`. During that window it raises the target depth by `liftPct` so the queue preloads ahead of a known spike. Once the lookahead duration expires the target snaps back to its baseline value.

### Scenario and swarm API
- Create swarms via the Orchestrator REST API: `POST /api/swarms/{swarmId}/create` with JSON such as:

  ```json
  {
    "templateId": "rest",
    "idempotencyKey": "create-rest-001",
    "autoPullImages": false,
    "sutId": null,
    "variablesProfileId": null,
    "networkMode": "DIRECT",
    "networkProfileId": null
  }
  ```

  The Orchestrator fetches the requested template from `scenario-manager-service`, expands it into a `SwarmPlan`, persists a checksummed startup artifact, boots a Swarm Controller runtime with the artifact reference, and tracks progress internally—no plan payload is sent through RabbitMQ.
- Subscribe to control-plane outcomes and alerts to follow the lifecycle:
  - `event.outcome.swarm-create.<swarmId>.orchestrator.<orchestratorInstance>` — emitted by the Orchestrator after the controller handshake completes.
  - `event.outcome.swarm-start.<swarmId>.orchestrator.<orchestratorInstance>` — the sole public terminal start outcome, emitted only after the Controller's correlated convergence result is accepted; `data.status` is `Succeeded`, `Rejected`, `Failed`, or `TimedOut`.
  - `event.outcome.swarm-stop.<swarmId>.orchestrator.<orchestratorInstance>` and `event.outcome.swarm-remove.<swarmId>.orchestrator.<orchestratorInstance>` follow the same ownership rule.
  - `event.alert.{type}.<swarmId>.*.*` — emitted for runtime/IO failures.
- Start execution with `POST /api/swarms/{swarmId}/start` (body: `{ "idempotencyKey": "start-rest-001" }`). The response includes both the Orchestrator outcome topic and an `operationUrl`; polling the operation URL is the broker-independent way to observe terminal state.

### Worker configuration overrides
- Scenario definitions provide per-role overrides directly inside each bee's `config` map. The Scenario Manager passes those maps into the `SwarmPlan.bees[*].config` payload and the Swarm Controller immediately broadcasts them as `config-update` signals during bootstrap. No environment variables are used for logical scenario settings.
- The `WorkItem` history policy is also configurable per worker via `config.historyPolicy` (values: `FULL`, `LATEST_ONLY`, `DISABLED`); it defaults to `FULL` when omitted. In all modes the current payload is treated as the last recorded step:
  - `FULL` – every logical stage (scheduler seed, templating, worker onMessage, processor) appends a new step; history is preserved end-to-end.
  - `LATEST_ONLY` – previous steps are collapsed so only the latest step remains (reindexed to `0`).
  - `DISABLED` – history snapshots are dropped after each hop, but the current step is still retained as a single baseline.
- Example snippet:
  ```yaml
  config:
    historyPolicy: FULL
    ratePerSec: 15
    message:
      path: /api/guarded
      body: warmup
  ```
- Service defaults declared under `pockethive.worker.*` remain useful for local development, but once a swarm runs under the controller the scenario-supplied config is the single source of truth.

## Troubleshooting
- **WebSocket errors**: ensure UI health is `ok`, RabbitMQ is running and Web-STOMP is enabled; check browser network logs for `/ws`.
- **Authentication**: RabbitMQ blocks remote logins for the built-in `guest` user; use the proxy or create a non-guest user.
- **UI access**: ensure port `8088` is free or adjust mapping in `docker-compose.yml`.
- **WSL2/Docker restarts**: if services suddenly time out talking to each other after a Docker restart, rebuild the compose network: `docker compose down --remove-orphans && docker compose up -d`.
- **WSL2 flakiness / “is it networking or the app?”**: run `tools/diag/docker-triage.sh` to collect container status, logs, and basic inter-container connectivity checks.
