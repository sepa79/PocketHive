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
- Capability metadata lookup must not depend on image version tags. The
  canonical key is the image name without registry, namespace, tag, or digest
  for now, for example `192.168.88.54:5000/pockethive/processor:0.15.17`
  resolves capabilities for `processor`. Add capability versions only when
  there is a real need to run multiple incompatible capability contracts.
- Keep `DOCKER_SINGLE` as the first remote runtime target. Treat full
  `SWARM_STACK` as a later upgrade with its own acceptance gates.

## HiveMind Note

The repository workflow requires the globally configured HiveMind server for
durable project memory. Earlier in this work the HiveMind server was visible to
VS Code but not to Codex because it was registered only in the VS Code MCP
configuration. The fix was to add the HiveMind server to Codex MCP directly.

Current HiveMind target:

```text
project_id=pockethive
workspace_path=/home/sepa/PocketHive
```

Recorded HiveMind state:

- Decision: runtime/E2E use `192.168.88.50`; `192.168.88.12` is only the
  Proxmox operator host.
- Decision: image metadata uses `tools/docker/image-manifest.sh` as SSOT for
  local and remote build tooling.
- Decision: split stable infrastructure from disposable PocketHive runtime
  tests. HiveMind and the Docker registry now run on `docker-infra-1`
  (`192.168.88.54`). Single-node PocketHive tests should use
  `pockethive-single-1` (`192.168.88.55`).
- Progress: Phase 2 registry deployed and verified.
- Progress: Proxmox now has dedicated Docker LXC nodes for infrastructure and
  single-node PocketHive runtime tests.
- Tooling note: Codex can now use HiveMind MCP after the MCP config move.

Do not create repo-local HiveMind storage and do not start a local HiveMind API
as a fallback.

## Current Execution Plan

1. Stabilize the remote target split:
   - HiveMind is already moved to `docker-infra-1` (`192.168.88.54`) as a
     standalone Docker Compose service.
   - Docker registry is already moved to `docker-infra-1`
     (`192.168.88.54:5000`) as a standalone Docker Compose service.
   - keep `192.168.88.50`-`192.168.88.53` as Swarm/Portainer nodes without
     HiveMind or registry dependencies.
   - use `pockethive-single-1` (`192.168.88.55`) for the next single-node
     PocketHive runtime pass.
2. Continue Phase 4 on `pockethive-single-1`: deploy a reduced single-node
   PocketHive runtime using registry-qualified images and `DOCKER_SINGLE`.
3. Remove the tag-to-capabilities dependency. Capability lookup should use the
   canonical image name without tag, so `processor:0.14`, `processor:0.15`,
   `processor:latest`, and registry-qualified dev tags all resolve the same
   `processor` capability metadata unless a future explicit versioned
   capability contract is introduced.
4. Modify the E2E harness in small steps so it can run against the remote
   ingress/API surface without substituting direct backend service ports.
5. Only after single-node runtime and E2E smoke/lifecycle checks pass, harden
   service-by-service for full Swarm mode, including explicit static/shared
   mounts and placement rules.

## Phase 0 - Baseline Inventory

- Status: complete. Proxmox, Swarm nodes, Portainer, HiveMind API, initial
  ingress target, dev tag format, registry mode, and dedicated infra/test
  nodes are identified.
- Inventory run: 2026-04-29 from `/home/sepa/PocketHive`.
- Proxmox:
  - Host: `serverro`
  - IP: `192.168.88.12`
  - Version: `pve-manager/9.1.7`
  - SSH: `root@192.168.88.12` with `~/.ssh/docker-swarm-lxc_ed25519`
- Proxmox guests:
  - LXC `240`: `docker-swarm-mgr-1`, `192.168.88.50`, running.
  - LXC `241`: `docker-swarm-mgr-2`, `192.168.88.51`, running.
  - LXC `242`: `docker-swarm-mgr-3`, `192.168.88.52`, running.
  - LXC `243`: `docker-swarm-wrk-1`, `192.168.88.53`, running.
  - LXC `254`: `docker-infra-1`, `192.168.88.54`, running.
  - LXC `255`: `pockethive-single-1`, `192.168.88.55`, running.
  - VM `102`: `lamp`, stopped.
  - VM `103`: `valheim`, running.
- Proxmox resource update on 2026-04-29:
  - Swarm nodes `240`-`243`: `4` vCPU, `8192` MiB RAM each.
  - Infrastructure/test nodes `254`-`255`: `4` vCPU, `12288` MiB RAM each.
  - New nodes were full clones of `docker-swarm-wrk-1`; their Docker state,
    Swarm identity, and machine-id were reset after cloning.
  - New nodes are Docker-ready but intentionally not part of any Swarm:
    `docker info` reports `Swarm=inactive`.
  - Docker nodes trust the current dev registry:
    `192.168.88.54:5000`.
  - New nodes use `containerd` root at `/var/lib/docker/containerd` so image
    layer storage stays on the 80 GiB Docker mount.
