#!/usr/bin/env node

import assert from "node:assert/strict";
import { cpSync, mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { stringify } from "yaml";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = process.env.PH_SCENARIO_REGRESSION_ROOT || mkdtempSync(join(tmpdir(), "ph-scenario-regression-"));
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://localhost:8088";
const SEED = resolve(__dirname, "fixtures/scenario-regression/v0.15.28-local-rest-topology");

function writeBundle(id, scenario) {
  const dir = resolve(ROOT, id);
  mkdirSync(dir, { recursive: true });
  writeFileSync(resolve(dir, "scenario.yaml"), typeof scenario === "string" ? scenario : stringify(scenario), "utf8");
}

function cleanScenario(id, body) {
  return {
    protocolVersion: "2.0.0",
    id,
    name: id,
    template: {
      image: "swarm-controller:latest",
      bees: [{
        role: "generator",
        image: "generator:latest",
        config: {
          inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 1 } },
          message: { bodyType: "SIMPLE", body },
        },
        work: { out: { out: "generated" } },
      }],
    },
  };
}

function stageCorpus() {
  cpSync(SEED, resolve(ROOT, "legacy-v01528-ids"), { recursive: true });
  const missing = cleanScenario("missing-protocol-version", "{}");
  delete missing.protocolVersion;
  writeBundle("missing-protocol-version", missing);

  const incompatible = cleanScenario("incompatible-protocol-major", "{}");
  incompatible.protocolVersion = "1.3.0";
  writeBundle("incompatible-protocol-major", incompatible);

  writeBundle("broken-multi-quotes", cleanScenario(
    "broken-multi-quotes",
    '{{ eval("#base64_encode("" ~ pan ~ "")") }}'));
  writeBundle("unclosed-pebble", cleanScenario("unclosed-pebble", "{{ payload "));
  writeBundle("invalid-eval", cleanScenario("invalid-eval", '{{ eval("#base64_encode(") }}'));
}

async function call(client, name, args = {}) {
  const response = await client.callTool({ name, arguments: args });
  if (response.isError) throw new Error(response.content?.[0]?.text || `${name} failed`);
  return JSON.parse(response.content[0].text);
}

async function validate(client, bundle, expected) {
  const started = await call(client, "bundle_validate", { bundle });
  let result;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    result = await call(client, "bundle_validate_result", { jobId: started.jobId });
    if (result.status !== "running") break;
    await new Promise(resolveWait => setTimeout(resolveWait, 250));
  }
  assert.equal(result?.status, "done", `${bundle}: validation did not complete`);
  const validation = result.scenarioManager;
  assert.equal(validation.ok, false, `${bundle}: invalid scenario unexpectedly passed`);
  assert.equal(validation.validation.supportedScenarioProtocolVersion, "2.0.0");
  assert.match(validation.validation.scenarioManagerVersion, /^\d+\.\d+\.\d+/);
  assert.match(validation.validation.artifactDigest, /^sha256:[0-9a-f]{64}$/);
  const serialized = JSON.stringify(validation.findings);
  for (const fragment of expected) assert.match(serialized, new RegExp(fragment), `${bundle}: missing ${fragment}`);
  console.log(`OK ${bundle}: ${validation.findings.length} finding(s)`);
}

stageCorpus();
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [resolve(__dirname, "server.mjs")],
  env: {
    ...process.env,
    BUNDLES_ROOT: ROOT,
    PH_BUNDLES_ROOTS: JSON.stringify([ROOT]),
    POCKETHIVE_BASE_URL: BASE_URL,
  },
});
const client = new Client({ name: "scenario-regression-agent", version: "1.0.0" }, { capabilities: {} });
await client.connect(transport);
try {
  await validate(client, "legacy-v01528-ids", ["Unrecognized field.*id"]);
  await validate(client, "missing-protocol-version", ["protocolVersion is required"]);
  await validate(client, "incompatible-protocol-major", ["incompatible"]);
  await validate(client, "broken-multi-quotes", ["Inline template syntax is invalid"]);
  await validate(client, "unclosed-pebble", ["Inline template syntax is invalid"]);
  await validate(client, "invalid-eval", ["Inline template syntax is invalid"]);
} finally {
  await client.close();
}
