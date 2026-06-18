import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { z } from "zod";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, "../..");

const LABELS = {
  managed: "pockethive.managed",
  swarmId: "pockethive.swarmId",
  runId: "pockethive.runId",
  resourceKind: "pockethive.resourceKind",
  role: "pockethive.role",
  instance: "pockethive.instance",
  logicalName: "pockethive.logicalName",
  image: "pockethive.image",
  version: "pockethive.version"
};

const LABEL_VALUES = {
  managed: "true",
  worker: "worker",
  manager: "manager"
};

const REQUIRED_CLEANUP_LABELS = [
  LABELS.managed,
  LABELS.swarmId,
  LABELS.runId,
  LABELS.resourceKind,
  LABELS.role,
  LABELS.instance
];

const COMPUTE_ADAPTER_SCHEMA = z.enum(["DOCKER_SINGLE", "SWARM_STACK"]);
const MANIFEST_FILE = "runtime-ownership-manifest.json";
const DEFAULT_JOURNAL_LIMIT = 100;
const RUNTIME_DEBUG_CAPABILITIES_PATH = "/api/runtime/debug/capabilities";
const REQUIRED_RUNTIME_DEBUG_CAPABILITIES = Object.freeze({
  runtimeDebugContractVersion: "2",
  cleanupContractVersion: "2",
  runtimeDebugReadsBackedByOrchestrator: true,
  cleanupPlanHasExecutionRisk: true,
  cleanupPlanUsesApprovalFields: false,
  cleanupExecuteRequiresCandidateSetHash: true,
  rabbitTopologyExactByDefault: true,
  cleanupSupportsRegisteredStateOverride: true
});

const runtimeReadOnly = {
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false }
};

const runtimeDestructiveWrite = {
  annotations: { readOnlyHint: false, destructiveHint: true, openWorldHint: false }
};

export function registerRuntimeTools(reg, options = {}) {
  const cleanupApi = options.httpJson ? runtimeCleanupApi(options.httpJson) : null;
  const runtimeApi = options.httpJson ? runtimeDebugApi(options.httpJson) : null;
  const sourceOptions = {
    httpJson: options.httpJson ?? null,
    rabbitManagementBaseUrl: options.rabbitManagementBaseUrl ?? null,
    rabbitAuth: options.rabbitAuth ?? null,
    rabbitVhost: options.rabbitVhost ?? process.env.RABBITMQ_VHOST ?? "/",
    manifestRoot: options.manifestRoot
      ?? process.env.POCKETHIVE_SCENARIOS_RUNTIME_ROOT
      ?? resolve(REPO_ROOT, "scenarios-runtime")
  };
  const runtimeContract = runtimeContractGuard(options.httpJson ?? null);
  const guarded = (handler) => async (input) => {
    await runtimeContract.assertCompatible();
    return await handler(input);
  };
  const actor = () => process.env.USER || "mcp-user";

  reg("runtime.cleanup.plan", "Create a read-only, label-gated cleanup plan for stale PocketHive runtime resources.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    includeRunning: z.boolean().optional(),
    includeRabbit: z.boolean().optional(),
    overrideRegisteredSwarmState: z.boolean().optional()
  }, guarded(async ({
    computeAdapter,
    swarmId,
    runId,
    includeRunning = false,
    includeRabbit,
    overrideRegisteredSwarmState
  }) => {
    return await requireCleanupApi(cleanupApi).plan({
      computeAdapter,
      swarmId,
      runId,
      includeRunning,
      includeRabbit,
      overrideRegisteredSwarmState
    });
  }), runtimeReadOnly);

  reg("runtime.tail-worker-logs", "Read recent Orchestrator-backed Docker/Swarm logs for one label-gated PocketHive worker or manager runtime resource.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    runtimeId: z.string().optional(),
    instance: z.string().optional(),
    role: z.string().optional(),
    resourceKind: z.enum(["worker", "manager"]).optional(),
    tailLines: z.number().int().min(1).max(2000).optional(),
    since: z.string().optional()
  }, guarded(async (input) => {
    const tailLines = normalizeTailLines(input.tailLines);
    return await requireRuntimeDebugApi(runtimeApi).logs({ ...input, tailLines });
  }), runtimeReadOnly);

  reg("runtime.get-worker-version", "Read Orchestrator-backed version metadata for one label-gated PocketHive worker or manager runtime resource.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    runtimeId: z.string().optional(),
    instance: z.string().optional(),
    role: z.string().optional(),
    resourceKind: z.enum(["worker", "manager"]).optional()
  }, guarded(async (input) => {
    return await requireRuntimeDebugApi(runtimeApi).version(input);
  }), runtimeReadOnly);

  reg("runtime.list-workers", "List Orchestrator-backed label-gated PocketHive manager and worker runtime resources for one swarm.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    includeManagers: z.boolean().optional()
  }, guarded(async (input) => {
    return await requireRuntimeDebugApi(runtimeApi).list(input);
  }), runtimeReadOnly);

  reg("runtime.inspect-worker", "Read an Orchestrator-backed bounded inspect summary for one PocketHive worker or manager runtime resource.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    runtimeId: z.string().optional(),
    instance: z.string().optional(),
    role: z.string().optional(),
    resourceKind: z.enum(["worker", "manager"]).optional()
  }, guarded(async (input) => {
    return await requireRuntimeDebugApi(runtimeApi).inspect(input);
  }), runtimeReadOnly);

  reg("runtime.diff-swarm-runtime", "Compare Orchestrator, manifest, Docker/Swarm, RabbitMQ, and cleanup views for one swarm.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    includeRabbit: z.boolean().optional(),
    journalLimit: z.number().int().min(1).max(1000).optional()
  }, guarded(async (input) => {
    const context = await runtimeDebugContext(input, sourceOptions);
    return buildRuntimeDiff(context);
  }), runtimeReadOnly);

  reg("runtime.control-plane-status", "Summarize control queues and recent control-plane events for one swarm.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    journalLimit: z.number().int().min(1).max(1000).optional()
  }, guarded(async (input) => {
    const context = await runtimeDebugContext({ ...input, includeRabbit: true }, sourceOptions);
    return buildControlPlaneStatus(context);
  }), runtimeReadOnly);

  reg("runtime.rabbit-topology-snapshot", "Read exact manifest-owned RabbitMQ queues and exchanges for one swarm.", {
    swarmId: z.string(),
    runId: z.string().optional(),
    includeUnmanagedDiagnostics: z.boolean().optional()
  }, guarded(async (input) => {
    const manifest = readRuntimeOwnershipManifest(input, sourceOptions);
    const rabbit = await readRabbitTopology(input, manifest, sourceOptions);
    return buildRabbitTopologySnapshot(input, manifest, rabbit);
  }), runtimeReadOnly);

  reg("runtime.swarm-timeline", "Build a read-only swarm timeline from Orchestrator journal and runtime state.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    limit: z.number().int().min(1).max(1000).optional()
  }, guarded(async (input) => {
    const context = await runtimeDebugContext({
      ...input,
      includeRabbit: false,
      journalLimit: input.limit ?? DEFAULT_JOURNAL_LIMIT
    }, sourceOptions);
    return buildSwarmTimeline(context, input);
  }), runtimeReadOnly);

  reg("runtime.manifest-validate", "Validate the runtime ownership manifest against live runtime and RabbitMQ state.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    includeRabbit: z.boolean().optional()
  }, guarded(async (input) => {
    const context = await runtimeDebugContext(input, sourceOptions);
    return buildManifestValidation(context);
  }), runtimeReadOnly);

  reg("runtime.cleanup.execute", "Remove PocketHive runtime cleanup candidates by exact candidate hash after external governance permits execution.", {
    computeAdapter: COMPUTE_ADAPTER_SCHEMA,
    swarmId: z.string(),
    runId: z.string().optional(),
    includeRunning: z.boolean().optional(),
    includeRabbit: z.boolean().optional(),
    overrideRegisteredSwarmState: z.boolean().optional(),
    candidateSetHash: z.string(),
    candidateIds: z.array(z.string()),
    idempotencyKey: z.string(),
    reason: z.string(),
    actor: z.string().optional()
  }, guarded(async (input) => {
    const executionInput = {
      ...input,
      actor: input.actor ?? actor()
    };
    return await requireCleanupApi(cleanupApi).execute(executionInput);
  }), runtimeDestructiveWrite);
}

