#!/usr/bin/env node

import { readNdjson, parseArgs, option, parsePositiveInt, requireOption, httpText, toInfluxLine } from "./common.mjs";

const DEFAULT_ENDPOINT = process.env.INFLUXDB3_ENDPOINT || "http://localhost:8181";
const DEFAULT_DATABASE = process.env.INFLUXDB3_DATABASE;
const DEFAULT_TOKEN = process.env.INFLUXDB3_TOKEN || "";
const DEFAULT_MEASUREMENT = "ph_tx_outcome";
const DEFAULT_BATCH_SIZE = 5000;

function printUsage() {
  console.error(`Usage:
  node tools/tx-outcome-storage/import-influxdb3.mjs [--input <path>] [--database <name>] [--measurement <name>] [--batch-size <n>]

Env:
  INFLUXDB3_ENDPOINT   default: http://localhost:8181
  INFLUXDB3_DATABASE   required if --database is not provided
  INFLUXDB3_TOKEN      optional bearer token
`);
}

async function writeBatch(endpoint, database, token, payload) {
  const url = new URL(endpoint.endsWith("/") ? `${endpoint}api/v3/write_lp` : `${endpoint}/api/v3/write_lp`);
  url.searchParams.set("db", database);
  url.searchParams.set("precision", "ms");

  const headers = {
    "Content-Type": "text/plain; charset=utf-8"
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  await httpText(url, {
    method: "POST",
    headers,
    body: payload
  });
}

async function main() {
  const { options } = parseArgs(process.argv.slice(2));
  if (option(options, "help", false)) {
    printUsage();
    process.exit(0);
  }

  const endpoint = option(options, "endpoint", DEFAULT_ENDPOINT);
  const database = option(options, "database", DEFAULT_DATABASE);
  const measurement = option(options, "measurement", DEFAULT_MEASUREMENT);
  const token = option(options, "token", DEFAULT_TOKEN);
  const input = option(options, "input");
  const batchSize = parsePositiveInt(option(options, "batch-size", DEFAULT_BATCH_SIZE), "batch-size");

  if (!database) {
    requireOption(options, "database");
  }

  let batch = [];
  let total = 0;

  for await (const record of readNdjson(input)) {
    batch.push(toInfluxLine(record, measurement));
    if (batch.length < batchSize) {
      continue;
    }
    await writeBatch(endpoint, database, token, `${batch.join("\n")}\n`);
    total += batch.length;
    console.error(`import-influxdb3: imported ${total} rows`);
    batch = [];
  }

  if (batch.length > 0) {
    await writeBatch(endpoint, database, token, `${batch.join("\n")}\n`);
    total += batch.length;
    console.error(`import-influxdb3: imported ${total} rows`);
  }

  console.error(`import-influxdb3: done, total=${total}`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
});
