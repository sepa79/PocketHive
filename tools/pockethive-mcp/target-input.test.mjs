import test from "node:test";
import assert from "node:assert/strict";
import { dirname, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const SERVER = resolve(__dirname, "server.mjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";
const BUNDLES_ROOT = tmpdir();

async function withClient(fn) {
  const transport = new StdioClientTransport({
    command: "node",
    args: [SERVER],
    env: {
      ...process.env,
      POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
      ORCHESTRATOR_BASE_URL: `${UNUSED_BASE_URL}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${UNUSED_BASE_URL}/scenario-manager`,
      RABBITMQ_MANAGEMENT_BASE_URL: `${UNUSED_BASE_URL}/rabbitmq/api`,
      POCKETHIVE_AUTH_TOKEN: "",
      POCKETHIVE_AUTH_USERNAME: "",
      BUNDLES_ROOT,
      PH_BUNDLES_ROOTS: JSON.stringify([BUNDLES_ROOT]),
    },
  });
  const client = new Client({ name: "target-input-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

async function callWithoutInputValidationError(client, name, args) {
  try {
    const result = await client.callTool({ name, arguments: args });
    const text = result?.content?.map((item) => item.text || "").join("\n") || "";
    assert.doesNotMatch(text, /Input validation error|Expected string, received object/);
    return result;
  } catch (err) {
    const message = err?.message || String(err);
    assert.doesNotMatch(message, /Input validation error|Expected string, received object/);
    throw err;
  }
}

test("bundle target fields accept VS Code tree item objects", async () => {
  await withClient(async (client) => {
    const bundleName = `__missing_bundle_${Date.now()}__`;
    const result = await callWithoutInputValidationError(client, "bundle.check", {
      bundle: {
        bundle: { name: bundleName },
        label: { label: "Wrong fallback label" },
        command: { arguments: ["wrong-command-arg"] },
      },
    });

    assert.equal(result.isError, undefined);
    const payload = JSON.parse(result.content[0].text);
    assert.equal(payload.bundle, bundleName);
    assert.equal(payload.ok, false);
    assert.equal(payload.checks[0].id, "bundle.exists");
  });
});

test("swarm target fields accept VS Code tree item objects before runtime calls", async () => {
  await withClient(async (client) => {
    const result = await callWithoutInputValidationError(client, "swarm.start", {
      swarmId: {
        swarm: { id: "__missing_swarm_object_target__" },
        label: { label: "Wrong fallback label" },
        command: { arguments: ["wrong-command-arg"] },
      },
    });

    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /Error:/);
    assert.doesNotMatch(result.content[0].text, /Input validation error|Expected string, received object/);
  });
});

test("scenario target fields accept VS Code tree item objects before runtime calls", async () => {
  await withClient(async (client) => {
    const result = await callWithoutInputValidationError(client, "scenario.get", {
      scenarioId: {
        scenario: { id: "__missing_scenario_object_target__" },
        label: { label: "Wrong fallback label" },
        command: { arguments: ["wrong-command-arg"] },
      },
    });

    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /Error:/);
    assert.doesNotMatch(result.content[0].text, /Input validation error|Expected string, received object/);
  });
});

test("optional swarm filters accept VS Code tree item objects", async () => {
  await withClient(async (client) => {
    const result = await callWithoutInputValidationError(client, "debug.queues", {
      swarmId: {
        swarm: { id: "__missing_swarm_object_target__" },
        label: { label: "Wrong fallback label" },
        command: { arguments: ["wrong-command-arg"] },
      },
    });

    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /Error:/);
    assert.doesNotMatch(result.content[0].text, /Input validation error|Expected string, received object/);
  });
});
