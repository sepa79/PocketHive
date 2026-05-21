#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

CONFIG_FILE="${POCKETHIVE_PROXMOX_CONFIG:-}"
REMOTE_USER=""
REMOTE_HOST=""
REMOTE_DIR=""
SSH_KEY=""
ENV_FILE=""
COPY_ENV=false
DRY_RUN=false
PULL_IMAGES=false
SERVICES=(
  rabbitmq
  postgres
  auth-service
  log-aggregator
  scenario-manager
  orchestrator
  pushgateway
  toxiproxy
  haproxy
  network-proxy-manager
  wiremock
  ui
)

usage() {
  cat <<'USAGE'
Usage: tools/docker/proxmox-reduced-runtime.sh [options]

Synchronize the reduced PocketHive runtime inputs to a configured remote Docker
host and run the explicit reduced docker compose profile.

Options:
  --config <path>        Load shell-style configuration before parsing options.
                         Defaults to POCKETHIVE_PROXMOX_CONFIG if set.
  --host <ip-or-host>    Remote Docker host.
  --user <name>          SSH user.
  --ssh-key <path>       SSH private key.
  --remote-dir <path>    Remote runtime directory.
  --env-file <path>      Copy this local env file to <remote-dir>/.env before deploy.
  --pull                 Run docker compose pull for selected services before up.
  --service <name>       Compose service to up (repeatable). Defaults to reduced runtime services.
  --dry-run              Print commands without executing them.
  --help                 Show this help.

Required remote inputs:
  --host, --user, --ssh-key, and --remote-dir must be provided explicitly,
  either as CLI options or through the config/env variables below.
  If --env-file is omitted, <remote-dir>/.env must already exist on the remote host.

Configuration variables:
  POCKETHIVE_REDUCED_REMOTE_HOST
  POCKETHIVE_REDUCED_REMOTE_USER or POCKETHIVE_REMOTE_USER
  POCKETHIVE_REDUCED_REMOTE_DIR
  POCKETHIVE_SSH_KEY
  POCKETHIVE_PROXMOX_CONFIG

Synced repository inputs:
  docker-compose.yml
  deploy/compose.proxmox-single-node.yml
  deploy/compose.proxmox-reduced.yml
  deploy/nginx.proxmox-reduced.conf
  rabbitmq/
  scenarios/
  wiremock/
USAGE
}

fail() {
  echo "proxmox-reduced-runtime: $*" >&2
  exit 1
}

load_config() {
  [[ -f "${CONFIG_FILE}" ]] || fail "config file does not exist: ${CONFIG_FILE}"
  set -a
  # shellcheck source=/dev/null
  . "${CONFIG_FILE}"
  set +a
}

find_config_arg() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --config)
        [[ $# -ge 2 ]] || fail "--config requires a value"
        CONFIG_FILE="$2"
        shift 2
        ;;
      --config=*)
        CONFIG_FILE="${1#*=}"
        shift
        ;;
      *)
        shift
        ;;
    esac
  done
}

load_env_defaults() {
  REMOTE_USER="${POCKETHIVE_REDUCED_REMOTE_USER:-${POCKETHIVE_REMOTE_USER:-}}"
  REMOTE_HOST="${POCKETHIVE_REDUCED_REMOTE_HOST:-}"
  REMOTE_DIR="${POCKETHIVE_REDUCED_REMOTE_DIR:-}"
  SSH_KEY="${POCKETHIVE_SSH_KEY:-}"
}

require_value() {
  local name="$1"
  local value="$2"
  local hint="$3"
  [[ -n "${value}" ]] || fail "${name} is required (${hint})"
}

run_cmd() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
  if ! "${DRY_RUN}"; then
    "$@"
  fi
}

ssh_target() {
  printf '%s@%s' "${REMOTE_USER}" "${REMOTE_HOST}"
}

ssh_base() {
  ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(ssh_target)" "$@"
}

remote_compose_cmd() {
  local services=("$@")
  local pull_cmd=""
  local up_services=""
  local svc

  for svc in "${services[@]}"; do
    up_services+=" $(printf '%q' "${svc}")"
  done

  if "${PULL_IMAGES}"; then
    pull_cmd="docker compose --env-file .env -f docker-compose.yml -f deploy/compose.proxmox-single-node.yml -f deploy/compose.proxmox-reduced.yml pull${up_services} && "
  fi

  printf 'cd %q && test -f .env && %sdocker compose --env-file .env -f docker-compose.yml -f deploy/compose.proxmox-single-node.yml -f deploy/compose.proxmox-reduced.yml up -d%s && docker ps --format "{{.Names}} {{.Status}}" | sort' \
    "${REMOTE_DIR}" "${pull_cmd}" "${up_services}"
}

