# GitHub Issue Rules — sepa79/PocketHive

These rules apply whenever creating or updating issues in the `sepa79/PocketHive` repository
via the `github` MCP server. Follow them every time without exception.

## Before creating an issue

1. **Search first** — use `list_issues` or `search_issues` to check if the issue already exists.
   If a matching open issue exists, add a comment instead of creating a duplicate.
2. **Confirm the repo** — always target `sepa79/PocketHive`, never the bundles repo.

## Issue types and title prefixes

Every issue title MUST start with one of these prefixes:

| Prefix | When to use |
|---|---|
| `bug:` | Something that works incorrectly at runtime |
| `validator:` | Discrepancy between the offline validator and the runtime |
| `feat:` | New capability or worker feature request |
| `docs:` | Missing, incorrect, or outdated documentation |
| `chore:` | Build, tooling, dependency, or CI change |
| `perf:` | Performance regression or improvement opportunity |
| `question:` | Clarification needed before work can start |

Examples:
- `bug: processor drops WorkItem when TCP connection times out`
- `validator: protocol field rejected by scenario-templating-check but required at runtime`
- `feat: add popDirection config to Redis generator`
- `docs: AUTH-USER-GUIDE missing example for hmac-signature strategy`

## Body structure

Every issue body MUST follow this template. Omit sections that genuinely do not apply,
but never omit **Description** or **Expected / Actual** for bugs.

```markdown
## Description
<!-- One paragraph: what is the problem or request? -->

## Steps to reproduce
<!-- For bugs: minimal steps to trigger the issue. -->
<!-- For validator discrepancies: the template/scenario snippet that triggers it. -->

## Expected behaviour
<!-- What should happen. -->

## Actual behaviour
<!-- What actually happens. Include error messages verbatim. -->

## Environment
<!-- PocketHive version/branch, OS, Java version if relevant. -->
- Branch/commit:
- OS:

## Suggested fix
<!-- Optional: your hypothesis or proposed approach. -->

## References
<!-- Links to relevant docs, PRs, or bundle files. -->
```

## Validator discrepancy issues

When raising an issue about a validator/runtime mismatch (prefix `validator:`), always include:

1. The **exact error message** from the validator output
2. The **template snippet** that triggers it
3. The **runtime class** that accepts the field (e.g. `io.pockethive.requesttemplates.HttpTemplateDefinition`)
4. The **validator class** that rejects it (e.g. `io.pockethive.httpbuilder.HttpTemplateDefinition`)
5. The **workaround** currently in use (e.g. "include the field anyway, accept the warning")

Example body:
```markdown
## Description
The `protocol` field is required by the runtime request-builder but rejected by the
offline validator (`scenario-templating-check`).

## Steps to reproduce
Add `protocol: HTTP` to any request-builder template and run `bundle.validate`.

## Expected behaviour
Validator accepts `protocol` — it is a valid field in `HttpTemplateDefinition`.

## Actual behaviour
```
FAIL: Unrecognized field "protocol" (class io.pockethive.httpbuilder.HttpTemplateDefinition)
```

## Environment
- Branch/commit: main / ba9f48fd
- Validator class: `io.pockethive.httpbuilder.HttpTemplateDefinition` (worker-sdk)
- Runtime class: `io.pockethive.requesttemplates.HttpTemplateDefinition` (common/request-templates)

## Suggested fix
Update `scenario-templating-check/pom.xml` to depend on `request-templates` instead of
`worker-sdk`, and update the import in `ScenarioTemplateValidator.java`.

## References
- `common/request-templates/src/.../HttpTemplateDefinition.java`
- `tools/scenario-templating-check/src/.../ScenarioTemplateValidator.java`
```

## Bug issues

For runtime bugs found during scenario testing, always include:

1. The **swarm ID** and **bundle name** where the bug was observed
2. Relevant **docker logs** snippet (from `debug.docker-logs`)
3. Relevant **journal events** (from `debug.journal`)
4. The **queue state** at the time (from `debug.queues`)
5. The **WorkItem payload** if available (from `debug.tap`)

## Labels

Apply labels when creating issues if the repo has them. Common labels for PocketHive:

| Label | Use for |
|---|---|
| `bug` | Runtime defects |
| `validator` | Validator/runtime discrepancies |
| `enhancement` | Feature requests |
| `documentation` | Doc issues |
| `good first issue` | Well-scoped, low-risk changes |

## What NOT to include

- No credentials, tokens, or secrets — ever
- No real PANs, account numbers, or customer data from test datasets
- No internal hostnames or IP addresses
- No stack traces longer than ~20 lines — trim and add `...` if needed
- No `scenario.yaml` content that contains real `clientSecret` values — redact to `<redacted>`
