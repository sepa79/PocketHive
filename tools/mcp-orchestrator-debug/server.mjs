#!/usr/bin/env node

import { spawn } from "node:child_process";
import { existsSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  childProcessExited,
  childProcessIsRunning,
  parseRecordedEntries
} from "./server-utils.mjs";
import {
  appendSessionLog,
  createEventId,
  createSessionId,
  summarizeSessionLog
} from "./feedback-store.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const repoRoot = resolve(__dirname, "../..");
const clientPath = resolve(__dirname, "client.mjs");
const recorderPath = resolve(__dirname, "rabbit-recorder.mjs");
const logPath = resolve(__dirname, "control-recording.jsonl");
const sessionLogPath = resolve(__dirname, "session-log.jsonl");
const sessionId = createSessionId();

let recorderChild = null;
let routingKeyPattern = null;

const server = new McpServer({
  name: "pockethive-debug",
  version: "1.0.0"
});

registerLoggedTool(
  "orchestrator.list-swarms",
  {
    title: "List PocketHive swarms",
    description: "List swarms from the PocketHive Orchestrator REST API.",
    inputSchema: {}
  },
  async () => {
    const swarms = await runJsonClient(["list-swarms"]);
    return {
      payload: { swarms },
      summary: `Retrieved ${swarms.length} swarm(s).`
    };
  }
);

registerLoggedTool(
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
    return {
      payload: { swarm },
      summary: `Retrieved swarm '${swarmId}'.`
    };
  }
);

registerLoggedTool(
  "scenario.reload-scenarios",
  {
    title: "Reload PocketHive scenarios",
    description: "Trigger Scenario Manager to reload scenarios from disk.",
    inputSchema: {}
  },
  async () => {
    const result = await runJsonClient(["reload-scenarios"]);
    return {
      payload: result,
      summary: "Triggered Scenario Manager scenario reload."
    };
  }
);

registerLoggedTool(
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
    return {
      payload: {
        recording: true,
        routingKeyPattern: routingKeyPattern ?? "#"
      },
      summary: `Started control-plane recording for routing key pattern '${routingKeyPattern ?? "#"}'.`
    };
  }
);

registerLoggedTool(
  "control.stop-recording",
  {
    title: "Stop PocketHive control recording",
    description: "Stop the background RabbitMQ recorder and keep the buffered messages.",
    inputSchema: {}
  },
  async () => {
    await stopRecorder();
    const bufferedMessages = parseRecordedEntries(logPath, routingKeyPattern).length;
    return {
      payload: {
        recording: false,
        bufferedMessages
      },
      summary: `Stopped recording with ${bufferedMessages} buffered message(s).`
    };
  }
);

registerLoggedTool(
  "control.get-recorded",
  {
    title: "Get recorded PocketHive control messages",
    description: "Read the current buffered control-plane messages from disk.",
    inputSchema: {}
  },
  async () => {
    const messages = parseRecordedEntries(logPath, routingKeyPattern);
    return {
      payload: { messages },
      summary:
        messages.length === 0
          ? "No recorded control-plane messages are buffered yet."
          : `Loaded ${messages.length} recorded control-plane message(s).`,
      nextHint:
        messages.length === 0
          ? {
              suggestedTool: "control.start-recording",
              reason: "Start recording before reading buffered control-plane messages."
            }
          : null
    };
  }
);

registerLoggedTool(
  "feedback.submit",
  {
    title: "Submit structured feedback for a tool event",
    description: "Store structured AI feedback for a previously logged MCP tool event.",
    inputSchema: {
      relatedEventId: z.string(),
      intent: z.string(),
      outcomeUnderstanding: z.string(),
      blockerType: z.enum([
        "misunderstood_contract",
        "missing_domain_step",
        "missing_tool",
        "tool_too_low_level",
        "tool_too_high_level",
        "validation_unclear",
        "docs_gap",
        "example_gap",
        "unexpected_side_effect"
      ]),
      proposedNextAction: z.string(),
      suggestedImprovements: z
        .array(
          z.object({
            type: z.enum([
              "improve_docs",
              "add_example",
              "improve_error_message",
              "narrow_contract",
              "add_parameter",
              "add_tool",
              "split_tool",
              "merge_tools",
              "rename_tool"
            ]),
            target: z.string(),
            reason: z.string(),
            confidence: z.enum(["low", "medium", "high"]).optional()
          })
        )
        .optional()
    }
  },
  async (input) => {
    appendSessionLog(sessionLogPath, {
      kind: "feedback_event",
      sessionId,
      timestamp: new Date().toISOString(),
      ...input
    });

    return {
      payload: {
        accepted: true,
        sessionId,
        relatedEventId: input.relatedEventId
      },
      summary: `Stored feedback for '${input.relatedEventId}'.`
    };
  }
);

