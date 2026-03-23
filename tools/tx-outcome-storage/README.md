# Tx Outcome Storage Tools

Utilities in this folder operate on one canonical intermediate format: newline-delimited JSON records matching the `TxOutcomeEvent` shape used by the postprocessor sink path.

This keeps migration and benchmark loads storage-neutral:

- `ClickHouse v1 -> NDJSON`
- `NDJSON -> ClickHouse v2`

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

## Notes

- The NDJSON format is the comparison baseline. Do not add storage-specific fields to it.
- `ph_tx_outcome_v1` remains read-only and should be used only as the source corpus.
- `ph_tx_outcome_v2` is the local default target for new ClickHouse writes and dashboard work.
