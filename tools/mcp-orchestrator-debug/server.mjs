#!/usr/bin/env node

import { spawn } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { parseRecordedEntries } from "./server-utils.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const repoRoot = resolve(__dirname, "../..");
const clientPath = resolve(__dirname, "client.mjs");
const recorderPath = resolve(__dirname, "rabbit-recorder.mjs");
const logPath = resolve(__dirname, "control-recording.jsonl");

let recorderChild = null;
let routingKeyPattern = null;

const server = new McpServer({
  name: "pockethive-debug",
  version: "1.0.0"
});

server.registerTool(
  "orchestrator.list-swarms",
  {
    title: "List PocketHive swarms",
    description: "List swarms from the PocketHive Orchestrator REST API.",
    inputSchema: {}
  },
  async () => {
    const swarms = await runJsonClient(["list-swarms"]);
    return jsonResult({ swarms });
  }
);

server.registerTool(
  "orchestrator.get-swarm",
  {
    title: "Get PocketHive swarm",
    description: "Get one swarm from the PocketHive Orchestrator REST API.",
    inputSchema: {
      swarmId: z.string()
    }
  },
  async ({ swarmId }) => {
    const swarm = await runJsonClient(["get-swarm", swarmId]);
    return jsonResult({ swarm });
  }
);

server.registerTool(
  "control.start-recording",
  {
    title: "Start PocketHive control recording",
    description: "Start recording PocketHive control-plane traffic to a JSONL buffer.",
    inputSchema: {
      routingKeyPattern: z.string().optional()
    }
  },
  async ({ routingKeyPattern: nextPattern }) => {
    routingKeyPattern = nextPattern ?? null;
    await startRecorder();
    return jsonResult({
      recording: true,
      routingKeyPattern: routingKeyPattern ?? "#"
    });
  }
);

server.registerTool(
  "control.stop-recording",
  {
    title: "Stop PocketHive control recording",
    description: "Stop the background RabbitMQ recorder and keep the buffered messages.",
    inputSchema: {}
  },
  async () => {
    await stopRecorder();
    return jsonResult({
      recording: false,
      bufferedMessages: parseRecordedEntries(logPath, routingKeyPattern).length
    });
  }
);

server.registerTool(
  "control.get-recorded",
  {
    title: "Get recorded PocketHive control messages",
    description: "Read the current buffered control-plane messages from disk.",
    inputSchema: {}
  },
  async () => {
    const messages = parseRecordedEntries(logPath, routingKeyPattern);
    return jsonResult({ messages });
  }
);

const transport = new StdioServerTransport();
await server.connect(transport);

process.on("SIGINT", async () => {
  await stopRecorder();
  process.exit(0);
});

process.on("SIGTERM", async () => {
  await stopRecorder();
  process.exit(0);
});

async function runJsonClient(args) {
  const result = await runNodeScript(clientPath, args);
  try {
    return JSON.parse(result.stdout);
  } catch (error) {
    throw new Error(
      `PocketHive debug client returned non-JSON output: ${result.stdout || result.stderr || String(error)}`
    );
  }
}

async function startRecorder() {
  if (recorderChild && !recorderChild.killed) {
    return;
  }

  if (existsSync(logPath)) {
    rmSync(logPath, { force: true });
  }

  recorderChild = spawn(process.execPath, [recorderPath], {
    cwd: repoRoot,
    env: process.env,
    stdio: ["ignore", "ignore", "pipe"]
  });

  let startupError = "";
  recorderChild.stderr?.on("data", (chunk) => {
    startupError += chunk.toString();
  });

  await sleep(400);
  if (recorderChild.exitCode && recorderChild.exitCode !== 0) {
    throw new Error(startupError || "Rabbit recorder exited before startup completed.");
  }
}

async function stopRecorder() {
  if (!recorderChild) {
    return;
  }

  const child = recorderChild;
  recorderChild = null;
  child.kill("SIGINT");

  await new Promise((resolve) => {
    child.once("close", () => resolve(undefined));
    setTimeout(() => resolve(undefined), 1000);
  });
}

async function runNodeScript(scriptPath, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [scriptPath, ...args], {
      cwd: repoRoot,
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"]
    });

    let stdout = "";
    let stderr = "";

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0) {
        reject(new Error(stderr || stdout || `Node script failed with code ${code}`));
        return;
      }
      resolve({ stdout: stdout.trim(), stderr: stderr.trim() });
    });
  });
}

function jsonResult(payload) {
  return {
    content: [
      {
        type: "text",
        text: JSON.stringify(payload, null, 2)
      }
    ],
    structuredContent: payload
  };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