registerLoggedTool(
  "feedback.summary",
  {
    title: "Summarize MCP feedback session",
    description: "Summarize tool events and AI feedback captured in the current MCP session.",
    inputSchema: {}
  },
  async () => {
    const summary = summarizeSessionLog(sessionLogPath, sessionId);
    return {
      payload: summary,
      summary: `Summarized ${summary.toolCalls.total} tool call(s) and ${summary.feedbackEvents} feedback event(s).`
    };
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
  if (childProcessIsRunning(recorderChild)) {
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
  const child = recorderChild;
  child.once("close", () => {
    if (recorderChild === child) {
      recorderChild = null;
    }
  });

  let startupError = "";
  child.stderr?.on("data", (chunk) => {
    startupError += chunk.toString();
  });

  await sleep(400);
  if (childProcessExited(child)) {
    const exitCode = child.exitCode;
    recorderChild = null;
    throw new Error(
      startupError || `Rabbit recorder exited before startup completed with code ${exitCode}.`
    );
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

function registerLoggedTool(name, metadata, handler) {
  server.registerTool(name, metadata, async (input = {}) => {
    const eventId = createEventId();
    try {
      const outcome = await handler(input);
      const toolEvent = {
        eventId,
        sessionId,
        toolName: name,
        resultStatus: "ok",
        summary: outcome.summary,
        validation: outcome.validation ?? [],
        nextHint: outcome.nextHint ?? null,
        feedbackRequired: false,
        timestamp: new Date().toISOString()
      };

      appendSessionLog(sessionLogPath, {
        kind: "tool_event",
        ...toolEvent
      });

      return jsonResult({
        ...(outcome.payload ?? {}),
        toolEvent
      });
    } catch (error) {
      const classified = classifyToolError(name, input, error);
      const toolEvent = {
        eventId,
        sessionId,
        toolName: name,
        resultStatus: classified.resultStatus,
        summary: classified.summary,
        validation: classified.validation,
        nextHint: classified.nextHint,
        feedbackRequired: classified.feedbackRequired,
        timestamp: new Date().toISOString()
      };

      appendSessionLog(sessionLogPath, {
        kind: "tool_event",
        ...toolEvent
      });

      return {
        isError: true,
        ...jsonResult({
          toolEvent
        })
      };
    }
  });
}

function classifyToolError(toolName, input, error) {
  const message = error instanceof Error ? error.message : String(error);

  if (toolName === "orchestrator.get-swarm" && message.includes("HTTP 404")) {
    return {
      resultStatus: "rejected",
      summary: `Swarm '${input.swarmId}' was not found.`,
      validation: [
        {
          code: "SWARM_NOT_FOUND",
          severity: "error",
          path: "swarmId",
          message: `No swarm exists with id '${input.swarmId}'.`
        }
      ],
      nextHint: {
        suggestedTool: "orchestrator.list-swarms",
        reason: "List available swarms and retry with an existing swarm id."
      },
      feedbackRequired: true
    };
  }

  if (toolName === "control.start-recording" && isRabbitConnectivityError(message)) {
    return {
      resultStatus: "failed",
      summary: "Could not start control-plane recording because RabbitMQ is unreachable.",
      validation: [
        {
          code: "RABBIT_UNREACHABLE",
          severity: "error",
          path: "rabbitmq",
          message
        }
      ],
      nextHint: {
        suggestedTool: "control.start-recording",
        reason: "Retry after RabbitMQ is reachable and the PocketHive stack is running."
      },
      feedbackRequired: true
    };
  }

  return {
    resultStatus: "failed",
    summary: `Tool '${toolName}' failed.`,
    validation: [
      {
        code: "MCP_TOOL_EXECUTION_FAILED",
        severity: "error",
        path: toolName,
        message
      }
    ],
    nextHint: null,
    feedbackRequired: true
  };
}

function isRabbitConnectivityError(message) {
  return [
    "ECONNREFUSED",
    "ENOTFOUND",
    "ACCESS_REFUSED",
    "Socket closed unexpectedly",
    "Connection closed",
    "connect ECONNREFUSED"
  ].some((fragment) => message.includes(fragment));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
