#!/usr/bin/env node

/**
 * PocketHive Scenario Bundles MCP Server
 *
 * Provides tools for the full TDD lifecycle of scenario bundles:
 *   - Validate bundle structure
 *   - Create / start / stop / remove swarms
 *   - Inspect swarm status, queues, control-plane messages
 *   - Tap data-plane messages for debugging
 *   - Read swarm journal for timeline inspection
 *
 * Designed to be spawned by IDEs and agent clients.
 */

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync, unlinkSync } from "node:fs";
import { createServer } from "node:http";
import net from "node:net";
import { dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { platform } from "node:os";
import { z } from "zod";
import amqplib from "amqplib";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import {
  configFromJournalEntry,
  latestComponentConfigFromJournalPage,
  planComponentConfigUpdate,
  summarizePatch,
} from "./config-update.mjs";
import { registerRuntimeTools } from "./runtime-tools.mjs";
import { registerWorkflowTools } from "./workflow-tools.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
// When running from tools/pockethive-mcp/, REPO_ROOT is two levels up.
const REPO_ROOT = resolve(__dirname, "../..");
// BUNDLES_DIR: resolved bundle directory used by all bundle.* tools.
// Priority: BUNDLES_ROOT (plugin injection) > POCKETHIVE_BUNDLES_DIR (legacy) > repo-relative default.
const BUNDLES_DIR_DEFAULT = process.env.POCKETHIVE_BUNDLES_DIR
  ? resolve(process.env.POCKETHIVE_BUNDLES_DIR)
  : resolve(REPO_ROOT, "scenarios", "bundles");
function getBundlesDir() {
  return BUNDLES_ROOT ? resolve(BUNDLES_ROOT) : BUNDLES_DIR_DEFAULT;
}

const POCKETHIVE_ROOT = process.env.POCKETHIVE_ROOT || "";
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://localhost:8088";
const POCKETHIVE_AUTH_TOKEN = process.env.POCKETHIVE_AUTH_TOKEN || "";
// PH_BUNDLES_ROOTS: JSON array of all configured bundle roots injected by the IDE plugin.
// Falls back to empty array when running standalone (bundles repo mode uses BUNDLES_DIR instead).
let _bundlesRoots = [];
try {
  if (process.env.PH_BUNDLES_ROOTS) {
    _bundlesRoots = JSON.parse(process.env.PH_BUNDLES_ROOTS);
  }
} catch { /* ignore malformed JSON */ }
// Active BUNDLES_ROOT — mutable so context.set-bundles-root can update it at runtime.
let BUNDLES_ROOT = process.env.BUNDLES_ROOT || "";
const ORCH_URL = process.env.ORCHESTRATOR_BASE_URL || `${BASE_URL}/orchestrator`;
const SM_URL = process.env.SCENARIO_MANAGER_BASE_URL || `${BASE_URL}/scenario-manager`;
const AUTH_SERVICE_URL = process.env.AUTH_SERVICE_BASE_URL || `${BASE_URL}/auth-service`;
const POCKETHIVE_AUTH_USERNAME = process.env.POCKETHIVE_AUTH_USERNAME || "";
const RABBIT_MGMT = process.env.RABBITMQ_MANAGEMENT_BASE_URL || `${BASE_URL}/rabbitmq/api`;
const PROM_URL = process.env.PROMETHEUS_BASE_URL || `${BASE_URL}/prometheus`;
const REDIS_HOST = process.env.REDIS_HOST || "localhost";
const REDIS_PORT = Number(process.env.REDIS_PORT || "6379");
// TCP_MOCK_BASE_URL and WIREMOCK_BASE_URL can be set explicitly to override
// the auto-derived URLs (host from BASE_URL, standard ports 8083/8080).

// ── Path helpers ──────────────────────────────────────────────────────────────

function ensureInside(base, target) {
  const rel = relative(resolve(base), resolve(target));
  if (rel.startsWith("..") || rel === ".." || resolve(rel) === rel) {
    throw new Error(`Path escapes configured bundle root: ${target}`);
  }
}

function bundleDir(bundle) {
  const dir = resolve(getBundlesDir(), bundle);
  ensureInside(getBundlesDir(), dir);
  return dir;
}

function bundleRootPolicy() {
  const activeRoot = resolve(getBundlesDir());
  const productRoot = resolve(POCKETHIVE_ROOT || REPO_ROOT);
  const rel = relative(productRoot, activeRoot);
  const colocatedWithPocketHive = rel === "" || (!rel.startsWith("..") && !resolve(rel).startsWith("/"));
  return {
    policy: "scenario bundles should normally live in a separate scenario-bundles repo",
    activeRoot,
    productRoot,
    colocatedWithPocketHive,
    warning: colocatedWithPocketHive
      ? "Active BUNDLES_ROOT is inside the PocketHive product repo. Use this only for examples/smoke tests; configure a separate scenario-bundles checkout for normal authoring."
      : null,
  };
}

function targetString(value, preferredKeys = ["id", "name"]) {
  if (typeof value === "string") return value.trim() || undefined;
  if (!value || typeof value !== "object") return undefined;
  for (const key of preferredKeys) {
    if (typeof value[key] === "string" && value[key].trim()) return value[key].trim();
  }
  for (const nested of ["swarm", "bundle", "scenario"]) {
    const found = targetString(value[nested], preferredKeys);
    if (found) return found;
  }
  const commandArg = Array.isArray(value.command?.arguments) ? value.command.arguments[0] : undefined;
  const fromCommand = targetString(commandArg, preferredKeys);
  if (fromCommand) return fromCommand;
  const label = value.label;
  if (typeof label === "string" && label.trim()) return label.trim();
  if (label && typeof label === "object") {
    const fromLabel = targetString(label, ["label", ...preferredKeys]);
    if (fromLabel) return fromLabel;
  }
  return undefined;
}

function targetStringSchema(description, preferredKeys = ["id", "name"]) {
  return z.preprocess(value => targetString(value, preferredKeys) ?? value, z.string()).describe(description);
}

const BUNDLE_ARG = targetStringSchema("Bundle name", ["name", "id"]);
const SCENARIO_ID_ARG = targetStringSchema("Scenario ID", ["id", "name"]);
const SWARM_ID_ARG = targetStringSchema("Swarm ID", ["id", "name"]);
const WORKER_IMAGE_NAMES = Object.freeze({
  GENERATOR: "generator",
  PROCESSOR: "processor",
  POSTPROCESSOR: "postprocessor",
  REQUEST_BUILDER: "request-builder",
  HTTP_SEQUENCE: "http-sequence",
  CLEARING_EXPORT: "clearing-export",
});
const TERMINAL_WORKER_IMAGE_NAMES = new Set([
  WORKER_IMAGE_NAMES.POSTPROCESSOR,
  WORKER_IMAGE_NAMES.CLEARING_EXPORT,
]);

function textOrNull(value) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function imageRepositoryName(image) {
  const normalized = textOrNull(image);
  if (!normalized) return null;
  const withoutDigest = normalized.split("@", 1)[0];
  const lastSegment = withoutDigest.slice(withoutDigest.lastIndexOf("/") + 1);
  const tagIndex = lastSegment.lastIndexOf(":");
  return textOrNull(tagIndex >= 0 ? lastSegment.slice(0, tagIndex) : lastSegment);
}

function beeImageName(bee) {
  return imageRepositoryName(bee?.image);
}

// ── HTTP helper ───────────────────────────────────────────────────────────────

let cachedAuthHeader = null;
const profileAuthHeaderCache = new Map();

async function resolveAuthorizationHeader({ forceRefresh = false } = {}) {
  if (POCKETHIVE_AUTH_TOKEN.trim()) {
    return POCKETHIVE_AUTH_TOKEN.startsWith("Bearer ")
      ? POCKETHIVE_AUTH_TOKEN
      : `Bearer ${POCKETHIVE_AUTH_TOKEN}`;
  }
  if (!POCKETHIVE_AUTH_USERNAME.trim()) {
    return null;
  }
  if (forceRefresh) {
    cachedAuthHeader = null;
  }
  if (cachedAuthHeader) {
    return cachedAuthHeader;
  }
  const base = AUTH_SERVICE_URL.replace(/\/+$/, "");
  const response = await fetch(`${base}/api/auth/dev/login`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept": "application/json",
    },
    body: JSON.stringify({ username: POCKETHIVE_AUTH_USERNAME.trim() }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Auth login failed for ${base}/api/auth/dev/login: HTTP ${response.status}: ${text || "<empty>"}`);
  }
  const payload = text ? JSON.parse(text) : null;
  const accessToken = payload && typeof payload.accessToken === "string" ? payload.accessToken.trim() : "";
  if (!accessToken) {
    throw new Error("Auth login returned empty accessToken");
  }
  cachedAuthHeader = `Bearer ${accessToken}`;
  return cachedAuthHeader;
}

function needsPocketHiveAuth(url) {
  return String(url).startsWith(ORCH_URL) || String(url).startsWith(SM_URL);
}

function normalizeBaseUrl(value) {
  return String(value || "").replace(/\/+$/, "");
}

async function resolveProfileAuthorizationHeader(profile) {
  const token = String(profile?.authToken || "").trim();
  if (token) return token.startsWith("Bearer ") ? token : `Bearer ${token}`;
  const username = String(profile?.authUsername || "").trim();
  if (!username) return null;
  const baseUrl = normalizeBaseUrl(profile?.baseUrl || BASE_URL);
  const authBase = normalizeBaseUrl(profile?.authServiceBaseUrl || `${baseUrl}/auth-service`);
  const cacheKey = `${authBase}|${username}`;
  if (profileAuthHeaderCache.has(cacheKey)) return profileAuthHeaderCache.get(cacheKey);
  const response = await fetch(`${authBase}/api/auth/dev/login`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept": "application/json",
    },
    body: JSON.stringify({ username }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Auth login failed for ${authBase}/api/auth/dev/login: HTTP ${response.status}: ${text || "<empty>"}`);
  }
  const payload = text ? JSON.parse(text) : null;
  const accessToken = payload && typeof payload.accessToken === "string" ? payload.accessToken.trim() : "";
  if (!accessToken) throw new Error("Auth login returned empty accessToken");
  const header = `Bearer ${accessToken}`;
  profileAuthHeaderCache.set(cacheKey, header);
  return header;
}

async function httpJson(url, opts = {}) {
  const full = url.startsWith("http") ? url : `${ORCH_URL}${url}`;
  const canRefreshAuth = needsPocketHiveAuth(full) && Boolean(POCKETHIVE_AUTH_USERNAME.trim()) && !POCKETHIVE_AUTH_TOKEN.trim();
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), opts.timeoutMs || 30000);
    const authHeader = needsPocketHiveAuth(full) ? await resolveAuthorizationHeader({ forceRefresh: attempt > 0 }) : null;
    const init = {
      method: opts.method || "GET",
      headers: {
        "content-type": "application/json",
        ...(authHeader ? { Authorization: authHeader } : {}),
        ...(opts.headers || {}),
      },
      signal: controller.signal,
    };
    if (opts.body !== undefined) {
      // Raw Buffer/Uint8Array (e.g. zip upload) — send as-is, don't JSON-stringify
      if (Buffer.isBuffer(opts.body) || opts.body instanceof Uint8Array) {
        init.body = opts.body;
      } else {
        init.body = typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body);
      }
    }
    try {
      const res = await fetch(full, init);
      const text = await res.text();
      if (res.status === 401 && canRefreshAuth && attempt === 0) {
        continue;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status} for ${full}: ${text || "<empty>"}`);
      return text ? JSON.parse(text) : null;
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error(`HTTP auth retry failed for ${full}`);
}

async function httpText(url, opts = {}) {
  const full = url.startsWith("http") ? url : `${ORCH_URL}${url}`;
  const canRefreshAuth = needsPocketHiveAuth(full) && Boolean(POCKETHIVE_AUTH_USERNAME.trim()) && !POCKETHIVE_AUTH_TOKEN.trim();
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), opts.timeoutMs || 30000);
    const authHeader = needsPocketHiveAuth(full) ? await resolveAuthorizationHeader({ forceRefresh: attempt > 0 }) : null;
    const init = {
      method: opts.method || "GET",
      headers: {
        "accept": opts.accept || "text/plain",
        ...(opts.contentType ? { "content-type": opts.contentType } : {}),
        ...(authHeader ? { Authorization: authHeader } : {}),
        ...(opts.headers || {}),
      },
      signal: controller.signal,
    };
    if (opts.body !== undefined) {
      init.body = typeof opts.body === "string" || Buffer.isBuffer(opts.body) || opts.body instanceof Uint8Array
        ? opts.body
        : JSON.stringify(opts.body);
    }
    try {
      const res = await fetch(full, init);
      const text = await res.text();
      if (res.status === 401 && canRefreshAuth && attempt === 0) {
        continue;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status} for ${full}: ${text || "<empty>"}`);
      return text;
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error(`HTTP auth retry failed for ${full}`);
}

function idempotencyKey() {
  return crypto.randomUUID?.() || `idemp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function rabbitAuth() {
  const user = process.env.RABBITMQ_DEFAULT_USER || "guest";
  const pass = process.env.RABBITMQ_DEFAULT_PASS || "guest";
  return "Basic " + Buffer.from(`${user}:${pass}`).toString("base64");
}

function rabbitUrl() {
  const host = process.env.RABBITMQ_HOST || "localhost";
  const port = Number(process.env.RABBITMQ_PORT || "5672");
  const user = process.env.RABBITMQ_DEFAULT_USER || "guest";
  const pass = process.env.RABBITMQ_DEFAULT_PASS || "guest";
  const vhost = process.env.RABBITMQ_VHOST || "/";
  const encodedVhost = vhost === "/" ? "%2F" : encodeURIComponent(vhost);
  return `amqp://${encodeURIComponent(user)}:${encodeURIComponent(pass)}@${host}:${port}/${encodedVhost}`;
}

function controlExchange() {
  return process.env.POCKETHIVE_CONTROL_PLANE_EXCHANGE || "ph.control";
}

// ── MCP Server ────────────────────────────────────────────────────────────────

const SERVER_INFO = { name: "pockethive-bundles", version: "1.0.0" };
const server = new McpServer(SERVER_INFO);

const HANDLER_TIMEOUT_MS = 150000; // 2.5 min max per tool call
const APP_RESOURCE_MIME_TYPE = "text/html;profile=mcp-app";
const EVIDENCE_WIDGET_URI = "ui://pockethive/evidence-summary-v1.html";
const WORKFLOW_EVIDENCE_WIDGET_URI = "ui://pockethive/workflow-evidence-v1.html";
const TOOL_NAME_MODES = new Set(["underscore", "legacy", "both"]);
const MCP_TOOL_NAME_MODE = process.env.PH_MCP_TOOL_NAME_MODE || "underscore";
if (!TOOL_NAME_MODES.has(MCP_TOOL_NAME_MODE)) {
  throw new Error(`PH_MCP_TOOL_NAME_MODE must be one of ${[...TOOL_NAME_MODES].join(", ")}`);
}

function exposedToolName(name) {
  return String(name).replace(/[.-]/g, "_");
}

function toolRegistrationNames(name) {
  const exposed = exposedToolName(name);
  if (MCP_TOOL_NAME_MODE === "legacy") return [name];
  if (MCP_TOOL_NAME_MODE === "both" && exposed !== name) return [exposed, name];
  return [exposed];
}

function registerMcpTool(name, config, handler) {
  const names = toolRegistrationNames(name);
  for (const registeredName of names) {
    server.registerTool(registeredName, {
      ...config,
      title: registeredName,
      description: registeredName === name
        ? config.description
        : `${config.description} Legacy name: ${name}.`,
      _meta: {
        ...(config._meta || {}),
        "pockethive/originalToolName": name,
      },
    }, handler);
  }
}

function reg(name, desc, schema, handler, options = {}) {
  const { rawResult = false, ...toolOptions } = options;
  registerMcpTool(name, { title: name, description: desc, inputSchema: schema, ...toolOptions }, async (input = {}) => {
    try {
      const result = await Promise.race([
        handler(input),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error(`Tool '${name}' timed out after ${HANDLER_TIMEOUT_MS / 1000}s`)), HANDLER_TIMEOUT_MS)
        ),
      ]);
      if (rawResult) return result;
      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    } catch (err) {
      return { isError: true, content: [{ type: "text", text: `Error: ${err.message || err}` }] };
    }
  });
}

function cloneRegisteredServer() {
  const cloned = new McpServer(SERVER_INFO);
  cloned._registeredTools = { ...server._registeredTools };
  cloned._registeredResources = { ...server._registeredResources };
  cloned._registeredResourceTemplates = { ...server._registeredResourceTemplates };
  cloned._registeredPrompts = { ...server._registeredPrompts };
  if (Object.keys(cloned._registeredTools).length > 0) cloned.setToolRequestHandlers();
  if (Object.keys(cloned._registeredResources).length > 0 || Object.keys(cloned._registeredResourceTemplates).length > 0) {
    cloned.setResourceRequestHandlers();
  }
  if (Object.keys(cloned._registeredPrompts).length > 0) cloned.setPromptRequestHandlers();
  return cloned;
}

function jsonToolResult(data, extra = {}) {
  return {
    content: [{ type: "text", text: JSON.stringify(data, null, 2) }],
    ...extra,
  };
}

async function runWithTimeout(name, handler, input) {
  return await Promise.race([
    handler(input),
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error(`Tool '${name}' timed out after ${HANDLER_TIMEOUT_MS / 1000}s`)), HANDLER_TIMEOUT_MS)
    ),
  ]);
}

// ── Bundle management ─────────────────────────────────────────────────────────

reg("bundle.list", "List all scenario bundles in the configured bundle root", {}, async () => {
  const dir = getBundlesDir();
  if (!existsSync(dir)) return { bundles: [] };
  const bundles = readdirSync(dir, { withFileTypes: true })
    .filter(d => d.isDirectory() && !d.name.startsWith("."))
    .map(d => {
      const p = resolve(dir, d.name);
      return {
        name: d.name,
        hasScenario: existsSync(resolve(p, "scenario.yaml")),
        hasTemplates: existsSync(resolve(p, "templates")),
        hasDatasets: existsSync(resolve(p, "datasets")),
        hasSut: existsSync(resolve(p, "sut")),
        hasVariables: existsSync(resolve(p, "variables.yaml")),
        hasReadme: existsSync(resolve(p, "README.md")),
      };
    });
  return { bundles };
});

reg("bundle.read", "Read a file from a bundle (scenario.yaml, template, dataset, etc.)", {
  bundle: BUNDLE_ARG,
  file: z.string().describe("Relative path within the bundle, e.g. 'scenario.yaml' or 'templates/http/default/my-call.yaml'"),
}, async ({ bundle, file }) => {
  const path = resolve(bundleDir(bundle), file);
  ensureInside(bundleDir(bundle), path);
  if (!existsSync(path)) throw new Error(`File not found: ${path}`);
  const content = readFileSync(path, "utf8");
  const MAX = 100_000;
  return {
    path,
    content: content.length > MAX ? content.slice(0, MAX) + `\n...[truncated at ${MAX} chars]` : content,
    truncated: content.length > MAX,
  };
});

reg("bundle.scaffold", "Scaffold a new bundle directory with a canonical scenario.yaml, optional template stub, and optional SUT definition.", {
  bundleId: z.string().describe("Bundle id — folder name and scenario id (lowercase, hyphens only)"),
  pattern: z.enum(["rest-simple", "rest-rbuilder", "sequence", "tcp-simple", "blank"]),
  sutType: z.enum(["wiremock-local", "tcp-mock-local", "none"]).default("none"),
}, async ({ bundleId, pattern, sutType }) => {
  const targetBundleDir = bundleDir(bundleId);
  if (existsSync(targetBundleDir)) throw new Error(`Bundle '${bundleId}' already exists at ${targetBundleDir}`);
  mkdirSync(targetBundleDir, { recursive: true });

  const sutBaseUrl = sutType === "wiremock-local" ? "http://wiremock:8080"
    : sutType === "tcp-mock-local" ? "tcp://tcp-mock-server:8080"
    : "http://localhost:8080";
  const sutProtocol = sutType === "tcp-mock-local" ? "TCP" : "HTTP";

  const beesByPattern = {
    "rest-simple": [
      { role: "generator", image: "generator:latest", work: { out: { out: "proc" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 10 } }, message: { bodyType: "SIMPLE", body: "{}" } } },
      { role: "processor", image: "processor:latest", work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}", mode: "THREAD_COUNT", threadCount: 5 } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "rest-rbuilder": [
      { role: "generator", image: "generator:latest", work: { out: { out: "build" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 10 } },
          message: { bodyType: "SIMPLE", body: "{}", headers: { "x-ph-call-id": "my-call", "x-ph-service-id": "default" } } } },
      { role: "request-builder", image: "request-builder:latest", work: { in: { in: "build" }, out: { out: "proc" } },
        config: { templateRoot: "/app/scenario/templates/http", serviceId: "default" } },
      { role: "processor", image: "processor:latest", work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}", mode: "THREAD_COUNT", threadCount: 5 } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "sequence": [
      { role: "generator", image: "generator:latest", work: { out: { out: "seq" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 5 } }, message: { bodyType: "SIMPLE", body: "{}" } } },
      { role: "http-sequence", image: "http-sequence:latest", work: { in: { in: "seq" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}" } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "tcp-simple": [
      { role: "generator", image: "generator:latest", work: { out: { out: "proc" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 10 } }, message: { bodyType: "SIMPLE", body: "PING" } } },
      { role: "processor", image: "processor:latest", work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}", mode: "THREAD_COUNT", threadCount: 5, tcpTransport: { type: "socket" } } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "blank": [],
  };

  // Build scenario.yaml as plain text to preserve YAML formatting
  const { stringify } = await import("yaml").catch(() => ({ stringify: JSON.stringify }));
  const scenario = {
    id: bundleId,
    name: bundleId,
    description: `Scaffolded by bundle.scaffold (pattern: ${pattern})`,
    template: { image: "swarm-controller:latest", bees: beesByPattern[pattern] },
  };
  writeFileSync(resolve(targetBundleDir, "scenario.yaml"), stringify(scenario), "utf8");

  if (pattern === "rest-rbuilder") {
    mkdirSync(resolve(targetBundleDir, "templates", "http", "default"), { recursive: true });
    const tpl = `serviceId: default\ncallId: my-call\nprotocol: HTTP\nmethod: POST\npathTemplate: /api/endpoint\nbodyTemplate: |\n  {}\nheadersTemplate:\n  Content-Type: application/json\n`;
    writeFileSync(resolve(targetBundleDir, "templates", "http", "default", "my-call.yaml"), tpl, "utf8");
  }

  if (sutType !== "none") {
    mkdirSync(resolve(targetBundleDir, "sut", sutType), { recursive: true });
    const sut = `id: ${sutType}\nname: ${sutType}\ntype: sandbox\nendpoints:\n  target:\n    kind: ${sutProtocol}\n    baseUrl: ${sutBaseUrl}\n`;
    writeFileSync(resolve(targetBundleDir, "sut", sutType, "sut.yaml"), sut, "utf8");
  }

  return { created: true, bundleId, path: targetBundleDir, pattern, sutType };
});

// ── Novice bundle wizard ─────────────────────────────────────────────────────

const WIZARD_SESSIONS = new Map();
const WIZARD_SESSION_TTL_MS = 2 * 60 * 60 * 1000;
const WIZARD_CANONICAL_FIELDS = [
  "bundleId",
  "protocol",
  "target",
  "targetBaseUrl",
  "endpoints",
  "requestBody",
  "tcpPayload",
  "defaultRatePerSec",
  "nftRatePerSec",
  "trafficShape",
  "runDuration",
  "nftDuration",
  "dataSource",
  "csvColumns",
  "redisLists",
  "redisOutput",
  "auth",
  "authTokenUrl",
  "authClientId",
  "authSecretSource",
  "authSecretEnvVar",
  "sutNftUrl",
  "sutDouble",
  "mockEndpoints",
  "resultRules",
  "resultCodePattern",
  "successCodes",
  "performanceObjective",
  "clickhouse",
  "grafanaDashboard",
  "docs",
];

const WIZARD_ALIASES = {
  endpoint: "endpoints",
  ratePerSec: "defaultRatePerSec",
  rate: "defaultRatePerSec",
  nft_rate: "nftRatePerSec",
  traffic_shape: "trafficShape",
  run_duration: "runDuration",
  nft_duration: "nftDuration",
  data_source: "dataSource",
  csv_columns: "csvColumns",
  redis_lists: "redisLists",
  redis_output: "redisOutput",
  auth_token_url: "authTokenUrl",
  auth_client_id: "authClientId",
  auth_secret_source: "authSecretSource",
  auth_secret_env_var: "authSecretEnvVar",
  sut_base_url: "targetBaseUrl",
  sut_nft_url: "sutNftUrl",
  sut_double: "sutDouble",
  mock_endpoints: "mockEndpoints",
  result_rules: "resultRules",
  result_code_pattern: "resultCodePattern",
  success_codes: "successCodes",
  performance_objective: "performanceObjective",
  grafana_dashboard: "grafanaDashboard",
};

