import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  buildControlPlaneStatus,
  buildCleanupPlan,
  buildManifestValidation,
  buildRabbitTopologySnapshot,
  buildRuntimeDiff,
  buildRuntimeWorkerList,
  buildWorkerLogTarget,
  buildWorkerInspection,
  buildWorkerVersion,
  normalizeTailLines,
  parseImageReference,
  registerRuntimeTools,
  redactLogText,
  validateCleanupExecution,
  validateRuntimeDebugCapabilities
} from "./runtime-tools.mjs";

test("runtime cleanup candidates require stopped PocketHive-owned resources", () => {
  const plan = buildCleanupPlan(
    [
      runtimeResource("c1", { state: "exited" }),
      runtimeResource("c2", { state: "running" }),
      {
        runtimeId: "foreign",
        runtimeType: "container",
        state: "exited",
        labels: { "pockethive.swarmId": "swarm-1" }
      }
    ],
    { swarmId: "swarm-1", runId: "run-1" }
  );

  assert.deepEqual(plan.candidates.map((candidate) => candidate.runtimeId), ["c1"]);
  assert.deepEqual(plan.blocked.map((blocked) => blocked.runtimeId), ["c2", "foreign"]);
  assert.match(plan.candidateSetHash, /^sha256:[a-f0-9]{64}$/);
});

test("runtime cleanup partitions same-swarm incomplete labels before run filtering", () => {
  const plan = buildCleanupPlan(
    [
      runtimeResource("old-run", { labels: { "pockethive.runId": "run-old" } }),
      runtimeResource("same-run", { labels: { "pockethive.runId": "run-1" } }),
      runtimeResource("partial", {
        labels: {
          "pockethive.runId": "",
          "pockethive.role": ""
        }
      }),
      {
        runtimeId: "unrelated-unmanaged",
        runtimeType: "container",
        state: "exited",
        labels: {}
      },
      runtimeResource("other-swarm", { labels: { "pockethive.swarmId": "swarm-2" } })
    ],
    { swarmId: "swarm-1", runId: "run-1" }
  );

  assert.deepEqual(plan.candidates.map((candidate) => candidate.runtimeId), ["same-run"]);
  assert.deepEqual(plan.blocked.map((blocked) => blocked.runtimeId), ["partial"]);
  assert.match(plan.blocked[0].reason, /missing required labels/);
});

test("runtime cleanup classifies running or broad cleanup as high risk", () => {
  const runningPlan = buildCleanupPlan(
    [runtimeResource("c1", { state: "running" })],
    { swarmId: "swarm-1", runId: "run-1", includeRunning: true }
  );
  const missingRunPlan = buildCleanupPlan(
    [runtimeResource("c1")],
    { swarmId: "swarm-1" }
  );
  const emptyPlan = buildCleanupPlan(
    [],
    { swarmId: "swarm-1", runId: "run-1" }
  );

  assert.equal(runningPlan.executionRisk, "high");
  assert.equal(missingRunPlan.executionRisk, "high");
  assert.equal(emptyPlan.executionRisk, "none");
});

