# PocketHive Deployment Package

## Overview

The deployment package is intended to bundle the files needed to run
PocketHive in external Compose/Portainer environments without requiring the
source repository.

## Choose a deployment path

| Goal | Path | Current status at tested source (`0524165e`) |
| --- | --- | --- |
| Develop or evaluate from source | Source checkout with `build-hive.sh` | **Candidate**; build succeeds, but the tested lifecycle rewrite is blocked at the UI Connectivity gate. |
| Evaluate on one host | This Compose/Portainer archive | **Candidate**; package creation is available, but the archive is not clean-host qualified. |
| Prepare a managed Docker Swarm release | HiveForge | **Recommended direction**; current actions prepare and validate but do not execute deployment. |

The canonical [deployment paths](docs/guides/operators/deployment.md) page owns
the current status and definitions of **working**, **available**,
**candidate**, **supported**, **recommended direction**, and
**production-like**. This page focuses on the candidate Compose archive.

> [!WARNING]
> The current archive is known to omit required bind-mounted files. Use it
> only to test package creation, Compose resolution, and the bind-source audit
> below. Do not run `docker compose pull`, `docker compose up`, or deploy the
> current archive through Portainer. Those steps apply only to a future exact
> artifact that passes the audit; a generated file is not qualification
> evidence.

## Creating the Package

Package creation requires a complete PocketHive source checkout and Maven
because the scripts derive `<version>` from the root Maven project.

### Linux/macOS

```bash
./package-deployment.sh
```

The current script can print `du: cannot access ...` and a blank `Size` after
writing the archive because its size display runs from the removed staging
directory. Verify the repository-root artifact explicitly:

```bash
test -s "pockethive-deployment-<version>.tar.gz"
sha256sum "pockethive-deployment-<version>.tar.gz"
```

This confirms a non-empty file and records its checksum; it does not qualify
the archive for startup.

### Windows

From PowerShell:

```powershell
$PackageVersion = 'REPLACE_WITH_PACKAGE_VERSION'
if ($PackageVersion -eq 'REPLACE_WITH_PACKAGE_VERSION') {
  throw 'Set PackageVersion to the generated Maven project version.'
}
& .\package-deployment.bat
if ($LASTEXITCODE -ne 0) { throw "package-deployment.bat failed with exit code $LASTEXITCODE." }
$Archive = Get-Item -LiteralPath ".\pockethive-deployment-$PackageVersion.zip"
if ($Archive.Length -le 0) { throw 'The deployment archive is empty.' }
Get-FileHash -Algorithm SHA256 -LiteralPath $Archive.FullName
```

**Expected result:** the repository root contains
`pockethive-deployment-<version>.tar.gz` on Linux/macOS or
`pockethive-deployment-<version>.zip` on Windows.

**What this proves:** the packaging script completed and created an archive.

**What this does not prove:** that every required runtime file is present or
that the archive starts on a clean host.

**If it fails:** confirm Maven and `tar` are available on Linux/macOS, or Maven
and PowerShell are available on Windows. Correct the first reported missing
tool or file and rerun the same command.

## Candidate Archive Contents

The tree below describes the intended Linux/macOS archive shape. The current
Windows package script does not yet copy every Linux archive path; that
cross-platform difference is one reason the package remains a candidate.

```text
pockethive/
├── docker-compose.yml          # Main deployment configuration
├── docker-compose.opt.yml      # Linux archive: absolute /opt paths
├── .env.example                # Environment variables template
├── start.sh                    # Linux/macOS quick start script
├── stop.sh                     # Linux/macOS stop script
├── DEPLOY.md                   # Deployment instructions
├── README.md                   # Project overview
├── LICENSE                     # License file
├── rabbitmq/                   # Rabbit definitions/config used by the stack
├── grafana/
│   ├── dashboards/             # Pre-built ClickHouse/Postgres dashboards
│   └── provisioning/           # ClickHouse/Postgres datasource configs
├── wiremock/
│   ├── mappings/               # HTTP mock stubs
│   ├── __files/                # Response templates
│   └── README.md
├── scenario-manager-service/
│   ├── capabilities/           # Worker capabilities (reference)
│   ├── network/                # Network profiles
│   └── sut/                    # SUT environment definitions
└── docs/
    ├── GHCR_SETUP.md          # Registry setup
    └── USAGE.md               # Usage guide
```

