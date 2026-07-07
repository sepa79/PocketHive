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
  roots for RabbitMQ, Postgres, ClickHouse, and Redis. ClickHouse owns product
  metrics. Grafana and the remaining runtime config/state stay under the shared
  HiveForge project root.

The `single-full` profile intentionally maps to the existing canonical local
PocketHive entrypoint instead of inventing a second compose orchestration path.
The `swarm-reduced` and `swarm-full` profiles render a Docker Stack compose file
from the Ansible Jinja2 template, then HiveForge deploys the rendered stack with
`docker stack deploy`. They do not build or push images.

For `swarm-reduced` and `swarm-full`, scenario/runtime workloads assume the HiveForge-managed
project root is shared and available on every eligible Swarm node. PocketHive
therefore does not require `pockethive.scenarios` or `pockethive.sut` node
labels for Scenario Manager, Orchestrator, swarm controllers, dynamically
launched workers, or bundled SUT/mock services. It also does not require a
`pockethive.proxy` node label for HAProxy, Network Proxy Manager, Toxiproxy,
Redis, or UI services because their state and config are either shared under
the HiveForge-managed project root or stateless. Swarm controllers still run on
manager nodes because they need Docker Swarm API access. Stateful placement
remains explicit where the runtime profile declares dedicated roots.

## HiveForge Path Contract

PocketHive Ansible actions run inside the HiveForge action container. They must
read and write the project action root, not Docker-daemon host paths.

The action-root constant is:

```yaml
hiveforge_root: /hf
```

Use these paths exactly in PocketHive action playbooks:

| Purpose | Path or value | Used by |
| --- | --- | --- |
| Action root visible inside the Ansible container | `/hf` | Ansible reads/writes |
| Managed runtime artifacts copied by `artifacts.managedPaths` | `/hf/artifacts/runtime/...` | Ansible reads |
| Rendered Docker Stack file | `/hf/stacks/compose.yml` | Ansible writes, HiveForge deploys |
| HiveForge-managed runtime state dirs | `/hf/state/...` | Ansible creates/chowns |
| Docker-daemon host-visible bind root | `HIVEFORGE_BIND_SOURCE_DIR` | Stack template render only |
| Dedicated `swarm-full` service data dirs | `POCKETHIVE_*_ROOT` | Stack template render only |

`HIVEFORGE_BIND_SOURCE_DIR` is not the path that PocketHive Ansible should write
to. It is the host-visible equivalent of the same managed project root for the
target Docker daemon, for example `/opt/hiveforge/data/deployed/pockethive`.
PocketHive passes it into the Ansible Stack template so rendered bind mounts point
at paths Docker can resolve. The Ansible action itself must create shared state
through `/hf/state/...`.

The mapping is therefore:

```text
Ansible action path:      /hf/state/haproxy/runtime
Rendered Docker bind:     ${HIVEFORGE_BIND_SOURCE_DIR}/state/haproxy/runtime
Example Docker host path: /opt/hiveforge/data/deployed/pockethive/state/haproxy/runtime
```

`artifacts.managedPaths` only prepares release/runtime files under
`/hf/artifacts/runtime/...`. Runtime state such as `/hf/state/grafana/data` and
`/hf/state/tcp-mock/data` is not a managed artifact; the PocketHive action must
create it before the rendered stack is handed back to HiveForge.

In `swarm-full`, HiveForge only creates directories under its managed project
root. It never creates, chmods, or chowns dedicated service mount roots. Those
paths must already exist on nodes selected by the matching placement labels.

Both Swarm profiles override the base Postgres healthcheck with a longer
startup grace period. Fresh Postgres data directories on shared or remote
storage can spend more than the local-compose default in `initdb`; Swarm must
not kill that first task before the entrypoint finishes creating the database
and host authentication rules.

## Declared Runtime Requirements

The `stack` component manifest declares these non-secret runtime environment
requirements so `validate_requirements` can fail before the playbook starts:

```text
DOCKER_REGISTRY
POCKETHIVE_VERSION
POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX
POCKETHIVE_STACK_NAME
```

`DOCKER_REGISTRY` must include the trailing slash and must equal
`POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX + "/"`.
`POCKETHIVE_VERSION` must be set explicitly. `latest` is allowed only when the
operator sets it intentionally; HiveForge must not infer it from a missing
value.

Current HiveForge component requirements are global per component, not
profile-specific. Because of that, `swarm-full` dedicated root variables are
documented here and validated by the playbook instead of being declared in
`requirements.environment`, which would incorrectly make `swarm-reduced`
require them too.

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
       POCKETHIVE_RABBITMQ_ROOT: /data/rabbitmq
       POCKETHIVE_POSTGRES_ROOT: /data/postgres
       POCKETHIVE_CLICKHOUSE_ROOT: /data/clickhouse
       POCKETHIVE_REDIS_ROOT: /data/redis
       HTTP_PROXY: http://proxy.example:3128
       HTTPS_PROXY: http://proxy.example:3128
       NO_PROXY: localhost,127.0.0.1,::1,clickhouse,rabbitmq,postgres,redis,scenario-manager,orchestrator,auth-service,network-proxy-manager,ui,ui-v2
   ```

   Set `HTTP_PROXY`, `HTTPS_PROXY`, and `NO_PROXY` only when the target
   environment requires outbound proxy access. PocketHive renders those values
   into Grafana so `GF_INSTALL_PLUGINS` can download
   `grafana-clickhouse-datasource`; it also renders lowercase proxy variables
   from the same uppercase values for tools that read lowercase names.

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
allowed only as an explicit operator choice.

`swarm-full` requires the same values plus explicit dedicated data directories.
Each value must be the exact host directory mounted into the service container;
PocketHive does not append another `/data` segment:

```text
POCKETHIVE_RABBITMQ_ROOT=/data/rabbitmq
POCKETHIVE_POSTGRES_ROOT=/data/postgres
POCKETHIVE_CLICKHOUSE_ROOT=/data/clickhouse
POCKETHIVE_REDIS_ROOT=/data/redis
```

Those directories are Docker-daemon host paths, not HiveForge container paths.
They must exist on nodes with the corresponding labels:

```text
node.labels.pockethive.rabbitmq == true
node.labels.pockethive.postgres == true
node.labels.pockethive.clickhouse == true
node.labels.pockethive.redis == true
```

HiveForge copies managed runtime files from the checked-out repo into the
container-visible action root under:

```text
/hf/artifacts/runtime/
```

For `swarm-reduced` and `swarm-full`, HiveForge must also provide
`HIVEFORGE_BIND_SOURCE_DIR`. PocketHive reads prepared runtime config files and
creates shared runtime state through `/hf`, but renders Docker Stack bind
sources with `HIVEFORGE_BIND_SOURCE_DIR` because those paths are resolved by the
target Docker daemon, not by the HiveForge action container.

Expected managed files for `swarm-reduced`:

```text
artifacts/runtime/config/rabbitmq/rabbitmq.conf
artifacts/runtime/config/clickhouse/init/02-ph-tx-outcome-v2.sql
artifacts/runtime/config/clickhouse/clickhouse-entrypoint.sh
artifacts/runtime/config/clickhouse/migrate-tx-outcome-v1-to-v2.sh
artifacts/runtime/scenarios
artifacts/runtime/scenario-manager/capabilities
artifacts/runtime/scenario-manager/network
artifacts/runtime/scenario-manager/sut
```
