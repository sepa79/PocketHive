import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import Ajv from "ajv";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

import {
  extractFences,
  publishedDocs,
  REPOSITORY_ROOT,
} from "../docs-validation/docs-scope.mjs";

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const SERVER = resolve(SCRIPT_DIRECTORY, "server.mjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";
const INVOCATION_PATTERN = /^([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\s+(\{.*\})\s*$/;

function log(message) {
  console.log(`[docs-mcp-contracts] ${message}`);
}

async function documentedInvocations() {
  const invocations = [];
  for (const doc of await publishedDocs()) {
    const source = await readFile(doc.path, "utf8");
    for (const fence of extractFences(source, doc.relativePath)) {
      if (fence.language !== "text") continue;
      const lines = fence.content.split(/\r?\n/);
      for (let index = 0; index < lines.length; index += 1) {
        const match = INVOCATION_PATTERN.exec(lines[index].trim());
        if (!match) continue;
        invocations.push({
          argumentsText: match[2],
          line: fence.line + index,
          relativePath: doc.relativePath,
          toolName: match[1],
        });
      }
    }
  }
  return invocations;
}

async function main() {
  const bundlesRoot = await mkdtemp(join(tmpdir(), "pockethive-doc-contracts-"));
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [SERVER],
    env: {
      ...process.env,
      BUNDLES_ROOT: bundlesRoot,
      ORCHESTRATOR_BASE_URL: `${UNUSED_BASE_URL}/orchestrator`,
      PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
      PH_WORKFLOW_PERSISTENCE: "memory",
      POCKETHIVE_AUTH_TOKEN: "",
      POCKETHIVE_AUTH_USERNAME: "",
      POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
      POCKETHIVE_ROOT: REPOSITORY_ROOT,
      RABBITMQ_MANAGEMENT_BASE_URL: `${UNUSED_BASE_URL}/rabbitmq/api`,
      SCENARIO_MANAGER_BASE_URL: `${UNUSED_BASE_URL}/scenario-manager`,
    },
  });
  const client = new Client(
    { name: "pockethive-doc-contract-audit", version: "1.0.0" },
    { capabilities: {} },
  );

  try {
    await client.connect(transport);
    const listed = await client.listTools();
    const tools = new Map(listed.tools.map((tool) => [tool.name, tool]));
    const invocations = await documentedInvocations();
    const ajv = new Ajv({ allErrors: true, strict: false, validateFormats: false });
    const validators = new Map();
    const failures = [];

    for (const invocation of invocations) {
      const location = `${invocation.relativePath}:${invocation.line}`;
      const tool = tools.get(invocation.toolName);
      if (!tool) {
        failures.push(`${location} references unregistered tool '${invocation.toolName}'`);
        continue;
      }

      let argumentsValue;
      try {
        argumentsValue = JSON.parse(invocation.argumentsText);
      } catch (error) {
        failures.push(`${location} has invalid JSON arguments: ${error.message}`);
        continue;
      }

      let validate = validators.get(invocation.toolName);
      if (!validate) {
        validate = ajv.compile(tool.inputSchema);
        validators.set(invocation.toolName, validate);
      }
      if (!validate(argumentsValue)) {
        failures.push(
          `${location} does not match ${invocation.toolName}: ${ajv.errorsText(validate.errors, { separator: "; " })}`,
        );
      }
    }

    log(`Server tools listed: ${tools.size}`);
    log(`Documented invocation payloads checked: ${invocations.length}`);
    if (failures.length > 0) {
      console.error(`\n[docs-mcp-contracts] FAIL ${failures.length} contract error(s):`);
      for (const failure of failures) console.error(`- ${failure}`);
      process.exitCode = 1;
      return;
    }
    log("PASS every documented MCP invocation names a registered tool and matches its input schema");
  } finally {
    await client.close().catch(() => undefined);
    await rm(bundlesRoot, { force: true, recursive: true });
  }
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
