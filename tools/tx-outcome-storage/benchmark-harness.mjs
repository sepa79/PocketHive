#!/usr/bin/env node

import { readFile, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import path from "node:path";
import { spawn } from "node:child_process";
import { average, httpText, option, parseArgs, parsePositiveInt, percentile } from "./common.mjs";
import { buildBenchmarkQueries, buildBenchmarkScenarios, defaultBenchmarkWindows } from "./benchmark-suite.mjs";

const CH_ENDPOINT = process.env.POCKETHIVE_CLICKHOUSE_HTTP_URL || "http://localhost:8123";
const CH_USERNAME = process.env.POCKETHIVE_CLICKHOUSE_USERNAME || "pockethive";
const CH_PASSWORD = process.env.POCKETHIVE_CLICKHOUSE_PASSWORD || "pockethive";
const CH_TABLE = "ph_tx_outcome_bench";

const INFLUX_ENDPOINT = process.env.INFLUXDB3_ENDPOINT || "http://localhost:8181";
const INFLUX_DATABASE = process.env.INFLUXDB3_DATABASE || "pockethive";
const INFLUX_TOKEN = process.env.INFLUXDB3_TOKEN || "apiv3_local_dev_replace_me";
const INFLUX_CONTAINER = process.env.POCKETHIVE_INFLUXDB3_CONTAINER || "pockethive-influxdb3-1";

function printUsage() {
  console.error(`Usage:
  node tools/tx-outcome-storage/benchmark-harness.mjs [options]

Options:
  --out-dir <path>              Output directory for corpus + report (default: .local-benchmarks/tx-outcome-<ts>)
  --rows <n>                    Override generated row count
  --rate-per-sec <n>            Synthetic throughput baseline (default: 250)
  --duration-minutes <n>        Synthetic generation duration baseline (default: 30)
  --history-window <duration>   Spread timestamps across this window (default: 2d)
  --windows <csv>               Benchmark windows, e.g. 3h,2d,30d
  --repeats <n>                 Warm repetitions per query/storage (default: 3)
  --seed <text>                 PRNG seed
  --capture-influx-server-timing  Parse local InfluxDB 3 container logs for plan/execute/end-to-end timings
  --skip-compose                Assume ClickHouse + InfluxDB 3 are already running
  --keep-corpus                 Keep generated NDJSON + manifest in output dir
  --report <path>               Explicit report JSON path
  --markdown <path>             Explicit markdown report path

Example:
  node tools/tx-outcome-storage/benchmark-harness.mjs \\
    --rate-per-sec 500 \\
    --duration-minutes 30 \\
    --history-window 30d \\
    --repeats 5
`);
}

async function main() {
  const { options } = parseArgs(process.argv.slice(2));
  if (option(options, "help", false)) {
    printUsage();
    process.exit(0);
  }

  const ratePerSec = parsePositiveInt(option(options, "rate-per-sec", 250), "rate-per-sec");
  const durationMinutes = parsePositiveInt(option(options, "duration-minutes", 30), "duration-minutes");
  const historyWindow = option(options, "history-window", "2d");
  const repeats = parsePositiveInt(option(options, "repeats", 3), "repeats");
  const seed = option(options, "seed", "tx-outcome-bench");
  const captureInfluxServerTiming = option(options, "capture-influx-server-timing", false) === true;
  const skipCompose = option(options, "skip-compose", false) === true;
  const keepCorpus = option(options, "keep-corpus", false) === true;
  const defaultOutDir = path.join("/home/sepa/PocketHive/.local-benchmarks", `tx-outcome-${Date.now()}`);
  const tempDir = option(options, "out-dir", defaultOutDir);
  const ndjsonPath = path.join(tempDir, "tx-outcome.ndjson");
  const manifestPath = path.join(tempDir, "tx-outcome-manifest.json");
  const rows = options.has("rows")
    ? parsePositiveInt(option(options, "rows"), "rows")
    : ratePerSec * durationMinutes * 60;
  const windows = String(option(options, "windows", defaultBenchmarkWindows(historyWindow).join(",")))
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const reportPath = option(options, "report", path.join(tempDir, "benchmark-report.json"));
  const markdownPath = option(options, "markdown", path.join(tempDir, "benchmark-report.md"));
  const measurement = `ph_tx_outcome_bench_${Date.now()}`;

  try {
    await (await import("node:fs/promises")).mkdir(tempDir, { recursive: true });

    if (!skipCompose) {
      await runCommand("docker", ["compose", "up", "-d", "clickhouse", "influxdb3", "influxdb3-init"]);
      await waitForInfluxInit();
    }

    await ensureClickHouseBenchmarkTable();
    await truncateClickHouseBenchmarkTable();

    await runCommand(process.execPath, [
      "tools/tx-outcome-storage/generate-synthetic-tx-outcome.mjs",
      "--out", ndjsonPath,
      "--manifest", manifestPath,
      "--rows", String(rows),
      "--rate-per-sec", String(ratePerSec),
      "--duration-minutes", String(durationMinutes),
      "--history-window", historyWindow,
      "--seed", seed
    ]);

    await runCommand(process.execPath, [
      "tools/tx-outcome-storage/import-clickhouse-v2.mjs",
      "--input", ndjsonPath,
      "--table", CH_TABLE
    ]);

    await runCommand(process.execPath, [
      "tools/tx-outcome-storage/import-influxdb3.mjs",
      "--input", ndjsonPath,
      "--endpoint", INFLUX_ENDPOINT,
      "--database", INFLUX_DATABASE,
      "--measurement", measurement,
      "--token", INFLUX_TOKEN
    ]);

    const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
    const scenarios = buildBenchmarkScenarios(manifest);
    const queryResults = [];

    for (const window of windows) {
      for (const scenario of scenarios) {
        const queries = buildBenchmarkQueries({
          clickhouseTable: CH_TABLE,
          influxMeasurement: measurement,
          window,
          scenario,
          referenceTime: manifest.referenceTime
        });
        for (const query of queries) {
          queryResults.push(await benchmarkQuery("clickhouse", query, repeats, window, scenario, (sql) => runClickHouseQuery(sql)));
          queryResults.push(await benchmarkQuery("influxdb3", query, repeats, window, scenario, (sql) => runInfluxQuery(sql, INFLUX_DATABASE, captureInfluxServerTiming)));
        }
      }
    }

    const report = {
      generatedAt: new Date().toISOString(),
      dataset: {
        rows,
        ratePerSec,
        durationMinutes,
        historyWindow,
        measurement,
        clickhouseTable: CH_TABLE,
        manifest
      },
      benchmark: summarizeResults(queryResults)
    };

    await writeFile(reportPath, JSON.stringify(report, null, 2) + "\n", "utf8");
    await writeFile(markdownPath, renderMarkdownReport(report), "utf8");
    console.log(JSON.stringify({
      reportPath,
      markdownPath,
      rows,
      measurement
    }, null, 2));
  } finally {
    if (!keepCorpus) {
      await rm(ndjsonPath, { force: true });
      await rm(manifestPath, { force: true });
    }
  }
}

async function benchmarkQuery(storage, query, repeats, window, scenario, runner) {
  const sql = storage === "clickhouse" ? query.clickhouseSql : query.influxSql;
  let cold;
  try {
    cold = await runner(sql);
  } catch (error) {
    return failedBenchmarkResult(storage, query, window, scenario, error);
  }
  const warmRuns = [];
  for (let i = 0; i < repeats; i += 1) {
    try {
      warmRuns.push(await runner(sql));
    } catch (error) {
      return failedBenchmarkResult(storage, query, window, scenario, error, cold, warmRuns);
    }
  }
  return {
    storage,
    id: query.id,
    label: query.label,
    window,
    scenarioId: scenario.id,
    scenarioLabel: scenario.label,
    cold,
    warmRuns,
    rowsReturned: cold.rowsReturned
  };
}

function summarizeResults(results) {
  return results.map((entry) => ({
    storage: entry.storage,
    id: entry.id,
    label: entry.label,
    window: entry.window,
    scenarioId: entry.scenarioId,
    scenarioLabel: entry.scenarioLabel,
    rowsReturned: entry.rowsReturned,
    client: summarizeTimingRuns(entry.cold, entry.warmRuns, "client"),
    server: summarizeTimingRuns(entry.cold, entry.warmRuns, "server"),
    error: entry.error ?? null
  }));
}

function renderMarkdownReport(report) {
  const lines = [];
  lines.push("# Tx Outcome Benchmark Report");
  lines.push("");
  lines.push(`- Generated: ${report.generatedAt}`);
  lines.push(`- Rows: ${report.dataset.rows}`);
  lines.push(`- Baseline throughput: ${report.dataset.ratePerSec}/s for ${report.dataset.durationMinutes}m`);
  lines.push(`- Timestamp history window: ${report.dataset.historyWindow}`);
  lines.push(`- ClickHouse table: ${report.dataset.clickhouseTable}`);
  lines.push(`- InfluxDB measurement: ${report.dataset.measurement}`);
  lines.push("");
  lines.push("| Storage | Window | Scenario | Query | Cold first byte ms | Cold total ms | Warm p95 first byte ms | Warm p95 total ms | Warm p95 server end-to-end ms | Rows | Error |");
  lines.push("|---|---|---|---|---:|---:|---:|---:|---:|---:|---|");
  for (const row of report.benchmark) {
    lines.push(`| ${row.storage} | ${row.window} | ${row.scenarioId} | ${row.id} | ${row.client?.cold?.firstByteMs ?? ""} | ${row.client?.cold?.totalMs ?? ""} | ${row.client?.warmP95?.firstByteMs ?? ""} | ${row.client?.warmP95?.totalMs ?? ""} | ${row.server?.warmP95?.endToEndMs ?? ""} | ${row.rowsReturned ?? ""} | ${markdownCell(row.error?.message)} |`);
  }
  lines.push("");
  return `${lines.join("\n")}\n`;
}

async function ensureClickHouseBenchmarkTable() {
  const schemaSql = await readFile("/home/sepa/PocketHive/clickhouse/init/02-ph-tx-outcome-v2.sql", "utf8");
  await runClickHouseCommand(schemaSql);
  await runClickHouseCommand(`CREATE TABLE IF NOT EXISTS ${CH_TABLE} AS ph_tx_outcome_v2`);
}

async function truncateClickHouseBenchmarkTable() {
  await runClickHouseCommand(`TRUNCATE TABLE ${CH_TABLE}`);
}

async function runClickHouseCommand(sql) {
  const url = new URL(CH_ENDPOINT.endsWith("/") ? CH_ENDPOINT : `${CH_ENDPOINT}/`);
  const headers = {};
  if (CH_USERNAME) {
    headers.Authorization = `Basic ${Buffer.from(`${CH_USERNAME}:${CH_PASSWORD}`, "utf8").toString("base64")}`;
  }
  headers["Content-Type"] = "text/plain; charset=utf-8";
  await httpText(url, { method: "POST", headers, body: sql });
}

async function runClickHouseQuery(sql) {
  const normalizedSql = normalizeSql(sql);
  const url = new URL(CH_ENDPOINT.endsWith("/") ? CH_ENDPOINT : `${CH_ENDPOINT}/`);
  url.searchParams.set("query", `${normalizedSql}\nFORMAT JSONEachRow`);
  const headers = {};
  if (CH_USERNAME) {
    headers.Authorization = `Basic ${Buffer.from(`${CH_USERNAME}:${CH_PASSWORD}`, "utf8").toString("base64")}`;
  }
  try {
    const response = await httpTextDetailed(url, { headers });
    return {
      rowsReturned: countResultRows(response.body),
      client: {
        headersMs: response.timeToHeadersMs,
        firstByteMs: response.timeToFirstByteMs,
        totalMs: response.totalMs
      },
      server: null
    };
  } catch (error) {
    attachQueryResult(error, null);
    throw error;
  }
}

async function runInfluxQuery(sql, database, captureServerTiming) {
  const normalizedSql = normalizeSql(sql);
  const startedAtIso = new Date(Date.now() - 1000).toISOString();
  const url = new URL(INFLUX_ENDPOINT.endsWith("/") ? `${INFLUX_ENDPOINT}api/v3/query_sql` : `${INFLUX_ENDPOINT}/api/v3/query_sql`);
  url.searchParams.set("db", database);
  url.searchParams.set("q", normalizedSql);
  url.searchParams.set("format", "jsonl");
  const headers = {
    Authorization: `Bearer ${INFLUX_TOKEN}`
  };
  try {
    const response = await httpTextDetailed(url, { headers });
    return {
      rowsReturned: countResultRows(response.body),
      client: {
        headersMs: response.timeToHeadersMs,
        firstByteMs: response.timeToFirstByteMs,
        totalMs: response.totalMs
      },
      server: captureServerTiming ? await readInfluxServerTiming(startedAtIso, normalizedSql) : null
    };
  } catch (error) {
    attachQueryResult(
      error,
      captureServerTiming ? await readInfluxServerTiming(startedAtIso, normalizedSql) : null
    );
    throw error;
  }
}

function countResultRows(body) {
  return body
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .length;
}

function summarizeTimingRuns(cold, warmRuns, field) {
  const coldTiming = cold[field];
  const warmTimings = warmRuns
    .map((run) => run[field])
    .filter((value) => value != null);
  if (coldTiming == null && warmTimings.length === 0) {
    return null;
  }
  return {
    cold: coldTiming == null ? null : roundTiming(coldTiming),
    warmAvg: summarizeTimingBucket(warmTimings, average),
    warmP50: summarizeTimingBucket(warmTimings, (values) => percentile(values, 0.50)),
    warmP95: summarizeTimingBucket(warmTimings, (values) => percentile(values, 0.95))
  };
}

function failedBenchmarkResult(storage, query, window, scenario, error, cold = null, warmRuns = []) {
  const queryResult = error?.queryResult ?? {};
  return {
    storage,
    id: query.id,
    label: query.label,
    window,
    scenarioId: scenario.id,
    scenarioLabel: scenario.label,
    cold: cold ?? (queryResult.client != null || queryResult.server != null ? queryResult : null),
    warmRuns,
    rowsReturned: cold?.rowsReturned ?? queryResult.rowsReturned ?? null,
    error: {
      stage: cold == null ? "cold" : "warm",
      message: compactErrorMessage(error)
    }
  };
}

function summarizeTimingBucket(timings, aggregate) {
  if (!Array.isArray(timings) || timings.length === 0) {
    return null;
  }
  const keys = Object.keys(timings[0]);
  const result = {};
  for (const key of keys) {
    const values = timings
      .map((entry) => entry[key])
      .filter((value) => value != null);
    result[key] = values.length === 0 ? null : round(aggregate(values));
  }
  return result;
}

function roundTiming(timing) {
  return Object.fromEntries(Object.entries(timing).map(([key, value]) => [key, round(value)]));
}

function normalizeSql(sql) {
  return String(sql).replace(/\s+/g, " ").trim();
}

async function httpTextDetailed(url, init = {}) {
  const target = typeof url === "string" ? new URL(url) : url;
  const client = target.protocol === "https:" ? https : http;
  const startedAt = process.hrtime.bigint();

  return await new Promise((resolve, reject) => {
    const request = client.request(target, {
      method: init.method ?? "GET",
      headers: init.headers ?? {}
    }, (response) => {
      const headersAt = process.hrtime.bigint();
      const chunks = [];
      let firstByteAt = null;

      response.on("data", (chunk) => {
        if (firstByteAt == null) {
          firstByteAt = process.hrtime.bigint();
        }
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      });
      response.on("end", () => {
        const endedAt = process.hrtime.bigint();
        const body = Buffer.concat(chunks).toString("utf8");
        const statusCode = response.statusCode ?? 0;
        if (statusCode < 200 || statusCode >= 300) {
          const error = new Error(`HTTP ${statusCode}: ${body.slice(0, 500)}`);
          error.queryResult = {
            rowsReturned: countResultRows(body),
            client: {
              headersMs: nanosToMillis(headersAt - startedAt),
              firstByteMs: nanosToMillis((firstByteAt ?? headersAt) - startedAt),
              totalMs: nanosToMillis(endedAt - startedAt)
            },
            server: null
          };
          reject(error);
          return;
        }
        resolve({
          body,
          statusCode,
          headers: response.headers,
          timeToHeadersMs: nanosToMillis(headersAt - startedAt),
          timeToFirstByteMs: nanosToMillis((firstByteAt ?? headersAt) - startedAt),
          totalMs: nanosToMillis(endedAt - startedAt)
        });
      });
    });

    request.on("error", reject);
    request.setTimeout(60_000, () => {
      request.destroy(new Error(`HTTP request timed out after 60000 ms: ${target}`));
    });

    if (init.body != null) {
      request.write(init.body);
    }
    request.end();
  });
}

function nanosToMillis(nanos) {
  return Number(nanos) / 1_000_000;
}

async function readInfluxServerTiming(sinceIso, normalizedSql) {
  try {
    const logs = await spawnText("docker", ["logs", "--since", sinceIso, INFLUX_CONTAINER]);
    const lines = logs
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line.includes('query when="success"') || line.includes('query when="fail"'));
    const exact = [...lines].reverse().find((line) => line.includes(`query_text=${normalizedSql}`));
    const fallback = exact ?? lines.at(-1);
    return fallback ? parseInfluxSuccessTiming(fallback) : null;
  } catch {
    return null;
  }
}

