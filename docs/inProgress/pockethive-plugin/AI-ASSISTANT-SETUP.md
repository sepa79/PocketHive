# AI Assistant Setup - Amazon Q, GitHub Copilot, Codex, GPT-5.5

## Status
`IN PROGRESS`

This guide shows how to connect common coding assistants to the PocketHive MCP
server. It is intentionally explicit: each assistant starts the same local
`tools/pockethive-mcp/start.cjs` stdio server, passes local environment values,
and follows the same tool-permission boundary.

## Shared Prerequisites

From the PocketHive repo root, install the MCP dependencies first:

```bash
npm ci --prefix tools/pockethive-mcp
```

Then run the doctor after `mcp.json`, `.amazonq/mcp.json`, or your shell
environment points at a real scenario-bundles checkout:

```bash
npm run mcp:doctor
```

Required local values:

| Value | Purpose | Example |
|---|---|---|
| `POCKETHIVE_ROOT` | This repo checkout | `/home/me/work/PocketHive` |
| `BUNDLES_ROOT` | Separate scenario-bundles checkout | `/home/me/work/pockethive-scenario-bundles` |
| `POCKETHIVE_BASE_URL` | PocketHive ingress/API base | `http://localhost:8088` |
| `POCKETHIVE_AUTH_USERNAME` | Local dev auth username | `local-admin` |
| `PH_BUNDLES_ROOTS` | JSON array of configured bundle roots | `["/home/me/work/pockethive-scenario-bundles"]` |
| `PH_WORKFLOW_SOURCE_ROOTS` | JSON array of extra source roots for JMeter/Postman/OpenAPI/etc. | `["/home/me/test-sources"]` |

Use absolute paths in IDE integrations unless the tool explicitly documents its
working directory. On Windows, use Windows-native paths for Windows-native IDEs:

```json
"C:\\Users\\me\\IdeaProjects\\PocketHive\\tools\\pockethive-mcp\\start.cjs"
```

Use WSL/Linux paths only when the assistant process runs inside WSL/Linux.

## PocketHive MCP Server

All assistant configurations point to the same command:

```text
node <absolute-path-to-PocketHive>/tools/pockethive-mcp/start.cjs
```

`start.cjs` ensures `tools/pockethive-mcp` dependencies are installed before it
imports the ESM server. If startup fails, run:

```bash
npm run mcp:doctor
```

## Tool Permission Boundary

For all assistants, prefer three permission tiers.

| Tier | Tools | Use when |
|---|---|---|
| Read/status | `workflow.config.get`, `workflow.config.validate`, `workflow.list`, `workflow.status`, `health.check`, `context.get`, `env.status`, `bundle.list`, `bundle.read` | Normal inspection and IDE status |
| Authoring | `workflow.start`, `workflow.source.read`, `workflow.update`, `workflow.preview`, `workflow.generate`, `workflow.validate`, `workflow.patch`, `workflow.report`, `bundle.check` | The user asks the assistant to create/fix a test workflow |
| Live runtime | `workflow.deploy`, `workflow.verify`, `scenario.*`, `swarm.*`, `debug.*`, `evidence.summary`, `mock.*`, `dataset.*` | The user explicitly wants deployment/runtime evidence |

Assistants must ask the user to fill required `nextQuestions` before generating
or deploying. The VS Code/IntelliJ plugin surfaces may display unanswered
questions, but they must not answer them on the user's behalf.

## Amazon Q Developer

Amazon Q Developer supports MCP servers in JetBrains and VS Code. Current AWS
docs say GUI-added MCP config is stored at `~/.aws/amazonq/default.json` for
global scope or `.amazonq/default.json` for local scope. Legacy
`~/.aws/amazonq/mcp.json` and `.amazonq/mcp.json` are also supported when
`useLegacyMcpJson` is enabled.

This repo currently includes:

- `.amazonq/mcp.json` for MCP server definitions.
- `.amazonq/agents/default.json` for the Q agent profile and allowed tools.

