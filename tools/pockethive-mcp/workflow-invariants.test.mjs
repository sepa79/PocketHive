import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";

async function withClient(bundlesRoot, fn, envOverrides = {}) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [START],
    env: {
      ...process.env,
      POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
      ORCHESTRATOR_BASE_URL: `${UNUSED_BASE_URL}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${UNUSED_BASE_URL}/scenario-manager`,
      RABBITMQ_MANAGEMENT_BASE_URL: `${UNUSED_BASE_URL}/rabbitmq/api`,
      POCKETHIVE_AUTH_TOKEN: "",
      POCKETHIVE_AUTH_USERNAME: "",
      BUNDLES_ROOT: bundlesRoot,
      PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
      PH_WORKFLOW_PERSISTENCE: "memory",
      ...envOverrides,
    },
  });
  const client = new Client({ name: "workflow-invariants-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

async function withScenarioManagerValidationClient(bundlesRoot, fn, options = {}) {
  const responses = Array.isArray(options.responses) && options.responses.length
    ? options.responses
    : [{ ok: true, source: "uploaded-zip", scenarioId: "test-bundle", summary: { errors: 0, warnings: 0 }, findings: [] }];
  let validationCalls = 0;
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/scenario-manager/validation/scenario-bundles") {
      validationCalls += 1;
      for await (const _ of req) { /* drain zip upload */ }
      const response = responses[Math.min(validationCalls - 1, responses.length - 1)];
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(typeof response === "function" ? response(validationCalls) : response));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `unhandled ${req.method} ${url.pathname}` }));
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await withClient(bundlesRoot, fn, {
      POCKETHIVE_BASE_URL: baseUrl,
      ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
    });
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
  return validationCalls;
}

async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) throw new Error(result.content?.[0]?.text || `${name} failed`);
  return JSON.parse(result.content[0].text);
}

async function callError(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  assert.equal(result.isError, true, `${name} should fail`);
  return result.content?.[0]?.text || "";
}

function writeSource(root, relativePath = "sources/source.jmx", content = "<jmeterTestPlan/>") {
  const path = resolve(root, relativePath);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content, "utf8");
  return path;
}

function completePlan(bundleId) {
  return {
    bundleId,
    protocol: "REST",
    target: "wiremock-local",
    endpoints: [{ method: "GET", path: "/hello", callId: "hello" }],
    traffic: { ratePerSec: 2, shape: "flat", duration: "30s" },
    dataset: { strategy: "SCHEDULER" },
    mock: { strategy: "wiremock" },
    observability: { goal: "Stakeholders need worker health, queue drain, mock-match, latency, and throughput evidence.", grafanaDashboard: "rtt_overview" },
    successCriteria: { resultRules: false, summary: "HTTP 2xx responses and no unmatched mock requests." },
    sourceFidelity: { status: "complete", convertedFeatures: ["method", "path", "traffic"], unsupportedConstructs: [] },
  };
}

function requiredProvenance(source = "user") {
  return {
    "plan.target": { source, note: "Confirmed target strategy." },
    "plan.dataset.strategy": { source, note: "Confirmed dataset strategy." },
    "plan.auth": { source, note: "Confirmed no auth required." },
    "plan.successCriteria": { source, note: "Confirmed measurable success criteria." },
    "plan.traffic.ratePerSec": { source, note: "Confirmed traffic rate." },
    "plan.traffic.shape": { source, note: "Confirmed traffic shape." },
    "plan.traffic.duration": { source, note: "Confirmed duration." },
    "plan.observability.goal": { source, note: "Confirmed stakeholder evidence goal." },
  };
}

async function completeThreeAmigos(client, workflowId, roles = ["architect", "developer", "tester"]) {
  for (const roleId of roles) {
    await call(client, "workflow_role_check", {
      workflowId,
      stageId: "three-amigos",
      roleId,
      outcome: "pass",
      summary: `${roleId} invariant review passed.`,
      risks: [],
    });
  }
}

function assertNoDeadEnd(status) {
  assert.deepEqual(status.unresolvableBlockers, [], "normal workflow blockers should be resolvable");
  for (const blocker of status.blockers || []) {
    assert.equal(blocker.unresolvable, false, `${blocker.id} should be resolvable`);
    assert.ok(blocker.resolvedBy, `${blocker.id} should declare resolvedBy`);
  }
  for (const question of status.nextQuestions || []) {
    assert.equal(question.resolution?.tool, "workflow_update", `${question.id} should point back to workflow_update`);
    assert.ok(question.blockedAction, `${question.id} should name the blocked action`);
  }
}