function parseInfluxSuccessTiming(line) {
  return {
    status: line.includes('query when="fail"') ? "fail" : "success",
    planMs: secondsMetric(line, "plan_duration_secs"),
    permitMs: secondsMetric(line, "permit_duration_secs"),
    executeMs: secondsMetric(line, "execute_duration_secs"),
    endToEndMs: secondsMetric(line, "end2end_duration_secs"),
    computeMs: secondsMetric(line, "compute_duration_secs")
  };
}

function secondsMetric(line, key) {
  const match = line.match(new RegExp(`${key}=([0-9.e+-]+)`));
  if (!match) {
    return null;
  }
  return Number.parseFloat(match[1]) * 1000;
}

function attachQueryResult(error, serverTiming) {
  if (!(error instanceof Error)) {
    return;
  }
  const base = error.queryResult ?? {};
  error.queryResult = {
    rowsReturned: base.rowsReturned ?? null,
    client: base.client ?? null,
    server: serverTiming ?? base.server ?? null
  };
}

function compactErrorMessage(error) {
  const message = error instanceof Error ? error.message : String(error);
  return message.replace(/\s+/g, " ").trim();
}

function markdownCell(value) {
  if (value == null || value === "") {
    return "";
  }
  return String(value).replaceAll("|", "\\|");
}

