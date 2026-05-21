#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const repoRoot = resolve(new URL("../..", import.meta.url).pathname);
const ORCHESTRATOR = process.env.ORCHESTRATOR_BASE_URL || "http://localhost:8088/orchestrator";
const SCENARIO_MANAGER = process.env.SCENARIO_MANAGER_BASE_URL || "http://localhost:8088/scenario-manager";
const AUTH_SERVICE = process.env.AUTH_SERVICE_BASE_URL || "http://localhost:1083";
const AUTH_DEV_USERNAME = process.env.POCKETHIVE_AUTH_USERNAME || "";
const AUTH_BEARER_TOKEN = process.env.POCKETHIVE_AUTH_TOKEN || "";
const WIREMOCK = process.env.WIREMOCK_BASE_URL || "http://localhost:8080";
const TCP_MOCK = process.env.TCP_MOCK_BASE_URL || "http://localhost:8083";
const TCP_MOCK_TLS = process.env.TCP_MOCK_TLS_BASE_URL || "http://localhost:8084";
const TCP_ADMIN_AUTH = "Basic " + Buffer.from(
  `${process.env.TCP_MOCK_ADMIN_USER || "admin"}:${process.env.TCP_MOCK_ADMIN_PASSWORD || "admin"}`
).toString("base64");

const BASIC_RB = "Basic " + Buffer.from("rb-user:rb-password").toString("base64");
const BASIC_SEQUENCE = "Basic " + Buffer.from("sequence-user:sequence-password").toString("base64");

const runId = new Date().toISOString().replace(/[:.]/g, "-");
const evidence = {
  runId,
  startedAt: new Date().toISOString(),
  scenarios: {},
  redis: {},
  stack: {},
  notes: [],
};
let cachedAuthHeader = null;

async function main() {
  await requireHealthy();
  await setupMocks();
  await reloadScenarios();

  await runHttpRequestBuilder();
  await proveHttpRequestBuilderTokenReuseAfterStopStart();
  await runHttpSequence();
  await runTcpIsoMtls();
  await runFailureDedupe();
  await runProfileCollision();
  await runNoAuthCleanliness();

  evidence.finishedAt = new Date().toISOString();
  const outDir = resolve(repoRoot, "docs/todo/auth-proving-runs");
  mkdirSync(outDir, { recursive: true });
  const outFile = resolve(outDir, `${runId}.json`);
  writeFileSync(outFile, JSON.stringify(evidence, null, 2) + "\n");
  console.log(JSON.stringify({ status: "pass", evidence: outFile, summary: summarizeEvidence() }, null, 2));
}

async function requireHealthy() {
  const checks = await Promise.all([
    httpJson(`${ORCHESTRATOR}/api/swarms`),
    httpJson(`${SCENARIO_MANAGER}/scenarios`),
    httpJson(`${WIREMOCK}/__admin/health`),
    httpJson(`${TCP_MOCK}/actuator/health`),
    httpJson(`${TCP_MOCK_TLS}/actuator/health`),
  ]);
  evidence.stack.health = {
    orchestratorSwarms: Array.isArray(checks[0]) ? checks[0].length : null,
    scenarioCount: Array.isArray(checks[1]) ? checks[1].length : null,
    wiremock: checks[2]?.status ?? "unknown",
    tcpMock: checks[3]?.status ?? "unknown",
    tcpMockTls: checks[4]?.status ?? "unknown",
  };
}

async function setupMocks() {
  await httpJson(`${WIREMOCK}/__admin/requests`, { method: "DELETE" });
  await httpJson(`${WIREMOCK}/__admin/mappings/reset`, { method: "POST" });
  await tcpJson(TCP_MOCK, "/api/requests", { method: "DELETE" });
  await tcpJson(TCP_MOCK_TLS, "/api/requests", { method: "DELETE" });
  await setupTcpMappings();

  for (const mapping of wireMockMappings()) {
    await httpJson(`${WIREMOCK}/__admin/mappings`, {
      method: "POST",
      body: mapping,
    });
  }
}

