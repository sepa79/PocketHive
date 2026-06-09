#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
docker compose -f "${SCRIPT_DIR}/compose.yml" up -d --wait
"${SCRIPT_DIR}/reset.sh"
