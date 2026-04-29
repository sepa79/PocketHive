# Proxmox Remote Swarm Deploy Plan

Status: in progress

## Goal

Move PocketHive testing from flaky local WSL Docker to the Proxmox host at
`192.168.88.12`, using an explicit local Docker registry first, then a
single-node PocketHive runtime through that registry, and only after that a
controlled upgrade path to full Docker Swarm service mode.

This plan is intentionally staged. Each phase has a concrete validation gate
before the next phase starts.

## Guardrails

- Follow `AGENTS.md` non-negotiables: explicit config, no implicit fallback
  chains, no direct service-port tests as substitutes for official ingress.
- Use `project_id=pockethive` in the global HiveMind project memory when the
  client exposes it.
- Do not create repo-local HiveMind storage and do not start a local HiveMind
  API as a fallback.
- Use explicit image tags for deploys; do not rely on mutable `latest` for
  remote testing.
- Keep `DOCKER_SINGLE` as the first remote runtime target. Treat full
  `SWARM_STACK` as a later upgrade with its own acceptance gates.

## HiveMind Note

The repository workflow requires the globally configured HiveMind server for
durable project memory. In this session no HiveMind MCP resources/tools were
exposed, so this plan is recorded in-repo only. Do not create a local fallback.
When HiveMind is available, record this work under:

```text
project_id=pockethive
workspace_path=/home/sepa/PocketHive
```

Minimum HiveMind entries to add later:

- Decision: remote deployment will start with registry-backed `DOCKER_SINGLE`.
- Risk: full Swarm services need static/shared mounts and placement rules.
- Progress: link this plan file and each completed phase gate.

## Phase 0 - Baseline Inventory

- [ ] Confirm Proxmox host access path for operators:
  - Docker endpoint available on `192.168.88.12`.
  - Docker Swarm manager status confirmed.
  - Portainer URL and admin access confirmed by a human.
- [ ] Confirm target ports intended for official ingress:
  - UI / nginx ingress: `http://192.168.88.12:8088`
  - Orchestrator through ingress: `/orchestrator`
  - Scenario Manager through ingress: `/scenario-manager`
  - Network Proxy Manager through ingress: `/network-proxy-manager`
- [ ] Decide the first explicit dev tag format, for example
  `dev-YYYYMMDD-HHMM` or `dev-<git-sha>`.
- [ ] Confirm whether the registry is HTTP-only or TLS-backed.

Gate: remote host shape and tag convention are explicit.

## Phase 1 - Remote Registry Support

Objective: make PocketHive images buildable, taggable, and pushable to the
remote registry with one explicit command path.

- [ ] Add a script or documented command path for building and tagging all
  PocketHive application images with:
  - registry host, for example `192.168.88.12:5000`
  - repository namespace, for example `pockethive`
  - explicit version tag
- [ ] Include every image used by `docker-compose.yml` and scenario plans:
  - `jvm-base`
  - `log-aggregator`
  - `auth-service`
  - `scenario-manager`
  - `network-proxy-manager`
  - `orchestrator`
  - `tcp-mock-server`
  - `network-proxy-haproxy`
  - `ui`
  - `ui-v2`
  - `swarm-controller`
  - `generator`
  - `request-builder`
  - `http-sequence`
  - `moderator`
  - `processor`
  - `postprocessor`
  - `clearing-export`
  - `trigger`
- [ ] Make script behavior explicit:
  - build only
  - tag only
  - push
  - optional subset by service
- [ ] Avoid implicit fallbacks. Missing registry/tag should fail fast.

Gate: local machine can build and tag the complete image set with one explicit
registry/tag tuple.

## Phase 2 - Create And Verify The Registry

Objective: run a registry on `192.168.88.12` and verify pull/push behavior
before PocketHive depends on it.

- [ ] Deploy Docker registry on the Proxmox host, initially as an explicit
  infrastructure service.
- [ ] Decide registry persistence path, for example:
  `/opt/pockethive/registry`.
- [ ] Configure Docker daemon trust if the registry is HTTP-only:
  `insecure-registries=["192.168.88.12:5000"]` on every node that will pull.
- [ ] Verify from the build machine:
  - `docker login` if auth is enabled.
  - push a small test image.
  - pull the same image back.
- [ ] Verify from the Proxmox Docker manager node:
  - pull the same test image.
  - remove it and pull again.

Gate: all Docker nodes that may run PocketHive tasks can pull from the registry.

## Phase 3 - Push PocketHive Images

Objective: prove the complete PocketHive image set is available from the
registry.

- [ ] Build all PocketHive artifacts locally using the normal Java 21 path.
- [ ] Build all app and worker images.
- [ ] Tag all images under:
  `192.168.88.12:5000/pockethive/<image>:<version>`.
- [ ] Push all images.
- [ ] Run a registry catalog/tag check and save the output in the work log.
- [ ] Pull a representative subset on the Proxmox host:
  - `orchestrator`
  - `scenario-manager`
  - `swarm-controller`
  - `processor`
  - `ui`

Gate: core service and worker images pull cleanly from Proxmox.

## Phase 4 - Single-Node Remote Deployment Using Registry

Objective: run PocketHive on the Proxmox Docker manager using registry images,
but keep worker provisioning in `DOCKER_SINGLE`.

- [ ] Prepare a remote deployment env file or compose override with:
  - `DOCKER_REGISTRY=192.168.88.12:5000/pockethive/`
  - `POCKETHIVE_VERSION=<explicit-version>`
  - `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX=192.168.88.12:5000/pockethive`
  - `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_DOCKER_COMPUTE_ADAPTER=DOCKER_SINGLE`
- [ ] Prepare required host paths on the Proxmox manager:
  - `/opt/pockethive/scenarios-runtime`
  - registry data path from Phase 2
  - bind-mounted config/source paths needed by the compose stack
