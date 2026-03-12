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
export NETWORK_PROXY_MANAGER_BASE_URL="${NETWORK_PROXY_MANAGER_BASE_URL:-http://localhost:8088/network-proxy-manager}"
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
export POCKETHIVE_CLICKHOUSE_HTTP_URL="${POCKETHIVE_CLICKHOUSE_HTTP_URL:-http://localhost:8123}"
export POCKETHIVE_CLICKHOUSE_USERNAME="${POCKETHIVE_CLICKHOUSE_USERNAME:-pockethive}"
export POCKETHIVE_CLICKHOUSE_PASSWORD="${POCKETHIVE_CLICKHOUSE_PASSWORD:-pockethive}"

usage() {
  cat <<'EOF'
Usage: ./start-e2e-tests.sh [--tags <cucumber-tag-expression>] [--name <cucumber-name-regex>] [--help] [-- <extra maven args>]

Examples:
  ./start-e2e-tests.sh
  ./start-e2e-tests.sh --tags @tcps-proxy
  ./start-e2e-tests.sh --tags "@http-proxy or @https-proxy"
  ./start-e2e-tests.sh --name "TCPS processor traffic"
  ./start-e2e-tests.sh --tags @tcps-proxy -- -DfailIfNoTests=false
EOF
}

CUCUMBER_TAGS=""
CUCUMBER_NAME=""
MVN_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tags)
      [[ $# -ge 2 ]] || { echo "--tags requires a value" >&2; exit 1; }
      CUCUMBER_TAGS="$2"
      shift 2
      ;;
    --name)
      [[ $# -ge 2 ]] || { echo "--name requires a value" >&2; exit 1; }
      CUCUMBER_NAME="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --)
      shift
      MVN_ARGS+=("$@")
      break
      ;;
    *)
      MVN_ARGS+=("$1")
      shift
      ;;
  esac
done

RESOLVED_TAGS="${CUCUMBER_TAGS:-not @wip}"

CMD=(./mvnw verify -pl e2e-tests -am "-Dcucumber.filter.tags=${RESOLVED_TAGS}")
if [[ -n "${CUCUMBER_NAME}" ]]; then
  CMD+=("-Dcucumber.filter.name=${CUCUMBER_NAME}")
fi
if [[ -n "${CUCUMBER_TAGS}" || -n "${CUCUMBER_NAME}" ]]; then
  CMD+=("-Dtest=io.pockethive.e2e.CucumberE2ETest")
fi
CMD+=("${MVN_ARGS[@]}")

"${CMD[@]}"