test("cleanup execution validates current candidates and idempotency", () => {
  const plan = buildCleanupPlan(
    [runtimeResource("c1"), runtimeResource("c2")],
    { swarmId: "swarm-1", runId: "run-1" }
  );
  assert.equal(plan.executionRisk, "standard");

  assert.throws(() => validateCleanupExecution({
    idempotencyKey: "idem-1",
    candidateSetHash: "sha256:stale",
    candidateIds: ["c1"],
    actor: "alice"
  }, plan, []), /candidateSetHash/);

  assert.throws(() => validateCleanupExecution({
    idempotencyKey: "idem-1",
    candidateSetHash: plan.candidateSetHash,
    candidateIds: ["missing"],
    actor: "alice"
  }, plan, []), /no longer in the current cleanup plan/);

  const first = validateCleanupExecution({
    idempotencyKey: "idem-1",
    candidateSetHash: plan.candidateSetHash,
    candidateIds: ["c1"],
    actor: "alice"
  }, plan, []);
  assert.equal(first.idempotent, false);
  assert.deepEqual(first.candidates.map((candidate) => candidate.runtimeId), ["c1"]);

  const repeat = validateCleanupExecution({
    idempotencyKey: "idem-1",
    candidateSetHash: plan.candidateSetHash,
    candidateIds: ["c1"],
    actor: "alice"
  }, plan, [{
    idempotencyKey: "idem-1",
    candidateSetHash: plan.candidateSetHash,
    candidateIds: ["c1"],
    actor: "alice"
  }]);
  assert.equal(repeat.idempotent, true);

  const sameKeyDifferentCandidate = validateCleanupExecution({
    idempotencyKey: "idem-1",
    candidateSetHash: plan.candidateSetHash,
    candidateIds: ["c2"],
    actor: "alice"
  }, plan, [{
    idempotencyKey: "idem-1",
    candidateSetHash: plan.candidateSetHash,
    candidateIds: ["c1"],
    actor: "alice"
  }]);
  assert.equal(sameKeyDifferentCandidate.idempotent, false);
  assert.deepEqual(sameKeyDifferentCandidate.candidates.map((candidate) => candidate.runtimeId), ["c2"]);
});

test("worker log target selection is label-gated and rejects ambiguity", () => {
  const target = buildWorkerLogTarget(
    [
      runtimeResource("manager-1", {
        labels: {
          "pockethive.resourceKind": "manager",
          "pockethive.role": "swarm-controller"
        }
      }),
      runtimeResource("worker-1", { labels: { "pockethive.instance": "processor-1" } })
    ],
    { swarmId: "swarm-1", runId: "run-1", instance: "processor-1" }
  );

  assert.equal(target.runtimeId, "worker-1");
  assert.equal(target.resourceKind, "worker");

  assert.throws(() => buildWorkerLogTarget(
    [
      runtimeResource("worker-1", { labels: { "pockethive.role": "processor" } }),
      runtimeResource("worker-2", { labels: { "pockethive.role": "processor" } })
    ],
    { swarmId: "swarm-1", runId: "run-1", role: "processor" }
  ), /ambiguous/);
});

test("runtime log helpers bound and redact output", () => {
  assert.equal(normalizeTailLines(undefined), 200);
  assert.equal(normalizeTailLines(25), 25);
  assert.throws(() => normalizeTailLines(2001), /2000/);

  const logs = redactLogText("Authorization: Bearer abc123 token=clear password=\"open\"");
  assert.match(logs, /Authorization: Bearer \[REDACTED\]/);
  assert.match(logs, /token=\[REDACTED\]/);
  assert.match(logs, /password=\[REDACTED\]/);
});

test("worker version derives from declared label and image reference", () => {
  const target = buildWorkerLogTarget(
    [
      runtimeResource("worker-1", {
        image: "ghcr.io/pockethive/processor:0.15.27@sha256:abc",
        labels: {
          "pockethive.instance": "processor-1",
          "pockethive.image": "ghcr.io/pockethive/processor:0.15.27@sha256:abc",
          "pockethive.version": "0.15.27"
        }
      })
    ],
    { swarmId: "swarm-1", runId: "run-1", instance: "processor-1" }
  );

  const version = buildWorkerVersion(target);

  assert.equal(version.declaredVersion, "0.15.27");
  assert.equal(version.imageTag, "0.15.27");
  assert.equal(version.imageDigest, "sha256:abc");
  assert.equal(version.reportedVersion, "0.15.27");
  assert.equal(version.reportedVersionSource, "pockethive.version");
});

test("runtime worker list partitions managers workers and blocked label issues", () => {
  const list = buildRuntimeWorkerList([
    runtimeResource("manager-1", {
      labels: {
        "pockethive.resourceKind": "manager",
        "pockethive.role": "swarm-controller"
      }
    }),
    runtimeResource("worker-1"),
    runtimeResource("bad-1", { labels: { "pockethive.instance": "" } }),
    runtimeResource("other", { labels: { "pockethive.swarmId": "other-swarm" } })
  ], { swarmId: "swarm-1", runId: "run-1" });

  assert.deepEqual(list.managers.map((entry) => entry.runtimeId), ["manager-1"]);
  assert.deepEqual(list.workers.map((entry) => entry.runtimeId), ["worker-1"]);
  assert.deepEqual(list.blocked.map((entry) => entry.runtimeId), ["bad-1"]);
  assert.equal(list.workers[0].reportedVersion, "0.15.27");
});