function requireCleanupApi(cleanupApi) {
  if (!cleanupApi) {
    throw new Error("runtime cleanup requires the Orchestrator runtime cleanup API; local MCP cleanup fallback is disabled");
  }
  return cleanupApi;
}

function requireRuntimeDebugApi(runtimeApi) {
  if (!runtimeApi) {
    throw new Error("runtime Docker/Swarm debug requires the Orchestrator runtime debug API; local MCP Docker fallback is disabled");
  }
  return runtimeApi;
}

function runtimeContractGuard(httpJson) {
  let cached = null;
  return {
    async assertCompatible() {
      if (!httpJson) {
        throw new Error("runtime tools require the Orchestrator runtime debug API; local MCP runtime fallback is disabled");
      }
      if (cached) {
        return cached;
      }
      const capabilities = await httpJson(RUNTIME_DEBUG_CAPABILITIES_PATH, {
        method: "GET",
        timeoutMs: 5000
      });
      validateRuntimeDebugCapabilities(capabilities);
      cached = capabilities;
      return cached;
    }
  };
}

export function validateRuntimeDebugCapabilities(capabilities) {
  const failures = [];
  for (const [field, expected] of Object.entries(REQUIRED_RUNTIME_DEBUG_CAPABILITIES)) {
    if (capabilities?.[field] !== expected) {
      failures.push(`${field} expected ${JSON.stringify(expected)} but was ${JSON.stringify(capabilities?.[field])}`);
    }
  }
  if (failures.length > 0) {
    throw new Error(`Incompatible Orchestrator runtime debug contract at ${RUNTIME_DEBUG_CAPABILITIES_PATH}: ${failures.join("; ")}`);
  }
  return capabilities;
}

function runtimeCleanupApi(httpJson) {
  return {
    plan: (body) => httpJson("/api/runtime/cleanup/plan", {
      method: "POST",
      body: cleanApiBody(body)
    }),
    execute: (body) => httpJson("/api/runtime/cleanup/execute", {
      method: "POST",
      body: cleanApiBody(body)
    })
  };
}

function runtimeDebugApi(httpJson) {
  return {
    list: (body) => httpJson("/api/runtime/debug/resources/list", {
      method: "POST",
      body: cleanApiBody(body)
    }),
    logs: (body) => httpJson("/api/runtime/debug/resources/logs", {
      method: "POST",
      body: cleanApiBody(body)
    }),
    version: (body) => httpJson("/api/runtime/debug/resources/version", {
      method: "POST",
      body: cleanApiBody(body)
    }),
    inspect: (body) => httpJson("/api/runtime/debug/resources/inspect", {
      method: "POST",
      body: cleanApiBody(body)
    })
  };
}

function cleanApiBody(body) {
  return Object.fromEntries(Object.entries(body ?? {}).filter(([, value]) => value !== undefined));
}

export function buildRuntimeWorkerList(resources, input = {}) {
  const swarmId = requireText(input.swarmId, "swarmId");
  const runId = optionalText(input.runId);
  const includeManagers = input.includeManagers !== false;
  const workers = [];
  const managers = [];
  const blocked = [];

  for (const resource of resources ?? []) {
    const normalized = normalizeRuntimeResource(resource);
    const labels = normalized.labels;
    if (labels[LABELS.swarmId] !== swarmId) {
      continue;
    }
    if (labels[LABELS.managed] !== LABEL_VALUES.managed) {
      blocked.push(blockedResource(normalized, `missing ${LABELS.managed}=${LABEL_VALUES.managed}`));
      continue;
    }
    const missingLabels = REQUIRED_CLEANUP_LABELS.filter((label) => !hasText(labels[label]));
    if (missingLabels.length > 0) {
      blocked.push(blockedResource(normalized, `missing required labels: ${missingLabels.join(", ")}`));
      continue;
    }
    if (runId && labels[LABELS.runId] !== runId) {
      continue;
    }

    const entry = runtimeListEntry(normalized);
    if (entry.resourceKind === LABEL_VALUES.worker) {
      workers.push(entry);
    } else if (entry.resourceKind === LABEL_VALUES.manager) {
      if (includeManagers) {
        managers.push(entry);
      }
    } else {
      blocked.push(blockedResource(normalized, `unsupported ${LABELS.resourceKind}=${entry.resourceKind}`));
    }
  }

  workers.sort(compareRuntimeId);
  managers.sort(compareRuntimeId);
  blocked.sort(compareRuntimeId);

  return {
    swarmId,
    runId: runId ?? null,
    counts: {
      workers: workers.length,
      managers: managers.length,
      blocked: blocked.length
    },
    workers,
    managers,
    blocked
  };
}

