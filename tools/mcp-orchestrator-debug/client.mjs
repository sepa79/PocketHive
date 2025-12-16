#!/usr/bin/env node

/**
 * PocketHive orchestrator / Rabbit debug CLI.
 *
 * This no longer speaks MCP; it just:
 * - Talks to the Orchestrator REST API directly
 * - Reads control-plane recordings written by rabbit-recorder.mjs
 * - Talks to RabbitMQ via AMQP and (optionally) HTTP management API
 *
 * Usage (from repo root):
 *
 *   node tools/mcp-orchestrator-debug/client.mjs list-swarms
 *   node tools/mcp-orchestrator-debug/client.mjs get-swarm foo
 *   node tools/mcp-orchestrator-debug/client.mjs get-recorded
 *
 * For RabbitMQ recording, run in a separate terminal:
 *
 *   node tools/mcp-orchestrator-debug/rabbit-recorder.mjs
 */

import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { readFileSync, existsSync, rmSync } from "node:fs";
import { spawn } from "node:child_process";
import amqplib from "amqplib";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const repoRoot = resolve(__dirname, "../..");
const LOG_PATH = resolve(__dirname, "control-recording.jsonl");

const ORCHESTRATOR_BASE_URL =
  process.env.ORCHESTRATOR_BASE_URL || "http://localhost:8088/orchestrator";
const SCENARIO_MANAGER_BASE_URL =
  process.env.SCENARIO_MANAGER_BASE_URL ||
  "http://localhost:8088/scenario-manager";
const RABBIT_MGMT_BASE_URL =
  process.env.RABBITMQ_MANAGEMENT_BASE_URL ||
  "http://localhost:15672/rabbitmq/api";

function printUsage() {
  console.error(
    "Usage:\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-swarms\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-swarm <swarmId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-snapshot <swarmId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs worker-configs <swarmId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-scenarios\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-scenario <scenarioId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs create-swarm <swarmId> <templateId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs start-swarm <swarmId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs stop-swarm <swarmId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs remove-swarm <swarmId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs status-request <swarmId> <role> <instanceId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs check-queues <queueName> [<queueName>...]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs tap-queue <exchange> <routingKey> [queueName]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-queues\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal <swarmId> [--runId <runId>] [--correlationId <id>] [--limit <n>] [--pages <n>|--all]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal-runs <swarmId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal-pin <swarmId> [--runId <runId>] [--mode FULL|SLIM|ERRORS_ONLY] [--name <name>]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs hive-journal [--swarmId <swarmId>] [--runId <runId>] [--correlationId <id>] [--limit <n>] [--pages <n>|--all]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs commands\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-recorded\n" +
      "  (append --record to create/start/stop/remove to capture control-plane messages)\n\n" +
      "Examples:\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-swarms\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-swarm foo\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-snapshot foo\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs worker-configs foo\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs create-swarm foo local-rest-defaults\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs start-swarm foo --record\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs remove-swarm foo --record\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs status-request foo processor foo-worker-bee-1234\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs check-queues ph.foo.gen ph.foo.mod ph.foo.final\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs tap-queue ph.foo.hive ph.foo.final\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-queues\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal-runs foo\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal foo --all\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal foo --runId <runId> --pages 2\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs swarm-journal-pin foo --runId <runId> --mode SLIM\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs hive-journal --swarmId foo --runId <runId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs commands\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-recorded\n"
  );
}

let args = process.argv.slice(2);
  if (args.length === 0) {
  printUsage();
  process.exit(1);
}

const recordEnabled = args.includes("--record");
if (recordEnabled) {
  args = args.filter((arg) => arg !== "--record");
}

const subcommand = args[0];

