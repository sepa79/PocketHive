#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${POCKETHIVE_PROXMOX_CONFIG:-}"
PROXMOX_HOST=""
PROXMOX_USER=""
SSH_KEY=""
VMIDS=()

usage() {
  cat <<'USAGE'
Usage: tools/docker/proxmox-swarm-inventory.sh [options]

Read-only inventory for PocketHive Proxmox Swarm nodes. It uses Proxmox
`pct exec` because direct SSH to individual LXC nodes can be flaky.

Options:
  --config <path>        Load shell-style configuration before parsing options.
                         Defaults to POCKETHIVE_PROXMOX_CONFIG if set.
  --proxmox-host <host>  Proxmox host.
  --proxmox-user <user>  Proxmox SSH user.
  --ssh-key <path>       SSH private key.
  --vmid <id>            LXC VMID to inspect (repeatable).
  --help                Show this help.

Configuration variables:
  POCKETHIVE_PROXMOX_HOST
  POCKETHIVE_PROXMOX_USER
  POCKETHIVE_PROXMOX_VMIDS="240 241 242 243"
  POCKETHIVE_SSH_KEY
  POCKETHIVE_PROXMOX_CONFIG
USAGE
}

fail() {
  echo "proxmox-swarm-inventory: $*" >&2
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
  local vmids=()

  PROXMOX_HOST="${POCKETHIVE_PROXMOX_HOST:-}"
  PROXMOX_USER="${POCKETHIVE_PROXMOX_USER:-}"
  SSH_KEY="${POCKETHIVE_SSH_KEY:-}"

  if [[ -n "${POCKETHIVE_PROXMOX_VMIDS:-}" ]]; then
    read -r -a vmids <<<"${POCKETHIVE_PROXMOX_VMIDS//,/ }"
    VMIDS=("${vmids[@]}")
  fi
}

require_value() {
  local name="$1"
  local value="$2"
  local hint="$3"
  [[ -n "${value}" ]] || fail "${name} is required (${hint})"
}

parse_args() {
  local custom_vmids=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --config)
        [[ $# -ge 2 ]] || fail "--config requires a value"
        shift 2
        ;;
      --config=*)
        shift
        ;;
      --proxmox-host)
        [[ $# -ge 2 ]] || fail "--proxmox-host requires a value"
        PROXMOX_HOST="$2"
        shift 2
        ;;
      --proxmox-host=*)
        PROXMOX_HOST="${1#*=}"
        shift
        ;;
      --proxmox-user)
        [[ $# -ge 2 ]] || fail "--proxmox-user requires a value"
        PROXMOX_USER="$2"
        shift 2
        ;;
      --proxmox-user=*)
        PROXMOX_USER="${1#*=}"
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
      --vmid)
        [[ $# -ge 2 ]] || fail "--vmid requires a value"
        if ! "${custom_vmids}"; then
          VMIDS=()
          custom_vmids=true
        fi
        VMIDS+=("$2")
        shift 2
        ;;
      --vmid=*)
        if ! "${custom_vmids}"; then
          VMIDS=()
          custom_vmids=true
        fi
        VMIDS+=("${1#*=}")
        shift
        ;;
      --help)
        usage
        exit 0
        ;;
      *)
        fail "unknown option: $1"
        ;;
    esac
  done
}

remote_script() {
  cat <<'SCRIPT'
set -eu
echo "hostname=$(hostname)"
echo "docker=$(docker --version 2>/dev/null || true)"
echo "swarm=$(docker info --format 'LocalNodeState={{.Swarm.LocalNodeState}} ControlAvailable={{.Swarm.ControlAvailable}}' 2>/dev/null || true)"
echo "paths:"
for p in \
  /opt/pockethive \
  /opt/pockethive/runtime \
  /opt/pockethive/scenarios-runtime \
  /opt/pockethive/swarm \
  /opt/pockethive/swarm/scenarios \
  /opt/pockethive/swarm/wiremock \
  /opt/pockethive/swarm/rabbitmq \
  /opt/pockethive/swarm/postgres \
  /opt/pockethive/swarm/clickhouse \
  /opt/pockethive/swarm/grafana \
  /opt/pockethive/swarm/loki \
  /opt/pockethive/swarm/prometheus \
  /opt/pockethive/swarm/haproxy \
  /opt/pockethive/swarm/tcp-mock
do
  if [ -e "$p" ]; then
    stat -c '  %A %U:%G %n' "$p"
  else
    echo "  missing $p"
  fi
done
SCRIPT
}

inspect_node() {
  local vmid="$1"
  echo "=== VMID ${vmid} ==="
  ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 \
    "${PROXMOX_USER}@${PROXMOX_HOST}" \
    "pct exec $(printf '%q' "${vmid}") -- sh -lc $(printf '%q' "$(remote_script)")"
}

main() {
  find_config_arg "$@"
  if [[ -n "${CONFIG_FILE}" ]]; then
    load_config
  fi
  load_env_defaults
  parse_args "$@"
  command -v ssh >/dev/null 2>&1 || fail "ssh is required"
  require_value "Proxmox host" "${PROXMOX_HOST}" "--proxmox-host or POCKETHIVE_PROXMOX_HOST"
  require_value "Proxmox user" "${PROXMOX_USER}" "--proxmox-user or POCKETHIVE_PROXMOX_USER"
  require_value "SSH key" "${SSH_KEY}" "--ssh-key or POCKETHIVE_SSH_KEY"
  [[ "${#VMIDS[@]}" -gt 0 ]] || fail "at least one VMID is required (--vmid or POCKETHIVE_PROXMOX_VMIDS)"
  [[ -f "${SSH_KEY}" ]] || fail "SSH key does not exist: ${SSH_KEY}"

  echo "Proxmox: ${PROXMOX_USER}@${PROXMOX_HOST}"
  echo "VMIDs: ${VMIDS[*]}"
  for vmid in "${VMIDS[@]}"; do
    inspect_node "${vmid}"
  done
}

main "$@"
