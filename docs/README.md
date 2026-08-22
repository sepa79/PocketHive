# PocketHive documentation

| Reader context | Details |
| --- | --- |
| Audience | Customers, evaluators, authors, operators, contributors, and AI assistants |
| Prerequisites | None |
| Expected outcome | Reach the shortest trustworthy page for your task |
| Candidate source tested | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

Customers use the version-matched site bundled at `/docs/`. Contributors and
AI assistants use the same sources here, starting with the
[project map](PROJECT_MAP.md) when they need implementation ownership.

GitHub Pages may publish a different branch or revision. Before following a
command there, match its displayed candidate/release boundary to the exact
source you intend to run. Prefer the bundled `/docs/` site for a running
installation.

## Choose by task

| Goal | Start here | Result |
| --- | --- | --- |
| Understand PocketHive | [Interactive overview](guides/presentation/interactive-pockethive-overview.mdx) | See the customer journey, architecture, lifecycle, and optional collaboration pattern. |
| Choose an evaluation path | [Start here](guides/onboarding/start-here.md) | Select the shortest guide for your environment. |
| Run locally | [15-minute quickstart](guides/onboarding/quickstart-15min.md) | Build and apply the startup gates; run a swarm only when Connectivity is OK. |
| Learn the application | [Application guide](guides/ui/application-guide.md) | Know which screen to use and what to verify. |
| Create a scenario | [First scenario](guides/onboarding/first-scenario.md) | Author, validate, and deploy one guarded bundle; stop at the current Connectivity gate, then remove the deployed copy. |
| Operate or investigate | [Swarm lifecycle](guides/operators/swarm-lifecycle.md) · [Troubleshooting](guides/operators/observability-troubleshooting.md) | Verify convergence and isolate failures. |
| Choose a deployment path | [Deployment paths](guides/operators/deployment.md) | Distinguish working, candidate, and target paths. |
| Automate with AI or an IDE | [PocketHive MCP](guides/integrations/pockethive-mcp-and-bundles.md) | Connect safely and run one guarded workflow. |
| Change the code | [Project map](PROJECT_MAP.md) | Find the owning component, contract, implementation, and tests. |

The customer sidebar presents these pages as a short book. Detailed contracts,
advanced authoring material, and maintenance evidence live in the separate
reference sidebar.

## Current release boundaries

These docs describe the unreleased lifecycle candidate at `0524165e` (whose
build still reports `0.15.35`); they do not designate a stable customer
release.

| Area | Current at tested source (`0524165e`) | Target; not current |
| --- | --- | --- |
| Deployment | Source build is available, but the tested candidate is blocked at the UI Connectivity gate. The Compose package is incomplete, and HiveForge renders and validates only. No path is documented as supported production deployment. | Qualified immutable releases deployed, verified, updated, and removed through HiveForge. |
| PocketHive MCP | Repository-local client-owned stdio server with guarded bundle, lifecycle, evidence, diagnostic, and cleanup tools; two read-only MCP Apps; partial VS Code integration. The HTTP implementation is not customer-qualified. | Authenticated explicitly bound HTTP, public package, checkout-free setup, broader qualified Apps, and complete IDE coverage. |

Use the linked deployment and MCP guides for exact commands, evidence, and
safety boundaries; this table is only the entry-point status summary.

## Canonical sources

| Topic | Owner |
| --- | --- |
| Shared terminology | [Glossary](GLOSSARY.md) |
| System ownership and traceability | [Architecture](ARCHITECTURE.md) |
| Repository and implementation entry points | [Project map](PROJECT_MAP.md) |
| Completed-work comparison and readiness | [Completed-work review](architecture/completed-work-review.md) |
| Lifecycle, signals, and configuration propagation | [System workflows](guides/concepts/system-workflows.md) |
| Scenario topology | [Scenario contract](scenarios/SCENARIO_CONTRACT.md) |
| Orchestrator HTTP API | [Orchestrator REST](ORCHESTRATOR-REST.md) |
| Deployment support status | [Deployment paths](guides/operators/deployment.md) |
| Screenshot provenance | [Screenshot evidence](guides/ui/screenshot-evidence.md) |

Do not copy normative wire details into customer guides. Link to their owner.

## Contributor path

Read repository [`AGENTS.md`](../AGENTS.md), then the [glossary](GLOSSARY.md),
[project map](PROJECT_MAP.md), [architecture](ARCHITECTURE.md), and the contract
named for the component you will change. The contributor workflow is in
[`CONTRIBUTING.md`](../CONTRIBUTING.md).

Plans, review evidence, future work, history, and release notes remain in
[`inProgress/`](inProgress/README.md), [`toBeReviewed/`](toBeReviewed/README.md),
[`todo/`](todo/README.md), [`archive/`](archive/readme.md), and
[`CHANGELOG.md`](../CHANGELOG.md); they are not customer contracts.