const WIZARD_QUESTION_IDS = [...WIZARD_CANONICAL_FIELDS, ...Object.keys(WIZARD_ALIASES)];
const WIZARD_PROTOCOLS = ["REST", "TCP", "SEQUENCE"];
const WIZARD_TARGETS = ["wiremock-local", "tcp-mock-local", "external"];
const WIZARD_DATA_SOURCES = ["SCHEDULER", "CSV_DATASET", "REDIS_DATASET"];
const WIZARD_TRAFFIC_SHAPES = ["smoke", "ramp_steady", "spike", "soak", "flat"];
const WIZARD_HTTP_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
const WIZARD_MUTATING_HTTP_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);
const WIZARD_AUTH_TYPES = [
  "none",
  "oauth2_client_credentials",
  "bearer_token_static",
  "basic_auth",
  "api_key",
  "hmac",
  "aws_sig_v4",
  "iso8583_mac",
  "mtls",
];
const WIZARD_SUT_DOUBLES = ["real_system", "wiremock", "tcp_mock", "wiremock_and_tcp"];
const WIZARD_CLICKHOUSE_MODES = ["yes_for_nft_only", "yes_always", "no"];
const WIZARD_GRAFANA_DASHBOARDS = ["rtt_overview", "tx_outcomes", "quality", "pipeline_observability", "none"];

const WIZARD_QUESTIONS = {
  bundleId: {
    prompt: "What should the bundle id be? Use lowercase letters, numbers, and hyphens, for example onboarding-smoke.",
  },
  protocol: {
    prompt: "Which protocol should this bundle exercise?",
    options: WIZARD_PROTOCOLS,
  },
  target: {
    prompt: "Which target should the generated bundle bind to?",
    options: WIZARD_TARGETS,
  },
  targetBaseUrl: {
    prompt: "What is the external target base URL? Use http(s):// for REST/SEQUENCE or tcp:// for TCP.",
  },
  endpoints: {
    prompt: "Which endpoint(s) should be called? Use METHOD /path, one per line, for example POST /api/onboarding.",
  },
  requestBody: {
    prompt: "What request body/template should be sent for this endpoint?",
  },
  tcpPayload: {
    prompt: "What TCP payload should the generator send?",
  },
  defaultRatePerSec: {
    prompt: "What default generation rate should be used, in requests/messages per second?",
  },
  trafficShape: {
    prompt: "What traffic shape should this scenario use?",
    options: WIZARD_TRAFFIC_SHAPES,
  },
  runDuration: {
    prompt: "How long should the default profile run, for example 60s or 5m?",
  },
  dataSource: {
    prompt: "Where does test data come from?",
    options: WIZARD_DATA_SOURCES,
  },
  csvColumns: {
    prompt: "What columns should the sample CSV dataset contain?",
  },
  redisLists: {
    prompt: "Which Redis dataset list names should this scenario read from?",
  },
  auth: {
    prompt: "Does the target require authentication?",
    options: WIZARD_AUTH_TYPES,
  },
  authTokenUrl: {
    prompt: "What token URL should the OAuth2 profile use?",
  },
  authClientId: {
    prompt: "What client id, username, or key id should the auth profile use?",
  },
  authSecretEnvVar: {
    prompt: "Which environment variable provides the auth secret at runtime?",
  },
  sutDouble: {
    prompt: "Is the target real, WireMock, TCP mock, or both?",
    options: WIZARD_SUT_DOUBLES,
  },
  resultRules: {
    prompt: "Is there a business result code to extract from the response?",
    options: ["yes", "no"],
  },
  resultCodePattern: {
    prompt: "What regex extracts the business result code? It must have one capture group.",
  },
  successCodes: {
    prompt: "Which business result code values count as success?",
  },
};

function canonicalWizardQuestionId(questionId) {
  const id = WIZARD_ALIASES[questionId] || questionId;
  if (!WIZARD_CANONICAL_FIELDS.includes(id)) throw new Error(`Unknown wizard questionId '${questionId}'`);
  return id;
}

function parseWizardList(value) {
  if (value === undefined || value === null || value === "") return [];
  if (Array.isArray(value)) return value.map(v => String(v).trim()).filter(Boolean);
  return String(value).split(/\r?\n|,/).map(v => v.trim()).filter(Boolean);
}

function normalizeWizardBoolean(value) {
  if (typeof value === "boolean") return value ? "yes" : "no";
  const normalized = String(value).trim().toLowerCase();
  if (["yes", "y", "true", "1"].includes(normalized)) return "yes";
  if (["no", "n", "false", "0"].includes(normalized)) return "no";
  throw new Error("Answer must be yes/no or boolean");
}

function normalizeWizardEnum(questionId, answer, allowed) {
  const value = String(answer).trim();
  if (!allowed.includes(value)) throw new Error(`${questionId} must be one of: ${allowed.join(", ")}`);
  return value;
}

function slug(value, fallback = "main") {
  const s = String(value || "").trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  return s || fallback;
}

function plainObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function firstDefined(...values) {
  return values.find(value => value !== undefined);
}