validate_inputs() {
  command -v ssh >/dev/null 2>&1 || fail "ssh is required"
  command -v tar >/dev/null 2>&1 || fail "tar is required"
  require_value "remote host" "${REMOTE_HOST}" "--host or POCKETHIVE_REDUCED_REMOTE_HOST"
  require_value "remote user" "${REMOTE_USER}" "--user or POCKETHIVE_REDUCED_REMOTE_USER/POCKETHIVE_REMOTE_USER"
  require_value "remote dir" "${REMOTE_DIR}" "--remote-dir or POCKETHIVE_REDUCED_REMOTE_DIR"
  require_value "SSH key" "${SSH_KEY}" "--ssh-key or POCKETHIVE_SSH_KEY"
  if ! "${DRY_RUN}"; then
    [[ -f "${SSH_KEY}" ]] || fail "SSH key does not exist: ${SSH_KEY}"
  fi
  [[ -f docker-compose.yml ]] || fail "docker-compose.yml is missing"
  [[ -f deploy/compose.proxmox-single-node.yml ]] || fail "deploy/compose.proxmox-single-node.yml is missing"
  [[ -f deploy/compose.proxmox-reduced.yml ]] || fail "deploy/compose.proxmox-reduced.yml is missing"
  [[ -f deploy/nginx.proxmox-reduced.conf ]] || fail "deploy/nginx.proxmox-reduced.conf is missing"
  [[ -d rabbitmq ]] || fail "rabbitmq directory is missing"
  [[ -d scenarios ]] || fail "scenarios directory is missing"
  [[ -d wiremock ]] || fail "wiremock directory is missing"
  if "${COPY_ENV}"; then
    [[ -f "${ENV_FILE}" ]] || fail "env file does not exist: ${ENV_FILE}"
  fi
}

sync_inputs() {
  run_cmd ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(ssh_target)" "mkdir -p $(printf '%q' "${REMOTE_DIR}")"

  if "${DRY_RUN}"; then
    echo "+ tar selected runtime inputs | ssh $(ssh_target) tar -C ${REMOTE_DIR} -xf -"
  else
    tar -cf - \
      docker-compose.yml \
      deploy/compose.proxmox-single-node.yml \
      deploy/compose.proxmox-reduced.yml \
      deploy/nginx.proxmox-reduced.conf \
      rabbitmq \
      scenarios \
      wiremock \
      | ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(ssh_target)" "tar -C $(printf '%q' "${REMOTE_DIR}") -xf -"
  fi

  if "${COPY_ENV}"; then
    run_cmd scp -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "${ENV_FILE}" "$(ssh_target):${REMOTE_DIR}/.env"
  fi
}

deploy_runtime() {
  local remote_cmd
  remote_cmd="$(remote_compose_cmd "${SERVICES[@]}")"
  run_cmd ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(ssh_target)" "${remote_cmd}"
}

parse_args() {
  local custom_services=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --config)
        [[ $# -ge 2 ]] || fail "--config requires a value"
        shift 2
        ;;
      --config=*)
        shift
        ;;
      --host)
        [[ $# -ge 2 ]] || fail "--host requires a value"
        REMOTE_HOST="$2"
        shift 2
        ;;
      --host=*)
        REMOTE_HOST="${1#*=}"
        shift
        ;;
      --user)
        [[ $# -ge 2 ]] || fail "--user requires a value"
        REMOTE_USER="$2"
        shift 2
        ;;
      --user=*)
        REMOTE_USER="${1#*=}"
        shift
        ;;
      --ssh-key)
        [[ $# -ge 2 ]] || fail "--ssh-key requires a value"
        SSH_KEY="$2"
        shift 2
        ;;
      --ssh-key=*)
        SSH_KEY="${1#*=}"
        shift
        ;;
      --remote-dir)
        [[ $# -ge 2 ]] || fail "--remote-dir requires a value"
        REMOTE_DIR="$2"
        shift 2
        ;;
      --remote-dir=*)
        REMOTE_DIR="${1#*=}"
        shift
        ;;
      --env-file)
        [[ $# -ge 2 ]] || fail "--env-file requires a value"
        ENV_FILE="$2"
        COPY_ENV=true
        shift 2
        ;;
      --env-file=*)
        ENV_FILE="${1#*=}"
        COPY_ENV=true
        shift
        ;;
      --pull)
        PULL_IMAGES=true
        shift
        ;;
      --service)
        [[ $# -ge 2 ]] || fail "--service requires a value"
        if ! "${custom_services}"; then
          SERVICES=()
          custom_services=true
        fi
        SERVICES+=("$2")
        shift 2
        ;;
      --service=*)
        if ! "${custom_services}"; then
          SERVICES=()
          custom_services=true
        fi
        SERVICES+=("${1#*=}")
        shift
        ;;
      --dry-run)
        DRY_RUN=true
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

main() {
  find_config_arg "$@"
  if [[ -n "${CONFIG_FILE}" ]]; then
    load_config
  fi
  load_env_defaults
  parse_args "$@"
  validate_inputs

  echo "Remote: $(ssh_target)"
  echo "Remote dir: ${REMOTE_DIR}"
  if "${COPY_ENV}"; then
    echo "Env file: ${ENV_FILE} -> ${REMOTE_DIR}/.env"
  else
    echo "Env file: using existing ${REMOTE_DIR}/.env"
  fi
  echo "Services:"
  printf ' - %s\n' "${SERVICES[@]}"

  sync_inputs
  deploy_runtime
}

main "$@"
