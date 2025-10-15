# Collapse manager swarm override into global setting

## Goal
Eliminate the redundant `pockethive.control-plane.manager.swarm-id` property and rely on a single `pockethive.control-plane.swarm-id` key across the manager services.

## Scope
- `orchestrator-service`
- `scenario-manager-service`
- `swarm-controller-service`
- `log-aggregator-service`
- Shared documentation and configuration snippets that mention the manager-scoped property

## Constraints
- Preserve current runtime defaults sourced from `PH_SWARM_ID` / `Topology.SWARM_ID` so deployments that only set the environment variable continue to work.
- Keep logback MDC population intact so swarm identifiers still appear in logs without requiring extra configuration.
- Avoid introducing breaking changes for the worker services; only the manager family should be touched.

## Acceptance Criteria
- No production configuration (code or YAML) references `pockethive.control-plane.manager.swarm-id`.
- Manager bootstrap code resolves the swarm ID directly from the consolidated property (and still honors the environment variable fallback).
- Logging configuration continues to render the correct swarm identifier using the single property.
- Tests and documentation (if any) reflect the new property name.

## Deliverables
1. Updated Java initializers for orchestrator and scenario-manager services that read `pockethive.control-plane.swarm-id` (with environment fallback) and remove any reinjection into the manager namespace.
2. Simplified `logback-spring.xml` files for orchestrator, scenario-manager, swarm-controller, and log-aggregator services that reference only the consolidated property.
3. Audit notes or updated docs clarifying that the manager override key no longer exists (e.g., changelog entry or configuration guide update).

## Task Breakdown
1. **Refactor initializer utilities**
   - Update `OrchestratorControlPlaneInitializer` and `ScenarioManagerControlPlaneInitializer` to fetch the swarm ID solely from `pockethive.control-plane.swarm-id`, retaining the `Topology.SWARM_ID` fallback path.
   - Remove constants, helper methods, and property writes that target the manager-specific namespace.
2. **Align logging configuration**
   - Edit each manager service `logback-spring.xml` so the `springProperty` for `swarmIdentifier` reads the global key directly and delete the redundant `globalSwarmIdentifier` definition.
   - Verify MDC population still references the correct property names.
3. **Config & documentation sweep**
   - Search for `pockethive.control-plane.manager.swarm-id` across the repo and update any remaining references (YAML samples, docs, scripts).
   - Document the change in the appropriate changelog or configuration guide so operators know the manager override has been removed.

## Context
The manager services currently duplicate swarm configuration by defining both a global control-plane swarm ID and an optional manager-specific override. The override is never used in practice, but the scaffolding introduces extra property resolution logic in both the Java initializers and Logback setup. Collapsing the configuration to a single property simplifies the startup path and reduces confusion while maintaining existing defaults driven by `PH_SWARM_ID`.
