# Methodology

## Goal

Compare read-path behavior of ClickHouse and InfluxDB 3 for the PocketHive `tx_outcome` workload.

## Corpus

- rows: 1800000
- synthetic ingest rate used to build the corpus: 1000/s
- synthetic duration used to build the corpus: 30 minutes
- history window: 30d
- swarms: 8
- callId cardinality: 24
- businessCode cardinality: 12
- reference time: 2026-03-18T00:55:05.694Z

## Query Surface

The benchmark query suite is derived from the Grafana dashboards used to inspect these tx_outcome datasets.

The bundle contains:

- the generated machine-readable report
- the exact ClickHouse and InfluxDB SQL used for every benchmark window/scenario
- the dashboard JSON definitions

## Timing Semantics

- client timings:
  - `headersMs`: time until HTTP headers were available
  - `firstByteMs`: time until the first byte of the response body
  - `totalMs`: time until the full response completed
- InfluxDB 3 server timings:
  - `planMs`
  - `permitMs`
  - `executeMs`
  - `endToEndMs`
  - `computeMs`

## Scenarios

- `all-swarms`: no swarm/call/businessCode filter
- `single-swarm`: one swarm only
- `focused-segment`: one swarm + hot callId + hot businessCode

## Important Caveat

This particular bundle is based on a synthetic 30-day spread, not a real production export. The query suite and the measurement methodology are production-oriented, but the corpus itself is synthetic.
