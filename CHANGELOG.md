# Changelog

All notable changes to this project will be documented in this file.

## [0.14.28] - 2026-02-22
Timestamp: 2026-02-22T17:26:00Z

- Clearing Export lifecycle journal: file lifecycle events (`created`, `write-failed`, `finalize-failed`, `flush-summary`) are now published as normal control-plane outcomes (`work-journal`) instead of alerts.
- Worker SDK: added explicit `publishWorkJournalEvent(...)` API for journal outcomes without synthetic `WorkItem` fallback, with required `correlationId` and normalized optional context fields.
- Clearing Export streaming hardening: improved failure semantics and state recovery for append/open/finalize paths (including rollover reopen failure handling) to avoid duplicate/misclassified lifecycle events.
- Clearing Export tests: added hardening coverage for flush policy, retry semantics, streaming open/finalize/reopen failures, and local sink finalize atomic rename behavior.
- Docs: expanded structured clearing playbook, linked canonical structured schema contract SSOT, and updated in-progress implementation tracking.
- Clearing Export: added template streaming append mode (`streamingAppendEnabled`, `streamingWindowMs`) to support long file windows with low memory usage (`*.tmp` append + finalize by time/count + atomic rename).
- Clearing Export: added structured schema-driven path hardening (schema registry usage, strict structured mode validation, XML formatter coverage, and structured demo scenario wiring).
- E2E/observability: added `clearing-export-streaming-demo` end-to-end scenario and fixed clearing-export status refresh after scheduled flush so `filesWritten`/buffer metrics stay accurate in runtime snapshots.
- Docs/playbook: expanded clearing-export configuration and usage guidance, including explicit note that `streamingFsyncPolicy` is intentionally not exposed in this version.

## [0.14.27] - 2026-02-21
Timestamp: 2026-02-21T17:33:17Z

- Processor service: HTTP clients now use JVM/system networking properties (`proxy`, `https.proxy*`, `http.nonProxyHosts`) so processor traffic honors proxy + no-proxy configuration.
- Processor service: added unit coverage confirming per-thread HTTP client uses the system route planner (`SystemDefaultRoutePlanner`).

## [0.14.26] - 2026-02-15
Timestamp: 2026-02-15T20:50:43Z

- Scenarios (UI): preserve YAML literal blocks (e.g. `body: |`) when editing plans/templates so YAML round-trips don’t introduce broken escapes.
- Scenario Manager: raw scenario updates now persist the submitted YAML verbatim (after validation) instead of re-serializing via YAML mapper (keeps `|` blocks intact); add coverage test.

## [0.14.25] - 2026-02-14
Timestamp: 2026-02-14T14:01:12Z

- Postprocessor/ClickHouse: batch tx-outcome inserts (`batch-size`, `flush-interval-ms`, `max-buffered-events`) and flush on shutdown; surface `txOutcomeBufferFull` in status for buffer backpressure.
- Control plane + Worker SDK: prevent redelivery storms by ACKing and dropping malformed/failing control/work messages (`default-requeue-rejected: false`, plus defensive listener try/catch).
- Orchestrator: fix illegal STOPPED -> STOPPING transition spam by making STOPPED transitions conditional on current registry state; stop is a no-op unless RUNNING.
- Orchestrator/Swarm Controller: add explicit Hikari pool/timeouts via env to reduce Postgres connection pressure.

## [0.14.24] - 2026-02-13
Timestamp: 2026-02-13T01:15:01Z

- Postprocessor: inject ClickHouse sink settings from the platform (orchestrator -> swarm-controller -> postprocessor env), avoiding any ClickHouse credentials in scenario bundles.
- Orchestrator: redact sensitive values (password/secret/token) when logging container environment for swarm-controller launches.
- ClickHouse: fix `ph_tx_outcome_v1` TTL expression to handle `DateTime64` columns reliably.

## [0.14.23] - 2026-02-06
Timestamp: 2026-02-06T18:17:37Z

- Worker SDK: canonicalise `config-update` payload keys (kebab/snake/camel case) so overrides like `baseUrl` are reliably applied (prevents accidental fallback to HTTP defaults when sending HTTPS endpoints).

## [0.14.22] - 2026-02-03
Timestamp: 2026-02-03T14:27:54Z

- Scenarios: added Scenario Variables (`variables.yaml`) with strict validation (types: string/int/float/bool) and profile + SUT matrix resolution.
- Scenarios: added bundle-local SUTs under `sut/<sutId>/sut.yaml`, plus Scenario Manager endpoints for listing/reading/editing them.
- Orchestrator: `swarm-create` accepts `variablesProfileId` (and `sutId` when needed) and injects resolved variables into bee configs as `config.vars`.
- Worker SDK: exposes `vars.*` in Pebble templates and `eval(...)` SpEL expressions; improved error/alert logs with correlation/idempotency context.
- UI (legacy): scenario editor can edit `variables.yaml` and bundle-local `sut.yaml`; create-swarm supports selecting `variablesProfileId`.
- Docs + examples: added `scenarios/bundles/variables-demo/` and expanded templating guides; E2E now covers Scenario Variables end-to-end.

## [0.14.21] - 2026-01-22
Timestamp: 2026-01-22T14:23:07Z

- CI: disabled GitHub Pages workflow to avoid accidental doc builds on repo content.
- Swarm Controller: stop bind-mounting `scenarios-runtime` into workers; rely on the scenario bundle mount instead.
- Worker SDK (CSV): resolve relative CSV paths against `/app/scenario` and surface resolved paths in diagnostics.
- Docs: update CSV dataset guidance to use scenario bundle-relative paths.

## [0.14.20] - 2026-01-16
Timestamp: 2026-01-16T10:47:13Z

- Auth system: added worker-sdk auth module with OAuth2 + static auth strategies, token caching, and background refresh.
- Request Builder: template-level auth blocks now inject headers for HTTP/TCP envelopes.
- Templating: added `#authToken()` helper for pulling cached tokens into templates.
- Tests/docs: added auth unit tests plus user/behavior docs for configuration and usage.

## [0.14.19] - 2026-01-14
Timestamp: 2026-01-14T11:17:56Z

- TCP processor: added protocol handlers with Netty/NIO/socket transports, connection pooling, timeout enforcement, metrics, and expanded tests.
- Request Builder service: new worker for HTTP/TCP template loading and definitions, default templates + protocol mappings, and worker tests.
- TCP Mock: introduced `tcp-mock-server` module + docker-compose service, mappings/UI/docs, and wired SUT environments + scenarios to it.
- Scenarios & E2E: added TCP demo bundles (echo/netty/nio/ssl/pooling/perf/streaming/timeout) and updated lifecycle tests for tcp-mock timeouts.
- Templating & performance: improved Pebble template error visibility and capped template cache (with tests); added TCP logging/performance docs and a dashboard password warning.
- Main baseline: Redis-backed sequencer functions (`#sequence`, `#sequenceWith`, `#resetSequence`) merged to `main` (docs: `docs/scenarios/SCENARIO_TEMPLATING.md`).

## [0.14.18] - 2025-12-19
Timestamp: 2025-12-19T00:00:00Z

- Journals (UI): added run list range/limit controls (default: last 1 day) to keep the runs view responsive with large Postgres histories.
- Journals (API): added `afterTs` filtering to `/api/journal/swarm/runs` so clients can query recent runs without scanning the full retention window.
- Hive UI: fixed SUT details panel to auto-refresh every 5s (including Wiremock panels) instead of only updating on click.
- E2E: updated lifecycle assertions to read canonical status fields from `data.context` (scenario progress + worker snapshots).
- Docs (todo): added critical work items for control-plane contract enforcement and a SwarmRuntimeCore refactor.

## [0.14.17] - 2025-12-17
Timestamp: 2025-12-17T00:00:00Z

- Scenario authoring (UI): fixed generator/http-builder capability paths to `config.worker.*`, so message body/headers render correctly and switching body type toggles HTTP-only fields.
- Capabilities API: Scenario Manager now exposes `when` conditions from manifests to the UI (dropdowns can hide/show dependent fields).
- Scenario authoring (UI): render weighted shortcuts inside JSON objects (e.g. `worker.message.headers`) using the weighted editor controls.

## [0.14.16] - 2025-12-17
Timestamp: 2025-12-17T00:00:00Z

- Scenario authoring (UI): improved config-update capability editing with group tabs, search + “only overridden” filtering, stable modal sizing, auto-override on edit, and a full-screen Monaco editor for large text/JSON fields.
- Capabilities rendering: added support for `when`-gated fields plus UI metadata (`ui.group`, `ui.label`, `ui.help`), and hid IO transport selectors in runtime views while keeping tunable IO parameters.
- Scenarios runtime root: hardcoded container destination to `/app/scenarios-runtime` and treated `POCKETHIVE_SCENARIOS_RUNTIME_ROOT` as the host bind-mount source when starting containers.

