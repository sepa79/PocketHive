#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

ALL_SERVICES=(rabbitmq log-aggregator scenario-manager orchestrator ui prometheus grafana loki wiremock pushgateway swarm-controller generator moderator processor postprocessor trigger)
declare -A DURATIONS=()
TIMING_ORDER=(clean build_base maven_package stage_artifacts docker_build compose_up restart)
BUILD_START_TIME=0
JAR_MODULES=(
  log-aggregator-service
  scenario-manager-service
  orchestrator-service
  swarm-controller-service
  generator-service
  moderator-service
  processor-service
  postprocessor-service
  trigger-service
)

declare -A MODULE_TO_SERVICE=(
  ["log-aggregator-service"]="log-aggregator"
  ["scenario-manager-service"]="scenario-manager"
  ["orchestrator-service"]="orchestrator"
  ["swarm-controller-service"]="swarm-controller"
  ["generator-service"]="generator"
  ["moderator-service"]="moderator"
  ["processor-service"]="processor"
  ["postprocessor-service"]="postprocessor"
  ["trigger-service"]="trigger"
)

declare -A SERVICE_TO_MODULE=(
  ["log-aggregator"]="log-aggregator-service"
  ["scenario-manager"]="scenario-manager-service"
  ["orchestrator"]="orchestrator-service"
  ["swarm-controller"]="swarm-controller-service"
  ["generator"]="generator-service"
  ["moderator"]="moderator-service"
  ["processor"]="processor-service"
  ["postprocessor"]="postprocessor-service"
  ["trigger"]="trigger-service"
)

LOCAL_ARTIFACTS_DIR="${LOCAL_ARTIFACTS_DIR:-.local-jars}"
COMPOSE_FILES=(-f docker-compose.yml)
MAVEN_CLI_OPTS="${MAVEN_CLI_OPTS:-}"
RUNTIME_IMAGE="${POCKETHIVE_RUNTIME_IMAGE:-pockethive-jvm-base:latest}"

SKIP_TESTS=false
MODULE_FILTER=()
SERVICE_FILTER=()
RESTART_TARGETS=()
CLEAN_STACK=false
MODULES_TO_BUILD=()
SERVICES_TO_BUILD=()

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --quick                 Skip tests during the Maven build (-DskipTests).
  --module <name>         Rebuild a given module (repeatable).
  --service <name>        Rebuild a docker-compose service (repeatable).
  --clean                 Stop the stack and remove stale containers before building.
  --restart <service>     Restart a running service after the build completes (repeatable).
  --help                  Show this help.

Behaviour:
  • No flags → local Maven build with tests, rebuild all services, start the full stack.
  • --quick  → skips tests for faster iterations.
  • --service generator --module orchestrator-service → rebuild specific services.

Environment:
  LOCAL_ARTIFACTS_DIR   Directory for staged jars (default: ${LOCAL_ARTIFACTS_DIR}).
  POCKETHIVE_RUNTIME_IMAGE  Tag for the shared JVM base image (default: ${RUNTIME_IMAGE}).
  MAVEN_CLI_OPTS        Extra Maven flags appended to the build command.
USAGE
}

compose_cmd() {
  docker compose "${COMPOSE_FILES[@]}" "$@"
}

