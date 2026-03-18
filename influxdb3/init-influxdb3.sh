#!/bin/sh
set -eu

host="${INFLUXDB3_HOST_URL:?INFLUXDB3_HOST_URL is required}"
token="${INFLUXDB3_AUTH_TOKEN:?INFLUXDB3_AUTH_TOKEN is required}"
database="${INFLUXDB3_DATABASE_NAME:?INFLUXDB3_DATABASE_NAME is required}"

echo "influxdb3-init: waiting for ${host}"
attempt=0
until influxdb3 show databases --host "${host}" --token "${token}" --format csv >/tmp/influxdb3-databases.csv 2>/tmp/influxdb3-init.err
do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 60 ]; then
    echo "influxdb3-init: database list failed after ${attempt} attempts" >&2
    cat /tmp/influxdb3-init.err >&2 || true
    exit 1
  fi
  sleep 2
done

if grep -Fqx "${database}" /tmp/influxdb3-databases.csv; then
  echo "influxdb3-init: database ${database} already exists"
  exit 0
fi

echo "influxdb3-init: creating database ${database}"
influxdb3 create database --host "${host}" --token "${token}" "${database}"