## [0.14.15] - 2025-12-16
Timestamp: 2025-12-16T00:00:00Z

- Journals (Postgres): added durable journal storage with retention + pin/archive support, plus paginated query endpoints for Hive and swarm runs.
- Journals (UI): introduced a Journals entry point with run-centric navigation (runId-first), metadata editing (scenario/test plan/tags), and corrected Hive vs Swarm journal routing.
- Grafana/Loki: provisioned Postgres datasource + added Journal/Logs dashboards and enabled local log shipping so log drill-down works.
- Scenario authoring: fixed `schemaRef` drift by introducing a single SSOT HTTP template definition shared by http-builder and the templating validator, and documented `schemaRef` for HTTP templates.
## [0.14.14] - 2025-12-12
Timestamp: 2025-12-12T00:00:00Z

- Hive Capacity view: renamed the “Perf” route to `/capacity` to match the Capacity tab, split the layout so the left configuration panel scrolls independently from the React Flow canvas, persisted the capacity model graph to browser storage (with a Reset view button to restore the default model), and tightened node connectors so Synthetic IN nodes expose only outputs while Synthetic OUT/DB nodes expose only inputs.
- Capacity zoom controls: added explicit dark-mode styling for the React Flow zoom/controls widget so its buttons and icons remain visible against the dark background across browsers (including Firefox).
- Hive SUT dropdowns: fixed SUT selection styling in the Hive swarm create modal so the control uses a dark background with light text, avoiding the white-on-white rendering seen in some Chrome configurations.

## [0.14.13] - 2025-12-11
Timestamp: 2025-12-11T00:00:00Z

- Capacity modeler (Hive UI, experimental): introduced a new “Capacity” page for building synthetic service/OUT/DB graphs, computing per-node throughput, latency and drop rate, visualising bottlenecks, and animating request paths with a bee overlay so parallel vs sequential calls and DB hops are easy to see.
- Scenario bundles & HTTP templates: extended Scenario Manager with bundle-level HTTP template/schema APIs and updated the Hive Scenarios page with a new `HTTP templates` view that lists bundle templates, supports adding new YAML files, and wires “Edit/Attach schema” flows via `schemaRef` into JSON Schema-backed body editors for generators and HTTP templates.
- Docs & tooling alignment: documented `schemaRef` in `SCENARIO_CONTRACT.md`, refreshed scenario bundle/templating docs, updated the in-progress `scenario-bundle-runtime-plan` to match the current implementation, and added a completion timestamp to `build-hive.sh` so local builds clearly show when they finished.

## [0.14.12] - 2025-12-10
Timestamp: 2025-12-10T00:00:00Z

- Version bump for the Capacity modeler work; see the Unreleased section for a high-level summary of the new UI and modeling capabilities.

## [0.14.11] - 2025-12-09
Timestamp: 2025-12-09T00:00:00Z

- Scenario plans: taught the swarm-controller timeline engine to parse both ISO-8601 (`PT5S`, `PT1M`) and shorthand (`5s`, `15s`) step offsets so plans authored or edited via the Hive UI remain executable; fixed the built-in `local-rest-plan-demo` bundle so its plan drives swarm start/stop and generator rate changes end-to-end again.
- Scenario bundles & UI: updated Scenario Manager to persist scenarios as bundle descriptors under `scenarios.dir/bundles/<id>/scenario.(yaml|json)` and wired the Hive Scenarios page help/docs to the new Scenario Plan guide and bundle overview, keeping authoring flows aligned with the on-disk layout.
- Hive topology polish: narrowed HTTP worker role detection to processors only and improved SUT node placement in the topology graph layout to reduce overlap and keep SUT relationships readable on initial render.

## [0.14.10] - 2025-12-05
Timestamp: 2025-12-05T00:00:00Z

- Swarms tabular view: added a new `/swarms` page in the Hive UI that renders a scalable text/table view of all swarms using the existing component feed and swarm metadata, including per-swarm status, heartbeat age, template/SUT, role counts, queue depth/consumer totals, scenario progress, and guard state.
- Swarm controls: wired per-row controls on the Swarms page for starting/stopping swarms, resetting the active scenario plan for the controller, and bulk start/stop actions across selected swarms, reusing the same control-plane operations as the Hive topology view.
- Swarm details drawer: integrated the existing swarm-controller detail panel as a side drawer on the Swarms page so operators can inspect controller/runtime diagnostics without leaving the text view; removed the broken “open swarm view” link until a dedicated zoom route is available.

## [0.14.9] - 2025-12-04
Timestamp: 2025-12-04T23:50:00Z

- Scenario plan controls: Swarm Controller now accepts `config-update` payloads under `data.scenario` (`reset`, optional `runs`) to restart the active plan and loop it a fixed number of times; controller status includes `scenario.totalRuns` and `scenario.runsRemaining` so UI/runtime tooling can surface progress.
- Scenario engine resiliency: Timeline runner resets cleanly, tracks run counters across loops, and emits last/next step details consistently in status snapshots, keeping plan progress visible even after repeated runs.
- Hive UI: Swarm Controller detail panel now renders the latest scenario status (last/next step with countdown) and adds a “Scenario controls” section with run-count input and a reset action wired to the new control-plane fields; STOMP merge logic was fixed so scenario updates no longer get dropped after the first snapshot.

## [0.14.8] - 2025-12-02
Timestamp: 2025-12-02T12:30:00Z

- SUT raw editing in Scenario Manager: extended the SUT registry with `/sut-environments/raw` (GET/PUT) so the full `sut-environments.yaml` file can be fetched and updated over HTTP, with server-side validation that the content parses into a `List<SutEnvironment>` before it is written back to disk and reloaded.
- SUT authoring UI: added a dedicated “Systems under test” view with a Monaco-based YAML editor wired to the raw SUT endpoints, including an “Insert template” helper snippet for new environments and a save flow that surfaces backend parse errors, making it practical to define and evolve SUTs from the Hive UI instead of hand-editing files in the container.
- SUT diagnostics & badges in Hive: refined the Hive topology and detail panels so swarms show their bound SUT in the swarm-controller runtime section, HTTP workers (processor/http-builder) display a small “SUT” badge plus a “System under test” line, and clicking SUT nodes on the graph opens a SUT detail panel that reuses the existing WireMock metrics view when `ui.panelId: wiremock` is configured.

## [0.14.7] - 2025-11-28
Timestamp: 2025-11-28T12:00:00Z

- System under test (SUT) environments: introduced `sut-environments.yaml` and a small SUT registry in Scenario Manager (`/sut-environments` + `sut-environments.schema.json`), so environments like `wiremock-local` can be defined once with named HTTP endpoints (e.g. `default.baseUrl`) and reused across scenarios without hard-coding URLs.
- SUT-aware swarm creation & templating: extended Orchestrator’s `SwarmCreateRequest`/`SwarmController` to accept an optional `sutId`, resolve the bound `SutEnvironment`, and apply `baseUrl: "{{ sut.endpoints['<id>'].baseUrl }}..."` templates when building bee configs; `templated-rest.yaml` and `redis-dataset-demo.yaml` now pick their processor baseUrl from SUT endpoints instead of literal WireMock URLs.
- SUT in Hive UI: added a `SutEnvironmentContext` with localStorage-backed selection, wired the *Create Swarm* modal to show a *System under test* dropdown driven by `/sut-environments`, and updated the Swarm Controller detail panel to render the bound SUT name/type for easier inspection of where a swarm is pointing.
- SUT-aware e2e harness: taught `SwarmLifecycleSteps` to pass `sutId` for scenarios that rely on SUT templating (`templated-rest`, `redis-dataset-demo`) and to expect `"guarded wiremock response"` for the guarded template while still asserting `"default generator response"` for the baseline scenarios, restoring green lifecycle tests after the SUT introduction.

## [0.14.6] - 2025-11-28
Timestamp: 2025-11-28T00:00:00Z