- Docker Swarm:
  - `docker-swarm-mgr-1` (`192.168.88.50`): manager, leader.
  - `docker-swarm-mgr-2` (`192.168.88.51`): manager, reachable.
  - `docker-swarm-mgr-3` (`192.168.88.52`): manager, reachable.
  - `docker-swarm-wrk-1` (`192.168.88.53`): worker.
  - Docker version on Swarm nodes: `29.4.1`.
- Existing Swarm stacks:
  - `portainer`: `portainer_portainer`, `portainer_agent`.
  - HiveMind was removed from Swarm after migration to `docker-infra-1`.
- Existing service endpoints:
  - Portainer UI: `https://192.168.88.50:9443` and `http://192.168.88.50:9000`.
  - Portainer tunnel/edge port: `192.168.88.50:8000`.
  - HiveMind API: `http://192.168.88.54:4010` (`/health` returns 200).
  - Docker registry: `http://192.168.88.54:5000`.
- [x] Confirm Proxmox host access path for operators:
  - SSH endpoint is reachable on `192.168.88.12:22`.
  - Docker Swarm manager access is available through `root@192.168.88.50`.
  - Docker Swarm manager status is confirmed.
  - Portainer URL is confirmed.
- [x] Confirm target ports intended for official ingress:
  - Proxmox `192.168.88.12` is not a Docker node; use a Swarm node IP unless a
    Proxmox reverse proxy/NAT is added.
  - Initial UI / nginx ingress candidate: `http://192.168.88.50:8088`
  - Orchestrator through ingress: `/orchestrator`
  - Scenario Manager through ingress: `/scenario-manager`
  - Network Proxy Manager through ingress: `/network-proxy-manager`
- [x] Decide the first explicit dev tag format:
  `dev-YYYYMMDD-HHMM-g<short-sha>`.
- [x] Confirm whether the registry is HTTP-only or TLS-backed:
  current registry is HTTP-only on `192.168.88.54:5000`, with explicit
  `insecure-registries` configuration on every node that must pull from it.

Gate: remote host shape and tag convention are explicit.

Phase 0 next required inputs:

- None for Phase 1. `192.168.88.12` is the Proxmox operator host only and must
  stay out of the E2E/runtime access path. Remote deployment and tests use the
  Swarm leader endpoint `192.168.88.50` unless a later explicit decision changes
  that target.

## Phase 1 - Remote Registry Support

Objective: make PocketHive images buildable, taggable, and pushable to the
remote registry with one explicit command path.

- Status: complete for tooling support. Full push validation waits for Phase 2,
  because the registry is not running yet.
- [x] Add a single image manifest as SSOT:
  `tools/docker/image-manifest.sh`.
- [x] Refactor local and remote image tooling to use the manifest:
  - `build-hive.sh`
  - `tools/docker/remote-images.sh`
- [x] Add a script or documented command path for building and tagging all
  PocketHive application images with:
  - registry host, for example `192.168.88.54:5000`
  - repository namespace, for example `pockethive`
  - explicit version tag
- [x] Include every image used by `docker-compose.yml` and scenario plans:
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
- [x] Make script behavior explicit:
  - build and tag without push
  - build, tag, and push with `--push`
  - dry-run
  - optional subset by image
- [x] Avoid implicit fallbacks. Missing registry/tag should fail fast.
- [x] Verify script syntax and dry-run command expansion.
- [x] Verify one real local image build/tag:
  `192.168.88.50:5000/pockethive/jvm-base:dev-20260429-1330-gphase1`.

Gate: local machine can build and tag the complete image set with one explicit
registry/tag tuple. Full complete-set build remains a Phase 3 validation item
after the registry is available.

## Phase 2 - Create And Verify The Registry

Objective: run a registry on `192.168.88.50` and verify pull/push behavior
before PocketHive depends on it.

- Status: complete.
- Result:
  - Registry service: `pockethive-registry`.
  - Image: `registry:2`.
  - Published endpoint: `http://192.168.88.50:5000`.
  - Placement: constrained to `node.hostname==docker-swarm-mgr-1`.
  - Persistence path: `/opt/pockethive/registry`.
  - Smoke image pushed from Swarm manager:
    `192.168.88.50:5000/pockethive/registry-smoke:phase2`.
  - PocketHive base image pushed from build machine:
    `192.168.88.50:5000/pockethive/jvm-base:dev-20260429-1330-gphase1`.
  - Worker pull verified from `docker-swarm-wrk-1`.
  - Note: after restarting Docker on `docker-swarm-mgr-1`, Swarm leadership
    moved to `docker-swarm-mgr-2`; the registry task remains on
    `docker-swarm-mgr-1` as intended.
  - Superseded on 2026-04-29: registry data was copied to
    `docker-infra-1` (`192.168.88.54`), and the Swarm service
    `pockethive-registry` was removed from `.50`.
- [x] Deploy Docker registry on the Swarm leader (`192.168.88.50`), initially
  as an explicit infrastructure service.
- [x] Decide registry persistence path:
  `/opt/pockethive/registry`.
- [x] Configure Docker daemon trust because the registry is HTTP-only:
  `insecure-registries=["192.168.88.50:5000"]` on every node that will pull.
