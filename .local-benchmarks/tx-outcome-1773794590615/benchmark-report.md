# Tx Outcome Benchmark Report

- Generated: 2026-03-18T00:43:28.878Z
- Rows: 50
- Baseline throughput: 250/s for 30m
- Timestamp history window: 2d
- ClickHouse table: ph_tx_outcome_bench
- InfluxDB measurement: ph_tx_outcome_bench_1773794590616

| Storage | Window | Scenario | Query | Cold first byte ms | Cold total ms | Warm p95 first byte ms | Warm p95 total ms | Warm p95 server end-to-end ms | Rows |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| clickhouse | 3h | all-swarms | variable-swarms | 7.54 | 7.86 | 3.14 | 3.56 |  | 2 |
| influxdb3 | 3h | all-swarms | variable-swarms | 30.08 | 31.51 | 6.42 | 6.65 | 4.33 | 2 |
| clickhouse | 3h | all-swarms | variable-call-ids | 4.33 | 4.93 | 3.07 | 3.75 |  | 2 |
| influxdb3 | 3h | all-swarms | variable-call-ids | 6.01 | 6.27 | 8.11 | 8.51 | 5.05 | 2 |
| clickhouse | 3h | all-swarms | variable-business-codes | 4.1 | 4.66 | 3.51 | 4.35 |  | 2 |
| influxdb3 | 3h | all-swarms | variable-business-codes | 5.82 | 6.17 | 6.46 | 6.71 | 4.2 | 2 |
| clickhouse | 3h | all-swarms | tx-volume-timeseries | 5.61 | 6.82 | 4.46 | 5.44 |  | 3 |
| influxdb3 | 3h | all-swarms | tx-volume-timeseries | 7.37 | 7.66 | 7.67 | 7.85 | 5.97 | 3 |
| clickhouse | 3h | all-swarms | processor-p95-timeseries | 5.18 | 6.2 | 4.33 | 5.42 |  | 3 |
| influxdb3 | 3h | all-swarms | processor-p95-timeseries | 7.24 | 7.51 | 7.98 | 8.16 | 6.43 | 3 |
| clickhouse | 3h | all-swarms | latency-percentiles | 10.03 | 11.25 | 10.73 | 12.11 |  | 9 |
| influxdb3 | 3h | all-swarms | latency-percentiles | 17.77 | 18.31 | 17.06 | 17.54 | 15.53 | 9 |
| clickhouse | 3h | all-swarms | tail-breach-rate | 10.66 | 11.97 | 9.37 | 10.82 |  | 9 |
| influxdb3 | 3h | all-swarms | tail-breach-rate | 17.82 | 18.41 | 17.87 | 18.31 | 16.52 | 9 |
| clickhouse | 3h | all-swarms | status-mix | 5.28 | 6.27 | 4.47 | 5.62 |  | 3 |
| influxdb3 | 3h | all-swarms | status-mix | 7.44 | 7.68 | 7.83 | 8.01 | 6.35 | 3 |
| clickhouse | 3h | all-swarms | top-groups | 5.73 | 6.97 | 5.32 | 6.44 |  | 3 |
| influxdb3 | 3h | all-swarms | top-groups | 12.08 | 12.31 | 9.15 | 9.43 | 6.91 | 3 |
| clickhouse | 3h | all-swarms | worst-groups | 7.1 | 7.19 | 5.8 | 5.9 |  | 0 |
| influxdb3 | 3h | all-swarms | worst-groups | 9.82 | 10.09 | 9.58 | 13.64 | 8.45 | 0 |
| clickhouse | 3h | single-swarm | variable-swarms | 3.81 | 4.4 | 2.78 | 3.4 |  | 2 |
| influxdb3 | 3h | single-swarm | variable-swarms | 6.95 | 8.03 | 5.71 | 5.91 | 4.32 | 2 |
| clickhouse | 3h | single-swarm | variable-call-ids | 3.82 | 4.25 | 2.99 | 3.36 |  | 1 |
| influxdb3 | 3h | single-swarm | variable-call-ids | 6.78 | 7.73 | 6.72 | 7.42 | 5.32 | 1 |
| clickhouse | 3h | single-swarm | variable-business-codes | 4.18 | 4.71 | 3.11 | 3.49 |  | 1 |
| influxdb3 | 3h | single-swarm | variable-business-codes | 6.29 | 7.76 | 6.43 | 6.66 | 5.37 | 1 |
| clickhouse | 3h | single-swarm | tx-volume-timeseries | 5.15 | 6.17 | 4.59 | 5.63 |  | 1 |
| influxdb3 | 3h | single-swarm | tx-volume-timeseries | 7.66 | 7.92 | 9.58 | 9.88 | 6.58 | 1 |
| clickhouse | 3h | single-swarm | processor-p95-timeseries | 5.38 | 6.38 | 4.02 | 5.02 |  | 1 |
| influxdb3 | 3h | single-swarm | processor-p95-timeseries | 8.24 | 8.46 | 7.58 | 7.74 | 6.39 | 1 |
| clickhouse | 3h | single-swarm | latency-percentiles | 9.96 | 11.6 | 8.97 | 10.17 |  | 3 |
| influxdb3 | 3h | single-swarm | latency-percentiles | 21 | 21.57 | 18.26 | 18.69 | 16.89 | 3 |
| clickhouse | 3h | single-swarm | tail-breach-rate | 10.65 | 12 | 8.87 | 10.23 |  | 3 |
| influxdb3 | 3h | single-swarm | tail-breach-rate | 21 | 21.49 | 18.49 | 19.13 | 17.26 | 3 |
| clickhouse | 3h | single-swarm | status-mix | 5.14 | 6.25 | 4.17 | 5.41 |  | 1 |
| influxdb3 | 3h | single-swarm | status-mix | 7.93 | 8.16 | 7.14 | 7.38 | 5.88 | 1 |
| clickhouse | 3h | single-swarm | top-groups | 5.78 | 6.93 | 4.63 | 5.86 |  | 1 |
| influxdb3 | 3h | single-swarm | top-groups | 8.52 | 8.73 | 9.24 | 9.42 | 7.58 | 1 |
| clickhouse | 3h | single-swarm | worst-groups | 7.01 | 7.11 | 5.85 | 5.89 |  | 0 |
| influxdb3 | 3h | single-swarm | worst-groups | 8.99 | 11.61 | 22.06 | 22.49 | 20.81 | 0 |
| clickhouse | 3h | focused-segment | variable-swarms | 3.27 | 3.82 | 2.56 | 3.3 |  | 2 |
| influxdb3 | 3h | focused-segment | variable-swarms | 5.56 | 5.76 | 6.2 | 6.41 | 4.92 | 2 |
| clickhouse | 3h | focused-segment | variable-call-ids | 4.03 | 4.09 | 3.28 | 3.34 |  | 0 |
| influxdb3 | 3h | focused-segment | variable-call-ids | 6.78 | 7.73 | 5.49 | 6.26 | 4.49 | 0 |
| clickhouse | 3h | focused-segment | variable-business-codes | 4.16 | 4.21 | 3.37 | 3.43 |  | 0 |
| influxdb3 | 3h | focused-segment | variable-business-codes | 5.69 | 6.4 | 5.33 | 6.05 | 4.2 | 0 |
| clickhouse | 3h | focused-segment | tx-volume-timeseries | 5.61 | 5.7 | 5.98 | 6.02 |  | 0 |
| influxdb3 | 3h | focused-segment | tx-volume-timeseries | 8.05 | 8.4 | 19.96 | 20.24 | 18.88 | 0 |
| clickhouse | 3h | focused-segment | processor-p95-timeseries | 5.99 | 6.02 | 5.06 | 5.13 |  | 0 |
| influxdb3 | 3h | focused-segment | processor-p95-timeseries | 9.03 | 9.27 | 21.12 | 21.36 | 20.06 | 0 |
| clickhouse | 3h | focused-segment | latency-percentiles | 10.99 | 11.02 | 9.94 | 9.97 |  | 0 |
| influxdb3 | 3h | focused-segment | latency-percentiles | 20.49 | 20.94 | 19.12 | 19.58 | 18.02 | 0 |
| clickhouse | 3h | focused-segment | tail-breach-rate | 11.56 | 11.62 | 10.5 | 10.55 |  | 0 |
| influxdb3 | 3h | focused-segment | tail-breach-rate | 20.72 | 21.25 | 20.73 | 21.19 | 18.79 | 0 |
| clickhouse | 3h | focused-segment | status-mix | 6.12 | 6.17 | 4.86 | 4.89 |  | 0 |
| influxdb3 | 3h | focused-segment | status-mix | 7.9 | 8.18 | 9.03 | 9.29 | 7.94 | 0 |
| clickhouse | 3h | focused-segment | top-groups | 6.81 | 6.85 | 5.63 | 5.68 |  | 0 |
| influxdb3 | 3h | focused-segment | top-groups | 8.53 | 8.89 | 8.51 | 8.73 | 7.43 | 0 |
| clickhouse | 3h | focused-segment | worst-groups | 7.42 | 7.58 | 5.54 | 5.6 |  | 0 |
| influxdb3 | 3h | focused-segment | worst-groups | 9.39 | 9.72 | 9.5 | 9.75 | 8.29 | 0 |

