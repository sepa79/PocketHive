# Scenario Builder MCP MVP / POC

## Goal

Deliver a **fast local MVP** that lets a new developer create a PocketHive
scenario in a guided wizard flow through GitHub Copilot, while keeping the
canonical PocketHive scenario bundle as the only SSOT.

## Product shape

The MVP is intentionally split into three layers:

1. **Copilot / chat UX**
   - the user talks to Copilot in agent mode,
   - Copilot drives a constrained wizard flow,
   - the user is never asked to hand-edit final YAML.
2. **Scenario Builder MCP**
   - local `stdio` MCP server,
   - tools-only,
   - session working copy under the current workspace,
   - no unrestricted repo writes.
3. **Canonical scenario bundle**
   - output is standard PocketHive `scenario.yaml` plus optional assets such as
     `templates/http/*.yaml`,
   - validation is done against the current PocketHive scenario contract.

## Why this is the fastest useful MVP

- It works with the current Copilot MCP model without waiting for a custom UI.
- It keeps the implementation thin and contract-first.
- It helps new developers through wizard steps instead of expecting them to know
  PocketHive internals up front.
- It avoids inventing a second scenario model.

## Scope

### Included

- local MCP server via `stdio`
- wizard-first flow for new developers
- `rest-basic` scenario scaffold
- `rest-request-builder` scenario scaffold
- guided setup for:
  - processor `baseUrl`
  - generator rate
  - simple stop plan
  - direct HTTP request or one request-builder template
- preview, validation, export

### Deferred

- VS Code webview wizard
- Postman import
- full capability-driven field coverage
- feedback analytics loop
- remote/shared MCP service
- multi-template authoring sessions

## Wizard flow

### `rest-basic`

1. Start session
2. Set SUT endpoint
3. Set traffic profile
4. Set HTTP request
5. Validate
6. Preview
7. Export

### `rest-request-builder`

1. Start session
2. Set SUT endpoint
3. Set traffic profile
4. Set HTTP template
5. Validate
6. Preview
7. Export

## Tool surface

- `session.start`
- `session.get_state`
- `processor.set_config`
- `generator.set_inputs`
- `plan.set_swarm`
- `generator.set_worker_message`
- `request_builder.set_config`
- `template_http.put`
- `bundle.validate`
- `bundle.preview`
- `bundle.export`
- `session.discard`

## Canonical mapping

The wizard updates only canonical scenario bundle fields:

- `template.bees[]`
- `template.bees[].ports[]`
- `template.bees[].work.in/out`
- `template.bees[].config`
- `topology.edges[]`
- `plan`
- `templates/http/*.yaml`

No MCP-only scenario model is introduced.

## POC implementation in this branch

- `tools/scenario-builder-mcp/server.mjs`
  - local MCP server
- `tools/scenario-builder-mcp/scenario-builder-core.mjs`
  - pure wizard/session logic
- `tools/scenario-builder-mcp/scenario-builder-core.test.mjs`
  - basic validation/export coverage
- `tools/scenario-builder-mcp/README.md`
  - local usage with Copilot
- `vscode-pockethive/src/chatParticipant.ts`
  - `@pockethive` chat entry point
- `vscode-pockethive/src/scenarioWizard.ts`
  - guided wizard command reused by chat and command palette
- `docs/concepts/pockethive-chat-wizard-architecture.md`
  - architecture overview and diagrams

## Next step after the POC

The next iteration should keep the same MCP core and add a better novice UX in
VS Code:

1. optional webview summary / review panel
2. capability-driven forms based on `scenario-manager-service/capabilities/*.latest.yaml`
3. richer validation and examples per scenario pattern
4. richer in-chat state inspection and repair flows for `@pockethive`
