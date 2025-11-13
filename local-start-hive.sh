#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

CORE_SERVICES=(rabbitmq log-aggregator scenario-manager orchestrator ui prometheus grafana loki wiremock)
BEE_SERVICES=(swarm-controller generator payload-generator moderator processor postprocessor trigger)
BEE_MODULES=(swarm-controller-service generator-service payload-generator-service moderator-service processor-service postprocessor-service trigger-service)
DEFAULT_STAGES=(clean build-core build-bees start)
ALL_STAGES=("${DEFAULT_STAGES[@]}" push restart)

LOCAL_ARTIFACTS_DIR="${LOCAL_ARTIFACTS_DIR:-.local-bee-artifacts}"
MAVEN_CLI_OPTS="${MAVEN_CLI_OPTS:--q -B -DskipTests}"
COMPOSE_FILES=(-f docker-compose.yml -f docker-compose.local.yml)

declare -A STAGE_TIMES
SERVICE_ARGS=()

# Registry configuration
DOCKER_REGISTRY="${DOCKER_REGISTRY:-}"
POCKETHIVE_VERSION="${POCKETHIVE_VERSION:-latest}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [stage ...]

Stages:
  clean        Stop the compose stack and remove stray swarm containers.
  build-core   Build core PocketHive service images (RabbitMQ, UI, etc.).
  build-bees   Package bee jars locally, then build lightweight images.
  push         Push images to configured registry.
  start        Launch the PocketHive stack via docker compose up -d.
  restart      Rebuild and restart only the services listed after '--'.

Environment Variables:
  MAVEN_CLI_OPTS        Maven options for packaging bees (default: "${MAVEN_CLI_OPTS}").
  LOCAL_ARTIFACTS_DIR   Directory for staged bee jars (default: ${LOCAL_ARTIFACTS_DIR}).
  DOCKER_REGISTRY       Registry prefix (e.g., 'myregistry.io/' or 'localhost:5000/').
  POCKETHIVE_VERSION    Image tag version (default: latest).

Examples:
  $(basename "$0")                                    Run default stages (${DEFAULT_STAGES[*]}).
  $(basename "$0") clean start                        Only clean and start (skip builds).
  $(basename "$0") build-bees                         Package jars locally and rebuild bees.
  $(basename "$0") restart -- payload-generator        Rebuild + restart payload-generator only.
USAGE
}

require_tools() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required but was not found in PATH." >&2
    exit 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose V2 is required (docker compose command)." >&2
    exit 1
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is required for local bee packaging." >&2
    exit 1
  fi
}

compose() {
  docker compose "${COMPOSE_FILES[@]}" "$@"
}

stage_header() {
  local label="$1"
  echo
  echo "=== ${label} ==="
}

format_duration() {
  local seconds=$1
  printf "%dm %ds" $((seconds / 60)) $((seconds % 60))
}