const COMMANDS = [
  {
    name: "list-swarms",
    description: "List swarms via GET /api/swarms",
  },
  {
    name: "get-swarm",
    description: "Fetch swarm by id via GET /api/swarms/{swarmId}",
    params: ["swarmId"],
  },
  {
    name: "swarm-snapshot",
    description:
      "Aggregate view for a swarm: REST status, work/control queues, recent control-plane messages",
    params: ["swarmId"],
  },
  {
    name: "worker-configs",
    description:
      "Dump latest worker config snapshot for a swarm by listening to status events on the control exchange",
    params: ["swarmId"],
  },
  {
    name: "list-scenarios",
    description: "List scenarios via GET /scenarios from Scenario Manager",
  },
  {
    name: "get-scenario",
    description: "Fetch scenario by id via GET /scenarios/{id} from Scenario Manager",
    params: ["scenarioId"],
  },
  {
    name: "create-swarm",
    description: "Create swarm via POST /api/swarms/{swarmId}/create",
    params: ["swarmId", "templateId", "[notes]", "[--record]"],
  },
  {
    name: "start-swarm",
    description: "Start swarm via POST /api/swarms/{swarmId}/start",
    params: ["swarmId", "[notes]", "[--record]"],
  },
  {
    name: "stop-swarm",
    description: "Stop swarm via POST /api/swarms/{swarmId}/stop",
    params: ["swarmId", "[notes]", "[--record]"],
  },
  {
    name: "remove-swarm",
    description: "Remove swarm via POST /api/swarms/{swarmId}/remove",
    params: ["swarmId", "[notes]", "[--record]"],
  },
  {
    name: "status-request",
    description:
      "Send a control-plane status-request signal directly via AMQP: signal.status-request.<swarmId>.<role>.<instanceId>",
    params: ["swarmId", "role", "instanceId"],
  },
  {
    name: "check-queues",
    description: "Check existence / counts for specific queues via AMQP",
    params: ["queueName", "[...queueNames]"],
  },
  {
    name: "tap-queue",
    description:
      "Declare a queue and bind it to an exchange/routingKey so you can tap messages",
    params: ["exchange", "routingKey", "[queueName]"],
  },
  {
    name: "list-queues",
    description:
      "List all queues from RabbitMQ HTTP management API (RABBITMQ_MANAGEMENT_BASE_URL)",
  },
  {
    name: "swarm-journal",
    description:
      "Fetch swarm journal via orchestrator paging API: GET /api/swarms/{swarmId}/journal/page",
    params: ["swarmId", "[--runId <runId>]", "[--correlationId <id>]", "[--limit <n>]", "[--pages <n>|--all]"],
  },
  {
    name: "swarm-journal-runs",
    description:
      "List known journal runs for a swarm id: GET /api/swarms/{swarmId}/journal/runs",
    params: ["swarmId"],
  },
  {
    name: "swarm-journal-pin",
    description:
      "Pin a swarm journal run into archive: POST /api/swarms/{swarmId}/journal/pin",
    params: ["swarmId", "[--runId <runId>]", "[--mode FULL|SLIM|ERRORS_ONLY]", "[--name <name>]"],
  },
  {
    name: "hive-journal",
    description:
      "Fetch hive journal via orchestrator paging API: GET /api/journal/hive/page",
    params: ["[--swarmId <swarmId>]", "[--runId <runId>]", "[--correlationId <id>]", "[--limit <n>]", "[--pages <n>|--all]"],
  },
  {
    name: "get-recorded",
    description: "Print recorded control-plane messages from control-recording.jsonl",
  },
  {
    name: "commands",
    description: "List available commands and their parameters in JSON form",
  },
];

