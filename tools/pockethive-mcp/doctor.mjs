#!/usr/bin/env node

import assert from "node:assert/strict";
import { existsSync, readFileSync, statSync } from "node:fs";
import { basename, dirname, isAbsolute, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { dependenciesAreFresh, ensureDependencies } = require("./setup.cjs");

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const REPO_ROOT = resolve(__dirname, "../..");
const UNUSED_BASE_URL = "http://127.0.0.1:9";
const argsResult = parseArgsSafe(process.argv.slice(2));
if (argsResult.error) {
  console.error(`FAIL CLI arguments - ${argsResult.error.message}`);
  process.exit(1);
}
const args = argsResult.args;
const MCP_SERVER_ID = args.server || process.env.PH_MCP_SERVER_ID || "pockethive-bundles";
const configResult = args.configDisabled
  ? { config: null, error: null }
  : loadMcpConfigSafe(args.config || process.env.PH_MCP_CONFIG || "");
const config = configResult.config;
const effectiveEnv = {
  ...(config?.env || {}),
  ...process.env,
};
const envBaseDir = config?.baseDir || process.cwd();
const CONFIGURED_BUNDLES_ROOT = effectiveEnv.BUNDLES_ROOT?.trim() || "";
const SMOKE_BUNDLES_ROOT = CONFIGURED_BUNDLES_ROOT ? resolvePath(envBaseDir, CONFIGURED_BUNDLES_ROOT) : tmpdir();
const USE_HTTP_CONFIG = Boolean(config?.server?.url);

const checks = [];

function parseArgs(argv) {
  const parsed = { config: "", configDisabled: false, server: "" };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--no-config") {
      parsed.configDisabled = true;
    } else if (arg === "--config") {
      parsed.config = argv[++i] || "";
    } else if (arg.startsWith("--config=")) {
      parsed.config = arg.slice("--config=".length);
    } else if (arg === "--server") {
      parsed.server = argv[++i] || "";
    } else if (arg.startsWith("--server=")) {
      parsed.server = arg.slice("--server=".length);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return parsed;
}

function parseArgsSafe(argv) {
  try {
    return { args: parseArgs(argv), error: null };
  } catch (err) {
    return { args: null, error: err };
  }
}

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

function loadMcpConfig(configPath) {
  const candidates = configPath
    ? uniquePaths([resolvePath(process.cwd(), configPath), resolvePath(REPO_ROOT, configPath)])
    : [resolve(REPO_ROOT, "mcp.json"), resolve(REPO_ROOT, ".amazonq", "mcp.json")];
  const path = candidates.find(candidate => existsSync(candidate));
  if (configPath && !path) {
    throw new Error(`MCP config not found: ${configPath}`);
  }
  if (!path) return null;

  const parsed = JSON.parse(readFileSync(path, "utf8"));
  const servers = parsed.mcpServers || parsed.servers || {};
  const server = servers[MCP_SERVER_ID];
  if (!server) {
    throw new Error(`MCP config ${path} does not define server '${MCP_SERVER_ID}'`);
  }

  const baseDir = inferConfigBaseDir(path);
  const cwd = server.cwd ? resolvePath(baseDir, server.cwd) : baseDir;
  return {
    path,
    server,
    baseDir: cwd,
    env: server.env && typeof server.env === "object" ? server.env : {},
  };
}

function loadMcpConfigSafe(configPath) {
  try {
    return { config: loadMcpConfig(configPath), error: null };
  } catch (err) {
    return { config: null, error: err };
  }
}

function uniquePaths(paths) {
  return [...new Set(paths)];
}

function inferConfigBaseDir(path) {
  const configDir = dirname(path);
  const configDirName = basename(configDir);
  if ([".amazonq", ".codex", ".cursor", ".vscode", ".windsurf"].includes(configDirName)) {
    return dirname(configDir);
  }
  return path.startsWith(REPO_ROOT) ? REPO_ROOT : configDir;
}

function expandConfigVariables(value, baseDir) {
  return String(value || "")
    .replaceAll("${workspaceFolder}", baseDir)
    .replaceAll("${workspaceFolderBasename}", basename(baseDir));
}

function resolvePath(baseDir, value) {
  const path = expandConfigVariables(value, baseDir).trim();
  return isAbsolute(path) ? path : resolve(baseDir, path);
}

function parseJsonStringArrayEnv(name) {
  const raw = effectiveEnv[name]?.trim();
  if (!raw) return { configured: false, values: [] };
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new Error(`${name} must be a JSON array of strings: ${err.message}`);
  }
  assert.ok(Array.isArray(parsed), `${name} must be a JSON array of strings`);
  const values = parsed.map((value, index) => {
    assert.equal(typeof value, "string", `${name}[${index}] must be a string`);
    const trimmed = value.trim();
    assert.ok(trimmed, `${name}[${index}] must not be blank`);
    return resolvePath(envBaseDir, trimmed);
  });
  return { configured: true, values };
}

