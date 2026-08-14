---
title: Choose how to deploy PocketHive
pagination_label: Choose a deployment path
---

# Choose how to deploy PocketHive

| Reader context | Details |
| --- | --- |
| Audience | Evaluators and operators choosing how to run PocketHive |
| Prerequisites | A target environment and permission to use the selected deployment path |
| Expected outcome | Choose a path, run its documented commands, and know what the result proves |
| Candidate source tested | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

This page owns PocketHive deployment status. It separates a command that is
available from a deployment path that has completed its qualification gate.

## Choose a path

| Goal | Path | Current status |
| --- | --- | --- |
| Develop or evaluate from source | [Local source](#local-source) | **Candidate**; build succeeds, but full UI lifecycle qualification is blocked at Connectivity |
| Evaluate on one Compose or Portainer host | [Compose package](#compose-package) | **Candidate**; clean-host qualification is incomplete |
| Prepare a governed Docker Swarm release | [HiveForge](#hiveforge) | **Recommended direction**; render and validation exist, runtime execution does not |

No path at the tested candidate source is documented as **supported**. Use the
source path for isolated build and startup evaluation, but do not claim a
verified lifecycle unless every quickstart gate passes.

:::warning Deployment evidence

Creating an archive or receiving a successful HiveForge validation result does
not prove that PocketHive was deployed. Require the path-specific verification
listed here.

:::

## Status language

| Term | Meaning |
| --- | --- |
| **Working** | Verified for the stated scope |
| **Available** | The command or artifact exists; qualification is not implied |
| **Candidate** | Available but missing one or more release gates |
| **Supported** | Approved for the exact release and backed by required evidence |
| **Recommended direction** | Intended delivery architecture; implementation may be incomplete |
| **Production-like** | Resembles production orchestration; not a support claim |

## Local source

Use a complete source checkout with Bash, Docker Compose V2, Docker Buildx,
Java 21, Maven, and port `8088` available. On Windows, run the build from Git
Bash or WSL.

```bash
docker buildx version
./build-hive.sh --quick
curl -fsS http://localhost:8088/healthz
```

PowerShell health check:

```powershell
$Health = Invoke-RestMethod http://localhost:8088/healthz
if ($Health -ne 'ok') { throw "Expected health body 'ok'; got '$Health'." }
$Health
```

**Verify:** Buildx is available, the health check returns exactly `ok`, and
`http://localhost:8088` opens. Sign in as `local-admin` only for the local
Compose environment, then follow the [local quickstart](../onboarding/quickstart-15min.md).

At `0524165e`, the isolated VM build and ingress health checks pass, but the UI
reports degraded Connectivity because it cannot resolve the external swarm
lifecycle schema while compiling the control-event schema. This blocks the
quickstart lifecycle gate. Treat the source path as a candidate until a later
exact revision passes that gate and the complete create-to-remove workflow.

If startup fails, inspect `docker compose ps` and
`docker compose logs --tail=200`, correct the first reported error, and rerun
the same build command.

## Compose package

The archive creation workflow remains available for single-host qualification,
but the generated archive is still a **candidate** and is known to be
incomplete.

:::caution Current archive limitation

The checked-in packagers do not yet copy every path bind-mounted by
`docker-compose.yml`. The Linux/macOS archive omits ClickHouse and TCP Mock
assets. The Windows archive also omits RabbitMQ, `grafana.ini`, root scenarios,
and Scenario Manager network/SUT assets, and uses outdated Scenario Manager
paths. A generated archive can therefore fail on a clean host. Inspect its
contents against every Compose bind mount before use.

`docker compose config` validates Compose syntax and interpolation; it does
not prove that bind-mounted source files exist. The current archive must stop
at the artifact audit below. The later `pull` and `up` commands are future
qualification steps only after an exact artifact passes that audit.

:::

Create the archive from a source checkout:

```bash
./package-deployment.sh
```

The current Linux/macOS script can print `du: cannot access ...` and a blank
`Size` after it has written the archive. Verify the repository-root file with
`test -s pockethive-deployment-<version>.tar.gz` and `sha256sum
pockethive-deployment-<version>.tar.gz`; this is a packager display defect, not
deployment evidence.

On Windows PowerShell:

```powershell
& .\package-deployment.bat
if ($LASTEXITCODE -ne 0) { throw "package-deployment.bat failed with exit code $LASTEXITCODE." }
```

The result is `pockethive-deployment-<version>.tar.gz` or
`pockethive-deployment-<version>.zip`. Copy it to the target host and set an
explicit `DOCKER_REGISTRY` and `POCKETHIVE_VERSION=<release-version>` in
`.env`; do not rely on an example tag. `DOCKER_REGISTRY` is concatenated with
image names and must therefore end in `/`, for example
`ghcr.io/example/pockethive/`.

Install `jq` on the target host; on Windows, put `jq.exe` on `PATH`. The
PowerShell audit deliberately does not use `ConvertFrom-Json` because Compose
can emit environment-variable keys that differ only by letter case.

Linux or macOS:

```bash
PACKAGE_VERSION="REPLACE_WITH_PACKAGE_VERSION"
tar xzf "pockethive-deployment-${PACKAGE_VERSION}.tar.gz"
cd pockethive
cp .env.example .env
# Set DOCKER_REGISTRY (including its trailing /) and POCKETHIVE_VERSION in .env.
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
# Set DOCKER_REGISTRY (including its trailing /) and POCKETHIVE_VERSION in .env.
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

The checked-in packagers currently fail this audit, so do not continue with
that artifact. For a future corrected archive, only after every bind source is
present, run the following qualification steps:

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

Before the first swarm on a clean host, require every controller and worker
image in the selected bundle to resolve to an approved, immutable registry
reference. Enable **Pull images** in **New swarm** only after registry
authentication succeeds; otherwise stop the package check.

**Current verification result:** package creation and Compose configuration
resolution succeed, but the bind-source audit fails; no clean-host start may
be claimed. A future corrected artifact must additionally prove exact image
pulls, service health, the official-ingress response, and the
[first-swarm workflow](../onboarding/quickstart-15min.md#3-create-a-demo-swarm).

For a future corrected archive that passes the audit, Portainer qualification
starts by extracting it on the managed host, creating a stack from the bundled
`docker-compose.yml`, configuring the same registry and release version, and
ensuring every relative bind-mounted file is available. Do not import the
current incomplete candidate.

Before calling an archive supported, require a clean-host start, complete
mounted assets, immutable image selection, the full first-swarm lifecycle, a
published checksum, and the remaining checks in `DEPLOYMENT_PACKAGE.md` from
the same source checkout. Use that file for the complete
package-qualification record; do not substitute the mutable `main` copy.

## HiveForge

HiveForge is the recommended direction for governed, production-like Docker
Swarm delivery. It uses an approved PocketHive git ref and registry images; it
does not consume the Compose archive.

| Current behavior at tested source (`0524165e`) | Not implemented yet |
| --- | --- |
| Prepare the repository and exact inputs | Execute `docker stack deploy` |
| Render `/hf/stacks/compose.yml` | Change a running deployment |
| Validate with `docker stack config` | Remove or roll back the managed runtime |
| Record preparation and validation in the journal | Prove UI health or lifecycle convergence |

To evaluate the current integration:

1. Confirm HiveForge health, project policy, and target environment.
2. Set the exact registry, version, repository prefix, profile, and git ref.
3. Select `swarm-reduced` or `swarm-full`.
4. Start the approved deploy or update action.
5. Poll the operation and inspect its journal.

**Verify:** the journal records repository preparation, stack rendering, and
successful validation. Report the result as **validated**, not deployed.
Do not run the current remove action or bypass HiveForge with direct SSH or
host-level Docker commands.

See [HiveForge integration](../../HIVEFORGE.md) for the exact component
contract and implementation boundary.

## Update and recovery

Before changing versions, follow [Upgrading PocketHive](../../UPGRADING.md).
Keep the previous archive and explicit image version, back up service-owned
data, run `docker compose config`, and preserve logs before recovery. Restoring
old containers does not automatically reverse persistent-data migrations.

HiveForge has no qualified runtime update, removal, or rollback path at the
tested source; treat a failed action as a preparation or validation failure.

## Troubleshooting

| Symptom | Next check |
| --- | --- |
| Source or Compose services fail | Use [observability and troubleshooting](observability-troubleshooting.md), then inspect Compose status and logs. |
| Package is missing a file or fails on a clean host | Stop qualification and follow the failed gate in the same checkout's `DEPLOYMENT_PACKAGE.md`. |
| HiveForge action fails or only validates | Follow the [HiveForge integration](../../HIVEFORGE.md); do not claim a runtime change. |

## Next step

- For local evaluation, continue with the
  [15-minute quickstart](../onboarding/quickstart-15min.md).
- For a single-host archive, complete the
  [Compose package checks](#compose-package).
- For managed delivery, confirm the [current HiveForge boundary](#hiveforge)
  before requesting an action.
