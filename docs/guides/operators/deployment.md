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
| Last verified PocketHive version | PocketHive `v0.15.35` |

This page owns PocketHive deployment status. It separates a command that is
available from a deployment path that has completed its qualification gate.

## Choose a path

| Goal | Path | Current status |
| --- | --- | --- |
| Develop or evaluate from source | [Local source](#local-source) | **Working** for contributor use |
| Evaluate on one Compose or Portainer host | [Compose package](#compose-package) | **Candidate**; clean-host qualification is incomplete |
| Prepare a governed Docker Swarm release | [HiveForge](#hiveforge) | **Recommended direction**; render and validation exist, runtime execution does not |

No `v0.15.35` package or managed path is documented as **supported**. Use the
source path when you need a verified local environment now.

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

Use a complete source checkout with Bash, Docker Compose V2, Java 21, Maven,
and port `8088` available. On Windows, run the build from Git Bash or WSL.

```bash
./build-hive.sh --quick
curl -fsS http://localhost:8088/healthz
```

PowerShell health check:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8088/healthz
```

**Verify:** the health check succeeds and `http://localhost:8088` opens. Sign
in as `local-admin` only for the local Compose environment, then follow the
[local quickstart](../onboarding/quickstart-15min.md).

If startup fails, inspect `docker compose ps` and
`docker compose logs --tail=200`, correct the first reported error, and rerun
the same build command.

## Compose package

The external Compose/Portainer workflow remains available for single-host
evaluation, but its archive is still a **candidate**.

:::caution Current archive limitation

The checked-in packagers do not yet copy every path bind-mounted by
`docker-compose.yml`. The Linux/macOS archive omits ClickHouse and TCP Mock
assets. The Windows archive also omits RabbitMQ, `grafana.ini`, root scenarios,
and Scenario Manager network/SUT assets, and uses outdated Scenario Manager
paths. A generated archive can therefore fail on a clean host. Inspect its
contents against every Compose bind mount before use.

:::

Create the archive from a source checkout:

```bash
./package-deployment.sh
```

On Windows Command Prompt:

```batch
package-deployment.bat
```

The result is `pockethive-deployment-<version>.tar.gz` or
`pockethive-deployment-<version>.zip`. Copy it to the target host and set an
explicit `DOCKER_REGISTRY` and `POCKETHIVE_VERSION=<release-version>` in
`.env`; do not rely on an example tag. `DOCKER_REGISTRY` is concatenated with
image names and must therefore end in `/`, for example
`ghcr.io/example/pockethive/`.

Linux or macOS:

```bash
PACKAGE_VERSION="REPLACE_WITH_PACKAGE_VERSION"
tar xzf "pockethive-deployment-${PACKAGE_VERSION}.tar.gz"
cd pockethive
cp .env.example .env
# Set DOCKER_REGISTRY (including its trailing /) and POCKETHIVE_VERSION in .env.
docker compose config
docker compose pull
docker compose up -d
curl -fsS http://localhost:8088/healthz
```

Windows PowerShell:

```powershell
$PackageVersion = 'REPLACE_WITH_PACKAGE_VERSION'
Expand-Archive -LiteralPath ".\pockethive-deployment-$PackageVersion.zip" -DestinationPath .
Set-Location .\pockethive
Copy-Item .env.example .env
# Set DOCKER_REGISTRY (including its trailing /) and POCKETHIVE_VERSION in .env.
docker compose config
docker compose pull
docker compose up -d
Invoke-WebRequest -UseBasicParsing http://localhost:8088/healthz
```

Before the first swarm on a clean host, require every controller and worker
image in the selected bundle to resolve to an approved, immutable registry
reference. Enable **Pull images** in **New swarm** only after registry
authentication succeeds; otherwise stop the package check.

**Verify:** configuration resolves, exact images pull, services start, the
ingress health check succeeds, and the
[first-swarm workflow](../onboarding/quickstart-15min.md#3-create-a-demo-swarm)
completes. Start at that section because the package is already running.

For Portainer, extract the archive on the managed host, create a stack from
the bundled `docker-compose.yml`, configure the same registry and release
version, and ensure relative bind-mounted files remain available to the stack.

Before calling an archive supported, require a clean-host start, complete
mounted assets, immutable image selection, the full first-swarm lifecycle, a
published checksum, and the remaining checks in
[Deployment package qualification](https://github.com/sepa79/PocketHive/blob/main/DEPLOYMENT_PACKAGE.md).

## HiveForge

HiveForge is the recommended direction for governed, production-like Docker
Swarm delivery. It uses an approved PocketHive git ref and registry images; it
does not consume the Compose archive.

| Current `v0.15.35` behavior | Not implemented yet |
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

HiveForge has no qualified runtime update, removal, or rollback path in
`v0.15.35`; treat a failed action as a preparation or validation failure.

## Troubleshooting

| Symptom | Next check |
| --- | --- |
| Source or Compose services fail | Use [observability and troubleshooting](observability-troubleshooting.md), then inspect Compose status and logs. |
| Package is missing a file or fails on a clean host | Stop qualification and follow the failed gate in [Deployment package qualification](https://github.com/sepa79/PocketHive/blob/main/DEPLOYMENT_PACKAGE.md). |
| HiveForge action fails or only validates | Follow the [HiveForge integration](../../HIVEFORGE.md); do not claim a runtime change. |

## Next step

- For local evaluation, continue with the
  [15-minute quickstart](../onboarding/quickstart-15min.md).
- For a single-host archive, complete the
  [Compose package checks](#compose-package).
- For managed delivery, confirm the [current HiveForge boundary](#hiveforge)
  before requesting an action.
