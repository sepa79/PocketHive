# PocketHive usage reference

This page collects operational details that do not belong in the first-run
guides. Start with the [PocketHive application guide](guides/ui/application-guide.md)
or [local quickstart](guides/onboarding/quickstart-15min.md).

## Deployment boundary

- A versioned Docker Compose package is the intended evaluation/local
  distribution, but the package for the exact tested ref has not passed its
  clean-host gate unless its qualification evidence says otherwise.
- HiveForge is the intended managed, production-like path, but its current
  actions prepare and validate a stack without executing deploy/update/remove.
- `./build-hive.sh` is the current working source-development entry point.

See [deployment paths](guides/operators/deployment.md) before starting an
environment.

### External deployment package

The current candidate has an artifact-inspection procedure, not a runnable
external Compose/Portainer procedure:

1. Create the archive with `./package-deployment.sh`. On Windows PowerShell,
   run `& .\package-deployment.bat` and stop if `$LASTEXITCODE` is non-zero.
2. Verify that the repository-root archive is non-empty and record its SHA-256
   checksum. The current Linux/macOS packager can print `du: cannot access ...`
   and a blank `Size` after writing the archive, so that display is not
   artifact evidence.
3. Copy and extract the generated
   `pockethive-deployment-<version>.tar.gz` or
   `pockethive-deployment-<version>.zip`, enter `pockethive`, copy
   `.env.example` to `.env`, and set an explicit registry and version.
4. Run `docker compose config`, then run the platform-specific bind-source
   audit in [deployment paths](guides/operators/deployment.md#compose-package).
5. Require zero `Missing package bind source` results before considering
   `docker compose pull`, `docker compose up`, or a Portainer import.

On Windows PowerShell, verify and hash the exact archive with an explicit
package version:

```powershell
$PackageVersion = 'REPLACE_WITH_PACKAGE_VERSION'
if ($PackageVersion -eq 'REPLACE_WITH_PACKAGE_VERSION') {
  throw 'Set PackageVersion to the generated Maven project version.'
}
$Archive = Get-Item -LiteralPath ".\pockethive-deployment-$PackageVersion.zip"
if ($Archive.Length -le 0) { throw 'The deployment archive is empty.' }
Get-FileHash -Algorithm SHA256 -LiteralPath $Archive.FullName
```

The exact tested Linux/macOS and Windows archives fail step 5. Stop there; do
not run their embedded start scripts, pull images, start containers, or import
the stack into Portainer. Those actions are qualification steps for a future
exact archive that passes the artifact audit.

### Managed deployment with HiveForge

HiveForge is the recommended managed, production-like path. It uses an
approved PocketHive git ref plus prebuilt registry-qualified images rather
than the Compose archive. Configure the exact registry/version inputs, select
`swarm-reduced` or `swarm-full`, and run the approved action through HiveForge.

Current actions render and validate the Docker Swarm stack but do not yet
execute deploy/update/remove. Qualification evidence must record the exact
tested ref. See
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
| `/auth-service/*` | Auth-service API proxy |
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

Windows PowerShell 5 and later:

```powershell
$Health = Invoke-RestMethod http://localhost:8088/healthz
if ($Health -ne 'ok') { throw "Expected health body 'ok'; got '$Health'." }
$Health
```

## Application workflow

For a future corrected candidate, no customer swarm is running when the stable
platform starts. On the exact tested lifecycle source, open **Connectivity**
before step 1. It reports an unresolved
`swarm-lifecycle.schema.json#/$defs/RuntimeMetadata` reference, so preserve that
error and stop before **New swarm**, **Create**, or **Start**. The steps below
remain completion criteria; they were not completed through this candidate UI.

1. Use **Scenarios** to inspect or edit a validated scenario bundle.
2. Use **Hive → New swarm** to select a scenario, SUT, and required network
   mode. API/MCP clients retain the returned correlation ID and operation URL;
   the current Hive create/remove flow uses the correlated Journal outcome and
   fresh state checks instead of exposing that URL.
3. Require CREATE terminal success (`SUCCEEDED` from an operation URL or
   `data.status=Succeeded` from the correlated Orchestrator outcome), then
   require controller observation `READY`, workload observation `STOPPED`, a
   fresh controller Snapshot, and the complete expected fresh worker set.
4. Start and require terminal success, then require workload observation
   `RUNNING` and fresh worker evidence with `enabled=true`.
5. Observe Snapshot, topology, Journal, health, and Grafana independently;
   health is not a lifecycle state.
6. Stop and require terminal success, then require workload observation
   `STOPPED` and fresh worker evidence with `enabled=false`.
7. Remove and require the correlated public terminal outcome with
   `data.status=Succeeded`, verified runtime and topology absence, and
   disappearance of the active swarm registration. Operation-aware clients may
   also poll the returned URL to `SUCCEEDED`.

An action response proves acceptance, not completion. PocketHive has no single
authoritative swarm state: use the operation URL plus the independent intent,
observation, health, and resource axes. A display badge or stale Snapshot is
never completion evidence.

See [swarm lifecycle](guides/operators/swarm-lifecycle.md) for the independent
state axes and completion evidence.

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
| Help | Placeholder with links; use `/docs/` for the version-matched task guides |

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

### Runtime filesystem roots

File-backed swarm journals live under:

`$POCKETHIVE_RUNTIME_FILESYSTEM_ROOT/<swarmId>/<runId>/journal.ndjson`

In the default stack this is a bind mount:

- Host: `/opt/pockethive/scenarios-runtime`
- Containers: `/app/scenarios-runtime`

Two explicit settings have distinct meanings:

- `POCKETHIVE_RUNTIME_FILESYSTEM_ROOT` is the absolute local path used by every
  process performing file IO (`/app/scenarios-runtime` in the default stack).
- `POCKETHIVE_SCENARIOS_RUNTIME_ROOT` is only the absolute host source passed to
  the runtime adapter when it creates a bind mount.

PocketHive MCP does not read this filesystem. Runtime manifest diagnostics go
through the Orchestrator runtime-debug API, so storage and parsing remain under
one owner. The controller receives the exact startup-artifact path and SHA-256;
it does not fall back to RabbitMQ or another filesystem root.

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

Windows PowerShell 5 and later:

```powershell
Invoke-RestMethod http://localhost:8088/scenario-manager/scenarios
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
- A lifecycle operation does not finish: poll its operation URL and inspect the
  correlated result, fresh worker-convergence evidence, and Journal diagnostics;
  do not infer completion from a display badge.
- Action is unavailable: check lifecycle state and the user's grant scope.
- Work does not reach the SUT: verify scenario topology, worker state, SUT
  binding, then Proxy or a focused debug tap.
- WebSocket state is stale: use Connectivity and confirm `/ws` through the same
  origin.

For lower-level source-development diagnostics, repository tools under
`tools/diag/` and `tools/mcp-orchestrator-debug/` are contributor surfaces, not
the normal customer workflow.
