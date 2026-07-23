#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${HAPROXY_CONFIG_FILE:?HAPROXY_CONFIG_FILE must be set}"
CONFIG_DIR="$(dirname "${CONFIG_FILE}")"
APPLIED_HASH_FILE="${HAPROXY_APPLIED_DIGEST_FILE:?HAPROXY_APPLIED_DIGEST_FILE must be set}"
ACTIVE_CONFIG_FILE="/var/run/pockethive-haproxy.cfg"
POLL_INTERVAL_SECONDS="${HAPROXY_CONFIG_POLL_INTERVAL_SECONDS:-1}"
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

config_hash() {
  sha256sum "$1" | awk '{print $1}'
}

publish_applied_hash() {
  local hash="$1"
  local temp_file="${APPLIED_HASH_FILE}.tmp.$$"
  printf '%s\n' "${hash}" > "${temp_file}"
  mv -f "${temp_file}" "${APPLIED_HASH_FILE}"
}

reload_haproxy() {
  local desired_hash candidate_hash current_hash
  local candidate_file="${ACTIVE_CONFIG_FILE}.candidate.$$"
  desired_hash="$(config_hash "${CONFIG_FILE}")" || return 1
  cp "${CONFIG_FILE}" "${candidate_file}" || return 1
  candidate_hash="$(config_hash "${candidate_file}")" || return 1
  current_hash="$(config_hash "${CONFIG_FILE}")" || return 1
  if [[ "${desired_hash}" != "${candidate_hash}" || "${candidate_hash}" != "${current_hash}" ]]; then
    rm -f "${candidate_file}"
    echo "HAProxy config changed while reading; it will be checked on the next poll" >&2
    return 1
  fi
  if ! haproxy -c -f "${candidate_file}"; then
    rm -f "${candidate_file}"
    echo "HAProxy config validation failed; keeping current config" >&2
    return 2
  fi
  mv -f "${candidate_file}" "${ACTIVE_CONFIG_FILE}" || return 1
  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    if ! haproxy -W -D -p "${PID_FILE}" -f "${ACTIVE_CONFIG_FILE}" -sf "$(cat "${PID_FILE}")"; then
      echo "HAProxy reload failed; applied digest was not updated" >&2
      return 1
    fi
  else
    if ! haproxy -W -D -p "${PID_FILE}" -f "${ACTIVE_CONFIG_FILE}"; then
      echo "HAProxy start failed; applied digest was not updated" >&2
      return 1
    fi
  fi
  if ! publish_applied_hash "${candidate_hash}"; then
    echo "HAProxy applied digest could not be published" >&2
    return 1
  fi
  LAST_APPLIED_HASH="${candidate_hash}"
}

shutdown_haproxy() {
  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
    kill -TERM "$(cat "${PID_FILE}")" 2>/dev/null || true
    wait "$(cat "${PID_FILE}")" 2>/dev/null || true
  fi
}

trap shutdown_haproxy EXIT INT TERM

LAST_APPLIED_HASH=""
REJECTED_HASH=""
reload_haproxy

while true; do
  sleep "${POLL_INTERVAL_SECONDS}"
  if ! desired_hash="$(config_hash "${CONFIG_FILE}")"; then
    echo "HAProxy config could not be read; it will be checked on the next poll" >&2
    continue
  fi
  if [[ "${desired_hash}" != "${LAST_APPLIED_HASH}" && "${desired_hash}" != "${REJECTED_HASH}" ]]; then
    if reload_haproxy; then
      REJECTED_HASH=""
    else
      reload_status=$?
      if [[ "${reload_status}" -eq 2 ]]; then
        REJECTED_HASH="${desired_hash}"
      fi
    fi
  fi
done
