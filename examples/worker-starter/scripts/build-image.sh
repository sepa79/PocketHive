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

REPO_ROOT="$(resolve_repo_root)" || REPO_ROOT=""

ROOT_POM=""
if [[ -n "${REPO_ROOT}" ]]; then
  ROOT_POM="${REPO_ROOT}/pom.xml"
fi

POCKETHIVE_VERSION="$(sed -n 's:.*<pockethive.version>\(.*\)</pockethive.version>.*:\1:p' "${PROJECT_ROOT}/pom.xml" | head -n 1)"
if [[ -z "${POCKETHIVE_VERSION}" ]]; then
  echo "Warning: Unable to determine PocketHive version from ${PROJECT_ROOT}/pom.xml." >&2
fi

install_parent_placeholder() {
  if [[ -z "${MVN_CMD}" || -z "${ROOT_POM}" ]]; then
    return 0
  fi

  if [[ ! -f "${ROOT_POM}" ]]; then
    return 0
  fi

  if [[ -z "${POCKETHIVE_VERSION}" ]]; then
    echo "Skipping parent placeholder install: PocketHive version is unknown." >&2
    return 0
  fi

  local install_args=(-B install:install-file "-Dfile=${ROOT_POM}" -DgroupId=io.pockethive -DartifactId=pockethive-mvp "-Dversion=${POCKETHIVE_VERSION}" -Dpackaging=pom)

  echo "Installing PocketHive parent placeholder with ${MVN_CMD} ${install_args[*]}"
  ( cd "${REPO_ROOT}" && "${MVN_CMD}" "${install_args[@]}" )
}

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
      if [[ -z "${GEN_IMAGE}" ]]; then
        GEN_IMAGE="$1"
      elif [[ -z "${PROC_IMAGE}" ]]; then
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

if [[ -z "${GEN_IMAGE}" || -z "${PROC_IMAGE}" ]]; then
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
SDK_INSTALL_ARGS=()
MODULE_BUILD_ARGS=(-B -pl generator-worker,processor-worker -am package)

if [[ -n "${ROOT_POM}" ]]; then
  PARENT_INSTALL_ARGS+=(-f "${ROOT_POM}")
  SDK_INSTALL_ARGS+=(-f "${ROOT_POM}")
fi

PARENT_INSTALL_ARGS+=(-B -N install)
SDK_INSTALL_ARGS+=(-B -pl common/worker-sdk -am install)

if [[ -n "${POCKETHIVE_VERSION}" ]]; then
  PARENT_INSTALL_ARGS+=(-Drevision=${POCKETHIVE_VERSION})
  SDK_INSTALL_ARGS+=(-Drevision=${POCKETHIVE_VERSION})
  MODULE_BUILD_ARGS+=(-Drevision=${POCKETHIVE_VERSION})
fi

if [[ "${SKIP_TESTS}" == "true" ]]; then
  PARENT_INSTALL_ARGS+=(-DskipTests)
  SDK_INSTALL_ARGS+=(-DskipTests)
  MODULE_BUILD_ARGS+=(-DskipTests)
fi

if [[ -n "${MVN_CMD}" ]]; then
  if [[ -n "${ROOT_POM}" && -f "${ROOT_POM}" ]]; then
    echo "Installing PocketHive parent POM with ${MVN_CMD} ${PARENT_INSTALL_ARGS[*]}"
    ( cd "${REPO_ROOT}" && "${MVN_CMD}" "${PARENT_INSTALL_ARGS[@]}" )
    install_parent_placeholder
    echo "Installing Worker SDK dependencies with ${MVN_CMD} ${SDK_INSTALL_ARGS[*]}"
    ( cd "${REPO_ROOT}" && "${MVN_CMD}" "${SDK_INSTALL_ARGS[@]}" )
  else
    echo "Unable to locate PocketHive repository root. Skipping parent install. Please install io.pockethive:pockethive-mvp manually." >&2
  fi
  echo "Running Maven build with ${MVN_CMD} ${MODULE_BUILD_ARGS[*]}"
  ( cd "${PROJECT_ROOT}" && "${MVN_CMD}" "${MODULE_BUILD_ARGS[@]}" )
else
  echo "Maven executable not found. Install Maven or use the bundled wrapper before building images." >&2
  exit 1
fi

find_built_jar() {
  local module_dir="$1"
  local target_dir="${PROJECT_ROOT}/${module_dir}/target"

  if [[ ! -d "${target_dir}" ]]; then
    echo "Expected build output not found: ${target_dir}" >&2
    echo "Run the script without --skip-tests (or execute Maven manually) to compile the module before building the image." >&2
    return 1
  fi

  local jar
  jar="$(find "${target_dir}" -maxdepth 1 -type f -name '*.jar' ! -name '*-original.jar' ! -name '*-plain.jar' | head -n 1)"

  if [[ -z "${jar}" ]]; then
    jar="$(find "${target_dir}" -maxdepth 1 -type f -name '*.jar' | head -n 1)"
  fi

  if [[ -z "${jar}" ]]; then
    echo "No JAR artifacts found in ${target_dir}. Ensure the Maven build completed successfully." >&2
    return 1
  fi

  printf '%s\n' "${jar}"
}

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required to build the container images." >&2
  exit 1
fi

build_image() {
  local module_dir="$1"
  local image_name="$2"
  local jar_path
  jar_path="$(find_built_jar "${module_dir}")" || return 1

  local relative_jar="${jar_path#${PROJECT_ROOT}/${module_dir}/}"

  if [[ "${relative_jar}" == "${jar_path}" ]]; then
    echo "Unable to derive relative path for ${jar_path}." >&2
    return 1
  fi

  docker build \
    --build-arg "APP_JAR=${relative_jar}" \
    -t "${image_name}" \
    -f "${PROJECT_ROOT}/${module_dir}/docker/Dockerfile" \
    "${PROJECT_ROOT}/${module_dir}"
}

build_image "generator-worker" "${GEN_IMAGE}"
build_image "processor-worker" "${PROC_IMAGE}"

printf '\nBuilt images:\n  Generator -> %s\n  Processor -> %s\n' "${GEN_IMAGE}" "${PROC_IMAGE}"
