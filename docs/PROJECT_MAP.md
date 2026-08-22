# PocketHive project map

| Reader context | Details |
| --- | --- |
| Audience | Contributors, maintainers, reviewers, and AI agents |
| Prerequisites | Read repository `AGENTS.md`; use the [PocketHive glossary](GLOSSARY.md) for shared terminology |
| Expected outcome | Locate the owning module, canonical contract, implementation entry point, and focused tests for a change |
| Last verified source | `rewrite/lifecycle-control-plane` at `195c8480` (unreleased) |

This is the fast orientation map for contributors and AI agents. It identifies
ownership, boundaries, authoritative contracts, implementation entry points,
and focused tests. It is curated rather than generated; use search inside the
named area after choosing the correct route below.

Repository `AGENTS.md` rules take precedence. Routing utilities, shared
envelopes, public contracts, production manifests, and security configuration
are protected areas and require explicit approval.

## PocketHive in 60 seconds

PocketHive turns a validated scenario bundle into one or more runtime swarms.
The stable platform services run continuously; Swarm Controllers and workers
are created dynamically for each swarm.

```text
PocketHive UI / PocketHive MCP / VS Code
  → official ingress REST APIs
  → Scenario Manager serves scenario browsing, validation, and authoring
  → Orchestrator asks Scenario Manager to resolve and prepare a create selection
  → Orchestrator owns desired lifecycle state
  → one Swarm Controller owns each swarm
  → the controller provisions work topology and workers
  → workers exchange WorkItems through RabbitMQ
  → target executors return internal command results through AMQP
  → the Orchestrator evaluates postconditions and publishes public terminal outcomes
  → worker status goes to its controller; only controller aggregate status goes to Orchestrator
  → Journal, logs, metrics, UI, and Grafana expose evidence
```

The scenario is the logical topology source of truth. The Orchestrator owns
cross-swarm lifecycle and public control APIs. A Swarm Controller owns its
swarm's topology, worker provisioning, concrete per-worker bootstrap config,
swarm-wide lifecycle broadcast, and worker aggregate. Workers own one bounded
behavior and use shared SDK contracts. A controller start/stop result is
produced only after fresh worker convergence; the Orchestrator owns operation
state and the public terminal outcome.

For published image inventory, use `tools/docker/image-manifest.sh`. For active
Java modules, use the root `pom.xml`.

## Customer and operator surfaces

