#!/usr/bin/env node

/**
 * PocketHive Scenario Bundles MCP Server
 *
 * Provides tools for the full TDD lifecycle of scenario bundles:
 *   - Validate templates offline
 *   - Sync bundles to running Scenario Manager
 *   - Create / start / stop / remove swarms
 *   - Inspect swarm status, queues, control-plane messages
 *   - Tap data-plane messages for debugging
 *   - Read docker logs for worker containers
 *   - Read swarm journal for timeline inspection
 *
 * Designed to be spawned by Amazon Q via .amazonq/mcp.json.
 */

import { spawn, execFileSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, writeFileSync, unlinkSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
import { platform } from "node:os";
import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

// Load CommonJS dev-tools
const require = createRequire(import.meta.url);
const { DockerTool } = require("./dev-tools/docker-tool.cjs");
const { GitTool } = require("./dev-tools/git-tool.cjs");
const { MavenTool } = require("./dev-tools/maven-tool.cjs");
const { NpmTool } = require("./dev-tools/npm-tool.cjs");

const dockerTool = new DockerTool();
const gitTool = new GitTool();
const mavenTool = new MavenTool();
const npmTool = new NpmTool();

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const REPO_ROOT = resolve(__dirname, "../..");
// BUNDLES_DIR: resolved bundle directory used by all bundle.* tools.
// Priority: BUNDLES_ROOT (plugin injection) > POCKETHIVE_BUNDLES_DIR (legacy) > repo-relative default.
const BUNDLES_DIR_DEFAULT = process.env.POCKETHIVE_BUNDLES_DIR
  ? resolve(process.env.POCKETHIVE_BUNDLES_DIR)
  : resolve(REPO_ROOT, "scenarios", "bundles");
function getBundlesDir() {
  return BUNDLES_ROOT ? resolve(BUNDLES_ROOT) : BUNDLES_DIR_DEFAULT;
}

// Load .env
const envFile = resolve(REPO_ROOT, ".env");
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eq = trimmed.indexOf("=");
    if (eq < 1) continue;
    const key = trimmed.slice(0, eq).trim();
    // Always override — ensures a re-saved .env (e.g. CRLF→LF fix) takes effect
    // Strip any stray \r that survived trimming on the value side
    process.env[key] = trimmed.slice(eq + 1).replace(/\r$/, "");
  }
}

const POCKETHIVE_ROOT = process.env.POCKETHIVE_ROOT || "";
const BASE_URL = process.env.POCKETHIVE_BASE_URL || "http://localhost:8088";
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
const RABBIT_MGMT = process.env.RABBITMQ_MANAGEMENT_BASE_URL || `${BASE_URL}/rabbitmq/api`;
const PROM_URL = process.env.PROMETHEUS_BASE_URL || `${BASE_URL}/prometheus`;
// TCP_MOCK_BASE_URL and WIREMOCK_BASE_URL can be set explicitly in .env to override
// the auto-derived URLs (host from BASE_URL, standard ports 8083/8080).

const IS_WINDOWS = platform() === "win32";
const WSL_EXE = "C:\\Windows\\System32\\wsl.exe";
const PS_EXE = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

// ── Path helpers ──────────────────────────────────────────────────────────────

/** Detect WSL mount prefix (/mnt or empty) by probing the live WSL instance. Cached after first call. */
let _wslMountPrefix = null;
async function getWslMountPrefix() {
  if (_wslMountPrefix !== null) return _wslMountPrefix;
  // Derive from POCKETHIVE_ROOT if it is already a POSIX path — avoids a WSL probe
  // that can cache a wrong result if WSL isn't fully up at MCP server start time.
  const pr = process.env.POCKETHIVE_ROOT || "";
  if (pr.startsWith('/')) {
    _wslMountPrefix = pr.startsWith('/mnt/') ? '/mnt' : '';
    return _wslMountPrefix;
  }
  // Probe live WSL instance
  try {
    const result = execFileSync(WSL_EXE, ['bash', '-lc', 'test -d /mnt/c && echo mnt || echo root'],
      { encoding: 'utf8', timeout: 5000 }).trim();
    _wslMountPrefix = result === 'mnt' ? '/mnt' : '';
  } catch {
    _wslMountPrefix = '/mnt'; // safe default
  }
  return _wslMountPrefix;
}

/** Convert a Windows path to a WSL path using the detected mount prefix. No-op if already POSIX. */
function toWslPath(p, mountPrefix = '/mnt') {
  if (!p) return p;
  if (p.startsWith('/')) return p;
  return p.replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_, d) => `${mountPrefix}/${d.toLowerCase()}`);
}

/** Async version — detects the correct mount prefix before converting. */
async function toWslPathAsync(p) {
  return toWslPath(p, await getWslMountPrefix());
}

/** Join path segments, normalising backslashes. */
function joinPath(base, ...parts) {
  if (!base) return parts.join("/");
  const b = base.replace(/\\/g, "/").replace(/\/+$/, "");
  return [b, ...parts.map(p => p.replace(/\\/g, "/"))].join("/");
}

// ── Shell execution ───────────────────────────────────────────────────────────

/**
 * Run a bash command via WSL.
 * On Windows: uses execFileSync(WSL_EXE, ['bash', '-lc', cmd]) — synchronous but
 * runs in a proper login shell with java/mvn/docker on PATH.
 * On Linux/macOS: uses execFileSync with bash directly.
 *
 * NOTE: Do NOT use this for commands that spawn the JVM — those hang due to a
 * WSL2 pipe-inheritance bug. Use wslRunToFile() for java invocations instead.
 */