package_bee_jars() {
  echo "Packaging bee modules locally (${MAVEN_CLI_OPTS})"
  local modules_csv
  modules_csv=$(IFS=,; echo "${BEE_MODULES[*]}")
  mvn ${MAVEN_CLI_OPTS} -pl "${modules_csv}" -am package

  rm -rf "${LOCAL_ARTIFACTS_DIR}"
  mkdir -p "${LOCAL_ARTIFACTS_DIR}"

  for module in "${BEE_MODULES[@]}"; do
    if [[ ! -d "${module}/target" ]]; then
      echo "Module ${module} has no target directory (build failed?)." >&2
      exit 1
    fi
    local jar_path
    jar_path=$(find "${module}/target" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*.jar' -print -quit)
    if [[ -z "${jar_path}" ]]; then
      echo "Unable to locate packaged jar for ${module}" >&2
      exit 1
    fi
    cp "${jar_path}" "${LOCAL_ARTIFACTS_DIR}/${module}.jar"
    echo " - Staged ${module} → ${LOCAL_ARTIFACTS_DIR}/${module}.jar"
  done
}

run_clean() {
  stage_header "Cleaning previous PocketHive stack"
  echo "Removing stray swarm containers (bees)..."
  mapfile -t bee_containers < <(docker ps -a --format '{{.ID}}\t{{.Names}}' | awk -F '\t' '$2 ~ /-bee-/')
  if [[ ${#bee_containers[@]} -eq 0 ]]; then
    echo "No stray swarm containers found."
  else
    for entry in "${bee_containers[@]}"; do
      IFS=$'\t' read -r cid cname <<<"$entry"
      if [[ -n "$cid" ]]; then
        echo " - Removing $cname ($cid)"
        docker rm -f "$cid" >/dev/null
      fi
    done
  fi

  echo "Stopping docker compose services..."
  compose down --remove-orphans || true
}

run_build_core() {
  stage_header "Building core PocketHive services"
  compose build "${CORE_SERVICES[@]}"
}

run_build_bees() {
  stage_header "Building swarm controller and bee images (local jars)"
  package_bee_jars
  compose --profile bees build "${BEE_SERVICES[@]}"
}

run_push() {
  stage_header "Pushing images to registry"
  if [[ -z "$DOCKER_REGISTRY" ]]; then
    echo "DOCKER_REGISTRY not set. Skipping push." >&2
    return 0
  fi
  echo "Pushing to registry: ${DOCKER_REGISTRY}"
  compose push "${CORE_SERVICES[@]}"
  compose --profile bees push "${BEE_SERVICES[@]}"
}

run_start() {
  stage_header "Starting PocketHive stack"
  compose up -d
}

run_restart() {
  if [[ ${#SERVICE_ARGS[@]} -eq 0 ]]; then
    echo "restart stage requires service names after '--' (e.g. ./local-start-hive.sh restart -- grafana ui)" >&2
    exit 1
  fi
  stage_header "Restarting services: ${SERVICE_ARGS[*]}"
  compose build "${SERVICE_ARGS[@]}"
  compose up -d "${SERVICE_ARGS[@]}"
}

resolve_stages() {
  if [[ $# -eq 0 ]]; then
    SELECTED_STAGES=("${DEFAULT_STAGES[@]}")
    return
  fi

  local args=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        usage
        exit 0
        ;;
      --all|all)
        SELECTED_STAGES=("${ALL_STAGES[@]}")
        return
        ;;
      clean|build-core|build-bees|push|start|restart)
        args+=("$1")
        ;;
      --)
        shift
        SERVICE_ARGS=("$@")
        break
        ;;
      *)
        echo "Unknown stage: $1" >&2
        usage >&2
        exit 1
        ;;
    esac
    shift
  done

  if [[ ${#args[@]} -eq 0 ]]; then
    echo "No stages selected." >&2
    usage >&2
    exit 1
  fi

  SELECTED_STAGES=("${args[@]}")
}

run_stage() {
  local stage="$1"
  local start_time=$(date +%s)

  case "$stage" in
    clean) run_clean ;;
    build-core) run_build_core ;;
    build-bees) run_build_bees ;;
    push) run_push ;;
    start) run_start ;;
    restart) run_restart ;;
    *) echo "Unknown stage: $stage" >&2; exit 1 ;;
  esac

  local end_time=$(date +%s)
  STAGE_TIMES["$stage"]=$((end_time - start_time))
}

main() {
  require_tools
  resolve_stages "$@"

  local total_start=$(date +%s)

  for stage in "${SELECTED_STAGES[@]}"; do
    run_stage "$stage"
  done

  local total_end=$(date +%s)
  local total_time=$((total_end - total_start))

  echo
  echo "=== Timing Summary ==="
  for stage in "${SELECTED_STAGES[@]}"; do
    printf "  %-12s %s\n" "$stage:" "$(format_duration ${STAGE_TIMES[$stage]})"
  done
  echo "  ────────────────────"
  printf "  %-12s %s\n" "Total:" "$(format_duration $total_time)"
  echo
  echo "PocketHive local stack setup complete."
}

main "$@"
