import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { dirname, resolve } from "node:path";

export function registerWorkflowTools(deps) {
  const {
    z,
    reg,
    exposedToolName = (name) => String(name).replace(/[.-]/g, "_"),
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
    validateWizardAnswers = () => [],
    writeWizardBundle,
    runBundleCheck,
    scenarioManagerDryRunValidateBundle,
    scenarioManagerUploadBundle,
    loadBundleMockConfig = async (bundleId) => ({ bundle: bundleId, wiremock: { attempted: false, loaded: 0, files: [] }, requestJournal: { reset: false } }),
    httpJson,
    idempotencyKey,
    buildEvidenceSummary,
    WORKFLOW_EVIDENCE_WIDGET_URI = "ui://pockethive/workflow-evidence-v1.html",
  } = deps;

  // ── Agent-managed workflows ──────────────────────────────────────────────────

  const WORKFLOW_SESSIONS = new Map();
  const WORKFLOW_SESSION_TTL_MS = 24 * 60 * 60 * 1000;
  const WORKFLOW_SOURCE_MAX_BYTES = 250_000;
  const WORKFLOW_TYPE = "agent-to-pockethive";
  const WORKFLOW_MODES = Object.freeze(["create", "modify"]);
  const REPO_EXAMPLES_ROOT = resolve(REPO_ROOT, "scenarios", "bundles");
  const toolName = exposedToolName;
  const sameToolAction = (left, right) => toolName(left) === toolName(right);
  const ROLE_IDS = Object.freeze({
    ARCHITECT: "architect",
    DEVELOPER: "developer",
    TESTER: "tester",
    SECURITY_REVIEWER: "security-reviewer",
    PERFORMANCE_TESTING_SPECIALIST: "performance-testing-specialist",
    POCKETHIVE_SME: "pockethive-sme",
  });
  const PROFILE_IDS = Object.freeze({
    NOVICE_TEST_BUILDER: "novice-test-builder",
    TEST_CONVERSION_SPECIALIST: "test-conversion-specialist",
    PERFORMANCE_ENGINEER: "performance-engineer",
    RUNTIME_VERIFIER: "runtime-verifier",
    MAINTAINER: "maintainer",
  });
  const DEFAULT_PROFILE_ID = PROFILE_IDS.NOVICE_TEST_BUILDER;
  const GUIDANCE_AUTHORITY = "guidance-only";
  const SOURCE_FIDELITY_STATUSES = Object.freeze(["complete", "partial-accepted", "instruction-derived"]);
  const SOURCE_BACKED_TYPES = new Set(["jmeter", "postman", "openapi", "k6", "gatling", "curl"]);
  const WORKFLOW_HTTP_METHODS = new Set(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]);
  const AGENT_VERDICTS = Object.freeze({
    NEEDS_INPUT: "needs_input",
    READY: "ready",
    RUNNING: "running",
    FAILED: "failed",
    PASSED: "passed",
    PARTIAL: "partial",
  });
  const AGENT_PHASES = Object.freeze({
    INTAKE: "intake",
    PLANNING: "planning",
    AUTHORING: "authoring",
    VALIDATION: "validation",
    DEPLOYMENT: "deployment",
    RUNTIME: "runtime",
    REPORT: "report",
    EVIDENCE: "evidence",
  });
  const AGENT_NEXT_ACTION_KINDS = Object.freeze({
    ANSWER: "answer",
    UPDATE: "update",
    CHECK: "check",
    REVIEW: "review",
    GENERATE: "generate",
    VALIDATE: "validate",
    PATCH: "patch",
    DEPLOY: "deploy",
    VERIFY: "verify",
    REPORT: "report",
    RESUME: "resume",
    NONE: "none",
  });
  const WORKFLOW_PROOF_MODES = Object.freeze(["accept-partial", "strict"]);

  const WORKFLOW_REQUIRED_FIELDS = [
    {
      id: "plan.bundleId",
      prompt: "What should the generated PocketHive bundle id be?",
      type: "string",
      answerType: "slug",
      examples: ["google-smoke", "checkout-nft"],
      whyAsked: "PocketHive uses the bundle id as the folder name and scenario id.",
    },
    {
      id: "plan.protocol",
      prompt: "Which protocol should PocketHive drive for this test?",
      type: "enum",
      options: ["REST", "TCP", "SEQUENCE"],
      answerType: "enum",
      examples: ["REST"],
      whyAsked: "Protocol selects the generated worker/template shape.",
    },
    {
      id: "plan.target",
      prompt: "Which target strategy should the workflow use?",
      type: "enum",
      options: ["wiremock-local", "tcp-mock-local", "external"],
      answerType: "enum",
      examples: ["wiremock-local", "external"],
      whyAsked: "Target strategy decides whether the bundle uses mocks or a real system.",
    },
    {
      id: "plan.traffic.ratePerSec",
      prompt: "What request/message rate should the generated test use?",
      type: "number",
      answerType: "positive-number",
      examples: [1, 5],
      whyAsked: "Rate is required to shape traffic and avoid accidental load.",
    },
    {
      id: "plan.traffic.shape",
      prompt: "What traffic shape should the generated test use?",
      type: "enum",
      options: WIZARD_TRAFFIC_SHAPES,
      answerType: "enum",
      examples: ["smoke", "flat"],
      whyAsked: "Shape defines how PocketHive schedules traffic over the run.",
    },
    {
      id: "plan.traffic.duration",
      prompt: "How long should the generated test run?",
      type: "duration",
      answerType: "duration",
      examples: ["30s", "5m"],
      whyAsked: "Duration is required for repeatable validation and stakeholder evidence.",
    },
    {
      id: "plan.dataset.strategy",
      prompt: "What dataset strategy should the generated test use?",
      type: "enum",
      options: WIZARD_DATA_SOURCES,
      answerType: "enum",
      examples: ["SCHEDULER", "CSV_DATASET"],
      whyAsked: "Dataset strategy decides whether generated artifacts need datasets or Redis state.",
      canBeNotApplicable: false,
    },
    {
      id: "plan.mock.strategy",
      prompt: "What mock or real-SUT strategy should be used?",
      type: "enum",
      options: WIZARD_SUT_DOUBLES,
      answerType: "enum",
      examples: ["wiremock", "real_system"],
      whyAsked: "Mock strategy controls runtime safety and evidence expectations.",
    },
    {
      id: "plan.observability.goal",
      prompt: "What evidence do stakeholders need from this run?",
      type: "text",
      answerType: "text",
      examples: ["worker health, queue drain, mock matches, and latency"],
      whyAsked: "Observability goals drive the evidence collected in the final report.",
    },
    {
      id: "plan.successCriteria",
      prompt: "What success criteria should the workflow prove?",
      type: "object-or-text",
      answerType: "object-or-text",
      examples: ["HTTP 2xx responses and no unmatched mock requests."],
      whyAsked: "Success criteria make pass/fail evidence auditable.",
    },
  ];
  const WORKFLOW_MODIFY_REQUIRED_FIELDS = [
    {
      id: "plan.changeSummary",
      prompt: "What change should be made to the existing bundle?",
      type: "text",
      answerType: "text",
      examples: ["Update README only", "Add a mock response for GET /health"],
      whyAsked: "Modify mode needs a bounded, reviewable change request before patching.",
    },
    {
      id: "plan.observability.goal",
      prompt: "What evidence should prove the modified bundle still works?",
      type: "text",
      answerType: "text",
      examples: ["Scenario Manager validation and changed-file summary"],
      whyAsked: "Modification reports need proof that the changed bundle remains usable.",
    },
    {
      id: "plan.successCriteria",
      prompt: "What success criteria should the modification prove?",
      type: "object-or-text",
      answerType: "object-or-text",
      examples: ["Scenario Manager validation passes after the patch."],
      whyAsked: "The agent needs a measurable stop condition for debug/fix work.",
    },
  ];
  const WORKFLOW_QUESTION_OWNERS = Object.freeze({
    USER_OR_SOURCE: "user-or-source",
    USER_OR_SOURCE_OR_AGENT: "user-or-source-or-agent",
  });
  const WORKFLOW_WIZARD_FIELD_MAP = Object.freeze({
    bundleId: "plan.bundleId",
    protocol: "plan.protocol",
    target: "plan.target",
    targetBaseUrl: "plan.targetBaseUrl",
    endpoints: "plan.endpoints",
    requestBody: "plan.requestBody",
    tcpPayload: "plan.tcpPayload",
    defaultRatePerSec: "plan.traffic.ratePerSec",
    trafficShape: "plan.traffic.shape",
    runDuration: "plan.traffic.duration",
    dataSource: "plan.dataset.strategy",
    csvColumns: "plan.dataset.csvColumns",
    redisLists: "plan.dataset.redisLists",
    authTokenUrl: "plan.auth.tokenUrl",
    authClientId: "plan.auth.clientId",
    authSecretEnvVar: "plan.auth.secretEnvVar",
    resultCodePattern: "plan.successCriteria.resultCodePattern",
    successCodes: "plan.successCriteria.successCodes",
    sutDouble: "plan.mock.strategy",
    performanceObjective: "plan.observability.goal",
  });
  const WORKFLOW_EXTRA_FIELD_QUESTIONS = Object.freeze({
    "plan.targetBaseUrl": Object.freeze({
      prompt: "What exact base URL should the external target use?",
      type: "url",
      answerType: "url",
      examples: ["https://www.google.com"],
      whyAsked: "External targets need an explicit public ingress or API base URL.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.endpoints": Object.freeze({
      prompt: "Which endpoints or calls should the scenario exercise?",
      type: "array",
      answerType: "endpoint-array",
      examples: [[{ method: "GET", path: "/search", callId: "search" }]],
      whyAsked: "PocketHive needs concrete calls to generate worker templates and mocks.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.requestBody": Object.freeze({
      prompt: "What request body template should be used for write calls?",
      type: "text",
      answerType: "body-template",
      examples: ["{\"id\":\"{{id}}\"}"],
      whyAsked: "POST, PUT, and PATCH calls need an explicit payload shape.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.tcpPayload": Object.freeze({
      prompt: "What TCP payload should the scenario send?",
      type: "text",
      answerType: "payload",
      examples: ["0800..."],
      whyAsked: "TCP scenarios need a concrete message payload before generation.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.dataset.csvColumns": Object.freeze({
      prompt: "Which CSV columns should the dataset contain?",
      type: "array",
      answerType: "string-array",
      examples: [["customerId", "accountId"]],
      whyAsked: "CSV-backed scenarios need a deterministic sample dataset shape.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.dataset.redisLists": Object.freeze({
      prompt: "Which Redis list names should seed the dataset?",
      type: "array",
      answerType: "string-array",
      examples: [["requests"]],
      whyAsked: "Redis-backed scenarios need explicit queue/list names.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.auth.tokenUrl": Object.freeze({
      prompt: "What auth token URL should the scenario use?",
      type: "url",
      answerType: "url",
      examples: ["https://idp.example.com/oauth/token"],
      whyAsked: "OAuth flows need an explicit token endpoint.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.auth.clientId": Object.freeze({
      prompt: "What auth client id should be referenced?",
      type: "text",
      answerType: "string",
      examples: ["pockethive-load-test"],
      whyAsked: "Auth profiles need a non-secret client identifier.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.auth.secretEnvVar": Object.freeze({
      prompt: "Which environment variable will provide the auth secret?",
      type: "text",
      answerType: "env-var",
      examples: ["POCKETHIVE_TEST_CLIENT_SECRET"],
      whyAsked: "Secrets must be referenced by environment variable, not embedded inline.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.successCriteria.resultCodePattern": Object.freeze({
      prompt: "What result-code regex should be used for pass/fail extraction?",
      type: "regex",
      answerType: "regex",
      examples: ["status=(\\\\d{3})"],
      whyAsked: "Result rules need a deterministic capture pattern.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.successCriteria.successCodes": Object.freeze({
      prompt: "Which result codes count as success?",
      type: "array",
      answerType: "string-array",
      examples: [["200", "202"]],
      whyAsked: "Result rules need explicit success codes.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
    }),
    "plan.safety.publicTargetConfirmed": Object.freeze({
      prompt: "Do you explicitly want to run this scenario against a public real system at the stated low rate?",
      type: "boolean",
      answerType: "confirmation",
      examples: [true],
      whyAsked: "External public real-system targets need explicit user or source-derived confirmation before generation.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
      canAgentInfer: false,
      dependsOn: ["plan.target", "plan.targetBaseUrl", "plan.mock.strategy"],
      triggeredBy: { code: "PUBLIC_TARGET_REAL_SYSTEM", field: "plan.target", value: "external" },
    }),
    "plan.sourceFidelity.status": Object.freeze({
      prompt: "How completely does the normalized plan represent the supplied source test?",
      type: "enum",
      options: SOURCE_FIDELITY_STATUSES,
      answerType: "enum",
      examples: ["complete", "partial-accepted"],
      whyAsked: "Source-backed conversions need an explicit fidelity declaration so unsupported test behavior is not silently dropped.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE_OR_AGENT,
    }),
    "plan.sourceFidelity.userAcceptedLimitations": Object.freeze({
      prompt: "Has the user or source explicitly accepted the listed unsupported source constructs?",
      type: "boolean",
      answerType: "confirmation",
      examples: [true],
      whyAsked: "The MCP can generate a partial conversion only when omissions are visible and accepted.",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
      canAgentInfer: false,
      dependsOn: ["plan.sourceFidelity.status", "plan.sourceFidelity.unsupportedConstructs"],
      triggeredBy: { code: "SOURCE_UNSUPPORTED_CONSTRUCTS", field: "plan.sourceFidelity.unsupportedConstructs" },
    }),
  });
  const WORKFLOW_TRACE_FILE = "WORKFLOW_TRACE.json";
  const WORKFLOW_TRACE_EXCLUDED_FILES = new Set([WORKFLOW_TRACE_FILE, "WORKFLOW_EVIDENCE.md"]);
  const WORKFLOW_STUCK_ATTEMPTS = 3;

  const WORKFLOW_ROLES = Object.freeze({
    [ROLE_IDS.ARCHITECT]: Object.freeze({
      id: ROLE_IDS.ARCHITECT,
      label: "Architect",
      mission: "Clarify intent, scope, assumptions, and unresolved decisions before build.",
      checklist: Object.freeze([
        "Confirm the user's objective and workflow scope.",
        "Identify missing decisions before artifact generation.",
        "Keep assumptions explicit in the workflow history or plan.",
      ]),
      mustNot: Object.freeze([
        "Do not generate artifacts while required questions remain unanswered.",
      ]),
    }),
    [ROLE_IDS.DEVELOPER]: Object.freeze({
      id: ROLE_IDS.DEVELOPER,
      label: "Developer",
      mission: "Generate or patch PocketHive artifacts within the allowed workflow scope.",
      checklist: Object.freeze([
        "Patch only generated bundle or mock-config artifacts.",
        "Preserve prior failed evidence while fixing the current issue.",
        "Keep changes tied to the structured failure code.",
      ]),
      mustNot: Object.freeze([
        "Do not mutate files outside the generated workflow bundle.",
      ]),
    }),
    [ROLE_IDS.TESTER]: Object.freeze({
      id: ROLE_IDS.TESTER,
      label: "Tester",
      mission: "Validate behavior, preserve failed/fixed attempts, and prove acceptance criteria.",
      checklist: Object.freeze([
        "Run validation before considering runtime deployment.",
        "Check success criteria against the produced evidence.",
        "Record failed and fixed attempts without hiding earlier failures.",
      ]),
      mustNot: Object.freeze([
        "Do not treat generated files as tested until validation evidence exists.",
      ]),
    }),
    [ROLE_IDS.SECURITY_REVIEWER]: Object.freeze({
      id: ROLE_IDS.SECURITY_REVIEWER,
      label: "Security Reviewer",
      mission: "Review auth, secrets, data sensitivity, unsafe defaults, and external exposure.",
      checklist: Object.freeze([
        "Confirm auth strategy and secret source are explicit.",
        "Check that datasets and examples avoid sensitive production data.",
        "Flag external target or live deployment exposure before runtime actions.",
      ]),
      mustNot: Object.freeze([
        "Do not embed credentials or tokens in generated artifacts.",
      ]),
    }),
    [ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST]: Object.freeze({
      id: ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST,
      label: "Performance Testing Specialist",
      mission: "Review traffic shape, rate, duration, success criteria, and observability.",
      checklist: Object.freeze([
        "Confirm rate, traffic shape, and duration are realistic for the goal.",
        "Ensure stakeholder evidence includes latency, throughput, and error signals.",
        "Check that mock and dataset behavior can sustain the requested load.",
      ]),
      mustNot: Object.freeze([
        "Do not deploy a performance run without explicit success criteria.",
      ]),
    }),
    [ROLE_IDS.POCKETHIVE_SME]: Object.freeze({
      id: ROLE_IDS.POCKETHIVE_SME,
      label: "PocketHive SME",
      mission: "Check PocketHive-native correctness across bundles, workers, mocks, queues, and evidence.",
      checklist: Object.freeze([
        "Check bundle shape, SUT target, worker roles, mocks, and datasets.",
        "Prefer official PocketHive APIs and ingress paths for runtime proof.",
        "Ensure evidence gaps remain visible until validation, deployment, verification, and report evidence exist.",
      ]),
      mustNot: Object.freeze([
        "Do not bypass workflow gates with direct service-port or shell proof.",
      ]),
    }),
  });

  const WORKFLOW_PROFILES = Object.freeze({
    [PROFILE_IDS.NOVICE_TEST_BUILDER]: Object.freeze({
      id: PROFILE_IDS.NOVICE_TEST_BUILDER,
      label: "Novice Test Builder",
      purpose: "Default guided flow for a user asking for a test from source files or instructions.",
      authority: GUIDANCE_AUTHORITY,
      defaultRole: ROLE_IDS.ARCHITECT,
      roleSequence: Object.freeze([
        ROLE_IDS.ARCHITECT,
        ROLE_IDS.POCKETHIVE_SME,
        ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST,
        ROLE_IDS.SECURITY_REVIEWER,
        ROLE_IDS.DEVELOPER,
        ROLE_IDS.TESTER,
      ]),
      reviewStages: Object.freeze([
        Object.freeze({
          id: "three-amigos",
          label: "Three Amigos Review",
          beforeAction: "workflow.generate",
          roles: Object.freeze([ROLE_IDS.ARCHITECT, ROLE_IDS.DEVELOPER, ROLE_IDS.TESTER]),
          purpose: "Confirm intent, implementability, and testability before bundle generation.",
        }),
      ]),
      evidenceRequirements: Object.freeze([
        Object.freeze({
          id: "intake.critical-provenance",
          label: "Critical answer provenance",
          requiredBefore: "workflow.generate",
          fields: Object.freeze(["plan.target", "plan.dataset.strategy", "plan.successCriteria"]),
          provenanceFields: Object.freeze(["plan.target", "plan.dataset.strategy", "plan.auth", "plan.successCriteria"]),
          allowedProvenance: Object.freeze(["user", "source-derived"]),
        }),
      ]),
      questionPolicy: "ask-before-generate",
      debugPolicy: "agent-decides-iterations",
      evidencePolicy: "stakeholder-handoff-required",
      allowedToolTier: "authoring",
    }),
    [PROFILE_IDS.TEST_CONVERSION_SPECIALIST]: Object.freeze({
      id: PROFILE_IDS.TEST_CONVERSION_SPECIALIST,
      label: "Test Conversion Specialist",
      purpose: "Source-heavy conversion flow for JMeter, Postman, OpenAPI, k6, Gatling, cURL, or similar inputs.",
      authority: GUIDANCE_AUTHORITY,
      defaultRole: ROLE_IDS.ARCHITECT,
      roleSequence: Object.freeze([
        ROLE_IDS.ARCHITECT,
        ROLE_IDS.POCKETHIVE_SME,
        ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST,
        ROLE_IDS.SECURITY_REVIEWER,
        ROLE_IDS.DEVELOPER,
        ROLE_IDS.TESTER,
      ]),
      reviewStages: Object.freeze([
        Object.freeze({
          id: "three-amigos",
          label: "Three Amigos Review",
          beforeAction: "workflow.generate",
          roles: Object.freeze([ROLE_IDS.ARCHITECT, ROLE_IDS.DEVELOPER, ROLE_IDS.TESTER]),
          purpose: "Confirm converted intent, implementation shape, and validation evidence before bundle generation.",
        }),
      ]),
      evidenceRequirements: Object.freeze([
        Object.freeze({
          id: "conversion.critical-provenance",
          label: "Converted answer provenance",
          requiredBefore: "workflow.generate",
          fields: Object.freeze(["plan.target", "plan.dataset.strategy", "plan.successCriteria"]),
          provenanceFields: Object.freeze(["plan.target", "plan.dataset.strategy", "plan.auth", "plan.successCriteria"]),
          allowedProvenance: Object.freeze(["user", "source-derived"]),
        }),
      ]),
      questionPolicy: "ask-before-generate",
      debugPolicy: "agent-decides-iterations",
      evidencePolicy: "source-conversion-evidence-required",
      allowedToolTier: "authoring",
    }),
    [PROFILE_IDS.PERFORMANCE_ENGINEER]: Object.freeze({
      id: PROFILE_IDS.PERFORMANCE_ENGINEER,
      label: "Performance Engineer",
      purpose: "Performance-focused flow with stronger traffic, objective, and observability guidance.",
      authority: GUIDANCE_AUTHORITY,
      defaultRole: ROLE_IDS.ARCHITECT,
      roleSequence: Object.freeze([
        ROLE_IDS.ARCHITECT,
        ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST,
        ROLE_IDS.POCKETHIVE_SME,
        ROLE_IDS.SECURITY_REVIEWER,
        ROLE_IDS.DEVELOPER,
        ROLE_IDS.TESTER,
      ]),
      reviewStages: Object.freeze([
        Object.freeze({
          id: "three-amigos",
          label: "Three Amigos Review",
          beforeAction: "workflow.generate",
          roles: Object.freeze([ROLE_IDS.ARCHITECT, ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST, ROLE_IDS.TESTER]),
          purpose: "Confirm objective, load model, and measurable acceptance before bundle generation.",
        }),
      ]),
      evidenceRequirements: Object.freeze([
        Object.freeze({
          id: "performance.traffic-shape-proof",
          label: "Traffic shape proof",
          requiredBefore: "workflow.generate",
          fields: Object.freeze(["plan.traffic.ratePerSec", "plan.traffic.shape", "plan.traffic.duration"]),
          provenanceFields: Object.freeze(["plan.traffic.ratePerSec", "plan.traffic.shape", "plan.traffic.duration"]),
          allowedProvenance: Object.freeze(["user", "source-derived"]),
        }),
        Object.freeze({
          id: "performance.observability-objective",
          label: "Performance objective and observability",
          requiredBefore: "workflow.generate",
          fields: Object.freeze(["plan.dataset.strategy", "plan.observability.goal", "plan.successCriteria"]),
          provenanceFields: Object.freeze(["plan.dataset.strategy", "plan.observability.goal", "plan.successCriteria", "plan.auth", "plan.target"]),
          allowedProvenance: Object.freeze(["user", "source-derived"]),
        }),
      ]),
      questionPolicy: "ask-before-generate",
      debugPolicy: "agent-decides-iterations",
      evidencePolicy: "performance-evidence-required",
      allowedToolTier: "authoring",
    }),
    [PROFILE_IDS.RUNTIME_VERIFIER]: Object.freeze({
      id: PROFILE_IDS.RUNTIME_VERIFIER,
      label: "Runtime Verifier",
      purpose: "Deployment, runtime health, queues, mocks, and evidence-focused flow.",
      authority: GUIDANCE_AUTHORITY,
      defaultRole: ROLE_IDS.POCKETHIVE_SME,
      roleSequence: Object.freeze([
        ROLE_IDS.POCKETHIVE_SME,
        ROLE_IDS.TESTER,
        ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST,
        ROLE_IDS.SECURITY_REVIEWER,
        ROLE_IDS.DEVELOPER,
        ROLE_IDS.ARCHITECT,
      ]),
      reviewStages: Object.freeze([]),
      evidenceRequirements: Object.freeze([
        Object.freeze({
          id: "runtime.validation-before-deploy",
          label: "Validation evidence before runtime",
          requiredBefore: "workflow.deploy",
          evidence: "validation.ok",
        }),
      ]),
      questionPolicy: "ask-before-runtime",
      debugPolicy: "agent-decides-iterations",
      evidencePolicy: "runtime-proof-required",
      allowedToolTier: "live-runtime",
    }),
    [PROFILE_IDS.MAINTAINER]: Object.freeze({
      id: PROFILE_IDS.MAINTAINER,
      label: "Maintainer",
      purpose: "Read/status/configuration-focused profile for MCP and workflow maintenance.",
      authority: GUIDANCE_AUTHORITY,
      defaultRole: ROLE_IDS.POCKETHIVE_SME,
      roleSequence: Object.freeze([
        ROLE_IDS.POCKETHIVE_SME,
        ROLE_IDS.ARCHITECT,
        ROLE_IDS.SECURITY_REVIEWER,
        ROLE_IDS.DEVELOPER,
        ROLE_IDS.TESTER,
        ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST,
      ]),
      reviewStages: Object.freeze([]),
      evidenceRequirements: Object.freeze([]),
      questionPolicy: "display-only",
      debugPolicy: "explicit-user-request-required",
      evidencePolicy: "configuration-status-only",
      allowedToolTier: "read-status",
    }),
  });

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

  function plainArray(value) {
    return Array.isArray(value) ? value : [];
  }

  function plainObject(value) {
    return value && typeof value === "object" && !Array.isArray(value);
  }

  function mergeWorkflowPatch(current = {}, patch = {}) {
    const merged = { ...(plainObject(current) ? current : {}) };
    for (const [key, value] of Object.entries(patch || {})) {
      merged[key] = plainObject(value) && plainObject(merged[key])
        ? mergeWorkflowPatch(merged[key], value)
        : value;
    }
    return merged;
  }

  function workflowProfilesConfigPath() {
    return process.env.PH_WORKFLOW_PROFILES_PATH || resolve(REPO_ROOT, "workflow-profiles.json");
  }

  let workflowDefinitionsCache;
  function workflowDefinitions() {
    if (workflowDefinitionsCache) return workflowDefinitionsCache;
    const path = workflowProfilesConfigPath();
    if (!existsSync(path)) {
      workflowDefinitionsCache = {
        source: "built-in",
        path: null,
        defaultProfileId: DEFAULT_PROFILE_ID,
        roles: WORKFLOW_ROLES,
        profiles: WORKFLOW_PROFILES,
      };
      return workflowDefinitionsCache;
    }

    let parsed;
    try {
      parsed = JSON.parse(readFileSync(path, "utf8"));
    } catch (err) {
      throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: ${path}: ${err.message}`);
    }

    const roles = { ...WORKFLOW_ROLES };
    if (parsed.roles !== undefined) {
      if (!parsed.roles || typeof parsed.roles !== "object" || Array.isArray(parsed.roles)) {
        throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: roles must be an object map`);
      }
      for (const [roleId, role] of Object.entries(parsed.roles)) {
        validateWorkflowRoleConfig(roleId, role);
        roles[roleId] = {
          id: roleId,
          label: role.label,
          mission: role.mission,
          checklist: Object.freeze([...role.checklist]),
          mustNot: Object.freeze([...plainArray(role.mustNot)]),
        };
      }
    }

    if (!parsed.profiles || typeof parsed.profiles !== "object" || Array.isArray(parsed.profiles)) {
      throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profiles must be an object map`);
    }
    const profiles = {};
    for (const [profileId, profile] of Object.entries(parsed.profiles)) {
      validateWorkflowProfileConfig(profileId, profile, roles);
      profiles[profileId] = {
        id: profileId,
        label: profile.label,
        purpose: profile.purpose,
        authority: GUIDANCE_AUTHORITY,
        defaultRole: profile.defaultRole,
        roleSequence: Object.freeze([...profile.roleSequence]),
        reviewStages: Object.freeze(plainArray(profile.reviewStages).map(stage => Object.freeze({
          id: stage.id,
          label: stage.label,
          beforeAction: stage.beforeAction,
          roles: Object.freeze([...stage.roles]),
          purpose: stage.purpose,
        }))),
        evidenceRequirements: Object.freeze(plainArray(profile.evidenceRequirements).map(requirement => Object.freeze({
          ...requirement,
          fields: Object.freeze(plainArray(requirement.fields)),
          provenanceFields: Object.freeze(plainArray(requirement.provenanceFields)),
          allowedProvenance: Object.freeze(plainArray(requirement.allowedProvenance).length ? requirement.allowedProvenance : ["user", "source-derived"]),
        }))),
        questionPolicy: profile.questionPolicy,
        debugPolicy: profile.debugPolicy,
        evidencePolicy: profile.evidencePolicy,
        allowedToolTier: profile.allowedToolTier,
      };
    }
    const defaultProfileId = parsed.defaultProfileId || Object.keys(profiles)[0];
    if (!profiles[defaultProfileId]) {
      throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: defaultProfileId '${defaultProfileId}' is not defined`);
    }
    workflowDefinitionsCache = { source: "custom", path, defaultProfileId, roles, profiles };
    return workflowDefinitionsCache;
  }

  function validateWorkflowRoleConfig(roleId, role) {
    if (!role || typeof role !== "object" || Array.isArray(role)) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: role '${roleId}' must be an object`);
    for (const field of ["label", "mission"]) {
      if (!hasValue(role[field])) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: role '${roleId}' missing ${field}`);
    }
    if (!Array.isArray(role.checklist) || !role.checklist.every(item => typeof item === "string" && item.trim())) {
      throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: role '${roleId}' checklist must be a non-empty string array`);
    }
  }

  function validateWorkflowProfileConfig(profileId, profile, roles) {
    if (!profile || typeof profile !== "object" || Array.isArray(profile)) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' must be an object`);
    for (const field of ["label", "purpose", "defaultRole", "roleSequence", "questionPolicy", "debugPolicy", "evidencePolicy", "allowedToolTier"]) {
      if (!hasValue(profile[field])) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' missing ${field}`);
    }
    if (!roles[profile.defaultRole]) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' defaultRole '${profile.defaultRole}' is not defined`);
    if (!Array.isArray(profile.roleSequence) || profile.roleSequence.length === 0) {
      throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' roleSequence must be a non-empty array`);
    }
    for (const roleId of profile.roleSequence) {
      if (!roles[roleId]) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' roleSequence references unknown role '${roleId}'`);
    }
    for (const stage of plainArray(profile.reviewStages)) {
      for (const field of ["id", "label", "beforeAction", "roles", "purpose"]) {
        if (!hasValue(stage[field])) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' review stage missing ${field}`);
      }
      for (const roleId of stage.roles) {
        if (!roles[roleId]) throw new Error(`WORKFLOW_PROFILE_CONFIG_INVALID: profile '${profileId}' review stage '${stage.id}' references unknown role '${roleId}'`);
      }
    }
  }

  function workflowAllowedSourceRoots() {
    return [...new Set([
      getBundlesDir(),
      POCKETHIVE_ROOT || REPO_ROOT,
      ...readJsonArrayEnv("PH_WORKFLOW_SOURCE_ROOTS"),
    ].filter(Boolean).map(root => resolve(root)))];
  }

  function workflowPersistenceMode() {
    const mode = process.env.PH_WORKFLOW_PERSISTENCE || "local";
    if (!["memory", "local"].includes(mode)) throw new Error(`WORKFLOW_PERSISTENCE_INVALID: ${mode}`);
    return mode;
  }

  function workflowStorePath() {
    return process.env.PH_WORKFLOW_STORE_PATH || resolve(getBundlesDir(), ".pockethive-workflows.json");
  }

  function workflowPersistenceStatus() {
    const mode = workflowPersistenceMode();
    return {
      mode,
      path: mode === "local" ? workflowStorePath() : null,
      loaded: workflowStoreLoaded,
    };
  }

  let workflowStoreLoaded = false;
  function ensureWorkflowStoreLoaded() {
    if (workflowStoreLoaded || workflowPersistenceMode() !== "local") {
      workflowStoreLoaded = true;
      return;
    }
    const path = workflowStorePath();
    if (!existsSync(path)) {
      workflowStoreLoaded = true;
      return;
    }
    let parsed;
    try {
      parsed = JSON.parse(readFileSync(path, "utf8"));
    } catch (err) {
      throw new Error(`WORKFLOW_STORE_INVALID: ${path}: ${err.message}`);
    }
    if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.sessions)) {
      throw new Error(`WORKFLOW_STORE_INVALID: ${path}: expected { version: 1, sessions: [] }`);
    }
    for (const session of parsed.sessions) {
      if (session?.workflowId) WORKFLOW_SESSIONS.set(session.workflowId, session);
    }
    workflowStoreLoaded = true;
  }

  function persistWorkflowSessions() {
    if (workflowPersistenceMode() !== "local") return;
    const path = workflowStorePath();
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify({
      version: 1,
      writtenAt: new Date().toISOString(),
      sessions: [...WORKFLOW_SESSIONS.values()],
    }, null, 2), "utf8");
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

  function safeReadText(path, maxBytes = 20_000) {
    if (!existsSync(path)) return "";
    const content = readFileSync(path, "utf8");
    return content.length > maxBytes ? `${content.slice(0, maxBytes)}\n...[truncated]` : content;
  }

  function workflowExampleSources() {
    return [
      { id: "repo-examples", authority: "canonical-example", root: REPO_EXAMPLES_ROOT },
      { id: "active-bundles-root", authority: "team-example", root: resolve(getBundlesDir()) },
    ];
  }

  function workflowExampleKeywords(bundleId, text, files) {
    const haystack = `${bundleId}\n${text}\n${files.join("\n")}`.toLowerCase();
    const keywords = new Set(bundleId.split(/[-_\s]+/).filter(Boolean));
    for (const [keyword, patterns] of Object.entries({
      http: ["http", "rest", "get ", "post ", "wiremock"],
      tcp: ["tcp"],
      sequence: ["sequence"],
      auth: ["auth", "oauth", "token", "authref"],
      dataset: ["dataset", "csv", "redis"],
      mock: ["mock", "wiremock", "tcp-mock"],
      schema: ["schema", "jsonschema"],
      observability: ["observability", "grafana", "metric", "evidence"],
      performance: ["rate", "nft", "performance", "throughput", "latency"],
      smoke: ["smoke", "simple", "minimal"],
    })) {
      if (patterns.some(pattern => haystack.includes(pattern))) keywords.add(keyword);
    }
    return [...keywords].sort();
  }

  function workflowExampleFiles(dir, limit = 80) {
    const files = [];
    function visit(current, prefix = "") {
      if (files.length >= limit || !existsSync(current)) return;
      for (const entry of readdirSync(current, { withFileTypes: true })) {
        if (entry.name.startsWith(".")) continue;
        const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
        const path = resolve(current, entry.name);
        if (entry.isDirectory()) visit(path, rel);
        else files.push(rel);
        if (files.length >= limit) return;
      }
    }
    visit(dir);
    return files.sort();
  }

  function workflowExampleSummary(bundleId, dir, source, authority, canonicalIds = new Set()) {
    const files = workflowExampleFiles(dir);
    const scenario = safeReadText(resolve(dir, "scenario.yaml"), 40_000);
    const readme = safeReadText(resolve(dir, "README.md"), 20_000);
    const flow = safeReadText(resolve(dir, "FLOW_DOCUMENT.md"), 20_000);
    const text = `${scenario}\n${readme}\n${flow}`;
    const lower = text.toLowerCase();
    const demonstrates = workflowExampleKeywords(bundleId, text, files);
    const requires = {
      auth: files.includes("authProfiles.yaml") || lower.includes("authref") || lower.includes("oauth") || lower.includes("token"),
      dataset: files.some(file => file.startsWith("datasets/")) || lower.includes("csv") || lower.includes("redis"),
      mock: files.some(file => file.startsWith("mock-config/")) || lower.includes("wiremock") || lower.includes("tcp-mock"),
      schema: files.some(file => file.includes("schema")) || lower.includes("jsonschema") || lower.includes("schema"),
      observability: lower.includes("observability") || lower.includes("grafana") || lower.includes("evidence"),
    };
    return {
      bundleId,
      source,
      authority,
      path: dir,
      hasScenario: files.includes("scenario.yaml"),
      hasReadme: files.includes("README.md"),
      demonstrates,
      requires,
      files,
      shadowedBy: source === "active-bundles-root" && canonicalIds.has(bundleId) ? `repo-examples/${bundleId}` : null,
    };
  }

  function workflowExamples(includeTeamExamples = true) {
    const sources = workflowExampleSources();
    const canonicalIds = new Set();
    const examples = [];
    for (const source of sources) {
      if (source.id === "active-bundles-root" && !includeTeamExamples) continue;
      if (!existsSync(source.root)) continue;
      if (source.id === "active-bundles-root" && resolve(source.root) === resolve(REPO_EXAMPLES_ROOT)) continue;
      for (const entry of readdirSync(source.root, { withFileTypes: true })) {
        if (!entry.isDirectory() || entry.name.startsWith(".")) continue;
        const dir = resolve(source.root, entry.name);
        const scenarioPath = resolve(dir, "scenario.yaml");
        if (!existsSync(scenarioPath)) continue;
        const summary = workflowExampleSummary(entry.name, dir, source.id, source.authority, canonicalIds);
        examples.push(summary);
        if (source.id === "repo-examples") canonicalIds.add(entry.name);
      }
    }
    return {
      sourceOrder: sources.map(source => ({ id: source.id, authority: source.authority, root: source.root })),
      examples,
    };
  }

  function workflowExample(bundleId, sourceId = null) {
    const matches = workflowExamples(true).examples
      .filter(example => example.bundleId === bundleId && (!sourceId || example.source === sourceId));
    if (!matches.length) throw new Error(`WORKFLOW_EXAMPLE_NOT_FOUND: ${sourceId ? `${sourceId}/` : ""}${bundleId}`);
    const order = new Map([["repo-examples", 0], ["active-bundles-root", 1]]);
    return matches.sort((a, b) => (order.get(a.source) ?? 99) - (order.get(b.source) ?? 99))[0];
  }

  function workflowExampleDetails(bundleId, sourceId = null) {
    const example = workflowExample(bundleId, sourceId);
    return {
      example,
      docs: {
        readme: safeReadText(resolve(example.path, "README.md"), 40_000),
        flow: safeReadText(resolve(example.path, "FLOW_DOCUMENT.md"), 40_000),
      },
      scenario: safeReadText(resolve(example.path, "scenario.yaml"), WORKFLOW_SOURCE_MAX_BYTES),
    };
  }

  function workflowExampleRecommendations(intent, limit = 5) {
    const terms = String(intent || "").toLowerCase().split(/[^a-z0-9]+/).filter(term => term.length > 2);
    const scored = workflowExamples(true).examples.map(example => {
      const haystack = `${example.bundleId} ${example.demonstrates.join(" ")} ${example.files.join(" ")}`.toLowerCase();
      const score = terms.reduce((sum, term) => sum + (haystack.includes(term) ? 2 : 0), 0)
        + (example.source === "repo-examples" ? 1 : 0);
      return { ...example, score };
    }).filter(example => example.score > 0);
    return scored
      .sort((a, b) => b.score - a.score || a.bundleId.localeCompare(b.bundleId))
      .slice(0, Math.max(1, Math.min(Number(limit) || 5, 20)));
  }

  function cleanupWorkflowSessions() {
    ensureWorkflowStoreLoaded();
    const now = Date.now();
    let changed = false;
    for (const [workflowId, session] of WORKFLOW_SESSIONS.entries()) {
      if (now - session.createdAtMs > WORKFLOW_SESSION_TTL_MS) {
        WORKFLOW_SESSIONS.delete(workflowId);
        changed = true;
      }
    }
    if (changed) persistWorkflowSessions();
  }

  function workflowSession(workflowId) {
    ensureWorkflowStoreLoaded();
    const session = WORKFLOW_SESSIONS.get(workflowId);
    if (!session) throw new Error(`WORKFLOW_NOT_FOUND: ${workflowId}`);
    if (Date.now() - session.createdAtMs > WORKFLOW_SESSION_TTL_MS) {
      WORKFLOW_SESSIONS.delete(workflowId);
      persistWorkflowSessions();
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

  function workflowRole(roleId) {
    const role = workflowDefinitions().roles[roleId];
    if (!role) throw new Error(`WORKFLOW_ROLE_NOT_FOUND: ${roleId}`);
    return role;
  }

  function workflowProfile(profileId) {
    const definitions = workflowDefinitions();
    const resolvedProfileId = profileId || definitions.defaultProfileId;
    const profile = definitions.profiles[resolvedProfileId];
    if (!profile) throw new Error(`WORKFLOW_PROFILE_NOT_FOUND: ${resolvedProfileId}`);
    return profile;
  }

  function workflowRolePayload(roleId) {
    const role = workflowRole(roleId);
    return {
      id: role.id,
      label: role.label,
      mission: role.mission,
      authority: GUIDANCE_AUTHORITY,
      mustDo: [...role.checklist],
      mustNot: [...role.mustNot],
    };
  }

  function workflowProfilePayload(profile, includeRoles = false) {
    const payload = {
      id: profile.id,
      label: profile.label,
      purpose: profile.purpose,
      authority: profile.authority,
      defaultRole: profile.defaultRole,
      roleSequence: [...profile.roleSequence],
      requiredRoles: profile.roleSequence.map(roleId => {
        const role = workflowRole(roleId);
        return { id: role.id, label: role.label };
      }),
      reviewStages: profile.reviewStages.map(stage => ({
        id: stage.id,
        label: stage.label,
        beforeAction: toolName(stage.beforeAction),
        roles: [...stage.roles],
        purpose: stage.purpose,
      })),
      evidenceRequirements: plainArray(profile.evidenceRequirements).map(requirement => ({
        id: requirement.id,
        label: requirement.label,
        requiredBefore: toolName(requirement.requiredBefore),
        fields: [...plainArray(requirement.fields)],
        provenanceFields: [...plainArray(requirement.provenanceFields)],
        allowedProvenance: [...plainArray(requirement.allowedProvenance)],
        evidence: requirement.evidence,
      })),
      questionPolicy: profile.questionPolicy,
      debugPolicy: profile.debugPolicy,
      evidencePolicy: profile.evidencePolicy,
      allowedToolTier: profile.allowedToolTier,
    };
    if (includeRoles) payload.roles = profile.roleSequence.map(roleId => workflowRolePayload(roleId));
    return payload;
  }

  function workflowProfilesPayload() {
    const definitions = workflowDefinitions();
    return {
      defaultProfileId: definitions.defaultProfileId,
      authority: GUIDANCE_AUTHORITY,
      source: definitions.source,
      configPath: definitions.path,
      roles: Object.values(definitions.roles).map(role => workflowRolePayload(role.id)),
      profiles: Object.values(definitions.profiles).map(profile => workflowProfilePayload(profile)),
    };
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
      sourceFidelity: plan.sourceFidelity,
      docs: "yes",
    };
  }

  function workflowRequiredFields(session) {
    return session.mode === "modify" ? WORKFLOW_MODIFY_REQUIRED_FIELDS : WORKFLOW_REQUIRED_FIELDS;
  }

  function workflowPublicTargetSafetyRequired(session) {
    if (session.mode === "modify") return false;
    const plan = session.plan || {};
    const mockStrategy = plan.mock?.strategy ?? plan.sutDouble;
    return isValidExternalPublicTarget(plan) && mockStrategy === "real_system";
  }

  function workflowPublicTargetSafetyConfirmed(session) {
    const plan = session.plan || {};
    return plan.safety?.publicTargetConfirmed === true || plan.publicTargetSafetyConfirmed === true;
  }

  function workflowSourceFidelityRequired(session) {
    if (session.mode === "modify") return false;
    if (session.source?.type === "plain-instructions" || !session.source?.path) return false;
    return SOURCE_BACKED_TYPES.has(session.source.type) || session.source.type === "other";
  }

  function workflowUnsupportedConstructs(session) {
    return plainArray(session.plan?.sourceFidelity?.unsupportedConstructs).filter(item => String(item || "").trim());
  }

  function workflowSourceLimitationsAccepted(session) {
    return session.plan?.sourceFidelity?.userAcceptedLimitations === true;
  }

  function workflowMissingFields(session) {
    const plan = session.plan || {};
    const missing = [];
    for (const field of workflowRequiredFields(session)) {
      if (!hasValue(getPath({ plan }, field.id))) missing.push(field.id);
    }
    if (session.mode === "modify") return missing;
    const wizardAnswers = normalizeWorkflowPlan(plan);
    const wizardMissing = wizardMissingQuestions(wizardAnswers)
      .map(field => WORKFLOW_WIZARD_FIELD_MAP[field] || `plan.${field}`);
    for (const field of wizardMissing) {
      if (!missing.includes(field)) missing.push(field);
    }
    if (workflowPublicTargetSafetyRequired(session) && !workflowPublicTargetSafetyConfirmed(session)) {
      missing.push("plan.safety.publicTargetConfirmed");
    }
    if (workflowSourceFidelityRequired(session) && !hasValue(plan.sourceFidelity?.status)) {
      missing.push("plan.sourceFidelity.status");
    }
    if (workflowUnsupportedConstructs(session).length && !workflowSourceLimitationsAccepted(session)) {
      missing.push("plan.sourceFidelity.userAcceptedLimitations");
    }
    return missing;
  }

  function workflowKnownQuestion(id) {
    return [...WORKFLOW_REQUIRED_FIELDS, ...WORKFLOW_MODIFY_REQUIRED_FIELDS].find(field => field.id === id)
      || WORKFLOW_EXTRA_FIELD_QUESTIONS[id]
      || null;
  }

  function workflowFieldLabel(id) {
    return String(id || "plan")
      .replace(/^plan\./, "")
      .replace(/([a-z])([A-Z])/g, "$1 $2")
      .replace(/[._-]+/g, " ");
  }

  function workflowQuestionIdPart(value) {
    return String(value || "unknown").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "unknown";
  }

  function workflowQuestionGroup(id) {
    if (id.includes("safety")) return "safety";
    if (id.includes("changeSummary")) return "change";
    if (id.includes("target") || id.includes("endpoint") || id.includes("Payload") || id.includes("requestBody")) return "target";
    if (id.includes("traffic") || id.includes("Duration") || id.includes("Rate")) return "traffic";
    if (id.includes("dataset") || id.includes("csv") || id.includes("redis")) return "data";
    if (id.includes("mock")) return "mock";
    if (id.includes("schema") || id.includes("sourceFidelity")) return "schema";
    if (id.includes("auth")) return "auth";
    if (id.includes("observability")) return "observability";
    if (id.includes("successCriteria")) return "success";
    return "intent";
  }

  function workflowQuestionOrder(id) {
    const order = [
      "plan.bundleId",
      "plan.protocol",
      "plan.target",
      "plan.targetBaseUrl",
      "plan.endpoints",
      "plan.requestBody",
      "plan.tcpPayload",
      "plan.traffic.ratePerSec",
      "plan.traffic.shape",
      "plan.traffic.duration",
      "plan.dataset.strategy",
      "plan.dataset.csvColumns",
      "plan.dataset.redisLists",
      "plan.mock.strategy",
      "plan.safety.publicTargetConfirmed",
      "plan.auth",
      "plan.auth.tokenUrl",
      "plan.auth.clientId",
      "plan.auth.secretEnvVar",
      "plan.observability.goal",
      "plan.successCriteria",
      "plan.successCriteria.resultCodePattern",
      "plan.successCriteria.successCodes",
      "plan.sourceFidelity.status",
      "plan.sourceFidelity.userAcceptedLimitations",
      "plan.changeSummary",
    ];
    const index = order.indexOf(id);
    return index === -1 ? 900 : index + 1;
  }

  function workflowQuestionDependencies(id) {
    if (id === "plan.targetBaseUrl") return ["plan.target"];
    if (id === "plan.endpoints") return ["plan.protocol"];
    if (id === "plan.requestBody") return ["plan.endpoints"];
    if (id === "plan.tcpPayload") return ["plan.protocol"];
    if (id === "plan.dataset.csvColumns" || id === "plan.dataset.redisLists") return ["plan.dataset.strategy"];
    if (id.startsWith("plan.auth.") || id === "plan.auth") return ["plan.auth"];
    if (id.startsWith("plan.successCriteria.")) return ["plan.successCriteria"];
    if (id === "plan.safety.publicTargetConfirmed") return ["plan.target", "plan.targetBaseUrl", "plan.mock.strategy"];
    if (id === "plan.sourceFidelity.userAcceptedLimitations") return ["plan.sourceFidelity.status", "plan.sourceFidelity.unsupportedConstructs"];
    return [];
  }

  function workflowQuestionTrigger(id) {
    if (id === "plan.safety.publicTargetConfirmed") return { code: "PUBLIC_TARGET_REAL_SYSTEM", field: "plan.target", value: "external" };
    if (id === "plan.dataset.csvColumns") return { code: "CSV_DATASET_SELECTED", field: "plan.dataset.strategy", value: "CSV_DATASET" };
    if (id === "plan.dataset.redisLists") return { code: "REDIS_DATASET_SELECTED", field: "plan.dataset.strategy", value: "REDIS_DATASET" };
    if (id.startsWith("plan.auth.")) return { code: "AUTH_ENABLED", field: "plan.auth" };
    if (id === "plan.sourceFidelity.userAcceptedLimitations") return { code: "SOURCE_UNSUPPORTED_CONSTRUCTS", field: "plan.sourceFidelity.unsupportedConstructs" };
    return null;
  }

  function workflowQuestionWithMetadata(question) {
    const answerOwner = question.answerOwner || WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE_OR_AGENT;
    const canAgentInfer = question.canAgentInfer ?? answerOwner === WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE_OR_AGENT;
    const dependsOn = plainArray(question.dependsOn).length ? question.dependsOn : workflowQuestionDependencies(question.field || question.id);
    return {
      ...question,
      questionGroup: question.questionGroup || workflowQuestionGroup(question.field || question.id),
      order: question.order || workflowQuestionOrder(question.field || question.id),
      dependsOn,
      triggeredBy: question.triggeredBy || workflowQuestionTrigger(question.field || question.id),
      answerOwner,
      canAgentInfer,
      confidence: question.confidence || (canAgentInfer ? "agent-fillable" : "required-confirmation"),
    };
  }

  function workflowMissingFieldQuestion(id) {
    const known = workflowKnownQuestion(id);
    const answerOwner = known?.answerOwner || WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE_OR_AGENT;
    return workflowQuestionWithMetadata({
      ...(known || {}),
      id,
      questionKind: "missing-field",
      field: id,
      prompt: known?.prompt || `Provide ${workflowFieldLabel(id)} for the generated PocketHive bundle.`,
      type: known?.type || "value",
      answerType: known?.answerType || "value",
      answerOwner,
      whyAsked: known?.whyAsked || "This value is required before the workflow can continue.",
      resolution: {
        tool: toolName("workflow.update"),
        planField: id.startsWith("plan.") ? id : null,
        provenanceField: null,
      },
      blockedAction: toolName("workflow.generate"),
    });
  }

  function workflowValidationQuestion(issue) {
    const known = workflowKnownQuestion(issue.field);
    const fieldLabel = workflowFieldLabel(issue.field);
    const requiresUserConfirmation = issue.code === "PUBLIC_TARGET_TRAFFIC_UNSAFE";
    return workflowQuestionWithMetadata({
      id: `validation.${workflowQuestionIdPart(issue.code)}.${issue.field}`,
      questionKind: "invalid-answer",
      field: issue.field,
      prompt: `Revise ${fieldLabel} so it is valid and measurable.`,
      type: known?.type || "value",
      answerType: known?.answerType || "value",
      answerOwner: requiresUserConfirmation ? WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE : known?.answerOwner || WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
      canAgentInfer: requiresUserConfirmation ? false : known?.canAgentInfer,
      examples: known?.examples || [],
      whyAsked: issue.message,
      validationIssue: issue,
      resolution: {
        tool: toolName("workflow.update"),
        planField: issue.field.startsWith("plan.") ? issue.field : null,
        provenanceField: null,
      },
      blockedAction: toolName("workflow.generate"),
    });
  }

  function workflowProvenanceQuestion(gap) {
    const known = workflowKnownQuestion(gap.field);
    const allowed = plainArray(gap.allowedProvenance);
    const current = gap.source ? ` Current provenance is '${gap.source}', which is not accepted here.` : "";
    return workflowQuestionWithMetadata({
      id: `${gap.field}.provenance`,
      questionKind: "provenance-confirmation",
      field: gap.field,
      prompt: `Confirm ${workflowFieldLabel(gap.field)} from ${allowed.join(" or ")} evidence.${current}`,
      type: "provenance",
      answerType: "provenance",
      answerOwner: WORKFLOW_QUESTION_OWNERS.USER_OR_SOURCE,
      allowedProvenance: allowed,
      currentProvenance: gap.source || null,
      examples: known?.examples || [],
      whyAsked: `${gap.requirementId} requires accepted provenance before ${toolName("workflow.generate")}.`,
      resolution: {
        tool: toolName("workflow.update"),
        planField: null,
        provenanceField: gap.field,
      },
      blockedAction: toolName("workflow.generate"),
    });
  }

  function workflowNextQuestions(session, missing = workflowMissingFields(session)) {
    const questions = [
      ...missing.map(id => workflowMissingFieldQuestion(id)),
      ...workflowValidationErrors(session).map(issue => workflowValidationQuestion(issue)),
      ...workflowProvenanceGaps(session).map(gap => workflowProvenanceQuestion(gap)),
    ];
    const seen = new Set();
    return questions.filter(question => {
      if (seen.has(question.id)) return false;
      seen.add(question.id);
      return true;
    }).sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));
  }

  function isExternalPublicTarget(plan) {
    if (plan?.target !== "external") return false;
    const url = String(plan.targetBaseUrl || plan.target?.baseUrl || "").trim();
    if (!url) return true;
    try {
      const hostname = new URL(url).hostname.toLowerCase();
      return !["localhost", "127.0.0.1", "::1"].includes(hostname)
        && !hostname.endsWith(".local")
        && !hostname.startsWith("10.")
        && !hostname.startsWith("192.168.");
    } catch {
      return true;
    }
  }

  function isValidExternalPublicTarget(plan) {
    if (plan?.target !== "external") return false;
    const url = String(plan.targetBaseUrl || plan.target?.baseUrl || "").trim();
    if (!url) return false;
    try {
      new URL(url);
      return isExternalPublicTarget(plan);
    } catch {
      return false;
    }
  }

  function vagueText(value) {
    const text = typeof value === "string"
      ? value
      : value && typeof value === "object"
        ? String(value.summary || value.goal || "")
        : "";
    const normalized = text.trim().toLowerCase();
    return normalized.length < 12 || ["works", "ok", "fine", "metrics", "success"].includes(normalized);
  }

  function workflowEndpointValidationIssues(plan, add) {
    if (!Array.isArray(plan.endpoints)) return;
    const callIds = new Set();
    for (const [index, endpoint] of plan.endpoints.entries()) {
      const method = String(endpoint?.method || "").trim().toUpperCase();
      const path = String(endpoint?.path || "").trim();
      const callId = String(endpoint?.callId || "").trim();
      if (!WORKFLOW_HTTP_METHODS.has(method)) {
        add("error", "ENDPOINT_METHOD_INVALID", "plan.endpoints", `Endpoint ${index + 1} method must be one of ${[...WORKFLOW_HTTP_METHODS].join(", ")}.`);
      }
      if (!path.startsWith("/")) {
        add("error", "ENDPOINT_PATH_INVALID", "plan.endpoints", `Endpoint ${index + 1} path must be an absolute /path.`);
      }
      if (!callId) {
        add("error", "ENDPOINT_CALL_ID_REQUIRED", "plan.endpoints", `Endpoint ${index + 1} callId is required.`);
      } else if (callIds.has(callId)) {
        add("error", "ENDPOINT_CALL_ID_DUPLICATE", "plan.endpoints", `Endpoint callId '${callId}' is duplicated.`);
      }
      callIds.add(callId);
    }
  }

  function workflowValidationIssues(session) {
    const plan = session.plan || {};
    const issues = [];
    const add = (severity, code, field, message) => issues.push({ severity, code, field, message });

    if (session.mode === "modify") {
      if (hasValue(plan.changeSummary) && vagueText(plan.changeSummary)) {
        add("error", "CHANGE_SUMMARY_TOO_VAGUE", "plan.changeSummary", "Modification summary must describe the intended bounded change.");
      }
    } else {
      const answers = normalizeWorkflowPlan(plan);
      for (const message of validateWizardAnswers(answers)) {
        const field = message.includes("targetBaseUrl") ? "plan.targetBaseUrl"
          : message.includes("bundleId") ? "plan.bundleId"
            : message.includes("resultCodePattern") ? "plan.successCriteria"
              : "plan";
        add("error", "WIZARD_ANSWER_INVALID", field, message);
      }
      const rate = Number(plan.traffic?.ratePerSec ?? plan.defaultRatePerSec ?? 0);
      const mockStrategy = plan.mock?.strategy ?? plan.sutDouble;
      if (isExternalPublicTarget(plan) && mockStrategy === "real_system" && rate > 10) {
        add("error", "PUBLIC_TARGET_TRAFFIC_UNSAFE", "plan.traffic.ratePerSec", "External real-system targets require an explicit low smoke rate or a mock strategy before generation.");
      }
      const auth = plan.auth;
      if (auth && typeof auth === "object" && (auth.secret || auth.token || auth.password)) {
        add("error", "INLINE_SECRET_NOT_ALLOWED", "plan.auth", "Auth secrets must use authRef or environment-variable references, not inline values.");
      }
      if (plan.sourceFidelity?.status && !SOURCE_FIDELITY_STATUSES.includes(plan.sourceFidelity.status)) {
        add("error", "SOURCE_FIDELITY_STATUS_INVALID", "plan.sourceFidelity.status", `Source fidelity status must be one of ${SOURCE_FIDELITY_STATUSES.join(", ")}.`);
      }
      if (workflowSourceFidelityRequired(session) && plan.sourceFidelity?.status === "instruction-derived") {
        add("error", "SOURCE_FIDELITY_STATUS_INVALID", "plan.sourceFidelity.status", "Source-backed conversions cannot use instruction-derived fidelity status.");
      }
      if (workflowUnsupportedConstructs(session).length && plan.sourceFidelity?.status === "complete") {
        add("error", "SOURCE_FIDELITY_UNSUPPORTED_WITH_COMPLETE_STATUS", "plan.sourceFidelity.status", "A plan with unsupported constructs cannot be marked complete.");
      }
      workflowEndpointValidationIssues(plan, add);
    }

    if (hasValue(plan.successCriteria) && vagueText(plan.successCriteria)) {
      add("error", "SUCCESS_CRITERIA_TOO_VAGUE", "plan.successCriteria", "Success criteria must be measurable enough to prove pass/fail evidence.");
    }
    if (hasValue(plan.observability?.goal) && vagueText(plan.observability.goal)) {
      add("error", "OBSERVABILITY_GOAL_TOO_VAGUE", "plan.observability.goal", "Observability goal must name the evidence stakeholders need.");
    }
    return issues;
  }

  function workflowValidationErrors(session) {
    return workflowValidationIssues(session).filter(issue => issue.severity === "error");
  }

  const PROVENANCE_SOURCES = new Set(["user", "source-derived", "agent-inferred", "defaulted"]);

  function normalizeProvenanceEntry(field, value) {
    const entry = typeof value === "string" ? { source: value } : value;
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
      throw new Error(`WORKFLOW_PROVENANCE_INVALID: ${field} provenance must be an object or source string`);
    }
    const source = String(entry.source || "").trim();
    if (!PROVENANCE_SOURCES.has(source)) {
      throw new Error(`WORKFLOW_PROVENANCE_INVALID: ${field} source must be one of ${[...PROVENANCE_SOURCES].join(", ")}`);
    }
    return {
      source,
      note: String(entry.note || "").trim(),
      at: entry.at || new Date().toISOString(),
    };
  }

  function normalizeProvenancePatch(provenance = {}) {
    const normalized = {};
    for (const [field, value] of Object.entries(provenance)) {
      normalized[field] = normalizeProvenanceEntry(field, value);
    }
    return normalized;
  }

  function workflowProvenanceGaps(session, beforeAction = "workflow.generate") {
    if (session.mode === "modify") return [];
    const profile = workflowProfile(session.profileId);
    const gaps = [];
    for (const requirement of plainArray(profile.evidenceRequirements)) {
      if (!sameToolAction(requirement.requiredBefore, beforeAction)) continue;
      const allowed = plainArray(requirement.allowedProvenance).length
        ? requirement.allowedProvenance
        : ["user", "source-derived"];
      for (const field of plainArray(requirement.provenanceFields)) {
        const entry = session.provenance?.[field];
        if (!entry) {
          gaps.push({ field, requirementId: requirement.id, allowedProvenance: allowed, reason: "missing" });
        } else if (!allowed.includes(entry.source)) {
          gaps.push({ field, requirementId: requirement.id, source: entry.source, allowedProvenance: allowed, reason: "source-not-allowed" });
        }
      }
    }
    if (sameToolAction(beforeAction, "workflow.generate") && workflowPublicTargetSafetyRequired(session) && workflowPublicTargetSafetyConfirmed(session)) {
      const field = "plan.safety.publicTargetConfirmed";
      const allowed = ["user", "source-derived"];
      const entry = session.provenance?.[field];
      if (!entry) {
        gaps.push({ field, requirementId: "safety.public-target-confirmation", allowedProvenance: allowed, reason: "missing" });
      } else if (!allowed.includes(entry.source)) {
        gaps.push({ field, requirementId: "safety.public-target-confirmation", source: entry.source, allowedProvenance: allowed, reason: "source-not-allowed" });
      }
    }
    if (sameToolAction(beforeAction, "workflow.generate") && workflowUnsupportedConstructs(session).length && workflowSourceLimitationsAccepted(session)) {
      const field = "plan.sourceFidelity.userAcceptedLimitations";
      const allowed = ["user", "source-derived"];
      const entry = session.provenance?.[field];
      if (!entry) {
        gaps.push({ field, requirementId: "source-fidelity.limitations-accepted", allowedProvenance: allowed, reason: "missing" });
      } else if (!allowed.includes(entry.source)) {
        gaps.push({ field, requirementId: "source-fidelity.limitations-accepted", source: entry.source, allowedProvenance: allowed, reason: "source-not-allowed" });
      }
    }
    return gaps;
  }

  function workflowEvidenceRequirementStatuses(session, options = {}) {
    if (session.mode === "modify") return [];
    const profile = workflowProfile(session.profileId);
    return plainArray(profile.evidenceRequirements).map(requirement => {
      const missingFields = plainArray(requirement.fields)
        .filter(field => !hasValue(getPath({ plan: session.plan || {} }, field)));
      const provenanceGaps = workflowProvenanceGaps(session, requirement.requiredBefore)
        .filter(gap => gap.requirementId === requirement.id);
      const evidenceOk = requirement.evidence ? getPath({ evidence: session.evidence || {} }, `evidence.${requirement.evidence}`) === true : true;
      const status = missingFields.length || provenanceGaps.length || !evidenceOk ? "missing" : "satisfied";
      return {
        id: requirement.id,
        label: requirement.label,
        requiredBefore: options.exposed ? toolName(requirement.requiredBefore) : requirement.requiredBefore,
        status,
        missingFields,
        provenanceGaps,
        evidence: requirement.evidence || null,
      };
    });
  }

  function workflowEvidenceGaps(session) {
    const gaps = [];
    if (!session.generated) gaps.push({ id: "bundle.generated", status: "missing" });
    if (!workflowScenarioManagerValidation(session)) gaps.push({ id: "bundle.validated", status: "missing" });
    if (!session.evidence?.deployment) gaps.push({ id: "runtime.deployed", status: "not-run" });
    if (!session.evidence?.runtime) gaps.push({ id: "runtime.verified", status: "not-run" });
    if (!session.evidence?.report) gaps.push({ id: "stakeholder.report", status: "missing" });
    for (const requirement of workflowEvidenceRequirementStatuses(session)) {
      if (requirement.status !== "satisfied") {
        gaps.push({ id: requirement.id, status: requirement.status, requiredBefore: toolName(requirement.requiredBefore) });
      }
    }
    return gaps;
  }

  function workflowQuestionGraph(session, questions = workflowNextQuestions(session)) {
    const nodes = questions.map(question => ({
      id: question.id,
      field: question.field,
      questionKind: question.questionKind,
      questionGroup: question.questionGroup,
      order: question.order,
      dependsOn: question.dependsOn,
      resolvedBy: question.resolution?.tool || null,
    }));
    const edges = nodes.flatMap(node => plainArray(node.dependsOn).map(dep => ({
      from: dep,
      to: node.field,
      questionId: node.id,
    })));
    return { nodes, edges };
  }

  function workflowBlockers(session, questions = workflowNextQuestions(session)) {
    const blockers = questions.map(question => ({
      id: question.id,
      kind: question.questionKind,
      field: question.field,
      blockedAction: question.blockedAction,
      resolvedBy: question.resolution?.tool || null,
      unresolvable: !question.resolution?.tool,
    }));
    if (session.mode !== "modify") {
      for (const gap of workflowRoleCheckGaps(session)) {
        blockers.push({
          id: `role.${gap.stageId}.${gap.roleId}`,
          kind: "role-check",
          field: null,
          blockedAction: toolName("workflow.generate"),
          resolvedBy: toolName("workflow.role.check"),
          unresolvable: false,
          stageId: gap.stageId,
          roleId: gap.roleId,
          status: gap.status,
        });
      }
    }
    return blockers;
  }

  function workflowUnresolvableBlockers(session, blockers = workflowBlockers(session)) {
    return blockers.filter(blocker => blocker.unresolvable || !blocker.resolvedBy);
  }

  function evidenceContractStatus(session, claimId) {
    switch (claimId) {
      case "bundle.generated":
        return session.mode === "modify" ? "not-applicable" : session.evidence?.generation?.ok ? "satisfied" : session.evidence?.generation ? "failed" : "pending";
      case "validation.scenario-manager":
        return workflowScenarioManagerValidation(session)?.ok ? "satisfied" : workflowScenarioManagerValidation(session) ? "failed" : "pending";
      case "stakeholder.report":
      case "observability.output":
        return session.evidence?.report?.ok ? "satisfied" : "pending";
      case "runtime.deployed":
      case "workers.healthy":
      case "queues.drained":
        return session.evidence?.deployment?.ok ? "satisfied" : session.evidence?.deployment ? "failed" : "not-run";
      case "runtime.verified":
      case "mock.matched":
      case "traffic.shape":
      case "dataset.rotated":
      case "auth.token":
        return session.evidence?.runtime?.ok ? "satisfied" : session.evidence?.runtime ? "failed" : "not-run";
      case "dataset.sample-artifact":
        return session.generated?.bundleId && existsSync(resolve(bundleDir(session.generated.bundleId), "datasets", "sample.csv")) ? "satisfied" : "pending";
      default:
        return "pending";
    }
  }

  function workflowEvidenceContract(session) {
    const plan = session.plan || {};
    const datasetStrategy = plan.dataset?.strategy ?? plan.dataSource;
    const mockStrategy = plan.mock?.strategy ?? plan.sutDouble;
    const auth = plan.auth;
    const runtimeRequired = plan.runtime?.required === true || plan.liveDeployment === true;
    const claim = (id, label, stage, required, proofTool, sourceFields = []) => ({
      id,
      label,
      stage,
      required,
      proofTool: proofTool ? toolName(proofTool) : null,
      sourceFields,
      status: evidenceContractStatus(session, id),
    });
    const claims = [
      claim("bundle.generated", "Generated bundle files exist in the active bundle root.", "authoring", session.mode !== "modify", "workflow.generate", ["plan.bundleId"]),
      claim("validation.scenario-manager", "Bundle passes Scenario Manager validation.", "validation", true, "workflow.validate", ["plan.bundleId"]),
      claim("stakeholder.report", "Stakeholder handoff report is written.", "report", true, "workflow.report", ["plan.successCriteria", "plan.observability.goal"]),
      claim("observability.output", "Stakeholder observability output is captured or reported.", "report", true, "workflow.report", ["plan.observability.goal"]),
      claim("runtime.deployed", "Bundle is deployed through official PocketHive APIs.", "runtime", runtimeRequired, "workflow.deploy", ["plan.target"]),
      claim("runtime.verified", "Runtime evidence is collected from supported evidence sources.", "runtime", runtimeRequired, "workflow.verify", ["plan.successCriteria"]),
      claim("workers.healthy", "Runtime worker health is evidenced.", "runtime", runtimeRequired, "workflow.verify", ["plan.observability.goal"]),
      claim("queues.drained", "Runtime queue drain is evidenced.", "runtime", runtimeRequired, "workflow.verify", ["plan.observability.goal"]),
      claim("traffic.shape", "Requested traffic shape is evidenced.", "runtime", runtimeRequired, "workflow.verify", ["plan.traffic.ratePerSec", "plan.traffic.shape", "plan.traffic.duration"]),
    ];
    if (mockStrategy && mockStrategy !== "real_system") {
      claims.push(claim("mock.matched", "Mock matched requests and unmatched requests are evidenced.", "runtime", runtimeRequired, "workflow.verify", ["plan.mock.strategy"]));
    }
    if (datasetStrategy === "CSV_DATASET") {
      claims.push(claim("dataset.sample-artifact", "CSV sample dataset artifact is generated for runtime CSV_DATASET input.", "authoring", false, "workflow.generate", ["plan.dataset.strategy", "plan.dataset.csvColumns"]));
    }
    if (datasetStrategy === "REDIS_DATASET") {
      claims.push(claim("dataset.rotated", "Dataset rotation or consumption is evidenced.", "runtime", runtimeRequired, "workflow.verify", ["plan.dataset.strategy"]));
    }
    if (auth && auth !== "none" && auth?.type !== "none") {
      claims.push(claim("auth.token", "Auth refresh or secret-reference handling is evidenced without inline secrets.", "runtime", runtimeRequired, "workflow.verify", ["plan.auth"]));
    }
    return claims;
  }

  function claimStatus(condition, missingStatus = "missing") {
    return condition ? "satisfied" : missingStatus;
  }

  function workflowClaimMatrix(session) {
    const missing = workflowMissingFields(session);
    const validationErrors = workflowValidationErrors(session);
    const roleGaps = session.mode === "modify" ? [] : workflowRoleCheckGaps(session);
    const bundlePath = session.bundle?.path || session.generated?.path;
    const plan = session.plan || {};
    const datasetStrategy = plan.dataset?.strategy ?? plan.dataSource;
    const auth = plan.auth;
    const mockStrategy = plan.mock?.strategy ?? plan.sutDouble;
    const csvSampleExists = Boolean(session.generated?.bundleId && existsSync(resolve(bundleDir(session.generated.bundleId), "datasets", "sample.csv")));
    const scenarioManagerValidation = workflowScenarioManagerValidation(session);
    const claim = (id, claimText, status, required = true, evidence = [], gap = null) => ({
      id,
      claim: claimText,
      status,
      required,
      evidence,
      gap,
    });

    return [
      claim(
        "workflow.questions",
        "Required workflow questions are answered.",
        claimStatus(missing.length === 0),
        true,
        missing.length ? [] : ["missing=[]"],
        missing.length ? missing.join(", ") : null,
      ),
      claim(
        "workflow.answer-validation",
        "Answers pass deterministic validation.",
        validationErrors.length ? "failed" : "satisfied",
        true,
        validationErrors.length ? validationErrors.map(issue => issue.code) : ["validationIssues=[]"],
        validationErrors.length ? validationErrors.map(issue => issue.field).join(", ") : null,
      ),
      claim(
        "workflow.provenance",
        "Critical answers have accepted provenance.",
        session.mode === "modify" ? "not-applicable" : claimStatus(workflowProvenanceGaps(session).length === 0),
        session.mode !== "modify",
        session.mode === "modify" ? ["modify-mode"] : ["provenanceGaps"],
        session.mode === "modify" ? null : workflowProvenanceGaps(session).map(gap => gap.field).join(", ") || null,
      ),
      claim(
        "workflow.role-checks",
        "Required role checks are complete.",
        session.mode === "modify" ? "not-applicable" : claimStatus(roleGaps.length === 0),
        session.mode !== "modify",
        session.mode === "modify" ? ["modify-mode"] : ["reviewStages"],
        roleGaps.length ? roleGaps.map(gap => `${gap.stageId}/${gap.roleId}`).join(", ") : null,
      ),
      claim(
        "bundle.exists",
        "Workflow bundle exists in the active bundles root.",
        claimStatus(Boolean(bundlePath) && existsSync(bundlePath)),
        true,
        bundlePath ? [bundlePath] : [],
        bundlePath ? null : "No bundle path is associated with the workflow.",
      ),
      claim(
        "generation.bundle",
        "Create-mode bundle generation completed.",
        session.mode === "modify" ? "not-applicable" : claimStatus(Boolean(session.evidence?.generation?.ok)),
        session.mode !== "modify",
        session.evidence?.generation ? ["evidence.generation"] : [],
        session.mode === "modify" ? null : "Run workflow_generate.",
      ),
      claim(
        "validation.scenario-manager",
        "Scenario Manager bundle validation passed.",
        scenarioManagerValidation?.ok ? "satisfied" : scenarioManagerValidation ? "failed" : "missing",
        true,
        scenarioManagerValidation ? ["evidence.scenarioManagerValidation"] : [],
        scenarioManagerValidation?.ok ? null : "Run workflow_validate and fix failures.",
      ),
      claim(
        "runtime.deployed",
        "Runtime deployment was attempted through official PocketHive APIs.",
        session.evidence?.deployment?.ok ? "satisfied" : session.evidence?.deployment ? "failed" : "not-run",
        false,
        session.evidence?.deployment ? ["evidence.deployment"] : [],
        session.evidence?.deployment ? null : "Live deployment was not run.",
      ),
      claim(
        "runtime.verified",
        "Runtime verification evidence was collected.",
        session.evidence?.runtime?.ok ? "satisfied" : session.evidence?.runtime ? "failed" : "not-run",
        false,
        session.evidence?.runtime ? ["evidence.runtime"] : [],
        session.evidence?.runtime ? null : "Runtime verification was not run.",
      ),
      claim(
        "dataset.handling",
        "Dataset strategy is explicit.",
        datasetStrategy && datasetStrategy !== "SCHEDULER" ? "satisfied" : datasetStrategy === "SCHEDULER" ? "not-applicable" : "missing",
        Boolean(datasetStrategy && datasetStrategy !== "SCHEDULER"),
        datasetStrategy ? [`dataset=${datasetStrategy}`] : [],
        datasetStrategy === "CSV_DATASET" ? "CSV creates a sample artifact consumed by the runtime CSV_DATASET input." : datasetStrategy ? null : "No dataset strategy is recorded.",
      ),
      claim(
        "dataset.sample-artifact",
        "CSV sample dataset artifact is generated when CSV_DATASET is selected.",
        datasetStrategy === "CSV_DATASET" ? csvSampleExists ? "satisfied" : "missing" : "not-applicable",
        datasetStrategy === "CSV_DATASET",
        csvSampleExists ? ["datasets/sample.csv"] : [],
        datasetStrategy === "CSV_DATASET" && !csvSampleExists ? "Generate or restore datasets/sample.csv." : null,
      ),
      claim(
        "auth.handling",
        "Auth handling is explicit and avoids inline secrets.",
        !auth || auth === "none" || auth?.type === "none" ? "not-applicable" : validationErrors.some(issue => issue.code === "INLINE_SECRET_NOT_ALLOWED") ? "failed" : "satisfied",
        Boolean(auth && auth !== "none" && auth?.type !== "none"),
        auth ? ["plan.auth"] : [],
        null,
      ),
      claim(
        "target.proof",
        "Mock/live target evidence is available or explicitly not run.",
        session.evidence?.runtime?.ok ? "satisfied" : "not-run",
        false,
        mockStrategy ? [`mock=${mockStrategy}`] : [],
        session.evidence?.runtime?.ok ? null : "Runtime target proof was not collected.",
      ),
      claim(
        "observability.output",
        "Stakeholder observability output is available.",
        session.evidence?.runtime?.ok || session.evidence?.report?.ok ? "satisfied" : "missing",
        true,
        session.evidence?.report?.ok ? ["workflow report"] : [],
        session.evidence?.report?.ok ? null : "Run workflow_report.",
      ),
      claim(
        "stakeholder.report",
        "Stakeholder handoff report was written.",
        session.evidence?.report?.ok ? "satisfied" : "missing",
        true,
        session.evidence?.report?.file ? [session.evidence.report.file] : [],
        session.evidence?.report?.ok ? null : "Run workflow_report.",
      ),
    ];
  }

  function workflowState(session) {
    if (session.reported) return "reported";
    if (session.evidence?.runtime?.ok) return "verified";
    if (session.evidence?.deployment?.ok) return "deployed";
    if (workflowScenarioManagerValidation(session)?.ok) return "validated";
    if (session.generated) return "generated";
    if (!Object.keys(session.plan || {}).length) return "source_ready";
    if (!workflowMissingFields(session).length && workflowValidationErrors(session).length) return "plan_invalid";
    return workflowMissingFields(session).length ? "plan_incomplete" : "plan_ready";
  }

  function latestWorkflowAttempt(session) {
    return session.history[session.history.length - 1] || null;
  }

  function workflowActiveRoleId(session) {
    const latest = latestWorkflowAttempt(session);
    if (latest?.ok === false && latest.code === "WORKFLOW_ENV_AUTH_FAILED") {
      return ROLE_IDS.SECURITY_REVIEWER;
    }
    if (latest?.ok === false && latest.code === "WORKFLOW_EXTERNAL_VALIDATION_FAILED") {
      return ROLE_IDS.POCKETHIVE_SME;
    }
    if (latest?.ok === false && ["generate", "validate", "deploy", "verify", "patch"].includes(latest.action)) {
      return ROLE_IDS.DEVELOPER;
    }

    switch (workflowState(session)) {
      case "source_ready":
      case "plan_incomplete":
        return ROLE_IDS.ARCHITECT;
      case "plan_ready":
      case "validated":
        return ROLE_IDS.POCKETHIVE_SME;
      case "generated":
        return ROLE_IDS.TESTER;
      case "deployed":
        return ROLE_IDS.PERFORMANCE_TESTING_SPECIALIST;
      case "verified":
      case "reported":
        return ROLE_IDS.TESTER;
      default:
        return workflowProfile(session.profileId).defaultRole;
    }
  }

  function workflowRoleChecklist(session) {
    const role = workflowRole(workflowActiveRoleId(session));
    return role.checklist.map((text, index) => ({
      id: `${role.id}.${index + 1}`,
      roleId: role.id,
      text,
      authority: GUIDANCE_AUTHORITY,
    }));
  }

  function roleCheckKey(stageId, roleId) {
    return `${stageId}:${roleId}`;
  }

  function workflowReviewStageStatuses(session, options = {}) {
    const profile = workflowProfile(session.profileId);
    return profile.reviewStages.map(stage => {
      const requiredRoles = stage.roles.map(roleId => {
        const check = session.roleChecks?.[roleCheckKey(stage.id, roleId)] || null;
        const status = check && ["pass", "risk-accepted"].includes(check.outcome) ? "complete" : check?.outcome === "fail" ? "failed" : "missing";
        return {
          roleId,
          label: workflowRole(roleId).label,
          status,
          check,
        };
      });
      const status = requiredRoles.every(role => role.status === "complete")
        ? "complete"
        : requiredRoles.some(role => role.status === "failed")
          ? "failed"
          : "missing";
      return {
        id: stage.id,
        label: stage.label,
        beforeAction: options.exposed ? toolName(stage.beforeAction) : stage.beforeAction,
        purpose: stage.purpose,
        status,
        requiredRoles,
      };
    });
  }

  function workflowRoleCheckGaps(session, beforeAction = "workflow.generate") {
    return workflowReviewStageStatuses(session)
      .filter(stage => sameToolAction(stage.beforeAction, beforeAction) && stage.status !== "complete")
      .flatMap(stage => stage.requiredRoles
        .filter(role => role.status !== "complete")
        .map(role => ({ stageId: stage.id, roleId: role.roleId, status: role.status })));
  }

  function workflowAllowedActions(session) {
    const actions = ["workflow.status", "workflow.result", "workflow.evidence.render", "workflow.source.read", "workflow.update"];
    const deployOperation = session.activeOperations?.deploy ? session.operations?.[session.activeOperations.deploy] : null;
    const verifyOperation = session.activeOperations?.verify ? session.operations?.[session.activeOperations.verify] : null;
    const missing = workflowMissingFields(session);
    const hasValidationErrors = workflowValidationErrors(session).length > 0;
    const validationOk = Boolean(workflowScenarioManagerValidation(session)?.ok);
    if (session.mode === "modify") {
      if (missing.length === 0 && !hasValidationErrors) actions.push("workflow.preview", "workflow.patch", "workflow.validate", "workflow.report");
      if (validationOk) actions.push("workflow.deploy", "workflow.deploy.start");
      if (deployOperation) actions.push("workflow.deploy.status", ...(deployOperation.status === "running" ? ["workflow.deploy.resume"] : []));
      if (session.evidence?.deployment?.ok) actions.push("workflow.verify", "workflow.verify.start");
      if (verifyOperation) actions.push("workflow.verify.status", ...(verifyOperation.status === "running" ? ["workflow.verify.resume"] : []));
      return actions.map(toolName);
    }
    if (missing.length === 0 && !session.generated) {
      actions.push("workflow.preview", "workflow.role.check");
      if (!hasValidationErrors && workflowProvenanceGaps(session).length === 0 && workflowRoleCheckGaps(session).length === 0) {
        actions.push("workflow.generate");
      }
    }
    if (session.generated) actions.push("workflow.patch", "workflow.validate", "workflow.report");
    if (validationOk) actions.push("workflow.deploy", "workflow.deploy.start");
    if (deployOperation) actions.push("workflow.deploy.status", ...(deployOperation.status === "running" ? ["workflow.deploy.resume"] : []));
    if (session.evidence?.deployment?.ok) actions.push("workflow.verify", "workflow.verify.start");
    if (verifyOperation) actions.push("workflow.verify.status", ...(verifyOperation.status === "running" ? ["workflow.verify.resume"] : []));
    return actions.map(toolName);
  }

  function workflowPatchScope(session) {
    if (!session.generated?.bundleId) return [];
    const path = workflowBundlePath(session);
    return [`${path}/**`, `${path}/mock-config/**`];
  }

  function workflowBundleFileList(dir, prefix = "") {
    if (!existsSync(dir)) return [];
    const files = [];
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.name.startsWith(".")) continue;
      const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
      const path = resolve(dir, entry.name);
      if (entry.isDirectory()) files.push(...workflowBundleFileList(path, rel));
      else if (!WORKFLOW_TRACE_EXCLUDED_FILES.has(rel)) files.push(rel);
    }
    return files.sort();
  }

  function workflowArtifactFingerprint(session) {
    if (!session.generated?.bundleId) return null;
    const dir = bundleDir(session.generated.bundleId);
    if (!existsSync(dir)) return null;
    const hash = createHash("sha256");
    for (const file of workflowBundleFileList(dir)) {
      hash.update(file);
      hash.update("\0");
      hash.update(readFileSync(resolve(dir, file)));
      hash.update("\0");
    }
    return hash.digest("hex");
  }

  function workflowEvidenceText(value) {
    if (value === undefined || value === null) return "";
    if (typeof value === "string") return value;
    if (value instanceof Error) return value.message || String(value);
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }

  function workflowEvidenceIsAuthFailure(value) {
    const text = workflowEvidenceText(value).toLowerCase();
    return /\b401\b/.test(text)
      || text.includes("unauthorized")
      || text.includes("unauthorised")
      || text.includes("invalid or expired bearer token")
      || text.includes("missing authorization")
      || text.includes("login failed");
  }

  function workflowFailureIsEnvironmentAuth(failure) {
    return failure?.code === "WORKFLOW_ENV_AUTH_FAILED"
      || workflowEvidenceIsAuthFailure(failure?.evidence || failure);
  }

  function workflowFailureNeedsPatch(failureCode) {
    return failureCode === "WORKFLOW_VALIDATION_FAILED"
      || failureCode === "WORKFLOW_ANSWER_VALIDATION_FAILED";
  }

  function workflowSuggestedNextActions(failureCode) {
    let actions;
    if (failureCode === "WORKFLOW_VALIDATION_FAILED") {
      actions = ["workflow.patch", "workflow.validate", "workflow.status"];
    } else if (failureCode === "WORKFLOW_ENV_AUTH_FAILED") {
      actions = ["env.status", "health.check", "workflow.status", "workflow.validate", "workflow.deploy.start"];
    } else if (failureCode === "WORKFLOW_EXTERNAL_VALIDATION_FAILED") {
      actions = ["env.status", "health.check", "workflow.status", "workflow.validate", "workflow.report"];
    } else if (failureCode === "WORKFLOW_DEPLOY_FAILED" || failureCode === "WORKFLOW_DEPLOY_NOT_READY") {
      actions = ["workflow.status", "workflow.validate", "workflow.deploy.start", "workflow.deploy"];
    } else if (failureCode === "WORKFLOW_VERIFY_FAILED" || failureCode === "WORKFLOW_RUNTIME_EVIDENCE_INCOMPLETE") {
      actions = ["workflow.status", "workflow.verify.start", "workflow.verify", "workflow.report"];
    } else {
      actions = ["workflow.status", "workflow.update"];
    }
    return actions.map(toolName);
  }

  function workflowRemediation(session, failureCode = null) {
    const failed = failureCode
      ? { code: failureCode }
      : [...session.history].reverse().find(entry => entry.ok === false && entry.code);
    if (!failed?.code) return null;
    return {
      failureCode: failed.code,
      activeRole: workflowRolePayload(workflowActiveRoleId(session)),
      suggestedNextActions: workflowSuggestedNextActions(failed.code),
      patchScope: workflowFailureNeedsPatch(failed.code) ? workflowPatchScope(session) : [],
      stuckState: workflowStuckState(session),
    };
  }

  function workflowStuckState(session) {
    const latest = [...session.history].reverse().find(entry => entry.ok === false && entry.code);
    if (!latest) return { stuck: false, attempts: 0 };
    let attempts = 0;
    for (let index = session.history.length - 1; index >= 0; index -= 1) {
      const entry = session.history[index];
      if (entry.ok !== false || entry.action !== latest.action || entry.code !== latest.code) break;
      if ((entry.artifactFingerprint || null) !== (latest.artifactFingerprint || null)) break;
      attempts += 1;
    }
    if (attempts < WORKFLOW_STUCK_ATTEMPTS) return { stuck: false, attempts };
    return {
      stuck: true,
      action: latest.action,
      failureCode: latest.code,
      attempts,
      artifactFingerprint: latest.artifactFingerprint || null,
      reason: `Same ${latest.action} failure ${latest.code} repeated ${attempts} times without an artifact change.`,
      suggestedNextActions: workflowSuggestedNextActions(latest.code),
      patchScope: workflowFailureNeedsPatch(latest.code) ? workflowPatchScope(session) : [],
    };
  }

  function workflowLatestFailure(session) {
    return [...session.history].reverse().find(entry => entry.ok === false && entry.code) || null;
  }

  function workflowLatestBlockingFailure(session) {
    const latest = latestWorkflowAttempt(session);
    return latest?.ok === false && latest.code ? latest : null;
  }

  function workflowFailureIsExternalValidation(session, failure) {
    if (failure?.code === "WORKFLOW_EXTERNAL_VALIDATION_FAILED" || failure?.code === "WORKFLOW_ENV_AUTH_FAILED") return true;
    if (failure?.action !== "validate") return false;
    const evidence = failure?.evidence || {};
    return Boolean(evidence.failureKind || (evidence.error && !evidence.scenarioManager));
  }

  function workflowAgentPhase(session) {
    const state = workflowState(session);
    const latestFailure = workflowLatestBlockingFailure(session);
    const deployOperation = session.activeOperations?.deploy ? session.operations?.[session.activeOperations.deploy] : null;
    const verifyOperation = session.activeOperations?.verify ? session.operations?.[session.activeOperations.verify] : null;
    if (deployOperation?.status === "running") return AGENT_PHASES.DEPLOYMENT;
    if (verifyOperation?.status === "running") return AGENT_PHASES.RUNTIME;
    if (latestFailure?.action === "validate") return AGENT_PHASES.VALIDATION;
    if (latestFailure?.action === "deploy" || latestFailure?.action === "deploy.start") return AGENT_PHASES.DEPLOYMENT;
    if (latestFailure?.action === "verify" || latestFailure?.action === "verify.start") return AGENT_PHASES.RUNTIME;
    if (state === "source_ready" || state === "plan_incomplete") return AGENT_PHASES.INTAKE;
    if (state === "plan_invalid" || state === "plan_ready") return AGENT_PHASES.PLANNING;
    if (state === "generated") return AGENT_PHASES.VALIDATION;
    if (state === "validated") return AGENT_PHASES.DEPLOYMENT;
    if (state === "deployed") return AGENT_PHASES.RUNTIME;
    if (state === "verified") return AGENT_PHASES.REPORT;
    if (state === "reported") return AGENT_PHASES.EVIDENCE;
    return AGENT_PHASES.PLANNING;
  }

  function workflowRuntimeVerdict(session) {
    const verdict = session.evidence?.runtime?.evidence?.report?.verdict;
    if (verdict === "pass") return AGENT_VERDICTS.PASSED;
    if (verdict === "partial") return AGENT_VERDICTS.PARTIAL;
    if (session.evidence?.runtime?.ok) return AGENT_VERDICTS.PASSED;
    return null;
  }

  function workflowAgentVerdict(session) {
    const deployOperation = session.activeOperations?.deploy ? session.operations?.[session.activeOperations.deploy] : null;
    const verifyOperation = session.activeOperations?.verify ? session.operations?.[session.activeOperations.verify] : null;
    if (deployOperation?.status === "running" || verifyOperation?.status === "running") return AGENT_VERDICTS.RUNNING;
    if (workflowLatestBlockingFailure(session)) return AGENT_VERDICTS.FAILED;
    const roleGaps = session.mode === "modify" ? [] : workflowRoleCheckGaps(session);
    if (workflowMissingFields(session).length || workflowValidationErrors(session).length || workflowProvenanceGaps(session).length || roleGaps.length) {
      return AGENT_VERDICTS.NEEDS_INPUT;
    }
    const runtimeVerdict = workflowRuntimeVerdict(session);
    if (runtimeVerdict) return runtimeVerdict;
    if (workflowScenarioManagerValidation(session)?.ok || session.generated) return AGENT_VERDICTS.READY;
    return AGENT_VERDICTS.NEEDS_INPUT;
  }

  function workflowFailureSummary(code) {
    switch (code) {
      case "WORKFLOW_VALIDATION_FAILED":
        return "Bundle validation failed; patch the generated bundle and validate again.";
      case "WORKFLOW_ENV_AUTH_FAILED":
        return "PocketHive environment auth failed; refresh MCP auth/environment settings and retry the workflow step.";
      case "WORKFLOW_EXTERNAL_VALIDATION_FAILED":
        return "Scenario Manager validation could not complete; inspect Scenario Manager availability before patching bundle files.";
      case "WORKFLOW_DEPLOY_FAILED":
        return "Runtime deployment failed while calling PocketHive APIs.";
      case "WORKFLOW_DEPLOY_NOT_READY":
        return "Runtime deployment did not reach ready state before the timeout.";
      case "WORKFLOW_RUNTIME_NOT_STARTED":
        return "Runtime verification needs a deployed swarm or explicit swarm id.";
      case "WORKFLOW_RUNTIME_EVIDENCE_INCOMPLETE":
        return "Runtime evidence was collected, but it did not satisfy the expected proof.";
      case "WORKFLOW_VERIFY_FAILED":
        return "Runtime verification failed while collecting evidence.";
      default:
        return code ? `Workflow failed with ${code}.` : "No failure is recorded.";
    }
  }

  function checklistStatus(evidence, id) {
    return evidence?.report?.checklist?.find(item => item.id === id)?.status || "not-run";
  }

  function workflowWorkerHealth(session) {
    const ctx = session.evidence?.deployment?.evidence?.ready?.status?.envelope?.data?.context
      || session.evidence?.runtime?.evidence?.lifecycle?.raw?.envelope?.data?.context
      || {};
    const totals = ctx.totals || {};
    return {
      desired: typeof totals.desired === "number" ? totals.desired : null,
      healthy: typeof totals.healthy === "number" ? totals.healthy : null,
      state: ctx.state || ctx.swarmStatus || session.evidence?.runtime?.evidence?.report?.lifecycleState || null,
      satisfied: typeof totals.desired === "number" && totals.desired > 0 && totals.healthy >= totals.desired,
    };
  }

  function workflowFlowProof(session) {
    const flow = session.evidence?.runtime?.evidence?.flow || {};
    const expected = flow.expected || [];
    const observed = flow.observed || [];
    return {
      expected,
      observed,
      matched: expected.length > 0 && expected.length === observed.length && expected.every((item, index) => item === observed[index]),
    };
  }

  function workflowScenarioManagerValidation(session) {
    return session.evidence?.scenarioManagerValidation
      || (session.evidence?.validation?.validationLevel === "scenario-manager" ? session.evidence.validation : null);
  }

  function workflowProofSummary(session) {
    const runtimeEvidence = session.evidence?.runtime?.evidence || null;
    const mocks = runtimeEvidence?.mocks || {};
    const scenarioManagerValidation = workflowScenarioManagerValidation(session);
    const latestValidation = session.evidence?.validation || null;
    return {
      validation: {
        status: latestValidation ? latestValidation.ok ? "pass" : "fail" : "not-run",
        code: latestValidation?.code || null,
        authoritative: Boolean(latestValidation?.authoritative),
        latestLevel: latestValidation?.validationLevel || null,
        scenarioManager: {
          status: scenarioManagerValidation ? scenarioManagerValidation.ok ? "pass" : "fail" : "not-run",
          code: scenarioManagerValidation?.code || null,
          authoritative: Boolean(scenarioManagerValidation?.authoritative),
        },
      },
      deployment: {
        status: session.evidence?.deployment ? session.evidence.deployment.ok ? "pass" : "fail" : "not-run",
        code: session.evidence?.deployment?.code || null,
        swarmId: session.swarmId || session.evidence?.deployment?.evidence?.swarmId || null,
      },
      runtime: {
        status: session.evidence?.runtime ? session.evidence.runtime.ok ? "pass" : "fail" : "not-run",
        code: session.evidence?.runtime?.code || null,
        reportVerdict: runtimeEvidence?.report?.verdict || null,
      },
      workers: workflowWorkerHealth(session),
      queues: {
        status: checklistStatus(runtimeEvidence, "queues.drained"),
      },
      traffic: {
        status: checklistStatus(runtimeEvidence, "requests.handled"),
        flow: workflowFlowProof(session),
        tapFlow: runtimeEvidence?.tapFlow || runtimeEvidence?.report?.tapFlow || null,
      },
      mocks: {
        wiremockRequests: typeof mocks.wiremockRequests === "number" ? mocks.wiremockRequests : null,
        wiremockUnmatched: typeof mocks.wiremockUnmatched === "number" ? mocks.wiremockUnmatched : null,
        status: checklistStatus(runtimeEvidence, "requests.handled"),
      },
      payloads: {
        status: checklistStatus(runtimeEvidence, "payloads.valid"),
      },
    };
  }

  function workflowReportFilePath(session) {
    const file = session.evidence?.report?.file;
    if (!file || !session.generated?.bundleId) return null;
    return resolve(bundleDir(session.generated.bundleId), file);
  }

  function workflowAgentRefs(session) {
    const bundleId = session.generated?.bundleId || session.bundle?.id || session.plan?.bundleId || null;
    const bundlePath = session.generated?.path || session.bundle?.path || (bundleId ? bundleDir(bundleId) : null);
    return {
      workflowId: session.workflowId,
      bundleId,
      bundlePath,
      swarmId: session.swarmId || session.evidence?.deployment?.evidence?.swarmId || null,
      reportFile: workflowReportFilePath(session),
      traceFile: bundlePath ? resolve(bundlePath, WORKFLOW_TRACE_FILE) : null,
      activeOperations: session.activeOperations || {},
    };
  }

  function workflowAgentDiagnosis(session) {
    const missing = workflowMissingFields(session);
    const validationIssues = workflowValidationIssues(session);
    const provenanceGaps = workflowProvenanceGaps(session);
    const roleGaps = session.mode === "modify" ? [] : workflowRoleCheckGaps(session);
    const failure = workflowLatestBlockingFailure(session);
    if (failure) {
      return {
        code: failure.code,
        message: workflowFailureIsEnvironmentAuth(failure)
          ? "PocketHive API auth failed; inspect MCP environment settings or refresh the active auth profile before patching bundle files."
          : workflowFailureIsExternalValidation(session, failure)
          ? "Scenario Manager validation could not complete; inspect MCP/runtime auth or Scenario Manager availability before patching bundle files."
          : workflowFailureSummary(failure.code),
        evidence: failure.evidence || null,
        causes: failure.code === "WORKFLOW_VALIDATION_FAILED"
          ? validationIssues.map(issue => ({ field: issue.field, code: issue.code, message: issue.message }))
          : [],
      };
    }
    if (missing.length) {
      return {
        code: "WORKFLOW_PLAN_INCOMPLETE",
        message: "Required workflow answers are still missing.",
        missing,
        causes: missing.map(field => ({ field, code: "MISSING_FIELD" })),
      };
    }
    if (validationIssues.length) {
      return {
        code: "WORKFLOW_ANSWER_VALIDATION_FAILED",
        message: "The normalized workflow plan has validation issues.",
        causes: validationIssues.map(issue => ({ field: issue.field, code: issue.code, message: issue.message })),
      };
    }
    if (provenanceGaps.length) {
      return {
        code: "WORKFLOW_PROVENANCE_INCOMPLETE",
        message: "Critical answers need user or source-derived provenance before generation.",
        causes: provenanceGaps.map(gap => ({ field: gap.field, code: gap.reason })),
      };
    }
    if (roleGaps.length) {
      return {
        code: "WORKFLOW_ROLE_CHECKS_INCOMPLETE",
        message: "Required role review checks are not complete.",
        causes: roleGaps.map(gap => ({ stageId: gap.stageId, roleId: gap.roleId, status: gap.status })),
      };
    }
    if (session.evidence?.runtime?.ok) {
      return {
        code: session.evidence.runtime.code,
        message: "Runtime evidence has been collected.",
        causes: [],
      };
    }
    return {
      code: null,
      message: "No blocker is recorded.",
      causes: [],
    };
  }

  function workflowAgentNextAction(session) {
    const missing = workflowMissingFields(session);
    if (missing.length) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.ANSWER,
        tool: toolName("workflow.update"),
        reason: "Provide the missing normalized plan fields.",
        fields: missing,
      };
    }
    const validationIssues = workflowValidationIssues(session);
    if (validationIssues.length) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.UPDATE,
        tool: toolName("workflow.update"),
        reason: "Correct invalid normalized plan fields before continuing.",
        fields: validationIssues.map(issue => issue.field),
      };
    }
    const provenanceGaps = workflowProvenanceGaps(session);
    if (provenanceGaps.length) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.UPDATE,
        tool: toolName("workflow.update"),
        reason: "Attach accepted provenance for critical answers.",
        fields: provenanceGaps.map(gap => gap.field),
      };
    }
    const roleGaps = session.mode === "modify" ? [] : workflowRoleCheckGaps(session);
    if (roleGaps.length) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.REVIEW,
        tool: toolName("workflow.role.check"),
        reason: "Record the required role review checkpoint before generation.",
        stageId: roleGaps[0].stageId,
        roleId: roleGaps[0].roleId,
      };
    }
    const deployOperation = session.activeOperations?.deploy ? session.operations?.[session.activeOperations.deploy] : null;
    if (deployOperation?.status === "running") {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.RESUME,
        tool: toolName("workflow.deploy.resume"),
        reason: "Resume the active deployment operation after the operation nextPollAfterMs interval.",
        operationId: deployOperation.operationId,
        nextPollAfterMs: deployOperation.nextPollAfterMs || 0,
        statusTool: toolName("workflow.deploy.status"),
      };
    }
    const verifyOperation = session.activeOperations?.verify ? session.operations?.[session.activeOperations.verify] : null;
    if (verifyOperation?.status === "running") {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.RESUME,
        tool: toolName("workflow.verify.resume"),
        reason: "Resume the active runtime verification operation after the operation nextPollAfterMs interval.",
        operationId: verifyOperation.operationId,
        nextPollAfterMs: verifyOperation.nextPollAfterMs || 0,
        statusTool: toolName("workflow.verify.status"),
      };
    }
    const failure = workflowLatestBlockingFailure(session);
    if (workflowFailureIsEnvironmentAuth(failure)) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.CHECK,
        tool: toolName("env.status"),
        reason: "PocketHive auth failed outside the generated bundle; check MCP environment/auth state, then retry the failed workflow step.",
        followUpTool: failure?.action === "deploy" ? toolName("workflow.deploy.start") : toolName("workflow.validate"),
        validator: failure?.action === "validate" ? "scenario-manager-dry-run" : undefined,
      };
    }
    if (failure?.action === "validate" || failure?.code === "WORKFLOW_VALIDATION_FAILED") {
      if (workflowFailureIsExternalValidation(session, failure)) {
        return {
          kind: AGENT_NEXT_ACTION_KINDS.VALIDATE,
          tool: toolName("workflow.validate"),
          reason: "Retry Scenario Manager validation after fixing MCP/runtime auth, or use workflow.result/evidence to continue with recorded non-authoritative proof.",
          validator: "scenario-manager-dry-run",
        };
      }
      return {
        kind: AGENT_NEXT_ACTION_KINDS.PATCH,
        tool: toolName("workflow.patch"),
        reason: "Patch the generated bundle, then validate again.",
        followUpTool: toolName("workflow.validate"),
        patchScope: workflowPatchScope(session),
      };
    }
    if (failure?.action === "deploy" || failure?.code === "WORKFLOW_DEPLOY_FAILED" || failure?.code === "WORKFLOW_DEPLOY_NOT_READY") {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.DEPLOY,
        tool: toolName("workflow.deploy.start"),
        reason: "Retry deployment with resumable polling after inspecting the deploy evidence.",
      };
    }
    if (failure?.action === "verify" || failure?.code === "WORKFLOW_VERIFY_FAILED" || failure?.code === "WORKFLOW_RUNTIME_EVIDENCE_INCOMPLETE") {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.VERIFY,
        tool: toolName("workflow.verify.start"),
        reason: "Retry runtime verification with resumable polling after inspecting evidence gaps.",
      };
    }
    if (!session.generated?.bundleId && session.mode !== "modify") {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.GENERATE,
        tool: toolName("workflow.generate"),
        reason: "The workflow is ready to generate bundle files.",
      };
    }
    if (!workflowScenarioManagerValidation(session)?.ok) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.VALIDATE,
        tool: toolName("workflow.validate"),
        reason: "Validate the generated bundle before runtime deployment.",
      };
    }
    if (!session.evidence?.deployment?.ok) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.DEPLOY,
        tool: toolName("workflow.deploy.start"),
        reason: "Create a resumable deploy operation through official PocketHive APIs; follow nextPollAfterMs and resume until complete.",
      };
    }
    if (!session.evidence?.runtime?.ok) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.VERIFY,
        tool: toolName("workflow.verify.start"),
        reason: "Create a resumable runtime verification operation and resume it until evidence is complete.",
      };
    }
    if (!session.evidence?.report?.ok) {
      return {
        kind: AGENT_NEXT_ACTION_KINDS.REPORT,
        tool: toolName("workflow.report"),
        reason: "Write the stakeholder handoff report.",
      };
    }
    return {
      kind: AGENT_NEXT_ACTION_KINDS.NONE,
      tool: null,
      reason: "No next action is required; the workflow has a rendered handoff report.",
    };
  }

  function workflowAgentSummary(session, verdict, phase) {
    const refs = workflowAgentRefs(session);
    if (verdict === AGENT_VERDICTS.FAILED) return workflowFailureSummary(workflowLatestBlockingFailure(session)?.code);
    if (verdict === AGENT_VERDICTS.NEEDS_INPUT) return "Workflow needs more plan, provenance, or role-review input before it can advance.";
    if (verdict === AGENT_VERDICTS.RUNNING) return `Workflow has an active ${phase} operation.`;
    if (verdict === AGENT_VERDICTS.PASSED) return "Workflow runtime proof passed.";
    if (verdict === AGENT_VERDICTS.PARTIAL) return "Workflow runtime proof is acceptable with recorded evidence gaps.";
    if (session.evidence?.report?.ok) return "Workflow report has been written.";
    if (refs.bundleId) return `Workflow is ready for the next ${phase} step for ${refs.bundleId}.`;
    return "Workflow is ready for the next step.";
  }

  function workflowAgentView(session) {
    const phase = workflowAgentPhase(session);
    const verdict = workflowAgentVerdict(session);
    return {
      workflowId: session.workflowId,
      verdict,
      phase,
      summary: workflowAgentSummary(session, verdict, phase),
      diagnosis: workflowAgentDiagnosis(session),
      nextAction: workflowAgentNextAction(session),
      proof: workflowProofSummary(session),
      refs: workflowAgentRefs(session),
      detailRefs: {
        statusTool: toolName("workflow.status"),
        evidenceTool: toolName("workflow.evidence.render"),
        reportTool: toolName("workflow.report"),
      },
    };
  }

  function workflowHiveMindUrl() {
    return process.env.HIVEMIND_MCP_URL || process.env.HIVEMIND_BASE_URL || process.env.HIVEMIND_API_BASE_URL || "";
  }

  function workflowEnrichmentStatus(session) {
    const url = workflowHiveMindUrl();
    return {
      hivemind: {
        status: url ? "configured" : "not-configured",
        url: url || null,
        lastAttempt: session.enrichment?.hivemind || null,
      },
    };
  }

  function workflowRedactedSnapshot(session) {
    const status = workflowStatusPayload(session);
    return {
      workflowId: session.workflowId,
      workflowType: session.workflowType,
      mode: session.mode || "create",
      profileId: session.profileId,
      state: status.state,
      source: {
        type: session.source.type,
        path: session.source.path,
        sha256: session.source.sha256,
        bytes: session.source.bytes,
      },
      bundle: status.bundle,
      generated: status.generated ? { bundleId: status.generated.bundleId, path: status.generated.path } : null,
      missing: status.missing,
      provenanceGaps: status.provenanceGaps,
      reviewStages: status.reviewStages,
      evidenceRequirements: status.evidenceRequirements,
      evidenceGaps: status.evidenceGaps,
      validationIssues: status.validationIssues,
      claimMatrix: status.claimMatrix,
      history: session.history.map(entry => ({
        id: entry.id,
        action: entry.action,
        ok: entry.ok,
        at: entry.at,
        code: entry.code,
      })),
    };
  }

  function candidateHiveMindMcpUrls(value) {
    const parsed = new URL(value);
    const candidates = [parsed.toString()];
    const withPath = new URL(parsed.toString());
    withPath.pathname = "/mcp";
    withPath.search = "";
    withPath.hash = "";
    candidates.push(withPath.toString());
    if (parsed.port === "4010") {
      const derived = new URL(parsed.toString());
      derived.port = "4011";
      derived.pathname = "/mcp";
      derived.search = "";
      derived.hash = "";
      candidates.push(derived.toString());
    }
    return [...new Set(candidates)];
  }

  async function mcpHttpRequest(state, method, params = {}) {
    const headers = {
      Accept: "application/json, text/event-stream",
      "Content-Type": "application/json",
    };
    if (state.sessionId) headers["mcp-session-id"] = state.sessionId;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 4000);
    try {
      const response = await fetch(state.url, {
        method: "POST",
        headers,
        body: JSON.stringify({ jsonrpc: "2.0", id: `workflow-hivemind-${Date.now()}`, method, params }),
        signal: controller.signal,
      });
      const nextSessionId = response.headers.get("mcp-session-id");
      if (nextSessionId) state.sessionId = nextSessionId;
      const text = await response.text();
      if (!response.ok) throw new Error(`${state.url} ${method} returned HTTP ${response.status}`);
      const payload = text.includes("data:")
        ? JSON.parse(text.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => line.slice(5).trim()).join("\n"))
        : JSON.parse(text || "{}");
      if (payload.error) throw new Error(payload.error.message || JSON.stringify(payload.error));
      return payload.result ?? payload;
    } finally {
      clearTimeout(timeout);
    }
  }

  async function resolveHiveMindMcpClient(url) {
    const failures = [];
    for (const candidate of candidateHiveMindMcpUrls(url)) {
      const state = { url: candidate, sessionId: "" };
      try {
        await mcpHttpRequest(state, "initialize", {
          protocolVersion: "2025-06-18",
          capabilities: {},
          clientInfo: { name: "pockethive-workflow-enrichment", version: "1.0.0" },
        });
        const listed = await mcpHttpRequest(state, "tools/list");
        const tools = new Set((listed.tools || []).map(tool => typeof tool === "string" ? tool : tool?.name).filter(Boolean));
        return { state, tools };
      } catch (err) {
        failures.push(`${candidate}: ${err.message}`);
      }
    }
    throw new Error(`HiveMind MCP endpoint could not be resolved from ${url}; tried ${failures.join("; ")}`);
  }

  async function callHiveMindTool(client, name, args) {
    const result = await mcpHttpRequest(client.state, "tools/call", { name, arguments: args });
    if (result.isError) throw new Error(`HiveMind tool ${name} returned an MCP error`);
    return result.structuredContent || mcpContentJson(result) || result;
  }

  function mcpContentJson(result) {
    const text = result?.content?.find?.(item => item?.type === "text" && typeof item.text === "string")?.text;
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  }

  async function enrichHiveMind(session) {
    const url = workflowHiveMindUrl();
    if (!url) {
      const result = { ok: false, status: "not-configured", at: new Date().toISOString() };
      session.enrichment = { ...(session.enrichment || {}), hivemind: result };
      return result;
    }
    try {
      const client = await resolveHiveMindMcpClient(url);
      const snapshot = workflowRedactedSnapshot(session);
      const projectId = process.env.HIVEMIND_PROJECT_ID || "pockethive";
      const branch = process.env.HIVEMIND_BRANCH || "main";
      const workspacePath = process.env.HIVEMIND_WORKSPACE_PATH || POCKETHIVE_ROOT || REPO_ROOT;
      const proofId = `workflow-${session.workflowId}`;
      if (client.tools.has("workflow_summary_write")) {
        await callHiveMindTool(client, "workflow_summary_write", {
          project_id: projectId,
          workflow_run_id: session.workflowId,
          summary: `PocketHive workflow ${session.workflowId} ${workflowState(session)}`,
          snapshot,
          redacted: true,
        });
      } else {
        for (const required of ["session_start", "entry_append"]) {
          if (!client.tools.has(required)) throw new Error(`HiveMind MCP did not advertise required tool ${required}`);
        }
        const started = await callHiveMindTool(client, "session_start", {
          project_id: projectId,
          branch,
          workspace_path: workspacePath,
          author_id: "pockethive-mcp",
          author_type: "agent",
          source: "mcp",
          agent_id: "pockethive-mcp",
          goal: `Persist PocketHive workflow ${session.workflowId}`,
          idempotencyKey: `${proofId}-session`,
        });
        const sessionId = started?.session?.session_id || started?.session_id;
        if (!sessionId) throw new Error("HiveMind session_start did not return a session id");
        await callHiveMindTool(client, "entry_append", {
          project_id: projectId,
          session_id: sessionId,
          branch,
          author_id: "pockethive-mcp",
          author_type: "agent",
          source: "mcp",
          entry_type: "progress",
          summary: `PocketHive workflow ${session.workflowId} ${workflowState(session)}`,
          details: JSON.stringify(snapshot),
          category: "pockethive-workflow",
          tags: ["pockethive", "workflow", session.profileId],
          idempotencyKey: `${proofId}-entry-${session.history.length}`,
        });
        if (client.tools.has("session_end")) {
          await callHiveMindTool(client, "session_end", { session_id: sessionId, status: "completed" });
        }
      }
      const result = { ok: true, status: "written", at: new Date().toISOString(), url: client.state.url };
      session.enrichment = { ...(session.enrichment || {}), hivemind: result };
      persistWorkflowSessions();
      return result;
    } catch (err) {
      const result = { ok: false, status: "failed", at: new Date().toISOString(), error: err.message };
      session.enrichment = { ...(session.enrichment || {}), hivemind: result };
      persistWorkflowSessions();
      return result;
    }
  }

  function workflowStatusPayload(session) {
    const missing = workflowMissingFields(session);
    const profile = workflowProfile(session.profileId);
    const nextQuestions = workflowNextQuestions(session, missing);
    const blockers = workflowBlockers(session, nextQuestions);
    return {
      workflowId: session.workflowId,
      workflowType: session.workflowType,
      mode: session.mode || "create",
      state: workflowState(session),
      profile: workflowProfilePayload(profile),
      activeRole: workflowRolePayload(workflowActiveRoleId(session)),
      roleChecklist: workflowRoleChecklist(session),
      reviewStages: workflowReviewStageStatuses(session, { exposed: true }),
      source: session.source,
      example: session.example || null,
      plan: session.plan || null,
      provenance: session.provenance || {},
      bundle: session.bundle || null,
      generated: session.generated || null,
      missing,
      nextQuestions,
      questionGraph: workflowQuestionGraph(session, nextQuestions),
      validationIssues: workflowValidationIssues(session),
      provenanceGaps: workflowProvenanceGaps(session),
      allowedActions: workflowAllowedActions(session),
      evidenceRequirements: workflowEvidenceRequirementStatuses(session, { exposed: true }),
      evidenceContract: workflowEvidenceContract(session),
      evidenceGaps: workflowEvidenceGaps(session),
      claimMatrix: workflowClaimMatrix(session),
      blockers,
      unresolvableBlockers: workflowUnresolvableBlockers(session, blockers),
      stuckState: workflowStuckState(session),
      evidence: session.evidence,
      operations: session.operations || {},
      activeOperations: session.activeOperations || {},
      remediation: workflowRemediation(session),
      persistence: workflowPersistenceStatus(),
      enrichment: workflowEnrichmentStatus(session),
      agent: workflowAgentView(session),
      history: session.history,
    };
  }

  function workflowEvidenceRenderPayload(session) {
    const status = workflowStatusPayload(session);
    const scenarioManagerValidation = workflowScenarioManagerValidation(session);
    return {
      ...status,
      generatedAt: new Date().toISOString(),
      summary: {
        workflowId: status.workflowId,
        state: status.state,
        mode: status.mode,
        bundleId: status.generated?.bundleId || status.bundle?.id || null,
        sourceType: status.source.type,
        profile: status.profile?.label || null,
        activeRole: status.activeRole?.label || null,
        nextQuestionCount: status.nextQuestions.length,
        validationIssueCount: status.validationIssues.length,
        evidenceGapCount: status.evidenceGaps.length,
        operationCount: Object.keys(status.operations || {}).length,
        validation: status.evidence?.validation?.code || (status.evidence?.validation ? "recorded" : "not-run"),
        scenarioManagerValidation: scenarioManagerValidation?.code || (scenarioManagerValidation ? "recorded" : "not-run"),
        deployment: status.evidence?.deployment?.code || "not-run",
        runtime: status.evidence?.runtime?.code || "not-run",
        verdict: status.agent.verdict,
        phase: status.agent.phase,
        nextAction: status.agent.nextAction,
      },
    };
  }

  function recordWorkflowAttempt(session, action, ok, details = {}) {
    const artifactFingerprint = workflowArtifactFingerprint(session);
    const entry = {
      id: `${action}-${session.history.length + 1}`,
      action,
      ok,
      at: new Date().toISOString(),
      ...(artifactFingerprint ? { artifactFingerprint } : {}),
      ...details,
    };
    session.history.push(entry);
    session.updatedAt = entry.at;
    return entry;
  }

  function newOperationId(type) {
    return `op-${type}-${crypto.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`}`;
  }

  function workflowOperations(session) {
    if (!session.operations || typeof session.operations !== "object" || Array.isArray(session.operations)) session.operations = {};
    if (!session.activeOperations || typeof session.activeOperations !== "object" || Array.isArray(session.activeOperations)) session.activeOperations = {};
    return session.operations;
  }

  function operationNextActions(type, operation) {
    const statusTool = `workflow.${type}.status`;
    const resumeTool = `workflow.${type}.resume`;
    if (operation.status === "running") return [resumeTool, statusTool, "workflow.status"].map(toolName);
    if (operation.status === "succeeded") {
      const next = type === "deploy" ? ["workflow.verify.start", "workflow.verify", statusTool, "workflow.status"] : ["workflow.report", statusTool, "workflow.status"];
      return next.map(toolName);
    }
    if (operation.status === "failed") return [`workflow.${type}.start`, statusTool, "workflow.status"].map(toolName);
    return [statusTool, "workflow.status"].map(toolName);
  }

  function operationLastStep(operation) {
    const attempts = operation.attempts || [];
    const last = attempts[attempts.length - 1];
    if (!last) return null;
    return {
      id: last.id,
      phase: last.phase,
      ok: last.ok,
      at: last.at,
      code: last.code || null,
      ready: typeof last.ready === "boolean" ? last.ready : undefined,
      apiActions: last.apiActions || [],
    };
  }

  function operationPhaseTimeline(operation) {
    const ordered = [];
    const byPhase = new Map();
    for (const attempt of operation.attempts || []) {
      if (!byPhase.has(attempt.phase)) {
        const entry = {
          phase: attempt.phase,
          firstSeenAt: attempt.at,
          lastSeenAt: attempt.at,
          attempts: 0,
          status: "succeeded",
          lastCode: null,
        };
        byPhase.set(attempt.phase, entry);
        ordered.push(entry);
      }
      const entry = byPhase.get(attempt.phase);
      entry.lastSeenAt = attempt.at;
      entry.attempts += 1;
      entry.lastCode = attempt.code || null;
      if (!attempt.ok) entry.status = "failed";
    }
    if (!["complete", "failed", "cancelled"].includes(operation.phase) && !byPhase.has(operation.phase)) {
      const now = operation.updatedAt || operation.createdAt;
      ordered.push({
        phase: operation.phase,
        firstSeenAt: now,
        lastSeenAt: now,
        attempts: 0,
        status: operation.status === "running" ? "running" : operation.status,
        lastCode: null,
      });
    }
    for (const entry of ordered) {
      if (operation.status === "running" && operation.phase === entry.phase) entry.status = "running";
      if (operation.status === "failed" && operation.phase === entry.phase) entry.status = "failed";
    }
    return ordered;
  }

  function operationPayload(session, operation) {
    const apiActions = operation.apiActions || [];
    return {
      workflowId: session.workflowId,
      operationId: operation.operationId,
      operationType: operation.type,
      status: operation.status,
      phase: operation.phase,
      createdAt: operation.createdAt,
      updatedAt: operation.updatedAt,
      elapsedMs: Date.now() - operation.createdAtMs,
      nextPollAfterMs: operation.nextPollAfterMs || 0,
      input: operation.input,
      attempts: operation.attempts || [],
      lastStep: operationLastStep(operation),
      apiActions,
      phaseTimeline: operationPhaseTimeline(operation),
      evidence: {
        ...(operation.evidence || {}),
        ...(operation.type === "deploy" && session.evidence?.deployment ? { deployment: session.evidence.deployment } : {}),
        ...(operation.type === "verify" && session.evidence?.runtime ? { runtime: session.evidence.runtime } : {}),
      },
      agent: workflowAgentView(session),
      ready: operation.evidence?.ready || null,
      nextActions: operationNextActions(operation.type, operation),
      state: workflowState(session),
      allowedActions: workflowAllowedActions(session),
    };
  }

  function createWorkflowOperation(session, type, input, phase) {
    const operations = workflowOperations(session);
    const now = new Date().toISOString();
    const operation = {
      operationId: newOperationId(type),
      type,
      status: "running",
      phase,
      input,
      idempotencyKeys: {},
      evidence: {},
      apiActions: [],
      attempts: [],
      nextPollAfterMs: 0,
      createdAt: now,
      updatedAt: now,
      createdAtMs: Date.now(),
    };
    operations[operation.operationId] = operation;
    session.activeOperations[type] = operation.operationId;
    recordWorkflowAttempt(session, `${type}.start`, true, { operationId: operation.operationId, phase });
    persistWorkflowSessions();
    return operation;
  }

  function recordOperationApiAction(operation, action) {
    const entry = {
      at: new Date().toISOString(),
      phase: operation.phase,
      action: action.action,
      method: action.method,
      target: action.target,
      result: action.result,
      evidenceKey: action.evidenceKey,
    };
    operation.apiActions = operation.apiActions || [];
    operation.apiActions.push(entry);
    return entry;
  }

  function workflowOperation(session, type, operationId = "") {
    const operations = workflowOperations(session);
    const effectiveOperationId = operationId || session.activeOperations?.[type];
    const operation = operations[effectiveOperationId];
    if (!operation || operation.type !== type) throw new Error(`WORKFLOW_OPERATION_NOT_FOUND: ${type}/${effectiveOperationId || "<active>"}`);
    return operation;
  }

  function recordOperationStep(operation, ok, details = {}) {
    const now = new Date().toISOString();
    const entry = {
      id: `${operation.type}-step-${operation.attempts.length + 1}`,
      phase: operation.phase,
      ok,
      at: now,
      ...details,
    };
    operation.attempts.push(entry);
    operation.updatedAt = now;
    return entry;
  }

  function failOperation(session, operation, code, evidence) {
    operation.status = "failed";
    operation.phase = "failed";
    operation.evidence = { ...(operation.evidence || {}), error: evidence?.error || evidence, code };
    operation.nextPollAfterMs = 0;
    recordOperationStep(operation, false, { code, evidence });
    if (operation.type === "deploy") {
      session.evidence.deployment = { ok: false, code, evidence };
      recordWorkflowAttempt(session, "deploy", false, { code, evidence, operationId: operation.operationId });
    } else if (operation.type === "verify") {
      session.evidence.runtime = { ok: false, code, evidence };
      recordWorkflowAttempt(session, "verify", false, { code, evidence, operationId: operation.operationId });
    }
    writeWorkflowTrace(session);
    persistWorkflowSessions();
    return operationPayload(session, operation);
  }

  function sleep(ms) {
    return new Promise(resolveSleep => setTimeout(resolveSleep, ms));
  }

  function evidenceClaim(evidence, id) {
    return evidence?.report?.checklist?.find(claim => claim.id === id) || null;
  }

  function runtimeFlowObserved(evidence) {
    const requests = evidenceClaim(evidence, "requests.handled");
    const flow = evidenceClaim(evidence, "flow.order");
    const expected = evidence?.flow?.expected || [];
    return requests?.status === "pass" && (!expected.length || flow?.status === "pass");
  }

  function runtimeQueuesDrained(evidence) {
    const queues = evidenceClaim(evidence, "queues.drained");
    return queues?.status === "pass";
  }

  function runtimeLifecycleSettled(evidence) {
    const context = evidence?.lifecycle?.raw?.envelope?.data?.context || {};
    const state = context.state || context.swarmStatus || evidence?.report?.lifecycleState;
    return ["READY", "STOPPED"].includes(String(state || "").toUpperCase());
  }

  function runtimeProofResult(evidence, proofMode = "accept-partial") {
    const verdict = evidence?.report?.verdict || "fail";
    const claims = new Map((evidence?.report?.checklist || []).map(claim => [claim.id, claim]));
    const strictBlocking = [
      claims.get("queues.drained"),
      claims.get("requests.handled"),
      claims.get("flow.order"),
      claims.get("auth.flow"),
      claims.get("payloads.valid"),
      claims.get("payload.trace"),
      claims.get("tap.flow"),
    ].filter(Boolean).filter(claim => {
      if (claim.id === "payloads.valid") return ["fail", "partial"].includes(claim.status);
      if (claim.id === "payload.trace") return !["pass", "not-applicable"].includes(claim.status);
      if (claim.id === "tap.flow") return !["pass", "not-applicable"].includes(claim.status);
      return !["pass", "not-applicable"].includes(claim.status);
    });
    const strict = proofMode === "strict";
    const ok = strict
      ? verdict !== "fail" && strictBlocking.length === 0
      : verdict === "pass" || verdict === "partial";
    return {
      ok,
      code: ok
        ? "WORKFLOW_RUNTIME_VERIFIED"
        : verdict === "partial" || strictBlocking.length
          ? "WORKFLOW_RUNTIME_PARTIAL_PROOF"
          : "WORKFLOW_RUNTIME_EVIDENCE_INCOMPLETE",
      proofMode,
      verdict,
      strictBlockingClaims: strict ? strictBlocking.map(claim => ({ id: claim.id, status: claim.status })) : [],
    };
  }

  async function workflowOpenTap(swarmId, ttlSeconds = 120) {
    return await httpJson("/api/debug/taps", {
      method: "POST",
      body: { swarmId, role: "postprocessor", direction: "IN", ioName: "in", maxItems: 1, ttlSeconds },
    });
  }

  async function collectRuntimeEvidenceUntil({ swarmId, includeTapSample, scenarioId, timeoutSec, ready }) {
    const startedAt = Date.now();
    const timeoutMs = Math.max(0, Math.min(Number(timeoutSec) || 0, 120)) * 1000;
    const deadline = startedAt + timeoutMs;
    let attempts = 0;
    let evidence = null;
    while (true) {
      attempts += 1;
      evidence = await buildEvidenceSummary({ swarmId, includeTapSample, scenarioId });
      if (ready(evidence) || Date.now() >= deadline) {
        return {
          ready: ready(evidence),
          attempts,
          elapsedMs: Date.now() - startedAt,
          timeoutSec: timeoutMs / 1000,
          evidence,
        };
      }
      await sleep(Math.min(2000, Math.max(250, deadline - Date.now())));
    }
  }

  async function advanceDeployOperation(session, operation) {
    ensureWorkflowGenerated(session);
    ensureWorkflowValidatedBeforeRuntime(session);
    if (operation.status !== "running") return operationPayload(session, operation);
    const effectiveSwarmId = operation.input.swarmId || session.swarmId || `${session.generated.bundleId}-swarm`;
    try {
      if (operation.phase === "upload") {
        const deploy = await scenarioManagerUploadBundle(session.generated.bundleId, { replaceExisting: true });
        operation.evidence.deploy = deploy;
        const apiAction = recordOperationApiAction(operation, {
          action: "scenario-manager.upload-bundle",
          method: "POST",
          target: `scenario-manager:${session.generated.bundleId}`,
          result: deploy?.uploaded ? "uploaded" : "attempted",
          evidenceKey: "deploy",
        });
        operation.nextPollAfterMs = 0;
        recordOperationStep(operation, true, { code: "WORKFLOW_DEPLOY_UPLOAD_COMPLETE", apiActions: [apiAction] });
        operation.phase = "mock-config";
      } else if (operation.phase === "mock-config") {
        operation.evidence.mockConfig = await loadBundleMockConfig(session.generated.bundleId);
        const apiAction = recordOperationApiAction(operation, {
          action: "mock.load-config",
          method: "POST",
          target: `mock-config:${session.generated.bundleId}`,
          result: operation.evidence.mockConfig?.loaded ? "loaded" : "attempted",
          evidenceKey: "mockConfig",
        });
        operation.nextPollAfterMs = 0;
        recordOperationStep(operation, true, { code: "WORKFLOW_DEPLOY_MOCK_CONFIG_COMPLETE", apiActions: [apiAction] });
        operation.phase = "create";
      } else if (operation.phase === "create") {
        const target = session.generated.target;
        const createBody = {
          templateId: session.generated.bundleId,
          idempotencyKey: operation.idempotencyKeys.create || idempotencyKey(),
        };
        operation.idempotencyKeys.create = createBody.idempotencyKey;
        if (operation.input.sutId || target?.id) createBody.sutId = operation.input.sutId || target.id;
        if (operation.input.variablesProfileId) createBody.variablesProfileId = operation.input.variablesProfileId;
        operation.evidence.create = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}/create`, { method: "POST", body: createBody });
        const apiAction = recordOperationApiAction(operation, {
          action: "orchestrator.swarm-create",
          method: "POST",
          target: `/api/swarms/${encodeURIComponent(effectiveSwarmId)}/create`,
          result: "requested",
          evidenceKey: "create",
        });
        session.swarmId = effectiveSwarmId;
        operation.nextPollAfterMs = 4000;
        recordOperationStep(operation, true, { code: "WORKFLOW_DEPLOY_CREATE_REQUESTED", apiActions: [apiAction] });
        operation.phase = "wait-ready";
      } else if (operation.phase === "wait-ready") {
        const status = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}`);
        const ctx = status?.envelope?.data?.context;
        const { desired, healthy } = ctx?.totals || {};
        const ready = Boolean(desired > 0 && healthy >= desired && ctx?.swarmStatus === "READY");
        operation.evidence.ready = { ready, status };
        const apiAction = recordOperationApiAction(operation, {
          action: "orchestrator.swarm-status",
          method: "GET",
          target: `/api/swarms/${encodeURIComponent(effectiveSwarmId)}`,
          result: ready ? "ready" : "waiting",
          evidenceKey: "ready",
        });
        recordOperationStep(operation, true, { code: ready ? "WORKFLOW_DEPLOY_READY" : "WORKFLOW_DEPLOY_WAITING_READY", ready, apiActions: [apiAction] });
        if (!ready) {
          operation.nextPollAfterMs = 4000;
        } else {
          operation.phase = "start";
          operation.nextPollAfterMs = 0;
        }
      } else if (operation.phase === "start") {
        const startBody = { idempotencyKey: operation.idempotencyKeys.start || idempotencyKey() };
        operation.idempotencyKeys.start = startBody.idempotencyKey;
        operation.evidence.start = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}/start`, { method: "POST", body: startBody });
        const apiAction = recordOperationApiAction(operation, {
          action: "orchestrator.swarm-start",
          method: "POST",
          target: `/api/swarms/${encodeURIComponent(effectiveSwarmId)}/start`,
          result: "requested",
          evidenceKey: "start",
        });
        recordOperationStep(operation, true, { code: "WORKFLOW_DEPLOY_STARTED", apiActions: [apiAction] });
        operation.status = "succeeded";
        operation.phase = "complete";
        operation.nextPollAfterMs = 0;
        const evidence = {
          swarmId: effectiveSwarmId,
          deploy: operation.evidence.deploy,
          mockConfig: operation.evidence.mockConfig,
          create: operation.evidence.create,
          ready: operation.evidence.ready,
          start: operation.evidence.start,
          operationId: operation.operationId,
        };
        session.evidence.deployment = { ok: true, code: "WORKFLOW_DEPLOYED", evidence };
        recordWorkflowAttempt(session, "deploy", true, { code: "WORKFLOW_DEPLOYED", evidence, operationId: operation.operationId });
        writeWorkflowTrace(session);
      } else {
        throw new Error(`WORKFLOW_OPERATION_PHASE_INVALID: deploy/${operation.phase}`);
      }
      persistWorkflowSessions();
      return operationPayload(session, operation);
    } catch (err) {
      const evidence = {
        swarmId: effectiveSwarmId,
        error: err.message,
        phase: operation.phase,
        failureKind: workflowEvidenceIsAuthFailure(err) ? "environment-auth" : "runtime-api",
      };
      return failOperation(session, operation, workflowEvidenceIsAuthFailure(err) ? "WORKFLOW_ENV_AUTH_FAILED" : "WORKFLOW_DEPLOY_FAILED", evidence);
    }
  }

  async function advanceVerifyOperation(session, operation) {
    if (operation.status !== "running") return operationPayload(session, operation);
    const effectiveSwarmId = operation.input.swarmId || session.swarmId;
    if (!effectiveSwarmId) {
      return failOperation(session, operation, "WORKFLOW_RUNTIME_NOT_STARTED", { error: "No swarmId is available. Call workflow.deploy or pass swarmId." });
    }
    try {
      if (operation.phase === "observe") {
        const evidence = await buildEvidenceSummary({
          swarmId: effectiveSwarmId,
          includeTapSample: false,
          scenarioId: session.generated?.bundleId,
        });
        operation.evidence.observation = {
          ready: runtimeFlowObserved(evidence),
          evidence,
        };
        const apiAction = recordOperationApiAction(operation, {
          action: "evidence.summary",
          method: "GET",
          target: `evidence-summary:${effectiveSwarmId}`,
          result: operation.evidence.observation.ready ? "observed" : "waiting",
          evidenceKey: "observation",
        });
        recordOperationStep(operation, true, {
          code: operation.evidence.observation.ready ? "WORKFLOW_VERIFY_OBSERVED" : "WORKFLOW_VERIFY_WAITING_OBSERVATION",
          apiActions: [apiAction],
        });
        if (operation.evidence.observation.ready) {
          if (operation.input.stopAfterObservation) operation.phase = "stop";
          else {
            operation.status = "succeeded";
            operation.phase = "complete";
            const finalEvidence = operation.input.includeTapSample
              ? await buildEvidenceSummary({
                  swarmId: effectiveSwarmId,
                  includeTapSample: true,
                  scenarioId: session.generated?.bundleId,
                  preArmedTap: operation.evidence.preArmedTap && !operation.evidence.preArmedTap.error ? operation.evidence.preArmedTap : undefined,
                })
              : evidence;
            session.evidence.runtime = {
              ...runtimeProofResult(finalEvidence, operation.input.proofMode),
              evidence: { ...finalEvidence, operationId: operation.operationId },
            };
            recordWorkflowAttempt(session, "verify", session.evidence.runtime.ok, { code: session.evidence.runtime.code, evidence: session.evidence.runtime.evidence, operationId: operation.operationId });
            writeWorkflowTrace(session);
          }
        }
        operation.nextPollAfterMs = operation.phase === "observe" ? 2000 : 0;
      } else if (operation.phase === "stop") {
        const stopBody = { idempotencyKey: operation.idempotencyKeys.stop || idempotencyKey() };
        operation.idempotencyKeys.stop = stopBody.idempotencyKey;
        try {
          operation.evidence.stop = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}/stop`, {
            method: "POST",
            body: stopBody,
          });
        } catch (err) {
          operation.evidence.stop = { error: err.message };
        }
        const apiAction = recordOperationApiAction(operation, {
          action: "orchestrator.swarm-stop",
          method: "POST",
          target: `/api/swarms/${encodeURIComponent(effectiveSwarmId)}/stop`,
          result: operation.evidence.stop?.error ? "failed" : "requested",
          evidenceKey: "stop",
        });
        operation.nextPollAfterMs = 2000;
        recordOperationStep(operation, true, { code: "WORKFLOW_VERIFY_STOP_REQUESTED", apiActions: [apiAction] });
        operation.phase = "settle";
      } else if (operation.phase === "settle") {
        const evidence = await buildEvidenceSummary({
          swarmId: effectiveSwarmId,
          includeTapSample: false,
          scenarioId: session.generated?.bundleId,
        });
        const settled = runtimeFlowObserved(evidence) && runtimeQueuesDrained(evidence) && runtimeLifecycleSettled(evidence);
        operation.evidence.settle = { ready: settled, evidence };
        const apiAction = recordOperationApiAction(operation, {
          action: "evidence.summary",
          method: "GET",
          target: `evidence-summary:${effectiveSwarmId}`,
          result: settled ? "settled" : "waiting",
          evidenceKey: "settle",
        });
        recordOperationStep(operation, true, { code: settled ? "WORKFLOW_VERIFY_SETTLED" : "WORKFLOW_VERIFY_WAITING_SETTLE", ready: settled, apiActions: [apiAction] });
        if (settled) {
          operation.status = "succeeded";
          operation.phase = "complete";
          operation.nextPollAfterMs = 0;
          const finalEvidence = operation.input.includeTapSample
            ? await buildEvidenceSummary({
                swarmId: effectiveSwarmId,
                includeTapSample: true,
                scenarioId: session.generated?.bundleId,
                preArmedTap: operation.evidence.preArmedTap && !operation.evidence.preArmedTap.error ? operation.evidence.preArmedTap : undefined,
              })
            : evidence;
          const runtimeEvidence = {
            ...finalEvidence,
            operationId: operation.operationId,
            workflowRuntime: {
              observation: {
                ready: operation.evidence.observation?.ready || false,
                attempts: operation.attempts.filter(attempt => attempt.phase === "observe").length,
              },
              stopAfterObservation: operation.input.stopAfterObservation,
              stop: operation.evidence.stop || null,
              settle: {
                ready: settled,
                attempts: operation.attempts.filter(attempt => attempt.phase === "settle").length,
              },
            },
          };
          session.evidence.runtime = {
            ...runtimeProofResult(runtimeEvidence, operation.input.proofMode),
            evidence: runtimeEvidence,
          };
          recordWorkflowAttempt(session, "verify", session.evidence.runtime.ok, { code: session.evidence.runtime.code, evidence: runtimeEvidence, operationId: operation.operationId });
          writeWorkflowTrace(session);
        } else {
          operation.nextPollAfterMs = 2000;
        }
      } else {
        throw new Error(`WORKFLOW_OPERATION_PHASE_INVALID: verify/${operation.phase}`);
      }
      persistWorkflowSessions();
      return operationPayload(session, operation);
    } catch (err) {
      return failOperation(session, operation, "WORKFLOW_VERIFY_FAILED", { swarmId: effectiveSwarmId, error: err.message, phase: operation.phase });
    }
  }

  function ensureWorkflowPlanComplete(session) {
    const missing = workflowMissingFields(session);
    if (missing.length) {
      throw new Error(`WORKFLOW_PLAN_INCOMPLETE: ask/answer required questions before continuing: ${missing.join(", ")}`);
    }
  }

  function ensureWorkflowValidationPassed(session) {
    const errors = workflowValidationErrors(session);
    if (errors.length) {
      throw new Error(`WORKFLOW_ANSWER_VALIDATION_FAILED: ${errors.map(issue => `${issue.field} ${issue.code}`).join(", ")}`);
    }
  }

  function ensureWorkflowProvenanceComplete(session, beforeAction = "workflow.generate") {
    const gaps = workflowProvenanceGaps(session, beforeAction);
    if (gaps.length) {
      throw new Error(`WORKFLOW_PROVENANCE_INCOMPLETE: ${gaps.map(gap => `${gap.field} (${gap.reason})`).join(", ")}`);
    }
  }

  function ensureWorkflowEvidenceRequirements(session, beforeAction) {
    const missing = workflowEvidenceRequirementStatuses(session)
      .filter(requirement => sameToolAction(requirement.requiredBefore, beforeAction) && requirement.status !== "satisfied");
    if (missing.length) {
      throw new Error(`WORKFLOW_EVIDENCE_REQUIREMENTS_INCOMPLETE: ${missing.map(requirement => requirement.id).join(", ")}`);
    }
  }

  function ensureWorkflowRoleChecksComplete(session, beforeAction = "workflow.generate") {
    const gaps = workflowRoleCheckGaps(session, beforeAction);
    if (gaps.length) {
      throw new Error(`WORKFLOW_ROLE_CHECKS_INCOMPLETE: ${gaps.map(gap => `${gap.stageId}/${gap.roleId}/${gap.status}`).join(", ")}`);
    }
  }

  function ensureWorkflowCanGenerate(session) {
    if (session.mode === "modify") throw new Error("WORKFLOW_MODIFY_MODE_NO_GENERATE: modify workflows patch the selected bundle instead of generating a new one");
    ensureWorkflowPlanComplete(session);
    ensureWorkflowValidationPassed(session);
    ensureWorkflowProvenanceComplete(session, "workflow.generate");
    ensureWorkflowEvidenceRequirements(session, "workflow.generate");
    ensureWorkflowRoleChecksComplete(session, "workflow.generate");
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

  function ensureWorkflowValidatedBeforeRuntime(session) {
    if (!workflowScenarioManagerValidation(session)?.ok) {
      throw new Error("WORKFLOW_VALIDATION_REQUIRED: call workflow.validate and fix failures before runtime deployment");
    }
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

  function workflowFlattenFields(value, prefix = "plan") {
    if (!value || typeof value !== "object" || Array.isArray(value)) return [];
    return Object.entries(value).flatMap(([key, child]) => {
      const field = `${prefix}.${key}`;
      if (child && typeof child === "object" && !Array.isArray(child)) {
        return [field, ...workflowFlattenFields(child, field)];
      }
      return [field];
    }).sort();
  }

  function workflowRedactValue(value) {
    if (Array.isArray(value)) return value.map(item => workflowRedactValue(item));
    if (!value || typeof value !== "object") return value;
    const redacted = {};
    for (const [key, child] of Object.entries(value)) {
      if (/secret|token|password/i.test(key) && typeof child === "string" && child.trim()) {
        redacted[key] = "[redacted]";
      } else {
        redacted[key] = workflowRedactValue(child);
      }
    }
    return redacted;
  }

  function workflowTrace(session) {
    const nextQuestions = workflowNextQuestions(session);
    const blockers = workflowBlockers(session, nextQuestions);
    return {
      version: 1,
      writtenAt: new Date().toISOString(),
      workflowId: session.workflowId,
      workflowType: session.workflowType,
      mode: session.mode || "create",
      state: workflowState(session),
      intent: session.source.instructions || null,
      source: {
        type: session.source.type,
        path: session.source.path,
        sha256: session.source.sha256,
        bytes: session.source.bytes,
      },
      example: session.example || null,
      plan: workflowRedactValue(session.plan || {}),
      answeredFields: workflowFlattenFields(session.plan || {}),
      provenanceFields: Object.keys(session.provenance || {}).sort(),
      nextQuestions,
      questionGraph: workflowQuestionGraph(session, nextQuestions),
      blockers,
      unresolvableBlockers: workflowUnresolvableBlockers(session, blockers),
      evidenceContract: workflowEvidenceContract(session),
      claimMatrix: workflowClaimMatrix(session),
      agent: workflowAgentView(session),
      generatedFiles: session.generated?.filesCreated || [],
      evidenceGaps: workflowEvidenceGaps(session),
      activeOperations: session.activeOperations || {},
      operations: Object.fromEntries(Object.entries(session.operations || {}).map(([operationId, operation]) => [operationId, {
        operationId,
        type: operation.type,
        status: operation.status,
        phase: operation.phase,
        createdAt: operation.createdAt,
        updatedAt: operation.updatedAt,
        lastStep: operationLastStep(operation),
        apiActions: operation.apiActions || [],
        phaseTimeline: operationPhaseTimeline(operation),
        attempts: (operation.attempts || []).map(attempt => ({ id: attempt.id, phase: attempt.phase, ok: attempt.ok, at: attempt.at, code: attempt.code || null })),
      }])),
      stuckState: workflowStuckState(session),
      history: session.history.map(entry => ({
        id: entry.id,
        action: entry.action,
        ok: entry.ok,
        at: entry.at,
        code: entry.code || null,
        changedFiles: entry.changedFiles || entry.filesChanged || [],
        artifactFingerprint: entry.artifactFingerprint || null,
      })),
    };
  }

  function writeWorkflowTrace(session) {
    if (!session.generated?.bundleId) return null;
    const target = workflowPatchPath(session, WORKFLOW_TRACE_FILE);
    writeFileSync(target, JSON.stringify(workflowTrace(session), null, 2), "utf8");
    return WORKFLOW_TRACE_FILE;
  }

  function markdownCell(value) {
    return String(value ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ");
  }

  function operationReportLines(operation) {
    const lastStep = operationLastStep(operation);
    const timeline = operationPhaseTimeline(operation);
    const apiActions = operation.apiActions || [];
    return [
      `### ${operation.operationId}`,
      "",
      `- Type: ${operation.type}`,
      `- Status: ${operation.status}`,
      `- Current phase: ${operation.phase}`,
      `- Last step: ${lastStep ? `${lastStep.phase} ${lastStep.code || ""}`.trim() : "none"}`,
      "",
      "#### Phase Timeline",
      "",
      "| Phase | Status | Attempts | Last Code | Last Seen |",
      "|---|---|---:|---|---|",
      ...timeline.map(phase => `| ${markdownCell(phase.phase)} | ${markdownCell(phase.status)} | ${phase.attempts} | ${markdownCell(phase.lastCode || "")} | ${markdownCell(phase.lastSeenAt)} |`),
      "",
      "#### API Actions",
      "",
      ...(apiActions.length
        ? [
            "| Phase | Action | Method | Target | Result | Evidence |",
            "|---|---|---|---|---|---|",
            ...apiActions.map(action => `| ${markdownCell(action.phase)} | ${markdownCell(action.action)} | ${markdownCell(action.method)} | ${markdownCell(action.target)} | ${markdownCell(action.result)} | ${markdownCell(action.evidenceKey)} |`),
          ]
        : ["- none"]),
    ];
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
      "## Agent Handoff",
      "",
      `- Verdict: ${status.agent.verdict}`,
      `- Phase: ${status.agent.phase}`,
      `- Summary: ${status.agent.summary}`,
      `- Next action: ${status.agent.nextAction.tool || "none"} (${status.agent.nextAction.reason})`,
      `- Diagnosis: ${status.agent.diagnosis.code || "none"} - ${status.agent.diagnosis.message}`,
      "",
      "## Claim Matrix",
      "",
      "| Claim | Status | Required | Gap |",
      "|---|---|---|---|",
      ...status.claimMatrix.map(claim => `| ${claim.id} | ${claim.status} | ${claim.required ? "yes" : "no"} | ${claim.gap || ""} |`),
      "",
      "## Evidence Contract",
      "",
      "| Claim | Stage | Required | Status | Proof Tool |",
      "|---|---|---|---|---|",
      ...status.evidenceContract.map(claim => `| ${claim.id} | ${claim.stage} | ${claim.required ? "yes" : "no"} | ${claim.status} | ${claim.proofTool || ""} |`),
      "",
      "## Answer Validation",
      "",
      ...(status.validationIssues.length ? status.validationIssues.map(issue => `- ${issue.severity} ${issue.code} ${issue.field}: ${issue.message}`) : ["- none"]),
      "",
      "## Role Review",
      "",
      ...status.reviewStages.flatMap(stage => [
        `- ${stage.label}: ${stage.status}`,
        ...stage.requiredRoles.map(role => `  - ${role.roleId}: ${role.check?.outcome || role.status}${role.check?.summary ? ` - ${role.check.summary}` : ""}`),
      ]),
      "",
      "## Evidence Requirements",
      "",
      ...status.evidenceRequirements.map(requirement => `- ${requirement.id}: ${requirement.status}`),
      "",
      "## Evidence Gaps",
      "",
      ...(status.evidenceGaps.length ? status.evidenceGaps.map(gap => `- ${gap.id}: ${gap.status}`) : ["- none"]),
      "",
      "## Lifecycle Operations",
      "",
      ...(Object.values(session.operations || {}).length
        ? Object.values(session.operations || {}).flatMap(operationReportLines)
        : ["- none"]),
      "",
      "## Attempt History",
      "",
      ...session.history.map(entry => `- ${entry.at} ${entry.action}: ${entry.ok ? "ok" : "failed"}${entry.code ? ` (${entry.code})` : ""}`),
    ];
    return lines.join("\n");
  }

  function workflowSessionSummary(session, includeQuestions = true) {
    const missing = workflowMissingFields(session);
    const profile = workflowProfile(session.profileId);
    const nextQuestions = includeQuestions ? workflowNextQuestions(session, missing) : [];
    const blockers = workflowBlockers(session, nextQuestions);
    return {
      workflowId: session.workflowId,
      workflowType: session.workflowType,
      mode: session.mode || "create",
      state: workflowState(session),
      profile: { id: profile.id, label: profile.label, authority: profile.authority },
      activeRole: workflowRolePayload(workflowActiveRoleId(session)),
      roleChecklist: workflowRoleChecklist(session),
      reviewStages: workflowReviewStageStatuses(session, { exposed: true }),
      source: {
        type: session.source.type,
        path: session.source.path,
        sha256: session.source.sha256,
        bytes: session.source.bytes,
      },
      example: session.example || null,
      bundle: session.bundle ? { id: session.bundle.id, path: session.bundle.path } : null,
      generated: session.generated ? { bundleId: session.generated.bundleId, path: session.generated.path } : null,
      missing,
      nextQuestions,
      questionGraph: workflowQuestionGraph(session, nextQuestions),
      validationIssues: workflowValidationIssues(session),
      provenanceGaps: workflowProvenanceGaps(session),
      allowedActions: workflowAllowedActions(session),
      evidenceRequirements: workflowEvidenceRequirementStatuses(session, { exposed: true }),
      evidenceContract: workflowEvidenceContract(session),
      evidenceGaps: workflowEvidenceGaps(session),
      claimMatrix: workflowClaimMatrix(session),
      blockers,
      unresolvableBlockers: workflowUnresolvableBlockers(session, blockers),
      stuckState: workflowStuckState(session),
      activeOperations: session.activeOperations || {},
      operations: Object.fromEntries(Object.entries(session.operations || {}).map(([operationId, operation]) => [operationId, {
        operationId,
        type: operation.type,
        status: operation.status,
        phase: operation.phase,
        createdAt: operation.createdAt,
        updatedAt: operation.updatedAt,
        nextPollAfterMs: operation.nextPollAfterMs || 0,
        lastStep: operationLastStep(operation),
        apiActions: operation.apiActions || [],
        phaseTimeline: operationPhaseTimeline(operation),
      }])),
      remediation: workflowRemediation(session),
      agent: workflowAgentView(session),
      persistence: workflowPersistenceStatus(),
      historyCount: session.history.length,
      createdAt: session.createdAt,
      updatedAt: session.updatedAt || session.createdAt,
    };
  }

  function workflowConfigPayload() {
    const profiles = workflowProfilesPayload();
    return {
      workflowType: WORKFLOW_TYPE,
      sessionTtlMs: WORKFLOW_SESSION_TTL_MS,
      sourceMaxBytes: WORKFLOW_SOURCE_MAX_BYTES,
      defaultProfileId: profiles.defaultProfileId,
      profileConfig: { source: profiles.source, path: profiles.configPath },
      roles: profiles.roles,
      profiles: profiles.profiles,
      supportedSourceTypes: ["jmeter", "postman", "openapi", "k6", "gatling", "curl", "plain-instructions", "other"],
      supportedModes: [...WORKFLOW_MODES],
      normalizedPlan: {
        version: 1,
        sourceFidelityStatuses: [...SOURCE_FIDELITY_STATUSES],
        endpointMethods: [...WORKFLOW_HTTP_METHODS],
        sourceFidelityRequiredFor: [...SOURCE_BACKED_TYPES, "other-with-source-file"],
      },
      examples: {
        sourceOrder: workflowExampleSources().map(source => ({ id: source.id, authority: source.authority, root: source.root })),
      },
      bundleRoot: resolve(getBundlesDir()),
      allowedSourceRoots: workflowAllowedSourceRoots(),
      runtime: {
        baseUrl: BASE_URL,
        orchestratorBaseUrl: ORCH_URL,
        scenarioManagerBaseUrl: SM_URL,
        rabbitmqManagementBaseUrl: RABBIT_MGMT,
        prometheusBaseUrl: PROM_URL,
      },
      persistence: workflowPersistenceStatus(),
      enrichment: workflowEnrichmentStatus({}),
      pluginBoundary: {
        mayAnswerQuestions: false,
        readOnlyTools: ["workflow.config.get", "workflow.config.validate", "workflow.examples.list", "workflow.examples.get", "workflow.examples.recommend", "workflow.profiles.list", "workflow.profiles.get", "workflow.list", "workflow.status", "workflow.result", "workflow.evidence.render", "workflow.deploy.status", "workflow.verify.status"].map(toolName),
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

  reg("workflow.examples.list", "List read-only workflow example bundles from canonical repo examples first, then the active bundle root.", {
    includeTeamExamples: z.boolean().optional().default(true),
  }, async ({ includeTeamExamples = true }) => {
    return workflowExamples(includeTeamExamples);
  }, workflowReadOnly);

  reg("workflow.examples.get", "Return one read-only example bundle summary and bounded documentation content.", {
    bundleId: z.string(),
    source: z.enum(["repo-examples", "active-bundles-root"]).optional(),
  }, async ({ bundleId, source }) => workflowExampleDetails(bundleId, source), workflowReadOnly);

  reg("workflow.examples.recommend", "Return deterministic keyword/tag matches from the example library without choosing an archetype.", {
    intent: z.string(),
    limit: z.number().optional().default(5),
  }, async ({ intent, limit = 5 }) => ({
    intent,
    recommendations: workflowExampleRecommendations(intent, limit),
    sourceOrder: workflowExampleSources().map(source => ({ id: source.id, authority: source.authority, root: source.root })),
  }), workflowReadOnly);

  reg("workflow.config.get", "Return sanitized workflow defaults and configured roots for plugin/status display.", {}, async () => {
    return workflowConfigPayload();
  }, workflowReadOnly);

  reg("workflow.profiles.list", "List built-in workflow roles and profiles for assistant/plugin display.", {}, async () => {
    return workflowProfilesPayload();
  }, workflowReadOnly);

  reg("workflow.profiles.get", "Return one built-in workflow profile with resolved role details.", {
    profileId: z.string(),
  }, async ({ profileId }) => workflowProfilePayload(workflowProfile(profileId), true), workflowReadOnly);

  reg("workflow.role.check", "Record an explicit agent-provided role review checkpoint for the workflow.", {
    workflowId: z.string(),
    stageId: z.string(),
    roleId: z.string(),
    outcome: z.enum(["pass", "risk-accepted", "fail"]),
    summary: z.string(),
    risks: z.array(z.string()).optional().default([]),
  }, async ({ workflowId, stageId, roleId, outcome, summary, risks = [] }) => {
    const session = workflowSession(workflowId);
    const stage = workflowProfile(session.profileId).reviewStages.find(candidate => candidate.id === stageId);
    if (!stage) throw new Error(`WORKFLOW_REVIEW_STAGE_NOT_FOUND: ${stageId}`);
    if (!stage.roles.includes(roleId)) throw new Error(`WORKFLOW_REVIEW_ROLE_NOT_REQUIRED: ${stageId}/${roleId}`);
    workflowRole(roleId);
    const check = {
      stageId,
      roleId,
      outcome,
      summary,
      risks,
      at: new Date().toISOString(),
      authority: GUIDANCE_AUTHORITY,
    };
    session.roleChecks = { ...(session.roleChecks || {}), [roleCheckKey(stageId, roleId)]: check };
    recordWorkflowAttempt(session, "role.check", outcome !== "fail", { stageId, roleId, outcome });
    persistWorkflowSessions();
    return workflowStatusPayload(session);
  }, workflowMutating);

  reg("workflow.hivemind.enrich", "Write a redacted workflow memory summary to a configured HiveMind MCP endpoint when available.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => {
    const session = workflowSession(workflowId);
    const result = await enrichHiveMind(session);
    return { workflowId, hivemind: result, enrichment: workflowEnrichmentStatus(session) };
  }, workflowRuntime);

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
    profileId: z.string().optional(),
    mode: z.enum(WORKFLOW_MODES).optional().default("create"),
    existingBundleId: z.string().optional(),
    exampleBundleId: z.string().optional(),
    exampleSource: z.enum(["repo-examples", "active-bundles-root"]).optional(),
    workflowType: z.literal(WORKFLOW_TYPE).optional().default(WORKFLOW_TYPE),
  }, async ({ sourceType, sourcePath, instructions, profileId, mode = "create", existingBundleId, exampleBundleId, exampleSource, workflowType = WORKFLOW_TYPE }) => {
    cleanupWorkflowSessions();
    const profile = workflowProfile(profileId);
    if (!sourcePath && !instructions) throw new Error("WORKFLOW_SOURCE_REQUIRED: provide sourcePath or instructions");
    const example = exampleBundleId ? workflowExample(exampleBundleId, exampleSource) : null;
    let existingBundle = null;
    if (mode === "modify") {
      if (!existingBundleId) throw new Error("WORKFLOW_MODIFY_BUNDLE_REQUIRED: provide existingBundleId for modify mode");
      const path = bundleDir(existingBundleId);
      if (!existsSync(path) || !existsSync(resolve(path, "scenario.yaml"))) {
        throw new Error(`WORKFLOW_MODIFY_BUNDLE_NOT_FOUND: ${existingBundleId}`);
      }
      existingBundle = { id: existingBundleId, path };
    }
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
      mode,
      profileId: profile.id,
      source,
      example: example ? {
        bundleId: example.bundleId,
        source: example.source,
        authority: example.authority,
        path: example.path,
        demonstrates: example.demonstrates,
      } : null,
      plan: {},
      answers: {},
      provenance: {},
      roleChecks: {},
      bundle: existingBundle,
      generated: existingBundle ? { bundleId: existingBundle.id, path: existingBundle.path, filesCreated: [], mode: "modify-existing" } : null,
      evidence: {},
      history: [],
      createdAt: new Date().toISOString(),
      createdAtMs: Date.now(),
    };
    recordWorkflowAttempt(session, "start", true, { mode, source: { type: source.type, path: source.path, sha256: source.sha256, bytes: source.bytes }, example: session.example, existingBundle });
    WORKFLOW_SESSIONS.set(workflowId, session);
    persistWorkflowSessions();
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
    provenance: z.object({}).passthrough().optional(),
  }, async ({ workflowId, answers, plan, provenance }) => {
    const session = workflowSession(workflowId);
    if (answers) session.answers = mergeWorkflowPatch(session.answers, answers);
    if (plan) session.plan = mergeWorkflowPatch(session.plan, plan);
    if (provenance) session.provenance = { ...(session.provenance || {}), ...normalizeProvenancePatch(provenance) };
    if (session.mode !== "modify") {
      session.bundle = session.plan?.bundleId ? { id: session.plan.bundleId, path: bundleDir(session.plan.bundleId) } : null;
    }
    recordWorkflowAttempt(session, "update", true, { changed: { answers: Boolean(answers), plan: Boolean(plan), provenance: Boolean(provenance) }, missing: workflowMissingFields(session) });
    persistWorkflowSessions();
    return workflowStatusPayload(session);
  }, workflowMutating);

  reg("workflow.status", "Return workflow state, missing fields, next questions, evidence gaps, allowed actions, and history.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => workflowStatusPayload(workflowSession(workflowId)), workflowReadOnly);

  reg("workflow.result", "Return the compact agent-facing verdict, diagnosis, next action, proof summary, and references for one workflow.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => workflowAgentView(workflowSession(workflowId)), workflowReadOnly);

  reg("workflow.evidence.render", "Render read-only workflow evidence, questions, claims, role checks, lifecycle operations, and evidence gaps in an MCP App widget.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => {
    const session = workflowSession(workflowId);
    const payload = workflowEvidenceRenderPayload(session);
    return {
      structuredContent: payload,
      content: [{
        type: "text",
        text: `Workflow evidence for ${workflowId}: ${payload.state}; ${payload.summary.evidenceGapCount} evidence gap(s), ${payload.summary.nextQuestionCount} question(s).`,
      }],
      _meta: {
        ui: { resourceUri: WORKFLOW_EVIDENCE_WIDGET_URI },
        "openai/outputTemplate": WORKFLOW_EVIDENCE_WIDGET_URI,
        workflowEvidence: payload,
      },
    };
  }, {
    ...workflowReadOnly,
    rawResult: true,
    _meta: {
      ui: { resourceUri: WORKFLOW_EVIDENCE_WIDGET_URI },
      "openai/outputTemplate": WORKFLOW_EVIDENCE_WIDGET_URI,
      "openai/toolInvocation/invoking": "Rendering evidence",
      "openai/toolInvocation/invoked": "Evidence ready",
    },
  });

  reg("workflow.preview", "Preview generated workflow bundle artifacts without writing files.", {
    workflowId: z.string(),
  }, async ({ workflowId }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowPlanComplete(session);
    ensureWorkflowValidationPassed(session);
    if (session.mode === "modify") {
      return {
        workflowId,
        mode: "modify",
        bundle: session.bundle,
        patchScope: workflowPatchScope(session),
        changeSummary: session.plan.changeSummary,
        sideEffect: "no-file-write",
      };
    }
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
    ensureWorkflowCanGenerate(session);
    const generated = await writeWizardBundle(workflowWizardSession(session));
    const structural = await runBundleCheck(generated.bundleId);
    if (!generated.filesCreated.includes(WORKFLOW_TRACE_FILE)) generated.filesCreated.push(WORKFLOW_TRACE_FILE);
    session.generated = generated;
    session.bundle = { id: generated.bundleId, path: generated.path };
    const ok = structural.ok;
    session.evidence.generation = { ok, generated, structural };
    recordWorkflowAttempt(session, "generate", ok, { bundleId: generated.bundleId, filesChanged: generated.filesCreated, code: ok ? "WORKFLOW_GENERATED" : "WORKFLOW_GENERATION_STRUCTURAL_FAILURE" });
    writeWorkflowTrace(session);
    persistWorkflowSessions();
    return { ok, workflowId, generated, structural, state: workflowState(session), activeRole: workflowRolePayload(workflowActiveRoleId(session)), allowedActions: workflowAllowedActions(session) };
  }, workflowMutating);

  reg("workflow.validate", "Validate the generated workflow bundle through Scenario Manager and record structured evidence.", {
    workflowId: z.string(),
    validator: z.enum(["scenario-manager-dry-run"]).optional().default("scenario-manager-dry-run"),
  }, async ({ workflowId, validator = "scenario-manager-dry-run" }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    if (session.mode === "modify") {
      ensureWorkflowPlanComplete(session);
      ensureWorkflowValidationPassed(session);
    }
    let evidence;
    let ok = false;
    let code = "WORKFLOW_VALIDATION_FAILED";
    try {
      const scenarioManager = await scenarioManagerDryRunValidateBundle(session.generated.bundleId);
      ok = scenarioManager?.ok === true;
      code = ok ? "WORKFLOW_VALIDATED" : "WORKFLOW_VALIDATION_FAILED";
      evidence = {
        validator,
        validationLevel: "scenario-manager",
        authoritative: true,
        scenarioManager,
      };
    } catch (e) {
      const authFailure = workflowEvidenceIsAuthFailure(e);
      evidence = {
        validator,
        error: e.message,
        validationLevel: "scenario-manager",
        authoritative: false,
        failureKind: authFailure ? "environment-auth" : "scenario-manager-unavailable",
        note: authFailure
          ? "Scenario Manager validation failed because PocketHive API auth was rejected."
          : "Scenario Manager validation could not complete against the configured environment.",
      };
      code = authFailure ? "WORKFLOW_ENV_AUTH_FAILED" : "WORKFLOW_EXTERNAL_VALIDATION_FAILED";
    }
    const authoritative = Boolean(evidence?.authoritative);
    const validationLevel = evidence?.validationLevel || "scenario-manager";
    session.evidence.validation = { ok, code, authoritative, validationLevel, evidence };
    if (validationLevel === "scenario-manager") {
      session.evidence.scenarioManagerValidation = { ok, code, authoritative, validationLevel, evidence };
    }
    recordWorkflowAttempt(session, "validate", ok, { code, evidence });
    writeWorkflowTrace(session);
    persistWorkflowSessions();
    return {
      ok,
      code,
      failureCode: ok ? null : code,
      authoritative,
      validationLevel,
      workflowId,
      evidence,
      activeRole: workflowRolePayload(workflowActiveRoleId(session)),
      suggestedNextActions: ok ? [] : workflowSuggestedNextActions(code),
      patchScope: ok || !workflowFailureNeedsPatch(code) ? [] : workflowPatchScope(session),
      state: workflowState(session),
      allowedActions: workflowAllowedActions(session),
    };
  }, workflowMutating);

  reg("workflow.deploy.start", "Create a resumable deploy lifecycle operation without waiting for readiness.", {
    workflowId: z.string(),
    swarmId: SWARM_ID_ARG.optional(),
    sutId: z.string().optional(),
    variablesProfileId: z.string().optional().default("default"),
  }, async ({ workflowId, swarmId, sutId, variablesProfileId = "default" }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    ensureWorkflowValidatedBeforeRuntime(session);
    const operation = createWorkflowOperation(session, "deploy", {
      swarmId: swarmId || `${session.generated.bundleId}-swarm`,
      sutId,
      variablesProfileId,
    }, "upload");
    return operationPayload(session, operation);
  }, workflowRuntime);

  reg("workflow.deploy.status", "Read a deploy lifecycle operation without advancing it.", {
    workflowId: z.string(),
    operationId: z.string().optional(),
  }, async ({ workflowId, operationId }) => {
    const session = workflowSession(workflowId);
    return operationPayload(session, workflowOperation(session, "deploy", operationId));
  }, workflowReadOnly);

  reg("workflow.deploy.resume", "Advance one deploy lifecycle step for slow startup-safe polling.", {
    workflowId: z.string(),
    operationId: z.string().optional(),
  }, async ({ workflowId, operationId }) => {
    const session = workflowSession(workflowId);
    const operation = workflowOperation(session, "deploy", operationId);
    return await advanceDeployOperation(session, operation);
  }, workflowRuntime);

  reg("workflow.deploy", "Deploy the generated workflow bundle and create/wait/start a swarm through official PocketHive APIs.", {
    workflowId: z.string(),
    swarmId: SWARM_ID_ARG.optional(),
    sutId: z.string().optional(),
    variablesProfileId: z.string().optional(),
    readyTimeoutSec: z.number().optional().default(90),
  }, async ({ workflowId, swarmId, sutId, variablesProfileId = "default", readyTimeoutSec = 90 }) => {
    const session = workflowSession(workflowId);
    ensureWorkflowGenerated(session);
    ensureWorkflowValidatedBeforeRuntime(session);
    const effectiveSwarmId = swarmId || `${session.generated.bundleId}-swarm`;
    let evidence;
    let ok = false;
    let code = "WORKFLOW_DEPLOY_FAILED";
    try {
      const deploy = await scenarioManagerUploadBundle(session.generated.bundleId, { replaceExisting: true });
      const mockConfig = await loadBundleMockConfig(session.generated.bundleId);
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
      evidence = { swarmId: effectiveSwarmId, deploy, mockConfig, create, ready, start };
    } catch (e) {
      const authFailure = workflowEvidenceIsAuthFailure(e);
      code = authFailure ? "WORKFLOW_ENV_AUTH_FAILED" : "WORKFLOW_DEPLOY_FAILED";
      evidence = {
        swarmId: effectiveSwarmId,
        error: e.message,
        failureKind: authFailure ? "environment-auth" : "runtime-api",
      };
    }
    session.swarmId = effectiveSwarmId;
    session.evidence.deployment = { ok, code, evidence };
    recordWorkflowAttempt(session, "deploy", ok, { code, evidence });
    writeWorkflowTrace(session);
    persistWorkflowSessions();
    return {
      ok,
      code,
      failureCode: ok ? null : code,
      workflowId,
      swarmId: effectiveSwarmId,
      evidence,
      activeRole: workflowRolePayload(workflowActiveRoleId(session)),
      suggestedNextActions: ok ? [] : workflowSuggestedNextActions(code),
      patchScope: ok || !workflowFailureNeedsPatch(code) ? [] : workflowPatchScope(session),
      state: workflowState(session),
      allowedActions: workflowAllowedActions(session),
    };
  }, workflowRuntime);

  reg("workflow.verify", "Collect runtime proof for a workflow swarm from existing evidence sources.", {
    workflowId: z.string(),
    swarmId: SWARM_ID_ARG.optional(),
    includeTapSample: z.boolean().optional().default(false),
    proofMode: z.enum(WORKFLOW_PROOF_MODES).optional().default("accept-partial"),
    observationTimeoutSec: z.number().optional().default(30),
    stopAfterObservation: z.boolean().optional().default(true),
    settleTimeoutSec: z.number().optional().default(30),
  }, async ({ workflowId, swarmId, includeTapSample = false, proofMode = "accept-partial", observationTimeoutSec = 30, stopAfterObservation = true, settleTimeoutSec = 30 }) => {
    const session = workflowSession(workflowId);
    const effectiveSwarmId = swarmId || session.swarmId;
    let evidence;
    let ok = false;
    let code = "WORKFLOW_RUNTIME_NOT_STARTED";
    let preArmedTap = null;
    if (!effectiveSwarmId) {
      evidence = { error: "No swarmId is available. Call workflow.deploy or pass swarmId." };
    } else {
      try {
        if (includeTapSample) {
          try {
            preArmedTap = await workflowOpenTap(effectiveSwarmId, observationTimeoutSec + settleTimeoutSec + 60);
          } catch (tapError) {
            preArmedTap = { error: tapError.message };
          }
        }
        const observation = await collectRuntimeEvidenceUntil({
          swarmId: effectiveSwarmId,
          includeTapSample: false,
          scenarioId: session.generated?.bundleId,
          timeoutSec: observationTimeoutSec,
          ready: runtimeFlowObserved,
        });
        evidence = observation.evidence;
        let stop = null;
        let settle = null;
        if (stopAfterObservation && observation.ready) {
          try {
            stop = await httpJson(`/api/swarms/${encodeURIComponent(effectiveSwarmId)}/stop`, {
              method: "POST",
              body: { idempotencyKey: idempotencyKey() },
            });
          } catch (e) {
            stop = { error: e.message };
          }
          settle = await collectRuntimeEvidenceUntil({
            swarmId: effectiveSwarmId,
            includeTapSample: false,
            scenarioId: session.generated?.bundleId,
            timeoutSec: settleTimeoutSec,
            ready: evidence => runtimeFlowObserved(evidence) && runtimeQueuesDrained(evidence) && runtimeLifecycleSettled(evidence),
          });
          evidence = settle.evidence;
        }
        if (includeTapSample) {
          evidence = await buildEvidenceSummary({
            swarmId: effectiveSwarmId,
            includeTapSample: true,
            scenarioId: session.generated?.bundleId,
            preArmedTap: preArmedTap && !preArmedTap.error ? preArmedTap : undefined,
          });
          if (preArmedTap?.error) {
            evidence.missingEvidence = [
              ...(evidence.missingEvidence || []),
              { source: "debug.tap.prearm", reason: preArmedTap.error },
            ];
          }
        }
        evidence.workflowRuntime = {
          observation: {
            ready: observation.ready,
            attempts: observation.attempts,
            elapsedMs: observation.elapsedMs,
            timeoutSec: observation.timeoutSec,
          },
          stopAfterObservation,
          stop,
          settle: settle ? {
            ready: settle.ready,
            attempts: settle.attempts,
            elapsedMs: settle.elapsedMs,
            timeoutSec: settle.timeoutSec,
          } : null,
        };
        const proof = runtimeProofResult(evidence, proofMode);
        ok = proof.ok;
        code = proof.code;
        evidence.proofMode = proofMode;
      } catch (e) {
        code = "WORKFLOW_VERIFY_FAILED";
        evidence = { error: e.message, proofMode };
      }
    }
    session.evidence.runtime = { ok, code, proofMode, evidence };
    recordWorkflowAttempt(session, "verify", ok, { code, evidence });
    writeWorkflowTrace(session);
    persistWorkflowSessions();
    return {
      ok,
      code,
      failureCode: ok ? null : code,
      workflowId,
      swarmId: effectiveSwarmId || null,
      evidence,
      activeRole: workflowRolePayload(workflowActiveRoleId(session)),
      suggestedNextActions: ok ? [] : workflowSuggestedNextActions(code),
      patchScope: ok ? [] : workflowPatchScope(session),
      state: workflowState(session),
      allowedActions: workflowAllowedActions(session),
    };
  }, workflowRuntime);

  reg("workflow.verify.start", "Create a resumable runtime verification operation without blocking for traffic or settlement.", {
    workflowId: z.string(),
    swarmId: SWARM_ID_ARG.optional(),
    includeTapSample: z.boolean().optional().default(false),
    proofMode: z.enum(WORKFLOW_PROOF_MODES).optional().default("accept-partial"),
    stopAfterObservation: z.boolean().optional().default(true),
  }, async ({ workflowId, swarmId, includeTapSample = false, proofMode = "accept-partial", stopAfterObservation = true }) => {
    const session = workflowSession(workflowId);
    const effectiveSwarmId = swarmId || session.swarmId;
    if (!effectiveSwarmId) throw new Error("WORKFLOW_RUNTIME_NOT_STARTED: No swarmId is available. Call workflow.deploy or pass swarmId.");
    const operation = createWorkflowOperation(session, "verify", {
      swarmId: effectiveSwarmId,
      includeTapSample,
      proofMode,
      stopAfterObservation,
    }, "observe");
    if (includeTapSample) {
      try {
        operation.evidence.preArmedTap = await workflowOpenTap(effectiveSwarmId, 120);
      } catch (err) {
        operation.evidence.preArmedTap = { error: err.message };
      }
      persistWorkflowSessions();
    }
    return operationPayload(session, operation);
  }, workflowRuntime);

  reg("workflow.verify.status", "Read a runtime verification operation without advancing it.", {
    workflowId: z.string(),
    operationId: z.string().optional(),
  }, async ({ workflowId, operationId }) => {
    const session = workflowSession(workflowId);
    return operationPayload(session, workflowOperation(session, "verify", operationId));
  }, workflowReadOnly);

  reg("workflow.verify.resume", "Advance one runtime verification lifecycle step for slow-run-safe polling.", {
    workflowId: z.string(),
    operationId: z.string().optional(),
  }, async ({ workflowId, operationId }) => {
    const session = workflowSession(workflowId);
    const operation = workflowOperation(session, "verify", operationId);
    return await advanceVerifyOperation(session, operation);
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
    if (session.mode === "modify") {
      ensureWorkflowPlanComplete(session);
      ensureWorkflowValidationPassed(session);
    }
    const changedFiles = [];
    for (const change of changes) {
      const target = workflowPatchPath(session, change.file);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, change.content, "utf8");
      changedFiles.push(change.file);
    }
    recordWorkflowAttempt(session, "patch", true, { changedFiles });
    writeWorkflowTrace(session);
    persistWorkflowSessions();
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
    session.reported = true;
    session.evidence.report = { ok: true, file };
    const markdown = workflowReportMarkdown(session);
    writeFileSync(target, markdown, "utf8");
    recordWorkflowAttempt(session, "report", true, { file });
    writeWorkflowTrace(session);
    persistWorkflowSessions();
    const report = workflowStatusPayload(session);
    return { ok: true, workflowId, file, report, claimMatrix: report.claimMatrix, markdown };
  }, workflowMutating);
}
