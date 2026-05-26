# PocketHive Plugin Docs

## Status
`IN PROGRESS`

This folder is the working design pack for the PocketHive IDE and MCP plugin
work. Read this file first, then follow the implementation order below.

## Current Product Shape

PocketHive exposes scenario, swarm, runtime, mock, dataset, and evidence APIs.
The plugin work adds agent-friendly access to those capabilities through an MCP
server and IDE integrations.

The important boundary is simple:

```
AI agent / IDE user
        |
        v
PocketHive MCP tools and IDE panels
        |
        v
PocketHive product APIs and guarded bundle files
        |
        v
PocketHive runtime evidence
```

The MCP server is not a terminal. It does not build services, start Docker,
run Git, run package managers, read container logs, or query Loki directly.

Scenario bundles should live in a separate scenario-bundles repository. The
PocketHive product repo may keep small bundled examples and legacy fixtures for
runtime smoke tests, but day-to-day authoring through the MCP/IDE plugin should
point `BUNDLES_ROOT` at an explicit external checkout. This keeps product code,
runtime examples, and team-owned test content on clean lifecycle boundaries.

## Read Order

| Step | Read | Purpose |
|---|---|---|
| 1 | `README.md` | Orientation, scope, implementation order |
| 2 | `AGENT-RULES.md` | Non-negotiable rules for implementation agents |
| 3 | `ARCHITECTURE.md` | System model and transport model |
| 4 | `MCP-SERVER.md` | MCP server migration and tool surface |
| 5 | `MCP-IMPROVEMENT-SPEC.md` | Authoring, validation, mocks, datasets, evidence |
| 6 | `TOOL-CONTRACTS.md` | Tool contract format and current tool ownership |
| 7 | `EVIDENCE.md` | Runtime evidence taxonomy |
| 8 | `DEVELOPER-SETUP.md` | Local/team setup for MCP, VS Code, HiveMind, GitHub MCP |
| 9 | `AI-ASSISTANT-SETUP.md` | Amazon Q, GitHub Copilot, Codex, and GPT-5.5 setup |
| 10 | `POC-RUNBOOK.md` | Start-to-finish proof path for the current POC |
| 11 | `BUNDLE-WIZARD.md` | Novice scenario creation flow |
| 12 | `VSCODE-PLUGIN.md` / `INTELLIJ-PLUGIN.md` | IDE-specific implementation |
| 13 | `BUILD-READY-GAPS.md` | Decisions that unblock implementation |

`MCP-APPS.md` has a narrow Phase 1.5 spike for a read-only evidence widget.
The broader MCP Apps dashboard/form platform remains future-facing until client
support is confirmed.

## Implementation Order

1. Migrate and simplify the MCP server.
   - Move the server to `tools/pockethive-mcp/`.
   - Remove shell/devops/log-scraping tools.
   - Keep API-backed and guarded bundle-file tools.
   - Make configuration explicit through injected environment variables.

2. Stabilise tool contracts.
   - Add or update wrappers using the contract template in `TOOL-CONTRACTS.md`.
   - Ensure every tool declares inputs, outputs, side effects, and evidence
     source.
   - Return explicit source metadata for contract tools.
   - Remove general `github.*` tools from PocketHive MCP; GitHub issue work
     belongs to a separate GitHub MCP with an issue-only token.

3. Build the IDE adapters.
   - VS Code and IntelliJ spawn the MCP server in stdio mode.
   - IDE panels reuse built `ui-v2` assets.
   - New commands call MCP tools, not PocketHive APIs directly.

4. Add authoring support.
   - Public novice flow uses `wizard.start`, `wizard.answer`,
     `wizard.summary`, and `wizard.complete`.
   - Advanced/internal authoring uses `session.*`, `scenario.*`,
     `pipeline.*`, `bee.*`, `template.*`, and `bundle.*` tools.

5. Add evidence and documentation generation.
   - Use the taxonomy in `EVIDENCE.md`.
   - Populate changelog entries only from actual tool output.
   - Use PocketHive-provided logs only if PocketHive exposes a structured log
     API.

6. Spike one MCP App evidence widget.
   - Add `evidence.summary` as a canonical JSON tool.
   - If the client explicitly supports MCP Apps, render a read-only evidence
     summary widget from the same result.
   - Do not build the broader dashboard/form platform in this phase.

## Responsibility Matrix

| Responsibility | PocketHive stack | MCP server | IDE plugin | AI agent |
|---|---|---|---|---|
| Start Docker or rebuild services | Dev workflow only | No | No | No |
| Run Git, Maven, npm, shell scripts | Dev workflow only | No | No | No |
| Expose scenario and swarm state | Yes | Reads via APIs | Displays via MCP | Interprets |
| Read container logs | No product contract | No | No | No |
| Read structured product logs | If product API exists | Via PocketHive API only | Via MCP only | Interprets |
| Query Loki directly | Future backend only | No | No | No |
| Manage scenario bundles | Owns runtime import | Guarded external bundle repo files | Calls MCP | Requests changes |
| Validate scenario contracts | Runtime source of truth | Exposes/checks contracts | Displays results | Uses results |
| Seed/check mocks and datasets | Runtime/admin APIs | API-backed tools | Calls MCP | Requests evidence |
| Record evidence | Provides raw evidence | Collects via tools | Displays results | Summarises |
| Store secrets | Product/ops concern | Receives env only | OS keychain | Never stores |
| GitHub issues | GitHub service | No general tools | External GitHub MCP only | Uses issue-only token MCP |
| Shared agent memory | HiveMind service | No | Configures global MCP | Records decisions |

## Current vs Future

| Area | Current phase | Future option |
|---|---|---|
| IDE integration | stdio MCP plus IDE webviews | Same |
| AI chat integration | JSON MCP tools | Read-only MCP App evidence widget, then broader Apps |
| Runtime evidence | journal, taps, queues, metrics, mocks, datasets | PocketHive structured log API |
| Logs backend | Not directly exposed | Loki behind a PocketHive API |
| Contract source | Runtime where available, explicit offline sources otherwise | Scenario Manager contract manifest APIs |
| GitHub issue workflow | External GitHub MCP with issue-only token | Optional evidence export helper |
| HiveMind | Global shared MCP config | Team-hosted remote profile |

## Definition Of Done For These Docs

The docs are ready for implementation when an agent can answer:

- Which file is the source of truth for the task?
- Which tools are public, advanced, or internal?
- Which tools may write files?
- Which evidence source proves each claim?
- Which phase owns the work?
- Which future ideas are explicitly deferred?
