# Architecture

## Goal
PocketHive delivers modular Java microservices and a React UI that together handle event processing and moderation.

## System context
Services communicate over HTTP and AMQP. Each service exposes APIs and consumes messages while remaining independent. The Scenario Manager provides REST endpoints that the UI queries to load swarm plans.

### High-level flow
```mermaid
flowchart LR
  SM[Scenario Manager] --> QN["Orchestrator (Queen)"]
  QN --> MSH["Swarm Controller (Marshal)"]
  MSH --> BW["Workers (Bees)"] --> SUT["System Under Test"]
  BW --> OBS[Observability]
```

### Queue pipeline
```mermaid
flowchart LR
  G[Generator] --> Qgen[(queue gen)] --> M[Moderator] --> Qmod[(queue mod)] --> P[Processor] --> Qfinal[(queue final)] --> PP[Postprocessor]
  P --> S[SUT]
```

### Topology with exchanges
```mermaid
flowchart LR
  %% Actors
  SC[Scenario]
  QN["Orchestrator (Queen)"]
  MSH["Swarm Controller (Marshal)"]
  G[Generator]
  M[Moderator]
  P[Processor]
  PP[Postprocessor]
  T[Trigger]
  S[SUT]

  %% Exchanges
  Xhive((exchange ph.swarm.hive))
  Xctrl((exchange ph.control))

  %% Queues
  Qgen[(queue ph.swarm.gen)]
  Qmod[(queue ph.swarm.mod)]
  Qfinal[(queue ph.swarm.final)]
  Qctrl[(queue ph.control)]

  %% Scenario and control flow
  SC --> QN --> MSH
  MSH -->|sig.swarm-start| Xctrl
  QN -->|publish sig.*| Xctrl
  Xctrl -->|bind| Qctrl
  Qctrl -.->|consume| G
  Qctrl -.->|consume| M
  Qctrl -.->|consume| P
  Qctrl -.->|consume| PP
  Qctrl -.->|consume| T
  T  -.->|publish sig.*| Xctrl

  %% Workload flow via ph.swarm.hive
  G -->|publish ph.swarm.gen| Xhive
  Xhive -->|bind ph.swarm.gen| Qgen -->|consume| M
  M -->|publish ph.swarm.mod| Xhive
  Xhive -->|bind ph.swarm.mod| Qmod -->|consume| P
  P -->|publish ph.swarm.final| Xhive
  Xhive -->|bind ph.swarm.final| Qfinal -->|consume| PP

  %% Direct call to SUT
  P -->|HTTP| S

  %% Styling
  classDef app fill:#0f1116,stroke:#9aa0a6,color:#ffffff,stroke-width:1;
  classDef svc fill:#0f1116,stroke:#66bb6a,color:#ffffff,stroke-width:1;
  classDef ex  fill:#1f2430,stroke:#ffc107,color:#ffc107,stroke-width:1.5;
  classDef q   fill:#11161e,stroke:#4fc3f7,color:#4fc3f7,stroke-width:1.5;

  class G,M,P,PP,T app;
  class QN,MSH,S svc;
  class Xhive,Xctrl ex;
  class Qgen,Qmod,Qfinal,Qctrl q;
```

### Observability
```mermaid
flowchart LR
  B["Workers (Bees)"] -->|logs| LA[Log Aggregator] --> LK[Loki]
  B -->|metrics| PR[Prometheus]
  LA --> GF[Grafana]
  LK --> GF
  PR --> GF
```

## Orchestration hierarchy
PocketHive coordinates work through a layered control plane:

- **Queen** – global scheduler that creates and stops swarms.
- **Marshal** – per-swarm controller started by the Queen; it provisions message queues and launches worker containers.
- **Bees** – the worker services inside each swarm.

### Queen (Orchestrator)
The Queen coordinates the entire hive. It loads scenario plans, spins up or tears down swarms, and hands each Marshal the fragment of the plan it should execute.

### Marshal (Swarm Controller)
A Marshal governs one swarm. After receiving its plan from the Queen it declares the swarm's exchanges and queues, launches the bee containers described in the template and fans out config signals to individual bees.

## Swarm coordination

### Swarm startup
1. UI retrieves available templates from the Scenario Manager and publishes `sig.swarm-create.<swarmId>` with `{ "templateId": "<id>" }`.
2. Queen resolves the template from the Scenario Manager, converts it into a `SwarmPlan`, launches a Marshal and sends `sig.swarm-template.<swarmId>` with the full plan (all bees default to `enabled: false`).
3. Queen announces controller launch with `ev.swarm-created.<swarmId>`.
4. Marshal provisions queues and disabled bee containers, then emits `ev.swarm-ready.<swarmId>`.
5. Queen relays the ready event to the UI. The swarm only starts when the UI later sends `sig.swarm-start.<swarmId>`.

```mermaid
sequenceDiagram
  participant MSH as Swarm Controller (Marshal)
  participant QN as Orchestrator (Queen)

  Note over MSH: declares ph.control.swarm-controller.<instance>
  QN->>MSH: sig.swarm-template.<swarmId> (SwarmPlan)
  QN->>UI: ev.swarm-created.<swarmId>
  MSH->>UI: ev.swarm-ready.<swarmId>
  UI->>QN: sig.swarm-start.<swarmId>
  QN->>MSH: sig.swarm-start.<swarmId>

```