This is the tested candidate's Linux/macOS archive shape; it does not include
`docs/HIVEFORGE.md`. Its generated `DEPLOY.md` and start scripts also still
lead directly to Compose startup. Do not follow those embedded startup steps
for the current candidate: the bind-source audit below is the controlling gate
and must stop the workflow when package content is missing.

The embedded guidance has additional known defects in this candidate:

- the Linux "another directory" example uses relative
  `-f docker-compose.opt.yml`, so it cannot find the file outside the package
  directory;
- configuration is called optional even though the supplied defaults leave the
  registry empty and select floating `latest` images;
- `down -v` is described as a complete reset while it deletes Compose named
  volumes only, and the persistence list omits several declared volumes;
- the Windows generated guide hardcodes its displayed current version instead
  of using the package version; and
- the generated Windows `start.bat` and `stop.bat` do not preserve a failed
  Compose exit code before printing their follow-up messages, so those messages
  and the final batch exit code are not reliable success evidence; and
- package-local Markdown links do not close, including links back to this
  mandatory audit guide.

These are packaging/generator defects, not alternative customer procedures.
Use the version-matched source or `/docs/` site for reference, and stop the
current archive at the audit failure.

Before treating any generated archive as complete, compare its contents with
every relative bind mount in its `docker-compose.yml`. `docker compose config`
does not prove those host-side source files exist. The archive's included
Markdown files are reference material; prefer the running installation's
version-matched `/docs/` site. GitHub Pages can publish a different ref, so
match its displayed source/version boundary before following commands.

## Using the Compose Archive

> [!WARNING]
> Use an explicit registry and `<release-version>`. The candidate's
> `.env.example` contains a default tag for source-development convenience;
> copying it unchanged is not a reproducible external deployment.

### Audit the archive on a target host

The audit needs `jq` on every platform. On Windows, install `jq.exe` and make
it available on `PATH`; Windows PowerShell's built-in JSON converter cannot
load Compose models that contain environment keys differing only by case. Run
the audit from a newly extracted archive so files from a source checkout
cannot mask omissions.

Linux/macOS:

```bash
PACKAGE_VERSION="REPLACE_WITH_PACKAGE_VERSION"
tar xzf "pockethive-deployment-${PACKAGE_VERSION}.tar.gz"
cd pockethive
cp .env.example .env
# Set DOCKER_REGISTRY and POCKETHIVE_VERSION=<release-version> in .env.
if ! docker compose config; then
  echo "Compose configuration failed; stop before the bind-source audit." >&2
  exit 1
fi
command -v jq >/dev/null || { echo "jq is required for the bind-source audit" >&2; exit 1; }
if ! compose_json="$(docker compose config --format json)"; then
  echo "Could not obtain resolved Compose JSON." >&2
  exit 1
fi
if ! raw_bind_sources="$(
  printf '%s\n' "$compose_json" |
    jq -r '.services[].volumes[]? | select(.type == "bind") | .source'
)"; then
  echo "jq could not read resolved bind sources." >&2
  exit 1
fi
if ! bind_sources="$(printf '%s\n' "$raw_bind_sources" | sort -u)"; then
  echo "Could not sort resolved bind sources." >&2
  exit 1
fi
package_root="$(pwd -P)/"
missing=0
while IFS= read -r source; do
  [ -n "$source" ] || continue
  case "$source" in
    "$package_root"*)
      if [ ! -e "$source" ]; then
        printf 'Missing package bind source: %s\n' "$source" >&2
        missing=1
      fi
      ;;
    *) printf 'External bind prerequisite (not package content): %s\n' "$source" ;;
  esac
done <<< "$bind_sources"
if [ "$missing" -ne 0 ]; then
  echo "Archive is incomplete; stop before pull or up." >&2
  exit 1
fi
```

Windows PowerShell:

