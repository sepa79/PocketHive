import { normalizedBusinessCodeKey, normalizedCallIdKey, parseDurationToMs } from "./common.mjs";

export function defaultBenchmarkWindows(historyWindow) {
  const historyMs = parseDurationToMs(historyWindow, "history-window");
  const windows = ["3h", "2d"];
  if (historyMs >= parseDurationToMs("30d")) {
    windows.push("30d");
  } else {
    windows.push(historyWindow);
  }
  return windows;
}

export function buildBenchmarkScenarios(manifest) {
  const firstSwarm = manifest.swarmIds[0];
  const hotCallId = manifest.hotCallIds[0];
  const hotBusinessCode = manifest.hotBusinessCodes[0];
  return [
    {
      id: "all-swarms",
      label: "All swarms",
      swarms: null,
      callIds: null,
      businessCodes: null
    },
    {
      id: "single-swarm",
      label: `Single swarm (${firstSwarm})`,
      swarms: [firstSwarm],
      callIds: null,
      businessCodes: null
    },
    {
      id: "focused-segment",
      label: `${firstSwarm} / ${hotCallId} / ${hotBusinessCode}`,
      swarms: [firstSwarm],
      callIds: [hotCallId],
      businessCodes: [hotBusinessCode]
    }
  ];
}

