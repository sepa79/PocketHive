# Client-side RTT dashboard suite (ClickHouse)

> Status: **implemented / archived**.

Poniżej zestaw dashboardów dla danych transakcyjnych widzianych tylko z perspektywy klienta (bez etapów wewnętrznych).

## 1) Tx RTT Overview

Plik: `grafana/dashboards/tx-rtt-overview-clickhouse.json`

- **Tx volume by transaction type** — wolumen transakcji w czasie po typie operacji (np. redemption/topup/reversal/balance).
- **Tx volume by protocol/channel** — wolumen po protokołach/kanałach; szybkie wykrycie awarii specyficznej dla kanału.
- **Business success by transaction type** — skuteczność biznesowa per typ operacji, trend regresji.
- **Top transaction segments (volume, quality, RTT)** — ranking segmentów `tx_type x protocol` z `txns`, `avg/p95/max RTT` i success rate.

## 2) Tx RTT Latency & Tail

Plik: `grafana/dashboards/tx-rtt-latency-clickhouse.json`

- **RTT percentiles (p50/p95/p99)** — główny trend opóźnienia klienta i rozjazd tail latency.
- **Tail breach rate by SLO threshold** — udział transakcji powyżej 1s/2s/5s.
- **Max RTT by transaction segment (anomaly view)** — wykrywanie pików/anomalii RTT per segment.
- **Worst callId/businessCode by RTT** — tabela triage dla najbardziej problematycznych przypadków.

## 3) Tx Quality & Failure Mix

Plik: `grafana/dashboards/tx-rtt-quality-clickhouse.json`

- **Business success by protocol/channel** — jakość biznesowa per kanał/protokół.
- **Processor status classes over time** — rozkład statusów technicznych (`2xx/4xx/5xx/other`) w czasie.
- **Bank operation mix over time** — mix operacji bankowych i jego zmiany.
- **Worst business segments (failure + RTT)** — segmenty o najwyższym `fail_pct` i najgorszym RTT.

## Uwagi implementacyjne

- Wszystkie wykresy time-series używają bucketów czasowych (`toStartOfInterval(..., $__interval_s)`), żeby nie pobierać surowych gigantycznych wolumenów.
- Segmenty są wyliczane z `dimensions` z fallbackami:
  - `transaction_type` -> `tx_type` -> `operation`
  - `protocol` -> `channel` -> `transport`
- Dashboardy filtrują po `swarm` i `callId`.