```powershell
$PackageVersion = 'REPLACE_WITH_PACKAGE_VERSION'
Expand-Archive -LiteralPath ".\pockethive-deployment-$PackageVersion.zip" -DestinationPath .
Set-Location .\pockethive
Copy-Item .env.example .env
# Set DOCKER_REGISTRY and POCKETHIVE_VERSION=<release-version> in .env.
docker compose config
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed.' }
$Jq = Get-Command jq -CommandType Application -ErrorAction Stop
$ComposeJson = @(docker compose config --format json)
if ($LASTEXITCODE -ne 0) { throw 'Could not obtain resolved Compose JSON.' }
$BindSources = @(
  $ComposeJson |
    & $Jq.Source -r --arg bindType bind '.services[].volumes[]? | select(.type == $bindType) | .source' |
    Sort-Object -Unique
)
if ($LASTEXITCODE -ne 0) { throw 'jq could not read resolved bind sources.' }
$PackagePrefix = [IO.Path]::GetFullPath((Get-Location).Path).TrimEnd('\') + '\'
$PackageBindSources = @(
  $BindSources | Where-Object {
    ([IO.Path]::GetFullPath([string]$_)).StartsWith(
      $PackagePrefix,
      [StringComparison]::OrdinalIgnoreCase
    )
  }
)
$ExternalBindSources = @(
  $BindSources | Where-Object {
    -not ([IO.Path]::GetFullPath([string]$_)).StartsWith(
      $PackagePrefix,
      [StringComparison]::OrdinalIgnoreCase
    )
  }
)
$ExternalBindSources | ForEach-Object {
  Write-Warning "External bind prerequisite (not package content): $_"
}
$MissingBindSources = @(
  $PackageBindSources | Where-Object { -not (Test-Path -LiteralPath $_) }
)
$MissingBindSources | ForEach-Object { Write-Error "Missing package bind source: $_" }
if ($MissingBindSources.Count -ne 0) {
  throw 'Archive is incomplete; stop before pull or up.'
}
```

**Expected result for the current candidate:** Compose accepts the resolved
configuration, then the bind-source audit reports missing paths and stops.

**What this proves:** package creation and Compose resolution work, while the
exact current archive is not deployable on a clean host.

**What this does not prove:** image availability, service startup,
official-ingress health, a complete first-swarm lifecycle, portability, or
supported status.

**Next step for the current candidate:** preserve the missing-path list and
stop. Do not continue to image pulls, startup, Portainer, or the first-swarm
workflow with this artifact.

For a future corrected archive, only after the audit exits successfully, run:

```bash
docker compose pull
docker compose up -d
curl -fsS http://localhost:8088/healthz
```

```powershell
docker compose pull
docker compose up -d
$Health = Invoke-RestMethod http://localhost:8088/healthz
if ($Health -ne 'ok') { throw "Expected health body 'ok'; got '$Health'." }
$Health
```

Require exact body `ok`, then complete the
[first-swarm workflow](docs/guides/onboarding/quickstart-15min.md) and record
the archive checksum, exact image tags/digests, host platform, commands, and
results if the run will be used as release evidence.

**If it fails:** inspect state and logs:

```bash
docker compose ps
docker compose logs --tail=200
docker compose down
```

For the current candidate, a missing bind-mounted asset is an archive defect,
not a target-host recovery step. Stop and report it. For a future artifact that
passed the audit but failed later, correct the explicit `.env`, image, or port
conflict and repeat the failed qualification step. `down` keeps named volumes
unless `--volumes` is explicitly added.

### Portainer (future corrected archive only)

Do not deploy the current candidate archive through Portainer because it fails
the same bind-source completeness gate. For a future exact archive that passes
the audit:

1. Extract the archive on the Portainer-managed host.
2. In Portainer, open **Stacks → Add stack** and use the bundled
   `docker-compose.yml`.
3. Configure `DOCKER_REGISTRY` and
   `POCKETHIVE_VERSION=<release-version>` explicitly.
4. Ensure every relative bind-mounted package path is available to the stack.
5. Deploy, inspect service logs/status, and check the official PocketHive
   ingress.

**Expected result:** Portainer reports the stack services running and the
PocketHive ingress responds.

**What this proves:** the candidate reached that state in this Portainer
environment; it does not qualify the archive as supported.

**If it fails:** preserve the service logs, remove the failed stack without
deleting persistent volumes, correct the archive placement or configuration,
and redeploy.

## Separate Managed Path: HiveForge

HiveForge is not a third archive deployment method. It checks out an approved
PocketHive git ref and consumes registry-qualified images; it does not import
the Compose `.tar.gz` or `.zip`.