function jsonValueOrString(value) {
  if (typeof value !== "string") return value;
  const trimmed = value.trim();
  if (!trimmed) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function copyDefinedFields(target, source, fields) {
  for (const field of fields) {
    if (source[field] !== undefined) target[field] = source[field];
  }
  return target;
}

function normalizeWizardEndpoint(entry, index = 0) {
  if (typeof entry === "object" && entry !== null) {
    const method = String(entry.method || "").trim().toUpperCase();
    const path = String(entry.path || "").trim();
    if (!method || !path.startsWith("/")) throw new Error("endpoint object must include method and an absolute /path");
    return copyDefinedFields({
      method,
      path,
      callId: slug(entry.callId || `${method}-${path}` || `call-${index + 1}`, `call-${index + 1}`),
      description: entry.description ? String(entry.description).trim() : "",
      bodyTemplate: entry.bodyTemplate !== undefined ? String(entry.bodyTemplate) : undefined,
    }, entry, [
      "headers",
      "query",
      "queryParameters",
      "weight",
      "expectedStatus",
      "assertions",
      "retry",
      "continueOnNon2xx",
      "extracts",
      "captures",
      "serviceId",
      "mock",
    ]);
  }
  const text = String(entry).trim();
  const match = text.match(/^([A-Za-z]+)\s+(\S+)(?:\s+[-–]\s+(.+))?$/);
  if (!match || !match[2].startsWith("/")) throw new Error("endpoint must be in the form METHOD /path");
  return {
    method: match[1].toUpperCase(),
    path: match[2],
    callId: slug(`${match[1]}-${match[2]}`, `call-${index + 1}`),
    description: match[3] ? match[3].trim() : "",
  };
}

function normalizeWizardEndpoints(answer) {
  const entries = Array.isArray(answer)
    ? answer
    : typeof answer === "object" && answer !== null
      ? [answer]
      : String(answer).split(/\r?\n/).map(v => v.trim()).filter(Boolean);
  return entries.map((entry, index) => normalizeWizardEndpoint(entry, index));
}

function normalizeWizardMockEndpoint(entry, index = 0) {
  if (typeof entry === "object" && entry !== null) {
    if (entry.method || entry.path) {
      const endpoint = normalizeWizardEndpoint(entry, index);
      const responseBody = firstDefined(
        entry.responseBody,
        entry.jsonBody,
        entry.body,
        entry.response?.jsonBody,
        entry.response?.body,
        entry.mock?.responseBody,
        entry.mock?.jsonBody,
        entry.mock?.body,
        entry.mock?.response?.jsonBody,
        entry.mock?.response?.body,
      );
      return copyDefinedFields({
        ...endpoint,
        status: Number(entry.status ?? entry.response?.status ?? entry.mock?.status ?? entry.mock?.response?.status ?? 200),
        responseBody: responseBody === undefined ? { ok: true, callId: endpoint.callId } : jsonValueOrString(responseBody),
      }, entry, ["requestHeaders", "queryParameters", "bodyPatterns", "priority", "responseHeaders"]);
    }
    return entry;
  }
  const endpoint = normalizeWizardEndpoint(entry, index);
  return { ...endpoint, status: 200, responseBody: { ok: true, callId: endpoint.callId } };
}

function normalizeWizardAnswer(questionId, answer) {
  questionId = canonicalWizardQuestionId(questionId);
  if (answer === undefined || answer === null) return undefined;
  if (["defaultRatePerSec", "nftRatePerSec"].includes(questionId)) {
    const value = Number(answer);
    if (!Number.isFinite(value) || value <= 0) throw new Error(`${questionId} must be a positive number`);
    return value;
  }
  if (questionId === "endpoints") {
    return normalizeWizardEndpoints(answer);
  }
  if (questionId === "protocol") {
    const value = String(answer).trim().toUpperCase();
    if (value === "HTTP") return "REST";
    return normalizeWizardEnum(questionId, value, WIZARD_PROTOCOLS);
  }
  if (questionId === "target") {
    return normalizeWizardEnum(questionId, answer, WIZARD_TARGETS);
  }
  if (questionId === "dataSource") {
    return normalizeWizardEnum(questionId, String(answer).trim().toUpperCase(), WIZARD_DATA_SOURCES);
  }
  if (questionId === "trafficShape") {
    return normalizeWizardEnum(questionId, String(answer).trim().toLowerCase(), WIZARD_TRAFFIC_SHAPES);
  }
  if (questionId === "auth") {
    return normalizeWizardEnum(questionId, String(answer).trim().toLowerCase(), WIZARD_AUTH_TYPES);
  }
  if (questionId === "authSecretSource") {
    return normalizeWizardEnum(questionId, String(answer).trim().toLowerCase(), ["env_var", "file"]);
  }
  if (questionId === "sutDouble") {
    return normalizeWizardEnum(questionId, String(answer).trim().toLowerCase(), WIZARD_SUT_DOUBLES);
  }
  if (["redisOutput", "resultRules", "docs"].includes(questionId)) {
    return normalizeWizardBoolean(answer);
  }
  if (questionId === "clickhouse") {
    return normalizeWizardEnum(questionId, String(answer).trim().toLowerCase(), WIZARD_CLICKHOUSE_MODES);
  }
  if (questionId === "grafanaDashboard") {
    return normalizeWizardEnum(questionId, String(answer).trim().toLowerCase(), WIZARD_GRAFANA_DASHBOARDS);
  }
  if (["csvColumns", "redisLists", "successCodes"].includes(questionId)) {
    return parseWizardList(answer);
  }
  if (questionId === "mockEndpoints") {
    const entries = Array.isArray(answer)
      ? answer
      : typeof answer === "object" && answer !== null
        ? [answer]
        : String(answer).split(/\r?\n/).map(v => v.trim()).filter(Boolean);
    return entries.map((entry, index) => normalizeWizardMockEndpoint(entry, index));
  }
  return String(answer).trim();
}

function applyWizardAnswers(target, source = {}) {
  for (const [rawId, value] of Object.entries(source)) {
    if (value === undefined || rawId === "intent") continue;
    if (!WIZARD_CANONICAL_FIELDS.includes(rawId) && !WIZARD_ALIASES[rawId]) continue;
    const id = canonicalWizardQuestionId(rawId);
    target[id] = normalizeWizardAnswer(id, value);
  }
}

function wizardAnswersWithDefaults(answers) {
  const protocol = answers.protocol;
  const target = answers.target;
  const defaultSutDouble = target === "external"
    ? "real_system"
    : target === "tcp-mock-local"
      ? "tcp_mock"
      : "wiremock";
  return {
    dataSource: "SCHEDULER",
    trafficShape: "ramp_steady",
    runDuration: "60s",
    auth: "none",
    authSecretSource: "env_var",
    sutDouble: defaultSutDouble,
    redisOutput: "no",
    resultRules: "no",
    clickhouse: "yes_for_nft_only",
    grafanaDashboard: "rtt_overview",
    docs: "yes",
    ...answers,
    protocol,
    target,
  };
}

function wizardAssumptions(rawAnswers, answers) {
  const assumptions = [];
  for (const [field, value] of Object.entries(wizardAnswersWithDefaults({}))) {
    if (rawAnswers[field] === undefined && answers[field] !== undefined) assumptions.push(`${field}=${value}`);
  }
  if (answers.mockEndpoints?.length === undefined && answers.sutDouble !== "real_system" && answers.endpoints?.length) {
    assumptions.push("mockEndpoints generated from endpoints");
  }
  if (answers.dataSource === "CSV_DATASET") {
    assumptions.push("CSV sample artifact generated and wired as generator CSV_DATASET input");
  }
  return assumptions;
}

function wizardMissingQuestions(answers) {
  answers = wizardAnswersWithDefaults(answers);
  const missing = [];
  for (const id of ["bundleId", "protocol", "target", "defaultRatePerSec"]) {
    if (answers[id] === undefined || answers[id] === "") missing.push(id);
  }
  if (answers.target === "external" && !answers.targetBaseUrl) missing.push("targetBaseUrl");
  if (answers.protocol === "REST" || answers.protocol === "SEQUENCE") {
    if (!answers.endpoints?.length) missing.push("endpoints");
    const firstEndpoint = answers.endpoints?.[0];
    if (firstEndpoint && ["POST", "PUT", "PATCH"].includes(firstEndpoint.method) && !firstEndpoint.bodyTemplate && !answers.requestBody) {
      missing.push("requestBody");
    }
  }
  if (answers.protocol === "TCP" && !answers.tcpPayload) missing.push("tcpPayload");
  if (answers.dataSource === "CSV_DATASET" && !answers.csvColumns?.length) missing.push("csvColumns");
  if (answers.dataSource === "REDIS_DATASET" && !answers.redisLists?.length) missing.push("redisLists");
  if (answers.auth === "oauth2_client_credentials") {
    for (const id of ["authTokenUrl", "authClientId", "authSecretEnvVar"]) {
      if (!answers[id]) missing.push(id);
    }
  } else if (answers.auth !== "none") {
    if (!answers.authSecretEnvVar) missing.push("authSecretEnvVar");
  }
  if (answers.resultRules === "yes") {
    if (!answers.resultCodePattern) missing.push("resultCodePattern");
    if (!answers.successCodes?.length) missing.push("successCodes");
  }
  return missing;
}

function validateWizardAnswers(answers) {
  answers = wizardAnswersWithDefaults(answers);
  const errors = [];
  if (answers.bundleId && !/^[a-z0-9][a-z0-9-]*$/.test(answers.bundleId)) {
    errors.push("bundleId must use lowercase letters, numbers, and hyphens only");
  }
  if (answers.protocol === "TCP" && answers.target === "wiremock-local") {
    errors.push("TCP bundles cannot target wiremock-local; use tcp-mock-local or external");
  }
  if ((answers.protocol === "REST" || answers.protocol === "SEQUENCE") && answers.target === "tcp-mock-local") {
    errors.push(`${answers.protocol} bundles cannot target tcp-mock-local; use wiremock-local or external`);
  }
  if (answers.target === "external" && answers.targetBaseUrl) {
    try { new URL(answers.targetBaseUrl); } catch { errors.push("targetBaseUrl must be a valid URL"); }
  }
  if (answers.protocol === "TCP" && answers.auth && !["none", "iso8583_mac", "mtls"].includes(answers.auth)) {
    errors.push("TCP wizard bundles support auth none, iso8583_mac, or mtls only");
  }
  if (Array.isArray(answers.endpoints)) {
    const callIds = new Set();
    for (const [index, endpoint] of answers.endpoints.entries()) {
      const prefix = `endpoint ${index + 1}`;
      if (!WIZARD_HTTP_METHODS.includes(String(endpoint.method || "").toUpperCase())) {
        errors.push(`${prefix} method must be one of: ${WIZARD_HTTP_METHODS.join(", ")}`);
      }
      if (!String(endpoint.path || "").startsWith("/")) {
        errors.push(`${prefix} path must be an absolute /path`);
      }
      const callId = String(endpoint.callId || "").trim();
      if (!callId) {
        errors.push(`${prefix} callId is required`);
      } else if (callIds.has(callId)) {
        errors.push(`endpoint callId '${callId}' is duplicated`);
      }
      callIds.add(callId);
      for (const field of ["headers", "query", "queryParameters"]) {
        if (endpoint[field] !== undefined && !plainObject(endpoint[field])) {
          errors.push(`${prefix} ${field} must be an object`);
        }
      }
      if (endpoint.weight !== undefined && (!Number.isFinite(Number(endpoint.weight)) || Number(endpoint.weight) <= 0)) {
        errors.push(`${prefix} weight must be a positive number`);
      }
      if (endpoint.extracts !== undefined && !Array.isArray(endpoint.extracts)) {
        errors.push(`${prefix} extracts must be an array`);
      }
      if (endpoint.retry !== undefined && !plainObject(endpoint.retry)) {
        errors.push(`${prefix} retry must be an object`);
      }
    }
  }
  if (answers.resultCodePattern) {
    try {
      const regex = new RegExp(answers.resultCodePattern);
      if (regex.exec("")?.length > 2) errors.push("resultCodePattern must have at most one capture group");
    } catch (e) {
      errors.push(`resultCodePattern must be a valid regex: ${e.message}`);
    }
  }
  if (answers.resultRules === "yes" && answers.resultCodePattern && answers.successCodes?.length && ["wiremock", "wiremock_and_tcp"].includes(answers.sutDouble)) {
    const resultField = resultFieldFromPattern(answers.resultCodePattern);
    if (resultField) {
      const allowed = new Set(answers.successCodes.map(String));
      for (const endpoint of wizardMockEndpoints(answers).filter(mock => mock.method && mock.path)) {
        const responseBody = defaultWizardMockResponseBody(endpoint, answers);
        if (plainObject(responseBody)) {
          if (responseBody[resultField] === undefined) {
            errors.push(`mock endpoint '${endpoint.callId}' responseBody must include '${resultField}' for resultCodePattern`);
          } else if (!allowed.has(String(responseBody[resultField]))) {
            errors.push(`mock endpoint '${endpoint.callId}' responseBody.${resultField} must match one of successCodes: ${answers.successCodes.join(", ")}`);
          }
        }
      }
    }
  }
  return errors;
}

function wizardNextQuestion(answers) {
  const id = wizardMissingQuestions(answers)[0];
  return id ? { id, ...WIZARD_QUESTIONS[id] } : null;
}

function buildHumanCheckpoint(answers, missing) {
  if (!missing.length) return null;
  const agentFilled = Object.keys(answers).length;
  return {
    message: "Before generating the bundle, please confirm the following with the user:",
    questions: missing.map(id => ({
      id,
      prompt: WIZARD_QUESTIONS[id]?.prompt,
      options: WIZARD_QUESTIONS[id]?.options || null,
    })),
    agentNote: `Agent pre-filled ${agentFilled} field(s) from context. Only these ${missing.length} field(s) require human confirmation.`,
  };
}

function wizardPattern(answers) {
  answers = wizardAnswersWithDefaults(answers);
  if (answers.protocol === "TCP") return "tcp-simple";
  if (answers.protocol === "SEQUENCE") return "sequence";
  if (answers.dataSource === "REDIS_DATASET" && answers.redisOutput === "yes") return "redis-loop";
  return "rest-rbuilder";
}

function wizardTarget(answers) {
  answers = wizardAnswersWithDefaults(answers);
  if (answers.target === "external") {
    return {
      id: "external-target",
      kind: answers.protocol === "TCP" ? "TCP" : "HTTP",
      baseUrl: answers.targetBaseUrl,
      endpointKey: answers.protocol === "TCP" ? "tcp-server" : "default",
    };
  }
  if (answers.target === "tcp-mock-local") {
    return { id: "tcp-mock-local", kind: "TCP", baseUrl: "tcp://tcp-mock-server:8080", endpointKey: "tcp-server" };
  }
  return { id: "wiremock-local", kind: "HTTP", baseUrl: "http://wiremock:8080", endpointKey: "default" };
}

function wizardMockEndpoints(answers) {
  answers = wizardAnswersWithDefaults(answers);
  if (answers.mockEndpoints?.length) return answers.mockEndpoints;
  if (answers.protocol === "TCP") {
    return [{ id: "tcp-request", request: answers.tcpPayload, response: "OK" }];
  }
  return (answers.endpoints || []).map((endpoint, index) => ({
    ...endpoint,
    status: endpoint.mock?.status || endpoint.expectedStatus || 200,
    responseBody: defaultWizardMockResponseBody(endpoint, answers, index),
  }));
}

function resultFieldFromPattern(pattern) {
  const text = String(pattern || "");
  for (const candidate of [
    /\\?["']([A-Za-z_][A-Za-z0-9_-]*)\\?["']\s*(?:\\s\*)?\s*:/,
  ]) {
    const match = text.match(candidate);
    if (match?.[1]) return match[1];
  }
  return null;
}

function defaultWizardMockResponseBody(endpoint, answers, index = 0) {
  const explicit = firstDefined(
    endpoint.mock?.responseBody,
    endpoint.mock?.jsonBody,
    endpoint.mock?.body,
    endpoint.mock?.response?.jsonBody,
    endpoint.mock?.response?.body,
    endpoint.responseBody,
    endpoint.jsonBody,
    endpoint.body,
    endpoint.response?.jsonBody,
    endpoint.response?.body,
  );
  if (explicit !== undefined) return jsonValueOrString(explicit);
  const callId = endpoint.callId || `call-${index + 1}`;
  const body = { ok: true, callId };
  if (answers?.resultRules === "yes" && answers?.resultCodePattern && answers?.successCodes?.length) {
    const field = resultFieldFromPattern(answers.resultCodePattern);
    if (field) body[field] = String(answers.successCodes[0]);
  }
  return body;
}

function wizardWireMockResponse(endpoint, answers = {}) {
  const body = defaultWizardMockResponseBody(endpoint, answers);
  const response = {
    status: endpoint.status || endpoint.response?.status || endpoint.mock?.status || endpoint.mock?.response?.status || 200,
    headers: endpoint.responseHeaders || endpoint.response?.headers || endpoint.mock?.responseHeaders || endpoint.mock?.response?.headers || { "Content-Type": "application/json" },
  };
  if (typeof body === "string") {
    response.body = body;
  } else {
    response.jsonBody = body;
  }
  return response;
}

function wizardAuthType(answers) {
  const map = {
    oauth2_client_credentials: "OAUTH2_CLIENT_CREDENTIALS",
    bearer_token_static: "STATIC_TOKEN",
    basic_auth: "BASIC_AUTH",
    api_key: "API_KEY",
    hmac: "HMAC_SIGNATURE",
    aws_sig_v4: "AWS_SIGNATURE_V4",
    iso8583_mac: "ISO8583_MAC",
    mtls: "TLS_CLIENT_CERT",
  };
  return map[answers.auth];
}

function wizardAuthProfiles(answers) {
  const profileId = "wizard-auth";
  const secretRef = answers.authSecretSource === "file"
    ? { file: answers.authSecretEnvVar }
    : { env: answers.authSecretEnvVar };
  const profile = { type: wizardAuthType(answers), storage: { mode: answers.auth === "oauth2_client_credentials" ? "REDIS" : "NONE" } };
  if (answers.auth === "oauth2_client_credentials") {
    profile.storage.tokenKey = `${answers.bundleId}.wizard-auth`;
    profile.refresh = { refreshAheadSeconds: 60, leaseSeconds: 15 };
    profile.tokenUrl = answers.authTokenUrl;
    profile.clientId = answers.authClientId;
    profile.clientSecret = secretRef;
  } else if (answers.auth === "bearer_token_static") {
    profile.token = secretRef;
  } else if (answers.auth === "basic_auth") {
    profile.username = answers.authClientId || "wizard-user";
    profile.password = secretRef;
  } else if (answers.auth === "api_key") {
    profile.key = secretRef;
    profile.headerName = "X-Api-Key";
  } else if (answers.auth === "hmac") {
    profile.secretKey = secretRef;
  } else if (answers.auth === "aws_sig_v4") {
    profile.accessKeyId = answers.authClientId || "WIZARD_ACCESS_KEY";
    profile.secretAccessKey = secretRef;
    profile.region = "eu-west-1";
    profile.service = "execute-api";
  } else if (answers.auth === "iso8583_mac") {
    profile.macKey = secretRef;
  } else if (answers.auth === "mtls") {
    profile.keyStorePath = "/run/secrets/client.p12";
    profile.keyStorePassword = secretRef;
  }
  return { [profileId]: profile };
}

function wizardAuthRef(answers) {
  const applyAs = answers.protocol === "TCP"
    ? answers.auth === "mtls" ? "MTLS_CLIENT_CERT" : "ISO8583_MAC_FIELD"
    : answers.auth === "bearer_token_static" || answers.auth === "oauth2_client_credentials"
      ? "HTTP_AUTHORIZATION_BEARER"
      : answers.auth === "api_key"
        ? "HTTP_HEADER"
        : "HTTP_HEADER";
  return { profileId: "wizard-auth", applyAs };
}

function wizardEndpointQuery(endpoint) {
  const query = plainObject(endpoint.query) ? endpoint.query : null;
  return query || {};
}

function wizardEndpointPathTemplate(endpoint) {
  const query = wizardEndpointQuery(endpoint);
  const entries = Object.entries(query).filter(([, value]) => value !== undefined && value !== null && value !== "");
  if (!entries.length) return endpoint.path;
  const joiner = endpoint.path.includes("?") ? "&" : "?";
  return `${endpoint.path}${joiner}${entries.map(([key, value]) => `${key}=${String(value)}`).join("&")}`;
}

function wizardTemplateHeaders(endpoint, includeJsonDefault = true) {
  return {
    ...(includeJsonDefault ? { "content-type": "application/json" } : {}),
    ...(plainObject(endpoint.headers) ? endpoint.headers : {}),
  };
}

function wireMockMatcherMap(value) {
  const source = plainObject(value) ? value : {};
  const mapped = {};
  for (const [key, matcher] of Object.entries(source)) {
    mapped[key] = plainObject(matcher) ? matcher : { equalTo: String(matcher) };
  }
  return mapped;
}

function objectJsonPathMatchers(value, prefix = "$") {
  if (!plainObject(value)) return [];
  const patterns = [];
  for (const [key, child] of Object.entries(value)) {
    if (child === undefined || child === null) continue;
    const path = `${prefix}.${key}`;
    if (plainObject(child)) {
      patterns.push(...objectJsonPathMatchers(child, path));
    } else if (Array.isArray(child)) {
      patterns.push({ matchesJsonPath: path });
    } else if (typeof child === "string" && child.includes("{{")) {
      patterns.push({ matchesJsonPath: path });
    } else {
      patterns.push({ matchesJsonPath: { expression: path, equalTo: String(child) } });
    }
  }
  return patterns;
}

function generatedBodyPatterns(endpoint, answers) {
  const method = String(endpoint.method || "").toUpperCase();
  if (!WIZARD_MUTATING_HTTP_METHODS.has(method)) return [];
  const bodyTemplate = endpoint.bodyTemplate || answers?.requestBody || "";
  if (!bodyTemplate.trim()) return [];
  try {
    const parsed = JSON.parse(bodyTemplate);
    const jsonPathMatchers = objectJsonPathMatchers(parsed);
    if (jsonPathMatchers.length) return jsonPathMatchers;
  } catch {
    // Template strings can contain placeholders; fall back to a conservative body matcher.
  }
  return [{ contains: bodyTemplate.trim().slice(0, 120) }];
}

function wizardWireMockRequest(endpoint, answers = {}) {
  const mock = plainObject(endpoint.mock) ? endpoint.mock : {};
  const request = { method: endpoint.method, urlPath: endpoint.path };
  const query = plainObject(endpoint.queryParameters)
    ? endpoint.queryParameters
    : plainObject(mock.queryParameters)
      ? mock.queryParameters
      : wizardEndpointQuery(endpoint);
  const headers = plainObject(endpoint.requestHeaders)
    ? endpoint.requestHeaders
    : plainObject(mock.requestHeaders)
      ? mock.requestHeaders
      : null;
  const bodyPatterns = Array.isArray(endpoint.bodyPatterns)
    ? endpoint.bodyPatterns
    : Array.isArray(mock.bodyPatterns)
      ? mock.bodyPatterns
      : generatedBodyPatterns(endpoint, answers);
  if (plainObject(query) && Object.keys(query).length) request.queryParameters = wireMockMatcherMap(query);
  if (plainObject(headers) && Object.keys(headers).length) request.headers = wireMockMatcherMap(headers);
  if (bodyPatterns?.length) request.bodyPatterns = bodyPatterns;
  return request;
}

function wizardSequenceStep(endpoint, index) {
  const step = { id: `step-${index + 1}-${endpoint.callId}`, callId: endpoint.callId };
  for (const field of ["serviceId", "continueOnNon2xx", "retry"]) {
    if (endpoint[field] !== undefined) step[field] = endpoint[field];
  }
  const extracts = Array.isArray(endpoint.extracts)
    ? endpoint.extracts
    : Array.isArray(endpoint.captures)
      ? endpoint.captures
      : [];
  if (extracts.length) step.extracts = extracts;
  return step;
}

function wizardTopology(bees) {
  const produced = new Map();
  const edges = [];
  for (const bee of bees) {
    const role = bee.role;
    for (const [port, suffix] of Object.entries(bee.work?.out || {})) {
      if (typeof suffix === "string") produced.set(suffix, { role, port });
    }
  }
  for (const bee of bees) {
    const role = bee.role;
    for (const [port, suffix] of Object.entries(bee.work?.in || {})) {
      const from = produced.get(suffix);
      if (from) edges.push({ id: `e${edges.length + 1}`, from, to: { role, port } });
    }
  }
  return { version: 1, edges };
}

function wizardFlowDocument(session, answers, target) {
  const endpointRows = (answers.endpoints || []).map((endpoint, index) =>
    `| ${index + 1} | ${endpoint.callId} | ${endpoint.method} | ${endpoint.path} | ${endpoint.description || "Generated by wizard"} |`
  );
  return [
    `# ${answers.bundleId} Flow`,
    "",
    `Intent: ${session.intent}`,
    "",
    "## Runtime Contract Source",
    "",
    "- Scenario shape follows `docs/scenarios/SCENARIO_CONTRACT.md` and `io.pockethive.scenarios.Scenario`.",
    "- Worker fields follow Scenario Manager capability manifests from `/api/capabilities`.",
    "- Runtime validation must use `bundle.validate`; Scenario Manager is the canonical static bundle validator.",
    "",
    "## Target",
    "",
    `- SUT: ${target.id}`,
    `- Endpoint key: ${target.endpointKey}`,
    `- Base URL: ${target.baseUrl}`,
    "",
    "## Endpoints",
    "",
    "| # | callId | Method | Path | Notes |",
    "|---|---|---|---|---|",
    ...(endpointRows.length ? endpointRows : ["| 1 | tcp-request | TCP | n/a | TCP request-response |"]),
    "",
    "## Data And Traffic",
    "",
    `- Data source: ${answers.dataSource}`,
    `- Default profile: ${answers.defaultRatePerSec} rps for ${answers.runDuration}`,
    `- NFT profile: ${answers.nftRatePerSec || answers.defaultRatePerSec} rps for ${answers.nftDuration || answers.runDuration}`,
    `- Traffic shape: ${answers.trafficShape}`,
    "",
    "## Evidence",
    "",
    `- ClickHouse mode: ${answers.clickhouse}`,
    `- Grafana dashboard: ${answers.grafanaDashboard}`,
    `- Objective: ${answers.performanceObjective || "not set"}`,
  ].join("\n");
}

function wizardChangelog(session, answers) {
  return [
    "# Changelog",
    "",
    `## ${new Date().toISOString().slice(0, 10)} - Wizard generated`,
    "",
    `- Created bundle ${answers.bundleId} from wizard intent.`,
    `- Pattern: ${wizardPattern(answers)}.`,
    `- Auth: ${answers.auth}.`,
    `- Data source: ${answers.dataSource}.`,
    "",
    "### Evidence",
    "",
    "- Pending first live run.",
  ].join("\n");
}

function wizardPlan(session) {
  const answers = wizardAnswersWithDefaults(session.answers);
  const missing = wizardMissingQuestions(answers);
  const errors = validateWizardAnswers(answers);
  const humanCheckpoint = buildHumanCheckpoint(session.answers, missing);
  // ready is only true when there are no missing fields, no errors,
  // AND the human has confirmed all checkpoint questions (humanCheckpoint is null).
  const ready = missing.length === 0 && errors.length === 0 && humanCheckpoint === null;
  const bundleIdValid = answers.bundleId && /^[a-z0-9][a-z0-9-]*$/.test(answers.bundleId);
  const target = answers.target && answers.protocol && (answers.target !== "external" || answers.targetBaseUrl)
    ? wizardTarget(answers)
    : null;
  return {
    sessionId: session.sessionId,
    status: ready ? "ready" : "gathering",
    intent: session.intent,
    ready,
    missing,
    errors,
    nextQuestion: missing.length ? wizardNextQuestion(answers) : null,
    nextQuestions: missing.map(id => ({ id, ...WIZARD_QUESTIONS[id] })),
    humanCheckpoint,
    assumptions: wizardAssumptions(session.answers, answers),
    bundle: bundleIdValid ? { id: answers.bundleId, path: bundleDir(answers.bundleId) } : null,
    scenario: answers.bundleId ? {
      id: answers.bundleId,
      protocol: answers.protocol ?? null,
      pattern: answers.protocol ? wizardPattern(answers) : null,
      target,
      endpoints: answers.endpoints ?? [],
      ratePerSec: answers.defaultRatePerSec ?? null,
      dataSource: answers.dataSource,
      trafficShape: answers.trafficShape,
      runDuration: answers.runDuration,
      sutDouble: answers.sutDouble,
      auth: answers.auth,
      observability: {
        clickhouse: answers.clickhouse,
        grafanaDashboard: answers.grafanaDashboard,
        performanceObjective: answers.performanceObjective || null,
      },
    } : null,
  };
}

function wizardSession(sessionId) {
  const session = WIZARD_SESSIONS.get(sessionId);
  if (!session) throw new Error(`No wizard session found for ${sessionId}`);
  if (Date.now() - session.createdAtMs > WIZARD_SESSION_TTL_MS) {
    WIZARD_SESSIONS.delete(sessionId);
    throw new Error(`Wizard session expired for ${sessionId}`);
  }
  return session;
}

function cleanupWizardSessions() {
  const now = Date.now();
  for (const [sessionId, session] of WIZARD_SESSIONS.entries()) {
    if (now - session.createdAtMs > WIZARD_SESSION_TTL_MS) WIZARD_SESSIONS.delete(sessionId);
  }
}

async function writeWizardBundle(session) {
  const { stringify } = await import("yaml").catch(() => ({ stringify: JSON.stringify }));
  const answers = wizardAnswersWithDefaults(session.answers);
  if (answers.endpoints?.length) answers.endpoints = normalizeWizardEndpoints(answers.endpoints);
  if (answers.mockEndpoints?.length) answers.mockEndpoints = answers.mockEndpoints.map((entry, index) => normalizeWizardMockEndpoint(entry, index));
  const target = wizardTarget(answers);
  const targetBundleDir = bundleDir(answers.bundleId);
  if (existsSync(targetBundleDir)) throw new Error(`Bundle '${answers.bundleId}' already exists at ${targetBundleDir}`);
  mkdirSync(targetBundleDir, { recursive: true });

  const filesCreated = [];
  const writeGenerated = (relativePath, content) => {
    const fullPath = resolve(targetBundleDir, relativePath);
    mkdirSync(dirname(fullPath), { recursive: true });
    writeFileSync(fullPath, content, "utf8");
    filesCreated.push(relativePath);
  };

  const serviceId = answers.protocol === "SEQUENCE" ? "sequence" : "default";
  const templateProtocolDir = answers.protocol === "TCP" ? "tcp" : "http";
  const templateRoot = `/app/scenario/templates/${templateProtocolDir}`;
  const processorBase = `{{ sut.endpoints['${target.endpointKey}'].baseUrl }}`;
  const firstEndpoint = answers.endpoints?.[0] || { method: "POST", path: "/", callId: "main" };
  const primaryCallId = answers.protocol === "TCP" ? "tcp-request" : firstEndpoint.callId || "main";
  const callIdTemplate = answers.protocol === "SEQUENCE"
    ? primaryCallId
    : answers.endpoints?.length > 1
      ? `{{ pickWeighted(${answers.endpoints.map(endpoint => `'${endpoint.callId}', 1`).join(", ")}) }}`
      : primaryCallId;
  const usesDatasetInput = answers.dataSource === "REDIS_DATASET" || answers.dataSource === "CSV_DATASET";
  const datasetPayloadBody = usesDatasetInput ? "{{ payload }}" : null;
  const generatorInputConfig = answers.dataSource === "CSV_DATASET"
    ? {
        type: "CSV_DATASET",
        csv: {
          filePath: "/app/scenario/datasets/sample.csv",
          ratePerSec: answers.defaultRatePerSec,
          skipHeader: true,
          rotate: false,
        },
      }
    : answers.dataSource === "REDIS_DATASET"
    ? {
        type: "REDIS_DATASET",
        redis: answers.redisLists.length === 1
          ? { host: "redis", port: 6379, listName: answers.redisLists[0], ratePerSec: answers.defaultRatePerSec }
          : {
              host: "redis",
              port: 6379,
              sources: answers.redisLists.map(listName => ({ listName, weight: 1 })),
              pickStrategy: "WEIGHTED_RANDOM",
              ratePerSec: answers.defaultRatePerSec,
            },
      }
    : { type: "SCHEDULER", scheduler: { ratePerSec: answers.defaultRatePerSec } };
  const postprocessor = { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } };
  const bees = [];

  if (answers.protocol === "TCP") {
    bees.push(
      {
        role: "generator",
        image: "generator:latest",
        work: { out: { out: "proc" } },
        config: {
          inputs: generatorInputConfig,
          outputs: { type: "RABBITMQ" },
          message: {
            bodyType: "SIMPLE",
            body: datasetPayloadBody ?? answers.tcpPayload,
            headers: { "x-ph-call-id": primaryCallId },
          },
        },
      },
      {
        role: "request-builder",
        image: "request-builder:latest",
        work: { in: { in: "proc" }, out: { out: "built" } },
        config: { templateRoot, serviceId },
      },
      {
        role: "processor",
        image: "processor:latest",
        work: { in: { in: "built" }, out: { out: "post" } },
        config: { baseUrl: processorBase, mode: "THREAD_COUNT", threadCount: 5, tcpTransport: { type: "socket" } },
      },
      postprocessor,
    );
  } else if (answers.protocol === "SEQUENCE") {
    bees.push(
      {
        role: "generator",
        image: "generator:latest",
        work: { out: { out: "seq" } },
        config: {
          inputs: generatorInputConfig,
          outputs: { type: "RABBITMQ" },
          message: { bodyType: "SIMPLE", body: datasetPayloadBody ?? (answers.requestBody || "{}") },
        },
      },
      {
        role: "http-sequence",
        image: "http-sequence:latest",
        work: { in: { in: "seq" }, out: { out: "post" } },
        config: {
          baseUrl: processorBase,
          templateRoot,
          serviceId,
          threadCount: 1,
          steps: answers.endpoints.map((endpoint, index) => wizardSequenceStep(endpoint, index)),
          debugCapture: {
            mode: "ERROR_ONLY",
            includeHeaders: true,
            includeRequest: true,
            bodyPreviewBytes: 4096,
            redisTtlSeconds: 120,
          },
        },
      },
      postprocessor,
    );
  } else {
    bees.push(
      {
        role: "generator",
        image: "generator:latest",
        work: { out: { out: "build" } },
        config: {
          inputs: generatorInputConfig,
          outputs: { type: "RABBITMQ" },
          message: {
            bodyType: "SIMPLE",
            body: datasetPayloadBody ?? "{}",
            headers: { "x-ph-call-id": callIdTemplate, "x-ph-service-id": serviceId },
          },
        },
      },
      {
        role: "request-builder",
        image: "request-builder:latest",
        work: { in: { in: "build" }, out: { out: "proc" } },
        config: { templateRoot, serviceId },
      },
      {
        role: "processor",
        image: "processor:latest",
        work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: processorBase, mode: "THREAD_COUNT", threadCount: 5 },
      },
      postprocessor,
    );
  }

  const scenario = {
    id: answers.bundleId,
    name: answers.bundleId,
    description: `Generated by wizard.complete from intent: ${session.intent}`,
    template: { image: "swarm-controller:latest", bees },
    topology: wizardTopology(bees),
    trafficPolicy: null,
    plan: {
      version: 1,
      source: "wizard.complete",
      pattern: wizardPattern(answers),
      profiles: {
        smoke: { ratePerSec: 1, duration: "10s", trafficShape: "smoke" },
        default: { ratePerSec: answers.defaultRatePerSec, duration: answers.runDuration, trafficShape: answers.trafficShape },
        nft: {
          ratePerSec: answers.nftRatePerSec || answers.defaultRatePerSec,
          duration: answers.nftDuration || answers.runDuration,
          trafficShape: answers.trafficShape,
        },
      },
      endpoints: answers.endpoints || [],
      dataSource: answers.dataSource,
      observability: {
        clickhouse: answers.clickhouse,
        grafanaDashboard: answers.grafanaDashboard,
        performanceObjective: answers.performanceObjective || null,
        resultRules: answers.resultRules === "yes"
          ? { resultCodePattern: answers.resultCodePattern, successCodes: answers.successCodes }
          : null,
      },
    },
  };
  writeGenerated("scenario.yaml", stringify(scenario));

  writeGenerated("variables.yaml", stringify({
    version: 1,
    definitions: [
      { name: "ratePerSec", scope: "global", type: "float", required: true },
      { name: "runDuration", scope: "global", type: "string", required: true },
      { name: "trafficShape", scope: "global", type: "string", required: true },
      { name: "targetBaseUrl", scope: "sut", type: "string", required: true },
    ],
    profiles: [
      { id: "smoke", name: "Smoke" },
      { id: "default", name: "Default" },
      { id: "nft", name: "NFT" },
    ],
    values: {
      global: {
        smoke: { ratePerSec: 1, runDuration: "10s", trafficShape: "smoke" },
        default: { ratePerSec: answers.defaultRatePerSec, runDuration: answers.runDuration, trafficShape: answers.trafficShape },
        nft: {
          ratePerSec: answers.nftRatePerSec || answers.defaultRatePerSec,
          runDuration: answers.nftDuration || answers.runDuration,
          trafficShape: answers.trafficShape,
        },
      },
      sut: {
        smoke: { [target.id]: { targetBaseUrl: target.baseUrl } },
        default: { [target.id]: { targetBaseUrl: target.baseUrl } },
        nft: { [target.id]: { targetBaseUrl: answers.sutNftUrl || target.baseUrl } },
      },
    },
  }));

  writeGenerated(`sut/${target.id}/sut.yaml`, stringify({
    id: target.id,
    name: target.id,
    type: target.id.includes("local") ? "sandbox" : "external",
    endpoints: { [target.endpointKey]: { kind: target.kind, baseUrl: target.baseUrl } },
  }));

  if (answers.auth !== "none") {
    writeGenerated("authProfiles.yaml", stringify({ profiles: wizardAuthProfiles(answers) }));
  }

  if (answers.protocol === "TCP") {
    const tcpTemplate = {
      serviceId,
      callId: primaryCallId,
      protocol: "TCP",
      behavior: "REQUEST_RESPONSE",
      transport: "socket",
      endTag: "ETX",
      maxBytes: 8192,
      bodyTemplate: answers.tcpPayload,
      headersTemplate: { "x-ph-call-id": primaryCallId },
    };
    if (answers.auth !== "none") tcpTemplate.authRef = wizardAuthRef(answers);
    writeGenerated(`templates/tcp/${serviceId}/${primaryCallId}.yaml`, stringify(tcpTemplate));
  } else {
    for (const endpoint of answers.endpoints) {
      const template = {
        serviceId,
        callId: endpoint.callId,
        protocol: "HTTP",
        method: endpoint.method,
        pathTemplate: wizardEndpointPathTemplate(endpoint),
        headersTemplate: wizardTemplateHeaders(endpoint, true),
      };
      const endpointBody = endpoint.bodyTemplate || answers.requestBody;
      if (endpointBody) template.bodyTemplate = endpointBody;
      if (answers.auth !== "none") template.authRef = wizardAuthRef(answers);
      writeGenerated(`templates/http/${serviceId}/${endpoint.callId}.yaml`, stringify(template));
    }
  }

  if (answers.dataSource === "CSV_DATASET") {
    writeGenerated("datasets/sample.csv", `${answers.csvColumns.join(",")}\n${answers.csvColumns.map((col, i) => `${slug(col, `value${i + 1}`)}-1`).join(",")}\n`);
  }

  if (answers.dataSource === "REDIS_DATASET") {
    writeGenerated("mock-config/redis-state.json", JSON.stringify({
      version: 1,
      lists: answers.redisLists.map(listName => ({
        listName,
        sourceFile: "datasets/sample.jsonl",
        records: [{ id: "sample-1", payload: answers.requestBody || answers.tcpPayload || "{}" }],
      })),
      output: answers.redisOutput,
    }, null, 2));
  }

  if (["wiremock", "wiremock_and_tcp"].includes(answers.sutDouble)) {
    for (const endpoint of wizardMockEndpoints(answers).filter(mock => mock.method && mock.path)) {
      writeGenerated(`mock-config/wiremock/${endpoint.callId}.json`, JSON.stringify({
        request: wizardWireMockRequest(endpoint, answers),
        response: wizardWireMockResponse(endpoint, answers),
        ...(endpoint.priority ? { priority: endpoint.priority } : {}),
      }, null, 2));
    }
  }

  if (["tcp_mock", "wiremock_and_tcp"].includes(answers.sutDouble)) {
    writeGenerated("mock-config/tcp/tcp-request.yaml", stringify({
      id: "tcp-request",
      request: answers.tcpPayload,
      response: "OK",
      transport: "socket",
    }));
  }

  writeGenerated("README.md", [
    `# ${answers.bundleId}`,
    "",
    `Generated by PocketHive wizard from: ${session.intent}`,
    "",
    `- Protocol: ${answers.protocol}`,
    `- Target: ${target.id} (${target.baseUrl})`,
    `- Pattern: ${wizardPattern(answers)}`,
    `- Data source: ${answers.dataSource}`,
    `- Default rate: ${answers.defaultRatePerSec}/s`,
    `- Default duration: ${answers.runDuration}`,
    `- Traffic shape: ${answers.trafficShape}`,
    `- Auth: ${answers.auth}`,
    `- Evidence dashboard: ${answers.grafanaDashboard}`,
    "",
    "## Generated Artifacts",
    "",
    ...filesCreated.map(file => `- ${file}`),
  ].join("\n"));

  if (answers.docs === "yes") {
    writeGenerated("FLOW_DOCUMENT.md", wizardFlowDocument(session, answers, target));
    writeGenerated("CHANGELOG.md", wizardChangelog(session, answers));
  }

  return {
    created: true,
    bundleId: answers.bundleId,
    path: targetBundleDir,
    pattern: wizardPattern(answers),
    target,
    filesCreated,
    assumptions: wizardAssumptions(session.answers, answers),
  };
}

// ── Enrich existing bundle with wizard-generated missing artifacts ────────────

async function readExistingBundleAnswers(targetBundleDir, parseYaml) {
  const answers = {};
  // Read scenario.yaml for bundleId, endpoints, dataSource, auth hints
  const scenarioPath = resolve(targetBundleDir, "scenario.yaml");
  if (existsSync(scenarioPath)) {
    try {
      const scenario = parseYaml(readFileSync(scenarioPath, "utf8"));
      if (scenario?.id) answers.bundleId = scenario.id;
      // Infer protocol from bees
      const bees = scenario?.template?.bees || [];
      const imageNames = bees.map(beeImageName).filter(Boolean);
      if (imageNames.includes(WORKER_IMAGE_NAMES.HTTP_SEQUENCE)) answers.protocol = "SEQUENCE";
      else if (imageNames.includes(WORKER_IMAGE_NAMES.PROCESSOR)) answers.protocol = "REST";
      // Infer dataSource from generator bee
      const gen = bees.find(b => beeImageName(b) === WORKER_IMAGE_NAMES.GENERATOR);
      const inputType = gen?.config?.inputs?.type;
      if (inputType) answers.dataSource = inputType;
      const ratePerSec = gen?.config?.inputs?.scheduler?.ratePerSec
        || gen?.config?.inputs?.csv?.ratePerSec
        || gen?.config?.inputs?.redis?.ratePerSec;
      if (ratePerSec) answers.defaultRatePerSec = ratePerSec;
      // Infer endpoints from plan
      const planEndpoints = scenario?.plan?.endpoints;
      if (Array.isArray(planEndpoints) && planEndpoints.length) answers.endpoints = planEndpoints;
      // Infer baseUrl from SUT reference in bees
      const sutRef = bees.find(b => b?.config?.baseUrl)?.config?.baseUrl || "";
      if (sutRef && !sutRef.includes("{{")) answers.targetBaseUrl = sutRef;
    } catch { /* ignore parse errors */ }
  }
  // Read authProfiles.yaml for auth type, tokenUrl, clientId, secretEnvVar
  const authPath = resolve(targetBundleDir, "authProfiles.yaml");
  if (existsSync(authPath)) {
    try {
      const authFile = parseYaml(readFileSync(authPath, "utf8"));
      const profiles = authFile?.profiles || {};
      const firstProfile = Object.values(profiles)[0];
      if (firstProfile) {
        const typeMap = {
          OAUTH2_CLIENT_CREDENTIALS: "oauth2_client_credentials",
          STATIC_TOKEN: "bearer_token_static",
          BASIC_AUTH: "basic_auth",
          API_KEY: "api_key",
          HMAC_SIGNATURE: "hmac",
          AWS_SIGNATURE_V4: "aws_sig_v4",
          ISO8583_MAC: "iso8583_mac",
          TLS_CLIENT_CERT: "mtls",
        };
        if (firstProfile.type) answers.auth = typeMap[firstProfile.type] || "none";
        if (firstProfile.tokenUrl) answers.authTokenUrl = firstProfile.tokenUrl;
        if (firstProfile.clientId) answers.authClientId = firstProfile.clientId;
        const secretEnv = firstProfile.clientSecret?.env || firstProfile.token?.env
          || firstProfile.password?.env || firstProfile.key?.env;
        if (secretEnv) answers.authSecretEnvVar = secretEnv;
      }
    } catch { /* ignore */ }
  }
  // Read sut/ to infer target and targetBaseUrl
  const sutDir = resolve(targetBundleDir, "sut");
  if (existsSync(sutDir)) {
    const sutDirs = readdirSync(sutDir, { withFileTypes: true }).filter(d => d.isDirectory());
    if (sutDirs.length) {
      const firstSut = sutDirs[0].name;
      if (firstSut === "wiremock-local") answers.target = "wiremock-local";
      else if (firstSut === "tcp-mock-local") answers.target = "tcp-mock-local";
      else answers.target = "external";
      const sutYaml = resolve(sutDir, firstSut, "sut.yaml");
      if (existsSync(sutYaml)) {
        try {
          const sut = parseYaml(readFileSync(sutYaml, "utf8"));
          const firstEndpoint = Object.values(sut?.endpoints || {})[0];
          if (firstEndpoint?.baseUrl && !firstEndpoint.baseUrl.includes("{{")) {
            answers.targetBaseUrl = firstEndpoint.baseUrl;
          }
        } catch { /* ignore */ }
      }
    }
  }
  return answers;
}