- [x] Verify from the build machine:
  - no `docker login`; registry has no auth in this dev phase.
  - push a test PocketHive image.
  - remove and pull the same image back.
- [x] Verify from a Swarm node:
  - push/pull smoke image from manager.
  - remove and pull PocketHive base image from worker.

Gate: all Docker nodes that may run PocketHive tasks can pull from the registry.

## Phase 2b - Move Registry To docker-infra-1

Objective: keep `192.168.88.50`-`192.168.88.53` as clean Swarm/Portainer nodes
so a Swarm runtime failure does not take down HiveMind or the image registry.

- Status: complete.
- Result:
  - Registry data copied from `.50:/opt/pockethive/registry` to
    `.54:/opt/pockethive/registry`.
  - Standalone Compose file created at
    `.54:/opt/pockethive/registry-compose/docker-compose.yml`.
  - Registry container `pockethive-registry` runs on `docker-infra-1` with
    `registry:2`, `restart: unless-stopped`, and port `5000`.
  - New endpoint: `http://192.168.88.54:5000`.
  - Old Swarm service `pockethive-registry` removed from `.50`.
  - Docker daemon `insecure-registries` on `.50`-`.53` and `.55` now points to
    only `192.168.88.54:5000`.
  - Validation:
    - `GET http://192.168.88.54:5000/v2/_catalog` lists all PocketHive repos.
    - `GET http://192.168.88.50:5000/v2/_catalog` fails because the old
      registry is no longer running.
    - `docker-swarm-wrk-1` pulled
      `192.168.88.54:5000/pockethive/registry-smoke:phase2`.
    - `pockethive-single-1` pulled
      `192.168.88.54:5000/pockethive/orchestrator:dev-20260429-1701-g6b3c9177-dirty`.

Gate: HiveMind and registry remain available from `docker-infra-1` while Swarm
nodes are free to be restarted or redeployed independently.

## Phase 3 - Push PocketHive Images

Objective: prove the complete PocketHive image set is available from the
registry.

- Status: complete.
- Result:
  - Explicit tag:
    `dev-20260429-1701-g6b3c9177-dirty`.
  - Dirty suffix is intentional because the image build/push tooling itself is
    still local WIP.
  - Full Maven package completed with `-DskipTests`.
  - Full image build and push completed through `tools/docker/remote-images.sh`.
  - Registry catalog contains all PocketHive repositories.
  - Remote pull verified from `docker-swarm-wrk-1` (`192.168.88.53`).
- [x] Build all PocketHive artifacts locally using the normal Java 21 path.
- [x] Build all app and worker images.
- [x] Tag all images under:
  `192.168.88.50:5000/pockethive/<image>:<version>`.
  This historical tag set has been migrated to
  `192.168.88.54:5000/pockethive/<image>:<version>`.
- [x] Push all images.
- [x] Run a registry catalog/tag check and save the output in the work log.
- [x] Pull a representative subset on the Proxmox host:
  - `orchestrator`
  - `scenario-manager`
  - `swarm-controller`
  - `processor`
  - `ui`

Gate: core service and worker images pull cleanly from Proxmox.

## Phase 4 - Single-Node Remote Deployment Using Registry

Objective: run PocketHive on the Proxmox Docker manager using registry images,
but keep worker provisioning in `DOCKER_SINGLE`.

- Status: partial. Core remote compose deployment was proven through ingress
  health checks, but the full local compose profile is too heavy for
  `docker-swarm-mgr-1` while HiveMind/OpenSearch also runs there.
- Result:
  - Added remote single-node override:
    `deploy/compose.proxmox-single-node.yml`.
  - Added explicit env template:
    `deploy/proxmox-single-node.env.example`.
  - Synced runtime bundle to `/opt/pockethive/runtime` on `192.168.88.50`.
  - Migrated `containerd` root on `docker-swarm-mgr-1` from the small root
    filesystem to `/var/lib/docker/containerd`; root free space improved from
    about 308 MiB to about 5.7 GiB.
  - Full compose stack started once and passed ingress health:
    - `GET http://192.168.88.50:8088/healthz` -> `ok`
    - `GET http://192.168.88.50:8088/orchestrator/actuator/health` -> `UP`
    - `GET http://192.168.88.50:8088/scenario-manager/actuator/health` -> `UP`
    - `GET http://192.168.88.50:8088/network-proxy-manager/actuator/health` -> `UP`
  - Added `proxmox-registry-smoke` scenario using explicit image tag
    `dev-20260429-1701-g6b3c9177-dirty`.
  - Create-swarm reached Orchestrator and resolved controller image to the
    registry-qualified explicit tag.
  - First create attempt failed because worker/controller images had not been
    pulled on `.50`; after pre-pulling the required images, create succeeded
    and controller status was visible.
  - Starting the full compose stack plus HiveMind/OpenSearch caused load to
    climb above 250 and Docker/SSH began timing out. The LXC was rebooted from
    Proxmox. Registry and HiveMind recovered; the compose runtime is currently
    not running.
- Decision: do not repeat the full compose profile on `.50`. Continue Phase 4
  on `pockethive-single-1` (`192.168.88.55`) with a reduced single-node profile
  that starts only the services required for the first lifecycle smoke.