| Area | Owns and does not own | Start reading |
| --- | --- | --- |
| `ui-v2/` | Primary customer application. Lifecycle/config mutations use Orchestrator REST; scenarios use Scenario Manager REST; STOMP control-plane consumption is read-only. | `src/App.tsx`, `src/pages/**`, `src/lib/scenariosApi.ts`, `src/lib/controlPlane/**`, `src/lib/auth.ts` |
| `docs-site/` + `docs/` | Customer documentation bundled into the UI image at `/docs/`. Docusaurus exposes only its configured include list. | `docs-site/docusaurus.config.ts`, `docs-site/sidebars.ts`, `ui-v2/Dockerfile`, `ui-v2/nginx.conf` |
| Grafana | Supported operator dashboards over PocketHive metrics. PocketHive owns provisioning and dashboards, not Grafana itself. | `grafana/provisioning/**`, `grafana/dashboards/**` |
| RabbitMQ, Redis Commander, WireMock | Supporting diagnostic/test applications, not the main customer workflow. | `rabbitmq/**`, `wiremock/**`, ingress routes in `ui-v2/nginx.conf` |
| TCP Mock UI | Supporting TCP SUT/mock application with its own UI and API. | `tcp-mock-server/src/main/resources/static/**`, its controller package |
| PocketHive MCP | **Current:** repository-local stdio/HTTP server for guarded bundle files and PocketHive APIs, with two read-only evidence Apps. **Target, not current:** public package, checkout-free setup, broader qualified Apps, and complete IDE coverage. Use the [MCP identity and status definition](guides/integrations/pockethive-mcp-and-bundles.md#current-versus-target). | `tools/pockethive-mcp/server.mjs`, `runtime-tools.mjs`, `workflow-tools.mjs`, `start.cjs` |
| VS Code extension | WIP technical authoring/runtime integration. Newer workflows use PocketHive MCP. | `vscode-pockethive/src/extension.ts`, `src/mcp/**`, `src/swarmLifecycle.ts`, `src/editors/**` |

Customer references:

- [PocketHive glossary](GLOSSARY.md)
- [PocketHive application guide](guides/ui/application-guide.md)
- [System workflows](guides/concepts/system-workflows.md)
- [Screenshot evidence manifest](guides/ui/screenshot-evidence.md)
- repository UI flow: `docs/ui-v2/UI_V2_FLOW.md`
- repository Scenario workspace specification:
  `docs/ui-v2/SCENARIO_WORKSPACE_UI_SPEC.md`

## Documentation ownership and canonical sources

Documentation ownership follows the behavior being described; it is not
defined by who last edited a page. The maintainer of an implementation area
owns the accuracy of its contract and reference material. Customer-facing
task guides are reviewed with that maintainer so they explain the same
behavior without becoming a second contract.

| Documentation class | Accountable maintainer role | Canonical source | Customer-facing projection |
| --- | --- | --- | --- |
| Shared product/evidence terminology | Architecture and documentation maintainers | [PocketHive glossary](GLOSSARY.md) | All customer, operator, and contributor entry guides link to the glossary |
| Product orientation and navigation | Product/UI maintainers | Current UI routes, access rules, and release composition | Repository `README.md`, `docs/README.md`, onboarding and application guides |
| Lifecycle and control plane | Orchestrator, Swarm Controller, and control-contract maintainers | [Architecture](ARCHITECTURE.md), [Orchestrator REST](ORCHESTRATOR-REST.md), `spec/asyncapi.yaml`, and `spec/control-events.schema.json` | [System workflows](guides/concepts/system-workflows.md), lifecycle and troubleshooting guides |
| Scenarios and worker capabilities | Scenario Manager and worker maintainers | [Scenario contract](scenarios/SCENARIO_CONTRACT.md), workspace API specification, capability manifests, and worker SDK reference | Scenario authoring and worker guides |
| Deployment and operations | Deployment/runtime maintainers | [Operator deployment guide](guides/operators/deployment.md) for path/status definitions; Compose/package manifests for mechanics; HiveForge manifest and guide for the managed path | Repository README, usage, package, and task-guide summaries |
| MCP and integrations | PocketHive MCP and integration maintainers | `tools/pockethive-mcp/README.md`, listed tool schemas, and checked-in client configuration | [PocketHive MCP and bundles](guides/integrations/pockethive-mcp-and-bundles.md) and [authoring/test tools](guides/integrations/authoring-and-test-tools.md) |
| Screenshots and walkthrough evidence | Owner of the guide that embeds the image | Running release, image file, and [screenshot evidence manifest](guides/ui/screenshot-evidence.md) | Application, onboarding, and operator task guides |
| Contributor and AI orientation | Cross-component architecture maintainers | Repository `AGENTS.md` plus the contracts named in this map | This project map |
| Documentation-impact automation | Architecture and documentation maintainers | [Documentation impact automation architecture](architecture/documentation-impact-automation.md) | `docs/inProgress/automated-documentation-foundation-plan.md` tracks delivery only and is not authoritative |
| Completed-work review | Architecture and documentation maintainers | [Completed-work review architecture](architecture/completed-work-review.md) and `docs/ci/completed-work-review-profiles.*` | `tools/completed-work-review/**` assembles identity-bound local comparisons; `docs/inProgress/completed-work-review-plan.md` tracks delivery only |

Use the following source rules when two pages appear to overlap:

1. Repository `AGENTS.md` contains the non-negotiable contribution rules.
2. A schema or public API contract owns its wire shape. Architecture owns
   component boundaries and cross-component behavior.
3. A task guide explains how to use that behavior. It links to the contract
   instead of redefining fields, routes, states, or support status.
4. This project map owns navigation to the correct source; it is not a
   substitute for that source.
5. `docs/toBeReviewed/` records evidence awaiting review.
   `docs/inProgress/`, `docs/todo/`, and `docs/archive/` do not define current
   behavior.

If a contract, implementation, and guide disagree, treat that as drift. Do not
choose the most convenient wording or copy the disagreement into another
page. Record the mismatch, identify the accountable implementation area, and
resolve or explicitly track it before presenting one version as current.

## Documentation task-to-file index

Start with one primary page, then update only the projections affected by the
same fact.

| Documentation task | Edit first | Cross-check before review |
| --- | --- | --- |
| Explain what PocketHive is or who it is for | Repository `README.md` or `docs/README.md`, depending on audience | Interactive overview, Start here, application guide |
| Change a first-run step | `guides/onboarding/quickstart-15min.md` | Start here, application guide, scenario guide, linked screenshot state |
| Change a UI route, label, role, or task flow | `guides/ui/application-guide.md` | `ui-v2/src/**`, quickstart, Help links, screenshot manifest |
| Change lifecycle evidence or terminology | [System workflows](guides/concepts/system-workflows.md) or the owning contract | Lifecycle guide, quickstart, MCP guide, Journal guidance |
| Change scenario shape or validation guidance | [Scenario contract](scenarios/SCENARIO_CONTRACT.md) | Scenario plan/pattern guides, examples, UI and MCP guidance |
| Change a deployment path or its status | [Operator deployment guide](guides/operators/deployment.md) | `USAGE.md`, repository README, `DEPLOYMENT_PACKAGE.md`, `HIVEFORGE.md` |
| Change an MCP tool, name, setup step, or safety boundary | `tools/pockethive-mcp/README.md` and tool schema | [PocketHive MCP and bundles](guides/integrations/pockethive-mcp-and-bundles.md), checked-in MCP client configuration, authoring/test tools |
| Change documentation-impact policy, schema, or behaviour | [Documentation impact automation architecture](architecture/documentation-impact-automation.md) | `docs/ci/docs-impact-map.*`, `tools/docs-impact/**`, the non-authoritative delivery tracker, and whole-repository seam fixtures |
| Change completed-work scoring, evidence, readiness, or rendering | [Completed-work review architecture](architecture/completed-work-review.md) | `docs/ci/completed-work-review-profiles.*`, `tools/completed-work-review/**`, validation receipts, and the non-authoritative delivery tracker |
| Add or replace a customer screenshot | [Screenshot evidence manifest](guides/ui/screenshot-evidence.md) | Embedding guide, route, role, visible state, release/source boundary, alt text and caption |
| Change a component boundary | [Architecture](ARCHITECTURE.md) | This map, system workflows, public contracts, focused tests |
| Add or move a module | Root `pom.xml` or the owning build manifest | This map, architecture diagrams, image manifest where applicable |

## Documentation drift review

Run this review whenever a change affects a documented route, command,
contract, state, screenshot, module, or deployment status:

1. Identify the canonical source from the ownership table and inspect the
   implementation entry point named in this map.
2. Search active documentation for the changed term, route, command, or state.
   Update projections or replace duplicated detail with a canonical link.
3. Confirm every command's prerequisites, platform, expected result, mutation
   level, proof boundary, failure path, and next step.
4. For visuals, verify the route and state against the running release and
   update the screenshot evidence manifest. Do not infer an unrecorded account,
   viewport, source revision, or redaction.
5. Confirm new or moved files are reachable from the documentation navigation
   or a task-oriented index and are included in the bundled Docusaurus set.
6. Build both supported documentation base paths and inspect the changed pages:

   ```powershell
   npm.cmd run typecheck --prefix docs-site
   npm.cmd run build --prefix docs-site
   $env:DOCS_BASE_URL = "/docs/"
   npm.cmd run build --prefix docs-site
   Remove-Item Env:DOCS_BASE_URL
   ```

7. Record the release or source boundary actually reviewed. A passing link
   build proves references resolve; it does not prove the described runtime
   behavior or that a screenshot is current.

## Platform services

| Module | Responsibility and boundary | Canonical contract / entry point | Focused tests |
| --- | --- | --- | --- |
| `auth-service` | Login, session resolution, users, and grants. Shared wire types belong in `common/auth-contracts`. | `docs/architecture/AUTH_SERVICE_API_SPEC.md`; `AuthController`, `AuthAdminController` | `AuthControllerTest`; `e2e-tests/.../auth-access.feature`; UI auth flows |
| `scenario-manager-service` | Scenario workspaces, parsing, validation, capability catalogue, SUT/network metadata, and prepared runtime bundles. It does not own swarm lifecycle. | [Scenario contract](scenarios/SCENARIO_CONTRACT.md); `ScenarioService`, `ScenarioBundleValidator`, `ScenarioController`, `ScenarioValidationController`, `CapabilityCatalogueService` | `ScenarioRepositoryValidationTest`, `ScenarioServiceTest`, `BundleValidationResultContractTest`, `CapabilityCatalogueServiceTest` |
| `orchestrator-service` | Runtime/workload intent, operation state, public lifecycle/config REST and terminal outcomes, Scenario Manager resolution, checksummed startup-artifact persistence, controller creation, journals, runtime debug, and governed cleanup. | [Orchestrator REST](ORCHESTRATOR-REST.md), [architecture](ARCHITECTURE.md); `SwarmController`, `ComponentController`, `ContainerLifecycleManager`, `ControllerStatusListener` | `SwarmControllerTest`, `ContainerLifecycleManagerTest`, `ComponentControllerTest`, `SwarmSignalListenerTest` |
| `swarm-controller-service` | One swarm: verifies and applies its assigned startup artifact, declares work topology, provisions and bootstraps workers, broadcasts start/stop enablement, waits for worker convergence, reports executor results, and aggregates observation. It does not own Orchestrator intent, operation state, or public outcomes. Dependency order is computed but is not currently enforced by the swarm-wide lifecycle dispatch. | `SwarmSignalListener`, `SwarmRuntimeCore`, `SwarmLifecycleManager`, `SwarmWorkTopologyManager`, `DockerWorkloadProvisioner` | `SwarmSignalListenerTest`, `SwarmLifecycleManagerTest`, `SwarmLifecycleManagerIntegrationTest`, `SwarmRuntimeCore*Test` |
| `network-proxy-manager-service` | Materializes Scenario Manager network profiles as Toxiproxy/HAProxy bindings requested per swarm by the Orchestrator. | `NetworkBindingController`, `NetworkBindingService`, `ToxiproxyHttpClient`, `HaproxyConfigClient` | `NetworkBindingServiceTest`, `NetworkBindingControllerTest`, adapter tests |

Stable infrastructure also includes RabbitMQ, Postgres, Redis, ClickHouse,
Grafana, and mock services. They are adapters or supporting applications, not
owners of PocketHive lifecycle contracts.

## Worker modules

All workers use `common/worker-sdk`, implement `PocketHiveWorkerFunction`,
receive controller-injected IO and bootstrap configuration, accept targeted
live updates directly from the Orchestrator route, and require a matching
capability manifest under `scenario-manager-service/capabilities/`. A targeted
live-update result returns to the Orchestrator, which owns the public outcome;
worker status is consumed by
the Swarm Controller and reaches the Orchestrator only through its aggregate.

| Worker module | Bounded responsibility | Primary implementation |
| --- | --- | --- |
| `generator-service` | Produce initial WorkItems from scheduler or dataset input. | `GeneratorWorkerImpl.java` |
| `moderator-service` | Shape, filter, gate, or rewrite work. | `ModeratorWorkerImpl.java` |
| `request-builder-service` | Build typed HTTP, TCP, or ISO8583 requests from templates. | `RequestBuilderWorkerImpl.java` |
| `processor-service` | Execute typed HTTP, TCP, or ISO8583 calls against the SUT. | `ProcessorWorkerImpl.java` |
| `http-sequence-service` | Execute a configured sequence of HTTP calls. | `HttpSequenceWorkerImpl.java`, `HttpSequenceRunner.java` |
| `db-query-service` | Execute named JDBC operations. | `DbQueryWorkerImpl.java`, `DbQueryRunner.java` |
| `postprocessor-service` | Project terminal outcomes and metrics, with an optional ClickHouse sink. | `PostProcessorWorkerImpl.java` |
| `clearing-export-service` | Batch terminal results into business export files. | `ClearingExportWorkerImpl.java` |
| `trigger-service` | Run scheduled shell or HTTP side effects as a terminal worker. | `TriggerWorkerImpl.java` |

When changing a worker, inspect its `*WorkerConfig.java`,
`*WorkerProperties.java`, `*WorkerImpl.java`, `application.yml`, capability
YAML, focused tests, and at least one representative scenario together.

## Shared modules

| Module | Owns |
| --- | --- |
| `common/swarm-model` | Canonical Java scenario/runtime model: bees, template, plan, topology, SUT, network DTOs, startup artifact, lifecycle axes, operation state, and runtime metadata. |
| `common/scenario-validation-contracts` | Validation result/layout DTOs shared with clients and MCP. |
| `common/control-plane-core` | Routing, envelopes, publishers/consumers, and topology descriptors. |
| `common/control-plane-spring` | Spring AMQP topology and auto-configuration. |
| `common/worker-sdk` | Worker discovery/runtime, control/config handling, explicit IO adapters, WorkItem API, and interceptors. |
| `common/manager-sdk` | Manager ports, compute abstraction, `ConfigFanout`, readiness, and buffer guard logic. |
| `common/docker-client` | Single-node Docker and Docker Swarm compute adapters. |
| `common/auth-contracts`, `common/auth-client` | Shared auth contracts and service-token client. |
| `observability`, `common/journal-postgres`, `common/sink-clickhouse` | Telemetry, Journal persistence, and product metric sinks. |
| `common/templating`, `common/request-templates` | Shared template engine and typed request definitions. |
| `common/topology-core` | Transitional control enums and historical topology material. Do not treat it as a source of implicit runtime-routing defaults. |

## Where to make a change

Follow the row for the change you intend. Contract-first means update and
review the named public contract before code.

| Change | Route |
| --- | --- |
| Scenario shape or validation | [Scenario contract](scenarios/SCENARIO_CONTRACT.md) → `common/swarm-model/**` and/or `common/scenario-validation-contracts/**` → `ScenarioBundleValidator` / `ScenarioService` → capability manifests → representative bundles → Scenario Manager, UI, and MCP tests |
| Swarm lifecycle | [Architecture](ARCHITECTURE.md) + [Orchestrator REST](ORCHESTRATOR-REST.md) → Orchestrator `SwarmController`, `ContainerLifecycleManager`, lifecycle state → Swarm Controller `SwarmSignalListener`, `SwarmRuntimeCore` → unit/integration tests → `e2e-tests/.../swarm-lifecycle.feature` |
| Worker runtime/config | `docs/sdk/worker-sdk-quickstart.md` + `docs/control-plane/worker-guide.md` + capability contract → `common/worker-sdk/**` → worker config/properties/implementation → capability YAML → scenario and tests |
| Control-plane signal, route, or schema | Contract first: `docs/spec/asyncapi.yaml` + `docs/spec/control-events.schema.json` → shared control enum only if needed → `common/control-plane-core/**` → Spring/runtime listeners → UI schema decoder → routing/schema/integration/E2E tests |
| WorkItem/data-plane envelope | `docs/spec/workitem-envelope.schema.json` → Worker SDK WorkItem API and typed envelopes → worker adapters → converter/unit tests → `workitem-headers.feature` |
| UI | `ui-v2/src/App.tsx` → page/component → `src/lib/**` API/store → authorization checks → focused test, lint, build. Ingress changes also update `ui-v2/nginx.conf` and HiveForge nginx parity. |
| Deployment | Local/evaluation: `docker-compose.yml`, `.env.example`, `build-hive.sh`, package scripts. Managed/production-like: `hiveforge.yaml`, component manifest, Ansible, stack template, runtime nginx. Keep the release artifact aligned with `tools/docker/image-manifest.sh`. |
| Observability | [Observability](observability.md) + [correlation and idempotency](correlation-vs-idempotency.md) → `observability/**` / Worker SDK interceptors → Postgres Journal or ClickHouse sink/schema → Grafana → focused tests |
| MCP/tooling | `tools/pockethive-mcp/README.md` → `server.mjs`, `runtime-tools.mjs`, or `workflow-tools.mjs` → schema/tool-list/workflow/runtime tests. Update VS Code adapters only when their exposed integration changes. |

| Completed-work review | [Completed-work review architecture](architecture/completed-work-review.md) → one explicit profile → identity and evidence receipts → `tools/completed-work-review/**` → contract, adversarial, renderer, and independent-review evidence |

For targeted live config, controller-owned bootstrap, and swarm-wide fan-out,
use the [canonical configuration workflows](guides/concepts/system-workflows.md#3-configuration-propagation)
and the [control-plane command contract](ARCHITECTURE.md#34-control-plane-commands-executor-results-and-outcomes).
This map only routes the change; it does not redefine the message flow.

## Tests by architecture seam

| Seam | Minimum focused evidence |
| --- | --- |
| Scenario admission | Scenario Manager validation tests and MCP `schema.test.mjs` |
| Lifecycle | Orchestrator lifecycle tests, Swarm Controller lifecycle tests, `swarm-lifecycle.feature` |
| Control plane | `ControlPlaneRoutingTest`, `ControlPlaneWireShapeTest`, `ControlEventsSchemaValidationTest`, `ControlPlanePublisherIntegrationTest` |
| Worker runtime/config | `DefaultWorkerRuntimeTest`, `WorkerControlPlaneRuntimeTest`, `LiveIoConfigUpdateGuardTest`, `WorkIOConfigBinderTest` |
| UI | `ui-v2/src/**/*.test.ts[x]`, lint, production build |
| Deployment | `tools/hiveforge-contract-check.sh`, package-content checks, `deployment-smoke.feature` |
| MCP | `npm test --prefix tools/pockethive-mcp`, then focused workflow acceptance/evals |
| Documentation | Docusaurus build at `/` and `/docs/`; UI image must contain the built site |

## Non-primary and historical paths

- `archive/legacy-ui/` is UI v1 and is not built, published, or deployed.
- `docs/archive/` preserves history; it is never a current contract.
- `docs/todo/`, `docs/inProgress/`, and `docs/toBeReviewed/` track work, not
  implemented behavior.
- `tools/mcp-orchestrator-debug/server.mjs` is legacy/additive debug MCP, not
  PocketHive MCP. Its CLI remains useful for lower-level local diagnosis.
- `scenarios/e2e/` contains test fixtures, not the preferred home for
  customer-owned bundles.
- Day-to-day MCP/IDE authoring uses an explicit external bundles directory via
  `BUNDLES_ROOT`; repository scenarios are examples and runtime fixtures.
- Standalone HTML under `docs/` can be a viewer/helper, while Markdown and
  schema files remain the source contracts.

## Recommended reading order

For a customer: [overview](guides/presentation/interactive-pockethive-overview.mdx)
→ [start here](guides/onboarding/start-here.md) → the task guide.

For a contributor or AI agent: repository `AGENTS.md` → this map → the
contract named in **Where to make a change** → the named implementation entry
point → focused tests. Do not begin with a repository-wide implementation
search unless the map has no route for the task.