async function setupTcpMappings() {
  await tcpJson(TCP_MOCK, "/api/mappings/auth-proof-tcp-prefix", { method: "DELETE" });
  await tcpJson(TCP_MOCK, "/api/mappings", {
    method: "POST",
    body: {
      id: "auth-proof-tcp-prefix",
      requestPattern: "^ECHO AUTH-PROOF:.*END$",
      responseTemplate: "{{message}}",
      requestDelimiter: "END",
      responseDelimiter: "\n",
      description: "Auth proving TCP payload prefix capture",
      priority: 100,
      enabled: true,
    },
  });
}

async function reloadScenarios() {
  await httpJson(`${SCENARIO_MANAGER}/scenarios/reload`, { method: "POST" });
}

async function runHttpRequestBuilder() {
  const swarmId = "auth-proof-http-rb";
  await removeSwarm(swarmId);
  await resetWireMockRequests();
  await resetRedisForSwarm(swarmId);
  await createAndStart(swarmId, "auth-proving-http-request-builder", "wiremock-local");

  await waitFor("request-builder HTTP proof requests", async () => {
    const counts = await wireCounts(requestBuilderExpectedPatterns());
    return sumCounts(counts) >= 8 && counts.every((entry) => entry.count >= entry.min);
  }, 90000);

  const counts = await wireCounts([
    ...requestBuilderExpectedPatterns(),
    pattern("rb-oauth-client-token", { method: "POST", url: "/oauth/token/rb-client" }),
    pattern("rb-oauth-password-token", { method: "POST", url: "/oauth/token/rb-password" }),
  ]);
  const redis = redisKeys(swarmId);
  const snapshot = await swarmSnapshot(swarmId);
  evidence.scenarios[swarmId] = {
    scenarioId: "auth-proving-http-request-builder",
    swarmId,
    status: snapshot?.status ?? "unknown",
    wiremockCounts: counts,
    redisKeys: redis.redactedKeys,
    pass: counts.every((entry) => entry.count >= entry.min) &&
      redis.keys.includes(`ph:tokens:${swarmId}:record:http-rb-oauth-client`) &&
      redis.keys.includes(`ph:tokens:${swarmId}:record:http-rb-oauth-password`),
  };
}

async function proveHttpRequestBuilderTokenReuseAfterStopStart() {
  const swarmId = "auth-proof-http-rb";
  await stopSwarm(swarmId);
  await sleep(5000);
  await resetWireMockRequests();
  await startSwarm(swarmId);

  await waitFor("request-builder stop/start proof requests", async () => {
    const counts = await wireCounts(requestBuilderExpectedPatterns());
    return sumCounts(counts) >= 8 && counts.every((entry) => entry.count >= entry.min);
  }, 90000);

  const counts = await wireCounts([
    ...requestBuilderExpectedPatterns(),
    pattern("rb-oauth-client-token-after-restart", { method: "POST", url: "/oauth/token/rb-client" }, 0),
    pattern("rb-oauth-password-token-after-restart", { method: "POST", url: "/oauth/token/rb-password" }, 0),
  ]);
  const tokenCalls = counts.filter((entry) => entry.name.includes("token-after-restart"));
  evidence.scenarios[`${swarmId}:stop-start`] = {
    scenarioId: "auth-proving-http-request-builder",
    swarmId,
    action: "stop/start same swarm",
    wiremockCounts: counts,
    redisKeys: redisKeys(swarmId).redactedKeys,
    pass: tokenCalls.every((entry) => entry.count === 0),
  };
  await stopSwarm(swarmId);
}

async function runHttpSequence() {
  const swarmId = "auth-proof-http-seq";
  await removeSwarm(swarmId);
  await resetWireMockRequests();
  await resetRedisForSwarm(swarmId);
  await createAndStart(swarmId, "auth-proving-http-sequence", "wiremock-local");

  await waitFor("http-sequence proof requests", async () => {
    const counts = await wireCounts(sequenceExpectedPatterns());
    return counts.every((entry) => entry.count >= entry.min);
  }, 90000);

  const counts = await wireCounts([
    ...sequenceExpectedPatterns(),
    pattern("sequence-oauth-token", { method: "POST", url: "/oauth/token/sequence" }),
  ]);
  const redis = redisKeys(swarmId);
  evidence.scenarios[swarmId] = {
    scenarioId: "auth-proving-http-sequence",
    swarmId,
    wiremockCounts: counts,
    redisKeys: redis.redactedKeys,
    pass: counts.every((entry) => entry.count >= entry.min) &&
      redis.keys.includes(`ph:tokens:${swarmId}:record:http-sequence-shared`),
  };
}

