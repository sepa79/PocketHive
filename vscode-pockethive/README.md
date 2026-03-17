# PocketHive VS Code extension (WIP)

Basic commands for the Orchestrator REST API.

## Setup

1. Open the repo root `PocketHive` in VS Code.
2. Run `npm install`.
3. Run `cd vscode-pockethive && npm install`.
4. Press `F5` and choose `Run PocketHive VS Code Extension`.
5. In the Extension Development Host window, open the same `PocketHive` repo root if it is not already open.
6. Optional: set `pockethive.authToken` if the API requires auth.

## Commands

- `PocketHive: Configure Orchestrator URL`
- `PocketHive: Configure Scenario Manager URL`
- `PocketHive: Create Scenario Wizard`
- `PocketHive: List swarms`
- `PocketHive: Start swarm`
- `PocketHive: Stop swarm`
- `PocketHive: Remove swarm`
- `PocketHive: Open Orchestrator`
- `PocketHive: Open scenario`

Chat:
- `@pockethive /tutorial` shows a short onboarding tutorial.
- `@pockethive /wizard` starts a guided scenario-creation flow for new developers.
- `@pockethive /restBasic` creates a direct REST scaffold.
- `@pockethive /requestBuilder` creates a REST + request-builder scaffold.
- `@pockethive /fromEvidence` starts an evidence-driven session from Cucumber, Java, Postman, or notes.
- `@pockethive /addEvidence` adds more files to the active evidence session and re-runs AI analysis.

## Develop

```bash
npm run vscode:build
```

Views (left sidebar):
- Hive: swarm list with basic actions.
- Buzz: recent hive journal entries (requires journal endpoint).
- Journal: per-swarm journal tail.
- Scenario: scenario list, open raw YAML (save writes back to Scenario Manager), plus a basic preview.

Scenario wizard:
- Uses the PocketHive scenario-builder POC in `tools/scenario-builder-mcp/`.
- Writes a canonical scenario bundle to an explicit target folder.
- Opens the generated `scenario.yaml` after export.
- Architecture overview for discussion lives in `docs/concepts/pockethive-chat-wizard-architecture.md`.

Evidence-driven POC:
- Keeps session state under `.pockethive-mcp/evidence-sessions/` in the workspace.
- Stores `session.json` plus `events.jsonl`.
- Uses the current Copilot chat model to decide `need_more_evidence` vs `ready_to_generate`.
- The evidence summary tells the user to reply with `@pockethive <number>` to choose a suggested next step, or `@pockethive <details>` to add missing information for re-analysis.
