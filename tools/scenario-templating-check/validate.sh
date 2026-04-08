#!/usr/bin/env bash
# Thin wrapper for bundle.validate in the MCP server.
# Usage: bash validate.sh <pockethive-root> <scenario-path> [extra args...]
set -euo pipefail

POCKETHIVE_ROOT="${1:?POCKETHIVE_ROOT required}"
SCENARIO_PATH="${2:?SCENARIO_PATH required}"
shift 2

MODULE_DIR="${POCKETHIVE_ROOT}/tools/scenario-templating-check"
CLASSES_DIR="${MODULE_DIR}/target/classes"
CP_CACHE="${MODULE_DIR}/target/mcp-classpath.txt"

if [[ ! -f "${CP_CACHE}" ]] || [[ "${MODULE_DIR}/pom.xml" -nt "${CP_CACHE}" ]]; then
  mvn -q -f "${MODULE_DIR}/pom.xml" dependency:build-classpath \
    -Dmdep.outputFile="${CP_CACHE}" 2>/dev/null
fi

exec java -cp "${CLASSES_DIR}:$(cat "${CP_CACHE}")" \
  io.pockethive.tools.ScenarioTemplateValidator \
  --scenario "${SCENARIO_PATH}" "$@"
