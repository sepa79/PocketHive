#!/usr/bin/env bash
set -euo pipefail

ORIGINAL_ENTRYPOINT="/entrypoint.sh"
MIGRATION_SCRIPT="/opt/pockethive/migrate-tx-outcome-v1-to-v2.sh"
CLICKHOUSE_HOST="127.0.0.1"
CLICKHOUSE_NATIVE_PORT="${CLICKHOUSE_NATIVE_PORT:-9000}"
CLICKHOUSE_USER_NAME="${CLICKHOUSE_USER:-pockethive}"
CLICKHOUSE_PASSWORD_VALUE="${CLICKHOUSE_PASSWORD:-pockethive}"
READINESS_RETRIES="${POCKETHIVE_CLICKHOUSE_MIGRATION_READINESS_RETRIES:-120}"
READINESS_SLEEP_SECONDS="${POCKETHIVE_CLICKHOUSE_MIGRATION_READINESS_SLEEP_SECONDS:-1}"

server_pid=""

shutdown() {
  if [[ -n "$server_pid" ]] && kill -0 "$server_pid" >/dev/null 2>&1; then
    kill -TERM "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" || true
  fi
}

trap shutdown TERM INT

if [[ ! -x "$ORIGINAL_ENTRYPOINT" ]]; then
  echo "Original ClickHouse entrypoint not found or not executable: ${ORIGINAL_ENTRYPOINT}" >&2
  exit 1
fi

"$ORIGINAL_ENTRYPOINT" "$@" &
server_pid="$!"

ready="false"
for _ in $(seq 1 "$READINESS_RETRIES"); do
  if ! kill -0 "$server_pid" >/dev/null 2>&1; then
    wait "$server_pid"
    exit $?
  fi

  if clickhouse-client \
    --host "$CLICKHOUSE_HOST" \
    --port "$CLICKHOUSE_NATIVE_PORT" \
    --user "$CLICKHOUSE_USER_NAME" \
    --password "$CLICKHOUSE_PASSWORD_VALUE" \
    --query "SELECT 1" >/dev/null 2>&1; then
    ready="true"
    break
  fi

  sleep "$READINESS_SLEEP_SECONDS"
done

if [[ "$ready" != "true" ]]; then
  echo "ClickHouse did not become ready before tx-outcome migration timeout" >&2
  shutdown
  exit 1
fi

if ! bash "$MIGRATION_SCRIPT" \
  --host "$CLICKHOUSE_HOST" \
  --port "$CLICKHOUSE_NATIVE_PORT" \
  --user "$CLICKHOUSE_USER_NAME" \
  --password "$CLICKHOUSE_PASSWORD_VALUE" \
  --drop-source-after-migration \
  --skip-if-source-missing; then
  shutdown
  exit 1
fi

wait "$server_pid"
