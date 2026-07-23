import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { createServer } from "node:http";
import net from "node:net";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const START = resolve(__dirname, "start.cjs");
const UNUSED_BASE_URL = "http://127.0.0.1:9";

async function withClient(bundlesRoot, fn, envOverrides = {}) {
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
      BUNDLES_ROOT: bundlesRoot,
      PH_BUNDLES_ROOTS: JSON.stringify([bundlesRoot]),
      ...envOverrides,
    },
  });
  const client = new Client({ name: "workflow-test", version: "1.0.0" }, { capabilities: {} });
  await client.connect(transport);
  try {
    await fn(client);
  } finally {
    await client.close();
  }
}

async function call(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) {
    throw new Error(result.content?.[0]?.text || `${name} failed`);
  }
  return JSON.parse(result.content[0].text);
}

async function callRaw(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  if (result.isError) {
    throw new Error(result.content?.[0]?.text || `${name} failed`);
  }
  return result;
}

async function callError(client, name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  assert.equal(result.isError, true, `${name} should fail`);
  return result.content?.[0]?.text || "";
}

function writeSource(root, relativePath = "sources/minimal.jmx") {
  const path = resolve(root, relativePath);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `<jmeterTestPlan><hashTree><HTTPSamplerProxy testname="GET /hello"/></hashTree></jmeterTestPlan>`, "utf8");
  return path;
}

function completePlan(bundleId) {
  return {
    bundleId,
    protocol: "REST",
    target: "wiremock-local",
    endpoints: [{ method: "GET", path: "/hello", callId: "hello" }],
    traffic: { ratePerSec: 2, shape: "flat", duration: "30s" },
    dataset: { strategy: "SCHEDULER" },
    mock: { strategy: "wiremock" },
    observability: { goal: "Stakeholders need throughput, latency, and mock-match evidence.", grafanaDashboard: "rtt_overview" },
    successCriteria: { resultRules: false, summary: "HTTP 2xx responses and no unmatched mock requests." },
    sourceFidelity: { status: "complete", convertedFeatures: ["http-method", "path", "traffic"], unsupportedConstructs: [] },
  };
}

function requiredProvenance(source = "user") {
  return {
    "plan.target": { source, note: "Confirmed target strategy." },
    "plan.dataset.strategy": { source, note: "Confirmed dataset strategy." },
    "plan.auth": { source, note: "Confirmed no auth required for this fixture." },
    "plan.successCriteria": { source, note: "Confirmed acceptance criteria." },
    "plan.traffic.ratePerSec": { source, note: "Confirmed target rate." },
    "plan.traffic.shape": { source, note: "Confirmed traffic shape." },
    "plan.traffic.duration": { source, note: "Confirmed duration." },
    "plan.observability.goal": { source, note: "Confirmed stakeholder evidence goal." },
  };
}

async function completeThreeAmigos(client, workflowId, roles = ["architect", "developer", "tester"]) {
  for (const roleId of roles) {
    await call(client, "workflow_role_check", {
      workflowId,
      stageId: "three-amigos",
      roleId,
      outcome: "pass",
      summary: `${roleId} review passed for test fixture.`,
      risks: [],
    });
  }
}

async function withMockHiveMind(fn) {
  const calls = [];
  const server = createServer(async (req, res) => {
    let body = "";
    for await (const chunk of req) body += chunk;
    const message = JSON.parse(body || "{}");
    calls.push(message);
    res.setHeader("content-type", "application/json");
    res.setHeader("mcp-session-id", "mock-hivemind-session");
    if (message.method === "initialize") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { protocolVersion: "2025-06-18", capabilities: {}, serverInfo: { name: "mock-hivemind", version: "1" } } }));
    } else if (message.method === "tools/list") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { tools: [{ name: "session_start" }, { name: "entry_append" }, { name: "session_end" }] } }));
    } else if (message.method === "tools/call" && message.params?.name === "session_start") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { content: [{ type: "text", text: JSON.stringify({ session_id: "hm-session-1" }) }] , session_id: "hm-session-1" } }));
    } else if (message.method === "tools/call") {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, result: { content: [{ type: "text", text: JSON.stringify({ ok: true }) }] } }));
    } else {
      res.end(JSON.stringify({ jsonrpc: "2.0", id: message.id, error: { code: -32601, message: "not found" } }));
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    await fn(`http://127.0.0.1:${address.port}/mcp`, calls);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
}

async function withFakeRedis(fn) {
  const server = net.createServer((socket) => {
    socket.on("data", () => {
      socket.write("*0\r\n");
    });
  });
  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const { port } = server.address();
  try {
    await fn({ host: "127.0.0.1", port });
  } finally {
    await new Promise((resolveClose) => server.close(resolveClose));
  }
}

async function withScenarioManagerValidationClient(bundlesRoot, fn, options = {}) {
  const responses = Array.isArray(options.responses) && options.responses.length
    ? options.responses
    : [{ ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: "test-bundle", summary: { errors: 0, warnings: 0 }, findings: [] }];
  let validationCalls = 0;
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/scenario-manager/validation/scenario-bundles") {
      validationCalls += 1;
      for await (const _ of req) { /* drain zip upload */ }
      const response = responses[Math.min(validationCalls - 1, responses.length - 1)];
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(typeof response === "function" ? response(validationCalls) : response));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `unhandled ${req.method} ${url.pathname}` }));
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await withClient(bundlesRoot, fn, {
      POCKETHIVE_BASE_URL: baseUrl,
      ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
      ...(options.env || {}),
    });
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
  return validationCalls;
}

async function withFakePocketHiveStack(bundleId, fn, options = {}) {
  const readyAfterPolls = options.readyAfterPolls ?? 1;
  const requestOnStart = options.requestOnStart ?? true;
  const requestAfterJournalReads = options.requestAfterJournalReads ?? 1;
  const state = {
    uploaded: false,
    started: false,
    stopped: false,
    mappings: [],
    requests: [],
    requestJournalReset: false,
    stopCalls: 0,
    createCalls: 0,
    startCalls: 0,
    readyPolls: 0,
    requestJournalReads: 0,
    taps: new Map(),
    tapCreates: 0,
    tapReads: 0,
    grafanaQueryCalls: 0,
    lastGrafanaQuery: null,
  };
  const scenario = {
    id: bundleId,
    name: bundleId,
    template: { bees: [] },
    plan: {
      endpoints: [{ method: "GET", path: "/hello", callId: "hello" }],
      dataSource: "SCHEDULER",
    },
  };

  function send(res, status, payload) {
    res.writeHead(status, { "content-type": "application/json" });
    res.end(payload === undefined ? "" : JSON.stringify(payload));
  }

  async function requestJson(req) {
    let body = "";
    for await (const chunk of req) body += chunk;
    return body ? JSON.parse(body) : null;
  }

  function swarmStateView() {
    if (!state.started && !state.stopped) state.readyPolls += 1;
    const ready = state.stopped || state.started || state.readyPolls >= readyAfterPolls;
    const running = state.started && !state.stopped;
    const stopped = state.stopped;
    return {
      id: "agent-live-stack",
      runId: "run-1",
      runtimeIntent: "PRESENT",
      workloadIntent: running ? "RUNNING" : "STOPPED",
      controllerState: ready ? "READY" : "PROVISIONING",
      workloadState: running ? "RUNNING" : ready ? "STOPPED" : "UNAVAILABLE",
      health: ready ? "HEALTHY" : "UNKNOWN",
      runtimeResourceState: "PRESENT",
      observationStale: !ready,
      templateId: bundleId,
      bees: [],
      observation: { workers: [] },
    };
  }

  function addExpectedRequest() {
    if (state.requests.length) return;
    const mapping = state.mappings.find(candidate => candidate?.request?.urlPath === "/hello");
    state.requests.push({
      request: { method: "GET", url: "/hello", loggedDate: Date.now(), headers: {} },
      response: { status: mapping?.response?.status || 404, body: JSON.stringify(mapping?.response?.jsonBody || {}) },
      wasMatched: Boolean(mapping),
      stubMapping: mapping || null,
    });
  }

  function tapSamples() {
    if (options.tapSamples) {
      return typeof options.tapSamples === "function" ? options.tapSamples(state) : options.tapSamples;
    }
    const steps = state.requests.flatMap(entry => ([
      {
        index: 0,
        payload: JSON.stringify({
          kind: "http.request",
          request: {
            method: entry.request.method,
            path: String(entry.request.url || "").split("?")[0],
          },
        }),
        headers: { "ph.step.service": "request-builder" },
      },
      {
        index: 1,
        payload: JSON.stringify({
          kind: "http.result",
          request: {
            method: entry.request.method,
            path: String(entry.request.url || "").split("?")[0],
            url: `http://wiremock:8080${String(entry.request.url || "").split("?")[0]}`,
          },
          outcome: { status: entry.response.status },
        }),
        headers: { "ph.step.service": "processor" },
      },
    ]));
    return steps.length ? [{ body: JSON.stringify({ steps }) }] : [];
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");

    if (req.method === "GET" && url.pathname === `/scenario-manager/scenarios/${bundleId}`) {
      return state.uploaded ? send(res, 200, scenario) : send(res, 404, { error: "missing" });
    }
    if (req.method === "POST" && url.pathname === "/scenario-manager/scenarios/bundles") {
      for await (const _ of req) { /* drain zip upload */ }
      state.uploaded = true;
      return send(res, 200, scenario);
    }
    if (req.method === "POST" && url.pathname === "/scenario-manager/validation/scenario-bundles") {
      for await (const _ of req) { /* drain zip upload */ }
      return send(res, 200, { ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: bundleId, summary: { errors: 0, warnings: 0 }, findings: [] });
    }
    if (req.method === "POST" && url.pathname === "/wiremock/__admin/mappings") {
      const mapping = await requestJson(req);
      state.mappings.push(mapping);
      return send(res, 201, { id: `mapping-${state.mappings.length}` });
    }
    if (req.method === "DELETE" && url.pathname === "/wiremock/__admin/requests") {
      state.requests = [];
      state.requestJournalReset = true;
      return send(res, 200, {});
    }
    if (req.method === "GET" && url.pathname === "/wiremock/__admin/requests") {
      state.requestJournalReads += 1;
      if (state.started && !requestOnStart && state.requestJournalReads >= requestAfterJournalReads) addExpectedRequest();
      return send(res, 200, { requests: state.requests });
    }
    if (req.method === "GET" && url.pathname === "/wiremock/__admin/requests/unmatched") {
      return send(res, 200, { requests: [] });
    }
    if (req.method === "GET" && url.pathname === "/rabbitmq/api/queues") {
      const queued = state.started && !state.stopped ? 1 : 0;
      return send(res, 200, [
        { name: "ph.agent-live-stack.build", messages: queued, consumers: state.stopped ? 0 : 1 },
        { name: "ph.agent-live-stack.proc", messages: 0, consumers: state.stopped ? 0 : 1 },
        { name: "ph.agent-live-stack.post", messages: 0, consumers: state.stopped ? 0 : 1 },
      ]);
    }
    if (req.method === "POST" && url.pathname === "/grafana/api/ds/query") {
      const body = await requestJson(req);
      state.grafanaQueryCalls += 1;
      state.lastGrafanaQuery = body;
      if (options.grafanaResponse !== undefined) {
        return send(res, options.grafanaStatus ?? 200, options.grafanaResponse);
      }
      return send(res, 200, {
        results: {
          A: {
            status: 200,
            frames: [
              {
                schema: { fields: [{ name: "total" }, { name: "success" }] },
                data: { values: [[5], [5]] },
              },
            ],
          },
        },
      });
    }
    if (req.method === "GET" && url.pathname === "/tcp-mock/api/requests") {
      return send(res, 200, []);
    }
    if (req.method === "GET" && url.pathname === "/tcp-mock/api/requests/unmatched") {
      return send(res, 200, []);
    }
    if (req.method === "POST" && url.pathname === "/orchestrator/api/swarms/agent-live-stack/create") {
      await requestJson(req);
      state.createCalls += 1;
      return send(res, 202, { accepted: true });
    }
    if (req.method === "GET" && url.pathname === "/orchestrator/api/swarms/agent-live-stack/operations/start-corr") {
      return send(res, 200, { state: "SUCCEEDED" });
    }
    if (req.method === "GET" && url.pathname === "/orchestrator/api/swarms/agent-live-stack/operations/stop-corr") {
      return send(res, 200, { state: "SUCCEEDED" });
    }
    if (req.method === "GET" && url.pathname === "/orchestrator/api/swarms/agent-live-stack") {
      return send(res, 200, swarmStateView());
    }
    if (req.method === "POST" && url.pathname === "/orchestrator/api/swarms/agent-live-stack/start") {
      await requestJson(req);
      state.startCalls += 1;
      state.started = true;
      if (requestOnStart) addExpectedRequest();
      return send(res, 202, {
        correlationId: "start-corr",
        idempotencyKey: "start-idem",
        operationUrl: "/api/swarms/agent-live-stack/operations/start-corr",
        outcomeTopic: "event.outcome.swarm-start.agent-live-stack.orchestrator.orch-1",
        timeoutMs: 180000,
      });
    }
    if (req.method === "POST" && url.pathname === "/orchestrator/api/swarms/agent-live-stack/stop") {
      await requestJson(req);
      state.stopCalls += 1;
      state.stopped = true;
      return send(res, 202, {
        correlationId: "stop-corr",
        idempotencyKey: "stop-idem",
        operationUrl: "/api/swarms/agent-live-stack/operations/stop-corr",
        outcomeTopic: "event.outcome.swarm-stop.agent-live-stack.orchestrator.orch-1",
        timeoutMs: 90000,
      });
    }
    if (req.method === "GET" && url.pathname === "/orchestrator/api/swarms/agent-live-stack/journal/page") {
      return send(res, 200, { items: [], nextCursor: null, hasMore: false });
    }
    if (req.method === "POST" && url.pathname === "/orchestrator/api/debug/taps") {
      const body = await requestJson(req);
      state.tapCreates += 1;
      const tapId = `tap-${state.tapCreates}`;
      const tap = { tapId, ...body };
      state.taps.set(tapId, tap);
      return send(res, 200, tap);
    }
    if (req.method === "GET" && url.pathname.startsWith("/orchestrator/api/debug/taps/")) {
      state.tapReads += 1;
      const tapId = decodeURIComponent(url.pathname.split("/").pop());
      if (!state.taps.has(tapId)) return send(res, 404, { error: "tap missing" });
      return send(res, 200, {
        tapId,
        samples: tapSamples(),
      });
    }
    if (req.method === "DELETE" && url.pathname.startsWith("/orchestrator/api/debug/taps/")) {
      const tapId = decodeURIComponent(url.pathname.split("/").pop());
      state.taps.delete(tapId);
      return send(res, 200, { ok: true });
    }

    return send(res, 404, { error: `unhandled ${req.method} ${url.pathname}` });
  });

  await new Promise((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
  const { port } = server.address();
  try {
    await fn({ baseUrl: `http://127.0.0.1:${port}`, state });
  } finally {
    await new Promise((resolveClose) => server.close(resolveClose));
  }
}

test("metrics_query reads product metrics through Grafana ClickHouse datasource", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakePocketHiveStack("agent-metrics", async ({ baseUrl, state }) => {
    await withClient(root, async (client) => {
      const result = await call(client, "metrics_query", {
        swarmId: "agent-live-stack",
        kind: "tx-outcomes-summary",
        from: "now-1h",
        to: "now",
      });

      assert.equal(result.source, "grafana-clickhouse");
      assert.equal(result.datasource.uid, "clickhouse");
      assert.equal(result.query.kind, "tx-outcomes-summary");
      assert.equal(result.query.table, "ph_tx_outcome_v2");
      assert.deepEqual(result.rows, [{ total: 5, success: 5 }]);
      assert.equal(state.grafanaQueryCalls, 1);
      assert.equal(state.lastGrafanaQuery.from, "now-1h");
      assert.equal(state.lastGrafanaQuery.to, "now");
      assert.equal(state.lastGrafanaQuery.queries[0].datasource.uid, "clickhouse");
      assert.equal(state.lastGrafanaQuery.queries[0].queryType, "table");
      assert.match(state.lastGrafanaQuery.queries[0].rawSql, /ph_tx_outcome_v2/);
    }, {
      POCKETHIVE_BASE_URL: baseUrl,
      POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
      POCKETHIVE_GRAFANA_USERNAME: "pockethive",
      POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
      POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
    });
  });
});

