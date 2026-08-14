# Authoring and test tools

| Reader context | Details |
| --- | --- |
| Audience | PocketHive customers, scenario authors, operators, and support engineers |
| Prerequisites | Know the environment, identity, and task before opening a secondary tool |
| Expected outcome | Choose the narrowest supported surface and respect its evidence and mutation boundary |
| Last verified PocketHive version | PocketHive `v0.15.35` |

The PocketHive application and its bundled documentation are the primary
customer surfaces. The tools below extend a focused task; an available icon or
endpoint does not make a tool a supported fallback.

## Current surface map

| Surface | Status in `v0.15.35` | Use it for | Boundary |
| --- | --- | --- | --- |
| PocketHive MCP | **Current, repository-local** | Guarded scenario authoring, validation, lifecycle, evidence, and governed cleanup for agents or IDE users | Includes file, remote, diagnostic, and destructive tools; read each mutation label. See [MCP current versus target](pockethive-mcp-and-bundles.md#current-versus-target). |
| VS Code extension | **Partial; target work remains** | Current scenario editor/preview and MCP-backed commands | Verify installed source and command coverage; rich `ui-v2` panels remain target. See the [project map](../../PROJECT_MAP.md#customer-and-operator-surfaces). |
| TCP Mock UI | **Current supporting app** | TCP mock definitions and captured test traffic | Not the main PocketHive workflow; definitions and traffic can contain payload data. |
| Grafana | **Current operator tool** | Rates, errors, latency, and historical trends | A dashboard does not prove lifecycle completion. |
| RabbitMQ Management | **Current advanced diagnostic** | A queue, exchange, binding, or transport question already identified by product evidence | Inspect only; PocketHive owns topology and message views can expose payloads. |
| Redis Commander | **Current advanced diagnostic** | Redis-backed scenario dataset state | Values are mutable and may contain customer-derived data. |
| WireMock | **Current supporting diagnostic** | Local HTTP mappings and received requests | Mutable local evidence does not prove a remote SUT. |
| Orchestrator debug CLI | **Current maintainer-only diagnostic** | Lower-level local development checks | Not the customer MCP or official-ingress proof; inspect the exact command in `tools/mcp-orchestrator-debug/README.md`. |

Choose **Hive** for interactive swarm control, **Scenarios** for interactive
bundle work, and [PocketHive MCP](pockethive-mcp-and-bundles.md) for automation.
For a failure, start with **Journal**, then follow the
[evidence ladder](../operators/observability-troubleshooting.md#evidence-ladder)
before opening an infrastructure surface. The
[project map](../../PROJECT_MAP.md#customer-and-operator-surfaces) identifies
the owning application or code area.

## Safety boundary

Before using a secondary surface, confirm environment, identity, target, and
time window. Start read-only; identify whether the next action changes files,
runtime, mocks, broker state, datasets, or taps. Preserve structured PocketHive
evidence before cleanup and sanitize tokens, identities, endpoints, payloads,
correlations, and raw configuration before sharing.

Do not switch to a direct service port or higher privilege because a supported
surface failed. Return to Journal and the documented recovery path.

## Troubleshooting

If a surface cannot answer its table question, return to
[observability and troubleshooting](../operators/observability-troubleshooting.md).
For MCP setup, unavailable tools, or mutation recovery, use the
[MCP and bundles guide](pockethive-mcp-and-bundles.md). Do not mutate RabbitMQ,
Redis, WireMock, or shared proxy state to manufacture evidence.

## Next step

- Use the [application guide](../ui/application-guide.md) for customer routes.
- Use [PocketHive MCP and bundles](pockethive-mcp-and-bundles.md) for commands and safety labels.
- Use the [screenshot evidence manifest](../ui/screenshot-evidence.md) when documenting UI state.
