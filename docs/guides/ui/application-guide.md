---
title: Find your way around PocketHive
pagination_label: Explore the application
---

# Find your way around PocketHive

| Reader context | Details |
| --- | --- |
| Audience | Customers, evaluators, scenario authors, operators, and auth administrators |
| Prerequisites | Access to a PocketHive environment and grants for the intended task |
| Expected outcome | Choose the correct screen and recognize the evidence that completes a customer workflow |
| Candidate source tested | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

Use the PocketHive application to manage scenario bundles and swarms. A
packaged installation serves the application at the environment root and its
version-matched documentation under `/docs/`.

Home links to scenarios, runtime state, and Connectivity when the signed-in
role permits them. Use the top-bar **Docs** link for the documentation bundled
with the running source; the current **Help** page is still a placeholder with
repository links.

## Choose a screen

| Task | Screen | Route or next evidence |
| --- | --- | --- |
| Learn without changing state | Top-bar **Docs** | Open the bundled `/docs/` guides. **Help** is not yet the customer documentation hub. |
| Browse, edit, or validate a bundle | **Scenarios** | Review the structured validation result. |
| Create or control a swarm | **Hive** | Continue through Snapshot, Scenario, topology, and Journal. |
| Understand a swarm graph | **Topology** | Open `/hive/{swarmId}/view`; `scenario.yaml` remains canonical. |
| Correlate an asynchronous action | **Journal** | Filter to the selected swarm and time window. |
| Inspect a proxied SUT binding | **Proxy** | Use only when the scenario selects a managed proxy profile. |
| Check application connectivity | **Connectivity** | Use `/health`; this does not prove worker readiness. |
| Manage users and grants | **Users** | Requires auth `ADMIN`. |

**Buzz** is a short live control-plane view for advanced diagnosis. It is not
part of the normal create-to-remove workflow.

## Complete a customer workflow

:::caution Current candidate preflight

Open **Connectivity** before step 1 and continue only when every required gate
reports OK. At tested source `0524165e`, Connectivity reports a
`swarm-lifecycle.schema.json#/$defs/RuntimeMetadata` resolution error. Preserve
that exact error and stop before **New swarm**, **Create**, or **Start**. The
steps below remain the completion criteria for a future corrected candidate;
they were not completed through this candidate UI.

:::

1. In **Scenarios**, select a known bundle and require a successful validation
   result.
2. In **Hive**, select **New swarm**, choose that scenario and its SUT, then
   create the swarm. Find it in Hive and select **Details**; creation does not
   navigate there automatically.
3. In **Journal**, require the correlated CREATE public outcome with
   `data.status=Succeeded`. Then require a fresh **Snapshot** with controller
   state `READY` and workload state `STOPPED`, and use **Scenario** to match
   every planned role to a live runtime worker.
4. Start the swarm and require its successful terminal feedback plus a fresh
   workload state `RUNNING` and `enabled`/`live` worker matches. Use topology to
   relate those workers to the scenario graph.
5. Stop and require successful terminal feedback plus fresh workload state
   `STOPPED` with disabled/live workers. Then remove and require the correlated
   Orchestrator outcome with `data.status=Succeeded` before confirming absence
   from a fresh Hive list. `REMOVED` is not a persistent swarm state.

The correlated removal outcome remains the completion rule. At the tested
candidate source, MCP can confirm fresh-list absence but loses
`debug_journal` access after removal, so it cannot independently meet that
rule. Preserve the removal response, ID, and timestamps when the outcome is
missing; do not replace it with disappearance alone.

The [swarm lifecycle guide](../operators/swarm-lifecycle.md) owns the exact
completion rules. The
[local quickstart](../onboarding/quickstart-15min.md) provides a safe runnable
example.

In the topology screen, verify that worker scopes and displayed edges match the
selected scenario. The graph explains intended routing; it does not prove live
traffic or an external SUT result.

## Read Hive details

- **Snapshot** shows the independent controller/workload observations, health,
  runtime identity, and freshness.
  Treat an old snapshot as stale evidence.
- **Scenario** matches planned roles to runtime workers and shows explicit
  `enabled`/`disabled` and `live`/`stale` chips. A disabled worker can currently
  show a red eye while controller state is `READY` and workload state is
  `STOPPED`; use the explicit chips and independent health field.
- **Network** shows the selected direct or managed-proxy SUT binding.
- **Inspector** exposes runtime resources for diagnosis; it is not the source
  of truth for scenario authoring.

## Access and tool boundaries

| Permission | Effect |
| --- | --- |
| `VIEW` | Browse permitted bundles and runtime state. |
| `RUN` | Perform run-level actions in the permitted scope. |
| `ALL` | Manage permitted content, configuration, and lifecycle actions. |
| Auth `ADMIN` | Manage auth-service users and grants. |

Grants can be global or limited to a folder or bundle. **Docs** opens the
documentation bundled with this release; **Grafana** is the supported metrics
application. RabbitMQ, Redis, WireMock, Debug Tap, and raw Journal payloads are
focused diagnostics that can expose environment or workload data. Start in
Hive and Journal, then follow the
[observability guide](../operators/observability-troubleshooting.md).

## Troubleshooting

| Symptom | First action |
| --- | --- |
| Create closed without opening the swarm | Find the new swarm in **Hive** and select **Details**. |
| An accepted action remains pending | Check Snapshot freshness, Scenario worker chips, then the filtered Journal. |
| An expected action is hidden or disabled | Check the independent lifecycle axes, active operation, and the user's global, folder, or bundle grant. |
| A worker eye is red while the workload is stopped | Read controller state, workload state, health, and the explicit enabled/disabled and live/stale chips separately. |
| Connectivity reports a schema-load or unresolved-reference error | Stop lifecycle work, preserve the exact error and source revision, and report the candidate blocker. `/healthz` alone is not a substitute. |
| The UI and guide appear mismatched | Open top-bar **Docs** in that installation for version-matched content; do not assume the current Help repository links match the running source. |

For unresolved symptoms, use the
[canonical troubleshooting route](../operators/observability-troubleshooting.md#troubleshooting).

## Next step

- [Run the local quickstart](../onboarding/quickstart-15min.md).
- [Understand swarm lifecycle](../operators/swarm-lifecycle.md).
- [Create your first scenario](../onboarding/first-scenario.md).
