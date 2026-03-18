# Tx Outcome Benchmark Report

- Generated: 2026-03-17T23:00:53.628Z
- Rows: 200
- Baseline throughput: 250/s for 30m
- Timestamp history window: 2d
- ClickHouse table: ph_tx_outcome_bench
- InfluxDB measurement: ph_tx_outcome_bench_1773788450004

| Storage | Window | Scenario | Query | Cold ms | Warm avg ms | Warm p50 ms | Warm p95 ms | Rows |
|---|---|---|---|---:|---:|---:|---:|---:|
| clickhouse | 3h | all-swarms | variable-swarms | 6.3 | 4.16 | 4.16 | 4.16 | 6 |
| influxdb3 | 3h | all-swarms | variable-swarms | 14.34 | 8.92 | 8.92 | 8.92 | 6 |
| clickhouse | 3h | all-swarms | variable-call-ids | 4.06 | 3.96 | 3.96 | 3.96 | 9 |
| influxdb3 | 3h | all-swarms | variable-call-ids | 15.07 | 16.67 | 16.67 | 16.67 | 9 |
| clickhouse | 3h | all-swarms | variable-business-codes | 4.15 | 3.62 | 3.62 | 3.62 | 5 |
| influxdb3 | 3h | all-swarms | variable-business-codes | 9.99 | 8.73 | 8.73 | 8.73 | 5 |
| clickhouse | 3h | all-swarms | tx-volume-timeseries | 5.19 | 4.71 | 4.71 | 4.71 | 13 |
| influxdb3 | 3h | all-swarms | tx-volume-timeseries | 10.4 | 11.12 | 11.12 | 11.12 | 13 |
| clickhouse | 3h | all-swarms | processor-p95-timeseries | 6.79 | 5.42 | 5.42 | 5.42 | 13 |
| influxdb3 | 3h | all-swarms | processor-p95-timeseries | 15.15 | 11.57 | 11.57 | 11.57 | 13 |
| clickhouse | 3h | all-swarms | latency-percentiles | 9.08 | 9.17 | 9.17 | 9.17 | 39 |
| influxdb3 | 3h | all-swarms | latency-percentiles | 29.33 | 27.7 | 27.7 | 27.7 | 39 |
| clickhouse | 3h | all-swarms | tail-breach-rate | 9.1 | 25.36 | 25.36 | 25.36 | 39 |
| influxdb3 | 3h | all-swarms | tail-breach-rate | 32.16 | 27.02 | 27.02 | 27.02 | 39 |
| clickhouse | 3h | all-swarms | status-mix | 5.3 | 15.02 | 15.02 | 15.02 | 13 |
| influxdb3 | 3h | all-swarms | status-mix | 12.46 | 9.36 | 9.36 | 9.36 | 13 |
| clickhouse | 3h | all-swarms | top-groups | 5.65 | 5.05 | 5.05 | 5.05 | 13 |
| influxdb3 | 3h | all-swarms | top-groups | 11.62 | 13.31 | 13.31 | 13.31 | 13 |
| clickhouse | 3h | all-swarms | worst-groups | 6.13 | 5.85 | 5.85 | 5.85 | 0 |
| influxdb3 | 3h | all-swarms | worst-groups | 13.82 | 11.21 | 11.21 | 11.21 | 0 |
| clickhouse | 3h | single-swarm | variable-swarms | 2.62 | 2.63 | 2.63 | 2.63 | 6 |
| influxdb3 | 3h | single-swarm | variable-swarms | 6.21 | 8.34 | 8.34 | 8.34 | 6 |
| clickhouse | 3h | single-swarm | variable-call-ids | 3.48 | 2.92 | 2.92 | 2.92 | 5 |
| influxdb3 | 3h | single-swarm | variable-call-ids | 13.08 | 13.36 | 13.36 | 13.36 | 5 |
| clickhouse | 3h | single-swarm | variable-business-codes | 3.18 | 3.28 | 3.28 | 3.28 | 3 |
| influxdb3 | 3h | single-swarm | variable-business-codes | 8.15 | 7.84 | 7.84 | 7.84 | 3 |
| clickhouse | 3h | single-swarm | tx-volume-timeseries | 4.9 | 4.45 | 4.45 | 4.45 | 5 |
| influxdb3 | 3h | single-swarm | tx-volume-timeseries | 8.95 | 10.53 | 10.53 | 10.53 | 5 |
| clickhouse | 3h | single-swarm | processor-p95-timeseries | 5.03 | 4.8 | 4.8 | 4.8 | 5 |
| influxdb3 | 3h | single-swarm | processor-p95-timeseries | 13.13 | 10.63 | 10.63 | 10.63 | 5 |
| clickhouse | 3h | single-swarm | latency-percentiles | 9.08 | 7.8 | 7.8 | 7.8 | 15 |
| influxdb3 | 3h | single-swarm | latency-percentiles | 36.18 | 28.38 | 28.38 | 28.38 | 15 |
| clickhouse | 3h | single-swarm | tail-breach-rate | 11.35 | 9.5 | 9.5 | 9.5 | 15 |
| influxdb3 | 3h | single-swarm | tail-breach-rate | 26.19 | 25.33 | 25.33 | 25.33 | 15 |
| clickhouse | 3h | single-swarm | status-mix | 4.8 | 4.17 | 4.17 | 4.17 | 5 |
| influxdb3 | 3h | single-swarm | status-mix | 9.5 | 9.4 | 9.4 | 9.4 | 5 |
| clickhouse | 3h | single-swarm | top-groups | 5.4 | 33.84 | 33.84 | 33.84 | 5 |
| influxdb3 | 3h | single-swarm | top-groups | 13.78 | 10.02 | 10.02 | 10.02 | 5 |
| clickhouse | 3h | single-swarm | worst-groups | 6.3 | 43.79 | 43.79 | 43.79 | 0 |
| influxdb3 | 3h | single-swarm | worst-groups | 15.16 | 11.2 | 11.2 | 11.2 | 0 |
| clickhouse | 3h | focused-segment | variable-swarms | 2.73 | 2.66 | 2.66 | 2.66 | 6 |
| influxdb3 | 3h | focused-segment | variable-swarms | 6.6 | 6.35 | 6.35 | 6.35 | 6 |
| clickhouse | 3h | focused-segment | variable-call-ids | 3.4 | 2.83 | 2.83 | 2.83 | 1 |
| influxdb3 | 3h | focused-segment | variable-call-ids | 16.51 | 17.42 | 17.42 | 17.42 | 1 |
| clickhouse | 3h | focused-segment | variable-business-codes | 4.57 | 2.96 | 2.96 | 2.96 | 1 |
| influxdb3 | 3h | focused-segment | variable-business-codes | 36.67 | 9.65 | 9.65 | 9.65 | 1 |
| clickhouse | 3h | focused-segment | tx-volume-timeseries | 5.65 | 4.77 | 4.77 | 4.77 | 1 |
| influxdb3 | 3h | focused-segment | tx-volume-timeseries | 9.78 | 12.52 | 12.52 | 12.52 | 1 |
| clickhouse | 3h | focused-segment | processor-p95-timeseries | 4.86 | 4.39 | 4.39 | 4.39 | 1 |
| influxdb3 | 3h | focused-segment | processor-p95-timeseries | 9.66 | 9.86 | 9.86 | 9.86 | 1 |
| clickhouse | 3h | focused-segment | latency-percentiles | 9.24 | 9.49 | 9.49 | 9.49 | 3 |
| influxdb3 | 3h | focused-segment | latency-percentiles | 29.29 | 29.15 | 29.15 | 29.15 | 3 |
| clickhouse | 3h | focused-segment | tail-breach-rate | 8.95 | 11.37 | 11.37 | 11.37 | 3 |
| influxdb3 | 3h | focused-segment | tail-breach-rate | 36.88 | 26.87 | 26.87 | 26.87 | 3 |
| clickhouse | 3h | focused-segment | status-mix | 5.21 | 4.74 | 4.74 | 4.74 | 1 |
| influxdb3 | 3h | focused-segment | status-mix | 9.34 | 9.18 | 9.18 | 9.18 | 1 |
| clickhouse | 3h | focused-segment | top-groups | 5.98 | 4.64 | 4.64 | 4.64 | 1 |
| influxdb3 | 3h | focused-segment | top-groups | 9.81 | 10.17 | 10.17 | 10.17 | 1 |
| clickhouse | 3h | focused-segment | worst-groups | 5.22 | 5.91 | 5.91 | 5.91 | 0 |
| influxdb3 | 3h | focused-segment | worst-groups | 10.75 | 10.01 | 10.01 | 10.01 | 0 |