test("metrics_query fails closed when Grafana metrics config is incomplete", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const error = await callError(client, "metrics_query", {
      swarmId: "agent-live-stack",
      kind: "tx-outcomes-summary",
      from: "now-1h",
      to: "now",
    });

    assert.match(error, /POCKETHIVE_GRAFANA_BASE_URL must be configured/);
  });
});

test("metrics_query fails closed when Grafana ClickHouse returns a datasource error", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakePocketHiveStack("agent-metrics-backpressure", async ({ baseUrl, state }) => {
    await withClient(root, async (client) => {
      const error = await callError(client, "metrics_query", {
        swarmId: "agent-live-stack",
        kind: "tx-outcomes-summary",
        from: "now-1h",
        to: "now",
      });

      assert.match(error, /Grafana ClickHouse query A failed: ClickHouse backpressure/);
      assert.equal(state.grafanaQueryCalls, 1);
    }, {
      POCKETHIVE_BASE_URL: baseUrl,
      POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
      POCKETHIVE_GRAFANA_USERNAME: "pockethive",
      POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
      POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
    });
  }, {
    grafanaResponse: {
      results: {
        A: {
          error: "ClickHouse backpressure",
        },
      },
    },
  });
});

test("metrics_query fails closed when Grafana metrics API is unavailable", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakePocketHiveStack("agent-metrics-down", async ({ baseUrl, state }) => {
    await withClient(root, async (client) => {
      const error = await callError(client, "metrics_query", {
        swarmId: "agent-live-stack",
        kind: "tx-outcomes-summary",
        from: "now-1h",
        to: "now",
      });

      assert.match(error, /HTTP 503/);
      assert.match(error, /clickhouse unavailable/);
      assert.equal(state.grafanaQueryCalls, 1);
    }, {
      POCKETHIVE_BASE_URL: baseUrl,
      POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
      POCKETHIVE_GRAFANA_USERNAME: "pockethive",
      POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
      POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
    });
  }, {
    grafanaStatus: 503,
    grafanaResponse: {
      error: "clickhouse unavailable",
    },
  });
});

test("workflow_start rejects source paths outside allowed roots", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const outside = resolve(tmpdir(), `ph-workflow-outside-${Date.now()}.jmx`);
  writeFileSync(outside, "<jmeterTestPlan/>", "utf8");

  await withScenarioManagerValidationClient(root, async (client) => {
    const error = await callError(client, "workflow_start", { sourceType: "jmeter", sourcePath: outside });
    assert.match(error, /WORKFLOW_SOURCE_OUTSIDE_ALLOWED_ROOTS/);
  }, {
    responses: [
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      { ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: "agent-stuck", summary: { errors: 0, warnings: 0 }, findings: [] },
    ],
  });
});

test("workflow_start accepts file and instruction sources", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const fromFile = await call(client, "workflow_start", { sourceType: "jmeter", sourcePath });
    assert.equal(fromFile.state, "source_ready");
    assert.equal(fromFile.source.type, "jmeter");
    assert.equal(fromFile.source.path, sourcePath);
    assert.match(fromFile.source.sha256, /^[a-f0-9]{64}$/);
    assert.ok(fromFile.missing.includes("plan.bundleId"));
    assert.ok(fromFile.nextQuestions.some((question) => question.id === "plan.bundleId"));
    assert.ok(fromFile.nextQuestions.some((question) => question.id === "plan.dataset.strategy"));

    const source = await call(client, "workflow_source_read", { workflowId: fromFile.workflowId, maxBytes: 40 });
    assert.equal(source.truncated, true);
    assert.match(source.content, /jmeterTestPlan/);

    const fromInstructions = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a REST smoke test for GET /hello with a WireMock double.",
    });
    assert.equal(fromInstructions.source.path, null);
    assert.match(fromInstructions.source.sha256, /^[a-f0-9]{64}$/);
  });
});

test("workflow examples list canonical repo examples before active bundle examples", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const teamBundle = resolve(root, "local-rest-schema-demo");
  mkdirSync(teamBundle, { recursive: true });
  writeFileSync(resolve(teamBundle, "scenario.yaml"), "id: local-rest-schema-demo\nname: Team override\n", "utf8");

  await withScenarioManagerValidationClient(root, async (client) => {
    const listed = await call(client, "workflow_examples_list");
    assert.deepEqual(listed.sourceOrder.map(source => source.id), ["repo-examples", "active-bundles-root"]);
    const canonical = listed.examples.find(example => example.bundleId === "local-rest-schema-demo" && example.source === "repo-examples");
    const team = listed.examples.find(example => example.bundleId === "local-rest-schema-demo" && example.source === "active-bundles-root");
    assert.ok(canonical, "canonical repo example should be listed");
    assert.ok(team, "active bundle example should be listed");
    assert.equal(team.shadowedBy, "repo-examples/local-rest-schema-demo");
    assert.ok(listed.examples.indexOf(canonical) < listed.examples.indexOf(team));

    const got = await call(client, "workflow_examples_get", { bundleId: "local-rest-schema-demo" });
    assert.equal(got.example.source, "repo-examples");
    assert.equal(got.example.authority, "canonical-example");

    const recommended = await call(client, "workflow_examples_recommend", { intent: "google http smoke test", limit: 5 });
    assert.ok(recommended.recommendations.length > 0);
    assert.ok(recommended.recommendations.every(example => typeof example.score === "number"));
  });
});