- Scheduler input diagnostics: fixed a `NullPointerException` in `SchedulerWorkInput` status publishing when no finite `maxMessages` limit is configured by switching from `Map.of(...)` to a null-safe `LinkedHashMap` and omitting the `remaining` field when there is no quota, restoring stable scheduler ticks in long-running generators.
- Guard IO alignment: adjusted Swarm Controller buffer guard resolution to treat scheduler and Redis inputs via `inputs.type` and `inputs.<kind>.ratePerSec`, updated guard-related unit tests and the `local-rest-two-moderators` scenario to use the new IO config shape, and removed the stale `SwarmLifecycleManager.start(planJson)` guard reconfiguration so `swarm-start` no longer clears guards that were configured from `swarm-template`.
- Guard diagnostics & status: extended `BufferGuardCoordinator` to track whether guards are active and the last diagnosed problem (e.g. `no-traffic-policy`, `missing-producer`, `no-rate-input`), surfaced this via `SwarmLifecycle.bufferGuardActive()` / `bufferGuardProblem()`, and added status fields under the swarm-controller node (`data.bufferGuard.active` and `data.bufferGuard.problem`) so operators can see when guard configuration is miswired without relying solely on logs.
- UI runtime panels: updated the Hive component details panel for the swarm-controller to render buffer guard runtime information (showing “Buffer guard: active|inactive” and any `Guard problem` string), aligned the moderator capability manifest with its JSON contract by using `pass-through`, `rate-per-sec`, and `sine` as option values, and ensured moderator mode selection in the UI reflects the actual worker mode instead of reverting to an uppercase fallback.

## [0.14.5] - 2025-11-27
Timestamp: 2025-11-27T12:00:00Z

- Swarm lifecycle & Hive UI: fixed a bug where Swarm Controller components could remain visible in the Hive topology after a swarm was removed by teaching the UI to drop all components for a swarm whenever it sees either a `ready.swarm-remove` confirmation or a controller status event with `data.swarmStatus: "REMOVED"`, ensuring the graph stays in sync with orchestrator state even if the UI misses the original remove confirmation.

## [0.14.4] - 2025-11-27
Timestamp: 2025-11-27T00:00:00Z

- Manager SDK & compute adapters: introduced a shared `ComputeAdapterType` enum (with `DOCKER_SINGLE`, `AUTO`, `SWARM_STACK`) and centralised `DockerSingleNodeComputeAdapter` and Swarm-based `ComputeAdapter` implementations in `common/docker-client`, so both Orchestrator and Swarm Controller can use the same single-host and Swarm-backed compute backends without duplicating logic.
- Orchestrator compute mode: added `pockethive.control-plane.orchestrator.docker.compute-adapter` with `AUTO` default; the orchestrator now inspects `docker info` to resolve `AUTO` into `DOCKER_SINGLE` or `SWARM_STACK` (only when the engine is a Swarm manager), logs the resolved adapter type at startup, and propagates the concrete choice to the Swarm Controller via `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_COMPUTE_ADAPTER`.
- Swarm Controller compute mode: rewired worker provisioning to go through the shared compute adapters (`DockerSingleNodeComputeAdapter` for single-host, `DockerSwarmServiceComputeAdapter` for Swarm services) while keeping existing queue, volume, and guard behaviour intact, and tightened config so controller no longer guesses adapter types.
- Status & UI diagnostics: extended orchestrator `status-full`/`status-delta` events to include `data.computeAdapter`, updated the Hive UI component details panel to show `Compute adapter` for the orchestrator node, and ensured status deltas keep this field up to date so cluster operators can see the active compute mode at a glance.

## [0.14.3] - 2025-11-26
Timestamp: 2025-11-26T00:00:00Z

- Orchestrator & Docker integration: added an explicit `autoPullImages` flag on `/api/swarms/{swarmId}/create` (surfaced in the swarm create modal) that, when enabled, causes the orchestrator to `docker pull` the swarm-controller image during swarm creation and then preload all bee images referenced by the scenario template using the configured image repository prefix, without introducing new fallbacks.
- Docker client: introduced a `DockerContainerClient.pullImage(image)` helper that wraps the Docker Java `pullImageCmd` with consistent error translation and interrupt handling, so orchestrator and future manager SDK users can preload images via a single, reusable entry point.
- Status & templates: extended swarm template metadata to keep the controller image and bee list available at runtime, allowing the orchestrator to resolve and log which images were preloaded for each swarm and paving the way for future image diagnostics and tooling.

## [0.14.2] - 2025-11-24
Timestamp: 2025-11-24T00:00:00Z

- Manager SDK & Swarm Controller: extracted a reusable `manager-sdk` module (runtime core, ports, guards, scenarios) and rewired the Swarm Controller onto `ManagerRuntimeCore`, cleaning up `SwarmLifecycleManager`, fixing swarm control-queue leaks on remove, and making BufferGuard/guards pluggable and multi-instance.
- Worker IO configuration: removed input/output wiring from `@PocketHiveWorker` so all workers now take IO types from `pockethive.inputs.type` / `pockethive.outputs.type`; updated generator, processor, postprocessor, moderator, trigger, HTTP Builder, and Swarm Controller workers to the new config model and retired the dedicated Data Provider worker in favour of reusing generator with Redis input.
- IO capabilities & UI: introduced shared IO capability manifests (`io.scheduler.latest.yaml`, `io.redis-dataset.latest.yaml`) keyed by `ui.ioType` so the Hive UI can surface scheduler (`inputs.scheduler.*`) and Redis dataset (`inputs.redis.*`) knobs for any worker whose `inputs.type` matches, and adjusted the config panel to merge worker- and IO-level config entries without duplicating definitions per worker.
- Scheduler finite-run & diagnostics: extended the scheduler input with `inputs.scheduler.maxMessages` and a one-shot `reset` flag, emitting `x-ph-scheduler-remaining` on seeds and surfacing `data.scheduler` telemetry (rate, maxMessages, dispatched, remaining, exhausted); the UI runtime panel now renders these fields.
- Redis dataset diagnostics: added `data.redisDataset` status (host, port, listName, ratePerSec, dispatched, lastPopAt/lastEmptyAt/lastErrorAt/lastErrorMessage) updated by `RedisDataSetWorkInput`, and wired the Hive UI runtime panel to display Redis list/rate/last activity and error information.
- Scenario-driven Docker volumes: completed the volume wiring so `Bee.config.docker.volumes` in scenarios is resolved by `SwarmRuntimeCore` into Docker bind mounts via `WorkloadProvisioner`, covered by `SwarmRuntimeCoreVolumesTest` and `DockerWorkloadProvisionerVolumesTest` and documented in the scheduler/volumes plan and Swarm Controller architecture notes.

## [0.14.1] - 2025-11-21
Timestamp: 2025-11-21T00:00:00Z

- Worker SDK: add a Redis dataset work input that pops from a configured Redis list at a fixed rate, emitting each entry as a `WorkItem` while skipping delivery when the list is empty.
- Stack: ship Redis + Redis Commander in docker-compose and surface the Redis UI link in the Hive toolbar.
- Worker SDK: add a Redis uploader interceptor (config-only opt-in) that can route payloads back into Redis lists based on simple regex rules or a fallback/original list.
- HTTP Builder: add a new `http-builder` worker that resolves disk-backed HTTP templates (`serviceId` + `callId`) into HTTP envelopes consumed by the existing processor, with configurable `passThroughOnMissingTemplate` behaviour and per-template status metrics.
-- Templating & helpers: extend the Pebble-based templating engine with a constrained SpEL-backed `eval(...)` helper (exposing `workItem`, `now`, `rand*`, hashing/encoding helpers, etc.) and integrate it into generator and HTTP Builder pipelines.
- Scenario tooling: introduce the `tools/scenario-templating-check` CLI to render generator templates and validate HTTP Builder templates against scenarios (callId coverage + one-shot render to catch Pebble/SpEL errors).
- Redis dataset demo: add the `redis-dataset-demo` scenario wiring generator → Redis uploader → per-customer data providers → HTTP Builder → processor, plus an e2e harness scenario that asserts dataset, HTTP request, and HTTP response steps are present on the final queue.
- Capabilities & UI: publish HTTP Builder and Data Provider capability manifests so the Hive UI can expose HTTP template roots, call selectors, and Redis dataset rate controls.
- Tooling: document the MCP-based orchestrator/debug CLI and expose it to agents via `AGENTS.md` to streamline control-plane inspection from MCP-enabled clients.

## [0.14.0] - 2025-11-20
Timestamp: 2025-11-20T00:00:00Z

- WorkItem history model and transport:
  - Refactored `WorkItem` to be step-driven (no internal `body` field); `asString()` and `payload()` now always reflect the latest `WorkStep` payload while `body()` and JSON helpers derive from that payload.
  - Moved step history off the `x-ph-workitem-steps` Rabbit header into a JSON envelope carried in the message payload, and updated the worker SDK converter, Rabbit-based outputs/inputs, and e2e harness to read/write `steps` from the payload.
  - Ensured generator → moderator → processor hops append successive steps so templated and default REST scenarios carry a complete, ordered history into the final queue.