async function main() {
  try {
    if (subcommand === "list-swarms") {
      const swarms = await httpJson("/api/swarms");
      console.log(JSON.stringify(swarms ?? [], null, 2));
      return;
    }

    if (subcommand === "get-swarm") {
      const swarmId = args[1];
      if (!swarmId) {
        console.error("get-swarm requires a swarm id");
        process.exit(1);
      }
      const swarm = await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}`);
      console.log(JSON.stringify(swarm ?? null, null, 2));
      return;
    }

    if (subcommand === "list-scenarios") {
      const url = `${SCENARIO_MANAGER_BASE_URL.replace(/\/+$/, "")}/scenarios`;
      const scenarios = await httpJson(url);
      console.log(JSON.stringify(scenarios ?? [], null, 2));
      return;
    }

    if (subcommand === "get-scenario") {
      const id = args[1];
      if (!id) {
        console.error("get-scenario requires a scenario id");
        process.exit(1);
      }
      const base = SCENARIO_MANAGER_BASE_URL.replace(/\/+$/, "");
      const url = `${base}/scenarios/${encodeURIComponent(id)}`;
      const scenario = await httpJson(url);
      console.log(JSON.stringify(scenario ?? null, null, 2));
      return;
    }

    if (subcommand === "swarm-snapshot") {
      const swarmId = args[1];
      if (!swarmId) {
        console.error("swarm-snapshot requires a swarm id");
        process.exit(1);
      }
      const snapshot = await buildSwarmSnapshot(swarmId);
      console.log(JSON.stringify(snapshot, null, 2));
      return;
    }

    if (subcommand === "worker-configs") {
      const swarmId = args[1];
      if (!swarmId) {
        console.error("worker-configs requires a swarm id");
        process.exit(1);
      }
      const configs = await collectWorkerConfigs(swarmId);
      console.log(JSON.stringify(configs, null, 2));
      return;
    }

    if (subcommand === "swarm-journal-runs") {
      const swarmId = args[1];
      if (!swarmId) {
        console.error("swarm-journal-runs requires <swarmId>");
        process.exit(1);
      }
      const runs = await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/journal/runs`);
      console.log(JSON.stringify(runs ?? [], null, 2));
      return;
    }

    if (subcommand === "swarm-journal-pin") {
      const swarmId = args[1];
      if (!swarmId) {
        console.error("swarm-journal-pin requires <swarmId>");
        process.exit(1);
      }
      const flags = parseFlags(args.slice(2));
      const runId = flags.runId || null;
      const mode = flags.mode || null;
      const name = flags.name || null;
      const result = await httpJson(`/api/swarms/${encodeURIComponent(swarmId)}/journal/pin`, {
        method: "POST",
        body: { runId, mode, name },
      });
      console.log(JSON.stringify(result ?? null, null, 2));
      return;
    }

    if (subcommand === "swarm-journal") {
      const swarmId = args[1];
      if (!swarmId) {
        console.error("swarm-journal requires <swarmId>");
        process.exit(1);
      }
      const flags = parseFlags(args.slice(2));
      const limit = flags.limit ? Number(flags.limit) : 200;
      const runId = flags.runId || null;
      const correlationId = flags.correlationId || null;
      const pages = flags.all ? Number.POSITIVE_INFINITY : flags.pages ? Number(flags.pages) : 1;

      const items = await collectPagedJournal({
        path: `/api/swarms/${encodeURIComponent(swarmId)}/journal/page`,
        limit,
        runId,
        correlationId,
        pages,
      });
      console.log(JSON.stringify(items, null, 2));
      return;
    }

    if (subcommand === "hive-journal") {
      const flags = parseFlags(args.slice(1));
      const limit = flags.limit ? Number(flags.limit) : 200;
      const swarmId = flags.swarmId || null;
      const runId = flags.runId || null;
      const correlationId = flags.correlationId || null;
      const pages = flags.all ? Number.POSITIVE_INFINITY : flags.pages ? Number(flags.pages) : 1;

      const items = await collectPagedJournal({
        path: "/api/journal/hive/page",
        limit,
        swarmId,
        runId,
        correlationId,
        pages,
      });
      console.log(JSON.stringify(items, null, 2));
      return;
    }

    if (subcommand === "commands") {
      console.log(JSON.stringify(COMMANDS, null, 2));
      return;
    }

    if (subcommand === "create-swarm") {
      await withOptionalRecording(async () => {
        const swarmId = args[1];
        const templateId = args[2];
        const notes = args[3];
        if (!swarmId || !templateId) {
          console.error("create-swarm requires <swarmId> and <templateId>");
          process.exit(1);
        }
        const body = {
          templateId,
          idempotencyKey: randomIdempotencyKey(),
          ...(notes ? { notes } : {}),
        };
        const resp = await httpJson(
          `/api/swarms/${encodeURIComponent(swarmId)}/create`,
          { method: "POST", body }
        );
        console.log(JSON.stringify(resp ?? null, null, 2));
      });
      if (recordEnabled) {
        await printRecorded();
      }
      return;
    }

    if (subcommand === "start-swarm") {
      await withOptionalRecording(async () => {
        const swarmId = args[1];
        const notes = args[2];
        if (!swarmId) {
          console.error("start-swarm requires <swarmId>");
          process.exit(1);
        }
        const body = {
          idempotencyKey: randomIdempotencyKey(),
          ...(notes ? { notes } : {}),
        };
        const resp = await httpJson(
          `/api/swarms/${encodeURIComponent(swarmId)}/start`,
          { method: "POST", body }
        );
        console.log(JSON.stringify(resp ?? null, null, 2));
      });
      if (recordEnabled) {
        await printRecorded();
      }
      return;
    }

    if (subcommand === "stop-swarm") {
      await withOptionalRecording(async () => {
        const swarmId = args[1];
        const notes = args[2];
        if (!swarmId) {
          console.error("stop-swarm requires <swarmId>");
          process.exit(1);
        }
        const body = {
          idempotencyKey: randomIdempotencyKey(),
          ...(notes ? { notes } : {}),
        };
        const resp = await httpJson(
          `/api/swarms/${encodeURIComponent(swarmId)}/stop`,
          { method: "POST", body }
        );
        console.log(JSON.stringify(resp ?? null, null, 2));
      });
      if (recordEnabled) {
        await printRecorded();
      }
      return;
    }

    if (subcommand === "remove-swarm") {
      await withOptionalRecording(async () => {
        const swarmId = args[1];
        const notes = args[2];
        if (!swarmId) {
          console.error("remove-swarm requires <swarmId>");
          process.exit(1);
        }
        const body = {
          idempotencyKey: randomIdempotencyKey(),
          ...(notes ? { notes } : {}),
        };
        const resp = await httpJson(
          `/api/swarms/${encodeURIComponent(swarmId)}/remove`,
          { method: "POST", body }
        );
        console.log(JSON.stringify(resp ?? null, null, 2));
      });
      if (recordEnabled) {
        await printRecorded();
      }
      return;
    }

    if (subcommand === "status-request") {
      const swarmId = args[1];
      const role = args[2];
      const instanceId = args[3];
      if (!swarmId || !role || !instanceId) {
        console.error("status-request requires <swarmId> <role> <instanceId>");
        process.exit(1);
      }
      await withOptionalRecording(async () => {
        await sendStatusRequest(swarmId, role, instanceId);
        console.log(
          JSON.stringify(
            {
              swarmId,
              role,
              instanceId,
              routingKey: `signal.status-request.${swarmId}.${role}.${instanceId}`,
            },
            null,
            2
          )
        );
      });
      if (recordEnabled) {
        await printRecorded();
      }
      return;
    }

    if (subcommand === "get-recorded") {
      await printRecorded();
      return;
    }

    if (subcommand === "check-queues") {
      const names = args.slice(1);
      if (names.length === 0) {
        console.error("check-queues requires at least one queue name");
        process.exit(1);
      }
      const results = await checkQueues(names);
      console.log(JSON.stringify(results, null, 2));
      return;
    }

    if (subcommand === "tap-queue") {
      const exchange = args[1];
      const routingKey = args[2];
      const queueName = args[3];
      if (!exchange || !routingKey) {
        console.error("tap-queue requires <exchange> and <routingKey> [queueName]");
        process.exit(1);
      }
      const tap = await createTapQueue(exchange, routingKey, queueName);
      console.log(JSON.stringify(tap, null, 2));
      return;
    }

    if (subcommand === "list-queues") {
      const queues = await listQueues();
      console.log(JSON.stringify(queues ?? [], null, 2));
      return;
    }

    printUsage();
    process.exit(1);
  } catch (err) {
    console.error("Debug client error:", err);
    process.exitCode = 1;
  }
}