export function buildBenchmarkQueries({
  clickhouseTable,
  influxMeasurement,
  window,
  scenario,
  referenceTime
}) {
  const windowMs = parseDurationToMs(window, "window");
  const toMs = Date.parse(referenceTime);
  const fromMs = toMs - windowMs;
  const intervalSeconds = timeBucketSeconds(windowMs);

  const clickhouseWhere = clickhouseFilters({
    fromMs,
    toMs,
    swarms: scenario.swarms,
    callIds: scenario.callIds,
    businessCodes: scenario.businessCodes
  });
  const influxWhere = influxFilters({
    fromMs,
    toMs,
    swarms: scenario.swarms,
    callIds: scenario.callIds,
    businessCodes: scenario.businessCodes
  });

  return [
    {
      id: "variable-swarms",
      label: "Variable values: swarmId",
      clickhouseSql: `SELECT DISTINCT swarmId
FROM ${clickhouseTable}
WHERE eventTime >= toDateTime64('${toClickHouseDateTime(fromMs)}', 3, 'UTC')
  AND eventTime < toDateTime64('${toClickHouseDateTime(toMs)}', 3, 'UTC')
ORDER BY swarmId`,
      influxSql: `SELECT DISTINCT "swarmId"
FROM "${influxMeasurement}"
WHERE time >= timestamp '${toInfluxDateTime(fromMs)}'
  AND time < timestamp '${toInfluxDateTime(toMs)}'
ORDER BY "swarmId"`
    },
    {
      id: "variable-call-ids",
      label: "Variable values: callIdKey",
      clickhouseSql: `SELECT DISTINCT callIdKey
FROM ${clickhouseTable}
${clickhouseWhere}
  AND callIdKey != 'unknown'
ORDER BY callIdKey
LIMIT 500`,
      influxSql: `SELECT DISTINCT "callIdKey"
FROM "${influxMeasurement}"
${influxWhere}
  AND "callIdKey" <> 'unknown'
ORDER BY "callIdKey"
LIMIT 500`
    },
    {
      id: "variable-business-codes",
      label: "Variable values: businessCodeKey",
      clickhouseSql: `SELECT DISTINCT businessCodeKey
FROM ${clickhouseTable}
${clickhouseWhere}
  AND businessCodeKey != 'n/a'
ORDER BY businessCodeKey
LIMIT 500`,
      influxSql: `SELECT DISTINCT "businessCodeKey"
FROM "${influxMeasurement}"
${influxWhere}
  AND "businessCodeKey" <> 'n/a'
ORDER BY "businessCodeKey"
LIMIT 500`
    },
    {
      id: "tx-volume-timeseries",
      label: "Tx volume by swarm/call/businessCode",
      clickhouseSql: `SELECT
  toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  count() AS txns
FROM ${clickhouseTable}
${clickhouseWhere}
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series`,
      influxSql: `SELECT
  date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  count(*) AS "txns"
FROM "${influxMeasurement}"
${influxWhere}
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2`
    },
    {
      id: "processor-p95-timeseries",
      label: "Processor duration p95",
      clickhouseSql: `SELECT
  toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey) AS series,
  quantileTDigest(0.95)(processorDurationMs) AS p95_duration_ms
FROM ${clickhouseTable}
${clickhouseWhere}
GROUP BY time, swarmId, callIdKey, businessCodeKey
ORDER BY time, series`,
      influxSql: `SELECT
  date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey") AS "series",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_duration_ms"
FROM "${influxMeasurement}"
${influxWhere}
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
ORDER BY 1, 2`
    },
    {
      id: "latency-percentiles",
      label: "RTT percentiles p50/p95/p99",
      clickhouseSql: `SELECT time, series, value_ms
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p50') AS series,
    quantileTDigest(0.50)(processorDurationMs) AS value_ms
  FROM ${clickhouseTable}
  ${clickhouseWhere}
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p95') AS series,
    quantileTDigest(0.95)(processorDurationMs) AS value_ms
  FROM ${clickhouseTable}
  ${clickhouseWhere}
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | p99') AS series,
    quantileTDigest(0.99)(processorDurationMs) AS value_ms
  FROM ${clickhouseTable}
  ${clickhouseWhere}
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series`,
      influxSql: `SELECT "time", "series", "value_ms"
FROM (
  SELECT
    date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p50') AS "series",
    approx_percentile_cont(0.50) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "${influxMeasurement}"
  ${influxWhere}
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p95') AS "series",
    approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "${influxMeasurement}"
  ${influxWhere}
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | p99') AS "series",
    approx_percentile_cont(0.99) WITHIN GROUP (ORDER BY "processorDurationMs") AS "value_ms"
  FROM "${influxMeasurement}"
  ${influxWhere}
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2`
    },
    {
      id: "tail-breach-rate",
      label: "Tail breach rate by SLO threshold",
      clickhouseSql: `SELECT time, series, pct
FROM (
  SELECT
    toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >1s') AS series,
    avg(if(processorDurationMs > 1000, 1, 0)) * 100 AS pct
  FROM ${clickhouseTable}
  ${clickhouseWhere}
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >2s') AS series,
    avg(if(processorDurationMs > 2000, 1, 0)) * 100 AS pct
  FROM ${clickhouseTable}
  ${clickhouseWhere}
  GROUP BY time, swarmId, callIdKey, businessCodeKey
  UNION ALL
  SELECT
    toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
    concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | >5s') AS series,
    avg(if(processorDurationMs > 5000, 1, 0)) * 100 AS pct
  FROM ${clickhouseTable}
  ${clickhouseWhere}
  GROUP BY time, swarmId, callIdKey, businessCodeKey
)
ORDER BY time, series`,
      influxSql: `SELECT "time", "series", "pct"
FROM (
  SELECT
    date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >1s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 1000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "${influxMeasurement}"
  ${influxWhere}
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >2s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 2000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "${influxMeasurement}"
  ${influxWhere}
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
  UNION ALL
  SELECT
    date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
    concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | >5s') AS "series",
    avg(CASE WHEN "processorDurationMs" > 5000 THEN 1 ELSE 0 END) * 100 AS "pct"
  FROM "${influxMeasurement}"
  ${influxWhere}
  GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey"
)
ORDER BY 1, 2`
    },
    {
      id: "status-mix",
      label: "Processor status class mix",
      clickhouseSql: `SELECT
  toStartOfInterval(eventTime, INTERVAL ${intervalSeconds} SECOND) AS time,
  concat(swarmId, ' | ', callIdKey, ' | ', businessCodeKey, ' | ', processorStatusClass) AS series,
  count() AS txns
FROM ${clickhouseTable}
${clickhouseWhere}
GROUP BY time, swarmId, callIdKey, businessCodeKey, processorStatusClass
ORDER BY time, series`,
      influxSql: `SELECT
  date_bin(interval '${intervalSeconds} seconds', time, timestamp '1970-01-01T00:00:00Z') AS "time",
  concat("swarmId", ' | ', "callIdKey", ' | ', "businessCodeKey", ' | ', "processorStatusClass") AS "series",
  count(*) AS "txns"
FROM "${influxMeasurement}"
${influxWhere}
GROUP BY 1, "swarmId", "callIdKey", "businessCodeKey", "processorStatusClass"
ORDER BY 1, 2`
    },
    {
      id: "top-groups",
      label: "Top groups summary",
      clickhouseSql: `SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  avg(processorDurationMs) AS avg_rtt_ms,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms,
  avg(processorSuccess) * 100 AS processor_success_pct,
  avg(businessSuccess) * 100 AS business_success_pct
FROM ${clickhouseTable}
${clickhouseWhere}
GROUP BY swarmId, callIdKey, businessCodeKey
ORDER BY txns DESC
LIMIT 50`,
      influxSql: `SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  avg("processorDurationMs") AS "avg_rtt_ms",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms",
  avg("processorSuccess") * 100 AS "processor_success_pct",
  avg("businessSuccess") * 100 AS "business_success_pct"
FROM "${influxMeasurement}"
${influxWhere}
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
ORDER BY "txns" DESC
LIMIT 50`
    },
    {
      id: "worst-groups",
      label: "Worst groups by failure mix",
      clickhouseSql: `SELECT
  swarmId,
  callIdKey AS callId,
  businessCodeKey AS businessCode,
  count() AS txns,
  (1 - avg(businessSuccess)) * 100 AS business_fail_pct,
  (1 - avg(processorSuccess)) * 100 AS processor_fail_pct,
  quantileTDigest(0.95)(processorDurationMs) AS p95_rtt_ms,
  max(processorDurationMs) AS max_rtt_ms
FROM ${clickhouseTable}
${clickhouseWhere}
GROUP BY swarmId, callIdKey, businessCodeKey
HAVING txns >= 20
ORDER BY business_fail_pct DESC, txns DESC
LIMIT 100`,
      influxSql: `SELECT
  "swarmId",
  "callIdKey" AS "callId",
  "businessCodeKey" AS "businessCode",
  count(*) AS "txns",
  (1 - avg("businessSuccess")) * 100 AS "business_fail_pct",
  (1 - avg("processorSuccess")) * 100 AS "processor_fail_pct",
  approx_percentile_cont(0.95) WITHIN GROUP (ORDER BY "processorDurationMs") AS "p95_rtt_ms",
  max("processorDurationMs") AS "max_rtt_ms"
FROM "${influxMeasurement}"
${influxWhere}
GROUP BY "swarmId", "callIdKey", "businessCodeKey"
HAVING count(*) >= 20
ORDER BY "business_fail_pct" DESC, "txns" DESC
LIMIT 100`
    }
  ];
}