test("workflow config and list are read-only plugin status surfaces", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const config = await call(client, "workflow_config_get");
    assert.equal(config.workflowType, "agent-to-pockethive");
    assert.equal(config.bundleRoot, root);
    assert.ok(config.allowedSourceRoots.includes(root));
    assert.equal(config.defaultProfileId, "novice-test-builder");
    assert.ok(config.roles.some((role) => role.id === "pockethive-sme"));
    assert.ok(config.profiles.some((profile) => profile.id === "performance-engineer"));
    assert.equal(config.pluginBoundary.mayAnswerQuestions, false);
    assert.ok(config.pluginBoundary.readOnlyTools.includes("workflow_list"));
    assert.ok(config.pluginBoundary.readOnlyTools.includes("workflow_profiles_list"));

    const validation = await call(client, "workflow_config_validate");
    assert.equal(validation.ok, true);
    assert.deepEqual(validation.missing, []);

    const started = await call(client, "workflow_start", { sourceType: "openapi", sourcePath });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      answers: { datasetOwner: "platform-team" },
      plan: { bundleId: "agent-status-only" },
    });

    const listed = await call(client, "workflow_list");
    assert.equal(listed.count, 1);
    assert.equal(listed.workflows[0].workflowId, started.workflowId);
    assert.equal(listed.workflows[0].state, "plan_incomplete");
    assert.equal(listed.workflows[0].profile.id, "novice-test-builder");
    assert.equal(listed.workflows[0].activeRole.id, "architect");
    assert.ok(listed.workflows[0].nextQuestions.some((question) => question.id === "plan.dataset.strategy"));
    assert.equal(Object.prototype.hasOwnProperty.call(listed.workflows[0], "plan"), false);
    assert.equal(Object.prototype.hasOwnProperty.call(listed.workflows[0], "answers"), false);

    const withoutQuestions = await call(client, "workflow_list", { includeQuestions: false });
    assert.deepEqual(withoutQuestions.workflows[0].nextQuestions, []);
  });
});

test("workflow profiles expose canonical agent hats without granting permissions", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const tools = (await client.listTools()).tools;
    for (const name of ["workflow_profiles_list", "workflow_profiles_get"]) {
      const tool = tools.find((candidate) => candidate.name === name);
      assert.ok(tool, `${name} should be listed`);
      assert.equal(tool.annotations?.readOnlyHint, true);
      assert.equal(tool.annotations?.destructiveHint, false);
    }

    const profiles = await call(client, "workflow_profiles_list");
    assert.equal(profiles.defaultProfileId, "novice-test-builder");
    assert.deepEqual(
      ["architect", "developer", "tester", "security-reviewer", "performance-testing-specialist", "pockethive-sme"]
        .every((roleId) => profiles.roles.some((role) => role.id === roleId)),
      true,
    );
    assert.ok(profiles.profiles.some((profile) => profile.id === "test-conversion-specialist"));

    const profile = await call(client, "workflow_profiles_get", { profileId: "performance-engineer" });
    assert.equal(profile.id, "performance-engineer");
    assert.ok(profile.roles.some((role) => role.id === "performance-testing-specialist"));
    assert.equal(profile.authority, "guidance-only");

    const missing = await callError(client, "workflow_profiles_get", { profileId: "not-a-profile" });
    assert.match(missing, /WORKFLOW_PROFILE_NOT_FOUND/);

    const badStart = await callError(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a test.",
      profileId: "not-a-profile",
    });
    assert.match(badStart, /WORKFLOW_PROFILE_NOT_FOUND/);

    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a performance test for GET /hello.",
      profileId: "performance-engineer",
    });
    assert.equal(started.profile.id, "performance-engineer");
    assert.equal(started.activeRole.id, "architect");
    assert.ok(started.roleChecklist.some((item) => item.roleId === "architect"));
    assert.equal(started.activeRole.authority, "guidance-only");
    assert.ok(started.allowedActions.includes("workflow_update"));
    assert.equal(started.allowedActions.includes("workflow_generate"), false);
  });
});

test("workflow profile evidence gates and provenance policies are profile-driven", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const start = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a performance test for GET /hello.",
      profileId: "performance-engineer",
    });
    assert.equal(start.profile.id, "performance-engineer");
    assert.ok(start.evidenceRequirements.some((requirement) => requirement.id === "performance.traffic-shape-proof"));

    await call(client, "workflow_update", { workflowId: start.workflowId, plan: completePlan("agent-performance") });
    const noProvenance = await callError(client, "workflow_generate", { workflowId: start.workflowId });
    assert.match(noProvenance, /WORKFLOW_PROVENANCE_INCOMPLETE/);
    assert.match(noProvenance, /plan\.traffic\.shape/);

    const updated = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      provenance: requiredProvenance("agent-inferred"),
    });
    assert.ok(updated.provenanceGaps.some((gap) => gap.field === "plan.target"));
    assert.ok(updated.nextQuestions.some((question) =>
      question.questionKind === "provenance-confirmation"
      && question.field === "plan.target"
      && question.answerOwner === "user-or-source"
      && question.resolution?.tool === "workflow_update"
      && question.resolution?.provenanceField === "plan.target"
    ));

    await call(client, "workflow_update", {
      workflowId: start.workflowId,
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, start.workflowId, ["architect", "performance-testing-specialist", "tester"]);
    const generated = await call(client, "workflow_generate", { workflowId: start.workflowId });
    assert.equal(generated.ok, true);
  }, {
    responses: [
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      {
        ok: false,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      {
        ok: false,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      { ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: "agent-stuck", summary: { errors: 0, warnings: 0 }, findings: [] },
    ],
  });
});

test("workflow answer validation blocks unsafe or vague generation", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const start = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "I want to create a scenario bundle to test Google.",
    });
    const plan = {
      ...completePlan("agent-invalid-answers"),
      target: "external",
      targetBaseUrl: "not-a-url",
      traffic: { ratePerSec: 100, shape: "flat", duration: "30s" },
      mock: { strategy: "real_system" },
      observability: { goal: "metrics" },
      successCriteria: "works",
    };
    const updated = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      plan,
      provenance: requiredProvenance("user"),
    });

    assert.ok(updated.validationIssues.some(issue => issue.field === "plan.targetBaseUrl" && issue.severity === "error"));
    assert.ok(updated.validationIssues.some(issue => issue.code === "PUBLIC_TARGET_TRAFFIC_UNSAFE"));
    assert.ok(updated.validationIssues.some(issue => issue.field === "plan.successCriteria"));
    assert.ok(updated.nextQuestions.some((question) =>
      question.questionKind === "invalid-answer"
      && question.field === "plan.successCriteria"
      && question.resolution?.tool === "workflow_update"
      && question.resolution?.planField === "plan.successCriteria"
    ));
    assert.ok(updated.nextQuestions.some((question) =>
      question.questionKind === "invalid-answer"
      && question.field === "plan.targetBaseUrl"
      && question.answerOwner === "user-or-source"
    ));

    await completeThreeAmigos(client, start.workflowId);
    const blocked = await callError(client, "workflow_generate", { workflowId: start.workflowId });
    assert.match(blocked, /WORKFLOW_ANSWER_VALIDATION_FAILED/);
  });
});

test("workflow source fidelity gates lossy conversions with resolvable questions", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const start = await call(client, "workflow_start", { sourceType: "jmeter", sourcePath });
    const lossyPlan = completePlan("agent-source-fidelity");
    delete lossyPlan.sourceFidelity;

    const missingFidelity = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      plan: lossyPlan,
      provenance: requiredProvenance("source-derived"),
    });
    assert.ok(missingFidelity.missing.includes("plan.sourceFidelity.status"));
    assert.ok(missingFidelity.nextQuestions.some(question =>
      question.field === "plan.sourceFidelity.status"
      && question.resolution?.tool === "workflow_update"
    ));
    assert.deepEqual(missingFidelity.unresolvableBlockers, []);

    const unsupported = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      plan: {
        sourceFidelity: {
          status: "partial-accepted",
          convertedFeatures: ["http-method", "path"],
          unsupportedConstructs: ["JMeter post-processor correlation"],
        },
      },
    });
    assert.ok(unsupported.missing.includes("plan.sourceFidelity.userAcceptedLimitations"));
    assert.ok(unsupported.nextQuestions.some(question =>
      question.field === "plan.sourceFidelity.userAcceptedLimitations"
      && question.answerOwner === "user-or-source"
      && question.canAgentInfer === false
    ));
    assert.deepEqual(unsupported.unresolvableBlockers, []);

    await completeThreeAmigos(client, start.workflowId);
    const blocked = await callError(client, "workflow_generate", { workflowId: start.workflowId });
    assert.match(blocked, /WORKFLOW_PLAN_INCOMPLETE|WORKFLOW_ANSWER_VALIDATION_FAILED/);
  });
});

test("workflow answer validation catches duplicate call ids and invalid endpoint semantics", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const start = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a scenario with two calls.",
    });
    const invalidPlan = {
      ...completePlan("agent-endpoint-invalid"),
      endpoints: [
        { method: "GET", path: "/one", callId: "duplicate" },
        { method: "FETCH", path: "two", callId: "duplicate" },
      ],
    };
    const status = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      plan: invalidPlan,
      provenance: requiredProvenance("user"),
    });
    assert.ok(status.validationIssues.some(issue => issue.code === "ENDPOINT_CALL_ID_DUPLICATE"));
    assert.ok(status.validationIssues.some(issue => issue.code === "ENDPOINT_METHOD_INVALID"));
    assert.ok(status.validationIssues.some(issue => issue.code === "ENDPOINT_PATH_INVALID"));
    assert.ok(status.nextQuestions.some(question =>
      question.questionKind === "invalid-answer"
      && question.field === "plan.endpoints"
      && question.resolution?.tool === "workflow_update"
    ));
  });
});

test("workflow public target safety gate is explicit and resolvable", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const start = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "I want to create a scenario bundle to test Google.",
    });
    const updated = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      plan: {
        ...completePlan("agent-google-live"),
        target: "external",
        targetBaseUrl: "https://www.google.com",
        traffic: { ratePerSec: 1, shape: "smoke", duration: "30s" },
        mock: { strategy: "real_system" },
      },
      provenance: requiredProvenance("user"),
    });

    assert.ok(updated.missing.includes("plan.safety.publicTargetConfirmed"));
    const safetyQuestion = updated.nextQuestions.find(question => question.field === "plan.safety.publicTargetConfirmed");
    assert.equal(safetyQuestion.questionKind, "missing-field");
    assert.equal(safetyQuestion.answerOwner, "user-or-source");
    assert.equal(safetyQuestion.canAgentInfer, false);
    assert.deepEqual(safetyQuestion.dependsOn, ["plan.target", "plan.targetBaseUrl", "plan.mock.strategy"]);
    assert.equal(safetyQuestion.triggeredBy.code, "PUBLIC_TARGET_REAL_SYSTEM");
    assert.equal(safetyQuestion.resolution.tool, "workflow_update");
    assert.deepEqual(updated.unresolvableBlockers, []);
    assert.ok(updated.blockers.some(blocker => blocker.field === "plan.safety.publicTargetConfirmed" && blocker.resolvedBy === "workflow_update"));
    assert.ok(updated.questionGraph.nodes.some(node => node.id === "plan.safety.publicTargetConfirmed"));
    assert.ok(updated.questionGraph.edges.some(edge => edge.from === "plan.target" && edge.to === "plan.safety.publicTargetConfirmed"));

    const confirmedByAgent = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      plan: { safety: { publicTargetConfirmed: true } },
      provenance: { "plan.safety.publicTargetConfirmed": { source: "agent-inferred", note: "The agent guessed this." } },
    });
    assert.equal(confirmedByAgent.missing.includes("plan.safety.publicTargetConfirmed"), false);
    assert.ok(confirmedByAgent.provenanceGaps.some(gap => gap.field === "plan.safety.publicTargetConfirmed"));
    assert.ok(confirmedByAgent.nextQuestions.some(question =>
      question.questionKind === "provenance-confirmation"
      && question.field === "plan.safety.publicTargetConfirmed"
      && question.answerOwner === "user-or-source"
    ));
    assert.deepEqual(confirmedByAgent.unresolvableBlockers, []);

    const confirmedByUser = await call(client, "workflow_update", {
      workflowId: start.workflowId,
      provenance: { "plan.safety.publicTargetConfirmed": { source: "user", note: "User confirmed low-rate public live target." } },
    });
    assert.equal(confirmedByUser.provenanceGaps.some(gap => gap.field === "plan.safety.publicTargetConfirmed"), false);
  });
});

