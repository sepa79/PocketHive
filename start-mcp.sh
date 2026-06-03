#!/usr/bin/env bash
# start-mcp.sh — Start the PocketHive MCP server.
#
# Modes:
#   ./start-mcp.sh              # foreground via node (WSL)
#   ./start-mcp.sh --bg         # background via node (WSL), PID in .mcp-server.pid
#   ./start-mcp.sh --docker     # via docker compose (recommended — no Node version issues)
#
# BUNDLES_ROOT defaults to scenarios/bundles inside this repo.
# For real scenario authoring, point it at a separate bundles repo checkout.
#
# Amazon Q connects via .amazonq/mcp.json at http://localhost:3100/mcp

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MCP_DIR="$SCRIPT_DIR/tools/pockethive-mcp"
REPO_DEFAULT_BUNDLES="$SCRIPT_DIR/scenarios/bundles"

# ── Docker mode ───────────────────────────────────────────────────────────────
if [ "${1:-}" = "--docker" ]; then
  echo "Starting PocketHive MCP server via Docker..."
  echo ""
  echo "BUNDLES_ROOT options:"
  echo "  1) $REPO_DEFAULT_BUNDLES (repo examples — default)"
  echo "  2) A separate bundles repo (recommended for real scenario authoring)"
  echo ""
  read -r -p "Bundles directory [${REPO_DEFAULT_BUNDLES}]: " INPUT
  HOST_BUNDLES="${INPUT:-$REPO_DEFAULT_BUNDLES}"

  if [ ! -d "$HOST_BUNDLES" ]; then
    echo "WARNING: directory does not exist: $HOST_BUNDLES" >&2
  fi

  echo ""
  echo "  BUNDLES_ROOT: $HOST_BUNDLES"
  echo "  Port:         3100"
  echo ""

  BUNDLES_ROOT="$HOST_BUNDLES" docker compose -f "$MCP_DIR/docker-compose.yml" up --build
  exit 0
fi

# ── WSL / native node mode ────────────────────────────────────────────────────
# Use explicit node path to avoid shell PATH cache picking up old version
NODE_BIN="${NODE_BIN:-/usr/local/bin/node}"
if ! "$NODE_BIN" --version >/dev/null 2>&1; then
  NODE_BIN="$(which node)"
fi

NODE_VER="$("$NODE_BIN" --version 2>/dev/null || echo unknown)"
NODE_MAJOR="${NODE_VER#v}"
NODE_MAJOR="${NODE_MAJOR%%.*}"
if [ "$NODE_MAJOR" != "unknown" ] && [ "$NODE_MAJOR" -lt 20 ] 2>/dev/null; then
  echo "ERROR: Node.js 20+ required. Found $NODE_VER at $NODE_BIN." >&2
  echo "Run ./start-mcp.sh --docker instead to avoid Node version issues." >&2
  exit 1
fi

echo ""
echo "BUNDLES_ROOT options:"
echo "  1) $REPO_DEFAULT_BUNDLES (repo examples — default)"
echo "  2) A separate bundles repo (recommended for real scenario authoring)"
echo ""
read -r -p "Bundles directory [${REPO_DEFAULT_BUNDLES}]: " INPUT_BUNDLES
BUNDLES_ROOT="${INPUT_BUNDLES:-$REPO_DEFAULT_BUNDLES}"
export BUNDLES_ROOT

if [ ! -d "$BUNDLES_ROOT" ]; then
  echo "WARNING: directory does not exist: $BUNDLES_ROOT" >&2
fi

export POCKETHIVE_ROOT="$SCRIPT_DIR"
export POCKETHIVE_BASE_URL="${POCKETHIVE_BASE_URL:-http://localhost:8088}"
export AUTH_SERVICE_BASE_URL="${AUTH_SERVICE_BASE_URL:-http://localhost:8088/auth-service}"
export POCKETHIVE_AUTH_USERNAME="${POCKETHIVE_AUTH_USERNAME:-local-admin}"
export PH_MCP_HTTP_PORT="${PH_MCP_HTTP_PORT:-3100}"
export RABBITMQ_DEFAULT_USER="${RABBITMQ_DEFAULT_USER:-guest}"
export RABBITMQ_DEFAULT_PASS="${RABBITMQ_DEFAULT_PASS:-guest}"
export PH_BUNDLES_ROOTS="[\"$BUNDLES_ROOT\"]"

echo ""
echo "Starting PocketHive MCP server on http://localhost:${PH_MCP_HTTP_PORT}/mcp"
echo "  BUNDLES_ROOT:        $BUNDLES_ROOT"
echo "  POCKETHIVE_ROOT:     $POCKETHIVE_ROOT"
echo "  POCKETHIVE_BASE_URL: $POCKETHIVE_BASE_URL"
echo "  Node.js:             $NODE_VER"
echo ""

if [ "${1:-}" = "--bg" ]; then
  PID_FILE="$SCRIPT_DIR/.mcp-server.pid"
  "$NODE_BIN" "$MCP_DIR/server.mjs" &
  echo $! > "$PID_FILE"
  echo "MCP server started in background (PID $(cat "$PID_FILE"))"
  echo "Stop with: kill \$(cat .mcp-server.pid)"
else
  exec "$NODE_BIN" "$MCP_DIR/server.mjs"
fi