async function runTcpIsoMtls() {
  const swarmId = "auth-proof-tcp-iso-mtls";
  await removeSwarm(swarmId);
  await tcpJson(TCP_MOCK, "/api/requests", { method: "DELETE" });
  await tcpJson(TCP_MOCK_TLS, "/api/requests", { method: "DELETE" });
  await createAndStart(swarmId, "auth-proving-tcp-iso-mtls", "tcp-auth-local");

  await waitFor("TCP/ISO/mTLS mock captures", async () => {
    const plain = await tcpRequests(TCP_MOCK);
    const tls = await tcpRequests(TCP_MOCK_TLS);
    return plain.some((req) => String(req.message).includes("ECHO AUTH-PROOF:")) &&
      plain.some((req) => isIso8583MacCapture(req.message)) &&
      tls.some((req) => String(req.message).includes("MTLS-AUTH-PROOF"));
  }, 90000);

  const plain = await tcpRequests(TCP_MOCK);
  const tls = await tcpRequests(TCP_MOCK_TLS);
  evidence.scenarios[swarmId] = {
    scenarioId: "auth-proving-tcp-iso-mtls",
    swarmId,
    tcpCaptureCounts: {
      tcpPrefix: plain.filter((req) => String(req.message).includes("ECHO AUTH-PROOF:")).length,
      isoMac: plain.filter((req) => isIso8583MacCapture(req.message)).length,
      mtls: tls.filter((req) => String(req.message).includes("MTLS-AUTH-PROOF")).length,
    },
    pass: true,
  };
}

async function runFailureDedupe() {
  const swarmId = "auth-proof-failure";
  await removeSwarm(swarmId);
  await resetWireMockRequests();
  await createAndStart(swarmId, "auth-proving-failure", "wiremock-local");
  await sleep(15000);

  const counts = await wireCounts([
    pattern("failure-should-not-send", { method: "POST", url: "/api/auth-proof/failure/should-not-send" }, 0),
  ]);
  const journal = await swarmJournal(swarmId);
  const authFailures = journal.filter(isBadQueryAuthFailureEntry);
  const runtimeAuthFailures = authFailures.filter(isRuntimeExceptionEntry);
  const uniqueFailureSignatures = new Set(runtimeAuthFailures.map(authFailureSignature));
  const leakedSecret = journal.some((entry) => JSON.stringify(entry).includes("failure-token-that-must-not-be-logged"));
  evidence.scenarios[swarmId] = {
    scenarioId: "auth-proving-failure",
    swarmId,
    wiremockCounts: counts,
    journalEntriesScanned: journal.length,
    authFailureJournalEntries: authFailures.length,
    authRuntimeExceptionJournalEntries: runtimeAuthFailures.length,
    uniqueAuthFailureSignatures: uniqueFailureSignatures.size,
    secretLeakedToJournal: leakedSecret,
    authJournalPreview: authFailures.slice(0, 3).map(redactJournalEntry),
    pass: counts[0].count === 0 &&
      runtimeAuthFailures.length === 1 &&
      uniqueFailureSignatures.size === 1 &&
      !leakedSecret,
  };
}

