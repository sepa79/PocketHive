#!/usr/bin/env node

import { openOutput, flushWritable, parseArgs, option, parsePositiveInt, sqlString, httpText } from "./common.mjs";

const DEFAULT_ENDPOINT = process.env.POCKETHIVE_CLICKHOUSE_HTTP_URL || "http://localhost:8123";
const DEFAULT_USERNAME = process.env.POCKETHIVE_CLICKHOUSE_USERNAME || "pockethive";
const DEFAULT_PASSWORD = process.env.POCKETHIVE_CLICKHOUSE_PASSWORD || "pockethive";
const DEFAULT_TABLE = "ph_tx_outcome_v1";
const DEFAULT_BATCH_SIZE = 10000;

function printUsage() {
  console.error(`Usage:
  node tools/tx-outcome-storage/extract-clickhouse-v1.mjs [--from <iso>] [--to <iso>] [--batch-size <n>] [--out <path>]

Env:
  POCKETHIVE_CLICKHOUSE_HTTP_URL
  POCKETHIVE_CLICKHOUSE_USERNAME
  POCKETHIVE_CLICKHOUSE_PASSWORD
`);
}

function buildSelectSql(table, from, to, batchSize, cursor) {
  const filters = [];
  if (from) {
    filters.push(`eventTime >= parseDateTime64BestEffort(${sqlString(from)}, 3, 'UTC')`);
  }
  if (to) {
    filters.push(`eventTime < parseDateTime64BestEffort(${sqlString(to)}, 3, 'UTC')`);
  }
  if (cursor) {
    filters.push(
      `(eventTime, swarmId, callId, traceId, sinkInstance) > (` +
      `parseDateTime64BestEffort(${sqlString(cursor.eventTime)}, 3, 'UTC'), ` +
      `${sqlString(cursor.swarmId)}, ${sqlString(cursor.callId)}, ${sqlString(cursor.traceId)}, ${sqlString(cursor.sinkInstance)})`
    );
  }

  const whereClause = filters.length === 0 ? "" : `WHERE ${filters.join("\n  AND ")}`;
  return `SELECT
  formatDateTime(eventTime, '%Y-%m-%dT%H:%M:%S.%3fZ', 'UTC') AS eventTime,
  swarmId,
  sinkRole,
  sinkInstance,
  traceId,
  callId,
  processorStatus,
  processorSuccess,
  processorDurationMs,
  businessCode,
  businessSuccess,
  dimensions
FROM ${table}
${whereClause}
ORDER BY eventTime, swarmId, callId, traceId, sinkInstance
LIMIT ${batchSize}
FORMAT JSONEachRow`;
}

async function fetchBatch(endpoint, username, password, sql) {
  const url = new URL(endpoint.endsWith("/") ? endpoint : `${endpoint}/`);
  url.searchParams.set("query", sql);
  const headers = {};
  if (username) {
    headers.Authorization = `Basic ${Buffer.from(`${username}:${password ?? ""}`, "utf8").toString("base64")}`;
  }
  const body = await httpText(url, { headers });
  return body
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => JSON.parse(line));
}

async function main() {
  const { options } = parseArgs(process.argv.slice(2));
  if (option(options, "help", false)) {
    printUsage();
    process.exit(0);
  }

  const endpoint = option(options, "endpoint", DEFAULT_ENDPOINT);
  const username = option(options, "username", DEFAULT_USERNAME);
  const password = option(options, "password", DEFAULT_PASSWORD);
  const table = option(options, "table", DEFAULT_TABLE);
  const from = option(options, "from");
  const to = option(options, "to");
  const batchSize = parsePositiveInt(option(options, "batch-size", DEFAULT_BATCH_SIZE), "batch-size");
  const outPath = option(options, "out");
  const output = openOutput(outPath);

  let total = 0;
  let cursor = null;

  while (true) {
    const batch = await fetchBatch(endpoint, username, password, buildSelectSql(table, from, to, batchSize, cursor));
    if (batch.length === 0) {
      break;
    }

    for (const record of batch) {
      output.write(`${JSON.stringify(record)}\n`);
      total += 1;
    }

    const last = batch[batch.length - 1];
    cursor = {
      eventTime: last.eventTime,
      swarmId: last.swarmId ?? "",
      callId: last.callId ?? "",
      traceId: last.traceId ?? "",
      sinkInstance: last.sinkInstance ?? ""
    };

    console.error(`extract-clickhouse-v1: exported ${total} rows`);
    if (batch.length < batchSize) {
      break;
    }
  }

  await flushWritable(output);
  console.error(`extract-clickhouse-v1: done, total=${total}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
});