- Decision: HiveMind and registry were moved off `.50` to `docker-infra-1`
  (`192.168.88.54`) as standalone Docker Compose services before repeated
  PocketHive runtime restarts.
- Result on `pockethive-single-1` (`192.168.88.55`):
  - Added reduced Compose override `deploy/compose.proxmox-reduced.yml`.
  - Added reduced UI ingress config `deploy/nginx.proxmox-reduced.conf` so the
    UI container does not require optional upstreams such as Prometheus,
    Grafana, Redis Commander, or WireMock.
  - Synced runtime bundle to `.55:/opt/pockethive/runtime`.
  - Started reduced services: rabbitmq, postgres, auth-service,
    log-aggregator, scenario-manager, orchestrator, ui, and pushgateway.
  - Extended the reduced runtime for remote E2E smoke with
    network-proxy-manager, toxiproxy, and haproxy because the smoke auth pack
    verifies Network Proxy Manager authorization via official ingress.
  - Extended the reduced runtime for remote lifecycle E2E with WireMock as the
    explicit SUT target. Copied `wiremock/mappings` and `wiremock/__files` to
    `.55:/opt/pockethive/runtime/wiremock` so the mounted WireMock instance
    serves the expected test stubs.
  - Added local `.55` Docker tags `192.168.88.54:5000/pockethive/<image>:latest`
    for the images used by existing `local-rest` auth smoke scenarios:
    swarm-controller, generator, moderator, processor, and postprocessor.
    This is explicit target preparation for current test scenarios that still
    declare `:latest`.
  - Verified official ingress on `.55`:
    - `GET http://192.168.88.55:8088/healthz` -> `ok`
    - `GET http://192.168.88.55:8088/orchestrator/actuator/health` -> `UP`
    - `GET http://192.168.88.55:8088/scenario-manager/actuator/health` -> `UP`
    - `GET http://192.168.88.55:8088/network-proxy-manager/actuator/health`
      -> `UP`
  - Created `proxmox-smoke-195109` through official Orchestrator ingress using
    `autoPullImages=true` and `networkMode=DIRECT`.
  - Confirmed controller and worker containers used registry-qualified images
    from `192.168.88.54:5000/pockethive`.
  - Started the smoke swarm; repeated status reads showed `RUNNING`, 3/3
    workers healthy, and 3/3 workers enabled.
  - Stopped and removed the smoke swarm through official Orchestrator ingress;
    no `proxmox-smoke-195109` containers remained afterward.
- [x] Prepare a remote deployment env file or compose override with:
  - `DOCKER_REGISTRY=192.168.88.54:5000/pockethive/`
  - `POCKETHIVE_VERSION=<explicit-version>`
  - `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX=192.168.88.54:5000/pockethive`
  - `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_DOCKER_COMPUTE_ADAPTER=DOCKER_SINGLE`
- [x] Prepare required host paths on the Proxmox manager:
  - `/opt/pockethive/scenarios-runtime`
  - registry data path from Phase 2
  - bind-mounted config/source paths needed by the compose stack
- [x] Deploy core stack through Portainer or `docker compose` on the manager.
- [x] Verify official ingress only:
  - `GET http://192.168.88.50:8088/healthz`
  - `GET http://192.168.88.50:8088/orchestrator/actuator/health`
  - `GET http://192.168.88.50:8088/scenario-manager/actuator/health`
- [x] Create one smoke swarm from UI or official Orchestrator ingress.
- [x] Confirm swarm-controller and worker containers are created on the
  Proxmox manager and use registry-qualified images.
- [x] Add a reduced remote compose profile for the first smoke pass:
  - required: rabbitmq, postgres, auth-service, log-aggregator,
    scenario-manager, orchestrator, ui, pushgateway, network-proxy-manager,
    toxiproxy, haproxy
  - required for lifecycle SUT checks: wiremock plus repository `wiremock/*`
    data mounted into the runtime directory.
  - optional/defer: clickhouse, grafana, loki, redis, redis-commander,
    tcp mocks
- [x] Add an explicit pre-pull or approved `autoPullImages` path for
  `DOCKER_SINGLE` smoke scenarios so controller/worker images are present on
  `.55` before create/start.
- [x] Add repeatable reduced runtime deploy helper:
  `tools/docker/proxmox-reduced-runtime.sh`.
  - Syncs `docker-compose.yml`, reduced/single-node compose overrides, reduced
    nginx config, `rabbitmq/`, `scenarios/`, and `wiremock/` to
    the configured remote runtime directory.
  - Requires explicit remote host/user/key/directory through CLI flags,
    `--config`, or `POCKETHIVE_PROXMOX_CONFIG`; the tracked template is
    `deploy/proxmox.env.example`.
  - Uses existing remote `.env` unless an explicit `--env-file` is provided.
  - Runs one explicit reduced `docker compose` command for the selected
    services.
  - Verified against `.55`; existing reduced runtime remained up/healthy.

Gate: one full lifecycle scenario runs through remote ingress while workers are
still single-node Docker containers.