export function buildWorkerInspection(target, inspectSource = { available: false }) {
  const raw = inspectSource.available ? inspectSource.data : null;
  const inspect = target.runtimeType === "service"
    ? serviceInspectSummary(raw)
    : containerInspectSummary(raw);
  return {
    target,
    source: sourceSummary(inspectSource),
    ...inspect
  };
}

export async function runtimeDebugContext(input = {}, options = {}) {
  const computeAdapter = requireText(input.computeAdapter, "computeAdapter");
  const swarmId = requireText(input.swarmId, "swarmId");
  const runId = optionalText(input.runId);
  const includeRabbit = input.includeRabbit !== false;
  const resourcesSource = await readRuntimeInventory({ computeAdapter, swarmId, runId }, options);
  const resources = resourcesSource.available ? runtimeResourcesFromListResponse(resourcesSource.data) : [];
  const manifest = readRuntimeOwnershipManifest({ swarmId, runId }, options);
  const rabbit = includeRabbit
    ? await readRabbitTopology({ swarmId, runId }, manifest, options, resources)
    : { available: false, skipped: true, reason: "includeRabbit=false" };
  const swarmSnapshot = await readOrchestratorSnapshot(swarmId, options);
  const journal = await readSwarmJournal(
    { swarmId, runId, limit: input.journalLimit ?? DEFAULT_JOURNAL_LIMIT },
    options
  );
  const cleanupPlanSource = await readCleanupPlan(
    { computeAdapter, swarmId, runId, includeRunning: true, includeRabbit },
    options
  );
  const cleanupPlan = cleanupPlanSource.available ? cleanupPlanSource.data : null;

  return {
    input: {
      computeAdapter,
      swarmId,
      runId: runId ?? null,
      includeRabbit
    },
    sources: {
      runtimeInventory: sourceSummary(resourcesSource),
      manifest: sourceSummary(manifest),
      rabbit: sourceSummary(rabbit),
      orchestratorSnapshot: sourceSummary(swarmSnapshot),
      journal: sourceSummary(journal),
      cleanupPlan: sourceSummary(cleanupPlanSource)
    },
    resources,
    manifest: manifest.manifest ?? null,
    rabbit,
    swarmSnapshot: swarmSnapshot.data ?? null,
    journal,
    cleanupPlan
  };
}

export function buildRuntimeDiff(context) {
  const workerList = buildRuntimeWorkerList(context.resources, {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    includeManagers: true
  });
  const manifestObjects = context.manifest?.runtimeObjects ?? [];
  const manifestById = new Map(manifestObjects.map((object) => [object.runtimeId, object]));
  const actualEntries = [...workerList.managers, ...workerList.workers];
  const actualById = new Map(actualEntries.map((entry) => [entry.runtimeId, entry]));
  const missingManifestRuntime = manifestObjects
    .filter((object) => !actualById.has(object.runtimeId))
    .map((object) => ({
      runtimeId: object.runtimeId,
      runtimeType: object.runtimeType,
      resourceKind: object.resourceKind,
      role: object.role,
      instance: object.instance,
      image: object.image
    }))
    .sort(compareRuntimeId);
  const unexpectedRuntime = context.manifest
    ? actualEntries
        .filter((entry) => !manifestById.has(entry.runtimeId))
        .map(runtimeDriftEntry)
        .sort(compareRuntimeId)
    : [];
  const stateIssues = actualEntries
    .filter((entry) => entry.resourceKind === LABEL_VALUES.worker && !entry.running)
    .map((entry) => ({
      runtimeId: entry.runtimeId,
      role: entry.role,
      instance: entry.instance,
      state: entry.state,
      reason: "worker runtime is not running"
    }))
    .sort(compareRuntimeId);
  const rabbitSnapshot = buildRabbitTopologySnapshot(
    { swarmId: context.input.swarmId, runId: context.input.runId },
    context.sources.manifest.available
      ? { available: true, manifest: context.manifest }
      : { available: false, error: context.sources.manifest.error },
    context.rabbit
  );

  return {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    sources: context.sources,
    summary: {
      workers: workerList.counts.workers,
      managers: workerList.counts.managers,
      cleanupCandidates: context.cleanupPlan?.candidates?.length ?? null,
      missingManifestRuntime: missingManifestRuntime.length,
      unexpectedRuntime: unexpectedRuntime.length,
      stateIssues: stateIssues.length,
      missingRabbitQueues: rabbitSnapshot.queues.filter((queue) => queue.present === false).length,
      missingRabbitExchanges: rabbitSnapshot.exchanges.filter((exchange) => exchange.present === false).length
    },
    workers: workerList,
    cleanupPlan: context.cleanupPlan,
    manifest: context.manifest,
    orchestratorSnapshot: summarizeSwarmSnapshot(context.swarmSnapshot),
    drift: {
      missingManifestRuntime,
      unexpectedRuntime,
      stateIssues,
      rabbit: rabbitSnapshot
    }
  };
}

export function buildControlPlaneStatus(context) {
  const workerList = buildRuntimeWorkerList(context.resources, {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    includeManagers: true
  });
  const queuesByName = rabbitQueuesByName(context.rabbit);
  const manifestControlQueues = context.manifest?.rabbit?.controlQueues ?? [];
  const exactQueueNames = new Set(manifestControlQueues);
  const journalEntries = normalizeJournalEntries(context.journal.data);
  const controlQueues = [...exactQueueNames]
    .filter(Boolean)
    .sort()
    .map((name) => rabbitQueueSnapshot(name, queuesByName.get(name)));

  return {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    sources: context.sources,
    controlQueues,
    workers: workerList.workers.map((worker) => controlPlaneRuntimeStatus(worker, manifestControlQueues, queuesByName, journalEntries)),
    managers: workerList.managers.map((manager) => controlPlaneRuntimeStatus(manager, manifestControlQueues, queuesByName, journalEntries)),
    recentControlEvents: journalEntries
      .filter((entry) => journalText(entry).match(/control|command|config/i))
      .slice(0, 20)
      .map(summarizeJournalEntry)
  };
}

