import { createReadStream, createWriteStream } from "node:fs";
import readline from "node:readline";

export function parseArgs(argv) {
  const options = new Map();
  const positional = [];

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) {
      positional.push(token);
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next == null || next.startsWith("--")) {
      options.set(key, true);
      continue;
    }
    options.set(key, next);
    i += 1;
  }

  return { options, positional };
}

export function option(options, key, fallback = undefined) {
  return options.has(key) ? options.get(key) : fallback;
}

export function requireOption(options, key) {
  const value = option(options, key);
  if (value == null || value === true || String(value).trim() === "") {
    throw new Error(`Missing required option --${key}`);
  }
  return String(value);
}

export function parsePositiveInt(value, field) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`Invalid ${field}: ${value}`);
  }
  return parsed;
}

export function parseNonNegativeInt(value, field) {
  const parsed = Number.parseInt(String(value), 10);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`Invalid ${field}: ${value}`);
  }
  return parsed;
}

export async function* readNdjson(inputPath) {
  const input = inputPath ? createReadStream(inputPath, { encoding: "utf8" }) : process.stdin;
  const rl = readline.createInterface({ input, crlfDelay: Infinity });
  for await (const line of rl) {
    if (!line.trim()) {
      continue;
    }
    yield JSON.parse(line);
  }
}

export function openOutput(outputPath) {
  if (!outputPath) {
    return process.stdout;
  }
  return createWriteStream(outputPath, { encoding: "utf8" });
}

export async function flushWritable(stream) {
  if (stream === process.stdout) {
    return;
  }
  await new Promise((resolve, reject) => {
    stream.end((error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

export function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

export function basicAuthHeader(username, password) {
  if (!username) {
    return null;
  }
  const token = Buffer.from(`${username}:${password ?? ""}`, "utf8").toString("base64");
  return `Basic ${token}`;
}

export async function httpText(url, init = {}) {
  const response = await fetch(url, init);
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${body.slice(0, 500)}`);
  }
  return body;
}

export async function httpJson(url, init = {}) {
  const body = await httpText(url, init);
  return body.trim() === "" ? null : JSON.parse(body);
}

export function parseDurationToMs(value, field = "duration") {
  const text = String(value).trim();
  const match = text.match(/^(\d+)(ms|s|m|h|d)$/);
  if (!match) {
    throw new Error(`Invalid ${field}: ${value}`);
  }
  const amount = Number.parseInt(match[1], 10);
  const unit = match[2];
  switch (unit) {
    case "ms":
      return amount;
    case "s":
      return amount * 1000;
    case "m":
      return amount * 60_000;
    case "h":
      return amount * 3_600_000;
    case "d":
      return amount * 86_400_000;
    default:
      throw new Error(`Unsupported ${field} unit: ${value}`);
  }
}

export function percentile(values, ratio) {
  if (!Array.isArray(values) || values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 1) {
    return sorted[0];
  }
  const position = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * ratio) - 1));
  return sorted[position];
}

export function average(values) {
  if (!Array.isArray(values) || values.length === 0) {
    return null;
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

export function formatUtcTimestamp(millis) {
  return new Date(millis).toISOString().replace("T", " ").replace("Z", "").slice(0, 23);
}

export function toClickHouseDateTime64(value) {
  const date = new Date(normalizeTimestamp(value));
  if (Number.isNaN(date.getTime())) {
    throw new Error(`Invalid eventTime: ${value}`);
  }
  return date.toISOString().replace("T", " ").replace("Z", "").slice(0, 23);
}

function normalizeTimestamp(value) {
  const text = String(value).trim();
  if (text.includes("T")) {
    return text;
  }
  return `${text.replace(" ", "T")}Z`;
}
