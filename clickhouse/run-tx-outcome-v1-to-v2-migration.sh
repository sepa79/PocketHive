#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh [migration options]

Copies the v1 -> v2 tx-outcome migration script into the local docker compose
ClickHouse container and runs it there.

Examples:
  bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh
  bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --from 2026-02-01 --to 2026-02-22
  bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --truncate-v2
  bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --drop-source-after-migration

Notes:
  - Uses the docker compose service named "clickhouse".
  - Passes all arguments through to migrate-tx-outcome-v1-to-v2.sh.
  - The inner migration refuses to write into a non-empty v2 table unless
    --truncate-v2 is passed explicitly.
EOF
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker not found in PATH" >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
migration_script="${repo_root}/clickhouse/migrate-tx-outcome-v1-to-v2.sh"
container_script="/tmp/pockethive-migrate-tx-outcome-v1-to-v2.sh"

if [[ ! -f "$migration_script" ]]; then
  echo "Migration script not found: ${migration_script}" >&2
  exit 1
fi

docker compose cp "$migration_script" "clickhouse:${container_script}"
docker compose exec clickhouse bash "$container_script" "$@"