function controlPlaneRuntimeStatus(entry, manifestControlQueues, queuesByName, journalEntries) {
  const controlQueueName = manifestControlQueueFor(entry, manifestControlQueues);
  return {
    runtimeId: entry.runtimeId,
    role: entry.role,
    instance: entry.instance,
    state: entry.state,
    running: entry.running,
    controlQueue: controlQueueName
      ? rabbitQueueSnapshot(controlQueueName, queuesByName.get(controlQueueName))
      : { name: null, present: false, reason: "not present in runtime ownership manifest" },
    lastStatusEvent: latestJournalEventForWorker(journalEntries, entry, ["status", "heartbeat"]),
    lastControlEvent: latestJournalEventForWorker(journalEntries, entry, ["control", "command", "config"])
  };
}

function manifestControlQueueFor(entry, manifestControlQueues = []) {
  const role = optionalText(entry.role);
  const instance = optionalText(entry.instance);
  if (!role || !instance) {
    return null;
  }
  const suffix = `.${role}.${instance}`;
  const matches = manifestControlQueues.filter((queue) => String(queue ?? "").endsWith(suffix));
  return matches.length === 1 ? matches[0] : null;
}

export function buildRabbitTopologySnapshot(input, manifestSource, rabbitSource) {
  const swarmId = requireText(input.swarmId, "swarmId");
  const runId = optionalText(input.runId);
  const manifest = manifestSource.manifest ?? null;
  const queuesByName = rabbitQueuesByName(rabbitSource);
  const exchangesByName = rabbitExchangesByName(rabbitSource);
  const exactQueues = manifest?.rabbit
    ? [...new Set([...(manifest.rabbit.controlQueues ?? []), ...(manifest.rabbit.workQueues ?? [])])]
    : [];
  const exactExchanges = manifest?.rabbit ? [...new Set(manifest.rabbit.exchanges ?? [])] : [];
  const liveQueues = rabbitSource.unmanagedQueues ?? rabbitSource.data?.unmanagedQueues ?? [];
  const unmanagedDiagnostics = input.includeUnmanagedDiagnostics && rabbitSource.available
    ? liveQueues
        .filter((queue) => isSwarmRabbitDiagnosticName(queue.name, swarmId) && !exactQueues.includes(queue.name))
        .map((queue) => ({ ...rabbitQueueSnapshot(queue.name, queue), diagnosticOnly: true }))
    : [];

  return {
    swarmId,
    runId: runId ?? null,
    manifest: sourceSummary(manifestSource),
    rabbit: sourceSummary(rabbitSource),
    exactOnly: true,
    queues: exactQueues.sort().map((name) => rabbitQueueSnapshot(name, queuesByName.get(name))),
    exchanges: exactExchanges.sort().map((name) => rabbitExchangeSnapshot(name, exchangesByName.get(name))),
    unmanagedDiagnostics
  };
}

export function buildSwarmTimeline(context, input = {}) {
  const limit = input.limit ?? context.journal.limit ?? DEFAULT_JOURNAL_LIMIT;
  const journalEntries = normalizeJournalEntries(context.journal.data)
    .map(summarizeJournalEntry)
    .reverse()
    .slice(-limit);
  const runtimeEntries = buildRuntimeWorkerList(context.resources, {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    includeManagers: true
  });
  return {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    sources: context.sources,
    entries: journalEntries,
    runtimeState: {
      managers: runtimeEntries.managers.map(runtimeTimelineState),
      workers: runtimeEntries.workers.map(runtimeTimelineState)
    },
    fallback: journalEntries.length === 0
      ? "No journal entries were available; runtimeState contains the current runtime-only view."
      : null
  };
}

export function buildManifestValidation(context) {
  const manifest = context.manifest;
  if (!manifest) {
    return {
      swarmId: context.input.swarmId,
      runId: context.input.runId,
      sources: context.sources,
      status: "missing_manifest",
      findings: [{
        severity: "error",
        reason: "runtime ownership manifest is missing"
      }]
    };
  }

  const actualById = new Map(context.resources.map((resource) => {
    const normalized = normalizeRuntimeResource(resource);
    return [normalized.runtimeId, normalized];
  }));
  const manifestObjects = manifest.runtimeObjects ?? [];
  const missingRuntimeObjects = [];
  const labelMismatches = [];
  for (const object of manifestObjects) {
    const actual = actualById.get(object.runtimeId);
    if (!actual) {
      missingRuntimeObjects.push(object);
      continue;
    }
    const expected = {
      [LABELS.swarmId]: manifest.swarmId,
      [LABELS.runId]: manifest.runId,
      [LABELS.resourceKind]: object.resourceKind,
      [LABELS.role]: object.role,
      [LABELS.instance]: object.instance
    };
    const mismatches = Object.entries(expected)
      .filter(([, value]) => hasText(value))
      .filter(([label, value]) => actual.labels[label] !== value)
      .map(([label, expectedValue]) => ({
        label,
        expected: expectedValue,
        actual: actual.labels[label] ?? null
      }));
    if (mismatches.length > 0) {
      labelMismatches.push({
        runtimeId: object.runtimeId,
        mismatches
      });
    }
  }

  const manifestIds = new Set(manifestObjects.map((object) => object.runtimeId));
  const unexpectedRuntimeObjects = context.resources
    .map(normalizeRuntimeResource)
    .filter((resource) => resource.labels[LABELS.managed] === LABEL_VALUES.managed)
    .filter((resource) => resource.labels[LABELS.swarmId] === manifest.swarmId)
    .filter((resource) => !context.input.runId || resource.labels[LABELS.runId] === context.input.runId)
    .filter((resource) => !manifestIds.has(resource.runtimeId))
    .map(runtimeDriftEntry)
    .sort(compareRuntimeId);
  const rabbit = buildRabbitTopologySnapshot(
    { swarmId: context.input.swarmId, runId: context.input.runId },
    { available: true, manifest },
    context.rabbit
  );
  const missingQueues = rabbit.queues.filter((queue) => queue.present === false);
  const missingExchanges = rabbit.exchanges.filter((exchange) => exchange.present === false);
  const queueRisks = rabbit.queues
    .filter((queue) => queue.present && ((queue.messages ?? 0) > 0 || (queue.consumers ?? 0) > 0))
    .map((queue) => ({
      name: queue.name,
      messages: queue.messages,
      consumers: queue.consumers,
      reason: "queue has messages or consumers"
    }));
  const status = missingRuntimeObjects.length > 0
    || labelMismatches.length > 0
    || missingQueues.length > 0
    || missingExchanges.length > 0
    ? "drift"
    : unexpectedRuntimeObjects.length > 0 || queueRisks.length > 0
      ? "warning"
      : "ok";

  return {
    swarmId: context.input.swarmId,
    runId: context.input.runId,
    sources: context.sources,
    status,
    manifest,
    missingRuntimeObjects,
    labelMismatches,
    unexpectedRuntimeObjects,
    rabbit: {
      missingQueues,
      missingExchanges,
      queueRisks
    }
  };
}

