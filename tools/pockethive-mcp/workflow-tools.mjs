import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { dirname, resolve } from "node:path";

export function registerWorkflowTools(deps) {
  const {
    z,
    reg,
    BASE_URL,
    ORCH_URL,
    SM_URL,
    RABBIT_MGMT,
    PROM_URL,
    POCKETHIVE_ROOT,
    REPO_ROOT,
    getBundlesDir,
    bundleDir,
    ensureInside,
    SWARM_ID_ARG,
    WIZARD_TRAFFIC_SHAPES,
    WIZARD_DATA_SOURCES,
    WIZARD_SUT_DOUBLES,
    wizardMissingQuestions,
    wizardAnswersWithDefaults,
    wizardTarget,
    wizardPattern,
    wizardMockEndpoints,
    writeWizardBundle,
    runBundleCheck,
    scenarioManagerDryRunValidateBundle,
    scenarioManagerUploadBundle,
    httpJson,
    idempotencyKey,
    buildEvidenceSummary,
  } = deps;

  // ── Agent-managed workflows ──────────────────────────────────────────────────

  const WORKFLOW_SESSIONS = new Map();
  const WORKFLOW_SESSION_TTL_MS = 24 * 60 * 60 * 1000;
  const WORKFLOW_SOURCE_MAX_BYTES = 250_000;
  const WORKFLOW_TYPE = "agent-to-pockethive";

  const WORKFLOW_REQUIRED_FIELDS = [
    {
      id: "plan.bundleId",
      prompt: "What should the generated PocketHive bundle id be?",
      type: "string",
    },
    {
      id: "plan.protocol",
      prompt: "Which protocol should PocketHive drive for this test?",
      type: "enum",
      options: ["REST", "TCP", "SEQUENCE"],
    },
    {
      id: "plan.target",
      prompt: "Which target strategy should the workflow use?",
      type: "enum",
      options: ["wiremock-local", "tcp-mock-local", "external"],
    },
    {
      id: "plan.traffic.ratePerSec",
      prompt: "What request/message rate should the generated test use?",
      type: "number",
    },
    {
      id: "plan.traffic.shape",
      prompt: "What traffic shape should the generated test use?",
      type: "enum",
      options: WIZARD_TRAFFIC_SHAPES,
    },
    {
      id: "plan.traffic.duration",
      prompt: "How long should the generated test run?",
      type: "duration",
    },
    {
      id: "plan.dataset.strategy",
      prompt: "What dataset strategy should the generated test use?",
      type: "enum",
      options: WIZARD_DATA_SOURCES,
    },
    {
      id: "plan.mock.strategy",
      prompt: "What mock or real-SUT strategy should be used?",
      type: "enum",
      options: WIZARD_SUT_DOUBLES,
    },
    {
      id: "plan.observability.goal",
      prompt: "What evidence do stakeholders need from this run?",
      type: "text",
    },
    {
      id: "plan.successCriteria",
      prompt: "What success criteria should the workflow prove?",
      type: "object-or-text",
    },
  ];

  function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
  }

  function readJsonArrayEnv(name) {
    try {
      const value = process.env[name];
      if (!value) return [];
      const parsed = JSON.parse(value);
      return Array.isArray(parsed) ? parsed.filter(item => typeof item === "string" && item.trim()) : [];
    } catch {
      return [];
    }
  }

  function workflowAllowedSourceRoots() {
    return [...new Set([
      getBundlesDir(),
      POCKETHIVE_ROOT || REPO_ROOT,
      ...readJsonArrayEnv("PH_WORKFLOW_SOURCE_ROOTS"),
    ].filter(Boolean).map(root => resolve(root)))];
  }

  function resolveWorkflowSourcePath(sourcePath) {
    const path = resolve(String(sourcePath || ""));
    for (const root of workflowAllowedSourceRoots()) {
      try {
        ensureInside(root, path);
        return path;
      } catch { /* try the next explicit root */ }
    }
    const allowed = workflowAllowedSourceRoots().join(", ");
    throw new Error(`WORKFLOW_SOURCE_OUTSIDE_ALLOWED_ROOTS: ${path} is outside allowed roots: ${allowed}`);
  }

  function cleanupWorkflowSessions() {
    const now = Date.now();
    for (const [workflowId, session] of WORKFLOW_SESSIONS.entries()) {
      if (now - session.createdAtMs > WORKFLOW_SESSION_TTL_MS) WORKFLOW_SESSIONS.delete(workflowId);
    }
  }

  function workflowSession(workflowId) {
    const session = WORKFLOW_SESSIONS.get(workflowId);
    if (!session) throw new Error(`WORKFLOW_NOT_FOUND: ${workflowId}`);
    if (Date.now() - session.createdAtMs > WORKFLOW_SESSION_TTL_MS) {
      WORKFLOW_SESSIONS.delete(workflowId);
      throw new Error(`WORKFLOW_EXPIRED: ${workflowId}`);
    }
    return session;
  }

  function getPath(obj, path) {
    return path.split(".").reduce((current, part) => current && current[part], obj);
  }

  function hasValue(value) {
    if (value === false || value === 0) return true;
    if (Array.isArray(value)) return value.length > 0;
    if (value && typeof value === "object") return Object.keys(value).length > 0;
    return value !== undefined && value !== null && String(value).trim() !== "";
  }

  function normalizeWorkflowPlan(plan = {}) {
    const traffic = plan.traffic || {};
    const dataset = plan.dataset || {};
    const mock = plan.mock || {};
    const observability = plan.observability || {};
    const auth = plan.auth || {};
    const success = plan.successCriteria || {};
    const successObject = success && typeof success === "object" && !Array.isArray(success) ? success : {};
    const successEnabled = successObject.resultRules === true || Boolean(successObject.resultCodePattern || successObject.successCodes);
    return {
      bundleId: plan.bundleId,
      protocol: plan.protocol,
      target: plan.target,
      targetBaseUrl: plan.targetBaseUrl,
      endpoints: plan.endpoints,
      endpoint: plan.endpoint,
      requestBody: plan.requestBody,
      tcpPayload: plan.tcpPayload,
      defaultRatePerSec: traffic.ratePerSec ?? plan.defaultRatePerSec ?? plan.ratePerSec,
      nftRatePerSec: traffic.nftRatePerSec ?? plan.nftRatePerSec,
      trafficShape: traffic.shape ?? plan.trafficShape,
      runDuration: traffic.duration ?? plan.runDuration,
      nftDuration: traffic.nftDuration ?? plan.nftDuration,
      dataSource: dataset.strategy ?? plan.dataSource,
      csvColumns: dataset.csvColumns ?? plan.csvColumns,
      redisLists: dataset.redisLists ?? plan.redisLists,
      redisOutput: dataset.redisOutput ?? plan.redisOutput,
      sutDouble: mock.strategy ?? plan.sutDouble,
      mockEndpoints: mock.endpoints ?? plan.mockEndpoints,
      auth: auth.type ?? plan.auth ?? "none",
      authTokenUrl: auth.tokenUrl ?? plan.authTokenUrl,
      authClientId: auth.clientId ?? plan.authClientId,
      authSecretSource: auth.secretSource ?? plan.authSecretSource,
      authSecretEnvVar: auth.secretEnvVar ?? plan.authSecretEnvVar,
      sutNftUrl: plan.sutNftUrl,
      resultRules: successEnabled ? "yes" : "no",
      resultCodePattern: successObject.resultCodePattern ?? plan.resultCodePattern,
      successCodes: successObject.successCodes ?? plan.successCodes,
      performanceObjective: observability.goal ?? plan.performanceObjective,
      clickhouse: observability.clickhouse ?? plan.clickhouse,
      grafanaDashboard: observability.grafanaDashboard ?? plan.grafanaDashboard,
      docs: "yes",
    };
  }

  function workflowMissingFields(session) {
    const plan = session.plan || {};
    const missing = [];
    for (const field of WORKFLOW_REQUIRED_FIELDS) {
      if (!hasValue(getPath({ plan }, field.id))) missing.push(field.id);
    }
    const wizardAnswers = normalizeWorkflowPlan(plan);
    const wizardMissing = wizardMissingQuestions(wizardAnswers).map(field => `plan.${field}`);
    for (const field of wizardMissing) {
      if (!missing.includes(field)) missing.push(field);
    }
    return missing;
  }

  function workflowNextQuestions(missing) {
    return missing.map(id => {
      const known = WORKFLOW_REQUIRED_FIELDS.find(field => field.id === id);
      if (known) return known;
      return {
        id,
        prompt: `Provide ${id.replace(/^plan\./, "")} for the generated PocketHive bundle.`,
        type: "value",
      };
    });
  }

  function workflowEvidenceGaps(session) {
    const gaps = [];
    if (!session.generated) gaps.push({ id: "bundle.generated", status: "missing" });
    if (!session.evidence?.validation) gaps.push({ id: "bundle.validated", status: "missing" });
    if (!session.evidence?.deployment) gaps.push({ id: "runtime.deployed", status: "not-run" });
    if (!session.evidence?.runtime) gaps.push({ id: "runtime.verified", status: "not-run" });
    if (!session.evidence?.report) gaps.push({ id: "stakeholder.report", status: "missing" });
    return gaps;
  }

  function workflowState(session) {
    if (session.reported) return "reported";
    if (session.evidence?.runtime?.ok) return "verified";
    if (session.evidence?.deployment?.ok) return "deployed";
    if (session.evidence?.validation?.ok) return "validated";
    if (session.generated) return "generated";
    if (!Object.keys(session.plan || {}).length) return "source_ready";
    return workflowMissingFields(session).length ? "plan_incomplete" : "plan_ready";
  }

  function workflowAllowedActions(session) {
    const actions = ["workflow.status", "workflow.source.read", "workflow.update"];
    const missing = workflowMissingFields(session);
    if (missing.length === 0 && !session.generated) actions.push("workflow.preview", "workflow.generate");
    if (session.generated) actions.push("workflow.patch", "workflow.validate", "workflow.report");
    if (session.evidence?.validation?.ok) actions.push("workflow.deploy");
    if (session.evidence?.deployment?.ok) actions.push("workflow.verify");
    return actions;
  }

  function workflowStatusPayload(session) {
    const missing = workflowMissingFields(session);
    return {
      workflowId: session.workflowId,
      workflowType: session.workflowType,
      state: workflowState(session),
      source: session.source,
      plan: session.plan || null,
      bundle: session.bundle || null,
      generated: session.generated || null,
      missing,
      nextQuestions: workflowNextQuestions(missing),
      allowedActions: workflowAllowedActions(session),
      evidenceGaps: workflowEvidenceGaps(session),
      evidence: session.evidence,
      history: session.history,
    };
  }

  function recordWorkflowAttempt(session, action, ok, details = {}) {
    const entry = {
      id: `${action}-${session.history.length + 1}`,
      action,
      ok,
      at: new Date().toISOString(),
      ...details,
    };
    session.history.push(entry);
    session.updatedAt = entry.at;
    return entry;
  }

  function ensureWorkflowPlanComplete(session) {
    const missing = workflowMissingFields(session);
    if (missing.length) {
      throw new Error(`WORKFLOW_PLAN_INCOMPLETE: ask/answer required questions before continuing: ${missing.join(", ")}`);
    }
  }

  function workflowWizardSession(session) {
    return {
      sessionId: `workflow-wizard-${session.workflowId}`,
      intent: `Agent-managed ${session.source.type} conversion from ${session.source.path || "inline instructions"}`,
      answers: normalizeWorkflowPlan(session.plan),
      createdAt: session.createdAt,
      createdAtMs: session.createdAtMs,
    };
  }

  function plannedWorkflowFiles(session) {
    const answers = wizardAnswersWithDefaults(normalizeWorkflowPlan(session.plan));
    const serviceId = answers.protocol === "SEQUENCE" ? "sequence" : "default";
    const protocolDir = answers.protocol === "TCP" ? "tcp" : "http";
    const files = ["scenario.yaml", "variables.yaml", `sut/${wizardTarget(answers).id}/sut.yaml`, "README.md", "FLOW_DOCUMENT.md", "CHANGELOG.md"];
    if (answers.auth !== "none") files.push("authProfiles.yaml");
    if (answers.protocol === "TCP") {
      files.push(`templates/tcp/${serviceId}/tcp-request.yaml`);
    } else {
      for (const endpoint of answers.endpoints || []) files.push(`templates/${protocolDir}/${serviceId}/${endpoint.callId}.yaml`);
    }
    if (answers.dataSource === "CSV_DATASET") files.push("datasets/sample.csv");
    if (answers.dataSource === "REDIS_DATASET") files.push("mock-config/redis-state.json");
    if (["wiremock", "wiremock_and_tcp"].includes(answers.sutDouble)) {
      for (const endpoint of wizardMockEndpoints(answers).filter(mock => mock.method && mock.path)) {
        files.push(`mock-config/wiremock/${endpoint.callId}.json`);
      }
    }
    if (["tcp_mock", "wiremock_and_tcp"].includes(answers.sutDouble)) files.push("mock-config/tcp/tcp-request.yaml");
    return [...new Set(files)];
  }

  function ensureWorkflowGenerated(session) {
    if (!session.generated?.bundleId) throw new Error("WORKFLOW_BUNDLE_NOT_GENERATED: call workflow.generate first");
  }

  function workflowBundlePath(session) {
    ensureWorkflowGenerated(session);
    return bundleDir(session.generated.bundleId);
  }

  function workflowPatchPath(session, file) {
    const bundlePath = workflowBundlePath(session);
    const target = resolve(bundlePath, file);
    try {
      ensureInside(bundlePath, target);
    } catch {
      throw new Error(`WORKFLOW_PATCH_OUTSIDE_BUNDLE: ${file}`);
    }
    return target;
  }

  function workflowReportMarkdown(session) {
    const status = workflowStatusPayload(session);
    const lines = [
      `# Workflow Evidence - ${session.generated?.bundleId || session.workflowId}`,
      "",
      `- Workflow: ${session.workflowId}`,
      `- Source type: ${session.source.type}`,
      `- Source: ${session.source.path || "inline instructions"}`,
      `- State: ${status.state}`,
      `- Generated at: ${new Date().toISOString()}`,
      "",
      "## Evidence",
      "",
      `- Bundle generated: ${session.generated ? "yes" : "no"}`,
      `- Validation: ${session.evidence.validation ? (session.evidence.validation.ok ? "pass" : "fail") : "not-run"}`,
      `- Deployment: ${session.evidence.deployment ? (session.evidence.deployment.ok ? "pass" : "fail") : "not-run"}`,
      `- Runtime verification: ${session.evidence.runtime ? (session.evidence.runtime.ok ? "pass" : "fail") : "not-run"}`,
      "",
      "## Attempt History",
      "",
      ...session.history.map(entry => `- ${entry.at} ${entry.action}: ${entry.ok ? "ok" : "failed"}${entry.code ? ` (${entry.code})` : ""}`),
    ];
    return lines.join("\n");
  }

  function workflowSessionSummary(session, includeQuestions = true) {
    const missing = workflowMissingFields(session);
    return {
      workflowId: session.workflowId,
      workflowType: session.workflowType,
      state: workflowState(session),
      source: {
        type: session.source.type,
        path: session.source.path,
        sha256: session.source.sha256,
        bytes: session.source.bytes,
      },
      bundle: session.bundle ? { id: session.bundle.id, path: session.bundle.path } : null,
      generated: session.generated ? { bundleId: session.generated.bundleId, path: session.generated.path } : null,
      missing,
      nextQuestions: includeQuestions ? workflowNextQuestions(missing) : [],
      allowedActions: workflowAllowedActions(session),
      evidenceGaps: workflowEvidenceGaps(session),
      historyCount: session.history.length,
      createdAt: session.createdAt,
      updatedAt: session.updatedAt || session.createdAt,
    };
  }

  function workflowConfigPayload() {
    return {
      workflowType: WORKFLOW_TYPE,
      sessionTtlMs: WORKFLOW_SESSION_TTL_MS,
      sourceMaxBytes: WORKFLOW_SOURCE_MAX_BYTES,
      supportedSourceTypes: ["jmeter", "postman", "openapi", "k6", "gatling", "curl", "plain-instructions", "other"],
      bundleRoot: resolve(getBundlesDir()),
      allowedSourceRoots: workflowAllowedSourceRoots(),
      runtime: {
        baseUrl: BASE_URL,
        orchestratorBaseUrl: ORCH_URL,
        scenarioManagerBaseUrl: SM_URL,
        rabbitmqManagementBaseUrl: RABBIT_MGMT,
        prometheusBaseUrl: PROM_URL,
      },
      pluginBoundary: {
        mayAnswerQuestions: false,
        readOnlyTools: ["workflow.config.get", "workflow.config.validate", "workflow.list", "workflow.status"],
        mutatingTools: "external-agent-only",
      },
    };
  }

  function workflowConfigValidation() {
    const config = workflowConfigPayload();
    const checks = [
      {
        id: "bundleRoot.exists",
        ok: existsSync(config.bundleRoot),
        value: config.bundleRoot,
        message: "Active bundle root exists.",
      },
      {
        id: "sourceRoots.present",
        ok: config.allowedSourceRoots.length > 0,
        value: config.allowedSourceRoots,
        message: "At least one workflow source root is configured.",
      },
      {
        id: "sourceRoots.exist",
        ok: config.allowedSourceRoots.every(root => existsSync(root)),
        value: config.allowedSourceRoots.filter(root => !existsSync(root)),
        message: "All configured workflow source roots exist.",
      },
      {
        id: "runtime.baseUrl.configured",
        ok: Boolean(String(config.runtime.baseUrl || "").trim()),
        value: config.runtime.baseUrl,
        message: "PocketHive base URL is configured.",
      },
    ];
    return {
      ok: checks.every(check => check.ok),
      checks,
      missing: checks.filter(check => !check.ok).map(check => check.id),
      config,
    };
  }

  const workflowReadOnly = { annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false } };
  const workflowMutating = { annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false } };
  const workflowRuntime = { annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: true } };

  reg("workflow.config.get", "Return sanitized workflow defaults and configured roots for plugin/status display.", {}, async () => {
    return workflowConfigPayload();
  }, workflowReadOnly);

  reg("workflow.config.validate", "Validate workflow configuration without creating files or calling runtime services.", {}, async () => {
    return workflowConfigValidation();
  }, workflowReadOnly);

  reg("workflow.list", "List in-memory workflow sessions for status display without returning answers or normalized plans.", {
    state: z.string().optional(),
    includeQuestions: z.boolean().optional().default(true),
  }, async ({ state, includeQuestions = true }) => {
    cleanupWorkflowSessions();
    const workflows = [...WORKFLOW_SESSIONS.values()]
      .map(session => workflowSessionSummary(session, includeQuestions))
      .filter(session => !state || session.state === state);
    return { workflows, count: workflows.length };
  }, workflowReadOnly);

  reg("workflow.start", "Start an agent-managed test-to-PocketHive workflow from a source file or instructions.", {
    sourceType: z.string().describe("Source kind, e.g. jmeter, postman, openapi, k6, gatling, curl, plain-instructions, or other."),
    sourcePath: z.string().optional().describe("Path to a source file inside an allowed source root."),
    instructions: z.string().optional().describe("Plain test instructions when there is no source file."),
    workflowType: z.literal(WORKFLOW_TYPE).optional().default(WORKFLOW_TYPE),
  }, async ({ sourceType, sourcePath, instructions, workflowType = WORKFLOW_TYPE }) => {
    cleanupWorkflowSessions();
    if (!sourcePath && !instructions) throw new Error("WORKFLOW_SOURCE_REQUIRED: provide sourcePath or instructions");
    let source;
    if (sourcePath) {
      const path = resolveWorkflowSourcePath(sourcePath);
      if (!existsSync(path)) throw new Error(`WORKFLOW_SOURCE_NOT_FOUND: ${path}`);
      const bytes = statSync(path).size;
      const content = readFileSync(path);
      source = { type: sourceType, path, sha256: sha256(content), bytes };
    } else {
      const content = String(instructions);
      source = { type: sourceType, path: null, sha256: sha256(content), bytes: Buffer.byteLength(content, "utf8"), instructions: content };
    }
    const workflowId = `wf-${crypto.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`}`;
    const session = {
      workflowId,
      workflowType,
      source,
      plan: {},
      answers: {},
      bundle: null,
      generated: null,
      evidence: {},
      history: [],
      createdAt: new Date().toISOString(),
      createdAtMs: Date.now(),
    };
    recordWorkflowAttempt(session, "start", true, { source: { type: source.type, path: source.path, sha256: source.sha256, bytes: source.bytes } });
    WORKFLOW_SESSIONS.set(workflowId, session);
    return workflowStatusPayload(session);
  }, workflowMutating);

  reg("workflow.source.read", "Read bounded workflow source content for external-agent interpretation.", {
    workflowId: z.string(),
    maxBytes: z.number().optional().default(WORKFLOW_SOURCE_MAX_BYTES),
  }, async ({ workflowId, maxBytes = WORKFLOW_SOURCE_MAX_BYTES }) => {
    const session = workflowSession(workflowId);
    const limit = Math.max(1, Math.min(maxBytes, WORKFLOW_SOURCE_MAX_BYTES));
    const fullContent = session.source.path ? readFileSync(session.source.path, "utf8") : session.source.instructions || "";
    return {
      workflowId,
      source: { type: session.source.type, path: session.source.path, sha256: session.source.sha256, bytes: session.source.bytes },
      content: fullContent.slice(0, limit),
      truncated: fullContent.length > limit,
    };
  }, workflowReadOnly);

  reg("workflow.update", "Update workflow answers and/or the external-agent normalized conversion plan.", {
    workflowId: z.string(),
    answers: z.object({}).passthrough().optional(),
    plan: z.object({}).passthrough().optional(),
  }, async ({ workflowId, answers, plan }) => {
    const session = workflowSession(workflowId);
    if (answers) session.answers = { ...session.answers, ...answers };
    if (plan) session.plan = { ...session.plan, ...plan };
    session.bundle = session.plan?.bundleId ? { id: session.plan.bundleId, path: bundleDir(session.plan.bundleId) } : null;
    recordWorkflowAttempt(session, "update", true, { changed: { answers: Boolean(answers), plan: Boolean(plan) }, missing: workflowMissingFields(session) });
    return workflowStatusPayload(session);
  }, workflowMutating);

  reg("workflow.status", "Return workflow state, missing fields, next questions, evidence gaps, allowed actions, and history.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => workflowStatusPayload(workflowSession(workflowId)), workflowReadOnly);

  reg("workflow.preview", "Preview generated workflow bundle artifacts without writing files.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowPlanComplete(session);
    const answers = wizardAnswersWithDefaults(normalizeWorkflowPlan(session.plan));
    return {
      workflowId,
      bundle: { id: answers.bundleId, path: bundleDir(answers.bundleId) },
      files: plannedWorkflowFiles(session),
      target: wizardTarget(answers),
      pattern: wizardPattern(answers),
      sideEffect: "no-file-write",
    };
  }, workflowReadOnly);

  reg("workflow.generate", "Generate the workflow bundle after required questions are answered.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowPlanComplete(session);
    const generated = await writeWizardBundle(workflowWizardSession(session));
    const structural = await runBundleCheck(generated.bundleId);
    session.generated = generated;
    session.bundle = { id: generated.bundleId, path: generated.path };
    const ok = structural.ok;
    session.evidence.generation = { ok, generated, structural };
    recordWorkflowAttempt(session, "generate", ok, { bundleId: generated.bundleId, filesChanged: generated.filesCreated, code: ok ? "WORKFLOW_GENERATED" : "WORKFLOW_GENERATION_STRUCTURAL_FAILURE" });
    return { ok, workflowId, generated, structural, state: workflowState(session), allowedActions: workflowAllowedActions(session) };
  }, workflowMutating);

  reg("workflow.validate", "Validate the generated workflow bundle and record structured evidence.", {
    workflowId: z.string(),
    validator: z.enum(["local-structural", "scenario-manager-dry-run"]).optional().default("local-structural"),
  }, async ({ workflowId, validator = "local-structural" }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    let evidence;
    let ok = false;
    let code = "WORKFLOW_VALIDATION_FAILED";
    try {
      const structural = await runBundleCheck(session.generated.bundleId);
      if (!structural.ok) {
        evidence = { validator, structural };
      } else if (validator === "scenario-manager-dry-run") {
        evidence = { validator, structural, scenarioManager: await scenarioManagerDryRunValidateBundle(session.generated.bundleId) };
        ok = true;
        code = "WORKFLOW_VALIDATED";
      } else {
        evidence = { validator, structural };
        ok = true;
        code = "WORKFLOW_VALIDATED";
      }
    } catch (e) {
      evidence = { validator, error: e.message };
    }
    session.evidence.validation = { ok, code, evidence };
    recordWorkflowAttempt(session, "validate", ok, { code, evidence });
    return { ok, code, workflowId, evidence, state: workflowState(session), allowedActions: workflowAllowedActions(session) };
  }, workflowMutating);

  reg("workflow.deploy", "Deploy the generated workflow bundle and create/wait/start a swarm through official PocketHive APIs.", {
    workflowId: z.string(),
    swarmId: SWARM_ID_ARG.optional(),
    sutId: z.string().optional(),
    variablesProfileId: z.string().optional(),
    readyTimeoutSec: z.number().optional().default(90),
  }, async ({ workflowId, swarmId, sutId, variablesProfileId = "default", readyTimeoutSec = 90 }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    const effectiveSwarmId = swarmId || `${session.generated.bundleId}-swarm`;
    let evidence;
    let ok = false;
    let code = "WORKFLOW_DEPLOY_FAILED";
    try {
      const deploy = await scenarioManagerUploadBundle(session.generated.bundleId, { replaceExisting: true });
      const target = session.generated.target;
      const createBody = { templateId: session.generated.bundleId, idempotencyKey: idempotencyKey() };
      if (sutId || target?.id) createBody.sutId = sutId || target.id;
      if (variablesProfileId) createBody.variablesProfileId = variablesProfileId;
      const create = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}/create`, { method: "POST", body: createBody });
      const ready = await (async () => {
        const deadline = Date.now() + Math.min(readyTimeoutSec, 80) * 1000;
        let last = null;
        while (Date.now() < deadline) {
          try {
            const status = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}`);
            last = status;
            const ctx = status?.envelope?.data?.context;
            const { desired, healthy } = ctx?.totals || {};
            if (desired > 0 && healthy >= desired && ctx?.swarmStatus === "READY") return { ready: true, status };
          } catch { /* keep polling */ }
          await new Promise(resolve => setTimeout(resolve, 4000));
        }
        return { ready: false, status: last };
      })();
      const start = ready.ready
        ? await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}/start`, { method: "POST", body: { idempotencyKey: idempotencyKey() } })
        : null;
      ok = Boolean(deploy?.uploaded && ready.ready && start !== null);
      code = ok ? "WORKFLOW_DEPLOYED" : "WORKFLOW_DEPLOY_NOT_READY";
      evidence = { swarmId: effectiveSwarmId, deploy, create, ready, start };
    } catch (e) {
      evidence = { swarmId: effectiveSwarmId, error: e.message };
    }
    session.swarmId = effectiveSwarmId;
    session.evidence.deployment = { ok, code, evidence };
    recordWorkflowAttempt(session, "deploy", ok, { code, evidence });
    return { ok, code, workflowId, swarmId: effectiveSwarmId, evidence, state: workflowState(session), allowedActions: workflowAllowedActions(session) };
  }, workflowRuntime);

  reg("workflow.verify", "Collect runtime proof for a workflow swarm from existing evidence sources.", {
    workflowId: z.string(),
    swarmId: SWARM_ID_ARG.optional(),
    includeTapSample: z.boolean().optional().default(false),
  }, async ({ workflowId, swarmId, includeTapSample = false }) => {
    const session = workflowSession(workflowId);
    const effectiveSwarmId = swarmId || session.swarmId;
    let evidence;
    let ok = false;
    let code = "WORKFLOW_RUNTIME_NOT_STARTED";
    if (!effectiveSwarmId) {
      evidence = { error: "No swarmId is available. Call workflow.deploy or pass swarmId." };
    } else {
      try {
        evidence = await buildEvidenceSummary({ swarmId: effectiveSwarmId, includeTapSample, scenarioId: session.generated?.bundleId });
        ok = evidence?.report?.verdict === "pass" || evidence?.report?.verdict === "partial";
        code = ok ? "WORKFLOW_RUNTIME_VERIFIED" : "WORKFLOW_RUNTIME_EVIDENCE_INCOMPLETE";
      } catch (e) {
        code = "WORKFLOW_VERIFY_FAILED";
        evidence = { error: e.message };
      }
    }
    session.evidence.runtime = { ok, code, evidence };
    recordWorkflowAttempt(session, "verify", ok, { code, evidence });
    return { ok, code, workflowId, swarmId: effectiveSwarmId || null, evidence, state: workflowState(session), allowedActions: workflowAllowedActions(session) };
  }, workflowRuntime);

  reg("workflow.patch", "Apply explicit agent-provided file fixes inside the generated workflow bundle.", {
    workflowId: z.string(),
    changes: z.array(z.object({
      file: z.string(),
      content: z.string(),
    })),
  }, async ({ workflowId, changes }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    const changedFiles = [];
    for (const change of changes) {
      const target = workflowPatchPath(session, change.file);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, change.content, "utf8");
      changedFiles.push(change.file);
    }
    recordWorkflowAttempt(session, "patch", true, { changedFiles });
    return { ok: true, workflowId, changedFiles, state: workflowState(session), allowedActions: workflowAllowedActions(session) };
  }, workflowMutating);

  reg("workflow.report", "Write a stakeholder workflow evidence report and return canonical JSON evidence.", {
    workflowId: z.string(),
    file: z.string().optional().default("WORKFLOW_EVIDENCE.md"),
  }, async ({ workflowId, file = "WORKFLOW_EVIDENCE.md" }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    const target = workflowPatchPath(session, file);
    mkdirSync(dirname(target), { recursive: true });
    const markdown = workflowReportMarkdown(session);
    writeFileSync(target, markdown, "utf8");
    session.reported = true;
    session.evidence.report = { ok: true, file };
    recordWorkflowAttempt(session, "report", true, { file });
    return { ok: true, workflowId, file, report: workflowStatusPayload(session), markdown };
  }, workflowMutating);
}