async function enrichWizardBundle(session, flags = {}) {
  const { stringify, parse: parseYaml } = await import("yaml").catch(() => ({ stringify: JSON.stringify, parse: JSON.parse }));
  const answers = wizardAnswersWithDefaults(session.answers);
  const target = wizardTarget(answers);
  const targetBundleDir = bundleDir(answers.bundleId);
  if (!existsSync(targetBundleDir)) throw new Error(`Bundle '${answers.bundleId}' does not exist. Use wizard.complete to create a new bundle.`);

  const filesWritten = [];
  const filesSkipped = [];

  // Write only if file does not exist, or if the matching force flag is set
  const writeIfMissing = (relativePath, content, forceFlag = false) => {
    const fullPath = resolve(targetBundleDir, relativePath);
    if (!forceFlag && existsSync(fullPath)) {
      filesSkipped.push(relativePath);
      return;
    }
    mkdirSync(dirname(fullPath), { recursive: true });
    writeFileSync(fullPath, content, "utf8");
    filesWritten.push(relativePath);
  };

  // variables.yaml
  writeIfMissing("variables.yaml", stringify({
    version: 1,
    definitions: [
      { name: "ratePerSec", scope: "global", type: "float", required: true },
      { name: "runDuration", scope: "global", type: "string", required: true },
      { name: "trafficShape", scope: "global", type: "string", required: true },
      { name: "targetBaseUrl", scope: "sut", type: "string", required: true },
    ],
    profiles: [
      { id: "smoke", name: "Smoke" },
      { id: "default", name: "Default" },
      { id: "nft", name: "NFT" },
    ],
    values: {
      global: {
        smoke: { ratePerSec: 1, runDuration: "10s", trafficShape: "smoke" },
        default: { ratePerSec: answers.defaultRatePerSec, runDuration: answers.runDuration, trafficShape: answers.trafficShape },
        nft: {
          ratePerSec: answers.nftRatePerSec || answers.defaultRatePerSec,
          runDuration: answers.nftDuration || answers.runDuration,
          trafficShape: answers.trafficShape,
        },
      },
      sut: {
        smoke: { [target.id]: { targetBaseUrl: target.baseUrl } },
        default: { [target.id]: { targetBaseUrl: target.baseUrl } },
        nft: { [target.id]: { targetBaseUrl: answers.sutNftUrl || target.baseUrl } },
      },
    },
  }));

  // authProfiles.yaml
  if (answers.auth !== "none") {
    writeIfMissing("authProfiles.yaml", stringify({ profiles: wizardAuthProfiles(answers) }));
  }

  // SUT
  const sutPath = `sut/${target.id}/sut.yaml`;
  writeIfMissing(sutPath, stringify({
    id: target.id,
    name: target.id,
    type: target.id.includes("local") ? "sandbox" : "external",
    endpoints: { [target.endpointKey]: { kind: target.kind, baseUrl: target.baseUrl } },
  }));

  // WireMock stubs — only write stubs for callIds that don't already have a file
  if (["wiremock", "wiremock_and_tcp"].includes(answers.sutDouble)) {
    for (const endpoint of wizardMockEndpoints(answers).filter(mock => mock.method && mock.path)) {
      writeIfMissing(`mock-config/wiremock/${endpoint.callId}.json`, JSON.stringify({
        request: wizardWireMockRequest(endpoint, answers),
        response: wizardWireMockResponse(endpoint, answers),
      }, null, 2));
    }
  }

  // HTTP templates — only write templates for callIds that don't already have a file
  if (answers.protocol !== "TCP" && Array.isArray(answers.endpoints)) {
    const serviceId = answers.protocol === "SEQUENCE" ? "sequence" : "default";
    const templateProtocolDir = "http";
    for (const endpoint of answers.endpoints) {
      const templatePath = `templates/${templateProtocolDir}/${serviceId}/${endpoint.callId}.yaml`;
      const template = {
        serviceId,
        callId: endpoint.callId,
        protocol: "HTTP",
        method: endpoint.method,
        pathTemplate: wizardEndpointPathTemplate(endpoint),
        headersTemplate: wizardTemplateHeaders(endpoint, true),
      };
      const endpointBody = endpoint.bodyTemplate || answers.requestBody;
      if (endpointBody) template.bodyTemplate = endpointBody;
      if (answers.auth !== "none") template.authRef = wizardAuthRef(answers);
      writeIfMissing(templatePath, stringify(template));
    }
  }

  // README.md
  writeIfMissing("README.md", [
    `# ${answers.bundleId}`,
    "",
    `Enriched by PocketHive wizard from: ${session.intent}`,
    "",
    `- Protocol: ${answers.protocol}`,
    `- Target: ${target.id} (${target.baseUrl})`,
    `- Pattern: ${wizardPattern(answers)}`,
    `- Data source: ${answers.dataSource}`,
    `- Default rate: ${answers.defaultRatePerSec}/s`,
    `- Auth: ${answers.auth}`,
  ].join("\n"), flags.forceReadme);

  // FLOW_DOCUMENT.md
  writeIfMissing("FLOW_DOCUMENT.md", wizardFlowDocument(session, answers, target), flags.forceFlowDoc);

  // CHANGELOG.md — always append an enrich entry; create if missing
  const changelogPath = resolve(targetBundleDir, "CHANGELOG.md");
  const enrichEntry = [
    "",
    `## ${new Date().toISOString().slice(0, 10)} - Wizard enriched`,
    "",
    `- Enriched bundle ${answers.bundleId} from wizard intent.`,
    `- Files written: ${filesWritten.length > 0 ? filesWritten.join(", ") : "none (all already present)"}.`,
    `- Files skipped (already exist): ${filesSkipped.length > 0 ? filesSkipped.join(", ") : "none"}.`,
  ].join("\n");
  if (flags.forceChangelog || !existsSync(changelogPath)) {
    writeFileSync(changelogPath, `# Changelog\n${enrichEntry}\n`, "utf8");
    filesWritten.push("CHANGELOG.md");
  } else {
    const existing = readFileSync(changelogPath, "utf8");
    writeFileSync(changelogPath, existing.trimEnd() + "\n" + enrichEntry + "\n", "utf8");
    filesWritten.push("CHANGELOG.md (appended)");
  }

  return {
    enriched: true,
    bundleId: answers.bundleId,
    path: targetBundleDir,
    filesWritten,
    filesSkipped,
  };
}

registerWorkflowTools({
  z,
  reg,
  exposedToolName,
  BASE_URL,
  ORCH_URL,
  SM_URL,
  RABBIT_MGMT,
  PROM_URL,
  POCKETHIVE_ROOT,
  REPO_ROOT,
  getBundlesDir,
  bundleDir,
  ensureInside,
  SWARM_ID_ARG,
  WIZARD_TRAFFIC_SHAPES,
  WIZARD_DATA_SOURCES,
  WIZARD_SUT_DOUBLES,
  wizardMissingQuestions,
  wizardAnswersWithDefaults,
  wizardTarget,
  wizardPattern,
  wizardMockEndpoints,
  validateWizardAnswers,
  writeWizardBundle,
  runGenerationSanityCheck,
  scenarioManagerDryRunValidateBundle,
  scenarioManagerUploadBundle,
  loadBundleMockConfig,
  httpJson,
  idempotencyKey,
  buildEvidenceSummary,
  WORKFLOW_EVIDENCE_WIDGET_URI,
});

reg("wizard.start", "Start a novice bundle creation session. Collects intent and explicit answers; it does not write files. IMPORTANT: if the response contains a non-null humanCheckpoint field, the agent MUST present all humanCheckpoint.questions to the human and collect their answers before calling wizard.answer or wizard.complete. Do not infer or assume answers to humanCheckpoint questions — they require explicit human confirmation.", {
  intent: z.string().describe("Natural-language description of the bundle the user wants."),
  bundleId: z.string().optional(),
  existingBundleId: z.string().optional().describe("If set, pre-populate answers from the existing bundle at this id before applying any other inputs."),
  protocol: z.enum(["REST", "TCP", "SEQUENCE", "HTTP"]).optional(),
  target: z.enum(["wiremock-local", "tcp-mock-local", "external"]).optional(),
  targetBaseUrl: z.string().optional(),
  endpoint: z.union([z.string(), z.object({ method: z.string(), path: z.string() })]).optional(),
  endpoints: z.union([
    z.string(),
    z.array(z.union([z.string(), z.object({
      method: z.string(),
      path: z.string(),
      callId: z.string().optional(),
      description: z.string().optional(),
      bodyTemplate: z.string().optional(),
    }).passthrough()])),
  ]).optional(),
  requestBody: z.string().optional(),
  tcpPayload: z.string().optional(),
  ratePerSec: z.number().optional(),
  defaultRatePerSec: z.number().optional(),
  nftRatePerSec: z.number().optional(),
  trafficShape: z.enum(WIZARD_TRAFFIC_SHAPES).optional(),
  runDuration: z.string().optional(),
  nftDuration: z.string().optional(),
  dataSource: z.enum(WIZARD_DATA_SOURCES).optional(),
  csvColumns: z.union([z.string(), z.array(z.string())]).optional(),
  redisLists: z.union([z.string(), z.array(z.string())]).optional(),
  redisOutput: z.union([z.boolean(), z.enum(["yes", "no"])]).optional(),
  auth: z.enum(WIZARD_AUTH_TYPES).optional(),
  authTokenUrl: z.string().optional(),
  authClientId: z.string().optional(),
  authSecretSource: z.enum(["env_var", "file"]).optional(),
  authSecretEnvVar: z.string().optional(),
  sutNftUrl: z.string().optional(),
  sutDouble: z.enum(WIZARD_SUT_DOUBLES).optional(),
  mockEndpoints: z.array(z.union([z.string(), z.object({}).passthrough()])).optional(),
  resultRules: z.union([z.boolean(), z.enum(["yes", "no"])]).optional(),
  resultCodePattern: z.string().optional(),
  successCodes: z.union([z.string(), z.array(z.string())]).optional(),
  performanceObjective: z.string().optional(),
  clickhouse: z.enum(WIZARD_CLICKHOUSE_MODES).optional(),
  grafanaDashboard: z.enum(WIZARD_GRAFANA_DASHBOARDS).optional(),
  docs: z.union([z.boolean(), z.enum(["yes", "no"])]).optional(),
}, async (input) => {
  cleanupWizardSessions();
  const session = {
    sessionId: `wiz-${crypto.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`}`,
    intent: input.intent,
    answers: {},
    createdAt: new Date().toISOString(),
    createdAtMs: Date.now(),
  };
  // Pre-populate from existing bundle if existingBundleId provided
  if (input.existingBundleId) {
    try {
      const { parse: parseYaml } = await import("yaml");
      const existingDir = bundleDir(input.existingBundleId);
      if (existsSync(existingDir)) {
        const inferred = await readExistingBundleAnswers(existingDir, parseYaml);
        Object.assign(session.answers, inferred);
      }
    } catch { /* ignore — best effort pre-population */ }
  }
  applyWizardAnswers(session.answers, input);
  WIZARD_SESSIONS.set(session.sessionId, session);
  return wizardPlan(session);
});

reg("wizard.answer", "Answer one question or a batch of questions in a novice bundle creation session. Does not write files. IMPORTANT: if the returned plan contains a non-null humanCheckpoint, the agent MUST present those questions to the human before calling wizard.complete. Use the batch answers map to submit all human-confirmed answers in a single call.", {
  sessionId: z.string(),
  questionId: z.enum(WIZARD_QUESTION_IDS).optional(),
  answer: z.any().optional(),
  answers: z.record(z.any()).optional().describe("Batch answer map — provide multiple question answers at once instead of one at a time."),
}, async ({ sessionId, questionId, answer, answers: batchAnswers }) => {
  const session = wizardSession(sessionId);
  // Batch path — agent collected all human answers and submits them together
  if (batchAnswers && typeof batchAnswers === "object" && !Array.isArray(batchAnswers)) {
    for (const [id, val] of Object.entries(batchAnswers)) {
      try {
        session.answers[id] = normalizeWizardAnswer(id, val);
      } catch (e) {
        throw new Error(`Invalid answer for '${id}': ${e.message}`);
      }
    }
  }
  // Single path — backward compatible
  if (questionId && answer !== undefined) {
    session.answers[questionId] = normalizeWizardAnswer(questionId, answer);
  }
  session.updatedAt = new Date().toISOString();
  return wizardPlan(session);
});

reg("wizard.summary", "Return the current wizard plan, missing inputs, and generated bundle preview. Does not write files.", {
  sessionId: z.string(),
}, async ({ sessionId }) => wizardPlan(wizardSession(sessionId)));

reg("wizard.complete", "Complete a wizard session and generate the bundle files. This is the only wizard.* tool that writes files. IMPORTANT: do NOT call this tool if the current plan has a non-null humanCheckpoint — all humanCheckpoint.questions must be answered by the human and submitted via wizard.answer first.", {
  sessionId: z.string(),
}, async ({ sessionId }) => {
  const session = wizardSession(sessionId);
  const plan = wizardPlan(session);
  if (!plan.ready) {
    throw new Error(`Wizard is not complete. Missing: ${plan.missing.join(", ") || "none"}. Errors: ${plan.errors.join("; ") || "none"}`);
  }
  const generated = await writeWizardBundle(session);
  const generationSanity = await runGenerationSanityCheck(generated.bundleId);
  WIZARD_SESSIONS.delete(sessionId);
  return { completed: true, generated, generationSanity };
});

reg("wizard.enrich", "Enrich an existing bundle with missing artifacts (variables, authProfiles, SUT, mock stubs, templates, README, FLOW_DOCUMENT, CHANGELOG). Never overwrites existing files unless a force flag is set. IMPORTANT: do NOT call this tool if the current plan has a non-null humanCheckpoint — all humanCheckpoint.questions must be answered by the human and submitted via wizard.answer first.", {
  sessionId: z.string(),
  forceReadme: z.boolean().optional().default(false).describe("Overwrite README.md even if it already exists."),
  forceFlowDoc: z.boolean().optional().default(false).describe("Overwrite FLOW_DOCUMENT.md even if it already exists."),
  forceChangelog: z.boolean().optional().default(false).describe("Replace CHANGELOG.md instead of appending to it."),
}, async ({ sessionId, forceReadme = false, forceFlowDoc = false, forceChangelog = false }) => {
  const session = wizardSession(sessionId);
  const plan = wizardPlan(session);
  if (!plan.ready) {
    throw new Error(`Wizard is not complete. Missing: ${plan.missing.join(", ") || "none"}. Errors: ${plan.errors.join("; ") || "none"}`);
  }
  const result = await enrichWizardBundle(session, { forceReadme, forceFlowDoc, forceChangelog });
  const generationSanity = await runGenerationSanityCheck(result.bundleId);
  WIZARD_SESSIONS.delete(sessionId);
  return { ...result, generationSanity };
});

async function runGenerationSanityCheck(bundle) {
  const targetBundleDir = bundleDir(bundle);
  const scenarioPath = resolve(targetBundleDir, "scenario.yaml");
  const checks = [];
  const errors = [];
  const warnings = [];

  const addCheck = (id, ok, message, severity = "error") => {
    checks.push({ id, ok, message, severity });
    if (!ok && severity === "error") errors.push({ id, message });
    if (!ok && severity === "warning") warnings.push({ id, message });
  };

  if (!existsSync(targetBundleDir)) {
    addCheck("bundle.exists", false, `Bundle directory not found: ${targetBundleDir}`);
    return { ok: false, bundle, path: targetBundleDir, checks, errors, warnings };
  }
  addCheck("bundle.exists", true, "Bundle directory exists");

  if (!existsSync(scenarioPath)) {
    addCheck("scenario.exists", false, "scenario.yaml exists");
    return { ok: false, bundle, path: targetBundleDir, checks, errors, warnings };
  }
  addCheck("scenario.exists", true, "scenario.yaml exists");

  let scenario;
  let parseYaml;
  try {
    ({ parse: parseYaml } = await import("yaml"));
    scenario = parseYaml(readFileSync(scenarioPath, "utf8"));
    addCheck("scenario.parse", true, "scenario.yaml parses as YAML");
  } catch (e) {
    addCheck("scenario.parse", false, `scenario.yaml parse failed: ${e.message}`);
    return { ok: false, bundle, path: targetBundleDir, checks, errors, warnings };
  }

  addCheck("scenario.id", typeof scenario?.id === "string" && scenario.id.length > 0, "scenario.id is required");
  addCheck("scenario.template", !!scenario?.template && typeof scenario.template === "object", "scenario.template is required");

  const bees = Array.isArray(scenario?.template?.bees) ? scenario.template.bees : [];
  addCheck("scenario.template.bees", bees.length > 0, "template.bees must contain at least one bee");

  const roles = new Set();
  const imageNames = new Set();
  const producedQueues = new Set();
  const consumedQueues = [];
  for (const [index, bee] of bees.entries()) {
    if (bee && Object.prototype.hasOwnProperty.call(bee, "id")) {
      addCheck(`bee.${index}.no-id`, false, "template.bees[].id is not supported; use role as the unique scenario node key");
    }
    const role = bee?.role;
    if (!role || typeof role !== "string") {
      addCheck("bee.role", false, "Each bee requires a string role");
      continue;
    }
    if (roles.has(role)) {
      addCheck(`bee.${role}.unique`, false, `Duplicate bee role '${role}'`);
    }
    roles.add(role);
    const imageName = beeImageName(bee);
    if (imageName) imageNames.add(imageName);
    addCheck(`bee.${role}.image`, typeof bee?.image === "string" && bee.image.length > 0, `Bee '${role}' has an image`);

    const outputs = bee?.work?.out && typeof bee.work.out === "object" ? bee.work.out : {};
    for (const suffix of Object.values(outputs)) {
      if (typeof suffix === "string" && suffix.length > 0) producedQueues.add(suffix);
    }

    const inputs = bee?.work?.in && typeof bee.work.in === "object" ? bee.work.in : {};
    for (const suffix of Object.values(inputs)) {
      if (typeof suffix === "string" && suffix.length > 0) consumedQueues.push({ role, suffix });
    }
  }

  for (const { role, suffix } of consumedQueues) {
    addCheck(
      `queue.${role}.${suffix}.producer`,
      producedQueues.has(suffix),
      `Bee '${role}' consumes queue suffix '${suffix}' with an upstream producer`
    );
  }

  const hasGenerator = imageNames.has(WORKER_IMAGE_NAMES.GENERATOR);
  const hasTerminal = [...TERMINAL_WORKER_IMAGE_NAMES].some(imageName => imageNames.has(imageName));
  addCheck("pattern.generator", hasGenerator, "Scenario has a generator bee", "warning");
  addCheck("pattern.terminal", hasTerminal, "Scenario has a terminal postprocessor or clearing-export bee", "warning");
  addCheck("scenario.topology", Object.prototype.hasOwnProperty.call(scenario || {}, "topology"), "scenario.topology is declared", "warning");
  const topologyEdges = Array.isArray(scenario?.topology?.edges) ? scenario.topology.edges : [];
  for (const [edgeIndex, edge] of topologyEdges.entries()) {
    for (const endpointName of ["from", "to"]) {
      const endpoint = edge?.[endpointName];
      if (!endpoint || typeof endpoint !== "object") {
        addCheck(`topology.${edgeIndex}.${endpointName}`, false, `Topology edge ${edgeIndex + 1} ${endpointName} endpoint is required`);
        continue;
      }
      if (Object.prototype.hasOwnProperty.call(endpoint, "beeId")) {
        addCheck(
          `topology.${edgeIndex}.${endpointName}.no-beeId`,
          false,
          "topology endpoint beeId is not supported; use role"
        );
      }
      const endpointRole = endpoint.role;
      addCheck(
        `topology.${edgeIndex}.${endpointName}.role`,
        typeof endpointRole === "string" && roles.has(endpointRole),
        `Topology edge ${edgeIndex + 1} ${endpointName} role references a declared bee role`
      );
    }
  }
  addCheck("scenario.trafficPolicy", Object.prototype.hasOwnProperty.call(scenario || {}, "trafficPolicy"), "scenario.trafficPolicy is declared", "warning");

  const artifacts = {
    templates: existsSync(resolve(targetBundleDir, "templates")),
    datasets: existsSync(resolve(targetBundleDir, "datasets")),
    sut: existsSync(resolve(targetBundleDir, "sut")),
    variables: existsSync(resolve(targetBundleDir, "variables.yaml")),
    mockConfig: existsSync(resolve(targetBundleDir, "mock-config")),
    readme: existsSync(resolve(targetBundleDir, "README.md")),
    flowDocument: existsSync(resolve(targetBundleDir, "FLOW_DOCUMENT.md")),
    changelog: existsSync(resolve(targetBundleDir, "CHANGELOG.md")),
  };

  const authProfilePath = resolve(targetBundleDir, "authProfiles.yaml");
  let authProfiles = {};
  if (existsSync(authProfilePath)) {
    try {
      authProfiles = parseYaml(readFileSync(authProfilePath, "utf8"))?.profiles || {};
      addCheck("authProfiles.parse", true, "authProfiles.yaml parses as YAML");
    } catch (e) {
      addCheck("authProfiles.parse", false, `authProfiles.yaml parse failed: ${e.message}`);
    }
  }

  const endpoints = Array.isArray(scenario?.plan?.endpoints) ? scenario.plan.endpoints : [];
  const endpointCallIds = new Set();
  for (const [index, endpoint] of endpoints.entries()) {
    const label = `endpoint.${index + 1}`;
    const method = String(endpoint?.method || "").toUpperCase();
    const path = String(endpoint?.path || "");
    const callId = String(endpoint?.callId || "");
    addCheck(`${label}.method`, WIZARD_HTTP_METHODS.includes(method), `${label} method is supported`);
    addCheck(`${label}.path`, path.startsWith("/"), `${label} path is absolute`);
    addCheck(`${label}.callId`, Boolean(callId), `${label} callId is set`);
    if (callId) {
      addCheck(`${label}.callId.unique`, !endpointCallIds.has(callId), `${label} callId '${callId}' is unique`);
      endpointCallIds.add(callId);
      const serviceId = endpoint.serviceId || (scenario.plan?.pattern === "sequence" ? "sequence" : "default");
      const templatePath = resolve(targetBundleDir, "templates", "http", serviceId, `${callId}.yaml`);
      addCheck(`${label}.template.exists`, existsSync(templatePath), `${label} HTTP template exists at templates/http/${serviceId}/${callId}.yaml`);
      if (existsSync(templatePath)) {
        try {
          const template = parseYaml(readFileSync(templatePath, "utf8"));
          addCheck(`${label}.template.callId`, template?.callId === callId, `${label} template callId matches plan`);
          addCheck(`${label}.template.method`, String(template?.method || "").toUpperCase() === method, `${label} template method matches plan`);
          addCheck(`${label}.template.pathTemplate`, typeof template?.pathTemplate === "string" && template.pathTemplate.startsWith(path), `${label} template path starts with plan path`);
          if (template?.authRef?.profileId) {
            addCheck(`${label}.template.authRef`, Boolean(authProfiles[template.authRef.profileId]), `${label} authRef profile exists`);
          }
        } catch (e) {
          addCheck(`${label}.template.parse`, false, `${label} template parse failed: ${e.message}`);
        }
      }
    }
  }

  const mockDir = resolve(targetBundleDir, "mock-config", "wiremock");
  if (existsSync(mockDir)) {
    for (const entry of readdirSync(mockDir, { withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith(".json")) continue;
      const path = resolve(mockDir, entry.name);
      try {
        const mock = JSON.parse(readFileSync(path, "utf8"));
        addCheck(`mock.${entry.name}.request`, Boolean(mock?.request?.method && mock?.request?.urlPath), `${entry.name} declares request method and urlPath`);
        addCheck(`mock.${entry.name}.response`, Number.isFinite(Number(mock?.response?.status)), `${entry.name} declares response status`);
      } catch (e) {
        addCheck(`mock.${entry.name}.parse`, false, `${entry.name} parse failed: ${e.message}`);
      }
    }
  }

  return {
    ok: errors.length === 0,
    bundle,
    path: targetBundleDir,
    scenarioId: scenario?.id,
    checks,
    errors,
    warnings,
    artifacts,
    source: "bundle.generation-sanity",
  };
}

class BundlePackagingError extends Error {
  constructor(message, { path = null, fix = "Repair the generated bundle files and rerun validation." } = {}) {
    super(message);
    this.name = "BundlePackagingError";
    this.code = "BUNDLE_PACKAGING_FAILED";
    this.localGenerationDefect = true;
    this.finding = {
      category: "bundle",
      code: "BUNDLE_PACKAGING_FAILED",
      severity: "error",
      path,
      message,
      fix,
    };
  }
}

async function createBundleZipBytes(bundle) {
  const targetBundleDir = bundleDir(bundle);
  if (!existsSync(targetBundleDir)) {
    throw new BundlePackagingError(`Bundle directory not found for '${bundle}'`, {
      fix: "Regenerate the workflow bundle or correct the bundle id, then rerun validation.",
    });
  }
  if (!existsSync(resolve(targetBundleDir, "scenario.yaml"))) {
    throw new BundlePackagingError(`No scenario.yaml found in bundle '${bundle}'`, {
      path: "scenario.yaml",
      fix: "Restore scenario.yaml in the generated bundle, then rerun validation.",
    });
  }
  const { createWriteStream } = await import("node:fs");
  const archiver = await import("archiver").catch(() => null);
  if (!archiver) throw new Error("archiver dependency is required for Scenario Manager bundle upload validation");

  const os = await import("node:os");
  const tmpZip = resolve(os.tmpdir(), `ph-bundle-${bundle}-${Date.now()}.zip`);
  await new Promise((res, rej) => {
    const output = createWriteStream(tmpZip);
    const archive = archiver.default("zip", { zlib: { level: 6 } });
    output.on("close", res);
    archive.on("error", rej);
    archive.pipe(output);
    archive.directory(targetBundleDir, false);
    archive.finalize();
  });
  const zipBytes = readFileSync(tmpZip);
  try { unlinkSync(tmpZip); } catch { /* ignore */ }
  return zipBytes;
}

async function scenarioManagerBundleExists(bundle) {
  try {
    await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(bundle)}`);
    return true;
  } catch {
    return false;
  }
}

