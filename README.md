# PocketHive

PocketHive is a portable transaction swarm. It orchestrates containerised components that generate, moderate, process and analyse workloads through durable queues.

## Architecture

### Swarm at a glance

```mermaid
flowchart LR
  SM[Scenario Manager] --> UI[UI]
  UI <--> O["Orchestrator (Queen)"]
  O -. REST .-> OAPI[/POST /api/swarms/{swarmId}/create/]
  O --> SW[Swarm]
  subgraph SW
    direction TB
    M["Swarm Controller (Marshal)"] --> W["Workers (Bees)"]
  end
  M -. AMQP .-> W
  W --> SUT[(System Under Test)]
  W --> OBS[Observability]
```

### How a swarm comes to life

```mermaid
sequenceDiagram
  participant UI as UI
  participant OR as Orchestrator
  participant RT as Runtime (Docker/K8s)
  participant SC as Swarm Controller

  UI->>OR: POST /api/swarms/{swarmId}/create
  OR->>RT: Launch swarm controller workload
  RT-->>OR: Container ready
  SC-->>OR: ev.ready.swarm-controller.<instance>
  OR-->>UI: ev.ready.swarm-create.<swarmId>
  OR->>SC: sig.swarm-template.<swarmId>
  SC-->>OR: ev.ready.swarm-template.<swarmId>
  OR->>SC: sig.swarm-start.<swarmId>
  SC-->>OR: ev.ready.swarm-start.<swarmId>
```

### Swarm lifecycle states

```mermaid
stateDiagram-v2
  [*] --> New
  New --> Creating: POST /api/swarms/{swarmId}/create
  Creating --> Ready: ev.ready.swarm-create
  Ready --> Starting: sig.swarm-start
  Starting --> Running: ev.ready.swarm-start
  Running --> Stopping: sig.swarm-stop
  Stopping --> Stopped: ev.ready.swarm-stop
  Stopped --> Starting: sig.swarm-start
  Ready --> Removing: sig.swarm-remove
  Stopped --> Removing: sig.swarm-remove
  Running --> Removing: sig.swarm-remove
  Removing --> Removed: ev.ready.swarm-remove
  Removed --> [*]
```

## Quick start
1. Install Docker.
2. Run `./start-hive.sh` (Linux/macOS) or `start-hive.bat` (Windows) to clean previous runs, build the images and launch RabbitMQ, services and the UI. Use `--help` to run individual stages (clean, build, start) when needed.
   - Alternatively run `docker compose up -d` directly to start the stack with your existing images.
3. Open <http://localhost:8088>. Only the Orchestrator (Queen) runs initially. Create and start swarms from the Hive view by selecting a scenario.

## Documentation
- [Docs index](docs/README.md)
- [Architecture reference](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Usage guide](docs/USAGE.md)
- [Contributor guide](CONTRIBUTING.md)

---

PocketHive · portable transaction · swarm
