# PocketHive Scenario Bundle Rules

These rules apply to all work in this repository. This repo contains **PocketHive scenario bundles** — self-contained packages that define load/behavior test scenarios for the PocketHive platform.

## Repository purpose

This is a **scenario authoring** repository, not a service codebase. All artifacts are YAML/JSON/CSV configuration consumed by PocketHive's Scenario Manager and Swarm Controller at runtime.

## Remote stacks

All API-based MCP tools work against any deployed PocketHive stack — local or remote.
To target a remote stack, change `POCKETHIVE_BASE_URL` in `.env` and restart the MCP server
(reload the IDE window):

```
POCKETHIVE_BASE_URL=http://your-nft-server:8088
```

### What works against a remote stack

| Tool | Works remotely | Notes |
|---|---|---|
| `health.check` | ✅ | Confirms all 4 services reachable |
| `scenario.list` / `scenario.get` | ✅ | |
| `scenario.deploy` | ✅ | Uses HTTP zip upload — no Docker needed |
| `swarm.create/start/stop/remove/get/list` | ✅ | |
| `swarm.wait-ready` | ✅ | |
| `debug.queues` | ✅ | |
| `debug.journal` | ✅ | |
| `debug.tap` / `debug.tap.read` / `debug.tap.close` | ✅ | |
| `debug.prometheus` | ✅ | |
| `debug.config-update` | ✅ | |
| `mock.wiremock.*` | ✅ | If WireMock port is accessible |
| `mock.tcp.*` | ✅ | If TCP mock admin port is accessible |
| `debug.docker-logs` | ❌ | Requires Docker socket on the remote host |
| `docker.execute` / `docker.compose` | ❌ | Requires local Docker socket |
| `scenario.sync` | ❌ | Uses `docker cp` — local only |
| `bundle.validate` | ✅ | Always runs locally against local files |

### `sutId` on remote stacks

`sutId` in `swarm.create` refers to a **bundle-local SUT** — a `sut/<sutId>/sut.yaml` file
inside the bundle. It does **not** refer to the global `sut-environments.yaml` on the server.

This means:
- Built-in scenarios (e.g. `local-rest-topology`) have no `sut/` directory — passing `sutId`
  will return 500. Omit `sutId` for these scenarios.
- Our bundles (e.g. `pcs-auth-csv`) include `sut/tcp-mock-local/sut.yaml` — pass
  `sutId: "tcp-mock-local"` and it will resolve correctly.
- The global `sut-environments.yaml` on the server is used by the Hive UI only, not by
  the Orchestrator at swarm create time.

### Debugging without docker-logs

When `debug.docker-logs` is unavailable, use these alternatives:

```
debug.journal { swarmId }          → control-plane events, errors, template-invalid
debug.queues  { swarmId }          → message counts and consumer state
debug.tap     { swarmId, role }    → sample actual WorkItem payloads
debug.prometheus { query }         → metrics if postprocessor is running
```

The journal `template-invalid` event type contains the deserialization error message
and is the primary substitute for container logs when diagnosing config/template issues.

### Switching between local and remote

Edit `.env` and restart the MCP server:

```
# Local
POCKETHIVE_BASE_URL=http://localhost:8088

# Remote NFT stack
POCKETHIVE_BASE_URL=http://your-nft-server:8088
```

`POCKETHIVE_ROOT` does not need to change — validation always runs locally.

## First-time setup check

If `.env` does not exist in the repo root, the developer has not run initialization.
Before doing any scenario work, run `scripts/init-dev.sh` (or `scripts\init-dev.bat`
on Windows) to:
- Locate and confirm the PocketHive repo checkout
- Generate `.env` with `POCKETHIVE_ROOT` and `POCKETHIVE_BASE_URL`
- Install MCP server dependencies
- Sync reference docs from the PocketHive repo

The MCP server auto-installs its own npm dependencies on first launch, but
`POCKETHIVE_ROOT` is needed for `bundle.validate` and `docs/pockethive-ref/`.

## MCP tools — ALWAYS use them

This repo has a registered MCP server (`pockethive-bundles`) with tools for the full
scenario development lifecycle. **You MUST use these MCP tools instead of raw shell
commands whenever a matching tool exists.** The tools have safety guards, correct
defaults, and structured output.

Mandatory tool usage:

- **Git** → use `git.execute` (not `executeBash git ...`)
- **Docker** → use `docker.execute` or `docker.compose` (not `executeBash docker ...`)
- **Maven** → use `maven.execute` (not `executeBash mvn ...`)
- **NPM** → use `npm.execute` (not `executeBash npm ...`)
- **Scenario sync** → use `scenario.sync` (not manual docker cp + curl)
- **Scenario deploy** → use `scenario.deploy` to load a bundle into the Scenario Manager
- **Swarm lifecycle** → use `swarm.create`, `swarm.wait-ready`, `swarm.start`, `swarm.stop`, `swarm.remove`
- **Queue inspection** → use `debug.queues` (not raw RabbitMQ API calls)
- **Message sampling** → use `debug.tap` / `debug.tap.read` / `debug.tap.close`
- **Container logs** → use `debug.docker-logs` (not `executeBash docker logs`)
- **Bundle validation** → use `bundle.validate` (not manual script invocation or run.sh)
- **Health checks** → use `health.check` before any deployment operation
- **Path diagnostics** → use `paths.check` when any path-related error occurs
- **WireMock** → use `mock.wiremock.*` tools (list, add, reset, requests, unmatched)
- **TCP Mock** → use `mock.tcp.*` tools (list, add, reset, requests, unmatched, scenarios)
- **GitHub issues** → use `github.*` tools built into `pockethive-bundles` (`github.create_issue`, `github.list_issues`, `github.get_issue`, `github.update_issue`, `github.add_issue_comment`, `github.search_issues`) for the `sepa79/PocketHive` repo

### Bundle validation flow — ALWAYS follow this order

1. `paths.check` — verify all paths show `OK` before proceeding.
2. `bundle.validate { bundle: "<name>" }` — starts validation in the background,
   returns a `jobId` immediately.
3. `bundle.validate.result { jobId: "<jobId>" }` — poll every 10-15 seconds until
   `status` is `done` or `error`. Validation typically takes 20-40 seconds.
4. Read the `generator` and `httpTemplates` fields in the result. Any `FAIL:` prefix
   contains the raw Java error explaining exactly which template field failed.

**Never invoke `run.sh` or `validate.sh` directly** — `bundle.validate` handles the
WSL/JVM invocation correctly for this Windows+WSL2 environment.

When creating or modifying bundles, follow the TDD workflow in
`.amazonq/rules/tdd-workflow.md`. The workflow defines the exact sequence of tool
calls for the red-green-refine cycle.

Only fall back to `executeBash` for operations that have no matching MCP tool.

## GitHub integration

GitHub issue tools are built into the `pockethive-bundles` MCP server. Use them to:

- Create issues for bugs, validator discrepancies, or feature requests
- List and search open issues before starting work
- Add comments to existing issues with findings or fixes

Tools: `github.list_issues`, `github.get_issue`, `github.create_issue`, `github.update_issue`, `github.add_issue_comment`, `github.search_issues`

Requires `GITHUB_TOKEN` in `.env` (set during `init-dev`). Use a classic token (ghp_...) with `repo` scope.

## Atlassian integration (Jira / Confluence / Compass)

An Atlassian Rovo MCP server is registered as `atlassian`. On first use it opens a
browser for OAuth 2.1 authorization. After auth, the AI can:

- **Jira**: Search issues, create/update tickets, bulk create from specs
- **Confluence**: Search/summarize pages, create documentation
- **Compass**: Query service dependencies, create components

Use Atlassian tools when:
- Creating test plan tickets from scenario requirements
- Searching Confluence for API specs or SUT documentation
- Linking scenario bundles to Jira stories
- Documenting test results in Confluence

Do NOT use Atlassian tools for scenario authoring itself — use `pockethive-bundles` tools.