async function shell(cmd, cwd, timeoutMs = 120000) {
  try {
    const opts = { encoding: "utf8", timeout: timeoutMs, stdio: ["ignore", "pipe", "pipe"] };
    let result;
    if (!IS_WINDOWS) {
      result = execFileSync("bash", ["-c", `cd '${cwd || REPO_ROOT}' && ${cmd}`], opts);
    } else {
      const prefix = await getWslMountPrefix();
      // Only cd if a cwd was explicitly provided — docker/git commands don't need it
      // and a wrong WSL mount prefix would cause every command to fail.
      const cdPart = cwd ? `cd '${toWslPath(cwd, prefix)}' && ` : "";
      result = execFileSync(WSL_EXE, ["bash", "-lc", `${cdPart}${cmd}`], opts);
    }
    return result.trim();
  } catch (e) {
    throw new Error((e.stderr || e.stdout || e.message || String(e)).toString().trim());
  }
}

/**
 * Run a bash command via WSL where the command spawns a JVM.
 * Uses PowerShell Start-Process to launch wsl in a completely detached Windows
 * process (no inherited pipe handles), writes output to a temp file, then reads it.
 * This is the only reliable way to run Java via WSL from a Windows Node process.
 */
async function wslRunToFile(bashCmd, timeoutMs = 60000) {
  const id = Date.now();
  const OUT = `/c/Windows/Temp/mcp-wsl-out-${id}.txt`;
  const DONE = `/c/Windows/Temp/mcp-wsl-done-${id}.txt`;

  const fullCmd = `${bashCmd} >'${OUT}' 2>&1; echo $? >'${DONE}'`;
  const ps1 = `$bashCmd = @'\n${fullCmd}\n'@\nStart-Process -FilePath '${WSL_EXE}' -ArgumentList @('bash', '-lc', $bashCmd) -WindowStyle Hidden -Wait\n`;
  const ps1Path = `C:\\Windows\\Temp\\mcp-launch-${id}.ps1`;
  writeFileSync(ps1Path, ps1, "utf8");

  // Use async spawn so the Node event loop stays alive for MCP keepalive messages
  await new Promise((res, rej) => {
    const proc = spawn(PS_EXE,
      ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps1Path],
      { stdio: "ignore" }
    );
    const t = setTimeout(() => { proc.kill(); rej(new Error(`wslRunToFile timed out after ${timeoutMs}ms`)); }, timeoutMs);
    proc.on("close", () => { clearTimeout(t); res(); });
    proc.on("error", err => { clearTimeout(t); rej(err); });
  });

  let output = "", exitCode = "?";
  try { output = execFileSync(WSL_EXE, ["bash", "-c", `cat '${OUT}' 2>/dev/null`], { encoding: "utf8", timeout: 10000 }).trim(); } catch { /* ignore */ }
  try { exitCode = execFileSync(WSL_EXE, ["bash", "-c", `cat '${DONE}' 2>/dev/null`], { encoding: "utf8", timeout: 5000 }).trim(); } catch { /* ignore */ }
  try { execFileSync(WSL_EXE, ["bash", "-c", `rm -f '${OUT}' '${DONE}'`], { timeout: 3000 }); } catch { /* ignore */ }
  try { unlinkSync(ps1Path); } catch { /* ignore */ }

  if (exitCode !== "0") throw new Error(output || `exit ${exitCode}`);
  return output;
}

// ── HTTP helper ───────────────────────────────────────────────────────────────

async function httpJson(url, opts = {}) {
  const full = url.startsWith("http") ? url : `${ORCH_URL}${url}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), opts.timeoutMs || 30000);
  const init = {
    method: opts.method || "GET",
    headers: { "content-type": "application/json", ...(opts.headers || {}) },
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
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${full}: ${text || "<empty>"}`);
    return text ? JSON.parse(text) : null;
  } finally {
    clearTimeout(timer);
  }
}

