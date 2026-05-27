import test from "node:test";
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import net from "node:net";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const DOCTOR = resolve(__dirname, "doctor.mjs");
const SERVER = resolve(__dirname, "server.mjs");
const START = resolve(__dirname, "start.cjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";

function doctorEnv(overrides = {}) {
  const env = {
    ...process.env,
    POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
    ORCHESTRATOR_BASE_URL: `${UNUSED_BASE_URL}/orchestrator`,
    SCENARIO_MANAGER_BASE_URL: `${UNUSED_BASE_URL}/scenario-manager`,
    RABBITMQ_MANAGEMENT_BASE_URL: `${UNUSED_BASE_URL}/rabbitmq/api`,
    POCKETHIVE_AUTH_TOKEN: "",
    POCKETHIVE_AUTH_USERNAME: "",
    ...overrides,
  };
  for (const key of ["BUNDLES_ROOT", "PH_BUNDLES_ROOTS", "PH_WORKFLOW_SOURCE_ROOTS", "PH_MCP_CONFIG", "PH_MCP_SERVER_ID"]) {
    if (overrides[key] === undefined) delete env[key];
  }
  return env;
}

function runDoctor(env, args = []) {
  return spawnSync(process.execPath, [DOCTOR, ...args], {
    cwd: __dirname,
    env,
    encoding: "utf8",
    timeout: 30000,
  });
}

function getFreePort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      server.close(() => resolvePort(address.port));
    });
  });
}

function waitForOutput(child, pattern, timeoutMs = 10000) {
  return new Promise((resolveWait, reject) => {
    let output = "";
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error(`Timed out waiting for ${pattern}. Output:\n${output}`));
    }, timeoutMs);
    function cleanup() {
      clearTimeout(timeout);
      child.stdout.off("data", onData);
      child.stderr.off("data", onData);
      child.off("exit", onExit);
      child.off("error", onError);
    }
    function onData(chunk) {
      output += chunk.toString();
      if (pattern.test(output)) {
        cleanup();
        resolveWait(output);
      }
    }
    function onExit(code) {
      cleanup();
      reject(new Error(`Server exited before startup with code ${code}. Output:\n${output}`));
    }
    function onError(err) {
      cleanup();
      reject(err);
    }
    child.stdout.on("data", onData);
    child.stderr.on("data", onData);
    child.on("exit", onExit);
    child.on("error", onError);
  });
}

async function stopChild(child) {
  if (child.exitCode !== null) return;
  await new Promise(resolveStop => {
    const timeout = setTimeout(() => {
      if (child.exitCode === null) child.kill("SIGKILL");
      resolveStop();
    }, 2000);
    child.once("exit", () => {
      clearTimeout(timeout);
      resolveStop();
    });
    child.kill("SIGTERM");
  });
}

test("doctor fails clearly when BUNDLES_ROOT is not configured", () => {
  const result = runDoctor(doctorEnv(), ["--no-config"]);

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /BUNDLES_ROOT is not set/);
});

test("doctor reports unknown CLI arguments without a stack trace", () => {
  const result = runDoctor(doctorEnv(), ["--unknown"]);

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /FAIL CLI arguments - Unknown argument: --unknown/);
  assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /at parseArgs/);
});

test("doctor passes with explicit bundle root and validates root arrays", () => {
  const bundlesRoot = mkdtempSync(join(tmpdir(), "ph-doctor-bundles-"));
  const sourceRoot = mkdtempSync(join(tmpdir(), "ph-doctor-sources-"));
  const result = runDoctor(doctorEnv({
    BUNDLES_ROOT: bundlesRoot,
    PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
    PH_WORKFLOW_SOURCE_ROOTS: JSON.stringify([sourceRoot]),
  }), ["--no-config"]);

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /PocketHive MCP doctor passed/);
});