test("workflow gates generation until a normalized plan is complete, then generates a valid bundle", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "postman", sourcePath });
    await call(client, "workflow_update", { workflowId: started.workflowId, plan: { bundleId: "agent-incomplete" } });
    const incompleteResult = await call(client, "workflow_result", { workflowId: started.workflowId });
    assert.equal(incompleteResult.verdict, "needs_input");
    assert.equal(incompleteResult.phase, "intake");
    assert.equal(incompleteResult.nextAction.tool, "workflow_update");
    assert.ok(incompleteResult.nextAction.fields.includes("plan.protocol"));

    const blocked = await callError(client, "workflow_generate", { workflowId: started.workflowId });
    assert.match(blocked, /WORKFLOW_PLAN_INCOMPLETE/);

    const updated = await call(client, "workflow_update", { workflowId: started.workflowId, plan: completePlan("agent-complete") });
    assert.equal(updated.state, "plan_ready");
    assert.equal(updated.profile.id, "novice-test-builder");
    assert.equal(updated.activeRole.id, "pockethive-sme");
    assert.deepEqual(updated.missing, []);
    assert.ok(updated.nextQuestions.some((question) =>
      question.questionKind === "provenance-confirmation"
      && question.field === "plan.auth"
      && question.resolution?.provenanceField === "plan.auth"
    ));

    const blockedByProvenance = await callError(client, "workflow_generate", { workflowId: started.workflowId });
    assert.match(blockedByProvenance, /WORKFLOW_PROVENANCE_INCOMPLETE/);

    const withProvenance = await call(client, "workflow_update", {
      workflowId: started.workflowId,
      provenance: requiredProvenance("source-derived"),
    });
    assert.equal(withProvenance.provenanceGaps.length, 0);

    const blockedByRoleChecks = await callError(client, "workflow_generate", { workflowId: started.workflowId });
    assert.match(blockedByRoleChecks, /WORKFLOW_ROLE_CHECKS_INCOMPLETE/);
    assert.ok(blockedByRoleChecks.includes("three-amigos"));

    await completeThreeAmigos(client, started.workflowId);

    const preview = await call(client, "workflow_preview", { workflowId: started.workflowId });
    assert.equal(preview.bundle.id, "agent-complete");
    assert.equal(existsSync(resolve(root, "agent-complete")), false);

    const generated = await call(client, "workflow_generate", { workflowId: started.workflowId });
    assert.equal(generated.ok, true);
    assert.equal(generated.generationSanity.ok, true);
    assert.equal(existsSync(resolve(root, "agent-complete", "scenario.yaml")), true);

    const generatedStatus = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(generatedStatus.activeRole.id, "tester");
    assert.equal(generatedStatus.agent.phase, "validation");
    assert.equal(generatedStatus.agent.nextAction.tool, "workflow_validate");
    assert.equal(generatedStatus.reviewStages.find((stage) => stage.id === "three-amigos").status, "complete");

    const validated = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(validated.ok, true);
    assert.equal(validated.code, "WORKFLOW_VALIDATED");
    assert.equal(validated.authoritative, true);
    assert.equal(validated.validationLevel, "scenario-manager");
    const validatedResult = await call(client, "workflow_result", { workflowId: started.workflowId });
    assert.equal(validatedResult.verdict, "ready");
    assert.equal(validatedResult.phase, "deployment");
    assert.equal(validatedResult.proof.validation.status, "pass");
    assert.equal(validatedResult.proof.validation.scenarioManager.status, "pass");
    assert.equal(validatedResult.nextAction.tool, "workflow_deploy_start");
  });
});

test("workflow generation preserves rich endpoint semantics in sequence bundles", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a two-step authenticated sequence with mocks.",
    });
    const plan = {
      ...completePlan("agent-rich-sequence"),
      protocol: "SEQUENCE",
      endpoints: [
        {
          method: "POST",
          path: "/session",
          callId: "session",
          headers: { "x-trace-id": "{{ payload.traceId }}" },
          query: { client: "demo" },
          bodyTemplate: "{\"traceId\":\"{{ payload.traceId }}\"}",
          retry: { maxAttempts: 2, initialBackoffMs: 25, backoffMultiplier: 1, maxBackoffMs: 50, on: ["5xx"] },
          continueOnNon2xx: false,
          extracts: [{ fromJsonPointer: "/sessionId", to: "session.id", required: true }],
          mock: {
            requestHeaders: { "X-Trace-Id": { equalTo: "trace-1" } },
            queryParameters: { client: { equalTo: "demo" } },
            bodyPatterns: [{ contains: "traceId" }],
            responseBody: { sessionId: "session-1" },
            status: 201,
          },
        },
        {
          method: "GET",
          path: "/profile",
          callId: "profile",
          headers: { accept: "application/json" },
          query: { sessionId: "{{ payload.sessionId }}" },
          extracts: [{ fromJsonPointer: "/profileId", to: "profile.id", required: true }],
        },
      ],
      mock: {
        strategy: "wiremock",
        endpoints: [
          {
            method: "POST",
            path: "/session",
            callId: "session",
            requestHeaders: { "X-Trace-Id": { equalTo: "trace-1" } },
            queryParameters: { client: { equalTo: "demo" } },
            bodyPatterns: [{ contains: "traceId" }],
            responseBody: { sessionId: "session-1" },
            status: 201,
          },
        ],
      },
    };
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan,
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });

    const { parse } = await import("yaml");
    const scenario = parse(readFileSync(resolve(root, "agent-rich-sequence", "scenario.yaml"), "utf8"));
    assert.ok(scenario.topology?.edges?.length >= 2);
    assert.equal(Object.prototype.hasOwnProperty.call(scenario, "trafficPolicy"), true);
    const sequence = scenario.template.bees.find(bee => bee.role === "http-sequence");
    assert.deepEqual(sequence.config.steps[0].extracts, [{ fromJsonPointer: "/sessionId", to: "session.id", required: true }]);
    assert.deepEqual(sequence.config.steps[0].retry, { maxAttempts: 2, initialBackoffMs: 25, backoffMultiplier: 1, maxBackoffMs: 50, on: ["5xx"] });
    assert.equal(sequence.config.steps[0].continueOnNon2xx, false);

    const template = parse(readFileSync(resolve(root, "agent-rich-sequence", "templates", "http", "sequence", "session.yaml"), "utf8"));
    assert.equal(template.pathTemplate, "/session?client=demo");
    assert.equal(template.headersTemplate["x-trace-id"], "{{ payload.traceId }}");

    const mock = JSON.parse(readFileSync(resolve(root, "agent-rich-sequence", "mock-config", "wiremock", "session.json"), "utf8"));
    assert.deepEqual(mock.request.queryParameters, { client: { equalTo: "demo" } });
    assert.deepEqual(mock.request.headers, { "X-Trace-Id": { equalTo: "trace-1" } });
    assert.deepEqual(mock.request.bodyPatterns, [{ contains: "traceId" }]);
    assert.equal(mock.response.status, 201);
  });
});

test("workflow generation adds conservative WireMock body matchers for mutating JSON calls", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a POST flow with generated mock body proof.",
    });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: {
        ...completePlan("agent-generated-bodypatterns"),
        endpoints: [{ method: "POST", path: "/topup", callId: "topup" }],
        requestBody: "{\"customerId\":\"{{customerId}}\",\"amount\":10,\"currency\":\"GBP\"}",
      },
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });

    const mock = JSON.parse(readFileSync(resolve(root, "agent-generated-bodypatterns", "mock-config", "wiremock", "topup.json"), "utf8"));
    assert.deepEqual(mock.request.bodyPatterns, [
      { matchesJsonPath: "$.customerId" },
      { matchesJsonPath: { expression: "$.amount", equalTo: "10" } },
      { matchesJsonPath: { expression: "$.currency", equalTo: "GBP" } },
    ]);
  });
});

test("wizard generation preserves WireMock response body aliases and result-rule bodies", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const explicit = await call(client, "wizard_start", {
      intent: "Create a payment POST bundle with explicit mock response body.",
      bundleId: "wizard-mock-body-alias",
      protocol: "REST",
      target: "wiremock-local",
      endpoints: [{ method: "POST", path: "/api/payments", callId: "create-payment" }],
      defaultRatePerSec: 2,
      requestBody: JSON.stringify({ customerId: "{{customerId}}", amount: 10 }),
      resultRules: "yes",
      resultCodePattern: "\"status\"\\s*:\\s*\"([A-Z_]+)\"",
      successCodes: ["ACCEPTED"],
      mockEndpoints: [{
        method: "POST",
        path: "/api/payments",
        callId: "create-payment",
        status: 201,
        body: { paymentId: "P-1001", status: "ACCEPTED" },
      }],
    });
    assert.equal(explicit.ready, true);
    await call(client, "wizard_complete", { sessionId: explicit.sessionId });

    const explicitMapping = JSON.parse(readFileSync(resolve(root, "wizard-mock-body-alias", "mock-config", "wiremock", "create-payment.json"), "utf8"));
    assert.equal(explicitMapping.response.status, 201);
    assert.deepEqual(explicitMapping.response.jsonBody, { paymentId: "P-1001", status: "ACCEPTED" });
    assert.ok(explicitMapping.request.bodyPatterns?.length > 0, "mutating WireMock stub should assert request body");

    const generated = await call(client, "wizard_start", {
      intent: "Create a payment POST bundle with generated mock response body.",
      bundleId: "wizard-result-rule-default-body",
      protocol: "REST",
      target: "wiremock-local",
      endpoints: [{ method: "POST", path: "/api/payments", callId: "create-payment" }],
      defaultRatePerSec: 2,
      requestBody: JSON.stringify({ customerId: "{{customerId}}", amount: 10 }),
      resultRules: "yes",
      resultCodePattern: "\"status\"\\s*:\\s*\"([A-Z_]+)\"",
      successCodes: ["ACCEPTED"],
    });
    assert.equal(generated.ready, true);
    await call(client, "wizard_complete", { sessionId: generated.sessionId });

    const generatedMapping = JSON.parse(readFileSync(resolve(root, "wizard-result-rule-default-body", "mock-config", "wiremock", "create-payment.json"), "utf8"));
    assert.equal(generatedMapping.response.jsonBody.status, "ACCEPTED");
  });
});

