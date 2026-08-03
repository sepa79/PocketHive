---
title: Verify and troubleshoot a run
pagination_label: Verify and troubleshoot
---

# Verify and troubleshoot a run

| Reader context | Details |
| --- | --- |
| Audience | Customers and operators investigating a swarm or application symptom |
| Prerequisites | Access to Hive and Journal; open infrastructure tools only when evidence identifies that layer |
| Expected outcome | Isolate the smallest affected layer, recover safely, and capture evidence suitable for escalation |
| Last verified PocketHive version | PocketHive `v0.15.35` |

Start with Hive and Journal. Move to metrics or infrastructure only when those
customer-facing layers identify a narrower question. The canonical meaning of
acceptance, dispatch, and convergence is in
[system workflows](../concepts/system-workflows.md#read-the-evidence-in-layers).

## Evidence ladder

1. **Hive state and action feedback** — what the application accepted and now
   projects.
2. **Snapshot freshness** — `receivedAt`, `staleAfterSec`, controller state,
   and swarm health.
3. **Scenario runtime matches** — each planned role matched to a live/stale,
   enabled/disabled worker.
4. **Journal** — correlated requests, signals, outcomes, and alerts.
5. **Grafana** — rates, errors, latency, and longer trends.
6. **Focused diagnostics** — Proxy, Buzz, Connectivity, Debug Tap, RabbitMQ,
   Redis, or WireMock only for the layer already implicated.

Stop when one layer gives an actionable cause. Broader raw dumps often add
sensitive data without improving the diagnosis.

## Read Hive and Journal together

In **Snapshot**, require a fresh aggregate and compare health with lifecycle
state; they are separate fields. In **Scenario**, verify every planned role and
use the explicit worker labels. A disabled worker may show a red eye at `READY`
or `STOPPED`; color alone is not failure evidence.

![Correlated lifecycle entries in the PocketHive Journal](/img/guides/operators/swarm-journal.png)

**What to verify in this screen:** the selected swarm's create, template, and
start entries can be followed as a sequence. Match scope, time, signal, and
outcome; the visible rows are an example, not proof of current Snapshot
freshness or complete worker convergence.

Open Journal from the selected swarm. Find the originating request, routed
signal, correlated outcome, and any alert. Keep raw payloads collapsed unless
structured fields are insufficient; they may expose configuration, endpoints,
identifiers, and workload content.

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
| Create is pending, absent, or only projected `READY` | Action feedback, create/template/plan outcomes, then fresh Snapshot and role matches | Correct the named scenario, image, SUT, volume, or readiness cause while stopped; do not Start before roles converge. |
| Start is pending or `RUNNING` workers disagree | Fresh Snapshot and Scenario chips, then start outcome and worker evidence | Wait through `NotReady`; diagnose the missing, stale, or disabled worker instead of repeating Start. |
| Stop is pending or `STOPPED` workers remain enabled | Fresh aggregate, stop outcome, and final worker status | Preserve the swarm and diagnose the worker; Remove is not a stop shortcut. |
| Remove is pending or Hive loses the swarm without `Removed` | Correlated Journal removal history | Preserve ID and timestamps and use governed reconciliation before reusing the ID. |
| Planned role has no runtime worker | Scenario role match, validation, launch evidence | Fix the named contract or launch problem; recreate only when cleanup is certain. |
| Work does not reach the SUT | Worker status and topology, then Network/Proxy/SUT or a focused tap | Correct the selected binding or SUT condition and verify the business result in the SUT. |
| UI data is old | Snapshot timestamp and Connectivity | Restore the supported connection or status cadence; refresh alone is not recovery proof. |
| Action is missing or disabled | Lifecycle state and permission scope | Use an approved grant or ask an administrator; Help links do not grant access. |

Do not repeat asynchronous actions without identifying the current outcome, or
mutate broker topology, Redis data, shared proxy profiles, or mock state as a
shortcut. Escalate with release, neutral swarm ID, expected/actual state,
timestamps, and the smallest structured error. Remove tokens, usernames,
hostnames, customer names, payloads, and raw configuration unless approved and
essential. Exact metric and log fields live in the
[observability reference](../../observability.md).

## Next step

- Revisit [swarm lifecycle](swarm-lifecycle.md) for action-specific evidence.
- Use [system workflows](../concepts/system-workflows.md) for ownership and routing.
- Check the [application guide](../ui/application-guide.md) for screen and access boundaries.