function idempotencyKey() {
  return crypto.randomUUID?.() || `idemp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function rabbitAuth() {
  const user = process.env.RABBITMQ_DEFAULT_USER || "guest";
  const pass = process.env.RABBITMQ_DEFAULT_PASS || "guest";
  return "Basic " + Buffer.from(`${user}:${pass}`).toString("base64");
}

// ── MCP Server ────────────────────────────────────────────────────────────────

const server = new McpServer({ name: "pockethive-bundles", version: "1.0.0" });

const HANDLER_TIMEOUT_MS = 150000; // 2.5 min max per tool call

function reg(name, desc, schema, handler) {
  server.registerTool(name, { title: name, description: desc, inputSchema: schema }, async (input = {}) => {
    try {
      const result = await Promise.race([
        handler(input),
        new Promise((_, reject) =>
          setTimeout(() => reject(new Error(`Tool '${name}' timed out after ${HANDLER_TIMEOUT_MS / 1000}s`)), HANDLER_TIMEOUT_MS)
        ),
      ]);
      return { content: [{ type: "text", text: JSON.stringify(result, null, 2) }] };
    } catch (err) {
      return { isError: true, content: [{ type: "text", text: `Error: ${err.message || err}` }] };
    }
  });
}

// ── Bundle management ─────────────────────────────────────────────────────────

reg("bundle.list", "List all scenario bundles in this repo", {}, async () => {
  const dir = getBundlesDir();
  if (!existsSync(dir)) return { bundles: [] };
  const bundles = readdirSync(dir, { withFileTypes: true })
    .filter(d => d.isDirectory())
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
  bundle: z.string().describe("Bundle name"),
  file: z.string().describe("Relative path within the bundle, e.g. 'scenario.yaml' or 'templates/default/my-call.yaml'"),
}, async ({ bundle, file }) => {
  const path = resolve(getBundlesDir(), bundle, file);
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
  const { mkdirSync } = await import("node:fs");
  const bundleDir = resolve(getBundlesDir(), bundleId);
  if (existsSync(bundleDir)) throw new Error(`Bundle '${bundleId}' already exists at ${bundleDir}`);
  mkdirSync(bundleDir, { recursive: true });

  const sutBaseUrl = sutType === "wiremock-local" ? "http://wiremock:8080"
    : sutType === "tcp-mock-local" ? "tcp://tcp-mock-server:8080"
    : "http://localhost:8080";
  const sutProtocol = sutType === "tcp-mock-local" ? "TCP" : "HTTP";

  const beesByPattern = {
    "rest-simple": [
      { role: "generator", image: "generator:latest", work: { out: { out: "proc" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 10 } }, worker: { message: { bodyType: "SIMPLE", body: "{}" } } } },
      { role: "processor", image: "processor:latest", work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}", worker: { mode: "THREAD_COUNT", threadCount: 5 } } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "rest-rbuilder": [
      { role: "generator", image: "generator:latest", work: { out: { out: "build" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 10 } },
          worker: { message: { bodyType: "SIMPLE", body: "{}", headers: { "x-ph-call-id": "my-call", "x-ph-service-id": "default" } } } } },
      { role: "request-builder", image: "request-builder:latest", work: { in: { in: "build" }, out: { out: "proc" } },
        config: { worker: { templateRoot: "/app/scenario/templates", serviceId: "default" } } },
      { role: "processor", image: "processor:latest", work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}", worker: { mode: "THREAD_COUNT", threadCount: 5 } } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "sequence": [
      { role: "generator", image: "generator:latest", work: { out: { out: "seq" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 5 } }, worker: { message: { bodyType: "SIMPLE", body: "{}" } } } },
      { role: "http-sequence", image: "http-sequence:latest", work: { in: { in: "seq" }, out: { out: "post" } },
        config: { worker: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}" } } },
      { role: "postprocessor", image: "postprocessor:latest", work: { in: { in: "post" } } },
    ],
    "tcp-simple": [
      { role: "generator", image: "generator:latest", work: { out: { out: "proc" } },
        config: { inputs: { type: "SCHEDULER", scheduler: { ratePerSec: 10 } }, worker: { message: { bodyType: "SIMPLE", body: "PING" } } } },
      { role: "processor", image: "processor:latest", work: { in: { in: "proc" }, out: { out: "post" } },
        config: { baseUrl: "{{ sut.endpoints['target'].baseUrl }}", worker: { mode: "THREAD_COUNT", threadCount: 5, tcpTransport: { type: "socket" } } } },
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
  writeFileSync(resolve(bundleDir, "scenario.yaml"), stringify(scenario), "utf8");

  if (pattern === "rest-rbuilder") {
    mkdirSync(resolve(bundleDir, "templates", "default"), { recursive: true });
    const tpl = `serviceId: default\ncallId: my-call\nprotocol: HTTP\nmethod: POST\npathTemplate: /api/endpoint\nbodyTemplate: |\n  {}\nheadersTemplate:\n  Content-Type: application/json\n`;
    writeFileSync(resolve(bundleDir, "templates", "default", "my-call.yaml"), tpl, "utf8");
  }

  if (sutType !== "none") {
    mkdirSync(resolve(bundleDir, "sut", sutType), { recursive: true });
    const sut = `id: ${sutType}\nname: ${sutType}\ntype: sandbox\nendpoints:\n  target:\n    kind: ${sutProtocol}\n    baseUrl: ${sutBaseUrl}\n`;
    writeFileSync(resolve(bundleDir, "sut", sutType, "sut.yaml"), sut, "utf8");
  }

  return { created: true, bundleId, path: bundleDir, pattern, sutType };
});

// In-memory job store for async validation results
const _validateJobs = new Map(); // jobId -> { status, result, error, startedAt }

// Sweep stale jobs older than 10 minutes to prevent unbounded growth
setInterval(() => {
  const cutoff = Date.now() - 10 * 60 * 1000;
  for (const [id, job] of _validateJobs) {
    if (job.startedAt < cutoff) _validateJobs.delete(id);
  }
}, 60_000).unref();

reg("bundle.validate", "Start async validation of a bundle. Returns a jobId immediately. Poll with bundle.validate.result.", {
  bundle: z.string().describe("Bundle name"),
}, async ({ bundle }) => {
  if (!POCKETHIVE_ROOT) throw new Error("POCKETHIVE_ROOT not set.");
  const bundleDir = resolve(getBundlesDir(), bundle);
  if (!existsSync(resolve(bundleDir, "scenario.yaml"))) throw new Error(`No scenario.yaml in ${bundle}`);

  const jobId = `${bundle}-${Date.now()}`;
  _validateJobs.set(jobId, { status: "running", result: null, error: null, startedAt: Date.now() });

  // Run validation in background — do not await
  (async () => {
    try {
      const bundleDir = resolve(getBundlesDir(), bundle);
      const results = { generator: null, httpTemplates: null };

      if (IS_WINDOWS) {
        // Windows: invoke Java via WSL to avoid pipe-inheritance hang
        const prWsl = await toWslPathAsync(POCKETHIVE_ROOT);
        const scenarioPath = await toWslPathAsync(joinPath(bundleDir.toString(), "scenario.yaml"));
        const classesDir = `${prWsl}/tools/scenario-templating-check/target/classes`;
        const cpCache = `${prWsl}/tools/scenario-templating-check/target/mcp-classpath.txt`;
        await shell(`mvn -q -f '${prWsl}/tools/scenario-templating-check/pom.xml' dependency:build-classpath -Dmdep.outputFile='${cpCache}' 2>/dev/null || true`, undefined, 120000);
        const javaBase = `source ~/.bashrc 2>/dev/null; java -cp '${classesDir}:'$(cat '${cpCache}') io.pockethive.tools.ScenarioTemplateValidator`;
        try {
          results.generator = await wslRunToFile(`${javaBase} --scenario '${scenarioPath}'`);
        } catch (e) { results.generator = `FAIL: ${e.message}`; }
        const templateDir = resolve(bundleDir, "templates");
        if (existsSync(templateDir)) {
          const tplPath = await toWslPathAsync(templateDir.toString());
          try {
            results.httpTemplates = await wslRunToFile(`${javaBase} --check-http-templates --scenario '${scenarioPath}' --template-root '${tplPath}'`);
          } catch (e) { results.httpTemplates = `FAIL: ${e.message}`; }
        }
      } else {
        // Linux/macOS: invoke Java directly via shell
        const scenarioPath = joinPath(bundleDir.toString(), "scenario.yaml");
        const classesDir = `${POCKETHIVE_ROOT}/tools/scenario-templating-check/target/classes`;
        const cpCache = `${POCKETHIVE_ROOT}/tools/scenario-templating-check/target/mcp-classpath.txt`;
        await shell(`mvn -q -f '${POCKETHIVE_ROOT}/tools/scenario-templating-check/pom.xml' dependency:build-classpath -Dmdep.outputFile='${cpCache}' 2>/dev/null || true`, undefined, 120000);
        const javaBase = `java -cp '${classesDir}':$(cat '${cpCache}') io.pockethive.tools.ScenarioTemplateValidator`;
        try {
          results.generator = await shell(`${javaBase} --scenario '${scenarioPath}'`);
        } catch (e) { results.generator = `FAIL: ${e.message}`; }
        const templateDir = resolve(bundleDir, "templates");
        if (existsSync(templateDir)) {
          try {
            results.httpTemplates = await shell(`${javaBase} --check-http-templates --scenario '${scenarioPath}' --template-root '${templateDir}'`);
          } catch (e) { results.httpTemplates = `FAIL: ${e.message}`; }
        }
      }

      _validateJobs.set(jobId, { status: "done", result: results, error: null, startedAt: _validateJobs.get(jobId).startedAt });
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

// ── Scenario sync ─────────────────────────────────────────────────────────────

reg("scenario.sync", "Sync bundles to the running Scenario Manager container and trigger reload", {}, async () => {
  let container;
  try {
    const raw = await shell('docker ps -q --filter "label=com.docker.compose.service=scenario-manager"');
    container = raw.split(/\r?\n/)[0]?.trim();
  } catch { container = ""; }
  if (!container) throw new Error("No running scenario-manager container found");
  await shell(`docker cp "${await toWslPathAsync(getBundlesDir())}/." "${container}:/app/scenarios-runtime/bundles"`);
  const reloadUrl = `${SM_URL}/scenarios/reload`;
  await httpJson(reloadUrl, { method: "POST" });
  return { synced: true, container, reloadUrl };
});

reg("scenario.deploy", "Deploy a bundle to the Scenario Manager's scenarios directory and reload. Use this (not scenario.sync) to make a bundle loadable via scenario.list/get. Works against both local and remote stacks — uses the HTTP bundle upload API, no Docker required.", {
  bundle: z.string().describe("Bundle name to deploy"),
}, async ({ bundle }) => {
  const bundleDir = resolve(getBundlesDir(), bundle);
  if (!existsSync(resolve(bundleDir, "scenario.yaml"))) throw new Error(`No scenario.yaml found in bundle '${bundle}'`);

  // ── Build zip in memory ──────────────────────────────────────────────────
  const { createWriteStream, createReadStream } = await import("node:fs");
  const { pipeline } = await import("node:stream/promises");
  const archiver = await import("archiver").catch(() => null);

  // Fall back to docker cp path if archiver is not available (local stack only)
  if (!archiver) {
    let container;
    try {
      const raw = await shell('docker ps -q --filter "label=com.docker.compose.service=scenario-manager"');
      container = raw.split(/\r?\n/)[0]?.trim();
    } catch { container = ""; }
    if (!container) throw new Error("archiver npm package not available and no local Docker container found. Run: npm install archiver in tools/mcp-server/");
    const jsonPath = resolve(bundleDir, "scenario.json");
    if (existsSync(jsonPath)) { const { unlinkSync } = await import("node:fs"); unlinkSync(jsonPath); }
    await shell(`docker cp "${await toWslPathAsync(bundleDir)}" "${container}:/app/scenarios/bundles/"`);
    await shell(`docker cp "${await toWslPathAsync(bundleDir)}" "${container}:/app/scenarios-runtime/bundles/"`);
    await httpJson(`${SM_URL}/scenarios/reload`, { method: "POST" });
    return { deployed: true, bundle, method: "docker-cp", container };
  }

  // Build zip from bundle directory
  const os = await import("node:os");
  const tmpZip = resolve(os.tmpdir(), `ph-bundle-${bundle}-${Date.now()}.zip`);
  await new Promise((res, rej) => {
    const output = createWriteStream(tmpZip);
    const archive = archiver.default("zip", { zlib: { level: 6 } });
    output.on("close", res);
    archive.on("error", rej);
    archive.pipe(output);
    archive.directory(bundleDir, false); // false = no top-level folder wrapper
    archive.finalize();
  });

  const zipBytes = readFileSync(tmpZip);
  try { unlinkSync(tmpZip); } catch { /* ignore */ }

  // Check if scenario already exists — use PUT (replace) or POST (create)
  let exists = false;
  try {
    await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(bundle)}`);
    exists = true;
  } catch { exists = false; }

  let result;
  try {
    if (exists) {
      // PUT /scenarios/{id}/bundle — replace existing
      result = await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(bundle)}/bundle`, {
        method: "PUT",
        body: zipBytes,
        headers: { "content-type": "application/zip" },
        timeoutMs: 60000,
      });
    } else {
      // POST /scenarios/bundles — create new
      result = await httpJson(`${SM_URL}/scenarios/bundles`, {
        method: "POST",
        body: zipBytes,
        headers: { "content-type": "application/zip" },
        timeoutMs: 60000,
      });
    }
  } catch (e) {
    // Surface the SM error body for easier diagnosis
    throw new Error(`Scenario Manager rejected bundle '${bundle}' (${exists ? "PUT replace" : "POST create"}): ${e.message}`);
  }

  return { deployed: true, bundle, method: exists ? "http-replace" : "http-create", scenario: result };
});

reg("scenario.list", "List scenarios loaded in the Scenario Manager", {}, async () => {
  return await httpJson(`${SM_URL}/scenarios`);
});

reg("scenario.get", "Get a specific scenario from the Scenario Manager", {
  scenarioId: z.string(),
}, async ({ scenarioId }) => {
  return await httpJson(`${SM_URL}/scenarios/${encodeURIComponent(scenarioId)}`);
});

// ── Swarm lifecycle ───────────────────────────────────────────────────────────

reg("swarm.list", "List all swarms from the Orchestrator", {}, async () => {
  return await httpJson("/api/swarms");
});

reg("swarm.get", "Get swarm status from the Orchestrator", {
  swarmId: z.string(),
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}`);
});