async function scenarioManagerUploadBundle(bundle, { replaceExisting = true } = {}) {
  const zipBytes = await createBundleZipBytes(bundle);
  const exists = await scenarioManagerBundleExists(bundle);
  if (exists && !replaceExisting) {
    throw new Error(`Scenario '${bundle}' already exists in Scenario Manager; set replaceExisting=true for upload validation`);
  }
  try {
    if (exists) {
      const scenario = await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(bundle)}/bundle`, {
        method: "PUT",
        body: zipBytes,
        headers: { "content-type": "application/zip" },
        timeoutMs: 60000,
      });
      return { uploaded: true, method: "http-replace", scenario };
    }
    const scenario = await httpJson(`${SM_URL}/scenarios/bundles`, {
      method: "POST",
      body: zipBytes,
      headers: { "content-type": "application/zip" },
      timeoutMs: 60000,
    });
    return { uploaded: true, method: "http-create", scenario };
  } catch (e) {
    throw new Error(`Scenario Manager rejected bundle '${bundle}' (${exists ? "PUT replace" : "POST create"}): ${e.message}`);
  }
}

async function scenarioManagerDryRunValidateBundle(bundle) {
  const zipBytes = await createBundleZipBytes(bundle);
  try {
    return await httpJson(`${SM_URL}/validation/scenario-bundles`, {
      method: "POST",
      body: zipBytes,
      headers: { "content-type": "application/zip" },
      timeoutMs: 60000,
    });
  } catch (e) {
    throw new Error(`Scenario Manager dry-run validation rejected bundle '${bundle}': ${e.message}`);
  }
}

async function loadBundleMockConfig(bundle) {
  const targetBundleDir = bundleDir(bundle);
  const result = {
    bundle,
    wiremock: {
      attempted: false,
      loaded: 0,
      files: [],
    },
    requestJournal: {
      reset: false,
    },
  };

  const wiremockDir = resolve(targetBundleDir, "mock-config", "wiremock");
  if (existsSync(wiremockDir)) {
    const files = readdirSync(wiremockDir, { withFileTypes: true })
      .filter(entry => entry.isFile() && entry.name.endsWith(".json"))
      .map(entry => entry.name)
      .sort();
    result.wiremock.attempted = true;
    for (const file of files) {
      const relativePath = `mock-config/wiremock/${file}`;
      const mapping = JSON.parse(readFileSync(resolve(wiremockDir, file), "utf8"));
      await httpJson(`${WIREMOCK_URL}/__admin/mappings`, { method: "POST", body: mapping, timeoutMs: 10000 });
      result.wiremock.loaded += 1;
      result.wiremock.files.push(relativePath);
    }
  }

  if (result.wiremock.loaded > 0) {
    await httpJson(`${WIREMOCK_URL}/__admin/requests`, { method: "DELETE", timeoutMs: 10000 });
    result.requestJournal.reset = true;
  }

  return result;
}

const _scenarioContractCache = new Map(); // baseUrl -> cached authoring contract

async function scenarioManagerContractSnapshot({
  scenarioId = "",
  includeCapabilities = true,
  includeTemplates = true,
  forceRefresh = false,
  checkFingerprint = false,
} = {}) {
  const cacheKey = SM_URL;
  let cached = _scenarioContractCache.get(cacheKey);
  let cacheState = cached ? "hit" : "miss";

  if (cached && checkFingerprint && !forceRefresh) {
    const remote = await httpJson(`${SM_URL}/api/authoring-contract/fingerprint`);
    if (remote?.fingerprint && remote.fingerprint !== cached.contract?.fingerprint) {
      cacheState = "stale";
      cached = null;
    }
  }

  if (!cached || forceRefresh) {
    const contract = await httpJson(`${SM_URL}/api/authoring-contract`);
    cached = { contract, fetchedAt: new Date().toISOString() };
    _scenarioContractCache.set(cacheKey, cached);
    cacheState = forceRefresh ? "refresh" : cacheState === "stale" ? "refresh-after-stale" : "miss";
  }

  const snapshot = {
    source: "scenario-manager-api",
    baseUrl: SM_URL,
    endpoints: {
      ...(cached.contract?.endpoints || {}),
      scenario: scenarioId ? `${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}` : null,
    },
    contract: cached.contract,
    cache: {
      state: cacheState,
      fetchedAt: cached.fetchedAt,
      fingerprint: cached.contract?.fingerprint || null,
      checkFingerprint,
      forceRefresh,
    },
  };
  if (includeCapabilities) snapshot.capabilities = cached.contract?.capabilities?.manifests || [];
  if (includeTemplates) snapshot.templates = cached.contract?.templateCatalog || [];
  if (scenarioId) snapshot.scenario = await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}`);
  return snapshot;
}

// In-memory job store for async validation results
const _validateJobs = new Map(); // jobId -> { status, result, error, startedAt }

// Sweep stale jobs older than 10 minutes to prevent unbounded growth
setInterval(() => {
  const cutoff = Date.now() - 10 * 60 * 1000;
  for (const [id, job] of _validateJobs) {
    if (job.startedAt < cutoff) _validateJobs.delete(id);
  }
}, 60_000).unref();

reg("bundle.validate", "Start async Scenario Manager validation of a bundle. Returns a jobId immediately. Poll with bundle.validate.result.", {
  bundle: BUNDLE_ARG,
  validator: z.enum(["scenario-manager-dry-run", "scenario-manager-upload"]).optional().default("scenario-manager-dry-run").describe("scenario-manager-dry-run validates through Scenario Manager without writes. scenario-manager-upload validates through Scenario Manager upload/replace and has write side effects."),
  replaceExisting: z.boolean().optional().describe("Only for scenario-manager-upload. Allows replacing an existing Scenario Manager bundle."),
}, async ({ bundle, validator = "scenario-manager-dry-run", replaceExisting = true }) => {
  const jobId = `${bundle}-${validator}-${Date.now()}`;
  _validateJobs.set(jobId, { status: "running", result: null, error: null, startedAt: Date.now() });

  (async () => {
    try {
      let result;
      if (validator === "scenario-manager-dry-run") {
        result = {
          mode: validator,
          source: "scenario-manager-api",
          scenarioManager: await scenarioManagerDryRunValidateBundle(bundle),
          note: "Scenario Manager dry-run validation uses the running Scenario Manager contract without importing or replacing the bundle.",
        };
      } else {
        result = {
          mode: validator,
          source: "scenario-manager-api",
          scenarioManager: await scenarioManagerUploadBundle(bundle, { replaceExisting }),
          note: "Scenario Manager upload/replace validates using the running Scenario Manager contract and stores/replaces the bundle there.",
        };
      }
      _validateJobs.set(jobId, { status: "done", result, error: null, startedAt: _validateJobs.get(jobId).startedAt });
    } catch (e) {
      _validateJobs.set(jobId, { status: "error", result: null, error: e.message, startedAt: _validateJobs.get(jobId).startedAt });
    }
  })();

  return { jobId, status: "running", message: "Validation started. Poll with bundle.validate.result." };
});

reg("bundle.validate.result", "Poll for the result of a bundle.validate job.", {
  jobId: z.string().describe("jobId returned by bundle.validate"),
}, async ({ jobId }) => {
  const job = _validateJobs.get(jobId);
  if (!job) throw new Error(`No job found for jobId: ${jobId}`);
  const elapsed = Math.round((Date.now() - job.startedAt) / 1000);
  if (job.status === "running") return { jobId, status: "running", elapsedSeconds: elapsed };
  if (job.status === "error") return { jobId, status: "error", error: job.error, elapsedSeconds: elapsed };
  _validateJobs.delete(jobId); // clean up after reading
  return { jobId, status: "done", elapsedSeconds: elapsed, ...job.result };
});

reg("scenario.deploy", "Deploy a bundle to the Scenario Manager's scenarios directory and reload. Works against both local and remote stacks through the HTTP bundle upload API.", {
  bundle: BUNDLE_ARG,
}, async ({ bundle }) => {
  const result = await scenarioManagerUploadBundle(bundle, { replaceExisting: true });
  return { deployed: true, bundle, method: result.method, scenario: result.scenario };
});

reg("scenario.list", "List scenarios loaded in the Scenario Manager", {}, async () => {
  return await httpJson(`${SM_URL}/scenarios`);
});

reg("scenario.get", "Get a specific scenario from the Scenario Manager", {
  scenarioId: SCENARIO_ID_ARG,
}, async ({ scenarioId }) => {
  return await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}`);
});

reg("scenario.raw.read", "Read raw scenario YAML through Scenario Manager.", {
  scenarioId: SCENARIO_ID_ARG,
}, async ({ scenarioId }) => {
  const content = await httpText(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}/raw`, {
    accept: "text/plain",
  });
  return { scenarioId, content };
});

reg("scenario.raw.write", "Write raw scenario YAML through Scenario Manager.", {
  scenarioId: SCENARIO_ID_ARG,
  content: z.string(),
}, async ({ scenarioId, content }) => {
  const response = await httpText(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}/raw`, {
    method: "PUT",
    body: content,
    accept: "text/plain",
    contentType: "text/plain",
  });
  return { scenarioId, written: true, response };
});

reg("scenario.schema.read", "Read a scenario schema file through Scenario Manager.", {
  scenarioId: SCENARIO_ID_ARG,
  path: z.string(),
}, async ({ scenarioId, path }) => {
  const content = await httpText(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}/schema?path=${encodeURIComponent(path)}`, {
    accept: "text/plain",
  });
  return { scenarioId, path, content };
});

reg("scenario.template.read", "Read a scenario template file through Scenario Manager.", {
  scenarioId: SCENARIO_ID_ARG,
  path: z.string(),
}, async ({ scenarioId, path }) => {
  const content = await httpText(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}/template?path=${encodeURIComponent(path)}`, {
    accept: "text/plain",
  });
  return { scenarioId, path, content };
});

reg("scenario.contracts.get", "Read Scenario Manager-backed contracts and capability manifests. This is the runtime contract source for wizard generation.", {
  scenarioId: SCENARIO_ID_ARG.optional(),
  includeCapabilities: z.boolean().optional(),
  includeTemplates: z.boolean().optional(),
  forceRefresh: z.boolean().optional(),
  checkFingerprint: z.boolean().optional(),
}, async ({ scenarioId = "", includeCapabilities = true, includeTemplates = true, forceRefresh = false, checkFingerprint = false }) => {
  return await scenarioManagerContractSnapshot({ scenarioId, includeCapabilities, includeTemplates, forceRefresh, checkFingerprint });
});

reg("scenario.capabilities.get", "Read Scenario Manager worker capability manifests from /api/capabilities.", {
  imageName: z.string().optional(),
  tag: z.string().optional(),
  all: z.boolean().optional(),
}, async ({ imageName = "", tag = "latest", all = false }) => {
  if (all || !imageName) return await httpJson(`${SM_URL}/api/capabilities?all=true`);
  return await httpJson(`${SM_URL}/api/capabilities?imageName=${encodeURIComponent(imageName)}&tag=${encodeURIComponent(tag)}`);
});

reg("scenario.templates.catalog", "Read Scenario Manager's bundle catalog from /api/templates, including defunct validation status.", {}, async () => {
  return await httpJson(`${SM_URL}/api/templates`);
});

// ── Swarm lifecycle ───────────────────────────────────────────────────────────

reg("swarm.list", "List all swarms from the Orchestrator", {}, async () => {
  return await httpJson("/api/swarms");
});

reg("swarm.get", "Get swarm status from the Orchestrator", {
  swarmId: SWARM_ID_ARG,
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}`);
});

reg("swarm.create", "Create a new swarm from a scenario template", {
  swarmId: SWARM_ID_ARG.describe("Unique swarm identifier"),
  templateId: z.string().describe("Scenario template ID (must match scenario.yaml id)"),
  sutId: z.string().optional().describe("SUT environment ID"),
  variablesProfileId: z.string().optional().describe("Variables profile ID"),
}, async ({ swarmId, templateId, sutId, variablesProfileId }) => {
  const body = { templateId, idempotencyKey: idempotencyKey() };
  if (sutId) body.sutId = sutId;
  if (variablesProfileId) body.variablesProfileId = variablesProfileId;
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/create`, { method: "POST", body });
});

reg("swarm.start", "Start a created swarm", {
  swarmId: SWARM_ID_ARG,
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/start`, {
    method: "POST", body: { idempotencyKey: idempotencyKey() },
  });
});

reg("swarm.wait-ready", "Poll swarm status until all workers are healthy (totals.healthy == totals.desired). Call this after swarm.create before swarm.start to avoid NotReady rejections.", {
  swarmId: SWARM_ID_ARG,
  timeoutSec: z.number().optional().default(90),
}, async ({ swarmId, timeoutSec }) => {
  // Cap at 80s to stay safely under the MCP framework's ~150s tool timeout.
  // For longer waits the caller should poll swarm.get manually.
  const effectiveTimeout = Math.min(timeoutSec, 80);
  const deadline = Date.now() + effectiveTimeout * 1000;
  let lastCtx = null;
  let polls = 0;
  while (Date.now() < deadline) {
    try {
      const status = await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}`);
      const ctx = status?.envelope?.data?.context;
      if (ctx) {
        lastCtx = ctx;
        const { desired, healthy } = ctx.totals || {};
        if (desired > 0 && healthy >= desired && ctx.swarmStatus === "READY") {
          return { ready: true, swarmId, totals: ctx.totals, swarmStatus: ctx.swarmStatus, polls };
        }
      }
    } catch { /* transient — keep polling */ }
    polls++;
    await new Promise(r => setTimeout(r, 4000));
  }
  // Return current state rather than throwing — lets the caller decide whether to retry
  return {
    ready: false,
    swarmId,
    totals: lastCtx?.totals ?? null,
    swarmStatus: lastCtx?.swarmStatus ?? "unknown",
    polls,
    message: `Did not reach READY within ${effectiveTimeout}s. Call swarm.get to check current state, then retry swarm.start when healthy==desired.`,
  };
});

reg("swarm.stop", "Stop a running swarm (non-destructive)", {
  swarmId: SWARM_ID_ARG,
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/stop`, {
    method: "POST", body: { idempotencyKey: idempotencyKey() },
  });
});

reg("swarm.remove", "Remove a swarm (destructive — tears down containers and queues)", {
  swarmId: SWARM_ID_ARG,
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/remove`, {
    method: "POST", body: { idempotencyKey: idempotencyKey() },
  });
});

// ── Debugging ─────────────────────────────────────────────────────────────────

reg("debug.queues", "List RabbitMQ queues (optionally filtered by swarm prefix)", {
  swarmId: SWARM_ID_ARG.optional().describe("Filter queues by swarm ID prefix"),
}, async ({ swarmId }) => {
  const queues = await httpJson(`${RABBIT_MGMT}/queues`, { headers: { authorization: rabbitAuth() } });
  if (!swarmId) return queues.map(q => ({ name: q.name, messages: q.messages, consumers: q.consumers }));
  const prefix = `ph.${swarmId}.`;
  const controlPrefix = `ph.control.${swarmId}.`;
  return queues
    .filter(q => q.name.startsWith(prefix) || q.name.startsWith(controlPrefix))
    .map(q => ({ name: q.name, messages: q.messages, consumers: q.consumers }));
});

reg("debug.tap", "Create a debug tap to sample data-plane messages from a swarm queue", {
  swarmId: SWARM_ID_ARG,
  role: z.string().describe("Worker role to tap, e.g. 'postprocessor'"),
  direction: z.enum(["IN", "OUT"]).default("IN"),
  ioName: z.string().default("in"),
  maxItems: z.number().default(3),
}, async ({ swarmId, role, direction, ioName, maxItems }) => {
  return await httpJson("/api/debug/taps", {
    method: "POST",
    body: { swarmId, role, direction, ioName, maxItems, ttlSeconds: 120 },
  });
});

reg("debug.tap.read", "Read samples from a debug tap", {
  tapId: z.string(),
  drain: z.number().optional(),
}, async ({ tapId, drain }) => {
  const query = drain !== undefined ? `?drain=${drain}` : "";
  return await httpJson(`/api/debug/taps/${encodeURIComponent(tapId)}${query}`);
});

reg("debug.tap.close", "Close and delete a debug tap", {
  tapId: z.string(),
}, async ({ tapId }) => {
  return await httpJson(`/api/debug/taps/${encodeURIComponent(tapId)}`, { method: "DELETE" });
});

reg("debug.journal", "Read swarm journal (timeline of control-plane events)", {
  swarmId: SWARM_ID_ARG,
  limit: z.number().optional().default(50),
  severity: z.enum(["ERROR", "WARN", "INFO"]).optional(),
}, async ({ swarmId, limit, severity }) => {
  const query = new URLSearchParams();
  query.set("limit", String(limit));
  if (severity !== undefined) query.set("severity", severity);
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/journal/page?${query.toString()}`);
});

reg("debug.hive-journal", "Read hive-level journal entries through Orchestrator.", {
  limit: z.number().optional().default(50),
}, async ({ limit }) => {
  return await httpJson(`/api/journal/hive/page?limit=${limit}`);
});

registerRuntimeTools(reg, {
  httpJson,
  rabbitManagementBaseUrl: RABBIT_MGMT,
  rabbitAuth,
  rabbitVhost: process.env.RABBITMQ_VHOST || "/"
});

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function controlScope({ swarmId, role, instanceId }) {
  return {
    swarmId,
    role,
    instance: instanceId,
  };
}

function controlRouting(signal, { swarmId, role, instanceId }) {
  return `signal.${signal}.${swarmId}.${role}.${instanceId}`;
}

function metricRouting(metric, { swarmId, role, instanceId }) {
  return `event.metric.${metric}.${swarmId}.${role}.${instanceId}`;
}

async function requestComponentStatusConfig({ swarmId, role, instanceId, timeoutMs = 10000 }) {
  const conn = await amqplib.connect(rabbitUrl());
  const ch = await conn.createChannel();
  const exchange = controlExchange();
  const target = { swarmId, role, instanceId };
  const statusRoutingKey = metricRouting("status-full", target);
  const requestRoutingKey = controlRouting("status-request", target);
  const correlationId = idempotencyKey();
  const requestKey = `status-request:${correlationId}`;

  try {
    await ch.assertExchange(exchange, "topic", { durable: true });
    const queue = await ch.assertQueue("", { exclusive: true, autoDelete: true });
    await ch.bindQueue(queue.queue, exchange, statusRoutingKey);

    const statusPromise = new Promise((resolveStatus, rejectStatus) => {
      const timer = setTimeout(() => {
        rejectStatus(new Error(`Timed out waiting for ${statusRoutingKey}`));
      }, timeoutMs);

      ch.consume(queue.queue, (msg) => {
        if (!msg) return;
        let payload = null;
        try {
          payload = JSON.parse(msg.content.toString("utf8"));
        } catch {
          return;
        }
        const scope = payload?.scope || {};
        const config = configFromJournalEntry(payload);
        if (
          payload?.kind === "metric" &&
          payload?.type === "status-full" &&
          scope.swarmId === swarmId &&
          scope.role === role &&
          scope.instance === instanceId &&
          config
        ) {
          clearTimeout(timer);
          resolveStatus({
            matchedRole: role,
            matchedInstanceId: instanceId,
            receivedAt: payload.timestamp || null,
            runId: payload.runtime?.runId || null,
            config,
          });
        }
      }, { noAck: true }).catch((error) => {
        clearTimeout(timer);
        rejectStatus(error);
      });
    });

    const signal = {
      version: "1",
      kind: "signal",
      type: "status-request",
      origin: "pockethive-mcp",
      scope: controlScope(target),
      correlationId,
      idempotencyKey: requestKey,
      data: {},
    };
    ch.publish(exchange, requestRoutingKey, Buffer.from(JSON.stringify(signal)), {
      contentType: "application/json",
    });
    const swarmSignal = {
      ...signal,
      scope: { swarmId, role: "ALL", instance: "ALL" },
    };
    ch.publish(exchange, controlRouting("status-request", { swarmId, role: "ALL", instanceId: "ALL" }), Buffer.from(JSON.stringify(swarmSignal)), {
      contentType: "application/json",
    });
    const refresh = await httpJson("/api/control-plane/refresh", { method: "POST", timeoutMs: 10000 }).catch(() => null);
    if (refresh?.throttled) {
      await sleep(2200);
      await httpJson("/api/control-plane/refresh", { method: "POST", timeoutMs: 10000 }).catch(() => null);
    }

    const selected = await statusPromise;
    return {
      source: `${exchange}:${statusRoutingKey}`,
      refreshRequested: true,
      ...selected,
    };
  } finally {
    try { await ch.close(); } catch { /* ignore */ }
    try { await conn.close(); } catch { /* ignore */ }
  }
}

async function currentComponentConfig({ swarmId, role, instanceId, refreshStatus = true, journalLimit = 500 }) {
  const endpoint = `/api/swarms/${encodeURIComponent(swarmId)}/journal/page?limit=${journalLimit}`;
  const page = await httpJson(endpoint, { timeoutMs: 10000 });
  const selected = latestComponentConfigFromJournalPage(page, { role, instanceId });

  if (selected) {
    return {
      source: `${ORCH_URL}${endpoint}`,
      refreshRequested: refreshStatus,
      ...selected,
    };
  }

  if (refreshStatus) {
    try {
      return await requestComponentStatusConfig({ swarmId, role, instanceId });
    } catch {
      // Fall through to the explicit unavailable error below.
    }
  }

  {
    throw new Error(
      `CURRENT_CONFIG_UNAVAILABLE: no latest status-full config found for ${role}/${instanceId} in swarm ${swarmId}. ` +
      "Request a fresh control-plane status snapshot and retry, or use the exact instance id from the UI component stream."
    );
  }
}

async function planLiveComponentConfigUpdate({ swarmId, role, instanceId, patch, allowEmptyPatch = false, refreshStatus = true }) {
  const current = await currentComponentConfig({ swarmId, role, instanceId, refreshStatus });
  return {
    current,
    ...planComponentConfigUpdate({ currentConfig: current.config, patch, allowEmptyPatch }),
  };
}

function configUpdatePlanResponse({ current, patchSummary, mergedConfigSummary, mergedConfig, includeMergedConfig = false }) {
  return {
    mode: "merge-with-current-config",
    currentConfig: {
      source: current.source,
      refreshRequested: current.refreshRequested,
      matchedRole: current.matchedRole,
      matchedInstanceId: current.matchedInstanceId,
      receivedAt: current.receivedAt,
      runId: current.runId,
      topLevelKeys: summarizePatch(current.config).topLevelKeys,
    },
    patchSummary,
    mergedConfigSummary,
    ...(includeMergedConfig ? { mergedConfig } : {}),
  };
}

async function sendComponentConfigUpdate({
  swarmId,
  role,
  instanceId,
  patch,
  idempotencyKey: providedIdempotencyKey = "",
  notes = "",
  allowEmptyPatch = false,
  refreshStatus = true,
}) {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) {
    throw new Error("patch must be an object");
  }
  const plan = await planLiveComponentConfigUpdate({ swarmId, role, instanceId, patch, allowEmptyPatch, refreshStatus });
  const key = providedIdempotencyKey || idempotencyKey();
  const endpoint = `/api/components/${encodeURIComponent(role)}/${encodeURIComponent(instanceId)}/config`;
  const body = {
    idempotencyKey: key,
    patch: plan.dispatchPatch,
    swarmId,
    ...(notes ? { notes } : {}),
  };
  const response = await httpJson(endpoint, {
    method: "POST",
    body,
  });
  return {
    accepted: true,
    source: "orchestrator-api",
    endpoint: `${ORCH_URL}${endpoint}`,
    target: { swarmId, role, instanceId },
    idempotencyKey: key,
    ...configUpdatePlanResponse(plan),
    response,
    watch: response?.watch || null,
    evidenceNext: [
      {
        tool: "debug.journal",
        input: { swarmId, limit: 50 },
        purpose: "Check control-plane dispatch, outcome, alert, and worker status entries after the update.",
      },
      {
        uiObservationApi: "STOMP /exchange/ph.control/#",
        purpose: "The web UI watches this read-only stream for status-full, outcome, alert, and queue metric events.",
      },
      {
        orchestratorApi: "POST /api/control-plane/refresh",
        purpose: "Request fresh status-full snapshots if component state is stale after the config update.",
      },
      {
        tool: "debug.prometheus",
        inputHint: {
          query: `ph_swarm_queue_depth{ph_swarm="${swarmId}"}`,
        },
        purpose: "Inspect queue depth or worker metrics to verify runtime effect, especially for rate changes.",
      },
    ],
  };
}

