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
  const client = new Client({ name: "tools-list-schema-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

function findArraySchemasMissingItems(node, path = "inputSchema", found = []) {
  if (!node || typeof node !== "object") return found;
  if (node.type === "array" && !Object.prototype.hasOwnProperty.call(node, "items")) {
    found.push(path);
  }
  for (const [key, value] of Object.entries(node)) {
    findArraySchemasMissingItems(value, `${path}.${key}`, found);
  }
  return found;
}

test("tools/list never emits array schemas without items", async () => {
  await withClient(async (client) => {
    const { tools } = await client.listTools();
    const missing = [];
    for (const tool of tools) {
      for (const path of findArraySchemasMissingItems(tool.inputSchema)) {
        missing.push(`${tool.name}:${path}`);
      }
    }

    assert.deepEqual(missing, []);

    const wizardStart = tools.find((tool) => tool.name === "wizard_start");
    assert.ok(wizardStart, "wizard_start tool must be listed");
    const mockEndpoints = wizardStart.inputSchema.properties.mockEndpoints;
    assert.equal(mockEndpoints.type, "array");
    assert.deepEqual(
      mockEndpoints.items.anyOf.map((schema) => schema.type),
      ["string", "object"]
    );
  });
});

test("swarm_create exposes an explicit network mode", async () => {
  await withClient(async (client) => {
    const { tools } = await client.listTools();
    const swarmCreate = tools.find((tool) => tool.name === "swarm_create");

    assert.ok(swarmCreate, "swarm_create tool must be listed");
    assert.ok(swarmCreate.inputSchema.required.includes("networkMode"));
    assert.deepEqual(swarmCreate.inputSchema.properties.networkMode.enum, ["DIRECT", "PROXIED"]);
  });
});

test("swarm_create rejects proxied mode without a SUT before issuing HTTP", async () => {
  await withClient(async (client) => {
    const result = await client.callTool({
      name: "swarm_create",
      arguments: {
        swarmId: "schema-test",
        templateId: "template-test",
        networkMode: "PROXIED",
        networkProfileId: "profile-test",
      },
    });

    assert.equal(result.isError, true);
    assert.match(result.content[0].text, /sutId is required when networkMode is PROXIED/);
  });
});