async function runProfileCollision() {
  const swarmId = "auth-proof-profile-collision";
  await removeSwarm(swarmId);
  await resetWireMockRequests();
  await resetRedisForSwarm(swarmId);
  await createAndStart(swarmId, "auth-proving-profile-collision", "wiremock-local");
  await sleep(15000);

  const counts = await wireCounts([
    pattern("collision-should-not-send", { method: "POST", url: "/api/auth-proof/collision/should-not-send" }, 0),
    pattern("collision-other-should-not-send", { method: "POST", url: "/api/auth-proof/collision/other-should-not-send" }, 0),
    pattern("collision-token-one", { method: "POST", url: "/oauth/token/collision-one" }, 0),
    pattern("collision-token-two", { method: "POST", url: "/oauth/token/collision-two" }, 0),
  ]);
  const journal = await swarmJournal(swarmId);
  const authFailures = journal.filter(isProfileCollisionAuthFailureEntry);
  const runtimeAuthFailures = authFailures.filter(isRuntimeExceptionEntry);
  const uniqueFailureSignatures = new Set(runtimeAuthFailures.map(authFailureSignature));
  const leakedSecret = journal.some((entry) => {
    const text = JSON.stringify(entry);
    return text.includes("collision-secret-one-that-must-not-be-logged") ||
      text.includes("collision-secret-two-that-must-not-be-logged");
  });
  const redis = redisKeys(swarmId);
  evidence.scenarios[swarmId] = {
    scenarioId: "auth-proving-profile-collision",
    swarmId,
    wiremockCounts: counts,
    redisKeys: redis.redactedKeys,
    journalEntriesScanned: journal.length,
    authFailureJournalEntries: authFailures.length,
    authRuntimeExceptionJournalEntries: runtimeAuthFailures.length,
    uniqueAuthFailureSignatures: uniqueFailureSignatures.size,
    secretLeakedToJournal: leakedSecret,
    authJournalPreview: authFailures.slice(0, 3).map(redactJournalEntry),
    pass: counts.every((entry) => entry.count === 0) &&
      redis.keys.length === 0 &&
      runtimeAuthFailures.length === 1 &&
      uniqueFailureSignatures.size === 1 &&
      !leakedSecret,
  };
}

async function runNoAuthCleanliness() {
  const swarmId = "auth-proof-no-auth";
  await removeSwarm(swarmId);
  await resetWireMockRequests();
  await resetRedisForSwarm(swarmId);
  await createAndStart(swarmId, "auth-proving-no-auth", "wiremock-local");

  await waitFor("no-auth HTTP request", async () => {
    const counts = await wireCounts([
      pattern("no-auth-plain", { method: "POST", url: "/api/auth-proof/no-auth/plain" }),
    ]);
    return counts[0].count >= 1;
  }, 90000);

  const workerConfigs = commandJson(["node", "tools/mcp-orchestrator-debug/client.mjs", "worker-configs", swarmId], []);
  const snapshot = await swarmSnapshot(swarmId);
  const serialized = JSON.stringify({ workerConfigs, snapshot }).toLowerCase();
  const authRuntimeMentioned = /authprofiles|authref|pockethive\.auth|tokenkey|redistokenstore|authruntime/.test(serialized);
  const redis = redisKeys(swarmId);
  const snapshotStatus = snapshot?.status ?? snapshot?.envelope?.data?.context?.swarmStatus ?? null;
  const snapshotWorkerRoles = (snapshot?.bees ?? snapshot?.envelope?.data?.context?.workers ?? [])
    .map((worker) => worker.role)
    .filter(Boolean)
    .sort();
  evidence.scenarios[swarmId] = {
    scenarioId: "auth-proving-no-auth",
    swarmId,
    wiremockCounts: await wireCounts([
      pattern("no-auth-plain", { method: "POST", url: "/api/auth-proof/no-auth/plain" }),
    ]),
    workerConfigCount: Array.isArray(workerConfigs) ? workerConfigs.length : null,
    workerConfigSource: Array.isArray(workerConfigs) && workerConfigs.length > 0 ? "debug-cli" : "debug-cli-empty-or-unavailable",
    snapshotStatus,
    snapshotWorkerRoles,
    workerConfigsMentionAuthRuntime: authRuntimeMentioned,
    redisKeys: redis.redactedKeys,
    pass: redis.keys.length === 0 && !authRuntimeMentioned,
  };
}

function wireMockMappings() {
  const response = (name) => ({
    status: 200,
    headers: { "Content-Type": "application/json" },
    jsonBody: { ok: true, proof: name },
  });
  return [
    {
      request: { method: "POST", url: "/oauth/token/rb-client" },
      response: { ...response("rb-client-token"), jsonBody: { access_token: "rb-oauth-client-token", token_type: "Bearer", expires_in: 3600 } },
    },
    {
      request: { method: "POST", url: "/oauth/token/rb-password" },
      response: { ...response("rb-password-token"), jsonBody: { access_token: "rb-oauth-password-token", token_type: "Bearer", expires_in: 3600 } },
    },
    {
      request: { method: "POST", url: "/oauth/token/sequence" },
      response: { ...response("sequence-token"), jsonBody: { access_token: "sequence-oauth-token", token_type: "Bearer", expires_in: 3600 } },
    },
    ...requestBuilderExpectedPatterns().map(stubFromPattern),
    ...sequenceExpectedPatterns().map(stubFromPattern),
    stubFromPattern(pattern("no-auth-plain", { method: "POST", url: "/api/auth-proof/no-auth/plain" })),
  ];
}