async function waitForInfluxInit() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const result = await spawnText("docker", ["compose", "logs", "--no-color", "influxdb3-init"]);
    if (result.includes("created successfully") || result.includes("already exists")) {
      return;
    }
    await sleep(1000);
  }
  throw new Error("Timed out waiting for influxdb3-init");
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runCommand(command, args) {
  const child = spawn(command, args, {
    cwd: "/home/sepa/PocketHive",
    stdio: "inherit"
  });
  const exitCode = await new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("exit", resolve);
  });
  if (exitCode !== 0) {
    throw new Error(`Command failed (${exitCode}): ${command} ${args.join(" ")}`);
  }
}

async function spawnText(command, args) {
  const child = spawn(command, args, {
    cwd: "/home/sepa/PocketHive",
    stdio: ["ignore", "pipe", "pipe"]
  });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (chunk) => {
    stdout += chunk.toString("utf8");
  });
  child.stderr.on("data", (chunk) => {
    stderr += chunk.toString("utf8");
  });
  const exitCode = await new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("exit", resolve);
  });
  if (exitCode !== 0) {
    throw new Error(`Command failed (${exitCode}): ${command} ${args.join(" ")}\n${stderr}`);
  }
  return stdout;
}

function round(value) {
  return value == null ? null : Math.round(value * 100) / 100;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exit(1);
});
