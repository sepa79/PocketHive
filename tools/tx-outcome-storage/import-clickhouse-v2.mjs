#!/usr/bin/env node

import { readNdjson, parseArgs, option, parsePositiveInt, httpText, toClickHouseDateTime64 } from "./common.mjs";

const DEFAULT_ENDPOINT = process.env.POCKETHIVE_CLICKHOUSE_HTTP_URL || "http://localhost:8123";
const DEFAULT_USERNAME = process.env.POCKETHIVE_CLICKHOUSE_USERNAME || "pockethive";
const DEFAULT_PASSWORD = process.env.POCKETHIVE_CLICKHOUSE_PASSWORD || "pockethive";
const DEFAULT_TABLE = "ph_tx_outcome_v2";
const DEFAULT_BATCH_SIZE = 5000;

function printUsage() {
  console.error(`Usage:
  node tools/tx-outcome-storage/import-clickhouse-v2.mjs [--input <path>] [--table <name>] [--batch-size <n>]

Reads NDJSON in the canonical TxOutcomeEvent shape and inserts it into ClickHouse using JSONEachRow.
`);
}

async function insertBatch(endpoint, username, password, table, batch) {
  const url = new URL(endpoint.endsWith("/") ? endpoint : `${endpoint}/`);
  url.searchParams.set("query", `INSERT INTO ${table} FORMAT JSONEachRow`);

  const headers = {
    "Content-Type": "application/json"
  };
  if (username) {
    headers.Authorization = `Basic ${Buffer.from(`${username}:${password ?? ""}`, "utf8").toString("base64")}`;
  }

  await httpText(url, {
    method: "POST",
    headers,
    body: `${batch.map((record) => JSON.stringify(normalizeRecord(record))).join("\n")}\n`
  });
}

function normalizeRecord(record) {
  return {
    ...record,
    eventTime: toClickHouseDateTime64(record.eventTime)
  };
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
  const input = option(options, "input");
  const batchSize = parsePositiveInt(option(options, "batch-size", DEFAULT_BATCH_SIZE), "batch-size");

  let batch = [];
  let total = 0;

  for await (const record of readNdjson(input)) {
    batch.push(record);
    if (batch.length < batchSize) {
      continue;
    }
    await insertBatch(endpoint, username, password, table, batch);
    total += batch.length;
    console.error(`import-clickhouse-v2: imported ${total} rows`);
    batch = [];
  }

  if (batch.length > 0) {
    await insertBatch(endpoint, username, password, table, batch);
    total += batch.length;
    console.error(`import-clickhouse-v2: imported ${total} rows`);
  }

  console.error(`import-clickhouse-v2: done, total=${total}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
});
