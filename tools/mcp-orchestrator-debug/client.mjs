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
const RABBIT_MGMT_BASE_URL =
  process.env.RABBITMQ_MANAGEMENT_BASE_URL ||
  "http://localhost:15672/rabbitmq/api";

function printUsage() {
  console.error(
    "Usage:\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-swarms\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-swarm <swarmId>\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs create-swarm <swarmId> <templateId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs start-swarm <swarmId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs stop-swarm <swarmId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs remove-swarm <swarmId> [notes]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs check-queues <queueName> [<queueName>...]\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-queues\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs commands\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-recorded\n" +
      "  (append --record to create/start/stop/remove to capture control-plane messages)\n\n" +
      "Examples:\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-swarms\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs get-swarm foo\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs create-swarm foo local-rest-defaults\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs start-swarm foo --record\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs remove-swarm foo --record\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs check-queues ph.foo.gen ph.foo.mod ph.foo.final\n" +
      "  node tools/mcp-orchestrator-debug/client.mjs list-queues\n" +
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
    name: "check-queues",
    description: "Check existence / counts for specific queues via AMQP",
    params: ["queueName", "[...queueNames]"],
  },
  {
    name: "list-queues",
    description:
      "List all queues from RabbitMQ HTTP management API (RABBITMQ_MANAGEMENT_BASE_URL)",
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

main().catch((err) => {
  console.error("Debug client fatal error:", err);
  process.exit(1);
});
