---
title: Choose your PocketHive path
pagination_label: Choose your path
---

# Choose your PocketHive path

| Reader context | Details |
| --- | --- |
| Audience | Customers, evaluators, scenario authors, and operators |
| Prerequisites | None; choose a task before installing or changing anything |
| Expected outcome | Reach the shortest guide for your goal with current limitations visible |
| Candidate source tested | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

New to PocketHive? Read the
[interactive overview](../presentation/interactive-pockethive-overview.mdx).
Use the [glossary](../../GLOSSARY.md) whenever a shared term is unfamiliar.

## Choose your path

| Your goal | Start here | You are finished when... |
| --- | --- | --- |
| Evaluate on one development machine | [Local source quickstart](quickstart-15min.md) | The build and startup gates are recorded; run a demo swarm only if Connectivity is OK. |
| Learn the application | [Application guide](../ui/application-guide.md) | You know where to create, observe, and troubleshoot. |
| Author a scenario | [Your first scenario](first-scenario.md) | One guarded bundle is validated and deployed; the current candidate stops at Connectivity before runtime mutation, then removes the deployed copy. |
| Operate a swarm | [Swarm lifecycle](../operators/swarm-lifecycle.md) | Learn the completion evidence; the current candidate UI remains gated before mutation. |
| Investigate a symptom | [Observability and troubleshooting](../operators/observability-troubleshooting.md) | You have isolated the affected layer and captured safe evidence. |
| Choose local, Compose, or HiveForge deployment | [Deployment paths](../operators/deployment.md) | You understand the current support boundary before running commands. |

At the tested lifecycle rewrite source, the source build is available but the
full UI lifecycle is still a **candidate**: the VM run stopped at the required
Connectivity gate. The Compose package and HiveForge status can change
independently; the [deployment guide](../operators/deployment.md) is the
canonical source for all three paths.

## Troubleshooting

If you are unsure which path applies, start with the
[application guide](../ui/application-guide.md) without changing state. For an
unfamiliar term, use the [glossary](../../GLOSSARY.md) instead of guessing from
a lower-level service document.

## Next step

Choose one route above. For a first local evaluation, continue with the
[local source quickstart](quickstart-15min.md).
