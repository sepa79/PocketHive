#!/bin/sh
set -e

HOST="${RABBITMQ_HOST:-rabbitmq}"
QUEUE="${PH_LOGS_QUEUE:-ph.logs.agg}"
USER="${RABBITMQ_USER:-guest}"
PASS="${RABBITMQ_PASS:-guest}"

log() {
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1"
}

log "waiting for RabbitMQ queue '$QUEUE' on '$HOST'"
while ! wget -qO- --user="$USER" --password="$PASS" "http://$HOST:15672/api/queues/%2f/$QUEUE" >/dev/null 2>&1; do
  log "queue not found, retrying in 5s"
  sleep 5
done
log "queue detected, starting log-aggregator"

exec java -jar /app/app.jar