test("blocked workflow states always expose resolvable next actions", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-agentic-invariants-"));
  const sourcePath = writeSource(root, "sources/lossy.jmx", "<jmeterTestPlan><JSR223PostProcessor/></jmeterTestPlan>");

  await withScenarioManagerValidationClient(root, async (client) => {
    const cases = [
      {
        name: "missing source fidelity",
        sourceType: "jmeter",
        sourcePath,
        plan: (() => {
          const plan = completePlan("invariant-missing-fidelity");
          delete plan.sourceFidelity;
          return plan;
        })(),
        provenance: requiredProvenance("source-derived"),
        expected: "plan.sourceFidelity.status",
      },
      {
        name: "unsupported source construct needs acceptance",
        sourceType: "jmeter",
        sourcePath,
        plan: {
          ...completePlan("invariant-unsupported-source"),
          sourceFidelity: {
            status: "partial-accepted",
            convertedFeatures: ["method", "path"],
            unsupportedConstructs: ["JMeter JSR223 postprocessor correlation"],
          },
        },
        provenance: requiredProvenance("source-derived"),
        expected: "plan.sourceFidelity.userAcceptedLimitations",
      },
      {
        name: "unsafe public target",
        sourceType: "plain-instructions",
        instructions: "Create a Google live smoke test.",
        plan: {
          ...completePlan("invariant-public-target"),
          target: "external",
          targetBaseUrl: "https://www.google.com",
          traffic: { ratePerSec: 100, shape: "flat", duration: "30s" },
          mock: { strategy: "real_system" },
        },
        provenance: requiredProvenance("user"),
        expectedCode: "PUBLIC_TARGET_TRAFFIC_UNSAFE",
      },
      {
        name: "duplicate and invalid endpoint",
        sourceType: "plain-instructions",
        instructions: "Create a two-call scenario.",
        plan: {
          ...completePlan("invariant-endpoint-invalid"),
          endpoints: [
            { method: "GET", path: "/one", callId: "same" },
            { method: "FETCH", path: "two", callId: "same" },
          ],
        },
        provenance: requiredProvenance("user"),
        expectedCode: "ENDPOINT_CALL_ID_DUPLICATE",
      },
    ];

    for (const scenario of cases) {
      const started = await call(client, "workflow_start", {
        sourceType: scenario.sourceType,
        sourcePath: scenario.sourcePath,
        instructions: scenario.instructions,
      });
      const status = await call(client, "workflow_update", {
        workflowId: started.workflowId,
        plan: scenario.plan,
        provenance: scenario.provenance,
      });
      assertNoDeadEnd(status);
      if (scenario.expected) {
        assert.ok(status.nextQuestions.some(question => question.field === scenario.expected), `${scenario.name} should ask ${scenario.expected}`);
      }
      if (scenario.expectedCode) {
        assert.ok(status.validationIssues.some(issue => issue.code === scenario.expectedCode), `${scenario.name} should expose ${scenario.expectedCode}`);
      }
      assert.equal(status.allowedActions.includes("workflow_generate"), false, `${scenario.name} should block generation`);
      if (scenario.expected === "plan.sourceFidelity.userAcceptedLimitations") {
        const accepted = await call(client, "workflow_update", {
          workflowId: started.workflowId,
          plan: { sourceFidelity: { userAcceptedLimitations: true } },
        });
        assert.equal(accepted.missing.includes("plan.sourceFidelity.status"), false, "nested plan updates must preserve sibling sourceFidelity fields");
        assert.ok(accepted.provenanceGaps.some(gap => gap.field === "plan.sourceFidelity.userAcceptedLimitations"));
      }
      await assert.rejects(
        () => call(client, "workflow_generate", { workflowId: started.workflowId }),
        /WORKFLOW_PLAN_INCOMPLETE|WORKFLOW_ANSWER_VALIDATION_FAILED|WORKFLOW_PROVENANCE_INCOMPLETE|WORKFLOW_ROLE_CHECKS_INCOMPLETE/,
      );
    }
  });
});

