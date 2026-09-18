#!/usr/bin/env bash
set -euo pipefail

# Simple wrapper to render generator templates from a scenario file without starting a swarm.
# Usage:
#   tools/scenario-templating-check/run.sh --scenario path/to/scenario.yaml [--context path/to/context.json]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODULE="tools/scenario-templating-check"
TMP_CP_RELATIVE="target/.scenario-templating-classpath.$$"
TMP_CP="${SCRIPT_DIR}/${TMP_CP_RELATIVE}"

cleanup() {
  rm -f "${TMP_CP}"
}
trap cleanup EXIT

cd "${PROJECT_ROOT}"

# Build and install the module (and its dependencies) quietly, skipping tests.
mvn -q -pl "${MODULE}" -am -DskipTests install

# Capture the runtime classpath for the module only.
mvn -q -f "${MODULE}/pom.xml" dependency:build-classpath \
  "-Dmdep.outputFile=${TMP_CP_RELATIVE}"

if [[ ! -s "${TMP_CP}" ]]; then
  echo "Maven did not produce the runtime classpath file: ${TMP_CP}" >&2
  exit 1
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    CLASSPATH_SEPARATOR=";"
    MODULE_CLASSES="$(cygpath -w "${PROJECT_ROOT}/${MODULE}/target/classes")"
    ;;
  Linux*|Darwin*)
    CLASSPATH_SEPARATOR=":"
    MODULE_CLASSES="${PROJECT_ROOT}/${MODULE}/target/classes"
    ;;
  *)
    echo "Unsupported shell platform: $(uname -s)" >&2
    exit 2
    ;;
esac

CLASSPATH="${MODULE_CLASSES}${CLASSPATH_SEPARATOR}$(cat "${TMP_CP}")"

java -cp "${CLASSPATH}" io.pockethive.tools.ScenarioTemplateValidator "$@"
