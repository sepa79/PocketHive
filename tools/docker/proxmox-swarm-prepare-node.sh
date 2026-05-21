#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

CONFIG_FILE="${POCKETHIVE_PROXMOX_CONFIG:-}"
TRANSPORT=""
REMOTE_USER=""
REMOTE_HOST=""
REMOTE_ROOT=""
SCENARIOS_RUNTIME_ROOT=""
PROXMOX_USER=""
PROXMOX_HOST=""
PROXMOX_VMID=""
SSH_KEY=""
DRY_RUN=false

usage() {
  cat <<'USAGE'
Usage: tools/docker/proxmox-swarm-prepare-node.sh [options]

Prepare one Swarm node with the explicit PocketHive static mount layout.
This syncs static config/source inputs only; it does not deploy services.

Options:
  --config <path>       Load shell-style configuration before parsing options.
                        Defaults to POCKETHIVE_PROXMOX_CONFIG if set.
  --transport <mode>    Transport: ssh or proxmox.
  --host <ip-or-host>   Remote Swarm node.
  --proxmox-host <host> Proxmox host for proxmox transport.
  --proxmox-user <user> Proxmox SSH user for proxmox transport.
  --proxmox-vmid <id>   LXC VMID for proxmox transport.
  --user <name>         SSH user.
  --ssh-key <path>      SSH private key.
  --remote-root <path>  Remote Swarm root.
  --dry-run             Print commands without executing them
  --help                Show this help.

Configuration variables:
  POCKETHIVE_SWARM_TRANSPORT=ssh|proxmox
  POCKETHIVE_SWARM_NODE_HOST
  POCKETHIVE_SWARM_NODE_VMID
  POCKETHIVE_SWARM_REMOTE_USER or POCKETHIVE_REMOTE_USER
  POCKETHIVE_SWARM_ROOT
  POCKETHIVE_SCENARIOS_RUNTIME_ROOT
  POCKETHIVE_PROXMOX_HOST
  POCKETHIVE_PROXMOX_USER
  POCKETHIVE_SSH_KEY
  POCKETHIVE_PROXMOX_CONFIG

Synced inputs:
  rabbitmq/ -> <root>/rabbitmq
  scenarios/ -> <root>/scenarios
  <shared-runtime-root> prepared for generated scenario runtime files
  wiremock/mappings -> <root>/wiremock/mappings
  wiremock/__files -> <root>/wiremock/__files
  tcp-mock-server/mappings -> <root>/tcp-mock/mappings
  tcp-mock-server/__files -> <root>/tcp-mock/__files
  clickhouse/init -> <root>/clickhouse/init
  grafana/grafana.ini -> <root>/grafana/config/grafana.ini
  grafana/provisioning -> <root>/grafana/provisioning
  grafana/dashboards -> <root>/grafana/dashboards
  loki/config.yml -> <root>/loki/config.yml
  prometheus/prometheus.yml -> <root>/prometheus/config.yml
  deploy/nginx.proxmox-swarm.conf -> <root>/nginx/default.conf
USAGE
}

