# PocketHive usage reference

This page collects operational details that do not belong in the first-run
guides. Start with the [PocketHive application guide](guides/ui/application-guide.md)
or [local quickstart](guides/onboarding/quickstart-15min.md).

## Deployment boundary

- A versioned Docker Compose package is the intended evaluation/local
  distribution, but the `v0.15.35` package has not passed its clean-host gate.
- HiveForge is the intended managed, production-like path, but its current
  actions prepare and validate a stack without executing deploy/update/remove.
- `./build-hive.sh` is the current working source-development entry point.

See [deployment paths](guides/operators/deployment.md) before starting an
environment.

### External deployment package

The established external Compose/Portainer procedure remains:

1. Create the package with `./package-deployment.sh` or
   `package-deployment.bat`.
2. Copy the generated `pockethive-deployment-<version>.tar.gz` or
   `pockethive-deployment-<version>.zip` to the target.
3. Extract it and enter the `pockethive` directory.
4. Run `docker compose up -d` or import the bundled `docker-compose.yml` into
   Portainer.

These commands describe the intended package workflow; the current archive
still requires the clean-host qualification described above.

### Managed deployment with HiveForge

HiveForge is the recommended managed, production-like path. It uses an
approved PocketHive git ref plus prebuilt registry-qualified images rather
than the Compose archive. Configure the exact registry/version inputs, select
`swarm-reduced` or `swarm-full`, and run the approved action through HiveForge.

Current `v0.15.35` actions render and validate the Docker Swarm stack but do
not yet execute deploy/update/remove. See
[HiveForge integration](HIVEFORGE.md) for profiles, inputs, and completion
gates.

## Official ingress

The default local source stack exposes the PocketHive application at:

```text
http://localhost:8088
```

Use this origin for customer and verification traffic:

| Route | Purpose |
| --- | --- |
| `/` | PocketHive application |
| `/docs/` | Documentation bundled with this UI build |
| `/healthz` | UI ingress health |
| `/orchestrator/*` | Orchestrator API proxy |
| `/scenario-manager/*` | Scenario Manager API proxy |
| `/auth/*` | Auth-service API proxy |
| `/grafana/` | Grafana operator application |
| `/rabbitmq/` | RabbitMQ advanced diagnostics |
| `/redis/` | Redis advanced diagnostics |
| `/wiremock/` | Local WireMock diagnostics |
| `/ws` | Same-origin RabbitMQ Web-STOMP proxy |

Browsers and customer tests must not replace these routes with direct backend
container ports. When the ingress uses HTTPS, the application uses `wss://` for
WebSocket traffic.

Health check:

```bash
curl -fsS http://localhost:8088/healthz
```

## Application workflow

When the stable platform starts, no customer swarm is running.

1. Use **Scenarios** to inspect or edit a validated scenario bundle.
2. Use **Hive → New swarm** to select a scenario, SUT, and network mode.
3. Wait for the projected `READY` state and a fresh controller Snapshot.
4. Start, wait for `RUNNING`, require a fresh Snapshot, and use **Scenario** to
   confirm the intended workers are enabled and live.
5. Observe Snapshot, topology, Journal, and Grafana as needed.
6. Stop, wait for `STOPPED`, require a fresh Snapshot, and use **Scenario** to
   confirm those workers are disabled and still live.
7. Remove, require the correlated `swarm-remove` outcome with status `Removed`
   in Journal, and then confirm that the swarm disappears from Hive.

An action response proves request acceptance, not completion. `v0.15.35` also
has a known readiness-ownership gap: projected `READY` can briefly precede the
controller's stricter template/plan/readiness gate. If start returns
`NotReady`, wait for fresh state rather than repeating it immediately.

See [swarm lifecycle](guides/operators/swarm-lifecycle.md) for all states.

## Current screens

| Screen | Use |
| --- | --- |
| Home | Customer task entry points |
| Scenarios | Scenario bundle workspace, validation, and upload |
| Hive | Swarm creation, lifecycle, Snapshot, Scenario, Network, and Inspector |
| Swarm topology | Scenario graph mapped to current runtime workers |
| Journal | Swarm and Hive control/lifecycle history |
| Proxy | Shared proxy stack, profiles, and per-swarm network support |
| Buzz | Advanced recent control-plane inspection |
| Connectivity | UI/service/control-plane connection diagnosis |
| Users | Auth administration for users with auth `ADMIN` |
| Help | Version-matched task guide hub |

