# HiveMind Workflow for PocketHive Agents

HiveMind is the shared project-memory service for PocketHive AI work. Use it to keep durable, structured context across agent sessions: decisions, progress, risks, learnings, issues, and rule checks.

## Source of Truth

Use the global HiveMind MCP server configured in the client environment, for example VS Code. Do not start a local HiveMind API from this repository just to create PocketHive memory.

PocketHive project identity:

```text
project_id: pockethive
name: PocketHive
root_path: /home/sepa/PocketHive
default_branch: main
```

If the client exposes a global `hivemind` MCP server, use that server with `project_id=pockethive` and `workspace_path=/home/sepa/PocketHive`.

## Agent Workflow

For meaningful PocketHive work:

1. Use the globally configured HiveMind context or work-unit tools before making changes.
2. Read the returned startup summary and relevant learnings.
3. Record durable decisions, progress, risks, issues, and useful learnings.
4. Attach repo-relative file links for important artifacts.
5. Submit rule checks for relevant PocketHive rules when the global HiveMind ruleset provides them.
6. End the session or close the context when the work is complete.

If no global HiveMind MCP server is available in the current client, say so explicitly in the work log or final note. Do not create a repository-local `.hivemind/` store, do not start `/home/sepa/Skrybe` as an implicit fallback, and do not switch endpoints unless a human gives that endpoint for the current task.