- HTTP processor execution modes and virtual threads:
  - Extended `ProcessorWorkerConfig` with `mode` (`THREAD_COUNT`/`RATE_PER_SEC`), `threadCount`, `ratePerSec`, and `connectionReuse` flags, and wired `ProcessorWorkerImpl` to cap concurrency via a semaphore and pace requests via a shared `nextAllowedTimeNanos`.
  - Added a small client pool for `connectionReuse=PER_THREAD` in `THREAD_COUNT` mode, and documented that `connectionReuse=NONE` currently behaves like `GLOBAL` due to JDK `HttpClient` header restrictions, with a future plan to introduce a true no-keep-alive client.
  - Introduced a `VirtualThreadRabbitContainerCustomizer` bean in the Worker SDK so the default `rabbitListenerContainerFactory` uses virtual threads for work dispatch, allowing blocking worker code to scale without exhausting platform threads.
- HTTP processor latency, connection reuse, and capabilities:
  - Replaced the JDK HttpClient-based processor with an Apache HttpClient 5 implementation that separates pure HTTP call latency from pacing delay in RATE_PER_SEC mode, exposing both on step headers (`x-ph-processor-duration-ms`, `x-ph-processor-connection-latency-ms`) and reporting average call latency in status (`avgLatencyMs`).
  - Introduced a generic MaxInFlightConfig hook in the worker SDK and wired RabbitMessageWorkerAdapter to cap concurrent worker invocations via a bounded ThreadPoolExecutor (SynchronousQueue, no backlog), so the processor’s `threadCount` now directly represents max in-flight HTTP calls per instance.
  - Added `keepAlive` and `connectionReuse` (`GLOBAL` / `PER_THREAD` / `NONE`) to `ProcessorWorkerConfig`, backed by a shared Apache connection pool for GLOBAL, per-thread clients for PER_THREAD, and a no-keep-alive client when disabled; surfaced the effective pool size via `httpMaxConnections` in runtime status.
  - Updated processor capabilities (`processor.latest.yaml`, capabilitiesVersion 1.1) to document THREAD_COUNT vs RATE_PER_SEC semantics, concurrency mapping, connection reuse/keep-alive behaviour, and latency metrics inline with the config entries so UI authors and scenario writers can tune the worker without chasing external docs.
  - Refined the Hive UI component details panel to render enum-backed config entries as dropdowns (e.g., processor mode/connectionReuse, moderator mode.type, generator HTTP method), display processor runtime fields such as HTTP mode/thread count/max connections, and improve the swarm create modal with a multi-line scenario list and rich preview pane.
- Moderator rate limiting: fixed mode transitions so switching to pass-through or zero/invalid rates clears pending schedule targets, preventing stalls after leaving a slow SINE/RATE_PER_SEC configuration.
- Deployment packaging: include `rabbitmq/` definitions/config in the deployment bundle so external deployments carry the Rabbit setup alongside compose and observability configs.
- Swarm controller observability: added INFO logs for controller start/stop, swarm-wide enable/disable config-updates, and buffer guard lifecycle transitions so operators can trace controller activity without digging through DEBUG output.
- Control-plane tracing: SwarmSignalListener now prints the incoming swarm template payload plus every controller-level config update with the resulting config snapshot, mirroring worker runtime logging.
- Logging configuration: relaxed the logback filter so `io.pockethive.swarmcontroller` logs at INFO by default, ensuring the new lifecycle/config messages reach both console and RabbitMQ appenders.
- Swarm controller bootstrap config: delay initial per-worker `config-update` signals until after the first status heartbeat so workers only receive overrides once their control queues are declared, reducing races where bees briefly run with default config.

## [0.13.9] - 2025-11-15
Timestamp: 2025-11-15T00:00:00Z

- WorkerContext API cleanup: `config(Class)` now returns the concrete configuration (plus a new `configOptional` helper) so worker code doesn’t have to unwrap `Optional`s. All worker implementations, starter samples, and SDK/runtime tests were updated to the new contract.
- Repository hygiene: removed the deprecated `examples/worker-starter` templates and scrubbed the quick start / removal plans so documentation no longer points to the deleted scaffolding.

## [0.13.8] - 2025-11-14
Timestamp: 2025-11-14T00:00:00Z

- Worker configuration unification: introduced the `@PocketHiveWorkerConfigProperties` meta-annotation so every worker binds defaults from `pockethive.worker.config`, updated all services (generator/moderator/processor/postprocessor/trigger and the starter samples) plus scenarios to drop the old `pockethive.workers.<role>` hierarchy, and refreshed the SDK docs/usage guides to describe the simplified contract.
- Hive UI resiliency: removed the hard-coded role allowlist so every component row exposes start/stop toggles, generalized control-event typing, and changed the topology builder to derive “SUT/WireMock” links from any worker that advertises a `baseUrl`, ensuring renamed/custom roles still render correctly.
- Scenario + e2e harness polish: SwarmLifecycleSteps now logs the exact scenario payload, always taps the postprocessor queue, and normalizes role matching so renamed bees (e.g., `samplePostprocessor`) work without heuristics; the SOAP scenario example now embeds `pockethive.worker.config` overrides.
- Tooling: `start-hive.sh` no longer includes the `restart` stage by default (restoring timing summaries on standard runs) but still supports targeted restarts via `./start-hive.sh restart -- <services>`.

## [0.13.7] - 2025-11-11
Timestamp: 2025-11-11T00:00:00Z

- Control-plane role cleanup: collapse the generator/moderator/processor/postprocessor/trigger topology descriptors into a single `WorkerControlPlaneTopologyDescriptor`, introduce `ControlPlaneEmitter.worker(...)`, and update the Spring descriptor factory plus tests so any worker role (including plugins) shares the same routing/queue logic without hardcoded role lists.
- Scenario config propagation: document `SwarmPlan.bees[*].config` in `docs/ORCHESTRATOR-REST.md`, add test-only scenarios so `SwarmCreationMock1E2ETest` asserts the generator/processor override payloads, and mark the remaining plan steps as complete.
- Swarm controller bootstrap: listen for `ev.error.config-update.*`, drop the pending lifecycle command, emit `ev.error.swarm-template`/`swarm-start`, and mark the swarm `FAILED` so invalid bootstrap configs fail fast instead of timing out.
- SDK naming cleanup: rename `WorkerInputType.RABBIT` to `WorkerInputType.RABBITMQ` so the input/output enums share the same RabbitMQ terminology.

- Worker SDK & workers: introduce `WorkIoBindings` so runtime uses swarm-plan IO config exclusively, remove all `inQueue/outQueue` defaults from service workers and starter samples, update SDK auto-config/listeners/outputs/status wiring plus tests to consume the plan-driven queues, and record the migration progress in `docs/sdk/remove-inqueue-outqueue-plan.md`.
- Swarm Controller queue guard: surface the active `trafficPolicy.bufferGuard` block in the controller’s status payloads and capability manifest so Hive UI can inspect the plan-driven bracket/rate settings directly via the existing Capabilities endpoint.
- Guard scenarios/documentation:
  - Update `local-rest-two-moderators` with guard-focused queue alias, tuned bracket, logging defaults, and WireMock latency notes.
  - Add `local-rest-two-moderators-randomized` plus `/api/guarded-random` WireMock mappings to demo bursty/backpressure control.
  - Extend `docs/USAGE.md` + `docs/observability.md` with guard launch/tuning instructions and a monitoring runbook.
- Scenario config propagation: update scenario/orchestrator/e2e tests to assert `Bee.config` defaults instead of env overrides, mark the plan progress in `docs/control-plane/worker-config-propagation-plan.md`, and keep template outputs in sync with the new config block.
- Swarm controller enablement: cache the desired enabled flag, resend targeted `config-update` commands on worker heartbeats until each instance reports the requested state, and add unit coverage so status snapshots flip to `enabled=true` as soon as the controller observes the heartbeat.

## [0.13.6] - 2025-11-09
Timestamp: 2025-11-09T00:00:00Z

- Control plane & status handling: teach `ControlPlaneConsumer`, `WorkerControlPlane`, and `WorkerControlPlaneRuntime` to resolve signal names from routing keys so status-request heartbeats log cleanly, ensure workers always emit snapshots on incoming probes, and add verbose swarm-controller logs explaining when/why status requests are broadcast (missing vs. stale heartbeats).
- Worker scheduling: enable scheduler support directly in the moderator, processor, postprocessor, and trigger Spring Boot applications and drop the redundant `Scheduling` configuration stubs so all workers rely on the SDK lifecycle hooks.
- SDK & samples: remove the legacy runtime adapters/defaults from the worker-starter generator/processor examples, rely on auto-wired inputs/outputs, and add typed `SampleGeneratorProperties` + updated tests to mirror the production services.
- Worker IO: enforce single-worker JVMs inside the SDK, bind Rabbit inputs/outputs via `pockethive.inputs/outputs.<type>` (and the matching `POCKETHIVE_INPUT_RABBIT_QUEUE` / `POCKETHIVE_OUTPUT_RABBIT_*` env vars), delete the `pockethive.control-plane.queues.*` contract, refresh the worker docs/READMEs/examples, and update orchestrator/swarm-controller tests to rely on the new environment layout.
- Scenarios: add `local-rest-two-moderators.yaml` (generator → moderator → processor → moderator → postprocessor) so the UI can launch a sample multi-moderator pipeline using the new IO contract.
- Documentation: update the SDK quick start, Worker SDK README, processor README, and worker plans to focus on auto-configured inputs/outputs, mark the unification/auto-config documentation tasks complete, and note that runtime adapters/default classes have been deleted.
- Release: bump PocketHive to 0.13.6 so downstream builds and Docker images consume the latest SDK/runtime improvements.

