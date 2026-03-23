#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  bash clickhouse/migrate-tx-outcome-v1-to-v2.sh [options]

Runs inside the ClickHouse container and migrates tx-outcome data from ph_tx_outcome_v1
to ph_tx_outcome_v2 day by day.

Options:
  --host <host>              ClickHouse host. Default: 127.0.0.1
  --port <port>              ClickHouse native TCP port. Default: 9000
  --user <user>              ClickHouse user. Default: pockethive
  --password <password>      ClickHouse password. Default: pockethive
  --source-table <name>      Source table. Default: ph_tx_outcome_v1
  --target-table <name>      Target table. Default: ph_tx_outcome_v2
  --from <timestamp>         Inclusive lower bound for eventTime.
                             Example: 2026-02-01 or 2026-02-01T00:00:00Z
  --to <timestamp>           Exclusive upper bound for eventTime.
                             Example: 2026-02-22 or 2026-02-22T00:00:00Z
  --truncate-v2              Explicitly truncate the target table before migration.
  --help                     Show this help.

Notes:
  - This script uses only bash and clickhouse-client.
  - By default it fails if the target table already contains data.
  - If ph_tx_outcome_v2 does not exist, the script creates it using the repo v2 schema.
EOF
}

HOST="127.0.0.1"
PORT="9000"
USER_NAME="pockethive"
PASSWORD="pockethive"
SOURCE_TABLE="ph_tx_outcome_v1"
TARGET_TABLE="ph_tx_outcome_v2"
FROM_TS=""
TO_TS=""
TRUNCATE_V2="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)
      HOST="${2:?missing value for --host}"
      shift 2
      ;;
    --port)
      PORT="${2:?missing value for --port}"
      shift 2
      ;;
    --user)
      USER_NAME="${2:?missing value for --user}"
      shift 2
      ;;
    --password)
      PASSWORD="${2:?missing value for --password}"
      shift 2
      ;;
    --source-table)
      SOURCE_TABLE="${2:?missing value for --source-table}"
      shift 2
      ;;
    --target-table)
      TARGET_TABLE="${2:?missing value for --target-table}"
      shift 2
      ;;
    --from)
      FROM_TS="${2:?missing value for --from}"
      shift 2
      ;;
    --to)
      TO_TS="${2:?missing value for --to}"
      shift 2
      ;;
    --truncate-v2)
      TRUNCATE_V2="true"
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v clickhouse-client >/dev/null 2>&1; then
  echo "clickhouse-client not found in PATH" >&2
  exit 1
fi

client_args=(
  --host "$HOST"
  --port "$PORT"
  --user "$USER_NAME"
  --format TSVRaw
)

if [[ -n "$PASSWORD" ]]; then
  client_args+=(--password "$PASSWORD")
fi

run_query() {
  local query="$1"
  clickhouse-client "${client_args[@]}" --query "$query"
}

scalar_query() {
  local query="$1"
  local value
  value="$(run_query "$query")"
  value="${value//$'\r'/}"
  printf '%s' "$value"
}

sql_literal() {
  local value="$1"
  value="${value//\'/\'\'}"
  printf "'%s'" "$value"
}

require_table() {
  local table_name="$1"
  local exists
  exists="$(scalar_query "EXISTS TABLE ${table_name}")"
  if [[ "$exists" != "1" ]]; then
    echo "Required table does not exist: ${table_name}" >&2
    exit 1
  fi
}

create_v2_if_missing() {
  local exists
  exists="$(scalar_query "EXISTS TABLE ${TARGET_TABLE}")"
  if [[ "$exists" == "1" ]]; then
    return
  fi

  echo "Target table ${TARGET_TABLE} is missing, creating it"
  run_query "
CREATE TABLE IF NOT EXISTS ${TARGET_TABLE} (
  eventTime DateTime64(3, 'UTC'),
  swarmId LowCardinality(String),
  sinkRole LowCardinality(String),
  sinkInstance LowCardinality(String),
  traceId String,
  callId String,
  callIdKey LowCardinality(String) MATERIALIZED if(callId = '', 'unknown', callId),
  processorStatus Int32,
  processorStatusClass LowCardinality(String) MATERIALIZED multiIf(
    processorStatus >= 200 AND processorStatus < 300, '2xx',
    processorStatus >= 400 AND processorStatus < 500, '4xx',
    processorStatus >= 500 AND processorStatus < 600, '5xx',
    'other'
  ),
  processorSuccess UInt8,
  processorDurationMs UInt64,
  businessCode String,
  businessCodeKey LowCardinality(String) MATERIALIZED if(businessCode = '', 'n/a', businessCode),
  businessSuccess UInt8,
  dimensions Map(String, String)
)
ENGINE = MergeTree
PARTITION BY toDate(eventTime)
ORDER BY (swarmId, eventTime, callIdKey, businessCodeKey, sinkRole, sinkInstance)
TTL toDateTime(eventTime) + INTERVAL 6 MONTH
SETTINGS index_granularity = 8192
"
}

