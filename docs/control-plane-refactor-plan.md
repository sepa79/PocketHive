# Control Plane Refactor Plan

## Goal
Deliver a common control-plane module that exposes canonical routing, queue topology, and payload helpers so that worker and manager components consume the control plane exclusively through typed APIs—no ad-hoc strings or bespoke wiring.

## Desired Outcomes
- Components delegate all control-plane configuration and messaging to the shared module ("common module"), relying on callbacks for business logic only.
- Queue names, bindings, routing keys, and payload envelopes are generated exclusively by shared helpers.
- New worker or manager implementations can be scaffolded rapidly by composing the shared control-plane logic with minimal custom code.

## Current Gaps
- `control-plane-core` stops at routing-key helpers and inbound consumer wrappers; it does not define queue descriptors, binding sets, or outbound payload builders.
- Each service declares control queues and bindings with hard-coded strings inside `RabbitConfig` classes, scattering the contract across the codebase.
- Workers and managers manually assemble confirmation/status payloads and maintain bespoke lists of expected routes, causing duplicated logic and drift risk.

## Refactor Strategy
1. **Expand `control-plane-core` capabilities.**
   - Introduce immutable topology descriptors for component roles that derive queue names, required bindings (config updates, lifecycle signals, status fan-outs), and canonical route catalogs.
   - Provide typed payload builders (ready/error confirmations, status snapshots/deltas) that wrap `StatusEnvelopeBuilder` and embed shared metadata automatically.
   - Add comprehensive unit tests that lock in queue names, routing keys, and payload schemas to protect backward compatibility.

2. **Create outbound control-plane DSLs.**
   - Expose publishers (e.g., `ControlPlaneEmitter`) that accept role-specific context objects and produce typed messages, replacing ad-hoc JSON assembly.
   - Offer convenience APIs for emitting confirmation/status events so component code only supplies business data and callbacks.

3. **Deliver Spring integration surfaces.**
   - Supply factories that convert topology descriptors into Spring `Declarable` objects for queues/bindings to eliminate raw strings in configuration classes.
   - Optionally provide Spring Boot starters for workers/managers that auto-register listeners, publishers, and duplicate guards based on simple properties, reducing boilerplate for new services.

4. **Migrate existing components incrementally.**
   - Update each worker (`processor`, `generator`, `trigger`, `moderator`, `postprocessor`) to consume the shared topology API and emit control-plane traffic through the new DSLs.
   - Refactor manager services (swarm controller, orchestrator, scenario manager) to replace bespoke control logic with shared emitters and descriptors.
   - Remove local helper methods for queue naming, route lists, and payload assembly once replacements are in place.

5. **Documentation and enablement.**
   - Produce developer guides showing how to bootstrap workers/managers with the new APIs, including examples of supplying configuration update handlers and status snapshot callbacks.
   - Optionally bundle a PocketHive Worker SDK (starter + testing fixtures) to support external teams creating bespoke bees.

## Viability Assessment
The refactor is technically feasible but high-effort:
- Workers already depend on `WorkerControlPlane`, so expanding the shared module gives a clear migration path; managers will require more restructuring because they entwine control logic with orchestration and lifecycle concerns.
- The largest risks are (a) breaking the existing AMQP contract if helpers diverge from today’s routing conventions, (b) coordinating broad cross-service changes without strong integration tests, and (c) over-coupling Spring configuration to new abstractions, limiting flexibility for advanced control flows.

## Risk Mitigation
- Build exhaustive unit and integration tests in `control-plane-core` to confirm queue/binding topology and payload shapes match current behaviour.
- Roll out helpers service-by-service, verifying each migration with smoke-level AMQP integration tests before proceeding to the next component.
- Keep the new abstractions modular so teams can opt into DSL factories without losing the ability to customise edge-case workflows.

## Next Steps Checklist
- [ ] Design topology descriptor interfaces and enumerate required routes/bindings per role.
- [ ] Implement payload builders and publishers with contract tests.
- [ ] Provide Spring-friendly factories/starter modules.
- [ ] Migrate one worker service as a pilot and document the process.
- [ ] Roll out to remaining workers, then managers, retiring bespoke helpers.
- [ ] Publish developer documentation and optional SDK tooling.