Recommended local `.amazonq/mcp.json`:

```json
{
  "mcpServers": {
    "pockethive-bundles": {
      "command": "node",
      "args": [
        "/absolute/path/to/PocketHive/tools/pockethive-mcp/start.cjs"
      ],
      "env": {
        "POCKETHIVE_BASE_URL": "http://localhost:8088",
        "POCKETHIVE_AUTH_USERNAME": "local-admin",
        "POCKETHIVE_ROOT": "/absolute/path/to/PocketHive",
        "BUNDLES_ROOT": "/absolute/path/to/pockethive-scenario-bundles",
        "PH_BUNDLES_ROOTS": "[\"/absolute/path/to/pockethive-scenario-bundles\"]",
        "PH_WORKFLOW_SOURCE_ROOTS": "[]",
        "PH_ACTIVE_ENVIRONMENT": "local"
      },
      "timeout": 60000,
      "disabled": false
    }
  }
}
```

JetBrains flow:

1. Install Amazon Q Developer in IntelliJ IDEA 2024.3 or later.
2. Sign in with Builder ID or IAM Identity Center.
3. Open the Amazon Q panel, then Chat.
4. Open the tools/MCP configuration UI.
5. Add or review the `pockethive-bundles` stdio server.
6. Enable the read/status and authoring tools. Enable live runtime tools only
   for users who are allowed to deploy/start swarms.

Keep `.amazonq/agents/default.json` aligned with the real tool surface. Remove
legacy allowed tools that no longer exist, such as old shell/Git/Docker tools,
when refreshing that profile.

## GitHub Copilot

GitHub Copilot Chat in JetBrains supports local and remote MCP servers. GitHub's
current JetBrains flow is: open Copilot Chat, switch to Agent mode, click the
tools icon, choose Add MCP Tools, and edit the `mcp.json` server definitions.

PocketHive local MCP example:

```json
{
  "servers": {
    "pockethive-bundles": {
      "type": "stdio",
      "command": "node",
      "args": [
        "/absolute/path/to/PocketHive/tools/pockethive-mcp/start.cjs"
      ],
      "env": {
        "POCKETHIVE_BASE_URL": "http://localhost:8088",
        "POCKETHIVE_AUTH_USERNAME": "local-admin",
        "POCKETHIVE_ROOT": "/absolute/path/to/PocketHive",
        "BUNDLES_ROOT": "/absolute/path/to/pockethive-scenario-bundles",
        "PH_BUNDLES_ROOTS": "[\"/absolute/path/to/pockethive-scenario-bundles\"]",
        "PH_WORKFLOW_SOURCE_ROOTS": "[]"
      }
    }
  }
}
```

If the user also wants GitHub issue/PR operations through Copilot, add GitHub's
remote MCP server with OAuth rather than putting a personal access token in a
project file:

```json
{
  "servers": {
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp/"
    }
  }
}
```

For organization or enterprise accounts, the Copilot policy for MCP servers
must be enabled by an administrator.

## Codex With GPT-5.5

OpenAI's current model guidance names `gpt-5.5` as the latest model and
recommends it for complex production workflows, coding, tool-heavy agents, and
long-running agentic tasks. Use the Responses API for custom API applications;
for Codex CLI/IDE usage, configure the Codex client.

Codex user-level configuration lives in:

```text
~/.codex/config.toml
```

Codex also supports project-scoped `.codex/config.toml` for trusted projects,
but machine-local auth/provider/profile settings belong in user-level config.
For this repo, prefer user-level config because the MCP server paths and bundle
roots are developer-local.

Example `~/.codex/config.toml`:

```toml
model = "gpt-5.5"
model_reasoning_effort = "medium"
model_verbosity = "medium"
approval_policy = "on-request"
sandbox_mode = "workspace-write"

[mcp_servers.pockethive-bundles]
command = "node"
args = ["/absolute/path/to/PocketHive/tools/pockethive-mcp/start.cjs"]
cwd = "/absolute/path/to/PocketHive"
startup_timeout_sec = 60
tool_timeout_sec = 150
enabled = true
enabled_tools = [
  "workflow.config.get",
  "workflow.config.validate",
  "workflow.list",
  "workflow.start",
  "workflow.source.read",
  "workflow.update",
  "workflow.status",
  "workflow.preview",
  "workflow.generate",
  "workflow.validate",
  "workflow.patch",
  "workflow.report",
  "bundle.list",
  "bundle.read",
  "bundle.check",
  "health.check",
  "context.get",
  "env.status"
]

[mcp_servers.pockethive-bundles.env]
POCKETHIVE_BASE_URL = "http://localhost:8088"
POCKETHIVE_AUTH_USERNAME = "local-admin"
POCKETHIVE_ROOT = "/absolute/path/to/PocketHive"
BUNDLES_ROOT = "/absolute/path/to/pockethive-scenario-bundles"
PH_BUNDLES_ROOTS = "[\"/absolute/path/to/pockethive-scenario-bundles\"]"
PH_WORKFLOW_SOURCE_ROOTS = "[]"
```

For live deployment/evidence work, add these tools only for a profile where the
developer is allowed to mutate the stack:

```toml
enabled_tools = [
  "workflow.deploy",
  "workflow.verify",
  "scenario.deploy",
  "scenario.list",
  "scenario.get",
  "swarm.list",
  "swarm.get",
  "swarm.create",
  "swarm.wait-ready",
  "swarm.start",
  "swarm.stop",
  "swarm.remove",
  "debug.queues",
  "debug.journal",
  "evidence.summary"
]
```

Do not commit `OPENAI_API_KEY`, GitHub tokens, or stack secrets to this repo.
Use Codex login, OS keychain-backed settings, or shell environment variables
outside version control.

## Recommended Workflow Prompt

Use this prompt with Amazon Q, Copilot, or Codex when asking for a new
PocketHive test workflow:

```text
Use the pockethive-bundles MCP workflow tools.
Start from my source file or instructions, then inspect workflow.status.
Ask me every remaining nextQuestion before generating artifacts.
After generation, validate. If validation/deployment/verification fails,
debug and patch explicitly, preserving attempt history.
Before completion, report evidence including bundle validation, deployment
state if run, runtime verification if run, evidence gaps, and changed files.
```

## Quick Diagnostics

| Symptom | Check |
|---|---|
| MCP server does not start | `npm run mcp:doctor`; use `npm run mcp:doctor -- --config .amazonq/mcp.json` for a specific assistant config |
| Assistant cannot see tools | Confirm the config key shape: Amazon Q uses `mcpServers`; Copilot uses `servers`; Codex uses `[mcp_servers.<id>]` |
| Path works in terminal but not IDE | Replace relative paths with absolute paths; use Windows paths for Windows-native IDEs |
| Bundle tools fail with path errors | Check `BUNDLES_ROOT` and `PH_BUNDLES_ROOTS` point to the same explicit scenario-bundles checkout |
| Workflow cannot read source file | Add its parent folder to `PH_WORKFLOW_SOURCE_ROOTS` |
| Runtime tools fail | Run `health.check`; confirm `POCKETHIVE_BASE_URL` points at the official ingress/API path |
| GitHub MCP blocked in Copilot | Ask the org admin to enable the MCP servers in Copilot policy |

## Official References

- Amazon Q Developer IDE MCP configuration:
  https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/mcp-ide.html
- Amazon Q Developer IDE setup:
  https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/q-in-IDE-setup.html
- GitHub Copilot MCP in JetBrains:
  https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/extend-copilot-chat-with-mcp?tool=jetbrains
- GitHub MCP server setup:
  https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp-in-your-ide/set-up-the-github-mcp-server?tool=jetbrains
- OpenAI GPT-5.5 model guidance:
  https://developers.openai.com/api/docs/guides/latest-model
- Codex configuration reference:
  https://developers.openai.com/codex/config-reference