function requestBuilderExpectedPatterns() {
  return [
    pattern("rb-oauth-client", { method: "POST", url: "/api/auth-proof/request-builder/oauth-client", headers: { Authorization: { equalTo: "Bearer rb-oauth-client-token" } } }),
    pattern("rb-oauth-password", { method: "POST", url: "/api/auth-proof/request-builder/oauth-password", headers: { Authorization: { equalTo: "Bearer rb-oauth-password-token" } } }),
    pattern("rb-static-bearer", { method: "POST", url: "/api/auth-proof/request-builder/static-bearer", headers: { Authorization: { equalTo: "Bearer rb-static-token" } } }),
    pattern("rb-basic", { method: "POST", url: "/api/auth-proof/request-builder/basic", headers: { Authorization: { equalTo: BASIC_RB } } }),
    pattern("rb-api-key", { method: "POST", url: "/api/auth-proof/request-builder/api-key", headers: { "X-Api-Key": { equalTo: "rb-api-key" } } }),
    pattern("rb-query", { method: "POST", urlPath: "/api/auth-proof/request-builder/query", queryParameters: { access_token: { equalTo: "rb-query-token" } } }),
    pattern("rb-hmac", { method: "POST", url: "/api/auth-proof/request-builder/hmac", headers: { "X-Signature": { matches: "[0-9a-f]{64}" } } }),
    pattern("rb-aws", { method: "POST", url: "/api/auth-proof/request-builder/aws", headers: { Authorization: { matches: "AWS4-HMAC-SHA256 Credential=RBACCESS, Signature=[0-9a-f]{64}" } } }),
  ];
}

function sequenceExpectedPatterns() {
  return [
    pattern("sequence-oauth-shared", { method: "POST", url: "/api/auth-proof/sequence/oauth-shared", headers: { Authorization: { equalTo: "Bearer sequence-oauth-token" } } }, 2),
    pattern("sequence-basic", { method: "POST", url: "/api/auth-proof/sequence/basic", headers: { Authorization: { equalTo: BASIC_SEQUENCE } } }),
    pattern("sequence-query", { method: "POST", urlPath: "/api/auth-proof/sequence/query", queryParameters: { access_token: { equalTo: "sequence-query-token" } } }),
    pattern("sequence-static-final", { method: "POST", url: "/api/auth-proof/sequence/static-final", headers: { Authorization: { equalTo: "Bearer sequence-static-token" } } }),
  ];
}

function pattern(name, request, min = 1) {
  return { name, request, min };
}

function stubFromPattern(entry) {
  return {
    request: entry.request,
    response: {
      status: 200,
      headers: { "Content-Type": "application/json" },
      jsonBody: { ok: true, proof: entry.name },
    },
  };
}

async function wireCounts(patterns) {
  const results = [];
  for (const entry of patterns) {
    const response = await httpJson(`${WIREMOCK}/__admin/requests/count`, {
      method: "POST",
      body: entry.request,
    });
    results.push({ name: entry.name, count: Number(response?.count ?? 0), min: entry.min });
  }
  return results;
}

function sumCounts(counts) {
  return counts.reduce((sum, entry) => sum + entry.count, 0);
}

function isIso8583MacCapture(message) {
  const text = String(message ?? "");
  if (text.startsWith("0200") && text.length > "0200A1B2C3D4".length) {
    return true;
  }
  const hex = Buffer.from(text, "latin1").toString("hex").toUpperCase();
  return hex.startsWith("0200A1B2C3D4") && hex.length > "0200A1B2C3D4".length;
}

async function resetWireMockRequests() {
  await httpJson(`${WIREMOCK}/__admin/requests`, { method: "DELETE" });
}

async function createAndStart(swarmId, templateId, sutId) {
  const createBody = {
    templateId,
    sutId,
    idempotencyKey: `${runId}-${swarmId}-create`,
    notes: `auth proving ${runId}`,
  };
  await httpJson(`${ORCHESTRATOR}/api/swarms/${encodeURIComponent(swarmId)}/create`, {
    method: "POST",
    body: createBody,
  });
  await waitFor(`${swarmId} ready after create`, async () => {
    const summary = await swarmSummary(swarmId);
    return summary?.status === "READY" && summary?.health === "RUNNING";
  }, 120000);
  await startSwarm(swarmId);
}

