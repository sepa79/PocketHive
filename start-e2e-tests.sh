#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -x ./mvnw ]]; then
  echo "Maven wrapper ./mvnw is required but was not found or is not executable." >&2
  exit 1
fi

usage() {
  cat <<'EOF'
Usage: ./start-e2e-tests.sh [--target <name>] [--group <name>[,<name>...]] [--tags <cucumber-tag-expression>] [--name <cucumber-name-regex>] [--list-groups] [--list-targets] [--help] [-- <extra maven args>]

Examples:
  ./start-e2e-tests.sh
  ./start-e2e-tests.sh --target local-swarm --group smoke
  ./start-e2e-tests.sh --group smoke
  ./start-e2e-tests.sh --group lifecycle,proxy
  ./start-e2e-tests.sh --tags @tcps-proxy
  ./start-e2e-tests.sh --tags "@http-proxy or @https-proxy"
  ./start-e2e-tests.sh --name "TCPS processor traffic"
  ./start-e2e-tests.sh --group data -- -DfailIfNoTests=false
EOF
}

CUCUMBER_TAGS=""
CUCUMBER_NAME=""
MVN_ARGS=()
GROUP_NAMES=()
TARGET_NAME=""
TARGET_DIR="${SCRIPT_DIR}/deploy/e2e-targets"

allowed_target_key() {
  local candidate="$1"
  local allowed_key
  local allowed_keys=(
    ORCHESTRATOR_BASE_URL
    SCENARIO_MANAGER_BASE_URL
    NETWORK_PROXY_MANAGER_BASE_URL
    AUTH_SERVICE_BASE_URL
    POCKETHIVE_AUTH_USERNAME
    POCKETHIVE_AUTH_TOKEN
    RABBITMQ_HOST
    RABBITMQ_PORT
    RABBITMQ_DEFAULT_USER
    RABBITMQ_DEFAULT_PASS
    RABBITMQ_VHOST
    RABBITMQ_MANAGEMENT_BASE_URL
    RABBITMQ_MANAGEMENT_PORT
    UI_BASE_URL
    UI_WEBSOCKET_URI
    POCKETHIVE_CONTROL_PLANE_EXCHANGE
    POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX
    POCKETHIVE_TCP_MOCK_URL
    POCKETHIVE_TCP_MOCK_USERNAME
    POCKETHIVE_TCP_MOCK_PASSWORD
    POCKETHIVE_REDIS_HOST
    POCKETHIVE_REDIS_PORT
    POCKETHIVE_CLICKHOUSE_HTTP_URL
    POCKETHIVE_CLICKHOUSE_USERNAME
    POCKETHIVE_CLICKHOUSE_PASSWORD
    SWARM_ID
    IDEMPOTENCY_KEY_PREFIX
  )
  for allowed_key in "${allowed_keys[@]}"; do
    if [[ "${candidate}" == "${allowed_key}" ]]; then
      return 0
    fi
  done
  return 1
}

set_if_unset() {
  local key="$1"
  local value="$2"
  if [[ -z "${!key+x}" || -z "${!key}" ]]; then
    export "${key}=${value}"
  fi
}

load_target_profile() {
  local target="$1"
  if [[ -z "${target}" ]]; then
    return
  fi
  if [[ ! "${target}" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "Invalid e2e target name: ${target}" >&2
    echo "Target names may contain only letters, numbers, dot, underscore, and dash." >&2
    exit 1
  fi

  local target_file="${TARGET_DIR}/${target}.env"
  if [[ ! -f "${target_file}" ]]; then
    echo "Unknown e2e target: ${target}" >&2
    echo "Expected target profile at ${target_file}" >&2
    echo "Use --list-targets to see available targets." >&2
    exit 1
  fi

  local line key value
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line}" || "${line}" =~ ^[[:space:]]*# ]] && continue
    if [[ "${line}" != *=* ]]; then
      echo "Invalid line in ${target_file}: ${line}" >&2
      exit 1
    fi
    key="${line%%=*}"
    value="${line#*=}"
    key="${key//[[:space:]]/}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "${value}" =~ ^\"(.*)\"$ ]]; then
      value="${BASH_REMATCH[1]}"
    elif [[ "${value}" =~ ^\'(.*)\'$ ]]; then
      value="${BASH_REMATCH[1]}"
    fi
    if ! allowed_target_key "${key}"; then
      echo "Unsupported key in ${target_file}: ${key}" >&2
      exit 1
    fi
    set_if_unset "${key}" "${value}"
  done < "${target_file}"

  echo "Using e2e target '${target}' from ${target_file}" >&2
}

apply_default_environment() {
  export ORCHESTRATOR_BASE_URL="${ORCHESTRATOR_BASE_URL:-http://localhost:8088/orchestrator}"
  export SCENARIO_MANAGER_BASE_URL="${SCENARIO_MANAGER_BASE_URL:-http://localhost:8088/scenario-manager}"
  export NETWORK_PROXY_MANAGER_BASE_URL="${NETWORK_PROXY_MANAGER_BASE_URL:-http://localhost:8088/network-proxy-manager}"
  export AUTH_SERVICE_BASE_URL="${AUTH_SERVICE_BASE_URL:-http://localhost:1083}"
  export POCKETHIVE_AUTH_USERNAME="${POCKETHIVE_AUTH_USERNAME:-local-admin}"
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
}

group_tag_expression() {
  case "$1" in
    smoke) echo "@group-smoke" ;;
    auth) echo "@group-auth" ;;
    contracts) echo "@group-contracts" ;;
    lifecycle) echo "@group-lifecycle" ;;
    proxy) echo "@group-proxy" ;;
    data) echo "@group-data" ;;
    exports) echo "@group-exports" ;;
    wip) echo "@wip" ;;
    all) echo "not @wip" ;;
    *)
      echo "Unknown e2e group: $1" >&2
      echo "Use --list-groups to see supported groups." >&2
      exit 1
      ;;
  esac
}

