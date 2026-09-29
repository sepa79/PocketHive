#!/usr/bin/env bash
# Own disposable build fixtures and their connection environment; never use stack data.
set -euo pipefail
if (( $# == 0 )); then
  echo "Usage: $0 command [args...]" >&2
  exit 2
fi
fixture_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ ! -v AUTH_OPENSSL_TEST_EXECUTABLE ]]; then
  AUTH_OPENSSL_TEST_EXECUTABLE=$(command -v openssl)
fi
if [[ "$AUTH_OPENSSL_TEST_EXECUTABLE" != /* || ! -x "$AUTH_OPENSSL_TEST_EXECUTABLE" ]]; then
  echo "AUTH_OPENSSL_TEST_EXECUTABLE must identify an absolute executable OpenSSL path" >&2
  exit 2
fi
export AUTH_OPENSSL_TEST_EXECUTABLE
"$AUTH_OPENSSL_TEST_EXECUTABLE" version
docker compose version >/dev/null
fixture_run=$(mktemp -d /tmp/ph-build-tests-XXXXXXXX)
compose=(docker compose -p "$(basename "$fixture_run" | tr '[:upper:]' '[:lower:]')" -f "$fixture_dir/infrastructure.compose.yml")
cleanup() {
  local result=$?
  trap - EXIT
  if (( result != 0 )); then
    "${compose[@]}" logs --no-color >&2 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans || result=1
  rmdir "$fixture_run" || result=1
  exit "$result"
}
# Job control gives each owned command a process group, including its descendants.
set -m
child_pid=
run_owned() {
  local result=0
  "$@" &
  child_pid=$!
  wait "$child_pid" || result=$?
  child_pid=
  return "$result"
}
interrupt() {
  local result=$1 watchdog
  trap '' INT TERM
  if [[ -n "$child_pid" ]]; then
    kill -TERM -- "-$child_pid" 2>/dev/null || true
    # Bound shutdown even if the command ignores TERM; then reap before cleanup.
    (sleep 5; kill -KILL -- "-$child_pid" 2>/dev/null || true) &
    watchdog=$!
    wait "$child_pid" 2>/dev/null || true
    kill -KILL -- "-$child_pid" 2>/dev/null || true
    kill -KILL -- "-$watchdog" 2>/dev/null || true
    wait "$watchdog" 2>/dev/null || true
    child_pid=
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'interrupt 130' INT
trap 'interrupt 143' TERM
run_owned "${compose[@]}" up -d --wait --wait-timeout 150
port() {
  local address
  address=$("${compose[@]}" port "$1" "$2")
  [[ "$address" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || {
    echo "Unexpected fixture address: $address" >&2
    return 1
  }
  printf '%s' "${BASH_REMATCH[1]}"
}
export AUTH_REDIS_TEST_HOST=127.0.0.1
AUTH_REDIS_TEST_PORT=$(port redis 6379)
export AUTH_REDIS_TEST_PORT
export RABBITMQ_TEST_HOSTNAME=127.0.0.1
RABBITMQ_TEST_PORT=$(port rabbitmq 5672)
export RABBITMQ_TEST_PORT
rabbit_admin_port=$(port rabbitmq 15672)
export RABBITMQ_TEST_ADMIN_URI="http://127.0.0.1:$rabbit_admin_port/api/"
export RABBITMQ_TEST_USER=guest RABBITMQ_TEST_PASSWORD=guest
export RABBITMQ_TEST_ADMIN_USER=guest RABBITMQ_TEST_ADMIN_PASSWORD=guest
export RABBITMQ_SERVER_REQUIRED=true
run_owned "$@"
