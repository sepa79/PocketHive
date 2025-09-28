# PocketHive End-to-End Test Harness

The `e2e-tests` module hosts the automated acceptance scenarios that exercise a deployed PocketHive stack. This
skeleton provides the build wiring, package layout, and documentation so subsequent tasks can focus on filling in the
behavioural coverage agreed in the phase roadmap.

## Project layout

```
src/
  main/java/io/pockethive/e2e/
    config/              # Environment & credential handling
    clients/             # HTTP, messaging, and websocket clients
    support/             # Shared assertions, polling helpers, and fixtures
  test/java/io/pockethive/e2e/
    CucumberE2ETest.java # JUnit Platform entry-point for Cucumber
    hooks/               # Before/After hooks for environment lifecycle management
    steps/               # Step definitions used by the feature files
  test/resources/features/
    deployment-smoke.feature # Phase 1 deployment smoke checks
    harness-skeleton.feature # Placeholder scenario tagged as @wip so it does not execute
```

## Execution

Run the suite once the PocketHive stack is deployed and the required environment variables are available. Helper
scripts are available at the repository root for Unix-like and Windows environments:

```bash
./start-e2e-tests.sh          # macOS/Linux
start-e2e-tests.bat           # Windows
```

Both wrappers accept additional Maven arguments, which are forwarded to the underlying `./mvnw verify -pl e2e-tests -am`
command. When invoked without extra configuration, the scripts seed the environment with defaults that mirror the
service container configuration (e.g. `http://localhost:8088/orchestrator`, `http://localhost:8088/scenario-manager`,
`rabbitmq:5672` with the `guest/guest` account, and `ws://localhost:8088/ws`). Override any of these values by exporting
the environment variables before launching the helper.

The deployment smoke feature runs automatically once the required environment variables are present; otherwise the
scenario is skipped via JUnit assumptions so local builds without a running stack still succeed. Remove the `@wip` tag
on the placeholder feature as new steps are implemented in later tasks.

## Environment configuration

Environment variables will be referenced by the harness once the step implementations arrive:

| Variable | Purpose |
| --- | --- |
| `ORCHESTRATOR_BASE_URL` | Base URL (e.g. `http://localhost:8080`) for orchestrator REST calls. |
| `SCENARIO_MANAGER_BASE_URL` | Base URL for querying available templates via the Scenario Manager. |
| `RABBITMQ_HOST` | RabbitMQ hostname to probe (defaults to `rabbitmq`). |
| `RABBITMQ_PORT` | RabbitMQ port (defaults to `5672`). |
| `RABBITMQ_DEFAULT_USER` | Username used for AMQP connectivity checks (defaults to `guest`). |
| `RABBITMQ_DEFAULT_PASS` | Password paired with `RABBITMQ_DEFAULT_USER` (defaults to `guest`). |
| `RABBITMQ_VHOST` | RabbitMQ virtual host (defaults to `/`). |
| `UI_WEBSOCKET_URI` | WebSocket endpoint exposed by the nginx proxy for UI-equivalent subscriptions. |
| `UI_BASE_URL` | Base HTTP URL for the nginx UI proxy. When omitted the harness derives it from `UI_WEBSOCKET_URI`. |
| `SWARM_ID` | Default swarm identifier used by shared steps (override per scenario when required). |
| `IDEMPOTENCY_KEY_PREFIX` | Prefix applied to generated idempotency keys to simplify log correlation. |

The harness consumes the same RabbitMQ environment variables as the orchestrator's Spring Boot configuration. Configure
them once (for example in your shell profile or deployment manifest) and both the service and the smoke checks will
point at the same broker.

## Phase roadmap reference

The harness evolves through the following phases (detailed in `docs/ai/e2e-test-suite-tasks.md`):

1. **Phase 0 – Test Harness Foundations**: module skeleton, environment plumbing, lightweight clients (this task).
2. **Phase 1 – Deployment Readiness & Smoke Coverage**: health checks for orchestrator, scenario catalogue, RabbitMQ, and UI.
3. **Phase 2 – Swarm Lifecycle Golden Path**: create → template → start → stop → remove with control-plane confirmations.
4. **Phase 3 – Controller & Component Configuration Toggles**: scoped config updates and aggregate status assertions.
5. **Phase 4 – Data Plane Flow & Observability Signals**: workload traversal through queues, status cadence checks.
6. **Phase 5 – Resilience, Error Handling & Idempotency**: retries, invalid requests, and component failure handling.
7. **Phase 6 – Reporting, CI Integration & Maintenance**: reporting outputs and automation hooks for CI/CD.

Each subsequent task will extend the clients, hooks, and support utilities while keeping documentation and configuration in
sync with the deployed platform.