function assertDirectory(path, label) {
  assert.ok(existsSync(path), `${label} does not exist: ${path}`);
  assert.ok(statSync(path).isDirectory(), `${label} is not a directory: ${path}`);
}

function assertMcpServerConfig() {
  assert.equal(config.server.disabled, false, `MCP server '${MCP_SERVER_ID}' must set disabled: false in ${config.path}`);
  const configuredUrl = expandConfigVariables(config.server.url || "", config.baseDir).trim();
  if (configuredUrl) {
    const parsedUrl = new URL(configuredUrl);
    assert.ok(["http:", "https:"].includes(parsedUrl.protocol), `MCP server '${MCP_SERVER_ID}' url must use http or https`);
    assert.ok(parsedUrl.pathname.endsWith("/mcp"), `MCP server '${MCP_SERVER_ID}' url must point at the /mcp endpoint`);
    return;
  }
  assert.equal(typeof config.server.command, "string", `MCP server '${MCP_SERVER_ID}' must define command`);
  const commandName = basename(config.server.command).toLowerCase();
  assert.ok(commandName === "node" || commandName === "node.exe", `MCP server '${MCP_SERVER_ID}' command must be node`);
  assert.ok(Array.isArray(config.server.args), `MCP server '${MCP_SERVER_ID}' must define args array`);
  for (const [index, arg] of config.server.args.entries()) {
    assert.equal(typeof arg, "string", `MCP server '${MCP_SERVER_ID}' args[${index}] must be a string`);
    assert.ok(arg.trim(), `MCP server '${MCP_SERVER_ID}' args[${index}] must not be blank`);
  }
  const configuredStart = config.server.args
    .map(arg => resolvePath(config.baseDir, arg))
    .find(candidate => candidate === START);
  assert.ok(
    configuredStart,
    `MCP server '${MCP_SERVER_ID}' args must include tools/pockethive-mcp/start.cjs resolved from ${config.baseDir}`
  );
}

function parseToolJson(result, toolName) {
  if (result?.isError) {
    throw new Error(`${toolName} failed: ${result.content?.[0]?.text || "unknown error"}`);
  }
  const text = result?.content?.find(item => item.type === "text")?.text;
  assert.ok(text, `${toolName} returned no text content`);
  return JSON.parse(text);
}

