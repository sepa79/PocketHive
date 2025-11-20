#!/usr/bin/env bash
set -euo pipefail

# Simple wrapper to render generator templates from a scenario file without starting a swarm.
# Usage:
#   tools/scenario-templating-check/run.sh --scenario path/to/scenario.yaml [--context path/to/context.json]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODULE="tools/scenario-templating-check"
TMP_CP="$(mktemp)"

# Build and install the module (and its dependencies) quietly, skipping tests.
mvn -q -f "${PROJECT_ROOT}/pom.xml" -pl "${MODULE}" -am -DskipTests install >/dev/null

# Capture the runtime classpath for the module only.
mvn -q -f "${SCRIPT_DIR}/pom.xml" dependency:build-classpath \
  -Dmdep.outputFile="${TMP_CP}" >/dev/null

CLASSPATH="${PROJECT_ROOT}/${MODULE}/target/classes:$(cat "${TMP_CP}")"
rm -f "${TMP_CP}"

java -cp "${CLASSPATH}" io.pockethive.tools.ScenarioTemplateValidator "$@"
