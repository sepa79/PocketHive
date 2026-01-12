# VS Code Extension (v1.0) — Plan

Scope: the main VS Code extension at `vscode-pockethive/` (successor to the PoC).

## Feature list

### Core
- Configure Orchestrator URL, Scenario Manager URL, and auth token.
- Connection status indicator and clear error surfaces for failed calls.
- Command palette shortcuts for all primary actions.

### Hive
- List swarms with status, scenario, and last activity.
- Start, stop, and remove swarms.
- Open Hive UI and Orchestrator UI in the browser.
- View swarm details (topology summary + worker health).

### Journal
- Hive journal list with filters (swarmId, runId, severity).
- Swarm journal tail with live refresh.
- Jump from a swarm to its journal context.

### Scenarios (focus area)
- List scenario bundles from Scenario Manager.
- Open and edit `scenario.yaml` with save.
- Apply a scenario to create a new swarm (explicit `swarmId` via Orchestrator `POST /api/swarms/{swarmId}/create`).
- Show validation errors returned by Scenario Manager.