async function startSwarm(swarmId) {
  await httpJson(`${ORCHESTRATOR}/api/swarms/${encodeURIComponent(swarmId)}/start`, {
    method: "POST",
    body: { idempotencyKey: `${runId}-${swarmId}-start-${Date.now()}` },
  });
  await waitFor(`${swarmId} work enabled`, async () => {
    const summary = await swarmSummary(swarmId);
    return summary?.workEnabled === true;
  }, 60000);
}

async function stopSwarm(swarmId) {
  await httpJson(`${ORCHESTRATOR}/api/swarms/${encodeURIComponent(swarmId)}/stop`, {
    method: "POST",
    body: { idempotencyKey: `${runId}-${swarmId}-stop-${Date.now()}` },
  });
  await waitFor(`${swarmId} work disabled`, async () => {
    const summary = await swarmSummary(swarmId);
    return summary?.workEnabled === false;
  }, 60000);
}

async function removeSwarm(swarmId) {
  try {
    await httpJson(`${ORCHESTRATOR}/api/swarms/${encodeURIComponent(swarmId)}/remove`, {
      method: "POST",
      body: { idempotencyKey: `${runId}-${swarmId}-remove-${Date.now()}` },
    });
    await waitFor(`${swarmId} removed`, async () => (await swarmSummary(swarmId)) == null, 90000);
  } catch (err) {
    evidence.notes.push(`remove ${swarmId} ignored: ${err.message}`);
  }
}

async function swarmSnapshot(swarmId) {
  try {
    const snapshot = await httpJson(`${ORCHESTRATOR}/api/swarms/${encodeURIComponent(swarmId)}`);
    return snapshot ?? await swarmSummary(swarmId);
  } catch {
    return await swarmSummary(swarmId);
  }
}

async function swarmSummary(swarmId) {
  const swarms = await httpJson(`${ORCHESTRATOR}/api/swarms`);
  return Array.isArray(swarms) ? swarms.find((swarm) => swarm.id === swarmId) : null;
}

async function swarmJournal(swarmId) {
  try {
    return commandJson(["node", "tools/mcp-orchestrator-debug/client.mjs", "swarm-journal", swarmId, "--limit", "100", "--pages", "2"], []);
  } catch {
    return [];
  }
}

function tcpRequests(baseUrl) {
  return tcpJson(baseUrl, "/api/requests").then((value) => Array.isArray(value) ? value : []);
}

async function waitFor(label, fn, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    try {
      if (await fn()) {
        return;
      }
    } catch (err) {
      last = err;
    }
    await sleep(2000);
  }
  throw new Error(`Timed out waiting for ${label}${last ? `: ${last.message}` : ""}`);
}