export function buildWorkerLogTarget(resources, input = {}) {
  const swarmId = requireText(input.swarmId, "swarmId");
  const runId = optionalText(input.runId);
  const runtimeId = optionalText(input.runtimeId);
  const instance = optionalText(input.instance);
  const role = optionalText(input.role);

  if (!runtimeId && !instance && !role) {
    throw new Error("runtimeId, instance, or role must identify a worker log target");
  }

  const matches = [];
  for (const resource of resources ?? []) {
    const normalized = normalizeRuntimeResource(resource);
    const labels = normalized.labels;
    if (labels[LABELS.managed] !== LABEL_VALUES.managed) {
      continue;
    }
    if (labels[LABELS.swarmId] !== swarmId) {
      continue;
    }
    if (runId && labels[LABELS.runId] !== runId) {
      continue;
    }
    if (labels[LABELS.resourceKind] !== LABEL_VALUES.worker) {
      continue;
    }
    if (runtimeId && normalized.runtimeId !== runtimeId) {
      continue;
    }
    if (instance && labels[LABELS.instance] !== instance) {
      continue;
    }
    if (role && labels[LABELS.role] !== role) {
      continue;
    }
    const missingLabels = REQUIRED_CLEANUP_LABELS.filter((label) => !hasText(labels[label]));
    if (missingLabels.length > 0) {
      throw new Error(`worker log target '${normalized.runtimeId}' is missing required labels: ${missingLabels.join(", ")}`);
    }
    matches.push(workerLogTarget(normalized));
  }

  matches.sort(compareRuntimeId);
  if (matches.length === 0) {
    throw new Error("no matching PocketHive worker log target was found");
  }
  if (matches.length > 1) {
    const ids = matches.map((match) => match.runtimeId).join(", ");
    throw new Error(`worker log target is ambiguous; provide runtimeId or instance. Matches: ${ids}`);
  }
  return matches[0];
}

export function readRuntimeOwnershipManifest(input = {}, options = {}) {
  const swarmId = requireText(input.swarmId, "swarmId");
  const runId = optionalText(input.runId);
  try {
    const root = resolve(String(options.manifestRoot ?? resolve(REPO_ROOT, "scenarios-runtime")));
    const file = runId
      ? manifestPath(root, swarmId, runId)
      : latestManifestPath(root, swarmId);
    if (!file || !existsSync(file)) {
      return {
        available: false,
        reason: "runtime ownership manifest was not found",
        path: file ?? null
      };
    }
    const manifest = JSON.parse(readFileSync(file, "utf8"));
    return {
      available: true,
      path: file,
      manifest: normalizeRuntimeManifest(manifest)
    };
  } catch (error) {
    return {
      available: false,
      error: error instanceof Error ? error.message : String(error)
    };
  }
}

async function readRabbitTopology(input, manifestSource, options = {}, resources = []) {
  if (!options.httpJson || !options.rabbitManagementBaseUrl) {
    return {
      available: false,
      reason: "RabbitMQ management client is not configured"
    };
  }
  return await safeSource("rabbit", async () => {
    const base = String(options.rabbitManagementBaseUrl).replace(/\/+$/, "");
    const authorization = typeof options.rabbitAuth === "function"
      ? options.rabbitAuth()
      : options.rabbitAuth;
    const headers = authorization ? { authorization } : {};
    const names = rabbitExactResourceNames(input, manifestSource, resources);
    const [queues, exchanges, unmanagedQueues] = await Promise.all([
      Promise.all([...names.queues].sort().map(async (name) =>
        await readRabbitManagementObject(options.httpJson, rabbitQueueUrl(base, options.rabbitVhost, name), headers)
          .then((queue) => queue ? { ...queue, name: queue.name ?? name } : null))),
      Promise.all([...names.exchanges].sort().map(async (name) =>
        await readRabbitManagementObject(options.httpJson, rabbitExchangeUrl(base, options.rabbitVhost, name), headers)
          .then((exchange) => exchange ? { ...exchange, name: exchange.name ?? name } : null))),
      input.includeUnmanagedDiagnostics === true
        ? options.httpJson(`${base}/queues`, { headers })
        : Promise.resolve([])
    ]);
    return {
      queues: queues.filter(Boolean),
      exchanges: exchanges.filter(Boolean),
      unmanagedQueues: Array.isArray(unmanagedQueues) ? unmanagedQueues : [],
      exactQueueNames: [...names.queues].sort(),
      exactExchangeNames: [...names.exchanges].sort(),
      manifestAvailable: manifestSource.available === true
    };
  });
}

async function readRuntimeInventory(input, options = {}) {
  if (!options.httpJson) {
    return {
      available: false,
      reason: "Orchestrator runtime debug API is not configured"
    };
  }
  return await safeSource("runtimeInventory", () => options.httpJson("/api/runtime/debug/resources/list", {
    method: "POST",
    body: cleanApiBody({
      computeAdapter: input.computeAdapter,
      swarmId: input.swarmId,
      runId: input.runId,
      includeManagers: true
    })
  }));
}

async function readOrchestratorSnapshot(swarmId, options = {}) {
  if (!options.httpJson) {
    return {
      available: false,
      reason: "Orchestrator HTTP client is not configured"
    };
  }
  return await safeSource("orchestratorSnapshot", () =>
    options.httpJson(`/api/swarms/${encodeURIComponent(swarmId)}`));
}

async function readSwarmJournal(input, options = {}) {
  if (!options.httpJson) {
    return {
      available: false,
      reason: "Orchestrator HTTP client is not configured",
      limit: input.limit
    };
  }
  const params = new URLSearchParams();
  params.set("limit", String(input.limit ?? DEFAULT_JOURNAL_LIMIT));
  if (hasText(input.runId)) {
    params.set("runId", input.runId);
  }
  const source = await safeSource("journal", () =>
    options.httpJson(`/api/swarms/${encodeURIComponent(input.swarmId)}/journal/page?${params.toString()}`));
  source.limit = input.limit ?? DEFAULT_JOURNAL_LIMIT;
  return source;
}