reg("swarm.create", "Create a new swarm from a scenario template", {
  swarmId: z.string().describe("Unique swarm identifier"),
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
  swarmId: z.string(),
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/start`, {
    method: "POST", body: { idempotencyKey: idempotencyKey() },
  });
});

reg("swarm.wait-ready", "Poll swarm status until all workers are healthy (totals.healthy == totals.desired). Call this after swarm.create before swarm.start to avoid NotReady rejections.", {
  swarmId: z.string(),
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
  swarmId: z.string(),
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/stop`, {
    method: "POST", body: { idempotencyKey: idempotencyKey() },
  });
});

reg("swarm.remove", "Remove a swarm (destructive — tears down containers and queues)", {
  swarmId: z.string(),
}, async ({ swarmId }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/remove`, {
    method: "POST", body: { idempotencyKey: idempotencyKey() },
  });
});

// ── Debugging ─────────────────────────────────────────────────────────────────

reg("debug.queues", "List RabbitMQ queues (optionally filtered by swarm prefix)", {
  swarmId: z.string().optional().describe("Filter queues by swarm ID prefix"),
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
  swarmId: z.string(),
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
  swarmId: z.string(),
  limit: z.number().optional().default(50),
}, async ({ swarmId, limit }) => {
  return await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/journal/page?limit=${limit}`);
});

