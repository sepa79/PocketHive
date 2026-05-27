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

async function listTools(extraEnv = {}) {
  return await withSchemaClient(async (client) => (await client.listTools()).tools, extraEnv);
}

async function withSchemaClient(fn, extraEnv = {}) {
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
      ...extraEnv,
    },
  });
  const client = new Client({ name: "pockethive-mcp-schema-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    return await fn(client);
  } finally {
    await client.close();
  }
}

async function listResources(extraEnv = {}) {
  return await withSchemaClient(async (client) => (await client.listResources()).resources, extraEnv);
}

async function readResource(uri, extraEnv = {}) {
  return await withSchemaClient(async (client) => await client.readResource({ uri }), extraEnv);
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

test("tools/list exposes Copilot-safe underscore tool names", async () => {
  const tools = await listTools();
  const invalid = tools
    .map((tool) => tool.name)
    .filter((name) => !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/.test(name));

  assert.deepEqual(invalid, []);
});

test("legacy dotted tool names require explicit opt-in", async () => {
  const tools = await listTools({ PH_MCP_TOOL_NAME_MODE: "legacy" });

  toolByName(tools, "workflow.start");
  assert.equal(tools.some((tool) => tool.name === "workflow_start"), false);
});

test("wizard_start mockEndpoints declares item shape", async () => {
  const tools = await listTools();
  const wizardStart = toolByName(tools, "wizard_start");
  const mockEndpoints = wizardStart.inputSchema.properties.mockEndpoints;

  assert.equal(mockEndpoints.type, "array");
  assert.ok(mockEndpoints.items, "wizard_start.mockEndpoints must declare array items");
  assert.deepEqual(
    mockEndpoints.items.anyOf.map((schema) => schema.type),
    ["string", "object"]
  );
  assert.equal((wizardStart.inputSchema.required ?? []).includes("mockEndpoints"), false);
});

test("workflow tools are listed with read/write annotations", async () => {
  const tools = await listTools();
  const expected = [
    "workflow_config_get",
    "workflow_config_validate",
    "workflow_examples_list",
    "workflow_examples_get",
    "workflow_examples_recommend",
    "workflow_profiles_list",
    "workflow_profiles_get",
    "workflow_role_check",
    "workflow_hivemind_enrich",
    "workflow_list",
    "workflow_start",
    "workflow_source_read",
    "workflow_update",
    "workflow_status",
    "workflow_result",
    "workflow_preview",
    "workflow_generate",
    "workflow_validate",
    "workflow_deploy",
    "workflow_deploy_start",
    "workflow_deploy_status",
    "workflow_deploy_resume",
    "workflow_verify",
    "workflow_verify_start",
    "workflow_verify_status",
    "workflow_verify_resume",
    "workflow_evidence_render",
    "workflow_patch",
    "workflow_report",
  ];

  for (const name of expected) toolByName(tools, name);

  assert.equal(toolByName(tools, "workflow_config_get").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_config_validate").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_examples_list").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_examples_get").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_examples_recommend").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_profiles_list").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_profiles_get").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_role_check").annotations.readOnlyHint, false);
  assert.equal(toolByName(tools, "workflow_hivemind_enrich").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_list").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_source_read").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_status").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_result").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_preview").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_generate").annotations.readOnlyHint, false);
  assert.equal(toolByName(tools, "workflow_patch").annotations.readOnlyHint, false);
  assert.equal(toolByName(tools, "workflow_deploy").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_deploy_start").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_deploy_status").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_deploy_resume").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_verify").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_verify_start").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_verify_status").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_verify_resume").annotations.openWorldHint, true);
  assert.equal(toolByName(tools, "workflow_evidence_render").annotations.readOnlyHint, true);
  assert.equal(toolByName(tools, "workflow_evidence_render")._meta.ui.resourceUri, "ui://pockethive/workflow-evidence-v1.html");
  assert.equal(toolByName(tools, "workflow_evidence_render")._meta["openai/outputTemplate"], "ui://pockethive/workflow-evidence-v1.html");
});

test("MCP App evidence resources are listed and use Apps SDK MIME type", async () => {
  const resources = await listResources();
  const summary = resources.find(resource => resource.uri === "ui://pockethive/evidence-summary-v1.html");
  const workflow = resources.find(resource => resource.uri === "ui://pockethive/workflow-evidence-v1.html");
  assert.ok(summary, "evidence summary widget resource should be listed");
  assert.ok(workflow, "workflow evidence widget resource should be listed");

  const workflowResource = await readResource("ui://pockethive/workflow-evidence-v1.html");
  assert.equal(workflowResource.contents?.[0]?.mimeType, "text/html;profile=mcp-app");
  assert.match(workflowResource.contents?.[0]?.text, /Workflow Evidence/);
  assert.equal(workflowResource.contents?.[0]?._meta.ui.prefersBorder, true);
});