build_global_where() {
  local clauses=()
  if [[ -n "$FROM_TS" ]]; then
    clauses+=("eventTime >= parseDateTime64BestEffort($(sql_literal "$FROM_TS"), 3, 'UTC')")
  fi
  if [[ -n "$TO_TS" ]]; then
    clauses+=("eventTime < parseDateTime64BestEffort($(sql_literal "$TO_TS"), 3, 'UTC')")
  fi

  if [[ ${#clauses[@]} -eq 0 ]]; then
    printf '1'
    return
  fi

  local joined=""
  local clause
  for clause in "${clauses[@]}"; do
    if [[ -n "$joined" ]]; then
      joined="${joined} AND "
    fi
    joined="${joined}${clause}"
  done
  printf '%s' "$joined"
}

build_day_where() {
  local day="$1"
  local day_clause="eventTime >= toDateTime64('${day} 00:00:00', 3, 'UTC') AND eventTime < toDateTime64('${day} 00:00:00', 3, 'UTC') + toIntervalDay(1)"
  local global_where
  global_where="$(build_global_where)"
  if [[ "$global_where" == "1" ]]; then
    printf '%s' "$day_clause"
    return
  fi
  printf '%s AND %s' "$day_clause" "$global_where"
}

echo "Checking source table"
require_table "$SOURCE_TABLE"

echo "Checking target table"
create_v2_if_missing
require_table "$TARGET_TABLE"

if [[ "$TRUNCATE_V2" == "true" ]]; then
  echo "Truncating ${TARGET_TABLE}"
  run_query "TRUNCATE TABLE ${TARGET_TABLE}"
fi

target_count_before="$(scalar_query "SELECT count() FROM ${TARGET_TABLE}")"
if [[ "$target_count_before" != "0" ]]; then
  echo "Target table ${TARGET_TABLE} is not empty (${target_count_before} rows)." >&2
  echo "Refusing to append into a non-empty v2 table. Use --truncate-v2 if this is intentional." >&2
  exit 1
fi

global_where="$(build_global_where)"
day_list_query="
SELECT DISTINCT toString(toDate(eventTime)) AS day
FROM ${SOURCE_TABLE}
WHERE ${global_where}
ORDER BY day
"

mapfile -t days < <(run_query "$day_list_query")

if [[ ${#days[@]} -eq 0 ]]; then
  echo "No source rows matched the requested range"
  exit 0
fi

echo "Migrating ${#days[@]} day partition(s) from ${SOURCE_TABLE} to ${TARGET_TABLE}"

total_source=0
total_target=0

for day in "${days[@]}"; do
  day_where="$(build_day_where "$day")"
  source_count="$(scalar_query "SELECT count() FROM ${SOURCE_TABLE} WHERE ${day_where}")"

  echo "Day ${day}: source=${source_count}"
  if [[ "$source_count" == "0" ]]; then
    echo "Day ${day}: skipping empty partition"
    continue
  fi

  insert_query="
INSERT INTO ${TARGET_TABLE}
  (eventTime, swarmId, sinkRole, sinkInstance, traceId, callId, processorStatus, processorSuccess, processorDurationMs, businessCode, businessSuccess, dimensions)
SELECT
  eventTime,
  swarmId,
  sinkRole,
  sinkInstance,
  traceId,
  callId,
  processorStatus,
  processorSuccess,
  processorDurationMs,
  businessCode,
  businessSuccess,
  dimensions
FROM ${SOURCE_TABLE}
WHERE ${day_where}
ORDER BY eventTime, swarmId, callId, traceId, sinkInstance
"
  run_query "$insert_query"

  target_count="$(scalar_query "SELECT count() FROM ${TARGET_TABLE} WHERE ${day_where}")"
  if [[ "$source_count" != "$target_count" ]]; then
    echo "Day ${day}: count mismatch source=${source_count} target=${target_count}" >&2
    exit 1
  fi

  total_source=$((total_source + source_count))
  total_target=$((total_target + target_count))
  echo "Day ${day}: OK source=${source_count} target=${target_count}"
done

source_total_query="SELECT count() FROM ${SOURCE_TABLE} WHERE ${global_where}"
target_total_query="SELECT count() FROM ${TARGET_TABLE}"

final_source_total="$(scalar_query "$source_total_query")"
final_target_total="$(scalar_query "$target_total_query")"

echo "Final totals: source=${final_source_total} target=${final_target_total}"

if [[ "$final_source_total" != "$final_target_total" ]]; then
  echo "Final count mismatch source=${final_source_total} target=${final_target_total}" >&2
  exit 1
fi

echo "Migration completed successfully"