## Live PocketHive reference

If `docs/pockethive-ref/` exists, it contains authoritative docs synced from the developer's
PocketHive checkout. **Always prefer these over the static docs in `docs/ai/`** when they
are available — they reflect the actual branch the developer is working against.

Key paths inside `docs/pockethive-ref/`:

- `scenarios/SCENARIO_CONTRACT.md` — authoritative YAML contract
- `scenarios/SCENARIO_VARIABLES.md` — variables system spec
- `scenarios/SCENARIO_PATTERNS.md` — official scenario patterns
- `scenarios/SCENARIO_PLAN_GUIDE.md` — plan lifecycle, traffic policy, buffer guard
- `scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` — bundle REST API (upload, reload, CRUD)
- `guides/templating-basics.md` — Pebble + SpEL reference
- `guides/templating-advanced.md` — advanced templating
- `guides/workers-basics.md` — worker fundamentals
- `guides/workers-advanced.md` — advanced worker patterns
- `capabilities/*.yaml` — authoritative config fields per worker role
- `examples/*/scenario.yaml` — real scenario examples from the PocketHive repo
- `worker-sdk-README.md` — runtime API, WorkItem, interceptors, Redis IO
- `request-builder-README.md` — template loading, serviceId/callId resolution
- `processor-README.md` — HTTP/TCP transport, connection management
- `TCP-TRANSPORT-GUIDE.md` — TCP behaviors, transports, TLS, connection reuse
- `AUTH-USER-GUIDE.md` — All 11 auth strategies, configuration methods, examples
- `AUTH-BEHAVIOR.md` — Token lifecycle, caching, refresh scheduler
- `SYNCED_FROM.md` — provenance (branch, commit, date)
- `ARCHITECTURE.md` — full system architecture, control-plane protocol, lifecycle states
- `ORCHESTRATOR-REST.md` — complete REST API reference

Check `SYNCED_FROM.md` for the source branch. If it is not `main`, the reference may
include in-progress changes — note this when giving advice.

If `docs/pockethive-ref/` does not exist, fall back to the static docs in `docs/ai/`.

## System flow (what scenario authors need to know)

For the full architecture, consult `docs/pockethive-ref/ARCHITECTURE.md`. Key points:

### Create → Start → Run → Stop → Remove

1. **Create** — Orchestrator launches a Swarm Controller container, sends `swarm-template`
   (the scenario plan with all bees, config, SUT). Controller provisions worker containers,
   declares queues (`ph.work.<swarmId>.*`), and fans out `config-update` to each worker.
   Workers start with `enabled=false` (control plane on, workloads off).

2. **Start** — Orchestrator sends `swarm-start`. Controller enables workers in dependency
   order (producers before consumers). Workers begin processing. Status: `RUNNING`.

3. **Run** — Workers emit `status-delta` every few seconds. Controller aggregates into
   swarm-level `status-full`. Orchestrator caches the latest snapshot (visible via `swarm.get`).
   Config updates can be sent at runtime via `debug.config-update`.

4. **Stop** — Orchestrator sends `swarm-stop`. Controller disables workers in reverse order.
   Containers stay running (control plane still active). Status: `STOPPED`.

5. **Remove** — Orchestrator sends `swarm-remove`. Controller tears down worker containers
   and queues, then Orchestrator removes the Controller container.

### What can fail and why

- **Create fails** — Bad image name, Docker socket issue, missing SUT endpoint.
  Check `debug.journal` for `event.outcome.swarm-create` with `data.status=Failed`.
- **Start rejected (NotReady)** — Controller hasn't finished processing template/plan,
  or pending config updates haven't been acknowledged by workers.
  Wait and retry, or check `debug.docker-logs` for the controller.
- **Worker not reporting** — Container crashed or config parse error.
  Check `debug.docker-logs` for that role.
- **Messages not flowing** — Queue wiring mismatch, worker disabled, or processor
  can't reach SUT. Check `debug.queues` and `debug.docker-logs`.

### How config reaches workers

```
scenario.yaml config: → Orchestrator → swarm-template signal → Swarm Controller
  → config-update signal per worker → Worker applies config → status-delta confirms
```

The `config` block in each bee is injected as environment + control-plane config.
Workers never read scenario.yaml directly — they receive config via the control plane.

### How templates/datasets reach workers

The Swarm Controller mounts the scenario runtime directory into each worker container:
```
/opt/pockethive/scenarios-runtime/<swarmId>/ → /app/scenario/
```
This is why template paths use `/app/scenario/templates/` and CSV paths use
`/app/scenario/datasets/` — they resolve to the bundle's files inside the container.

### Image resolution

`image: generator:latest` in scenario.yaml is resolved by the Orchestrator using the
configured Docker registry prefix and version tag. The Scenario Manager's
`POCKETHIVE_IMAGES_DEFAULT_TAG` controls the default tag.

### Concrete trace: create + start a scenario called `my-test`

This is what happens end-to-end. Use `debug.journal` and `debug.queues` to observe each step.

**1. Create swarm (`swarm.create` with swarmId=`my-test`, templateId=`my-test`)**

```
Orchestrator:
  → Fetches scenario from Scenario Manager: GET /scenarios/my-test
  → Resolves SUT endpoints (if sutId provided)
  → Resolves variables (if variablesProfileId provided)
  → Launches Swarm Controller container: my-test-marshal-bee-<hash>
  → Waits for controller status-full on ph.control exchange

Swarm Controller (container starts):
  → Declares control queue: ph.control.my-test.swarm-controller.<instance>
  → Binds to signal.swarm-template.my-test.swarm-controller.<instance>
  → Emits: event.metric.status-full.my-test.swarm-controller.<instance>

Orchestrator (receives status-full):
  → Emits: event.outcome.swarm-create.my-test.orchestrator.<instance>
  → Sends: signal.swarm-template.my-test.swarm-controller.<instance>
    (payload = full SwarmPlan with bees, config, SUT, all enabled=false)

Swarm Controller (receives swarm-template):
  → Declares hive exchange: ph.my-test.hive
  → Declares work queues: ph.work.my-test.build, ph.work.my-test.proc, ph.work.my-test.post
  → Launches worker containers: my-test-generator-bee-<hash>, my-test-processor-bee-<hash>, etc.
  → Mounts /opt/pockethive/scenarios-runtime/my-test → /app/scenario in each container
  → Sends config-update to each worker (enabled=false + worker config from scenario)
  → Emits: event.outcome.swarm-template.my-test.swarm-controller.<instance>

Workers (each container starts):
  → Declare control queue: ph.control.my-test.<role>.<instance>
  → Load templates from /app/scenario/templates/ (request-builder)
  → Load CSV from /app/scenario/datasets/ (generator)
  → Apply config-update (enabled=false, worker-specific config)
  → Emit: event.metric.status-full.my-test.<role>.<instance>
```

**Status at this point**: Swarm is `READY`. All workers reporting, all `enabled=false`.
`debug.queues` shows: `ph.work.my-test.build`, `ph.work.my-test.proc`, `ph.work.my-test.post` (all empty).

**2. Start swarm (`swarm.start`)**

```
Orchestrator:
  → Sends: signal.swarm-start.my-test.swarm-controller.<instance>

Swarm Controller:
  → Enables workers in dependency order (generator last, postprocessor first):
    signal.config-update.my-test.postprocessor.<instance> {enabled: true}
    signal.config-update.my-test.processor.<instance> {enabled: true}
    signal.config-update.my-test.request-builder.<instance> {enabled: true}
    signal.config-update.my-test.generator.<instance> {enabled: true}
  → Each worker confirms via status-delta (enabled=true)
  → Emits: event.outcome.swarm-start.my-test.swarm-controller.<instance> {status: Running}
```

**3. Data flows (steady state)**

