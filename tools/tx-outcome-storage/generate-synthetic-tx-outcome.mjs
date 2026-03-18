#!/usr/bin/env node

import { openOutput, flushWritable, option, parseArgs, parseDurationToMs, parseNonNegativeInt, parsePositiveInt } from "./common.mjs";

const DEFAULT_DURATION_MINUTES = 30;
const DEFAULT_RATE_PER_SEC = 250;
const DEFAULT_HISTORY_WINDOW = "2d";
const DEFAULT_SWARMS = 8;
const DEFAULT_CALL_IDS = 24;
const DEFAULT_BUSINESS_CODES = 12;
const DEFAULT_SINK_INSTANCES = 3;
const DEFAULT_SEED = "tx-outcome-bench";

function printUsage() {
  console.error(`Usage:
  node tools/tx-outcome-storage/generate-synthetic-tx-outcome.mjs [--out <path>] [--manifest <path>]
    [--rows <n> | --rate-per-sec <n> --duration-minutes <n>]
    [--history-window <duration>] [--swarms <n>] [--call-ids <n>] [--business-codes <n>]
    [--sink-instances <n>] [--seed <text>] [--reference-time <iso>]

Examples:
  node tools/tx-outcome-storage/generate-synthetic-tx-outcome.mjs \\
    --out /tmp/tx-outcome.ndjson \\
    --manifest /tmp/tx-outcome-manifest.json \\
    --rate-per-sec 1000 \\
    --duration-minutes 30 \\
    --history-window 30d
`);
}

async function main() {
  const { options } = parseArgs(process.argv.slice(2));
  if (option(options, "help", false)) {
    printUsage();
    process.exit(0);
  }

  const outPath = option(options, "out");
  const manifestPath = option(options, "manifest");
  const durationMinutes = parsePositiveInt(option(options, "duration-minutes", DEFAULT_DURATION_MINUTES), "duration-minutes");
  const ratePerSec = parsePositiveInt(option(options, "rate-per-sec", DEFAULT_RATE_PER_SEC), "rate-per-sec");
  const totalRows = options.has("rows")
    ? parseNonNegativeInt(option(options, "rows"), "rows")
    : durationMinutes * 60 * ratePerSec;
  const historyWindow = option(options, "history-window", DEFAULT_HISTORY_WINDOW);
  const swarms = parsePositiveInt(option(options, "swarms", DEFAULT_SWARMS), "swarms");
  const callIdsCount = parsePositiveInt(option(options, "call-ids", DEFAULT_CALL_IDS), "call-ids");
  const businessCodesCount = parsePositiveInt(option(options, "business-codes", DEFAULT_BUSINESS_CODES), "business-codes");
  const sinkInstancesCount = parsePositiveInt(option(options, "sink-instances", DEFAULT_SINK_INSTANCES), "sink-instances");
  const seedText = String(option(options, "seed", DEFAULT_SEED));
  const referenceTime = option(options, "reference-time", new Date().toISOString());
  const referenceTimeMs = Date.parse(referenceTime);

  if (Number.isNaN(referenceTimeMs)) {
    throw new Error(`Invalid reference-time: ${referenceTime}`);
  }

  const historyWindowMs = parseDurationToMs(historyWindow, "history-window");
  const startTimeMs = referenceTimeMs - historyWindowMs;
  const output = openOutput(outPath);
  const manifest = buildManifest({
    seedText,
    totalRows,
    historyWindow,
    durationMinutes,
    ratePerSec,
    swarms,
    callIdsCount,
    businessCodesCount,
    sinkInstancesCount,
    referenceTime: new Date(referenceTimeMs).toISOString(),
    startTime: new Date(startTimeMs).toISOString()
  });
  const random = mulberry32(hashSeed(seedText));
  const slotWidthMs = Math.max(1, Math.floor(historyWindowMs / Math.max(1, totalRows)));

  for (let i = 0; i < totalRows; i += 1) {
    const slotStart = startTimeMs + Math.floor((historyWindowMs * i) / Math.max(1, totalRows));
    const eventTimeMs = Math.min(referenceTimeMs - 1, slotStart + Math.floor(random() * slotWidthMs));
    const swarmId = weightedPick(manifest.swarmIds, random, zipfWeights(manifest.swarmIds.length, 1.12));
    const callId = weightedPick(manifest.callIds, random, zipfWeights(manifest.callIds.length, 1.08));
    const businessCode = chooseBusinessCode(manifest.businessCodes, callId, random);
    const processorStatus = chooseProcessorStatus(random);
    const processorSuccess = processorStatus >= 200 && processorStatus < 300 ? 1 : 0;
    const businessSuccess = processorSuccess === 1 && businessCode !== "" ? 1 : 0;
    const record = {
      eventTime: new Date(eventTimeMs).toISOString(),
      swarmId,
      sinkRole: "postprocessor",
      sinkInstance: `${swarmId}-post-${1 + Math.floor(random() * sinkInstancesCount)}`,
      traceId: makeTraceId(seedText, i),
      callId,
      processorStatus,
      processorSuccess,
      processorDurationMs: chooseDurationMs(processorStatus, random),
      businessCode,
      businessSuccess,
      dimensions: {
        tenant: `tenant-${1 + Math.floor(random() * 6)}`,
        region: ["eu-west", "eu-central", "us-east"][Math.floor(random() * 3)],
        lane: ["retail", "partner", "backoffice"][Math.floor(random() * 3)],
        channel: callId.startsWith("http") ? "http" : "mq"
      }
    };
    output.write(`${JSON.stringify(record)}\n`);
  }

  await flushWritable(output);

  if (manifestPath) {
    const fs = await import("node:fs/promises");
    await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2) + "\n", "utf8");
  }

  console.error(`generate-synthetic-tx-outcome: rows=${totalRows} swarms=${swarms} callIds=${callIdsCount} businessCodes=${businessCodesCount} historyWindow=${historyWindow}`);
}

