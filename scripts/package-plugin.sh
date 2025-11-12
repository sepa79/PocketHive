#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/package-plugin.sh [options]

Required options:
  --plugin-class <fqcn>    Fully-qualified @Configuration class exposed by the plugin.
  --role <name>            Worker role (e.g., generator, moderator).
  --version <version>      Plugin semantic version.
  --config-prefix <name>   Configuration prefix (e.g., pockethive.workers.generator).
  --defaults <file>        Path to the default config YAML to embed inside the plugin jar.

Build options:
  --module <path>          Maven module that produces the plugin jar (relative to repo root).
  --jar <path>             Existing jar to update. Skip Maven build when provided.
  --output <dir>           Directory to copy the packaged jar into (default: dist/plugins).
  --capability <value>     Repeat to declare WorkerCapability entries in the manifest.
  --run-tests              Run Maven tests instead of skipping them.

One of --module or --jar must be supplied.
EOF
}

MODULE=""
JAR_PATH=""
OUTPUT_DIR="$REPO_ROOT/dist/plugins"
PLUGIN_CLASS=""
ROLE=""
VERSION=""
CONFIG_PREFIX=""
DEFAULTS_FILE=""
CAPABILITIES=()
SKIP_TESTS="-DskipTests"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --module)
      MODULE="$2"
      shift 2
      ;;
    --jar)
      JAR_PATH="$2"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --plugin-class)
      PLUGIN_CLASS="$2"
      shift 2
      ;;
    --role)
      ROLE="$2"
      shift 2
      ;;
    --version)
      VERSION="$2"
      shift 2
      ;;
    --config-prefix)
      CONFIG_PREFIX="$2"
      shift 2
      ;;
    --defaults)
      DEFAULTS_FILE="$2"
      shift 2
      ;;
    --capability)
      CAPABILITIES+=("$2")
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
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$PLUGIN_CLASS" || -z "$ROLE" || -z "$VERSION" || -z "$CONFIG_PREFIX" || -z "$DEFAULTS_FILE" ]]; then
  echo "Missing required option." >&2
  usage
  exit 1
fi

if [[ -z "$MODULE" && -z "$JAR_PATH" ]]; then
  echo "Either --module or --jar must be provided." >&2
  usage
  exit 1
fi

if [[ -n "$MODULE" && -z "$JAR_PATH" ]]; then
  echo "Building module '$MODULE'..."
  mvn -q -f "$REPO_ROOT/pom.xml" -pl "$MODULE" -am $SKIP_TESTS package
  mapfile -t CANDIDATES < <(find "$REPO_ROOT/$MODULE/target" -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort)
  if [[ ${#CANDIDATES[@]} -eq 0 ]]; then
    echo "Unable to locate jar under $MODULE/target." >&2
    exit 1
  fi
  JAR_PATH="${CANDIDATES[-1]}"
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

if [[ ! -f "$DEFAULTS_FILE" ]]; then
  echo "Defaults file not found: $DEFAULTS_FILE" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
mkdir -p "$TMP_DIR/META-INF" "$TMP_DIR/config"

MANIFEST="$TMP_DIR/META-INF/pockethive-plugin.yml"
{
  echo "pluginClass: $PLUGIN_CLASS"
  echo "role: $ROLE"
  echo "version: $VERSION"
  echo "configPrefix: $CONFIG_PREFIX"
  echo "defaultConfig: config/defaults.yaml"
  if [[ ${#CAPABILITIES[@]} -eq 0 ]]; then
    echo "capabilities: []"
  else
    echo "capabilities:"
    for cap in "${CAPABILITIES[@]}"; do
      echo "  - $cap"
    done
  fi
} > "$MANIFEST"

cp "$DEFAULTS_FILE" "$TMP_DIR/config/defaults.yaml"

jar uf "$JAR_PATH" -C "$TMP_DIR" META-INF/pockethive-plugin.yml -C "$TMP_DIR" config/defaults.yaml

mkdir -p "$OUTPUT_DIR"
cp "$JAR_PATH" "$OUTPUT_DIR/"
echo "Packaged plugin jar copied to $OUTPUT_DIR/$(basename "$JAR_PATH")"