- [ ] Deploy core stack through Portainer or `docker compose` on the manager.
- [ ] Verify official ingress only:
  - `GET http://192.168.88.12:8088/healthz`
  - `GET http://192.168.88.12:8088/orchestrator/actuator/health`
  - `GET http://192.168.88.12:8088/scenario-manager/actuator/health`
- [ ] Create one smoke swarm from UI or official Orchestrator ingress.
- [ ] Confirm swarm-controller and worker containers are created on the
  Proxmox manager and use registry-qualified images.

Gate: one full lifecycle scenario runs through remote ingress while workers are
still single-node Docker containers.

## Phase 5 - E2E Harness Remote Mode

Objective: make the E2E harness run against the remote deployment without
rewriting tests around direct service ports.

- [ ] Add or document a remote env profile for:
  - `UI_BASE_URL=http://192.168.88.12:8088`
  - `UI_WEBSOCKET_URI=ws://192.168.88.12:8088/ws`
  - `ORCHESTRATOR_BASE_URL=http://192.168.88.12:8088/orchestrator`
  - `SCENARIO_MANAGER_BASE_URL=http://192.168.88.12:8088/scenario-manager`
  - `NETWORK_PROXY_MANAGER_BASE_URL=http://192.168.88.12:8088/network-proxy-manager`
  - `AUTH_SERVICE_BASE_URL=http://192.168.88.12:1083`
  - `RABBITMQ_HOST=192.168.88.12`
  - `RABBITMQ_MANAGEMENT_BASE_URL=http://192.168.88.12:15672/rabbitmq/api`
- [ ] Run `./start-e2e-tests.sh --group smoke` against remote.
- [ ] Run `./start-e2e-tests.sh --group lifecycle` against remote.
- [ ] Identify tests that still assume local direct ports or local Docker state.
- [ ] Convert those tests incrementally to official ingress/API paths or mark
  them as explicit service-interface checks only when approved.

Gate: smoke and a small lifecycle pack pass from the local workstation against
the remote single-node deployment.

## Phase 6 - Service-By-Service Hardening For Swarm

Objective: make the platform ready for Swarm service scheduling before enabling
`SWARM_STACK`.

- [ ] Convert or document static mounts for every stateful/bind-mounted service:
  - RabbitMQ definitions/config/data
  - Postgres data
  - ClickHouse init/data
  - Grafana provisioning/dashboards/data
  - Loki config/data
  - Scenario files
  - scenario runtime root
  - TCP mock mappings/files/data
  - HAProxy runtime volume
- [ ] Decide per-service placement constraints:
  - manager-only services
  - services requiring local bind mounts
  - services safe on workers
- [ ] Add static node labels for mount-bearing nodes.
- [ ] Add Swarm deploy constraints one service at a time.
- [ ] Validate each service after adding constraints through official ingress
  or its documented service interface.

Gate: the core stack can be deployed as Swarm services with predictable data
placement and no accidental scheduling onto nodes without required mounts.

## Phase 7 - Upgrade PocketHive Runtime To Full Swarm

Objective: switch swarm-controller and workers from `DOCKER_SINGLE` containers
to Swarm services only after the platform is stable.

- [ ] Enable:
  `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_DOCKER_COMPUTE_ADAPTER=SWARM_STACK`
  in a dedicated test deployment.
- [ ] Add or verify placement constraints for swarm-controller services.
- [ ] Decide how worker services receive required static mounts:
  - shared path on all eligible nodes, or
  - node labels and placement constraints, or
  - remove bind dependency from the worker path.
- [ ] Verify controller service creation.
- [ ] Verify worker service creation role by role:
  - `generator`
  - `moderator`
  - `request-builder`
  - `http-sequence`
  - `processor`
  - `postprocessor`
  - `clearing-export`
  - `trigger`
- [ ] Run lifecycle E2E after each meaningful service group.
- [ ] Add cleanup verification for service removal and RabbitMQ queue cleanup.

Gate: full `SWARM_STACK` lifecycle passes for at least one representative
scenario and leaves no stale Swarm services after remove.

## Phase 8 - Broaden E2E Coverage

Objective: expand remote E2E coverage after full Swarm basics are proven.

- [ ] Re-run groups in this order:
  - `smoke`
  - `contracts`
  - `lifecycle`
  - `proxy`
  - `data`
  - `exports`
- [ ] For each group, classify failures as:
  - deployment/config issue
  - E2E remote-mode assumption
  - PocketHive runtime bug
  - Swarm scheduling/storage issue
- [ ] Fix one class at a time. Do not add fallback behavior to make tests pass.

Gate: remote Swarm can replace local WSL Docker for the normal PocketHive test
loop.

## Known Risks

- `docker stack deploy` does not honor Compose `depends_on.condition`; service
  readiness may require explicit retry behavior or manual staged deployment.
- Current scenarios use plain image names like `processor:latest`; remote
  deployment depends on Orchestrator image prefixing and explicit tags.
- Worker bind mounts require identical host paths on every node that may run a
  worker, or placement constraints that keep workers on prepared nodes.
- `SWARM_STACK` currently creates one Swarm service per manager/worker and is
  intentionally minimal; reconciliation and placement need careful testing.
- E2E harness still has direct dependencies on RabbitMQ AMQP/management and
  selected service interfaces. Those must stay explicit and must not replace
  official ingress checks.

## Immediate Next Work Items

1. Implement the remote image build/tag/push script.
2. Add a documented registry deployment snippet for `192.168.88.12:5000`.
3. Add a remote E2E env example for the Proxmox target.
4. Deploy the registry and verify test image push/pull.
5. Push PocketHive images with one explicit version tag.
