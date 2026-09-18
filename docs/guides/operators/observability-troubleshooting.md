---
title: Observe and troubleshoot PocketHive
pagination_label: Troubleshoot PocketHive
---

# Observe and troubleshoot PocketHive

| Reader context | Details |
| --- | --- |
| Audience | Customers and operators investigating PocketHive behavior |
| Prerequisites | Access to Hive, Journal, and the affected swarm |
| Expected outcome | Isolate one failing evidence layer and retain enough safe context for recovery or escalation |
| Candidate source tested | `rewrite/lifecycle-control-plane` at `0524165e` (unreleased) |

Start with Hive and Journal. Move to metrics or infrastructure only when those
customer-facing layers identify a narrower question. The canonical meaning of
acceptance, executor result, public outcome, and current observation is in
[system workflows](../concepts/system-workflows.md#read-the-evidence-in-layers).

## Evidence ladder

1. **Hive action feedback and operation** — what the application accepted and
   the terminal state it has observed.
2. **Snapshot freshness** — `receivedAt`, `staleAfterSec`, controller state,
   workload state, health, and runtime-resource state.
3. **Scenario runtime matches** — each planned role matched to a live/stale,
   enabled/disabled worker.
4. **Journal** — correlated requests, signals, executor evidence, public
   outcomes, and alerts.
5. **Grafana** — rates, errors, latency, and longer trends.
6. **Focused diagnostics** — Proxy, Buzz, Connectivity, Debug Tap, RabbitMQ,
   Redis, or WireMock only for the layer already implicated.

Stop when one layer gives an actionable cause. Broader raw dumps often add
sensitive data without improving the diagnosis.

## Read Hive and Journal together

In **Snapshot**, require a fresh aggregate and read controller state, workload
state, health, and runtime-resource state independently. In **Scenario**, verify
every planned role and use the explicit worker labels. A disabled worker may
show a red eye while controller state is `READY` and workload state is
`STOPPED`; color alone is not failure evidence.

Open Journal from the selected swarm. Find the originating request, routed
signal, executor result when applicable, Orchestrator outcome, and any alert.
Keep raw payloads collapsed unless structured fields are insufficient; they may
expose configuration, endpoints, identifiers, and workload content.

At the tested candidate source, the MCP `debug_journal` tool does not retain
the public terminal outcome for any phase: Create has no correlated entry;
Start and Stop expose a routed signal and/or controller-internal result; and
the tool returns `404` after registry removal. A signal or internal result may
be paired with fresh runtime state for diagnosis, but it is not the
Orchestrator outcome. This MCP evidence gap is a product limitation to preserve
and escalate, not a reason to invent completion evidence.

## Secondary tools

| Tool group | Use when | Boundary |
| --- | --- | --- |
| Grafana | You need historical rates, errors, or latency. | A dashboard panel is not lifecycle-completion proof. |
| Proxy, Buzz, Connectivity, Debug Tap | The product evidence names network, recent notices, connectivity, or one work connection. | Use a short, focused observation; do not leave taps or shared profile changes behind. |
| RabbitMQ Management | Queue, binding, or control transport is the suspected layer. | Inspect only; PocketHive owns topology and message views can expose payloads. |
| Redis Commander or WireMock | A Redis dataset state or local HTTP stub is implicated. | Do not edit data or mappings during diagnosis; local stub evidence does not prove a remote SUT. |

## Troubleshooting

| Symptom | First evidence | Recovery or escalation |
| --- | --- | --- |
| Create is pending, absent, or only shows controller `READY` | CREATE operation/outcome, startup digest, fresh Snapshot, and complete worker set | Correct the named scenario, artifact, image, SUT, volume, or bootstrap cause; do not Start before CREATE succeeds and roles converge. |
| Start is pending/rejected or workload/worker observations disagree | Operation/public outcome, structured rejection or non-converged-worker context, then fresh Snapshot and Scenario chips | Diagnose the missing, stale, or disabled worker instead of repeating Start against unchanged evidence. |
| Stop is pending or workload is `STOPPED` while workers remain enabled | Operation/public outcome, fresh aggregate, and final worker status | Preserve the swarm and diagnose the named worker before another mutation. A deliberate Stop-first workflow is easier to inspect, although Remove performs its own disablement. |
| Remove is pending, failed, timed out, or Hive loses the swarm without a `Succeeded` outcome | Correlated operation/Journal history and canonical absence checks | Preserve ID, correlation, and timestamps; do not reuse the ID or infer successful cleanup from disappearance. |
| Planned role has no runtime worker | Scenario role match, validation, launch evidence | Fix the named contract or launch problem; recreate only when cleanup is certain. |
| Work does not reach the SUT | Worker status and topology, then Network/Proxy/SUT or a focused tap | Correct the selected binding or SUT condition and verify the business result in the SUT. |
| UI data is old | Snapshot timestamp and Connectivity | Restore the supported connection or status cadence; refresh alone is not recovery proof. |
| Action is missing or disabled | Independent lifecycle axes, active operation, and permission scope | Use an approved grant or ask an administrator; Help links do not grant access. |

Do not repeat asynchronous actions without identifying the current operation
and outcome, or mutate broker topology, Redis data, shared proxy profiles, or
mock state as a shortcut. Escalate with source revision, neutral swarm ID,
expected/actual axes, timestamps, and the smallest structured error. Remove
tokens, usernames, hostnames, customer names, payloads, and raw configuration
unless approved and essential. Exact metric and log fields live in the
[observability reference](../../observability.md).

## Next step

- Revisit [swarm lifecycle](swarm-lifecycle.md) for action-specific evidence.
- Use [system workflows](../concepts/system-workflows.md) for ownership and routing.
- Check the [application guide](../ui/application-guide.md) for screen and access boundaries.