test("worker inspection summarizes runtime details without raw bind host paths", () => {
  const target = buildWorkerLogTarget(
    [runtimeResource("worker-1", { labels: { "pockethive.instance": "processor-1" } })],
    { swarmId: "swarm-1", runId: "run-1", instance: "processor-1" }
  );

  const inspection = buildWorkerInspection(target, {
    available: true,
    data: {
      Created: "2026-01-01T00:00:00Z",
      RestartCount: 2,
      State: {
        Status: "exited",
        Running: false,
        ExitCode: 137,
        Error: "",
        StartedAt: "2026-01-01T00:01:00Z",
        FinishedAt: "2026-01-01T00:02:00Z",
        Health: { Status: "unhealthy" }
      },
      HostConfig: { RestartPolicy: { Name: "on-failure" } },
      Mounts: [
        { Type: "bind", Source: "/host/secret/path", Destination: "/app/scenario", Mode: "ro", RW: false },
        { Type: "volume", Name: "ph-data", Source: "/var/lib/docker/volumes/ph-data/_data", Destination: "/data", RW: true }
      ],
      NetworkSettings: { Networks: { pockethive: {}, bridge: {} } }
    }
  });

  assert.equal(inspection.state.exitCode, 137);
  assert.equal(inspection.restartCount, 2);
  assert.equal(inspection.mounts[0].source, "[REDACTED]");
  assert.equal(inspection.mounts[1].name, "ph-data");
  assert.deepEqual(inspection.networks, ["bridge", "pockethive"]);
});

test("rabbit topology snapshot uses exact manifest resources and marks missing objects", () => {
  const snapshot = buildRabbitTopologySnapshot(
    { swarmId: "swarm-1", runId: "run-1", includeUnmanagedDiagnostics: true },
    { available: true, manifest: runtimeManifest() },
    {
      available: true,
      data: {
        queues: [
          { name: "ph.control.swarm-1.processor.worker-1", messages: 0, consumers: 1, state: "running" }
        ],
        unmanagedQueues: [
          { name: "ph.swarm-1.extra", messages: 1, consumers: 0, state: "running" }
        ],
        exchanges: []
      }
    }
  );

  assert.deepEqual(snapshot.queues.map((queue) => [queue.name, queue.present]), [
    ["ph.control.swarm-1.processor.worker-1", true],
    ["ph.swarm-1.work", false]
  ]);
  assert.deepEqual(snapshot.exchanges.map((exchange) => [exchange.name, exchange.present]), [
    ["ph.swarm-1.hive", false]
  ]);
  assert.deepEqual(snapshot.unmanagedDiagnostics.map((queue) => queue.name), ["ph.swarm-1.extra"]);
});

test("runtime diff reports manifest drift and cleanup candidates", () => {
  const resources = [
    runtimeResource("worker-1"),
    runtimeResource("unexpected-worker", { labels: { "pockethive.instance": "unexpected-worker" } })
  ];
  const context = runtimeContext(resources, {
    manifest: runtimeManifest({
      runtimeObjects: [
        manifestObject("worker-1"),
        manifestObject("missing-worker", { instance: "missing-worker" })
      ]
    }),
    rabbit: {
      available: true,
      data: {
        queues: [{ name: "ph.control.swarm-1.processor.worker-1", messages: 0, consumers: 1 }],
        exchanges: [{ name: "ph.swarm-1.hive", type: "direct" }]
      }
    }
  });

  const diff = buildRuntimeDiff(context);

  assert.equal(diff.summary.missingManifestRuntime, 1);
  assert.equal(diff.summary.unexpectedRuntime, 1);
  assert.deepEqual(diff.drift.missingManifestRuntime.map((entry) => entry.runtimeId), ["missing-worker"]);
  assert.deepEqual(diff.drift.unexpectedRuntime.map((entry) => entry.runtimeId), ["unexpected-worker"]);
  assert.equal(diff.cleanupPlan.candidates.length, 2);
});

