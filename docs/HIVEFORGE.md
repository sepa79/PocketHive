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
- `swarm-full` - deploys the Docker Swarm runtime with dedicated service-owned
  roots for RabbitMQ, Postgres, ClickHouse, Prometheus, Loki, and Redis. Grafana
  and the remaining runtime config/state stay under the shared HiveForge project
  root.

The `single-full` profile intentionally maps to the existing canonical local
PocketHive entrypoint instead of inventing a second compose orchestration path.
The `swarm-reduced` profile uses `docker compose config` only to render the
managed compose files, then deploys the rendered stack with `docker stack
deploy`. It does not build or push images.

For `swarm-reduced` and `swarm-full`, scenario/runtime workloads assume the HiveForge-managed
project root is shared and available on every eligible Swarm node. PocketHive
therefore does not require a `pockethive.scenarios` node label for
Scenario Manager, Orchestrator, swarm controllers, or dynamically launched
workers. Swarm controllers still run on manager nodes because they need Docker
Swarm API access. Proxy/SUT/stateful placement remains explicit where the
runtime profile declares it.

In `swarm-full`, HiveForge only creates directories under its managed project
root. It never creates, chmods, or chowns dedicated service mount roots. Those
paths must already exist on nodes selected by the matching placement labels.

## Component

- `stack` - the whole local PocketHive stack.

Actions:

- `deploy` - runs `./build-hive.sh --quick` for `single-full`; deploys the
  rendered Docker Stack for `swarm-reduced` and `swarm-full`
- `update` - runs `./build-hive.sh --quick` for `single-full`; redeploys the
  rendered Docker Stack for `swarm-reduced` and `swarm-full`
- `remove` - runs `./build-hive.sh --clean` for `single-full`; removes the
  Docker Stack for `swarm-reduced` and `swarm-full`

## Agent MCP Deploy Checklist

When a human asks an agent to deploy PocketHive through HiveForge, use the
HiveForge MCP tools only. Do not SSH to hosts, inspect Proxmox, run Docker
commands locally against the target, or add deployment workarounds. Treat
HiveForge validation failures as explicit configuration gaps to fix through
HiveForge MCP configuration.

For the large Docker Swarm environment currently exposed by HiveForge as
`environmentId=swarm`, the production-like PocketHive stack profile is
`swarm-full`.

Agent sequence:

1. Use HiveMind MCP for work context with `project_id=pockethive`.
2. Confirm HiveForge is reachable with `check_health`.
3. Confirm project/policy with `list_projects` and `list_environments`.
4. If needed, allow PocketHive on the swarm environment:

   ```text
   set_environment_project_policy:
     environmentId: swarm
     projectId: pockethive
     profiles: [swarm-full]
     actions: [deploy, update, remove]
   ```

5. Set non-secret runtime env before `validate_requirements` or `start_action`.
   For a release tag such as `v0.15.24`, use `POCKETHIVE_VERSION=0.15.24`
   without the leading `v`:

   ```text
   set_project_runtime_env:
     projectId: pockethive
     profile: swarm-full
     values:
       DOCKER_REGISTRY: ghcr.io/sepa79/pockethive/
       POCKETHIVE_VERSION: <release version without leading v>
       POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX: ghcr.io/sepa79/pockethive
       POCKETHIVE_STACK_NAME: pockethive
       POCKETHIVE_RABBITMQ_ROOT: /opt/pockethive-data/rabbitmq
       POCKETHIVE_POSTGRES_ROOT: /opt/pockethive-data/postgres
       POCKETHIVE_CLICKHOUSE_ROOT: /opt/pockethive-data/clickhouse
       POCKETHIVE_PROMETHEUS_ROOT: /opt/pockethive-data/prometheus
       POCKETHIVE_LOKI_ROOT: /opt/pockethive-data/loki
       POCKETHIVE_REDIS_ROOT: /opt/pockethive-data/redis
   ```

6. Start the lifecycle action through HiveForge MCP:

   ```text
   start_action:
     projectId: pockethive
     gitRef: v<release version>
     component: stack
     action: update
     profile: swarm-full
   ```

7. Poll only through `get_operation`. Verify completion with
   `list_deployments` and/or `read_journal`.

The synchronous `validate_requirements` and repository inspection calls may
time out for PocketHive because checkout/managed-artifact preparation is large.
Prefer `start_action` plus `get_operation` for the actual deployment workflow.

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

`swarm-full` requires the same values plus explicit dedicated mount roots:

```text
POCKETHIVE_RABBITMQ_ROOT=/opt/pockethive-data/rabbitmq
POCKETHIVE_POSTGRES_ROOT=/opt/pockethive-data/postgres
POCKETHIVE_CLICKHOUSE_ROOT=/opt/pockethive-data/clickhouse
POCKETHIVE_PROMETHEUS_ROOT=/opt/pockethive-data/prometheus
POCKETHIVE_LOKI_ROOT=/opt/pockethive-data/loki
POCKETHIVE_REDIS_ROOT=/opt/pockethive-data/redis
```

Those roots are Docker-daemon host paths, not HiveForge container paths. They
must exist on nodes with the corresponding labels:

```text
node.labels.pockethive.rabbitmq == true
node.labels.pockethive.postgres == true
node.labels.pockethive.clickhouse == true
node.labels.pockethive.prometheus == true
node.labels.pockethive.loki == true
node.labels.pockethive.redis == true
```

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
artifacts/pockethive-runtime/config/clickhouse/init/02-ph-tx-outcome-v2.sql
artifacts/pockethive-runtime/config/clickhouse/clickhouse-entrypoint.sh
artifacts/pockethive-runtime/config/clickhouse/migrate-tx-outcome-v1-to-v2.sh
artifacts/pockethive-runtime/scenarios
artifacts/pockethive-runtime/scenario-manager/capabilities
artifacts/pockethive-runtime/scenario-manager/network
artifacts/pockethive-runtime/scenario-manager/sut
```

`swarm-full` also requires:

```text
artifacts/pockethive-runtime/compose/compose.swarm-full.yml
```
