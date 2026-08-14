---
title: Run PocketHive locally from source
pagination_label: Run locally from source
description: Build PocketHive from a pinned candidate revision, apply its startup gates, and run a manually time-boxed evaluation only when those gates pass.
---

# Run PocketHive locally from source

PocketHive runs a reusable scenario as an isolated **swarm** of workers. In
this walkthrough, four workers collaborate through local RabbitMQ: `genA`
creates messages, `modA` limits their rate, `procA` sends HTTP requests to
local WireMock, and `postA` consumes the results. **Hive**, **Snapshot**,
**Scenario**, and **Journal** show what happened.

New to PocketHive? Read the [interactive overview](../presentation/interactive-pockethive-overview.mdx)
first and keep the [glossary](../../GLOSSARY.md) available for unfamiliar
terms.

| Reader context | Details |
| --- | --- |
| Audience | Evaluators and contributors on an isolated development machine |
| Starting point | A complete PocketHive source checkout at the pinned lifecycle rewrite revision below |
| Time | About 15 minutes on a prepared machine with warm Docker and Maven caches; a cold first build can take longer |
| Expected outcome | Build the candidate and apply the official-ingress and Connectivity gates. Continue to the `docs-demo` lifecycle only if both pass; otherwise retain the blocker and stop before Start. |
| Build version | `0.15.35` (the lifecycle rewrite is unreleased) |
| Last-tested source | `rewrite/lifecycle-control-plane` at `0524165e0ebadc34f8a9b28044580374f9da6d26` |

:::danger Use an isolated development machine

- `./build-hive.sh --quick` stops and rebuilds the full PocketHive Compose
  stack. If the build fails, the previous stack can remain stopped.
- At this source revision, build cleanup force-removes local Docker containers whose
  names contain `-bee-`. Step 2 shows how to find these containers. Stop if
  any match is unrelated to PocketHive or must be preserved.

<details>
<summary>Why this workflow is limited to an isolated host</summary>

The development Compose file publishes multiple service ports and mounts the
Docker socket into the Orchestrator. Its port mappings are not explicitly
restricted to the loopback interface. Host firewall and Docker networking are
therefore part of the safety boundary. Do not use this workflow on a shared
host or an untrusted network.

</details>

The downloadable Compose package has not passed its clean-host release gate.
See [deployment paths](../operators/deployment.md) for the current package and
managed-environment status.

:::

## 1. Prepare the source checkout

For a new checkout:

```bash
git --version
git clone https://github.com/sepa79/PocketHive.git
cd PocketHive
git fetch origin rewrite/lifecycle-control-plane
git checkout --detach 0524165e0ebadc34f8a9b28044580374f9da6d26
```

For a new or existing checkout, verify the exact revision and working tree:

```bash
git rev-parse HEAD
git status --short
```

The first command must print exactly
`0524165e0ebadc34f8a9b28044580374f9da6d26`. The second command must print
nothing. Any other result means that you are not reproducing the runtime source
state used by this walkthrough. This is an unreleased revision; the build still
reports version `0.15.35`, so record build version and source revision
separately.

## 2. Check prerequisites and local safety

Run from Bash 4.3 or newer. On Windows use WSL or a current Git Bash. The Bash
3.2 bundled with older macOS installations is not sufficient for
`build-hive.sh`.

From the repository root, run this read-only preflight:

```bash
bash --version | head -n 1
git --version
docker version
docker compose version
docker buildx version
java -version
javac -version
mvn -version
curl --version | head -n 1

if docker compose config --quiet; then
  printf 'Compose configuration is valid\n'
else
  printf 'Compose configuration is invalid\n' >&2
  exit 1
fi

docker compose config --services | wc -l
docker ps -a --filter 'name=-bee-' \
  --format 'table {{.ID}}\t{{.Names}}\t{{.Status}}'
```

Continue only when:

- Bash is 4.3 or newer.
- Docker reports both client and server information, proving that the daemon
  is running and accessible.
- `docker compose version` reports Compose V2.
- `docker buildx version` reports a usable Buildx plugin. The legacy Docker
  builder is insufficient for this repository's multi-stage image build.
