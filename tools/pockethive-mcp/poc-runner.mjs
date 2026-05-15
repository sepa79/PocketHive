#!/usr/bin/env node

import { existsSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const SERVER = resolve("server.mjs");
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://localhost:8088";
const BUNDLES_ROOT = process.env.PH_POC_BUNDLES_ROOT || mkdtempSync(resolve(tmpdir(), "pockethive-mcp-poc-"));
const BUNDLE_ID = process.env.PH_POC_BUNDLE_ID || `poc-onboarding-${Date.now().toString(36)}`;
const LIVE_MODE = process.env.PH_POC_LIVE === "1";

const wizardInput = {
  intent: "Create a REST onboarding smoke test against WireMock",
  bundleId: BUNDLE_ID,
  protocol: "REST",
  target: "wiremock-local",
  endpoints: [
    {
      method: "POST",
      path: "/api/onboarding",
      callId: "onboarding",
      description: "Submit onboarding request",
    },
  ],
  requestBody: JSON.stringify({ customerId: "poc-123", email: "poc@example.com", plan: "smoke" }, null, 2),
  defaultRatePerSec: 2,
  runDuration: "30s",
  trafficShape: "ramp_steady",
  dataSource: "SCHEDULER",
  auth: "none",
  sutDouble: "wiremock",
  mockEndpoints: [
    {
      method: "POST",
      path: "/api/onboarding",
      callId: "onboarding",
      status: 200,
      responseBody: { resultCode: "SUCCESS", onboardingId: "ONB-POC" },
    },
  ],
  resultRules: "yes",
  resultCodePattern: "\"resultCode\"\\s*:\\s*\"([^\"]+)\"",
  successCodes: ["SUCCESS"],
  performanceObjective: "p99 latency < 500ms at 2 rps",
  clickhouse: "yes_for_nft_only",
  grafanaDashboard: "rtt_overview",
  docs: "yes",
};

function log(step, detail = "") {
  const suffix = detail ? ` ${detail}` : "";
  console.log(`✓ ${step}${suffix}`);
}

function parseTool(result) {
  const text = result.content?.[0]?.text;
  if (typeof text !== "string") return result.content;
  return JSON.parse(text);
}

async function withStdioClient(fn) {
  const transport = new StdioClientTransport({
    command: "node",
    args: [SERVER],
    env: {
      ...process.env,
      BUNDLES_ROOT,
      POCKETHIVE_BASE_URL: BASE_URL,
      PH_BUNDLES_ROOTS: JSON.stringify([BUNDLES_ROOT]),
    },
  });
  const client = new Client({ name: "pockethive-poc-runner", version: "1.0.0" }, { capabilities: {} });
  try {
    await client.connect(transport);
    return await fn(client);
  } finally {
    await client.close().catch(() => {});
  }
}

async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) throw new Error(result.content?.[0]?.text || `${name} failed`);
  return parseTool(result);
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const port = server.address().port;
  await new Promise(resolveClose => server.close(resolveClose));
  return port;
}