test("wizard rejects explicit WireMock response bodies that contradict result rules", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const plan = await call(client, "wizard_start", {
      intent: "Create a payment POST bundle with a conflicting mock response body.",
      bundleId: "wizard-mock-body-conflict",
      protocol: "REST",
      target: "wiremock-local",
      endpoints: [{ method: "POST", path: "/api/payments", callId: "create-payment" }],
      defaultRatePerSec: 2,
      requestBody: JSON.stringify({ customerId: "{{customerId}}", amount: 10 }),
      resultRules: "yes",
      resultCodePattern: "\"status\"\\s*:\\s*\"([A-Z_]+)\"",
      successCodes: ["ACCEPTED"],
      mockEndpoints: [{
        method: "POST",
        path: "/api/payments",
        callId: "create-payment",
        status: 409,
        body: { paymentId: "P-1001", status: "CONFLICTED" },
      }],
    });

    assert.equal(plan.ready, false);
    assert.ok(plan.errors.some((message) => message.includes("responseBody.status")));
    const completeError = await callError(client, "wizard_complete", { sessionId: plan.sessionId });
    assert.match(completeError, /responseBody\.status/);
  });
});

test("Scenario Manager auth refreshes username-derived bearer token once after 401", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  let loginCalls = 0;
  let validateCalls = 0;
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/auth-service/api/auth/dev/login") {
      loginCalls += 1;
      for await (const _ of req) { /* drain */ }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ accessToken: loginCalls === 1 ? "expired-token" : "fresh-token" }));
      return;
    }
    if (req.method === "POST" && url.pathname === "/scenario-manager/validation/scenario-bundles") {
      validateCalls += 1;
      for await (const _ of req) { /* drain zip upload */ }
      const auth = req.headers.authorization || "";
      if (auth !== "Bearer fresh-token") {
        res.writeHead(401, { "content-type": "application/json" });
        res.end(JSON.stringify({ message: "Invalid or expired bearer token" }));
        return;
      }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({
        ok: true,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "auth-refresh-validation",
        summary: { errors: 0, warnings: 0 },
        findings: [],
      }));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `unhandled ${req.method} ${url.pathname}` }));
  });
  await new Promise(resolveListen => server.listen(0, "127.0.0.1", resolveListen));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await withClient(root, async (client) => {
      const started = await call(client, "workflow_start", {
        sourceType: "plain-instructions",
        instructions: "Create a bundle and validate it through Scenario Manager.",
      });
      await call(client, "workflow_update", {
        workflowId: started.workflowId,
        plan: completePlan("auth-refresh-validation"),
        provenance: requiredProvenance("user"),
      });
      await completeThreeAmigos(client, started.workflowId);
      await call(client, "workflow_generate", { workflowId: started.workflowId });
      const validated = await call(client, "workflow_validate", { workflowId: started.workflowId, validator: "scenario-manager-dry-run" });
      assert.equal(validated.ok, true);
      assert.equal(validated.authoritative, true);
    }, {
      POCKETHIVE_BASE_URL: baseUrl,
      ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
      AUTH_SERVICE_BASE_URL: `${baseUrl}/auth-service`,
      POCKETHIVE_AUTH_USERNAME: "local-admin",
    });
    assert.equal(loginCalls, 2);
    assert.equal(validateCalls, 2);
  } finally {
    await new Promise(resolveClose => server.close(resolveClose));
  }
});

test("Scenario Manager auth failure is classified as environment auth, not bundle validation", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  let loginCalls = 0;
  let validateCalls = 0;
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/auth-service/api/auth/dev/login") {
      loginCalls += 1;
      for await (const _ of req) { /* drain */ }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ accessToken: `still-expired-${loginCalls}` }));
      return;
    }
    if (req.method === "POST" && url.pathname === "/scenario-manager/validation/scenario-bundles") {
      validateCalls += 1;
      for await (const _ of req) { /* drain zip upload */ }
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify({ message: "Invalid or expired bearer token" }));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `unhandled ${req.method} ${url.pathname}` }));
  });
  await new Promise(resolveListen => server.listen(0, "127.0.0.1", resolveListen));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await withClient(root, async (client) => {
      const started = await call(client, "workflow_start", {
        sourceType: "plain-instructions",
        instructions: "Create a bundle and show auth failure classification.",
      });
      await call(client, "workflow_update", {
        workflowId: started.workflowId,
        plan: completePlan("auth-failure-classification"),
        provenance: requiredProvenance("user"),
      });
      await completeThreeAmigos(client, started.workflowId);
      await call(client, "workflow_generate", { workflowId: started.workflowId });

      const failed = await call(client, "workflow_validate", { workflowId: started.workflowId, validator: "scenario-manager-dry-run" });
      assert.equal(failed.ok, false);
      assert.equal(failed.code, "WORKFLOW_ENV_AUTH_FAILED");
      assert.equal(failed.failureCode, "WORKFLOW_ENV_AUTH_FAILED");
      assert.equal(failed.validationLevel, "scenario-manager");
      assert.equal(failed.patchScope.length, 0);
      assert.ok(failed.suggestedNextActions.includes("env_status"));

      const result = await call(client, "workflow_result", { workflowId: started.workflowId });
      assert.equal(result.phase, "validation");
      assert.equal(result.diagnosis.code, "WORKFLOW_ENV_AUTH_FAILED");
      assert.equal(result.nextAction.tool, "env_status");
      assert.equal(result.nextAction.followUpTool, "workflow_validate");
      assert.equal(result.proof.validation.scenarioManager.status, "fail");

      const status = await call(client, "workflow_status", { workflowId: started.workflowId });
      assert.equal(status.activeRole.id, "security-reviewer");
      assert.equal(status.remediation.patchScope.length, 0);
      assert.equal(status.claimMatrix.find(claim => claim.id === "validation.scenario-manager").status, "failed");
    }, {
      POCKETHIVE_BASE_URL: baseUrl,
      ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
      AUTH_SERVICE_BASE_URL: `${baseUrl}/auth-service`,
      POCKETHIVE_AUTH_USERNAME: "local-admin",
    });
    assert.equal(loginCalls, 2);
    assert.equal(validateCalls, 2);
  } finally {
    await new Promise(resolveClose => server.close(resolveClose));
  }
});

test("Scenario Manager validation unavailable is classified without patch scope", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a bundle and show Scenario Manager unavailable classification.",
    });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: completePlan("scenario-manager-unavailable"),
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });

    const failed = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(failed.ok, false);
    assert.equal(failed.failureCode, "WORKFLOW_EXTERNAL_VALIDATION_FAILED");
    assert.equal(failed.patchScope.length, 0);

    const result = await call(client, "workflow_result", { workflowId: started.workflowId });
    assert.equal(result.proof.validation.status, "fail");
    assert.equal(result.proof.validation.scenarioManager.status, "fail");
    assert.equal(result.nextAction.validator, "scenario-manager-dry-run");
    assert.equal(result.nextAction.tool, "workflow_validate");

    const status = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(status.claimMatrix.find(claim => claim.id === "validation.scenario-manager").status, "failed");
    assert.equal(status.evidenceContract.find(claim => claim.id === "validation.scenario-manager").status, "failed");
  });
});

test("workflow_validate classifies local bundle packaging defects as patchable validation failures", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  let failed;
  let status;
  let result;

  const validationCalls = await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a bundle and show local packaging failure classification.",
    });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: completePlan("bundle-packaging-defect"),
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });
    unlinkSync(resolve(root, "bundle-packaging-defect", "scenario.yaml"));

    failed = await call(client, "workflow_validate", { workflowId: started.workflowId });
    status = await call(client, "workflow_status", { workflowId: started.workflowId });
    result = await call(client, "workflow_result", { workflowId: started.workflowId });
  });

  assert.equal(validationCalls, 0);
  assert.equal(failed.ok, false);
  assert.equal(failed.code, "WORKFLOW_VALIDATION_FAILED");
  assert.equal(failed.failureCode, "WORKFLOW_VALIDATION_FAILED");
  assert.equal(failed.authoritative, false);
  assert.ok(failed.patchScope.some((scope) => scope.endsWith("/bundle-packaging-defect/**")));
  assert.ok(failed.suggestedNextActions.includes("workflow_patch"));
  assert.equal(status.agent.nextAction.tool, "workflow_patch");
  assert.equal(status.agent.diagnosis.causes[0].code, "BUNDLE_PACKAGING_FAILED");
  assert.equal(status.agent.diagnosis.causes[0].path, "scenario.yaml");
  assert.equal(result.proof.validation.status, "fail");
  assert.equal(result.proof.validation.scenarioManager.status, "not-run");
});

test("workflow CSV datasets generate runtime CSV input config and sample artifact", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a CSV-backed smoke scenario.",
    });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: {
        ...completePlan("agent-csv-evidence"),
        dataset: { strategy: "CSV_DATASET", csvColumns: ["customerId", "accountId"] },
      },
      provenance: requiredProvenance("user"),
    });
    const status = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.ok(status.evidenceContract.some(claim => claim.id === "dataset.sample-artifact"));
    assert.equal(status.evidenceContract.some(claim => claim.id === "dataset.rotated"), false);

    await completeThreeAmigos(client, started.workflowId);
    const generated = await call(client, "workflow_generate", { workflowId: started.workflowId });
    assert.equal(generated.ok, true);
    assert.ok(generated.generated.filesCreated.includes("datasets/sample.csv"));
    const scenario = readFileSync(join(root, "agent-csv-evidence", "scenario.yaml"), "utf8");
    assert.match(scenario, /inputs:\n\s+type: CSV_DATASET\n\s+csv:/);
    assert.match(scenario, /filePath: \/app\/scenario\/datasets\/sample\.csv/);
    assert.doesNotMatch(scenario, /outputs:\n\s+type: CSV_DATASET/);
  });
});

test("workflow evidence contract is available before build and trace links intent to evidence", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Create a mock-backed Google smoke scenario with stakeholder evidence.",
    });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: completePlan("agent-trace"),
      provenance: requiredProvenance("user"),
    });

    const ready = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.ok(ready.evidenceContract.some(claim => claim.id === "bundle.generated" && claim.required === true));
    assert.ok(ready.evidenceContract.some(claim => claim.id === "validation.scenario-manager" && claim.required === true));
    assert.ok(ready.evidenceContract.some(claim => claim.id === "mock.matched" && claim.required === false));
    assert.ok(ready.evidenceContract.some(claim => claim.id === "traffic.shape" && claim.required === false));
    assert.deepEqual(ready.unresolvableBlockers, []);

    await completeThreeAmigos(client, started.workflowId);
    const generated = await call(client, "workflow_generate", { workflowId: started.workflowId });
    assert.ok(generated.generated.filesCreated.includes("WORKFLOW_TRACE.json"));
    await call(client, "workflow_validate", { workflowId: started.workflowId });
    await call(client, "workflow_report", { workflowId: started.workflowId });

    const trace = JSON.parse(readFileSync(resolve(root, "agent-trace", "WORKFLOW_TRACE.json"), "utf8"));
    assert.equal(trace.workflowId, started.workflowId);
    assert.equal(trace.agent.refs.bundleId, "agent-trace");
    assert.equal(trace.agent.nextAction.kind, "deploy");
    assert.equal(trace.intent, "Create a mock-backed Google smoke scenario with stakeholder evidence.");
    assert.equal(trace.plan.bundleId, "agent-trace");
    assert.ok(trace.answeredFields.includes("plan.traffic.ratePerSec"));
    assert.ok(trace.provenanceFields.includes("plan.successCriteria"));
    assert.ok(trace.generatedFiles.includes("scenario.yaml"));
    assert.ok(trace.evidenceContract.some(claim => claim.id === "validation.scenario-manager"));
    assert.ok(trace.claimMatrix.some(claim => claim.id === "stakeholder.report"));
    const report = readFileSync(resolve(root, "agent-trace", "WORKFLOW_EVIDENCE.md"), "utf8");
    assert.match(report, /## Agent Handoff/);
    assert.match(report, /- Verdict:/);
  });
});