Legacy Bees/Nectar panels and the archived UI are not part of UI v2.

## Journal

PocketHive exposes:

- a **Swarm Journal** for one swarm/run;
- a **Hive Journal** for Orchestrator-level history, filterable by swarm/run.

`POCKETHIVE_JOURNAL_SINK` selects the backend:

| Value | Behavior |
| --- | --- |
| `postgres` | Default Compose mode; paginated Swarm/Hive APIs, runs, and pin support. |
| `file` | Explicit lightweight mode; Postgres-only APIs return `501`, with per-run `journal.ndjson` under the configured runtime root. |

The Orchestrator propagates the chosen sink to dynamic Swarm Controllers.
There is no silent API fallback from Postgres to file mode.

Raw Journal data can contain configuration, endpoints, identifiers, and
payloads. Keep it collapsed unless needed and sanitize it before sharing.

## Metrics and Grafana

PocketHive product metrics are stored in ClickHouse and displayed through
Grafana at `/grafana/`. The local Compose-only default login is
`pockethive` / `pockethive`; it is not a production credential.

Provisioned dashboards include:

- PocketHive Journal;
- Pipeline observability;
- PocketHive Pipeline Deep Dive.

Use [observability and troubleshooting](guides/operators/observability-troubleshooting.md)
for evidence order. Grafana trends do not replace lifecycle outcome and status
evidence.

Existing ClickHouse volumes may require the documented `tx_outcome` migration.
Read [Upgrading PocketHive](UPGRADING.md) before changing versions; do not run a
destructive migration from an old standalone instruction.

## Scenario Manager behavior

Scenario Manager owns scenario workspaces, static validation, capability
metadata, SUT/network metadata, and validated runtime preparation. The
Orchestrator performs admission and run-specific resolution before launch.

Scenario images require an explicit tag or digest unless the environment sets
`POCKETHIVE_IMAGES_DEFAULT_TAG`. Digest-pinned references are preserved.
Capability lookup normalizes the image name without registry, namespace, tag,
or digest.

Use the same-origin Scenario Manager route when an API check is required:

```bash
curl -fsS http://localhost:8088/scenario-manager/scenarios
```

For guarded file authoring and dry-run validation, use
[PocketHive MCP](guides/integrations/pockethive-mcp-and-bundles.md).

## Worker configuration

Scenario `template.bees[].config` is the logical runtime configuration source.
The Swarm Controller injects IO and bootstrap config when it materializes
workers. A targeted live patch is routed by the Orchestrator directly to the
selected worker.

Live updates are limited:

- only capability entries explicitly marked `liveMutable: true` can be offered
  as normal live edits;
- unsafe IO wiring, adapters, endpoints, protocols, credentials, and routes
  require rematerialization;
- the Redis dataset list-name exception is allowed only for an already stopped,
  single-source worker under the documented runtime guard.

See [technical architecture](ARCHITECTURE.md) for the exact config propagation
paths and [worker capabilities](architecture/workerCapabilities.md) for the UI
contract.

## Queue guard scenarios

The controller feature flag
`POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_FEATURES_BUFFER_GUARD_ENABLED`
enables the feature globally. A scenario must still set
`trafficPolicy.bufferGuard.enabled: true`.

Operational guidance:

- choose `queueAlias` from the scenario's queue suffixes;
- start with a 3–5 second sample period and a 5–10 sample moving average;
- set min/max depths wide enough to avoid oscillation;
- keep rate adjustments bounded to the SUT's safe operating range;
- use downstream backpressure thresholds only when the downstream capacity is
  understood;
- monitor guard metrics and Journal evidence through Grafana and PocketHive,
  not by mutating controller-owned RabbitMQ topology.

## Troubleshooting

- UI is unreachable: check port `8088`, then `/healthz`.
- Scenario is missing: check Scenario Manager through the ingress and validate
  the bundle.
- Creation/start/stop remains transitional: open the selected swarm's Journal
  and confirm Snapshot freshness.
- Action is unavailable: check lifecycle state and the user's grant scope.
- Work does not reach the SUT: verify scenario topology, worker state, SUT
  binding, then Proxy or a focused debug tap.
- WebSocket state is stale: use Connectivity and confirm `/ws` through the same
  origin.

For lower-level source-development diagnostics, repository tools under
`tools/diag/` and `tools/mcp-orchestrator-debug/` are contributor surfaces, not
the normal customer workflow.