compose_build_services() {
  local services=("$@")
  (( ${#services[@]} == 0 )) && return
  docker compose -f docker-compose.yml -f docker-compose.local.yml build "${services[@]}"
}

compose_up_services() {
  local services=("$@")
  (( ${#services[@]} == 0 )) && return
  compose_cmd up -d "${services[@]}"
}

compose_up_full_stack() {
  compose_cmd up -d
}

dedupe_list() {
  local -n result=$1
  shift
  declare -A seen=()
  for svc in "$@"; do
    [[ -z "$svc" ]] && continue
    if [[ -z "${seen[$svc]:-}" ]]; then
      seen["$svc"]=1
      result+=("$svc")
    fi
  done
}

clean_stack() {
  echo "Stopping PocketHive stack and removing stray bees..."
  mapfile -t bee_containers < <(docker ps -a --format '{{.ID}}\t{{.Names}}' | awk -F '\t' '$2 ~ /-bee-/')
  for entry in "${bee_containers[@]}"; do
    IFS=$'\t' read -r cid cname <<<"$entry"
    [[ -n "$cid" ]] && docker rm -f "$cid" >/dev/null
  done
  compose_cmd down --remove-orphans || true
}

require_tools() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required but missing from PATH." >&2
    exit 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose V2 is required (docker compose)." >&2
    exit 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is required for local builds." >&2
    exit 1
  fi
}

build_base_image() {
  echo "Building shared JVM runtime image (${RUNTIME_IMAGE})"
  docker build -f docker/base/Dockerfile -t "${RUNTIME_IMAGE}" docker/base
}

resolve_module_token() {
  local token="${1,,}"
  if [[ -n "${MODULE_TO_SERVICE[$token]:-}" ]]; then
    echo "$token"
    return
  fi
  if [[ -n "${SERVICE_TO_MODULE[$token]:-}" ]]; then
    echo "${SERVICE_TO_MODULE[$token]}"
    return
  fi
  echo "Unknown module/service: $1" >&2
  exit 1
}

determine_targets() {
  if [[ ${#MODULE_FILTER[@]} -eq 0 && ${#SERVICE_FILTER[@]} -eq 0 ]]; then
    MODULES_TO_BUILD=("${JAR_MODULES[@]}")
    SERVICES_TO_BUILD=("${ALL_SERVICES[@]}")
    return
  fi

  local module_acc=()
  local service_acc=()

  for entry in "${MODULE_FILTER[@]}"; do
    local module="$(resolve_module_token "$entry")"
    module_acc+=("$module")
    service_acc+=("${MODULE_TO_SERVICE[$module]}")
  done

  for svc in "${SERVICE_FILTER[@]}"; do
    local token="${svc,,}"
    service_acc+=("$token")
    if [[ -n "${SERVICE_TO_MODULE[$token]:-}" ]]; then
      module_acc+=("${SERVICE_TO_MODULE[$token]}")
    fi
  done

  dedupe_list MODULES_TO_BUILD "${module_acc[@]}"
  dedupe_list SERVICES_TO_BUILD "${service_acc[@]}"

  if (( ${#SERVICES_TO_BUILD[@]} == 0 )); then
    SERVICES_TO_BUILD=("${ALL_SERVICES[@]}")
  fi
}

run_maven_package() {
  local modules=("$@")
  (( ${#modules[@]} == 0 )) && return
  local csv
  csv=$(IFS=,; echo "${modules[*]}")
  local mvn_cmd=(mvn -B -pl "$csv" -am package)
  if $SKIP_TESTS; then
    mvn_cmd+=("-DskipTests")
  fi
  if [[ -n "${MAVEN_CLI_OPTS}" ]]; then
    read -r -a extra_opts <<<"${MAVEN_CLI_OPTS}"
    mvn_cmd+=("${extra_opts[@]}")
  fi

  echo "Packaging modules (${csv}) via local Maven"
  "${mvn_cmd[@]}"
}

stage_artifacts() {
  local modules=("$@")
  (( ${#modules[@]} == 0 )) && return
  rm -rf "${LOCAL_ARTIFACTS_DIR}"
  mkdir -p "${LOCAL_ARTIFACTS_DIR}"
  for module in "${modules[@]}"; do
    local jar_path
    jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*-exec.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | head -n 1 || true)
    if [[ -z "$jar_path" ]]; then
      jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' | head -n 1 || true)
    fi
    if [[ -z "$jar_path" ]]; then
      echo "Unable to locate packaged jar for ${module}" >&2
      exit 1
    fi
    cp "${jar_path}" "${LOCAL_ARTIFACTS_DIR}/${module}.jar"
    echo " - Staged ${module} → ${LOCAL_ARTIFACTS_DIR}/${module}.jar"
  done
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h)
        usage
        exit 0
        ;;
      --quick)
        SKIP_TESTS=true
        ;;
      --module)
        [[ $# -lt 2 ]] && { echo "--module requires a value" >&2; exit 1; }
        MODULE_FILTER+=("$2")
        shift
        ;;
      --module=*)
        MODULE_FILTER+=("${1#*=}")
        ;;
      --service)
        [[ $# -lt 2 ]] && { echo "--service requires a value" >&2; exit 1; }
        SERVICE_FILTER+=("$2")
        shift
        ;;
      --service=*)
        SERVICE_FILTER+=("${1#*=}")
        ;;
      --clean)
        CLEAN_STACK=true
        ;;
      --restart)
        [[ $# -lt 2 ]] && { echo "--restart requires a service name" >&2; exit 1; }
        RESTART_TARGETS+=("$2")
        shift
        ;;
      --restart=*)
        RESTART_TARGETS+=("${1#*=}")
        ;;
      *)
        echo "Unknown option: $1" >&2
        usage >&2
        exit 1
        ;;
    esac
    shift
  done
}

format_duration() {
  local seconds=$1
  if (( seconds < 0 )); then
    printf "n/a"
  else
    printf "%dm %02ds" $((seconds / 60)) $((seconds % 60))
  fi
}

measure() {
  local label="$1"
  shift
  local start
  start=$(date +%s)
  set +e
  "$@"
  local status=$?
  set -e
  local end
  end=$(date +%s)
  DURATIONS["$label"]=$((end - start))
  if (( status != 0 )); then
    exit "$status"
  fi
}

print_timing_summary() {
  local total_end
  total_end=$(date +%s)
  local total_duration=$((total_end - BUILD_START_TIME))
  echo
  echo "=== Timing Summary ==="
  for label in "${TIMING_ORDER[@]}"; do
    local pretty_label
    case "$label" in
      build_base) pretty_label="build base";;
      maven_package) pretty_label="maven package";;
      stage_artifacts) pretty_label="stage jars";;
      docker_build) pretty_label="docker build";;
      compose_up) pretty_label="docker up";;
      restart) pretty_label="restart";;
      *) pretty_label="$label";;
    esac
    local duration=${DURATIONS[$label]:--1}
    printf "  %-16s %s\n" "${pretty_label}:" "$(format_duration "$duration")"
  done
  printf "  %-16s %s\n" "total:" "$(format_duration "$total_duration")"
  echo
}

main() {
  parse_args "$@"
  require_tools
  determine_targets
  BUILD_START_TIME=$(date +%s)

  # Always clean before full-stack builds,
  # and honour explicit --clean for partial builds.
  if $CLEAN_STACK || { (( ${#MODULE_FILTER[@]} == 0 )) && (( ${#SERVICE_FILTER[@]} == 0 )) ; }; then
    measure "clean" clean_stack
  else
    DURATIONS["clean"]=-1
  fi

  export LOCAL_ARTIFACT_DIR="${LOCAL_ARTIFACTS_DIR}"
  export POCKETHIVE_RUNTIME_IMAGE="${RUNTIME_IMAGE}"

  if (( ${#MODULES_TO_BUILD[@]} )); then
    measure "build_base" build_base_image
    measure "maven_package" run_maven_package "${MODULES_TO_BUILD[@]}"
    measure "stage_artifacts" stage_artifacts "${MODULES_TO_BUILD[@]}"
  else
    DURATIONS["build_base"]=-1
    DURATIONS["maven_package"]=-1
    DURATIONS["stage_artifacts"]=-1
  fi

  if (( ${#SERVICES_TO_BUILD[@]} )); then
    echo "Building Docker images for: ${SERVICES_TO_BUILD[*]}"
    measure "docker_build" compose_build_services "${SERVICES_TO_BUILD[@]}"
  else
    echo "No services selected for build; skipping image build."
    DURATIONS["docker_build"]=-1
  fi

  echo "Starting PocketHive stack via docker compose up -d"
  measure "compose_up" compose_up_full_stack

  if (( ${#RESTART_TARGETS[@]} )); then
    echo "Restarting requested services: ${RESTART_TARGETS[*]}"
    measure "restart" compose_up_services "${RESTART_TARGETS[@]}"
  else
    DURATIONS["restart"]=-1
  fi

  print_timing_summary
  echo
  echo "PocketHive local build complete."
}

main "$@"
