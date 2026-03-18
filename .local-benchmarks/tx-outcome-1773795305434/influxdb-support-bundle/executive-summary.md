# Executive Summary

This package compares ClickHouse and InfluxDB 3 on the same synthetic tx_outcome corpus and the same read-oriented query suite.

- 3h: ClickHouse warm p95 total avg 6.99 ms, InfluxDB 3 22.94 ms (3.28x slower)
- 2d: ClickHouse warm p95 total avg 11.41 ms, InfluxDB 3 96.53 ms (8.46x slower)
- 30d: ClickHouse warm p95 total avg 41.18 ms, InfluxDB 3 8.34 s (202.62x slower)

## Worst InfluxDB 3 Queries

- 30d / All swarms / RTT percentiles p50/p95/p99: warm p95 total 18.24 s, server warm p95 end-to-end 1.44 s
- 30d / All swarms / Tail breach rate by SLO threshold: warm p95 total 17.89 s, server warm p95 end-to-end 1.33 s
- 30d / All swarms / Tx volume by swarm/call/businessCode: warm p95 total 15.85 s, server warm p95 end-to-end 500.97 ms
- 30d / All swarms / Processor duration p95: warm p95 total 14.95 s, server warm p95 end-to-end 463.97 ms
- 30d / All swarms / Top groups summary: warm p95 total 14.84 s, server warm p95 end-to-end 512.75 ms

## Key Interpretation

- ClickHouse is consistently faster on this query surface.
- For some InfluxDB 3 queries, server-side timing is materially lower than client-observed total latency.
- The bundle includes both client timing and server-side timing so the discrepancy can be investigated directly.