function parseFlags(argv) {
  const flags = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg || !arg.startsWith("--")) {
      continue;
    }
    const name = arg.slice(2);
    if (name === "all") {
      flags.all = true;
      continue;
    }
    const value = argv[i + 1];
    if (value && !value.startsWith("--")) {
      flags[name] = value;
      i += 1;
    } else {
      flags[name] = true;
    }
  }
  return flags;
}

function buildQuery(params) {
  const parts = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined) continue;
    const text = String(value);
    if (!text.trim()) continue;
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(text)}`);
  }
  return parts.length ? `?${parts.join("&")}` : "";
}

async function collectPagedJournal({ path, limit, swarmId, runId, correlationId, pages }) {
  const all = [];
  let beforeTs = null;
  let beforeId = null;
  let remaining = pages;

  while (remaining > 0) {
    const query = buildQuery({
      limit,
      swarmId: swarmId || undefined,
      runId: runId || undefined,
      correlationId: correlationId || undefined,
      beforeTs,
      beforeId,
    });
    const page = await httpJson(`${path}${query}`);
    const items = Array.isArray(page?.items) ? page.items : [];
    all.push(...items);
    if (!page?.hasMore || !page?.nextCursor) {
      break;
    }
    beforeTs = page.nextCursor.ts;
    beforeId = page.nextCursor.id;
    remaining -= 1;
  }
  return all;
}

async function httpJson(path, options = {}) {
  const url =
    path.startsWith("http://") || path.startsWith("https://")
      ? path
      : `${ORCHESTRATOR_BASE_URL}${path}`;

  const init = {
    method: options.method || "GET",
    headers: {
      "content-type": "application/json",
      ...(options.headers || {}),
    },
    signal: AbortSignal.timeout?.(options.timeoutMs || 30_000),
  };

  if (options.body !== undefined) {
    init.body =
      typeof options.body === "string"
        ? options.body
        : JSON.stringify(options.body);
  }

  const res = await fetch(url, init);
  const text = await res.text();
  if (!res.ok) {
    throw new Error(
      `HTTP ${res.status} ${res.statusText} for ${url}: ${text || "<empty>"}`
    );
  }
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function randomIdempotencyKey() {
  if (typeof crypto !== "undefined" && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  // Fallback: timestamp + random
  return (
    "idemp-" +
    Date.now().toString(36) +
    "-" +
    Math.floor(Math.random() * 1e9).toString(36)
  );
}

async function withOptionalRecording(fn) {
  if (!recordEnabled) {
    return fn();
  }
  if (existsSync(LOG_PATH)) {
    try {
      rmSync(LOG_PATH);
    } catch {
      // ignore
    }
  }
  const recorderPath = resolve(__dirname, "rabbit-recorder.mjs");
  const child = spawn(process.execPath, [recorderPath], {
    cwd: repoRoot,
    stdio: ["ignore", "ignore", "inherit"],
  });
  try {
    const result = await fn();
    await sleep(2000);
    return result;
  } finally {
    try {
      child.kill("SIGINT");
    } catch {
      // ignore
    }
  }
}

async function printRecorded() {
  if (!existsSync(LOG_PATH)) {
    console.log("[]");
    return;
  }
  const text = readFileSync(LOG_PATH, "utf8");
  const lines = text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  const entries = [];
  for (const line of lines) {
    try {
      entries.push(JSON.parse(line));
    } catch {
      // skip malformed lines
    }
  }
  console.log(JSON.stringify(entries, null, 2));
}

function readRecordedEntries() {
  if (!existsSync(LOG_PATH)) {
    return [];
  }
  const text = readFileSync(LOG_PATH, "utf8");
  const lines = text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
  const entries = [];
  for (const line of lines) {
    try {
      entries.push(JSON.parse(line));
    } catch {
      // ignore malformed
    }
  }
  return entries;
}

async function buildSwarmSnapshot(swarmId) {
  const trimmed = String(swarmId).trim();

  // Swarm status from orchestrator
  let swarm = null;
  try {
    swarm = await httpJson(`/api/swarms/${encodeURIComponent(trimmed)}`);
  } catch (err) {
    swarm = { error: String(err) };
  }

  // Queues from Rabbit management API
  let queues = [];
  try {
    queues = await listQueues();
  } catch (err) {
    queues = { error: String(err) };
  }

  const workQueues = Array.isArray(queues)
    ? queues
        .filter(
          (q) =>
            typeof q?.name === "string" &&
            q.name.startsWith(`ph.${trimmed}.`)
        )
        .map((q) => q.name)
        .sort()
    : [];

  const controlQueues = Array.isArray(queues)
    ? queues
        .filter(
          (q) =>
            typeof q?.name === "string" &&
            q.name.startsWith(`ph.control.${trimmed}.`)
        )
        .map((q) => q.name)
        .sort()
    : [];

  // Control-plane messages from recording (if present)
  const recorded = readRecordedEntries();
  const controlMessages = recorded.filter((entry) => {
    if (!entry || typeof entry !== "object") {
      return false;
    }
    const rk = entry.routingKey || "";
    if (typeof rk === "string" && rk.includes(`.${trimmed}.`)) {
      return true;
    }
    // Try to inspect JSON body for swarmId hints
    if (typeof entry.body === "string") {
      try {
        const parsed = JSON.parse(entry.body);
        if (
          parsed &&
          typeof parsed === "object" &&
          parsed.swarmId === trimmed
        ) {
          return true;
        }
      } catch {
        // ignore parse errors
      }
    }
    return false;
  });

  return {
    swarmId: trimmed,
    orchestrator: swarm,
    queues: {
      work: workQueues,
      control: controlQueues,
    },
    controlMessages,
  };
}

/**
 * Collects the latest worker config snapshot for a given swarm by listening to
 * status-full and status-delta events on the control exchange.
 *
 * The result is a map keyed by worker role, each entry containing:
 *   - instanceId
 *   - enabled (aggregate flag)
 *   - config (raw config map from the status payload)
 *   - statusData (status.data field for debugging)
 */
async function collectWorkerConfigs(swarmId) {
  const trimmed = String(swarmId).trim();
  if (!trimmed) {
    throw new Error("swarmId must not be blank");
  }
  const url = rabbitUrl();
  const conn = await amqplib.connect(url);
  const ch = await conn.createChannel();
  try {
    const ex = controlExchange();
    await ch.assertExchange(ex, "topic", { durable: true });
    const q = await ch.assertQueue("", {
      exclusive: true,
      autoDelete: true,
    });
    // Bind to status-full and status-delta events for this swarm.
    const keys = [
      `event.metric.status-full.${trimmed}.#`,
      `event.metric.status-delta.${trimmed}.#`,
      `event.status-full.${trimmed}.#`,
      `event.status-delta.${trimmed}.#`,
    ];
    for (const key of keys) {
      await ch.bindQueue(q.queue, ex, key);
    }

    const byRole = {};
    const start = Date.now();
    const timeoutMs = 5000;

    while (Date.now() - start < timeoutMs) {
      const msg = await ch.get(q.queue, { noAck: false });
      if (!msg) {
        await sleep(100);
        continue;
      }
      try {
        const rk = msg.fields.routingKey || "";
        const body = msg.content.toString("utf8");
        let payload;
        try {
          payload = JSON.parse(body);
        } catch {
          payload = null;
        }
        if (!payload || typeof payload !== "object") {
          ch.ack(msg);
          continue;
        }
        if (payload.event !== "status") {
          ch.ack(msg);
          continue;
        }
        const role = payload.role || "unknown";
        const instance = payload.instance || "unknown";
        const enabled = !!payload.enabled;
        const data = payload.data && typeof payload.data === "object"
          ? payload.data
          : {};
        const workers = Array.isArray(data.workers) ? data.workers : [];

        for (const worker of workers) {
          if (!worker || typeof worker !== "object") continue;
          const workerRole = worker.role || role;
          const workerConfig =
            worker.config && typeof worker.config === "object"
              ? worker.config
              : {};
          const statusData =
            worker.data && typeof worker.data === "object" ? worker.data : {};
          byRole[workerRole] = {
            role: workerRole,
            instanceId: instance,
            enabled,
            config: workerConfig,
            statusData,
            routingKey: rk,
          };
        }
      } finally {
        ch.ack(msg);
      }
    }

    return {
      swarmId: trimmed,
      workers: byRole,
    };
  } finally {
    try {
      await ch.close();
    } catch {
      // ignore
    }
    try {
      await conn.close();
    } catch {
      // ignore
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function checkQueues(names) {
  const url = rabbitUrl();
  const conn = await amqplib.connect(url);
  const ch = await conn.createChannel();
  const results = [];
  try {
    for (const name of names) {
      try {
        const info = await ch.checkQueue(name);
        results.push({
          queue: name,
          exists: true,
          messageCount: info.messageCount,
          consumerCount: info.consumerCount,
        });
      } catch (err) {
        results.push({
          queue: name,
          exists: false,
          error: err?.message ?? String(err),
        });
      }
    }
  } finally {
    try {
      await ch.close();
    } catch {
      // ignore
    }
    try {
      await conn.close();
    } catch {
      // ignore
    }
  }
  return results;
}

async function createTapQueue(exchange, routingKey, queueName) {
  const url = rabbitUrl();
  const conn = await amqplib.connect(url);
  const ch = await conn.createChannel();
  try {
    const ex = (exchange || "").trim();
    const key = (routingKey || "").trim();
    if (!ex || !key) {
      throw new Error("exchange and routingKey must be non-empty");
    }
    await ch.assertExchange(ex, "topic", { durable: true });
    const q = await ch.assertQueue(queueName || "", {
      exclusive: !queueName,
      durable: !!queueName,
      autoDelete: !queueName,
    });
    await ch.bindQueue(q.queue, ex, key);
    return {
      exchange: ex,
      routingKey: key,
      queue: q.queue,
      durable: !!queueName,
      exclusive: !queueName,
      autoDelete: !queueName,
    };
  } finally {
    try {
      await ch.close();
    } catch {
      // ignore
    }
    try {
      await conn.close();
    } catch {
      // ignore
    }
  }
}

function rabbitUrl() {
  const host = process.env.RABBITMQ_HOST || "localhost";
  const port = Number(process.env.RABBITMQ_PORT || "5672");
  const user = process.env.RABBITMQ_DEFAULT_USER || "guest";
  const pass = process.env.RABBITMQ_DEFAULT_PASS || "guest";
  const vhost = process.env.RABBITMQ_VHOST || "/";
  const encodedVhost = vhost === "/" ? "%2F" : encodeURIComponent(vhost);
  return `amqp://${encodeURIComponent(user)}:${encodeURIComponent(
    pass
  )}@${host}:${port}/${encodedVhost}`;
}

function controlExchange() {
  return process.env.POCKETHIVE_CONTROL_PLANE_EXCHANGE || "ph.control";
}

async function listQueues() {
  const base = RABBIT_MGMT_BASE_URL.replace(/\/+$/, "");
  const url = `${base}/queues`;
  const host = process.env.RABBITMQ_HOST || "localhost";
  const user = process.env.RABBITMQ_DEFAULT_USER || "guest";
  const pass = process.env.RABBITMQ_DEFAULT_PASS || "guest";
  const authHeader = "Basic " + Buffer.from(`${user}:${pass}`).toString("base64");
  const res = await fetch(url, {
    method: "GET",
    headers: {
      "content-type": "application/json",
      "authorization": authHeader,
    },
    signal: AbortSignal.timeout?.(30_000),
  });
  const text = await res.text();
  if (!res.ok) {
    throw new Error(
      `HTTP ${res.status} ${res.statusText} for ${url}: ${text || "<empty>"}`
    );
  }
  if (!text) {
    return [];
  }
  try {
    return JSON.parse(text);
  } catch {
    return [];
  }
}

async function sendStatusRequest(swarmId, role, instanceId) {
  const url = rabbitUrl();
  const conn = await amqplib.connect(url);
  const ch = await conn.createChannel();
  try {
    const exchange = controlExchange();
    const rk = `signal.status-request.${swarmId}.${role}.${instanceId}`;
    const payload = JSON.stringify({});
    await ch.assertExchange(exchange, "topic", { durable: true });
    ch.publish(exchange, rk, Buffer.from(payload, "utf8"), {
      contentType: "application/json",
    });
  } finally {
    try {
      await ch.close();
    } catch {
      // ignore
    }
    try {
      await conn.close();
    } catch {
      // ignore
    }
  }
}

main().catch((err) => {
  console.error("Debug client fatal error:", err);
  process.exit(1);
});
