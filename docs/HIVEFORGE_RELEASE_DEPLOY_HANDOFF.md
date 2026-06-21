# HiveForge Release Deploy Handoff

## Purpose

This document records the PocketHive-side follow-up for HiveForge
release-driven deployment.

HiveForge work is tracked in `/home/sepa/HiveForge/docs/ai/POCKETHIVE_RELEASE_DEPLOY_SLICES.md`.

## Current PocketHive State

PocketHive currently has a minimal HiveForge compatibility bridge:

- `hiveforge.yaml`
- `deploy/hiveforge/components/stack/hiveforge.yaml`
- `deploy/hiveforge/components/stack/ansible/deploy.yml`
- `deploy/hiveforge/components/stack/ansible/update.yml`
- `deploy/hiveforge/components/stack/ansible/remove.yml`

The current `deploy` and `update` actions run:

```bash
./build-hive.sh --quick
```

That is useful for the repo/ref POC, but it is not the target release deploy
model.

## Target Model

HiveForge deploys already-built PocketHive images from explicit registry/tag
inputs.

Example release inputs:

```text
imageRepository.project=192.168.88.54:5000/pockethive
release.imageTag=dev-YYYYMMDD-HHMM-g<sha>
```

PocketHive builds and pushes images outside HiveForge. HiveForge consumes those
image refs and must not build or push images.

Runtime profile storage model:

- `swarm-reduced` keeps all runtime config and state under the shared
  HiveForge-managed project root.
- `swarm-full` keeps Postgres and ClickHouse data under explicit service-owned
  roots and uses placement labels so those services schedule only where their
  directories exist. The rest of the runtime config and state stays under the
  shared HiveForge-managed project root.

## Existing Build/Push Tooling

Use the existing local registry tooling:

```bash
tools/docker/remote-images.sh \
  --registry 192.168.88.54:5000 \
  --namespace pockethive \
  --tag dev-YYYYMMDD-HHMM-g<sha> \
  --push
```

Notes:

- The tool rejects `--tag latest`.
- The image list source of truth is `tools/docker/image-manifest.sh`.
- Do not add a second `build-images.sh` / `push-images.sh` convention unless
  this tool is deliberately replaced.

## PocketHive Follow-up Slices

### PH Slice 1 - GHCR And Docs Hygiene

Status: done.

- Keep `.github/workflows/publish-images.yml` aligned with
  `tools/docker/image-manifest.sh`.
- Keep `docs/GHCR_SETUP.md` aligned with the real workflow triggers and image
  list.
- Document local registry and GHCR as registry sources, not as HiveForge deploy
  modes.

### PH Slice 2 - Release Artifact Shape

Status: done for HiveForge prepare/deploy input.

Create the PocketHive deployment artifact/templates that HiveForge can render.

For current compose compatibility, the key mapping is:

```text
DOCKER_REGISTRY={{ imageRepository.project }}/
POCKETHIVE_VERSION={{ release.imageTag }}
```

Do not rely on:

```text
${DOCKER_REGISTRY:-}
${POCKETHIVE_VERSION:-latest}
```

in the HiveForge release/test deploy path.

The current artifact template is:

```text
deploy/hiveforge/release-artifact.json
```

It lists PocketHive-owned runtime images and worker images with explicit
`{{ imageRepository.project }}` and `{{ release.imageTag }}` templates. Keep
this file aligned with `tools/docker/image-manifest.sh` when adding/removing
published application images.

### PH Slice 2.5 - Managed Runtime Files

Status: done for HiveForge checkout-backed prepare.

`hiveforge.yaml` declares `artifacts.managedPaths` for:

- base, single-node, swarm, and reduced Compose files,
- reduced/swarm nginx configs,
- scenario bundles,
- Scenario Manager capabilities/network/SUT definitions,
- WireMock files,
- TCP mock mappings/files.

HiveForge copies those files from the checked-out repo into its
container-visible managed project root under:

```text
/hf/artifacts/runtime/
```

For Docker Stack bind sources, `swarm-reduced` and `swarm-full` require the
HiveForge-provided host-visible equivalent `HIVEFORGE_BIND_SOURCE_DIR`.
PocketHive Ansible must not write to that value. It reads and writes the
action-root constant `/hf`:

```text
/hf/artifacts/runtime/...  # managed artifacts copied by HiveForge
/hf/state/...              # shared runtime state created by PocketHive Ansible
/hf/stacks/compose.yml     # rendered stack file written by PocketHive Ansible
```

The rendered Compose file uses `HIVEFORGE_BIND_SOURCE_DIR` only for
Docker-daemon bind sources:

```text
${HIVEFORGE_BIND_SOURCE_DIR}/state/...
```

For example, if HiveForge sets
`HIVEFORGE_BIND_SOURCE_DIR=/opt/hiveforge/data/deployed/pockethive`, Ansible
creates `/hf/state/haproxy/runtime` and the rendered Docker bind points at
`/opt/hiveforge/data/deployed/pockethive/state/haproxy/runtime`.

Expected first required files for `deploy_release`:

```text
artifacts/runtime/config/rabbitmq/rabbitmq.conf
artifacts/runtime/config/clickhouse/init/01-ph-tx-outcome-v1.sql
artifacts/runtime/scenarios
artifacts/runtime/scenario-manager/capabilities
artifacts/runtime/scenario-manager/network
artifacts/runtime/scenario-manager/sut
```

### PH Slice 3 - Replace POC Build Action

`swarm-reduced` now renders HiveForge-managed compose artifacts and deploys the
result with `docker stack deploy` using prebuilt images from explicit runtime
environment values. `single-full` still uses `build-hive.sh --quick` for local
developer stacks. `swarm-full` now uses the same Docker Stack render/deploy path
as `swarm-reduced`, with explicit dedicated service-owned mount roots supplied
through runtime environment values.

Hard rule for PocketHive swarm image deployment:

- HiveForge swarm deploy must not call `build-hive.sh --quick`.
- HiveForge deploy must not call `tools/docker/remote-images.sh`.
- Build/push remains an operator/CI step before HiveForge deploy.

### PH Slice 4 - External Images

PocketHive currently has third-party infrastructure images in compose that may
use `latest` or no tag.

Decide explicitly whether the first HiveForge release path:

- validates only PocketHive application images, or
- also pins/templates all third-party infra images.

Do not mix these validation classes accidentally.

### PH Slice 5 - End-To-End Smoke

Expected local registry smoke:

1. Build/push PocketHive images:

```bash
tools/docker/remote-images.sh \
  --registry 192.168.88.54:5000 \
  --namespace pockethive \
  --tag dev-YYYYMMDD-HHMM-g<sha> \
  --push
```

2. Set non-secret HiveForge runtime environment for the `swarm-reduced` profile:

```text
DOCKER_REGISTRY=192.168.88.54:5000/pockethive/
POCKETHIVE_VERSION=dev-YYYYMMDD-HHMM-g<sha>
POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX=192.168.88.54:5000/pockethive
POCKETHIVE_STACK_NAME=pockethive
```

3. Ask HiveForge MCP to deploy `stack/deploy` with profile `swarm-reduced`.

4. Verify the running stack through documented ingress/API paths, not direct
container ports unless the check is explicitly for that service interface.

## Hard Rules

- No implicit `latest` for PocketHive application images.
- No tag inference from branch/ref/dirty state.
- No fallback to local Docker images.
- No build or push during HiveForge deploy.
- No provider/IP/SSH/host-path details in portable PocketHive manifests.
- Use `tools/docker/image-manifest.sh` as the PocketHive image list source of
  truth.
