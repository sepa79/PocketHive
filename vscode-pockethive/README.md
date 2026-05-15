# PocketHive VS Code extension (WIP)

Basic commands for the Orchestrator REST API.

## Setup

1. Open this repo in VS Code.
2. Run `init.sh`.
3. Add a `pockethive.environments` entry with a `name`, PocketHive `baseUrl`, and optional per-environment `authToken`.
4. Set `pockethive.activeEnvironment` to that environment name.
5. Add one or more `pockethive.bundlesFolders` entries for local scenario bundles.

## Commands

- `PocketHive: Configure Orchestrator URL`
- `PocketHive: Configure Scenario Manager URL`
- `PocketHive: List swarms`
- `PocketHive: Start swarm`
- `PocketHive: Stop swarm`
- `PocketHive: Remove swarm`
- `PocketHive: Open Orchestrator`
- `PocketHive: Open scenario`

## Develop

```bash
cd vscode-pockethive
npm install
npm run build
```

Views (left sidebar):
- Hive: swarm list with basic actions.
- Buzz: recent hive journal entries (requires journal endpoint).
- Journal: per-swarm journal tail.
- Scenario: scenario list, open raw YAML (save writes back to Scenario Manager), plus a basic preview.
