Subject: PocketHive read benchmark: ClickHouse vs InfluxDB 3

Hi,

Attached is a support bundle with a read-oriented benchmark we ran against the same PocketHive tx_outcome dataset/query surface in ClickHouse and InfluxDB 3.

What is attached:

- full benchmark report (`benchmark-report.json` and `benchmark-report.md`)
- methodology and timing notes
- exact ClickHouse and InfluxDB SQL for the benchmark suite
- Grafana dashboard JSON used as the benchmark surface

Headline findings:

- 3h: ClickHouse warm p95 total avg 6.99 ms, InfluxDB 3 22.94 ms (3.28x slower)
- 2d: ClickHouse warm p95 total avg 11.41 ms, InfluxDB 3 96.53 ms (8.46x slower)
- 30d: ClickHouse warm p95 total avg 41.18 ms, InfluxDB 3 8.34 s (202.62x slower)
- 30d / All swarms / RTT percentiles p50/p95/p99: warm p95 total 18.24 s, server warm p95 end-to-end 1.44 s
- 30d / All swarms / Tail breach rate by SLO threshold: warm p95 total 17.89 s, server warm p95 end-to-end 1.33 s
- 30d / All swarms / Tx volume by swarm/call/businessCode: warm p95 total 15.85 s, server warm p95 end-to-end 500.97 ms

The main point we would like help with is this:

- for several InfluxDB 3 queries, server-side timings are substantially lower than client-observed total latency
- long-window queries are still materially slower than ClickHouse on this workload

Could you review the attached query suite and advise:

1. whether these query shapes are appropriate for InfluxDB 3 SQL / Flight SQL
2. whether there are known issues or settings that explain the client-observed latency gap
3. whether the schema/query style should be changed materially for this workload

If useful, we can rerun the same harness on a real 3-week production export next.

Regards,
