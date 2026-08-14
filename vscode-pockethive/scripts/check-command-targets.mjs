#!/usr/bin/env node

import assert from "node:assert/strict";

const targets = await import("../out/targetResolver.js");
const lifecycle = await import("../out/swarmLifecycle.js");

assert.equal(
  targets.resolveSwarmId({
    swarm: { id: "real-swarm" },
    label: { label: "wrong-label" },
    command: { arguments: ["wrong-command"] },
  }),
  "real-swarm"
);

assert.equal(
  targets.resolveSwarmId({
    id: "tree-item-swarm",
    label: { label: "tree-item-swarm" },
  }),
  "tree-item-swarm"
);

assert.equal(
  targets.resolveSwarmId({
    label: { label: "label-swarm" },
  }),
  "label-swarm"
);

assert.equal(targets.resolveSwarmId("[object Object]"), undefined);
assert.equal(
  targets.resolveBundleName({ bundle: { name: "bundle-a" }, label: { label: "wrong" } }),
  "bundle-a"
);
assert.equal(
  targets.resolveScenarioId({ command: { arguments: [{ scenario: { id: "scenario-a" } }] } }),
  "scenario-a"
);

const running = lifecycle.summarizeSwarmLifecycle({
  controllerState: "READY", workloadState: "RUNNING", health: "HEALTHY", observationStale: false,
});
assert.equal(lifecycle.isExpectedLifecycleState("start", running), true);
assert.equal(lifecycle.formatLifecycleState(running), "controller READY / workload RUNNING / health HEALTHY");

const stopped = lifecycle.summarizeSwarmLifecycle({
  controllerState: "READY", workloadState: "STOPPED", health: "HEALTHY", observationStale: false,
});
assert.equal(lifecycle.isExpectedLifecycleState("stop", stopped), true);
assert.equal(lifecycle.isExpectedLifecycleState("start", stopped), false);
assert.equal(lifecycle.formatLifecycleState(stopped).includes("[object Object]"), false);
assert.equal(lifecycle.shouldWaitForStartReadiness(stopped), false);
assert.equal(lifecycle.shouldWaitForStartReadiness(stopped), false);

assert.equal(
  lifecycle.extractLifecycleCorrelationId({
    correlationId: "attempt-1",
  }),
  "attempt-1"
);

const rejectedOutcome = lifecycle.findLifecycleOutcome({
  items: [
    {
      timestamp: "2026-05-27T09:00:00.000Z",
      kind: "outcome",
      type: "swarm-start",
      correlationId: "old-attempt",
      data: { status: "Succeeded" },
    },
    {
      timestamp: "2026-05-27T10:00:00.000Z",
      kind: "outcome",
      type: "swarm-start",
      correlationId: "attempt-1",
      data: {
        status: "Rejected",
        context: {
          initialized: true,
          ready: false,
          pendingConfigUpdates: true,
          ignored: "not shown",
        },
      },
    },
  ],
}, "start", "attempt-1");
assert.equal(rejectedOutcome?.status, "Rejected");
assert.equal(
  lifecycle.formatLifecycleOutcome(rejectedOutcome),
  "Rejected (initialized=true, ready=false, pendingConfigUpdates=true)"
);
assert.throws(() => lifecycle.findLifecycleOutcome({
  items: [{ kind: "outcome", type: "swarm-stop", data: { status: "TIMEDOUT" } }],
}, "stop"), /TerminalStatus/);

assert.equal(
  lifecycle.formatReadyResult(lifecycle.summarizeReadyResult({
    ready: true,
    controllerState: "READY",
    workloadState: "STOPPED",
  })),
  "ready / controller READY / workload STOPPED"
);

console.log("PocketHive VS Code command target checks passed.");
