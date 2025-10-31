#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

CORE_SERVICES=(rabbitmq log-aggregator scenario-manager orchestrator ui prometheus grafana loki wiremock)
BEE_SERVICES=(swarm-controller generator moderator processor postprocessor trigger)
ALL_STAGES=(clean build-core build-bees start)

declare -A STAGE_TIMES

usage() {
  cat <<USAGE
Usage: $(basename "$0") [stage ...]

Stages:
  clean        Stop the compose stack and remove stray swarm containers.
  build-core   Build core PocketHive service images (RabbitMQ, UI, etc.).
  build-bees   Build swarm controller and bee images.
  start        Launch the PocketHive stack via docker compose up -d.

Examples:
  $(basename "$0")            Run all stages in order.
  $(basename "$0") clean start  Only clean the stack and start it (skip builds).
  $(basename "$0") build-bees   Build the bee images only.
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

run_start() {
  stage_header "Starting PocketHive stack"
  docker compose up -d
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
      clean|build-core|build-bees|start)
        args+=("$1")
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
    start) run_start ;;
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