async function readCleanupPlan(input, options = {}) {
  if (!options.httpJson) {
    return {
      available: false,
      skipped: true,
      reason: "Orchestrator HTTP client is not configured"
    };
  }
  return safeSource("cleanupPlan", () => options.httpJson("/api/runtime/cleanup/plan", {
    method: "POST",
    body: cleanApiBody(input)
  }));
}

async function safeSource(name, fn) {
  try {
    const data = await fn();
    if (data && typeof data === "object" && Object.prototype.hasOwnProperty.call(data, "available")) {
      return data;
    }
    return {
      available: true,
      data
    };
  } catch (error) {
    return {
      available: false,
      source: name,
      error: error instanceof Error ? error.message : String(error)
    };
  }
}

function sourceSummary(source = {}) {
  return {
    available: source.available === true,
    skipped: source.skipped === true || undefined,
    reason: source.reason ?? undefined,
    error: source.error ?? undefined,
    path: source.path ?? undefined
  };
}

function runtimeResourcesFromListResponse(response = {}) {
  return [...(response.managers ?? []), ...(response.workers ?? [])].map((entry) => ({
    runtimeId: entry.runtimeId,
    runtimeType: entry.runtimeType,
    name: entry.name,
    image: entry.image,
    state: entry.state,
    createdAt: entry.createdAt,
    startedAt: entry.startedAt,
    finishedAt: entry.finishedAt,
    labels: entry.labels ?? {}
  }));
}

function runtimeListEntry(resource) {
  const labels = resource.labels;
  const imageMetadata = parseImageReference(resource.image ?? labels[LABELS.image] ?? null);
  const declaredVersion = optionalText(labels[LABELS.version]);
  return {
    runtimeId: resource.runtimeId,
    runtimeType: resource.runtimeType,
    name: resource.name,
    resourceKind: labels[LABELS.resourceKind],
    swarmId: labels[LABELS.swarmId],
    runId: labels[LABELS.runId],
    role: labels[LABELS.role],
    instance: labels[LABELS.instance],
    logicalName: labels[LABELS.logicalName] ?? null,
    state: resource.state,
    running: isRunningRuntime(resource),
    image: imageMetadata.image,
    imageTag: imageMetadata.imageTag,
    imageDigest: imageMetadata.imageDigest,
    declaredVersion,
    reportedVersion: declaredVersion ?? imageMetadata.imageTag ?? null,
    createdAt: resource.createdAt,
    startedAt: resource.startedAt,
    finishedAt: resource.finishedAt,
    registryStatus: "unknown",
    labels: pockethiveLabelsOnly(labels)
  };
}

function containerInspectSummary(raw) {
  if (!raw) {
    return {
      state: null,
      restartCount: null,
      restartPolicy: null,
      mounts: [],
      networks: []
    };
  }
  return {
    state: {
      status: raw.State?.Status ?? null,
      running: raw.State?.Running ?? null,
      exitCode: raw.State?.ExitCode ?? null,
      error: raw.State?.Error || null,
      health: raw.State?.Health?.Status ?? null,
      startedAt: raw.State?.StartedAt ?? null,
      finishedAt: raw.State?.FinishedAt ?? null
    },
    createdAt: raw.Created ?? null,
    restartCount: raw.RestartCount ?? null,
    restartPolicy: raw.HostConfig?.RestartPolicy?.Name ?? null,
    mounts: sanitizeContainerMounts(raw.Mounts),
    networks: Object.keys(raw.NetworkSettings?.Networks ?? {}).sort()
  };
}

function serviceInspectSummary(raw) {
  if (!raw) {
    return {
      state: null,
      restartCount: null,
      restartPolicy: null,
      mounts: [],
      networks: []
    };
  }
  return {
    state: {
      status: "service",
      running: true,
      exitCode: null,
      error: null,
      health: null,
      startedAt: null,
      finishedAt: null
    },
    createdAt: raw.CreatedAt ?? null,
    restartCount: null,
    restartPolicy: raw.Spec?.TaskTemplate?.RestartPolicy?.Condition ?? null,
    mounts: sanitizeServiceMounts(raw.Spec?.TaskTemplate?.ContainerSpec?.Mounts),
    networks: (raw.Spec?.TaskTemplate?.Networks ?? raw.Spec?.Networks ?? [])
      .map((network) => network.Target ?? network.NetworkID ?? network.Name)
      .filter(Boolean)
      .sort()
  };
}

function sanitizeContainerMounts(mounts = []) {
  return (Array.isArray(mounts) ? mounts : []).map((mount) => ({
    type: mount.Type ?? null,
    name: mount.Name ?? null,
    destination: mount.Destination ?? null,
    mode: mount.Mode ?? null,
    rw: mount.RW ?? null,
    propagation: mount.Propagation ?? null,
    source: mount.Type === "volume" ? mount.Source ?? null : mount.Source ? "[REDACTED]" : null
  }));
}

function sanitizeServiceMounts(mounts = []) {
  return (Array.isArray(mounts) ? mounts : []).map((mount) => ({
    type: mount.Type ?? null,
    name: mount.Source && mount.Type === "volume" ? mount.Source : null,
    destination: mount.Target ?? null,
    mode: mount.ReadOnly ? "ro" : "rw",
    rw: mount.ReadOnly == null ? null : !mount.ReadOnly,
    source: mount.Type === "volume" ? mount.Source ?? null : mount.Source ? "[REDACTED]" : null
  }));
}

function normalizeRuntimeManifest(manifest) {
  return {
    swarmId: manifest?.swarmId ?? null,
    runId: manifest?.runId ?? null,
    templateId: manifest?.templateId ?? null,
    computeAdapter: manifest?.computeAdapter ?? null,
    createdAt: manifest?.createdAt ?? null,
    runtimeObjects: Array.isArray(manifest?.runtimeObjects) ? manifest.runtimeObjects : [],
    rabbit: {
      controlQueues: Array.isArray(manifest?.rabbit?.controlQueues) ? manifest.rabbit.controlQueues : [],
      workQueues: Array.isArray(manifest?.rabbit?.workQueues) ? manifest.rabbit.workQueues : [],
      exchanges: Array.isArray(manifest?.rabbit?.exchanges) ? manifest.rabbit.exchanges : []
    }
  };
}

