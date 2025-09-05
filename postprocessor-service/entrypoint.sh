#!/bin/sh
set -e

HOST="${RABBITMQ_HOST:-rabbitmq}"
USER="${RABBITMQ_USER:-guest}"
PASS="${RABBITMQ_PASS:-guest}"

log(){
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1"
}

log "waiting for RabbitMQ on '$HOST'"
while ! curl -fsS -u "$USER:$PASS" "http://$HOST:15672/api/healthchecks/node" >/dev/null; do
  log "RabbitMQ not ready, retrying in 5s"
  sleep 5
done
log "RabbitMQ ready, starting postprocessor-service"

exec java -jar /app/app.jar