test("control plane status derives queues and recent worker journal events", () => {
  const context = runtimeContext([runtimeResource("worker-1")], {
    rabbit: {
      available: true,
      data: {
        queues: [{ name: "ph.control.swarm-1.processor.worker-1", messages: 0, consumers: 1 }],
        exchanges: []
      }
    },
    journal: {
      available: true,
      data: {
        items: [{
          timestamp: "2026-01-01T00:00:00Z",
          kind: "status-full",
          type: "worker-status",
          scope: { swarmId: "swarm-1", role: "processor", instance: "worker-1" },
          data: { type: "heartbeat" }
        }]
      }
    }
  });

  const status = buildControlPlaneStatus(context);

  assert.equal(status.controlQueues[0].name, "ph.control.swarm-1.processor.worker-1");
  assert.equal(status.controlQueues[0].consumers, 1);
  assert.equal(status.workers[0].lastStatusEvent.type, "worker-status");
});

test("manifest validation reports label mismatches missing queues and unexpected runtime objects", () => {
  const context = runtimeContext([
    runtimeResource("worker-1", { labels: { "pockethive.role": "generator" } }),
    runtimeResource("extra-worker", { labels: { "pockethive.instance": "extra-worker" } })
  ], {
    manifest: runtimeManifest({
      runtimeObjects: [manifestObject("worker-1")]
    }),
    rabbit: {
      available: true,
      data: {
        queues: [],
        exchanges: [{ name: "ph.swarm-1.hive", type: "direct" }]
      }
    }
  });

  const validation = buildManifestValidation(context);

  assert.equal(validation.status, "drift");
  assert.equal(validation.labelMismatches[0].runtimeId, "worker-1");
  assert.deepEqual(validation.unexpectedRuntimeObjects.map((entry) => entry.runtimeId), ["extra-worker"]);
  assert.deepEqual(validation.rabbit.missingQueues.map((queue) => queue.name), [
    "ph.control.swarm-1.processor.worker-1",
    "ph.swarm-1.work"
  ]);
});

test("image parser does not treat registry ports as tags", () => {
  assert.deepEqual(parseImageReference("localhost:5000/pockethive/processor:0.15.27"), {
    image: "localhost:5000/pockethive/processor:0.15.27",
    imageTag: "0.15.27",
    imageDigest: null
  });
  assert.deepEqual(parseImageReference("localhost:5000/pockethive/processor"), {
    image: "localhost:5000/pockethive/processor",
    imageTag: null,
    imageDigest: null
  });
});

test("runtime cleanup tools delegate to orchestrator cleanup API when httpJson is provided", async () => {
  const handlers = new Map();
  const calls = [];
  registerRuntimeTools((name, _description, _schema, handler) => {
    handlers.set(name, handler);
  }, {
    httpJson: async (path, options) => {
      calls.push({ path, options });
      if (path === "/api/runtime/debug/capabilities") {
        return runtimeCapabilities();
      }
      return { delegated: true, path, body: options.body };
    }
  });

  const plan = await handlers.get("runtime.cleanup.plan")({
    computeAdapter: "DOCKER_SINGLE",
    swarmId: "sw1",
    runId: "run-1",
    includeRunning: false,
    includeRabbit: true
  });
  const execution = await handlers.get("runtime.cleanup.execute")({
    computeAdapter: "DOCKER_SINGLE",
    swarmId: "sw1",
    candidateSetHash: "sha256:abc",
    candidateIds: ["lifecycle:swarm:sw1"],
    idempotencyKey: "idem-1",
    reason: "test",
    actor: "alice"
  });

  assert.equal(plan.path, "/api/runtime/cleanup/plan");
  assert.equal(execution.path, "/api/runtime/cleanup/execute");
  assert.deepEqual(calls.map((call) => call.path), [
    "/api/runtime/debug/capabilities",
    "/api/runtime/cleanup/plan",
    "/api/runtime/cleanup/execute"
  ]);
  assert.deepEqual(calls.map((call) => call.options.method), ["GET", "POST", "POST"]);
  assert.equal(calls[1].options.body.includeRabbit, true);
  assert.equal(calls[2].options.body.actor, "alice");
  assert.equal(calls[2].options.body.idempotencyKey, "idem-1");
});

