# Tx Outcome Storage Tools

Utilities in this folder operate on one canonical intermediate format: newline-delimited JSON records matching the `TxOutcomeEvent` shape used by the postprocessor sink path.

This keeps migration and benchmark loads storage-neutral:

- `ClickHouse v1 -> NDJSON`
- `NDJSON -> ClickHouse v2`
- `NDJSON -> InfluxDB 3`

## 1. Extract from ClickHouse v1

```bash
node tools/tx-outcome-storage/extract-clickhouse-v1.mjs \
  --from 2026-02-01T00:00:00Z \
  --to 2026-02-08T00:00:00Z \
  --out /tmp/tx-outcome.ndjson
```

Environment:

- `POCKETHIVE_CLICKHOUSE_HTTP_URL` default `http://localhost:8123`
- `POCKETHIVE_CLICKHOUSE_USERNAME` default `pockethive`
- `POCKETHIVE_CLICKHOUSE_PASSWORD` default `pockethive`

Defaults:

- source table: `ph_tx_outcome_v1`
- batch size: `10000`

## 2. Import into ClickHouse v2

```bash
node tools/tx-outcome-storage/import-clickhouse-v2.mjs \
  --input /tmp/tx-outcome.ndjson
```

Defaults:

- target table: `ph_tx_outcome_v2`
- batch size: `5000`

## 3. Import into InfluxDB 3

```bash
docker compose up -d influxdb3 influxdb3-init

export INFLUXDB3_ENDPOINT=http://localhost:8181
export INFLUXDB3_DATABASE=pockethive
export INFLUXDB3_TOKEN=apiv3_local_dev_replace_me

node tools/tx-outcome-storage/import-influxdb3.mjs \
  --input /tmp/tx-outcome.ndjson \
  --measurement ph_tx_outcome
```

The Influx importer writes to the official InfluxDB 3 line protocol endpoint:

- `POST /api/v3/write_lp?db=<database>&precision=ms`

The importer keeps low-cardinality dimensions as tags:

- `swarmId`
- `sinkRole`
- `sinkInstance`
- `callIdKey`
- `businessCodeKey`
- `processorStatusClass`

High-cardinality/raw values remain fields:

- `traceId`
- `callId`
- `businessCode`
- `dimensionsJson`

This avoids uncontrolled tag explosion while still preserving the original event content.

Local Docker Compose provisions:

- service: `influxdb3`
- bootstrap: `influxdb3-init`
- database: `pockethive`
- Grafana datasource UID: `influxdb3`
- default measurement/table: `ph_tx_outcome`
- optional env: `POCKETHIVE_INFLUXDB3_QUERY_FILE_LIMIT=<n>` to raise the local InfluxDB 3 Core planner file limit for long-window diagnostics

## Notes

- The NDJSON format is the comparison baseline. Do not add storage-specific fields to it.
- `ph_tx_outcome_v1` remains read-only and should be used only as the source corpus.
- `ph_tx_outcome_v2` is the local default target for new ClickHouse writes and dashboard work.
- `grafana/dashboards/tx-outcomes-influxdb3.json` is the initial InfluxDB 3 dashboard/query path for local read benchmarking.
- The InfluxDB 3 dashboard suite now mirrors the ClickHouse benchmark surface:
  - `grafana/dashboards/tx-outcomes-influxdb3.json`
  - `grafana/dashboards/tx-rtt-overview-influxdb3.json`
  - `grafana/dashboards/tx-rtt-latency-influxdb3.json`
- `grafana/dashboards/tx-rtt-quality-influxdb3.json`

## 4. Local benchmark harness

The harness generates one canonical NDJSON corpus, replays it into ClickHouse v2 and InfluxDB 3, then runs a read-oriented query suite derived from the dashboard surface.

Example:

```bash
node tools/tx-outcome-storage/benchmark-harness.mjs \
  --rate-per-sec 250 \
  --duration-minutes 30 \
  --history-window 2d \
  --windows 3h,2d \
  --repeats 3 \
  --capture-influx-server-timing
```

Outputs:

- default report directory: `.local-benchmarks/tx-outcome-<timestamp>/`
- machine-readable report: `benchmark-report.json`
- human-readable report: `benchmark-report.md`

Useful knobs:

- `--rows <n>` for a fixed-size smoke corpus
- `--history-window 30d` for a month-lite synthetic spread
- `--capture-influx-server-timing` to parse local `influxdb3` container logs and record server-side `plan / execute / end-to-end` timings alongside client-observed latency
- `--skip-compose` if ClickHouse and InfluxDB 3 are already running
- `--keep-corpus` to keep the generated NDJSON + manifest alongside the report

Report interpretation:

- client timings are split into `headersMs`, `firstByteMs`, and `totalMs`
- for InfluxDB 3, `totalMs` can be materially larger than `firstByteMs`; this helps separate SQL execution from end-to-end HTTP response completion
- failed queries are recorded in the report with the HTTP/client timing that was observed before failure plus the error text
- scenario filters are now explicit:
  - `all-swarms`: no swarm/call/businessCode filter
  - `single-swarm`: filter only by `swarmId`
  - `focused-segment`: filter by `swarmId + callIdKey + businessCodeKey`

## 5. Vendor support bundle

To package a benchmark run for vendor review:

```bash
node tools/tx-outcome-storage/export-influxdb-support-bundle.mjs \
  --report-dir .local-benchmarks/tx-outcome-1773795305434
```

This creates:

- `<report-dir>/influxdb-support-bundle/`
- `<report-dir>/influxdb-support-bundle.zip` when `python3` is available locally

The bundle includes:

- executive summary
- methodology
- mail template
- machine-readable + markdown reports
- rendered query suite for both ClickHouse and InfluxDB 3
- Grafana dashboards used as the benchmark surface