test("adversarial agent cannot skip provenance, role, patch, or validation gates", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-agentic-adversarial-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a low-rate live public target test.",
    });
    const plan = {
      ...completePlan("invariant-adversarial"),
      target: "external",
      targetBaseUrl: "https://www.google.com",
      traffic: { ratePerSec: 1, shape: "smoke", duration: "30s" },
      mock: { strategy: "real_system" },
      safety: { publicTargetConfirmed: true },
    };
    const guessed = await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan,
      provenance: {
        ...requiredProvenance("user"),
        "plan.safety.publicTargetConfirmed": { source: "agent-inferred", note: "Hostile agent guessed confirmation." },
      },
    });
    assertNoDeadEnd(guessed);
    assert.ok(guessed.provenanceGaps.some(gap => gap.field === "plan.safety.publicTargetConfirmed"));
    await assert.rejects(
      () => call(client, "workflow_generate", { workflowId: started.workflowId }),
      /WORKFLOW_PROVENANCE_INCOMPLETE/,
    );

    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      provenance: { "plan.safety.publicTargetConfirmed": { source: "user", note: "User confirmed low-rate public target." } },
    });
    await assert.rejects(
      () => call(client, "workflow_generate", { workflowId: started.workflowId }),
      /WORKFLOW_ROLE_CHECKS_INCOMPLETE/,
    );
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });

    const escape = await callError(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "../escape.txt", content: "nope" }],
    });
    assert.match(escape, /WORKFLOW_PATCH_OUTSIDE_BUNDLE/);

    const skippedValidation = await callError(client, "workflow_deploy", { workflowId: started.workflowId });
    assert.match(skippedValidation, /WORKFLOW_VALIDATION_REQUIRED/);

    const validation = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(validation.ok, true);
    assert.equal(validation.authoritative, true);
    assert.equal(validation.validationLevel, "scenario-manager");
  });
});

test("Scenario Manager validation and claim matrix react to artifact mutation", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-agentic-mutation-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const mockStarted = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a mock-backed scenario and prove Scenario Manager validation.",
    });
    await call(client, "workflow_update", {
      workflowId: mockStarted.workflowId,
      plan: completePlan("invariant-mock-mutation"),
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, mockStarted.workflowId);
    await call(client, "workflow_generate", { workflowId: mockStarted.workflowId });
    const mockPath = resolve(root, "invariant-mock-mutation", "mock-config", "wiremock", "hello.json");
    const originalMock = readFileSync(mockPath, "utf8");

    await call(client, "workflow_patch", {
      workflowId: mockStarted.workflowId,
      changes: [{ file: "mock-config/wiremock/hello.json", content: "{ not-json" }],
    });
    const failed = await call(client, "workflow_validate", { workflowId: mockStarted.workflowId });
    assert.equal(failed.ok, false);
    const failedStatus = await call(client, "workflow_status", { workflowId: mockStarted.workflowId });
    assert.equal(failedStatus.claimMatrix.find(claim => claim.id === "validation.scenario-manager").status, "failed");

    await call(client, "workflow_patch", {
      workflowId: mockStarted.workflowId,
      changes: [{ file: "mock-config/wiremock/hello.json", content: originalMock }],
    });
    const fixed = await call(client, "workflow_validate", { workflowId: mockStarted.workflowId });
    assert.equal(fixed.ok, true);

    const csvStarted = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a CSV-backed scenario.",
    });
    await call(client, "workflow_update", {
      workflowId: csvStarted.workflowId,
      plan: {
        ...completePlan("invariant-csv-mutation"),
        dataset: { strategy: "CSV_DATASET", csvColumns: ["customerId", "accountId"] },
      },
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, csvStarted.workflowId);
    await call(client, "workflow_generate", { workflowId: csvStarted.workflowId });
    let csvStatus = await call(client, "workflow_status", { workflowId: csvStarted.workflowId });
    assert.equal(csvStatus.claimMatrix.find(claim => claim.id === "dataset.sample-artifact").status, "satisfied");
    assert.equal(csvStatus.evidenceContract.some(claim => claim.id === "dataset.rotated"), false);

    const samplePath = resolve(root, "invariant-csv-mutation", "datasets", "sample.csv");
    assert.equal(existsSync(samplePath), true);
    unlinkSync(samplePath);
    csvStatus = await call(client, "workflow_status", { workflowId: csvStarted.workflowId });
    assert.equal(csvStatus.claimMatrix.find(claim => claim.id === "dataset.sample-artifact").status, "missing");
    assert.equal(csvStatus.evidenceContract.find(claim => claim.id === "dataset.sample-artifact").status, "pending");
  }, {
    responses: [
      {
        ok: false,
        source: "uploaded-zip",
        scenarioId: "invariant-mock-mutation",
        summary: { errors: 1, warnings: 0 },
        findings: [{
          category: "mock",
          code: "MOCK_CONFIG_INVALID",
          severity: "error",
          path: "mock-config/wiremock/hello.json",
          message: "Invalid WireMock mapping JSON.",
          fix: "Repair the WireMock mapping JSON.",
        }],
      },
      { ok: true, source: "uploaded-zip", scenarioId: "invariant-mock-mutation", summary: { errors: 0, warnings: 0 }, findings: [] },
    ],
  });
});