## [0.13.5] - 2025-11-04
Timestamp: 2025-11-04T20:30:00Z

- Docker Compose & deployment: add persistent named volumes for RabbitMQ, Prometheus, Grafana, and Loki, align both compose bundles to shared paths, expose RabbitMQ AMQP/management ports, and update Loki config plus entrypoint to chown `/var/lib/loki` on start.
- Observability & logging: switch the log-aggregator HTTP client to a Spring `RestTemplate`, ensuring Loki pushes work in tests, and adjust push payload retries with clearer error handling.
- UI & routing: fix Prometheus URL construction so relative `/prometheus` proxies resolve correctly from any host and add Loki pushgateway path defaults to start scripts.
- Infrastructure: documented the UI reverse proxy routes (orchestrator, scenario manager, RabbitMQ, Prometheus, Grafana, WireMock) so remote users can reach services via the UI origin.
- Tests & scenarios: refresh orchestrator and end-to-end tests to use the new `local-rest`/`local-rest-with-named-queues` templates, update expected worker image names and config, align RabbitMQ management base URL, and unblock scenario lookups.
- Tooling & scripts: add management API defaults to `start-e2e-tests` scripts, ensure Loki data directories exist with correct ownership, and keep Portainer compose routing consistent with local development.
- Release: bump PocketHive patch version to 0.13.5 so tooling and published artifacts reference the latest build.

## [0.13.4] - 2025-10-31
Timestamp: 2025-10-31T13:02:46Z

- UI: Fix tooltip visibility on Nectar page graphs by adding overflow-visible styling to chart containers and wrapping VictoryChart with positioned div to allow tooltips to escape card boundaries.
- Build & deployment: Add configurable Docker registry support with DOCKER_REGISTRY and POCKETHIVE_VERSION environment variables, enable image tagging for external registries, and introduce push stage to start-hive.sh for publishing images.
- CI/CD: Create GitHub Actions workflows for automatic Docker image publishing to GitHub Container Registry (GHCR) on push to main, tags, and releases with matrix-based parallel builds and build caching.
- Observability: Add execution timing to start-hive.sh with per-stage duration tracking and summary display showing time spent on clean, build-core, build-bees, push, and start stages.
- Metrics: Add publish-all-metrics mode to postprocessor worker for per-transaction metric emission, fix gauge collection handling in detailed metrics tests, and emit processor latency histogram buckets for percentile graph support.
- Moderator: Preserve moderator config structure when merging control-plane data and update moderator capabilities for operation mode configuration.
- Tests: Fix SwarmController tests for startSwarm overload, remove unused imports from stompClient test, address TypeScript build regressions, and preserve mock typing in API helper specs.
- UI: Refresh swarm metadata after swarm-create confirmations, handle unassigned swarm metadata in Hive UI, use swarmId from control events, use controller metadata for capability fallback, and allow Prometheus fetches to skip correlation header.
- Documentation: Create GHCR setup guide with authentication, publishing, and troubleshooting instructions, and add .env.example for registry configuration.
- Release: Bump PocketHive patch version to 0.13.4 so tooling and published artifacts reference the latest build.

## [0.13.3] - 2025-10-28
Timestamp: 2025-10-28T00:00:00Z

- Capabilities & scenario management: add a capability catalogue service with
  YAML/JSON manifest loading, ship curated manifests for each PocketHive worker,
  expose REST endpoints for capability discovery and scenario templates, wire in
  catalogue-aware scenario validation, and publish an updated multi-generator
  mock scenario alongside health/logging coverage.
- Hive UI & UX: introduce a shared capabilities context, render component
  configuration forms from the catalogue with edit toggles, persist dragged node
  positions, streamline the default bee list, and refresh topology styling to
  match the capabilities-driven layout with updated unit tests.
- Orchestrator & idempotency: introduce reservation semantics across component,
  swarm, and swarm manager controllers, extend the in-memory idempotency store,
  and guard swarm creation with per-swarm locks so duplicate requests are
  rejected atomically.
- Documentation & architecture: publish the traffic-shaping guide, moderator
  pattern notes, and updated worker capability references detailing the new
  catalogue workflow.
- Release: bump the PocketHive patch version to 0.13.3 so tooling and published
  artifacts reference the latest build.

## [0.13.2] - 2025-10-25
Timestamp: 2025-10-25T00:00:00Z

- UI & UX: rename the default swarm card to "Services," hide lifecycle and
  removal controls for that special card, and align health metadata and tests so
  the right panel reflects the new label consistently.
- Release: bump the PocketHive patch version to 0.13.2 so tooling and published
  artifacts reference the latest build.

## [0.13.1] - 2025-10-24
Timestamp: 2025-10-24T00:00:00Z

- Control plane & swarm lifecycle: bind bee identities to the shared `ControlPlaneProperties` instance id, drop the unused
  scenario-step APIs, require explicit work assignments, and drive worker queue environment variables from swarm templates so
  queue-first preparations and metrics settings stay in sync across controller, workers, and tests.
- Orchestrator & REST: guard against duplicate swarm creation, make create requests idempotent-friendly, tidy Optional
  handling, and document the conflict semantics in the public REST contract.
- UI & UX: add a full swarm removal flow, prune removed components when swarms become ready, and surface swarm creation
  conflicts in the Hive dialogs with supporting unit tests.
- Documentation & planning: publish the worker capability catalogue, capture the next-phase scenario plan while archiving old
  roadmap material, and add a queue-first mock scenario to the scenario manager.
- Observability & testing: refresh the pipeline Grafana dashboard, standardise the default credentials, propagate metrics fixes
  throughout service configs, and extend the end-to-end suite for named queue suffix coverage.
- Release: bump the PocketHive patch version to 0.13.1 so tooling and published artifacts reference the latest build.

## [0.13.0] - 2025-10-20
Timestamp: 2025-10-20T00:00:00Z

- Control plane & Spring bootstrapping: introduce `WorkerControlPlaneProperties`, the
  `ControlPlaneContainerEnvironmentFactory`, and stricter descriptor settings so
  workers, managers, and the orchestrator fail fast when required control-plane
  queue prefixes, traffic exchanges, or RabbitMQ settings are missing and no
  longer rely on baked-in topology defaults.
- Worker SDK & services: retire the legacy topology constants, resolve queues
  through the new property contract, and update worker runtime adapters, entry
  points, and tests across generator, moderator, processor, postprocessor, and
  trigger services to honour the injected control-plane environment.
- Swarm controller & orchestrator: normalise control queue prefixes, verify the
  controller queue exists at startup, and have the orchestrator build container
  environments (including Docker socket, logging, and traffic wiring) via the
  shared factory so swarms inherit the same contract as local manifests.
- Metrics & ops: surface Prometheus Pushgateway settings through dedicated
  properties, propagate them from the orchestrator to worker containers,
  refresh docker-compose metrics defaults, and require RabbitMQ credentials in
  entrypoint health checks to harden startup diagnostics.
- Documentation & tests: expand the worker bootstrap guide, swarm controller
  configuration reference, migration notes, and SDK guides around the explicit
  environment contract while aligning examples, e2e tests, and orchestrator
  scenarios with the property-driven topology.
- Release: bump the PocketHive minor version to 0.13.0 so published artifacts
  and tooling reference the latest build.

## [0.12.8] - 2025-10-18
Timestamp: 2025-10-18T00:00:00Z

- Control plane & orchestrator: replace ad-hoc initializers with the new `OrchestratorProperties` binding, align every property
  with the control-plane namespace, and refresh Docker Compose overrides, logging configuration, and scenario manager client
  handling to respect the updated config surface and keep tests green.
- Swarm controller: introduce strongly typed `SwarmControllerProperties`, fix binding defaults, forward worker configuration
  updates, and sync application/logback settings with the consolidated control-plane placeholders to stabilise lifecycle, Docker,
  and messaging tests.