reg("debug.docker-logs", "Read recent docker logs for a swarm's containers", {
  swarmId: z.string(),
  tail: z.number().optional().default(50).describe("Number of log lines"),
  role: z.string().optional().describe("Filter by worker role in container name"),
}, async ({ swarmId, tail, role }) => {
  const filter = role ? `${swarmId}.*${role}` : swarmId;
  let ids;
  try {
    const raw = await shell(`docker ps -q --filter "name=${filter}"`);
    ids = raw.split(/\r?\n/).filter(Boolean);
  } catch (e) {
    return { containers: [], message: `No containers found matching '${filter}'`, error: e.message };
  }
  if (ids.length === 0) return { containers: [], message: `No containers found matching '${filter}'` };
  const logs = {};
  for (const id of ids.slice(0, 10)) {
    try {
      const name = (await shell(`docker inspect --format "{{.Name}}" ${id}`)).replace(/^\//, "");
      logs[name] = await shell(`docker logs --tail ${tail} ${id} 2>&1`);
    } catch (e) { logs[id] = `Error: ${e.message}`; }
  }
  return { containers: Object.keys(logs), logs };
});

reg("debug.config-update", "Send a config-update signal to a worker via the Orchestrator", {
  swarmId: z.string(),
  role: z.string(),
  instanceId: z.string(),
  patch: z.record(z.any()).describe("Config patch object, e.g. {enabled: true, ratePerSec: 10}"),
}, async ({ swarmId, role, instanceId, patch }) => {
  return await httpJson(`/api/components/${encodeURIComponent(role)}/${encodeURIComponent(instanceId)}/config`, {
    method: "POST",
    body: { idempotencyKey: idempotencyKey(), patch, swarmId },
  });
});

reg("debug.prometheus", "Query Prometheus for metrics (instant query). Use to verify postprocessor metrics are flowing, e.g. ph_transaction_total_latency_ms", {
  query: z.string().describe("PromQL query, e.g. ph_transaction_total_latency_ms{ph_swarm=\"my-swarm\"}"),
}, async ({ query }) => {
  return await httpJson(`${PROM_URL}/api/v1/query?query=${encodeURIComponent(query)}`, { timeoutMs: 10000 });
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

// ── Path diagnostics ─────────────────────────────────────────────────────────

reg("paths.check", "Show all resolved paths used by the MCP server. Run this first when debugging path issues.", {}, async () => {
  const mountPrefix = await getWslMountPrefix();
  const prWsl = toWslPath(POCKETHIVE_ROOT, mountPrefix);
  const repoWsl = toWslPath(REPO_ROOT, mountPrefix);
  const checks = {
    REPO_ROOT_windows: REPO_ROOT,
    REPO_ROOT_wsl: repoWsl,
    POCKETHIVE_ROOT_raw: POCKETHIVE_ROOT || "(not set)",
    POCKETHIVE_ROOT_wsl: prWsl || "(not set)",
    IS_WINDOWS,
    WSL_EXE,
  };
  // Verify paths exist in WSL
  for (const [key, p] of [
    ["repo_exists", repoWsl],
    ["pockethive_exists", prWsl],
    ["validator_classes", prWsl && `${prWsl}/tools/scenario-templating-check/target/classes`],
    ["validator_cp_cache", prWsl && `${prWsl}/tools/scenario-templating-check/target/mcp-classpath.txt`],
  ]) {
    if (!p) { checks[key] = "SKIP (path not set)"; continue; }
    try {
      const r = execFileSync(WSL_EXE, ["bash", "-c", `test -e '${p}' && echo yes || echo no`],
        { encoding: "utf8", timeout: 5000 }).trim();
      checks[key] = r === "yes" ? "OK" : `MISSING: ${p}`;
    } catch (e) { checks[key] = `ERROR: ${e.message}`; }
  }
  // Show example resolved paths for a bundle
  const exampleBundle = existsSync(getBundlesDir())
    ? readdirSync(getBundlesDir(), { withFileTypes: true }).find(d => d.isDirectory())?.name
    : undefined;
  if (exampleBundle) {
    const bundleDir = resolve(getBundlesDir(), exampleBundle);
    checks.example_bundle = exampleBundle;
    checks.example_bundleDir_windows = bundleDir.toString();
    checks.example_bundleDir_wsl = toWslPath(bundleDir.toString(), mountPrefix);
    checks.example_scenarioPath_wsl = toWslPath(joinPath(bundleDir.toString(), "scenario.yaml"), mountPrefix);
  }
  return checks;
});


// ── Context tools ────────────────────────────────────────────────────────────

reg("context.get", "Return the current active configuration context. Call at session start to understand what the server is working with.", {}, async () => {
  return {
    bundlesRoot: getBundlesDir(),
    bundlesRootName: getBundlesDir().split(/[\\/]/).filter(Boolean).pop() || "",
    pockethiveRoot: POCKETHIVE_ROOT || "(not set)",
    baseUrl: BASE_URL,
    mcpVersion: "1.0.0",
    platform: platform(),
    allBundlesRoots: _bundlesRoots,
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

reg("env.current", "Return the active environment details (without secrets).", {}, async () => {
  const activeContent = existsSync(resolve(REPO_ROOT, ".env"))
    ? readFileSync(resolve(REPO_ROOT, ".env"), "utf8") : "";
  return {
    baseUrl: BASE_URL,
    rabbitUser: process.env.RABBITMQ_DEFAULT_USER || "guest",
    tcpMockUrl: TCP_MOCK_URL,
    wiremockUrl: WIREMOCK_URL,
    hasAuthToken: !!(process.env.GITHUB_TOKEN),
    pockethiveRoot: POCKETHIVE_ROOT || "(not set)",
    bundlesRoot: getBundlesDir(),
  };
});

reg("env.add", "Create a new named environment profile (.env.<name> file).", {
  name: z.string().describe("Profile name, e.g. 'nft-remote'"),
  baseUrl: z.string().describe("PocketHive base URL"),
  rabbitUser: z.string().optional().default("guest"),
  tcpMockUrl: z.string().optional().default(""),
  wiremockUrl: z.string().optional().default(""),
}, async ({ name, baseUrl, rabbitUser, tcpMockUrl, wiremockUrl }) => {
  const profileFile = resolve(REPO_ROOT, `.env.${name}`);
  const lines = [
    `# Profile: ${name}`,
    `POCKETHIVE_BASE_URL=${baseUrl}`,
    `RABBITMQ_DEFAULT_USER=${rabbitUser}`,
  ];
  if (tcpMockUrl) lines.push(`TCP_MOCK_BASE_URL=${tcpMockUrl}`);
  if (wiremockUrl) lines.push(`WIREMOCK_BASE_URL=${wiremockUrl}`);
  writeFileSync(profileFile, lines.join("\n") + "\n", "utf8");
  return { added: true, name, file: profileFile };
});

reg("env.remove", "Remove a named environment profile (.env.<name> file).", {
  name: z.string().describe("Profile name to remove"),
}, async ({ name }) => {
  const profileFile = resolve(REPO_ROOT, `.env.${name}`);
  if (!existsSync(profileFile)) throw new Error(`Profile '${name}' not found`);
  unlinkSync(profileFile);
  return { removed: true, name };
});

reg("env.list", "List available environment profiles (.env.<profile> files) and show the active one", {}, async () => {
  const profiles = [];
  const examples = [];
  const files = existsSync(REPO_ROOT) ? readdirSync(REPO_ROOT).filter(f => f.startsWith(".env.")) : [];
  for (const f of files) {
    if (f.startsWith(".env.example.")) {
      examples.push(f.replace(".env.example.", ""));
      continue;
    }
    const profile = f.replace(".env.", "");
    const content = readFileSync(resolve(REPO_ROOT, f), "utf8");
    const baseUrl = content.match(/^POCKETHIVE_BASE_URL=(.+)$/m)?.[1] || "(not set)";
    const tcpMock = content.match(/^TCP_MOCK_BASE_URL=(.+)$/m)?.[1] || "(auto-derived)";
    profiles.push({ profile, file: f, baseUrl, tcpMockUrl: tcpMock });
  }
  // Show active .env
  const activeContent = existsSync(resolve(REPO_ROOT, ".env"))
    ? readFileSync(resolve(REPO_ROOT, ".env"), "utf8") : "";
  const activeProfile = activeContent.match(/# Profile: (\S+)/)?.[1] ||
    activeContent.match(/# Activate with: scripts\/switch-env\.sh (\S+)/)?.[1] || "unknown";
  const activeBaseUrl = activeContent.match(/^POCKETHIVE_BASE_URL=(.+)$/m)?.[1] || BASE_URL;
  const result = { activeProfile, activeBaseUrl, profiles };
  if (profiles.length === 0) {
    result.hint = `No profiles found. Copy an example template to get started: ${examples.map(e => `cp .env.example.${e} .env.${e}`).join(" or ")}`;
  }
  if (examples.length > 0) result.exampleTemplates = examples;
  return result;
});

reg("env.switch", "Switch to a different environment profile. Copies .env.<profile> to .env. Requires IDE reload to take effect.", {
  profile: z.string().describe("Profile name, e.g. 'local' or 'remote-nft'"),
}, async ({ profile }) => {
  const profileFile = resolve(REPO_ROOT, `.env.${profile}`);
  if (!existsSync(profileFile)) {
    const available = readdirSync(REPO_ROOT)
      .filter(f => f.startsWith(".env.") && !f.startsWith(".env.example."))
      .map(f => f.replace(".env.", ""));
    const examples = readdirSync(REPO_ROOT)
      .filter(f => f.startsWith(".env.example."))
      .map(f => f.replace(".env.example.", ""));
    let msg = `Profile '${profile}' not found.`;
    if (available.length > 0) msg += ` Available: ${available.join(", ")}.`;
    if (examples.length > 0) msg += ` To create it from a template: cp .env.example.${examples[0]} .env.${profile}`;
    throw new Error(msg);
  }
  const content = readFileSync(profileFile, "utf8");
  writeFileSync(resolve(REPO_ROOT, ".env"), content, "utf8");
  const baseUrl = content.match(/^POCKETHIVE_BASE_URL=(.+)$/m)?.[1] || "(not set)";
  return {
    switched: true,
    profile,
    baseUrl,
    // requiresRestart signals the IDE plugin to kill and respawn the MCP server
    // with the new env vars — the server itself cannot reload its own environment.
    requiresRestart: true,
    message: "Profile switched. The IDE plugin will restart the MCP server automatically.",
  };
});

reg("stack.start", "Start the PocketHive stack via docker compose up. Use when health.check shows services are DOWN.", {
  build: z.boolean().optional().default(false).describe("If true, runs build-hive.sh --quick instead of docker compose up"),
}, async ({ build }) => {
  if (!POCKETHIVE_ROOT) throw new Error("POCKETHIVE_ROOT not set in .env");
  const mountPrefix = await getWslMountPrefix();
  const prWsl = toWslPath(POCKETHIVE_ROOT, mountPrefix);
  const cmd = build
    ? `cd '${prWsl}' && bash build-hive.sh --quick`
    : `cd '${prWsl}' && docker compose up -d`;
  // Fire and forget — detach immediately so MCP doesn't time out
  const proc = spawn(WSL_EXE, ["bash", "-lc", cmd], { stdio: "ignore", detached: true });
  proc.unref();
  return {
    started: true,
    build,
    message: "Stack start launched in background. Poll health.check every 10s to confirm services come up."
  };
});

reg("stack.stop", "Stop the PocketHive stack via docker compose down.", {}, async () => {
  if (!POCKETHIVE_ROOT) throw new Error("POCKETHIVE_ROOT not set in .env");
  const prWsl = await toWslPathAsync(POCKETHIVE_ROOT);
  const output = await shell(`cd '${prWsl}' && docker compose down`, undefined, 60000);
  return { stopped: true, output };
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

  const syncedFrom = resolve(REPO_ROOT, "docs/pockethive-ref/SYNCED_FROM.md");
  if (existsSync(syncedFrom)) {
    const content = readFileSync(syncedFrom, "utf8");
    const commitMatch = content.match(/Commit:\s*`([^`]+)`/);
    const dateMatch = content.match(/Date:\s*(\S+)/);
    results.pockethiveRefSynced = dateMatch?.[1] || "unknown";
    results.pockethiveRefCommit = commitMatch?.[1] || "unknown";
    if (POCKETHIVE_ROOT) {
      try {
        const phWsl = await toWslPathAsync(POCKETHIVE_ROOT);
        const headSha = await shell(`git -C "${phWsl}" rev-parse --short HEAD`);
        results.pockethiveHeadCommit = headSha;
        results.pockethiveRefStale = headSha !== (commitMatch?.[1] || "");
      } catch { /* ignore */ }
    }
  } else {
    results.pockethiveRefSynced = "NOT SYNCED — run scripts/sync-pockethive-ref.sh";
  }
  results.pockethiveRoot = POCKETHIVE_ROOT || "(not set)";
  results.baseUrl = BASE_URL;
  results.tcpMockUrl = TCP_MOCK_URL;
  results.wiremockUrl = WIREMOCK_URL;
  return results;
});

// ── Git helpers ───────────────────────────────────────────────────────────────

reg("git.status", "Show git status of the bundles repo", {}, async () => {
  const repoWsl = await toWslPathAsync(REPO_ROOT);
  const [status, branch] = await Promise.all([
    shell(`git -C '${repoWsl}' status --short`),
    shell(`git -C '${repoWsl}' rev-parse --abbrev-ref HEAD`),
  ]);
  return { status, branch };
});

reg("docs.refresh", "Re-sync PocketHive reference docs and regenerate derived files (capabilities, AGENTS.md). Run when health.check shows pockethiveRefStale=true.", {}, async () => {
  if (!POCKETHIVE_ROOT) throw new Error("POCKETHIVE_ROOT not set. Run scripts/init-dev.sh first.");
  const mountPrefix = await getWslMountPrefix();
  const syncScript = toWslPath(joinPath(REPO_ROOT, "scripts/sync-pockethive-ref.sh"), mountPrefix);
  const phRootWsl = toWslPath(POCKETHIVE_ROOT, mountPrefix);
  // Use -C flag pattern: no cwd needed, paths are explicit
  const output = await shell(`"${syncScript}" --pockethive-root "${phRootWsl}"`);
  // Run drift check — non-zero exit is informational, not an error
  let driftOutput = "";
  try {
    const driftScript = toWslPath(joinPath(REPO_ROOT, "scripts/check-docs-drift.mjs"), mountPrefix);
    driftOutput = await shell(`node '${driftScript}'`);
  } catch (e) {
    driftOutput = e.message || String(e);
  }
  return { refreshed: true, output, drift: driftOutput || "No drift detected." };
});

reg("git.diff", "Show git diff for a specific bundle or the whole repo", {
  bundle: z.string().optional(),
}, async ({ bundle }) => {
  const repoWsl = await toWslPathAsync(REPO_ROOT);
  const path = bundle ? `scenarios/bundles/${bundle}` : ".";
  return { diff: await shell(`git -C '${repoWsl}' diff -- "${path}"`) };
});

// ── Dev tools ─────────────────────────────────────────────────────────────────

function toolResult(label, result) {
  return { tool: label, success: result.success, exitCode: result.exitCode, stdout: result.stdout, stderr: result.stderr };
}

reg("docker.execute", "Execute Docker commands (build, run, ps, logs, stop, inspect, cp, etc.)", {
  command: z.string().describe("Docker command (build, run, ps, images, logs, stop, rm, pull, push, inspect, exec, compose, cp)"),
  args: z.array(z.string()).optional().describe("Command arguments"),
  workingDir: z.string().optional().describe("Working directory"),
}, async ({ command, args = [], workingDir }) => {
  const result = await dockerTool.execute(command, args, workingDir || REPO_ROOT);
  return toolResult(`docker ${command}`, result);
});

reg("docker.compose", "Execute Docker Compose commands (up, down, build, logs, ps, stop, restart)", {
  command: z.string().describe("Compose command (up, down, build, logs, ps, stop, restart)"),
  args: z.array(z.string()).optional().describe("Command arguments"),
  workingDir: z.string().optional().describe("Working directory (must contain docker-compose.yml)"),
}, async ({ command, args = [], workingDir }) => {
  const result = await dockerTool.composeExecute(command, args, workingDir || REPO_ROOT);
  return toolResult(`docker compose ${command}`, result);
});

reg("git.execute", "Execute Git commands with safety guards (status, log, diff, add, commit, push, branch, stash, etc.)", {
  command: z.string().describe("Git command (status, log, diff, branch, checkout, add, commit, push, pull, fetch, merge, rebase, stash, tag, show, blame)"),
  args: z.array(z.string()).optional().describe("Command arguments"),
  workingDir: z.string().optional().describe("Working directory"),
}, async ({ command, args = [], workingDir }) => {
  const result = await gitTool.execute(command, args, workingDir || REPO_ROOT);
  return toolResult(`git ${command}`, result);
});

reg("maven.execute", "Execute Maven commands (clean, compile, test, package, install, verify)", {
  command: z.string().describe("Maven command (clean, compile, test, package, install, verify)"),
  workingDir: z.string().optional().describe("Working directory (must contain pom.xml)"),
}, async ({ command, workingDir }) => {
  const result = await mavenTool.execute(command, workingDir || POCKETHIVE_ROOT || REPO_ROOT);
  return toolResult(`mvn ${command}`, result);
});

reg("npm.execute", "Execute NPM commands (install, test, build, run, audit, etc.)", {
  command: z.string().describe("NPM command (install, ci, run, test, build, audit, etc.)"),
  args: z.array(z.string()).optional().describe("Command arguments"),
  workingDir: z.string().optional().describe("Working directory"),
}, async ({ command, args = [], workingDir }) => {
  const result = await npmTool.execute(command, args, workingDir || REPO_ROOT);
  return toolResult(`npm ${command}`, result);
});

reg("tools.check", "Check which development tools are available on the system", {}, async () => {
  const checks = {};
  for (const [name, tool] of [["docker", dockerTool], ["git", gitTool], ["maven", mavenTool], ["npm", npmTool]]) {
    try { checks[name] = await tool.isAvailable(); } catch { checks[name] = false; }
  }
  return checks;
});

// ── GitHub Issues ────────────────────────────────────────────────────────────

const GITHUB_TOKEN = process.env.GITHUB_TOKEN;
const GITHUB_REPO = process.env.GITHUB_REPO || "sepa79/PocketHive";
const [GITHUB_OWNER, GITHUB_REPO_NAME] = GITHUB_REPO.split("/");

function githubAuth() {
  if (!GITHUB_TOKEN) throw new Error("GITHUB_TOKEN not set in .env");
  return `Bearer ${GITHUB_TOKEN}`;
}

reg("github.list_issues", "List issues in the GitHub repository", {
  state: z.enum(["open", "closed", "all"]).optional().default("open"),
  per_page: z.number().optional().default(10),
  page: z.number().optional().default(1),
  labels: z.array(z.string()).optional(),
}, async ({ state, per_page, page, labels }) => {
  let url = `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO_NAME}/issues?state=${state}&per_page=${per_page}&page=${page}`;
  if (labels && labels.length > 0) {
    url += `&labels=${labels.join(",")}`;
  }
  return await httpJson(url, {
    headers: { authorization: githubAuth() },
    timeoutMs: 10000,
  });
});

reg("github.get_issue", "Get details of a specific issue", {
  issue_number: z.number(),
}, async ({ issue_number }) => {
  return await httpJson(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO_NAME}/issues/${issue_number}`, {
    headers: { authorization: githubAuth() },
    timeoutMs: 10000,
  });
});

reg("github.create_issue", "Create a new issue in the GitHub repository", {
  title: z.string(),
  body: z.string().optional(),
  labels: z.array(z.string()).optional(),
  assignees: z.array(z.string()).optional(),
}, async ({ title, body, labels, assignees }) => {
  const issueData = { title };
  if (body) issueData.body = body;
  if (labels && labels.length > 0) issueData.labels = labels;
  if (assignees && assignees.length > 0) issueData.assignees = assignees;
  
  return await httpJson(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO_NAME}/issues`, {
    method: "POST",
    body: issueData,
    headers: { authorization: githubAuth() },
    timeoutMs: 10000,
  });
});

reg("github.update_issue", "Update an existing issue", {
  issue_number: z.number(),
  title: z.string().optional(),
  body: z.string().optional(),
  state: z.enum(["open", "closed"]).optional(),
  labels: z.array(z.string()).optional(),
  assignees: z.array(z.string()).optional(),
}, async ({ issue_number, title, body, state, labels, assignees }) => {
  const updateData = {};
  if (title) updateData.title = title;
  if (body) updateData.body = body;
  if (state) updateData.state = state;
  if (labels) updateData.labels = labels;
  if (assignees) updateData.assignees = assignees;
  
  return await httpJson(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO_NAME}/issues/${issue_number}`, {
    method: "PATCH",
    body: updateData,
    headers: { authorization: githubAuth() },
    timeoutMs: 10000,
  });
});

reg("github.add_issue_comment", "Add a comment to an existing issue", {
  issue_number: z.number(),
  body: z.string(),
}, async ({ issue_number, body }) => {
  return await httpJson(`https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO_NAME}/issues/${issue_number}/comments`, {
    method: "POST",
    body: { body },
    headers: { authorization: githubAuth() },
    timeoutMs: 10000,
  });
});

reg("github.search_issues", "Search for issues across the repository", {
  q: z.string().describe("Search query (GitHub search syntax)"),
  per_page: z.number().optional().default(10),
  page: z.number().optional().default(1),
}, async ({ q, per_page, page }) => {
  const searchQuery = q.includes("repo:") ? q : `repo:${GITHUB_OWNER}/${GITHUB_REPO_NAME} ${q}`;
  return await httpJson(`https://api.github.com/search/issues?q=${encodeURIComponent(searchQuery)}&per_page=${per_page}&page=${page}`, {
    headers: { authorization: githubAuth() },
    timeoutMs: 10000,
  });
});

// ── Start ─────────────────────────────────────────────────────────────────────

const transport = new StdioServerTransport();
await server.connect(transport);