function manifestPath(root, swarmId, runId) {
  return resolve(root, safePathSegment(swarmId, "swarmId"), safePathSegment(runId, "runId"), MANIFEST_FILE);
}

function latestManifestPath(root, swarmId) {
  const swarmDir = resolve(root, safePathSegment(swarmId, "swarmId"));
  if (!existsSync(swarmDir)) {
    return null;
  }
  const manifests = readdirSync(swarmDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => resolve(swarmDir, entry.name, MANIFEST_FILE))
    .filter((file) => existsSync(file))
    .map((file) => {
      try {
        const manifest = JSON.parse(readFileSync(file, "utf8"));
        return { file, createdAt: Date.parse(manifest.createdAt ?? "") || 0 };
      } catch {
        return { file, createdAt: 0 };
      }
    })
    .sort((left, right) => right.createdAt - left.createdAt);
  return manifests[0]?.file ?? null;
}

function safePathSegment(value, name) {
  const segment = requireText(value, name);
  if (segment.includes("/") || segment.includes("\\") || segment === "." || segment === "..") {
    throw new Error(`${name} is not a safe path segment`);
  }
  return segment;
}

function rabbitQueuesByName(rabbitSource = {}) {
  const queues = rabbitSource.queues ?? rabbitSource.data?.queues ?? [];
  return new Map((Array.isArray(queues) ? queues : []).map((queue) => [queue.name, queue]));
}

function rabbitExchangesByName(rabbitSource = {}) {
  const exchanges = rabbitSource.exchanges ?? rabbitSource.data?.exchanges ?? [];
  return new Map((Array.isArray(exchanges) ? exchanges : []).map((exchange) => [exchange.name, exchange]));
}

function rabbitQueueSnapshot(name, queue) {
  return {
    name,
    present: Boolean(queue),
    messages: queue?.messages ?? queue?.messages_ready ?? null,
    consumers: queue?.consumers ?? null,
    state: queue?.state ?? null,
    durable: queue?.durable ?? null,
    autoDelete: queue?.auto_delete ?? null
  };
}

function rabbitExchangeSnapshot(name, exchange) {
  return {
    name,
    present: Boolean(exchange),
    type: exchange?.type ?? null,
    durable: exchange?.durable ?? null,
    autoDelete: exchange?.auto_delete ?? null
  };
}

function isSwarmRabbitDiagnosticName(name, swarmId) {
  const text = String(name ?? "");
  return text.startsWith(`ph.${swarmId}.`)
    || text.startsWith(`ph.control.${swarmId}.`)
    || text.includes(`.${swarmId}.`);
}

function rabbitExactResourceNames(input = {}, manifestSource = {}, resources = []) {
  const queues = new Set();
  const exchanges = new Set();
  const manifest = manifestSource.manifest ?? null;
  for (const queue of manifest?.rabbit?.controlQueues ?? []) {
    if (hasText(queue)) {
      queues.add(queue);
    }
  }
  for (const queue of manifest?.rabbit?.workQueues ?? []) {
    if (hasText(queue)) {
      queues.add(queue);
    }
  }
  for (const exchange of manifest?.rabbit?.exchanges ?? []) {
    if (hasText(exchange)) {
      exchanges.add(exchange);
    }
  }
  return { queues, exchanges };
}

async function readRabbitManagementObject(httpJson, url, headers) {
  try {
    return await httpJson(url, { headers });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (message.includes("HTTP 404")) {
      return null;
    }
    throw error;
  }
}

function rabbitQueueUrl(base, vhost, name) {
  return `${base}/queues/${encodeRabbitPathSegment(vhost)}/${encodeRabbitPathSegment(name)}`;
}

function rabbitExchangeUrl(base, vhost, name) {
  return `${base}/exchanges/${encodeRabbitPathSegment(vhost)}/${encodeRabbitPathSegment(name)}`;
}

function encodeRabbitPathSegment(value) {
  const text = value == null || value === "" ? "/" : String(value);
  return encodeURIComponent(text);
}

function normalizeJournalEntries(data) {
  if (Array.isArray(data)) {
    return data;
  }
  if (Array.isArray(data?.items)) {
    return data.items;
  }
  if (Array.isArray(data?.entries)) {
    return data.entries;
  }
  if (Array.isArray(data?.events)) {
    return data.events;
  }
  return [];
}

function latestJournalEventForWorker(entries, worker, keywords = []) {
  const lowered = keywords.map((keyword) => keyword.toLowerCase());
  const match = entries.find((entry) => {
    const scope = entry.scope ?? {};
    const role = scope.role ?? entry.role ?? entry.scopeRole;
    const instance = scope.instance ?? entry.instance ?? entry.scopeInstance;
    if (role !== worker.role || instance !== worker.instance) {
      return false;
    }
    const text = journalText(entry).toLowerCase();
    return lowered.some((keyword) => text.includes(keyword));
  });
  return match ? summarizeJournalEntry(match) : null;
}

function summarizeJournalEntry(entry) {
  return {
    timestamp: entry.timestamp ?? entry.ts ?? entry.time ?? null,
    eventId: entry.eventId ?? entry.id ?? null,
    severity: entry.severity ?? null,
    direction: entry.direction ?? null,
    kind: entry.kind ?? null,
    type: entry.type ?? null,
    origin: entry.origin ?? null,
    scope: summarizeJournalScope(entry),
    correlationId: entry.correlationId ?? null,
    routingKey: entry.routingKey ?? null,
    summary: journalSummary(entry)
  };
}

function summarizeJournalScope(entry) {
  const scope = entry.scope ?? {};
  return {
    swarmId: scope.swarmId ?? entry.swarmId ?? null,
    role: scope.role ?? entry.role ?? entry.scopeRole ?? null,
    instance: scope.instance ?? entry.instance ?? entry.scopeInstance ?? null
  };
}

function journalSummary(entry) {
  const parts = [
    entry.kind,
    entry.type,
    entry.direction,
    entry.routingKey
  ].filter(Boolean);
  const dataType = entry.data?.type ?? entry.raw?.type ?? entry.extra?.type;
  if (dataType) {
    parts.push(dataType);
  }
  return parts.join(" | ") || "journal event";
}

function journalText(entry) {
  return JSON.stringify({
    kind: entry.kind,
    type: entry.type,
    direction: entry.direction,
    routingKey: entry.routingKey,
    dataType: entry.data?.type,
    rawType: entry.raw?.type,
    extraType: entry.extra?.type
  });
}