- Scenario manager & log aggregator: unify RabbitMQ placeholder usage, supply default ports for Logback, convert the AMQP log
  toggle to an explicit boolean parse, and drop obsolete control-plane initializer wiring so runtime logging honours configuration
  flags.
- Documentation: retire the outdated manager override note, publish the explicit control-plane configuration migration plan, and
  expand the orchestrator configuration guide around the new property model.
- Release: bump the PocketHive patch version to 0.12.8 so published artifacts and tooling reference the latest build.

## [0.12.7] - 2025-10-17
Timestamp: 2025-10-17T00:00:00Z

- Control plane & orchestrator: unify identity property naming across auto-configurations, enforce explicit topology/config defaults, propagate swarm identifiers from service configuration, and inline swarm id resolution so control-plane metadata stays accurate across orchestrator and log aggregator instances.
- Worker runtime: introduce the WorkerStatusScheduler with configuration hooks, centralize delta scheduling, propagate control-plane instance ids through environment wiring, and extend processor/postprocessor metrics collection to cover HTTP hops and Pushgateway publishing.
- UI: add a WireMock synthetic component with polling, metrics panels, request history, and scenario visibility fixes so hive dashboards surface WireMock health alongside other services.
- Ops: standardize environment variable names across manifests and deduplicate the orchestrator instance id configuration to simplify deployment management.
- Tests: configure the unified control-plane identity defaults in integration and unit tests to keep assertions aligned with the new property names.
- Release: bump the PocketHive patch version to 0.12.7 so published artifacts and tooling reference the latest build.

## [0.12.6] - 2025-10-15
Timestamp: 2025-10-15T00:00:00Z

- Worker starter Docker builds: stage the repository documentation and parent modules inside the container context so AsyncAPI schema validation succeeds during packaging.
- Release: bump the PocketHive patch version to 0.12.6 so published artifacts and tooling reference the latest build.

## [0.12.5] - 2025-10-14
Timestamp: 2025-10-14T00:00:00Z

- Worker control-plane runtime: ensure partially targeted worker overrides ignore beans omitted from the workers map so their existing configuration is preserved.
- Release: bump the PocketHive patch version to 0.12.5 so published artifacts and tooling reference the latest build.

## [0.12.4] - 2025-10-13
Timestamp: 2025-10-13T00:00:00Z

- Worker control-plane runtime: seed worker beans with their default configuration, preserve the derived enablement flag,
  and keep status publishers wired so workers surface meaningful state before receiving overrides.
- Worker SDK: merge control-plane config updates on top of seeded defaults and expose helper accessors so worker services can
  read raw maps or typed configs without losing tracking of the latest enablement state.
- Worker services: register each runtime adapter's initial config with the control-plane helper so generator, moderator,
  processor, postprocessor, and trigger workers advertise their defaults immediately after startup.
- Release: bump the PocketHive patch version to 0.12.4 so published artifacts and tooling reference the latest build.

## [0.12.3] - 2025-10-12
Timestamp: 2025-10-12T00:00:00Z

- E2E lifecycle: exclude the implicit default-exchange binding from RabbitMQ management assertions so Mock-1 status checks only
  evaluate the intentional control-plane routing keys.
- Release: bump the PocketHive patch version to 0.12.3 so published artifacts and tooling reference the latest build.

## [0.12.2] - 2025-10-11
Timestamp: 2025-10-11T00:00:00Z

- Release: bump the PocketHive patch version to 0.12.2 so published artifacts and tooling reference the latest build.

## [0.12.1] - 2025-10-10
Timestamp: 2025-10-10T00:00:00Z

- Swarm controller: clean up legacy short hive bindings on every prepare run, rebinding generator/moderator/processor queues with fully qualified routing keys so only controller-managed routes remain.
- Worker runtime & SDK: resolve outbound queue names via swarm-qualified topology constants, remove processor-owned RabbitMQ declarables, and adjust runtime adapters/tests to rely on the controller-provisioned traffic queues.
- Control-plane auto-config: restore worker/manager traffic bindings to the hive exchange using the qualified routing keys and harden tests that guard the declarable wiring.
- Documentation: clarify that worker services consume the autoconfigured RabbitMQ topology and must not override the controller’s hive bindings.

## [0.12.0] - 2025-10-09
Timestamp: 2025-10-09T00:00:00Z

- Control-plane: Introduced an emitter DSL, payload factories, topology descriptors, and Spring auto-configuration so worker services share canonical routing, confirmation builders, and origin-aware filtering when handling control signals.
- Worker SDK: Published the Worker SDK starter bundling the runtime, registry, state store, and observability-aware interceptors alongside Stage 1–3 adoption guides so teams can adopt the simplified worker pipeline.
- Runtime adapters: Adopted the reusable RabbitMessageWorkerAdapter and Worker SDK across worker services, with the trigger runtime coordinating generator scheduling, single-request overrides, and status emission through the control plane.
- Documentation: Consolidated the scenario builder MVP planning archive and refreshed contributor guidance/README content to orient teams around the new control-plane and worker tooling.

## [0.11.2] - 2025-09-27
Timestamp: 2025-09-27T00:00:00Z

- Docs: refresh the published changelog HTML fallback and align the embedded version metadata with 0.11.2 for offline viewing.

## [0.11.1] - 2025-09-26
Timestamp: 2025-09-26T00:00:00Z

- Swarm controller: derive config-update routing keys via `ControlPlaneRouting.signal` so Start/Stop All fan-outs target `sig.config-update.<swarm>.ALL.ALL`, ensuring bees receive broadcasts and the controller resumes itself.

## [0.11.0] - 2025-09-25
Timestamp: 2025-09-25T00:00:00Z

- Build: Copy common/control-plane-core module into every service Docker build context so Docker builds resolve worker control-plane helpers.
- Orchestrator: Default controller toggle requests to CommandTarget.SWARM when omitted and backfill tests covering legacy payloads.
- UI: Send commandTarget with swarm manager enable/disable calls and expand Vitest coverage to guard the new payload contract.

## [0.10.2] - 2025-09-19
Timestamp: 2025-09-19T00:00:00Z

- UI: replace the queen-specific swarm controls with a dedicated orchestrator panel that surfaces health via HAL indicators, shows active swarm counts, toggles the orchestrator enablement badge, and confirms start/stop commands before dispatching them.
- Swarm controller: advertise the concrete `sig.swarm-*` control routes for the active swarm in status events and emit config-update confirmations on the precise `ev.ready.config-update.<role>.<instance>` routing key so orchestrator clients can correlate responses.
- Tooling: rewrite the Windows `start-hive.bat` bootstrapper into a staged workflow that validates Docker prerequisites, allows selecting clean/build/start phases, and removes duplicated echo syntax.

## [0.10.1] - 2025-09-18
Timestamp: 2025-09-18T00:00:00Z

- UI: render swarm controllers as grouped topology cards that embed their swarm component icons and preview internal queues while leaving hive-level services outside swarm navigation.
- UI: open swarm details from the controller's Details action to show full component cards and keep hive-level selections from toggling swarm filters.
- UI: display instance identifiers and role metadata on topology cards, including active swarm totals for orchestrators, while preserving node positions after drag updates.

## [0.10.0] - 2025-09-17
Timestamp: 2025-09-17T00:00:00Z

- Orchestrator: mount the controller's Docker socket bind mount, wrap swarm-template control signals, and propagate the socket path/`DOCKER_HOST` so controllers can talk to whatever daemon the runtime exposes.
- Docker client: surface Docker daemon availability failures with a dedicated exception and allow callers to customise the `HostConfig` while keeping automatic control-network discovery, enabling bind mounts without losing networking defaults.
- SwarmController: redeclare queues when they go missing and honour `DOCKER_HOST` overrides from the environment or JVM properties to reuse the orchestrator's socket configuration.
- Control-plane services: project the AsyncAPI control payload schemas into runtime validation so generator, moderator, processor, postprocessor, and trigger services enforce the documented signal shape.
- UI (Buzz): add an error filter to the log stream to spotlight failing events quickly.
- Tests: cover Docker socket propagation, host bind wiring, AsyncAPI schema validation, and controller Docker host overrides.

## [0.9.6] - 2025-09-16
Timestamp: 2025-09-16T00:00:00Z

- Docs: align `ControlSignal` payload documentation with runtime shape, including `signal`/scope/`args`, and bump AsyncAPI spec to 0.5.0.

## [0.9.5] - 2025-09-15
Timestamp: 2025-09-15T10:01:39Z

