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
export RABBITMQ_URI="${RABBITMQ_URI:-amqp://ph-observer:ph-observer@localhost:5672/}"
export UI_BASE_URL="${UI_BASE_URL:-http://localhost:8088}"
export UI_WEBSOCKET_URI="${UI_WEBSOCKET_URI:-ws://localhost:8088/ws}"

./mvnw verify -pl e2e-tests -am "$@"