```
Generator (enabled, ratePerSec=10):
  → Reads CSV row / Redis item / scheduler tick
  → Renders message body via Pebble template
  → Publishes WorkItem to ph.my-test.hive exchange, routing key ph.work.my-test.build

Request Builder (consumes from ph.work.my-test.build):
  → Reads x-ph-call-id header from WorkItem
  → Resolves template: serviceId::callId
  → Renders pathTemplate, bodyTemplate, headersTemplate
  → Appends HTTP/TCP envelope step to WorkItem
  → Publishes to ph.work.my-test.proc

Processor (consumes from ph.work.my-test.proc):
  → Reads envelope step: {path, method, headers, body} or {protocol: TCP, ...}
  → Combines path with baseUrl from config (resolved from SUT)
  → Executes HTTP call or TCP connection
  → Appends response as new step to WorkItem
  → Publishes to ph.work.my-test.post

Postprocessor (consumes from ph.work.my-test.post):
  → Calculates hop durations from WorkItem step timestamps
  → Records metrics: ph_transaction_total_latency_ms, ph_transaction_processor_duration_ms
  → Pushes metrics to Prometheus Pushgateway
  → Writes transaction outcomes to ClickHouse (when writeTxOutcomeToClickHouse: true)
  → WorkItem processing complete (no further output)
```

**What to check at each hop**:
- `debug.queues` — message counts at each queue (should be low, not piling up)
- `debug.tap` on any role — sample actual WorkItem payloads
- `debug.docker-logs` for any role — errors, template failures, connection issues
- `debug.prometheus` — `ph_transaction_total_latency_ms{ph_swarm="my-test"}` for metrics
- `debug.journal` — control-plane events (config-update, status-full, alerts)

## Bundle structure (mandatory)

Every bundle lives under `bundles/<bundle-name>/` and MUST contain:

```
bundles/<bundle-name>/
  scenario.yaml          # Required — main scenario definition
  templates/             # Optional — request-builder HTTP/TCP templates
    <serviceId>/
      <callId>.yaml
  datasets/              # Optional — CSV or other data files
  sut/                   # Optional — bundle-local SUT environment definitions
    <sut-id>/
      sut.yaml
  variables.yaml         # Optional — scenario variables (profiles × SUTs)
  schemas/               # Optional — JSON schemas for body validation hints
  README.md              # Recommended — bundle documentation
```

## Scenario plan (`plan` section)

The optional `plan` section defines a **time-based automation timeline** that runs
inside the Swarm Controller. It lets you ramp rates, enable/disable workers, and
auto-stop the swarm on a schedule — no external intervention needed.

For full details and examples see `docs/pockethive-ref/scenarios/SCENARIO_PLAN_GUIDE.md` (when available).

```yaml
id: my-scenario
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      work:
        out:
          out: build
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 5
    # ... other bees

plan:
  bees:                          # per-instance steps
    - instanceId: generator      # matches the bee's role name
      steps:
        - stepId: ramp-10
          name: Ramp to 10/s
          time: PT15S            # ISO-8601 duration from plan start
          type: config-update    # default — can be omitted
          config:
            inputs:
              scheduler:
                ratePerSec: 10

        - stepId: ramp-20
          time: PT30S
          config:
            inputs:
              scheduler:
                ratePerSec: 20

  swarm:                         # swarm-wide steps (optional)
    - stepId: swarm-stop
      name: Stop after 5 minutes
      time: PT5M
      type: stop
```

### Step types

- `config-update` (default) — sends a config patch to the target worker
- `start` — enables the worker (`enabled: true`) or the whole swarm
- `stop` — disables the worker (`enabled: false`) or the whole swarm

### Key rules

- `stepId` must be unique within the plan (used for idempotency)
- `time` is elapsed from plan start, not wall clock
- For simultaneous actions, stagger by 0.1s: `PT60S`, `PT60.1S`, `PT60.2S`
- `instanceId` in `plan.bees` matches the bee's `role` name (or explicit `id` if set)
- Plan progress is visible in `swarm.get` under `envelope.data.context.scenario`
  and in `debug.journal` as `scenario-plan-loaded` events
- Plan is separate from `trafficPolicy` (buffer guard) — both can coexist

## scenario.yaml contract

Every `scenario.yaml` MUST follow this structure. All top-level fields:

```yaml
id: <unique-kebab-case-id>        # Required — must match folder name
name: <Human Readable Name>       # Required
description: <what this does>     # Optional but recommended
template:                         # Required
  image: swarm-controller:latest  # Required — always this value
  bees: [...]                     # Required — list of bee definitions
topology:                         # Optional — logical graph for Hive UI
  version: 1
  edges: [...]
trafficPolicy:                    # Optional — buffer guard / rate control
  bufferGuard: {...}
plan:                             # Optional — time-based automation timeline
  bees: [...]
  swarm: [...]
```

### Work section format

The `work` field uses **map form** — `in` and `out` are `map<string,string>` where the
key is the port id and the value is the queue suffix:

```yaml
# Standard single-input/single-output (use port ids 'in' and 'out')
work:
  in:
    in: moderate
  out:
    out: process

# Terminal bee (no output)
work:
  in:
    in: post

# Source bee (no input)
work:
  out:
    out: moderate

# No IO (e.g. generator with Redis uploader only)
work: {}
```

Note: Some legacy bundles use a short form (`out: queue-name` as a string instead of
a map). The authoritative contract requires the map form. When creating new bundles,
always use the map form. When fixing existing bundles, convert short form to map form.

### Bee fields (full reference)

Every bee in `template.bees` supports these fields:

```yaml
- role: generator          # required — logical role name
  id: gen-a                # optional — stable id for topology edges and plan targeting
                           #   Required when topology is declared.
                           #   Used as instanceId in plan.bees when multiple bees share a role.
  image: generator:latest  # required — container image (logical name)
  work:                    # required — queue wiring (map form)
    in:
      in: <queue-suffix>
    out:
      out: <queue-suffix>
  config: {}               # optional — worker config (fanned out via config-update)
  env:                     # optional — raw environment variables injected into the container
    MY_VAR: "value"        #   Use for secrets or low-level overrides not covered by config.
    JAVA_OPTS: "-Xmx512m"  #   Prefer config over env for all worker-level settings.
  ports:                   # optional — logical IO ports for topology edges (UI only)
    - { id: out, direction: out }
    - { id: in, direction: in }
```

### config.outputs (explicit output routing)

By default workers output to RabbitMQ. Use `config.outputs` to override:

```yaml
# Default — output to RabbitMQ (implicit, no need to specify)
config:
  outputs:
    type: RABBITMQ

# Route processed WorkItems directly to a Redis list (bypasses postprocessor)
config:
  outputs:
    type: REDIS
    redis:
      host: redis
      port: 6379
      username: ""              # optional Redis auth
      password: ""              # optional Redis auth
      ssl: false
      sourceStep: FIRST     # FIRST = generator payload, LAST = processor response
      pushDirection: RPUSH
      routes:
        - header: x-ph-redis-list
          headerMatch: '^ph:dataset:custA$'
          list: ph:dataset:custA-processed
      defaultList: ph:dataset:fallback
      maxLen: -1                # -1 = unlimited; >0 = cap list length

# Discard output — useful for terminal workers that only write side-effects
config:
  outputs:
    type: NOOP
```

`outputs.type` options: `RABBITMQ` (default), `REDIS`, `NOOP`.

### config.docker.volumes (custom container mounts)

Mount additional host paths into a bee's container. The Swarm Controller handles
this automatically for the scenario bundle (`/app/scenario`), but you can add more:

```yaml
config:
  docker:
    volumes:
      - /opt/pockethive/scenarios-runtime/<swarmId>:/app/scenario:ro
      - /opt/shared-certs:/app/certs:ro
  worker:
    # normal worker config
```

Format: `hostPath:containerPath[:mode]` where mode is `ro` or `rw`.
In practice you rarely need this — the scenario bundle is already mounted automatically.

### config.worker.schemaRef (UI hint only)

