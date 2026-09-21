# HiveMind Workflow for PocketHive Agents

HiveMind is the shared project-memory service for PocketHive AI work. Use it to keep durable, structured context across agent sessions: decisions, progress, risks, learnings, issues, and rule checks.

## Source of Truth

Use the global HiveMind MCP server configured in the client environment, for
example VS Code. For team work, point that global MCP server at the shared
remote HiveMind URL. Do not start a local HiveMind API from this repository
just to create PocketHive memory.

PocketHive project identity:

```text
project_id: pockethive
name: PocketHive
root_path: <developer-local-pocket-hive-path>
default_branch: main
```

If the client exposes a global `hivemind` MCP server, use that server with
`project_id=pockethive` and `workspace_path=<developer-local-pocket-hive-path>`.
The project id is shared; the workspace path is local to each developer.

Recommended remote configuration values:

```text
HIVEMIND_BASE_URL=https://<team-hivemind-host>
HIVEMIND_PROJECT_ID=pockethive
HIVEMIND_WORKSPACE_PATH=<developer-local-pocket-hive-path>
```

## MCP Tool Naming

MCP clients used by agents may reject tool names that contain characters outside `[a-z0-9_-]`.
HiveMind tools should therefore use names such as `context_open`, `project_register`, and `feature_list`,
not dotted names such as `context.open`.

## Agent Workflow

For meaningful PocketHive work:

1. Use the globally configured HiveMind context or work-unit tools before making changes.
2. Read the returned startup summary and relevant learnings.
   Check them against `docs/ai/HIVEMIND_MEMORY_STATUS.md`, the active plan in
   `docs/inProgress/README.md`, and the current revision before acting. An old
   finding, deployment observation, or permission is not a current instruction.
3. Record durable decisions, progress, risks, issues, and useful learnings.
4. Attach repo-relative file links for important artifacts.
5. Submit rule checks for relevant PocketHive rules when the global HiveMind ruleset provides them.
6. End the session or close the context when the work is complete.

## Memory maintenance

Use the selected global MCP server's actual tool catalogue, not a proposed endpoint
name or a different deployment. Discover available tools before declaring one missing.
`project_review` gives recommendations; age alone does not justify closing a record.

- Use `entry_mark` to change an existing journal entry's lifecycle with a reason and,
  where appropriate, its replacement ID. Use `entry_correct` when part of the old entry
  remains valid; preserve the evidence and create one current replacement.
- Keep real unresolved risks open. Superseded work sequencing is not a fixed defect;
  deferred test work remains deferred. Never close historical blocked rule checks by
  writing unrelated passing checks or by assuming a later successful run resolves them.
- `learning_feedback` annotates an existing learning; it does not change its active
  status. Creating a new learning with `status=superseded` does not supersede an old ID.
  Do not claim stale learning suppression until a fresh read demonstrates it.
- Use explicit learning lifecycle tools only when the connected server actually exposes
  them. If absent, record the exact stale IDs and current interpretation in
  `HIVEMIND_MEMORY_STATUS.md`, attach correction feedback, and report the limitation.
  Do not add competing active replacement learnings or modify backing storage directly.
- Project deletion/merge tools operate on entire projects. They are not substitutes for
  retiring individual entries or learnings. Keep maintenance scoped to PocketHive.
- Verify mutations using fresh entry searches and project/branch startup or brief output.
  Preserve history. Past operational permissions never authorize a new push/deployment.

The current review-pass definitions are owned by `docs/REVIEW_RULES.md`; memory links
to those rules and records evidence rather than maintaining a competing review procedure.

If no global HiveMind MCP server is available in the current client, say so
explicitly in the work log or final note. Do not create a repository-local
`.hivemind/` store, do not start any local HiveMind service as an implicit
fallback, and do not switch endpoints unless a human gives that endpoint for
the current task.
