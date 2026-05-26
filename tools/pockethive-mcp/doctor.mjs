#!/usr/bin/env node

import assert from "node:assert/strict";
import { dirname, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { dependenciesAreFresh, ensureDependencies } = require("./setup.cjs");

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";
const BUNDLES_ROOT = process.env.BUNDLES_ROOT || tmpdir();

const checks = [];

function pass(name, detail = "") {
  checks.push({ ok: true, name, detail });
  console.log(`OK   ${name}${detail ? ` - ${detail}` : ""}`);
}

function fail(name, err) {
  checks.push({ ok: false, name, detail: err?.message || String(err) });
  console.error(`FAIL ${name} - ${err?.message || err}`);
}

async function runCheck(name, fn) {
  try {
    const detail = await fn();
    pass(name, detail);
  } catch (err) {
    fail(name, err);
  }
}

function collectArraysMissingItems(node, path = "$", missing = []) {
  if (!node || typeof node !== "object") return missing;
  if (node.type === "array" && !Object.prototype.hasOwnProperty.call(node, "items")) missing.push(path);
  for (const [key, value] of Object.entries(node)) {
    collectArraysMissingItems(value, `${path}.${key}`, missing);
  }
  return missing;
}

async function listTools() {
  const [{ Client }, { StdioClientTransport }] = await Promise.all([
    import("@modelcontextprotocol/sdk/client/index.js"),
    import("@modelcontextprotocol/sdk/client/stdio.js"),
  ]);
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [START],
    env: {
      ...process.env,
      POCKETHIVE_BASE_URL: process.env.POCKETHIVE_BASE_URL || UNUSED_BASE_URL,
      ORCHESTRATOR_BASE_URL: process.env.ORCHESTRATOR_BASE_URL || `${UNUSED_BASE_URL}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: process.env.SCENARIO_MANAGER_BASE_URL || `${UNUSED_BASE_URL}/scenario-manager`,
      RABBITMQ_MANAGEMENT_BASE_URL: process.env.RABBITMQ_MANAGEMENT_BASE_URL || `${UNUSED_BASE_URL}/rabbitmq/api`,
      POCKETHIVE_AUTH_TOKEN: process.env.POCKETHIVE_AUTH_TOKEN || "",
      POCKETHIVE_AUTH_USERNAME: process.env.POCKETHIVE_AUTH_USERNAME || "",
      BUNDLES_ROOT,
      PH_BUNDLES_ROOTS: process.env.PH_BUNDLES_ROOTS || JSON.stringify([BUNDLES_ROOT]),
    },
  });
  const client = new Client({ name: "pockethive-mcp-doctor", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    return (await client.listTools()).tools;
  } finally {
    await client.close();
  }
}

await runCheck("Node.js version", () => {
  const major = Number.parseInt(process.versions.node.split(".")[0], 10);
  assert.ok(major >= 18, `Node 18+ required, found ${process.version}`);
  return process.version;
});

await runCheck("MCP dependencies", () => {
  const wasFresh = dependenciesAreFresh();
  const result = ensureDependencies({ stdio: "inherit" });
  return result.installed ? "installed from lockfile" : wasFresh ? "already current" : "current";
});

let tools = [];
await runCheck("MCP stdio startup", async () => {
  tools = await listTools();
  assert.ok(tools.length > 0, "tools/list returned no tools");
  return `${tools.length} tools`;
});

await runCheck("MCP input schemas", () => {
  const missing = tools.flatMap((tool) =>
    collectArraysMissingItems(tool.inputSchema, `tools.${tool.name}.inputSchema`)
  );
  assert.deepEqual(missing, []);
  return "all array schemas declare items";
});

await runCheck("Bundle root config", () => {
  assert.ok(BUNDLES_ROOT, "BUNDLES_ROOT is empty");
  return BUNDLES_ROOT;
});

const failures = checks.filter((check) => !check.ok);
if (failures.length) {
  console.error(`\nPocketHive MCP doctor found ${failures.length} problem(s).`);
  process.exit(1);
}

console.log("\nPocketHive MCP doctor passed.");
