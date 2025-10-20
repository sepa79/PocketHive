#!/bin/sh
set -e

HOST="${SPRING_RABBITMQ_HOST:-rabbitmq}"
PORT="${SPRING_RABBITMQ_PORT:-5672}"
USER="${SPRING_RABBITMQ_USERNAME:-guest}"
PASS="${SPRING_RABBITMQ_PASSWORD:-guest}"
VHOST="${SPRING_RABBITMQ_VIRTUAL_HOST:-/}"

log(){
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1"
}

log "waiting for RabbitMQ on '$HOST' (port $PORT, vhost '$VHOST')"
while ! curl -fsS -u "$USER:$PASS" "http://$HOST:15672/api/healthchecks/node" >/dev/null; do
  log "RabbitMQ not ready, retrying in 5s"
  sleep 5
done
log "RabbitMQ ready, starting swarm-controller-service"

exec java -jar /app/app.jar