`schemaRef` is an **authoring-time-only** hint for the Hive UI editor. Workers ignore it
at runtime. It points to a JSON Schema file inside the bundle for rendering a form:

```yaml
config:
  worker:
    message:
      bodyType: SIMPLE
      schemaRef: "schemas/my-payload.schema.json#/body"  # UI only — ignored at runtime
      body: |
        { "pan": "{{ payloadAsJson.pan }}" }
```

Place schema files under `schemas/` in the bundle. Do not rely on `schemaRef` for
any runtime behaviour.

### topology (logical graph for Hive UI)

`topology` declares the logical wiring graph used by the Hive UI to draw edges between
bees. It is **UI-only** — it does not affect runtime queue wiring (that is controlled
by `work.in`/`work.out`). You only need it if you want the Hive editor to show a
visual pipeline diagram.

```yaml
template:
  image: swarm-controller:latest
  bees:
    - id: genA                    # id required when topology is declared
      role: generator
      image: generator:latest
      ports:
        - { id: out, direction: out }
      work:
        out:
          out: genQ
      config: { ... }

    - id: procA
      role: processor
      image: processor:latest
      ports:
        - { id: in, direction: in }
        - { id: out, direction: out }
      work:
        in:
          in: genQ
        out:
          out: postQ
      config: { ... }

    - id: postA
      role: postprocessor
      image: postprocessor:latest
      ports:
        - { id: in, direction: in }
      work:
        in:
          in: postQ

topology:
  version: 1
  edges:
    - id: e1
      from: { beeId: genA, port: out }
      to:   { beeId: procA, port: in }
    - id: e2
      from: { beeId: procA, port: out }
      to:   { beeId: postA, port: in }
```

Rules:
- Every bee referenced in `topology.edges` must have an `id` field.
- `ports` on each bee must list the port ids used in edges.
- `work` port keys must match the `ports` ids.
- For single-input/output bees, use standard port ids `in` and `out`.
- Edge `selector` is optional: `{ policy: predicate, expr: "payload.priority >= 50" }`
  for multi-IO routing hints (UI only).

**When to add topology**: only when you want the Hive UI to render a visual pipeline
diagram. For all other purposes (validation, runtime, testing) it adds no value.

## Worker roles and their config shapes

### generator (data source)
```yaml
config:
  inputs:
    type: SCHEDULER | CSV_DATASET | REDIS_DATASET
    scheduler:
      ratePerSec: <number>
      maxMessages: <number>       # 0 = infinite; >0 = finite run
      reset: false                # set true via config-update to restart a finite run
    csv:
      filePath: /app/scenario/datasets/<file>.csv
      ratePerSec: <number>
      rotate: true|false
      skipHeader: true|false
      startupDelaySeconds: <number>
    redis:
      host: redis
      port: 6379
      username: ""              # optional Redis auth
      password: ""              # optional Redis auth
      ssl: false
      listName: <list>            # single-source mode
      # OR sources: [{listName: ..., weight: ...}]  # multi-source mode
      pickStrategy: ROUND_ROBIN | WEIGHTED_RANDOM
      ratePerSec: <number>
  worker:
    message:
      bodyType: SIMPLE | HTTP
      body: <pebble-template>
      headers:
        x-ph-call-id: <callId>
        x-ph-service-id: <serviceId>
        x-ph-tcp-end-tag: "</Doc>"  # optional — overrides template endTag per-message
```

### trigger (event-driven source)

Variant of generator for event-driven (non-scheduled) input. Use when messages should
be emitted in response to external events rather than at a fixed rate. Config shape is
similar to generator; consult `docs/pockethive-ref/guides/workers-basics.md`.

### moderator (traffic shaping)
```yaml
config:
  worker:
    mode:
      type: pass-through | rate-per-sec | sine
      ratePerSec: <number>
      sine:
        minRatePerSec: <number>
        maxRatePerSec: <number>
        periodSeconds: <number>
        phaseOffsetSeconds: <number>
```

### request-builder (template application)
```yaml
config:
  worker:
    templateRoot: /app/scenario/templates
    serviceId: <default-service-id>
    passThroughOnMissingTemplate: true|false
    auth:                         # optional — shared auth across all calls
      - tokenKey: "api:auth"
        type: oauth2-client-credentials
        tokenUrl: "https://auth.example.com/token"
        clientId: my-client
        clientSecret: my-secret
```

### processor (HTTP/TCP caller)
```yaml
config:
  baseUrl: "{{ sut.endpoints['<endpoint-id>'].baseUrl }}"
  worker:
    mode: THREAD_COUNT | RATE_PER_SEC
    threadCount: <number>
    ratePerSec: <number>
    keepAlive: true|false
    timeoutMs: 30000
    sslVerify: false
    connectionReuse: GLOBAL | PER_THREAD | NONE
    tcpTransport:
      type: socket | nio | netty
      connectionReuse: GLOBAL | PER_THREAD | NONE
      connectTimeoutMs: 5000
      readTimeoutMs: 30000
      timeout: 30000
      maxRetries: 3
      maxBytes: 8192
      keepAlive: true
      tcpNoDelay: true
      workerThreads: 4
      sslVerify: false
      ssl:
        enabled: false
        verifyHostname: true
```

### postprocessor (metrics/results)
```yaml
config:
  worker:
    forwardToOutput: false            # true = forward WorkItems to output queue after processing
    writeTxOutcomeToClickHouse: false  # true = write transaction outcomes to ClickHouse
    dropTxOutcomeWithoutCallId: true   # true = skip ClickHouse write if no x-ph-call-id
```

> **Note**: `publish-all-metrics` was removed in 0.15.7. Do not use it — it causes a
> `MismatchedInputException` on 0.15.7+ stacks. Transaction metrics are now always written
> to ClickHouse when `writeTxOutcomeToClickHouse: true`.

### clearing-export (file output)

Batches WorkItems and writes clearing files using custom templates.
See `docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md` for full config schema and examples.

```yaml
config:
  worker:
    mode: structured               # or: streaming
    schemaRegistryRoot: /app/scenario/clearing-schemas
    schemaId: my-schema
    schemaVersion: "1.0.0"
    fileNameTemplate: |
      CLEAR_{{ eval("#date_format(now, 'yyyyMMdd')") }}.xml
    maxRecordsPerFile: 1000
    flushIntervalMs: 5000
    maxBufferedRecords: 5000      # max records held in memory before forced flush
    localTargetDir: /tmp/pockethive/clearing-out
    businessCodeFilterEnabled: false
    businessCodeAllowList: []
```

## Queue wiring rules

- Every bee MUST have a `work:` section (even if empty `{}`).
- `in` and `out` are `map<string,string>` — key is port id, value is queue suffix.
- For single-input/output bees, use port ids `in` and `out`.
- The `out` value of one bee must match the `in` value of the next bee in the pipeline.
- Queue suffixes are logical names; the Swarm Controller resolves them to full queue names
  (`ph.work.<swarmId>.<suffix>`).
- Standard pipeline: generator → moderator → request-builder → processor → postprocessor.
- Not all stages are required. Minimal: generator → processor → postprocessor.

Example wiring:
```yaml
- role: generator
  work:
    out:
      out: build          # produces to 'build' queue
- role: request-builder
  work:
    in:
      in: build           # consumes from 'build' queue
    out:
      out: proc           # produces to 'proc' queue
- role: processor
  work:
    in:
      in: proc
    out:
      out: post
- role: postprocessor
  work:
    in:
      in: post            # terminal — no out
```

## Templating rules (Pebble + SpEL)

Templates use Pebble syntax with SpEL via `eval(...)`.

### Template context variables

All templates have access to these root variables:

