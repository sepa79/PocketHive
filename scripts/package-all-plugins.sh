#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
POCKETHIVE_VERSION="${POCKETHIVE_VERSION:-latest}"
VERSION="$POCKETHIVE_VERSION"

declare -a PLUGINS=(
  "generator-service io.pockethive.generator.Application generator generator-service/config/defaults.yaml"
  "moderator-service io.pockethive.moderator.Application moderator moderator-service/config/defaults.yaml"
  "processor-service io.pockethive.processor.Application processor processor-service/config/defaults.yaml"
  "postprocessor-service io.pockethive.postprocessor.Application postprocessor postprocessor-service/config/defaults.yaml"
  "trigger-service io.pockethive.trigger.Application trigger trigger-service/config/defaults.yaml"
)

echo "Packaging worker plugins (version $VERSION)..."
for entry in "${PLUGINS[@]}"; do
  read -r module plugin_class role defaults <<<"$entry"
  if [[ ! -f "$REPO_ROOT/$defaults" ]]; then
    echo "Defaults file missing for $role: $defaults" >&2
    exit 1
  fi
  "$SCRIPT_DIR/package-plugin.sh" \
    --module "$module" \
    --plugin-class "$plugin_class" \
    --role "$role" \
    --version "$VERSION" \
    --config-prefix "pockethive.workers.$role" \
    --defaults "$defaults"
done

echo "Building worker-plugin-host image..."
IMAGE_TAG="${DOCKER_REGISTRY:-}worker-plugin-host:$VERSION"
docker build -f "$REPO_ROOT/worker-plugin-host/Dockerfile" \
  -t "$IMAGE_TAG" \
  "$REPO_ROOT"

echo "All plugins packaged and host image built."
