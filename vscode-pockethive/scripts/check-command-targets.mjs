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
  envelope: { data: { enabled: true, context: { swarmStatus: "RUNNING", swarmHealth: "RUNNING" } } },
});
assert.equal(lifecycle.isExpectedLifecycleState("start", running), true);
assert.equal(lifecycle.formatLifecycleState(running), "RUNNING / enabled / health RUNNING");

const stopped = lifecycle.summarizeSwarmLifecycle({
  envelope: { data: { enabled: false, context: { swarmStatus: "STOPPED", swarmHealth: "RUNNING" } } },
});
assert.equal(lifecycle.isExpectedLifecycleState("stop", stopped), true);
assert.equal(lifecycle.isExpectedLifecycleState("start", stopped), false);
assert.equal(lifecycle.formatLifecycleState(stopped).includes("[object Object]"), false);
assert.equal(lifecycle.shouldWaitForStartReadiness(stopped), false);
assert.equal(lifecycle.shouldWaitForStartReadiness({ status: "READY", enabled: false }), true);

assert.equal(
  lifecycle.extractLifecycleCorrelationId({
    watch: { correlationId: "attempt-1" },
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
      data: { status: "Running" },
    },
    {
      timestamp: "2026-05-27T10:00:00.000Z",
      kind: "outcome",
      type: "swarm-start",
      correlationId: "attempt-1",
      data: {
        status: "NotReady",
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
assert.equal(rejectedOutcome?.status, "NotReady");
assert.equal(
  lifecycle.formatLifecycleOutcome(rejectedOutcome),
  "NotReady (initialized=true, ready=false, pendingConfigUpdates=true)"
);

assert.equal(
  lifecycle.formatReadyResult(lifecycle.summarizeReadyResult({
    ready: true,
    swarmStatus: "READY",
    totals: { desired: 4, healthy: 4, running: 0, enabled: 0 },
  })),
  "ready / status READY / desired 4, healthy 4, running 0, enabled 0"
);

console.log("PocketHive VS Code command target checks passed.");
