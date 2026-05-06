# HiveMind Rules — qa-nft-pockethive-bundles

These rules govern how HiveMind is used in this repo via the `hivemind` MCP server.
Follow them every time without exception.

## Project registration

This repo is registered in HiveMind as:
- `project_id`: `qa-nft-pockethive-bundles`
- `default_branch`: `main`

Always use this `project_id` in all HiveMind tool calls.

## Session lifecycle — ALWAYS follow this pattern

### Resolve the current branch FIRST

**Before any HiveMind call**, run `git.execute branch` and extract the line prefixed
with `*` to get the actual current branch. Never assume `main` or any other branch.
Never hardcode a branch name.

### Start of meaningful work
Call `session_start` at the beginning of any meaningful task (creating a bundle,
debugging a swarm, fixing a script, etc.):

```
session_start {
  project_id: "qa-nft-pockethive-bundles",
  branch: "<resolved from git.execute branch — never hardcoded>",
  workspace_path: "<absolute path to repo root>",
  author_id: "<your-username>",
  author_type: "human",
  source: "mcp",
  agent_id: "amazon-q",
  goal: "<concise description of what this session is doing>"
}
```

Read the returned startup summary — it contains recent decisions, active learnings,
and open issues relevant to the current branch and feature.

### During work
Record important context as it happens using `entry_append`. Do not wait until the end.

Use `context_open` when you need a reusable token for multiple tool calls in the same
session (learning_capture, issue_report, etc.). Always pass the resolved branch here too.

### End of session
Always call `session_end` when work is complete or abandoned:

```
session_end {
  session_id: "<session_id from session_start>",
  status: "completed" | "abandoned"
}
```

Read the closeout report — it lists missing required rule checks and active learnings.

## Entry types — when to use each

| Type | Use for |
|---|---|
| `decision` | Durable design or implementation choice (e.g. "use stepId not id in http-sequence steps") |
| `progress` | Meaningful milestone (e.g. "smarter-onboarding-sequence swarm running") |
| `feedback` | Friction, lesson, or follow-up note (e.g. "validator jar stale — WSL ETIMEDOUT") |
| `tooling_note` | Local workflow/tool behaviour worth remembering (e.g. "mcp.json needs Windows paths not WSL paths") |
| `risk` | Unresolved concern (e.g. "http-sequence docker.volumes deserialization bug") |
| `artifact_ref` | Output reference — file, commit, PR |
| `plan_ref` | Link to a plan file or external plan |

Keep entries **concise and high-signal**. No transcript dumping — one entry per
meaningful fact, not one entry per message.

## Learnings — capture reusable lessons

Use `learning_capture` for lessons that apply beyond the current session and should
inform future work:

```
learning_capture {
  context_token: "<token>",
  summary: "<one-line lesson>",
  details: "<optional elaboration>",
  scope: "tool" | "env" | "data" | "workflow" | "test_strategy",
  recommended_action: "<what to do next time>",
  importance: "low" | "normal" | "high",
  tags: [...]
}
```

Scope guide:
- `tool` — PocketHive worker config, MCP tool behaviour, validator quirks
- `env` — stack, network, proxy, WSL, Windows path issues
- `workflow` — TDD cycle, deploy sequence, debugging approach
- `test_strategy` — how to verify a scenario works

Always call `learning_get_recent` at session start to check for relevant active learnings
before starting work on a feature or tool.

## Issues — track bugs and blockers

Use `issue_report` for bugs, platform defects, or blockers discovered during work:

```
issue_report {
  context_token: "<token>",
  title: "<short title>",
  summary: "<what is wrong>",
  details: "<optional — error messages, journal events, queue state>",
  severity: "low" | "normal" | "high" | "critical",
  tags: [...],
  github_issue_url: "<if a GitHub issue was also raised>"
}
```

Use `issue_add_event` to record workarounds, fixes, or verifications against existing issues.
Use `issue_list` at session start to check for open issues on the current branch/feature.

## Features — use the project vocabulary

Always tag entries with a `feature` from the project vocabulary when the work belongs
to a feature or work stream. Use `feature_list` to see available features.

Current features for this project:
- `smarter-onboarding` — SmartER onboarding scenario bundle work
- `hivemind-mcp-setup` — HiveMind MCP installation and init-dev scripts
- `pcs-auth` — PCS auth scenario bundles
- `bundle-authoring` — General bundle creation and validation
- `stack-debugging` — Swarm debugging, queue inspection, journal analysis

Add new features with `feature_add` when starting a new work stream.

## Rule checks — submit before session_end

Before calling `session_end`, submit rule checks for applicable rules using `rule_check_submit`.

Key rules for this project:

| rule_id | When to check |
|---|---|
| `validate-before-deploy` | Always — did you run bundle.validate before deploying? |
| `no-bee-id-without-topology` | When authoring scenarios — no `id:` on bees unless topology is declared |
| `sut-yaml-map-not-list` | When creating/editing sut.yaml — must be a plain map, not a list |
| `stepid-not-id-in-steps` | When authoring http-sequence steps — use `stepId` not `id` |
| `windows-paths-in-mcp-json` | When editing mcp.json — paths must be Windows format for npx/node |
| `plan-required` | When creating scenarios — always include a minimal `plan:` section |

## Context tokens vs session IDs

- Use `session_id` for `entry_append` and `rule_check_submit`
- Use `context_token` (from `context_open`) for `learning_capture`, `issue_report`,
  `learning_get_recent`, `issue_list`, and brief fetches
- A context token is lighter than a session — open one when you only need recall
  or capture tools without a full session

## What NOT to store

- No credentials, tokens, secrets, or PANs
- No raw conversation transcripts or chat logs
- No stack traces longer than ~10 lines — summarise instead
- No machine-specific absolute paths in `summary` or `details` — use repo-relative
  `repo_file` links instead
- No duplicate entries — check `entry_search` or `learning_search` before adding
  something that may already exist
