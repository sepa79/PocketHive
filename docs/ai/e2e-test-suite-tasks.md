# PocketHive E2E Test Suite Tasks

This backlog tracks the work needed to deliver the end-to-end acceptance suite that runs against a deployed PocketHive stack. The tasks mirror the phase-based plan agreed with stakeholders so progress can continue iteratively as the product evolves.

## Task 1 – Harness Skeleton & Phase Plan Foundation
**Goal**
- Provide a compilable `e2e-tests` Maven module skeleton together with documentation that embeds the agreed phase roadmap.

**Scope**
- Add the `e2e-tests` Maven module with a minimal `pom.xml`, empty package structure (`config`, `clients`, `support`, `steps`, `hooks`), and sample Cucumber feature placeholder.
- Introduce a module-level README (or `docs/` entry) summarising phase objectives, environment configuration variables, and execution commands.
- Ensure the module is wired into the root build (`pom.xml` modules list) without affecting existing services.

**Constraints**
- No real HTTP/AMQP calls yet; all classes remain stubs.
- Keep dependencies aligned with approved stack (Java 21, JUnit 5, Cucumber, Spring HTTP/AMQP).

**Acceptance Criteria**
- `./mvnw -pl e2e-tests -am verify` completes with the skeleton in place, running zero scenarios.
- Phase roadmap is captured alongside the module so future contributors can continue with subsequent tasks.

**Deliverables**
- Committed module skeleton and documentation capturing the phase plan.

**Context**
- Unlocks later tasks by establishing the build, package naming, and documentation conventions.

## Task 2 – Phase 1 Smoke & Deployment Readiness
**Goal**
- Author fast-failing smoke checks that validate the deployed stack is reachable before deeper suites run.

**Scope**
- Implement health endpoint probes for Orchestrator, Scenario Manager, RabbitMQ, and UI proxy.
- Capture baseline swarm state immediately after deployment, asserting the default idle configuration.
- Reuse shared environment config from Task 1.

**Constraints**
- Tests must complete quickly and leave the deployment untouched.

**Acceptance Criteria**
- Smoke feature passes against a freshly deployed stack; failures are actionable with clear diagnostics.

**Deliverables**
- Cucumber feature(s) and supporting step definitions within the skeleton module.

**Context**
- Establishes gating checks for subsequent scenarios.

## Task 3 – Phase 2 Swarm Lifecycle Golden Path
**Goal**
- Cover the create → template → start → stop → remove lifecycle using REST calls and RabbitMQ confirmations.

**Scope**
- Implement REST clients for Orchestrator and Scenario Manager operations.
- Subscribe to confirmation topics (`ev.ready.*`, `ev.error.*`) and aggregate status streams required for lifecycle assertions.
- Provide reusable polling utilities to await expected states.

**Constraints**
- Focus on a single canonical swarm instance and template; additional variants deferred.

**Acceptance Criteria**
- Scenario validates correct ordering and single confirmation per signal, including data-plane sanity check via queue traversal.

**Deliverables**
- Lifecycle feature(s), clients, and assertion helpers wired into the harness.

**Context**
- Demonstrates the happy path for operators and underpins later resilience tests.

## Task 4 – Phase 3 Controller & Component Configuration Toggles
**Goal**
- Exercise controller-scope and component-level configuration updates, verifying scope semantics and emitted confirmations.

**Scope**
- Extend messaging helpers to publish `sig.config-update.*` signals with varying scopes.
- Assert controller aggregates (`state.workloads.enabled`, `state.controller.enabled`) and component status deltas reflect the changes.
- Parameterise scenarios to adapt to evolving component inventories.

**Constraints**
- Maintain idempotency: repeated signals with the same `idempotencyKey` should replay confirmations rather than reapply changes.

**Acceptance Criteria**
- Tests cover both `scope=swarm` and `scope=controller` toggles and at least one bee role configuration update.

**Deliverables**
- Configuration toggle features and reusable verification utilities.

**Context**
- Validates control-plane semantics critical for pause/resume workflows.

## Task 5 – Phase 4 Data Plane Flow & Observability
**Goal**
- Validate message traversal through the swarm queues and ensure observability streams remain fresh.

**Scope**
- Produce workload messages (e.g., publish into `ph.<swarmId>.gen`) and assert they pass through `gen → mod → final` queues with expected transformations.
- Monitor `ev.status-{full|delta}` streams for controllers and bees, enforcing freshness guarantees and heartbeat cadence.

**Constraints**
- Keep assertions tolerant to expected asynchronous timing variance using Awaitility or equivalent utilities from prior tasks.

**Acceptance Criteria**
- Scenarios confirm data-plane delivery and observability updates within acceptable time windows.

**Deliverables**
- Data-plane feature(s) plus supporting message producers and observers.

**Context**
- Ensures the suite covers both workload flow and monitoring obligations.

## Task 6 – Phase 5 Resilience, Error Handling & Idempotency
**Goal**
- Model retries, replay tolerance, and failure scenarios to ensure robust operator experience.

**Scope**
- Retry lifecycle actions with stable `idempotencyKey` and fresh `correlationId` to confirm each attempt produces its own
  confirmation without crashing downstream services.
- Trigger invalid operations (e.g., non-existent template) to surface `ev.error.*` events and verify state preservation.
- Simulate component failures (container stop or Actuator outage) and assert recovery handling.

**Constraints**
- Tests should clean up any mutated state to keep the environment reusable.

**Acceptance Criteria**
- Failure scenarios produce the documented error events; retries remain safe (commands are idempotent) even though confirmations
  are re-emitted for every attempt.

**Deliverables**
- Resilience-focused features, utilities for fault injection, and documentation on required environment hooks.

**Context**
- Completes coverage of negative paths and operational safeguards.

## Task 7 – Phase 6 Reporting, CI Integration & Maintenance
**Goal**
- Integrate the suite into CI/CD, ensuring reporting and contributor guidance are available.

**Scope**
- Configure reporting outputs (Cucumber JSON/HTML, Allure if approved).
- Document environment variables, execution commands, and troubleshooting steps.
- Add CI target (e.g., makefile or GitHub Actions job) to run `./mvnw verify -pl e2e-tests` post-deployment.

**Constraints**
- Align with existing project CI requirements and avoid introducing unapproved dependencies.

**Acceptance Criteria**
- Reports generated and archived; CI pipeline step executes suite against a provisioned environment or stubbed harness.

**Deliverables**
- Reporting configuration, documentation updates, and CI integration scripts.

**Context**
- Ensures long-term maintainability and discoverability of the e2e suite.