test("workflow_patch is constrained and validation history preserves failed and fixed attempts", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "k6", sourcePath });
    await call(client, "workflow_update", { workflowId: started.workflowId, plan: completePlan("agent-patch") });
    await call(client, "workflow_update", { workflowId: started.workflowId, provenance: requiredProvenance("user") });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });

    const escape = await callError(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "../escape.txt", content: "nope" }],
    });
    assert.match(escape, /WORKFLOW_PATCH_OUTSIDE_BUNDLE/);

    const scenarioPath = resolve(root, "agent-patch", "scenario.yaml");
    const originalScenario = readFileSync(scenarioPath, "utf8");
    await call(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "scenario.yaml", content: "not: [valid" }],
    });
    const failed = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(failed.ok, false);
    assert.equal(failed.code, "WORKFLOW_VALIDATION_FAILED");
    assert.equal(failed.failureCode, "WORKFLOW_VALIDATION_FAILED");
    assert.equal(failed.activeRole.id, "developer");
    assert.ok(failed.suggestedNextActions.includes("workflow_patch"));
    assert.ok(failed.patchScope.some((scope) => scope.endsWith("/agent-patch/**")));
    const failedStatus = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(failedStatus.activeRole.id, "developer");
    assert.equal(failedStatus.remediation.failureCode, "WORKFLOW_VALIDATION_FAILED");
    assert.equal(failedStatus.agent.verdict, "failed");
    assert.equal(failedStatus.agent.phase, "validation");
    assert.equal(failedStatus.agent.diagnosis.code, "WORKFLOW_VALIDATION_FAILED");
    assert.deepEqual(failedStatus.agent.diagnosis.causes[0], {
      code: "SCENARIO_DESCRIPTOR_INVALID",
      path: "scenario.yaml",
      message: "Invalid scenario descriptor.",
      fix: "Repair scenario.yaml.",
      category: "scenario",
      severity: "error",
    });
    assert.equal(failedStatus.agent.nextAction.tool, "workflow_patch");
    assert.equal(failedStatus.agent.nextAction.followUpTool, "workflow_validate");
    const failedResult = await call(client, "workflow_result", { workflowId: started.workflowId });
    assert.deepEqual(failedResult, failedStatus.agent);

    await call(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "scenario.yaml", content: originalScenario }],
    });
    const fixed = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(fixed.ok, true);

    const status = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(status.activeRole.id, "pockethive-sme");
    assert.equal(status.agent.verdict, "ready");
    assert.equal(status.agent.nextAction.tool, "workflow_deploy_start");
    const validationAttempts = status.history.filter((entry) => entry.action === "validate");
    assert.equal(validationAttempts.length, 2);
    assert.deepEqual(validationAttempts.map((entry) => entry.ok), [false, true]);
  }, {
    responses: [
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-patch",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      { ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: "agent-patch", summary: { errors: 0, warnings: 0 }, findings: [] },
    ],
  });
});

test("workflow detects repeated unchanged failures without creating an unresolvable loop", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "k6", sourcePath });
    await call(client, "workflow_update", { workflowId: started.workflowId, plan: completePlan("agent-stuck") });
    await call(client, "workflow_update", { workflowId: started.workflowId, provenance: requiredProvenance("user") });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });

    const scenarioPath = resolve(root, "agent-stuck", "scenario.yaml");
    const originalScenario = readFileSync(scenarioPath, "utf8");
    await call(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "scenario.yaml", content: "not: [valid" }],
    });
    for (let i = 0; i < 3; i += 1) {
      const failed = await call(client, "workflow_validate", { workflowId: started.workflowId });
      assert.equal(failed.ok, false);
      assert.equal(failed.failureCode, "WORKFLOW_VALIDATION_FAILED");
    }

    const stuck = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(stuck.stuckState.stuck, true);
    assert.equal(stuck.stuckState.failureCode, "WORKFLOW_VALIDATION_FAILED");
    assert.equal(stuck.stuckState.action, "validate");
    assert.ok(stuck.stuckState.suggestedNextActions.includes("workflow_patch"));
    assert.deepEqual(stuck.unresolvableBlockers, []);
    assert.equal(stuck.remediation.stuckState.stuck, true);

    await call(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "scenario.yaml", content: originalScenario }],
    });
    const fixed = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(fixed.ok, true);
    const resolved = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(resolved.stuckState.stuck, false);
  }, {
    responses: [
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      {
        ok: false,
        validation: validationEvidence,
        source: "uploaded-zip",
        scenarioId: "agent-stuck",
        summary: { errors: 1, warnings: 0 },
        findings: [{ category: "scenario", code: "SCENARIO_DESCRIPTOR_INVALID", severity: "error", path: "scenario.yaml", message: "Invalid scenario descriptor.", fix: "Repair scenario.yaml." }],
      },
      { ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: "agent-stuck", summary: { errors: 0, warnings: 0 }, findings: [] },
    ],
  });
});

test("workflow modify mode patches and validates an existing active-root bundle", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withScenarioManagerValidationClient(root, async (client) => {
    await call(client, "bundle_scaffold", { bundleId: "agent-modify", pattern: "rest-simple", sutType: "none" });

    const missingBundle = await callError(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Modify the existing bundle.",
      mode: "modify",
    });
    assert.match(missingBundle, /WORKFLOW_MODIFY_BUNDLE_REQUIRED/);

    const started = await call(client, "workflow_start", {
      sourceType: "plain-instructions",
      instructions: "Change the existing bundle and keep it valid.",
      mode: "modify",
      existingBundleId: "agent-modify",
    });
    assert.equal(started.mode, "modify");
    assert.equal(started.bundle.id, "agent-modify");
    assert.ok(started.missing.includes("plan.changeSummary"));
    assert.equal(started.allowedActions.includes("workflow_patch"), false);

    const blockedPatch = await callError(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "README.md", content: "# changed\n" }],
    });
    assert.match(blockedPatch, /WORKFLOW_ANSWER_VALIDATION_FAILED|WORKFLOW_PLAN_INCOMPLETE/);

    const updated = await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: {
        changeSummary: "Add a clearer README while preserving the existing scenario contract.",
        observability: { goal: "Show that the modified bundle passes Scenario Manager validation for reviewers." },
        successCriteria: { summary: "Scenario Manager validation passes after the change." },
      },
    });
    assert.deepEqual(updated.validationIssues.filter(issue => issue.severity === "error"), []);
    assert.ok(updated.allowedActions.includes("workflow_patch"));

    await call(client, "workflow_patch", {
      workflowId: started.workflowId,
      changes: [{ file: "README.md", content: "# agent-modify\n\nModified by workflow test.\n" }],
    });
    const validated = await call(client, "workflow_validate", { workflowId: started.workflowId });
    assert.equal(validated.ok, true);
    const status = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(status.claimMatrix.find(claim => claim.id === "bundle.exists").status, "satisfied");
    assert.equal(status.claimMatrix.find(claim => claim.id === "validation.scenario-manager").status, "satisfied");
  });
});

test("workflow deploy loads mock config and verify settles live evidence", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakeRedis(async (redis) => {
    await withFakePocketHiveStack("agent-live-runtime", async ({ baseUrl, state }) => {
      await withClient(root, async (client) => {
        const started = await call(client, "workflow_start", {
          sourceType: "plain-instructions",
          instructions: "Create a live mock-backed HTTP proof.",
        });
        await call(client, "workflow_update", {
          workflowId: started.workflowId,
          plan: completePlan("agent-live-runtime"),
          provenance: requiredProvenance("user"),
        });
        await completeThreeAmigos(client, started.workflowId);
        await call(client, "workflow_generate", { workflowId: started.workflowId });
        await call(client, "workflow_validate", { workflowId: started.workflowId });

        const deploy = await call(client, "workflow_deploy", {
          workflowId: started.workflowId,
          swarmId: "agent-live-stack",
          readyTimeoutSec: 2,
        });
        assert.equal(deploy.ok, true);
        assert.equal(deploy.evidence.mockConfig.wiremock.loaded, 1);
        assert.equal(state.mappings.length, 1);
        assert.equal(state.requestJournalReset, true);

        const verified = await call(client, "workflow_verify", {
          workflowId: started.workflowId,
          includeTapSample: true,
          observationTimeoutSec: 2,
          settleTimeoutSec: 2,
        });
        assert.equal(verified.ok, true);
        assert.equal(verified.evidence.flow.observed[0], "GET /hello");
        assert.equal(verified.evidence.report.checklist.find(claim => claim.id === "requests.handled").status, "pass");
        assert.equal(verified.evidence.report.checklist.find(claim => claim.id === "queues.drained").status, "pass");
        assert.equal(verified.evidence.report.checklist.find(claim => claim.id === "payload.trace").status, "pass");
        assert.equal(verified.evidence.report.checklist.find(claim => claim.id === "tap.flow").status, "pass");
        assert.deepEqual(verified.evidence.tapFlow.observed, ["GET /hello"]);
        assert.equal(verified.evidence.tapFlow.matchedExpected, true);
        assert.equal(verified.evidence.tapFlow.agreesWithWireMock, true);
        assert.equal(state.tapCreates, 1);
        assert.equal(state.tapReads, 1);
        assert.equal(verified.evidence.report.checklist.find(claim => claim.id === "auth.flow").status, "not-applicable");
        assert.equal(state.stopCalls, 1);
        const result = await call(client, "workflow_result", { workflowId: started.workflowId });
        assert.ok(["passed", "partial"].includes(result.verdict));
        assert.equal(result.phase, "report");
        assert.equal(result.nextAction.tool, "workflow_report");
        assert.equal(result.proof.runtime.status, "pass");
        assert.equal(result.proof.traffic.flow.observed[0], "GET /hello");
        assert.equal(result.proof.traffic.flow.matched, true);
        assert.equal(result.proof.traffic.tapFlow.observed[0], "GET /hello");
        assert.equal(result.proof.mocks.wiremockRequests, 1);

        const strict = await call(client, "workflow_verify", {
          workflowId: started.workflowId,
          proofMode: "strict",
          observationTimeoutSec: 1,
          settleTimeoutSec: 1,
        });
        assert.equal(strict.ok, false);
        assert.equal(strict.code, "WORKFLOW_RUNTIME_PARTIAL_PROOF");
        assert.equal(strict.evidence.proofMode, "strict");
      }, {
        POCKETHIVE_BASE_URL: baseUrl,
        ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
        SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
        RABBITMQ_MANAGEMENT_BASE_URL: `${baseUrl}/rabbitmq/api`,
        POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
        POCKETHIVE_GRAFANA_USERNAME: "pockethive",
        POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
        POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
        WIREMOCK_BASE_URL: `${baseUrl}/wiremock`,
        TCP_MOCK_BASE_URL: `${baseUrl}/tcp-mock`,
        REDIS_HOST: redis.host,
        REDIS_PORT: String(redis.port),
      });
    });
  });
});