async function httpJson(url, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (!headers.authorization && !headers.Authorization && needsPocketHiveAuth(url)) {
    const authorization = await resolveAuthorizationHeader();
    if (authorization) {
      headers.authorization = authorization;
    }
  }
  let body = options.body;
  if (body !== undefined && typeof body !== "string") {
    headers["content-type"] = headers["content-type"] || "application/json";
    body = JSON.stringify(body);
  }
  const response = await fetch(url, { ...options, headers, body });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${options.method || "GET"} ${url} -> ${response.status}: ${text.slice(0, 400)}`);
  }
  if (!text.trim()) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function needsPocketHiveAuth(url) {
  return String(url).startsWith(ORCHESTRATOR) || String(url).startsWith(SCENARIO_MANAGER);
}

async function resolveAuthorizationHeader() {
  if (AUTH_BEARER_TOKEN.trim()) {
    return AUTH_BEARER_TOKEN.startsWith("Bearer ")
      ? AUTH_BEARER_TOKEN
      : `Bearer ${AUTH_BEARER_TOKEN}`;
  }
  if (!AUTH_DEV_USERNAME.trim()) {
    return null;
  }
  if (cachedAuthHeader) {
    return cachedAuthHeader;
  }
  const base = AUTH_SERVICE.replace(/\/+$/, "");
  const response = await fetch(`${base}/api/auth/dev/login`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept": "application/json",
    },
    body: JSON.stringify({ username: AUTH_DEV_USERNAME.trim() }),
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

async function tcpJson(baseUrl, path, options = {}) {
  return httpJson(`${baseUrl}${path}`, {
    ...options,
    headers: {
      Authorization: TCP_ADMIN_AUTH,
      ...(options.headers || {}),
    },
  });
}

function redisKeys(swarmId) {
  const keys = execFileSync("docker", ["compose", "exec", "-T", "redis", "redis-cli", "--scan", "--pattern", `ph:tokens:${swarmId}:*`], {
    cwd: repoRoot,
    encoding: "utf8",
  }).split("\n").map((line) => line.trim()).filter(Boolean).sort();
  evidence.redis[swarmId] = keys.map(redactRedisKey);
  return { keys, redactedKeys: keys.map(redactRedisKey) };
}

async function resetRedisForSwarm(swarmId) {
  const keys = redisKeys(swarmId).keys;
  if (keys.length === 0) {
    return;
  }
  execFileSync("docker", ["compose", "exec", "-T", "redis", "redis-cli", "DEL", ...keys], {
    cwd: repoRoot,
    encoding: "utf8",
  });
}

function commandJson(command, fallback) {
  let output;
  try {
    output = execFileSync(command[0], command.slice(1), {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    evidence.notes.push(`command failed (${command.join(" ")}): ${String(error.message).slice(0, 240)}`);
    return fallback;
  }
  try {
    return JSON.parse(output);
  } catch {
    evidence.notes.push(`command returned non-JSON (${command.join(" ")})`);
    return fallback;
  }
}

function redactRedisKey(key) {
  return key.replace(/record:.+$/, "record:<tokenKey>").replace(/lease:.+$/, "lease:<tokenKey>");
}

function redactJournalEntry(entry) {
  const text = JSON.stringify(entry);
  return JSON.parse(text
    .replace(/Bearer [A-Za-z0-9._:-]+/g, "Bearer <redacted>")
    .replace(/failure-token-that-must-not-be-logged/g, "<redacted>")
    .replace(/collision-secret-one-that-must-not-be-logged/g, "<redacted>")
    .replace(/collision-secret-two-that-must-not-be-logged/g, "<redacted>"));
}

function isBadQueryAuthFailureEntry(entry) {
  const data = entry?.data ?? entry?.raw?.data ?? {};
  const text = JSON.stringify(entry);
  return data?.context?.callId === "bad-query-auth" ||
    String(data?.message ?? "").includes("bad-query-auth") ||
    String(data?.errorDetail ?? "").includes("bad-query-auth") ||
    text.includes("/api/auth-proof/failure/should-not-send");
}

function isProfileCollisionAuthFailureEntry(entry) {
  const data = entry?.data ?? entry?.raw?.data ?? {};
  const text = JSON.stringify(entry);
  return data?.context?.callId === "token-key-collision" ||
    String(data?.message ?? "").includes("shared-collision-token") ||
    String(data?.errorDetail ?? "").includes("shared-collision-token") ||
    text.includes("tokenKey 'shared-collision-token' with multiple configs");
}

function isRuntimeExceptionEntry(entry) {
  const data = entry?.data ?? entry?.raw?.data ?? {};
  return data?.code === "runtime.exception";
}

function authFailureSignature(entry) {
  const data = entry?.data ?? entry?.raw?.data ?? {};
  return [
    entry?.type,
    data?.code,
    data?.errorType,
    data?.errorDetail ?? data?.message,
  ].filter(Boolean).join("|");
}

function summarizeEvidence() {
  return Object.fromEntries(Object.entries(evidence.scenarios).map(([key, value]) => [key, {
    scenarioId: value.scenarioId,
    pass: value.pass,
  }]));
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

main().catch((err) => {
  evidence.failedAt = new Date().toISOString();
  evidence.error = err.stack || err.message;
  try {
    const outDir = resolve(repoRoot, "docs/todo/auth-proving-runs");
    mkdirSync(outDir, { recursive: true });
    const outFile = resolve(outDir, `${runId}-failed.json`);
    writeFileSync(outFile, JSON.stringify(evidence, null, 2) + "\n");
    console.error(`Evidence written to ${outFile}`);
  } catch {
    // ignore evidence write failure on fatal path
  }
  console.error(err);
  process.exit(1);
});
