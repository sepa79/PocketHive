#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

resolve_repo_root() {
  if command -v git >/dev/null 2>&1; then
    if REPO_ROOT=$(git -C "${PROJECT_ROOT}" rev-parse --show-toplevel 2>/dev/null); then
      printf '%s\n' "${REPO_ROOT}"
      return 0
    fi
  fi

  local dir="${PROJECT_ROOT}"
  while [[ "${dir}" != "/" ]]; do
    if [[ -f "${dir}/pom.xml" ]] && grep -q '<artifactId>pockethive-mvp</artifactId>' "${dir}/pom.xml" 2>/dev/null; then
      printf '%s\n' "${dir}"
      return 0
    fi
    dir="$(dirname "${dir}")"
  done
  return 1
}

REPO_ROOT="$(resolve_repo_root || true)"
ROOT_POM=""
if [[ -n "${REPO_ROOT}" ]]; then
  ROOT_POM="${REPO_ROOT}/pom.xml"
fi

POCKETHIVE_VERSION="$(sed -n 's:.*<pockethive.version>\(.*\)</pockethive.version>.*:\1:p' "${PROJECT_ROOT}/pom.xml" | head -n 1)"
if [[ -z "${POCKETHIVE_VERSION}" ]]; then
  echo "Warning: Unable to determine PocketHive version from ${PROJECT_ROOT}/pom.xml." >&2
fi

LOCAL_REPO="${MAVEN_REPO_LOCAL:-}"
if [[ -z "${LOCAL_REPO}" ]]; then
  if [[ -n "${MAVEN_USER_HOME:-}" ]]; then
    LOCAL_REPO="${MAVEN_USER_HOME}/repository"
  else
    LOCAL_REPO="${HOME}/.m2/repository"
  fi
fi
STALE_PARENT_DIR="${LOCAL_REPO}/io/pockethive/pockethive-mvp/\${revision}"
if [[ -d "${STALE_PARENT_DIR}" ]]; then
  echo "Removing stale cached Maven metadata at ${STALE_PARENT_DIR}"
  rm -rf "${STALE_PARENT_DIR}"
fi

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
if [[ -x "${PROJECT_ROOT}/mvnw" ]]; then
  MVN_CMD="${PROJECT_ROOT}/mvnw"
elif command -v mvn >/dev/null 2>&1; then
  MVN_CMD="mvn"
fi

PARENT_INSTALL_ARGS=()
INSTALL_ARGS=()
if [[ -n "${ROOT_POM}" ]]; then
  PARENT_INSTALL_ARGS+=(-f "${ROOT_POM}")
  INSTALL_ARGS+=(-f "${ROOT_POM}")
fi
PARENT_INSTALL_ARGS+=(-B -N install)
INSTALL_ARGS+=(-B -pl common/worker-sdk -am install)
MVN_ARGS=(-B -pl generator-worker,processor-worker -am package)
DOCKER_MAVEN_ARGS=()
if [[ -n "${POCKETHIVE_VERSION}" ]]; then
  PARENT_INSTALL_ARGS+=("-Drevision=${POCKETHIVE_VERSION}")
  INSTALL_ARGS+=("-Drevision=${POCKETHIVE_VERSION}")
  MVN_ARGS+=("-Drevision=${POCKETHIVE_VERSION}")
  DOCKER_MAVEN_ARGS+=("-Drevision=${POCKETHIVE_VERSION}")
fi
if [[ "${SKIP_TESTS}" == "true" ]]; then
  PARENT_INSTALL_ARGS+=("-DskipTests")
  INSTALL_ARGS+=("-DskipTests")
  MVN_ARGS+=("-DskipTests")
  DOCKER_MAVEN_ARGS+=("-DskipTests")
fi

if [[ -n "${MVN_CMD}" ]]; then
  if [[ -n "${ROOT_POM}" && -f "${ROOT_POM}" ]]; then
    echo "Installing PocketHive parent POM with ${MVN_CMD} ${PARENT_INSTALL_ARGS[*]}"
    ( cd "${REPO_ROOT}" && "${MVN_CMD}" "${PARENT_INSTALL_ARGS[@]}" )
    if [[ -n "${POCKETHIVE_VERSION}" ]]; then
      LOCAL_PARENT_DIR="${LOCAL_REPO}/io/pockethive/pockethive-mvp/${POCKETHIVE_VERSION}"
      if [[ -d "${LOCAL_PARENT_DIR}" ]]; then
        mkdir -p "${STALE_PARENT_DIR}"
        cp "${LOCAL_PARENT_DIR}/pockethive-mvp-${POCKETHIVE_VERSION}.pom" "${STALE_PARENT_DIR}/pockethive-mvp-\${revision}.pom"
      fi
    fi
    echo "Installing parent and shared artifacts with ${MVN_CMD} ${INSTALL_ARGS[*]}"
    ( cd "${REPO_ROOT}" && "${MVN_CMD}" "${INSTALL_ARGS[@]}" )
  else
    echo "Unable to locate PocketHive repository root. Skipping parent install. Please install io.pockethive:pockethive-mvp manually." >&2
  fi
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
  local maven_args="${DOCKER_MAVEN_ARGS[*]}"
  docker build \
    --build-arg "MAVEN_ARGS=${maven_args}" \
    -t "${image_name}" \
    -f "${PROJECT_ROOT}/${module_dir}/docker/Dockerfile" \
    "${PROJECT_ROOT}"
}

build_image "generator-worker" "${GEN_IMAGE}"
build_image "processor-worker" "${PROC_IMAGE}"

echo "\nBuilt images:\n  Generator -> ${GEN_IMAGE}\n  Processor -> ${PROC_IMAGE}"