## Phase 5 - E2E Harness Remote Mode

Objective: make the E2E harness run against the remote deployment without
rewriting tests around direct service ports.

- [x] Add or document a remote env profile:
  `deploy/e2e.proxmox-single-node.env`.
  - `UI_BASE_URL=http://192.168.88.55:8088`
  - `UI_WEBSOCKET_URI=ws://192.168.88.55:8088/ws`
  - `ORCHESTRATOR_BASE_URL=http://192.168.88.55:8088/orchestrator`
  - `SCENARIO_MANAGER_BASE_URL=http://192.168.88.55:8088/scenario-manager`
  - `NETWORK_PROXY_MANAGER_BASE_URL=http://192.168.88.55:8088/network-proxy-manager`
  - `AUTH_SERVICE_BASE_URL=http://192.168.88.55:8088/auth-service`
  - `RABBITMQ_HOST=192.168.88.55`
  - `RABBITMQ_MANAGEMENT_BASE_URL=http://192.168.88.55:15672/rabbitmq/api`
- [x] Run `./start-e2e-tests.sh --group smoke` against remote.
  - First run failed because the reduced UI ingress did not route
    `/network-proxy-manager/*`, and auth smoke create paths used existing
    `local-rest` templates that reference `:latest`.
  - After adding Network Proxy Manager ingress, starting NPM/toxiproxy/haproxy,
    and adding explicit local `.55` compatibility tags for the current dev
    images, the second run passed: `17 Scenarios`, `191 Steps`,
    `BUILD SUCCESS`.
  - Cleanup removed leftover auth smoke swarms through Orchestrator ingress;
    only the stable runtime stack remained.
- [x] Run a small lifecycle subset against remote:
  `./start-e2e-tests.sh --group lifecycle --tags "@golden-path or @gating or @templated-generator"`.
  - First run failed because the reduced runtime did not include WireMock, so
    processor workers emitted `runtime.exception / Processor request failed`
    and final queues stayed empty.
  - Second run, after starting WireMock, reached final queues but returned 404
    because the remote WireMock mount had no stub mappings.
  - Third run, after copying `wiremock/mappings` and `wiremock/__files` to the
    runtime host and restarting WireMock, passed: `3 Scenarios`, `33 Steps`,
    `BUILD SUCCESS`.
- [x] Broaden small lifecycle subset against remote:
  - `@scenario-variables`: passed, `1 Scenario`, `8 Steps`,
    `BUILD SUCCESS`.
  - `@workitem-headers`: passed, `1 Scenario`, `12 Steps`,
    `BUILD SUCCESS`.
  - Post-run cleanup check found no leftover swarm/e2e containers on `.55`.
- [x] Rebuild, redeploy, and retest after image-name-only capability lookup:
  - Built full image set with tag `dev-20260429-2300-g6b3c9177-dirty`.
  - Local Docker was still configured only for old HTTP registry
    `192.168.88.50:5000`, so local `docker push` to `.54` failed with
    `server gave HTTP response to HTTPS client`.
  - Workaround used without restarting local Docker: `docker save` selected
    local images, `ssh` to `.54`, `docker load`, then `docker push` from `.54`,
    whose daemon already trusts `192.168.88.54:5000` as an insecure registry.
  - Updated `.55:/opt/pockethive/runtime/.env` to
    `POCKETHIVE_VERSION=dev-20260429-2300-g6b3c9177-dirty`.
  - Redeployed reduced runtime on `.55` with
    `tools/docker/proxmox-reduced-runtime.sh --config <local-env> --pull`.
  - Verified `.55` runtime containers use the new tag for app services and
    official ingress health endpoints return `UP`/`ok`.
  - Verified `GET /scenario-manager/api/capabilities?all=true` no longer
    returns `X-Pockethive-Capability-Fallback-Tag`.
  - Verified capability lookup canonicalizes full image refs:
    `processor`,
    `192.168.88.54:5000/pockethive/processor:some-other-tag`, and
    `pockethive/processor@sha256:abc` all resolve to the `processor` manifest.
  - Smoke retest did not fully pass: `16/17` scenarios passed twice; the
    remaining auth rollout scenario exposed existing runtime races:
    first a 20s visibility timeout, then `POST /api/debug/taps` returned 500
    because RabbitMQ had no `ph.<swarm>.hive` exchange yet.
  - Lifecycle retest passed with:
    `./start-e2e-tests.sh --group lifecycle --tags "@golden-path or @gating or @templated-generator or @scenario-variables or @workitem-headers"`:
    `5 Scenarios`, `53 Steps`, `BUILD SUCCESS`.
  - Post-run cleanup check found no leftover swarm/e2e containers on `.55`.
- [x] Identify tests that still assume local direct ports or local Docker state.
  - Smoke uses RabbitMQ AMQP/management as explicit service-interface checks.
  - Auth smoke uses `local-rest` templates that still declare `:latest`, so
    remote targets must either provide explicit compatibility tags or the
    scenarios must be updated to explicit immutable tags.
- [ ] Convert those tests incrementally to official ingress/API paths or mark
  them as explicit service-interface checks only when approved.

