import test from "node:test";
import assert from "node:assert/strict";
import { dirname, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";
const BUNDLES_ROOT = tmpdir();

async function listTools() {
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
      BUNDLES_ROOT,
      PH_BUNDLES_ROOTS: JSON.stringify([BUNDLES_ROOT]),
    },
  });
  const client = new Client({ name: "pockethive-mcp-schema-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    const response = await client.listTools();
    return response.tools;
  } finally {
    await client.close();
  }
}

function collectArraysMissingItems(node, path = "$", missing = []) {
  if (!node || typeof node !== "object") return missing;

  if (node.type === "array" && !Object.prototype.hasOwnProperty.call(node, "items")) {
    missing.push(path);
  }

  for (const [key, value] of Object.entries(node)) {
    collectArraysMissingItems(value, `${path}.${key}`, missing);
  }

  return missing;
}

function toolByName(tools, name) {
  const tool = tools.find((candidate) => candidate.name === name);
  assert.ok(tool, `Expected MCP tool '${name}' to be registered`);
  return tool;
}

test("tools/list input schemas declare items for every array", async () => {
  const tools = await listTools();

  const missing = tools.flatMap((tool) =>
    collectArraysMissingItems(tool.inputSchema, `tools.${tool.name}.inputSchema`)
  );

  assert.deepEqual(missing, []);
});

test("wizard.start mockEndpoints declares item shape", async () => {
  const tools = await listTools();
  const wizardStart = toolByName(tools, "wizard.start");
  const mockEndpoints = wizardStart.inputSchema.properties.mockEndpoints;

  assert.equal(mockEndpoints.type, "array");
  assert.ok(mockEndpoints.items, "wizard.start.mockEndpoints must declare array items");
  assert.deepEqual(
    mockEndpoints.items.anyOf.map((schema) => schema.type),
    ["string", "object"]
  );
  assert.equal((wizardStart.inputSchema.required ?? []).includes("mockEndpoints"), false);
});

test("workflow tools are listed with read/write annotations", async () => {
  const tools = await listTools();
  const expected = [
    "workflow.config.get",
    "workflow.config.validate",
    "workflow.list",
    "workflow.start",
    "workflow.source.read",
    "workflow.update",
    "workflow.status",
    "workflow.preview",
    "workflow.generate",
    "workflow.validate",
    "workflow.deploy",
    "workflow.verify",
    "workflow.patch",
    "workflow.report",
  ];

  for (const name of expected) toolByName(tools, name);

  assert.equal(toolByName(tools, "workflow.config.get").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow.config.validate").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow.list").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow.source.read").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow.status").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow.preview").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow.generate").annotations.readOnlyHint, false);
  assert.equal(toolByName(tools, "workflow.patch").annotations.readOnlyHint, false);
  assert.equal(toolByName(tools, "workflow.deploy").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow.verify").annotations.openWorldHint, true);
});
