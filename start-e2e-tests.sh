#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -x ./mvnw ]]; then
  echo "Maven wrapper ./mvnw is required but was not found or is not executable." >&2
  exit 1
fi

export ORCHESTRATOR_BASE_URL="${ORCHESTRATOR_BASE_URL:-http://localhost:8088/orchestrator}"
export SCENARIO_MANAGER_BASE_URL="${SCENARIO_MANAGER_BASE_URL:-http://localhost:8088/scenario-manager}"
export RABBITMQ_HOST="${RABBITMQ_HOST:-rabbitmq}"
export RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
export RABBITMQ_DEFAULT_USER="${RABBITMQ_DEFAULT_USER:-guest}"
export RABBITMQ_DEFAULT_PASS="${RABBITMQ_DEFAULT_PASS:-guest}"
export RABBITMQ_VHOST="${RABBITMQ_VHOST:-/}"
export UI_BASE_URL="${UI_BASE_URL:-http://localhost:8088}"
export UI_WEBSOCKET_URI="${UI_WEBSOCKET_URI:-ws://localhost:8088/ws}"

./mvnw verify -pl e2e-tests -am "$@"