fail() {
  echo "proxmox-swarm-prepare-node: $*" >&2
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
  TRANSPORT="${POCKETHIVE_SWARM_TRANSPORT:-}"
  REMOTE_USER="${POCKETHIVE_SWARM_REMOTE_USER:-${POCKETHIVE_REMOTE_USER:-}}"
  REMOTE_HOST="${POCKETHIVE_SWARM_NODE_HOST:-}"
  REMOTE_ROOT="${POCKETHIVE_SWARM_ROOT:-}"
  SCENARIOS_RUNTIME_ROOT="${POCKETHIVE_SCENARIOS_RUNTIME_ROOT:-}"
  PROXMOX_USER="${POCKETHIVE_PROXMOX_USER:-}"
  PROXMOX_HOST="${POCKETHIVE_PROXMOX_HOST:-}"
  PROXMOX_VMID="${POCKETHIVE_SWARM_NODE_VMID:-}"
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

proxmox_target() {
  printf '%s@%s' "${PROXMOX_USER}" "${PROXMOX_HOST}"
}

remote_prepare_script() {
  cat <<SCRIPT
set -eu
root=$(printf '%q' "${REMOTE_ROOT}")
runtime_root=$(printf '%q' "${SCENARIOS_RUNTIME_ROOT}")
mkdir -p \
  "\$root/rabbitmq" \
  "\$root/rabbitmq/data" \
  "\$root/postgres/data" \
  "\$root/clickhouse/init" \
  "\$root/clickhouse/data" \
  "\$root/grafana/config" \
  "\$root/grafana/provisioning" \
  "\$root/grafana/dashboards" \
  "\$root/grafana/data" \
  "\$root/loki/data" \
  "\$root/nginx" \
  "\$root/prometheus/data" \
  "\$root/scenarios" \
  "\$root/wiremock/mappings" \
  "\$root/wiremock/__files" \
  "\$root/tcp-mock/mappings" \
  "\$root/tcp-mock/__files" \
  "\$root/tcp-mock/data" \
  "\$root/tcp-mock/tls-data" \
  "\$root/haproxy/runtime" \
  "\$runtime_root"
SCRIPT
}

validate_inputs() {
  command -v ssh >/dev/null 2>&1 || fail "ssh is required"
  command -v tar >/dev/null 2>&1 || fail "tar is required"
  require_value "transport" "${TRANSPORT}" "--transport or POCKETHIVE_SWARM_TRANSPORT"
  [[ "${TRANSPORT}" == "ssh" || "${TRANSPORT}" == "proxmox" ]] || fail "transport must be ssh or proxmox"
  require_value "remote root" "${REMOTE_ROOT}" "--remote-root or POCKETHIVE_SWARM_ROOT"
  require_value "SSH key" "${SSH_KEY}" "--ssh-key or POCKETHIVE_SSH_KEY"
  if [[ "${TRANSPORT}" == "ssh" ]]; then
    require_value "remote host" "${REMOTE_HOST}" "--host or POCKETHIVE_SWARM_NODE_HOST"
    require_value "remote user" "${REMOTE_USER}" "--user or POCKETHIVE_SWARM_REMOTE_USER/POCKETHIVE_REMOTE_USER"
  else
    require_value "Proxmox host" "${PROXMOX_HOST}" "--proxmox-host or POCKETHIVE_PROXMOX_HOST"
    require_value "Proxmox user" "${PROXMOX_USER}" "--proxmox-user or POCKETHIVE_PROXMOX_USER"
    require_value "Proxmox VMID" "${PROXMOX_VMID}" "--proxmox-vmid or POCKETHIVE_SWARM_NODE_VMID"
  fi
  require_value "shared scenarios runtime root" "${SCENARIOS_RUNTIME_ROOT}" "--config POCKETHIVE_SCENARIOS_RUNTIME_ROOT"
  if ! "${DRY_RUN}"; then
    [[ -f "${SSH_KEY}" ]] || fail "SSH key does not exist: ${SSH_KEY}"
  fi
  [[ -d rabbitmq ]] || fail "rabbitmq directory is missing"
  [[ -d scenarios ]] || fail "scenarios directory is missing"
  [[ -d wiremock/mappings ]] || fail "wiremock/mappings directory is missing"
  [[ -d wiremock/__files ]] || fail "wiremock/__files directory is missing"
  [[ -d tcp-mock-server/mappings ]] || fail "tcp-mock-server/mappings directory is missing"
  [[ -d tcp-mock-server/__files ]] || fail "tcp-mock-server/__files directory is missing"
  [[ -d clickhouse/init ]] || fail "clickhouse/init directory is missing"
  [[ -f grafana/grafana.ini ]] || fail "grafana/grafana.ini is missing"
  [[ -d grafana/provisioning ]] || fail "grafana/provisioning directory is missing"
  [[ -d grafana/dashboards ]] || fail "grafana/dashboards directory is missing"
  [[ -f loki/config.yml ]] || fail "loki/config.yml is missing"
  [[ -f prometheus/prometheus.yml ]] || fail "prometheus/prometheus.yml is missing"
  [[ -f deploy/nginx.proxmox-swarm.conf ]] || fail "deploy/nginx.proxmox-swarm.conf is missing"
}

sync_inputs() {
  if [[ "${TRANSPORT}" == "ssh" ]]; then
    sync_inputs_via_ssh
  else
    sync_inputs_via_proxmox
  fi
}

sync_inputs_via_ssh() {
  run_cmd ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(ssh_target)" "$(remote_prepare_script)"

  if "${DRY_RUN}"; then
    echo "+ tar selected static inputs | ssh $(ssh_target) tar -C ${REMOTE_ROOT} -xf -"
    return
  fi

  tar -cf - \
    rabbitmq \
    scenarios \
    wiremock/mappings \
    wiremock/__files \
    tcp-mock-server/mappings \
    tcp-mock-server/__files \
    clickhouse/init \
    grafana/grafana.ini \
    grafana/provisioning \
    grafana/dashboards \
    loki/config.yml \
    prometheus/prometheus.yml \
    deploy/nginx.proxmox-swarm.conf \
    | ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(ssh_target)" \
      "sh -lc $(printf '%q' "$(remote_sync_script)")"
}

sync_inputs_via_proxmox() {
  run_cmd ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(proxmox_target)" \
    "pct exec $(printf '%q' "${PROXMOX_VMID}") -- sh -lc $(printf '%q' "$(remote_prepare_script)")"

  if "${DRY_RUN}"; then
    echo "+ tar selected static inputs | ssh $(proxmox_target) pct exec ${PROXMOX_VMID} -- sh -lc 'tar/move into ${REMOTE_ROOT}'"
    return
  fi

  tar -cf - \
    rabbitmq \
    scenarios \
    wiremock/mappings \
    wiremock/__files \
    tcp-mock-server/mappings \
    tcp-mock-server/__files \
    clickhouse/init \
    grafana/grafana.ini \
    grafana/provisioning \
    grafana/dashboards \
    loki/config.yml \
    prometheus/prometheus.yml \
    deploy/nginx.proxmox-swarm.conf \
    | ssh -i "${SSH_KEY}" -o BatchMode=yes -o ConnectTimeout=8 "$(proxmox_target)" \
      "pct exec $(printf '%q' "${PROXMOX_VMID}") -- sh -lc $(printf '%q' "$(remote_sync_script)")"
}

remote_sync_script() {
  cat <<SCRIPT
set -eu
root=$(printf '%q' "${REMOTE_ROOT}")
runtime_root=$(printf '%q' "${SCENARIOS_RUNTIME_ROOT}")
tmp=\$(mktemp -d)
tar -C "\$tmp" -xf -
rm -rf "\$root/rabbitmq" \
       "\$root/wiremock/mappings" \
       "\$root/wiremock/__files" \
       "\$root/tcp-mock/mappings" \
       "\$root/tcp-mock/__files" \
       "\$root/clickhouse/init" \
       "\$root/grafana/provisioning" \
       "\$root/grafana/dashboards" \
       "\$root/nginx/default.conf"
mv "\$tmp/rabbitmq" "\$root/rabbitmq"
mkdir -p "\$root/scenarios"
find "\$root/scenarios" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -a "\$tmp/scenarios/." "\$root/scenarios/"
mkdir -p "\$root/wiremock" "\$root/tcp-mock" "\$root/clickhouse" "\$root/grafana/config" "\$root/loki" "\$root/nginx" "\$root/prometheus"
mv "\$tmp/wiremock/mappings" "\$root/wiremock/mappings"
mv "\$tmp/wiremock/__files" "\$root/wiremock/__files"
mv "\$tmp/tcp-mock-server/mappings" "\$root/tcp-mock/mappings"
mv "\$tmp/tcp-mock-server/__files" "\$root/tcp-mock/__files"
mv "\$tmp/clickhouse/init" "\$root/clickhouse/init"
mv "\$tmp/grafana/provisioning" "\$root/grafana/provisioning"
mv "\$tmp/grafana/dashboards" "\$root/grafana/dashboards"
mv "\$tmp/grafana/grafana.ini" "\$root/grafana/config/grafana.ini"
mv "\$tmp/loki/config.yml" "\$root/loki/config.yml"
mv "\$tmp/deploy/nginx.proxmox-swarm.conf" "\$root/nginx/default.conf"
mv "\$tmp/prometheus/prometheus.yml" "\$root/prometheus/config.yml"
rm -rf "\$tmp"
mkdir -p \
  "\$root/rabbitmq/data" \
  "\$root/postgres/data" \
  "\$root/clickhouse/data" \
  "\$root/grafana/data" \
  "\$root/loki/data" \
  "\$root/prometheus/data" \
  "\$root/tcp-mock/data" \
  "\$root/tcp-mock/tls-data" \
  "\$root/haproxy/runtime" \
  "\$runtime_root"
chown -R root:root \
  "\$root/rabbitmq" \
  "\$root/scenarios" \
  "\$root/wiremock" \
  "\$root/tcp-mock/mappings" \
  "\$root/tcp-mock/__files" \
  "\$root/clickhouse/init" \
  "\$root/grafana/config" \
  "\$root/grafana/provisioning" \
  "\$root/grafana/dashboards" \
  "\$root/loki/config.yml" \
  "\$root/nginx/default.conf" \
  "\$root/prometheus/config.yml"
chown -R 472:472 "\$root/grafana/data"
chown -R 65534:65534 "\$root/prometheus/data"
SCRIPT
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --config)
        [[ $# -ge 2 ]] || fail "--config requires a value"
        shift 2
        ;;
      --config=*)
        shift
        ;;
      --transport)
        [[ $# -ge 2 ]] || fail "--transport requires a value"
        TRANSPORT="$2"
        shift 2
        ;;
      --transport=*)
        TRANSPORT="${1#*=}"
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
      --proxmox-vmid)
        [[ $# -ge 2 ]] || fail "--proxmox-vmid requires a value"
        PROXMOX_VMID="$2"
        shift 2
        ;;
      --proxmox-vmid=*)
        PROXMOX_VMID="${1#*=}"
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
      --remote-root)
        [[ $# -ge 2 ]] || fail "--remote-root requires a value"
        REMOTE_ROOT="$2"
        shift 2
        ;;
      --remote-root=*)
        REMOTE_ROOT="${1#*=}"
        shift
        ;;
      --dry-run)
        DRY_RUN=true
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

main() {
  find_config_arg "$@"
  if [[ -n "${CONFIG_FILE}" ]]; then
    load_config
  fi
  load_env_defaults
  parse_args "$@"
  validate_inputs

  if [[ "${TRANSPORT}" == "ssh" ]]; then
    echo "Remote: $(ssh_target)"
  else
    echo "Proxmox: $(proxmox_target)"
    echo "VMID: ${PROXMOX_VMID}"
  fi
  echo "Remote root: ${REMOTE_ROOT}"
  sync_inputs
}

main "$@"