test("workflow strict proof fails when tap flow disagrees with WireMock flow", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakeRedis(async (redis) => {
    await withFakePocketHiveStack("agent-tap-mismatch", async ({ baseUrl }) => {
      await withClient(root, async (client) => {
        const started = await call(client, "workflow_start", {
          sourceType: "plain-instructions",
          instructions: "Create a tap mismatch proof.",
        });
        await call(client, "workflow_update", {
          workflowId: started.workflowId,
          plan: completePlan("agent-tap-mismatch"),
          provenance: requiredProvenance("user"),
        });
        await completeThreeAmigos(client, started.workflowId);
        await call(client, "workflow_generate", { workflowId: started.workflowId });
        await call(client, "workflow_validate", { workflowId: started.workflowId });
        await call(client, "workflow_deploy", {
          workflowId: started.workflowId,
          swarmId: "agent-live-stack",
          readyTimeoutSec: 2,
        });

        const verified = await call(client, "workflow_verify", {
          workflowId: started.workflowId,
          includeTapSample: true,
          proofMode: "strict",
          observationTimeoutSec: 2,
          settleTimeoutSec: 2,
        });
        const tapClaim = verified.evidence.report.checklist.find(claim => claim.id === "tap.flow");
        assert.equal(verified.ok, false);
        assert.equal(verified.code, "WORKFLOW_RUNTIME_PARTIAL_PROOF");
        assert.equal(tapClaim.status, "fail");
        assert.deepEqual(verified.evidence.tapFlow.observed, ["POST /wrong"]);
        assert.equal(verified.evidence.tapFlow.matchedExpected, false);
        assert.equal(verified.evidence.tapFlow.agreesWithWireMock, false);
      }, {
        POCKETHIVE_BASE_URL: baseUrl,
        ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
        SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
        RABBITMQ_MANAGEMENT_BASE_URL: `${baseUrl}/rabbitmq/api`,
        POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
        POCKETHIVE_GRAFANA_USERNAME: "pockethive",
        POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
        POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
        WIREMOCK_BASE_URL: `${baseUrl}/wiremock`,
        TCP_MOCK_BASE_URL: `${baseUrl}/tcp-mock`,
        REDIS_HOST: redis.host,
        REDIS_PORT: String(redis.port),
      });
    }, {
      tapSamples: [{ body: JSON.stringify({ steps: [{ callId: "wrong", method: "POST", path: "/wrong", status: 200 }] }) }],
    });
  });
});

test("workflow async deploy job survives slow readiness without blocking one tool call", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakeRedis(async (redis) => {
    await withFakePocketHiveStack("agent-async-runtime", async ({ baseUrl, state }) => {
      await withClient(root, async (client) => {
        const started = await call(client, "workflow_start", {
          sourceType: "plain-instructions",
          instructions: "Create a slow-starting runtime proof.",
        });
        await call(client, "workflow_update", {
          workflowId: started.workflowId,
          plan: completePlan("agent-async-runtime"),
          provenance: requiredProvenance("user"),
        });
        await completeThreeAmigos(client, started.workflowId);
        await call(client, "workflow_generate", { workflowId: started.workflowId });
        await call(client, "workflow_validate", { workflowId: started.workflowId });

        const created = await call(client, "workflow_deploy_start", {
          workflowId: started.workflowId,
          swarmId: "agent-live-stack",
        });
        assert.equal(created.status, "running");
        assert.equal(created.phase, "upload");
        assert.equal(state.createCalls, 0);
        assert.ok(created.nextActions.includes("workflow_deploy_resume"));

        let step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.phase, "mock-config");
        assert.equal(state.uploaded, true);

        step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.phase, "create");
        assert.equal(state.mappings.length, 1);

        step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.phase, "wait-ready");
        assert.equal(state.createCalls, 1);

        step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "wait-ready");
        assert.equal(step.ready.ready, false);
        assert.equal(state.startCalls, 0);
        assert.equal(step.agent.nextAction.tool, "workflow_deploy_resume");
        assert.equal(step.agent.nextAction.nextPollAfterMs, 4000);
        assert.equal(step.agent.nextAction.statusTool, "workflow_deploy_status");

        step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "start");
        assert.equal(step.ready.ready, true);
        assert.equal(state.startCalls, 0);

        step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "wait-start");
        assert.equal(state.startCalls, 1);

        step = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "succeeded", JSON.stringify(step));
        assert.equal(step.phase, "complete");

        const status = await call(client, "workflow_deploy_status", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(status.status, "succeeded");
        assert.equal(status.evidence.deployment.ok, true);
        assert.equal(status.evidence.deployment.code, "WORKFLOW_DEPLOYED");
        assert.equal(status.lastStep.code, "WORKFLOW_DEPLOY_STARTED");
        assert.ok(status.apiActions.some(action => action.action === "scenario-manager.upload-bundle"));
        assert.ok(status.apiActions.some(action => action.action === "orchestrator.swarm-start"));
        assert.ok(status.phaseTimeline.some(phase => phase.phase === "wait-ready" && phase.attempts >= 2));
        assert.ok(status.phaseTimeline.some(phase => phase.phase === "start" && phase.status === "succeeded"));

        await call(client, "workflow_report", { workflowId: started.workflowId });
        const report = readFileSync(resolve(root, "agent-async-runtime", "WORKFLOW_EVIDENCE.md"), "utf8");
        assert.match(report, /### op-deploy-/);
        assert.match(report, /Phase Timeline/);
        assert.match(report, /API Actions/);
        assert.match(report, /orchestrator.swarm-start/);
      }, {
        POCKETHIVE_BASE_URL: baseUrl,
        ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
        SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
        RABBITMQ_MANAGEMENT_BASE_URL: `${baseUrl}/rabbitmq/api`,
        POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
        POCKETHIVE_GRAFANA_USERNAME: "pockethive",
        POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
        POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
        WIREMOCK_BASE_URL: `${baseUrl}/wiremock`,
        TCP_MOCK_BASE_URL: `${baseUrl}/tcp-mock`,
        REDIS_HOST: redis.host,
        REDIS_PORT: String(redis.port),
      });
    }, { readyAfterPolls: 2 });
  });
});

test("workflow deploy auth failure is classified as environment auth", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  let loginCalls = 0;
  let uploadCalls = 0;
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    if (req.method === "POST" && url.pathname === "/auth-service/api/auth/dev/login") {
      loginCalls += 1;
      for await (const _ of req) { /* drain */ }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ accessToken: `deploy-expired-${loginCalls}` }));
      return;
    }
    if (req.method === "GET" && url.pathname === "/scenario-manager/scenarios/deploy-auth-failure") {
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "missing" }));
      return;
    }
    if (req.method === "POST" && url.pathname === "/scenario-manager/validation/scenario-bundles") {
      for await (const _ of req) { /* drain zip upload */ }
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, validation: validationEvidence, source: "uploaded-zip", scenarioId: "deploy-auth-failure", summary: { errors: 0, warnings: 0 }, findings: [] }));
      return;
    }
    if (req.method === "POST" && url.pathname === "/scenario-manager/scenarios/bundles") {
      uploadCalls += 1;
      for await (const _ of req) { /* drain zip upload */ }
      res.writeHead(401, { "content-type": "application/json" });
      res.end(JSON.stringify({ message: "Invalid or expired bearer token" }));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `unhandled ${req.method} ${url.pathname}` }));
  });
  await new Promise(resolveListen => server.listen(0, "127.0.0.1", resolveListen));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    await withClient(root, async (client) => {
      const started = await call(client, "workflow_start", {
        sourceType: "plain-instructions",
        instructions: "Create a bundle and fail deployment auth.",
      });
      await call(client, "workflow_update", {
        workflowId: started.workflowId,
        plan: completePlan("deploy-auth-failure"),
        provenance: requiredProvenance("user"),
      });
      await completeThreeAmigos(client, started.workflowId);
      await call(client, "workflow_generate", { workflowId: started.workflowId });
      await call(client, "workflow_validate", { workflowId: started.workflowId });

      const operation = await call(client, "workflow_deploy_start", { workflowId: started.workflowId, swarmId: "deploy-auth-swarm" });
      const failed = await call(client, "workflow_deploy_resume", { workflowId: started.workflowId, operationId: operation.operationId });
      assert.equal(failed.status, "failed");
      assert.equal(failed.evidence.deployment.code, "WORKFLOW_ENV_AUTH_FAILED");
      assert.equal(failed.agent.diagnosis.code, "WORKFLOW_ENV_AUTH_FAILED");
      assert.equal(failed.agent.nextAction.tool, "env_status");
      assert.equal(failed.agent.nextAction.followUpTool, "workflow_deploy_start");

      const status = await call(client, "workflow_status", { workflowId: started.workflowId });
      assert.equal(status.remediation.failureCode, "WORKFLOW_ENV_AUTH_FAILED");
      assert.equal(status.remediation.patchScope.length, 0);
    }, {
      POCKETHIVE_BASE_URL: baseUrl,
      ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
      SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
      AUTH_SERVICE_BASE_URL: `${baseUrl}/auth-service`,
      POCKETHIVE_AUTH_USERNAME: "local-admin",
    });
    assert.equal(loginCalls, 2);
    assert.equal(uploadCalls, 2);
  } finally {
    await new Promise(resolveClose => server.close(resolveClose));
  }
});