test("doctor can load explicit MCP config and resolve relative bundle roots", () => {
  const configRoot = mkdtempSync(join(tmpdir(), "ph-doctor-config-"));
  mkdirSync(resolve(configRoot, "bundles"), { recursive: true });
  mkdirSync(resolve(configRoot, "sources"), { recursive: true });
  const configPath = resolve(configRoot, "mcp.json");
  writeFileSync(configPath, JSON.stringify({
    mcpServers: {
      "pockethive-bundles": {
        command: "node",
        args: [START],
        env: {
          POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
          BUNDLES_ROOT: "bundles",
          PH_BUNDLES_ROOTS: "[\"bundles\"]",
          PH_WORKFLOW_SOURCE_ROOTS: "[\"sources\"]",
        },
        disabled: false,
      },
    },
  }), "utf8");

  const result = runDoctor(doctorEnv(), ["--config", configPath]);

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, new RegExp(`MCP config source - ${configPath.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}#pockethive-bundles`));
  assert.match(result.stdout, new RegExp(resolve(configRoot, "bundles").replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(result.stdout, new RegExp(resolve(configRoot, "sources").replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
});

test("doctor can load VS Code MCP config with workspaceFolder variables", () => {
  const configRoot = mkdtempSync(join(tmpdir(), "ph-doctor-vscode-config-"));
  mkdirSync(resolve(configRoot, "bundles"), { recursive: true });
  mkdirSync(resolve(configRoot, "sources"), { recursive: true });
  mkdirSync(resolve(configRoot, ".vscode"), { recursive: true });
  const configPath = resolve(configRoot, ".vscode", "mcp.json");
  writeFileSync(configPath, JSON.stringify({
    servers: {
      "pockethive-bundles": {
        type: "stdio",
        command: "node",
        args: [START],
        env: {
          POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
          BUNDLES_ROOT: "${workspaceFolder}/bundles",
          PH_BUNDLES_ROOTS: "[\"${workspaceFolder}/bundles\"]",
          PH_WORKFLOW_SOURCE_ROOTS: "[\"${workspaceFolder}/sources\"]",
        },
        disabled: false,
      },
    },
  }), "utf8");

  const result = runDoctor(doctorEnv(), ["--config", configPath]);

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, new RegExp(resolve(configRoot, "bundles").replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  assert.match(result.stdout, new RegExp(resolve(configRoot, "sources").replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
});

test("doctor reports invalid MCP config as a normal failed check", () => {
  const configRoot = mkdtempSync(join(tmpdir(), "ph-doctor-bad-config-"));
  const configPath = resolve(configRoot, "mcp.json");
  writeFileSync(configPath, "{not json", "utf8");

  const result = runDoctor(doctorEnv(), ["--config", configPath]);

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /FAIL MCP config source/);
  assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /SyntaxError:/);
});

test("doctor fails closed when an explicit MCP config path is missing", () => {
  const result = runDoctor(doctorEnv(), ["--config", "missing-mcp.json"]);

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /MCP config not found: missing-mcp\.json/);
});

test("doctor rejects MCP config that does not point at the PocketHive MCP entrypoint", () => {
  const configRoot = mkdtempSync(join(tmpdir(), "ph-doctor-wrong-entrypoint-"));
  mkdirSync(resolve(configRoot, "bundles"), { recursive: true });
  const configPath = resolve(configRoot, "mcp.json");
  writeFileSync(configPath, JSON.stringify({
    mcpServers: {
      "pockethive-bundles": {
        command: "node",
        args: ["wrong-start.cjs"],
        env: {
          POCKETHIVE_BASE_URL: UNUSED_BASE_URL,
          BUNDLES_ROOT: "bundles",
        },
        disabled: false,
      },
    },
  }), "utf8");

  const result = runDoctor(doctorEnv(), ["--config", configPath]);

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /args must include tools\/pockethive-mcp\/start\.cjs/);
});

test("doctor can load Streamable HTTP MCP config and reconnect", async () => {
  const configRoot = mkdtempSync(join(tmpdir(), "ph-doctor-http-config-"));
  const bundlesRoot = resolve(configRoot, "bundles");
  const sourceRoot = resolve(configRoot, "sources");
  mkdirSync(bundlesRoot, { recursive: true });
  mkdirSync(sourceRoot, { recursive: true });
  const port = await getFreePort();
  const configPath = resolve(configRoot, "mcp.json");
  writeFileSync(configPath, JSON.stringify({
    mcpServers: {
      "pockethive-bundles": {
        url: `http://127.0.0.1:${port}/mcp`,
        timeout: 300000,
        disabled: false,
      },
    },
  }), "utf8");

  const child = spawn(process.execPath, [SERVER], {
    cwd: __dirname,
    env: doctorEnv({
      PH_MCP_HTTP_PORT: String(port),
      BUNDLES_ROOT: bundlesRoot,
      PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
      PH_WORKFLOW_SOURCE_ROOTS: JSON.stringify([sourceRoot]),
    }),
    stdio: ["ignore", "pipe", "pipe"],
  });

  try {
    await waitForOutput(child, /Streamable HTTP listening/);
    const first = runDoctor(doctorEnv(), ["--config", configPath]);
    assert.equal(first.status, 0, `${first.stdout}\n${first.stderr}`);
    assert.match(first.stdout, /MCP Streamable HTTP startup/);
    assert.match(first.stdout, new RegExp(bundlesRoot.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));

    const second = runDoctor(doctorEnv(), ["--config", configPath]);
    assert.equal(second.status, 0, `${second.stdout}\n${second.stderr}`);
  } finally {
    await stopChild(child);
  }
});
