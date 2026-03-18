#!/bin/sh
set -eu

cp /etc/influxdb3/admin-token.json /tmp/admin-token.json
chmod 600 /tmp/admin-token.json

set -- \
  --node-id pockethive-local \
  --object-store file \
  --data-dir /var/lib/influxdb3 \
  --admin-token-file /tmp/admin-token.json

if [ -n "${POCKETHIVE_INFLUXDB3_QUERY_FILE_LIMIT:-}" ]; then
  set -- "$@" --query-file-limit "${POCKETHIVE_INFLUXDB3_QUERY_FILE_LIMIT}"
fi

exec influxdb3 serve "$@"