- SwarmController: emit control signal confirmations, dedupe signals, sort lifecycle by dependency, request status on staleness, and record metrics.
- Orchestrator: publish swarm created/ready events, listen for control events, track controller health, and guard duplicate stops.
- UI: render swarm topology with React Flow, gate swarm start on readiness, and log API fetches.
- Docs: simplify component READMEs and document swarm lifecycle events and startup.
- Tests: verify status bindings, confirmation flow, and ready/error binding patterns.
- UI: migrate swarm actions to REST
- Orchestrator: retain REST argument names by compiling with `-parameters`

## [0.9.4] - 2025-09-11
Timestamp: 2025-09-11T23:42:19Z

- SwarmController: attach containers to control network, derive network automatically, and propagate swarm ID via shared Docker client.
- Orchestrator/UI: surface swarm creation errors with detailed events.
- ScenarioManager/UI: return JSON for scenario endpoints and load scenario details by ID in swarm modal.
- Tests: fix swarm-controller topology and RabbitMQ expectations.

## [0.9.3] - 2025-09-11
Timestamp: 2025-09-11T08:25:35Z

- Fix: use curl in log-aggregator healthcheck to ensure container starts.

## [0.9.2] - 2025-09-10
Timestamp: 2025-09-10T15:30:28Z

- Docs: remove Queen tab references, note only Queen runs initially and describe scenario-based swarm creation.
- Docs: document `GET /scenarios`, STOMP `sig.swarm-create`/`sig.swarm-start` endpoints and update architecture diagrams.

## [0.9.1] - 2025-09-09
Timestamp: 2025-09-09T23:22:21Z

- SwarmController: emit status after startup.
- SwarmControllerService: support multiple containers per role.
- Herald: add status controls, metrics, and handshake between queen and herald.
- SwarmController: manage container lifecycle with Docker and clean up resources on stop.
- Fix: keep controller enabled, provide ObjectMapper bean, and remove invalid binding builder call.
- Docs: outline scenario manager and swarm lifecycle.
- Tests: verify queue provisioning and RabbitTemplate messaging conversions.

## [0.9.0] - 2025-09-09
Timestamp: 2025-09-09T18:39:00Z

- SwarmController: introduce service to orchestrate swarm lifecycle and handle control signals.
- Swarm: UI supports creating swarms and incorporates swarm ID in bee names and metrics.
- Build: centralize topology constants in a shared module and copy shared modules into service images.
- Fix: resolve topology-core parent POM and update bee role aliases.
- Docs: expand guidance on control signals, orchestration roles, and multi-region queue adapters.

## [0.8.2] - 2025-09-08
Timestamp: 2025-09-08T10:27:41Z

- Traffic: scope exchanges and queues by swarm ID (`ph.<swarmId>.hive`, `ph.<swarmId>.gen`, `ph.<swarmId>.mod`).
- Orchestrator: remove swarm-specific queues after containers stop.
- Docs: note `PH_SWARM_ID` env for multi-swarm isolation.

## [0.8.1] - 2025-09-04
Timestamp: 2025-09-04T17:55:20Z

- Build: centralize module parent version via revision property and bump to 0.8.1.
- UI: let Hive, Buzz, and Nectar views fill the viewport with flexible layouts.

## [0.8.0] - 2025-09-04
Timestamp: 2025-09-04T17:17:36Z

- **BREAKING**: Remove all legacy UI files and directories (`UI-Legacy/`, `ui/assets/js/`)
- Clean up codebase by eliminating unused static JavaScript modules
- Update documentation to reflect React-only UI architecture
- Bump version to 0.8.0 to reflect major cleanup

## [0.7.1] - 2025-09-04
Timestamp: 2025-09-04T11:28:55Z

- UI: add start/stop toggle and manual status refresh for each component.
- UI: send config updates over role-specific control routes and show enabled state in drawers; disabled components appear gray in topology.
- Docs: relocate spec and control-plane rules under docs/ and update references and UI Dockerfile.

## [0.7.0] - 2025-09-04
Timestamp: 2025-09-04T01:41:55Z

- UI: topology nodes render with type-specific shapes and a legend, plus smaller queue labels and arrows for clarity.
- UI: topology view lays out components in a linear row, resizes with its container, and includes a reset button.

## [0.6.3] - 2025-09-03
Timestamp: 2025-09-03T22:57:37Z

- UI: render interactive topology view with draggable nodes, zoom/pan, and queue tooltips.
- UI: preserve node positions across STOMP topology updates.
- Services: map roles to bee aliases and generate role-based bee names without spaces.

## [0.6.2] - 2025-09-03
Timestamp: 2025-09-03T19:33:17Z

- UI: add STOMP client with shared connection, log streams, and configuration tab in Buzz.
- UI: build Hive page with live component list, detail drawer, and queue panel.

## [0.6.1] - 2025-09-03
Timestamp: 2025-09-03T12:18:00Z

- UI: archive the static site under `UI-Legacy` and scaffold a React 19 + Vite app in `/ui` with the Hive view and menu.

## [0.6.0] - 2025-09-02
Timestamp: 2025-09-02T22:51:35Z

- WireMock: enable cross-origin admin access
- WireMock: return 200 JSON for generator's default `/api/test` request
- UI: request WireMock admin using browser hostname and CORS headers

## [0.5.12] - 2025-09-02
Timestamp: 2025-09-02T20:54:45Z

- Grafana: remove the Loki Logs dashboard

## [0.5.11] - 2025-09-02
Timestamp: 2025-09-02T17:23:07Z

- Trigger: introduce service for scheduled external calls and include it in the Compose stack.
- UI: source version from the root VERSION file.

## [0.5.10] - 2025-09-02
Timestamp: 2025-09-02T16:50:02Z

- UI: enhance hive view with component details.

## [0.5.9] - 2025-09-02
Timestamp: 2025-09-02T14:44:30Z

- Log aggregator: parse raw AMQP messages, tolerate unknown fields, and ensure every Loki stream has a label (defaulting to `service="unknown"`).

## [0.5.8] - 2025-09-02
Timestamp: 2025-09-02T12:52:14Z

- Processor: forward moderated messages unchanged to the system under test and relay the response to the final queue.
- Processor: default base URL includes WireMock port 8080.
- UI: cap WireMock journal view to the 25 most recent requests.

## [0.5.7] - 2025-09-02
Timestamp: 2025-09-02T12:42:27Z

- Generator: support sub-1 TPS rates by accumulating fractional requests.
- UI: add numeric input for generator rate and stretch slider to panel width.

## [0.5.6] - 2025-09-01
Timestamp: 2025-09-01T23:45:28Z

- UI: add dropdown under WireMock icon to view request journal from `/__admin/requests`.
- Chore: remove unused ESLint directive in Buzz menu.
## [0.5.5] - 2025-09-01
Timestamp: 2025-09-01T23:35:26Z

- Generator: construct HTTP messages from configuration with Hello World defaults for path, method, headers, and body.
- Processor: expose configurable base URL (default `http://wiremock`) for downstream calls.
- UI: add dedicated sections for message template and base URL, requiring explicit confirmation for updates.

## [0.5.4] - 2025-09-01
Timestamp: 2025-09-01T22:21:33Z

- UI: ensure Buzz OUT panel logs broadcast and UI control messages by fetching the STOMP client on demand.
- Docs: expand README and requirements to describe Buzz IN/OUT logs and UI controls.

## [0.5.3] - 2025-09-01
Timestamp: 2025-09-01T22:06:01Z

- Docker: disable WireMock request journaling for lean performance tests.

## [0.5.2] - 2025-09-01
Timestamp: 2025-09-01T21:54:24Z

- Docker: upgrade WireMock image and relax volume permissions so the container starts without MissingResourceException.

## [0.5.1] - 2025-09-01
Timestamp: 2025-09-01T21:36:58Z

- UI: add top-bar icon links to RabbitMQ, Prometheus, Grafana, and WireMock, pre-filled with default credentials where applicable.

## [0.5.0] - 2025-09-01
Timestamp: 2025-09-01T21:21:35Z

- Observability: integrate Loki and Promtail logging stack with a dedicated log-aggregator service, RabbitMQ logback appender, and Grafana dashboards; services wait for the log-aggregator and route AMQP logs to console.
- UI: refactor Hive panels into modular components with shared controls and a common status renderer; panels auto-request and render initial status, display generator rate and queue bindings/config, open controls in modals, toggle visibility, and provide tabbed system logs.
- Control: announce inbound queues and publishing topics, use explicit control topics and dedicated per-service control queues; actions request status and status blocks expand with queue topology.
- Processor: declare final queue binding and forward moderated messages to the postprocessor.
- TPS: measure throughput using elapsed intervals across Generator, Moderator, and Processor.
- Docker: add Wiremock service for NFT tests with journaling disabled.

## [0.4.20] - 2025-08-30
Timestamp: 2025-08-30T23:39:40Z