test("runtime tools fail closed when orchestrator runtime contract is incompatible", async () => {
  const handlers = new Map();
  const calls = [];
  registerRuntimeTools((name, _description, _schema, handler) => {
    handlers.set(name, handler);
  }, {
    httpJson: async (path, options) => {
      calls.push({ path, options });
      if (path === "/api/runtime/debug/capabilities") {
        return runtimeCapabilities({ cleanupPlanHasExecutionRisk: false });
      }
      return { shouldNotDelegate: true };
    }
  });

  await assert.rejects(
    () => handlers.get("runtime.cleanup.plan")({
      computeAdapter: "DOCKER_SINGLE",
      swarmId: "sw1",
      runId: "run-1"
    }),
    /Incompatible Orchestrator runtime debug contract/
  );
  assert.deepEqual(calls.map((call) => call.path), ["/api/runtime/debug/capabilities"]);
});

test("runtime contract validation pins cleanup drift markers", () => {
  assert.equal(validateRuntimeDebugCapabilities(runtimeCapabilities()).cleanupContractVersion, "1");
  assert.throws(
    () => validateRuntimeDebugCapabilities(runtimeCapabilities({ cleanupPlanUsesApprovalFields: true })),
    /cleanupPlanUsesApprovalFields/
  );
});

test("rabbit topology tool reads exact manifest resources by default", async () => {
  const manifestRoot = mkdtempSync(join(tmpdir(), "ph-runtime-manifest-"));
  try {
    const manifestDir = join(manifestRoot, "swarm-1", "run-1");
    mkdirSync(manifestDir, { recursive: true });
    writeFileSync(join(manifestDir, "runtime-ownership-manifest.json"), JSON.stringify(runtimeManifest()), "utf8");

    const handlers = new Map();
    const calls = [];
    registerRuntimeTools((name, _description, _schema, handler) => {
      handlers.set(name, handler);
    }, {
      manifestRoot,
      rabbitManagementBaseUrl: "http://rabbit/api",
      rabbitVhost: "/",
      rabbitAuth: "Basic test",
      httpJson: async (path, options) => {
        calls.push({ path, options });
        if (path === "/api/runtime/debug/capabilities") {
          return runtimeCapabilities();
        }
        if (path.includes("/queues/%2F/")) {
          return {
            name: decodeURIComponent(path.split("/queues/%2F/")[1]),
            messages: 0,
            consumers: 0
          };
        }
        if (path.includes("/exchanges/%2F/")) {
          return {
            name: decodeURIComponent(path.split("/exchanges/%2F/")[1]),
            type: "direct"
          };
        }
        throw new Error(`unexpected call ${path}`);
      }
    });

    const snapshot = await handlers.get("runtime.rabbit-topology-snapshot")({
      swarmId: "swarm-1",
      runId: "run-1"
    });

    assert.equal(snapshot.queues.length, 2);
    assert.equal(snapshot.exchanges.length, 1);
    assert.equal(calls.some((call) => call.path === "http://rabbit/api/queues"), false);
    assert.equal(calls.some((call) => call.path === "http://rabbit/api/exchanges"), false);
    assert.ok(calls
      .filter((call) => call.path.startsWith("http://rabbit/api/"))
      .every((call) => call.options.headers.authorization === "Basic test"));
  } finally {
    rmSync(manifestRoot, { recursive: true, force: true });
  }
});

