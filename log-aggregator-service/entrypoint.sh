#!/bin/sh
set -e

HOST="${RABBITMQ_HOST:-rabbitmq}"
QUEUE="${PH_LOGS_QUEUE:-ph.logs.agg}"
USER="${RABBITMQ_USER:-guest}"
PASS="${RABBITMQ_PASS:-guest}"

printf 'Waiting for RabbitMQ queue %s on %s...' "$QUEUE" "$HOST"
while ! wget -qO- --user="$USER" --password="$PASS" "http://$HOST:15672/api/queues/%2f/$QUEUE" >/dev/null 2>&1; do
  printf '.'
  sleep 5
done
printf 'done\n'

exec java -jar /app/app.jar
