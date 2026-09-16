# Client loading and qualification

Use the same unpacked `pockethive-intake/` folder for every client. Its `SKILL.md`
loads references and assets as files; nested native skill invocation is not
required. The runtime prerequisite and CLI commands are defined in `SKILL.md`.

Native skill discovery is not proof that the templates were read, the validator
ran or an authenticated MCP connection works. Record the actual client/version
and observed support. Do not claim all clients are qualified by packaging a ZIP.

## Load the canonical files

Where the client supports repository skills, place this folder under its
documented `.agents/skills/` location. Install only one active copy of this skill
name for the workspace. For a client requiring explicit file context, supply
`SKILL.md` and the files it directs the agent to read from this same root.

For Amazon Q Developer IDE, a minimal project-rule bridge can contain:

```text
When PocketHive requirements intake is requested, read
.agents/skills/pockethive-intake/SKILL.md and follow its selected mode and
referenced files from that folder. Do not reconstruct templates or validators.
```

This checkout supplies a scoped `.amazonq/rules/pockethive-intake.md` bridge and
a `.github/copilot-instructions.md` pointer. Distributed copies use the snippet
above at the client's documented location; no global configuration is required.

For a Copilot surface using repository instructions, add the same short pointer
to its supported instruction file. These are snippets to apply when integration
is requested, not permission to overwrite existing configuration. Preserve other
instructions and the user's agent JSON. The bridge must not copy workflow rules,
template values or validators.

If the folder was installed elsewhere, point to that explicitly selected root.
Do not search home directories or silently switch to another installation after
a read failure. A missing file or disabled project rule is a visible limitation.

## Surface-specific limits

The following is a guidance snapshot dated **2026-09-16**, not a conformance
certificate. Verify the installed version before relying on a capability.

| Surface | Use and limit |
| --- | --- |
| Codex CLI / IDE | Repository skill discovery and file/tool execution must be checked with a real saved output and validator result. |
| Codex app / desktop | Use supported discovery or explicit file context. Do not assume every chat surface can access local files. |
| Copilot VS Code / CLI | Use the same repository skill; qualify referenced-file loading and CLI execution. |
| Copilot cloud agent | Retain drafts/questions in its workspace and conversation. Remote MCP resources/prompts and OAuth are not supported in the reviewed baseline; explicitly selected local-source intake can proceed. |
| Ordinary GitHub.com Chat | Explicit file context may support drafts. Native skill execution and local validator access are not established. |
| Copilot code review | Review supplied documents. Do not describe a review surface as completing an interactive intake interview. |
| Amazon Q Developer IDE | Use explicit canonical-file context and, when enabled, a thin project rule. Native `SKILL.md` discovery is not assumed. |
| Legacy Amazon Q CLI | Qualify its installed version and explicit file loading. Do not silently replace the requested product with Kiro. |

An unavailable validator leaves drafts unvalidated. An unavailable MCP source
blocks that source inspection; it does not authorise raw service calls, copied
credentials or a different source mode. In asynchronous clients, preserve
questions and continue independent work according to the shared workflow.

## Account-free checks

Without an account, exercise the packaged CLI with synthetic sources in an
explicitly selected local sandbox. Record this as package/workflow evidence;
it does not qualify the client model, project-rule application or conversation
resume. Record authentication failures from the actual client commands instead
of substituting another model and describing it as Amazon Q.

See the [recorded Amazon Q checks](../tests/AMAZON_Q_QUALIFICATION.md) for the
installed versions, sandbox results and remaining authentication gate.

## Qualification evidence

For each claimed client/version, retain evidence that it loaded the same package
revision, produced the four saved documents, preserved unanswered questions and
ran validation if supported. Exercise interruption/resume and a missing tool.
Distinguish native discovery, document drafting, CLI validation and MCP access.
Use the shared [conversation cases and rubric](evaluation.md) for repeatable
client trials; keep blocked prerequisites and unassessed capabilities explicit.

Vendor references are advisory and may change. They are not runtime dependencies:

- [Agent Skills format](https://agentskills.io/specification)
- [VS Code agent skills](https://code.visualstudio.com/docs/agent-customization/agent-skills)
- [Copilot supported skill surfaces](https://docs.github.com/en/copilot/concepts/agents/about-agent-skills)
- [Copilot cloud MCP limitations](https://docs.github.com/en/copilot/concepts/agents/cloud-agent/mcp-and-cloud-agent)
- [Amazon Q project rules](https://docs.aws.amazon.com/amazonq/latest/qdeveloper-ug/context-project-rules.html)