function runtimeTimelineState(entry) {
  return {
    runtimeId: entry.runtimeId,
    resourceKind: entry.resourceKind,
    role: entry.role,
    instance: entry.instance,
    state: entry.state,
    running: entry.running,
    image: entry.image,
    createdAt: entry.createdAt,
    startedAt: entry.startedAt,
    finishedAt: entry.finishedAt
  };
}

function runtimeDriftEntry(resource) {
  const normalized = resource.labels ? normalizeRuntimeResource(resource) : resource;
  const labels = normalized.labels ?? {};
  return {
    runtimeId: normalized.runtimeId,
    runtimeType: normalized.runtimeType,
    name: normalized.name ?? null,
    resourceKind: normalized.resourceKind ?? labels[LABELS.resourceKind] ?? null,
    role: normalized.role ?? labels[LABELS.role] ?? null,
    instance: normalized.instance ?? labels[LABELS.instance] ?? null,
    runId: normalized.runId ?? labels[LABELS.runId] ?? null,
    state: normalized.state ?? null,
    image: normalized.image ?? labels[LABELS.image] ?? null
  };
}

function summarizeSwarmSnapshot(snapshot) {
  if (!snapshot) {
    return null;
  }
  const payload = snapshot.snapshot ?? snapshot;
  return {
    receivedAt: snapshot.receivedAt ?? null,
    staleAfterSeconds: snapshot.staleAfterSeconds ?? null,
    status: payload.status ?? payload.lifecycleStatus ?? payload.state ?? null,
    templateId: payload.templateId ?? payload.runtime?.templateId ?? null,
    runId: payload.runId ?? payload.runtime?.runId ?? null,
    workerCount: Array.isArray(payload.bees)
      ? payload.bees.length
      : Array.isArray(payload.workers)
        ? payload.workers.length
        : null
  };
}

export function normalizeTailLines(value, options = {}) {
  const defaultLines = options.defaultLines ?? 200;
  const maxLines = options.maxLines ?? 2000;
  if (value == null) {
    return defaultLines;
  }
  const lines = Number(value);
  if (!Number.isInteger(lines) || lines < 1) {
    throw new Error("tailLines must be a positive integer");
  }
  if (lines > maxLines) {
    throw new Error(`tailLines must be less than or equal to ${maxLines}`);
  }
  return lines;
}

export function redactLogText(value) {
  return String(value ?? "")
    .replace(/\b(Authorization:\s*(?:Bearer|Basic)\s+)[^\s]+/gi, "$1[REDACTED]")
    .replace(/\b((?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key)\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;]+)/gi, "$1[REDACTED]");
}

export function buildWorkerVersion(target) {
  const imageMetadata = parseImageReference(target?.image ?? target?.labels?.[LABELS.image] ?? null);
  const declaredVersion = optionalText(target?.labels?.[LABELS.version]);
  const reportedVersion = declaredVersion ?? imageMetadata.imageTag ?? null;
  return {
    target,
    declaredVersion,
    image: imageMetadata.image,
    imageTag: imageMetadata.imageTag,
    imageDigest: imageMetadata.imageDigest,
    reportedVersion,
    reportedVersionSource: declaredVersion
      ? LABELS.version
      : imageMetadata.imageTag
        ? "imageTag"
        : null
  };
}

export function parseImageReference(value) {
  const image = optionalText(value);
  if (!image) {
    return {
      image: null,
      imageTag: null,
      imageDigest: null
    };
  }

  const digestIndex = image.indexOf("@");
  const imageWithoutDigest = digestIndex >= 0 ? image.slice(0, digestIndex) : image;
  const imageDigest = digestIndex >= 0 ? optionalText(image.slice(digestIndex + 1)) : null;
  const lastSlash = imageWithoutDigest.lastIndexOf("/");
  const lastColon = imageWithoutDigest.lastIndexOf(":");
  const imageTag = lastColon > lastSlash ? optionalText(imageWithoutDigest.slice(lastColon + 1)) : null;

  return {
    image,
    imageTag,
    imageDigest
  };
}

function normalizeRuntimeResource(resource) {
  return {
    runtimeId: requireText(resource.runtimeId, "runtimeId"),
    runtimeType: requireText(resource.runtimeType, "runtimeType"),
    name: optionalText(resource.name),
    image: optionalText(resource.image),
    state: optionalText(resource.state) ?? "unknown",
    createdAt: optionalText(resource.createdAt),
    startedAt: optionalText(resource.startedAt),
    finishedAt: optionalText(resource.finishedAt),
    labels: resource.labels && typeof resource.labels === "object" ? resource.labels : {}
  };
}

function workerLogTarget(resource) {
  const labels = resource.labels;
  return {
    runtimeId: resource.runtimeId,
    runtimeType: resource.runtimeType,
    name: resource.name,
    resourceKind: labels[LABELS.resourceKind],
    swarmId: labels[LABELS.swarmId],
    runId: labels[LABELS.runId],
    role: labels[LABELS.role],
    instance: labels[LABELS.instance],
    logicalName: labels[LABELS.logicalName] ?? null,
    state: resource.state,
    image: resource.image ?? labels[LABELS.image] ?? null,
    labels: pockethiveLabelsOnly(labels)
  };
}

function blockedResource(resource, reason) {
  return {
    runtimeId: resource.runtimeId,
    runtimeType: resource.runtimeType,
    name: resource.name,
    state: resource.state,
    reason,
    labels: pockethiveLabelsOnly(resource.labels)
  };
}

function pockethiveLabelsOnly(labels) {
  return Object.fromEntries(
    Object.entries(labels ?? {})
      .filter(([key]) => key.startsWith("pockethive."))
      .sort(([left], [right]) => left.localeCompare(right))
  );
}

function isRunningRuntime(resource) {
  if (resource.runtimeType === "service") {
    return true;
  }
  return ["running", "restarting", "created", "paused"].includes(
    String(resource.state ?? "").toLowerCase()
  );
}

function compareRuntimeId(left, right) {
  return left.runtimeId.localeCompare(right.runtimeId);
}

function optionalText(value) {
  if (value == null) {
    return null;
  }
  const text = String(value).trim();
  return text.length > 0 ? text : null;
}

function requireText(value, name) {
  const text = optionalText(value);
  if (!text) {
    throw new Error(`${name} must not be blank`);
  }
  return text;
}

function hasText(value) {
  return optionalText(value) != null;
}
