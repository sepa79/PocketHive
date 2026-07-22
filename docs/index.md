# PocketHive Documentation Index

This page groups the main docs so humans and tools have a single
starting point. All paths are relative to the repo root and are served
by the UI under `/docs/...` as part of the runtime image.

## Operator / Running PocketHive

- `docs/UPGRADING.md` – required version-to-version migration actions.
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
- `docs/archive/worker-configurable-io-plan.md` – historical delivery plan for
  config-driven IO types.
- `docs/todo/worker-configurable-io-followups.md` – remaining explicit IO work.
- `docs/todo/config-key-normalisation-plan.md` (Status: future / design) –
  key naming conventions backlog.

## Control Plane, Manager, Orchestrator

- `docs/ARCHITECTURE.md` – end-to-end architecture, roles, and
  contracts.
- `docs/ORCHESTRATOR-REST.md` – Orchestrator API and control-plane
  signals.
- `docs/architecture/AUTH_SERVICE_API_SPEC.md` – implemented baseline HTTP
  contract for shared auth, bearer token resolution, and product integration.
- `docs/todo/auth-service-followups.md` – future LDAP, HiveWatch, admin, and
  permission-model work.
- `docs/ui-v2/SCENARIO_WORKSPACE_UI_SPEC.md` – implemented Scenario workspace
  UI baseline.
- `docs/todo/sut-environments-followups.md` – remaining SUT environment work.
- `docs/todo/scenario-plan-followups.md` – remaining Scenario Plan extensions.
- `docs/todo/ui-v2-control-plane-followups.md` – remaining UI control-plane hardening.
- `docs/todo/network-proxy-followups.md` – post-V1 proxy reliability, plan integration,
  and isolation work.

## Plans and history

- `docs/inProgress/README.md` – actively delivered work only.
- `docs/toBeReviewed/README.md` – bounded review queue.
- `docs/todo/README.md` – future work and follow-ups.
- `docs/archive/readme.md` – historical and completed plans; do not use archived
  plans as current contracts.

## Specs & Tools

- `docs/spec/asyncapi.yaml` – messaging contract (AsyncAPI).
- `docs/docs.html` – simple HTML viewer for the AsyncAPI spec.
- `tools/scenario-templating-check` – CLI for validating generator and
  request templates used in scenarios.
- `tools/pockethive-mcp` – canonical PocketHive MCP server for agents and IDEs.
- `tools/mcp-orchestrator-debug` – lower-level emergency/local diagnostic CLI.
