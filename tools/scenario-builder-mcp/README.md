# PocketHive Scenario Builder MCP POC

This folder contains a **quick MVP/POC** for guided PocketHive scenario authoring
through a local MCP server.

It is intentionally narrow:

- local `stdio` server only,
- wizard-first flow for new developers,
- canonical PocketHive bundle output only,
- explicit export path,
- no direct final-YAML editing by the AI.

The server keeps a working copy under:

- `<workspace>/.pockethive-mcp/sessions/<sessionId>/`

and exports only when `bundle.export` is called.

## What the POC supports

- `rest-basic` wizard flow
- `rest-request-builder` wizard flow
- guided steps for:
  - processor `baseUrl`
  - generator rate + simple stop plan
  - direct HTTP request or request-builder template
- preview of generated bundle files
- validation of the working copy
- explicit bundle export

## What it does not support

- arbitrary mutation of every scenario field
- full worker capability-driven forms
- multi-template request-builder flows
- Postman import
- VS Code webview UI
- remote MCP deployment

## Running the server

From the repo root:

```bash
node tools/scenario-builder-mcp/server.mjs
```

## Reused by the VS Code wizard

The VS Code extension reuses the same core through:

```bash
node tools/scenario-builder-mcp/cli.mjs create-from-wizard
```

The command reads wizard payload JSON from stdin and writes the export result as JSON to stdout.

## Suggested Copilot MCP config

In `.vscode/mcp.json`:

```json
{
  "servers": {
    "pockethive-scenario-builder": {
      "command": "node",
      "args": ["tools/scenario-builder-mcp/server.mjs"]
    }
  }
}
```

Then in Copilot Chat agent mode, the suggested flow is:

1. `session.start`
2. `processor.set_config`
3. `generator.set_inputs`
4. `plan.set_swarm`
5. `generator.set_worker_message`
6. `request_builder.set_config` and `template_http.put` for request-builder flows
7. `bundle.validate`
8. `bundle.preview`
9. `bundle.export`

## Example prompt for Copilot

```text
Use the PocketHive scenario builder wizard to create a rest-request-builder scenario
called demo-request-builder in this workspace. Use {{ sut.endpoints['default'].baseUrl }}
as the processor baseUrl, a scheduler ratePerSec of 10, a plan.swarm stop step at PT90S,
and an auth template that posts to /api/login.
Preview the bundle, validate it, and export it to /absolute/path/to/workspace/scenarios/bundles/demo-request-builder.
```

## Validation

```bash
npx vitest run tools/scenario-builder-mcp/scenario-builder-core.test.mjs
```
