#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

CORE_SERVICES=(rabbitmq log-aggregator scenario-manager orchestrator ui prometheus grafana loki wiremock)
BEE_SERVICES=(swarm-controller generator moderator processor postprocessor trigger)
ALL_STAGES=(clean build-core build-bees start push restart)

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
  build-bees   Build swarm controller and bee images.
  push         Push images to configured registry.
  start        Launch the PocketHive stack via docker compose up -d.
  restart      Rebuild and restart only the services listed after '--'.

Environment Variables:
  DOCKER_REGISTRY       Registry prefix (e.g., 'myregistry.io/' or 'localhost:5000/')
  POCKETHIVE_VERSION    Image tag version (default: latest)

Examples:
  $(basename "$0")                                    Run all stages in order.
  $(basename "$0") clean start                        Only clean and start (skip builds).
  $(basename "$0") build-bees                         Build bee images only.
  $(basename "$0") restart -- grafana ui              Rebuild + restart only Grafana and UI.
  DOCKER_REGISTRY=myregistry.io/ $(basename "$0") push  Push to external registry.
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
  docker compose down --remove-orphans
}

run_build_core() {
  stage_header "Building core PocketHive services"
  docker compose build "${CORE_SERVICES[@]}"
}

run_build_bees() {
  stage_header "Building swarm controller and bee images"
  docker compose --profile bees build "${BEE_SERVICES[@]}"
}

run_push() {
  stage_header "Pushing images to registry"
  if [[ -z "$DOCKER_REGISTRY" ]]; then
    echo "DOCKER_REGISTRY not set. Skipping push." >&2
    return 0
  fi
  echo "Pushing to registry: ${DOCKER_REGISTRY}"
  docker compose push "${CORE_SERVICES[@]}"
  docker compose --profile bees push "${BEE_SERVICES[@]}"
}

run_start() {
  stage_header "Starting PocketHive stack"
  docker compose up -d
}

run_restart() {
  if [[ ${#SERVICE_ARGS[@]} -eq 0 ]]; then
    echo "restart stage requires service names after '--' (e.g. ./start-hive.sh restart -- grafana ui)" >&2
    exit 1
  fi
  stage_header "Restarting services: ${SERVICE_ARGS[*]}"
  docker compose build "${SERVICE_ARGS[@]}"
  docker compose up -d "${SERVICE_ARGS[@]}"
}

resolve_stages() {
  if [[ $# -eq 0 ]]; then
    SELECTED_STAGES=("${ALL_STAGES[@]}")
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
  echo "PocketHive stack setup complete."
}

main "$@"