reg("component.config-preview", "Preview the merge-with-current-config plan for a running component without sending an update.", {
  swarmId: SWARM_ID_ARG,
  role: z.string(),
  instanceId: z.string(),
  patch: z.record(z.any()).describe("Config patch object, e.g. {enabled: true, ratePerSec: 10}"),
  allowEmptyPatch: z.boolean().optional().describe("Defaults false. Set true only when an explicit empty config-update/reset is intended."),
  refreshStatus: z.boolean().optional().describe("Defaults true. Requests fresh status-full snapshots before reading the current config from the journal."),
  includeMergedConfig: z.boolean().optional().describe("Defaults false. Set true to include the full merged config in the response for review."),
}, async ({ swarmId, role, instanceId, patch, allowEmptyPatch = false, refreshStatus = true, includeMergedConfig = false }) => {
  const plan = await planLiveComponentConfigUpdate({ swarmId, role, instanceId, patch, allowEmptyPatch, refreshStatus });
  return {
    sideEffect: "no-config-write",
    target: { swarmId, role, instanceId },
    ...configUpdatePlanResponse({ ...plan, includeMergedConfig }),
  };
});

reg("component.config-update", "Send a real-time config-update signal to one running component through the Orchestrator API used by the UI.", {
  swarmId: SWARM_ID_ARG,
  role: z.string(),
  instanceId: z.string(),
  patch: z.record(z.any()).describe("Config patch object, e.g. {enabled: true, ratePerSec: 10}"),
  idempotencyKey: z.string().optional(),
  notes: z.string().optional(),
  allowEmptyPatch: z.boolean().optional().describe("Defaults false. Set true only when an explicit empty config-update/reset is intended."),
  refreshStatus: z.boolean().optional().describe("Defaults true. Requests fresh status-full snapshots before reading the current config from the journal."),
}, async ({ swarmId, role, instanceId, patch, idempotencyKey: requestedKey = "", notes = "", allowEmptyPatch = false, refreshStatus = true }) => {
  return await sendComponentConfigUpdate({
    swarmId,
    role,
    instanceId,
    patch,
    idempotencyKey: requestedKey,
    notes,
    allowEmptyPatch,
    refreshStatus,
  });
});

reg("debug.config-update", "Compatibility alias for component.config-update.", {
  swarmId: SWARM_ID_ARG,
  role: z.string(),
  instanceId: z.string(),
  patch: z.record(z.any()).describe("Config patch object, e.g. {enabled: true, ratePerSec: 10}"),
}, async ({ swarmId, role, instanceId, patch }) => {
  return await sendComponentConfigUpdate({ swarmId, role, instanceId, patch });
});

reg("debug.prometheus", "Query Prometheus for metrics (instant query). Use to verify postprocessor metrics are flowing, e.g. ph_transaction_total_latency_ms", {
  query: z.string().describe("PromQL query, e.g. ph_transaction_total_latency_ms{ph_swarm=\"my-swarm\"}"),
}, async ({ query }) => {
  return await httpJson(`${PROM_URL}/api/v1/query?query=${encodeURIComponent(query)}`, { timeoutMs: 10000 });
});

async function evidenceSource(name, fn) {
  try {
    return { name, status: "ok", data: await fn() };
  } catch (e) {
    return { name, status: "unavailable", error: e.message || String(e) };
  }
}

function countArrayLike(value) {
  if (Array.isArray(value)) return value.length;
  if (Array.isArray(value?.requests)) return value.requests.length;
  if (Array.isArray(value?.requestJournal)) return value.requestJournal.length;
  if (Array.isArray(value?.mappings)) return value.mappings.length;
  return undefined;
}

function redisEncode(args) {
  return `*${args.length}\r\n` + args.map(arg => {
    const value = Buffer.from(String(arg));
    return `$${value.length}\r\n${value.toString("utf8")}\r\n`;
  }).join("");
}

class RespIncomplete extends Error {}

function parseResp(buffer, offset = 0) {
  if (offset >= buffer.length) throw new RespIncomplete();
  const type = String.fromCharCode(buffer[offset]);
  const lineEnd = buffer.indexOf("\r\n", offset);
  if (lineEnd < 0) throw new RespIncomplete();
  const line = buffer.toString("utf8", offset + 1, lineEnd);
  const next = lineEnd + 2;
  if (type === "+") return { value: line, offset: next };
  if (type === "-") throw new Error(line);
  if (type === ":") return { value: Number(line), offset: next };
  if (type === "$") {
    const length = Number(line);
    if (length < 0) return { value: null, offset: next };
    const end = next + length;
    if (buffer.length < end + 2) throw new RespIncomplete();
    return { value: buffer.toString("utf8", next, end), offset: end + 2 };
  }
  if (type === "*") {
    const count = Number(line);
    if (count < 0) return { value: null, offset: next };
    const values = [];
    let cursor = next;
    for (let i = 0; i < count; i++) {
      const parsed = parseResp(buffer, cursor);
      values.push(parsed.value);
      cursor = parsed.offset;
    }
    return { value: values, offset: cursor };
  }
  throw new Error(`Unsupported Redis response type: ${type}`);
}

async function redisCommand(args, timeoutMs = 5000) {
  return await new Promise((resolvePromise, reject) => {
    const socket = net.createConnection({ host: REDIS_HOST, port: REDIS_PORT });
    let done = false;
    let chunks = Buffer.alloc(0);
    const timer = setTimeout(() => {
      finish(new Error(`Redis command timed out after ${timeoutMs}ms`));
    }, timeoutMs);
    function finish(err, value) {
      if (done) return;
      done = true;
      clearTimeout(timer);
      socket.destroy();
      if (err) reject(err);
      else resolvePromise(value);
    }
    socket.on("connect", () => socket.write(redisEncode(args)));
    socket.on("data", chunk => {
      chunks = Buffer.concat([chunks, chunk]);
      try {
        finish(null, parseResp(chunks).value);
      } catch (err) {
        if (!(err instanceof RespIncomplete)) finish(err);
      }
    });
    socket.on("error", finish);
  });
}

function hgetallToObject(values) {
  const out = {};
  if (!Array.isArray(values)) return out;
  for (let i = 0; i < values.length; i += 2) out[values[i]] = values[i + 1];
  return out;
}

function redactTokenRecord(record) {
  if (!record || typeof record !== "object") return record;
  const copy = { ...record };
  if (copy.accessToken) copy.accessToken = "<redacted>";
  return copy;
}

function redactHeaders(headers) {
  if (!headers || typeof headers !== "object") return headers;
  const copy = { ...headers };
  for (const key of Object.keys(copy)) {
    if (["authorization", "proxy-authorization", "x-api-key"].includes(key.toLowerCase())) {
      copy[key] = "<redacted>";
    }
  }
  return copy;
}

function redactDebugCapture(value) {
  if (!value || typeof value !== "object") return value;
  const copy = { ...value };
  if (copy.headers) copy.headers = redactHeaders(copy.headers);
  if (copy.request && typeof copy.request === "object") {
    copy.request = { ...copy.request, headers: redactHeaders(copy.request.headers) };
  }
  return copy;
}

async function readRedisEvidence(swarmId) {
  const tokenKeys = await redisCommand(["KEYS", `ph:tokens:${swarmId}:*`]);
  const recordKeys = (Array.isArray(tokenKeys) ? tokenKeys : []).filter(key => key.includes(":record:")).sort();
  const records = [];
  for (const key of recordKeys) {
    records.push({ key, ttl: await redisCommand(["TTL", key]), fields: redactTokenRecord(hgetallToObject(await redisCommand(["HGETALL", key]))) });
  }
  const dueKey = `ph:tokens:${swarmId}:due`;
  const due = Array.isArray(tokenKeys) && tokenKeys.includes(dueKey)
    ? await redisCommand(["ZRANGE", dueKey, "0", "-1", "WITHSCORES"])
    : [];
  return { tokenKeys: Array.isArray(tokenKeys) ? tokenKeys.sort() : [], records, due };
}

async function readRedisDebugCaptures(swarmId) {
  const keys = await redisCommand(["KEYS", `ph:debug:http-seq:${swarmId}:*`]);
  const sortedKeys = Array.isArray(keys) ? keys.sort() : [];
  const captures = [];
  for (const key of sortedKeys.slice(0, 50)) {
    const raw = await redisCommand(["GET", key]);
    let parsed = null;
    try { parsed = raw ? JSON.parse(raw) : null; } catch { parsed = { parseError: true }; }
    captures.push({ key, ttl: await redisCommand(["TTL", key]), data: redactDebugCapture(parsed) });
  }
  return { count: sortedKeys.length, keys: sortedKeys, captures };
}

function sourceData(sources, name) {
  const source = sources.find(s => s.name === name);
  return source?.status === "ok" ? source.data : null;
}

function inferScenarioId(swarmSource, fallbackSwarmId) {
  const fromRuntime = swarmSource?.envelope?.runtime?.templateId
    || swarmSource?.envelope?.data?.context?.workers?.find(worker => worker?.runtime?.templateId)?.runtime?.templateId;
  if (fromRuntime) return fromRuntime;
  const match = String(fallbackSwarmId || "").match(/^(.+)-\d{10,}$/);
  return match ? match[1] : null;
}

function pathOnly(url) {
  return String(url || "").split("?")[0];
}

function wiremockEntries(data) {
  return Array.isArray(data?.requests) ? data.requests : [];
}

function parseJsonLoose(value) {
  if (!value || typeof value !== "string") return null;
  try { return JSON.parse(value); } catch { return null; }
}

function flattenStringValues(value, prefix = "") {
  if (value == null) return [];
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    const text = String(value);
    return text.length >= 3 ? [{ path: prefix, value: text }] : [];
  }
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => flattenStringValues(item, `${prefix}/${index}`));
  }
  if (typeof value === "object") {
    return Object.entries(value).flatMap(([key, item]) => flattenStringValues(item, `${prefix}/${key}`));
  }
  return [];
}

function headerValue(headers, name) {
  if (!headers || typeof headers !== "object") return "";
  const found = Object.entries(headers).find(([key]) => key.toLowerCase() === name.toLowerCase());
  const value = found?.[1];
  return Array.isArray(value) ? value.join(",") : String(value || "");
}

function bodyPatternCount(entry) {
  const patterns = entry?.stubMapping?.request?.bodyPatterns;
  return Array.isArray(patterns) ? patterns.length : 0;
}

function tapSampleItems(tapSample) {
  const direct = tapSample?.samples;
  const read = tapSample?.read?.samples;
  if (Array.isArray(read)) return read;
  if (Array.isArray(direct)) return direct;
  return [];
}

function tapHasSamples(tapSample) {
  return tapSampleItems(tapSample).length > 0;
}

function knownTapPayloadValue(sample) {
  if (!sample || typeof sample !== "object") return sample;
  for (const key of ["body", "payload", "data", "message", "input", "content", "event"]) {
    if (Object.prototype.hasOwnProperty.call(sample, key)) {
      const value = sample[key];
      return typeof value === "string" ? parseJsonLoose(value) || value : value;
    }
  }
  return sample;
}

function tapPathOnly(value) {
  const text = String(value || "");
  if (!text) return "";
  try {
    const parsed = new URL(text);
    return pathOnly(parsed.pathname);
  } catch {
    return pathOnly(text);
  }
}

function tapPathValue(value) {
  return tapPathOnly(value?.path || value?.urlPath || value?.url || value?.uri || value?.endpoint);
}

function tapHeaderValue(headers, name) {
  if (!headers || typeof headers !== "object") return "";
  const found = Object.entries(headers).find(([key]) => key.toLowerCase() === name.toLowerCase());
  return found ? String(found[1] || "") : "";
}

function tapFlowItem(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const embedded = typeof value.payload === "string" ? parseJsonLoose(value.payload) : null;
  if (embedded && typeof embedded === "object" && !Array.isArray(embedded)) {
    const kind = String(embedded.kind || "");
    if (kind === "http.request" && !embedded.outcome) return null;
    const request = embedded.request || embedded;
    const method = String(request.method || request.httpMethod || "").toUpperCase();
    const path = tapPathOnly(request.path || request.urlPath || request.url || request.uri || request.endpoint);
    const callId = embedded.callId || request.callId || tapHeaderValue(value.headers, "x-ph-call-id") || tapHeaderValue(value.headers, "ph.call-id") || null;
    const status = embedded.outcome?.status || embedded.status || embedded.statusCode || null;
    if (method && path) {
      return { label: `${method} ${path}`, callId: callId ? String(callId) : null, status };
    }
  }
  const method = String(value.method || value.httpMethod || value.requestMethod || "").toUpperCase();
  const path = tapPathValue(value);
  const callId = value.callId || value.stepId || value.id || value.name || null;
  const status = value.status || value.statusCode || value.responseStatus || value.httpStatus || null;
  if (method && path) {
    return { label: `${method} ${pathOnly(path)}`, callId: callId ? String(callId) : null, status };
  }
  if (callId && (status || value.request || value.response || value.output || value.result)) {
    return { label: null, callId: String(callId), status };
  }
  return null;
}

function collectTapFlowItems(value, seen = new WeakSet()) {
  if (!value || typeof value !== "object") return [];
  if (seen.has(value)) return [];
  seen.add(value);
  if (Array.isArray(value)) {
    return value.flatMap(item => collectTapFlowItems(item, seen));
  }
  const sequenceKeys = ["steps", "calls", "flow", "requests", "results", "items", "outputs"];
  const rows = [];
  for (const key of sequenceKeys) {
    const child = value[key];
    if (child && typeof child === "object") rows.push(...collectTapFlowItems(child, seen));
  }
  if (rows.length) return rows;
  const item = tapFlowItem(value);
  if (item) return [item];
  for (const child of Object.values(value)) {
    if (child && typeof child === "object") rows.push(...collectTapFlowItems(child, seen));
  }
  return rows;
}

