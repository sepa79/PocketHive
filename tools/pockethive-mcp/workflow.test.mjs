import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";

async function withClient(bundlesRoot, fn) {
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
    },
  });
  const client = new Client({ name: "workflow-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) {
    throw new Error(result.content?.[0]?.text || `${name} failed`);
  }
  return JSON.parse(result.content[0].text);
}

async function callError(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  assert.equal(result.isError, true, `${name} should fail`);
  return result.content?.[0]?.text || "";
}

function writeSource(root, relativePath = "sources/minimal.jmx") {
  const path = resolve(root, relativePath);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `<jmeterTestPlan><hashTree><HTTPSamplerProxy testname="GET /hello"/></hashTree></jmeterTestPlan>`, "utf8");
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
    observability: { goal: "Stakeholders need throughput, latency, and mock-match evidence.", grafanaDashboard: "rtt_overview" },
    successCriteria: { resultRules: false, summary: "HTTP 2xx responses and no unmatched mock requests." },
  };
}

test("workflow.start rejects source paths outside allowed roots", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const outside = resolve(tmpdir(), `ph-workflow-outside-${Date.now()}.jmx`);
  writeFileSync(outside, "<jmeterTestPlan/>", "utf8");

  await withClient(root, async (client) => {
    const error = await callError(client, "workflow.start", { sourceType: "jmeter", sourcePath: outside });
    assert.match(error, /WORKFLOW_SOURCE_OUTSIDE_ALLOWED_ROOTS/);
  });
});

test("workflow.start accepts file and instruction sources", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withClient(root, async (client) => {
    const fromFile = await call(client, "workflow.start", { sourceType: "jmeter", sourcePath });
    assert.equal(fromFile.state, "source_ready");
    assert.equal(fromFile.source.type, "jmeter");
    assert.equal(fromFile.source.path, sourcePath);
    assert.match(fromFile.source.sha256, /^[a-f0-9]{64}$/);
    assert.ok(fromFile.missing.includes("plan.bundleId"));
    assert.ok(fromFile.nextQuestions.some((question) => question.id === "plan.bundleId"));
    assert.ok(fromFile.nextQuestions.some((question) => question.id === "plan.dataset.strategy"));

    const source = await call(client, "workflow.source.read", { workflowId: fromFile.workflowId, maxBytes: 40 });
    assert.equal(source.truncated, true);
    assert.match(source.content, /jmeterTestPlan/);

    const fromInstructions = await call(client, "workflow.start", {
      sourceType: "plain-instructions",
      instructions: "Create a REST smoke test for GET /hello with a WireMock double.",
    });
    assert.equal(fromInstructions.source.path, null);
    assert.match(fromInstructions.source.sha256, /^[a-f0-9]{64}$/);
  });
});

test("workflow config and list are read-only plugin status surfaces", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withClient(root, async (client) => {
    const config = await call(client, "workflow.config.get");
    assert.equal(config.workflowType, "agent-to-pockethive");
    assert.equal(config.bundleRoot, root);
    assert.ok(config.allowedSourceRoots.includes(root));
    assert.equal(config.pluginBoundary.mayAnswerQuestions, false);
    assert.ok(config.pluginBoundary.readOnlyTools.includes("workflow.list"));

    const validation = await call(client, "workflow.config.validate");
    assert.equal(validation.ok, true);
    assert.deepEqual(validation.missing, []);

    const started = await call(client, "workflow.start", { sourceType: "openapi", sourcePath });
    await call(client, "workflow.update", {
      workflowId: started.workflowId,
      answers: { datasetOwner: "platform-team" },
      plan: { bundleId: "agent-status-only" },
    });

    const listed = await call(client, "workflow.list");
    assert.equal(listed.count, 1);
    assert.equal(listed.workflows[0].workflowId, started.workflowId);
    assert.equal(listed.workflows[0].state, "plan_incomplete");
    assert.ok(listed.workflows[0].nextQuestions.some((question) => question.id === "plan.dataset.strategy"));
    assert.equal(Object.prototype.hasOwnProperty.call(listed.workflows[0], "plan"), false);
    assert.equal(Object.prototype.hasOwnProperty.call(listed.workflows[0], "answers"), false);

    const withoutQuestions = await call(client, "workflow.list", { includeQuestions: false });
    assert.deepEqual(withoutQuestions.workflows[0].nextQuestions, []);
  });
});

test("workflow gates generation until a normalized plan is complete, then generates a valid bundle", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withClient(root, async (client) => {
    const started = await call(client, "workflow.start", { sourceType: "postman", sourcePath });
    await call(client, "workflow.update", { workflowId: started.workflowId, plan: { bundleId: "agent-incomplete" } });

    const blocked = await callError(client, "workflow.generate", { workflowId: started.workflowId });
    assert.match(blocked, /WORKFLOW_PLAN_INCOMPLETE/);

    const updated = await call(client, "workflow.update", { workflowId: started.workflowId, plan: completePlan("agent-complete") });
    assert.equal(updated.state, "plan_ready");
    assert.deepEqual(updated.missing, []);
    assert.deepEqual(updated.nextQuestions, []);

    const preview = await call(client, "workflow.preview", { workflowId: started.workflowId });
    assert.equal(preview.bundle.id, "agent-complete");
    assert.equal(existsSync(resolve(root, "agent-complete")), false);

    const generated = await call(client, "workflow.generate", { workflowId: started.workflowId });
    assert.equal(generated.ok, true);
    assert.equal(generated.structural.ok, true);
    assert.equal(existsSync(resolve(root, "agent-complete", "scenario.yaml")), true);

    const check = await call(client, "bundle.check", { bundle: "agent-complete" });
    assert.equal(check.ok, true);
  });
});

test("workflow.patch is constrained and validation history preserves failed and fixed attempts", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withClient(root, async (client) => {
    const started = await call(client, "workflow.start", { sourceType: "k6", sourcePath });
    await call(client, "workflow.update", { workflowId: started.workflowId, plan: completePlan("agent-patch") });
    await call(client, "workflow.generate", { workflowId: started.workflowId });

    const escape = await callError(client, "workflow.patch", {
      workflowId: started.workflowId,
      changes: [{ file: "../escape.txt", content: "nope" }],
    });
    assert.match(escape, /WORKFLOW_PATCH_OUTSIDE_BUNDLE/);

    const scenarioPath = resolve(root, "agent-patch", "scenario.yaml");
    const originalScenario = readFileSync(scenarioPath, "utf8");
    await call(client, "workflow.patch", {
      workflowId: started.workflowId,
      changes: [{ file: "scenario.yaml", content: "not: [valid" }],
    });
    const failed = await call(client, "workflow.validate", { workflowId: started.workflowId, validator: "local-structural" });
    assert.equal(failed.ok, false);
    assert.equal(failed.code, "WORKFLOW_VALIDATION_FAILED");

    await call(client, "workflow.patch", {
      workflowId: started.workflowId,
      changes: [{ file: "scenario.yaml", content: originalScenario }],
    });
    const fixed = await call(client, "workflow.validate", { workflowId: started.workflowId, validator: "local-structural" });
    assert.equal(fixed.ok, true);

    const status = await call(client, "workflow.status", { workflowId: started.workflowId });
    const validationAttempts = status.history.filter((entry) => entry.action === "validate");
    assert.equal(validationAttempts.length, 2);
    assert.deepEqual(validationAttempts.map((entry) => entry.ok), [false, true]);
  });
});
