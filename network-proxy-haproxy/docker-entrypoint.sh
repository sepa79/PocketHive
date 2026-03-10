#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${HAPROXY_CONFIG_FILE:-/opt/haproxy-runtime/haproxy.cfg}"
CONFIG_DIR="$(dirname "${CONFIG_FILE}")"
PID_FILE="/var/run/haproxy.pid"
DEFAULT_CONFIG='global
  log stdout format raw local0
  maxconn 4096
  stats socket /var/run/haproxy/admin.sock mode 660 level admin

defaults
  mode tcp
  log global
  timeout connect 5s
  timeout client 60s
  timeout server 60s
  option dontlognull

frontend healthcheck
  mode http
  bind *:8404
  http-request return status 200 content-type text/plain string ok if { path /healthz }
  stats enable
  stats uri /stats
'

mkdir -p "${CONFIG_DIR}" /var/run/haproxy
if [[ ! -f "${CONFIG_FILE}" ]]; then
  printf '%s\n' "${DEFAULT_CONFIG}" > "${CONFIG_FILE}"
fi

reload_haproxy() {
  if ! haproxy -c -f "${CONFIG_FILE}"; then
    echo "HAProxy config validation failed; keeping current config" >&2
    return 1
  fi
  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    haproxy -W -D -p "${PID_FILE}" -f "${CONFIG_FILE}" -sf "$(cat "${PID_FILE}")"
  else
    haproxy -W -D -p "${PID_FILE}" -f "${CONFIG_FILE}"
  fi
}

shutdown_haproxy() {
  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    kill -TERM "$(cat "${PID_FILE}")" 2>/dev/null || true
    wait "$(cat "${PID_FILE}")" 2>/dev/null || true
  fi
}

trap shutdown_haproxy EXIT INT TERM

reload_haproxy

while true; do
  inotifywait -q -e close_write,create,delete,move "${CONFIG_DIR}" >/dev/null 2>&1 || true
  reload_haproxy || true
done