- `java -version`, `javac -version`, and `mvn -version` all report Java 21.
  Set `JAVA_HOME` if Maven reports another Java version.
- The Compose check prints `Compose configuration is valid`, and the service
  count is `16`.
- The container table contains only its header, or every listed `-bee-`
  container is a disposable PocketHive container. **Stop here** if an unknown
  or important container appears; the build would force-remove it.
- Git, Maven, and Docker can reach the repositories and registries needed for
  a cold build.
- Port `8088` and the additional development ports below are available.

No tested minimum RAM, free-disk requirement, or cold-build duration is
published for this pinned revision. A first build compiles several modules and downloads
multiple images, so check Docker's available resources before starting.

<details>
<summary>Development ports and how to check them</summary>

The current Compose file publishes these ports:

`5672`, `6379`, `8080`, `8081`, `8083`, `8084`, `8088`, `9000`, `9090`,
`9091`, `1081`, `1082`, `1083`, `15672`, `15674`, `18474`, `5432`, and
`8123`.

List listening TCP ports with the command for your host:

```bash title="Linux or WSL"
ss -ltn
```

```bash title="macOS"
lsof -nP -iTCP -sTCP:LISTEN
```

```powershell title="Windows PowerShell"
Get-NetTCPConnection -State Listen |
  Sort-Object LocalPort |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

If a required port appears, identify its owning process. Stop here unless it
is an earlier PocketHive stack that you intentionally allow the build to
replace.

</details>

## 3. Start PocketHive

From the repository root:

```bash
./build-hive.sh --quick
```

`--quick` skips tests and Maven clean/cache cleanup, rebuilds local artifacts
and images, then restarts the full Compose stack. A successful run includes
this marker near the end:

```text
PocketHive local rebuild/redeploy complete.
```

Confirm the official application address returns the exact health body, then
inspect all Compose services:

```bash
if health_body="$(curl -fsS http://localhost:8088/healthz)"; then
  if [ "$health_body" = "ok" ]; then
    printf 'PocketHive application address is responding\n'
  else
    printf 'Unexpected /healthz body: %s\n' "$health_body" >&2
    exit 1
  fi
else
  printf 'PocketHive /healthz request failed\n' >&2
  exit 1
fi