> [!WARNING]
> Current tested-source HiveForge deploy/update actions stop after stack
> preparation, rendering, and validation. They do not change the target
> runtime, and remove fails deliberately.

**Current behavior:** operators configure exact registry/version inputs,
select `swarm-reduced` or `swarm-full`, start an approved action, and inspect
the HiveForge journal for preparation/validation evidence.

**Intended workflow:** after the execution gate is implemented and qualified,
HiveForge will govern deploy, update, official-ingress verification, and remove
for an immutable PocketHive release.

See the canonical [HiveForge path status](docs/guides/operators/deployment.md#hiveforge)
and [HiveForge integration](docs/HIVEFORGE.md) before using this path.

## What's Included vs What's Not

### Included in the Candidate Archive

- Docker Compose configuration
- Configuration files for RabbitMQ, Grafana, and ClickHouse dashboards/provisioning
- WireMock stubs
- Grafana dashboards
- Documentation
- Start/stop scripts

The exact set differs between the current Linux/macOS and Windows scripts.
Verify generated contents against the Compose bind mounts rather than treating
this summary as a qualification result.

### Not Included (Pulled from the Explicit Registry)

- Docker images (pulled from `ghcr.io/sepa79/pockethive/`)
- Source code
- Build tools

### Included for Reference Only

- Scenarios (baked into `scenario-manager` image)
- Capabilities, network profiles, and SUT definitions (also baked into `scenario-manager` image)

To use custom scenarios/capabilities, mount them as volumes in `docker-compose.yml`.

## Customization

These examples apply only after an exact archive passes the bind-source audit
and completes clean-host qualification. They are not a workaround for the
current incomplete candidate.

### Custom Scenarios

Edit `docker-compose.yml`:
```yaml
scenario-manager:
  volumes:
    - ./scenarios:/app/scenarios:ro
    - ./scenario-manager-service/capabilities:/app/capabilities:ro
    - ./scenario-manager-service/network:/app/network:ro
    - ./scenario-manager-service/sut:/app/sut:ro
```

### Custom WireMock Stubs

1. Edit files in `wiremock/mappings/`
2. Restart: `docker compose restart wiremock`

### Custom Grafana Dashboards

1. Add JSON files to `grafana/dashboards/`
2. Restart: `docker compose restart grafana`

### Environment Variables

1. Copy `.env.example` to `.env`
2. Edit values
3. Restart: `docker compose up -d`

## Image Sources

Set one explicit registry prefix and release tag in `.env`. For example:

```text
DOCKER_REGISTRY=ghcr.io/<owner>/pockethive/
POCKETHIVE_VERSION=<release-version>
```

PocketHive-owned images then resolve in this form:

- `ghcr.io/<owner>/pockethive/orchestrator:<release-version>`
- `ghcr.io/<owner>/pockethive/scenario-manager:<release-version>`
- `ghcr.io/<owner>/pockethive/ui:<release-version>`
- `ghcr.io/<owner>/pockethive/swarm-controller:<release-version>`
- the worker images required by the selected scenario.

RabbitMQ, Postgres, ClickHouse, Grafana, Redis, Redis Commander, WireMock, and
Toxiproxy are third-party images resolved by the bundled Compose file. Some
third-party references are not yet immutable; that remains part of the
candidate qualification boundary. See
[GHCR setup](docs/GHCR_SETUP.md) for publication and authentication details.

## Ports

The tested candidate Compose file publishes the following host ports. By
default these mappings are not restricted to loopback; use host firewall and
explicit bind-address controls before running a future corrected archive on an
untrusted network. Confirm the effective list with `docker compose config`.

| Port  | Service              | Description                    |
|-------|----------------------|--------------------------------|
| 8088  | UI                   | Web interface                  |
| 5672  | RabbitMQ             | AMQP protocol                  |
| 15672 | RabbitMQ Management  | Admin UI (guest/guest)         |
| 15674 | RabbitMQ Web STOMP   | WebSocket STOMP                |
| 6379  | Redis                | Dataset cache/source           |
| 8081  | Redis Commander      | Redis web UI                   |
| 8088 `/grafana/` | Grafana     | Dashboards via UI ingress      |
| 8123  | ClickHouse           | HTTP API                       |
| 9000  | ClickHouse           | Native protocol                |
| 8080  | WireMock             | HTTP mocks                     |
| 5432  | Postgres             | Relational datastore           |
| 8083  | TCP Mock             | TCP Mock web UI                |
| 9090  | TCP Mock             | Plain TCP listener             |
| 8084  | TLS TCP Mock         | TLS TCP Mock web UI            |
| 9091  | TLS TCP Mock         | TLS TCP listener               |
| 18474 | Toxiproxy            | Toxiproxy API                  |
| 1083  | Auth service         | Authentication API             |
| 1081  | Scenario Manager     | Scenario API                   |
| 1082  | Network Proxy Manager | Network proxy API             |

Use the UI origin on port `8088` and its proxied paths for customer
verification. Treat direct service ports as diagnostics, not substitutes for
the official ingress.

## Persistent Data

Compose creates named volumes for:

- `rabbitmq-data` - RabbitMQ queues and broker state
- `postgres-data` - application relational data
- `clickhouse-data` - product metrics and transaction outcomes
- `grafana-data` - Grafana state and plugins
- `redis-data` - Redis datasets
- `tcp-mock-data` and `tcp-mock-tls-data` - TCP Mock state and TLS material

Docker prefixes these names with the Compose project name unless an explicit
name is configured. `docker compose down` retains them;
`docker compose down --volumes` deletes them and is not a routine recovery
step.

## Troubleshooting

### Package Creation Fails

- **Linux/macOS:** confirm Maven and `tar` are available.
- **Windows:** confirm Maven and PowerShell are available.

Run the packaging command again and act on the first missing source path or
tool reported. A partial archive is not a usable candidate.

### Images Won't Pull

Use this subsection only with a complete source checkout, or with a future
archive that passed the bind-source audit. Do not pull images as a workaround
for the current incomplete archive.

Check network connectivity and the exact published tag:

```bash
docker pull ghcr.io/OWNER/pockethive/ui:RELEASE_VERSION
```

Replace `OWNER` and `RELEASE_VERSION` before running the command.

For private registries, login first:

Linux/macOS, Git Bash, or WSL:

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u USERNAME --password-stdin
```

Windows PowerShell:

```powershell
$env:GITHUB_TOKEN | docker login ghcr.io -u USERNAME --password-stdin
```

If the pull still fails, verify package visibility, token scope, registry
prefix, and release tag before retrying. Do not substitute a floating tag.

### Services Won't Start

Use this section only for a future archive that passed the bind-source audit.
Do not use runtime troubleshooting to work around missing files in the current
candidate.

Check logs:

```bash
docker compose ps
docker compose logs --tail=200
```

Common issues:

- Port conflicts: Change ports in `docker-compose.yml`
- RabbitMQ not ready: Wait for healthcheck
- Docker socket: Ensure orchestrator can access `/var/run/docker.sock`

After correcting the reported problem, rerun `docker compose config`,
`docker compose pull`, and `docker compose up -d`, then check
`http://localhost:8088/healthz`.

## Updates

Read [Upgrading PocketHive](docs/UPGRADING.md) and the target release notes
before changing versions. The steps below require an archive that passed the
bind-source audit and was qualified for the target release; they do not apply
to the current incomplete candidate.

1. Keep the previous archive and `.env`.
2. Back up persistent data using each service owner's documented procedure.
3. Extract the new archive to a separate directory.
4. Set the exact new `POCKETHIVE_VERSION=<release-version>` and registry.
5. Run `docker compose config` and `docker compose pull`.
6. Run `docker compose up -d`.
7. Verify official-ingress health and the first-swarm workflow.

In Portainer, preserve the old stack definition and variables, update the
explicit release tag, pull/redeploy, then run the same checks.

If verification fails, preserve logs and evidence first. Restoring the previous
archive and image tag can restore application files and containers, but may
not reverse persistent-data migrations. Do not roll data backward unless the
release notes document that recovery path.

## Support

- Canonical deployment status:
  [Deployment paths](docs/guides/operators/deployment.md)
- Version-matched documentation: `docs/README.md` in the same source checkout,
  or `/docs/` in the running installation after verifying its displayed source
  boundary
- Issues: https://github.com/sepa79/PocketHive/issues
- Releases: https://github.com/sepa79/PocketHive/releases
