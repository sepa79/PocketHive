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
| Last verified PocketHive version | PocketHive `v0.15.35` |

Use the PocketHive application to manage scenario bundles and swarms. A
packaged installation serves the application at the environment root and its
version-matched documentation under `/docs/`.

![PocketHive home with task-based entry points](/img/guides/ui/home-overview.png)

**What to verify in this screen:** Home links to scenarios, runtime state,
diagnostics, and Help; the signed-in role still controls which routes and
actions are available.

## Choose a screen

| Task | Screen | Route or next evidence |
| --- | --- | --- |
| Learn without changing state | **Home** or **Help** | Open the bundled `/docs/` guides. |
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

1. In **Scenarios**, select a known bundle and require a successful validation
   result.
2. In **Hive**, select **New swarm**, choose that scenario and its SUT, then
   create the swarm. Find it in Hive and select **Details**; creation does not
   navigate there automatically.
3. Wait for `READY`. Require a fresh healthy **Snapshot** and use **Scenario**
   to match every planned role to a live runtime worker.
4. Start the swarm, wait for `RUNNING`, then require a fresh Snapshot and
   `enabled`/`live` worker matches. Use topology to relate those workers to the
   scenario graph.
5. Stop, verify `STOPPED` with disabled/live workers, then remove. Require the
   correlated `Removed` Journal outcome before confirming disappearance from
   Hive.

The [swarm lifecycle guide](../operators/swarm-lifecycle.md) owns the exact
completion rules. The
[local quickstart](../onboarding/quickstart-15min.md) provides a safe runnable
example.

![Scenario-defined workers and logical work path](/img/guides/operators/swarm-topology.png)

**What to verify in this screen:** Worker scopes and the three displayed edges
match the selected scenario. The graph explains intended routing; it does not
prove live traffic or an external SUT result.

## Read Hive details

- **Snapshot** shows aggregate status, health, runtime identity, and freshness.
  Treat an old snapshot as stale evidence.
- **Scenario** matches planned roles to runtime workers and shows explicit
  `enabled`/`disabled` and `live`/`stale` chips. A disabled worker can currently
  show a red eye at `READY` or `STOPPED`; use the chips and swarm health.
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
| An expected action is hidden or disabled | Check lifecycle state and the user's global, folder, or bundle grant. |
| A worker eye is red at `READY` or `STOPPED` | Read the explicit enabled/disabled and live/stale chips with swarm health. |
| The UI and guide appear mismatched | Open **Docs** or **Help** in that installation for version-matched content. |

For unresolved symptoms, use the
[canonical troubleshooting route](../operators/observability-troubleshooting.md#troubleshooting).

## Next step

- [Run the local quickstart](../onboarding/quickstart-15min.md).
- [Understand swarm lifecycle](../operators/swarm-lifecycle.md).
- [Create your first scenario](../onboarding/first-scenario.md).