- `{{ payload }}` — current step payload as text
- `{{ payload.fieldName }}` — JSON field access when payload is valid JSON
- `{{ payloadAsJson.fieldName }}` — explicit JSON parse; also works in generator body for CSV input (columns exposed as JSON fields)
- `{{ headers['x-custom'] }}` — WorkItem top-level headers
- `{{ vars.variableName }}` — scenario variables (from `variables.yaml`)
- `{{ workItem.messageId }}` — full WorkItem object; access `workItem.steps`, `workItem.messageId`, etc.
- `{{ sut.endpoints['id'].baseUrl }}` — SUT endpoint (resolved at swarm create time only)

In SpEL (`eval(...)`) the same roots are available plus:
- `now` — current `Instant`
- `nowIso` — current time as UTC ISO-8601 string (e.g. `"2026-04-01T12:00:00Z"`)

### SpEL functions (`#name(...)`)

| Function | Description |
|---|---|
| `#randInt(min, max)` | Random integer, inclusive |
| `#randLong(min, max)` | Random long (pass large bounds as strings) |
| `#uuid()` | Random UUID string |
| `#md5_hex(value)` | MD5 hash in hex |
| `#sha256_hex(value)` | SHA-256 hash in hex |
| `#base64_encode(value)` / `#base64_decode(value)` | Base64 helpers |
| `#hmac_sha256_hex(key, value)` | HMAC-SHA256 in hex |
| `#regex_match(input, pattern)` | Boolean match |
| `#regex_extract(input, pattern, group)` | String extraction (empty if no match) |
| `#json_path(payload, path)` | **JSON Pointer** (RFC 6901) extractor — use `/field/nested/0` syntax, NOT `$.field` JSONPath syntax |
| `#date_format(instant, pattern)` | Format `now` or any Instant |
| `#sequence(key, mode, format)` | Redis-backed sequence counter |
| `#sequenceWith(key, mode, format, start, max)` | Sequence with explicit start/max |
| `#resetSequence(key)` | Delete Redis counter; returns `true` |
| `#authToken(tokenKey)` | Inject cached auth token value directly into body/headers |

> **`#json_path` uses JSON Pointer, not JSONPath.** Use `/customerId` not `$.customerId`.
> Nested: `/customer/code`. Array element: `/items/0/id`.

### Pebble functions (not SpEL)

These are native Pebble functions, called without `eval()`:

```yaml
# Weighted call selection — idiomatic way to route x-ph-call-id
headers:
  x-ph-call-id: "{{ pickWeighted('redis-auth', 50, 'redis-balance', 30, 'redis-topup', 20) }}"

# Deterministic weighted selection (same sequence per label+seed)
headers:
  x-ph-call-id: "{{ pickWeightedSeeded('callId', 'my-seed-001', 'redis-auth', 50, 'redis-balance', 50) }}"
```

`pickWeighted(value, weight, value, weight, ...)` — arguments are `(value, weight)` pairs.
Weights are relative integers; total must be > 0.

### Special WorkItem headers set by the runtime

These headers are injected automatically and can be read in templates:

| Header | Set by | Value |
|---|---|---|
| `x-ph-call-id` | Generator | Template callId to use |
| `x-ph-service-id` | Generator | Template serviceId namespace |
| `x-ph-auth-token-key` | Generator | Which cached token to attach (e.g. `api:auth`) |
| `x-ph-tcp-end-tag` | Generator | Per-message override of template `endTag` for TCP |
| `x-ph-redis-list` | Redis dataset input | Source list that produced this WorkItem |
| `x-ph-scheduler-remaining` | Scheduler input | Messages remaining before `maxMessages` exhaustion |
| `x-ph-csv-file` | CSV dataset input | Source CSV file path |
| `x-ph-csv-row` | CSV dataset input | Row number in the CSV file |
| `x-ph-processor-success` | Processor | `true`/`false` — whether the call succeeded |
| `x-ph-processor-status` | Processor | HTTP status code or TCP result code |
| `x-ph-processor-duration-ms` | Processor | Call duration in milliseconds |

## Request templates (templates/ directory)

HTTP template files:
```yaml
serviceId: <namespace>
callId: <unique-call-id>          # Must match x-ph-call-id header
protocol: HTTP                    # Required
method: POST | GET | PUT | DELETE
pathTemplate: "/api/path"
bodyTemplate: |
  <pebble template for body>
headersTemplate:
  Content-Type: application/json
auth:                             # Optional — per-template auth
  type: oauth2-client-credentials
  tokenKey: api:auth
  tokenUrl: "https://auth.example.com/token"
  clientId: my-client
  clientSecret: my-secret
resultRules:                      # Optional — business outcome classification
  businessCode:
    source: RESPONSE_BODY         # or REQUEST_BODY
    pattern: '(?is)<RsltCode>([^<]+)</RsltCode>'  # regex; capture group 1 is the code
  successRegex: '^TRS0001$'       # matched against extracted businessCode
  dimensions:                     # optional — extra Prometheus label dimensions
    - name: transaction-type
      source: REQUEST_BODY
      pattern: '<TxTp>([^<]+)</TxTp>'
```

`resultRules` drives postprocessor classification:
- `businessCode.pattern` extracts a code from the request or response body
- `successRegex` determines if the transaction is a success (matched = success)
- `dimensions` extract additional label values for Prometheus metrics
- Without `resultRules`, success is determined solely by HTTP status code

TCP template files add: `behavior`, `transport`, `endTag`, `maxBytes`.

### TCP template fields (full reference)

```yaml
serviceId: <namespace>
callId: <call-id>
protocol: TCP
behavior: REQUEST_RESPONSE | FIRE_FORGET | ECHO | STREAMING
transport: socket | nio | netty
endTag: "</Response>"          # Delimiter for REQUEST_RESPONSE reads
maxBytes: 8192                  # Max bytes for STREAMING reads
bodyTemplate: |
  <request>{{ payload.data }}</request>
headersTemplate:
  Content-Type: application/xml
```

Behaviors:
- `REQUEST_RESPONSE` — write request, read until `endTag` delimiter found
- `FIRE_FORGET` — write only, return empty body
- `ECHO` — read until at least request payload length received
- `STREAMING` — read up to `maxBytes`

Transports:
- `socket` — Java Socket (default). Supports keep-alive reuse (`connectionReuse: GLOBAL|PER_THREAD`). Supports `tcps://` TLS.
- `nio` — Java NIO. New connection per request. Plaintext only.
- `netty` — Netty async. New connection per request. Supports `tcps://` TLS.

Processor `baseUrl` for TCP: `tcp://host:port` or `tcps://host:port` (TLS).

### Processor HTTP modes

- `THREAD_COUNT` — cap concurrency to `threadCount` in-flight HTTP calls. Messages queue in RabbitMQ when all threads busy.
- `RATE_PER_SEC` — pace calls to `ratePerSec` requests/second, still honouring `threadCount` as max-in-flight cap.

Connection management:
- `keepAlive: true` + `connectionReuse: GLOBAL` — shared pool (200 connections)
- `keepAlive: true` + `connectionReuse: PER_THREAD` — one connection per thread
- `keepAlive: false` or `connectionReuse: NONE` — new connection per call

### Authentication

Auth is configured on the request-builder (template or worker config). 11 strategies supported.
For full details consult `docs/pockethive-ref/AUTH-USER-GUIDE.md`.

**Method 1 — Auth in template** (per-call, recommended):
```yaml
auth:
  type: oauth2-client-credentials
  tokenKey: api:auth
  tokenUrl: "https://auth.example.com/oauth/token"
  clientId: my-client
  clientSecret: my-secret
  scope: api.read
```

**Method 2 — Auth in worker config** (shared across calls; use when you need `sut.endpoints`):
```yaml
- role: request-builder
  config:
    worker:
      auth:
        - tokenKey: "api:auth"
          type: oauth2-client-credentials
          tokenUrl: "{{ sut.endpoints['auth'].baseUrl }}/oauth/token"
          clientId: my-client
          clientSecret: my-secret
```
Generator sets `x-ph-auth-token-key: "api:auth"` header to select which token to use.

