# PocketHive Documentation

Welcome to the PocketHive documentation hub. Use these resources to understand the system, explore individual services, and contribute effectively.

## Architecture
- [System Architecture](ARCHITECTURE.md)
- [SUT + Dataset + Simulation Model (proposal)](architecture/sut-dataset-simulation-model.md)

## Roadmap
- [Release Notes](../CHANGELOG.md)

## Usage
- [Usage Guide](USAGE.md)
- Prometheus helpers for the UI live in `ui/src/lib/prometheus.ts` and provide typed range/query helpers plus React Query hooks.

## Guides
- [Guides Hub](guides/README.md)
- [Workers Basics](guides/workers-basics.md)
- [Workers Advanced](guides/workers-advanced.md)
- [Templating Basics](guides/templating-basics.md)
- [Templating Advanced](guides/templating-advanced.md)

## Services
- [Orchestrator](../orchestrator-service/README.md)
- [Swarm Controller](../swarm-controller-service/README.md)
- [Generator](../generator-service/README.md)
- [Moderator](../moderator-service/README.md)
- [Processor](../processor-service/README.md)
- [Postprocessor](../postprocessor-service/README.md)
- [Trigger](../trigger-service/README.md)
- [Log Aggregator](../log-aggregator-service/README.md)

## Core Modules
- [Topology Core](../common/topology-core/README.md)

## SDK
- [Worker SDK Quick Start](sdk/worker-sdk-quickstart.md)

## Scenarios
- [Scenario overview](scenarios/README.md)
- [Scenario contract](scenarios/SCENARIO_CONTRACT.md)
- [Scenario patterns](scenarios/SCENARIO_PATTERNS.md)
- [Scenario templating (moved)](scenarios/SCENARIO_TEMPLATING.md)

## Contributing
- [Contributor Guide](../CONTRIBUTING.md)
- [Control Plane Testing Playbook](ci/control-plane-testing.md)