Gate: smoke and a small lifecycle pack pass from the local workstation against
the remote single-node deployment.

## Phase 6 - Service-By-Service Hardening For Swarm

Objective: make the platform ready for Swarm service scheduling before enabling
`SWARM_STACK`.

- Current read-only inventory on 2026-04-29:
  - Swarm nodes are healthy:
    - `docker-swarm-mgr-1`: Ready, Active, Leader
    - `docker-swarm-mgr-2`: Ready, Active, Reachable
    - `docker-swarm-mgr-3`: Ready, Active, Reachable
    - `docker-swarm-wrk-1`: Ready, Active
  - Active Swarm stacks: only `portainer`.
  - Active Swarm services: `portainer_agent` (`4/4`) and
    `portainer_portainer` (`1/1`).
  - Direct SSH to `.50`/`.53` was flaky during this check; Proxmox
    `pct exec` through `.12` was reliable for read-only inventory.
  - `.50` still has stale `/opt/pockethive/runtime`,
    `/opt/pockethive/registry`, and old `scenarios-runtime` directories from
    earlier experiments. Do not treat those as approved shared Swarm mounts;
    either clean them explicitly or recreate a fresh, documented mount layout
    before any `SWARM_STACK` run.

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
- [x] Add Phase 6 read-only inventory and proposed placement/mount matrix:
  `docs/inProgress/proxmox-swarm-placement-inventory.md`.
- [x] Add repeatable read-only Proxmox inventory helper:
  `tools/docker/proxmox-swarm-inventory.sh`.
  - Helper is environment-agnostic: pass `--config <local-env>`, set
    `POCKETHIVE_PROXMOX_CONFIG`, or use explicit CLI flags. The tracked
    template is `deploy/proxmox.env.example`; local `.env` files under
    `deploy/` are intentionally ignored.
- [x] Prepare initial explicit Swarm mount root on `.50`:
  `/opt/pockethive/swarm`.
  - Synced RabbitMQ, scenarios, WireMock, TCP mock, ClickHouse init, Grafana,
    Loki, and Prometheus static inputs with
    `tools/docker/proxmox-swarm-prepare-node.sh --config <local-env>`.
  - Created persistent data/runtime directories for Postgres, ClickHouse,
    Grafana, Loki, Prometheus, TCP mock, HAProxy, and generated scenarios.
  - Normalized ownership to `root:root`.
- [x] Add initial `.50` node labels:
  - `pockethive.storage=true`
  - `pockethive.scenarios=true`
  - `pockethive.sut=true`
  - `pockethive.proxy=true`
- [x] Decide initial per-service placement constraints:
  - manager-only services
  - services requiring local bind mounts
  - services safe on workers
- [x] Add static node labels for the first mount-bearing node.
- [x] Add Swarm deploy constraints for the first conservative `.50` pass.
- [x] Validate the first constrained Swarm stack through official ingress
  or its documented service interface.
  - Added `deploy/compose.proxmox-swarm.yml` and
    `deploy/nginx.proxmox-swarm.conf`.
  - Added `tools/docker/proxmox-swarm-stack-config.sh` and
    `tools/docker/stack-compose-normalize.mjs` so the stack render has no
    unsupported `depends_on` semantics before `docker stack deploy`.
  - Deployed tag `dev-20260430-0058-g6b3c9177-dirty` from
    `192.168.88.54:5000/pockethive`.
  - Core stack converged to `1/1` for all PocketHive services after resetting
    corrupted local RabbitMQ test data by moving
    `/opt/pockethive/swarm/rabbitmq/data` to a timestamped backup and letting
    RabbitMQ re-import definitions.
  - Official ingress validation passed on `.50`:
    `/healthz`, `/orchestrator/actuator/health`,
    `/scenario-manager/actuator/health`,
    `/network-proxy-manager/actuator/health`,
    `/auth-service/actuator/health`, and `/prometheus/-/ready`.
  - Smoke against `.50` passed: `17 Scenarios`, `191 Steps`,
    `BUILD SUCCESS`.

Gate: the core stack can be deployed as Swarm services with predictable data
placement and no accidental scheduling onto nodes without required mounts.

## Phase 7 - Upgrade PocketHive Runtime To Full Swarm

Objective: switch swarm-controller and workers from `DOCKER_SINGLE` containers
to Swarm services only after the platform is stable.

- [x] Enable:
  `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_DOCKER_COMPUTE_ADAPTER=SWARM_STACK`
  in a dedicated test deployment.
- [x] Add or verify placement constraints for swarm-controller services.
  - Orchestrator now propagates explicit
    `POCKETHIVE_DOCKER_SWARM_PLACEMENT_CONSTRAINTS`.
  - First pass uses `node.labels.pockethive.scenarios == true`.
  - Dynamic manager services also retain `node.role == manager`.
- [x] Decide how worker services receive required static mounts:
  - Full Swarm uses an explicit shared scenarios runtime root:
    `POCKETHIVE_SCENARIOS_RUNTIME_ROOT=/mnt/shared/pockethive/scenarios-runtime`.
  - The shared host path is mounted into scenario-manager and orchestrator as
    `/app/scenarios-runtime`.
  - `tools/docker/proxmox-swarm-prepare-node.sh` and
    `tools/docker/proxmox-swarm-stack-config.sh` now require this setting so a
    Swarm deployment fails before render/prepare if the shared root is missing.
