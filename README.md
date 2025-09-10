# PocketHive

PocketHive is a portable transaction swarm. It orchestrates containerised components that generate, moderate, process and analyse workloads through durable queues.

## Architecture

```mermaid
flowchart LR
  SC[Scenario] --> O[Orchestrator (Queen)]
  UI[UI] <--> O
  O --> SW[Swarm]
  subgraph SW
    direction TB
    M[Swarm Controller (Marshal)] --> W[(Workers (Bees))]
  end
  W --> SUT[(System Under Test)]
  W --> OBS[Observability]
```

## Quick start
1. Install Docker.
2. Run `docker compose up -d` to launch RabbitMQ, services and the UI.
3. Open <http://localhost:8088>. Only the Orchestrator (Queen) runs initially. Create and start swarms from the Hive view by selecting a scenario.

## Documentation
- [Docs index](docs/README.md)
- [Architecture reference](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Usage guide](docs/USAGE.md)
- [Contributor guide](CONTRIBUTING.md)

---

PocketHive · portable transaction · swarm