docker compose ps --all
```

`/healthz` proves only that the application's reverse-proxy entry point is
responding. It does not prove that every backend or future swarm worker is
ready. `docker compose ps --all` should show all 16 configured services. Do
not continue while one is absent, exited, restarting, or unhealthy.

<a href="http://localhost:8088" target="_blank" rel="noopener noreferrer">Open PocketHive</a>
in a separate browser tab.

### Sign in to the local DEV environment

1. Select the user icon in the upper-right corner.
2. Select **Login / users…**.
3. Enter `local-admin` as the username.
4. Select **Sign in (DEV)**.
5. Confirm that **Hive**, **Scenarios**, and **Journal** appear in the
   navigation.

If the navigation contains only **Other**, the session is still anonymous.
`local-admin` is a full-access development identity for this local Compose
environment only; do not use it in another environment.

**Startup success:** the home page loads, the expected navigation is visible,
and hovering the top-bar connection indicator shows **Connectivity: OK (click
for details)**. `checking`, `unknown`, `degraded`, or `problems` is not a
successful startup state.

## 4. Create the demo and confirm readiness {#3-create-a-demo-swarm}

The `local-rest-topology` scenario uses the bundled WireMock as its **system
under test (SUT)**. Only `procA` sends HTTP requests to WireMock; the workers
exchange messages through local RabbitMQ.

This demo does not end by itself. `genA` creates messages indefinitely at 50
per second (`maxMessages: 0`), while `modA` passes at most 10 per second, so a
backlog can grow until you stop the swarm. This walkthrough is **manually
time-boxed**: read the emergency-stop instructions in Step 5 before selecting
Start, and stop the run within 60 seconds.

In **Hive**, select **New swarm** and enter:

- **Swarm ID:** `docs-demo`
- **Network mode:** **Direct**
- **Scenario:** **Local REST - Simple REST Swarm (Topology)**
  (`bundles/local-rest-topology`)
- **Bundle SUT:** **WireMock (local docker-compose)** (`wiremock-local`)
- **Pull images:** off

Before selecting **Create**, confirm the controller image is
`swarm-controller:latest` and the planned worker roles are `genA`, `modA`,
`procA`, and `postA`.

Select **Create**. When the dialog closes, find `docs-demo` in **Hive** and
select **Details**.

**Creation success looks like:**

- The swarm-filtered **Journal** contains the correlated Orchestrator CREATE
  outcome with `data.status=Succeeded`.
- **Controller state** is `READY` and **Workload state** is `STOPPED`.
- **Snapshot** is fresh: its `receivedAt` age remains below the displayed
  `staleAfterSec` value.
- **Scenario** shows exactly one `live` runtime worker for each planned role:
  `genA`, `modA`, `procA`, and `postA`.

`READY` alone is not enough. There is no fixed wait time. If
`receivedAt` stops changing and its age exceeds `staleAfterSec`, or the roles
do not match, do not select Start. Follow the
[swarm lifecycle guide](../operators/swarm-lifecycle.md).

## 5. Run for no more than 60 seconds, then stop

Keep **Details** open so that the required checks are visible:

1. Start the 60-second safety clock, select **Start**, and wait for the action
   to reach `SUCCEEDED` and its public outcome to report
   `data.status=Succeeded`. Work can begin before terminal convergence, so the
   clock starts at dispatch rather than when `RUNNING` is first observed.
2. Confirm **Snapshot** remains fresh, **Workload state** is `RUNNING`, and
   **Health** is `HEALTHY` for this clean demo run. Health is an independent
   axis; `RUNNING` is not a health value.
3. In **Scenario**, confirm `genA`, `modA`, `procA`, and `postA` are all
   `enabled` and `live`.
4. Immediately select **Stop**. Dispatch Stop no later than 60 seconds after
   selecting Start.

:::caution If the Stop action is unavailable

On this isolated quickstart host only, stop containers belonging to
`docs-demo` by their PocketHive labels:

```bash
mapfile -t docs_demo_containers < <(
  docker ps -q \
    --filter 'label=pockethive.managed=true' \
    --filter 'label=pockethive.swarmId=docs-demo'
)

