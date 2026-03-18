# Tx Outcome Storage & Benchmark Plan

> Status: started
> Scope: postprocessor-service, ClickHouse tx-outcome storage, InfluxDB 3 sink, data migration tooling, Grafana dashboards, local benchmark harness

This plan replaces the current ClickHouse-only tx-outcome path with an explicit sink mode, adds a second storage target in InfluxDB 3, introduces a new ClickHouse v2 layout optimized for read-heavy dashboard usage, and prepares tooling for benchmarking on both synthetic local data and later on real production-like history.

## Goals

- Replace `writeTxOutcomeToClickHouse` with an explicit sink mode enum.
- Keep `CH v1` read-only as the historical source and benchmark reference point.
- Use one canonical intermediate export format for migration and benchmark loads.
- Treat Grafana queries and dashboards as part of the benchmark surface, not as a post-hoc add-on.
- Benchmark primarily for read/query latency and operator ergonomics, not for max ingest throughput.
- Keep write-path validation only as a safety gate: sink must not create backpressure before the postprocessor.

## Non-goals

- No implicit backward compatibility for the old boolean sink flag.
- No dual-write mode in the postprocessor.
- No fallback from one sink/protocol/storage engine to another.
- No attempt to infer the sink from configured properties.

## Constraints

- Explicit configuration only. Sink selection must be a required enum/state in the effective config.
- `TxOutcomeEvent` remains the SSOT payload for tx-outcome capture until an explicit contract update is approved.
- `ph_tx_outcome_v1` remains unchanged and read-only.
- Migration/import tooling must read/write one canonical intermediate format, not implement bespoke `CH -> Influx` special cases.

## Phase 1 — Sink Mode Refactor

- [x] Replace `writeTxOutcomeToClickHouse` in the postprocessor config with `txOutcomeSinkMode`.
- [x] Introduce `TxOutcomeSinkMode` enum with explicit values:
  - [x] `NONE`
  - [x] `CLICKHOUSE_V2`
  - [x] `INFLUXDB3`
- [x] Introduce a `TxOutcomeSink` port and a resolver/selection path driven only by the enum.
- [x] Keep the current ClickHouse HTTP writer behind the new sink port as the initial adapter.
- [x] Expose sink mode in worker status so runtime inspection shows the active storage target explicitly.
- [x] Update postprocessor unit tests to assert explicit sink mode behavior.
- [ ] Add integration coverage for config-update flows that switch the sink mode at runtime.

## Phase 2 — ClickHouse v2

- [x] Define `ph_tx_outcome_v2` as a new schema; do not mutate `ph_tx_outcome_v1`.
- [ ] Optimize the table layout for read-heavy time-window queries used in Grafana:
  - [ ] `last 3h`
  - [ ] `last 2d`
  - [ ] `last 3w`
  - [ ] optional `last 1m` synthetic stress query
- [ ] Revisit sort key, partitioning, TTL, and low-cardinality usage using the real dashboard query suite as the design driver.
- [ ] Add projections/materialized views only if the benchmark suite shows a clear need.
- [x] Update local compose/init assets so `CH v2` can be provisioned reproducibly.

## Phase 3 — InfluxDB 3 Sink

- [ ] Add a dedicated `common/sink-influxdb3` module.
- [x] Add explicit configuration properties for endpoint, database, token, measurement/table, batching, and buffering.
- [x] Implement write path using one explicit InfluxDB 3 write protocol.
- [x] Add local Docker Compose provisioning for InfluxDB 3.
- [ ] Add integration tests proving:
  - [x] writes succeed,
  - [x] flush works,
  - [x] invalid config fails fast,
  - [x] bounded buffering does not silently fallback.

## Phase 4 — Canonical Extract / Import Tooling

- [x] Define a canonical intermediate export format based on `TxOutcomeEvent`.
- [x] Implement `CH v1 -> NDJSON` extractor.
- [x] Implement `NDJSON -> CH v2` importer.
- [x] Implement `NDJSON -> InfluxDB 3` importer.
- [x] Ensure the same NDJSON corpus can be replayed into both targets for fair benchmark comparisons.

## Phase 5 — Dashboard Query Suite

- [ ] Freeze a benchmark query suite derived from the current Grafana dashboards:
  - [ ] `grafana/dashboards/tx-outcomes-clickhouse.json`
  - [ ] `grafana/dashboards/tx-rtt-overview-clickhouse.json`
  - [ ] `grafana/dashboards/tx-rtt-latency-clickhouse.json`
  - [ ] `grafana/dashboards/tx-rtt-quality-clickhouse.json`
- [x] Rewrite the ClickHouse dashboards/queries to target `CH v2`.
- [x] Create equivalent InfluxDB 3 dashboard/query variants for the same operator use cases.
- [x] Include variable queries (`swarmId`, `callId`, `businessCode`) in the benchmark suite, not only panel queries.
- [x] Keep the benchmark query filters semantically fair across engines:
  - [x] no-op `all values` filters should not be emitted as giant `IN (...)` clauses
  - [x] variable queries should filter on normalized low-cardinality keys, not raw field copies

## Phase 6 — Benchmark Harness

- [x] Add a local benchmark harness based on a synthetic `30m` dataset with realistic cardinality:
  - [x] multiple swarms
  - [x] multiple `callId` values
  - [x] multiple `businessCode` values
  - [x] mixed success/error distributions
  - [x] realistic latency spread
- [ ] Use the harness for local end-to-end verification of:
  - [ ] postprocessor write safety
  - [x] extractor/importer correctness
  - [x] dashboard query execution
- [x] Keep the harness storage-neutral so the same generated/exported dataset can feed both `CH v2` and `InfluxDB 3`.
- [x] Split read latency reporting into:
  - [x] time to headers / first byte
  - [x] full response completion
  - [x] optional InfluxDB 3 server-side timing from local logs

## Phase 7 — Real Dataset Benchmark

- [ ] Run the extractor against the available `3 weeks` of `CH v1` data.
- [ ] Import the same corpus into `CH v2` and `InfluxDB 3`.
- [ ] Execute the frozen query suite for:
  - [ ] cold runs
  - [ ] warm runs
  - [ ] narrow window (`3h`)
  - [ ] operational window (`2d`)
  - [ ] long window (`3w`)
- [ ] Record latency, scanned data, and operator-facing dashboard usability notes.

## First implementation slice

The first slice in this branch is intentionally narrow:

- [x] write this plan,
- [x] implement explicit sink mode in the postprocessor,
- [x] keep current ClickHouse adapter behind the new port,
- [x] leave `CH v2`, InfluxDB 3, and extract/import tooling for follow-up commits.

## Notes

- Read performance is the primary decision driver.
- Write performance matters only insofar as it must not create a queue/backpressure problem ahead of the postprocessor.
- Synthetic local data is a development harness, not the final benchmark source of truth.
- The final storage comparison should be executed on the real `CH v1` history when that dataset is available.
