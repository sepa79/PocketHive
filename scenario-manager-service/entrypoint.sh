#!/bin/sh
set -e

HOST="${RABBITMQ_HOST:-rabbitmq}"
QUEUE="${PH_LOGS_QUEUE:-ph.logs.agg}"
USER="${RABBITMQ_USER:-guest}"
PASS="${RABBITMQ_PASS:-guest}"

log(){
  printf '%s %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$1"
}

log "waiting for log aggregator queue '$QUEUE' on '$HOST'"
while ! curl -fsS -u "$USER:$PASS" "http://$HOST:15672/api/queues/%2f/$QUEUE" >/dev/null; do
  log "queue not found, retrying in 5s"
  sleep 5
done
log "log aggregator ready, starting scenario-manager-service"

exec java -jar /app/app.jar