- [x] Verify controller service creation.
  - Dynamic controller services are created as Swarm services with deterministic
    Docker-safe names of at most 63 characters.
  - Original logical ids are preserved in `ph.logicalName` labels and in the
    adapter's logical id to service id mapping.
  - `TaskTemplate.Networks` and runtime containers attach to
    `pockethive_default`; this fixed the earlier `tasks.rabbitmq` DNS/connect
    failure from bridge-only dynamic controllers.
- [x] Verify worker service creation role by role in smoke:
  - `generator`
  - `moderator`
  - `processor`
  - `postprocessor`
  - `request-builder`, `http-sequence`, `clearing-export`, and `trigger`
    still need targeted lifecycle coverage in Swarm service mode.
- [x] Run lifecycle E2E after each meaningful service group.
  - 2026-04-30 first `lifecycle` run against `.50` official ingress failed
    consistently before worker traffic:
    `7 Scenarios (7 failed)`, `75 Steps (7 failed, 46 skipped, 22 passed)`.
  - Command used the same remote endpoints as smoke:
    `UI_BASE_URL=http://192.168.88.50:8088`,
    `ORCHESTRATOR_BASE_URL=http://192.168.88.50:8088/orchestrator`,
    `SCENARIO_MANAGER_BASE_URL=http://192.168.88.50:8088/scenario-manager`,
    `AUTH_SERVICE_BASE_URL=http://192.168.88.50:8088/auth-service`,
    `NETWORK_PROXY_MANAGER_BASE_URL=http://192.168.88.50:8088/network-proxy-manager`,
    `RABBITMQ_HOST=192.168.88.50`,
    `RABBITMQ_MANAGEMENT_BASE_URL=http://192.168.88.50:8088/rabbitmq/api`,
    then `./start-e2e-tests.sh --group lifecycle`.
  - Common failure:
    `Missing outcome for swarm-template correlation=<create-correlation>`.
  - Orchestrator logs show the backend did accept create, launch the dynamic
    controller service, send `swarm-template`/`swarm-plan`, and emit
    `event.outcome.swarm-create` with the same create correlation id. The
    harness summary also reported `Control-plane capture collected 265
    message(s)`. This initially looked like an E2E watcher problem, but the
    follow-up diagnosis below found the real backend cause: worker services
    were rejected by Docker before they could report ready.
  - 2026-04-30 follow-up diagnosis reproduced the blocker with
    `diag-full-swarm-e2e` on full Swarm. The controller service reached `1/1`,
    but all worker services stayed `0/1` and Docker rejected their tasks with:
    `invalid mount config for type "bind": bind source path does not exist:
    /opt/pockethive/scenarios-runtime/<swarmId>`. This explains why single-node
    testing worked while full Swarm did not: the worker path depended on a
    per-swarm bind source that was available/created in the single-node flow but
    did not exist on the Swarm node at service scheduling time. Because workers
    never started, they never emitted the disabled status/ready signals needed
    for the controller to close pending `swarm-template`, so
    `event.outcome.swarm-template` was never emitted. The diagnostic swarm was
    stopped and removed after inspection.
  - Fix: the full Swarm stack now uses the shared runtime root above, prepared
    on the host as `/mnt/shared/pockethive/scenarios-runtime`.
  - A second full-Swarm-only data-plane issue appeared after provisioning was
    fixed: workers resolved `wiremock:8080` through a Swarm VIP that refused
    connections. SUT/proxy services now use `endpoint_mode: dnsrr` with
    host-mode published ports, so dynamic workers resolve task IPs.
  - Verification against `.50` official ingress:
    `./start-e2e-tests.sh --group lifecycle` passed with `7 Scenarios`,
    `75 Steps`, and `BUILD SUCCESS`.
  - Post-run cleanup found no remaining `diag-e2e`, `diag-shared`, or
    `pockethive-e2e-gp` services; stable stack services remained running.
- [x] Add cleanup verification for service removal and RabbitMQ queue cleanup.
  - Post-smoke cleanup removed all non-`pockethive_` and non-`portainer_`
    dynamic services; stable stack services remained `1/1`.
  - Full lifecycle cleanup after the shared-mount and DNSRR fixes also left no
    diagnostic or E2E dynamic services behind.

Gate: full `SWARM_STACK` lifecycle passes for at least one representative
scenario and leaves no stale Swarm services after remove.

## Phase 8 - Broaden E2E Coverage

Objective: expand remote E2E coverage after full Swarm basics are proven.

- [x] Re-run groups in this order:
  - `smoke` (green before Phase 8)
  - `contracts`
  - `lifecycle` (green on 2026-04-30 against `.50` full Swarm)
  - `proxy`
  - `data`
  - `exports`
- [x] For each group, classify failures as:
  - deployment/config issue
  - E2E remote-mode assumption
  - PocketHive runtime bug
  - Swarm scheduling/storage issue