function arraysEqual(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function tapFlowEvidence(tapSample, expectedEndpoints, actualSequence) {
  const samples = tapSampleItems(tapSample);
  const expected = expectedEndpoints.map(endpoint => `${endpoint.method} ${endpoint.path}`);
  const expectedCallIds = expectedEndpoints.map(endpoint => endpoint.callId).filter(Boolean).map(String);
  const endpointByCallId = new Map(expectedEndpoints.filter(endpoint => endpoint.callId).map(endpoint => [String(endpoint.callId), endpoint]));
  const endpointByLabel = new Map(expectedEndpoints.map(endpoint => [`${endpoint.method} ${endpoint.path}`, endpoint]));
  const items = samples
    .map(knownTapPayloadValue)
    .flatMap(value => collectTapFlowItems(value));
  const observedLabels = items
    .map(item => item.label || (item.callId && endpointByCallId.has(item.callId)
      ? `${endpointByCallId.get(item.callId).method} ${endpointByCallId.get(item.callId).path}`
      : null))
    .filter(Boolean);
  const observedCallIds = items
    .map(item => item.callId || (item.label && endpointByLabel.get(item.label)?.callId) || null)
    .filter(Boolean)
    .map(String);
  const wiremockCallIds = actualSequence
    .map(label => endpointByLabel.get(label)?.callId || null)
    .filter(Boolean)
    .map(String);
  const matchedExpected = expected.length > 0 && (
    arraysEqual(observedLabels, expected)
    || (expectedCallIds.length > 0 && arraysEqual(observedCallIds, expectedCallIds))
  );
  const agreesWithWireMock = actualSequence.length > 0
    ? arraysEqual(observedLabels, actualSequence)
      || (wiremockCallIds.length > 0 && arraysEqual(observedCallIds, wiremockCallIds))
    : null;
  const extractable = observedLabels.length > 0 || observedCallIds.length > 0;
  return {
    sampleCount: samples.length,
    extractable,
    observed: observedLabels.length ? observedLabels : observedCallIds,
    observedCallIds,
    expected,
    expectedCallIds,
    wiremockObserved: actualSequence,
    matchedExpected,
    agreesWithWireMock,
    evidence: [
      `${samples.length} tap sample(s)`,
      ...items.map(item => item.label || item.callId).filter(Boolean),
    ],
  };
}

function authMatcherPresent(entry) {
  return !!entry?.stubMapping?.request?.headers?.Authorization;
}

function selectLatestExpectedSequence(entries, expectedEndpoints) {
  if (!expectedEndpoints.length) return entries;
  const expected = expectedEndpoints.map(endpoint => `${endpoint.method} ${endpoint.path}`);
  const labelled = entries.map(entry => ({
    entry,
    label: `${entry?.request?.method} ${pathOnly(entry?.request?.url)}`,
  }));
  for (let start = labelled.length - expected.length; start >= 0; start--) {
    const slice = labelled.slice(start, start + expected.length);
    if (slice.every((item, index) => item.label === expected[index])) {
      return slice.map(item => item.entry);
    }
  }
  return entries.slice(Math.max(0, entries.length - expected.length));
}

function makeClaim(id, label, status, summary, evidence = [], gaps = []) {
  return { id, label, status, summary, evidence, gaps };
}

const AUTH_REF_FIELDS = new Set(["auth", "authRef", "authProfile", "authProfileId", "authTokenUrl", "authClientId", "Authorization", "authorization"]);

function hasAuthConfiguration(value) {
  if (!value || typeof value !== "object") return false;
  if (Array.isArray(value)) return value.some(hasAuthConfiguration);
  for (const [key, child] of Object.entries(value)) {
    if (AUTH_REF_FIELDS.has(key) && child !== null && child !== undefined && child !== "" && child !== "none") return true;
    if (hasAuthConfiguration(child)) return true;
  }
  return false;
}

function reportVerdict(claims) {
  if (claims.some(claim => claim.status === "fail")) return "fail";
  if (claims.some(claim => ["partial", "unknown"].includes(claim.status))) return "partial";
  return "pass";
}

function buildReport({ swarmId, scenarioId, sources, queueRows, tapSample, includeTapSample }) {
  const scenario = sourceData(sources, "scenario.get");
  const wiremockData = sourceData(sources, "mock.wiremock.requests");
  const unmatchedData = sourceData(sources, "mock.wiremock.unmatched");
  const redisAuth = sourceData(sources, "redis.auth-tokens");
  const redisDebug = sourceData(sources, "redis.http-sequence-debug");
  const swarm = sourceData(sources, "swarm.get");
  const expectedEndpoints = Array.isArray(scenario?.plan?.endpoints) ? scenario.plan.endpoints : [];
  const expectedPaths = new Set(expectedEndpoints.map(endpoint => endpoint.path).filter(Boolean));
  const expectedByPath = new Map(expectedEndpoints.map(endpoint => [endpoint.path, endpoint]));
  const allRequests = wiremockEntries(wiremockData);
  const businessRequests = allRequests
    .filter(entry => expectedPaths.has(pathOnly(entry?.request?.url)))
    .sort((a, b) => Number(a?.request?.loggedDate || 0) - Number(b?.request?.loggedDate || 0));
  const selectedBusinessRequests = selectLatestExpectedSequence(businessRequests, expectedEndpoints);
  const selectedStart = Math.min(...selectedBusinessRequests.map(entry => Number(entry?.request?.loggedDate || 0)).filter(Boolean));
  const selectedEnd = Math.max(...selectedBusinessRequests.map(entry => Number(entry?.request?.loggedDate || 0)).filter(Boolean));
  const tokenRequestsAll = allRequests
    .filter(entry => pathOnly(entry?.request?.url).includes("/oauth/token"))
    .sort((a, b) => Number(a?.request?.loggedDate || 0) - Number(b?.request?.loggedDate || 0));
  const tokenRequests = Number.isFinite(selectedStart) && Number.isFinite(selectedEnd)
    ? tokenRequestsAll.filter(entry => {
        const loggedDate = Number(entry?.request?.loggedDate || 0);
        return loggedDate >= selectedStart - 1000 && loggedDate <= selectedEnd + 1000;
      })
    : tokenRequestsAll;
  const unmatchedForScenario = wiremockEntries(unmatchedData).filter(entry => {
    const path = pathOnly(entry?.request?.url);
    return expectedPaths.has(path) || path.includes("/wizard-proof") || path.includes("/oauth/token/wizard-proof");
  });
  const workQueues = queueRows.filter(row => !String(row.name || "").startsWith(`ph.control.${swarmId}.`));
  const controlQueues = queueRows.filter(row => String(row.name || "").startsWith(`ph.control.${swarmId}.`));
  const workQueueMessages = workQueues.reduce((sum, row) => sum + (Number(row.messages) || 0), 0);
  const controlQueueMessages = controlQueues.reduce((sum, row) => sum + (Number(row.messages) || 0), 0);
  const expectedSequence = expectedEndpoints.map(endpoint => `${endpoint.method} ${endpoint.path}`);
  const actualSequence = selectedBusinessRequests.map(entry => `${entry?.request?.method} ${pathOnly(entry?.request?.url)}`);
  const selectedResponseStatuses = selectedBusinessRequests.map(entry => Number(entry?.response?.status || 0));
  const allSelectedSuccessful = selectedResponseStatuses.length > 0
    && selectedResponseStatuses.every(status => status >= 200 && status < 400);
  const ordered = expectedSequence.length > 0
    && expectedSequence.length === actualSequence.length
    && expectedSequence.every((value, index) => value === actualSequence[index]);
  const nonGetExpected = expectedEndpoints.filter(endpoint => String(endpoint.method || "").toUpperCase() !== "GET");
  const payloadRows = nonGetExpected.map(endpoint => {
    const entry = selectedBusinessRequests.find(item => pathOnly(item?.request?.url) === endpoint.path && item?.request?.method === endpoint.method);
    const parsed = parseJsonLoose(entry?.request?.body);
    return {
      callId: endpoint.callId,
      path: endpoint.path,
      parseableJson: !!parsed,
      bodyPatterns: bodyPatternCount(entry),
      matched: !!entry?.wasMatched,
    };
  });
  const allPayloadsJson = payloadRows.length > 0 && payloadRows.every(row => row.parseableJson);
  const allPayloadsAsserted = payloadRows.length > 0 && payloadRows.every(row => row.bodyPatterns > 0 && row.matched);
  const responseValues = selectedBusinessRequests.flatMap((entry, index) => {
    const response = parseJsonLoose(entry?.response?.body);
    return flattenStringValues(response)
      .filter(item => !["true", "false"].includes(item.value) && !["start", "profile", "validate", "session-update", "confirm", "receipt"].includes(item.value))
      .map(item => ({ ...item, stepIndex: index, callId: expectedByPath.get(pathOnly(entry?.request?.url))?.callId || pathOnly(entry?.request?.url) }));
  });
  const dataLinks = [];
  for (const candidate of responseValues) {
    for (let index = candidate.stepIndex + 1; index < selectedBusinessRequests.length; index++) {
      const later = selectedBusinessRequests[index];
      const haystack = `${later?.request?.url || ""}\n${later?.request?.body || ""}`;
      if (candidate.value && haystack.includes(candidate.value)) {
        dataLinks.push({
          from: candidate.callId,
          valuePath: candidate.path,
          to: expectedByPath.get(pathOnly(later?.request?.url))?.callId || pathOnly(later?.request?.url),
        });
        break;
      }
    }
  }
  const configuredExtracts = (scenario?.template?.bees || [])
    .find(bee => beeImageName(bee) === WORKER_IMAGE_NAMES.HTTP_SEQUENCE)?.config?.steps
    ?.flatMap(step => (step.extracts || []).map(extract => ({ stepId: step.id, callId: step.callId, to: extract.to }))) || [];
  const authHeaders = selectedBusinessRequests.map(entry => headerValue(entry?.request?.headers, "authorization"));
  const bearerCount = authHeaders.filter(value => value.startsWith("Bearer ")).length;
  const authMatchers = selectedBusinessRequests.filter(authMatcherPresent).length;
  const tokenRecord = Array.isArray(redisAuth?.records) ? redisAuth.records[0] : null;
  const nowMs = Date.now();
  const refreshAt = Number(tokenRecord?.fields?.refreshAt);
  const expiresAt = Number(tokenRecord?.fields?.expiresAt);
  const refreshDue = Number.isFinite(refreshAt) && refreshAt <= nowMs;
  const lifecycleState = swarm?.envelope?.data?.context?.state || swarm?.envelope?.data?.context?.swarmStatus || swarm?.status || "unknown";
  const dataSource = scenario?.plan?.dataSource;
  const usesRedisDataset = dataSource === "REDIS_DATASET";
  const debugCaptureCount = Number(redisDebug?.count || 0);
  const authRequired = hasAuthConfiguration(scenario);
  const tapCaptured = tapHasSamples(tapSample);
  const tapFlow = tapFlowEvidence(tapSample, expectedEndpoints, actualSequence);
  const tapFlowStatus = !includeTapSample
    ? "not-applicable"
    : !tapCaptured
      ? "fail"
      : !tapFlow.extractable
        ? "unknown"
        : tapFlow.matchedExpected && tapFlow.agreesWithWireMock !== false
          ? "pass"
          : "fail";

  const claims = [
    makeClaim(
      "queues.drained",
      "Queues drained",
      workQueueMessages === 0 ? "pass" : "fail",
      workQueueMessages === 0
        ? `Work queues are empty${controlQueueMessages ? `; ${controlQueueMessages} control-plane message(s) remain` : ""}.`
        : `${workQueueMessages} work message(s) remain.`,
      [`${workQueues.length} work queue(s), ${controlQueues.length} control queue(s)`],
      [],
    ),
    makeClaim(
      "requests.handled",
      "Requests handled",
      expectedEndpoints.length > 0 && selectedBusinessRequests.length === expectedEndpoints.length && allSelectedSuccessful && unmatchedForScenario.length === 0 ? "pass" : "fail",
      `${selectedBusinessRequests.length}/${expectedEndpoints.length || "unknown"} expected business request(s) matched in the latest flow; ${unmatchedForScenario.length} scenario unmatched request(s); ${selectedResponseStatuses.filter(status => status < 200 || status >= 400).length} non-2xx/3xx response(s).`,
      selectedBusinessRequests.map(entry => `${entry?.request?.method} ${entry?.request?.url} -> ${entry?.response?.status}`),
      [
        ...(unmatchedForScenario.length ? ["WireMock has unmatched requests for this scenario prefix."] : []),
        ...(selectedResponseStatuses.some(status => status < 200 || status >= 400) ? ["Expected every business request to return a 2xx or 3xx response."] : []),
      ],
    ),
    makeClaim(
      "payloads.valid",
      "Payloads valid",
      allPayloadsAsserted ? "pass" : allPayloadsJson ? "partial" : "unknown",
      allPayloadsAsserted ? "WireMock body matchers validated each mutating request payload." : allPayloadsJson ? "Mutating payloads were parseable JSON, but not all stubs asserted body fields." : "No mutating payload evidence was available.",
      payloadRows.map(row => `${row.callId}: json=${row.parseableJson}, bodyMatchers=${row.bodyPatterns}`),
      allPayloadsAsserted ? [] : ["Add WireMock bodyPatterns for every mutating request."],
    ),
    makeClaim(
      "data.between_steps",
      "Data passed between steps",
      dataLinks.length > 0 && configuredExtracts.length > 0 ? "pass" : dataLinks.length > 0 ? "partial" : "unknown",
      dataLinks.length > 0 ? `${dataLinks.length} response value(s) appeared in later requests.` : "No response-to-later-request value propagation was detected.",
      dataLinks.map(link => `${link.from}${link.valuePath} -> ${link.to}`),
      dataLinks.length > 0 && configuredExtracts.length > 0 ? [] : ["Use step extracts and later request templates that reference extracted payload fields."],
    ),
    makeClaim(
      "flow.order",
      "Step flow",
      ordered ? "pass" : "fail",
      ordered ? "Observed HTTP calls match the scenario plan order." : "Observed HTTP call order does not match the scenario plan.",
      actualSequence,
      ordered ? [] : [`Expected: ${expectedSequence.join(" -> ")}`],
    ),
    makeClaim(
      "auth.flow",
      "Auth flow",
      !authRequired ? "not-applicable" : tokenRequests.length > 0 && bearerCount === selectedBusinessRequests.length && authMatchers === selectedBusinessRequests.length ? "pass" : "fail",
      !authRequired
        ? "Scenario does not declare auth, so token refresh and bearer propagation are not required."
        : `${tokenRequests.length} token request(s); ${bearerCount}/${selectedBusinessRequests.length} business request(s) used bearer auth; ${authMatchers}/${selectedBusinessRequests.length} stubs required auth.`,
      authRequired ? tokenRequests.map(entry => `${entry?.request?.method} ${entry?.request?.url} -> ${entry?.response?.status}`) : [],
      !authRequired || bearerCount === selectedBusinessRequests.length ? [] : ["Not every business request used bearer auth."],
    ),
    makeClaim(
      "auth.expiry",
      "Auth expiry / refresh",
      !authRequired ? "not-applicable" : tokenRequests.length > 1 && refreshDue ? "pass" : tokenRequests.length > 1 || refreshDue ? "partial" : "unknown",
      !authRequired
        ? "Scenario does not declare token-based auth."
        : tokenRequests.length > 1 ? `Token endpoint was called ${tokenRequests.length} times during one flow.` : "Only one token acquisition was observed.",
      authRequired ? [
        tokenRecord ? `Redis token refreshAt=${tokenRecord.fields?.refreshAt || "unknown"}, expiresAt=${tokenRecord.fields?.expiresAt || "unknown"}` : "No Redis token record",
      ] : [],
      !authRequired || tokenRequests.length > 1 && refreshDue ? [] : ["Use a short token TTL proof to exercise refresh/expiry behavior."],
    ),
    makeClaim(
      "redis.flows",
      "Redis data flows",
      tokenRecord && debugCaptureCount >= expectedEndpoints.length && !usesRedisDataset ? "pass" : tokenRecord || debugCaptureCount > 0 ? "partial" : "unknown",
      usesRedisDataset
        ? "Scenario uses Redis dataset input; dataset list checks are required."
        : `Redis token record ${tokenRecord ? "exists" : "missing"}; ${debugCaptureCount} HTTP sequence debug capture(s).`,
      [
        tokenRecord ? tokenRecord.key : "No auth token record",
        `${debugCaptureCount} debug capture key(s)`,
      ],
      usesRedisDataset ? ["Add dataset.check evidence for Redis list input/output."] : [],
    ),
    makeClaim(
      "payload.trace",
      "Runtime payload trace",
      tapCaptured || debugCaptureCount >= expectedEndpoints.length ? "pass" : includeTapSample ? "fail" : "partial",
      tapCaptured
        ? "A postprocessor input tap sample was captured."
        : debugCaptureCount >= expectedEndpoints.length
          ? "Redis debug capture contains one HTTP sequence capture per expected step."
          : includeTapSample
            ? "Tap proof was requested, but no sample was captured."
            : "No runtime payload trace is attached to this summary.",
      tapCaptured ? ["debug.tap.postprocessor.in samples>0"] : [`${debugCaptureCount} Redis debug capture key(s)`],
      tapCaptured || debugCaptureCount >= expectedEndpoints.length ? [] : ["Open the tap before the run or enable HTTP sequence Redis debug capture."],
    ),
    makeClaim(
      "tap.flow",
      "Tap step flow",
      tapFlowStatus,
      !includeTapSample
        ? "Tap step-flow proof was not requested."
        : !tapCaptured
          ? "Tap proof was requested, but no sample was captured."
          : !tapFlow.extractable
            ? "Tap sample was captured, but no step-flow fields were extractable."
            : tapFlow.matchedExpected && tapFlow.agreesWithWireMock !== false
              ? "Tap step flow matches the scenario plan and agrees with WireMock."
              : "Tap step flow does not match the scenario plan or WireMock request sequence.",
      tapFlow.evidence,
      tapFlowStatus === "pass" || tapFlowStatus === "not-applicable" ? [] : [
        !tapFlow.extractable
          ? "Emit steps, calls, requests, results, or callId/method/path fields in the tapped payload."
          : `Expected: ${expectedSequence.join(" -> ")}; WireMock: ${actualSequence.join(" -> ")}`,
      ],
    ),
  ];

  return {
    verdict: reportVerdict(claims),
    title: `Evidence report for ${swarmId}`,
    generatedAt: new Date().toISOString(),
    lifecycleState,
    tapFlow,
    checklist: claims,
    sections: [
      { title: "Expected flow", rows: expectedSequence },
      { title: "Observed flow", rows: actualSequence },
      { title: "Tap flow", rows: tapFlow.observed },
      { title: "Redis debug captures", rows: (redisDebug?.captures || []).map(capture => `${capture.data?.callId || "unknown"} -> ${capture.data?.status || "unknown"} (${capture.key})`) },
    ],
  };
}

async function buildEvidenceSummary({ swarmId, includeTapSample, scenarioId, preArmedTap }) {
  const sources = [];
  const swarmSource = await evidenceSource("swarm.get", () => httpJson(`/api/swarms/${encodeURIComponent(swarmId)}`));
  sources.push(swarmSource);
  const inferredScenarioId = scenarioId || inferScenarioId(swarmSource.status === "ok" ? swarmSource.data : null, swarmId);
  if (inferredScenarioId) {
    sources.push(await evidenceSource("scenario.get", () => httpJson(`${SM_URL}/scenarios/${encodeURIComponent(inferredScenarioId)}`)));
  }
  sources.push(await evidenceSource("debug.queues", async () => {
    const queues = await httpJson(`${RABBIT_MGMT}/queues`, { headers: { authorization: rabbitAuth() } });
    const prefix = `ph.${swarmId}.`;
    const controlPrefix = `ph.control.${swarmId}.`;
    return queues
      .filter(q => q.name.startsWith(prefix) || q.name.startsWith(controlPrefix))
      .map(q => ({ name: q.name, messages: q.messages, consumers: q.consumers }));
  }));
  sources.push(await evidenceSource("debug.journal", () => httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/journal/page?limit=50`)));
  sources.push(await evidenceSource("debug.prometheus.success", () =>
    httpJson(`${PROM_URL}/api/v1/query?query=${encodeURIComponent(`ph_transaction_processor_success{ph_swarm="${swarmId}"}`)}`, { timeoutMs: 10000 })
  ));
  sources.push(await evidenceSource("debug.prometheus.latency", () =>
    httpJson(`${PROM_URL}/api/v1/query?query=${encodeURIComponent(`ph_transaction_total_latency_ms{ph_swarm="${swarmId}"}`)}`, { timeoutMs: 10000 })
  ));
  sources.push(await evidenceSource("mock.wiremock.requests", () => httpJson(`${WIREMOCK_URL}/__admin/requests?limit=20`, { timeoutMs: 10000 })));
  sources.push(await evidenceSource("mock.wiremock.unmatched", () => httpJson(`${WIREMOCK_URL}/__admin/requests/unmatched`, { timeoutMs: 10000 })));
  sources.push(await evidenceSource("mock.tcp.requests", () => httpJson(`${TCP_MOCK_URL}/api/requests?limit=20`, {
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  })));
  sources.push(await evidenceSource("mock.tcp.unmatched", () => httpJson(`${TCP_MOCK_URL}/api/requests/unmatched`, {
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  })));
  sources.push(await evidenceSource("redis.auth-tokens", () => readRedisEvidence(swarmId)));
  sources.push(await evidenceSource("redis.http-sequence-debug", () => readRedisDebugCaptures(swarmId)));

  let tapSample = null;
  if (includeTapSample) {
    const tapSource = await evidenceSource("debug.tap.postprocessor.in", async () => {
      const tap = preArmedTap || await httpJson("/api/debug/taps", {
        method: "POST",
        body: { swarmId, role: "postprocessor", direction: "IN", ioName: "in", maxItems: 1, ttlSeconds: 30 },
      });
      try {
        const read = await httpJson(`/api/debug/taps/${encodeURIComponent(tap.tapId || tap.id)}?drain=1`);
        return { tap, read };
      } finally {
        const tapId = tap.tapId || tap.id;
        if (tapId) {
          try { await httpJson(`/api/debug/taps/${encodeURIComponent(tapId)}`, { method: "DELETE" }); } catch { /* best effort cleanup */ }
        }
      }
    });
    sources.push(tapSource);
    tapSample = tapSource.status === "ok" ? tapSource.data : null;
  }

  const source = (name) => sources.find(s => s.name === name);
  const queues = source("debug.queues");
  const journal = source("debug.journal");
  const wiremockRequests = source("mock.wiremock.requests");
  const wiremockUnmatched = source("mock.wiremock.unmatched");
  const tcpRequests = source("mock.tcp.requests");
  const tcpUnmatched = source("mock.tcp.unmatched");
  const redisAuth = source("redis.auth-tokens");
  const redisDebug = source("redis.http-sequence-debug");

  const queueRows = queues?.status === "ok" && Array.isArray(queues.data) ? queues.data : [];
  const missingEvidence = sources
    .filter(s => s.status !== "ok")
    .map(s => ({ source: s.name, reason: s.error }));
  if (!includeTapSample) {
    missingEvidence.push({ source: "debug.tap", reason: "Tap sample was not requested. Call with includeTapSample=true when payload evidence is needed." });
  }
  const report = buildReport({ swarmId, scenarioId: inferredScenarioId, sources, queueRows, tapSample, includeTapSample });

  return {
    swarmId,
    scenarioId: inferredScenarioId || null,
    lifecycle: {
      available: source("swarm.get")?.status === "ok",
      raw: source("swarm.get")?.data ?? null,
    },
    queues: {
      available: queues?.status === "ok",
      count: queueRows.length,
      totalMessages: queueRows.reduce((sum, q) => sum + (Number(q.messages) || 0), 0),
      rows: queueRows,
    },
    journal: {
      available: journal?.status === "ok",
      raw: journal?.data ?? null,
    },
    metrics: {
      success: source("debug.prometheus.success")?.data ?? null,
      latency: source("debug.prometheus.latency")?.data ?? null,
    },
    mocks: {
      wiremockRequests: countArrayLike(wiremockRequests?.data),
      wiremockUnmatched: countArrayLike(wiremockUnmatched?.data),
      tcpRequests: countArrayLike(tcpRequests?.data),
      tcpUnmatched: countArrayLike(tcpUnmatched?.data),
    },
    datasets: {
      available: false,
      note: "dataset.check is not implemented in this MCP server yet.",
    },
    redis: {
      available: redisAuth?.status === "ok" || redisDebug?.status === "ok",
      authTokens: redisAuth?.data ?? null,
      httpSequenceDebug: redisDebug?.data ?? null,
    },
    flow: {
      expected: report.sections.find(section => section.title === "Expected flow")?.rows || [],
      observed: report.sections.find(section => section.title === "Observed flow")?.rows || [],
    },
    tapFlow: report.tapFlow || null,
    auth: report.checklist.find(claim => claim.id === "auth.flow") || null,
    payloads: report.checklist.find(claim => claim.id === "payloads.valid") || null,
    report,
    tapSample,
    missingEvidence,
    sources: sources.map(({ name, status, error }) => ({ name, status, error })),
  };
}

function evidenceWidgetHtml() {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    :root { color-scheme: light dark; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    :root[data-theme="light"] { color-scheme: light; }
    :root[data-theme="dark"] { color-scheme: dark; }
    body { margin: 0; padding: 16px; background: Canvas; color: CanvasText; }
    .wrap { display: grid; gap: 12px; }
    .top { display: flex; justify-content: space-between; gap: 12px; align-items: start; }
    .top-actions { display: flex; align-items: start; gap: 8px; }
    h1 { font-size: 18px; line-height: 1.25; margin: 0; }
    .theme-toggle { border: 1px solid color-mix(in srgb, CanvasText 18%, transparent); border-radius: 6px; background: color-mix(in srgb, Canvas 92%, CanvasText 8%); color: CanvasText; cursor: pointer; font: inherit; font-size: 12px; font-weight: 700; min-width: 64px; padding: 4px 8px; }
    .theme-toggle:focus-visible { outline: 2px solid color-mix(in srgb, CanvasText 45%, transparent); outline-offset: 2px; }
    .badge { border: 1px solid color-mix(in srgb, CanvasText 18%, transparent); border-radius: 999px; padding: 4px 8px; font-size: 12px; white-space: nowrap; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 8px; }
    .panel { border: 1px solid color-mix(in srgb, CanvasText 16%, transparent); border-radius: 8px; padding: 10px; background: color-mix(in srgb, Canvas 94%, CanvasText 6%); }
    .table { display: grid; gap: 6px; }
    .row { display: grid; grid-template-columns: minmax(130px, 1fr) 92px minmax(160px, 2fr); gap: 8px; align-items: start; padding: 8px 0; border-top: 1px solid color-mix(in srgb, CanvasText 10%, transparent); }
    .row:first-child { border-top: 0; }
    .label { font-size: 11px; text-transform: uppercase; letter-spacing: 0; opacity: .72; margin-bottom: 6px; }
    .value { font-size: 20px; font-weight: 650; line-height: 1.2; }
    .small { font-size: 12px; opacity: .78; margin-top: 4px; overflow-wrap: anywhere; }
    .status { display: inline-flex; width: fit-content; border-radius: 999px; padding: 3px 8px; font-size: 11px; font-weight: 700; text-transform: uppercase; border: 1px solid color-mix(in srgb, CanvasText 16%, transparent); }
    .pass { color: #12753c; background: color-mix(in srgb, #19a957 14%, Canvas); }
    .partial, .unknown { color: #835800; background: color-mix(in srgb, #d99a00 16%, Canvas); }
    .fail { color: #a11224; background: color-mix(in srgb, #d8243c 13%, Canvas); }
    .not_applicable { color: color-mix(in srgb, CanvasText 70%, transparent); background: color-mix(in srgb, CanvasText 7%, Canvas); }
    ul { margin: 6px 0 0; padding-left: 18px; }
    li { margin: 3px 0; }
    pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; margin: 0; }
    @media (max-width: 560px) { .top, .row { display: grid; grid-template-columns: 1fr; } .top-actions { justify-content: space-between; } }
  </style>
</head>
<body>
  <div id="root" class="wrap"></div>
  <script type="module">
    const root = document.getElementById("root");
    const openai = globalThis.openai || {};
    const data = openai.toolOutput || openai.structuredContent || openai.toolResponseMetadata?.evidenceSummary || {};
    const themeKey = "pockethive.evidenceWidget.theme";
    let activeTheme = readTheme() || (globalThis.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light");
    applyTheme(activeTheme);
    const sourceCounts = Array.isArray(data.sources)
      ? { ok: data.sources.filter(s => s.status === "ok").length, total: data.sources.length }
      : { ok: 0, total: 0 };
    const missing = Array.isArray(data.missingEvidence) ? data.missingEvidence : [];
    const queues = data.queues || {};
    const mocks = data.mocks || {};
    const report = data.report || {};
    const checklist = Array.isArray(report.checklist) ? report.checklist : [];
    const lifecycleStatus = data.lifecycle?.raw?.status || data.lifecycle?.raw?.state || (data.lifecycle?.available ? "available" : "unknown");

    function esc(value) {
      return String(value ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
    }
    function panel(label, value, small = "") {
      return '<div class="panel"><div class="label">' + esc(label) + '</div><div class="value">' + esc(value) + '</div>' +
        (small ? '<div class="small">' + esc(small) + '</div>' : '') + '</div>';
    }
    function statusPill(status) {
      const cls = String(status || "unknown").replace(/[^a-z_]/g, "");
      return '<span class="status ' + esc(cls) + '">' + esc(status || "unknown") + '</span>';
    }
    function readTheme() {
      try {
        const theme = globalThis.localStorage?.getItem(themeKey);
        return theme === "dark" || theme === "light" ? theme : null;
      } catch {
        return null;
      }
    }
    function writeTheme(theme) {
      try { globalThis.localStorage?.setItem(themeKey, theme); } catch { /* storage can be unavailable in some hosts */ }
    }
    function themeToggleHtml() {
      return '<button id="theme-toggle" class="theme-toggle" type="button" title="Toggle light/dark mode"></button>';
    }
    function applyTheme(theme) {
      document.documentElement.dataset.theme = theme;
      const button = document.getElementById("theme-toggle");
      if (!button) return;
      const next = theme === "dark" ? "Light" : "Dark";
      button.textContent = next;
      button.setAttribute("aria-label", "Switch to " + next.toLowerCase() + " mode");
      button.setAttribute("aria-pressed", String(theme === "dark"));
    }
    function bindThemeToggle() {
      const button = document.getElementById("theme-toggle");
      if (!button) return;
      button.addEventListener("click", () => {
        activeTheme = activeTheme === "dark" ? "light" : "dark";
        writeTheme(activeTheme);
        applyTheme(activeTheme);
      });
      applyTheme(activeTheme);
    }
    function checklistRows() {
      if (!checklist.length) return '<div class="small">No report checklist was provided.</div>';
      return '<div class="table">' + checklist.map(item =>
        '<div class="row"><div><strong>' + esc(item.label) + '</strong></div><div>' + statusPill(item.status) + '</div><div>' +
        esc(item.summary) +
        (Array.isArray(item.gaps) && item.gaps.length ? '<div class="small">Gap: ' + esc(item.gaps.join("; ")) + '</div>' : '') +
        '</div></div>'
      ).join('') + '</div>';
    }
    function section(title) {
      const found = Array.isArray(report.sections) ? report.sections.find(item => item.title === title) : null;
      const rows = Array.isArray(found?.rows) ? found.rows : [];
      return '<div class="panel"><div class="label">' + esc(title) + '</div>' +
        (rows.length ? '<ul>' + rows.slice(0, 12).map(row => '<li>' + esc(typeof row === "string" ? row : JSON.stringify(row)) + '</li>').join('') + '</ul>' : '<div class="small">No rows.</div>') +
        '</div>';
    }

    if (!data.swarmId) {
      root.innerHTML = '<div class="top"><h1>Evidence Report</h1><div class="top-actions">' + themeToggleHtml() + '</div></div><div class="panel">No evidence summary was provided.</div>';
      bindThemeToggle();
    } else {
      root.innerHTML = [
        '<div class="top"><h1>Evidence Report: ' + esc(data.swarmId) + '</h1><div class="top-actions">' + themeToggleHtml() + '<div class="badge">' + esc(report.verdict || lifecycleStatus) + '</div></div></div>',
        '<div class="grid">',
          panel('Sources', sourceCounts.ok + '/' + sourceCounts.total, 'available evidence feeds'),
          panel('Queues', queues.totalMessages ?? 'n/a', (queues.count ?? 0) + ' queue(s)'),
          panel('WireMock', mocks.wiremockRequests ?? 'n/a', (mocks.wiremockUnmatched ?? 0) + ' unmatched'),
          panel('Redis', data.redis?.httpSequenceDebug?.count ?? 'n/a', 'HTTP sequence capture(s)'),
        '</div>',
        '<div class="panel"><div class="label">Acceptance checklist</div>' + checklistRows() + '</div>',
        section('Observed flow'),
        section('Redis debug captures'),
        '<div class="panel"><div class="label">Missing evidence</div>',
          missing.length ? '<ul>' + missing.map(item => '<li><strong>' + esc(item.source) + '</strong>: ' + esc(item.reason) + '</li>').join('') + '</ul>' : '<div class="small">No missing evidence reported.</div>',
        '</div>',
        data.tapSample ? '<div class="panel"><div class="label">Tap sample</div><pre>' + esc(JSON.stringify(data.tapSample, null, 2)).slice(0, 4000) + '</pre></div>' : ''
      ].join('');
      bindThemeToggle();
    }
  </script>
</body>
</html>`;
}

function workflowEvidenceWidgetHtml() {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    :root { color-scheme: light dark; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    :root[data-theme="light"] { color-scheme: light; }
    :root[data-theme="dark"] { color-scheme: dark; }
    body { margin: 0; padding: 16px; background: Canvas; color: CanvasText; }
    .wrap { display: grid; gap: 12px; }
    .top { display: flex; justify-content: space-between; gap: 12px; align-items: start; }
    .top-actions { display: flex; align-items: start; gap: 8px; }
    h1 { font-size: 18px; line-height: 1.25; margin: 0; }
    h2 { font-size: 13px; margin: 0 0 8px; text-transform: uppercase; letter-spacing: 0; opacity: .74; }
    .theme-toggle { border: 1px solid color-mix(in srgb, CanvasText 18%, transparent); border-radius: 6px; background: color-mix(in srgb, Canvas 92%, CanvasText 8%); color: CanvasText; cursor: pointer; font: inherit; font-size: 12px; font-weight: 700; min-width: 64px; padding: 4px 8px; }
    .theme-toggle:focus-visible { outline: 2px solid color-mix(in srgb, CanvasText 45%, transparent); outline-offset: 2px; }
    .badge { border: 1px solid color-mix(in srgb, CanvasText 18%, transparent); border-radius: 999px; padding: 4px 8px; font-size: 12px; white-space: nowrap; text-transform: uppercase; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 8px; }
    .panel { border: 1px solid color-mix(in srgb, CanvasText 16%, transparent); border-radius: 8px; padding: 10px; background: color-mix(in srgb, Canvas 94%, CanvasText 6%); }
    .table { display: grid; gap: 0; }
    .row { display: grid; grid-template-columns: minmax(130px, 1fr) 110px minmax(150px, 2fr); gap: 8px; align-items: start; padding: 8px 0; border-top: 1px solid color-mix(in srgb, CanvasText 10%, transparent); }
    .row:first-child { border-top: 0; }
    .label { font-size: 11px; text-transform: uppercase; letter-spacing: 0; opacity: .72; margin-bottom: 6px; }
    .value { font-size: 20px; font-weight: 650; line-height: 1.2; overflow-wrap: anywhere; }
    .small { font-size: 12px; opacity: .78; margin-top: 4px; overflow-wrap: anywhere; }
    .pill { display: inline-flex; width: fit-content; border-radius: 999px; padding: 3px 8px; font-size: 11px; font-weight: 700; text-transform: uppercase; border: 1px solid color-mix(in srgb, CanvasText 16%, transparent); }
    .satisfied, .pass, .complete, .succeeded, .validated, .verified, .reported { color: #12753c; background: color-mix(in srgb, #19a957 14%, Canvas); }
    .missing, .pending, .running, .not-run, .not_run, .partial { color: #835800; background: color-mix(in srgb, #d99a00 16%, Canvas); }
    .failed, .fail, .blocked, .error { color: #a11224; background: color-mix(in srgb, #d8243c 13%, Canvas); }
    .not-applicable, .not_applicable { color: color-mix(in srgb, CanvasText 70%, transparent); background: color-mix(in srgb, CanvasText 7%, Canvas); }
    ul { margin: 6px 0 0; padding-left: 18px; }
    li { margin: 3px 0; }
    @media (max-width: 620px) { .top, .row { grid-template-columns: 1fr; display: grid; } .top-actions { justify-content: space-between; } }
  </style>
</head>
<body>
  <div id="root" class="wrap"></div>
  <script type="module">
    const root = document.getElementById("root");
    const openai = globalThis.openai || {};
    const data = openai.toolOutput || openai.structuredContent || openai.toolResponseMetadata?.workflowEvidence || {};
    const themeKey = "pockethive.evidenceWidget.theme";
    let activeTheme = readTheme() || (globalThis.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light");
    applyTheme(activeTheme);

    function esc(value) {
      return String(value ?? "").replace(/[&<>"']/g, ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
    }
    function cls(status) {
      return String(status || "unknown").toLowerCase().replace(/[^a-z0-9_-]/g, "_");
    }
    function pill(status) {
      return '<span class="pill ' + esc(cls(status)) + '">' + esc(status || "unknown") + '</span>';
    }
    function panel(label, value, small = "") {
      return '<div class="panel"><div class="label">' + esc(label) + '</div><div class="value">' + esc(value) + '</div>' +
        (small ? '<div class="small">' + esc(small) + '</div>' : '') + '</div>';
    }
    function readTheme() {
      try {
        const theme = globalThis.localStorage?.getItem(themeKey);
        return theme === "dark" || theme === "light" ? theme : null;
      } catch {
        return null;
      }
    }
    function writeTheme(theme) {
      try { globalThis.localStorage?.setItem(themeKey, theme); } catch { /* storage can be unavailable in some hosts */ }
    }
    function themeToggleHtml() {
      return '<button id="theme-toggle" class="theme-toggle" type="button" title="Toggle light/dark mode"></button>';
    }
    function applyTheme(theme) {
      document.documentElement.dataset.theme = theme;
      const button = document.getElementById("theme-toggle");
      if (!button) return;
      const next = theme === "dark" ? "Light" : "Dark";
      button.textContent = next;
      button.setAttribute("aria-label", "Switch to " + next.toLowerCase() + " mode");
      button.setAttribute("aria-pressed", String(theme === "dark"));
    }
    function bindThemeToggle() {
      const button = document.getElementById("theme-toggle");
      if (!button) return;
      button.addEventListener("click", () => {
        activeTheme = activeTheme === "dark" ? "light" : "dark";
        writeTheme(activeTheme);
        applyTheme(activeTheme);
      });
      applyTheme(activeTheme);
    }
    function table(items, empty, row) {
      if (!Array.isArray(items) || !items.length) return '<div class="small">' + esc(empty) + '</div>';
      return '<div class="table">' + items.map(row).join('') + '</div>';
    }
    function claimRows() {
      return table(data.claimMatrix, "No claims available.", claim =>
        '<div class="row"><div><strong>' + esc(claim.id) + '</strong><div class="small">' + esc(claim.claim || claim.label || "") + '</div></div><div>' +
        pill(claim.status) + '</div><div>' + esc(claim.gap || (Array.isArray(claim.evidence) ? claim.evidence.join("; ") : "")) + '</div></div>'
      );
    }
    function roleRows() {
      return table(data.reviewStages, "No role checks available.", stage =>
        '<div class="row"><div><strong>' + esc(stage.label || stage.id) + '</strong></div><div>' + pill(stage.status) + '</div><div>' +
        esc((stage.requiredRoles || []).map(role => role.roleId + ": " + (role.check?.outcome || role.status)).join(" | ")) + '</div></div>'
      );
    }
    function operationRows() {
      const operations = Object.values(data.operations || {});
      return table(operations, "No lifecycle operations recorded.", op => {
        const timeline = Array.isArray(op.phaseTimeline) ? op.phaseTimeline : [];
        const summary = timeline.map(phase => phase.phase + ": " + phase.attempts + " attempt(s)" + (phase.lastCode ? " " + phase.lastCode : "")).join(" | ");
        return '<div class="row"><div><strong>' + esc(op.operationId) + '</strong><div class="small">' + esc(op.type) + '</div></div><div>' +
          pill(op.status) + '</div><div>' + esc(op.phase) + '<div class="small">' + esc(summary) + '</div></div></div>';
      });
    }
    function questionRows() {
      const questions = Array.isArray(data.nextQuestions) ? data.nextQuestions : [];
      if (!questions.length) return '<div class="small">No remaining questions.</div>';
      return '<ul>' + questions.map(question => '<li><strong>' + esc(question.id) + '</strong>: ' + esc(question.prompt) + '</li>').join('') + '</ul>';
    }
    function gapRows() {
      const gaps = Array.isArray(data.evidenceGaps) ? data.evidenceGaps : [];
      if (!gaps.length) return '<div class="small">No evidence gaps reported.</div>';
      return '<ul>' + gaps.map(gap => '<li><strong>' + esc(gap.id) + '</strong>: ' + esc(gap.status) + '</li>').join('') + '</ul>';
    }

    if (!data.workflowId) {
      root.innerHTML = '<div class="top"><h1>Workflow Evidence</h1><div class="top-actions">' + themeToggleHtml() + '</div></div><div class="panel">No workflow evidence was provided.</div>';
      bindThemeToggle();
    } else {
      const summary = data.summary || {};
      root.innerHTML = [
        '<div class="top"><h1>Workflow Evidence: ' + esc(data.workflowId) + '</h1><div class="top-actions">' + themeToggleHtml() + '<div class="badge">' + esc(data.state) + '</div></div></div>',
        '<div class="grid">',
          panel('Bundle', summary.bundleId || 'not generated', summary.mode || 'create'),
          panel('Questions', summary.nextQuestionCount ?? 0, 'remaining intake items'),
          panel('Evidence gaps', summary.evidenceGapCount ?? 0, 'open proof items'),
          panel('Operations', summary.operationCount ?? 0, 'lifecycle jobs'),
        '</div>',
        '<div class="panel"><h2>Claim Matrix</h2>' + claimRows() + '</div>',
        '<div class="panel"><h2>Role Review</h2>' + roleRows() + '</div>',
        '<div class="panel"><h2>Lifecycle Operations</h2>' + operationRows() + '</div>',
        '<div class="panel"><h2>Remaining Questions</h2>' + questionRows() + '</div>',
        '<div class="panel"><h2>Evidence Gaps</h2>' + gapRows() + '</div>'
      ].join('');
      bindThemeToggle();
    }
  </script>
</body>
</html>`;
}

server.registerResource(
  "evidence-summary-widget",
  EVIDENCE_WIDGET_URI,
  {
    title: "PocketHive Evidence Summary",
    description: "Read-only evidence summary widget for one PocketHive swarm.",
    mimeType: APP_RESOURCE_MIME_TYPE,
  },
  async () => ({
    contents: [{
      uri: EVIDENCE_WIDGET_URI,
      mimeType: APP_RESOURCE_MIME_TYPE,
      text: evidenceWidgetHtml(),
      _meta: {
        ui: {
          prefersBorder: true,
          csp: { connectDomains: [], resourceDomains: [] },
        },
        "openai/widgetDescription": "Read-only PocketHive swarm evidence summary.",
        "openai/widgetPrefersBorder": true,
        "openai/widgetCSP": { connect_domains: [], resource_domains: [] },
      },
    }],
  }),
);

server.registerResource(
  "workflow-evidence-widget",
  WORKFLOW_EVIDENCE_WIDGET_URI,
  {
    title: "PocketHive Workflow Evidence",
    description: "Read-only workflow evidence and stakeholder report widget.",
    mimeType: APP_RESOURCE_MIME_TYPE,
  },
  async () => ({
    contents: [{
      uri: WORKFLOW_EVIDENCE_WIDGET_URI,
      mimeType: APP_RESOURCE_MIME_TYPE,
      text: workflowEvidenceWidgetHtml(),
      _meta: {
        ui: {
          prefersBorder: true,
          csp: { connectDomains: [], resourceDomains: [] },
        },
        "openai/widgetDescription": "Read-only PocketHive workflow evidence, questions, role checks, claims, and lifecycle operations.",
        "openai/widgetPrefersBorder": true,
        "openai/widgetCSP": { connect_domains: [], resource_domains: [] },
      },
    }],
  }),
);

registerMcpTool("evidence.summary", {
  title: "evidence.summary",
  description: "Return a read-only aggregate evidence summary for a swarm and render the optional evidence widget for App-capable clients.",
  inputSchema: {
    swarmId: SWARM_ID_ARG,
    includeTapSample: z.boolean().optional().default(false),
    scenarioId: SCENARIO_ID_ARG.optional(),
  },
  annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: true },
  _meta: {
    ui: { resourceUri: EVIDENCE_WIDGET_URI },
    "openai/outputTemplate": EVIDENCE_WIDGET_URI,
  },
}, async (input = {}) => {
  try {
    const result = await runWithTimeout("evidence.summary", buildEvidenceSummary, input);
    return jsonToolResult(result, {
      structuredContent: result,
      _meta: {
        ui: { resourceUri: EVIDENCE_WIDGET_URI },
        "openai/outputTemplate": EVIDENCE_WIDGET_URI,
        evidenceSummary: result,
      },
    });
  } catch (err) {
    return { isError: true, content: [{ type: "text", text: `Error: ${err.message || err}` }] };
  }
});

// ── Mock server tools ─────────────────────────────────────────────────────────

// WireMock / TCP Mock: prefer explicit env override, then derive from BASE_URL host.
// This means remote stacks work automatically — no manual URL config needed.
const _baseHost = (() => { try { return new URL(BASE_URL).hostname; } catch { return "localhost"; } })();
const WIREMOCK_URL = process.env.WIREMOCK_BASE_URL || `http://${_baseHost}:8080`;
const TCP_MOCK_URL = process.env.TCP_MOCK_BASE_URL || `http://${_baseHost}:8083`;
const TCP_MOCK_USER = process.env.TCP_MOCK_USER || "admin";
const TCP_MOCK_PASS = process.env.TCP_MOCK_PASS || "admin";

function tcpMockAuth() {
  return "Basic " + Buffer.from(`${TCP_MOCK_USER}:${TCP_MOCK_PASS}`).toString("base64");
}

// WireMock tools
reg("mock.wiremock.list", "List all WireMock stub mappings", {}, async () => {
  return await httpJson(`${WIREMOCK_URL}/__admin/mappings`, { timeoutMs: 10000 });
});

reg("mock.wiremock.add", "Add a WireMock stub mapping at runtime", {
  mapping: z.record(z.any()).describe("WireMock mapping object with request and response fields"),
}, async ({ mapping }) => {
  return await httpJson(`${WIREMOCK_URL}/__admin/mappings`, { method: "POST", body: mapping, timeoutMs: 10000 });
});

reg("mock.wiremock.reset", "Reset all WireMock stubs and request journal", {}, async () => {
  await httpJson(`${WIREMOCK_URL}/__admin/reset`, { method: "POST", timeoutMs: 10000 });
  return { reset: true };
});

reg("mock.wiremock.requests", "Get recent requests received by WireMock", {
  limit: z.number().optional().default(10),
}, async ({ limit }) => {
  return await httpJson(`${WIREMOCK_URL}/__admin/requests?limit=${limit}`, { timeoutMs: 10000 });
});

reg("mock.wiremock.unmatched", "Get WireMock requests that did not match any stub — use to debug why requests aren't matching", {}, async () => {
  return await httpJson(`${WIREMOCK_URL}/__admin/requests/unmatched`, { timeoutMs: 10000 });
});

// TCP Mock tools — API is at /api/... with Basic auth (admin:admin)
reg("mock.tcp.list", "List all TCP mock server mappings", {}, async () => {
  return await httpJson(`${TCP_MOCK_URL}/api/mappings`, {
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

reg("mock.tcp.add", "Add a TCP mock mapping at runtime", {
  mapping: z.record(z.any()).describe("TCP mock mapping with id, requestPattern, responseTemplate, priority, enabled"),
}, async ({ mapping }) => {
  return await httpJson(`${TCP_MOCK_URL}/api/mappings`, {
    method: "POST", body: mapping,
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

reg("mock.tcp.reset", "Reload TCP mock mappings from disk (picks up any new mapping files)", {}, async () => {
  await httpJson(`${TCP_MOCK_URL}/api/mappings/reload`, {
    method: "POST", headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
  return { reloaded: true };
});

reg("mock.tcp.requests", "Get recent requests received by the TCP mock server", {
  limit: z.number().optional().default(10),
}, async ({ limit }) => {
  const safeLimit = Math.min(limit, 20); // guard against oversized responses
  const data = await httpJson(`${TCP_MOCK_URL}/api/requests?limit=${safeLimit}`, {
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
  // Truncate individual request bodies to prevent tool output overflow
  if (Array.isArray(data)) {
    return data.map(r => ({
      ...r,
      body: typeof r.body === "string" && r.body.length > 500
        ? r.body.slice(0, 500) + `...[truncated ${r.body.length} chars]`
        : r.body,
    }));
  }
  return data;
});

reg("mock.tcp.unmatched", "Get TCP mock requests that did not match any mapping — use to debug why TCP requests aren't matching", {}, async () => {
  return await httpJson(`${TCP_MOCK_URL}/api/requests/unmatched`, {
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

reg("mock.tcp.scenarios", "Get TCP mock stateful scenario states (for multi-step flows)", {}, async () => {
  return await httpJson(`${TCP_MOCK_URL}/api/scenarios`, {
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

reg("mock.tcp.reset-scenarios", "Reset all TCP mock stateful scenario states back to 'Started'", {}, async () => {
  await httpJson(`${TCP_MOCK_URL}/api/scenarios/reset`, {
    method: "POST", headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
  return { reset: true };
});

reg("mock.tcp.enable", "Enable a TCP mock mapping by ID without re-uploading the full mapping", {
  id: z.string().describe("Mapping ID to enable"),
}, async ({ id }) => {
  return await httpJson(`${TCP_MOCK_URL}/api/mappings/${encodeURIComponent(id)}`, {
    method: "PATCH", body: { enabled: true },
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

reg("mock.tcp.disable", "Disable a TCP mock mapping by ID without removing it", {
  id: z.string().describe("Mapping ID to disable"),
}, async ({ id }) => {
  return await httpJson(`${TCP_MOCK_URL}/api/mappings/${encodeURIComponent(id)}`, {
    method: "PATCH", body: { enabled: false },
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

reg("mock.tcp.update", "Update specific fields of a TCP mock mapping by ID (e.g. enabled, priority, responseTemplate)", {
  id: z.string().describe("Mapping ID to update"),
  patch: z.record(z.any()).describe("Fields to update, e.g. {enabled: true, priority: 20}"),
}, async ({ id, patch }) => {
  return await httpJson(`${TCP_MOCK_URL}/api/mappings/${encodeURIComponent(id)}`, {
    method: "PATCH", body: patch,
    headers: { authorization: tcpMockAuth() }, timeoutMs: 10000,
  });
});

// ── Context tools ────────────────────────────────────────────────────────────

reg("context.get", "Return the current active configuration context. Call at session start to understand what the server is working with.", {}, async () => {
  return {
    bundlesRoot: getBundlesDir(),
    bundlesRootName: getBundlesDir().split(/[\\/]/).filter(Boolean).pop() || "",
    pockethiveRoot: POCKETHIVE_ROOT || "(not set)",
    activeEnvironment: process.env.PH_ACTIVE_ENVIRONMENT || "",
    baseUrl: BASE_URL,
    hasAuthToken: Boolean(POCKETHIVE_AUTH_TOKEN),
    hasAuthUsername: Boolean(POCKETHIVE_AUTH_USERNAME),
    mcpVersion: "1.0.0",
    platform: platform(),
    allBundlesRoots: _bundlesRoots,
    bundleRootPolicy: bundleRootPolicy(),
  };
});

reg("context.set-bundles-root", "Switch the active bundles root at runtime. Takes effect immediately without server restart.", {
  path: z.string().describe("Absolute path to the bundles directory"),
}, async ({ path: newPath }) => {
  if (!existsSync(newPath)) throw new Error(`Path does not exist: ${newPath}`);
  BUNDLES_ROOT = newPath;
  const bundleCount = existsSync(newPath)
    ? readdirSync(newPath, { withFileTypes: true }).filter(d => d.isDirectory()).length
    : 0;
  return { switched: true, path: newPath, bundleCount };
});

reg("context.list-bundles-roots", "List all configured bundle roots injected by the IDE plugin.", {}, async () => {
  const active = getBundlesDir();
  const roots = _bundlesRoots.length > 0
    ? _bundlesRoots.map(p => ({
        path: p,
        name: p.split(/[\\/]/).filter(Boolean).pop() || p,
        active: resolve(p) === resolve(active),
      }))
    : [{ path: active, name: active.split(/[\\/]/).filter(Boolean).pop() || active, active: true }];
  return { roots, active };
});

// ── Environment profile switching ───────────────────────────────────────────

reg("env.current", "Return the active environment details injected into this MCP process (without secrets).", {}, async () => {
  return {
    activeEnvironment: process.env.PH_ACTIVE_ENVIRONMENT || "",
    baseUrl: BASE_URL,
    hasAuthToken: Boolean(POCKETHIVE_AUTH_TOKEN),
    hasAuthUsername: Boolean(POCKETHIVE_AUTH_USERNAME),
    rabbitUser: process.env.RABBITMQ_DEFAULT_USER || "guest",
    tcpMockUrl: TCP_MOCK_URL,
    wiremockUrl: WIREMOCK_URL,
    pockethiveRoot: POCKETHIVE_ROOT || "(not set)",
    bundlesRoot: getBundlesDir(),
    bundleRootPolicy: bundleRootPolicy(),
  };
});

reg("env.add", "Request creation of a named environment profile in the client settings store.", {
  name: z.string().describe("Profile name, e.g. 'nft-remote'"),
  baseUrl: z.string().describe("PocketHive base URL"),
  rabbitUser: z.string().optional().default("guest"),
  tcpMockUrl: z.string().optional().default(""),
  wiremockUrl: z.string().optional().default(""),
}, async ({ name, baseUrl, rabbitUser, tcpMockUrl, wiremockUrl }) => {
  return {
    added: false,
    requiresClientPersistence: true,
    environment: { name, baseUrl, rabbitUser, tcpMockUrl, wiremockUrl },
    message: "Persist this environment in the IDE/client settings store and restart the MCP server with the selected environment.",
  };
});

reg("env.remove", "Request removal of a named environment profile from the client settings store.", {
  name: z.string().describe("Profile name to remove"),
}, async ({ name }) => {
  return {
    removed: false,
    requiresClientPersistence: true,
    name,
    message: "Remove this environment from the IDE/client settings store and restart the MCP server if it was active.",
  };
});

reg("env.list", "List environment profiles injected by the IDE/client settings store.", {}, async () => {
  let profiles = [];
  try {
    profiles = process.env.PH_ENVIRONMENTS ? JSON.parse(process.env.PH_ENVIRONMENTS) : [];
  } catch {
    throw new Error("PH_ENVIRONMENTS is not valid JSON");
  }
  return {
    activeEnvironment: process.env.PH_ACTIVE_ENVIRONMENT || "",
    activeBaseUrl: BASE_URL,
    profiles,
    source: "injected-env",
  };
});

async function probeHealthEndpoint(baseUrl, servicePath, authHeader) {
  const url = `${normalizeBaseUrl(baseUrl)}${servicePath}/actuator/health`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3000);
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: {
        "accept": "application/json",
        ...(authHeader ? { authorization: authHeader } : {}),
      },
      signal: controller.signal,
    });
    const text = await response.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text || null; }
    return {
      ok: response.ok,
      httpStatus: response.status,
      status: body?.status || (response.ok ? "UP" : "DOWN"),
      error: response.ok ? null : (typeof body === "string" ? body : body?.error || body?.message || response.statusText),
    };
  } catch (e) {
    return {
      ok: false,
      httpStatus: null,
      status: "DOWN",
      error: e?.name === "AbortError" ? "timeout" : (e?.message || String(e)),
    };
  } finally {
    clearTimeout(timer);
  }
}

function classifyEnvironmentHealth(services) {
  const statuses = Object.values(services);
  if (statuses.every(service => service.ok && service.status === "UP")) return "reachable";
  if (statuses.some(service => service.httpStatus === 401 || String(service.error || "").toLowerCase().includes("missing authorization"))) return "auth-required";
  if (statuses.some(service => service.ok)) return "degraded";
  return "inaccessible";
}

reg("env.status", "Check configured PocketHive environments through MCP and classify them for IDE settings views.", {}, async () => {
  let profiles = [];
  try {
    profiles = process.env.PH_ENVIRONMENTS ? JSON.parse(process.env.PH_ENVIRONMENTS) : [];
  } catch {
    throw new Error("PH_ENVIRONMENTS is not valid JSON");
  }
  const activeEnvironment = process.env.PH_ACTIVE_ENVIRONMENT || "";
  const results = [];
  for (const profile of Array.isArray(profiles) ? profiles : []) {
    const name = String(profile?.name || "");
    const baseUrl = normalizeBaseUrl(profile?.baseUrl || "");
    const active = name === activeEnvironment;
    if (!name || !baseUrl) {
      results.push({
        name,
        baseUrl,
        active,
        state: "invalid",
        services: {},
        message: "Environment requires name and baseUrl.",
      });
      continue;
    }
    let authHeader = null;
    try {
      authHeader = active ? await resolveAuthorizationHeader() : await resolveProfileAuthorizationHeader(profile);
    } catch (e) {
      results.push({
        name,
        baseUrl,
        active,
        state: "auth-error",
        services: {},
        message: e?.message || String(e),
      });
      continue;
    }
    const [orchestrator, scenarioManager] = await Promise.all([
      probeHealthEndpoint(baseUrl, "/orchestrator", authHeader),
      probeHealthEndpoint(baseUrl, "/scenario-manager", authHeader),
    ]);
    const services = {
      orchestrator,
      "scenario-manager": scenarioManager,
    };
    const reachability = classifyEnvironmentHealth(services);
    results.push({
      name,
      baseUrl,
      active,
      state: active ? (reachability === "reachable" ? "active" : reachability) : (reachability === "reachable" ? "inactive" : reachability),
      services,
    });
  }
  return {
    activeEnvironment,
    environments: results,
    source: "mcp-env-status",
  };
});

reg("env.switch", "Request a switch to a named environment profile in the IDE/client settings store.", {
  profile: z.string().describe("Profile name, e.g. 'local' or 'remote-nft'"),
}, async ({ profile }) => {
  return {
    switched: false,
    profile,
    requiresClientPersistence: true,
    requiresRestart: true,
    message: "Persist the active environment in the IDE/client settings store, then restart the MCP server with the new injected environment.",
  };
});

reg("health.check", "Check connectivity to Orchestrator, Scenario Manager, RabbitMQ, and Prometheus", {}, async () => {
  const results = {};
  for (const [name, url] of [
    ["orchestrator", `${ORCH_URL}/actuator/health`],
    ["scenario-manager", `${SM_URL}/actuator/health`],
    ["rabbitmq", `${RABBIT_MGMT}/overview`],
    ["prometheus", `${PROM_URL}/api/v1/status/config`],
  ]) {
    try {
      const headers = name === "rabbitmq" ? { authorization: rabbitAuth() } : {};
      await httpJson(url, { headers, timeoutMs: 5000 });
      results[name] = "UP";
    } catch (e) { results[name] = `DOWN: ${e.message}`; }
  }

  results.pockethiveRoot = POCKETHIVE_ROOT || "(not set)";
  results.baseUrl = BASE_URL;
  results.tcpMockUrl = TCP_MOCK_URL;
  results.wiremockUrl = WIREMOCK_URL;
  return results;
});

// ── Start ─────────────────────────────────────────────────────────────────────

async function startHttpServer() {
  const port = Number(process.env.PH_MCP_HTTP_PORT);
  if (!Number.isInteger(port) || port <= 0) throw new Error("PH_MCP_HTTP_PORT must be a positive integer");
  const sessions = new Map();

  function writeMcpHttpError(res, status, code, message) {
    if (!res.headersSent) res.writeHead(status, { "content-type": "application/json" });
    res.end(JSON.stringify({ jsonrpc: "2.0", error: { code, message }, id: null }));
  }

  async function createSessionTransport() {
    const sessionServer = cloneRegisteredServer();
    let transport;
    transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => crypto.randomUUID(),
      onsessioninitialized: (sessionId) => {
        sessions.set(sessionId, { transport, server: sessionServer });
      },
      onsessionclosed: (sessionId) => {
        sessions.delete(sessionId);
      },
    });
    transport.onclose = () => {
      if (transport.sessionId) sessions.delete(transport.sessionId);
    };
    await sessionServer.connect(transport);
    return transport;
  }

  const httpServer = createServer(async (req, res) => {
    try {
      const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
      if (url.pathname !== "/mcp") {
        res.writeHead(404, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "not_found", message: "Use /mcp for the PocketHive MCP endpoint." }));
        return;
      }
      const sessionId = req.headers["mcp-session-id"];
      const normalizedSessionId = Array.isArray(sessionId) ? sessionId[0] : sessionId;
      const entry = normalizedSessionId ? sessions.get(normalizedSessionId) : null;
      if (normalizedSessionId && !entry) {
        writeMcpHttpError(res, 404, -32001, "Session not found");
        return;
      }
      if (!normalizedSessionId && req.method !== "POST") {
        writeMcpHttpError(res, 400, -32000, "Bad Request: Mcp-Session-Id header is required");
        return;
      }
      const transport = entry?.transport || await createSessionTransport();
      await transport.handleRequest(req, res);
    } catch (err) {
      if (!res.headersSent) res.writeHead(500, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "mcp_http_error", message: err.message || String(err) }));
    }
  });
  httpServer.listen(port, () => {
    console.error(`PocketHive MCP Streamable HTTP listening on http://localhost:${port}/mcp`);
  });
}

if (process.env.PH_MCP_HTTP_PORT) {
  await startHttpServer();
} else {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}
