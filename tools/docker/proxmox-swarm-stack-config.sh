#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

OUTPUT_FILE=""

usage() {
  cat <<'USAGE'
Usage: tools/docker/proxmox-swarm-stack-config.sh [options]

Render the Proxmox Swarm stack config from docker-compose.yml plus
deploy/compose.proxmox-swarm.yml.

Options:
  --output <path>  Write the rendered stack config to a file instead of stdout.
  --help           Show this help.

Required environment:
  DOCKER_REGISTRY
  POCKETHIVE_VERSION
  POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX
  POCKETHIVE_SWARM_ROOT
  POCKETHIVE_SCENARIOS_RUNTIME_ROOT
USAGE
}

fail() {
  echo "proxmox-swarm-stack-config: $*" >&2
  exit 1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --output)
        [[ $# -ge 2 ]] || fail "--output requires a value"
        OUTPUT_FILE="$2"
        shift 2
        ;;
      --output=*)
        OUTPUT_FILE="${1#*=}"
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        fail "unknown option: $1"
        ;;
    esac
  done
}

render_config() {
  docker compose \
    -f docker-compose.yml \
    -f deploy/compose.proxmox-swarm.yml \
    config --format json \
    | node tools/docker/stack-compose-normalize.mjs
}

main() {
  parse_args "$@"
  command -v docker >/dev/null 2>&1 || fail "docker is required"
  command -v node >/dev/null 2>&1 || fail "node is required"
  [[ -n "${DOCKER_REGISTRY:-}" ]] || fail "DOCKER_REGISTRY is required"
  [[ -n "${POCKETHIVE_VERSION:-}" ]] || fail "POCKETHIVE_VERSION is required"
  [[ -n "${POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX:-}" ]] || fail "POCKETHIVE_CONTROL_PLANE_ORCHESTRATOR_IMAGE_REPOSITORY_PREFIX is required"
  [[ -n "${POCKETHIVE_SWARM_ROOT:-}" ]] || fail "POCKETHIVE_SWARM_ROOT is required"
  [[ -n "${POCKETHIVE_SCENARIOS_RUNTIME_ROOT:-}" ]] || fail "POCKETHIVE_SCENARIOS_RUNTIME_ROOT is required"

  if [[ -n "${OUTPUT_FILE}" ]]; then
    render_config > "${OUTPUT_FILE}"
  else
    render_config
  fi
}

main "$@"