async function waitForLine(child, pattern, timeoutMs = 5000) {
  let buffer = "";
  return await new Promise((resolveWait, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${pattern}`)), timeoutMs);
    const onData = (chunk) => {
      buffer += chunk.toString();
      if (buffer.includes(pattern)) {
        clearTimeout(timer);
        resolveWait(buffer);
      }
    };
    child.stdout.on("data", onData);
    child.stderr.on("data", onData);
    child.once("exit", code => {
      clearTimeout(timer);
      reject(new Error(`HTTP MCP server exited early with code ${code}: ${buffer}`));
    });
  });
}

async function retryStep(label, fn, timeoutMs = 30000, intervalMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      return await fn();
    } catch (error) {
      lastError = error;
      await new Promise(resolveRetry => setTimeout(resolveRetry, intervalMs));
    }
  }
  throw new Error(`${label} did not succeed within ${timeoutMs}ms: ${lastError?.message || "unknown error"}`);
}

async function runHttpResourceSmoke() {
  const port = Number(process.env.PH_POC_HTTP_PORT) || await freePort();
  const child = spawn("node", [SERVER], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      PH_MCP_HTTP_PORT: String(port),
      BUNDLES_ROOT,
      POCKETHIVE_BASE_URL: BASE_URL,
      PH_BUNDLES_ROOTS: JSON.stringify([BUNDLES_ROOT]),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  try {
    await waitForLine(child, `http://localhost:${port}/mcp`);
    const transport = new StreamableHTTPClientTransport(new URL(`http://localhost:${port}/mcp`));
    const client = new Client({ name: "pockethive-poc-http", version: "1.0.0" }, { capabilities: {} });
    try {
      await client.connect(transport);
      const resources = await client.listResources();
      const widget = resources.resources.find(resource => resource.uri === "ui://pockethive/evidence-summary-v1.html");
      if (!widget) throw new Error("Evidence widget resource is not listed");
      const resource = await client.readResource({ uri: widget.uri });
      const mimeType = resource.contents?.[0]?.mimeType;
      if (mimeType !== "text/html;profile=mcp-app") throw new Error(`Unexpected widget MIME type: ${mimeType}`);
      log("HTTP/App resource smoke", widget.uri);
    } finally {
      await client.close().catch(() => {});
    }
  } finally {
    child.kill();
  }
}

async function runOfflinePoc() {
  console.log("PocketHive MCP POC runner");
  console.log(`Bundles root: ${BUNDLES_ROOT}`);
  console.log(`Bundle id:    ${BUNDLE_ID}`);
  console.log(`Base URL:     ${BASE_URL}`);

  await withStdioClient(async (client) => {
    const context = await call(client, "context.get");
    log("MCP context", context.bundlesRoot);

    const start = await call(client, "wizard.start", wizardInput);
    if (!start.ready) throw new Error(`Wizard should be ready, missing: ${start.missing.join(", ")}`);
    log("wizard.start", start.sessionId);

    const summary = await call(client, "wizard.summary", { sessionId: start.sessionId });
    if (!summary.ready) throw new Error("Wizard summary is not ready");
    log("wizard.summary", summary.scenario.pattern);

    const complete = await call(client, "wizard.complete", { sessionId: start.sessionId });
    if (!complete.completed || !complete.structural?.ok) {
      throw new Error(`Wizard completion failed: ${JSON.stringify(complete, null, 2)}`);
    }
    log("wizard.complete", complete.generated.path);

    const scenarioPath = resolve(BUNDLES_ROOT, BUNDLE_ID, "scenario.yaml");
    const templatePath = resolve(BUNDLES_ROOT, BUNDLE_ID, "templates", "http", "default", "onboarding.yaml");
    const variablesPath = resolve(BUNDLES_ROOT, BUNDLE_ID, "variables.yaml");
    const mockPath = resolve(BUNDLES_ROOT, BUNDLE_ID, "mock-config", "wiremock", "onboarding.json");
    const flowPath = resolve(BUNDLES_ROOT, BUNDLE_ID, "FLOW_DOCUMENT.md");
    const changelogPath = resolve(BUNDLES_ROOT, BUNDLE_ID, "CHANGELOG.md");
    if (![scenarioPath, templatePath, variablesPath, mockPath, flowPath, changelogPath].every(existsSync)) {
      throw new Error("Generated bundle is missing one or more expected wizard artifacts");
    }
    log("bundle files", "scenario.yaml + templates/http/default/onboarding.yaml + docs/mock artifacts");

    const check = await call(client, "bundle.check", { bundle: BUNDLE_ID });
    if (!check.ok) throw new Error(`bundle.check failed: ${JSON.stringify(check.errors, null, 2)}`);
    log("bundle.check", "ok");

    const resources = await client.listResources();
    const widget = resources.resources.find(resource => resource.uri === "ui://pockethive/evidence-summary-v1.html");
    if (!widget) throw new Error("Evidence widget resource is not listed in stdio mode");
    log("widget resource listed", widget.uri);

    const tools = await client.listTools();
    const toolNames = new Set(tools.tools.map(tool => tool.name));
    for (const requiredTool of ["component.config-preview", "component.config-update"]) {
      if (!toolNames.has(requiredTool)) throw new Error(`${requiredTool} is not listed`);
    }
    log("real-time control tools listed", "component.config-preview + component.config-update");

    if (LIVE_MODE) {
      const health = await call(client, "health.check");
      log("health.check", JSON.stringify(health));

      await call(client, "scenario.deploy", { bundle: BUNDLE_ID });
      log("scenario.deploy", BUNDLE_ID);

      const swarmId = process.env.PH_POC_SWARM_ID || `${BUNDLE_ID}-swarm`;
      await call(client, "swarm.create", {
        swarmId,
        templateId: BUNDLE_ID,
        sutId: "wiremock-local",
        variablesProfileId: "default",
      });
      log("swarm.create", swarmId);

      await call(client, "swarm.wait-ready", { swarmId, timeoutSec: Number(process.env.PH_POC_READY_TIMEOUT_SEC || 45) });
      log("swarm.wait-ready", swarmId);

      await call(client, "swarm.start", { swarmId });
      log("swarm.start", swarmId);

      const status = await call(client, "swarm.get", { swarmId });
      const workers = status.envelope?.data?.context?.workers || status.context?.workers || [];
      const generator = workers.find(worker => worker.role === "generator");
      if (!generator?.instance) throw new Error("Could not locate generator instance for config update proof");
      const ratePatch = { inputs: { scheduler: { ratePerSec: 1 } } };
      const preview = await retryStep("component.config-preview", () => call(client, "component.config-preview", {
          swarmId,
          role: "generator",
          instanceId: generator.instance,
          patch: ratePatch,
          includeMergedConfig: true,
        }),
      );
      if (preview.sideEffect !== "no-config-write") throw new Error("component.config-preview had unexpected side effect marker");
      if (preview.mergedConfig?.inputs?.scheduler?.ratePerSec !== 1) {
        throw new Error(`component.config-preview did not merge rate patch: ${JSON.stringify(preview.mergedConfig)}`);
      }
      log("component.config-preview", generator.instance);

      const configUpdate = await call(client, "component.config-update", {
        swarmId,
        role: "generator",
        instanceId: generator.instance,
        patch: ratePatch,
        notes: "PocketHive MCP live POC rate update proof",
      });
      if (!configUpdate.accepted || !configUpdate.watch) {
        throw new Error(`component.config-update was not accepted: ${JSON.stringify(configUpdate)}`);
      }
      log("component.config-update", generator.instance);

      const evidence = await call(client, "evidence.summary", { swarmId, includeTapSample: false });
      log("evidence.summary", `${evidence.sources.filter(source => source.status === "ok").length}/${evidence.sources.length} sources available`);
    } else {
      console.log("ℹ Live stack steps skipped. Set PH_POC_LIVE=1 to deploy, create/start a swarm, and gather evidence.");
    }
  });

  await runHttpResourceSmoke();
  console.log("\nPOC runner completed.");
}

runOfflinePoc().catch(error => {
  console.error(`\nPOC runner failed: ${error.message}`);
  process.exit(1);
});
