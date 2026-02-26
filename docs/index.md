# PocketHive Documentation Index

This page groups the main docs so humans and tools have a single
starting point. All paths are relative to the repo root and are served
by the UI under `/docs/...` as part of the runtime image.

## Operator / Running PocketHive

- `docs/USAGE.md` – how to run the stack, use the UI, create/start
  swarms, and interpret buffer guards.
- `docs/observability.md` – metrics, logs, dashboards.
- `docs/GHCR_SETUP.md` – using published images from GitHub Container
  Registry.

## Scenario Authoring

- `docs/scenarios/README.md` – overview of scenarios.
- `docs/scenarios/SCENARIO_CONTRACT.md` – canonical scenario YAML
  contract and mapping to `SwarmTemplate` / `SwarmPlan`.
- `docs/scenarios/SCENARIO_PATTERNS.md` – real e2e scenarios and
  patterns (REST, Redis dataset, guards, request-builder/http-sequence).
- `docs/guides/README.md` – practical guides for day-to-day use.
- `docs/guides/workers-basics.md` – worker roles and minimal flow setup.
- `docs/guides/workers-advanced.md` – control-plane operations and
  multi-swarm patterns.
- `docs/guides/templating-basics.md` – Pebble + `eval(...)` baseline.
- `docs/guides/templating-advanced.md` – sequences, advanced patterns,
  and validation workflow.
- `docs/scenarios/SCENARIO_TEMPLATING.md` – compatibility entry pointing
  to the guides.

## Worker SDK & IO

- `docs/sdk/worker-sdk-quickstart.md` – single worker contract, WorkItem
  basics, how to write a worker.
- `docs/sdk/worker-interceptors.md` – interceptor pipeline (metrics,
  observability, templating hooks).
- `docs/toBeReviewed/worker-configurable-io-plan.md` (Status: to be reviewed) –
  config-driven IO types and future IO factory work.
- `docs/todo/config-key-normalisation-plan.md` (Status: future / design) –
  key naming conventions backlog.

## Control Plane, Manager, Orchestrator

- `docs/ARCHITECTURE.md` – end-to-end architecture, roles, and
  contracts.
- `docs/ORCHESTRATOR-REST.md` – Orchestrator API and control-plane
  signals.
- `docs/archive/manager-sdk-plan.md`
  (Status: implemented / archived) – historical implementation plan for
  Manager SDK and Swarm Controller extraction.
- `docs/archive/worker-config-propagation-plan.md`
  (Status: implemented / archived) – historical implementation plan for
  worker config flow from scenario to control-plane.
- `docs/archive/queue-guard-plan.md` (Status: implemented / archived) –
  historical BufferGuard/queue guard implementation plan.
- `docs/toBeReviewed/sut-environments-plan.md` (Status: to be reviewed) –
  SUT environments and swarm binding plan pending final review.
- `docs/toBeReviewed/ScenarioPlan.md` (Status: to be reviewed) –
  scenario engine architecture plan pending review against SUT plan
  changes from branch work.
- `docs/archive/scenario-bundle-runtime-plan.md`
  (Status: implemented / archived) – directory-based scenario bundles and
  per-swarm runtimes (historical implementation plan).
- `docs/toBeReviewed/scenario-sut-editor-plan.md` (Status: to be reviewed) –
  SUT/scenario editor plan pending review against SUT environment plan.
- `docs/toBeReviewed/ui-v2-control-plane-adoption.md` (Status: to be reviewed) –
  UI-v2 control-plane adoption plan with review findings pending alignment.

## Specs & Tools

- `docs/spec/asyncapi.yaml` – messaging contract (AsyncAPI).
- `docs/docs.html` – simple HTML viewer for the AsyncAPI spec.
- `tools/scenario-templating-check` – CLI for validating generator and
  request templates used in scenarios.
- `tools/mcp-orchestrator-debug` – MCP client for driving Orchestrator
  from tools/IDEs.