**Method 3 — Generator fetches token into payload** (batch scenarios):
```yaml
- role: generator
  config:
    worker:
      auth:
        - tokenKey: "api:auth"
          type: oauth2-client-credentials
          tokenUrl: https://auth.example.com/token
          clientId: my-client
          clientSecret: my-secret
      message:
        body: '{"token": "{{ eval(''#authToken(\"api:auth\")'') }}"}'
```
Template then reads `{{ payloadAsJson.token }}` to inject into headers.

**Other auth types**: `basic-auth`, `bearer-token`, `api-key`, `aws-signature-v4`,
`hmac-signature`, `oauth2-password-grant`, `iso8583-mac`, `tls-client-cert`.

Token lifecycle: fetched on first request, cached in memory, auto-refreshed before expiry.
Zero overhead when not used.

## SUT endpoint references

When using `{{ sut.endpoints['<id>'].baseUrl }}` in config, the bundle SHOULD provide a local SUT definition under `sut/<sut-id>/sut.yaml`:

```yaml
id: <sut-id>
name: <Human Name>
type: sandbox | dev | uat
endpoints:
  <endpoint-id>:
    kind: HTTP
    baseUrl: <url>
```

## Validation

Before committing, validate bundles using the `bundle.validate` MCP tool.
Always run `paths.check` first to confirm the validator is ready.

```
# 1. Check paths are OK
paths.check

# 2. Start validation (returns jobId immediately)
bundle.validate { bundle: "<name>" }

# 3. Poll every 10-15s until status=done
bundle.validate.result { jobId: "<jobId>" }
```

Never invoke `run.sh` or `validate.sh` directly — `bundle.validate` handles the
WSL/JVM invocation correctly for this Windows+WSL2 environment.

## Data and state management

For detailed guidance on SUT configuration, schemas, CSV/Redis datasets, data recycling,
multi-phase coordination, and template design patterns, see the key rules below and consult `docs/pockethive-ref/` when available.
Key rules:

- **SUT config**: Every external URL must use `{{ sut.endpoints['id'].baseUrl }}` with a matching
  `sut/<sut-id>/sut.yaml`. Multiple SUTs allow the same scenario to target different environments.
- **`sut.endpoints[...]` scope**: Resolves only in `config.baseUrl` on the processor. Use hardcoded
  URLs or `vars.*` for `auth.tokenUrl` and all other nested config fields.
- **CSV datasets**: Place under `datasets/`, reference as `/app/scenario/datasets/<file>.csv`.
  Use `rotate: true` for infinite replay, `rotate: false` for one-shot setup phases.
- **Redis datasets**: Use Redis lists for inter-phase coordination. LPOP provides atomic
  exactly-once consumption. Populate via `redisUploader` interceptor or manual `redis-cli RPUSH`.
- **redisUploader routing** — three target resolution methods in priority order:
  1. First matching `routes` entry: match by `header`+`headerMatch` (header regex) OR `match` (payload body regex)
  2. `targetListTemplate` — Pebble template rendered against the WorkItem (e.g. `"ph:dataset:{{ payloadAsJson.customerId }}"`)
  3. `fallbackList` / `defaultList` — static fallback list name
- **Variables**: Use `variables.yaml` for environment/profile differences. Reference as `{{ vars.name }}`.

- **SUT config**: Every external URL must use `{{ sut.endpoints['id'].baseUrl }}` with a matching
  `sut/<sut-id>/sut.yaml`. Multiple SUTs allow the same scenario to target different environments.

## Mock servers (WireMock + TCP Mock)

For testing without a real SUT, use the built-in mock servers. Consult
`docs/pockethive-ref/` for full details when available. Key points:

- **WireMock** (`http://wiremock:8080`) — HTTP REST/SOAP mocking. Place JSON stub
  files in `wiremock/mappings/`. Match on URL, method, body patterns, headers.
- **TCP Mock Server** (`tcp://tcp-mock-server:9090` for TCP, `http://tcp-mock-server:8083` for admin API) — TCP protocol mocking.
  Place JSON mapping files in `tcp-mock-server/mappings/`. Match on regex patterns,
  JSONPath, XPath. Supports stateful scenarios, fault injection, delays.
- **TCP Mock TLS** (TCP `tcps://tcp-mock-server:9091`, admin `http://tcp-mock-server-tls:8083`) — Same as TCP mock with TLS enabled.
- Pre-configured mappings exist for echo, payment, ISO-8583, banking, JSON, XML, and fault injection.
- Use `/__admin/requests/unmatched` on WireMock to debug why requests aren't matching.
- SUT definitions: `wiremock-local` for HTTP, `tcp-mock-local` for TCP.
- **ISO-8583 binary wire formats**: The TCP mock uses text/regex matching and cannot
  correctly respond to binary length-prefixed protocols like `MC_2BYTE_LEN_BIN_BITMAP`.
  The processor's `LengthPrefix2BResponseReader` will time out. Use a real ISO-8583
  simulator for end-to-end ISO-8583 testing.
- **Framing alignment**: The mock's `requestDelimiter` must match the template's `endTag`.
  Mismatch causes the processor to time out waiting for the end-of-frame marker.
- **CSV datasets**: Place under `datasets/`, reference as `/app/scenario/datasets/<file>.csv`.
  Use `rotate: true` for infinite replay, `rotate: false` for one-shot setup phases.
  Use `skipHeader: true` when the first row contains column names.
- **Redis datasets**: Use Redis lists for inter-phase coordination. Populate via
  `redisUploader` interceptor, `outputs.type=REDIS`, or manual `redis-cli RPUSH`.
  LPOP provides atomic exactly-once consumption.
- **Data recycling**: Closed-loop patterns route processed records back to input lists
  using `outputs.redis.routes` with header matching on `x-ph-redis-list`.
- **Multi-phase**: Use `startupDelaySeconds` for in-swarm sequencing, or separate
  bundles for distinct phases. Redis LPOP naturally blocks when the source is empty.
- **Variables**: Use `variables.yaml` for environment/profile differences. Variables have two scopes:
  - `global` — same value for all SUTs, resolved by `variablesProfileId`
  - `sut` — value differs per SUT, resolved by `(variablesProfileId, sutId)` pair
  Reference as `{{ vars.name }}` in templates and SpEL.

## JMeter conversion

When asked to convert a JMeter `.jmx` test plan, use the key mappings below:
Key mappings:

- **Thread Group** → generator + pipeline bees. `num_threads` → `threadCount`, loops → `maxMessages`.
- **HTTP Sampler** → request-builder template. `${var}` → `{{ payloadAsJson.var }}`.
- **CSV Data Set** → generator with `type: CSV_DATASET`. `recycle` → `rotate`.
- **Timer** → `ratePerSec` on generator/moderator. Ramp-up → moderator sine wave.
- **Sequential Thread Groups** → `startupDelaySeconds` or Redis coordination.
- **Parallel Thread Groups** → multiple pipelines in one swarm.
- **Extractors** → multi-step WorkItem or Redis handoff between phases.
- **JMeter functions** → SpEL: `${__UUID()}` → `eval('#uuid()')`, `${__Random}` → `eval('#randInt')`.

## Keeping this repo in sync with PocketHive

This repo's knowledge comes from three tiers:

1. **`docs/pockethive-ref/`** (live) — Synced from PocketHive checkout. Re-run
   `scripts/sync-pockethive-ref.sh` after pulling PocketHive changes. This is the
   authoritative source for worker capabilities, scenario contract, auth, and architecture.
2. **`.amazonq/rules/`** (static) — Inline summaries for fast AI access. These may
   drift from PocketHive over time. When in doubt, prefer `docs/pockethive-ref/`.
3. **`docs/ai/`** (static) — Detailed guides and coding standards. Update when PocketHive adds new patterns.
   - `docs/ai/AI_GUIDELINES.md` — general contribution rules (scope, tech stack, commit policy)
   - `docs/ai/JAVA_GUIDELINES.md` — Java 21 / Spring Boot coding standards
   - `docs/ai/UI_REACT_GUIDELINES.md` — React UI coding standards
   - `docs/ai/REVIEW_CHECKLIST.md` — PR review checklist
   - `docs/ai/TASK_TEMPLATE.md` — template for scoping AI tasks
   - `docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md` — clearing-export worker config and usage

