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
  patterns (REST, Redis dataset, guards, HTTP Builder).
- `docs/scenarios/SCENARIO_TEMPLATING.md` – Pebble + SpEL helpers and
  the scenario templating CLI.

## Worker SDK & IO

- `docs/sdk/worker-sdk-quickstart.md` – single worker contract, WorkItem
  basics, how to write a worker.
- `docs/sdk/worker-interceptors.md` – interceptor pipeline (metrics,
  observability, templating hooks).
- `docs/sdk/worker-configurable-io-plan.md` (Status: in progress) –
  config-driven IO types and future IO factory work.
- `docs/sdk/config-key-normalisation-plan.md` (Status: in progress) –
  key naming conventions.

## Control Plane, Manager, Orchestrator

- `docs/ARCHITECTURE.md` – end-to-end architecture, roles, and
  contracts.
- `docs/ORCHESTRATOR-REST.md` – Orchestrator API and control-plane
  signals.
- `docs/architecture/manager-sdk-plan.md` (Status: in progress) –
  Manager SDK and Swarm Controller extraction.
- `docs/control-plane/worker-config-propagation-plan.md`
  (Status: in progress) – how worker config flows from scenario to
  workers via control-plane.
- `docs/control-plane/queue-guard-plan.md` (Status: in progress) –
  BufferGuard/queue guard details.
- `docs/architecture/sut-environments-plan.md` (Status: in progress) –
  System Under Test (SUT) environments and how swarms bind to them.
- `docs/architecture/scenario-sut-editor-plan.md` (Status: in progress) –
  visual + text editors for SUT environments and scenarios.

## Specs & Tools

- `docs/spec/asyncapi.yaml` – messaging contract (AsyncAPI).
- `docs/docs.html` – simple HTML viewer for the AsyncAPI spec.
- `tools/scenario-templating-check` – CLI for validating generator and
  HTTP Builder templates used in scenarios.
- `tools/mcp-orchestrator-debug` – MCP client for driving Orchestrator
  from tools/IDEs.