- UI: Reduce monolith icon depth to a quarter of its width for accurate proportions.

## [0.4.19] - 2025-08-30
Timestamp: 2025-08-30T23:33:46Z

- UI: Widen monolith status icon's front face for clearer orientation.

## [0.4.18] - 2025-08-30
Timestamp: 2025-08-30T23:16:22Z

- UI: Correct isometric angle of monolith status icon.

## [0.4.17] - 2025-08-30
Timestamp: 2025-08-30T23:11:47Z

- UI: Remove ground from monolith status icon and add subtle pulsing glow with hover effect.

## [0.4.16] - 2025-08-30
Timestamp: 2025-08-30T22:58:51Z

- UI: Narrow the monolith status icon and anchor it to the ground.

## [0.4.15] - 2025-08-30
Timestamp: 2025-08-30T22:50:15Z

- UI: Swap Update Status button for Space Odyssey monolith icon with hover glow.

## [0.4.14] - 2025-08-30
Timestamp: 2025-08-30T22:25:29Z

- UI: Allow separate line limits for Control and System Logs with 10–500 range.

## [0.4.13] - 2025-08-30
Timestamp: 2025-08-30T22:20:47Z

- UI: Align TPS dropdown buttons with header menu style.

## [0.4.12] - 2025-08-30
Timestamp: 2025-08-30T22:12:26Z

- UI: Style TPS metric dropdown and options with shared menu dropdown theme.

## [0.4.11] - 2025-08-30
Timestamp: 2025-08-30T22:02:56Z

- UI: Match TPS dropdown menu styling to header Menu theme without bold text.

## [0.4.10] - 2025-08-30
Timestamp: 2025-08-30T21:48:20Z

- UI: Raise chart metric dropdown above graphs so it remains clickable.

## [0.4.9] - 2025-08-30
Timestamp: 2025-08-30T21:42:34Z

- UI: Replace metric selection with menu-style dropdown using menu button theme.

## [0.4.8] - 2025-08-30
Timestamp: 2025-08-30T21:32:51Z

- UI: Style chart metric dropdown to match the menu dropdown.

## [0.4.7] - 2025-08-30
Timestamp: 2025-08-30T21:14:45Z

- UI: Align log and system log line selectors in their header rows.
- UI: Theme chart metric selector to match the menu button.

## [0.4.6] - 2025-08-30
Timestamp: 2025-08-30T21:04:56Z

- UI: Add themed line limit controls for control and system logs (default 600 lines).
- UI: Style metric selection dropdown to match cyan button theme.

## [0.4.5] - 2025-08-30
Timestamp: 2025-08-30T20:55:01Z

- UI: Restore menu to original header position and remove logo hover spin.

## [0.4.4] - 2025-08-30
Timestamp: 2025-08-30T20:43:37Z

- UI: Remove animated network background, leaving a static dark backdrop.

## [0.4.3] - 2025-08-30
Timestamp: 2025-08-30T17:06:56Z

- UI: Move Menu beneath the spacestation logo and spin the logo when hovered.

## [0.4.2] - 2025-08-30
Timestamp: 2025-08-30T16:58:33Z

- UI: Replace heavy canvas network background with lightweight SVG animation to reduce GPU usage.

## [0.4.1] - 2025-08-29
Timestamp: 2025-08-29T14:39:23Z

- Auth: Default all components to RabbitMQ `guest/guest`; remove custom user creation script (`rabbitmq/add-user.sh`) and related Compose envs.
- Compose: Stop setting `RABBITMQ_DEFAULT_USER/PASS`; services rely on defaults and `RABBITMQ_HOST` only.
- UI: Defaults and placeholders use `guest/guest` (config.json, app.js, generator page, legacy controls).
- Control-plane: Switch routing key kinds to kebab-case — `status-delta`, `status-full` (generator, moderator, processor, docs, spec, schema).
- Docs: README, control-plane rules, AsyncAPI, and JSON schema updated to kebab-case and guest defaults.
- RabbitMQ image: Do not copy `add-user.sh`. Note: `definitions.json` remains minimal; if it causes issues, consider removing it from the image.

## [0.4.0] - 2025-08-29
Timestamp: 2025-08-29T11:05:00Z

- UI: Added top-level tabs (Control, Swarm); Control hosts existing charts/logs.
- UI: New Swarm View — auto-discovers components from Control messages, draws nodes and links (Generator → Moderator → Processor → SUT), with hold time and Clear & Restart.
- UI: Moved background selector into Menu; background options accessible via HAL-style icon near WS health.
- UI: WS health icon opens connection menu; connection fields collapsed into dropdown.
- UI: README available in app; fixed image paths and transparency; served docs/ and /ui alias in Nginx.
- Docs: Simplified flow diagram with SUT and explicit data directions.

## [0.3.1] - 2025-08-29
Timestamp: 2025-08-29T10:50:00Z

- UI: README available in Menu (`/readme.html`) with in‑app markdown render.
- UI: Fix README images (logo + flow) — copy `docs/` to image, path rewriting, and Nginx alias for `/ui/*`.
- UI: Remove white backgrounds under images; transparent flow diagram background for dark UI.
- UI: Background selector moved under Menu; add HAL‑style background options icon next to WS health.
- UI: WS health icon opens connection menu.
- Docs: Simplified flow diagram (components, queues, direction, SUT) and README tweaks.

## [0.3.0] - 2025-08-29
Timestamp: 2025-08-29T10:24:00Z

- UI: New "Network" background and 3‑mode selector (Bees / Network / Old).
- UI: Auto‑apply controls for Network; performance pause for inactive backgrounds.
- UI: HAL status eyes with center‑pulse animations and colorblind‑friendly contrast.
- UI: Unified typography; improved dark dropdown styling and focus visibility.
- UI: Removed duplicate top bars; simplified background UI logic.
- UI: Nginx WS resolver fix so UI starts without RabbitMQ present.
- Docs: Updated README with new visuals and notes.

## [0.2.3] - 2025-08-29
Timestamp: 2025-08-29T02:20:00Z

- UI: Refactor Events connection to use StompJS (same as Generator) instead of a custom STOMP client.
- UI: Improve diagnostics around connect/handshake and socket lifecycle; clearer syslog entries.
- UI: Normalize CRLF handling in STOMP frame parsing (pre-refactor) to avoid missed CONNECTED frames.
- Docs: Update README with authentication notes (RabbitMQ `guest` remote restriction), recommended non‑guest user setup, and troubleshooting.

## [0.2.2] - 2025-08-29
Timestamp: 2025-08-29T01:24:02Z

- Docs: Add AsyncAPI spec (`docs/spec/asyncapi.yaml`) describing control/traffic contracts.
- UI: Add API Docs page (`/docs.html`) and group header actions into a ☰ Menu dropdown.
- UI: Fix changelog page showing duplicated header (remove extra static H1).
- Config: Align exchanges across UI/backends — `ph.<swarmId>.hive` (traffic), `ph.control` (control).

## [0.2.1] - 2025-08-29
Timestamp: 2025-08-29T01:03:45Z

- UI: Add browser generator integration under `/generator/` and visible UI/WS health icons.
- UI: Share connection settings (URL/login/pass/vhost) with generator via localStorage.
- UI: Add changelog viewer (`/changelog.html`) and expose current `VERSION` and `CHANGELOG.md` via Nginx.
- UI: Load global config from `/config.json` (STOMP path/vhost, subscriptions) for ready‑to‑run defaults.
- Backend: Make topology names overridable via `PH_*` env; fix `@RabbitListener` to use property placeholders (compilation error resolved).
- Compose: Start UI immediately; other services wait for RabbitMQ health.
- Generator: Add UUID v4 polyfill for wider browser compatibility.

## [0.2.0] - 2025-08-29
Timestamp: 2025-08-29T00:18:36Z

- UI: Add Nginx reverse proxy for WebSocket at `/ws` to RabbitMQ Web‑STOMP (`rabbitmq:15674/ws`).
- UI: Default WebSocket URL to same‑origin (`ws(s)://<host>/ws`) to avoid CORS/origin issues.
- UI: Add System Logs panel to capture system events and user actions (connect/disconnect, field edits, WS lifecycle).
- UI: Periodic health ping to `/healthz` with status transitions logged.
- UI: Add `/healthz` endpoint in Nginx and Docker healthcheck for `ui` service.
- Docs: Update README with stack, quick start, proxy, healthchecks, troubleshooting, and development notes.

## [0.1.1] - 2025-08-29
Timestamp: 2025-08-28T23:51:09Z

- Initial UI wiring and assets.

## [0.1.0] - 2025-08-29
Timestamp: 2025-08-28T23:07:54Z

- Initial repository setup.
# Changelog