When PocketHive evolves (new worker, new config field, new auth strategy):
- Re-run `scripts/sync-pockethive-ref.sh` — this pulls the latest docs automatically.
- Check `docs/pockethive-ref/SYNCED_FROM.md` for the branch and commit.
- If the synced docs contain new capabilities not in the rules, the synced docs take
  precedence. Mention the discrepancy to the user.

## Traffic policy and buffer guard

Scenarios can include an optional `trafficPolicy` section for intelligent rate control.
For full details consult `docs/pockethive-ref/traffic-shaping.md`.

```yaml
trafficPolicy:
  bufferGuard:
    enabled: true
    queueAlias: moderator-a-out       # Queue suffix to monitor
    targetDepth: 100                  # Desired steady depth
    minDepth: 70                      # Lower hysteresis bound
    maxDepth: 130                     # Upper hysteresis bound
    samplePeriod: 5s
    movingAverageWindow: 5
    adjust:
      maxIncreasePct: 10
      maxDecreasePct: 10
      minRatePerSec: 1
      maxRatePerSec: 100
    prefill:
      enabled: false                  # Pre-load buffer before a spike
      lookahead: 30s
      liftPct: 20
    backpressure:
      queueAlias: proc-out            # Downstream queue to watch
      highDepth: 500
      recoveryDepth: 200
      moderatorReductionPct: 25
```

The buffer guard watches a queue depth and adjusts generator/moderator rates to keep
it within bounds. Use it when you need stable throughput with backpressure protection.

## Network proxy profiles (fault injection)

PocketHive includes Toxiproxy + HAProxy for network fault injection. Pre-configured
profiles are in `docs/pockethive-ref/network-profiles.yaml`:

- `passthrough` — no faults
- `latency-250ms` — adds 250ms ± 25ms latency
- `bandwidth-1mbps` — limits to 1 Mbps

Network profiles are selected per-SUT endpoint at swarm creation time.

## Global SUT environments

Pre-configured SUTs are in `docs/pockethive-ref/sut-environments.yaml`. Use these
instead of creating bundle-local SUTs when targeting the standard mock servers:

- `wiremock-local` — `http://wiremock:8080` (HTTP mocking)
- `tcp-mock-local` — `tcp://tcp-mock-server:9090` (TCP), `tcps://tcp-mock-server:9090` (TLS)
- `demo-http-sut` — `http://wiremock:8080` (demo alias)

## Additional workers

### http-sequence

Multi-step HTTP call chains. Executes a sequence of HTTP calls where each step can
use the response of the previous step. Key config fields:

```yaml
config:
  worker:
    baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
    templateRoot: /app/scenario/templates
    serviceId: default
    threadCount: 1              # max concurrent journeys
    steps: []                   # journey step definitions
    debugCapture:               # optional debug capture config
      mode: ERROR_ONLY          # ERROR_ONLY | ALL | NONE
      samplePct: 0.0
      maxBodyBytes: 262144
      includeHeaders: true
```

Consult `docs/pockethive-ref/capabilities/http-sequence.latest.yaml` for the full manifest.

### clearing-export

Template-driven file output worker. Batches WorkItems and writes clearing files.
See `docs/ai/CLEARING_EXPORT_WORKER_PLAYBOOK.md` for full config schema and examples.
Consult `docs/pockethive-ref/clearing-export-service-README.md` for implementation notes.

## WorkItem history policy

Configurable per worker via `config.worker.historyPolicy`:

- `FULL` (default) — every stage appends a step; full pipeline history preserved
- `LATEST_ONLY` — collapses to latest step only (saves memory for high-volume)
- `DISABLED` — drops history after each hop; current step retained

```yaml
config:
  worker:
    historyPolicy: LATEST_ONLY
```

## Docker compose topology (standard ports)

| Service | Internal port | Host port | Proxy route |
|---|---|---|---|
| UI (nginx) | 8088 | 8088 | `/` |
| Orchestrator | 8080 | — | `/orchestrator/*` |
| Scenario Manager | 8080 | 1081 | `/scenario-manager/*` |
| RabbitMQ AMQP | 5672 | 5672 | — |
| RabbitMQ Management | 15672 | 15672 | `/rabbitmq/` |
| RabbitMQ WebSocket | 15674 | — | `/ws` |
| Prometheus | 9090 | — | `/prometheus/` |
| Grafana | 3000 | — | `/grafana/` |
| WireMock | 8080 | — | `/wiremock/` |
| TCP Mock Server | 8083 (admin) / 9090 (TCP) | 8083 / 9090 | — |
| TCP Mock TLS | 8083 (admin) / 9091 (TCP) | 8084 / 9091 | — |
| ClickHouse | 8123 | — | — |
| Postgres | 5432 | — | — |
| Redis | 6379 | 6379 | — |

All proxy routes go through the UI nginx on port 8088. Use `POCKETHIVE_BASE_URL`
(default `http://localhost:8088`) as the base for all API calls.

## Common mistakes to avoid

- Do NOT hard-code hostnames in `baseUrl` — use `{{ sut.endpoints[...].baseUrl }}`.
- Do NOT use `work: null` — use `work: {}` for terminal bees.
- Do NOT forget `x-ph-call-id` header when using request-builder.
- Do NOT mix `listName` and `sources` in Redis config — use exactly one.
- Do NOT put secrets in scenario files — use SUT environment variables.
- Ensure `scenario.yaml` `id` matches the bundle folder name.
- Ensure every `x-ph-call-id` value has a matching template file.

## Known runtime vs validator discrepancies

These are confirmed differences between the offline validator and the live runtime.
Always follow the **runtime** behaviour described here.

### 1. `protocol` field in request templates — REQUIRED at runtime

The offline validator previously used `io.pockethive.httpbuilder.HttpTemplateDefinition`
(from `worker-sdk`) which did not have a `protocol` field and would reject it.

The validator has been updated to use `io.pockethive.requesttemplates.HttpTemplateDefinition`
(from `common/request-templates`) which correctly accepts `protocol`, `auth`, and all
other runtime fields. **Rebuild the validator jar if you see `protocol` errors:**

```bash
# From PocketHive repo root in WSL:
./mvnw install -pl common/request-templates,tools/scenario-templating-check -am -DskipTests -q
rm -f tools/scenario-templating-check/target/mcp-classpath.txt
```

Until rebuilt, the validator will still reject `protocol` — but the runtime requires it.
**Always include `protocol` in every template file regardless of validator output.**

```yaml
# HTTP template — protocol required at runtime
serviceId: my-service
callId: my-call
protocol: HTTP        # required by runtime
method: POST
pathTemplate: /api/endpoint
```

Valid values: `HTTP`, `TCP`, `ISO8583`.

### 2. `#sequenceWith` / `#sequence` — validator false positive (Redis unreachable)

The `#sequenceWith` and `#sequence` SpEL functions connect to Redis at evaluation time.
The offline validator runs outside Docker and cannot reach `redis:6379`, so it always
fails with:

```
EL1023E: A problem occurred whilst attempting to invoke the function 'sequenceWith': 'null'
Caused by: io.lettuce.core.RedisConnectionException: Unable to connect to redis/<unresolved>:6379
```

**This is a validator environment limitation, not a template bug.** The function works
correctly at runtime. Treat this error as a false positive and proceed to deployment.
Do not remove or replace `#sequenceWith` calls to fix this error.

### 2. `sut.endpoints[...]` only resolves in `config.baseUrl`

The Orchestrator resolves `{{ sut.endpoints['id'].baseUrl }}` expressions at swarm
create time, but **only in specific top-level config fields** — primarily `config.baseUrl`
on the processor bee. It does NOT resolve these expressions in nested config objects.

