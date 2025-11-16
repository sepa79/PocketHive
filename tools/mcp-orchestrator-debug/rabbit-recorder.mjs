#!/usr/bin/env node

/**
 * PocketHive RabbitMQ control-plane recorder.
 *
 * Connects to the control exchange (ph.control by default), binds sig.# and ev.#,
 * and writes every control-plane message to stdout and to a JSONL log file:
 *
 *   tools/mcp-orchestrator-debug/control-recording.jsonl
 *
 * Usage (from repo root):
 *
 *   node tools/mcp-orchestrator-debug/rabbit-recorder.mjs
 *
 * Stop with Ctrl+C when done.
 */

import amqplib from "amqplib";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { appendFileSync } from "node:fs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const LOG_PATH = resolve(__dirname, "control-recording.jsonl");

const RABBITMQ_HOST = process.env.RABBITMQ_HOST || "localhost";
const RABBITMQ_PORT = Number(process.env.RABBITMQ_PORT || "5672");
const RABBITMQ_USER = process.env.RABBITMQ_DEFAULT_USER || "guest";
const RABBITMQ_PASS = process.env.RABBITMQ_DEFAULT_PASS || "guest";
const RABBITMQ_VHOST = process.env.RABBITMQ_VHOST || "/";
const CONTROL_EXCHANGE =
  process.env.POCKETHIVE_CONTROL_PLANE_EXCHANGE || "ph.control";

function rabbitUrl() {
  const vhost =
    RABBITMQ_VHOST === "/" ? "%2F" : encodeURIComponent(RABBITMQ_VHOST);
  return `amqp://${encodeURIComponent(RABBITMQ_USER)}:${encodeURIComponent(
    RABBITMQ_PASS
  )}@${RABBITMQ_HOST}:${RABBITMQ_PORT}/${vhost}`;
}

async function main() {
  const url = rabbitUrl();
  console.log(
    `Connecting to RabbitMQ at ${url}, exchange='${CONTROL_EXCHANGE}'...`
  );
  const conn = await amqplib.connect(url);
  const ch = await conn.createChannel();
  await ch.assertExchange(CONTROL_EXCHANGE, "topic", { durable: true });
  const q = await ch.assertQueue("", { exclusive: true, autoDelete: true });

  const keys = ["sig.#", "ev.#"];
  for (const key of keys) {
    await ch.bindQueue(q.queue, CONTROL_EXCHANGE, key);
  }

  console.log(
    `Recording control-plane messages on ${CONTROL_EXCHANGE} (bindings: ${keys.join(
      ", "
    )})`
  );
  console.log(`Appending JSON lines to ${LOG_PATH}`);

  ch.consume(
    q.queue,
    (msg) => {
      if (!msg) {
        return;
      }
      const rk = msg.fields.routingKey;
      const body = msg.content.toString("utf8");
      const headers = msg.properties.headers || {};
      const entry = {
        timestamp: new Date().toISOString(),
        routingKey: rk,
        headers,
        body,
      };
      const line = JSON.stringify(entry);
      try {
        appendFileSync(LOG_PATH, line + "\n", "utf8");
      } catch (err) {
        console.error("Failed to append to log file:", err?.message ?? String(err));
      }
      console.log(
        `[${entry.timestamp}] rk=${rk} body=${body.slice(0, 200)}${
          body.length > 200 ? "…" : ""
        }`
      );
      ch.ack(msg);
    },
    { noAck: false }
  );

  const handleShutdown = async () => {
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
    process.exit(0);
  };

  process.on("SIGINT", handleShutdown);
  process.on("SIGTERM", handleShutdown);
}

main().catch((err) => {
  console.error("Rabbit recorder error:", err);
  process.exit(1);
});