test("rabbit topology unmanaged diagnostics explicitly perform broad queue scan", async () => {
  const manifestRoot = mkdtempSync(join(tmpdir(), "ph-runtime-manifest-"));
  try {
    const manifestDir = join(manifestRoot, "swarm-1", "run-1");
    mkdirSync(manifestDir, { recursive: true });
    writeFileSync(join(manifestDir, "runtime-ownership-manifest.json"), JSON.stringify(runtimeManifest()), "utf8");

    const handlers = new Map();
    const calls = [];
    registerRuntimeTools((name, _description, _schema, handler) => {
      handlers.set(name, handler);
    }, {
      manifestRoot,
      rabbitManagementBaseUrl: "http://rabbit/api",
      rabbitVhost: "/",
      httpJson: async (path) => {
        calls.push(path);
        if (path === "/api/runtime/debug/capabilities") {
          return runtimeCapabilities();
        }
        if (path === "http://rabbit/api/queues") {
          return [{ name: "ph.swarm-1.legacy", messages: 1, consumers: 0 }];
        }
        if (path.includes("/queues/%2F/")) {
          return null;
        }
        if (path.includes("/exchanges/%2F/")) {
          return null;
        }
        throw new Error(`unexpected call ${path}`);
      }
    });

    const snapshot = await handlers.get("runtime.rabbit-topology-snapshot")({
      swarmId: "swarm-1",
      runId: "run-1",
      includeUnmanagedDiagnostics: true
    });

    assert.ok(calls.includes("http://rabbit/api/queues"));
    assert.deepEqual(snapshot.unmanagedDiagnostics.map((queue) => queue.name), ["ph.swarm-1.legacy"]);
  } finally {
    rmSync(manifestRoot, { recursive: true, force: true });
  }
});

function runtimeResource(runtimeId, overrides = {}) {
  return {
    runtimeId,
    runtimeType: "container",
    name: runtimeId,
    image: overrides.image ?? "processor:0.15.27",
    state: overrides.state ?? "exited",
    labels: {
      "pockethive.managed": "true",
      "pockethive.swarmId": "swarm-1",
      "pockethive.runId": "run-1",
      "pockethive.resourceKind": "worker",
      "pockethive.role": "processor",
      "pockethive.instance": runtimeId,
      "pockethive.image": overrides.image ?? "processor:0.15.27",
      "pockethive.version": "0.15.27",
      ...(overrides.labels ?? {})
    }
  };
}

function manifestObject(runtimeId, overrides = {}) {
  return {
    runtimeId,
    runtimeType: overrides.runtimeType ?? "container",
    resourceKind: overrides.resourceKind ?? "worker",
    role: overrides.role ?? "processor",
    instance: overrides.instance ?? runtimeId,
    image: overrides.image ?? "processor:0.15.27"
  };
}

function runtimeManifest(overrides = {}) {
  return {
    swarmId: "swarm-1",
    runId: "run-1",
    templateId: "template-1",
    computeAdapter: "DOCKER_SINGLE",
    createdAt: "2026-01-01T00:00:00Z",
    runtimeObjects: overrides.runtimeObjects ?? [manifestObject("worker-1")],
    rabbit: overrides.rabbit ?? {
      controlQueues: ["ph.control.swarm-1.processor.worker-1"],
      workQueues: ["ph.swarm-1.work"],
      exchanges: ["ph.swarm-1.hive"]
    }
  };
}

function runtimeCapabilities(overrides = {}) {
  return {
    runtimeDebugContractVersion: "1",
    cleanupContractVersion: "1",
    cleanupPlanHasExecutionRisk: true,
    cleanupPlanUsesApprovalFields: false,
    cleanupExecuteRequiresCandidateSetHash: true,
    rabbitTopologyExactByDefault: true,
    ...overrides
  };
}

function runtimeContext(resources, overrides = {}) {
  return {
    input: {
      computeAdapter: "DOCKER_SINGLE",
      swarmId: "swarm-1",
      runId: "run-1",
      includeRabbit: true
    },
    sources: {
      runtimeInventory: { available: true },
      manifest: { available: overrides.manifest !== null },
      rabbit: { available: overrides.rabbit?.available === true },
      orchestratorSnapshot: { available: false, reason: "not configured" },
      journal: { available: overrides.journal?.available === true }
    },
    resources,
    manifest: overrides.manifest === undefined ? runtimeManifest() : overrides.manifest,
    rabbit: overrides.rabbit ?? { available: false, reason: "not configured" },
    swarmSnapshot: null,
    journal: overrides.journal ?? { available: false, reason: "not configured" },
    cleanupPlan: buildCleanupPlan(resources, {
      swarmId: "swarm-1",
      runId: "run-1",
      includeRunning: true
    })
  };
}
