#!/usr/bin/env node

import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { parse as parseYaml } from "yaml";

const SERVER = resolve("server.mjs");
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://localhost:8088";
const BUNDLES_ROOT = process.env.PH_WIZARD_ACCEPTANCE_BUNDLES_ROOT || mkdtempSync(resolve(tmpdir(), "pockethive-wizard-acceptance-"));

function log(step, detail = "") {
  const suffix = detail ? ` ${detail}` : "";
  console.log(`✓ ${step}${suffix}`);
}

function parseTool(result) {
  const text = result.content?.[0]?.text;
  if (typeof text !== "string") return result.content;
  return JSON.parse(text);
}

async function withClient(fn) {
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
  const client = new Client({ name: "pockethive-wizard-acceptance", version: "1.0.0" }, { capabilities: {} });
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

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function readYaml(path) {
  return parseYaml(readFileSync(path, "utf8"));
}

function file(bundleId, relativePath) {
  return resolve(BUNDLES_ROOT, bundleId, relativePath);
}

function assertFiles(bundleId, files) {
  for (const relativePath of files) {
    assert(existsSync(file(bundleId, relativePath)), `${bundleId} missing ${relativePath}`);
  }
}

function assertBeeRoles(bundleId, expectedRoles) {
  const scenario = readYaml(file(bundleId, "scenario.yaml"));
  const roles = new Set((scenario.template?.bees || []).map((bee) => bee.role));
  for (const role of expectedRoles) {
    assert(roles.has(role), `${bundleId} missing bee role ${role}`);
  }
}

async function validateGeneratedBundle(client, bundleId) {
  const check = await call(client, "bundle_check", { bundle: bundleId });
  assert(check.ok, `${bundleId} bundle_check failed: ${JSON.stringify(check.errors, null, 2)}`);

  const validation = await call(client, "bundle_validate", { bundle: bundleId, validator: "local-structural" });
  const result = await call(client, "bundle_validate_result", { jobId: validation.jobId });
  assert(result.status === "done", `${bundleId} validation did not complete: ${JSON.stringify(result)}`);
  assert(result.structural?.ok === true, `${bundleId} local validation failed: ${JSON.stringify(result.structural, null, 2)}`);
}

const cases = [
  {
    name: "rest-wiremock-result-rules",
    input: {
      intent: "Create a REST onboarding smoke test with result rules",
      bundleId: "accept-rest-wiremock",
      protocol: "REST",
      target: "wiremock-local",
      endpoints: [{ method: "POST", path: "/api/onboarding", callId: "onboarding" }],
      requestBody: JSON.stringify({ customerId: "{{ customerId }}", plan: "smoke" }),
      defaultRatePerSec: 2,
      runDuration: "30s",
      resultRules: "yes",
      resultCodePattern: "\"resultCode\"\\s*:\\s*\"([^\"]+)\"",
      successCodes: ["SUCCESS"],
      mockEndpoints: [{
        method: "POST",
        path: "/api/onboarding",
        callId: "onboarding",
        status: 200,
        responseBody: { resultCode: "SUCCESS" },
      }],
    },
    expectedFiles: [
      "scenario.yaml",
      "templates/http/default/onboarding.yaml",
      "variables.yaml",
      "sut/wiremock-local/sut.yaml",
      "mock-config/wiremock/onboarding.json",
      "README.md",
      "FLOW_DOCUMENT.md",
      "CHANGELOG.md",
    ],
    expectedRoles: ["generator", "request-builder", "processor", "postprocessor"],
  },
  {
    name: "sequence-external-oauth",
    input: {
      intent: "Create a two-step REST sequence against a shared test API with OAuth",
      bundleId: "accept-sequence-oauth",
      protocol: "SEQUENCE",
      target: "external",
      targetBaseUrl: "https://sut.example.test",
      endpoints: [
        { method: "POST", path: "/api/login", callId: "login" },
        { method: "GET", path: "/api/profile/{{ userId }}", callId: "profile" },
      ],
      requestBody: JSON.stringify({ username: "{{ username }}" }),
      defaultRatePerSec: 1,
      auth: "oauth2_client_credentials",
      authTokenUrl: "https://auth.example.test/oauth/token",
      authClientId: "wizard-client",
      authSecretEnvVar: "WIZARD_CLIENT_SECRET",
    },
    expectedFiles: [
      "scenario.yaml",
      "templates/http/sequence/login.yaml",
      "templates/http/sequence/profile.yaml",
      "authProfiles.yaml",
      "sut/external-target/sut.yaml",
      "FLOW_DOCUMENT.md",
    ],
    expectedRoles: ["generator", "http-sequence", "postprocessor"],
  },
  {
    name: "tcp-mock-mtls",
    input: {
      intent: "Create a TCP request-response smoke test with mTLS",
      bundleId: "accept-tcp-mtls",
      protocol: "TCP",
      target: "tcp-mock-local",
      tcpPayload: "0200|{{ customerId }}|PING",
      defaultRatePerSec: 3,
      auth: "mtls",
      authSecretEnvVar: "TCP_CLIENT_CERT_PASSWORD",
      sutDouble: "tcp_mock",
    },
    expectedFiles: [
      "scenario.yaml",
      "templates/tcp/default/tcp-request.yaml",
      "authProfiles.yaml",
      "mock-config/tcp/tcp-request.yaml",
      "sut/tcp-mock-local/sut.yaml",
    ],
    expectedRoles: ["generator", "request-builder", "processor", "postprocessor"],
  },
  {
    name: "redis-dataset-loop",
    input: {
      intent: "Create a Redis dataset backed REST loop test",
      bundleId: "accept-redis-loop",
      protocol: "REST",
      target: "wiremock-local",
      endpoints: [{ method: "POST", path: "/api/redeem", callId: "redeem" }],
      requestBody: JSON.stringify({ customerId: "{{ customerId }}", amount: 10 }),
      defaultRatePerSec: 5,
      dataSource: "REDIS_DATASET",
      redisLists: ["customers", "vip-customers"],
      redisOutput: "yes",
    },
    expectedFiles: [
      "scenario.yaml",
      "templates/http/default/redeem.yaml",
      "mock-config/redis-state.json",
      "mock-config/wiremock/redeem.json",
    ],
    expectedRoles: ["generator", "request-builder", "processor", "postprocessor"],
  },
];

async function runCase(client, testCase) {
  const start = await call(client, "wizard_start", testCase.input);
  assert(start.ready, `${testCase.name} should be ready, missing: ${start.missing?.join(", ")}`);
  assert(start.scenario?.pattern, `${testCase.name} did not return a pattern`);

  const summary = await call(client, "wizard_summary", { sessionId: start.sessionId });
  assert(summary.ready, `${testCase.name} summary should be ready`);

  const complete = await call(client, "wizard_complete", { sessionId: start.sessionId });
  assert(complete.completed, `${testCase.name} did not complete`);
  assert(complete.structural?.ok, `${testCase.name} completion structural check failed`);

  assertFiles(testCase.input.bundleId, testCase.expectedFiles);
  assertBeeRoles(testCase.input.bundleId, testCase.expectedRoles);
  await validateGeneratedBundle(client, testCase.input.bundleId);
  log(testCase.name, `${complete.generated.pattern} ${complete.generated.filesCreated.length} files`);
}

async function runAcceptance() {
  console.log("PocketHive wizard acceptance suite");
  console.log(`Bundles root: ${BUNDLES_ROOT}`);
  console.log(`Base URL:     ${BASE_URL}`);

  await withClient(async (client) => {
    const context = await call(client, "context_get");
    assert(context.bundlesRoot === BUNDLES_ROOT, "MCP context did not use acceptance bundles root");
    log("context_get", context.bundlesRoot);

    const invalid = await call(client, "wizard_start", {
      intent: "Invalid mixed protocol target",
      bundleId: "accept-invalid",
      protocol: "TCP",
      target: "wiremock-local",
      defaultRatePerSec: 1,
      tcpPayload: "PING",
    });
    assert(!invalid.ready, "invalid TCP + wiremock-local input should not be ready");
    assert(
      invalid.errors?.some((error) => /TCP bundles cannot target wiremock-local/.test(error)),
      `invalid input did not explain protocol/target mismatch: ${(invalid.errors || []).join("; ")}`
    );
    log("invalid-input guard", "TCP + wiremock-local rejected");

    for (const testCase of cases) {
      await runCase(client, testCase);
    }
  });

  console.log("\nWizard acceptance suite completed.");
}

runAcceptance().catch(error => {
  console.error(`\nWizard acceptance failed: ${error.message}`);
  process.exit(1);
});
