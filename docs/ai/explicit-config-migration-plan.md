# Explicit Configuration Migration Plan

## Goal
Eliminate all implicit configuration fallbacks across PocketHive by moving orchestrator, swarm-controller, and worker SDK components to Spring Boot configuration that derives exclusively from `application.yml` (overridable through standard Spring mechanisms). Once all consumers rely on the new configuration layer, remove the legacy control-plane topology helpers.

## Principles
- **Single source of truth:** Every configurable value must come from bound Spring properties; no lookups from `System.getenv`, `System.getProperty`, or `Topology`.
- **Fail fast:** Missing configuration halts application startup with clear error messages.
- **Incremental rollout:** Migrate one subsystem at a time while the legacy topology remains in place, then delete it after the final migration.
- **Tests/documentation updated:** Each phase updates tests and docs to reflect the new configuration contract.

## Phase 1 – Orchestrator
1. **Introduce orchestrator property bindings**
   - Create dedicated `@ConfigurationProperties` for control-plane identifiers, RabbitMQ/logging endpoints, Pushgateway, and Docker settings.
   - Replace references to `Topology`, `ControlPlaneProperties` fallbacks, and direct env/system lookups with injected properties.
2. **Remove initializer fallbacks**
   - Delete `OrchestratorControlPlaneInitializer` and any bee-name or system-property fallbacks for swarm/instance IDs.
   - Add validation (e.g., `@Validated` properties, startup checks) that fails if identifiers are missing.
3. **Refactor lifecycle & clients**
   - Update `ContainerLifecycleManager`, `ScenarioManagerClient`, and any other component that currently reads env/system properties so they rely solely on the new property beans.
   - Ensure queue/exchange names are sourced from configuration instead of `Topology`.
4. **Normalize configuration files**
   - Remove `${ENV:default}` expressions from `application.yml` and `logback-spring.xml`, replacing them with literal defaults documented within the files.
5. **Update tests/documentation**
   - Adjust unit/integration tests to provide required properties explicitly via `@TestPropertySource` or property overrides.
   - Document the orchestrator configuration contract under `docs/` as needed.

## Phase 2 – Swarm Controller
1. **Create swarm-controller properties**
   - Bind swarm ID, queue/exchange names, container templates, Rabbit/logging endpoints, metrics, and Docker host/socket into new `@ConfigurationProperties` classes.
   - Inject these properties into `SwarmLifecycleManager` and related infrastructure instead of using `Topology` or env/system lookups.
2. **Enforce explicit identifiers**
   - Require container instance IDs and swarm identifiers to be provided via configuration (i.e. from Swarm-manager via env variables matching Spring properties from application.yml) or orchestrator/swarm-manager payloads. Only Orchestrator and SwarmManager might generate configration and pass them over, workers use values found via application.yml/spring.
3. **Rework metrics/logging configuration**
   - Drive Pushgateway and logging configuration through the new properties while keeping `application.yml` entries as explicit `${ENV}` bindings with no fallbacks; Orchestrator supplies these values via container environment variables when it launches the controller, so do not introduce literal defaults in YAML/logback configs.
4. **Adjust Docker integration**
   - Replace `resolveDockerHostOverride()` and similar helpers with property-based configuration that fails on missing values.
5. **Refresh tests/docs**
   - Update swarm-controller tests to inject explicit properties and assert startup failure when required settings are absent.
   - Document the new property set for operators.

## Phase 3 – Worker SDK & Bees
1. **Define worker configuration contract**
   - Introduce `WorkerControlPlaneProperties` (or equivalent) that supplies swarm ID, control exchange, queue names, and metrics/logging endpoints.
   - Update SDK auto-configuration to depend on these properties rather than `Topology` or static defaults.
2. **Tighten runtime context**
   - Modify `DefaultWorkerContextFactory` and related components to require configured identifiers or explicit message headers; remove any default value substitutions.
3. **Update worker services**
   - For each bee (`generator`, `moderator`, `processor`, `postprocessor`, `trigger`), ensure `application.yml` and logging configs continue to read the required values from `${ENV}` placeholders (with no literal defaults), because SwarmController injects these environment variables when spawning worker containers.
   - Ensure `@RabbitListener` and outbound publisher configuration uses the bound properties only.
4. **Revise tests/examples**
   - Adjust SDK tests, worker service tests, and documentation/examples to provide the necessary properties explicitly.

## Phase 4 – Retire Legacy Topology
1. **Remove `Topology` and related helpers**
   - After all components consume the new properties, delete `Topology`, `TopologyDefaults`, `BeeNameGenerator`, and any fallbacks in `ControlPlaneProperties`.
   - Update common modules to reference the new property-based descriptors exclusively.
2. **Final cleanup**
   - Run integration tests to confirm cross-service compatibility.
   - Update architecture/control-plane documentation to describe the new configuration model and remove references to the legacy topology.

## Completion Criteria
- All services start only when required properties are supplied (or defaulted in `application.yml`) and fail fast otherwise.
- No code paths read configuration from environment or system properties directly.
- No classes reference the legacy `Topology` or bee-name generation utilities.
- Documentation and tests reflect the new configuration-first approach.