function buildManifest(base) {
  const swarmIds = Array.from({ length: base.swarms }, (_, index) => `swarm-${String(index + 1).padStart(2, "0")}`);
  const callIds = Array.from({ length: base.callIdsCount }, (_, index) => {
    const prefix = index % 3 === 0 ? "http-payments" : index % 3 === 1 ? "http-status" : "mq-clearing";
    return `${prefix}-${String(index + 1).padStart(2, "0")}`;
  });
  const businessCodes = [
    "approved",
    "captured",
    "settled",
    "queued",
    "retry_scheduled",
    "timeout_upstream",
    "validation_error",
    "rejected",
    "duplicate",
    "downstream_unavailable",
    "limit_exceeded",
    "pending_review"
  ].slice(0, base.businessCodesCount);

  return {
    ...base,
    swarmIds,
    callIds,
    businessCodes,
    hotCallIds: callIds.slice(0, Math.min(3, callIds.length)),
    hotBusinessCodes: businessCodes.filter((value) => value !== "").slice(0, Math.min(3, businessCodes.length))
  };
}

function chooseBusinessCode(businessCodes, callId, random) {
  if (random() < 0.07) {
    return "";
  }
  if (callId.startsWith("http-status")) {
    return weightedPick(
        ["approved", "queued", "pending_review", "validation_error", "duplicate"],
        random,
        [45, 20, 12, 15, 8]
    );
  }
  if (callId.startsWith("mq-clearing")) {
    return weightedPick(
        ["settled", "captured", "queued", "timeout_upstream", "downstream_unavailable"],
        random,
        [30, 28, 18, 12, 12]
    );
  }
  return weightedPick(businessCodes, random, zipfWeights(businessCodes.length, 1.05));
}

function chooseProcessorStatus(random) {
  const roll = random();
  if (roll < 0.83) {
    return weightedPick([200, 201, 202, 204], random, [60, 20, 12, 8]);
  }
  if (roll < 0.93) {
    return weightedPick([400, 401, 404, 409, 422, 429], random, [14, 5, 12, 18, 31, 20]);
  }
  return weightedPick([500, 502, 503, 504], random, [20, 18, 44, 18]);
}

function chooseDurationMs(status, random) {
  if (status >= 500) {
    return 250 + Math.floor(Math.pow(random(), 0.28) * 9_000);
  }
  if (status >= 400) {
    return 20 + Math.floor(Math.pow(random(), 0.65) * 1_500);
  }
  if (random() < 0.02) {
    return 1_500 + Math.floor(Math.pow(random(), 0.35) * 8_000);
  }
  return 25 + Math.floor(Math.pow(random(), 0.55) * 1_200);
}

function zipfWeights(length, exponent) {
  return Array.from({ length }, (_, index) => 1 / Math.pow(index + 1, exponent));
}

function weightedPick(values, random, weights) {
  const total = weights.reduce((sum, value) => sum + value, 0);
  let threshold = random() * total;
  for (let i = 0; i < values.length; i += 1) {
    threshold -= weights[i];
    if (threshold <= 0) {
      return values[i];
    }
  }
  return values[values.length - 1];
}

function hashSeed(value) {
  let h = 1779033703 ^ value.length;
  for (let i = 0; i < value.length; i += 1) {
    h = Math.imul(h ^ value.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return () => {
    h = Math.imul(h ^ (h >>> 16), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    return (h ^= h >>> 16) >>> 0;
  };
}

function mulberry32(seedFactory) {
  let a = seedFactory();
  return () => {
    a |= 0;
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function makeTraceId(seedText, index) {
  return `${seedText.replace(/[^a-zA-Z0-9]+/g, "").slice(0, 8)}-${index.toString(16).padStart(12, "0")}`;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
});