function clickhouseFilters({ fromMs, toMs, swarms, callIds, businessCodes }) {
  const clauses = [
    `eventTime >= toDateTime64('${toClickHouseDateTime(fromMs)}', 3, 'UTC')`,
    `eventTime < toDateTime64('${toClickHouseDateTime(toMs)}', 3, 'UTC')`
  ];
  if (Array.isArray(swarms) && swarms.length > 0) {
    clauses.push(`swarmId IN (${toSqlList(swarms)})`);
  }
  if (Array.isArray(callIds) && callIds.length > 0) {
    clauses.push(`callIdKey IN (${toSqlList(callIds.map(normalizedCallIdKey))})`);
  }
  if (Array.isArray(businessCodes) && businessCodes.length > 0) {
    clauses.push(`businessCodeKey IN (${toSqlList(businessCodes.map(normalizedBusinessCodeKey))})`);
  }
  return `WHERE ${clauses.join("\n  AND ")}`;
}

function influxFilters({ fromMs, toMs, swarms, callIds, businessCodes }) {
  const clauses = [
    `time >= timestamp '${toInfluxDateTime(fromMs)}'`,
    `time < timestamp '${toInfluxDateTime(toMs)}'`
  ];
  if (Array.isArray(swarms) && swarms.length > 0) {
    clauses.push(`"swarmId" IN (${toSqlList(swarms)})`);
  }
  if (Array.isArray(callIds) && callIds.length > 0) {
    clauses.push(`"callIdKey" IN (${toSqlList(callIds.map(normalizedCallIdKey))})`);
  }
  if (Array.isArray(businessCodes) && businessCodes.length > 0) {
    clauses.push(`"businessCodeKey" IN (${toSqlList(businessCodes.map(normalizedBusinessCodeKey))})`);
  }
  return `WHERE ${clauses.join("\n  AND ")}`;
}

function toClickHouseDateTime(millis) {
  return new Date(millis).toISOString().replace("T", " ").replace("Z", "").slice(0, 23);
}

function toInfluxDateTime(millis) {
  return new Date(millis).toISOString().replace(".000Z", "Z");
}

function toSqlList(values) {
  return values.map((value) => `'${String(value).replaceAll("'", "''")}'`).join(", ");
}

function timeBucketSeconds(windowMs) {
  const candidates = [60, 300, 900, 1800, 3600, 10_800, 21_600, 43_200, 86_400];
  const target = Math.max(60, Math.floor(windowMs / 120));
  return candidates.find((candidate) => candidate >= target) ?? candidates[candidates.length - 1];
}
