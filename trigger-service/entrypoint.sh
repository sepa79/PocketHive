#!/bin/sh
set -eu

require() {
  if [ -z "$2" ]; then
    echo "Missing required environment variable: $1" >&2
    exit 1
  fi
}

HOST="${SPRING_RABBITMQ_HOST}"
USER="${SPRING_RABBITMQ_USERNAME}"
PASS="${SPRING_RABBITMQ_PASSWORD}"

require "SPRING_RABBITMQ_HOST" "$HOST"
require "SPRING_RABBITMQ_USERNAME" "$USER"
require "SPRING_RABBITMQ_PASSWORD" "$PASS"

log(){
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1"
}

log "waiting for RabbitMQ on '$HOST'"
while ! curl -fsS -u "$USER:$PASS" "http://$HOST:15672/api/healthchecks/node" >/dev/null; do
  log "RabbitMQ not ready, retrying in 5s"
  sleep 5
done
log "RabbitMQ ready, starting trigger-service"

exec java -jar /app/app.jar
