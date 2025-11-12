#!/usr/bin/env bash
set -euo pipefail

MODULE=""
OUTPUT_DIR="dist/plugins"
VERSION="${POCKETHIVE_VERSION:-}"
SKIP_TESTS="-DskipTests"

usage() {
  cat <<USAGE
Usage: scripts/package-plugin.sh --module <module> [--output <dir>] [--version <tag>] [--run-tests]

Examples:
  scripts/package-plugin.sh --module generator-service
  scripts/package-plugin.sh --module generator-service --version 0.13.7 --output ./dist/plugins
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --module)
      MODULE="$2"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --version)
      VERSION="$2"
      shift 2
      ;;
    --run-tests)
      SKIP_TESTS=""
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$MODULE" ]]; then
  echo "--module is required" >&2
  exit 1
fi

resolve_version() {
  if [[ -n "${VERSION}" ]]; then
    echo "${VERSION}"
    return
  fi
  if [[ -n "${POCKETHIVE_VERSION:-}" ]]; then
    echo "${POCKETHIVE_VERSION}"
    return
  fi
  mvn -q -pl "$MODULE" help:evaluate -Dexpression=project.version -DforceStdout
}

VERSION="$(resolve_version)"

echo "Building $MODULE (version $VERSION)..."
mvn -q -pl "$MODULE" -am $SKIP_TESTS package

PLAIN_JAR=$(find "$MODULE/target" -maxdepth 1 -type f -name '*.jar.original' | sort | tail -n1)
if [[ -n "$PLAIN_JAR" ]]; then
  SOURCE_JAR="$PLAIN_JAR"
else
  SOURCE_JAR=$(find "$MODULE/target" -maxdepth 1 -type f -name '*.jar' ! -name '*sources.jar' | sort | tail -n1)
fi

if [[ -z "${SOURCE_JAR:-}" ]]; then
  echo "Unable to locate jar for $MODULE" >&2
  exit 1
fi

DEST_NAME="$(basename "$MODULE")-$VERSION.jar"
DEST_PATH="$OUTPUT_DIR/$DEST_NAME"

mkdir -p "$OUTPUT_DIR"
cp "$SOURCE_JAR" "$DEST_PATH"

validate_entry() {
  local entry="$1"
  if ! jar tf "$DEST_PATH" | grep -q "^$entry"; then
    echo "Error: $DEST_NAME is missing $entry" >&2
    exit 1
  fi
}

validate_entry "plugin.properties"
validate_entry "META-INF/pockethive-plugin.yml"

echo "Packaged plugin copied to $DEST_PATH"