test("workflow async verify job observes, stops, and settles in resumable steps", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));

  await withFakeRedis(async (redis) => {
    await withFakePocketHiveStack("agent-async-verify", async ({ baseUrl, state }) => {
      await withClient(root, async (client) => {
        const started = await call(client, "workflow_start", {
          sourceType: "plain-instructions",
          instructions: "Create a delayed-observation runtime proof.",
        });
        await call(client, "workflow_update", {
          workflowId: started.workflowId,
          plan: completePlan("agent-async-verify"),
          provenance: requiredProvenance("user"),
        });
        await completeThreeAmigos(client, started.workflowId);
        await call(client, "workflow_generate", { workflowId: started.workflowId });
        await call(client, "workflow_validate", { workflowId: started.workflowId });
        const deploy = await call(client, "workflow_deploy", {
          workflowId: started.workflowId,
          swarmId: "agent-live-stack",
          readyTimeoutSec: 2,
        });
        assert.equal(deploy.ok, true);

        const created = await call(client, "workflow_verify_start", { workflowId: started.workflowId });
        assert.equal(created.status, "running");
        assert.equal(created.phase, "observe");

        let step = await call(client, "workflow_verify_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "observe");
        assert.equal(state.stopCalls, 0);

        step = await call(client, "workflow_verify_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "stop");

        step = await call(client, "workflow_verify_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "wait-stop");
        assert.equal(state.stopCalls, 1);

        step = await call(client, "workflow_verify_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "running");
        assert.equal(step.phase, "settle");

        step = await call(client, "workflow_verify_resume", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(step.status, "succeeded");
        assert.equal(step.phase, "complete");
        assert.equal(step.evidence.runtime.ok, true);
        assert.equal(step.evidence.runtime.code, "WORKFLOW_RUNTIME_VERIFIED");

        const status = await call(client, "workflow_verify_status", { workflowId: started.workflowId, operationId: created.operationId });
        assert.equal(status.status, "succeeded");
        assert.equal(status.evidence.runtime.evidence.flow.observed[0], "GET /hello");
        assert.equal(status.lastStep.code, "WORKFLOW_VERIFY_SETTLED");
        assert.ok(status.apiActions.some(action => action.action === "evidence.summary"));
        assert.ok(status.apiActions.some(action => action.action === "orchestrator.swarm-stop"));
        assert.ok(status.phaseTimeline.some(phase => phase.phase === "observe" && phase.attempts >= 2));
        assert.ok(status.phaseTimeline.some(phase => phase.phase === "settle" && phase.status === "succeeded"));
      }, {
        POCKETHIVE_BASE_URL: baseUrl,
        ORCHESTRATOR_BASE_URL: `${baseUrl}/orchestrator`,
        SCENARIO_MANAGER_BASE_URL: `${baseUrl}/scenario-manager`,
        RABBITMQ_MANAGEMENT_BASE_URL: `${baseUrl}/rabbitmq/api`,
        POCKETHIVE_GRAFANA_BASE_URL: `${baseUrl}/grafana`,
        POCKETHIVE_GRAFANA_USERNAME: "pockethive",
        POCKETHIVE_GRAFANA_PASSWORD: "pockethive",
        POCKETHIVE_GRAFANA_CLICKHOUSE_DATASOURCE_UID: "clickhouse",
        WIREMOCK_BASE_URL: `${baseUrl}/wiremock`,
        TCP_MOCK_BASE_URL: `${baseUrl}/tcp-mock`,
        REDIS_HOST: redis.host,
        REDIS_PORT: String(redis.port),
      });
    }, { requestOnStart: false, requestAfterJournalReads: 2 });
  });
});

test("workflow sessions persist across MCP restarts with local JSON persistence", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const storePath = resolve(root, "workflow-store.json");
  const sourcePath = writeSource(root);
  let workflowId = "";
  let operationId = "";

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "openapi", sourcePath });
    workflowId = started.workflowId;
    await call(client, "workflow_update", {
      workflowId,
      plan: { bundleId: "persisted-workflow" },
      provenance: { "plan.bundleId": { source: "user", note: "Named by test." } },
    });
  }, {
    env: {
      PH_WORKFLOW_PERSISTENCE: "local",
      PH_WORKFLOW_STORE_PATH: storePath,
    },
  });

  await withScenarioManagerValidationClient(root, async (client) => {
    await call(client, "workflow_update", {
      workflowId,
      plan: completePlan("persisted-workflow"),
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, workflowId);
    await call(client, "workflow_generate", { workflowId });
    await call(client, "workflow_validate", { workflowId });
    const operation = await call(client, "workflow_deploy_start", { workflowId, swarmId: "persisted-swarm" });
    operationId = operation.operationId;
    assert.equal(operation.phase, "upload");
  }, {
    env: {
      PH_WORKFLOW_PERSISTENCE: "local",
      PH_WORKFLOW_STORE_PATH: storePath,
    },
  });

  await withClient(root, async (client) => {
    const listed = await call(client, "workflow_list");
    assert.equal(listed.count, 1);
    assert.equal(listed.workflows[0].workflowId, workflowId);
    assert.equal(listed.workflows[0].bundle.id, "persisted-workflow");
    assert.equal(listed.workflows[0].activeOperations.deploy, operationId);
    assert.equal(listed.workflows[0].operations[operationId].status, "running");
    assert.equal(listed.workflows[0].operations[operationId].phase, "upload");
    const status = await call(client, "workflow_status", { workflowId });
    assert.ok(status.history.length >= 2);
    assert.equal(status.persistence.mode, "local");
    assert.equal(status.activeOperations.deploy, operationId);
    const operation = await call(client, "workflow_deploy_status", { workflowId, operationId });
    assert.equal(operation.operationId, operationId);
    assert.equal(operation.phase, "upload");
  }, {
    PH_WORKFLOW_PERSISTENCE: "local",
    PH_WORKFLOW_STORE_PATH: storePath,
  });
});

test("HiveMind enrichment is explicit, redacted, and optional", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "postman", sourcePath });
    const noConfig = await call(client, "workflow_hivemind_enrich", { workflowId: started.workflowId });
    assert.equal(noConfig.hivemind.status, "not-configured");
  }, { HIVEMIND_MCP_URL: "", HIVEMIND_BASE_URL: "", HIVEMIND_API_BASE_URL: "" });

  await withMockHiveMind(async (url, calls) => {
    await withClient(root, async (client) => {
      const started = await call(client, "workflow_start", { sourceType: "plain-instructions", instructions: "Create a HiveMind enrichment test." });
      await call(client, "workflow_update", { workflowId: started.workflowId, plan: { bundleId: "hivemind-enrichment" } });
      const enriched = await call(client, "workflow_hivemind_enrich", { workflowId: started.workflowId });
      assert.equal(enriched.hivemind.status, "written");
      assert.ok(calls.some(message => message.method === "tools/call" && message.params?.name === "entry_append"));
      const append = calls.find(message => message.method === "tools/call" && message.params?.name === "entry_append");
      assert.doesNotMatch(JSON.stringify(append), /Create a HiveMind enrichment test/);
      assert.match(JSON.stringify(append), /hivemind-enrichment/);
    }, { HIVEMIND_MCP_URL: url, HIVEMIND_BASE_URL: "", HIVEMIND_API_BASE_URL: "" });
  });
});

test("custom workflow profiles are schema validated without falling back to built-ins", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const validPath = resolve(root, "workflow-profiles.json");
  writeFileSync(validPath, JSON.stringify({
    defaultProfileId: "team-profile",
    profiles: {
      "team-profile": {
        id: "team-profile",
        label: "Team Profile",
        purpose: "Team-specific test workflow.",
        defaultRole: "architect",
        roleSequence: ["architect", "tester"],
        reviewStages: [{
          id: "three-amigos",
          label: "Team Three Amigos",
          beforeAction: "workflow_generate",
          roles: ["architect", "tester"],
          purpose: "Team review.",
        }],
        evidenceRequirements: [{
          id: "team.intent",
          label: "Team intent",
          requiredBefore: "workflow_generate",
          fields: ["plan.bundleId"],
          provenanceFields: ["plan.bundleId"],
          allowedProvenance: ["user"],
        }],
        questionPolicy: "ask-before-generate",
        debugPolicy: "agent-decides-iterations",
        evidencePolicy: "team-evidence-required",
        allowedToolTier: "authoring",
      },
    },
  }), "utf8");

  await withClient(root, async (client) => {
    const profiles = await call(client, "workflow_profiles_list");
    assert.equal(profiles.defaultProfileId, "team-profile");
    assert.ok(profiles.profiles.some((profile) => profile.id === "team-profile"));
    const start = await call(client, {
      name: "workflow_start",
    }.name, {
      sourceType: "plain-instructions",
      instructions: "Create a team workflow.",
    });
    assert.equal(start.profile.id, "team-profile");
  }, { PH_WORKFLOW_PROFILES_PATH: validPath });

  const invalidPath = resolve(root, "bad-workflow-profiles.json");
  writeFileSync(invalidPath, JSON.stringify({
    defaultProfileId: "broken",
    profiles: {
      broken: {
        id: "broken",
        label: "Broken",
        purpose: "Invalid role reference.",
        defaultRole: "not-a-role",
        roleSequence: ["not-a-role"],
        reviewStages: [],
        evidenceRequirements: [],
        questionPolicy: "ask-before-generate",
        debugPolicy: "agent-decides-iterations",
        evidencePolicy: "broken",
        allowedToolTier: "authoring",
      },
    },
  }), "utf8");

  await withClient(root, async (client) => {
    const error = await callError(client, "workflow_profiles_list");
    assert.match(error, /WORKFLOW_PROFILE_CONFIG_INVALID/);
    assert.match(error, /not-a-role/);
  }, { PH_WORKFLOW_PROFILES_PATH: invalidPath });
});

test("workflow report includes role completion and evidence gaps", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "postman", sourcePath });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: completePlan("agent-report-roles"),
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });
    await call(client, "workflow_validate", { workflowId: started.workflowId });
    const status = await call(client, "workflow_status", { workflowId: started.workflowId });
    assert.equal(status.claimMatrix.find(claim => claim.id === "bundle.exists").status, "satisfied");
    assert.equal(status.claimMatrix.find(claim => claim.id === "runtime.deployed").status, "not-run");
    const reportResult = await call(client, "workflow_report", { workflowId: started.workflowId });
    assert.ok(reportResult.claimMatrix.some(claim => claim.id === "stakeholder.report"));

    const report = readFileSync(resolve(root, "agent-report-roles", "WORKFLOW_EVIDENCE.md"), "utf8");
    assert.match(report, /## Role Review/);
    assert.match(report, /Three Amigos Review/);
    assert.match(report, /architect: pass/);
    assert.match(report, /## Evidence Gaps/);
    assert.match(report, /## Claim Matrix/);
    assert.match(report, /## Agent Handoff/);
    assert.match(report, /Next action: workflow_deploy_start/);
  });
});

test("workflow evidence render returns an MCP App widget payload without mutating state", async () => {
  const root = mkdtempSync(join(tmpdir(), "ph-workflow-root-"));
  const sourcePath = writeSource(root);

  await withScenarioManagerValidationClient(root, async (client) => {
    const started = await call(client, "workflow_start", { sourceType: "postman", sourcePath });
    await call(client, "workflow_update", {
      workflowId: started.workflowId,
      plan: completePlan("agent-render-widget"),
      provenance: requiredProvenance("user"),
    });
    await completeThreeAmigos(client, started.workflowId);
    await call(client, "workflow_generate", { workflowId: started.workflowId });
    await call(client, "workflow_validate", { workflowId: started.workflowId });

    const before = await call(client, "workflow_status", { workflowId: started.workflowId });
    const rendered = await callRaw(client, "workflow_evidence_render", { workflowId: started.workflowId });
    const after = await call(client, "workflow_status", { workflowId: started.workflowId });

    assert.equal(rendered.structuredContent.workflowId, started.workflowId);
    assert.equal(rendered.structuredContent.summary.bundleId, "agent-render-widget");
    assert.equal(rendered.structuredContent.agent.nextAction.tool, "workflow_deploy_start");
    assert.equal(rendered.structuredContent.summary.verdict, rendered.structuredContent.agent.verdict);
    assert.equal(rendered.structuredContent.summary.nextAction.tool, "workflow_deploy_start");
    assert.ok(rendered.structuredContent.claimMatrix.some(claim => claim.id === "validation.scenario-manager"));
    assert.equal(rendered._meta.ui.resourceUri, "ui://pockethive/workflow-evidence-v1.html");
    assert.equal(rendered._meta["openai/outputTemplate"], "ui://pockethive/workflow-evidence-v1.html");
    assert.equal(after.history.length, before.history.length, "rendering evidence must not record a workflow attempt");
  });
});
const validationEvidence = {
  scenarioProtocolVersion: "2.0.0",
  supportedScenarioProtocolVersion: "2.0.0",
  scenarioManagerVersion: "0.15.35",
  artifactDigest: "sha256:test",
};
