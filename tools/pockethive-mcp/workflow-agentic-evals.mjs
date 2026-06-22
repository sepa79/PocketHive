#!/usr/bin/env node

import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const BUNDLES_ROOT = process.env.PH_AGENTIC_EVALS_BUNDLES_ROOT || mkdtempSync(join(tmpdir(), "pockethive-agentic-evals-"));
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://127.0.0.1:9";

function log(step, detail = "") {
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
      PH_WORKFLOW_PERSISTENCE: "memory",
    },
  });
  const client = new Client({ name: "workflow-agentic-evals", version: "1.0.0" }, { capabilities: {} });
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

async function callError(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  assert.equal(result.isError, true, `${name} should fail`);
  return result.content?.[0]?.text || "";
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
    observability: { goal: "Stakeholder proof needs worker health, queue drain, mock match, latency, and throughput.", grafanaDashboard: "rtt_overview" },
    successCriteria: { resultRules: false, summary: "HTTP 2xx responses and no unmatched mock requests." },
    sourceFidelity: { status: "complete", convertedFeatures: ["method", "path", "traffic"], unsupportedConstructs: [] },
  };
}

function provenance(source = "user") {
  return {
    "plan.target": { source, note: "Fake agent obtained target answer." },
    "plan.dataset.strategy": { source, note: "Fake agent obtained dataset answer." },
    "plan.auth": { source, note: "Fake agent confirmed no auth." },
    "plan.successCriteria": { source, note: "Fake agent obtained success criteria." },
    "plan.traffic.ratePerSec": { source, note: "Fake agent obtained rate." },
    "plan.traffic.shape": { source, note: "Fake agent obtained shape." },
    "plan.traffic.duration": { source, note: "Fake agent obtained duration." },
    "plan.observability.goal": { source, note: "Fake agent obtained observability goal." },
  };
}

async function completeThreeAmigos(client, workflowId, roles = ["architect", "developer", "tester"]) {
  for (const roleId of roles) {
    await call(client, "workflow_role_check", {
      workflowId,
      stageId: "three-amigos",
      roleId,
      outcome: "pass",
      summary: `${roleId} fake-agent eval review passed.`,
      risks: [],
    });
  }
}

function assertNoDeadEnds(status) {
  assert.deepEqual(status.unresolvableBlockers, [], "agent must never see unresolvable normal blockers");
  for (const blocker of status.blockers || []) {
    assert.ok(blocker.resolvedBy, `${blocker.id} must declare a resolving tool`);
    assert.equal(blocker.unresolvable, false, `${blocker.id} must be resolvable`);
  }
}

async function deterministicNoviceAgent(client) {
  const started = await call(client, "workflow_start", {
    sourceType: "plain-instructions",
    instructions: "I want to create a scenario bundle to test Google.",
  });
  assert.ok(started.nextQuestions.length > 0, "novice intent should trigger intake questions");
  assertNoDeadEnds(started);
  await callError(client, "workflow_generate", { workflowId: started.workflowId });

  const planned = await call(client, "workflow_update", {
    workflowId: started.workflowId,
    plan: {
      ...basePlan("agentic-google-mock"),
      endpoints: [{ method: "GET", path: "/search", callId: "search", query: { q: "pockethive" } }],
      mock: {
        strategy: "wiremock",
        endpoints: [{
          method: "GET",
          path: "/search",
          callId: "search",
          queryParameters: { q: { equalTo: "pockethive" } },
          responseBody: { ok: true, engine: "mock-google" },
        }],
      },
    },
    provenance: provenance("user"),
  });
  assert.equal(planned.allowedActions.includes("workflow_generate"), false, "role checks still gate generation");
  assertNoDeadEnds(planned);

  await completeThreeAmigos(client, started.workflowId);
  const preview = await call(client, "workflow_preview", { workflowId: started.workflowId });
  assert.equal(preview.sideEffect, "no-file-write");
  assert.equal(existsSync(resolve(BUNDLES_ROOT, "agentic-google-mock")), false, "preview must not write files");

  const generated = await call(client, "workflow_generate", { workflowId: started.workflowId });
  assert.equal(generated.ok, true);
  const validated = await call(client, "workflow_validate", { workflowId: started.workflowId });
  assert.equal(validated.code, "WORKFLOW_VALIDATED");
  assert.equal(validated.authoritative, true);
  assert.equal(validated.validationLevel, "scenario-manager");
  const report = await call(client, "workflow_report", { workflowId: started.workflowId });
  assert.equal(report.ok, true);
  assert.equal(report.claimMatrix.find(claim => claim.id === "workflow.questions").status, "satisfied");
  assert.equal(report.claimMatrix.find(claim => claim.id === "stakeholder.report").status, "satisfied");
  log("deterministic novice agent", started.workflowId);
}

