# Changelog

All notable changes to this project will be documented in this file.

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

