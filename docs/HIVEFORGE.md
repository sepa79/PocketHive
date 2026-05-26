# HiveForge Deployment

PocketHive declares a HiveForge POC manifest at the repository root:
`hiveforge.yaml`.

The current HiveForge implementation is repository/ref-driven and runs declared
component actions through Ansible. PocketHive keeps image build and push outside
HiveForge; deploy actions consume registry-qualified images through explicit
runtime environment values.

## Current Supported Profiles

- `single-full` - deploys the existing local Docker Compose stack on a single
  Docker host by running `./build-hive.sh --quick`.
- `swarm-reduced` - deploys the portable reduced Docker Swarm runtime from
  HiveForge-managed compose/config artifacts and prebuilt images.
- `swarm-full` - prepares the Docker Swarm runtime with Postgres and ClickHouse
  data under explicit service-owned roots and placement labels; the remaining
  runtime config and state stays under the shared HiveForge project root. The
  action is declared but not wired yet.

The `single-full` profile intentionally maps to the existing canonical local
PocketHive entrypoint instead of inventing a second compose orchestration path.
The `swarm-reduced` profile uses `docker compose config` only to render the
managed compose files, then deploys the rendered stack with `docker stack
deploy`. It does not build or push images.

## Component

- `stack` - the whole local PocketHive stack.

Actions:

- `deploy` - runs `./build-hive.sh --quick` for `single-full`; deploys the
  rendered Docker Stack for `swarm-reduced`
- `update` - runs `./build-hive.sh --quick` for `single-full`; redeploys the
  rendered Docker Stack for `swarm-reduced`
- `remove` - runs `./build-hive.sh --clean` for `single-full`; removes the
  Docker Stack for `swarm-reduced`

## Example HiveForge Registry

HiveForge keeps project registry and environment policy outside the project
repository. For a local development registration, use a registry entry shaped
like:

```yaml
projects:
  - id: pockethive
    name: PocketHive
    source: github
    repository: https://github.com/sepa79/PocketHive.git
    approvedRefs:
      - main
```

The matching local environment policy must explicitly allow the project,
profile, and action:

```yaml
current: local
environments:
  - id: local
    name: Local Docker
    kind: local-docker
    capabilities:
      runtime:
        - docker-single
      managedRoot:
        shared: false
        nodes:
          - local-docker
    policy:
      projects:
        - id: pockethive
          profiles:
            - single-full
          actions:
            - deploy
            - remove
            - update
```

For `swarm-reduced`, HiveForge runtime environment must include these explicit
non-secret values before `start_action`. Agents should set them before
`validate_requirements` so the profile scope is ready for the whole deployment
flow:

```text
DOCKER_REGISTRY=192.168.88.54:5000/pockethive/
POCKETHIVE_VERSION=dev-YYYYMMDD-HHMM-g<sha>
POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX=192.168.88.54:5000/pockethive
POCKETHIVE_STACK_NAME=pockethive
```

`DOCKER_REGISTRY` must include the trailing slash because the base compose file
concatenates it directly with image names. `POCKETHIVE_VERSION=latest` is
rejected.

HiveForge copies managed runtime files from the checked-out repo into its
container-visible managed project root under:

```text
HIVEFORGE_PROJECT_DIR/artifacts/pockethive-runtime/
```

For `swarm-reduced`, HiveForge must also provide `HIVEFORGE_PROJECT_HOST_DIR`.
PocketHive reads prepared compose/config files through `HIVEFORGE_PROJECT_DIR`,
but renders Docker Stack bind sources with `HIVEFORGE_PROJECT_HOST_DIR` because
those paths are resolved by the target Docker daemon, not by the HiveForge
container.

Expected managed files for `swarm-reduced`:

```text
artifacts/pockethive-runtime/compose/docker-compose.yml
artifacts/pockethive-runtime/compose/compose.swarm.yml
artifacts/pockethive-runtime/config/rabbitmq/rabbitmq.conf
artifacts/pockethive-runtime/config/clickhouse/init/01-ph-tx-outcome-v1.sql
artifacts/pockethive-runtime/scenarios
```
