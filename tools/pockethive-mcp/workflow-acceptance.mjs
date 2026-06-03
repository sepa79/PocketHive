#!/usr/bin/env node

import assert from "node:assert/strict";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const BUNDLES_ROOT = process.env.PH_WORKFLOW_ACCEPTANCE_BUNDLES_ROOT || mkdtempSync(join(tmpdir(), "pockethive-workflow-acceptance-"));
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://127.0.0.1:9";
const LIVE = process.env.PH_WORKFLOW_ACCEPTANCE_LIVE === "1";
const SCENARIO_MANAGER_BASE_URL = process.env.SCENARIO_MANAGER_BASE_URL || `${BASE_URL}/scenario-manager`;
const AUTH_SERVICE_BASE_URL = process.env.AUTH_SERVICE_BASE_URL || `${BASE_URL}/auth-service`;
const POCKETHIVE_AUTH_TOKEN = process.env.POCKETHIVE_AUTH_TOKEN || "";
const POCKETHIVE_AUTH_USERNAME = process.env.POCKETHIVE_AUTH_USERNAME || "";
let cachedAuthHeader = null;

function log(step, detail) {
  console.log(`OK ${step}${detail ? ` - ${detail}` : ""}`);
}

async function withClient(fn) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [START],
    env: {
      ...process.env,
      POCKETHIVE_BASE_URL: BASE_URL,
      ORCHESTRATOR_BASE_URL: process.env.ORCHESTRATOR_BASE_URL || `${BASE_URL}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: process.env.SCENARIO_MANAGER_BASE_URL || `${BASE_URL}/scenario-manager`,
      RABBITMQ_MANAGEMENT_BASE_URL: process.env.RABBITMQ_MANAGEMENT_BASE_URL || `${BASE_URL}/rabbitmq/api`,
      POCKETHIVE_AUTH_TOKEN: process.env.POCKETHIVE_AUTH_TOKEN || "",
      POCKETHIVE_AUTH_USERNAME: process.env.POCKETHIVE_AUTH_USERNAME || "",
      BUNDLES_ROOT,
      PH_BUNDLES_ROOTS: JSON.stringify([BUNDLES_ROOT]),
    },
  });
  const client = new Client({ name: "workflow-acceptance", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) throw new Error(result.content?.[0]?.text || `${name} failed`);
  return JSON.parse(result.content[0].text);
}

async function authorizationHeader() {
  if (POCKETHIVE_AUTH_TOKEN.trim()) {
    return POCKETHIVE_AUTH_TOKEN.startsWith("Bearer ") ? POCKETHIVE_AUTH_TOKEN : `Bearer ${POCKETHIVE_AUTH_TOKEN}`;
  }
  if (!POCKETHIVE_AUTH_USERNAME.trim()) return null;
  if (cachedAuthHeader) return cachedAuthHeader;
  const response = await fetch(`${AUTH_SERVICE_BASE_URL}/api/auth/dev/login`, {
    method: "POST",
    headers: { "content-type": "application/json", "accept": "application/json" },
    body: JSON.stringify({ username: POCKETHIVE_AUTH_USERNAME.trim() }),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Auth login failed: HTTP ${response.status}: ${text || "<empty>"}`);
  const payload = text ? JSON.parse(text) : null;
  if (!payload?.accessToken) throw new Error("Auth login returned empty accessToken");
  cachedAuthHeader = `Bearer ${payload.accessToken}`;
  return cachedAuthHeader;
}

