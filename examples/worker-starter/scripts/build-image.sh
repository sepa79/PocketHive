#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"

print_help() {
  cat <<'HELP'
Usage: build-image.sh <generator-image> <processor-image> [--skip-tests]

Builds the sample generator and processor worker container images.

Arguments:
  <generator-image>  Required. Repository/tag for the generator worker image.
  <processor-image>  Required. Repository/tag for the processor worker image.
  --skip-tests       Optional. Skip Maven tests (can also set SKIP_TESTS=true).

Environment variables:
  SKIP_TESTS         When set to "true", skips test execution during the Maven build.

Examples:
  ./scripts/build-image.sh my-org/gen:local my-org/proc:local
  ./scripts/build-image.sh my-org/gen:local my-org/proc:local --skip-tests
HELP
}

if [[ $# -eq 0 ]]; then
  print_help >&2
  exit 1
fi

GEN_IMAGE=""
PROC_IMAGE=""
SKIP_TESTS="${SKIP_TESTS:-false}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      print_help
      exit 0
      ;;
    --skip-tests)
      SKIP_TESTS="true"
      ;;
    *)
      if [[ -z "$GEN_IMAGE" ]]; then
        GEN_IMAGE="$1"
      elif [[ -z "$PROC_IMAGE" ]]; then
        PROC_IMAGE="$1"
      else
        echo "Unknown argument: $1" >&2
        print_help >&2
        exit 1
      fi
      ;;
  esac
  shift
done

if [[ -z "$GEN_IMAGE" || -z "$PROC_IMAGE" ]]; then
  echo "Both generator and processor image names are required." >&2
  print_help >&2
  exit 1
fi

MVN_CMD=""
if [[ -x "${REPO_ROOT}/mvnw" ]]; then
  MVN_CMD="${REPO_ROOT}/mvnw"
elif command -v mvn >/dev/null 2>&1; then
  MVN_CMD="mvn"
fi

INSTALL_ARGS=(-B -pl common/worker-sdk -am install)
MVN_ARGS=(-B -pl generator-worker,processor-worker -am package)
DOCKER_MAVEN_ARGS=""
if [[ "${SKIP_TESTS}" == "true" ]]; then
  INSTALL_ARGS+=("-DskipTests")
  MVN_ARGS+=("-DskipTests")
  DOCKER_MAVEN_ARGS="-DskipTests"
fi

if [[ -n "${MVN_CMD}" ]]; then
  echo "Installing parent and shared artifacts with ${MVN_CMD} ${INSTALL_ARGS[*]}"
  ( cd "${REPO_ROOT}" && "${MVN_CMD}" "${INSTALL_ARGS[@]}" )
  echo "Running Maven build with ${MVN_CMD} ${MVN_ARGS[*]}"
  ( cd "${PROJECT_ROOT}" && "${MVN_CMD}" "${MVN_ARGS[@]}" )
else
  echo "Maven executable not found. Skipping host build and relying on the Docker multi-stage build." >&2
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required to build the container images." >&2
  exit 1
fi

build_image() {
  local module_dir="$1"
  local image_name="$2"
  docker build \
    --build-arg "MAVEN_ARGS=${DOCKER_MAVEN_ARGS}" \
    -t "${image_name}" \
    -f "${PROJECT_ROOT}/${module_dir}/docker/Dockerfile" \
    "${PROJECT_ROOT}"
}

build_image "generator-worker" "${GEN_IMAGE}"
build_image "processor-worker" "${PROC_IMAGE}"

echo "\nBuilt images:\n  Generator -> ${GEN_IMAGE}\n  Processor -> ${PROC_IMAGE}"