- [x] Fix one class at a time. Do not add fallback behavior to make tests pass.
  - 2026-04-30 `contracts` passed against `.50` full Swarm:
    `5 Scenarios`, `35 Steps`, `BUILD SUCCESS`.
  - 2026-04-30 first `proxy` run failed at swarm create with HTTP 500. The
    Orchestrator tried to reach Network Proxy Manager through the Swarm VIP
    `network-proxy-manager:8080`, which refused connections; the task DNS name
    was healthy. Fix: set the full Swarm Orchestrator config to the explicit
    service task endpoint
    `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_NETWORK_PROXY_MANAGER_URL=http://tasks.network-proxy-manager:8080`.
    Re-run passed: `3 Scenarios`, `45 Steps`, `BUILD SUCCESS`.
  - 2026-04-30 first `data` run exposed three remote-mode/service-interface
    gaps:
    - the E2E harness needed explicit remote values for TCP mock, Redis, and
      ClickHouse service-interface checks:
      `POCKETHIVE_TCP_MOCK_URL=http://192.168.88.50:8083`,
      `POCKETHIVE_REDIS_HOST=192.168.88.50`,
      `POCKETHIVE_REDIS_PORT=6379`,
      `POCKETHIVE_CLICKHOUSE_HTTP_URL=http://192.168.88.50:8123`,
      `POCKETHIVE_CLICKHOUSE_USERNAME=pockethive`,
      `POCKETHIVE_CLICKHOUSE_PASSWORD=pockethive`;
    - Redis and ClickHouse published endpoints needed explicit DNSRR plus
      host-mode ports; Redis is placed on the proxy/ingress node so the
      documented `.50:6379` service-interface check reaches the running task;
    - Docker Swarm expands `{{ ... }}` in service env values. The
      `SWARM_STACK` adapter now escapes application template delimiters when
      building Docker service env entries, preserving values such as
      `webauth.RED.{{ payloadAsJson.Customer }}` for the worker runtime.
    Re-run passed: `5 Scenarios`, `71 Steps`, `BUILD SUCCESS`.
  - 2026-04-30 `exports` passed against `.50` full Swarm:
    `3 Scenarios`, `38 Steps`, `BUILD SUCCESS`.
  - Post-run cleanup check found no remaining `diag-*` or
    `pockethive-e2e-gp*` Swarm services.

Gate: remote Swarm can replace local WSL Docker for the normal PocketHive test
loop.

## Known Risks

- `docker stack deploy` does not honor Compose `depends_on.condition`; service
  readiness may require explicit retry behavior or manual staged deployment.
- Current scenarios use plain image names like `processor:latest`; remote
  deployment depends on Orchestrator image prefixing and explicit tags.
- Worker bind mounts require identical host paths on every node that may run a
  worker, or placement constraints that keep workers on prepared nodes. The
  current full Swarm pass uses the explicit shared root
  `/mnt/shared/pockethive/scenarios-runtime`.
- `SWARM_STACK` currently creates one Swarm service per manager/worker and is
  intentionally minimal; smoke, contracts, lifecycle, proxy, data, and exports
  are green on the `.50` full Swarm target as of 2026-04-30.
- E2E harness still has direct dependencies on RabbitMQ AMQP/management and
  selected service interfaces. Those must stay explicit and must not replace
  official ingress checks.

## Immediate Next Work Items

1. Make the `.55` reduced runtime redeploy path repeatable:
   sync compose/env/nginx/scenario/WireMock inputs and run one documented
   `docker compose` command against `/opt/pockethive/runtime`.
   - Status: done via `tools/docker/proxmox-reduced-runtime.sh --config <local-env>`.
2. Broaden `.55` lifecycle coverage incrementally:
   start with `@scenario-variables`, then `@workitem-headers`, then proxy tags
   only after the core SUT path stays green.
   - Status: `@scenario-variables` and `@workitem-headers` pass on `.55`.
3. Remove the tag-to-capabilities dependency:
   normalize image references to image name only for capability metadata
   lookup, and remove the temporary `POCKETHIVE_CAPABILITIES_FALLBACK_TAG`
   behavior once the canonical lookup is in place.
   - Status: implemented in Scenario Manager, `ui`, `ui-v2`, and the VS Code
     scenario editor. Capability manifests no longer require `image.tag`, and
     runtime image refs are normalized to canonical image name before lookup.
4. Broaden full Swarm mode beyond lifecycle:
   run `contracts`, `proxy`, `data`, and `exports` groups against `.50` full
   Swarm through the documented ingress/API paths.
   - Status: done on 2026-04-30. Smoke, lifecycle, contracts, proxy, data, and
     exports are green on `.50`; Phase 8 also fixed the Network Proxy Manager
     task endpoint, Redis/ClickHouse host-mode service interfaces, and Docker
     Swarm env template delimiter escaping for worker services.
5. Before PR:
   - finish Proxmox/Swarm docs for what is local-only vs reviewable;
   - review the dirty worktree and split unrelated changes;
   - run targeted UI/ui-v2 checks against real scenario data.
