# PocketHive glossary

| Reader context | Details |
| --- | --- |
| Audience | Customers, operators, scenario authors, contributors, and AI agents |
| Prerequisites | None |
| Expected outcome | Use one shared meaning for PocketHive terms and follow the linked canonical contract when exact behavior matters |
| Last verified source | `rewrite/lifecycle-control-plane` at `195c8480` (unreleased) |

This page owns the short, plain-language definitions reused across PocketHive
documentation. Task guides link here instead of creating another definition.
Wire fields, validation rules, routes, and state-machine details remain owned by
the linked contracts.

| Term | Canonical plain-language definition | Exact details |
| --- | --- | --- |
| **Scenario bundle** | A versionable directory that describes reusable test behavior, including its scenario definition and referenced assets. | [Scenario contract](scenarios/SCENARIO_CONTRACT.md) |
| **Template, plan, and startup artifact** | The scenario template describes reusable intent. Scenario Manager resolves it into a concrete `SwarmPlan`; the Orchestrator persists that plan and timeline in one checksummed startup artifact, which is the controller's sole initialization input. | [Scenario contract](scenarios/SCENARIO_CONTRACT.md), [system workflows](guides/concepts/system-workflows.md) |
| **Swarm** | One isolated runtime instance created from a scenario, with one controller and the workers required by its resolved plan. | [Architecture](ARCHITECTURE.md), [swarm lifecycle](guides/operators/swarm-lifecycle.md) |
| **Orchestrator** | The platform component that owns public swarm-control APIs, runtime/workload intent, operation state, and every public terminal command outcome. | [Architecture](ARCHITECTURE.md), [Orchestrator REST](ORCHESTRATOR-REST.md) |
| **Swarm Controller** | The per-swarm executor that loads its assigned startup artifact, materializes runtime resources, applies swarm-wide control, reports internal results, and aggregates worker observation. | [Architecture](ARCHITECTURE.md) |
| **Worker / bee** | A bounded runtime component that performs one scenario-defined role and reports its own status. “Bee” is the product metaphor; “worker” is the technical term used in contracts and code. | [Worker capabilities](architecture/workerCapabilities.md) |
| **System under test (SUT)** | The external system, service, or mock that a scenario exercises. PocketHive does not own the SUT's business outcome. | [Scenario contract](scenarios/SCENARIO_CONTRACT.md) |
| **Control plane** | The path for lifecycle/configuration signals, executor results, public outcomes, status, journal, and alert messages. | [AsyncAPI](spec/asyncapi.yaml), [control-event schema](spec/control-events.schema.json) |
| **Work plane / WorkItem** | The scenario-defined path and envelope used to move workload data between workers. | [WorkItem schema](spec/workitem-envelope.schema.json), [scenario contract](scenarios/SCENARIO_CONTRACT.md) |
| **Signal, result, outcome, and status** | A signal requests work. A target executor's `event.result.*` is internal correlated evidence. The Orchestrator's `event.outcome.*` is the sole public terminal result after postconditions. Status reports observed component or aggregate state. | [System workflows](guides/concepts/system-workflows.md), [control-event schema](spec/control-events.schema.json) |
| **Journal** | The customer-visible correlated history of requests, signals, executor evidence, public outcomes, and alerts. It is not the source for every current aggregate field. | [Observability](observability.md) |
| **Snapshot / fresh aggregate** | The latest controller aggregate shown for a swarm, interpreted together with its receive time and staleness boundary. | [Swarm lifecycle](guides/operators/swarm-lifecycle.md), [system workflows](guides/concepts/system-workflows.md) |
| **Acceptance, dispatch, and convergence** | Acceptance assigns an asynchronous operation. Dispatch sends its work. Convergence means fresh runtime evidence satisfies the intended state; a successful public lifecycle outcome is emitted only after its required convergence/postconditions. | [System workflows](guides/concepts/system-workflows.md#read-the-evidence-in-layers) |
| **`correlationId` and `idempotencyKey`** | `correlationId` connects a request to its evidence; `idempotencyKey` identifies a retry-safe logical operation where the contract supports it. They are not interchangeable. | [Correlation versus idempotency](correlation-vs-idempotency.md) |
| **Official ingress** | The supported customer origin for an environment, including its documented proxied paths. Direct backend ports are diagnostic interfaces unless a contract explicitly says otherwise. | [Application guide](guides/ui/application-guide.md), [deployment paths](guides/operators/deployment.md) |
| **Current and target** | **Current** is verified behavior at the stated source/release boundary. **Target** is planned or proposed behavior and must not be presented as implemented. | [Architecture](ARCHITECTURE.md) |

When a guide and a linked contract appear to disagree, use the contract for the
exact shape and record the documentation drift. Do not add a third definition.
