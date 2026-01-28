#!/usr/bin/env bash
set -euo pipefail

PORT="${PORT:-8000}"

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
VIEW_DIR="${ROOT}/tools/control-traffic-viewer"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found. Install Python 3 or run any static HTTP server from: $ROOT" >&2
  exit 1
fi

echo "Serving: ${VIEW_DIR}"
echo "Viewer:  http://localhost:${PORT}/"
echo "Stop:    Ctrl+C"
echo

python3 -m http.server "${PORT}" --directory "${VIEW_DIR}"
