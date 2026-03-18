# Tx Outcome Benchmark Report

- Generated: 2026-03-18T01:21:30.002Z
- Rows: 1800000
- Baseline throughput: 1000/s for 30m
- Timestamp history window: 30d
- ClickHouse table: ph_tx_outcome_bench
- InfluxDB measurement: ph_tx_outcome_bench_1773795305434

| Storage | Window | Scenario | Query | Cold first byte ms | Cold total ms | Warm p95 first byte ms | Warm p95 total ms | Warm p95 server end-to-end ms | Rows | Error |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---|
| clickhouse | 3h | all-swarms | variable-swarms | 6.87 | 7.33 | 4.02 | 4.49 |  | 8 |  |
| influxdb3 | 3h | all-swarms | variable-swarms | 14.77 | 16.97 | 16.71 | 17.43 | 15.12 | 8 |  |
| clickhouse | 3h | all-swarms | variable-call-ids | 4.26 | 5.05 | 3.67 | 4.44 |  | 24 |  |
| influxdb3 | 3h | all-swarms | variable-call-ids | 11.03 | 11.9 | 13.86 | 14.33 | 12.32 | 24 |  |
| clickhouse | 3h | all-swarms | variable-business-codes | 4.64 | 5.29 | 3.35 | 4.2 |  | 12 |  |
| influxdb3 | 3h | all-swarms | variable-business-codes | 11.85 | 12.22 | 10.67 | 11.05 | 9.39 | 12 |  |
| clickhouse | 3h | all-swarms | tx-volume-timeseries | 6.92 | 8.27 | 5.83 | 6.91 |  | 1654 |  |
| influxdb3 | 3h | all-swarms | tx-volume-timeseries | 13.71 | 14.08 | 15.6 | 15.93 | 13.15 | 1654 |  |
| clickhouse | 3h | all-swarms | processor-p95-timeseries | 7.34 | 8.63 | 6.45 | 7.56 |  | 1654 |  |
| influxdb3 | 3h | all-swarms | processor-p95-timeseries | 16.05 | 16.44 | 21.13 | 21.46 | 18.21 | 1654 |  |
| clickhouse | 3h | all-swarms | latency-percentiles | 13.25 | 14.7 | 11.97 | 13.61 |  | 4962 |  |
| influxdb3 | 3h | all-swarms | latency-percentiles | 41.85 | 42.21 | 40.65 | 41.48 | 35.78 | 4962 |  |
| clickhouse | 3h | all-swarms | tail-breach-rate | 13.51 | 15.21 | 11.81 | 13.23 |  | 4962 |  |
| influxdb3 | 3h | all-swarms | tail-breach-rate | 35.42 | 35.82 | 43.52 | 44.38 | 39.59 | 4962 |  |
| clickhouse | 3h | all-swarms | status-mix | 6.57 | 7.94 | 6 | 7.31 |  | 2366 |  |
| influxdb3 | 3h | all-swarms | status-mix | 19.99 | 20.32 | 15.03 | 15.51 | 12.26 | 2366 |  |
| clickhouse | 3h | all-swarms | top-groups | 6.48 | 7.45 | 5.52 | 6.52 |  | 50 |  |
| influxdb3 | 3h | all-swarms | top-groups | 20.5 | 21.13 | 16.07 | 16.55 | 13.31 | 50 |  |
| clickhouse | 3h | all-swarms | worst-groups | 7.21 | 8.44 | 6.13 | 7.35 |  | 80 |  |
| influxdb3 | 3h | all-swarms | worst-groups | 16.22 | 16.55 | 23.33 | 23.69 | 19.99 | 80 |  |
| clickhouse | 3h | single-swarm | variable-swarms | 3.64 | 4.17 | 2.91 | 3.54 |  | 8 |  |
| influxdb3 | 3h | single-swarm | variable-swarms | 10.4 | 10.9 | 10.9 | 11.38 | 9.19 | 8 |  |
| clickhouse | 3h | single-swarm | variable-call-ids | 4.37 | 4.94 | 3.61 | 4.25 |  | 24 |  |
| influxdb3 | 3h | single-swarm | variable-call-ids | 12.06 | 12.71 | 12.6 | 13.18 | 11.36 | 24 |  |
| clickhouse | 3h | single-swarm | variable-business-codes | 4.56 | 5.27 | 3.4 | 3.96 |  | 12 |  |
| influxdb3 | 3h | single-swarm | variable-business-codes | 11.91 | 12.55 | 12.27 | 12.53 | 10.87 | 12 |  |
| clickhouse | 3h | single-swarm | tx-volume-timeseries | 5.92 | 6.85 | 4.58 | 5.55 |  | 338 |  |
| influxdb3 | 3h | single-swarm | tx-volume-timeseries | 14.45 | 14.75 | 20.04 | 20.32 | 17.56 | 338 |  |
| clickhouse | 3h | single-swarm | processor-p95-timeseries | 6.32 | 7.36 | 5.09 | 6.13 |  | 338 |  |
| influxdb3 | 3h | single-swarm | processor-p95-timeseries | 15.18 | 15.59 | 19.81 | 20.18 | 16.82 | 338 |  |
| clickhouse | 3h | single-swarm | latency-percentiles | 11.78 | 13.52 | 11.92 | 13.17 |  | 1014 |  |
| influxdb3 | 3h | single-swarm | latency-percentiles | 35.75 | 36.42 | 39.23 | 40.08 | 37.21 | 1014 |  |
| clickhouse | 3h | single-swarm | tail-breach-rate | 10.99 | 12.37 | 11.46 | 13.06 |  | 1014 |  |
| influxdb3 | 3h | single-swarm | tail-breach-rate | 38.43 | 39.1 | 51.71 | 52.73 | 50.12 | 1014 |  |
| clickhouse | 3h | single-swarm | status-mix | 5.5 | 6.58 | 4.85 | 6.02 |  | 584 |  |
| influxdb3 | 3h | single-swarm | status-mix | 15.73 | 16.13 | 15.76 | 16.14 | 13.61 | 584 |  |
| clickhouse | 3h | single-swarm | top-groups | 5.21 | 6.47 | 5.37 | 6.4 |  | 50 |  |
| influxdb3 | 3h | single-swarm | top-groups | 14.59 | 15.04 | 16.32 | 16.68 | 14.8 | 50 |  |
| clickhouse | 3h | single-swarm | worst-groups | 5.85 | 6.88 | 4.77 | 6.14 |  | 39 |  |
| influxdb3 | 3h | single-swarm | worst-groups | 15.78 | 16.52 | 21.44 | 21.92 | 19.32 | 39 |  |
| clickhouse | 3h | focused-segment | variable-swarms | 3.84 | 4.5 | 3.23 | 3.83 |  | 8 |  |
| influxdb3 | 3h | focused-segment | variable-swarms | 11.48 | 11.82 | 13.75 | 14.21 | 11.96 | 8 |  |
| clickhouse | 3h | focused-segment | variable-call-ids | 4.51 | 5.1 | 3.58 | 4.14 |  | 1 |  |
| influxdb3 | 3h | focused-segment | variable-call-ids | 11.41 | 11.8 | 13.89 | 14.4 | 11.22 | 1 |  |
| clickhouse | 3h | focused-segment | variable-business-codes | 4.64 | 5.23 | 3.83 | 4.56 |  | 1 |  |
| influxdb3 | 3h | focused-segment | variable-business-codes | 12.71 | 13.32 | 17.92 | 18.26 | 16.04 | 1 |  |
| clickhouse | 3h | focused-segment | tx-volume-timeseries | 6.67 | 7.6 | 5.38 | 6.42 |  | 2 |  |
| influxdb3 | 3h | focused-segment | tx-volume-timeseries | 14.68 | 15.18 | 20.62 | 21.01 | 19.31 | 2 |  |
| clickhouse | 3h | focused-segment | processor-p95-timeseries | 6.18 | 7.18 | 5.16 | 6.33 |  | 2 |  |
| influxdb3 | 3h | focused-segment | processor-p95-timeseries | 15.09 | 15.63 | 20.43 | 20.89 | 18.49 | 2 |  |
| clickhouse | 3h | focused-segment | latency-percentiles | 10.98 | 12.27 | 10.04 | 11.22 |  | 6 |  |
| influxdb3 | 3h | focused-segment | latency-percentiles | 42.74 | 44.46 | 58.49 | 59.53 | 57.32 | 6 |  |
| clickhouse | 3h | focused-segment | tail-breach-rate | 10.51 | 11.87 | 9.24 | 10.47 |  | 6 |  |
| influxdb3 | 3h | focused-segment | tail-breach-rate | 54.45 | 55.43 | 40.82 | 41.78 | 39.43 | 6 |  |
| clickhouse | 3h | focused-segment | status-mix | 5.97 | 7.2 | 5.1 | 6.24 |  | 6 |  |
| influxdb3 | 3h | focused-segment | status-mix | 15.48 | 15.87 | 16.91 | 17.26 | 15.57 | 6 |  |
| clickhouse | 3h | focused-segment | top-groups | 5.77 | 6.9 | 4.79 | 5.85 |  | 1 |  |
| influxdb3 | 3h | focused-segment | top-groups | 14.78 | 15.21 | 16.37 | 16.88 | 15.06 | 1 |  |
| clickhouse | 3h | focused-segment | worst-groups | 5.75 | 6.99 | 5.47 | 6.69 |  | 1 |  |
| influxdb3 | 3h | focused-segment | worst-groups | 21.11 | 21.51 | 16.51 | 16.89 | 15.04 | 1 |  |
| clickhouse | 2d | all-swarms | variable-swarms | 4.76 | 5.51 | 4.38 | 5.17 |  | 8 |  |
| influxdb3 | 2d | all-swarms | variable-swarms | 110.02 | 111.64 | 118.21 | 119.57 | 37.48 | 8 |  |
| clickhouse | 2d | all-swarms | variable-call-ids | 7.74 | 8.43 | 6.6 | 7.42 |  | 24 |  |
| influxdb3 | 2d | all-swarms | variable-call-ids | 102.19 | 103.64 | 139.85 | 141.26 | 42.24 | 24 |  |
| clickhouse | 2d | all-swarms | variable-business-codes | 7.68 | 8.4 | 6.6 | 7.43 |  | 12 |  |
| influxdb3 | 2d | all-swarms | variable-business-codes | 113.04 | 114.96 | 117.29 | 119.6 | 42.33 | 12 |  |
| clickhouse | 2d | all-swarms | tx-volume-timeseries | 13.89 | 15.13 | 12.39 | 13.51 |  | 3797 |  |
| influxdb3 | 2d | all-swarms | tx-volume-timeseries | 108.72 | 109.39 | 120.05 | 120.85 | 40.19 | 3797 |  |
| clickhouse | 2d | all-swarms | processor-p95-timeseries | 15.18 | 16.36 | 14.89 | 16.15 |  | 3797 |  |
| influxdb3 | 2d | all-swarms | processor-p95-timeseries | 114.23 | 115.63 | 118.37 | 119.9 | 27.66 | 3797 |  |
| clickhouse | 2d | all-swarms | latency-percentiles | 30.37 | 32.27 | 25.16 | 27.46 |  | 11391 |  |
| influxdb3 | 2d | all-swarms | latency-percentiles | 202.63 | 210.19 | 212.84 | 219.56 | 107.18 | 11391 |  |
| clickhouse | 2d | all-swarms | tail-breach-rate | 19.1 | 21.29 | 21.98 | 24.06 |  | 11391 |  |
| influxdb3 | 2d | all-swarms | tail-breach-rate | 182.82 | 187.07 | 189.17 | 195.05 | 97.54 | 11391 |  |
| clickhouse | 2d | all-swarms | status-mix | 13.79 | 15.14 | 17.23 | 19.38 |  | 7838 |  |
| influxdb3 | 2d | all-swarms | status-mix | 112.39 | 114.11 | 106.08 | 107.55 | 27.15 | 7838 |  |
| clickhouse | 2d | all-swarms | top-groups | 15.88 | 16.99 | 15.1 | 16.09 |  | 50 |  |
| influxdb3 | 2d | all-swarms | top-groups | 126.05 | 128.07 | 114.9 | 116.69 | 25.35 | 50 |  |
| clickhouse | 2d | all-swarms | worst-groups | 15.97 | 17.25 | 15.19 | 16.33 |  | 100 |  |
| influxdb3 | 2d | all-swarms | worst-groups | 125.02 | 126.79 | 115.17 | 116.57 | 29.41 | 100 |  |
| clickhouse | 2d | single-swarm | variable-swarms | 5.46 | 6.23 | 4.45 | 5.24 |  | 8 |  |
| influxdb3 | 2d | single-swarm | variable-swarms | 92.52 | 93.88 | 106.55 | 107.88 | 28.3 | 8 |  |
| clickhouse | 2d | single-swarm | variable-call-ids | 6.46 | 7.24 | 5.8 | 6.6 |  | 24 |  |
| influxdb3 | 2d | single-swarm | variable-call-ids | 58.35 | 60.04 | 51.88 | 53.24 | 23.01 | 24 |  |
| clickhouse | 2d | single-swarm | variable-business-codes | 6.36 | 7.4 | 5.34 | 6.1 |  | 12 |  |
| influxdb3 | 2d | single-swarm | variable-business-codes | 62.55 | 64.18 | 67.14 | 68.5 | 31.92 | 12 |  |
| clickhouse | 2d | single-swarm | tx-volume-timeseries | 8.2 | 9.33 | 7.83 | 8.86 |  | 557 |  |
| influxdb3 | 2d | single-swarm | tx-volume-timeseries | 60.01 | 61.07 | 60.98 | 62.41 | 26.18 | 557 |  |
| clickhouse | 2d | single-swarm | processor-p95-timeseries | 9.48 | 10.58 | 9.09 | 10.15 |  | 557 |  |
| influxdb3 | 2d | single-swarm | processor-p95-timeseries | 55.56 | 57.02 | 70.12 | 71.52 | 36.53 | 557 |  |
| clickhouse | 2d | single-swarm | latency-percentiles | 15.28 | 16.69 | 16.71 | 18.05 |  | 1671 |  |
| influxdb3 | 2d | single-swarm | latency-percentiles | 126.98 | 134.71 | 173.05 | 178.66 | 129.2 | 1671 |  |
| clickhouse | 2d | single-swarm | tail-breach-rate | 15.22 | 16.71 | 16.71 | 18.14 |  | 1671 |  |
| influxdb3 | 2d | single-swarm | tail-breach-rate | 119.62 | 127.62 | 148.05 | 155.76 | 112.34 | 1671 |  |
| clickhouse | 2d | single-swarm | status-mix | 9.58 | 10.87 | 9.05 | 10.29 |  | 1376 |  |
| influxdb3 | 2d | single-swarm | status-mix | 61.74 | 62.2 | 69.17 | 70.21 | 31.03 | 1376 |  |
| clickhouse | 2d | single-swarm | top-groups | 10.99 | 12.21 | 10.14 | 11.43 |  | 50 |  |
| influxdb3 | 2d | single-swarm | top-groups | 78.34 | 80.12 | 70.43 | 71.85 | 39.4 | 50 |  |
| clickhouse | 2d | single-swarm | worst-groups | 8.71 | 10.36 | 9.85 | 10.99 |  | 100 |  |
| influxdb3 | 2d | single-swarm | worst-groups | 67.79 | 69.03 | 63.47 | 64.73 | 31.79 | 100 |  |
| clickhouse | 2d | focused-segment | variable-swarms | 4.74 | 5.52 | 4.67 | 5.35 |  | 8 |  |
| influxdb3 | 2d | focused-segment | variable-swarms | 100.63 | 102.13 | 99.14 | 101.44 | 22.75 | 8 |  |
| clickhouse | 2d | focused-segment | variable-call-ids | 6.11 | 6.87 | 5.08 | 5.85 |  | 1 |  |
| influxdb3 | 2d | focused-segment | variable-call-ids | 34.93 | 37.2 | 42.22 | 43.52 | 31.57 | 1 |  |
| clickhouse | 2d | focused-segment | variable-business-codes | 5.85 | 6.56 | 4.84 | 5.52 |  | 1 |  |
| influxdb3 | 2d | focused-segment | variable-business-codes | 37.16 | 38.64 | 39.25 | 40.45 | 29.92 | 1 |  |
| clickhouse | 2d | focused-segment | tx-volume-timeseries | 7 | 8.05 | 6.24 | 7.27 |  | 3 |  |
| influxdb3 | 2d | focused-segment | tx-volume-timeseries | 40.78 | 42.15 | 41.17 | 42.93 | 32.74 | 3 |  |
| clickhouse | 2d | focused-segment | processor-p95-timeseries | 6.99 | 8.01 | 6.16 | 7.23 |  | 3 |  |
| influxdb3 | 2d | focused-segment | processor-p95-timeseries | 38.79 | 39.92 | 39.71 | 41.12 | 33.91 | 3 |  |
| clickhouse | 2d | focused-segment | latency-percentiles | 14.01 | 15.38 | 13.22 | 14.58 |  | 9 |  |
| influxdb3 | 2d | focused-segment | latency-percentiles | 93.23 | 98.09 | 96.77 | 101.85 | 90.95 | 9 |  |
| clickhouse | 2d | focused-segment | tail-breach-rate | 15.09 | 16.51 | 13.3 | 14.7 |  | 9 |  |
| influxdb3 | 2d | focused-segment | tail-breach-rate | 104.18 | 109.27 | 95.32 | 100.86 | 89.26 | 9 |  |
| clickhouse | 2d | focused-segment | status-mix | 6.47 | 7.54 | 6 | 6.99 |  | 9 |  |
| influxdb3 | 2d | focused-segment | status-mix | 24.05 | 25.26 | 61.81 | 63.01 | 54.26 | 9 |  |
| clickhouse | 2d | focused-segment | top-groups | 8.55 | 9.75 | 6.96 | 8.01 |  | 1 |  |
| influxdb3 | 2d | focused-segment | top-groups | 25.44 | 26.56 | 42.77 | 44.13 | 37.77 | 1 |  |
| clickhouse | 2d | focused-segment | worst-groups | 8.38 | 9.57 | 6.94 | 8.02 |  | 1 |  |
| influxdb3 | 2d | focused-segment | worst-groups | 26.41 | 27.88 | 33.88 | 35.35 | 24.8 | 1 |  |
| clickhouse | 30d | all-swarms | variable-swarms | 13.13 | 14.57 | 14.57 | 16.31 |  | 8 |  |
| influxdb3 | 30d | all-swarms | variable-swarms | 13668.04 | 13731.53 | 13682.53 | 13742.96 | 465.09 | 8 |  |
| clickhouse | 30d | all-swarms | variable-call-ids | 18.05 | 19.14 | 18.84 | 20.09 |  | 24 |  |
| influxdb3 | 30d | all-swarms | variable-call-ids | 13248.88 | 13322.78 | 14620.3 | 14692.11 | 508.71 | 24 |  |
| clickhouse | 30d | all-swarms | variable-business-codes | 20.55 | 21.73 | 18.52 | 19.76 |  | 12 |  |
| influxdb3 | 30d | all-swarms | variable-business-codes | 12661.33 | 12722.19 | 12634.47 | 12693.17 | 424.22 | 12 |  |
| clickhouse | 30d | all-swarms | tx-volume-timeseries | 49.71 | 53.89 | 50.65 | 52.97 |  | 47161 |  |
| influxdb3 | 30d | all-swarms | tx-volume-timeseries | 12805.17 | 12872.4 | 15769.45 | 15845.82 | 500.97 | 47161 |  |
| clickhouse | 30d | all-swarms | processor-p95-timeseries | 67.11 | 73.34 | 67.99 | 72.8 |  | 47161 |  |
| influxdb3 | 30d | all-swarms | processor-p95-timeseries | 14596.43 | 14664.17 | 14881.25 | 14951.11 | 463.97 | 47161 |  |
| clickhouse | 30d | all-swarms | latency-percentiles | 151.04 | 176.33 | 140.05 | 172.19 |  | 141483 |  |
| influxdb3 | 30d | all-swarms | latency-percentiles | 17815.39 | 18027.88 | 18001.97 | 18239.68 | 1444.31 | 141483 |  |
| clickhouse | 30d | all-swarms | tail-breach-rate | 127.94 | 152.64 | 116.83 | 135.37 |  | 141483 |  |
| influxdb3 | 30d | all-swarms | tail-breach-rate | 17952.89 | 18187.85 | 17658.93 | 17889.92 | 1332.65 | 141483 |  |
| clickhouse | 30d | all-swarms | status-mix | 68.5 | 80.09 | 70.37 | 77.55 |  | 104721 |  |
| influxdb3 | 30d | all-swarms | status-mix | 13511.77 | 13582.07 | 14760.12 | 14839.56 | 462.19 | 104721 |  |
| clickhouse | 30d | all-swarms | top-groups | 45.13 | 47.05 | 36.17 | 37.65 |  | 50 |  |
| influxdb3 | 30d | all-swarms | top-groups | 14620.41 | 14687.69 | 14778.6 | 14842.41 | 512.75 | 50 |  |
| clickhouse | 30d | all-swarms | worst-groups | 40.13 | 41.48 | 33.28 | 35.02 |  | 100 |  |
| influxdb3 | 30d | all-swarms | worst-groups | 13531.94 | 13592 | 14179.19 | 14244.57 | 451.73 | 100 |  |
| clickhouse | 30d | single-swarm | variable-swarms | 13.62 | 15.26 | 12.14 | 13.89 |  | 8 |  |
| influxdb3 | 30d | single-swarm | variable-swarms | 13603.07 | 13667.33 | 14517.37 | 14588.47 | 481.64 | 8 |  |
| clickhouse | 30d | single-swarm | variable-call-ids | 14.89 | 16.37 | 16.24 | 17.62 |  | 24 |  |
| influxdb3 | 30d | single-swarm | variable-call-ids | 6233.49 | 6296.64 | 5928.32 | 5999.35 | 445.19 | 24 |  |
| clickhouse | 30d | single-swarm | variable-business-codes | 14.59 | 15.79 | 13.95 | 14.96 |  | 12 |  |
| influxdb3 | 30d | single-swarm | variable-business-codes | 5152.38 | 5217.55 | 5336.22 | 5402.48 | 446.88 | 12 |  |
| clickhouse | 30d | single-swarm | tx-volume-timeseries | 24.8 | 25.96 | 22.9 | 24.45 |  | 6157 |  |
| influxdb3 | 30d | single-swarm | tx-volume-timeseries | 5710.61 | 5782.56 | 5942.49 | 6002.15 | 441.61 | 6157 |  |
| clickhouse | 30d | single-swarm | processor-p95-timeseries | 46.36 | 47.97 | 39.1 | 40.46 |  | 6157 |  |
| influxdb3 | 30d | single-swarm | processor-p95-timeseries | 5892.62 | 5958.34 | 5944.82 | 6020.58 | 470.93 | 6157 |  |
| clickhouse | 30d | single-swarm | latency-percentiles | 73.81 | 76.92 | 72.69 | 76.33 |  | 18471 |  |
| influxdb3 | 30d | single-swarm | latency-percentiles | 8217.1 | 8409.18 | 7968.42 | 8163.33 | 1407.14 | 18471 |  |
| clickhouse | 30d | single-swarm | tail-breach-rate | 63.87 | 66.92 | 61.83 | 64.91 |  | 18471 |  |
| influxdb3 | 30d | single-swarm | tail-breach-rate | 7475.82 | 7665.42 | 8203.82 | 8401.67 | 1385.57 | 18471 |  |
| clickhouse | 30d | single-swarm | status-mix | 33.5 | 35.55 | 32.33 | 34.26 |  | 17108 |  |
| influxdb3 | 30d | single-swarm | status-mix | 5689.34 | 5755.07 | 5762.17 | 5825.5 | 442.34 | 17108 |  |
| clickhouse | 30d | single-swarm | top-groups | 44.87 | 48.7 | 52.06 | 54.15 |  | 50 |  |
| influxdb3 | 30d | single-swarm | top-groups | 5960.27 | 6027.64 | 5865.56 | 5932.84 | 442.34 | 50 |  |
| clickhouse | 30d | single-swarm | worst-groups | 31.99 | 33.5 | 32.38 | 33.56 |  | 100 |  |
| influxdb3 | 30d | single-swarm | worst-groups | 6092.75 | 6172.91 | 6243.27 | 6312.97 | 454.97 | 100 |  |
| clickhouse | 30d | focused-segment | variable-swarms | 13.58 | 15.14 | 11.94 | 13.69 |  | 8 |  |
| influxdb3 | 30d | focused-segment | variable-swarms | 13564.57 | 13626.9 | 14085.4 | 14148.67 | 454.38 | 8 |  |
| clickhouse | 30d | focused-segment | variable-call-ids | 15.51 | 16.92 | 16.05 | 17.38 |  | 1 |  |
| influxdb3 | 30d | focused-segment | variable-call-ids | 1031.38 | 1112.63 | 961.18 | 1038.66 | 410.78 | 1 |  |
| clickhouse | 30d | focused-segment | variable-business-codes | 16.7 | 17.81 | 15.99 | 17.33 |  | 1 |  |
| influxdb3 | 30d | focused-segment | variable-business-codes | 932.93 | 995.21 | 1004.57 | 1076.06 | 415.53 | 1 |  |
| clickhouse | 30d | focused-segment | tx-volume-timeseries | 14.91 | 16.17 | 15.65 | 16.9 |  | 31 |  |
| influxdb3 | 30d | focused-segment | tx-volume-timeseries | 917.47 | 979.86 | 968.16 | 1035.77 | 451.43 | 31 |  |
| clickhouse | 30d | focused-segment | processor-p95-timeseries | 18.75 | 20.19 | 17.36 | 18.96 |  | 31 |  |
| influxdb3 | 30d | focused-segment | processor-p95-timeseries | 985.07 | 1061.4 | 987.45 | 1060.57 | 436.77 | 31 |  |
| clickhouse | 30d | focused-segment | latency-percentiles | 42.14 | 44.38 | 43.06 | 45.68 |  | 93 |  |
| influxdb3 | 30d | focused-segment | latency-percentiles | 1864.54 | 2056.71 | 1974.57 | 2180.19 | 1319.13 | 93 |  |
| clickhouse | 30d | focused-segment | tail-breach-rate | 41.05 | 42.89 | 38.25 | 40.16 |  | 93 |  |
| influxdb3 | 30d | focused-segment | tail-breach-rate | 1846.52 | 2050.13 | 1921.21 | 2103.46 | 3894.78 | 93 |  |
| clickhouse | 30d | focused-segment | status-mix | 16.29 | 17.48 | 14.33 | 15.44 |  | 93 |  |
| influxdb3 | 30d | focused-segment | status-mix | 929.79 | 988.83 | 946.14 | 1006.59 | 439.15 | 93 |  |
| clickhouse | 30d | focused-segment | top-groups | 16.59 | 18.01 | 15.78 | 17.1 |  | 1 |  |
| influxdb3 | 30d | focused-segment | top-groups | 895.61 | 961.66 | 921.79 | 983.43 | 438.81 | 1 |  |
| clickhouse | 30d | focused-segment | worst-groups | 17.12 | 18.42 | 17.18 | 18.42 |  | 1 |  |
| influxdb3 | 30d | focused-segment | worst-groups | 928.47 | 999.95 | 977.14 | 1043.16 | 429.19 | 1 |  |