if ((${#docs_demo_containers[@]})); then
  docker stop "${docs_demo_containers[@]}"
fi
```

This is an emergency containment action, not proof of a successful lifecycle
stop. Do not use `docker compose down` as a substitute: it does not remove
swarm-created controller and worker containers. Preserve `docs-demo` and the
failure time, then use the
[swarm lifecycle recovery path](../operators/swarm-lifecycle.md). Do not
continue this quickstart after using the emergency action.

:::

## 6. Inspect the recorded evidence, then remove the swarm

After selecting **Stop**:

1. Wait for the Stop action to reach `SUCCEEDED`, its public outcome to report
   `data.status=Succeeded`, and **Workload state** to become `STOPPED`.
2. Confirm **Snapshot** is fresh.
3. In **Scenario**, confirm all four roles are `disabled` and still `live`.
4. Open **View** and identify the four-worker scenario path.
5. Open the swarm-filtered **Journal**. Find the create, start, and stop
   requests and their matching outcomes for `docs-demo`.

:::tip Completion claim

Only when every check above passes may you record that the local swarm reached
`RUNNING`, all four planned workers were live and enabled, and the time-boxed
workload stopped against local WireMock. That proves the documented control
and worker flow for this exact source checkout; it does not prove an external
SUT or a business result.

:::

<details>
<summary>Evidence worth retaining</summary>

- The Snapshot `receivedAt` value and `staleAfterSec` boundary.
- The four enabled/live role matches in Scenario.
- The scenario topology view.
- The swarm-filtered Journal entries for create, start, and stop.

Grafana, RabbitMQ, Redis, and WireMock are optional focused diagnostics, not
additional quickstart completion checks.

</details>

To remove the stopped swarm:

1. Select **Remove** and confirm the exact `docs-demo` swarm.
2. In **Journal**, require the correlated Orchestrator `swarm-remove` outcome
   for `docs-demo` with `data.status=Succeeded`.
3. Confirm that `docs-demo` disappears from a fresh **Hive** list. `REMOVED` is
   not a persistent swarm state; the successful Journal outcome and absence are
   the GUI-visible completion evidence in this walkthrough.

Do not reuse the swarm ID or shut down the Compose stack while removal is
uncertain. If the successful terminal outcome is missing, retain the swarm ID, failed
action, and timestamps and follow the lifecycle recovery guide.

## 7. Shut down PocketHive

Only after the successful Remove outcome and registry absence are
confirmed, run from the repository root:

```bash
docker compose down
```

This removes the Compose containers and network but intentionally leaves:

- named volumes containing local RabbitMQ, PostgreSQL, Redis, Grafana,
  ClickHouse, TCP Mock, and HAProxy data;
- locally built and pulled Docker images, plus Docker build cache;
- downloaded Maven artifacts in `~/.m2`;
- Maven `target` directories and staged `.local-jars` artifacts;
- data under the configured scenario runtime host path, whose default is
  `/opt/pockethive/scenarios-runtime`.

:::warning Persisted-data deletion

`docker compose down -v` deletes the Compose named volumes. It does not remove
every image, build artifact, cache entry, Maven download, or host bind-mounted
file. Run it only when deleting that persisted local data is intentional.

:::

## Troubleshooting by symptom

<details>
<summary>A prerequisite or the build fails</summary>

- If a command is missing or reports the wrong version, install or select the
  required tool before running the build.
- If the build fails after stopping the previous stack, fix the first reported
  build error and rerun the same command. Do not assume the old stack is still
  running.
- If a required port is occupied or an unrelated `-bee-` container exists,
  stop here and resolve the conflict rather than allowing the build to remove
  it.

</details>

<details>
<summary>The application does not become ready</summary>

- If `/healthz` is not exactly `ok`, run `docker compose ps --all`, identify
  the UI service state, and confirm that port `8088` is not occupied by another
  process.
- If the app shows only **Other**, complete **User menu → Login / users… →
  local-admin → Sign in (DEV)**.
- If Connectivity is not `OK`, open it for the affected service. The
  `/healthz` response alone is not enough.
- If the details show a schema-load, unresolved-reference, or validation
  error, preserve the exact error and source revision and stop this workflow.
  Rebuilding, refreshing, or attempting Start does not replace the
  Connectivity gate.

At the tested candidate source, the VM evaluation stopped here because the UI
could not resolve `swarm-lifecycle.schema.json` while compiling
`control-events.schema.json`; Connectivity remained degraded. Steps 4-7 are
the qualification criteria for a future passing candidate, not a claim that
this candidate completed the UI lifecycle.

</details>

<details>
<summary>Create does not converge or Start is rejected</summary>

Wait for a newer, fresh Snapshot and matching Scenario roles. Do not repeat
Start against unchanged evidence. If the Snapshot age exceeds
`staleAfterSec`, stop issuing lifecycle actions and follow the
[observability route](../operators/observability-troubleshooting.md#troubleshooting).

</details>

<details>
<summary>The swarm is still running after 60 seconds</summary>

Select **Stop** immediately. If the action is unavailable, use the label-scoped
emergency stop in Step 5, then retain the failure evidence for recovery.

</details>

<details>
<summary>Removal is uncertain</summary>

Preserve `docs-demo`, the failed action, and timestamps. Do not reuse the ID
and do not run `docker compose down` until the successful Remove outcome and
registry absence are confirmed.

</details>

For a stack-level failure, start with:

```bash
docker compose ps --all
```

Then inspect one affected service. For example:

```bash
service_name=orchestrator
docker compose logs --tail=200 "$service_name"
```

## Recommended next step

Continue with [Learn the PocketHive application](../ui/application-guide.md)
to understand Hive, Scenario, Snapshot, Journal, and Connectivity without
changing another environment.

After that:

- [Understand lifecycle states and evidence](../operators/swarm-lifecycle.md).
- [Create your first guarded scenario](first-scenario.md).