async function connectClient() {
  const [{ Client }] = await Promise.all([
    import("@modelcontextprotocol/sdk/client/index.js"),
  ]);
  let transport;
  const configuredUrl = config?.server?.url
    ? expandConfigVariables(config.server.url, config.baseDir).trim()
    : "";
  if (configuredUrl) {
    const { StreamableHTTPClientTransport } = await import("@modelcontextprotocol/sdk/client/streamableHttp.js");
    transport = new StreamableHTTPClientTransport(new URL(configuredUrl));
  } else {
    const { StdioClientTransport } = await import("@modelcontextprotocol/sdk/client/stdio.js");
    transport = new StdioClientTransport({
      command: process.execPath,
      args: [START],
      env: {
        ...effectiveEnv,
        POCKETHIVE_BASE_URL: effectiveEnv.POCKETHIVE_BASE_URL || UNUSED_BASE_URL,
        ORCHESTRATOR_BASE_URL: effectiveEnv.ORCHESTRATOR_BASE_URL || `${UNUSED_BASE_URL}/orchestrator`,
        SCENARIO_MANAGER_BASE_URL: effectiveEnv.SCENARIO_MANAGER_BASE_URL || `${UNUSED_BASE_URL}/scenario-manager`,
        RABBITMQ_MANAGEMENT_BASE_URL: effectiveEnv.RABBITMQ_MANAGEMENT_BASE_URL || `${UNUSED_BASE_URL}/rabbitmq/api`,
        POCKETHIVE_AUTH_TOKEN: effectiveEnv.POCKETHIVE_AUTH_TOKEN || "",
        POCKETHIVE_AUTH_USERNAME: effectiveEnv.POCKETHIVE_AUTH_USERNAME || "",
        BUNDLES_ROOT: SMOKE_BUNDLES_ROOT,
        PH_BUNDLES_ROOTS: effectiveEnv.PH_BUNDLES_ROOTS || JSON.stringify([SMOKE_BUNDLES_ROOT]),
      },
    });
  }
  const client = new Client({ name: "pockethive-mcp-doctor", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  return client;
}

async function inspectMcp() {
  const client = await connectClient();
  try {
    const tools = (await client.listTools()).tools;
    const context = parseToolJson(await client.callTool({ name: "context_get", arguments: {} }), "context_get");
    return { tools, context };
  } finally {
    await client.close();
  }
}

await runCheck("Node.js version", () => {
  const major = Number.parseInt(process.versions.node.split(".")[0], 10);
  assert.ok(major >= 18, `Node 18+ required, found ${process.version}`);
  return process.version;
});

await runCheck("MCP config source", () => {
  if (configResult.error) throw configResult.error;
  if (!config) return "env only";
  assertMcpServerConfig();
  return `${config.path}#${MCP_SERVER_ID}`;
});

await runCheck("MCP dependencies", () => {
  const wasFresh = dependenciesAreFresh();
  const result = ensureDependencies({ stdio: "inherit" });
  return result.installed ? "installed from lockfile" : wasFresh ? "already current" : "current";
});

let tools = [];
let mcpContext = null;
await runCheck(USE_HTTP_CONFIG ? "MCP Streamable HTTP startup" : "MCP stdio startup", async () => {
  const inspected = await inspectMcp();
  tools = inspected.tools;
  mcpContext = inspected.context;
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

await runCheck("MCP tool names", () => {
  const invalid = tools
    .map((tool) => tool.name)
    .filter((name) => !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/.test(name));
  assert.deepEqual(invalid, []);
  return "all tool names are underscore-safe";
});

await runCheck("Bundle root config", () => {
  const root = USE_HTTP_CONFIG
    ? mcpContext?.bundlesRoot
    : (CONFIGURED_BUNDLES_ROOT ? resolvePath(envBaseDir, CONFIGURED_BUNDLES_ROOT) : "");
  assert.ok(root, "BUNDLES_ROOT is not set. Configure it to a separate scenario-bundles checkout.");
  assertDirectory(root, "BUNDLES_ROOT");
  return root;
});

await runCheck("Bundle roots list config", () => {
  if (USE_HTTP_CONFIG && Array.isArray(mcpContext?.allBundlesRoots)) {
    if (!mcpContext.allBundlesRoots.length) return "not set; MCP will use BUNDLES_ROOT";
    for (const root of mcpContext.allBundlesRoots) assertDirectory(root, "PH_BUNDLES_ROOTS entry");
    return mcpContext.allBundlesRoots.join(", ");
  }
  const { configured, values } = parseJsonStringArrayEnv("PH_BUNDLES_ROOTS");
  if (!configured) return "not set; MCP will use BUNDLES_ROOT";
  assert.ok(values.length > 0, "PH_BUNDLES_ROOTS must include at least one bundle root when set");
  for (const root of values) assertDirectory(root, "PH_BUNDLES_ROOTS entry");
  return values.join(", ");
});

await runCheck("Workflow source roots config", () => {
  const { configured, values } = parseJsonStringArrayEnv("PH_WORKFLOW_SOURCE_ROOTS");
  if (!configured) return "not set";
  for (const root of values) assertDirectory(root, "PH_WORKFLOW_SOURCE_ROOTS entry");
  return values.length ? values.join(", ") : "[]";
});

const failures = checks.filter((check) => !check.ok);
if (failures.length) {
  console.error(`\nPocketHive MCP doctor found ${failures.length} problem(s).`);
  process.exit(1);
}

console.log("\nPocketHive MCP doctor passed.");
