#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker compose -f "${SCRIPT_DIR}/compose.yml" exec -T dbquery-test-postgres \
  psql -U dbquery -d dbquery -f /docker-entrypoint-initdb.d/01-schema-seed.sql