print_groups() {
  cat <<'EOF'
Available e2e groups:
  smoke      Fast platform smoke checks, auth smoke, and template/default contract checks
  auth       Authentication and scoped access behaviour
  contracts  Scenario/template contract and runtime-config verification
  lifecycle  Core swarm lifecycle and control-plane behaviour
  proxy      HTTP/HTTPS/TCPS proxy scenarios
  data       Data-plane, timeout, Redis, ClickHouse, and WebAuth scenarios
  exports    Clearing export scenarios
  wip        Work in progress scenarios only
  all        Entire pack except @wip
EOF
}

print_targets() {
  if [[ ! -d "${TARGET_DIR}" ]]; then
    echo "No e2e targets directory found at ${TARGET_DIR}"
    return
  fi
  local found=false
  for target_file in "${TARGET_DIR}"/*.env; do
    [[ -e "${target_file}" ]] || continue
    found=true
    basename "${target_file}" .env
  done
  if [[ "${found}" == false ]]; then
    echo "No e2e targets found in ${TARGET_DIR}"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      [[ $# -ge 2 ]] || { echo "--target requires a value" >&2; exit 1; }
      TARGET_NAME="$2"
      shift 2
      ;;
    --group)
      [[ $# -ge 2 ]] || { echo "--group requires a value" >&2; exit 1; }
      IFS=',' read -r -a parsed_groups <<< "$2"
      for group in "${parsed_groups[@]}"; do
        trimmed_group="$(echo "$group" | xargs)"
        [[ -n "${trimmed_group}" ]] || continue
        GROUP_NAMES+=("${trimmed_group}")
      done
      shift 2
      ;;
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
    --list-groups)
      print_groups
      exit 0
      ;;
    --list-targets)
      print_targets
      exit 0
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

load_target_profile "${TARGET_NAME}"
apply_default_environment

GROUP_TAGS=()
for group_name in "${GROUP_NAMES[@]}"; do
  GROUP_TAGS+=("$(group_tag_expression "${group_name}")")
done

GROUP_EXPRESSION=""
if [[ ${#GROUP_TAGS[@]} -gt 0 ]]; then
  GROUP_EXPRESSION="$(printf ' or %s' "${GROUP_TAGS[@]}")"
  GROUP_EXPRESSION="${GROUP_EXPRESSION:4}"
fi

if [[ -n "${CUCUMBER_TAGS}" && -n "${GROUP_EXPRESSION}" ]]; then
  RESOLVED_TAGS="(${GROUP_EXPRESSION}) and (${CUCUMBER_TAGS})"
elif [[ -n "${CUCUMBER_TAGS}" ]]; then
  RESOLVED_TAGS="${CUCUMBER_TAGS}"
elif [[ -n "${GROUP_EXPRESSION}" ]]; then
  RESOLVED_TAGS="${GROUP_EXPRESSION}"
else
  RESOLVED_TAGS="not @wip"
fi

CMD=(./mvnw verify -pl e2e-tests -am "-Dcucumber.filter.tags=${RESOLVED_TAGS}")
if [[ -n "${CUCUMBER_NAME}" ]]; then
  CMD+=("-Dcucumber.filter.name=${CUCUMBER_NAME}")
fi
if [[ ${#GROUP_NAMES[@]} -gt 0 || -n "${CUCUMBER_TAGS}" || -n "${CUCUMBER_NAME}" ]]; then
  CMD+=("-Dtest=io.pockethive.e2e.CucumberE2ETest")
  CMD+=("-Dsurefire.failIfNoSpecifiedTests=false")
fi
CMD+=("${MVN_ARGS[@]}")

"${CMD[@]}"
