import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const DOCTOR = resolve(__dirname, "doctor.mjs");
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
  }));

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
      },
    },
  }), "utf8");

  const result = runDoctor(doctorEnv(), ["--config", configPath]);

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, new RegExp(`MCP config source - ${configPath.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}#pockethive-bundles`));
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
      },
    },
  }), "utf8");

  const result = runDoctor(doctorEnv(), ["--config", configPath]);

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}\n${result.stderr}`, /args must include tools\/pockethive-mcp\/start\.cjs/);
});