async function scenarioManagerRequest(path, opts = {}) {
  const auth = await authorizationHeader();
  const response = await fetch(`${SCENARIO_MANAGER_BASE_URL}${path}`, {
    method: opts.method || "GET",
    headers: {
      "accept": "application/json",
      ...(auth ? { authorization: auth } : {}),
      ...(opts.headers || {}),
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text || "<empty>"}`);
  return text ? JSON.parse(text) : null;
}

function writeSource(name, content) {
  const path = resolve(BUNDLES_ROOT, "sources", name);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content, "utf8");
  return path;
}

function basePlan(bundleId) {
  return {
    bundleId,
    protocol: "REST",
    target: "wiremock-local",
    endpoints: [{ method: "GET", path: "/hello", callId: "hello" }],
    traffic: { ratePerSec: 2, shape: "flat", duration: "30s" },
    dataset: { strategy: "SCHEDULER" },
    mock: { strategy: "wiremock" },
    observability: { goal: "Stakeholder proof needs worker health, queue drain, mock match, and latency.", grafanaDashboard: "rtt_overview" },
    successCriteria: { resultRules: false, summary: "No unmatched mock requests and successful responses." },
    sourceFidelity: { status: "complete", convertedFeatures: ["http-method", "path", "traffic"], unsupportedConstructs: [] },
  };
}

function requiredProvenance() {
  return {
    "plan.target": { source: "user", note: "Acceptance fixture target." },
    "plan.dataset.strategy": { source: "user", note: "Acceptance fixture dataset strategy." },
    "plan.auth": { source: "user", note: "Acceptance fixture has no auth." },
    "plan.successCriteria": { source: "user", note: "Acceptance fixture success criteria." },
    "plan.traffic.ratePerSec": { source: "user", note: "Acceptance fixture rate." },
    "plan.traffic.shape": { source: "user", note: "Acceptance fixture shape." },
    "plan.traffic.duration": { source: "user", note: "Acceptance fixture duration." },
    "plan.observability.goal": { source: "user", note: "Acceptance fixture observability goal." },
  };
}

async function completeThreeAmigos(client, workflowId, roles = ["architect", "developer", "tester"]) {
  for (const roleId of roles) {
    await call(client, "workflow_role_check", {
      workflowId,
      stageId: "three-amigos",
      roleId,
      outcome: "pass",
      summary: `${roleId} acceptance review passed.`,
      risks: [],
    });
  }
}

async function generateValidateReport(client, workflowId, bundleId) {
  const generated = await call(client, "workflow_generate", { workflowId });
  assert.equal(generated.ok, true, `${bundleId} generation failed`);
  const validated = await call(client, "workflow_validate", { workflowId, validator: "local-structural" });
  assert.equal(validated.ok, true, `${bundleId} validation failed`);
  const report = await call(client, "workflow_report", { workflowId });
  assert.equal(report.ok, true, `${bundleId} report failed`);
  assert.ok(report.claimMatrix.some(claim => claim.id === "bundle.exists" && claim.status === "satisfied"));
  assert.equal(existsSync(resolve(BUNDLES_ROOT, bundleId, "WORKFLOW_EVIDENCE.md")), true);
}

async function cleanupLiveSwarm(client, swarmId) {
  if (!swarmId) return;
  try {
    await call(client, "swarm_remove", { swarmId });
    log("live swarm teardown", swarmId);
  } catch (err) {
    console.warn(`WARN live swarm teardown failed for ${swarmId}: ${err.message}`);
  }
}

async function cleanupLiveBundle(bundleId) {
  if (!bundleId) return;
  try {
    await scenarioManagerRequest(`/scenarios/${encodeURIComponent(bundleId)}`, { method: "DELETE" });
    log("live bundle teardown", bundleId);
  } catch (err) {
    console.warn(`WARN live bundle teardown failed for ${bundleId}: ${err.message}`);
  }
}

async function examplesCase(client) {
  const listed = await call(client, "workflow_examples_list");
  assert.deepEqual(listed.sourceOrder.map(source => source.id), ["repo-examples", "active-bundles-root"]);
  assert.ok(listed.examples.some(example => example.source === "repo-examples"), "repo examples should be listed");
  const recommended = await call(client, "workflow_examples_recommend", { intent: "http google smoke test", limit: 3 });
  assert.ok(recommended.recommendations.length > 0, "recommendations should include deterministic example matches");
  log("workflow examples", `${listed.examples.length} example(s)`);
}

async function minimalHttpCase(client) {
  const sourcePath = writeSource("minimal.jmx", "<jmeterTestPlan><HTTPSamplerProxy testname='GET /hello'/></jmeterTestPlan>");
  const start = await call(client, "workflow_start", { sourceType: "jmeter", sourcePath });
  assert.ok(start.nextQuestions.length > 0, "workflow_start should return required questions");
  await call(client, "workflow_update", { workflowId: start.workflowId, plan: basePlan("accept-workflow-minimal"), provenance: requiredProvenance() });
  await completeThreeAmigos(client, start.workflowId);
  await generateValidateReport(client, start.workflowId, "accept-workflow-minimal");
  log("minimal source workflow", start.workflowId);
}

async function datasetQuestionCase(client) {
  const start = await call(client, "workflow_start", {
    sourceType: "plain-instructions",
    instructions: "Create a CSV-backed checkout flow with customerId and accountId rotation.",
  });
  const incomplete = {
    ...basePlan("accept-workflow-dataset"),
    dataset: { strategy: "CSV_DATASET" },
  };
  const status = await call(client, "workflow_update", { workflowId: start.workflowId, plan: incomplete });
  assert.ok(status.nextQuestions.some(question => question.id === "plan.dataset.csvColumns"));
  await call(client, "workflow_update", {
    workflowId: start.workflowId,
    plan: { dataset: { strategy: "CSV_DATASET", csvColumns: ["customerId", "accountId"] } },
    provenance: requiredProvenance(),
  });
  await completeThreeAmigos(client, start.workflowId);
  await generateValidateReport(client, start.workflowId, "accept-workflow-dataset");
  log("dataset question workflow", start.workflowId);
}

async function mockBackedCase(client) {
  const sourcePath = writeSource("mock-backed.postman.json", JSON.stringify({ item: [{ name: "GET /hello" }] }));
  const start = await call(client, "workflow_start", { sourceType: "postman", sourcePath });
  const plan = {
    ...basePlan("accept-workflow-mock"),
    mock: {
      strategy: "wiremock",
      endpoints: [{ method: "GET", path: "/hello", callId: "hello", status: 202, responseBody: { accepted: true } }],
    },
  };
  await call(client, "workflow_update", { workflowId: start.workflowId, plan, provenance: requiredProvenance() });
  await completeThreeAmigos(client, start.workflowId);
  await generateValidateReport(client, start.workflowId, "accept-workflow-mock");
  assert.equal(existsSync(resolve(BUNDLES_ROOT, "accept-workflow-mock", "mock-config", "wiremock", "hello.json")), true);
  log("mock-backed workflow", start.workflowId);
}

async function answerValidationCase(client) {
  const start = await call(client, "workflow_start", {
    sourceType: "plain-instructions",
    instructions: "Create a public Google smoke test.",
  });
  const status = await call(client, "workflow_update", {
    workflowId: start.workflowId,
    plan: {
      ...basePlan("accept-workflow-invalid"),
      target: "external",
      targetBaseUrl: "not-a-url",
      mock: { strategy: "real_system" },
      traffic: { ratePerSec: 100, shape: "flat", duration: "30s" },
      successCriteria: "works",
      observability: { goal: "metrics" },
    },
    provenance: requiredProvenance(),
  });
  assert.ok(status.validationIssues.some(issue => issue.severity === "error"));
  log("answer validation workflow", start.workflowId);
}

async function modifyWorkflowCase(client) {
  await call(client, "bundle_scaffold", { bundleId: "accept-workflow-modify", pattern: "rest-simple", sutType: "none" });
  const start = await call(client, "workflow_start", {
    sourceType: "plain-instructions",
    instructions: "Modify the existing bundle README and prove it still validates.",
    mode: "modify",
    existingBundleId: "accept-workflow-modify",
  });
  assert.equal(start.mode, "modify");
  await call(client, "workflow_update", {
    workflowId: start.workflowId,
    plan: {
      changeSummary: "Update README only while preserving the scenario contract.",
      observability: { goal: "Reviewer can see structural validation after modification." },
      successCriteria: { summary: "Bundle check passes after the README update." },
    },
  });
  await call(client, "workflow_patch", {
    workflowId: start.workflowId,
    changes: [{ file: "README.md", content: "# accept-workflow-modify\n\nModified by workflow acceptance.\n" }],
  });
  const validated = await call(client, "workflow_validate", { workflowId: start.workflowId });
  assert.equal(validated.ok, true);
  log("modify workflow", start.workflowId);
}

async function failingPatchCase(client) {
  const sourcePath = writeSource("patch.k6.js", "export default function () { http.get('/hello') }");
  const start = await call(client, "workflow_start", { sourceType: "k6", sourcePath });
  await call(client, "workflow_update", { workflowId: start.workflowId, plan: basePlan("accept-workflow-patch"), provenance: requiredProvenance() });
  await completeThreeAmigos(client, start.workflowId);
  await call(client, "workflow_generate", { workflowId: start.workflowId });
  const scenarioPath = resolve(BUNDLES_ROOT, "accept-workflow-patch", "scenario.yaml");
  const originalScenario = readFileSync(scenarioPath, "utf8");
  await call(client, "workflow_patch", { workflowId: start.workflowId, changes: [{ file: "scenario.yaml", content: "not: [valid" }] });
  const failed = await call(client, "workflow_validate", { workflowId: start.workflowId });
  assert.equal(failed.ok, false);
  await call(client, "workflow_patch", { workflowId: start.workflowId, changes: [{ file: "scenario.yaml", content: originalScenario }] });
  const fixed = await call(client, "workflow_validate", { workflowId: start.workflowId });
  assert.equal(fixed.ok, true);
  const status = await call(client, "workflow_status", { workflowId: start.workflowId });
  assert.deepEqual(status.history.filter(entry => entry.action === "validate").map(entry => entry.ok), [false, true]);
  log("agent patch workflow", start.workflowId);
}

async function liveCase(client) {
  if (!LIVE) {
    console.log("SKIP live workflow acceptance. Set PH_WORKFLOW_ACCEPTANCE_LIVE=1 with a configured PocketHive stack.");
    return;
  }
  const start = await call(client, "workflow_start", {
    sourceType: "plain-instructions",
    instructions: "Create a live smoke proof for GET /hello.",
  });
  const liveBundleId = `accept-workflow-live-${Date.now()}`;
  const liveSwarmId = `${liveBundleId}-swarm`;
  try {
    await call(client, "workflow_update", { workflowId: start.workflowId, plan: basePlan(liveBundleId), provenance: requiredProvenance() });
    await completeThreeAmigos(client, start.workflowId);
    await generateValidateReport(client, start.workflowId, liveBundleId);
    const deploy = await call(client, "workflow_deploy", { workflowId: start.workflowId, swarmId: liveSwarmId });
    assert.equal(deploy.ok, true, "live workflow deploy failed");
    const verify = await call(client, "workflow_verify", { workflowId: start.workflowId, includeTapSample: true, proofMode: "strict" });
    assert.equal(verify.ok, true, "live workflow verify failed");
    log("live workflow proof", start.workflowId);
  } finally {
    await cleanupLiveSwarm(client, liveSwarmId);
    await cleanupLiveBundle(liveBundleId);
  }
}

console.log("PocketHive agent workflow acceptance");
console.log(`Bundles root: ${BUNDLES_ROOT}`);
console.log(`Base URL: ${BASE_URL}`);

await withClient(async (client) => {
  await examplesCase(client);
  await minimalHttpCase(client);
  await datasetQuestionCase(client);
  await mockBackedCase(client);
  await failingPatchCase(client);
  await answerValidationCase(client);
  await modifyWorkflowCase(client);
  await liveCase(client);
});

console.log("Workflow acceptance passed.");