async function sourceFidelityAgent(client) {
  const sourcePath = writeSource("lossy-jmeter.jmx", [
    "<jmeterTestPlan>",
    "  <HTTPSamplerProxy testname='GET /hello'/>",
    "  <JSR223PostProcessor testname='correlate-token'/>",
    "</jmeterTestPlan>",
  ].join("\n"));
  const started = await call(client, "workflow_start", { sourceType: "jmeter", sourcePath });
  const blocked = await call(client, "workflow_update", {
    workflowId: started.workflowId,
    plan: {
      ...basePlan("agentic-lossy-jmeter"),
      sourceFidelity: {
        status: "partial-accepted",
        convertedFeatures: ["http-method", "path"],
        unsupportedConstructs: ["JSR223 postprocessor correlation"],
      },
    },
    provenance: provenance("source-derived"),
  });
  assert.ok(blocked.nextQuestions.some(question => question.field === "plan.sourceFidelity.userAcceptedLimitations"));
  assertNoDeadEnds(blocked);
  await callError(client, "workflow_generate", { workflowId: started.workflowId });

  await call(client, "workflow_update", {
    workflowId: started.workflowId,
    plan: { sourceFidelity: { userAcceptedLimitations: true } },
    provenance: { "plan.sourceFidelity.userAcceptedLimitations": { source: "user", note: "User accepted listed conversion limitation." } },
  });
  await completeThreeAmigos(client, started.workflowId);
  await call(client, "workflow_generate", { workflowId: started.workflowId });
  await call(client, "workflow_validate", { workflowId: started.workflowId });
  const report = await call(client, "workflow_report", { workflowId: started.workflowId });
  assert.ok(report.report.history.some(entry => entry.action === "generate"));
  assert.ok(report.report.history.some(entry => entry.action === "validate"));
  log("source fidelity agent", started.workflowId);
}

async function adversarialAgent(client) {
  const started = await call(client, "workflow_start", {
    sourceType: "plain-instructions",
    instructions: "Create a live public target test and skip the ceremony.",
  });
  await call(client, "workflow_update", {
    workflowId: started.workflowId,
    plan: {
      ...basePlan("agentic-adversarial"),
      target: "external",
      targetBaseUrl: "https://www.google.com",
      traffic: { ratePerSec: 1, shape: "smoke", duration: "30s" },
      mock: { strategy: "real_system" },
      safety: { publicTargetConfirmed: true },
    },
    provenance: {
      ...provenance("user"),
      "plan.safety.publicTargetConfirmed": { source: "agent-inferred", note: "Bad agent guessed." },
    },
  });
  let status = await call(client, "workflow_status", { workflowId: started.workflowId });
  assert.ok(status.provenanceGaps.some(gap => gap.field === "plan.safety.publicTargetConfirmed"));
  assertNoDeadEnds(status);
  await callError(client, "workflow_generate", { workflowId: started.workflowId });

  await call(client, "workflow_update", {
    workflowId: started.workflowId,
    provenance: { "plan.safety.publicTargetConfirmed": { source: "user", note: "User explicitly confirmed low-rate live target." } },
  });
  await completeThreeAmigos(client, started.workflowId);
  await call(client, "workflow_generate", { workflowId: started.workflowId });
  assert.match(await callError(client, "workflow_deploy", { workflowId: started.workflowId }), /WORKFLOW_VALIDATION_REQUIRED/);
  await call(client, "workflow_validate", { workflowId: started.workflowId });
  assert.match(await callError(client, "workflow_patch", {
    workflowId: started.workflowId,
    changes: [{ file: "../escape.txt", content: "nope" }],
  }), /WORKFLOW_PATCH_OUTSIDE_BUNDLE/);
  const verify = await call(client, "workflow_verify", { workflowId: started.workflowId });
  assert.equal(verify.ok, false);
  assert.equal(verify.failureCode, "WORKFLOW_RUNTIME_NOT_STARTED");
  status = await call(client, "workflow_status", { workflowId: started.workflowId });
  const failures = status.history.filter(entry => entry.ok === false);
  assert.ok(failures.some(entry => entry.action === "verify" && entry.code === "WORKFLOW_RUNTIME_NOT_STARTED"));
  log("adversarial agent blocked", started.workflowId);
}

async function main() {
  console.log("PocketHive MCP agentic evals");
  console.log(`Bundles root: ${BUNDLES_ROOT}`);
  console.log(`Base URL: ${BASE_URL}`);
  await withClient(async (client) => {
    await deterministicNoviceAgent(client);
    await sourceFidelityAgent(client);
    await adversarialAgent(client);
  });
  console.log("Agentic evals passed.");
}

main().catch((err) => {
  console.error(err.stack || err.message);
  process.exitCode = 1;
});