**Does NOT resolve:**
```yaml
- role: request-builder
  config:
    worker:
      auth:
        - tokenUrl: "{{ sut.endpoints['auth'].baseUrl }}/token"  # NOT resolved
```

**Does resolve:**
```yaml
- role: processor
  config:
    baseUrl: "{{ sut.endpoints['api'].baseUrl }}"  # resolved at create time
```

For nested fields like `tokenUrl`, use a hardcoded URL or a `variables.yaml` variable.
This applies to ALL nested config — `auth.tokenUrl`, `redis.host`, etc.

### 3. `scenario.deploy` vs `scenario.sync` — use the right tool

- `scenario.sync` copies bundles to `/app/scenarios-runtime/` — this is for **worker
  container mounts** (templates/datasets at runtime). It does NOT make scenarios
  loadable via `scenario.list` or `scenario.get`.
- `scenario.deploy` copies bundles to `/app/scenarios/bundles/` — this is the correct
  path for the Scenario Manager to load and serve scenarios.

**Always use `scenario.deploy` to make a bundle available for swarm creation.**

### 4. Docker Swarm mode breaks swarm creation — force DOCKER_SINGLE

If Docker Swarm mode is active (`docker info | grep Swarm` shows `active`), the
Orchestrator auto-detects it and uses `SWARM_STACK` compute adapter. This causes
two problems:
1. Worker containers land on the overlay network and cannot reach `rabbitmq` on the
   bridge network — all swarms fail silently
2. The swarm-controller is launched as a Docker service which pulls `:latest` from
   the registry. If the registry `latest` tag differs from the locally-cached version,
   you get a `MismatchedInputException` in the journal `template-invalid` event —
   the swarm-controller deserializes the swarm-template with a different schema version

**Diagnostic**: `swarm.get` shows `bufferGuard.problem: "plan-parse-error: MismatchedInputException"`
and `context.totals.desired: 0` (no workers provisioned). The swarm-controller container
appears as a Docker service (`docker service ls`) rather than a plain container.

**Fix:** Set `POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_DOCKER_COMPUTE_ADAPTER: DOCKER_SINGLE`
in the orchestrator service environment in `docker-compose.yml`. This forces plain
`docker run` regardless of Swarm mode being active.

Also set `POCKETHIVE_IMAGES_DEFAULT_TAG: <version>` in the Orchestrator environment
to ensure workers are launched with the correct pinned version rather than `:latest`.

### 5. `swarm.start` timing — wait for workers before starting

`swarm.create` returns as soon as the controller is up, but workers take 20-40s to
start and register. Calling `swarm.start` before all workers have checked in returns
`NotReady` with `pendingConfigUpdates: true` and the start is silently dropped.

**Do NOT use `swarm.wait-ready` for waits longer than ~60s** — it can time out the
MCP connection. Instead, poll `swarm.get` manually in a loop:

```
swarm.create
  → poll swarm.get every 5s until context.totals.healthy == context.totals.desired
  → swarm.start
```

If `swarm.wait-ready` returns `{ ready: false }` rather than `{ ready: true }`, the
swarm is not yet ready — call `swarm.get` to check current state and retry `swarm.start`
once `totals.healthy == totals.desired`.

### 7. `scenario.deploy` silently drops `sut/` and `templates/` on shared filesystems

When the Scenario Manager's scenarios directory is on an NFS or shared mount, the
Scenario Manager container may not have write permission to directories it creates.
`scenario.deploy` (zip upload) creates the bundle directory as root with `755` permissions.
If the container runs as a non-root user, subsequent writes to subdirectories (`sut/`,
`templates/`, `datasets/`) silently fail — only `scenario.yaml` at the root is written.

**Symptoms**:
- `scenario.list` shows the scenario (scenario.yaml was written)
- `GET /scenarios/{id}/suts` returns `[]` even after deploying a bundle with `sut/`
- `GET /scenarios/{id}/templates` returns `[]`
- `swarm.create` with `sutId` returns 500 (`Failed to resolve SUT environment`)

**Diagnostic**: On the server, check the bundle directory permissions:
```bash
ls -la /path/to/scenarios/
# Look for drwxr-xr-x (755) on the bundle dir — group/others have no write
```

**Fix**: Make the Scenario Manager container run as root (`user: "0"` in compose),
or `chmod -R 777` the bundle directory after first deploy, or pre-create bundle
directories with the correct ownership before deploying.

### 8. `debug.tap` on postprocessor — use IN direction

The postprocessor is a terminal worker with no output queue. Tapping `OUT` always
returns empty samples.

**Always tap the postprocessor with `direction: IN, ioName: in`:**
```
debug.tap { swarmId, role: "postprocessor", direction: "IN", ioName: "in" }
```

This is now the default in the MCP tool.

## Observability chain

The postprocessor is the end of the data pipeline and the primary source of transaction
metrics. Understanding this chain is essential for verifying scenarios work correctly.

### Metric flow

```
Postprocessor → Prometheus Pushgateway → Prometheus → Grafana dashboards
```

Workers push metrics to the Pushgateway (scraped every 5s by Prometheus). Grafana
queries Prometheus for visualization. ClickHouse stores detailed transaction outcomes
for historical analysis.

### Key metrics (emitted by postprocessor)

Metrics are always emitted to Prometheus. The postprocessor also writes transaction
outcomes to ClickHouse when `writeTxOutcomeToClickHouse: true`.

| Metric | Type | Labels | What it measures |
|---|---|---|---|
| `ph_transaction_hop_duration_ms` | Gauge | `ph_swarm`, `ph_role`, `hop_index`, `hop_service` | Duration of each pipeline hop |
| `ph_transaction_total_latency_ms` | Gauge | `ph_swarm`, `ph_role` | End-to-end latency from generator to postprocessor |
| `ph_transaction_processor_duration_ms` | Gauge | `ph_swarm`, `ph_role` | Time spent in the processor HTTP/TCP call |
| `ph_transaction_processor_success` | Gauge | `ph_swarm`, `ph_role` | 1.0 = success, 0.0 = failure |
| `ph_transaction_processor_status` | Gauge | `ph_swarm`, `ph_role` | HTTP status code (200, 500, etc.) |

### Grafana dashboards (pre-provisioned)

| Dashboard | Data source | What it shows |
|---|---|---|
| Pipeline Observability | Prometheus | Worker throughput, queue depths, latencies |
| Pipeline Observability (Detailed) | Prometheus | Per-hop durations, processor call stats |
| TX Outcomes | ClickHouse | Transaction success/failure rates, status codes |
| TX RTT Latency | ClickHouse | Round-trip time distributions |
| TX RTT Overview | ClickHouse | Aggregate RTT by swarm/role |
| TX RTT Quality | ClickHouse | Latency percentiles (p50, p95, p99) |
| Logs | Loki | Structured log search across all workers |
| Journal | Postgres | Control-plane event timeline |

### Verifying metrics in the TDD workflow

During Phase 4 (VERIFY), after confirming messages flow via `debug.queues` and
`debug.tap`, also check:

1. **Prometheus** — Query `ph_transaction_total_latency_ms{ph_swarm="<swarmId>"}` via
   the Prometheus API at `<POCKETHIVE_BASE_URL>/prometheus/api/v1/query`.
2. **Grafana** — Open `<POCKETHIVE_BASE_URL>/grafana/` and check the Pipeline
   Observability dashboard filtered by swarm.
3. **Status snapshot** — `swarm.get` returns worker status with throughput and latency
   in `data.context`.

### Enabling detailed metrics

```yaml
- role: postprocessor
  image: postprocessor:latest
  config:
    worker:
      writeTxOutcomeToClickHouse: true   # Write outcomes to ClickHouse
      forwardToOutput: false
  work:
    in:
      in: post
```

> **Note**: `publish-all-metrics` was removed in 0.15.7. Do not use it.
> Use `writeTxOutcomeToClickHouse: true` instead for detailed transaction recording.