### Handshake
1. Marshal declares its control queue `ph.control.swarm-controller.<instance>` bound to `ph.control` for `sig.*` and `ev.*` topics.
2. Queen sends `sig.swarm-template.<swarmId>` once the controller is reachable and publishes `ev.swarm-created.<swarmId>`.
3. Marshal provisions queues and disabled bees, then emits `ev.swarm-ready.<swarmId>` to signal readiness.
4. Queen forwards the ready event to the UI. The Marshal remains idle until `sig.swarm-start.<swarmId>`.

```mermaid
sequenceDiagram
  participant MSH as Swarm Controller (Marshal)
  participant QN as Orchestrator (Queen)

  Note over MSH: declares ph.control.swarm-controller.<instance>
  QN->>MSH: sig.swarm-template.<swarmId> (SwarmPlan)
  QN->>UI: ev.swarm-created.<swarmId>
  MSH->>UI: ev.swarm-ready.<swarmId>
  UI->>QN: sig.swarm-start.<swarmId>
  QN->>MSH: sig.swarm-start.<swarmId>

```

### Queue provisioning
- Marshal expands queue suffixes with the swarm id.
- Declares `ph.<swarmId>.hive` and all required queues.
- Binds each queue to the exchange using its suffix as the routing key.

```mermaid
flowchart TB
  A[Expand suffixes with swarm id] --> B[Declare ph.&lt;swarmId&gt;.hive]
  B --> C[Declare queues]
  C --> D[Bind queues to exchange]
```

### Container lifecycle
- Marshal launches the bee containers defined in the plan.
- Runtime adjustments or shutdowns use signals such as `sig.swarm-stop.<swarmId>` on `ph.control`.

```mermaid
sequenceDiagram
  participant QN as Orchestrator (Queen)
  participant MSH as Swarm Controller (Marshal)
  participant WS as Worker Service (Bee)

  QN->>MSH: sig.swarm-start.<swarmId>
  MSH->>WS: launch container
  WS-->>MSH: status-full
  QN->>MSH: sig.swarm-stop.<swarmId>
  MSH->>WS: stop container
```

### Swarm Plan Template
Queens hand Marshals a resolved plan describing the swarm composition:

```yaml
id: rest
exchange: hive
bees:
  - role: generator
    image: generator-service:latest
    work:
      out: gen
  - role: moderator
    image: moderator-service:latest
    work:
      in: gen
      out: mod
  - role: processor
    image: processor-service:latest
    work:
      in: mod
      out: final
  - role: postprocessor
    image: postprocessor-service:latest
    work:
      in: final
```

Marshal prefixes each `work.in/out` with the swarm id to form queues like `ph.<swarmId>.gen` and binds them to the `ph.<swarmId>.hive` exchange using the same suffix as routing key.

### Control-plane signals
Communication between the Queen and Marshal uses these topics on `ph.control`:

| Direction | Routing key | Body | Purpose |
|-----------|-------------|------|---------|
| Queen → Marshal | `sig.swarm-template.<swarmId>` | `SwarmPlan` | Provide plan |
| Queen → Marshal | `sig.swarm-start.<swarmId>` | _(empty)_ | Start swarm |
| Queen → Marshal | `sig.swarm-stop.<swarmId>` | _(empty)_ | Stop swarm |
| Queen → Marshal (optional) | `sig.config-update...` | Partial plan | Adjust running swarm |
| Queen → Marshal (optional) | `sig.status-request...` | _(empty)_ | Request status |
| Queen → ph.control | `ev.swarm-created.<swarmId>` | _(empty)_ | Controller launched |
| Marshal → ph.control | `ev.swarm-ready.<swarmId>` | _(empty)_ | Swarm provisioned |
| Marshal → Queen | `ev.status-full.swarm-controller.<instance>` | Status snapshot | Report state |

## Multi-Region & Queue Adapters
PocketHive will support swarms running in multiple geolocations. Each swarm connects to a region-local broker while the Queen coordinates them through the control plane. Regions remain isolated from each other’s traffic yet share common naming and signalling conventions.

To enable broker diversity, messaging will flow through pluggable queue adapters. The core interface will expose publish and consume operations and minimal lifecycle hooks. Implementations for AMQP, Kafka, SQS and others can plug in without altering domain code.

### Adding a new driver
1. Implement the adapter interface for the target broker.
2. Provide configuration mapping and wiring inside the swarm controller.
3. Register the driver with the Queen so swarms may select it at launch time.

## Layers
Every service follows a hexagonal layout:
- **api** – inbound ports and DTOs.
- **app** – use cases and orchestration.
- **domain** – business rules, framework-free.
- **infra** – adapters for persistence, messaging and external systems.

## Boundaries
Cross-service calls go through API or message contracts only. Shared libraries live outside the domain to keep it pure.

## Testing strategy
- JUnit 5 for unit and integration tests.
- Cucumber for behaviour specs when features require it.
- ArchUnit guards package boundaries.

## Security
Principle of least privilege, encrypted transport, no secrets in code or logs.

## Versioning
Semantic Versioning with per-service change logs.
