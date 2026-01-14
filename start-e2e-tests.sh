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
export RABBITMQ_HOST="${RABBITMQ_HOST:-localhost}"
export RABBITMQ_PORT="${RABBITMQ_PORT:-5672}"
export RABBITMQ_DEFAULT_USER="${RABBITMQ_DEFAULT_USER:-guest}"
export RABBITMQ_DEFAULT_PASS="${RABBITMQ_DEFAULT_PASS:-guest}"
export RABBITMQ_VHOST="${RABBITMQ_VHOST:-/}"
export RABBITMQ_MANAGEMENT_BASE_URL="${RABBITMQ_MANAGEMENT_BASE_URL:-http://localhost:15672/rabbitmq/api}"
export UI_BASE_URL="${UI_BASE_URL:-http://localhost:8088}"
export UI_WEBSOCKET_URI="${UI_WEBSOCKET_URI:-ws://localhost:8088/ws}"
export POCKETHIVE_CONTROL_PLANE_EXCHANGE="${POCKETHIVE_CONTROL_PLANE_EXCHANGE:-ph.control}"
export POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX="${POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX:-ph.control}"
export POCKETHIVE_TCP_MOCK_URL="${POCKETHIVE_TCP_MOCK_URL:-http://localhost:8083}"
export POCKETHIVE_TCP_MOCK_USERNAME="${POCKETHIVE_TCP_MOCK_USERNAME:-admin}"
export POCKETHIVE_TCP_MOCK_PASSWORD="${POCKETHIVE_TCP_MOCK_PASSWORD:-admin}"

./mvnw verify -pl e2e-tests -am "$@"
